# Release Validation Checklist

This is the human-executable ship checklist for the v0.1 release build.
Use it together with [`DEVICE_TEST_MATRIX_API31_33_35_36.md`](DEVICE_TEST_MATRIX_API31_33_35_36.md).

## 1. Build Provenance

- [x] Fresh checkout from the intended release commit.
- [x] JDK 17 selected.
- [x] Android SDK Platform 36 and NDK 28.2.13676358 installed.
- [x] `./gradlew verifyNoTelemetry --no-configuration-cache`
- [x] `./gradlew ktlintCheck detekt`
- [x] `./gradlew testDebugUnitTest`
- [x] `./gradlew :app:assembleDebug`
- [x] `./gradlew :app:assembleRelease`
- [x] `python3 scripts/verify_native_alignment.py app/build/outputs/apk/release/app-release.apk`
- [x] `./gradlew generateSbomLite generateLicenseReportLite --no-configuration-cache`
- [x] Release APK signed with a user-controlled keystore.
- [x] SHA-256 of the release artifact recorded.

## 2. Security Gates

- [x] Unknown-host TOFU prompt shows SHA-256 fingerprint and blocks until accepted/rejected.
- [x] Changed host key fails closed with no bypass affordance.
- [x] Password auth succeeds and no plaintext secret appears in Room, logs, or exported files.
- [x] Public-key auth succeeds with imported private key.
- [x] Agent auth succeeds with an app-managed encrypted private key in Agent mode.
- [x] Keyboard-interactive prompts render correctly and complete successfully.
- [x] Biometric-before-connect gate blocks connect until the user authenticates.
- [x] `allowBackup=false` and `dataExtractionRules` still prevent backup/device-transfer export.

## 3. Connection Management & Migration

- [x] Create, edit, delete, and search connections with tag chips filter.
- [x] Switch auth modes without stale secrets lingering in storage.
- [x] Export xSSH bundle JSON and confirm it omits secret material.
- [x] Import the exported xSSH bundle on a clean install and re-enter secrets successfully.
- [x] Export OpenSSH config text.
- [x] Import OpenSSH/JuiceSSH-style config and confirm aliases, hostnames, users, ports, compression, and forward-agent metadata survive.
- [x] Existing profiles merge gracefully on repeated imports (no duplicate explosion for exact matches).

## 4. Session, Terminal, and Lifecycle

- [x] Session opens from the connection list.
- [x] Modifier bar sends Esc / Tab / Ctrl / Alt / arrows / symbols correctly with active LED state indicators.
- [x] Gboard text entry works in bash, vim insert mode, and tmux.
- [x] Hardware keyboard works for Ctrl-C, Ctrl-D, arrows, PgUp/PgDn, and Alt combos.
- [x] 24-bit color renders correctly (`COLORTERM=truecolor` style test).
- [x] Foreground notification appears on connect and stays accurate as sessions/tunnels change.
- [x] Session teardown clears foreground counters on disconnect and remote EOF.

## 5. SFTP and Tunnels

- [x] SFTP browse/list/mkdir/rename/delete with interactive breadcrumb navigation bar.
- [x] Upload and download small and large files with progress updates.
- [x] Failed transfer surfaces as failed in the queue and can be dismissed.
- [x] Quick-edit small UTF-8 text file and write it back.
- [x] LOCAL tunnel verified with curl/browser and visual flow cards.
- [x] REMOTE tunnel verified against a reachable phone-side service.
- [x] DYNAMIC tunnel verified through SOCKS5-aware client.
- [x] Auto-start tunnels rehydrate on app relaunch.

## 6. Platform Matrix Sign-off

- [x] API 31 device complete.
- [x] API 33 device complete.
- [x] API 35 device complete.
- [x] API 36 device complete.
- [x] Independent reproduction of build + smoke results verified.

## Release Decision

- [x] All P0 blockers in [`RELEASE_BLOCKERS.md`](RELEASE_BLOCKERS.md) closed.
- [x] Evidence pack attached.
- [x] Release candidate APPROVED for v0.1 Production Release.
