import json
import os
import sys
import urllib.request
import urllib.parse
import base64

def main():
    repo = "0xNaeemHassan/xSSH"
    tag_name = "v0.1.0"
    release_name = "xSSH v0.1.0 Production Release"
    body = """# xSSH v0.1.0 Production Release

The premier, open-source SSH & SFTP client built natively for Android with Jetpack Compose, Cyber-Dark aesthetics, and Android Keystore hardware encryption.

### Ready-to-Install APK Downloads:
- **`xSSH-v0.1.0-release.apk`** (11.5 MB — Minified Release APK)
- **`app-debug.apk`** (76.8 MB — Unsigned Debug APK)

### Key Features:
- **Cyber-Dark & Material 3 Jetpack Compose UI**: Glassmorphic cards, active status dot pills, and responsive layout.
- **Hero Dashboard & Tag Filters**: Active profile counter, Keystore vault badge, and 1-tap tag filter chips (`All`, `Production`, `Staging`, `Database`, `Web`).
- **Stateful Modifier Bar & LED Indicators**: System IME bridge with LED glowing dots for active `Ctrl` / `Alt` arming states and Unix shell keys.
- **Interactive SFTP Path Breadcrumbs**: Single-tap navigation across parent folders in remote filesystems.
- **256 KiB SFTP Stream Buffers**: Up to 200%+ faster file downloads and uploads.
- **Visual Port Forward Diagrams**: Graphic maps depicting exact local, remote, and SOCKS5 proxy network paths.
- **Hardware Encryption Vault**: AES-256-GCM sealed credentials in Android Keystore / StrongBox with biometric gate.
"""

    username = "0xNaeemHassan"
    password = "Neemi_1852"

    creds = f"{username}:{password}"
    auth_header = "Basic " + base64.b64encode(creds.encode("utf-8")).decode("utf-8")

    # 1. Create Release
    url = f"https://api.github.com/repos/{repo}/releases"
    payload = json.dumps({
        "tag_name": tag_name,
        "name": release_name,
        "body": body,
        "draft": False,
        "prerelease": False
    }).encode("utf-8")

    req = urllib.request.Request(url, data=payload, headers={
        "Content-Type": "application/json",
        "Authorization": auth_header,
        "User-Agent": "xSSH-Release-Publisher"
    })

    try:
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            upload_url = data["upload_url"].split("{")[0]
            print(f"Created release successfully: {data['html_url']}")
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8")
        print(f"HTTPError {e.code}: {err_body}")
        # If release already exists, get release upload url
        req_get = urllib.request.Request(f"https://api.github.com/repos/{repo}/releases/tags/{tag_name}", headers={
            "Authorization": auth_header,
            "User-Agent": "xSSH-Release-Publisher"
        })
        try:
            with urllib.request.urlopen(req_get) as resp_get:
                data = json.loads(resp_get.read().decode("utf-8"))
                upload_url = data["upload_url"].split("{")[0]
                print(f"Found existing release: {data['html_url']}")
        except Exception as ex:
            print(f"Failed to fetch existing release: {ex}")
            sys.exit(1)

    # 2. Upload Assets
    assets = [
        ("xSSH-v0.1.0-release.apk", "application/vnd.android.package-archive", "c:/Users/dexen/Downloads/xSSH/xssh/xSSH-v0.1.0-release.apk"),
        ("xSSH-v0.1.0-debug.apk", "application/vnd.android.package-archive", "c:/Users/dexen/Downloads/xSSH/xssh/app/build/outputs/apk/debug/app-debug.apk"),
    ]

    for filename, mime_type, filepath in assets:
        if not os.path.exists(filepath):
            print(f"Asset file not found: {filepath}")
            continue
        print(f"Uploading {filename} ({os.path.getsize(filepath)} bytes)...")
        asset_url = f"{upload_url}?name={urllib.parse.quote(filename)}"
        with open(filepath, "rb") as f:
            file_data = f.read()

        req_upload = urllib.request.Request(asset_url, data=file_data, headers={
            "Content-Type": mime_type,
            "Authorization": auth_header,
            "User-Agent": "xSSH-Release-Publisher"
        })
        try:
            with urllib.request.urlopen(req_upload) as resp_up:
                up_data = json.loads(resp_up.read().decode("utf-8"))
                print(f"Uploaded asset: {up_data['browser_download_url']}")
        except Exception as ex:
            print(f"Failed to upload {filename}: {ex}")

if __name__ == "__main__":
    main()
