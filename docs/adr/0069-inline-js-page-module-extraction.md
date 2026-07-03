# ADR-0069 — Extract inline template JavaScript into static page modules (bootstrap-dict + verbatim module)

- **Status:** Proposed
- **Date:** 2026-07-03
- **Deciders:** Repository owner (@greluc)
- **Related:** issue #924 (L5 part 2, epic #905) · ADR-0068 (the controller split of the same issue) · ADR-0012/0013 (krtFetch foundation) · REQ-FE-001…011 ([`frontend-ajax-mutations.md`](../specs/frontend-ajax-mutations.md)) · #574 (mission i18n-dict precedent)

## Context

~16k lines of JavaScript live inline in `<script>` blocks of 62 Thymeleaf templates instead of in
testable, lintable, cacheable static modules. Inline JS is invisible to ESLint/Prettier (which only
cover `static/js/**`), re-shipped on every page load, and entangles Thymeleaf i18n/server-value
interpolation with page logic. Issue #924 stages the extraction template by template, starting with
the top 5 (mission-detail 2837 inline lines, orders-detail 1452, inventory-my 1184, inventory-admin
1076, promotion-manage 691 — ~7.2k of the 16k total).

The extraction must be behaviour-identical: these pages carry the krtFetch live-update contract
(REQ-FE-001…010), optimistic-lock version propagation, delegated event wiring that survives
fragment swaps, and (mission) the presence-WebSocket live sync. Three of the five templates predate
the `let`/`const` era (~300 `var` declarations), and ESLint on `static/js` enforces `no-var`,
`eqeqeq smart` and `no-undef` as errors.

## Decision

**Split every page's inline JS into a minimal inline *bootstrap* and one classic static *page
module*, moved verbatim.**

- **Bootstrap** — one small `th:inline="javascript"` block per original conditional context keeps
  ONLY the Thymeleaf-interpolated material: the `/*[[#{…}]]*/ 'fallback'` i18n consts/dicts
  (keys and fallbacks byte-identical — no `data-*` camelCase renames of existing dict keys) and the
  server-value consts (`missionId`, order-age thresholds, …). Mid-logic interpolations are hoisted
  into new bootstrap consts and the logic site references the hoisted name; orders-detail's raw
  `'[(#{…})]'` text-inline sites were converted to the JS-escaping `/*[[#{…}]]*/` comment form
  during hoisting (strictly safer: the raw form does not JS-escape quotes). This extends the
  window-dict handoff precedent of #574 (`window.MISSION_SUBRES_I18N`) rather than the `data-*`
  attribute variant of notifications.js — both are established; the dict form avoids rewriting
  hundreds of dotted dict keys.

- **Page module** — all logic moves token-identically into one classic (non-module, non-defer)
  script `static/js/<template-name>.js`, blocks concatenated in document order, **no IIFE
  wrapping** (cross-block bare-identifier consumption relies on the classic-script global lexical
  environment; `typeof` self-references and function-declaration window properties must survive).
  The module is loaded via `<script th:src="@{/js/<name>.js}" th:attr="nonce=${cspNonce}">`
  immediately after the bootstrap — same end-of-body position, so every parse-time DOM lookup and
  the relative order against the sync head scripts (`event-delegation.js`, `krt-fetch.js`) are
  unchanged. Conditional blocks keep their condition on **both** tags (promotion-manage's
  `th:unless="${isAllSquadronsMode}"`); tiny conditional interpolation-dominated blocks
  (mission-detail's `openEditFinanceModal`, the presence bootstrap) stay inline.

- **Lint adaptation, not suppression** — moved code satisfies the `static/js` ESLint rules by
  actual conversion: `var`→`let`/`const` (per-site audited: reassignments→`let`, no same-scope
  redeclarations, no TDZ/hoisting reliance, no loop-closure capture semantics changes), the two
  object-identity `== ` comparisons→`===`, and a `/* global …bootstrap consts… */` header per
  module for the bare bootstrap bindings (`no-undef`). The bootstrap itself stays outside ESLint's
  reach and keeps its original declarations verbatim.

- **Bugs are preserved, not silently fixed** — inventory-admin's eight `[[#{…}]]` markers sat in a
  block *without* `th:inline` and already reached users as literal text; they move verbatim and
  stay a separately-tracked defect. Same for the `window.confirmKrtDialog` ghost guard and the
  divergent mission live-sync section maps.

- **`krtFetch.sectionWrite(config)`** — mission-detail's `krtMissionWrite` /
  `krtRefreshMissionSection` / `krtNotifyMissionChanged` trio is generalized into a factory in
  `krt-fetch.js` returning `{write, refresh, notify}` from a config of i18n key prefixes +
  fallbacks, a section→`{container, fragmentValue}` map, and **late-bound** dict/pageUrl/broadcast
  getters (the dict and `window.missionPresence` only exist after later bootstraps run).
  mission-detail.js instantiates it and re-publishes the three `window.*` aliases, so all ~60 call
  sites are untouched; future section-structured pages (orders, inventory) reuse the factory
  instead of hand-rolling the wrapper.

## Consequences

- The five templates lose ~7.2k inline lines; the logic becomes ESLint/Prettier-governed,
  browser-cacheable and diffable. The remaining ~57 templates follow the same recipe in later PRs.
- **No behaviour change**: i18n keys, fallbacks, listener registration order, delegated-wiring
  guards, CSP nonce usage and the two pre-existing defects are preserved; the only accepted
  ordering shift is mission-detail's date-localisation listeners now registering before the
  presence bootstrap's (verified independent). CI Playwright e2e is the behavioural gate
  (promotion-manage has no e2e coverage — flagged; its conversion relied on per-site static audit).
- Accepted costs: page state that was `window`-visible via top-level `var` becomes script-scope
  (`let`/`const`) — verified unread as `window.*` anywhere; per-module `/* global */` headers
  couple module and bootstrap explicitly; the `sectionWrite` factory adds a small generic surface
  to `krt-fetch.js` that only mission uses until the next page adopts it.

