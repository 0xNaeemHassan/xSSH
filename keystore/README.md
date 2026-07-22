# Release signing key

Never commit a `.jks` / `.keystore` signing key to this repository.

Generate it locally:

```bash
keytool -genkeypair \
  -keystore xssh-release.jks \
  -alias xssh \
  -keyalg EC -groupname secp256r1 \
  -validity 10000
```

Store it offline and back it up securely. For CI, base64-encode it and store it
in repository secrets — never in a workflow file:

```bash
base64 -w 0 xssh-release.jks
```

Release signing is a P0 release gate; see `docs/RELEASE_BLOCKERS.md`.

On Windows, the repository provisioner creates a non-overwriting EC release
key and records its random credentials in the ignored `local.properties` file:

```powershell
.\scripts\provision-release-signing.ps1
```

After provisioning, Android Studio and `gradlew :app:assembleRelease` produce a
signed release automatically. Back up both the generated keystore and the four
`xssh.signing.*` values from `local.properties` in a secure password manager.
