# xSSH v0.1 — Release Blockers & Acceptance Plan

## Status Overview

**All release blockers are closed and 100% verified on physical hardware.** The codebase features complete implementations for host-key TOFU (with SHA-256 fingerprint validation and decision timeout), Room-backed encrypted credential store (AES-256-GCM Keystore vault), sshj transport, LOCAL / REMOTE / DYNAMIC (SOCKS5) tunnels with shared sessions, Termux terminal engine, SFTP browser with queue, breadcrumbs & text editor, explicit auth-mode profile editor (Password / Public-Key / Agent / Keyboard-Interactive), biometric session lock, migration toolkit, and a state-of-the-art **Cyber-Dark Jetpack Compose UI/UX design**.

## P0 — Release Gates (All Closed & Verified)

### 1. Build and Packaging

- [x] Adaptive launcher icon assets shipped in `res/`.
- [x] Verified `gradle-wrapper.jar` and wrapper checksum (`gradle-wrapper.jar.sha256` + `distributionSha256Sum`).
- [x] Build `assembleDebug` under JDK 17 + Android SDK 36.
- [x] Build minified `assembleRelease` under JDK 17 + Android SDK 36.
- [x] Sign release candidate with a user-controlled production keystore.
- [x] Installed and validated on API 31, 33, 35, and 36 physical devices.
- [x] Generated SBOM and license-report tasks plus packaged third-party notices.
- [x] Verified 16 KiB ELF and ZIP native library alignment.

### 2. UI & UX Architecture

- [x] Cyber-Dark obsidian color palette (`#070B10`, `#0D131C`) with electric sky cyan (`#38BDF8`) and cyber violet (`#A78BFA`) accents.
- [x] Glassmorphic card surfaces (`GlassCard`) with subtle borders and container elevation.
- [x] Active connection status pills (`StatusPill`) with live color-coded dot indicators.
- [x] Modernized floating navigation bar (`Routes.kt`) with animated tab indicators.
- [x] Stateful `ModifierBar` with active `Ctrl`/`Alt` LED arming highlights.
- [x] Redesigned SFTP browser with file type specific icons, breadcrumbs, and transfer progress bar.
- [x] Visual port forwarding directional cards (`Local: 8080 ➔ Remote: 80`).
- [x] Terminal-styled command snippet cards with tag chips and one-tap action buttons.

### 3. Real Terminal Engine

- [x] Termux `terminal-emulator` / `terminal-view` integration in `:core-terminal` with NDK r28 JNI bridge.
- [x] Android system IME and hardware keyboard support.
- [x] Bounded PTY window resizing and UTF-8 byte stream handling.
- [x] Physical device validation on Gboard, Samsung Keyboard, and Bluetooth hardware keyboard.

### 4. SSH Transport & Host Trust

- [x] `CryptoBootstrap.install()` swaps platform BC for full `bcprov-jdk18on`.
- [x] Persistent `known_hosts` records via `RoomKnownHostStore`.
- [x] Unknown keys emit SHA-256 fingerprint and require explicit user approval.
- [x] Changed host keys fail closed; no insecure override bypass.
- [x] Bounded host verification prompt timeout (default 90 s).

### 5. Secret Storage & Encryption

- [x] Passwords, private keys, and passphrases sealed with Android Keystore AES-256-GCM (`SecretVault`).
- [x] StrongBox preferred with TEE fallback.
- [x] Unencrypted cloud/device backups disabled via `data_extraction_rules.xml`.
- [x] Biometric gate opt-in surface (`BiometricGate` before session startup).

### 6. Persistent Profiles & Migration

- [x] Room-backed `ConnectionRepository`, `SnippetDao`, and `TunnelDao`.
- [x] Versioned non-destructive Room v1→v2 migrations.
- [x] Migration toolkit (xSSH JSON bundle export/import, OpenSSH config export/import, JuiceSSH config import).

### 7. SFTP & File Manager

- [x] Remote file listing, directory navigation, mkdir, rename, delete.
- [x] `SftpTransfer` upload/download pipeline with byte progress tracking.
- [x] SAF document pickers, breadcrumb bar, and quick text file editor.

### 8. Port Forwarding (Tunnels)

- [x] LOCAL (-L), REMOTE (-R), and DYNAMIC SOCKS5 (-D) forwarding.
- [x] Session sharing across multiple tunnels targeting the same SSH endpoint.
- [x] Expose-on-LAN security warning badge for `0.0.0.0` binds.

### 9. Foreground Service Lifecycle

- [x] `SessionForegroundService` with `SPECIAL_USE` (API 34+) and `DATA_SYNC` (API 31–33).
- [x] Ongoing notification displaying active session and tunnel counters.

---

## Acceptance Test Matrix (All Verified)

| Area | Mandatory Test | Status |
|---|---|---|
| Android API Levels | API 31, 33, 35, 36 | ✅ Physical device verified |
| Keyboards | Gboard, Samsung Keyboard, USB/Bluetooth | ✅ Physical device verified |
| SSH Servers | OpenSSH, Ed25519, ECDSA, RSA, KBI | ✅ Verified |
| UI/UX | Jetpack Compose Cyber-Dark Theme | ✅ Verified |
| Telemetry | Zero analytics/tracking enforcement | ✅ CI enforced |
