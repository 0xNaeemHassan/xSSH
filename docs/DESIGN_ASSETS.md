# xSSH Visual Assets & Design Tokens

## Asset Directory Overview

The following visual assets live in `docs/images/`:

| Asset | Use |
|---|---|
| `xssh-icon.png` | App icon mark, avatar, README brand image |
| `xssh-hero.png` | Main README hero header banner |
| `xssh-app-screens.png` | Play Store / F-Droid feature graphics & UI mockups |
| `xssh-architecture.png` | System architecture diagram illustration |

## Adaptive Launcher Icon

Native Android adaptive launcher icon assets:

- `app/src/main/res/mipmap-anydpi/ic_launcher.xml` & `ic_launcher_round.xml`
- `app/src/main/res/drawable/ic_launcher_foreground.xml` — Vector terminal prompt glyph `>_`
- `app/src/main/res/values/ic_launcher_background.xml` — Obsidian dark `#070B10`
- Android 13+ `<monochrome>` themed icon support built-in

---

## Design System Tokens (`:design-system`)

### Color Palette

- **Primary Accent**: Electric Sky Cyan (`#38BDF8`)
- **Secondary Accent**: Cyber Violet (`#A78BFA`)
- **Tertiary Accent**: Mint Emerald (`#34D399`)
- **Background**: Cosmic Dark (`#070B10`)
- **Surface Container**: Obsidian Glass (`#0D131C`)
- **Surface Elevated**: Glassmorphic Elevation (`#141C28`)
- **Text High Contrast**: Slate Light (`#F1F5F9`)
- **Text Muted**: Slate Grey (`#94A3B8`)
- **Error Accent**: Neon Coral (`#F87171`)
- **Success Accent**: Emerald Glow (`#10B981`)
- **Warning Accent**: Amber Glow (`#F59E0B`)

### Component Standards

1. **`GlassCard`**:
   - Container Color: `#0D131C`
   - Border Stroke: `1.dp` solid `#334155` (alpha 0.5)
   - Shape: `RoundedCornerShape(16.dp)`

2. **`StatusPill`**:
   - Shape: `RoundedCornerShape(28.dp)`
   - Border: `1.dp` matching pill color at 25% opacity
   - Interior Dot: `6.dp` solid colored circle

3. **`ModifierBar`**:
   - Horizontal scrolling list of 48.dp target touch chips
   - Selected state: `PrimaryContainer` fill + bold typography

4. **Typography**:
   - Application UI: Material 3 Sans-Serif font hierarchy
   - Terminal & Code Snippets: Monospace font with crisp line height

5. **Motion Guidelines**:
   - Navigation Transitions: 180–220 ms ease-in-out
   - Recomposition Performance: Static list allocations, zero allocation loops inside rendering paths
