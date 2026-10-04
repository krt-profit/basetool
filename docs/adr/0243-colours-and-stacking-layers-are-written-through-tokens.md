# ADR-0243 — Colours and stacking layers are written through tokens

- **Status:** Accepted — implements the CSS row of the domain modularisation plan §8.2 (owner-ratified
  plan, 2026-10-01).
- **Date:** 2026-10-04
- **Deciders:** @greluc
- **Related:** spec [`ui-design-system.md`](../specs/ui-design-system.md) (`REQ-UI-001`, `REQ-UI-023`)
  · [ADR-0212](0212-every-stylesheet-sits-in-a-cascade-layer.md) (cascade layers) ·
  [ADR-0239](0239-the-browser-baseline-is-baseline-2025-and-trusted-types-follow.md) (browser
  baseline) · [`DOMAIN_MODULARISATION_PLAN.md`](../DOMAIN_MODULARISATION_PLAN.md) §8.2, §9.2 B-03

## Context

The colour tokens on `:root` in `styles.css` were the only place a brand or state colour was meant
to live, but 120 declarations wrote a token's value out by hand — alpha variants such as
`rgb(231 126 35 / 20%)` (the primary orange at 20 %) in 98 of them, opaque copies such as `#fff`
or `#d41a25` in the rest. A renamed or retuned token would have left all of them behind. The 74 page
stylesheets under `static/css/pages/` were excluded from the strict Stylelint configuration and
linted only with the small template rule set, so 66 findings of the standard configuration had
accumulated there. Stacking was 50 `z-index` declarations with 30 different numbers between 1 and
10 000; the order of header, drawer, dropdowns, modal, toasts and the confirm dialog was implicit in
the 26 of them at 50 and above, spread over eight stylesheets.

## Decision

1. **A colour token's value appears only in its own declaration.** An opaque copy is
   `var(--color-x)`; an alpha variant is `color-mix(in srgb, var(--color-x) N%, transparent)`, which
   is the token's colour at alpha N % — premultiplied mixing with `transparent` keeps the channels
   and scales only the alpha; a fully transparent one is `transparent`. A colour that is not a token
   stays a literal. `ColourTokenCopyTest`
   fails the build on a copy.
2. **The page-level stacking layers are a `--z-*` scale** on `:root` in `styles.css`, declared from
   bottom to top: popover 50, raised popover 60, dropdown 99, sticky page head 900, chart scrollbar
   998, footer 999, header 1000, header control 1001, floating list 1100, raised floating list 1200,
   drawer scrim 1999, drawer 2000, phone tab bar 2100, modal 3000, hint 4000, live-sync pill 9000,
   toast 9999, confirm dialog 10 000. Every token keeps the number it replaces, so the stacking order
   is unchanged. A literal `z-index` below 50 stays allowed: it orders the parts of one component
   (sticky table cells, a lifted overflow menu) and competes with nothing outside it.
   `ZIndexScaleTest` pins the order of the scale and fails on a literal of 50 or more or an undeclared
   token.
3. **The page stylesheets are linted with the standard configuration** (`:frontend:lintCss`);
   `:frontend:lintCssInline` keeps only the `<style>` blocks a template might bring back.
4. **`CustomPropertyExistenceTest` reads every `var()`** — stylesheets, templates and scripts — and
   proves with a planted fixture that it reports an undeclared property in each.
5. **Container queries and nesting are not introduced** until a design decision asks for them.

## Consequences

- Each substitution has the same computed value; a headless-Chrome comparison of all 16 616
  declarations of the 37 touched stylesheets against `main` found no difference beyond the
  serialisation. The one measurable deviation is legacy alpha quantisation: `rgb()` stores alpha in
  8 bits (2.5 % becomes 6/255), `color-mix()` keeps it exact — at most half an 8-bit step.
- `word-break: break-word` (deprecated) became its specified equivalent `word-break: normal;
  overflow-wrap: anywhere`; the three camel-case ids in the materials page's `[hidden]` rule became
  attribute selectors, which lowers that rule's specificity from (1,1,0) to (0,2,0) — no rule of the
  page layer on those elements lies in between.
- A transition between a `color-mix()` colour and a legacy one interpolates in Oklab instead of sRGB;
  the end states are identical, and every transition and gradient in the app runs between the same
  hue at different alphas or to `transparent`, where both spaces give the same frames.
- `color-mix()` needs Chrome 111, Firefox 113, Safari 16.2 — inside the Baseline-2025 floor of
  ADR-0239.

## Rejected

- **Alpha tokens** (`--color-primary-20` …): 30 distinct alpha steps would each need a token, and a
  retuned base colour would still need every step edited.
- **Relative colour syntax** (`rgb(from var(--color-x) r g b / N%)`): equivalent, but newer than
  `color-mix()` and longer.
- **A z-index token for every literal**, including the component-local ones: the local values order
  cells within one table and mean nothing outside it.
