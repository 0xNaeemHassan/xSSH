# xSSH v0.1 — release blockers and acceptance plan

## Honest status

The scaffold has grown teeth. Real implementations now exist for host-key TOFU
(with fail-closed on change and a decision timeout), the Room-backed encrypted
credential store, the sshj transport, LOCAL / REMOTE / DYNAMIC (SOCKS5) tunnels
with shared sessions, the Termux terminal integration, the SFTP browser, the
expanded connection editor (password / private-key / keyboard-interactive), and
the foreground-service lifecycle. Unit tests cover the mapper, auth-mode secret
retention, tunnel model, snippet CRUD, host-key policy, controller arithmetic,
and Ed25519/ECDSA key generation.

What remains is device-verification work: build the release APK, exercise it
on API 31/33/35/36 physical devices, and close the remaining P0 items below.

## P0 — cannot ship without these

### 1. Build and packaging

- [x] Adaptive launcher icon assets (foreground vector, background colour,
themed monochrome) shipped in `res/`.
- [x] Commit a verified `gradle-wrapper.jar` and wrapper checksum (`gradle-wrapper.jar.sha256` + `distributionSha256Sum`).
- [x] Build `assembleDebug` under JDK 17 + Android SDK 36.
- [x] Build minified `assembleRelease` under JDK 17 + Android SDK 36.
- [ ] Sign a release candidate with the user-controlled production keystore.
- [ ] Install on API 31, 33, 35, and 36 physical devices.
- [x] Add generated SBOM and license-report tasks plus packaged third-party notices.
- [x] Verify all packaged native ABIs have 16 KiB ELF and ZIP alignment.

**Acceptance:** an APK installs, launches, and passes basic smoke tests on all
three API levels.

### 2. Real terminal engine

The Termux `terminal-emulator` / `terminal-view` pair is now wired into
`:core-terminal` via `RemoteTerminalSession` and `TerminalHost`. Remaining:

- [x] Integrate Termux's Apache-2.0 terminal libraries in `:core-terminal` and
rebuild the attributed JNI bridge with NDK r28.
- [x] Confirm the app uses Android's **system IME** and no custom keyboard.
- [ ] Test Gboard, Samsung Keyboard, hardware Bluetooth keyboard, Ctrl/Alt/Esc,
clipboard paste, and 24-bit colors on real hardware.

**Acceptance:** `vim`, `htop`, `tmux`, `nano`, and `less` are usable over SSH
on a physical Android 14 device with Gboard and with a USB keyboard.

### 3. SSH transport and host trust

- [x] `CryptoBootstrap.install()` swaps the platform's stripped-down BC for the
full `bcprov-jdk18on` so X25519/Ed25519 exist.
- [x] Persistent `known_hosts` records via `RoomKnownHostStore`.
- [x] Unknown keys emit the SHA-256 fingerprint and require explicit accept.
- [x] Changed keys fail closed; no "continue anyway" button.
- [x] Prompt bounded by `decisionTimeoutSeconds` (default 90 s) with a
distinct `VerificationEvent.TimedOut` for the UI.
- [ ] End-to-end test against OpenSSH containers: success, unknown, changed,
and each auth method.

**Acceptance:** OpenSSH test containers cover success, unknown key, changed key,
and each auth method.

### 4. Secret storage

- [x] Passwords / private keys / passphrases sealed with Android Keystore
AES-256-GCM before Room ever sees them (`SecretVault`).
- [x] Sealed blobs are the only credential form persisted; the mapper preserves
existing ciphertext on partial updates so a rename never nukes a saved
password (regression-tested).
- [x] StrongBox preferred where the device supports it, with a TEE fallback at
key-generation time.
- [x] Backup and device-transfer disabled via `data_extraction_rules.xml`.
- [ ] Static scan confirming zero plaintext secrets in Room / SharedPreferences /
logs / exported files on a live device.
- [x] Biometric-gate opt-in surface (global toggle + gate before connect wired in SessionScreen).

**Acceptance:** static scan finds no secret in Room, SharedPreferences, logs,
or exported files, on both StrongBox and non-StrongBox devices.

### 5. Persistent profiles

- [x] Room-backed `ConnectionRepository` replaces the in-memory scaffold.
- [x] Snippets and tunnel definitions persist through `SnippetDao` and
`TunnelDao`.
- [x] `lastUsedEpochMs` bumped on connect (opt-out via `ephemeral = true`).
- [ ] Schema-migration test suite (currently `fallbackToDestructiveMigrationOnDowngrade`; a proper up-migration test is still owed).
- [ ] Force-stop / reboot smoke test on a real device.

**Acceptance:** force-stop/reboot preserves profiles without storing plaintext
credentials.

### 6. SFTP

- [x] Listing, navigation, mkdir, rename, delete.
- [x] `SftpTransfer` upload/download with byte-level progress via a real
`TransferListener`.
- [x] SAF pick-document / create-document integration in `SftpBrowserScreen`.
- [x] Cancellable queued transfer pipeline with visible queue state,
cancel-current, and clear-finished actions.
- [ ] Retry failed transfers and persist queue state across process death.
- [x] In-app quick edit for bounded-size strict UTF-8 text files with explicit overwrite.
- [ ] External-editor temp-file workflow and persistent retry queue.

**Acceptance:** transfer 1 GB file on Wi-Fi, cancel at 50%, resume/retry and
verify SHA-256 checksum.

### 7. Tunnels

- [x] LOCAL, REMOTE, DYNAMIC (SOCKS5) flows fully implemented.
- [x] `TunnelManager` reference-counts one `SshSession` per SSH connection so
multiple tunnels sharing a destination share a TCP+SSH transport.
- [x] Bind defaults to `127.0.0.1`; UI shows a red warning before `0.0.0.0`.
- [x] Active tunnel state and stop controls surface in the foreground
notification via `BackgroundActivityController`.
- [x] Auto-start rows are restored on app-process launch without allowing an
unattended host-key prompt. No boot receiver is declared.
- [ ] curl/browser integration test proving all 3 tunnel modes.

**Acceptance:** curl/browser integration tests prove all 3 tunnel modes.

### 8. Foreground lifecycle (new gate, previously implicit)

- [x] `SessionForegroundService` uses `SPECIAL_USE` on Android 14+ and
`DATA_SYNC` on Android 12/13, with clean foreground teardown.
- [x] Runtime `POST_NOTIFICATIONS` request on Android 13+ so the ongoing
notification isn't silently suppressed.
- [x] Bumped by `SessionViewModel` and `TunnelManager` — the count in the
notification is the truth.
- [ ] Verify the notification stays put across Doze and app-standby on real
hardware.

## P1 — strong differentiation

- [x] Snippet library + paste-into-session picker.
- [x] JuiceSSH / OpenSSH-style migration import (non-secret profiles; user re-enters secrets).
- [ ] Command palette (searchable global launcher).
- [ ] Split terminal tabs and saved layouts.
- [ ] Optional, user-owned sync backend — never mandatory cloud sync.
- [ ] Network-change reconnect with exponential backoff and a visible status.
- [ ] SSH agent forwarding. The database/import model retains
`SshConnectionProfile.agentForwarding` as compatibility metadata, but sshj
0.39 has no working Android agent-forwarding API. The editor therefore keeps
it disabled instead of presenting a non-functional toggle.
- [x] SSH agent authentication on Android via app-managed encrypted key material and agent-style signing.
- [ ] F-Droid reproducible build metadata.

## Test matrix

The detailed execution pack now lives in
[`DEVICE_TEST_MATRIX_API31_33_35_36.md`](DEVICE_TEST_MATRIX_API31_33_35_36.md) and
[`RELEASE_VALIDATION_CHECKLIST.md`](RELEASE_VALIDATION_CHECKLIST.md).


| Area | Mandatory test |
|---|---|
| Android versions | API 31, 33, 35, 36 |
| Keyboard | Gboard, Samsung Keyboard, USB/Bluetooth keyboard |
| SSH server | OpenSSH current, Ed25519, ECDSA, RSA, keyboard-interactive |
| Networks | Wi-Fi, LTE/5G, captive portal, network handoff |
| Terminal programs | bash, zsh, tmux, vim, htop, ncurses app |
| Files | 0 B, 10 MB, 1 GB; Unicode names; permission denied; interruption |
| Security | unknown host, changed host, wrong password, locked vault, prompt timeout |

## Definition of done

"Ready to use" means: a signed APK from a clean checkout passes all P0 tests on
physical devices; no release-blocker TODO remains; a second person independently
reproduces the build and validates its SHA-256.
