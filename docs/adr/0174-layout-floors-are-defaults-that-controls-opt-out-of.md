# ADR-0174 — Layout floors are defaults a control opts out of, not lists a control must join

- **Status:** Accepted — implemented
- **Date:** 2026-09-13
- **Deciders:** Claude (measurement and implementation), review of PR #1870 (raised the inversion
  twice)
- **Related:** [ADR-0172](0172-the-phone-class-gets-its-own-layout-contract.md) (the phone-class
  contract and the guard this builds on) · [ADR-0171](0171-an-adr-number-is-claimed-against-the-base-branch.md)
  (how this number was claimed) · specs [`ui-design-system.md`](../specs/ui-design-system.md)
  `REQ-UI-009` (amended)

## Context

ADR-0172 closed a large set of phone-class layout defects by **enumerating the selectors that had
them**. Two classes of defect were fixed that way, and both enumerations were caught short before
the pull request that introduced them had even merged.

**The touch-target floor.** The `width <= 1024px` block listed the controls that must reach 44px —
`input, select, textarea, .btn, .hamburger-btn` — plus about eight hand-added exceptions. Each round
of review found more the list had missed:

| Round | What the list missed | Measured |
| --- | --- | --- |
| 1 | Three chrome controls: language switcher, sidebar close, notification bell | 17px, 34px, 40px |
| 2 | Four in-row controls the first sweep could not see, because a fresh stack renders no rows | 15–26px |
| 3 | `.krt-modal-close` — no size rule in **any** stylesheet | browser default |
| 4 | `.close-modal` (16 templates) and `.btn-close` (6) — the same control under two more names | unsized |
| 5 | `.pa-sort-controls .pa-sort-btn` | 29×18px |

The last one is the clearest statement of the problem. The guard could not see it either, because
`promotion-admin-topics.html` hides the sort control behind a "more than one topic" condition, so an
under-seeded stack renders no such button at all. A list of names cannot close this class of defect:
**absence from the list is indistinguishable from not existing yet.** Every control written after
the list starts out non-compliant, and silently so, and each fix teaches only the one name it added.

**The flex-wrap fix.** Three overlapping lists of "action rows that must wrap" accumulated in the
same block. These could never have been complete either, and for a sharper reason: a good number of
the app's rows carry a generated class from `inline-migration.css`, whose names are **content-hashed**
(`krtm-display-flex-gap-1rem-align-items-center-6529`). A hashed name cannot be enumerated in advance
by anyone.

PR #1870 chose to fix the instances rather than the mechanism. That was the right call for a branch
already carrying a large measured change; it was never meant to stand.

## Decision

**A layout floor is a default that a control opts OUT of, and the opt-out is a declaration.**

### 1. The touch floor is a zero-specificity default

```css
@media (width <= 1024px) {
    :where(button, [role='button'], summary, a.btn, input, select, textarea) {
        min-height: var(--touch-target);
    }
}
```

`:where()` is the mechanism, not decoration. It contributes **zero specificity**, so the selector is
(0,0,0) and any authored rule beats it — in the same file, in a per-page `<style>` block read after
it, at any specificity. That is what makes a default safe to apply to every control at once, and it
is why the rule cannot resurrect the specificity traps the old exceptions were written to dodge
(`.btn.btn-xs2`, `input.item-checkbox`): it never competes with them.

**The sanctioned opt-out is `min-height: var(--touch-target-dense)`** — the token REQ-UI-009 already
names for a repeated dense in-row control. A declared marker, not an absence. The review suggested a
`[data-dense]` attribute; the token was chosen over it for three reasons:

- It is **already the marker**. `TouchClassLayoutE2eTest` builds its dense set by reading
  `--touch-target-dense` consumers out of the CSSOM, so the inversion needed no new mechanism on the
  test side — and deliberately did not get one. An opt-out the guard had to be told about separately
  would be a hand-kept list again, wearing a different name.
- **The design system declares the exemption**, which is where REQ-UI-009 puts it. An attribute moves
  a styling decision into markup.
- `[data-dense]` would have meant editing roughly 200 call sites across templates and JS, including
  elements built by `document.createElement`.

`min-height` only, and that is deliberate. The floor that kept being missed is the vertical one, it
is the one the guard asserts, and it is monotonic — a floor can only ever raise a control. A
universal `min-width` would not be: `.pa-sort-btn` declares `width: 1.8rem` and `.matrix-flag` a 32px
square, neither declares `min-width`, so a default min-width would widen the very columns those two
exist to keep narrow.

### 2. The wrap rule asks what an element IS, not what it is called

```css
@media (width <= 768px) {
    :where(:has(> :where(button, [role='button'], summary, a, input, select, textarea))) {
        flex-wrap: wrap;
    }
}
```

A row that directly contains a control or a link is an action row, whatever its class. `:has()` reads
the structure the templates already have, which is the one thing the class generator cannot rename.
`flex-wrap` is inert on anything that is not a flex container, so matching a `<td>` or a `<form>`
that happens to hold a button costs nothing — and `.btn` itself is never matched, because a button
holding an icon and a label has no control *child*, which is what would otherwise stack the glyph
above its text.

Two named rules remain beside it, and both are named on purpose. The `.flex-gap-*` utilities are the
design system's own vocabulary for "put these things in a row", so that list is closed and stable in
a way page-specific class names are not; they also catch rows holding no control at all.
`.kpi-card-foot` is the single row from the old hand-written list that neither mechanism reaches — a
`<span>` and an inline `<svg>`, a row of content rather than of controls.

### 3. The four copies of the panel height expression become one token

`--krt-panel-viewport-rest` in `:root`, with the `dvh`/`vh` fallback expressed **once** as an
`@supports (height: 1dvh)` block rather than as the two-declaration trick the four call sites each
carried with a thirteen-line comment. The trick could not have survived the move: with
`max-height: var(--x)` written twice, both declarations parse, the cascade keeps only the last, and
if its substituted value is invalid the property falls back to `unset` — `none` for `max-height` — so
the panel would get no cap at all, which is the exact failure the trick existed to prevent.

The per-panel reserve (22rem or 25rem) stays at the call site. It cannot be parameterised into the
token: a `var()` inside a custom property is substituted on the element where that property is
*computed*, so a `--krt-panel-reserve` set on the consumer would never be seen by a definition on
`:root`.

## Consequences

**Wanted.** A control added tomorrow is born compliant, on every page, including pages no test can
reach and controls no reviewer thought about. The three remaining per-control rules in the touch
block are there because each states a decision — a page-local override to beat, a `<span>` that no
element selector reaches — rather than because a list needed another entry.

**The inversion forced two exemptions to be written down that previously existed only in prose.**
`.matrix-flag` sized itself with `width`/`height` and no `min-height`; since a `min-height` clamps a
`height` regardless of specificity (different properties never compete), it had to declare the dense
token to decline the default. `.master-row` is the more interesting one: REQ-UI-009 has granted it a
32px exemption since 2026-09-13, and **no stylesheet anywhere declared it**. The guard derives its
dense set from the CSSOM, so a class that consumes the token nowhere was in no such set, whatever the
requirement said about it — the exemption was being measured against 44px. It is declared now.

**Verifying the inversion exposed three defects in the guard itself**, all found by loading the real
stylesheets into a browser and asking for the dense set. They are fixed here because a guard that
cannot read the stylesheet cannot be the thing that follows it:

1. `if (rule.cssRules) { walk(rule.cssRules); continue; }` skipped **every style rule**.
   `CSSStyleRule` inherits from `CSSGroupingRule` since CSS Nesting shipped, so it now carries a
   `cssRules` property of its own — an empty `CSSRuleList`, which is an object, which is truthy. The
   test meant to tell a grouping rule from a style rule stopped telling them apart, and the dense set
   came back **empty in every current engine**: 728 rules in `styles.css`, none reached.
2. The branch that exists to report exactly that pushed to a `const` declared ~100 lines later, so it
   threw a `ReferenceError` out of the temporal dead zone instead of reporting anything. Two silent
   failures in series is how a guard reports on a stylesheet it never read.
3. Class names were extracted from each selector's subject, but a compound means AND: `.btn.btn-xs`
   contributed the bare class `btn`, which exempted **every button in the app** down to 32px. The
   subject is now kept as a selector and matched with `Element.matches`.

**Risk accepted, and one instance of it found.** The wrap rule changes behaviour on rows nobody
enumerated, which is the point and also the exposure: a flex row that relied on the initial `nowrap`
without declaring it now wraps on the phone class. The zero-specificity form is the mitigation — any
row that must not break says `flex-wrap: nowrap` and wins outright — and `TouchClassLayoutE2eTest`
measures overflow on every page at every touch width.

The case that matters is a **column** container, because a column flex container that wraps lays its
overflow into a *second column* rather than scrolling. A sweep of every `flex-direction: column` rule
in the stylesheets found exactly two whose height is genuinely capped rather than floored:
`.krt-modal` (`max-height: 90vh`) and `.krt-modal > form`, which inherits that cap through the flex
layout with its `min-height: 0` — and a form's direct children are inputs and buttons, so it matches.
Both now say `nowrap`. Everything else (`.sidebar-content`, the search-form columns, `.drop-zone`)
constrains itself with `min-height`, which is a floor and can never make a flex line run out of
space.

**Not done here.** `.close-modal` is a `<span>` in `admin/mission-data.html`'s three dialogs, so it
is reached by no element selector on either side; the touch block keeps an explicit `min-height` for
it and the guard names the class directly. The real defect is the markup — a dismiss control that is
not a button is not keyboard-operable — and that is a separate change.
