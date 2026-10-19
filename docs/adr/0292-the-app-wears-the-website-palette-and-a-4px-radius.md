# ADR-0292 — The app wears the website palette and a 4 px radius

- **Status:** Accepted — the colour values are superseded by [ADR-0294](0294-the-app-wears-the-measured-website-palette.md)
- **Date:** 2026-10-19
- **Deciders:** @greluc
- **Related:** spec [`ui-design-system.md`](../specs/ui-design-system.md) (REQ-UI-002, REQ-UI-003,
  REQ-UI-006, REQ-UI-007, REQ-UI-019, REQ-UI-020) · [ADR-0259](0259-the-design-system-submodule-follows-its-main.md) ·
  design system `krt-profit/design-system` 2026-10 (design-system#9, design-system#10)

## Context

The Basetool stood on a black canvas with square corners and the house orange `#E77E23`, while the
organisation's website das-kartell.org uses dark grays, a 4 px radius and the orange `#FB8A28`. The
2026-10 design system moves to the website: canvas `#202020`, panel `#35393D`, bar `#2A2D31`,
control `#3F474C`, filled controls with a visible border, filled department tags, and every logo and
icon in the new orange. The department colours stay byte-identical.

The design system publishes its tokens as `tokens.json`, compiled to `tokens.css`, and its
components as `krt-components.css` or the stand-alone `components/bundle.css`.

## Decision

1. **The app adopts the 2026-10 palette, radius and component rules** exactly as `tokens.css` and
   `krt-components.css` define them. `--color-bg-black` stays `#000000` but is no surface: it is
   the text on orange and on the light department fills, and the base of shadows and scrims.
2. **The app mirrors the tokens in the `:root` of `kernel/styles.css`; it does not load
   `tokens.css` or `bundle.css`.** The submodule lives under `.claude/` and is not part of the
   frontend jar; the CSP allows only same-origin styles; and REQ-UI-024 needs every rule inside a
   cascade layer, which a copied `tokens.css` or `bundle.css` would break with unlayered rules
   above all layers. The Keycloak theme, the exchange docs, the maintenance page and the PDF
   palette mirror the same values for the same reason.
3. **Controls and surfaces take `--radius-sm` (4 px)**; pills, circles, table cells, accent bars
   and gauges keep their shape, and no rounded container sets `overflow: hidden`.
4. **Department tags are filled** in the department colour (`.dept-tag--<slug>`); a department hue
   is never small text on the gray surfaces.

## Consequences

- A token change in the design system needs the same change in `styles.css`, the Keycloak theme,
  `krt-docs.css`, `maintenance.html` and `KrtPdfSupport` in one change; `ColourTokenCopyTest`,
  `ItemClassChipContrastTest`, `AccessibleTextTintTest` and `WebAppManifestControllerTest` read the
  `:root` values and fail on a stale copy in the app.
- The item-class text tints were lightened in the design system so a chip label keeps 4.5:1 on the
  new panel and row hover.
- The PWA `theme-color` is the bar `#2a2d31`, the manifest `background_color` the canvas `#202020`.
