# Architecture

## Module Graph

```
┌───────────────┐
│     :app      │  ← Application + MainActivity + Compose Nav + Hilt
└───┬───────┬───┘
    │       │
┌───┴───────┴──┐    │
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
       \               │               /
        \              ▼              /
         (sshj + BouncyCastle + Termux)
```

### Module Rules & Layer Boundaries

- **Features do not depend on each other**, except for `:feature-tunnels` depending on `:feature-session` for `BackgroundActivityController` reference bumps during active port forwards.
- **`:design-system` provides core UI theme & components.** All feature modules consume design system tokens (Cyber-Dark color schemes, glassmorphism containers, status pills, dialogs).
- **Cores never depend on features.** `:core-data` exposes storage-neutral repository contracts (`KnownHostStore`, `ConnectionRepository`) implemented via Room DAOs so `:core-ssh` remains free of Android Room dependencies.
- **`:app` is the composition root.** It wires Hilt dependency injection, single-activity entry, Compose navigation graph (`XSshNavHost`), system bar insets, and background tunnel recovery on process launch.

---

## Design System Architecture (`:design-system`)

The application adopts a **Cyber-Dark & Material 3** visual language:

- **Theme Engine (`XSshTheme`)**: Maps `DarkColorScheme` and `LightColorScheme` to Material 3 tokens. Supports Android 12+ dynamic Material You colors while retaining a dark-first fallback (`#070B10` background, `#0D131C` surface, `#38BDF8` primary cyan).
- **Component Palette**:
  - `GlassCard`: Card surface with subtle border stroke and elevated container fill.
  - `StatusPill`: Dot-indicated status badge with HSL alpha backgrounds for live connection states.
  - `SectionCard`: Grouped settings container with bold title and subtitle typography.
  - `EmptyState`: Hero illustrations with glowing icon badge and action button.
  - `StatSummaryCard`: Compact metrics card for tunnel and transfer statistics.

---

## Threading Model

- **UI Layer** — Compose recompositions run on `Dispatchers.Main.immediate`.
- **SSH Transport** — All blocking `sshj` network operations run on `Dispatchers.IO` (`SshSession.connect`, `openShell`, `openLocalForward`, `openRemoteForward`).
- **Terminal Input/Output** — A dedicated reader coroutine reads bytes from `Session.Shell.inputStream` and feeds them to the Termux emulator via `feedRemoteBytes(...)`.
- **Foreground Service Controller** — `BackgroundActivityController` uses lock-free `AtomicInteger` operations to increment active session and tunnel counters, delegating `ServiceLauncher` intents to the main thread handler.

---

## Data Flow — Terminal Keystroke Lifecycle

```
System IME / Soft Keyboard  ─▶  TerminalView (InputConnection)
│
▼
RemoteTerminalSession.write(bytes)
│
▼
ShellIo.onUserInput(bytes)
│  (Ctrl/Alt "arm-once" stateful modifier transforms applied here)
▼
Session.Shell.outputStream.write(bytes)     ← sshj transport on Dispatchers.IO
│
▼
Remote Server PTY
│
▼
Session.Shell.inputStream.read(...)         ← Reader coroutine
│
▼
feedRemoteBytes(termuxSession, bytes, n)
│
▼
Termux TerminalEmulator (append + invalidate)
│
▼
TerminalView Canvas Redraw
```

---

## Snippet Paste Flow

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
ShellIo.onUserInput(bytes)
```

---

## Host-Key TOFU State Machine

```
sshj transport thread            InteractiveHostKeyVerifier          UI (Jetpack Compose)
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
│           │  ◀── acceptPending()/rejectPending() ─── UI Dialog
│           │      OR withTimeoutOrNull expires
│           ▼
│      store.put(new) if accepted → return true
│      else                       → return false
│
│  (mismatch)  events = Changed(expected, actual) → return false
│  (no answer) events = TimedOut → return false
```

Decision timeout (90 s) prevents blocked transport threads if UI context changes during verification.

---

## Tunnel Lifecycle & Shared Sessions

`TunnelManager` reference-counts one `SshSession` per SSH connection id. Multiple tunnels targeting the same host share the underlying transport:

```
tunnelA ─┐
tunnelB ─┼─▶  Shared(session_c1, refs = {A, B, C})  ─▶  SSHClient (single connection)
tunnelC ─┘

stop(A) → refs = {B, C}    (session stays active)
stop(B) → refs = {C}       (session stays active)
stop(C) → refs = {}        (SSHClient closes cleanly)
```

---

## Foreground Service Architecture

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
▼
User sees ongoing notification: "N active sessions • M tunnels"
```

---

## Terminal Engine Integration

`:core-terminal` wraps Termux's `terminal-emulator` and `terminal-view` libraries (Apache 2.0). NDK native JNI bindings are compiled with NDK 28. `RemoteTerminalSession` extends Termux's `TerminalSession` to direct terminal output into `ShellIo` while keeping full terminal emulation capabilities.
