# ADR-0378 — Navigation and list surfaces have distinct edges

- **Status:** Accepted
- **Date:** 2026-11-13
- **Deciders:** @greluc
- **Related:** [ADR-0371](0371-the-basetool-follows-the-websites-design-3-0.md) ·
  [UI design system](../specs/ui-design-system.md) (REQ-UI-007, REQ-UI-013, REQ-UI-028)

## Context

The Lager overview placed a rounded notice immediately under a square tab strip. Its Material /
Items selector formed another full-width strip whose ends met the rounded table frame. The same
components compose pages, detail views and dialogs across the Basetool.

## Decision

1. Keep full-width tabs square for navigation between page or detail sections. Leave
   `--space-4` below a tab strip, counting any equivalent content inset already supplied by the
   layout. Bars within the same chrome may share an edge.
2. Use the shared segmented control for local choices such as Material / Items. Place it in the
   list toolbar so it stays next to the results it controls.
3. Use one rounded outer frame for the toolbar, filters, results and pagination. The toolbar has
   an inset and a hairline divider. The table head under it has straight corners. Content that
   reaches an outer corner takes the frame's inner radius.
4. Give standard dialog content the same `--space-4` inset below its title divider. Dedicated
   compositions such as the quick-access palette keep their own content layout. Lists in dialogs
   use the shared list frame.

## Consequences

The existing Design 3.0 tokens express the geometry of the composition. A page can insert or omit
a notice under its tabs without changing the separation from the first visible content. The
table and toolbar share a frame that lets popovers extend past it.

## Alternatives considered

- Round every tab strip. The top corners would match a panel. The components would still meet
  edge to edge and local switches would still look like page navigation.
- Add space above every table. This would separate the borders and visually detach the toolbar
  from the results it controls.
