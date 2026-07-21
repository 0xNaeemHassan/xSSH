# Privacy contract

Short version: xSSH connects your Android device to the SSH servers you
choose. Nothing else. Read on for exactly what that means.

## What xSSH sends over the network

Only outbound SSH sessions that **you** initiate to a server **you** configured.
That's it.

We do not:

- Contact any xSSH-operated backend on launch, install, or update.
- Send crash reports or performance metrics to any endpoint.
- Ping an analytics or A/B testing service.
- Validate a license, subscription, or account with any server.
- Load ad SDKs or fetch ad inventory.

xSSH has no account system. There is nothing to log in to.

## Permissions declared and why

| Permission | Reason |
|---|---|
| `INTERNET` | Open TCP sockets to SSH servers. |
| `POST_NOTIFICATIONS` | Show the ongoing "N active sessions • M tunnels" foreground notification on Android 13+. Requested at runtime; declining does not disable the service. |
| `USE_BIOMETRIC` | Optional pre-connect confirmation using a strong biometric or the device credential. This is a UI gate; the Keystore vault key is not biometric-bound. |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` + `FOREGROUND_SERVICE_SPECIAL_USE` | Keep user-initiated SSH sessions and tunnels visible while the app is backgrounded. Android 14+ requires the declared special-use subtype. |

xSSH does **not** request `ACCESS_FINE_LOCATION`, `READ_CONTACTS`,
`READ_PHONE_STATE`, `QUERY_ALL_PACKAGES`, `READ_EXTERNAL_STORAGE`,
`WRITE_EXTERNAL_STORAGE`, or any other broad-access permission. SFTP file
access goes through the Android **Storage Access Framework** so you pick each
file or destination explicitly.

AndroidX Biometric contributes the legacy `USE_FINGERPRINT` compatibility
permission, and AndroidX Core contributes an app-signature-scoped dynamic
receiver permission. Neither grants access to user data or other apps.

## What is stored on disk

Room database at the app's private data directory:

- Connection profiles (name, host, port, username, options, tags).
- Sealed credential blobs (encrypted with an Android Keystore / StrongBox
key that cannot be exported).
- Known-hosts records (host, port, SHA-256 fingerprint, key type, timestamp).
- Tunnel definitions (kind, bind host + port, dest host + port, auto-start
flag).
- Command snippets (label, body, optional execute-on-paste flag).

Never on disk:

- Plaintext passwords, private keys, or private-key passphrases.
- Terminal output, session transcripts, or command history.
- Analytics events, click-through logs, or performance samples.

## What is not backed up

`res/xml/data_extraction_rules.xml` explicitly excludes every domain from
both **cloud backup** and **device-transfer**:

```xml
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="root" />
        <exclude domain="database" />
        <exclude domain="sharedpref" />
        <exclude domain="file" />
    </cloud-backup>
    <device-transfer>
        <exclude domain="root" />
        <exclude domain="database" />
        <exclude domain="sharedpref" />
        <exclude domain="file" />
    </device-transfer>
</data-extraction-rules>
```

`android:allowBackup="false"` in the manifest is the belt-and-braces.
Combined, your credentials never leave the device via Google's backup or a
phone-to-phone transfer.

## Clipboard, screenshots, task previews

- The terminal never auto-copies output to the system clipboard. Selection
is user-initiated only.
- The single Activity sets `FLAG_SECURE`, so the terminal is blanked out in
screenshots and in the task-switcher preview.

## Logging

xSSH installs no application logging backend in debug or release builds. The
SSH transport depends only on the SLF4J API and no logger implementation is
packaged. Secrets are never intentionally written to Logcat.

## Third-party libraries

Every library on the classpath is:

- Open source under an OSI-approved license (Apache 2.0, LGPL, BSD, or MIT).
- Reviewed and pinned in `gradle/libs.versions.toml`, with artifact checksums
recorded in Gradle dependency-verification metadata.
- Scanned by the GitHub `dependency-review-action` on every pull request.
- Not on the telemetry-banned list enforced by `verifyNoTelemetry`.

Full list of currently pinned dependencies lives in `gradle/libs.versions.toml`.

## Auditing xSSH

Because this contract is code, not marketing:

- Grep the source for `com.google.firebase`, `analytics`, `crashlytics`,
`sentry`, `amplitude`, `mixpanel`, `segment`, `bugsnag`, `datadog`,
`appsflyer`, `branch`, `facebook-android-sdk` — you will find hits only in
`build.gradle.kts` at the root, in the `bannedTelemetryPrefixes` list.
- Run `./gradlew verifyNoTelemetry --no-configuration-cache`. This is also
CI-enforced; the flag is required because the task inspects every project's
resolved graph at execution time.
- Run `./gradlew generateSbomLite generateLicenseReportLite --no-configuration-cache`
and compare the
resolved inventory against the pinned catalog.
- On a device, take a `mitmproxy` capture of xSSH's traffic. You should see
only your own SSH sessions and no other outbound connection at any point.

## Changes to this document

Any material change to what xSSH stores or transmits requires a PR to this
file **and** a matching entry in `docs/CHANGELOG.md`, so the git history is
the authoritative log of the privacy contract.
