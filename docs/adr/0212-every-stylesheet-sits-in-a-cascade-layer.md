# ADR-0212 — Every stylesheet sits in a cascade layer

- **Status:** Accepted
- **Date:** 2026-09-23
- **Deciders:** @greluc (owner decisions 2026-09-23)
- **Requirement:** [REQ-UI-024](../specs/ui-design-system.md)
- **Related:** [ADR-0093](0093-eliminate-inline-style-attributes-csp-style-src-attr-none.md) (the
  migrated inline styles), [ADR-0176](0176-layout-floors-are-defaults-that-controls-opt-out-of.md)
  (the zero-specificity floors), [ADR-0177](0177-the-app-has-exactly-one-dialog-shape.md) (the
  dialog state classes)
- **Source:** improvement audit 2026-09-22, finding FE-MOD-02

## Context

The frontend's CSS settled conflicts between files by **load order**, and five things depended on
it. `styles.css` loaded first. A page's stylesheets came after it, in `extraLinks`, so they won ties
against the design system. `inline-migration.css` loaded last, so a migrated inline style won a tie
"like an inline style". Any rule that had to beat another file did so by that order, or by a
specificity bump written for the purpose: `main .form-group select`, `.btn.btn-xs2`,
`div.page-wrapper`, and a co-located `.x.krtm-hidden` next to any rule that set `display`. The specs
and the vault record each time one of those went wrong. `.krtm-hidden` at (0,1,0) lost to any
(0,2,0) rule that set `display`, and 29 `!important` declarations existed to win arguments that
order and specificity had lost.

## Decision

**Every stylesheet sits in one of five cascade layers, declared in this order at the top of every
file:**

```css
@layer base, components, page, migration, utilities;
```

| Layer | Holds |
| --- | --- |
| `base` | `@font-face` and the `:root` design tokens (`styles.css`) |
| `components` | the rest of `styles.css`: the design system's components and helpers |
| `page` | every page / area stylesheet (`bank.css`, `org-chart.css`, …, `css/pages/*.css`), and the few design-system declarations that must keep beating them |
| `migration` | `inline-migration.css`, one class per former inline `style="…"` |
| `utilities` | the two runtime state classes `krtm-hidden` and `krtm-modal-open` |

Between layers the order decides, before specificity. Inside a layer, specificity and source order
decide as before.

- **The state classes win outright.** `krtm-hidden` hides whatever a component or page rule sets,
  and the two co-located `.x.krtm-hidden` re-assertions are gone.
- **A migrated inline style wins against page and component CSS**, as the inline style it replaced
  did. Before, it lost to any rule with higher specificity. The owner accepted the visible
  corrections this makes (listed in REQ-UI-024) rather than preserving each old loss with a
  compensating rule.
- **Design-system declarations that must keep beating page CSS live in the page layer**, in a block
  at the end of `styles.css`, where specificity against the page stylesheets still decides exactly
  as before. They are not in `utilities`: the owner's brief suggested a layer above `page`, but
  measured, that also let them beat the page rules that deliberately out-specify them
  (`.krt-personal-inventory .form-group select`) and every migrated class on the same elements.
  Two dead declarations the move exposed were deleted (`.leitung-intro`'s colour and
  `.krt-pi-search-input`'s padding, both overruled everywhere they applied), and one page
  (`promotion-admin`) received the input background and size it had inherited from a rule that no
  longer reaches it.
- **An `!important` in a page stylesheet is unnecessary against components**, so 27 were removed.
  The two that beat a migrated class (the Lager filter rows) stay.

Verified by a computed-style and full-page pixel diff of every page route at 375 and 1280 px: the
old CSS against the layered CSS, in the same stack at the same moment, with the winning rule of
every changed property attributed through the DevTools protocol. Of 140 measurements (70 routes ×
2 widths), 26 on 13 routes differ in pixels, and two more routes differ only in computed values,
with no pixel difference. Every changed property resolves to one of 12 accepted
migration-class wins, a knock-on of one (a checkbox blockified inside a now-flex label, a `ch`-based
`max-width` following a smaller font), or the live-sync pill's pulse animation, which differs
between two identical renders too. **No declaration changed hands between the design system and a
page stylesheet.**

## Consequences

- A new rule's precedence is read off its file, not off its position in `head.html`.
- A page stylesheet that has to beat the design system writes an ordinary rule. One that has to beat
  a migrated inline class cannot do it with specificity; it needs `!important` in the page layer or,
  better, the migrated class removed from the markup.
- A design-system declaration that must keep beating page CSS goes into the page-layer block at the
  end of `styles.css`, with a comment naming what it beats.
- `CascadeLayerOrderTest` fails the build on a stylesheet without the order line, on a rule outside
  any layer (unlayered CSS would beat every layer), and on a file using a layer it does not own.
- `SingleModalShapeTest` and `TouchClassLayoutE2eTest` read rules inside `@layer` blocks, which
  their parsers already handled as nested rule lists.

## Alternatives considered

- **Move the design-system winners into `utilities`.** Rejected after measuring: it also flips the
  page rules that out-specify them, and the migrated classes on the same elements.
- **Preserve every group-2 result with a compensating rule.** Rejected by the owner: each of those
  results was a page rule overruling a style its author had written inline on purpose.
- **Keep load order.** Rejected: the traps above.
