# ADR-0294 — The app wears the measured website palette

- **Status:** Accepted
- **Date:** 2026-10-19
- **Deciders:** @greluc
- **Related:** spec [`ui-design-system.md`](../specs/ui-design-system.md) (REQ-UI-002) ·
  [ADR-0292](0292-the-app-wears-the-website-palette-and-a-4px-radius.md) ·
  [ADR-0259](0259-the-design-system-submodule-follows-its-main.md)

## Context

The palette of ADR-0292 was read from screenshots taken with the Windows Snipping Tool while HDR was
active. HDR lifts dark grays and the orange, so every value was too light, and `#FB8A28` was in truth
the house orange `#E77E23` of the Corporate Design Manual. The app looked lighter and milkier than
das-kartell.org.

## Decision

1. **The palette takes the values measured in the browser** (`getComputedStyle` and the DevTools
   eyedropper) on three das-kartell.org pages: the forum, the start page and the academy.
   Colours are measured in the browser, never from screenshots.

   | Token | Was | Now | Measured on |
   | :-- | :-- | :-- | :-- |
   | `--color-primary` | `#FB8A28` | `#E77E23` | the "Antworten" button |
   | `--color-canvas` | `#202020` | `#0F0F0F` | `body` |
   | `--color-surface-input` (bar) | `#2A2D31` | `#1A1D21` | `#pageHeaderContainer` |
   | `--color-gray-4` / `--color-bg-dark-gray` (panel) | `#35393D` | `#282C30` | panels, breadcrumb |
   | `--color-control` | `#3F474C` | `#373E43` | the website buttons |
   | `--color-control-hover` | `#4A535A` | `#434B51` | derived from the control |
   | `--color-gray-3` (divider) | `#404145` | `#36373B` | `div.section` borders |
   | `--color-count-badge` | `#374256` | `#2F394C` | the academy |
   | `--color-gray-1` (text) | `#DADBDE` | `#D1D2D3` | body text |
   | `--color-gray-2-text` (muted) | `#B0B3B7` | `#A2A5A9` | secondary text |
   | `--control-selected-bg` | `rgba(251,138,40,.18)` | `rgba(231,126,35,.26)` | the site's active tint |

   The website's own muted text `#8A8C8F` reaches only 4.2:1 on the panel and fails AA, so the muted
   text takes `#A2A5A9`. Glows and every other orange tint keep their alpha on the new orange.
2. **The original logos and icons in `#E77E23` are restored.**
3. **Unchanged:** `--color-gray-2` `#8F9399`, the light and dark accent, the status text colours, the
   radii, the component rules and the department colours.

## Consequences

- Supersedes the colour values of ADR-0292 only. Its 4 px radius, the filled controls and tags and
  the mirroring of the tokens in the `:root` of `styles.css` stand.
- Text on orange stays dark; small orange text on a control fill uses `--color-accent-light`.
- The colour copies named in ADR-0292 (Keycloak theme, `krt-docs.css`, `maintenance.html`,
  `KrtPdfSupport`, the PWA `theme-color` and manifest `background_color`) move to the new values in
  the same change.
