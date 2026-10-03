# ADR-0239 — The browser baseline is "Baseline 2025", and Trusted Types follow

- **Status:** Accepted — implementation pending (the language level waits for TypeScript 7 to
  accept the `ES2025` lib; Trusted Types start in report-only mode)
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
