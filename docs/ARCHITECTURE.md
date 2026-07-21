# Architecture

## Module graph

```
┌───────────────┐
│     :app      │  ← Application + MainActivity + Hilt
└───┬───────┬───┘
│       │
┌───────────┴──┐    │
▼              ▼    ▼
:feature-session  :feature-tunnels
│              │
│ ┌────────────┘
▼ ▼
:feature-connections  :feature-sftp  :feature-snippets
│                 │              │
└───────┬─────────┴──────────────┘
▼
:design-system   :core-terminal   :core-ssh   :core-crypto   :core-data
\      │      /
\     ▼     /
(sshj + BouncyCastle)
```

Rules:

- **Feature → feature dependencies are the exception, not the rule.**
`:feature-tunnels` depends on `:feature-session` because `TunnelManager` needs
to bump `BackgroundActivityController` on start/stop; that is the only such
edge in the graph.
- **Cores never depend on features.** `:core-data` exposes a
storage-neutral `KnownHostStore` interface implemented by
`:core-data:RoomKnownHostStore` so `:core-ssh` stays Room-free.
- **`:app` is the composition root** — it wires Application, MainActivity,
Hilt entry points, navigation, and tunnel restoration on app-process launch.

## Threading model

- **UI** — Compose recompositions on `Dispatchers.Main.immediate` where safe.
- **SSH transport** — every sshj call bounces to `Dispatchers.IO` because
sshj is a blocking JVM library. `SshSession.connect`, `openShell`,
`openLocalForward`, and `openRemoteForward` all use `withContext(Dispatchers.IO)`.
- **Terminal reader** — a single per-session coroutine reads from the sshj
input stream and feeds bytes into the Termux emulator via
`feedRemoteBytes(...)`; the emulator itself handles thread-safety for
atomic appends.
- **Foreground-service counter** — `BackgroundActivityController` uses
`AtomicInteger.updateAndGet` for lock-free bumps; the `ServiceLauncher`
seam then dispatches start/stop-service intents onto the main-thread Handler
because several OEM Android builds refuse those calls from background
threads.

## Data flow — one keystroke, one byte back

```
System IME  ─▶  TerminalView (InputConnection)
│
▼
RemoteTerminalSession.write(bytes)
│
▼
ShellIo.onUserInput(bytes)
│  (Ctrl/Alt "arm-once" transforms happen here)
▼
Session.Shell.outputStream.write(bytes)     ← sshj, on Dispatchers.IO
│
▼
PTY on server
│
▼
Session.Shell.inputStream.read(...)         ← reader coroutine
│
▼
feedRemoteBytes(termuxSession, bytes, n)
│
▼
Termux TerminalEmulator (append + invalidate)
│
▼
TerminalView redraws (Canvas)
```

There is **no custom on-screen keyboard**. The optional `ModifierBar` supplies
only the keys that Android IMEs don't emit reliably (Esc, Tab, Ctrl, Alt,
arrows, common shell symbols, PgUp/PgDn, Home/End).

## Snippet paste flow

```
SnippetsScreen (Room CRUD)
│  save(SnippetEntity)
▼
SnippetDao.upsert  ─▶  Room  ─▶  SnippetDao.observeAll (Flow)
│
└─▶ SessionViewModel.snippets: StateFlow
│
▼
SessionScreen.SnippetPasteSheet
│  tap paste / paste-and-run
▼
SessionViewModel.pasteSnippet(body, appendNewline)
│
▼
(same writeAsync path used by real keystrokes)
```

`executeOnPaste = true` on the snippet, or the paste-and-run button on the
picker, both add a trailing `\n` so the command runs immediately.

## Host-key TOFU state machine

```
sshj transport thread            InteractiveHostKeyVerifier          UI
│
│  verify(host, port, key)
│──────────────────────────▶
│                             ▲
│                             │ store.get(hostPort)
│                             │
│           ┌────────────── existing == null ? ─────────┐
│           │                                            │
│           ▼ yes                                        ▼ no
│      pendingPrompt = ...                     matches?  → true
│      events = Unknown(...)                             │ or
│                                                        ▼
│           │  ◀── acceptPending()/rejectPending() ─── UI
│           │      OR withTimeoutOrNull expires
│           ▼
│      store.put(new) if accepted → return true
│      else                       → return false
│
│  (mismatch)  events = Changed(expected, actual) → return false
│  (no answer) events = TimedOut → return false
```

The decision timeout (default 90 s) guarantees the sshj transport thread
never blocks indefinitely, even if the UI is torn down mid-prompt.

## Tunnel lifecycle & session sharing

`TunnelManager` reference-counts one `SshSession` per SSH connection id.
Multiple tunnels that target the same server share the underlying TCP+SSH
transport:

```
tunnelA ─┐
tunnelB ─┼─▶  Shared(session_c1, refs = {A, B, C})  ─▶  SSHClient (one)
tunnelC ─┘

stop(A) → refs = {B, C}    (session stays)
stop(B) → refs = {C}       (session stays)
stop(C) → refs = {}        (SSHClient.close())
```

Direct-TCP channels for LOCAL forwards live on a dedicated daemon thread each,
because sshj's `LocalPortForwarder.listen()` blocks the caller until the bound
`ServerSocket` is closed. REMOTE forwards close via
`RemotePortForwarder.cancel(...)`. DYNAMIC forwards run a `Socks5Server` that
opens one `SSHClient.newDirectConnection(host, port)` per accepted TCP
connection.

## Foreground service

`SessionForegroundService` is promoted whenever `BackgroundActivityController`
sees a session or tunnel counter > 0. Every bump is a single-intent round
trip:

```
SessionViewModel.start()            TunnelManager.doStart()
│                                      │
│ bumpSessions(+1)                     │ bumpTunnels(+1)
▼                                      ▼
BackgroundActivityController.refresh()
│
│  s + t > 0  →  ServiceLauncher.promote(s, t)  →  startForegroundService
│  s + t = 0  →  ServiceLauncher.demote()       →  stopService
▼
SessionForegroundService.onStartCommand
│
│  startForeground(id, notification, SPECIAL_USE [34+] / DATA_SYNC [31–33])
│
▼
User sees ongoing notification: "N active sessions • M tunnels"
```

`ServiceLauncher` is an interface with two implementations:
`AndroidServiceLauncher` (production, main-thread Handler + real Context) and a
per-test `RecordingLauncher` used in
`BackgroundActivityControllerTest`. That seam is why the controller's counter
logic is JVM-unit-testable without Robolectric.

## Terminal engine choice

We use Termux's `terminal-emulator` / `terminal-view` pair (Apache 2.0) in
`:core-terminal`. The attributed JNI bridge is rebuilt locally with NDK r28
instead of packaging Termux's older binary. `RemoteTerminalSession` subclasses `TerminalSession` and
overrides `write(...)` so user keystrokes flow into `ShellIo.onUserInput`
instead of a local subprocess.
