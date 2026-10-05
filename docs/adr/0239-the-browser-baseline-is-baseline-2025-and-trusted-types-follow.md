# ADR-0239 — The browser baseline is "Baseline 2025", and Trusted Types follow

- **Status:** Accepted — the baseline and the language level are implemented (2026-10-04);
  Trusted Types are implemented in report-only mode (2026-10-04), and the only step left is the
  owner-approved switch of production to `enforce`
- **Date:** 2026-10-01
- **Deciders:** @greluc (owner decision D-16)
- **Related:** [domain modularisation plan](../DOMAIN_MODULARISATION_PLAN.md) §8.2 · spec
  REQ-FE-018 ([`frontend-ajax-mutations.md`](../specs/frontend-ajax-mutations.md)) ·
  [ADR-0125](0125-typed-javascript-via-checkjs-not-typescript.md),
  [ADR-0130](0130-own-openapi-dts-emitter-and-typescript-7.md) (the type check) ·
  [ADR-0069](0069-inline-js-page-module-extraction.md) (the page scripts)

## Context

The project never documented which browsers it supports. The type check (`frontend/tsconfig.json`)
and ESLint (`frontend/eslint.config.mjs`) both target ES2023, so a newer API in an unchecked file
passes every gate, and every decision about a modern feature lacks its premise. The features
already shipped imply at least Chrome 105, Firefox 121 and Safari 16.4.

DOM-based cross-site scripting is today held off by the nonce CSP, by ESLint rules on HTML sinks
(REQ-FE-022) and by review. The browser can enforce sink safety itself through Trusted Types, and
the frontend already collects CSP violations through its client-error beacon (`csp_violation`).

## Decision

1. **The browser baseline is "Baseline 2025" (ES2025).** The floor is at least **Chrome 122,
   Firefox 131 and Safari / iOS 18.4** — the releases that ship the iterator helpers. Single 2025
   features that need newer releases (`Promise.try`, `RegExp.escape`, `Float16Array`) are not used
   until the floor moves past them.
2. **The type check's `lib` / `target` and ESLint's `ecmaVersion` rise from 2023 to 2025** once
   TypeScript 7 is proven to accept the `ES2025` lib. Until then the gates stay at ES2023, and code
   uses ES2025 APIs only where the type check knows them.
3. The floor is written into REQ-FE-018; a later raise is a new decision that amends this ADR and
   the requirement together.
4. **Trusted Types, report-only first, then enforced.** The CSP gains
   `require-trusted-types-for 'script'` in report-only mode, reported through the existing
   `csp_violation` beacon; two named policies and a tagged-template HTML builder serve the sinks; no
   `default` policy. Enforcement follows once the dialog page-walk E2E collects no violation.

## Implementation

- **2026-10-04 — the language level.** TypeScript 7.0.2, the version the build uses, accepts the
  `ES2025` lib: a planted `// @ts-check` file using `Set.prototype.union` and an iterator helper
  (`values().map().toArray()`) passed `:frontend:typecheckJs` under `ES2025` and failed it under
  `ES2023` with TS2550 and TS2339. `tsconfig.json` (`target`, `lib`) and `eslint.config.mjs`
  (`ecmaVersion`, browser and Node scripts) moved to 2025 together. The `ES2025` lib also declares
  `Promise.try`, `RegExp.escape` and `Float16Array`, which the floor does not ship, so ESLint rejects
  them (`no-restricted-properties`, `no-restricted-globals`; proven by `:frontend:testEslintBans`).
  The floor is written into REQ-FE-018 and `ui-design-system.md`.
- **2026-10-04 — Trusted Types, report-only.**
  - **The two policies.** `krt-html` belongs to the tagged-template builder `krtHtml`
    (`krt-html.js`): every interpolated value is escaped unless it is itself `krtHtml` markup,
    arrays render element by element, `krtHtml.set(el, value)` writes markup as HTML and anything
    else as text, and a call with a forged strings array throws. `krt-fragment` lives inside
    `krt-fetch.js` and wraps only the text of same-origin fragment responses
    (`setTrustedHtml`, `replaceWithTrustedHtml`, the new `parseTrustedDocument`). Neither policy
    object leaves its closure, no `default` policy exists, and where the browser has no Trusted
    Types both fall back to plain strings.
  - **The sinks.** The 42 builder sites in 15 scripts moved from `escapeHtml` / `escapeAttr` to
    `krtHtml`, the 26 `innerHTML = ''` clears to `replaceChildren()`, the one `DOMParser` read to
    `parseTrustedDocument`, and two client-built tables that misused `setTrustedHtml` (the materials
    matrix and the profit calculation) to `krtHtml`. `escape-html.js` is gone; nothing used it
    any more. ESLint (`no-restricted-syntax`, `no-eval`, `no-implied-eval`, `no-new-func`) rejects
    every HTML or script sink and every `createPolicy` outside the two helpers, and
    `:frontend:testEslintBans` proves each ban fires; `:frontend:testTrustedTypesJs` pins the
    builder and both policies.
  - **The header.** `SecurityHeaders` sends `require-trusted-types-for 'script'; trusted-types
    krt-html krt-fragment`. `app.security.trusted-types` (`APP_SECURITY_TRUSTED_TYPES`) is `report`
    by default — the directives form a `Content-Security-Policy-Report-Only` header of their own —
    or `enforce`, which appends them to the enforced policy; a blank or unknown value is `report`.
    The gauge `basetool_trusted_types_mode{mode}` shows the effective mode. Chromium dispatches
    `securitypolicyviolation` for a report-only policy without a `report-uri` (checked by hand
    against a scratch page before the change), so the beacon's existing listener reports a
    violation as `csp_violation`, now with the sink name (`require-trusted-types-for Element
    innerHTML`) and never the markup.
  - **The collector.** `DialogA11yE2eTest` registers a `securitypolicyviolation` listener before
    every page script and fails the page walk on any Trusted Types violation; it first plants an
    `innerHTML` write on the home page and fails if the collector does not hear it.
  - **What is left.** Switching production to `enforce` is a production configuration change that
    the owner approves after a quiet period with no `csp_violation` report and a green dialog walk
    ([`deployment.md` → *Trusted Types: report, then
    enforce*](../deployment.md#trusted-types-report-then-enforce)). Nothing else of this decision
    is open.

## Consequences

- Set methods, iterator helpers, the popover attribute and same-document view transitions become
  available without polyfills.
- Members who cannot update iOS to 18.4 or later lose functions; that was accepted against the
  rejected "Baseline widely available" (ES2024, Safari 17.4).
- Under enforced Trusted Types every sink assignment needs the helpers, even `innerHTML = ''`, and a
  new sink outside them throws in the browser; the E2E violation collector is what catches it
  before production.
- DOM-based XSS becomes a browser-enforced property instead of a review property.

## Alternatives considered

- **"Baseline widely available" (ES2024)** — a lower floor, without the iterator helpers.
- **No documented baseline** — the status quo, in which an unchecked file can use any API.
- **Trusted Types enforced at once** — the first unknown sink would break a page for members
  instead of producing a report.
- **A default Trusted Types policy** — it would pass every string through one function and turn
  the enforcement back into a convention.
