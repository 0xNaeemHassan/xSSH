# Device test matrix pack — API 31 / 33 / 35 / 36

Record one line per device run. A single release candidate is not complete until
all rows marked **required** have evidence attached.

## Device inventory

| API | Required | Device | Build fingerprint | Keyboard coverage | Notes |
|---|---|---|---|---|---|
| 31 | yes |  |  | Gboard + hardware |  |
| 33 | yes |  |  | Gboard + Samsung/Gboard equivalent + hardware |  |
| 35 | yes |  |  | Gboard + hardware |  |
| 36 | yes |  |  | Gboard + hardware | 16 KiB-page device preferred |

## Core smoke matrix

| Area | Scenario | API 31 | API 33 | API 35 | API 36 | Evidence |
|---|---|---:|---:|---:|---:|---|
| Install | fresh install succeeds |  |  |  |  |  |
| Launch | cold start to connection list |  |  |  |  |  |
| Connection CRUD | create/edit/delete/search |  |  |  |  |  |
| Password auth | connect with stored password |  |  |  |  |  |
| Public-key auth | connect with imported key |  |  |  |  |  |
| Agent auth | connect with Agent mode profile |  |  |  |  |  |
| Keyboard-interactive | prompt dialog round-trip |  |  |  |  |  |
| Host trust | unknown-host prompt |  |  |  |  |  |
| Host trust | changed-key refusal |  |  |  |  |  |
| Biometric gate | required before connect |  |  |  |  |  |
| Terminal | vim basic editing |  |  |  |  |  |
| Terminal | tmux attach/new-window |  |  |  |  |  |
| Terminal | truecolor sample |  |  |  |  |  |
| Modifier bar | Esc/Ctrl/Alt/Tab/arrows |  |  |  |  |  |
| Notification | foreground status accuracy |  |  |  |  |  |
| SFTP | upload/download progress |  |  |  |  |  |
| SFTP | quick-edit UTF-8 file |  |  |  |  |  |
| Tunnels | LOCAL |  |  |  |  |  |
| Tunnels | REMOTE |  |  |  |  |  |
| Tunnels | DYNAMIC SOCKS5 |  |  |  |  |  |
| Migration | export xSSH bundle |  |  |  |  |  |
| Migration | import xSSH bundle |  |  |  |  |  |
| Migration | import OpenSSH/JuiceSSH config |  |  |  |  |  |

## Network-behaviour matrix

| Scenario | Expected result | API 31 | API 33 | API 35 | API 36 | Evidence |
|---|---|---:|---:|---:|---:|---|
| Wi-Fi stable | session remains usable |  |  |  |  |  |
| Wi-Fi → LTE handoff | disconnect surfaced cleanly or recovers predictably |  |  |  |  |  |
| Captive portal / blocked network | connect fails quickly with actionable error |  |  |  |  |  |
| Doze / screen off for 5 min | foreground session/tunnel remains honest |  |  |  |  |  |

## Secret-handling spot checks

| Check | Expected result | API 31 | API 33 | API 35 | API 36 | Evidence |
|---|---|---:|---:|---:|---:|---|
| Room DB inspection | no plaintext password/private key |  |  |  |  |  |
| SharedPreferences/DataStore | no plaintext secrets |  |  |  |  |  |
| Export bundle JSON | no secret material present |  |  |  |  |  |
| OpenSSH export text | no secret material present |  |  |  |  |  |
| Logs/logcat | no secret leakage |  |  |  |  |  |

## Pass criteria

- Every **required** device row is filled.
- No unchecked P0 blocker remains.
- Any failure has a linked issue and a conscious ship/no-ship decision.
