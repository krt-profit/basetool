# ADR-0371 — The Basetool follows the website's Design 3.0

- **Status:** Accepted
- **Date:** 2026-11-11
- **Deciders:** @greluc (design decisions); the Organisationsleitung signed off Design 3.0
- **Related:** spec [`ui-design-system.md`](../specs/ui-design-system.md) (REQ-UI-002, REQ-UI-003,
  REQ-UI-006, REQ-UI-007, REQ-UI-020) ·
  [ADR-0292](0292-the-app-wears-the-website-palette-and-a-4px-radius.md) ·
  [ADR-0294](0294-the-app-wears-the-measured-website-palette.md) ·
  [ADR-0259](0259-the-design-system-submodule-follows-its-main.md) · design system
  `krt-profit/design-system` (design-system#22, design-system#23, design-system#24,
  design-system#25)

## Context

The Basetool wears the surfaces of das-kartell.org (ADR-0292, ADR-0294). The website moves to
Design 3.0 — the WoltLab style „Das Kartell - 3.0" with its individual CSS „Entwurf 4": blue-gray
surfaces, a lighter tile inside a box, a box title bar with a short orange mark, tinted labels and
rounded corners by role. The Organisationsleitung signed it off. The design system took the values
from the style's colour set (ACP) and its individual CSS, not from screenshots, and is merged at
`9e0c06f`.

Taken one to one, Design 3.0 would also drop things the Basetool depends on: its controls have no
visible frame, its labels tint every hue, its navigation is a menu bar.

## Decision

1. **The app takes the Design 3.0 surfaces, text colours and radii**, exactly as the design
   system's `tokens.json` holds them:

   | Token | 2.0 | 3.0 |
   | :-- | :-- | :-- |
   | `--color-canvas` | `#0F0F0F` | `#0D0F12` |
   | `--color-surface-input` (bar, sunken level) | `#1A1D21` | `#14171C` |
   | `--color-gray-4` / `--color-bg-dark-gray` (box) | `#282C30` | `#1A1E24` |
   | `--surface-card` (tile), `--surface-card-hover` | — | `#20252C`, `#272D35` |
   | `--color-control`, `--color-control-hover` | `#373E43`, `#434B51` | `#2F3640`, `#353C48` |
   | `--color-gray-3` (frame) | `#36373B` | `#2A3038` |
   | `--color-gray-1` (text), `--color-gray-2-text` (muted) | `#D1D2D3`, `#A2A5A9` | `#D7DADE`, `#9AA1AB` |
   | `--color-title` (box title) | — | `#C9CED5` |
   | `--table-stripe-bg`; `--table-stripe-tile`, `--table-row-hover-tile` | control at 50 % | `#1E2329`; `#242930`, `#2C323A` |
   | `--radius-box`, `--radius-tile`, `--radius-control` | 4 px | 10 px, 8 px, 6 px |

   `--radius-sm` (4 px) stays for inner elements: chips, labels, badges.
2. **Surface ladder:** canvas → box → a lighter tile → a sunken third level on the bar colour.
   A level never takes the fill of its parent, and every level keeps the `#2A3038` frame, because
   the steps are only 1.06–1.17 : 1. Zebra and row hover follow the surface a table sits on.
3. **Components** follow the design system's `krt-components.css` in the rules they belong to:
   the radius list is split into box, tile, control and inner-element lists; flush-card tables take
   the box corners; the 3.0 title bar on `.card-head`, a leading `.card-title` and
   `.section-title`; `.status-pill` and `.status-badge` are tinted labels; alerts lose the 4 px
   left bar for a tinted frame.
4. **Deliberate departures from Design 3.0:**
   - controls keep the visible `#8F9399` border — the website's frameless controls reach 1.4 : 1,
     WCAG 1.4.11 needs 3 : 1;
   - department tags stay filled — Sub-Radar, Forschung and Marinekorps fall to 2.0–2.7 : 1 as
     tinted text;
   - tinted status labels use the existing text tints;
   - the HUD corner brackets stay on `.hud-box`, the modal and the toast, and follow the rounded
     corner;
   - the navigation stays as it is (no 3.0 menu bar, no header image), Lato stays the only
     typeface, and the department colours and the house orange `#E77E23` are unchanged.
5. **Every copy of the palette moves in the same change:** the Keycloak login and account themes,
   the PDF palette in `KrtPdfSupport`, the Exchange docs site (`krt-docs.css`, its `theme-color`),
   the maintenance page, the PWA `theme-color` and manifest `background_color`.

## Consequences

- Supersedes the colour values of ADR-0294 and the 4 px radius of ADR-0292. Filled controls and
  department tags (ADR-0292) and the browser- or style-sourced measurement of colours stand.
- A page's own tile inside a box takes `--surface-card` and `--radius-tile`; the bar colour is the
  sunken level (detail rows, progress tracks, a table inside a padded card).
- Small orange text on a control fill still takes `--color-accent-light` (4.3 : 1 on `#2F3640`).
- A rounded container never sets `overflow: hidden`, so the child that fills one of its corners
  takes the inner radius instead (REQ-UI-007): a flush card hands it to its table or scroll wrapper
  through `--flush-corner-top` / `--flush-corner-bottom`, and a master list to its first and last
  visible row (design-system#25).

## Alternatives considered

- **Take Design 3.0 unchanged** — rejected: frameless controls and tinted department tags fail
  WCAG, and the 3.0 menu bar would undo the navigation of ADR-0240.
- **Stay on the 2.0 palette** — rejected: the Basetool and the website are meant to read as one
  product, and the owner chose to follow the website.
