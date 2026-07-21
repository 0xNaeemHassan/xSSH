# Release validation checklist

This is the human-executable ship checklist for the current pre-alpha build.
Use it together with [`DEVICE_TEST_MATRIX_API31_33_35_36.md`](DEVICE_TEST_MATRIX_API31_33_35_36.md)
and attach evidence in the format from [`TEST_EVIDENCE_TEMPLATE.md`](TEST_EVIDENCE_TEMPLATE.md).

## 1. Build provenance

- [ ] Fresh checkout from the intended release commit.
- [ ] JDK 17 selected.
- [ ] Android SDK Platform 36 and NDK 28.2.13676358 installed.
- [ ] `./gradlew verifyNoTelemetry --no-configuration-cache`
- [ ] `./gradlew ktlintCheck detekt`
- [ ] `./gradlew testDebugUnitTest`
- [ ] `./gradlew :app:assembleDebug`
- [ ] `./gradlew :app:assembleRelease`
- [ ] `python3 scripts/verify_native_alignment.py app/build/outputs/apk/release/app-release.apk`
- [ ] `./gradlew generateSbomLite generateLicenseReportLite --no-configuration-cache`
- [ ] Release APK signed with a user-controlled keystore that is not stored in the repo.
- [ ] SHA-256 of the release artifact recorded.

## 2. Security gates

- [ ] Unknown-host TOFU prompt shows SHA-256 fingerprint and blocks until accepted/rejected.
- [ ] Changed host key fails closed with no bypass affordance.
- [ ] Password auth succeeds and no plaintext secret appears in Room, logs, or exported files.
- [ ] Public-key auth succeeds with imported private key.
- [ ] Agent auth succeeds with an app-managed encrypted private key in Agent mode.
- [ ] Keyboard-interactive prompts render correctly and complete successfully.
- [ ] Biometric-before-connect gate blocks connect until the user authenticates.
- [ ] `allowBackup=false` and `dataExtractionRules` still prevent backup/device-transfer export.

## 3. Connection-management and migration

- [ ] Create, edit, delete, and search connections.
- [ ] Switch auth modes without stale secrets lingering in storage.
- [ ] Export xSSH bundle JSON and confirm it omits secret material.
- [ ] Import the exported xSSH bundle on a clean install and re-enter secrets successfully.
- [ ] Export OpenSSH config text.
- [ ] Import OpenSSH/JuiceSSH-style config and confirm aliases, hostnames, users, ports, compression, and forward-agent metadata survive.
- [ ] Existing profiles merge sensibly on repeated imports (no duplicate explosion for exact matches).

## 4. Session, terminal, and lifecycle

- [ ] Session opens from the connection list.
- [ ] Modifier bar sends Esc / Tab / Ctrl / Alt / arrows correctly.
- [ ] Gboard text entry works in bash, vim insert mode, and tmux.
- [ ] Hardware keyboard works for Ctrl-C, Ctrl-D, arrows, PgUp/PgDn, and Alt combos.
- [ ] 24-bit color renders correctly (`COLORTERM=truecolor` style test).
- [ ] Foreground notification appears on connect and stays accurate as sessions/tunnels change.
- [ ] Session teardown clears foreground counters on disconnect and remote EOF.

## 5. SFTP and tunnels

- [ ] SFTP browse/list/mkdir/rename/delete.
- [ ] Upload and download small and large files with progress updates.
- [ ] Failed transfer surfaces as failed in the queue and can be dismissed.
- [ ] Quick-edit small UTF-8 text file and write it back.
- [ ] LOCAL tunnel verified with curl/browser.
- [ ] REMOTE tunnel verified against a reachable phone-side service.
- [ ] DYNAMIC tunnel verified through SOCKS5-aware client.
- [ ] Auto-start tunnels rehydrate on app relaunch.

## 6. Platform matrix sign-off

- [ ] API 31 device complete.
- [ ] API 33 device complete.
- [ ] API 35 device complete.
- [ ] API 36 device complete.
- [ ] Second reviewer independently reproduces build + smoke results.

## Release decision

- [ ] All P0 blockers in [`RELEASE_BLOCKERS.md`](RELEASE_BLOCKERS.md) closed.
- [ ] Evidence pack attached.
- [ ] Release candidate approved.
