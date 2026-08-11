# xSSH

<p align="center">
    <img src="docs/images/xssh-icon.png" width="128" alt="xSSH icon" />
</p>

<h3 align="center">Secure. Fast. Local-First. Zero Tracking.</h3>
<p align="center">
The premier, open-source SSH & SFTP client built natively for Android with Jetpack Compose, Cyber-Dark aesthetics, and Android Keystore hardware encryption.
</p>

![xSSH hero visual](docs/images/xssh-hero.png)

> **Project status — v0.1 Production Release.** xSSH features an ultra-modern Jetpack Compose UI/UX, full profile management (Password, Public Key, Agent, Interactive auth), passphrase-aware private key loading and in-app Ed25519 key generation, biometric session lock, queued SFTP file manager with interactive breadcrumbs & quick text editing, local/remote/dynamic port forwarding with visual traffic flow diagrams, command snippets, and a complete OpenSSH/JuiceSSH migration toolkit. Tested and verified on physical hardware across Android 12, 13, 14, and 15 (APIs 31, 33, 35, 36). See [`docs/CHANGELOG.md`](docs/CHANGELOG.md) for detailed milestone history.

## Product Thesis

xSSH exists to make the reliable, offline-first parts of the Android SSH experience available without an account, subscription gate, ad SDK, analytics SDK, or opaque cloud vault. Credentials stay sealed in local Android Keystore hardware storage. The user's native phone keyboard is the primary input device, supplemented by a slim, stateful modifier bar (`Ctrl`, `Alt`, `Esc`, `Tab`, arrows, `Paste`, shell symbols) with LED-style state indicators.

## Visual Direction & Design System

![xSSH UI screens](docs/images/xssh-app-screens.png)

- **Cyber-Dark & Material You Aesthetic**: High-contrast obsidian backgrounds (`#070B10`), glassmorphic surface cards (`#0D131C`), electric sky cyan accents (`#38BDF8`), and cyber violet secondary indicators (`#A78BFA`).
- **Dynamic Dashboard & Navigation**: Workspace hero dashboard banner, tag filter chips (`All`, `Production`, `Staging`, `Database`, `Web`), bottom navigation bar with active tab indicators, glowing status dot pills, and interactive modal sheets.
- **Terminal Viewport**: Full 24-bit color xterm terminal emulator powered by Termux, pinch-to-zoom font scaling, long-press text selection, floating copy/paste toolbar, clipboard integration, and stateful LED modifier key dock.

### Capability Matrix

| Capability | Status |
|---|---|
| SSH2 profiles, tags, full-text search, quick connect | ✅ Shipped & Verified (Room-backed) |
| System IME + hardware keyboard support | ✅ Termux emulator + system-IME bridge |
| xterm terminal emulation, 24-bit color, scrollback | ✅ via Termux `terminal-emulator` |
| Password authentication | ✅ Profile editor + AES-256-GCM Keystore vault |
| Ed25519 / ECDSA / RSA private-key auth | ✅ Import/generate + passphrase-aware loading |
| Keyboard-interactive auth | ✅ Challenge prompt dialog wired through session flow |
| SSH agent authentication | ✅ App-managed encrypted key, agent-style signing |
| Android Keystore / StrongBox encrypted secret vault | ✅ Hardware sealed blobs |
| Biometric pre-connect gate | ✅ Hardware biometric check before interactive sessions |
| Strict host-key TOFU + fail-closed on change | ✅ SHA-256 fingerprint verification |
| SFTP browser (list, mkdir, rename, delete, up/download) | ✅ Queued transfers + SAF + breadcrumbs + text editor |
| Local (`-L`), remote (`-R`), dynamic SOCKS5 (`-D`) forwarding | ✅ Visual flow cards + shared background sessions |
| Command snippets + paste-into-session | ✅ Room-backed + auto-run option |
| Migration / import / export | ✅ xSSH JSON bundle + OpenSSH/JuiceSSH config migration |
| Modifier bar (Ctrl/Alt LED armed, Esc, Tab, arrows, Paste, symbols) | ✅ Stateful horizontal tool dock |
| Foreground service for background sessions and tunnels | ✅ Ongoing notification |
| Cyber-Dark UI & Glassmorphism Design System | ✅ Jetpack Compose components |
| Zero analytics, zero telemetry, zero ads | ✅ CI-enforced |

See [`docs/COMPETITION_2026.md`](docs/COMPETITION_2026.md) for competitive targets.

## Architecture

![Module architecture concept](docs/images/xssh-architecture.png)

```
:app                    Single Activity, Compose navigation, Hilt DI, background tunnel restoration
:design-system          Cyber-Dark Material 3 theme, glassmorphic cards, status pills, host-key dialogs
:core-ssh               sshj transport, TOFU host-key policy, tunnels, SOCKS5, SFTP engine
:core-terminal          Termux terminal emulator + system-IME bridge + modifier bar
:core-crypto            Keystore vault, biometric gate, key generator helpers
:core-data              Room database, DAOs, entities, Hilt DI module
:feature-connections    profiles, connection edit screen, encrypted repository
:feature-session        live terminal session, foreground service, snippet paste
:feature-sftp           remote file browser, breadcrumb bar, queue manager, text edit
:feature-tunnels        local / remote / SOCKS port forwards + visual flow diagrams
:feature-snippets       reusable command library
```

Detailed data-flow and threading notes in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Privacy & Security Contract

The full contract is documented in [`docs/PRIVACY.md`](docs/PRIVACY.md) and [`docs/SECURITY.md`](docs/SECURITY.md).
Key guarantees:

- No account required.
- Zero analytics, crash reporting, advertising, or tracking SDKs.
- CI enforces `./gradlew verifyNoTelemetry` on every pull request.
- Credentials and private keys never leave the device and are never backed up unencrypted.
- `FLAG_SECURE` prevents recent-task previews and screenshots from leaking sensitive terminal screens.

## Building & Testing

```bash
# Bootstrap Gradle wrapper
gradle wrapper --gradle-version 8.13 --distribution-type all

# Build Debug APK
./gradlew :app:assembleDebug

# Run static analysis and telemetry verification
./gradlew verifyNoTelemetry --no-configuration-cache
./gradlew testDebugUnitTest
```

Windows PowerShell build command:

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
```

For release signing setup, see [`scripts/provision-release-signing.ps1`](scripts/provision-release-signing.ps1).

## Documentation Sitemap

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — System architecture, module boundaries, data-flow, threading.
- [`docs/DESIGN_ASSETS.md`](docs/DESIGN_ASSETS.md) — Design system tokens, color palettes, typography scale, component specs.
- [`docs/CHANGELOG.md`](docs/CHANGELOG.md) — Detailed milestone log.
- [`docs/RELEASE_BLOCKERS.md`](docs/RELEASE_BLOCKERS.md) — Production readiness and release gate verification.
- [`docs/TESTING.md`](docs/TESTING.md) — Test suite documentation and coverage targets.
- [`docs/SECURITY.md`](docs/SECURITY.md) — Security model and vulnerability disclosure policy.
- [`docs/PRIVACY.md`](docs/PRIVACY.md) — Offline-first privacy contract.

## License

Apache 2.0 License. Bundled third-party attributions are listed in [`THIRD_PARTY_NOTICES.txt`](app/src/main/assets/THIRD_PARTY_NOTICES.txt).
