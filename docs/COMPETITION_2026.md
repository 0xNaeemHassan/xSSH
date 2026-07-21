# xSSH competitive brief — Android SSH clients in 2026

> **Purpose:** product requirements, not marketing claims. Features marked
> `ship gate` are required before xSSH can legitimately claim parity or
> superiority in that category.

## Evidence snapshot

### Termius

Termius is the polished mainstream cross-platform client. Its public product
page emphasizes one-tap access and cross-device sync, while its Android listing
positions it as a full SSH client and terminal. It is proprietary and its most
valuable workflow features are tied to a paid account / cloud product.

**xSSH response:** native Material You UI, zero account requirement, local-first
connection vault, no telemetry, open-source build and reproducible releases.

### JuiceSSH

Community reporting in late 2025 says JuiceSSH had not been updated since 2021,
its website and license validation infrastructure went down, and paid users lost
Pro-only functions such as port forwarding, shortcuts, and sync. This is the
clearest warning against server-validated ownership for a local SSH client.

**xSSH response:** every offline core feature is in the APK: no license server,
no subscription check, no remote entitlement gate. Import compatibility is a
ship-gate feature so stranded users can migrate.

### ConnectBot

ConnectBot remains a respected open-source option. It is known for reliability,
key import, host management, and agent forwarding. Its recurring weakness is a
dated visual and interaction model.

**xSSH response:** retain the trust model and core capabilities; deliver
Material 3/Material You, accessible touch targets, command palette, a real
system keyboard path, and a first-class file browser.

### Termux

Termux is the reference Android terminal environment. It is a powerful local
Linux/terminal experience rather than a focused SSH connection manager.

**xSSH response:** reuse a mature terminal-emulation engine where licensing
allows, while designing the application around remote-host workflows: profiles,
known-host trust, keys, SFTP, tunnel lifecycle, and reconnect behavior.

## Feature comparison: product target

| Capability | xSSH target | Termius | JuiceSSH (legacy) | ConnectBot | Termux |
|---|---:|---:|---:|---:|---:|
| Account-free core SSH | **Yes** | Partial | Yes | Yes | Yes |
| No subscription or entitlement server | **Yes** | No | No (historic failure) | Yes | Yes |
| Open-source client | **Yes** | No | No | Yes | Yes |
| System IME, not a custom keyboard | **Yes** | Yes | Mixed UX | Yes | Yes |
| Modern Material You UI | **Yes** | Yes | No | No | No |
| Strict host-key change refusal | **Yes** | Required | Required | Required | Manual |
| Hardware/Keystore-backed secret vault | **Yes** | Unknown/proprietary | Legacy | Limited | Manual |
| SFTP browser + SAF transfers | **Yes** | Yes | Pro legacy | Limited | CLI |
| Local / remote / SOCKS tunnels | **Yes** | Paid tier | Pro legacy | Yes | CLI |
| Agent forwarding | **Not yet** | Tier-dependent | Yes | Yes | CLI |
| Offline-first profiles | **Yes** | Not default | Yes | Yes | Yes |
| No analytics SDKs | **Yes, CI-enforced** | Unknown | Unknown | Yes | Yes |

## Non-negotiable ship gates

xSSH must not claim to beat any of the above until all of these pass on physical
Android 12–15 devices:

1. **Terminal correctness** — xterm-256color, Unicode/wide glyphs, resize,
   selection/copy/paste, system IME, Bluetooth/USB keyboard, and 5,000-line
   scrollback.
2. **SSH correctness** — Ed25519 + ECDSA + RSA keys; modern KEX/ciphers; strict
   TOFU acceptance; refusal on key mismatch; keyboard-interactive auth.
3. **Persistence** — encrypted Room/DataStore implementation, migration tests,
   zero plaintext secrets, account-free export/import of *non-secret* profiles.
4. **SFTP** — list, upload, download, rename, delete, mkdir, edit-to-temp,
   cancellable transfer queue and SAF URI handling.
5. **Tunnels** — tested local, remote and SOCKS5 dynamic forwarding with explicit
   loopback default binding and lifecycle visibility.
6. **Resilience** — foreground notification for live sessions, explicit
   reconnect UX, network-change test suite, no hidden background retry storm.
7. **Security release process** — signed release, SBOM/dependency scan,
   `verifyNoTelemetry`, reproducible build instructions, SECURITY.md contact.

## Sources

- Termius product site: <https://termius.com/>
- Termius Android listing: <https://play.google.com/store/apps/details?id=com.server.auditor.ssh.client&hl=en_US>
- JuiceSSH discontinuation / license-server community report: <https://www.reddit.com/r/androidapps/comments/1pqzq46/juice_ssh_delisted_december_2025/>
- ConnectBot comparison and agent-forwarding discussion: <https://blog.cdnsun.com/why-connectbot-is-the-best-client-ssh-for-android-comparison-with-juicessh-and-termius/>
- Termux app repository: <https://github.com/termux/termux-app>
- Termux library distribution notes: <https://github.com/termux/termux-app/wiki/Termux-Libraries>
- sshj Android X25519 issue: <https://github.com/hierynomus/sshj/issues/905>
