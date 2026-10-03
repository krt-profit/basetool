# ADR-0242 — Page patterns A–C and the design-system update 2026-10

- **Status:** Accepted — owner decision 2026-10-03 (design hand-off „Website-Überarbeitung", website
  audit of all 90 templates, changes DS-1 to DS-12).
- **Date:** 2026-10-03
- **Deciders:** @greluc
- **Related:** spec [`ui-design-system.md`](../specs/ui-design-system.md) (`REQ-UI-027`, `REQ-UI-009`,
  `REQ-UI-006`, `REQ-UI-004`) · [ADR-0212](0212-every-stylesheet-sits-in-a-cascade-layer.md) (cascade
  layers) · [ADR-0240](0240-navigation-is-a-structured-drawer-a-quick-access-and-a-phone-tab-bar.md)
  (the navigation, built before this) · krt-profit/design-system#6

## Context

The website audit of 2026-10-03 went through every template of the web frontend. The same faults
came back on almost every page: a `.greeting` banner with a description that repeats the title, the
whole page wrapped in `.hud-box`es (often one inside another), up to twelve filled orange buttons on
one page, lists as wide tables that scroll sideways on a phone, raw enum values as status, empty
lists as a `colspan` cell, filters behind a collapsed panel with a „Filtern" button, forms as one long
column, and about 600 generated one-off `krtm-*` classes. Body text in Lato 300 read too thin on black
at table and phone sizes, every heading level was orange and competed with the one primary action,
and the stylesheets used fifteen different breakpoints.

## Decision

1. **The design system takes twelve changes, DS-1 to DS-12**, in `krt-profit/design-system` first and
   with the same selectors in `styles.css`'s `components` layer:
   - type: body text Lato **400**, `.lead` for 300; only `h1` is orange, `h2`/`h3` white, `h4`–`h6`
     Grau 1; muted text always `--color-gray-2-text`;
   - new building blocks: `.page-head` with `.overflow-menu`, `.toolbar` with `.filter-chips`,
     `.segmented` and `.switch`, the list `.data-table` (rows as links, `--stack` on phones), the
     `.form-layout`, and the layout primitives `.stack` / `.cluster` / `.split` / `.grid-auto`;
   - rules: at most one `.hud-box` per view, never nested; content never on bare black; breakpoints
     only 768 / 1024 / 1440 px.
2. **Every work page follows one of three patterns** (`REQ-UI-027`): **A** list, **B** overview (the
   home page), **C** form. Pages that are none of these (master-detail, matrix, detail with tabs) use
   the same page head, toolbar and table.
3. **The patterns are fragments**, not copied markup: `fragments/page-head :: pageHead` and, in
   `fragments/components`, `emptyState`, `toolbarSearch`, `segmented`, `overflowMenu` and
   `filterChips`, with two global scripts — `krt-overflow-menu.js` (toggle, Escape, outside click,
   focus return, arrow keys) and `krt-filter-chips.js` (chips rendered from the filter form's state;
   removing one clears its control and fires the page's own live filter).
4. **The breakpoint rule is enforced**: Stylelint's `media-feature-name-value-allowed-list` allows
   only `768px`, `1024px` and `1440px` for `width` in both configurations. A component that has to
   react to its own width uses a container query instead of a new breakpoint.
5. **The rollout is phased**, each phase its own PR: the design system and the fragments (phase 0),
   the list pages (1), the home page and the forms (2), the areas F–M of the audit (3), removing
   `inline-migration.css` (4), and the extended layout gate (5).

## Consequences

- DS-2 and DS-3 change the look of **every** page at once, migrated or not; no class was removed or
  renamed, so nothing breaks.
- The outlier breakpoints moved to the next device-class boundary (480/560/620/720/760 → 768,
  820/880/900/980 → 1024, 1600/1800 → 1440); the master-detail layouts, the Materialbörse and the
  blueprint browser switch to a single pane at 1024 px instead of 900 px, and the blueprint script's
  `matchMedia` moved with them.
- The hand-off's stacked table put every meta cell on its own line on phones, which is not what its
  own screenshot shows; the stacked row is a wrapping flex row (title and status, then one meta line)
  in both the design system and the product.
- New code may not add a `krtm-*` class; every touched template replaces its own.

## Alternatives considered

- **Restyling the existing classes** (`.greeting`, `.hud-box`) in place: would change every page
  without fixing the structure (nested boxes, CTA count, sideways tables). Rejected.
- **A JavaScript table component** for the phone layout: the markup stays a `<table>` and CSS stacks
  it, so the server-rendered page and the screen-reader table semantics stay as they are. Rejected.
- **Button segments with a script** for the segmented control: radios inside labels are form values
  without any script, keep the existing form names, and get arrow-key navigation from the browser.
  Rejected in favour of radios.
- **Copying the hand-off's CSS with its comments**: new code carries no comments (ADR-0214); the
  explanations moved into the design system's README. Rejected.
