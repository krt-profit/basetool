# ADR-0069 — Extract inline template JavaScript into static page modules (bootstrap-dict + verbatim module)

- **Status:** Accepted — amended 2026-10-02 (IIFE namespaces per domain; a correction, see below);
  finished 2026-10-04 (every inline script is a data bootstrap)
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

## Amendment — 2026-10-02: IIFE namespaces per domain, and a correction

**Correction (found by the domain modularisation audit, plan §15).** "No IIFE wrapping" was the rule
for the page modules this decision extracted, and those five still follow it. It was never a property
of the scripts as a whole: on 2026-10-02, 50 of the 100 scripts under `static/js` are wrapped in an
IIFE and 50 are not. "Non-defer" no longer holds either: since the ADR-0125 amendment of 2026-09-23,
120 of the 121 `th:src` script tags carry `defer`; the bootstraps and the order between a
bootstrap and its module are unchanged.

**Decision (plan §5.9 and §7.8 step F5).** The scripts move into per-domain folders and each domain
exposes what other scripts need through one namespace object on `window`, built inside an IIFE,
instead of top-level bindings in the shared global scope, which held 523 top-level names in the 50
non-IIFE files at the audit. An ESLint `no-implicit-globals` ratchet keeps the count from growing,
and a boundary text test forbids one domain's page from reading another domain's `window.*` API
outside an allow-list. The bootstrap/module split of the decision above stays, and every new
namespace is declared in `frontend/types/globals.d.ts` (REQ-FE-018). The remaining 70 inline
script blocks move into modules on the same recipe.

## Amendment — 2026-10-04: finished, and the bootstrap is data only

**Correction of the count.** On 2026-10-04 the templates held 67 inline `<script>` blocks with 1,645
non-blank lines, and 55 of them were already what this decision calls a bootstrap: Thymeleaf values
declared as literals, nothing else. The "70 blocks, 1,860 lines" of the audit counted those as
logic still to move. Eleven blocks carried code: the bank holder and manage pagers, the org-unit
bank tabs, the member list (166 lines) and the member edit form, the mission finance dialog and the
mission presence start, the head's SCU parser fallback, the two Materialbörse dictionaries that
merged themselves with `Object.assign` and a conditional, and the Leitung conflict alias; five more
were empty `DOMContentLoaded` listeners on the error pages.

**Decision.** An inline script is a `th:inline="javascript"` block of `const` / `let` / `var NAME =
literal` and `window.NAME = literal` statements, where a literal is a string, number, boolean,
`null`, or an array or object of literals, filled from `/*[[…]]*/` natural-template values. A
function, call, listener, merge or reference to another global belongs in the page's module, and a
server value the module needs reaches it through such a bootstrap or a `data-*` attribute.
`InlineScriptDataOnlyTest` parses every inline block against that grammar, with a planted template
proving it fails and floors of 100 templates and 55 blocks; the one block that keeps code, the
head's `krtEvents` stub and watchdog, is listed in its `EXCEPTIONS` with the reason (it detects a
deferred script that never ran, so it must not be one). The test also rejects a Thymeleaf
expression inside a JavaScript string literal: `'[[#{key}]]'` in an inlined block renders as
`'"Gespeichert."'`, so the toasts and dialog titles of the mission-data and special-commands admin
pages showed the text in quotes, and the mission finance dialog's form action carried a quoted
mission id. Both pages now use the natural-template form.

**How the eleven moved.** The pagers into `bank-holder-detail.js` and `bank-manage.js`, the tabs
into a new `org-unit-bank.js`, the member list into `members.js` and the edit form into
`member-edit.js` (with its labels as `window.krtMemberEditI18n`), the finance dialog into
`mission-detail.js` and the presence start into `mission-presence.js` (with the viewer id as
`window.missionPresenceUserId`). The SCU parser fallback was dropped: `scu-decimal-input.js`
installs the same `window.krtScuInput` before any page module and nothing calls it at parse time.
The dialogue dictionaries of `fragments/materialboerse-modal.html` and
`fragments/materialgesuch-modal.html` are now `window.materialboerseModalI18n` /
`window.materialgesuchModalI18n`, which their modules merge with the page's dictionary; the conflict
labels are written out instead of aliased. Listener registration moved from parse time into the
deferred module of the same page, which runs before `DOMContentLoaded`, so every listener still
fires; the order among them changes only where they are independent.
