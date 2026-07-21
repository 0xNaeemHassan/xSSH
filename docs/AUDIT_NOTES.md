# Audit notes

Public record of the audit-driven changes applied between checkpoints 10
(the CK10 gamma snapshot) and 21 (full security/lifecycle/UI/release audit).
Kept as a doc so a future reviewer can quickly see what was
already looked at.

## Findings resolved

### Build blockers

| # | Finding | Resolution | Checkpoint |
|---|---|---|---|
| 1 | Manifest references `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round` but the adaptive resources were absent — build would fail at resource merge. | Added adaptive-icon suite in `mipmap-anydpi/`, foreground/background resources, and a monochrome themed variant. | 12 |
| 2 | Base `values/themes.xml` set `android:windowLightStatusBar="true"` while the window background is black — first frame flashed light-on-dark status-bar icons. No `values-night` override. | Set `windowLightStatusBar="false"` in base and added `values-night/themes.xml`. | 12 |

### Runtime correctness

| # | Finding | Resolution | Checkpoint |
|---|---|---|---|
| 3 | `Socks5Server` opened SSH channels via `client.connection.openChannel("direct-tcpip", ...)` — not sshj's public API; signature drifts between minor versions and would fail at runtime. | Replaced with `SSHClient.newDirectConnection(host, port)`. Also added RFC-1928-conforming "no acceptable methods" (0xFF) reply when the SOCKS5 client doesn't offer 0x00, and named the pump threads. | 13 |
| 4 | `InteractiveHostKeyVerifier.decisionTimeoutSeconds` declared but never used. The verifier would park the sshj transport thread indefinitely if the UI died before the user answered the prompt. | Bounded the wait with `withTimeoutOrNull(decisionTimeoutSeconds * 1000L)`. Added `VerificationEvent.TimedOut` so the UI can distinguish timeout from an explicit reject; `SessionScreen` now shows a distinct dialog for the timeout case. | 13 |
| 5 | `SftpTransfer.upload` installed a `TransferListener { object : TransferListener { ... } }` — a lambda-of-object that never actually installed. Progress callbacks were silently dropped. | Replaced with a proper `TransferListener` object writing `moved` bytes into the progress callback, plus a full `LocalSourceFile` (permissions, isFile/isDirectory, atime/mtime opt-out). RemoteFile + caller stream now closed in a finally block on both download and upload. | 13 |
| 6 | `SessionForegroundService` was never started by any code path; `EXTRA_SESSIONS` / `EXTRA_TUNNELS` were declared but unused. | Introduced `BackgroundActivityController` (@Singleton, atomic counters, `ServiceLauncher` seam). Wired into `SessionViewModel` (bump on connect/disconnect) and `TunnelManager` (bump on start/stop/stopAll). | 14 |
| 7 | `SessionForegroundService` did not pass a foreground-service type on `startForeground`. | Added typed promotion and zero-count teardown; the current implementation uses `SPECIAL_USE` on API 34+ and `DATA_SYNC` on API 31–33. | 14/21 |
| 8 | No runtime `POST_NOTIFICATIONS` request. On Android 13+ the foreground-service ongoing notification is silently suppressed if the permission was never granted. | `MainActivity.onCreate` now calls the runtime request through `ActivityResultContracts.RequestPermission` on TIRAMISU+. Declining does not disable the service; only its notification. | 14 |
| 9 | `CryptoBootstrap.install()` — the critical BouncyCastle provider swap for X25519 / Ed25519 — was defined but never called. First sshj handshake using `curve25519-sha256` would throw `no such algorithm`. | Now the first statement of `XSshApplication.onCreate`, before SSH or tunnel startup. Idempotent and cheap. | 14 |
| 10 | `SessionScreen` had a hand-rolled `private fun Int.dp() = Dp(this.toFloat())` shim used as `.padding(16.dp())` — the file was missing the standard `.dp` import. | Imported `androidx.compose.ui.unit.dp` properly; deleted the shim. | 14 |
| 15 | `ConnectionEditScreen` inferred auth mode from whether the password field was blank. Editing an existing password-backed profile and leaving the field empty silently flipped it to public-key auth while stale ciphertext lingered in Room. | Reworked the screen into an explicit auth-method editor; added password/public-key/interactive selection, private-key import/generation, and repository-side clearing of irrelevant encrypted secrets when auth mode changes. | 19 |
| 16 | `SessionViewModel` only set `connected = false` when the remote shell ended; it did not consistently release `shell`, `out`, verifier state, or the foreground-session count on EOF. | Centralized teardown in `cleanupSessionState(...)` and reuse it for explicit disconnects and reader termination. | 19 |
| 17 | The SFTP queue existed in the ViewModel but mostly disappeared from the UI when nothing was actively transferring, making failures/completions hard to inspect or dismiss. | Added a persistent transfer-queue card showing queued/running/completed/failed items with dismiss actions. | 19 |
| 18 | Agent auth existed as a profile enum but threw at connect time, leaving Android users with a broken path and no honest migration story. | Added app-managed agent-backed signing in `:core-ssh`, plumbed encrypted key/passphrase persistence for Agent mode, and updated the editor copy so Agent mode is now a real end-to-end flow. | 20 |
| 19 | There was no first-party migration surface for non-secret profile transfer, so import/export polish and release validation were still manual tribal knowledge. | Added bundle/config import-export UI plus a release checklist, API 31/33/35 matrix pack, and evidence template docs. | 20 |

### Testability

| # | Finding | Resolution | Checkpoint |
|---|---|---|---|
| 11 | `feature-tunnels/src/test/` was declared but empty in CK10; no test covered the mapper. | Added `TunnelRepositoryTest` (7 cases). | 15 |
| 12 | No test covered `ConnectionRepository`, the class that owns the secret-preservation invariant. | Added `ConnectionRepositoryTest` (7 cases) including the regression guard for "partial update must not wipe existing sealed credentials". | 15 |
| 13 | No test covered snippet CRUD or the ordering that matches the Room `COLLATE NOCASE` clause. | Added `SnippetsViewModelTest` (5 cases). | 15 |
| 14 | `BackgroundActivityController` touched Android's Handler / Context surface in its constructor — untestable without Robolectric. | Extracted a `ServiceLauncher` interface with `AndroidServiceLauncher` as the production impl. Added `BackgroundActivityControllerTest` (8 cases) using a plain string-recording fake. Zero Robolectric footprint. | 15 |

## Findings still open

Nothing in the code base is currently in the "broken but undocumented" bucket
to my knowledge; the remaining items are the physical-device gates listed in
[`RELEASE_BLOCKERS.md`](RELEASE_BLOCKERS.md), plus:

- Physical-device validation remains outstanding (IME edge cases, hardware keyboards, API 31/33/35/36 smoke tests).
- The in-app SFTP quick-edit path is now present for small UTF-8 text files, but a full external-editor temp-file workflow is still not implemented.
- Schema migration still relies on destructive downgrade handling; a formal migration test suite is still owed.
- Physical API 36 validation and 16 KiB-device smoke testing remain outstanding;
the packaged binaries are verified statically for all four ABIs.

If you spot something I missed, please open a private issue as per
[`SECURITY.md`](SECURITY.md) — do **not** file a public issue.
