# Testing

Every non-UI module ships JVM unit tests. The tests are the primary safety
net for the mapper, tunnel model, host-key policy, controller arithmetic,
and crypto helpers; they do not (and cannot) replace real-device verification
for the terminal engine, the foreground service, or the SFTP transfers, all
of which are on the release-blocker checklist for physical-device testing.

## Running

```bash
./gradlew testDebugUnitTest  # all modules
./gradlew :core-ssh:testDebugUnitTest  # one module
./gradlew :feature-tunnels:testDebugUnitTest --tests "TunnelRepositoryTest"
```

CI runs the same task on every pull request.

## What is tested where

### `:core-ssh`

- **`HostKeyPolicyTest`** — unknown-host TOFU requires explicit accept and
persists on success; changed host key returns false and emits
`VerificationEvent.Changed`. Runs against ephemeral RSA keypairs.
- **`PortForwarderTest`** — `Tunnel(kind = LOCAL/REMOTE, destHost = null)`
throws `IllegalArgumentException` at construction and DYNAMIC forbids a
destination.
- **`SecureConfigTest`** — the configured cipher, MAC, KEX, and host-key
factories match the strict allowlists and per-profile removals cannot enable a
legacy algorithm.
- **Agent auth coverage** — the repository and migration tests now exercise
Agent-mode persistence and profile import/export semantics; the final end-to-end
auth handshake still requires device/container validation from the release pack.

### `:core-crypto`

- **`KeyMaterialTest`** — Ed25519 and ECDSA P-256 keypair generation both
succeed; `fingerprintSha256` produces the `SHA256:` prefix; the OpenSSH
`authorized_keys` line for an Ed25519 key starts with `ssh-ed25519 `
and ends with the caller-supplied comment. Installs `CryptoBootstrap`
once in a `@BeforeClass` because BouncyCastle must be present.

### `:core-terminal`

- **`SpecialKeyTest`** — every `SpecialKey` value emits the exact
RFC/xterm byte sequence (Esc `0x1B`, arrows `ESC [ A/B/C/D`, PgUp/PgDn
`ESC [ 5 ~ / 6 ~`); modifier toggles emit no bytes.

### `:feature-connections`

- **`ConnectionRepositoryTest`** — coverage for mapper round-trip for every
`AuthMethod`, seal-on-upsert (the plaintext never reaches Room),
preserve-existing-secret-on-partial-update (regression guard), Agent-mode key
persistence, decrypt round-trip, and delete. `SecretVault` is stubbed with a
deterministic identity-plus-prefix transform via mockk so the assertion
actually shows ciphertext ≠ plaintext.
- **`ProfileTransferCodecTest`** — bundle round-trip plus OpenSSH/JuiceSSH-style
config parsing, including Agent auth hints and wildcard alias filtering.

### `:feature-tunnels`

- **`TunnelRepositoryTest`** — 7 cases covering LOCAL / REMOTE / DYNAMIC
entity round-trip, `observeAll` and `observeForConnection` flow
behaviour, delete. Uses a `MutableStateFlow`-backed in-memory DAO
fake that mirrors the Room-generated `ORDER BY bindPort` semantics.

### `:feature-snippets`

- **`SnippetsViewModelTest`** — 5 cases covering blank-label guard,
save/delete propagation through the DAO, UUID uniqueness for `blank()`,
case-insensitive ordering matching the `ORDER BY label COLLATE NOCASE`
clause. Uses `Dispatchers.setMain(UnconfinedTestDispatcher)` so
`viewModelScope` executes synchronously.

### `:feature-session`

- **`BackgroundActivityControllerTest`** — 8 cases covering counter
arithmetic, negative-bump clamping (never below zero), promote/demote
decision, `refresh()` idempotence, `reset()` teardown, and a 100×
bump-up/down stress run. `ServiceLauncher` is faked with a plain
string-recording implementation so we assert the exact call sequence
without Robolectric.

## Style guide for adding tests

1. **Prefer hand-rolled fakes over mockk for small boundaries.**
The DAO interfaces are three methods each — a `MutableStateFlow`-backed
fake is more readable than mockk's argument-captor gymnastics, and it
documents the contract you are supposed to preserve.

2. **Use mockk only when the collaborator is genuinely opaque.**
The one place we lean on mockk is `SecretVault`, which is a final
Kotlin class talking to the Android Keystore. mockk-mock-final works
because mockk registers as a JVM agent by default.

3. **Use turbine for order-sensitive Flow assertions.**
`Flow.first()` is fine for a single-value check; anything that inspects
emissions across time reads much cleaner with `.test { … }` because
`awaitItem()` and `cancelAndIgnoreRemainingEvents()` make the intent
explicit.

4. **Use `runTest(dispatcher)` when the subject uses `viewModelScope`
or its own coroutine.**
Pairing `Dispatchers.setMain(UnconfinedTestDispatcher())` with
`runTest(dispatcher)` guarantees every launched coroutine has completed
before the assertion runs — no `.await()` sprinkled in production
code just for tests.

5. **Name tests with backticks and full sentences.**
`` @Test fun `LOCAL record round-trips through the repo without drift`() ``
reads unambiguously in the JUnit report and in `--tests` filters. This
is our house style; keep it.

6. **One behaviour per test, one Truth chain per assertion.**
Multiple `assertThat` calls in one test are fine when they're checking
different facets of the same result; if you find yourself repeating the
setup, split the test.

7. **Test the guard, not the code path.**
`save with a blank label is silently dropped` proves the guard survives
a refactor; `save calls dao.upsert once` proves the current implementation
works but breaks the moment someone renames `upsert`. Prefer the former.

## Instrumented tests

Instrumented (`androidTest`) coverage is currently minimal — the
`:app` module declares `androidx.test.junit` and `espresso-core` as
`androidTestImplementation` but no scenarios are wired yet. Adding the
following is on the P0 release-blocker list:

- Terminal correctness on real hardware against `vim`, `tmux`, and `htop`.
- SFTP transfer under `mitmproxy` conditions (Wi-Fi drop mid-transfer,
captive portal, slow-link).
- Host-key TOFU end-to-end against an OpenSSH-in-Docker rig.

Those live in the release-blocker doc and the dedicated pack:
[`RELEASE_VALIDATION_CHECKLIST.md`](RELEASE_VALIDATION_CHECKLIST.md),
[`DEVICE_TEST_MATRIX_API31_33_35_36.md`](DEVICE_TEST_MATRIX_API31_33_35_36.md), and
[`TEST_EVIDENCE_TEMPLATE.md`](TEST_EVIDENCE_TEMPLATE.md), because they are gate
criteria for shipping v0.1 rather than day-to-day contributor tests.
