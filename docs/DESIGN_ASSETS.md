# xSSH visual assets

The following generated assets are included in `docs/images/` and are intended
as **editable visual direction**, not proof of a final product UI:

| Asset | Use |
|---|---|
| `xssh-icon.png` | App icon concept, social avatar, README mark |
| `xssh-hero.png` | README / website hero banner |
| `xssh-app-screens.png` | Play/F-Droid feature graphic concept and UI direction |
| `xssh-architecture.png` | Architecture-document illustration |

## Adaptive launcher icon (in-tree)

Since checkpoint 12 the repo also ships a real, ready-to-build adaptive
launcher icon:

- `app/src/main/res/mipmap-anydpi/ic_launcher.xml` + `ic_launcher_round.xml`
- `app/src/main/res/drawable/ic_launcher_foreground.xml` — vector `>_`
  terminal-prompt glyph in the accent green
- `app/src/main/res/values/ic_launcher_background.xml` — solid `#0B0F14` ink
- `<monochrome>` variant so Android 13+ themed-icons render cleanly

The store-quality asset export (feature graphic, 512×512 hi-res icon, real
device screenshots from a signed build) is still on the production-asset
checklist below.

## In-app visual language

- **Mood:** dark-first developer tool, calm rather than neon.
- **Fallback palette:** background `#0B0F14`, surface `#11161D`, cyan accent
  `#5AC8FA`, violet secondary `#8E97F0`, error `#FF6B6B`.
- **Dynamic color:** Android 12+ should use Material You by default; retain the
  fallback palette for users who opt out.
- **Typography:** Material 3 for application UI; monospace for terminal only.
- **Motion:** 180–220 ms route transitions; respect Android animator-duration
  scale; no animation inside terminal rendering.
- **Touch:** modifier chips must be at least 48 dp tall and scroll horizontally.

## Production asset checklist

Generated rasters should be replaced/approved before store release:

- [x] Adaptive launcher icon foreground/background/monochrome layers shipped
      in-tree.
- [ ] Export a 512×512 hi-res launcher icon PNG for Play Store metadata.
- [ ] Produce a 1024×500 Play feature graphic and at least 2 real device
      screenshots from a signed release build.
- [ ] Confirm no generated text is relied on for legal claims or UI labels.
- [ ] Add attribution/license notes if non-original assets enter the project.
