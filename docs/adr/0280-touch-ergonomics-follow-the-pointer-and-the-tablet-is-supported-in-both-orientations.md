# ADR-0280 — Touch ergonomics follow the pointer, and the tablet is supported in both orientations

- **Status:** Accepted
- **Date:** 2026-10-17
- **Deciders:** @greluc
- **Related:** spec `REQ-UI-009`, `REQ-UI-020` ([`ui-design-system.md`](../specs/ui-design-system.md)) ·
  [ADR-0172](0172-the-phone-class-gets-its-own-layout-contract.md) ·
  [ADR-0176](0176-layout-floors-are-defaults-that-controls-opt-out-of.md) ·
  [ADR-0242](0242-page-patterns-a-to-c-and-the-design-system-update-2026-10.md) ·
  `static/css/kernel/styles.css`, `TouchClassLayoutE2eTest`

## Context

REQ-UI-009 names four device classes by width, and the touch ergonomics — the 44px default floor,
the field padding, the checkbox and label sizing — sat in the `width <= 1024px` block. A tablet held
landscape is 1080 to 1366px wide. It therefore got the mouse layout's control sizes, and iOS zoomed
the page in whenever a field below 16px took focus. The owner decided that the tablet must be fully
usable held portrait and landscape, and that a phone needs to be usable held portrait only.

A walk through every page, modal and the drawer on the phone and the tablet also found defects the
width-based guard had no rule for: checkboxes and radios stretched to 44px slabs by the default
floor, fields below 16px that zoom iOS, a block segmented control whose labels overlapped, floating
elements over the phone tab bar, and an installed app whose safe-area insets all read `0`.

## Decision

1. **The touch ergonomics apply under `(width <= 1024px), (pointer: coarse)`.** A narrow window gets
   them by width, every touch screen by its input device, whatever its width. Layout — columns, the
   phone tab bar, the drawer, the footer behaviour — keeps following the width classes alone. DS-11's
   rule that breakpoints are only the class boundaries is about width and stays.
2. **Supported orientations:** the phone portrait, the tablet portrait and landscape. A landscape
   phone is not a target; nothing is done to break it, and nothing is measured for it.
3. **A checkbox or radio is a 24px box on touch; its label carries the 44px.** A label that wraps the
   control or follows it, and the label of a `.check-row`, is at least `--touch-target` tall. A
   checkbox with no label gets one, which may be textless (`.table-check`), since the guard counts a
   label as part of the hit area.
4. **A text-entry control renders at 16px or more on touch**, through one `!important` declaration
   in the touch block. Disabling the zoom with `maximum-scale` would also disable pinch-zoom on
   Android, which WCAG 1.4.4 forbids.
5. **The viewport is `viewport-fit=cover`** and the fixed chrome pads by the safe-area insets of the
   edges it touches; the status bar stays `black`.
6. **The guard measures a landscape tablet (1180×820) as a touch class** and emulates touch only on
   the touch classes, so the desktop classes are measured as a mouse sees them. It gains three rules:
   text entry below 16px on a touch class, a floating element over the phone tab bar, and the
   landscape-tablet class itself.

## Consequences

- A touch laptop wider than 1024px now gets the touch floors too. That is what REQ-UI-009 already
  asked for buttons, whose floor was unconditional, and it is the honest reading of "touch first".
- A mouse user in a desktop window narrower than 1024px keeps getting the touch sizes, as before.
- Page CSS that sizes a field below 16px is overridden on touch screens. A dense table input that
  needs to stay small on a touch screen cannot; that is the trade for iOS not zooming.
- A new checkbox without a label fails the guard on its own 24px box instead of passing on a
  44px-tall empty slab.
