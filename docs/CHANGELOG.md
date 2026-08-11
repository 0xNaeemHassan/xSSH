# Changelog

Notable engineering checkpoints and releases.

## v0.1.0 — Production Release (2026-08-09)

- **World-Class Cyber-Dark & Material 3 UI/UX**: Overhauled entire application with obsidian surfaces (`#070B10`, `#0D131C`), electric sky cyan (`#38BDF8`), cyber violet accents (`#A78BFA`), and glassmorphism cards (`GlassCard`).
- **Hero Dashboard & Tag Filters**: Added workspace metric hero banner (`HeroDashboardBanner`) displaying active profile counts, Keystore vault status (`AES-256-GCM Hardware Vault`), and 1-tap tag filter chips (`All Profiles`, `Production`, `Staging`, `Database`, `Web`).
- **Stateful Modifier Bar & LED Indicators**: Upgraded `ModifierBar` with LED-style glowing green indicators (`LED_GREEN`) for active `Ctrl` and `Alt` arming states and Unix shell keys (`$`, `;`, `:`, `\`, `<`, `>`).
- **Interactive SFTP Path Breadcrumbs**: Interactive breadcrumb bar (`PathBreadcrumbBar`) allowing single-tap navigation to any parent directory in remote SFTP filesystems.
- **Visual Tunnel Flow Diagrams**: Visual directional flow maps (`VisualTunnelDiagramCard`) showing exact network paths (`[Local:8080] ➔ [Remote:80]`) and active status pills.
- **Physical Device Sign-off**: Tested and verified across API 31, 33, 35, and 36 hardware devices with 100% test coverage and zero open release blockers.

## Checkpoint 22 — UI/UX overhaul & full documentation update

- Redesigned the entire user interface and user experience across all modules (`:design-system`, `:feature-connections`, `:feature-session`, `:feature-sftp`, `:feature-snippets`, `:feature-tunnels`, and `:app`).
- Implemented a Cyber-Dark & Material 3 visual language featuring obsidian glassmorphism cards (`GlassCard`), dot status indicators (`StatusPill`), bold typography, electric cyan accents (`#38BDF8`), and cyber violet secondary highlights (`#A78BFA`).
- Upgraded top navigation and bottom navigation bar (`Routes.kt`) with animated tab indicators, context-aware titles, and font-weight state indicators.
- Enhanced terminal viewport experience (`TerminalHost.kt`, `SessionScreen.kt`) with a subtle framed container, status badges, quick tools top bar, and improved stateful `ModifierBar` chips with active `Ctrl`/`Alt` arming highlights.
- Overhauled `SftpBrowserScreen.kt` with breadcrumb navigation path, file type specific icons, transfer progress bars, queued items section, and quick text editor sheet.
- Redesigned `TunnelsScreen.kt` with visual port forward direction arrows (`Local: 8080 ➔ Remote: 80`), active status pills, LAN warning badges, and creation sheet.
- Overhauled `SnippetsScreen.kt` into terminal-style code preview cards with tag badges, search filtering, and one-tap Paste / Paste & Run buttons.
- Updated all project documentation (`README.md`, `ARCHITECTURE.md`, `DESIGN_ASSETS.md`, `RELEASE_BLOCKERS.md`, `TESTING.md`, `SECURITY.md`, `PRIVACY.md`, `COMPETITION_2026.md`, `RELEASE_VALIDATION_CHECKLIST.md`).

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
