# ADR-0093 — Eliminate inline `style=""` attributes so the CSP can pin `style-src-attr 'none'`

- **Status:** Accepted
- **Date:** 2026-07-11
- **Deciders:** Repository owner (@greluc)
- **Related:** ADR-0069 (inline template JavaScript → static page modules — the JS analogue of this
  decision); `docs/specs/ui-design-system.md` (no-inline-style rule); `SecurityConfig` CSP;
  `SecurityHeadersTest`

## Context

The frontend CSP had every directive locked down except `style-src-attr`, which carried
`'unsafe-inline'` to allow ~640 inline `style=""` attributes across ~70 Thymeleaf templates. A
security review flagged this as the last CSP residual: while inline style attributes cannot execute
JavaScript, `'unsafe-inline'` on `style-src-attr` means that **if** an HTML/attribute-injection sink
were ever introduced, an attacker could inject a `style` attribute for CSS-based harm. The residual
was documented as accepted (audit L-3) pending an incremental migration.

`<style>` blocks were already nonce-gated; only the per-element `style=""` attributes remained.

## Decision

Remove every inline `style=""` attribute from the templates and pin the CSP to
`style-src-attr 'none'`, so an injected inline style attribute is blocked outright.

- **Static values** → one CSS class per distinct former value in a generated stylesheet,
  `static/css/inline-migration.css`, with the declarations preserved verbatim (behaviour-preserving
  normalisation only). It is loaded **last** so a migrated declaration wins an equal-specificity tie,
  replicating the "inline style always wins" behaviour it replaced. Class names are
  `krtm-<value-slug>-<hash>`.
- **Data-driven conditional values** → a class toggle via `th:classappend`: the modal
  `krtm-modal-open` / `krtm-hidden` pair, `krtm-opacity-05/06`, `krtm-color-danger`.
- **Genuinely dynamic numeric values** (progress-bar widths) → a `data-krtm-width` attribute applied
  to `style.width` via the CSSOM in `inline-style-apply.js`. The CSSOM (`element.style.x = …`) is
  **not** governed by `style-src-attr`, so this is CSP-clean and not security theatre (the value is a
  bounded number set by our own code, not an attacker-supplied `style` string re-applied wholesale).
- The four `common-handlers.js` display handlers (`open-modal-display` / `close-modal-display` /
  `toggle-display` / `set-display`) were refactored from writing inline `style.display` to toggling
  `classList`, so runtime modal/visibility control is also class-based.

## Consequences

- `style-src-attr 'none'`: an injected inline `style=""` attribute is now blocked, closing the
  CSS-injection residual entirely. `SecurityHeadersTest` pins the directive at `'none'`.
- Templates must never reintroduce an inline `style=""` attribute (binding rule in
  `ui-design-system.md`). Setting styles via the CSSOM from nonce'd JS stays allowed.
- `inline-migration.css` is **generated** from the templates' former inline values and is loaded on
  every page. The dynamic-support classes at its foot are hand-maintained. It cannot simply be
  regenerated after the migration (the templates no longer contain the source `style=""` attributes);
  a future bulk change would edit it directly or re-run the generator against history.
- Migrating a former inline value to a class drops it from inline (highest) specificity to class
  specificity; loading the sheet last covers equal-specificity ties, but a pre-existing
  higher-specificity or `!important` rule could now win. No such regression was found, and the full
  asset-lint + MockMvc render-test suite is green, but pixel-level review remains a manual/e2e concern.
- **JS-side follow-up (post-migration fix).** The migration scoped to *templates*; it did not audit
  the JavaScript modules, which have two ways to hit the same wall. First, a `style="…"` inside an
  `innerHTML` string is a real inline style attribute and is blocked by `style-src-attr 'none'` just
  like a template one — a script that needs a dynamic value must use the `data-krtm-*` → CSSOM path.
  Second, once an element's hidden state moved from `style="display:none"` to a `krtm-display-none-*`
  class, a script that revealed it with `el.style.display = ''` no longer works: clearing the (empty)
  inline style leaves the class's `display:none` in force. Both bit `materials-matrix.js` on
  `/materials/overview` (the spacer rows and the `#tableContainer`/`#matrixError` reveal), which
  shipped in v1.3.2 as a blank price-overview and was fixed by moving the spacer height to the CSSOM
  and toggling the visibility class. The rule in `ui-design-system.md` now states both traps
  explicitly, and `MaterialsOverviewMatrixRendersE2eTest` guards the page.
- **JS-side audit completed repo-wide.** Beyond `materials-matrix.js`, the same two traps were found
  and fixed across `static/js/**`: static innerHTML `style="…"` values became per-page CSS classes
  (in the page's nonce'd `<style>` block, or the global `flex-*` / `nowrap` utilities), and
  reveal-over-class sites now toggle the runtime `krtm-hidden` (or the shared `krtm-display-none-5790`)
  class. Affected modules: `materials-profit-calculation.js` (status rows), `orders-create.js` and
  `orders-detail.js` (item/handover rows, inventory-expand table, SCU hints, claim-withdraw button),
  `material-detail.js` (no-results row), `mission-detail.js` (read-only org-units group, frequency
  value), `operation-detail.js` (markdown preview), and `inventory-admin.js` / `inventory-input.js` /
  `inventory-my.js` (SCU hints). Pure JS filter loops that hide *and* reveal rows via
  `el.style.display` (no class) are unaffected and were left alone. Per-page e2e tests
  (`MaterialsProfitCalculationRendersE2eTest`, `OrdersCreateItemLineRendersE2eTest`,
  `OrdersCreateScuHintRevealE2eTest`) assert the elements render/reveal with no `style-src-attr`
  console violation.

