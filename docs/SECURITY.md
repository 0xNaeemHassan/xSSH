# Security

## Reporting a vulnerability

Do **not** file public issues for security problems. Contact the maintainers
privately, preferably PGP-encrypted, and give us 90 days before public
disclosure unless we agree on a shorter window.

We honour credit in the release notes.

## Threat model — non-goals

xSSH is **not** designed to protect against:

- A fully rooted device with an active attacker (they own everything).
- A compromised OS, root process, malicious accessibility service, or attacker
who can already unlock the device and operate xSSH as the user.
- Cold-storage forensic recovery of a StrongBox-less device by a
well-funded adversary.

## Threat model — goals

xSSH aims to prevent, in decreasing order of priority:

1. **Silent MITM between phone and server.** Strict TOFU with fail-closed on
host-key change; the changed-key dialog is dismiss-only.
2. **Credential exfiltration by another Android app.** Passwords and private
keys live only as AES-256-GCM ciphertext produced by an Android Keystore
/StrongBox key (never exportable). Backup and device-transfer are disabled.
3. **Silent capture via clipboard or screenshots.** `FLAG_SECURE` is set on
the sole Activity; the terminal never auto-copies to the clipboard.
4. **Analytics or crash-reporter data leaks.** No such SDK exists in the
dependency graph; the `verifyNoTelemetry` Gradle task fails the build if
one is added.
5. **A single stripped-down BouncyCastle mis-configuration killing modern
crypto.** `CryptoBootstrap` installs the full `bcprov-jdk18on` at
position 1 in `Security.providers` before anything else touches sshj.

## First-principles rules the codebase enforces

1. **All secrets are opaque bytes to Room.**
`ConnectionRepository.upsert` never lets a plaintext CharArray reach a
DAO — everything is sealed via `SecretVault` first. Unit-tested in
`ConnectionRepositoryTest`.
2. **Partial updates never wipe secrets.**
If a caller omits `plaintextPassword` on upsert, the previously-stored
ciphertext is preserved. Regression-tested.
3. **Only explicitly approved SSH algorithms are negotiable.**
`buildSecureConfig` filters SSHJ factories through fixed cipher, MAC, KEX, and
host-key allowlists. `TransportOptions.disabledAlgorithms` can remove more
algorithms per profile but can never add one outside those allowlists.
4. **Host-key changes fail closed.**
`InteractiveHostKeyVerifier.verify` returns false and emits
`VerificationEvent.Changed`. There is no "continue anyway" button anywhere
in the code, and adding one would require reviewer sign-off.
5. **Host-key prompts time out.**
`decisionTimeoutSeconds` (default 90 s) bounds how long the sshj transport
thread can be parked waiting for a user decision. A torn-down UI cannot
leave the thread stuck.
6. **Bind local, always.**
`Tunnel.bindHost` defaults to `127.0.0.1`; the UI shows a red warning
before allowing `0.0.0.0`.
7. **No cleartext HTTP.**
`android:usesCleartextTraffic="false"` plus `network_security_config.xml`
with `cleartextTrafficPermitted="false"` blocks accidental HTTP inside any
transitive dependency.
8. **No backup, no device transfer.**
Both are explicitly forbidden in `data_extraction_rules.xml`.
9. **`FLAG_SECURE` is unconditional.**
Set in `MainActivity.onCreate`; hides the terminal from screenshots and
task previews.
10. **Debug-only logging.**
No application logging backend is installed. Release and debug builds avoid a
local logging sink that could accidentally retain session material.
11. **Host trust precedes secret decryption.**
Interactive sessions, SFTP, and tunnels finish host-key verification before
opening the encrypted credential blob, minimizing plaintext lifetime on failed
or untrusted connections.

The optional biometric setting is a pre-connect interaction gate, not a
Keystore authentication policy. The AES vault key is intentionally available
to app-managed background tunnels; do not describe the biometric option as
cryptographically binding the vault key.

## Cryptographic choices

| Concern | Choice |
|---|---|
| Symmetric AEAD | AES-256-GCM (Android Keystore-generated, non-exportable) |
| GCM nonce | 12 bytes, cipher-provided (never reused per key) |
| GCM tag length | 128 bits |
| Preferred SSH KEX | `curve25519-sha256`, `curve25519-sha256@libssh.org` |
| Preferred SSH host-key algorithms | `ssh-ed25519`, `ecdsa-sha2-nistp256/384/521`, `rsa-sha2-256/512` |
| Preferred SSH ciphers | `chacha20-poly1305@openssh.com`, `aes256-gcm@openssh.com`, `aes128-gcm@openssh.com` |
| Preferred MAC | `hmac-sha2-256`, `hmac-sha2-512` |
| Preferred key types (user-generated) | Ed25519 primary, ECDSA P-256 fallback |

Excluded by the allowlists (and also present in the default per-profile deny set):
`ssh-dss`, `ssh-rsa`, `diffie-hellman-group1-sha1`, `diffie-hellman-group14-sha1`,
`diffie-hellman-group-exchange-sha1`, `hmac-md5*`, `hmac-sha1*`, `3des-cbc`,
`aes128-cbc`, `aes192-cbc`, `aes256-cbc`, `arcfour*`, `blowfish-cbc`,
`cast128-cbc`.

## Dependencies

- `sshj` 0.40 (Apache 2.0) — SSH transport.
- `bcprov-jdk18on`, `bcpkix-jdk18on` (BC-license, Apache-2.0-compatible) —
KEX / host-key / signature primitives Android's stripped BC omits.
- `androidx.biometric` — biometric prompt.
- Termux `terminal-emulator`, `terminal-view` 0.118.3 (Apache 2.0) — terminal
engine. Its JNI bridge is rebuilt from attributed source with NDK r28 for
16 KiB page-size compatibility.

Anything with a `-analytics`, `-crashlytics`, `-perf`, `-ads`,
`facebook-android-sdk`, `amplitude`, `mixpanel`, `segment`, `sentry-android`,
`bugsnag`, or `datadog` group/artifact prefix is banned by
`verifyNoTelemetry` in the root `build.gradle.kts` — even transitively.

## What we scan for in CI

- `verifyNoTelemetry` — resolved-dependency scan for banned SDKs.
- `dependency-review.yml` GitHub Action — fails on `moderate`+ severity CVEs
on any pull request.
- `ktlintCheck` and `detekt` — style + static analysis.
- `testDebugUnitTest` — the full unit-test matrix documented in
[TESTING.md](TESTING.md).
- `scripts/verify_native_alignment.py` — checks every packaged Termux ABI for
16 KiB ELF load-segment and APK ZIP alignment.

We do not currently ship a SAST scanner; adding one (e.g. Semgrep with a
security rule pack) is on the P0 release-gate list.
