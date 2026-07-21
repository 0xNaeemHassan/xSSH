# Contributing to xSSH

Thanks for wanting to help! A few ground rules.

## Non-negotiables

1. **No telemetry, ever.** No analytics, no crash reporters, no ads, no
   "just this one library" exceptions. The `verifyNoTelemetry` Gradle task
   fails the build if a banned dependency slips in — please don't circumvent it.
2. **No secrets on disk in plaintext.** All passwords and private keys go
   through `SecretVault` (`:core-crypto`).
3. **No legacy crypto by default.** `TransportOptions.disabledAlgorithms` is
   the source of truth — add to it, don't shrink it, unless you also open a
   security-review issue.

## Style

- `./gradlew ktlintFormat` before pushing.
- Detekt must be green.
- Public APIs get KDoc.
- Compose composables live under `com.xssh.feature.<name>` — one file per screen.

## Commit format

Conventional Commits, e.g.:

```
feat(session): honor Ctrl+key modifier bar toggles
fix(ssh): filter legacy DH group1 from server proposals
```

## PR checklist

- [ ] Unit tests where feasible
- [ ] `./gradlew ktlintCheck detekt verifyNoTelemetry` passes
- [ ] Manifest permissions unchanged (or PR explains why they must change)
- [ ] No new third-party dependencies without discussion in an issue
