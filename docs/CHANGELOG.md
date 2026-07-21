# Changelog

Notable engineering checkpoints on the way to v0.1. The pre-release
checkpoints are numbered so a reviewer can identify exactly which snapshot
of the code an artifact came from.

## Checkpoint 21 — full security, lifecycle, UI, and release audit

- Reworked every main Compose surface with a cohesive Material 3 design system,
responsive navigation, validation, empty/error/loading states, safer destructive
actions, and clearer terminal/SFTP/tunnel workflows.
- Fixed SSH host-verification ordering, terminal FIFO/backpressure and PTY resize,
SFTP atomicity/reconnect behavior, import collision handling, DAO replace/cascade
loss, tunnel sharing races, SOCKS concurrency, and foreground-service lifecycle.
- Enforced explicit modern SSH algorithm allowlists, SHA-256 host fingerprints,
credential buffer clearing, Android 14 special-use FGS declarations, and
fail-closed dependency/telemetry reporting.
- Upgraded to API 36, KSP2, current API-36-compatible AndroidX libraries, SSHJ
0.40, Bouncy Castle 1.84, and Termux 0.118.3.
- Rebuilt `libtermux.so` for every ABI with NDK r28 and added artifact-level ELF
and ZIP 16 KiB alignment verification to CI and release workflows.
- Expanded unit/static-analysis gates to every module and corrected privacy,
security, licensing, and release documentation to match actual behavior.

## Checkpoint 20 — agent auth + migration toolkit + release pack

- Added end-to-end Android SSH agent authentication using app-managed encrypted key material, an agent-backed signer, and an explicit Agent auth mode in the profile editor.
- Extended `ConnectionRepository` so Agent profiles persist encrypted private keys/passphrases and clear stale password ciphertext on auth-mode switches.
- Added a migration/import/export toolkit: xSSH bundle JSON export/import, OpenSSH config export, and OpenSSH/JuiceSSH-style config import from the connection list.
- Added release-ops documentation pack: `RELEASE_VALIDATION_CHECKLIST.md`, the device matrix, and `TEST_EVIDENCE_TEMPLATE.md`.
- Added migration codec tests and new repository coverage for Agent-mode secret persistence.
- Updated README / release blockers / testing docs / audit notes to reflect the new shipped capability set and the remaining physical-device gates.

## Checkpoint 19 — auth editor completion + session cleanup

- Reworked `ConnectionEditScreen` into an explicit auth-method editor instead of inferring auth from a blank/non-blank password field.
- Added private-key import via SAF, optional key passphrase capture, generated Ed25519 key drafts, and an `authorized_keys` preview.
- Hardened `ConnectionRepository.upsert(...)` so changing auth method clears irrelevant stored ciphertext instead of silently carrying old secrets forward.
- Fixed `SessionViewModel` teardown so shell/output/session cleanup and foreground-service counters are released consistently on disconnect or remote EOF.
- Surfaced the SFTP transfer queue even when no transfer is actively running, so completed/failed items remain visible and dismissible.
- Updated README / release blockers / audit notes to reflect the shipped auth-editor state and the still-open SSH-agent-auth gap honestly.

## Checkpoint 18 — biometric gate + SFTP quick edit + repo hardening

- Added a global "require biometric before connecting" toggle backed by DataStore, and wired `BiometricGate` into `SessionScreen` before transport startup.
- Added retry/error handling around biometric failures so a cancelled prompt does not hard-crash or silently fail.
- Added an in-app SFTP quick-edit flow for small UTF-8 text files: download into memory, edit in a bottom sheet, upload back to the same remote path.
- Added a queued-transfer surface in the SFTP UI with cancel-current / clear-finished affordances.
- Added lightweight `generateSbomLite` and `generateLicenseReportLite` Gradle reporting tasks.
- Added GitHub issue templates for bug reports and feature requests.
- Updated README / release blockers / audit notes to match the latest implementation state.

## Checkpoint 17 — auth/UI hardening + SFTP queue

- Added unified `ConnectionRepository.credentialFor(...)` dispatch so shell, SFTP, and tunnels all honor password / public-key / agent / keyboard-interactive consistently.
- Added `core-ssh/KeyProviders.kt` to load pasted/imported OpenSSH, PKCS#8, PEM, and PuTTY private keys into sshj.
- Extended `KeyMaterial` with private-key export + public-key fingerprint helpers.
- Reworked `SessionViewModel` + `SessionScreen` to surface keyboard-interactive prompts through a dialog instead of failing silently.
- Reworked `SftpViewModel` to use a cancellable queued transfer pipeline and default to the server's canonical home directory rather than `/`.
- Updated `TunnelManager` to refuse unattended keyboard-interactive auth explicitly instead of deadlocking.
- Added issue templates and lightweight SBOM / dependency-inventory Gradle tasks.

## Checkpoint 16 — documentation refresh & polish

- Rewrote `README.md` to reflect the actual implemented capability matrix
(10 modules, tunnels/snippets/foreground-service all shipping).
- Rewrote `docs/ARCHITECTURE.md`: added module dependency graph, threading
model, keystroke/byte-return data flow, snippet paste flow, host-key TOFU
state machine, tunnel session-sharing diagram, and foreground-service
lifecycle.
- Rewrote `docs/RELEASE_BLOCKERS.md` to flip closed items to `[x]` and add
a new P0 gate for foreground-service correctness.
- Rewrote `docs/SECURITY.md` into a proper threat model plus 10 first-principles
rules that the codebase actually enforces, plus a cryptographic-choices table.
- Added `docs/PRIVACY.md` — the formal privacy contract as its own doc.
- Added `docs/TESTING.md` — how to run tests, what each module covers, style
guide for adding new ones.
- Added `docs/AUDIT_NOTES.md` — a record of the audit findings resolved in
checkpoints 12–15 so future contributors know what was already reviewed.
- Light update to `docs/DESIGN_ASSETS.md` acknowledging the adaptive icon
now exists in-tree.

## Checkpoint 15 — tests

- Added `TunnelRepositoryTest` (7 cases): LOCAL/REMOTE/DYNAMIC round-trips,
observeAll flow behaviour, observeForConnection filtering, delete.
- Added `SnippetsViewModelTest` (5 cases): blank-label guard, CRUD flow,
UUID uniqueness, DAO ordering.
- Added `ConnectionRepositoryTest` (7 cases): mapper round-trip for every
`AuthMethod`, seal-on-upsert contract, **preserve-existing-secret on
partial update** (regression guard), decrypt round-trip, delete.
- Added `BackgroundActivityControllerTest` (8 cases): counter arithmetic,
clamp at zero for negative bumps, promote/demote sequence, 100× stress.
- Refactored `BackgroundActivityController` to sit behind a `ServiceLauncher`
seam so its counter logic is unit-testable without Robolectric; production
uses `AndroidServiceLauncher` (main-thread Handler + real Context).
- Added `mockk`, `truth`, `kotlinx-coroutines-test`, `turbine` test deps to
the four feature modules that gained tests.

## Checkpoint 14 — foreground service + snippet paste

- New `BackgroundActivityController` singleton with atomic session/tunnel
counters; wired into `SessionViewModel` (bumps on connect/disconnect) and
`TunnelManager` (bumps on start/stop/stopAll).
- Rewrote `SessionForegroundService` to correctly handle a zero-count start
intent (drops foreground promotion cleanly), pass the required
`FOREGROUND_SERVICE_TYPE_DATA_SYNC` on Q+, and use a monochrome status-bar
icon.
- Added `SessionViewModel.pasteSnippet(...)` plus a `SnippetPasteSheet` on
`SessionScreen` (paste / paste-and-run).
- Handled `InteractiveHostKeyVerifier.VerificationEvent.TimedOut` in
`SessionScreen` with a distinct dialog copy.
- `XSshApplication.onCreate` now calls `CryptoBootstrap.install()` before
anything can touch sshj / BouncyCastle.
- `MainActivity` requests `POST_NOTIFICATIONS` at runtime on Android 13+ so
the foreground notification is not silently suppressed.
- Removed the ugly `Modifier.padding(16.dp())` shim in `SessionScreen`;
proper `androidx.compose.ui.unit.dp` import restored.

## Checkpoint 13 — runtime correctness fixes

- **Fixed Socks5Server.** Was calling `client.connection.openChannel(
"direct-tcpip", ...)` — that is not sshj's public API and the signature
drifts between minor versions. Replaced with the real
`SSHClient.newDirectConnection(host, port)`, added the RFC-1928
"no acceptable methods" reply when the client did not offer 0x00, and
named the pump threads so profiler traces are actionable.
- **Fixed InteractiveHostKeyVerifier timeout.** `decisionTimeoutSeconds`
was a declared but unused constructor parameter — the verifier would park
the sshj transport thread indefinitely if the UI died before answering.
Now bounded via `withTimeoutOrNull`; a distinct
`VerificationEvent.TimedOut` is emitted so the UI can distinguish
timeout from rejection.
- **Fixed SftpTransfer.upload.** Was building a nonsensical
`TransferListener { object : TransferListener { ... } }` lambda-of-object
that never installed. Replaced with a proper `TransferListener` object
writing `moved` bytes into the progress callback, plus a full
`LocalSourceFile` implementation (permissions, isFile/isDirectory,
atime/mtime opt-out).
- Also closed the RemoteFile handle and the caller-supplied stream in a
finally block on both download and upload.

## Checkpoint 12 — launcher icons & theme polish

- Added the adaptive-icon suite (now in `mipmap-anydpi/ic_launcher.xml`),
`ic_launcher_round.xml`, `drawable/ic_launcher_foreground.xml`,
`values/ic_launcher_background.xml`. Foreground is a vector `>_` terminal
glyph; `<monochrome>` variant handles Android 13+ themed icons.
- Fixed `windowLightStatusBar="true"` on the pre-Compose theme so the
first frame doesn't flash light-on-dark status-bar icons over the black
activity background. Added a `values-night/themes.xml` override.

## Checkpoint 11 — CK9 + CK10 merged

- Merged `xssh-checkpoint-9-sftp.zip` with `xssh-ck10-gamma.zip`. CK10 was
a strict superset (new tunnel implementation, snippet UI, Hilt DI module,
refined SshClient + Socks5 + SecretVault + SessionForegroundService).
Merge = start from CK10 and preserve CK9's `.git` history separately.

## Checkpoint 10 (gamma) — new feature landing

Prior to the CK9/CK10 merge, CK10 introduced:

- `TunnelManager`, `TunnelRepository`, `TunnelsViewModel` — full tunnel
runtime with reference-counted shared `SshSession`s.
- `SnippetsScreen`, `SnippetsViewModel` — full snippet CRUD.
- `core-data/di/DataModule.kt` — Hilt module for Room and DAOs.
- Refined `SshClient`, `Socks5Server`, `SecretVault`,
`SessionForegroundService`, `XSshApplication`, `XSshNavHost`.

## Checkpoint 9 — SFTP

The earliest snapshot in this changelog. Contained the scaffold with the
initial SFTP browser but without functional tunnels, snippet UI, or DI.
