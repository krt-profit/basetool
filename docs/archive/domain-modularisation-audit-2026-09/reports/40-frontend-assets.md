# 40 — Frontend static assets and templates (finding prefix `FEA`)

Scope: `frontend/src/main/resources/static/js` (100 files), `static/css` (64), `templates` (120), the
lint/type-check configuration in `frontend/` and `frontend/build.gradle.kts`, the CSP writer and the
asset-serving configuration. Read-only; worktree `$REPO`
at `95e945326`. All counts come from the scripts in §3.13 (ASTs via espree/eslint-scope, htmlparser2,
postcss — used as parser libraries only) or from the grep commands listed there. Paths below are
relative to `frontend/src/main/resources/` unless they start with `frontend/`, `docs/` or the vault.

---

## 1. Summary — the ten most important conclusions

1. **The assets are organised by page and by audience, not by domain, yet they map cleanly onto the
   domain taxonomy**: 100 scripts = 19 core + 81 domain scripts in 21 domains, 64 stylesheets = 8
   shared + 1 public + 55 domain, 120 templates = 16 shared + 7 public + 97 domain; `static/js` has no
   subfolder and `templates/admin/` holds 22 pages of 10 domains — while real cross-domain couplings
   are few (1 JS API, 6 stylesheet links, 1 inverted core→page call), so a per-domain layout is a
   mechanical move, not a redesign (FEA-01, §3.1–3.3).
2. **The coupling mechanism is the classic shared global scope**: 50 non-IIFE scripts declare 523
   top-level names (443 functions), 23 names are declared in 2–3 files, 36 files import 276 names via
   `/* global */`, 34 files read 213 Thymeleaf bootstrap constants, and 185 `typeof window.X` checks
   plus 133 `window.krtFetch` presence guards compensate for dependencies nothing declares (FEA-02,
   §3.4).
3. **FE-SIMP-03 worked for the JavaScript but the Lager duplication moved into the templates**:
   inventory-admin.js↔inventory-my.js now share 69 (K=6) to 154/167 (K=4) non-trivial lines instead of
   ~700, but `fragments/inventory-stack-entries.html` is two near-identical fragments (608 of 688
   non-trivial lines duplicated) and inventory-admin.html↔inventory-my.html share 194; larger copies
   remain in refinery create/details (112 JS + 97 CSS + 88 template lines), item/material collection
   (84 of 98 lines), mission/operation detail CSS (169) and five near-identical error-page
   stylesheets (FEA-03, §3.9).
4. **Modern JavaScript stops at ES2015**: 0 `var` (lint-enforced) but 3,007 `function` forms vs 305
   arrows, 664 string concatenations vs 64 template literals, 14 `?.` vs 245 `a && a.b` guards, no
   classes, no ES modules, one `AbortController`, and not a single ES2022+ API (`at`,
   `Object.hasOwn`, `structuredClone`, `toSorted`, `groupBy`, `withResolvers`, Set methods, iterator
   helpers) (FEA-04, §3.5).
5. **No browser-support baseline is written down anywhere**, although ESLint (`ecmaVersion: 2023`),
   `tsconfig` (`target`/`lib: ES2023`, enforced only in checked files) and CSS that already needs
   `@layer`, `:has()` and range media queries set one implicitly — and the installable web app is the
   iPhone/iPad client (FEA-05).
6. **Type checking covers 44 of 100 files but only 37.8 % of the lines**: the three largest scripts
   (mission-detail 3,249, bank 2,400, orders-detail 2,325 lines — 20 % of all JavaScript) are
   unchecked, mission/bank/joborder/promotion/catalogue sit at 0–15 %, and REQ-FE-018 and the
   TypeScript plan still quote 40 of 96 and an execution-order objection that FE-PERF-05 removed
   (FEA-06).
7. **ADR-0069's extraction is unfinished**: 60 templates carry 70 inline blocks (1,860 lines, 142
   `var`, never linted or type-checked), 17 templates still hold top-level logic (five bank pages,
   members, member-edit: 503 plain inline lines), and `templates/members.html:215-225` puts 11
   `[[#{…}]]` markers in a script without `th:inline` — wrong-context escaping at best, literal
   markers in three dialogs at worst (FEA-07).
8. **The CSS architecture is modern but its lint coverage is split**: 64/64 files layered with 0
   unlayered rules, `:where()` floors, 73/74 range media queries and a global reduced-motion reset —
   but the 54 page stylesheets (5,208 lines) run under a 2-rule Stylelint set, and static analysis
   finds an undefined token that makes the sticky material-demand search header transparent
   (`static/css/styles.css:2999,3006`), 6 unused tokens, 8 dead `krtm-*` classes and 45 unscaled
   z-indexes; nesting, container queries, `@scope`, `color-mix()` and popover are unused (FEA-08).
9. **The asset security posture is strong, with four concrete gaps**: 56 raw GET `fetch` calls in 32
   files handle session loss and the terms gate inconsistently (only 3 files honour
   `X-Reauthenticate` on their reads, ≥ 15 sites trust `res.ok`, the defect shape
   `HandRolledFetchGateContractTest` pins for only two modules), the two gate redirect helpers accept
   `//host` and four navigate-after-write sites follow an unchecked `targetUrl`, the CSP still
   carries an unused style nonce and `font-src data:`, and the CSV export lacks formula
   neutralisation (FEA-10).
10. **Trusted Types enforcement is feasible without dependencies, and ES modules are feasible
    without a bundler — but only behind a server-rendered import map**: there are no
    `eval`/`Function`/string-timer/script-URL sinks, 72 `innerHTML` sites (23 clears, 49 escaped or
    static builders) plus two sanctioned fragment helpers, and the `csp_violation` beacon and
    `ClientErrorSpike` alert already exist for a report-only phase; for modules, unversioned asset
    URLs are served `immutable` for a year, so relative `import`s would pin stale code across deploys
    (FEA-11, FEA-12).

Suggested order (each step independently shippable): FEA-13 → FEA-08 (1–3) → FEA-10 (2–4) →
FEA-07 (1–4) → FEA-10 (1) → FEA-02 stage 1 → FEA-06 → FEA-01 + FEA-03 + FEA-09 → FEA-11 → (optional,
own ADR) FEA-12.

---

## 2. Findings

### FEA-01 — Assets are organised by page and audience, not by domain

**Evidence**
- Layout: `static/js` has 100 files and no subdirectory; `static/css` has 10 root files and
  `pages/` (54); `templates/` has 90 pages (root, `admin/` 22, `organisation/` 2, `error/` 4) and 30
  files in one flat `fragments/` (`find … | wc -l`, §3.13).
- `templates/admin/` groups by audience: its 22 pages belong to catalogue (7), identity (4),
  blueprint (3), orgunit (2), audit, bank, dashboard, exchange, notification and personal inventory.
- Domain fragments live beside the shared ones in `fragments/`: bank 6, materialexchange 3,
  catalogue 3, inventory 2, identity 2, orgunit 2, orgchart 1 (§3.1).
- Cross-domain couplings (all of them, §3.3):
  - JS API: `inventory-materialboerse.js` (Lager page) → `window.krtMaterialRelease` of
    `materialboerse-release.js`; the page also links `materialboerse.css` and includes
    `fragments/materialboerse-modal`.
  - Stylesheets: `admin/audit-log.html:6` links `bank.css` and uses its `bank-panel`,
    `bank-filterbar`, `bank-form-row`, `bank-datetime-row`, … classes; `admin/materials.html:6` links
    `promotion-admin.css` for `.form-row`/`.form-input`/`.form-hint`; three blueprint pages link
    `personal-inventory.css`.
  - Inverted core → page call: `static/js/common-handlers.js:157-167` calls `window.filterTable`,
    which three catalogue scripts define separately (`admin-materials.js:186,203`,
    `locations.js:33,50`, `ship-data.js:43`).
  - Domain code in the global head: `templates/fragments/head.html:144` loads
    `krt-bank-account-search.js` on every page, only bank pages use it.
  - One page, three domains: `admin/mission-data.html` + `mission-data.js` manage squadrons
    (orgunit), job types and frequency types.
- The component pattern already exists once: `fragments/materialboerse-modal.html:80-111` ships its
  bootstrap dictionary and its script with the fragment.

**Impact** — The asset tree has no ownership boundary: nothing stops a page from linking another
domain's CSS or reading another domain's `window.*` API, and two pages already do. If the backend and
frontend Java move to per-domain packages (options A/C), the assets would remain the one layer that is
not partitioned.

**Proposed change**
- Folders `static/js/core/` (+ `core/pickers/`), `static/js/<domain>/`, `static/css/<domain>/`
  (domain sheet + its page sheets), `templates/<domain>/` (+ `admin/` and `fragments/` beneath it);
  `templates/fragments/` keeps only chrome and shared components. Domain names = the Java package
  names chosen by the frontend/backend refactor.
- Move shared primitives out of domain sheets (`.form-row`/`.form-input`/`.form-hint`, the bank
  panel/filter-bar family) into `styles.css` components; move `krt-bank-account-search.js` to
  `bank/` and load it on bank pages only (it registers into `krtComboboxRemoteSources`, so the core
  combobox needs no change); replace the three `filterTable` copies with one core `krtTableFilter`;
  split `admin/mission-data` by domain (at least its script).
- Guard: `AssetDomainBoundaryTest` (JUnit text test like `TemplateCommentHygieneTest`, no new
  dependency): (1) every `@{/js/…}`/`@{/css/…}` reference resolves to a classpath file — no test
  checks this for JS today; (2) a template under `templates/<d>/` references only `core`, `<d>` or
  allow-listed assets; (3) a script under `js/<d>/` reads only `window.*` names owned by core or `<d>`
  (ownership from per-domain sections in `frontend/types/globals.d.ts`) plus an explicit allow-list
  (initially `inventory → materialexchange: krtMaterialRelease`). Optionally ESLint
  `no-restricted-properties` per folder (core rule).

**Pros** — Ownership and review scope per domain; the boundary becomes testable; enables per-domain
type-check ratchets (FEA-06) and module boundaries later (FEA-12).
**Cons** — A large rename diff: 121 `th:src`, ~74 stylesheet links, and — if templates move —
~164 `return "<view>"` statements and ~102 `"x :: fragment"` selectors in frontend Java, 93 hard-coded
asset paths in 25 test classes, and path-based lint config (`frontend/.stylelintrc.json:7`,
`frontend/build.gradle.kts:488-508`, `frontend/eslint.config.mjs:66-74`, the page-CSS regex in
`TemplateCommentHygieneTest`).
**Risks, regressions, security** — A missed reference 404s and is only visible as a `resource_error`
beacon → guard (1), `ScriptLoadOrderE2eTest`, the `DialogA11yE2eTest` page walk. Content hashing and
the `immutable` header apply to nested paths (`WebMvcConfig.java:67-76` registers the content strategy
on `/**`). CSP is path-independent; `/css/**` and `/js/**` stay `permitAll` recursively
(`SecurityConfig.java:187-188`); page authorization is decided by security config and controllers,
not by template location. Loading bank code only on bank pages shrinks every other page's script
surface.
**Effort** L (M for JS/CSS alone). **Prerequisites** — ADR "assets are organised by domain" (amends
the `frontend/CLAUDE.md` rule "Page CSS goes into `static/css/pages/<page>.css`" and REQ-UI-023's
wording); align with the frontend-Java package split; vault `Frontend` note.

---

### FEA-02 — The shared global scope is the de-facto module system

**Evidence**
- All 100 scripts parse as classic scripts (espree `sourceType: script`); 0 `import`/`export`, 0
  `type="module"`, 0 import maps in templates.
- 50 files are IIFE-wrapped; the other 50 declare **523 top-level names (443 function
  declarations)** into the one global scope — joborder 146, inventory 142, promotion 92, refinery 54,
  catalogue 51 (§3.2).
- **23 names are declared at top level in 2–3 files** (§3.4), e.g. `refinery-orders-create.js` /
  `refinery-orders-details.js` share 8 (`addMaterialRow` also in `orders-create.js:180`), the two
  promotion-admin scripts share `apiCall`, `openModal`, `closeModal`, `toastSuccess`, `toastError`,
  the Lager pair shares `openUmbuchenModal`, `submitUmbuchen`. No pair is loaded on the same page
  today (§3.4), so nothing breaks — but between function declarations the later file silently wins,
  and TS6200 reports collisions only between checked files.
- Bare-name cross-file calls: `inventory-admin.js`/`inventory-my.js` call six top-level functions of
  `inventory-note-modal.js` (`openNoteModal`, `saveNote`, …), which calls back through
  `window.krtNotifyInventoryChanged`; `orders-index.js` ↔ `orders-index-reorder.js` through
  `window.__ordersDragging` / `window.krtRefreshOrdersQueue`.
- 36 files carry `/* global */` headers with 276 names; 34 files consume 213 names from
  `frontend/types/thymeleaf-bootstrap.d.ts` (`orders-detail.js` alone 71); 41 `window.*` values are
  provided only by inline template blocks.
- Defensive code: 185 `typeof window.X` checks, 133 `window.krtFetch` presence guards in 64 files,
  35 `window.krtEvents &&` guards.
- Positive: 57 `window.*` names are written by scripts and 56 are declared in
  `frontend/types/globals.d.ts` (the 57th is `window.onclick`, FEA-14); 0 implicit globals, 0
  top-level `this`, 0 `arguments`, 0 `document.currentScript` (eslint-scope + AST).

**Impact** — Each domain's client-side "API" is whatever its scripts leave on the global scope;
cross-domain use is invisible except through `/* global */` headers, and it is the main obstacle to
modules (FEA-12).

**Proposed change (stage 1, no module switch)**
1. Wrap every page module in an IIFE and publish one namespace object per domain API
   (`window.krtInventoryNotes`, …), each declared in a per-domain section of `globals.d.ts`.
2. ESLint core rule `no-implicit-globals` with `lexicalBindings: true` as an error, starting with
   `core` and each clean domain; names that must stay global (functions an inline bootstrap calls,
   e.g. `openEditFinanceModal`) are listed with `/* exported */` — a tool directive ADR-0214 allows.
3. Replace bare-name cross-file calls by namespace calls; break the note-modal ↔ page cycle with an
   event (`krt:inventory-changed`) or a callback passed at init.
4. Drop presence guards only where REQ-FE-023 guarantees the global exists; keep the deliberate
   `if (!window.krtFetch) return;` fallback in form listeners (REQ-FE-002) until modules replace it.

**Pros** — ~523 global names shrink to a few dozen declared namespaces; collisions become
impossible; the dependency graph is explicit; prerequisite for modules and for FEA-01's boundary test.
**Cons** — Touches ~50 files; ADR-0069's "no IIFE wrapping" rule (ADR-0069:41-42) needs an
amendment — it described the verbatim move, and half the files are wrapped today anyway.
**Risks, regressions, security** — A now-private function still called by an inline script or another
file throws `ReferenceError` → `lintJs` `no-undef` against updated `/* global */` headers,
`typecheckJs`, domain E2E, `ScriptLoadOrderE2eTest`. Security: fewer optional `window.*` look-ups
also means fewer names an element `id` could clobber (named properties) when a script failed to load.
**Effort** M. **Prerequisites** — ADR-0069 amendment; FEA-07 for the inline callers.

---

### FEA-03 — Duplicated code between domain assets (FE-SIMP-03 re-evaluated)

**Evidence** (`40-frontend-assets-clones.py`: windows of K consecutive whitespace-normalised
non-trivial lines; a line counts when its window occurs elsewhere; §3.9)
- **JavaScript**: 2,018 of 26,369 non-trivial lines (7.7 %; K=4: 12.3 %). Pairs:
  refinery-orders-create↔details 112/112; materialboerse-release↔materialgesuch-modal 85/79;
  item-collection↔material-collection 84/84 of 98 each; inventory-admin↔inventory-my 69 (K=4:
  154/167); personal-inventory-blueprints↔personal-inventory 52; missions↔operations 42;
  material-detail↔materials-profit-calculation 37; the promotion-admin pair 30;
  admin-personal-blueprints-purge↔bank 20; audit-log↔bank 15. Inside files: mission-detail.js 204,
  orders-detail.js 184, inventory-my.js 147, bank.js 91.
- **Three pickers for the same job**: the shared combobox `krt-searchable-select.js` (REQ-FE-011/016),
  the legacy `autocomplete.js` (`krtAutocomplete`, used by 6 pages incl. `members.html:193`) and a
  bespoke participant search in `mission-detail.js:1767-1840` (`closeAllLists`, its own debounced
  `/users/search`).
- **CSS**: 1,856 of 12,003 non-trivial lines (15.5 %): mission-detail.css↔operation-detail.css 169;
  refinery create↔details 97 (whole files); bank.css↔styles.css 52; inventory-admin↔inventory-my 50;
  the five error-page sheets 32–46 identical lines each; one 19-line admin block copied into 7 page
  sheets.
- **Templates**: 3,711 of 18,553 non-trivial lines (20.0 %): `fragments/inventory-stack-entries.html`
  608/688 — fragments `stackEntriesMy` (:5) and `stackEntriesAdmin` (:415) differ mainly in the
  `inv-my-*`/`inv-admin-*` trigger prefixes; inventory-admin.html↔inventory-my.html 194; refinery
  create↔details 88; admin/personal-inventory↔personal-inventory 87;
  admin/personal-blueprints↔personal-inventory-blueprints 77;
  bank-account-detail↔org-unit-bank-account-detail 59 (including copied inline scripts); the page
  header copied 83× (FEA-09).
- **FE-SIMP-03 today**: the JavaScript is largely consolidated (`inventory-common.js`, `createLager`);
  `submitUmbuchen` still exists twice (61 vs 86 lines, 33 % similar). The duplication now sits in the
  Lager templates and in a doubled trigger vocabulary (`inv-admin-toggle-multi` /
  `inv-my-toggle-multi` …, 7 uses each).

**Impact** — Most copies are inside one domain (refinery, joborder, inventory, promotion, personal
inventory/blueprints) and dissolve naturally with domain folders; the cross-domain ones
(audit↔bank, blueprint purge↔bank, missions↔operations, mission↔operation detail CSS) point to missing
core components (list filter/pager, detail layout, confirm/download helpers). ADR-0177 recorded the
cost pattern: "every dialog defect had to be fixed once per shape".

**Proposed change**
- JS: one refinery order-form module for create/details; one collection module for item/material
  (parameterised by kind); a shared picker for the two materialexchange dialogs; delete the promotion
  admin wrappers in favour of `krtFetch`/`krtModal`/`toast.js`; migrate `autocomplete.js` users and the
  mission participant search to the combobox (after checking every `krtAutocomplete` use case maps to
  a combobox kind).
- CSS: one error sheet; a core detail-page sheet for the mission/operation block; the admin block into
  `styles.css` components; one refinery form sheet.
- Templates: one parameterised stack-entries fragment (a `mode`/prefix parameter) and one trigger set
  for both Lager instances, scoped by container.
- Guard: a duplication ratchet test (the same K-window hashing in JUnit, baseline per asset kind that
  may only go down), plus the touched areas' E2E.

**Pros** — A defect is fixed once; smaller files are easier to type-check (FEA-06).
**Cons** — Merged modules need configuration seams; review effort.
**Risks, regressions, security** — Two formerly separate copies may have drifted on purpose (refinery
details has live sync and conflict handling that create lacks) → `RefineryOrderCreateE2eTest`,
`RefineryOrderLiveSyncE2eTest`, `InventoryStackViewE2eTest`, `InventorySharedLagerLiveSyncE2eTest`,
`LiveSyncSectionMapParityTest`, `I18nDictionaryCoverageTest`. A unified admin/member fragment must keep
every `sec:authorize`/model-flag condition on admin-only controls (server-side `@PreAuthorize` stays
the authority, but a leaked control would expose admin UI). Merged builders must stay under
`no-unsanitized`.
**Effort** M–L overall (S–M per pair). **Prerequisites** — none; easier after FEA-01/FEA-02.

---

### FEA-04 — Modern JavaScript: ES2015 syntax without ES2020+ idioms

**Evidence** (full table §3.5) — 0 `var`, 4,462 `const`, 502 `let`; 305 arrows vs 1,564 function
expressions (1,123 are callbacks using neither `this` nor `arguments`) and 1,443 declarations; 0
classes, private names or static blocks; `?.` 14 (3 files) vs 245 `a && a.b` guards in 53 files
(e.g. `static/js/bank.js:334,337,344`); `??` 43 (7 files) vs 34 null-check ternaries
(`a != null ? a : b`, `a !== null ? a : b` — e.g. `orders-detail.js:1437,1737`; the `!== null` form
is not equivalent to `??` for `undefined`); 0 `??=`/`||=`/`&&=`; 0 `.at()` vs 5 `a[a.length - 1]`
(e.g. `org-chart.js:462,575`); 0 `Object.hasOwn`, `structuredClone`, `toSorted`/`toReversed`/`with`,
`Object.groupBy`, `Promise.withResolvers`, Set methods, iterator helpers; 64 template literals (9 files) vs 664 string concatenations with literals (82 files); 71
`indexOf` comparisons vs 24 `includes`; 190 `.then()` (45 files) vs 87 `await` (17 files); 52 index
loops vs 17 `for…of`; 0 object destructuring; 52 `parseInt` without radix; 57 global `isNaN`; 39
`innerText` writes; 3 `keyCode`/`which`; 1 `AbortController` (`static/js/krt-fetch.js:707`), 0
`AbortSignal.timeout`/`any`; 61 `fetch` calls, 0 XHR.

**Impact** — Readability and consistency, not capability: none of the ES2022+ APIs would remove real
complexity here except abortable reads (FEA-10). The 245 hand-written null guards are the relevant
smell — `?.` plus checkJs makes the nullability explicit.

**Proposed change** (zero new dependencies — ESLint core rules with autofix, one commit each, added
to the existing `.git-blame-ignore-revs`)
1. `prefer-template` (664 sites) — compatible with `no-unsanitized`, which accepts template literals
   whose expressions go through the configured escapers.
2. `prefer-arrow-callback` (default `allowUnboundThis: true` leaves `this`-using callbacks alone).
3. `prefer-object-spread`, `prefer-object-has-own`, optionally `logical-assignment-operators`,
   `radix`, and `no-restricted-properties` for `keyCode`/`which`.
4. `?.`/`??` by hand while opting a file into `@ts-check` (semantics differ for falsy non-null values:
   `a && a.b` yields `a` for `0`/`''`/`false`; `||` vs `??` differ for `0`/`''`).
5. New code: `async`/`await`, `for…of`, destructuring. ES2024+ APIs only after FEA-05.

**Pros** — Consistent modern style; autofix is mechanical. **Cons** — Very large diffs (≈1,800
sites) that conflict with in-flight PRs; review cost.
**Risks, regressions, security** — `prefer-arrow-callback` only fixes where `this`/`arguments` are
unused; the `&&`→`?.` and `||`→`??` traps above are the real risk → `lintJs`, `typecheckJs`, full E2E
matrix. Security neutral; keep `no-unsanitized` green for every sink touched.
**Effort** S (rules) + M (review). **Prerequisites** — FEA-05 for anything beyond ES2023.

---

### FEA-05 — No browser-support baseline is documented

**Evidence** — A search of the repository (`*.md`, `*.json`, `*.mjs`, `*.kts`, `*.yml`) and the
vault for browserslist/baseline/"supported browsers"/"browser support" finds nothing; only
`CHANGELOG-ARCHIVE.md:2225` refers to an unstated "Supportbasis". The effective floor is implicit:
`frontend/eslint.config.mjs:26` (`ecmaVersion: 2023`), `frontend/tsconfig.json:24-29`
(`target`/`lib: ES2023`, enforced only in the 44 checked files), CSS that needs `@layer` (64 files),
`:has()` (29), range media queries (73) and `:focus-visible` (34), native `<dialog>.showModal()`
(`krt-modal.js`), and Playwright's current Chromium/Firefox/WebKit (`frontend/build.gradle.kts:414`).
The installable web app is the iPhone/iPad client (vault `10 Systems/Frontend.md:154-160`).

**Impact** — Every modern-feature proposal (FEA-04/08/11/12) needs this answer first; today an
ES2024 API in an unchecked file passes every gate.
**Proposed change** — State the baseline (e.g. "Baseline widely available" or explicit minimum
versions including iOS Safari) in `docs/specs/ui-design-system.md` or a new REQ-FE; align ESLint
`ecmaVersion`, TS `target`/`lib` and the E2E matrix; ban APIs above the baseline with core
`no-restricted-properties`/`no-restricted-globals` rather than adding `eslint-plugin-compat` (a new
dependency with its own data feed).
**Pros** — One decision unblocks the others. **Cons** — Needs current support data (§3.11).
**Risks, security** — None directly; a baseline that is too high strands older iOS devices.
**Effort** S. **Prerequisites** — research-agent data (§3.11).

---

### FEA-06 — Type-checking coverage, and where the TypeScript plan stands

**Evidence**
- 44 of 100 files carry `// @ts-check` (37 on line 1, 7 after the licence header); 14,956 of 39,577
  lines (37.8 %). The largest unchecked files: `mission-detail.js` 3,249, `bank.js` 2,400,
  `orders-detail.js` 2,325 (20.2 % of all lines). By domain (§3.2): mission 0 %, bank 2 %, promotion
  9 %, joborder 14 %, catalogue 15 %, hangar 16 % vs inventory 74 %, blueprint 73 %, notification and
  exchange 100 %; core 13 of 19 files — unchecked `krt-client-error.js` 282, `scu-decimal-input.js`
  248, `sidebar.js` 209, `common-handlers.js` 201, `datetime-splitter.js` 169, `autocomplete.js` 131.
- `frontend/tsconfig.json:34` keeps `noImplicitAny: false`; `KrtLiveSyncApi.createReceiver(config:
  unknown): unknown` (`frontend/types/globals.d.ts:329`) leaves the most-used shared API after
  `krtFetch` (51 calls) untyped.
- Stale statements: REQ-FE-018 (`docs/specs/frontend-ajax-mutations.md:1720-1722`) "40 files (of 96)";
  `docs/TYPESCRIPT_MIGRATION_PLAN.md:21-26` (44,078 lines/95 files, ~2,480 inline lines, 114 `th:src`,
  40 of 96) vs today 39,577/100, 1,860, 121, 44 of 100; and its "Why not now" (:34-38): "`type=module`
  implies `defer`, which changes execution order across all 114 script tags" — since FE-PERF-05, 120
  of 121 external scripts already are `defer` (REQ-FE-023).
- Plan triggers: 1 (bundler) not met; 2 (inline JS zero) not met — 1,860 lines; 3 (≥ 80 % checked) not
  met — 44 %; 4 (defect pressure) UNKNOWN.

**Proposed change** — (1) finish `core` (6 files, ~1,240 lines), then a folder ratchet: a text test
fails when a file in a "checked folder" lacks `// @ts-check` or when the checked-file count drops
(with FEA-01 this reads "domain X is fully checked"); (2) prioritise by risk — joborder
(`orders-detail.js`: 71 bootstrap names, 23 `innerHTML` sinks in the domain), mission, bank; (3) type
`createReceiver`; (4) Phase 3 `noImplicitAny` once core is clean (ADR-0130 measured 514 errors —
recount); (5) correct REQ-FE-018 and the plan (numbers and the execution-order argument).
**Pros** — Null-safety and DTO drift protection reach the files that change most.
**Cons** — Cast churn in DOM-heavy code; the three largest files want splitting first (FEA-03).
**Risks, regressions, security** — Added null guards change behaviour (ADR-0130 precedent: early
returns replace `TypeError`s) → E2E. Security positive: `krt-client-error.js`, the beacon every CSP
and script error depends on, is unchecked today.
**Effort** L, incremental. **Prerequisites** — none; synergy with FEA-01/02.

---

### FEA-07 — Inline template JavaScript: ADR-0069 is unfinished

**Evidence**
- 70 inline `<script>` blocks in 60 templates, 1,860 lines: 56 `th:inline="javascript"` bootstrap
  blocks (1,357 lines) and 14 plain blocks (503 lines); 142 `var` in 18 templates; none linted,
  type-checked or Prettier-formatted (the lint globs cover `static/js` only).
- Top-level statements (espree over each block): 320 data declarations vs 53 logic statements in 17
  templates — `bank-account-detail.html:346-400` (functions with `var`), `org-unit-bank-account-detail.html:445-552`,
  `org-unit-bank.html`, `bank-manage.html`, `bank-holder-detail.html`, `members.html:155-343` (187
  lines, 6 functions), `member-edit.html` (72 + 43 lines), `mission-detail.html` (the two ADR-0069
  exceptions), five error pages with an empty `DOMContentLoaded` handler (`error/404.html:27-30`), the
  materialboerse/materialgesuch fragments (dictionary merges) and `fragments/head.html` (stubs by
  design).
- `templates/members.html:215-225` declares 11 `const MSG_… = "[[#{…}]]"` in a script **without**
  `th:inline` — the only such block (§3.13, item 8). The markers feed the delete, sync and
  consolidate dialogs (`members.html:242,248,259,320`). ADR-0069:57-60 recorded that such markers in
  inventory-admin "already reached users as literal text". Whether Thymeleaf renders these literally
  or HTML-escapes them is UNKNOWN — a MockMvc render of `/members` asserting the localized text
  settles it; either way JavaScript context gets HTML (not JS) escaping.
- `fragments/head.html:42-63` keeps a `krtScuInput` stub whose `normalize()` duplicates
  `static/js/scu-decimal-input.js:14-27` verbatim (with `var`). It served parse-time callers, which
  REQ-FE-023 now forbids; whether anything still needs it is UNKNOWN.
- Vault drift: `10 Systems/Frontend.md:1010-1015` says ADR-0069 "removed page-scoped inline JavaScript
  from every Thymeleaf template", with only `head.html` and the error pages as exceptions.

**Proposed change** — (1) move the bank inline logic into the bank module(s), members/member-edit
into their modules; delete the five empty error-page blocks; (2) turn the members markers into a
`th:inline="javascript"` dictionary with `/*[[…]]*/` natural-template literals (the ADR-0069 recipe);
(3) extend `InlineScriptLoadOrderTest` (or a sibling): no `[[`/`[(` in a script without `th:inline`,
no function declarations or listeners in non-head inline scripts, no `var`; (4) remove the
`krtScuInput` stub if `ScriptLoadOrderE2eTest` and a search for parse-time use confirm it is dead;
(5) optional — replace the bootstrap blocks by one typed JSON data island per page
(`<script type="application/json" id="krt-page-data">`, read via `JSON.parse`): not executable, no
nonce, one declared shape per page (the TS plan's Phase 6 `__KRT_BOOTSTRAP__` idea), and the natural
input for modules.
**Pros** — Reaches TS-plan trigger 2; the remaining logic becomes lintable and checkable; fixes a
probable i18n defect.
**Cons** — Per-page work on pages with peer sync (REQ-FE-015). JSON islands need a Java-side
dictionary builder.
**Risks, regressions, security** — Inline scripts run during parsing, modules after it; moving code
changes timing → `InlineScriptLoadOrderTest`, `ScriptLoadOrderE2eTest`, bank/members E2E. For (5):
the JSON must be serialised with `<`, `>`, `&`, U+2028/U+2029 escaped as `\uXXXX`, or a message
containing `</script>` breaks out; pin with a render test that feeds exactly that.
**Effort** M (1–4), L with (5). **Prerequisites** — `I18nDictionaryCoverageTest` keys; vault
correction.

---

### FEA-08 — CSS: modern foundations, split lint coverage, three token defects

**Evidence**
- Layers: 64/64 files declare `@layer base, components, page, migration, utilities;`; 0 unlayered
  rules; rules per layer — page 1,566, components 778, migration 249, utilities 2, base 2; 21
  `!important` (19 in `styles.css`).
- In use: `:has()` 29, `:where()` 18, `:is()` 1, `:focus-visible` 34 (vs `:focus` 38, with 18
  `outline: none|0`), range media queries in 73 of 74 `@media`, one global
  `prefers-reduced-motion` reset (`static/css/styles.css:3627-3633`), `accent-color` 9, `gap` 430,
  `display: grid` 42, `inset` 6, `dvh` 4 (vs `vh`/`vw` 18).
- Unused: native nesting, `@container`/`container-type`, `@scope`, `color-mix()`, `clamp()`,
  `@property`, `@starting-style`, view transitions, `subgrid`, popover — all 0; logical properties 10
  vs 1,611 physical ones (862 side-specific margin/padding/offset/border, 749 width/height family,
  plus 59 `text-align: left|right`) — no RTL locale exists.
- Tokens: 130 custom properties, 2,567 `var()` uses, 127 hex literals; 107 `rgb()` and 48 legacy
  `rgba()` — all 48 `rgba()` in `static/css/pages/**`.
- Split lint: `frontend/.stylelintrc.json:7` excludes `static/css/pages/**` from
  `stylelint-config-standard`; those 54 files (5,208 lines) are checked only by
  `.stylelintrc.templates.json` (2 rules) through `lintCssInline` (`frontend/build.gradle.kts:488-508`).
  Drift example: `pages/mission-detail.css:37` `rgba(231, 126, 35, 0.3)` restates
  `--glow-primary` (`styles.css:152`).
- **Defects**: `var(--color-black)` at `styles.css:2999` and `:3006` — no such token (it is
  `--color-bg-black`, `styles.css:32`), so `background` falls back to its initial value: the sticky
  `.demand-material-search` header and `.demand-search-input` on the material-demand page are
  transparent. `var(--color-text)` at `bank.css:1240` is undefined too (colour inherits).
- Dead: 6 tokens defined and never used (`--color-dept-combat`, `--color-dept-research`,
  `--action-primary`, `--action-emphasis`, `--action-neutral`, `--tracking-overline` — may be a
  deliberate design-system reserve; check the design skill); 8 of 251 `krtm-*` classes in
  `inline-migration.css` referenced nowhere (e.g. `krtm-display-none-z-index-3100-25fb`,
  `krtm-width-auto-7e37`); 692 `krtm-*` uses (246 distinct) remain in templates.
- z-index: 45 declarations with 32 distinct values, no token scale.
- Minifier `minifyStaticCss` (`frontend/build.gradle.kts:332-386`): regex comment strip + line trim;
  `styles.css` 129.6 KB → 97.5 KB raw (17.5 KB gzip before minification). With ADR-0214 the only
  comments left are 4 licence headers (4 of 64 files).

**Proposed change** — (1) lint `static/css/pages/**` with the standard config (autofix
`color-function-notation`, `alpha-value-notation`, …) and extend the existing `color-no-hex` override
pattern to page sheets; (2) `CustomPropertyExistenceTest` (text test): every `var(--x)` resolves to a
definition or to an allow-list of script-set properties (`--krt-footer-height`, `--hint-shift`) —
fixes the three undefined uses (or a Stylelint core rule if one exists, §3.11); (3) delete the dead
`krtm-*` classes, decide on the 6 tokens; (4) a z-index token scale, and prefer the top layer
(dialog/popover) to numbers; (5) `color-mix()` for alpha variants of brand tokens instead of restated
RGB; (6) forced-colors-safe focus (`outline: 2px solid transparent` or `:focus-visible` rules instead
of `outline: none` + box-shadow); (7) design-led options: container queries for components rendered in
narrow and wide contexts (combobox, cards), nesting only where it removes repetition without raising
specificity inside the `page` layer, `@view-transition { navigation: auto }` as a zero-JS enhancement;
logical properties not recommended (no RTL requirement); (8) the minifier can stay (low value, low
risk) or go.
**Pros** — Removes a visible defect; one lint standard; tokens become the single colour source.
**Cons** — Autofix touches most page sheets; (7) needs design decisions.
**Risks, regressions, security** — Visual changes → `CascadeLayerOrderTest`,
`AccessibleTextTintTest`, `TouchClassLayoutE2eTest`, screenshot review. CSS cannot execute script;
`style-src-attr 'none'` and the five `data:` SVG images are unaffected.
**Effort** S (1–3), M (4–6), design decision (7). **Prerequisites** — the design skill for (3) and
(7); FEA-05 for (5) and (7); REQ-UI-024/ADR-0212 unchanged.

---

### FEA-09 — Templates: no layout, copied chrome, flat fragments

**Evidence** — Every page assembles its chrome itself: 90 calls of `fragments/head :: head`, 83 of
`sidebar`, 39 of `toast`, and 83 pages carry an inline `<header>` (hamburger + brand) of 11–13 lines
in 5 variants (73 identical) — ≈ 1,000 copied lines. `fragments/` mixes 11 shared and 19 domain
fragments. Good practice already in place: 106 `modal-wrapper` calls (ADR-0177), 11 `<template>`
elements, 26 `<details>`, 6 `<datalist>`, 0 `style=`/`on*=`/`<style>`/`javascript:`/`[(…)]`; 30
`th:utext` (7 Markdown values rendered with commonmark `escapeHtml(true).sanitizeUrls(true)`,
`MarkdownRenderer.java:39`; 23 message-bundle HTML). `frontend/.htmlhintrc:14-16` keeps
`inline-style-disabled`, `inline-script-disabled` and `style-disabled` off although there are 0
violations; 37 `<button>` elements have no `type` (default `submit` inside a form). 0 `popover`, 1
`inert`.
**Proposed change** — (1) a native Thymeleaf layout fragment
(`fragments/layout :: page(title, links, content, scripts)`) carrying head, sidebar, header and toast —
no Layout Dialect dependency; (2) the header into that chrome with parameters for the 5 variants;
(3) enable the three HTMLHint rules (free lint-time duplicates of CSP/REQ-UI-023) and pin
`type` on buttons with a text test if HTMLHint has no rule (§3.11); (4) per-domain template folders
(FEA-01).
**Pros** — Chrome changes once; five header variants cannot drift further.
**Cons** — Touches 83–90 templates; Thymeleaf precedence traps (`th:replace` outranks `th:if`, vault
`Frontend.md:858-866`).
**Risks, regressions, security** — Render regressions → MockMvc render tests,
`TemplateCommentHygieneTest`, `ModalWrapperRenderTest`, E2E. Keep every `sec:authorize` on the element
it guards; `th:utext` usage unchanged.
**Effort** M. **Prerequisites** — none; do it together with FEA-01 if templates move.

---

### FEA-10 — Security of the asset code: reads, redirects, CSP, CSV

**Evidence**
- CSP (`frontend/src/main/java/.../config/SecurityHeaders.java:43-49`): `default-src 'self';
  object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action …;
  upgrade-insecure-requests; img-src 'self' data:; font-src 'self' data:; style-src 'self' 'nonce-…';
  style-src-attr 'none'; script-src 'nonce-…' 'strict-dynamic'` — no `report-to`, no Trusted Types.
  0 `<style>` elements and 0 script-created style elements → the style nonce is unused; 0 `data:`
  fonts (the 5 `data:` SVGs in `styles.css:1160,1562,1570,3414,4773` need `img-src data:`); 0
  `<base>`; 0 dynamic script insertion, so `'strict-dynamic'` currently propagates to nothing.
  `SecurityHeadersTest.java:79-99` pins the nonce form of `style-src`.
- Writes (FE-SEC-03 holds): 0 non-GET `fetch` outside `krt-fetch.js`/`krt-client-error.js` (AST), 0
  XHR. The lint selector (`frontend/eslint.config.mjs:5-12`) cannot see an init object built
  elsewhere, `new Request(…)` or XHR (REQ-FE-002 names the first as a review item,
  `frontend-ajax-mutations.md:156`).
- **Reads**: 56 raw GET `fetch` calls in 32 files; only 3 files honour the re-auth answer on their
  reads (`admin-materials.js:328`, `notifications.js`, `p4k-import.js:68`); ≥ 15 sites use
  `res.ok ? res.json() : fallback` (`krt-user-search.js:36`, `krt-catalog-search.js:55`,
  `krt-bank-account-search.js:43`, `inventory-my.js:364,1000,1270`,
  `admin-exchange-clients.js:231,568,738`, …) — the shape `HandRolledFetchGateContractTest` calls the
  defect ("trusting `ok` … true for a followed redirect") and pins for `p4k-import.js` and
  `notifications.js` only. About 35 of the 56 calls send no `X-Requested-With` (heuristic,
  `40-frontend-assets-xhrmarker.py`), which is what `TermsAcceptanceGateFilter.isAjax`
  (`TermsAcceptanceGateFilter.java:198-200`) keys on, so under a pending terms gate they follow a
  redirect to an HTML page; after session loss `SsoReAuthenticationEntryPoint.java:116-131` answers
  401 + `X-Reauthenticate`, which these sites turn into an empty picker instead of a re-login.
- Redirect helpers: `reauthRedirect` (`krt-fetch.js:119-131`) and `termsGateRedirect` (:171-177)
  accept any string starting with `/`, including `//host` and `/\host`; `safe-url.js:3-15` and the
  server-side FE-SEC-02 rule reject both. Four navigate-after-write sites assign a JSON `targetUrl`
  unchecked (`inventory-input.js:484`, `orders-create.js:525`, `refinery-orders-create.js:406`,
  `refinery-orders-details.js:754`). All these values come from our own headers, SSE events, WS
  close reasons or controller JSON, and a `javascript:` target is blocked by the nonce-only
  `script-src` → defence in depth only (open-redirect class if a server path ever reflected input).
- CSV: `pmCsvEscape` (`static/js/promotion-manage.js:505-517`) quotes but does not neutralise a
  leading `=`, `+`, `-`, `@`, TAB or CR (formula injection); cells carry member usernames
  (`data-pm-username`) and admin-authored topic names.
- Positive: 0 third-party browser JS/CSS (`frontend/oss-bundled-components.json` lists only Lato;
  charts are server-side SVG); the npm tool chain runs `npm ci` with `ignore-scripts=true`
  (`frontend/.npmrc`), 228 lockfile packages, 0 with install scripts; 0 unescaped `[(…)]` inlining.

**Proposed change** — (1) `krtFetch.get`/`getJson(url, {accept, signal})`: always sends
`X-Requested-With`, runs `maybeReauthenticate` + `maybeTermsGate`, refuses redirected or non-JSON
answers, supports `AbortController` (supersede typeahead requests) and optionally
`AbortSignal.timeout`; route all 56 reads through it, then forbid `fetch(` outside
`krt-fetch.js`/`krt-client-error.js` entirely and ban `XMLHttpRequest` (core
`no-restricted-syntax`/`no-restricted-globals`) — which also closes the init-built-elsewhere gap;
(2) both redirect helpers and the four `targetUrl` navigations call `safeSameOriginUrl` (loaded
before `krt-fetch.js`, `fragments/head.html:71,75`) — or `krtFetch` validates `targetUrl` once for all
navigate-after-write callers (REQ-FE-006); (3) CSP: `style-src 'self'`, `font-src 'self'`, `base-uri 'none'`;
keep `'strict-dynamic'` only if FEA-12 is pursued; (4) prefix `'` to CSV cells that start with
`= + - @ TAB CR`.
**Pros** — One read path replaces 56; a recurring defect class disappears; the CSP shrinks for free.
**Cons** — Visible behaviour change: pickers that silently emptied after session loss now send the
user to login or the terms page (the intended contract).
**Risks, regressions, security** — CSRF unaffected (GETs carry none; writes stay on
`krtFetch.write`); SSE/WebSocket are not `fetch`; tenancy/authorization unchanged (server-side). Guards:
the generic lint rule replaces the per-module `HandRolledFetchGateContractTest`;
`SecurityHeadersTest` re-pinned; `LoginSmokeE2eTest` and domain E2E; a new E2E for a picker after
session loss and under a pending terms gate (no E2E covers either today — the terms gate is only
passed through in `E2eSupport`/`BackendSeeder`; `TermsAcceptanceGateFilterTest` covers the server
side); the client-error beacon (`unhandled_rejection`) as production signal.
**Effort** M. **Prerequisites** — REQ-FE-002 amended to cover reads; security-spec CSP requirement
amended for (3).

---

### FEA-11 — Trusted Types: feasible now, report-only first

**Evidence**
- Sinks: 72 `innerHTML` assignments in 23 files — 23 clears (`= ''`), 6 static literals, 22 builders
  escaping every interpolation inline, 21 assignments of a locally built markup variable (accepted
  by `no-unsanitized`'s variable tracing, REQ-FE-022 text); 0 `outerHTML`, `insertAdjacentHTML`,
  `document.write`, `srcdoc`. The sanctioned helpers `krtFetch.setTrustedHtml`
  (`krt-fetch.js:623-629`, the codebase's only `eslint-disable`) and `replaceWithTrustedHtml`
  (:638-645, parses into a `<template>`) with 6 + 3 call sites plus `swap()`; one
  `DOMParser.parseFromString` of a whole same-origin page outside the helpers
  (`admin-materials.js:323-337`).
- Script sinks: 0 `eval`, 0 `new Function`, 0 string timers, 0 `createElement('script')`, 0
  `setAttribute('on…')`; no third-party code; no template `on*=` attributes.
- Telemetry exists: `krt-client-error.js:232-242` reports every `securitypolicyviolation` as
  `csp_violation` (effective directive + blocked origin) into `basetool_client_error_total{kind}`;
  alert `ClientErrorSpike` (`monitoring/prometheus/alerts/business.yml:621`); the dashboard notes
  "the CSP has no report-uri, so this beacon is its only signal"
  (`monitoring/grafana/dashboards/07-basetool-operations.json:2331`).
- No prior discussion of Trusted Types in repo or vault (grep, 0 hits).

**Proposed change** — (1) one core file creating exactly two policies: `krt-fragment` (identity; used
only by the two fragment helpers and by the DOMParser path, which moves to `krtFetch.swap` or a
fragment endpoint) and `krt-html` (returns TrustedHTML only from a `krtHtml` tagged template that
escapes every interpolation); (2) 23 clears → `el.replaceChildren()`; (3) 49 builders → `krtHtml`
tagged templates or DOM building / `<template>` clones; (4) an identity shim when
`window.trustedTypes` is absent, so unsupported engines behave as today; (5)
`Content-Security-Policy-Report-Only: require-trusted-types-for 'script'; trusted-types krt-fragment
krt-html` → watch `csp_violation` → enforce in the main policy (no `'allow-duplicates'`, no `default`
policy).
**Pros** — DOM XSS becomes a browser-enforced property instead of lint + review; policy creation is
centralised and auditable; no dependency.
**Cons** — Every future sink must use the helpers; enforcement coverage depends on engine support
(§3.11).
**Risks, regressions, security** — A missed sink throws at runtime and disables a feature → the
report-only phase plus an E2E listener: add a `securitypolicyviolation`/console collector to the
`DialogA11yE2eTest` page walk (89 dialogs on 82 pages, vault `Frontend.md:948-953`) and fail on any
Trusted Types violation. The identity `krt-fragment` policy must only ever receive same-origin
fragment bodies — the REQ-FE-022 contract, kept by review and by restricting
`trustedTypes.createPolicy` to one file (core `no-restricted-properties`/`no-restricted-syntax`);
`no-unsanitized` gets `escape.taggedTemplates: ['krtHtml']`; `SecurityHeadersTest` pins the new
directives. Security impact strictly positive when report-only precedes enforcement; monitoring needs
no new metric (`csp_violation` covers it, REQ-OBS-011 label set unchanged).
**Effort** M. **Prerequisites** — FEA-05 (support data), REQ-FE-022 and the security spec's CSP
requirement amended, an ADR; batch with FEA-10 (3).

---

### FEA-12 — ES modules and a bundler: what it would take

**Evidence**
- Binding today: REQ-FE-018 ("classic non-module `<script>` tags sharing one global scope",
  `frontend-ajax-mutations.md:1668-1672`), ADR-0069, ADR-0125 (no build step; its 2026-09-23 amendment
  declined even comment-stripping minification so the served file stays the checked file).
- The main historical objection is gone: 120 of 121 external scripts are `defer` (REQ-FE-023); non-
  `async` module scripts are deferred too (their relative order to classic `defer` scripts: §3.11).
  Module-semantics hazards are small: 0 implicit globals, 0 top-level `this`, 0 `arguments`, 0
  `document.currentScript`; 58 sloppy-mode files would become strict. The real work: 523 global names
  (FEA-02), 213 bootstrap constants and 41 inline-provided `window` values (FEA-07).
- **Versioning trap**: `WebMvcConfig.java:67-76` serves every asset tree with content-hash
  versioning and `Cache-Control: max-age=31536000, public, immutable` — for unversioned URLs as well
  (`StaticResourcesCachingTest.java:105-111` asserts it for `/css/pages/error-404.css`). Only `@{…}`
  URLs in templates get the hash; a static `import './x.js'` inside a module would request the
  unversioned URL, which a browser may keep for a year across deploys.
- No runtime third-party JS; the scripts every page loads weigh 175.9 KB raw / 45.8 KB gzip across
  20 files (§3.8).

**Proposed change** — Modules only after FEA-02 and FEA-07, and without a bundler: (1) a head
fragment rendering `<script type="importmap" th:attr="nonce=${cspNonce}">` that maps bare specifiers
(`krt/fetch`, `inventory/common`) to hashed `@{/js/…}` URLs (a small bean over `ResourceUrlProvider`);
(2) one entry module per page in its domain folder; (3) `krt-client-error.js` and the `krtEvents`
stub stay classic and first; (4) boundaries with the core ESLint rule `no-restricted-imports`
(patterns per domain folder); (5) `modulepreload` for entry dependencies; (6) a test that every
import specifier in `static/js/**` is a bare specifier present in the import map (no relative
imports). **A bundler is not recommended**: little to gain (HTTP/2, edge gzip, `immutable` caching,
no npm runtime dependencies, no scheduled TypeScript conversion) against a build-time component that
can inject code into every shipped asset — a compromised bundler release would be site-wide XSS that
the CSP cannot stop, because we nonce its output — plus source maps and served ≠ checked files
(breaking ADR-0125's premise). Revisit only with TS-plan Phase 4.
**Pros (modules)** — Explicit dependency graph, lint-enforced domain boundaries with a core rule, no
global names, strict mode, and a natural path to `.ts` files.
**Cons** — Import-map and nonce plumbing; all 121 script tags and 70 inline blocks reworked; three
binding documents to amend.
**Risks, regressions, security** — Stale modules if any import bypasses the map → guard (6).
Execution-order changes → `ScriptLoadOrderE2eTest`, `InlineScriptLoadOrderTest` rewritten for
modules. CSP: the import map needs the nonce; nonce trust must propagate to static and dynamic imports
under `'strict-dynamic'` (§3.11); module fetches use CORS mode — same-origin, and
`Cross-Origin-Resource-Policy: same-origin` is already set — fine. A failed import aborts the page
module, which yields the same no-JS fallback REQ-FE-002's presence guards provide today.
**Effort** XL overall (L per large domain). **Prerequisites** — FEA-02, FEA-05, FEA-07; a new ADR
superseding ADR-0069's load model, amendments to REQ-FE-018/023 and ADR-0125; TS plan update.

---

### FEA-13 — Lint gates that check less than they claim, and ADR-0214 residue

**Evidence**
- `no-unused-vars` is `warn` (`frontend/eslint.config.mjs:40-47`) and `lintJs` passes no
  `--max-warnings 0` (`frontend/build.gradle.kts:563-575`) → unused code never fails CI (count
  UNKNOWN; ESLint was not run).
- `lintJs` and `prettierCheck` read only `static/js` (+ css, types) (`frontend/build.gradle.kts:570,
  583-590`), while `eslint.config.mjs:75-98` configures `scripts/**/*.mjs` and ADR-0130 says those
  files "join the ESLint and Prettier globs" — only the npm scripts include them
  (`frontend/package.json:12,16`), and CI runs Gradle (`.github/workflows/ci.yml:63`), so
  `gen-api-types.mjs` and its test are ungated.
- Page CSS under two rules (FEA-08); lax HTMLHint (FEA-09).
- `frontend/tsconfig.json:2-19` holds a 17-line prose `"//"` field — a comment in all but syntax,
  against ADR-0214's intent; its content is in ADR-0125 already.
- ADR-0212:79-80 instructs "with a comment naming what it beats" — contradicted by ADR-0214; the code
  (no such comments) is right.
- `CspNonceFilter.java:37` Javadoc names `SecurityConfig` as the header writer; it is
  `SecurityHeaders`.
- ADR-0214 compliance otherwise: 0 prose comments in 100 scripts (73 licence headers, 444 JSDoc
  blocks, 334 inline type casts, 83 directives, 1 `eslint-disable`), 4 licence headers in 64
  stylesheets — licence-header coverage differs by asset type.

**Proposed change** — `no-unused-vars: error` (or `--max-warnings 0`); add `scripts/**/*.mjs` to both
Gradle tasks; delete the `"//"` field; amend ADR-0212's sentence; fix the Javadoc; decide one
licence-header policy for assets and pin it with a text test.
**Pros/Cons** — Cheap; the first change may surface existing warnings to fix.
**Risks, security** — None; closes "green but not checking" gaps. **Effort** S.
**Prerequisites** — ADR-0212 and ADR-0130 text amendments.

---

### FEA-14 — Event delegation, property handlers, backdrop clicks (low priority)

**Evidence** — `static/js/event-delegation.js:18-29` adds one `document` listener per registration and
runs `closest('[data-trigger="…"]')` in each: 237 registrations (168 `click`), so every click walks
the DOM once per registration on the page. 291 distinct `data-trigger` values (528 uses) carry informal
domain prefixes (`inv-admin-*`, `inv-my-*`, `oc-*`, `pa-*`, `bp-*`, `bank-*`). `window.onclick = …` in
`announcement.js:43`, `inventory-common.js:1574` and `mission-data.js:218` implements
backdrop-click-to-close through one global slot (the later assignment wins); 15 `.onX =` property
handlers in total.
**Proposed change** — One listener per event type with a `Map` from action to handlers (walking
`closest('[data-trigger]')` upward to keep nested-trigger semantics); domain-namespaced action names
enforced by the FEA-01 boundary test; backdrop close as a `krtModal` option instead of
`window.onclick` (`closedby` attribute: §3.11).
**Pros/Cons** — Small speed-up, clearer ownership; renaming 291 actions is churn.
**Risks, security** — Handler order changes → `DialogA11yE2eTest` (Escape/backdrop), domain E2E; no
security effect. **Effort** S–M. **Prerequisites** — FEA-01 for the namespacing; REQ-UI-013 wording
for the backdrop option.

---

### FEA-15 — Documentation and vault drift found (for the owning session to correct)

| Where | Says | Code says |
| --- | --- | --- |
| `docs/specs/frontend-ajax-mutations.md:1720-1722` (REQ-FE-018) | 40 files of 96 under `@ts-check` | 44 of 100 |
| `docs/TYPESCRIPT_MIGRATION_PLAN.md:21-26,90` | 44,078 lines/95 files; ~2,480 inline lines; 114 `th:src`; 40 of 96; "all 96 files" | 39,577/100; 1,860; 121; 44 of 100 |
| `docs/TYPESCRIPT_MIGRATION_PLAN.md:34-38` | `type=module` → `defer` changes the order of all script tags | 120 of 121 are `defer` since FE-PERF-05 |
| vault `10 Systems/Frontend.md:1010-1015` | inline page JS removed from every template (exceptions: head, error pages) | 5 bank pages, members, member-edit keep logic (FEA-07) |
| `docs/adr/0069-…md:41-42` | "no IIFE wrapping" | 50 of 100 scripts are IIFE-wrapped |
| `docs/adr/0212-…md:79-80` | "with a comment naming what it beats" | ADR-0214 forbids it; no such comments |
| `docs/adr/0130-…md:56` | `scripts/**/*.mjs` joins the ESLint and Prettier globs | not in the Gradle gate tasks |
| `CspNonceFilter.java:37` | header written in `SecurityConfig` | written in `SecurityHeaders` |

**Effort** S. No security impact.

---

## 3. Data appendix

### 3.1 Domain map of the assets

JS (`static/js`, 100 files). *Core* = loaded by `fragments/head.html` (16 incl. two domain scripts
marked †), the sidebar fragment (`sidebar.js`, `unsaved-changes.js`; 83 pages) or the toast fragment
(`toast.js`; 39 pages), plus `autocomplete.js` (6 pages).

| Domain | Scripts |
| --- | --- |
| core (19) | krt-client-error (sync, first), escape-html, safe-url, event-delegation, common-handlers, krt-modal, krt-fetch, krt-live-sync, krt-user-search, krt-catalog-search, krt-searchable-select, datetime-splitter, scu-decimal-input, inline-style-apply, krt-filter-panel, sidebar, unsaved-changes, toast, autocomplete |
| mission (3) | mission-detail, missions, mission-presence |
| operation (3) | operation-detail, operations, operations-index |
| joborder (7) | orders-create, orders-detail, orders-index, orders-index-reorder, orders-material-demand, item-collection, material-collection |
| inventory (10) | inventory-admin, inventory-my, inventory-common, inventory-input, inventory-index, inventory-material, inventory-game-item, inventory-herkunft, inventory-note-modal, inventory-materialboerse |
| personalinventory (1) | personal-inventory |
| blueprint (7) | personal-inventory-blueprints, -import, -recipe, blueprint-overview, admin-blueprints, admin-default-blueprints, admin-personal-blueprints-purge |
| hangar (2) | hangar, hangar-squadron |
| materialexchange (3) | materialboerse, materialboerse-release, materialgesuch-modal |
| refinery (4) | refinery-orders-create, refinery-orders-details, refinery-orders-index, refinery-yield-badge |
| bank (2) | bank (9 pages), krt-bank-account-search † |
| notification (2) | notifications †, notification-rules |
| catalogue (12) | materials, material-detail, materials-matrix, materials-profit-calculation, locations, uex, admin-materials, admin-material-aliases, ship-data, mission-data (mixed, FEA-01), p4k-import, sync-reports |
| audit (1) | audit-log |
| promotion (5) | promotion-admin-rank-requirements, promotion-admin-topics, promotion-manage, promotion-my-evaluations, promotion-overview |
| orgchart / leadership (1 / 1) | org-chart / leitung |
| orgunit (4) | special-commands, special-command-detail, admin-org-structure, members |
| identity (7) | profile, terms-accept, admin-terms, discord-registrations, pending-approval, admin-deletion-requests, admin-person-search |
| dashboard (2) / admin (1) | index, announcement / admin-settings |
| exchange (3) | admin-exchange-clients, connected-apps, connected-apps-confirm |

CSS: shared = `styles.css` (design system, layers base+components+page block), `inline-migration.css`
(migration + utilities), `pages/toast.css`, five `pages/error*.css`; public = `pages/sc-links.css`;
domain root sheets = `bank.css`, `leitung.css`, `materialboerse.css`, `materials-overview.css`,
`org-chart.css`, `personal-inventory.css`, `promotion-admin.css`, `terms-accept.css`; every other
`pages/*.css` belongs to the one template that links it (`TemplateCommentHygieneTest` pins "linked
exactly once").

Templates: 90 pages + 30 fragments; shared = head, sidebar, footer, toast, icons, modal-wrapper,
components, pagination, scu-hint, unsaved-modal, fankit + 5 error pages; public = impressum, privacy,
licenses, landing, sc-links, app-link-help, terms; the rest by domain (per-file assignment in
`40-frontend-assets-domains.py`).

### 3.2 Metrics per domain (`python 40-frontend-assets-domains.py`)

All line counts are `wc -l` semantics (newline count per file).

| Domain | JS files | JS lines | @ts-check files (lines %) | CSS files | CSS lines | Templates | Template lines | Inline JS lines (logic stmts) | innerHTML sinks | raw GET fetch | bootstrap names | global top-level names |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| core | 19 | 4297 | 13 (71%) | 0 | 0 | 0 | 0 | 0 (0) | 2 | 2 | 0 | 3 |
| shared | 0 | 0 | – | 8 | 6934 | 16 | 1059 | 117 (8) | 0 | 0 | 0 | 0 |
| public/legal | 0 | 0 | – | 1 | 72 | 7 | 482 | 0 (0) | 0 | 0 | 0 | 0 |
| mission | 3 | 3581 | 0 (0%) | 1 | 898 | 2 | 1758 | 112 (3) | 0 | 2 | 12 | 14 |
| operation | 3 | 701 | 0 (0%) | 2 | 482 | 2 | 588 | 33 (0) | 0 | 1 | 6 | 11 |
| joborder | 7 | 4319 | 1 (14%) | 4 | 335 | 6 | 2272 | 184 (0) | 23 | 10 | 103 | 146 |
| inventory | 10 | 5476 | 6 (74%) | 6 | 266 | 8 | 2784 | 199 (0) | 4 | 10 | 21 | 142 |
| personalinventory | 1 | 494 | 0 (0%) | 1 | 1236 | 2 | 416 | 36 (0) | 3 | 1 | 0 | 0 |
| blueprint | 7 | 3091 | 4 (73%) | 2 | 214 | 5 | 1046 | 166 (0) | 9 | 6 | 0 | 0 |
| hangar | 2 | 653 | 1 (16%) | 2 | 211 | 2 | 458 | 18 (0) | 1 | 0 | 2 | 1 |
| materialexchange | 3 | 2212 | 0 (0%) | 1 | 462 | 4 | 786 | 102 (4) | 8 | 4 | 0 | 0 |
| refinery | 4 | 1626 | 2 (55%) | 2 | 236 | 3 | 833 | 63 (0) | 2 | 3 | 27 | 54 |
| bank | 2 | 2457 | 1 (2%) | 1 | 1423 | 15 | 3612 | 224 (22) | 2 | 3 | 0 | 0 |
| notification | 2 | 1170 | 2 (100%) | 1 | 38 | 2 | 254 | 0 (0) | 3 | 5 | 0 | 0 |
| catalogue | 12 | 3497 | 1 (15%) | 11 | 1066 | 15 | 2222 | 118 (0) | 15 | 4 | 19 | 51 |
| audit | 1 | 265 | 0 (0%) | 0 | 0 | 1 | 227 | 0 (0) | 0 | 1 | 0 | 0 |
| promotion | 5 | 1528 | 2 (9%) | 6 | 1237 | 5 | 1273 | 56 (0) | 0 | 0 | 44 | 92 |
| orgchart | 1 | 661 | 0 (0%) | 1 | 574 | 2 | 534 | 20 (0) | 0 | 0 | 1 | 2 |
| leadership | 1 | 379 | 0 (0%) | 1 | 91 | 1 | 301 | 15 (0) | 1 | 0 | 0 | 0 |
| orgunit | 4 | 503 | 3 (62%) | 4 | 129 | 7 | 1250 | 328 (16) | 0 | 0 | 4 | 3 |
| identity | 7 | 1044 | 5 (43%) | 4 | 128 | 9 | 1010 | 39 (0) | 0 | 1 | 1 | 0 |
| dashboard | 2 | 156 | 0 (0%) | 1 | 13 | 2 | 165 | 13 (0) | 0 | 0 | 2 | 2 |
| admin | 1 | 178 | 0 (0%) | 1 | 24 | 1 | 216 | 17 (0) | 0 | 0 | 11 | 1 |
| exchange | 3 | 1289 | 3 (100%) | 3 | 115 | 3 | 527 | 0 (0) | 0 | 3 | 0 | 1 |
| **total** | 100 | 39577 | 44 (38%) | 64 | 16184 | 120 | 24073 | 1860 (53) | 73 | 56 | 253 | 523 |

("innerHTML sinks" includes the one `parseFromString`; "bootstrap names" counts per-file uses, 213
distinct.)

### 3.3 Cross-domain couplings (complete list from the dependency graph)

| From | To | Mechanism |
| --- | --- | --- |
| inventory (`inventory-materialboerse.js`, `inventory-my.html`) | materialexchange | `window.krtMaterialRelease`; `materialboerse.css`; `fragments/materialboerse-modal` |
| audit (`admin/audit-log.html:6`) | bank | links `bank.css`, uses 9 `bank-*` classes (14 classes that `bank.css` defines) |
| catalogue (`admin/materials.html:6`) | promotion | links `promotion-admin.css` for `.form-row/.form-input/.form-hint` |
| blueprint (`admin/default-blueprints`, `admin/personal-blueprints`, `personal-inventory-blueprints`) | personalinventory | link `personal-inventory.css` |
| core (`common-handlers.js:157-167`) | catalogue | calls `window.filterTable` defined in three catalogue scripts |
| core head (`fragments/head.html:144`) | bank | loads `krt-bank-account-search.js` on every page |
| catalogue page `admin/mission-data` | orgunit + catalogue | squadrons, job types, frequency types in one page/script |

JS dependency edges into core (per script) are listed by `python 40-frontend-assets-jssum.py deps`;
the most used shared APIs: `krtEvents.on` 272 references (237 registrations with literal event and
action names), `krtFetch` 174 (+111 `.write`, 83 `.swap`,
15 `.submitForm`), `krtI18nText` 171, `krtModal.close`/`.open` 72/58, `krtLiveSync.sendChanged` 54,
`krtLiveSync.createReceiver` 51.

### 3.4 Global scope

- Top-level names by file (non-IIFE files only): `python 40-frontend-assets-jssum.py toplevel`;
  totals 523 names in 50 files (443 function declarations).
- Collisions (`python 40-frontend-assets-jssum.py collisions`, 23 names): `filterTable`
  (admin-materials, locations, ship-data); `openUmbuchenModal`, `submitUmbuchen` (inventory-admin,
  inventory-my); `collectionRow`, `broadcastCollectionChanged`, `collectionTransfer`,
  `onDeliveredToggle` (item-collection, material-collection); `sortTable`, `updateSortIndicators`
  (material-detail, materials-profit-calculation); `openDeleteModal` (operation-detail,
  operations-index); `addMaterialRow` (orders-create, refinery-orders-create, refinery-orders-details);
  `toastSuccess`, `toastError`, `apiCall` (both promotion-admin scripts); `closeModal`, `openModal`
  (both promotion-admin scripts + ship-data); `calcScu`, `updateOutputMaterial`, `setStartedAtNow`,
  `removeMaterialRow`, `updateMethodRatings`, `updateEndsAt`, `updateProfitPreview` (both refinery
  order scripts). None of the colliding pairs is loaded by the same template
  (`python 40-frontend-assets-tplsum.py jsusage`).
- `/* global */` headers: 36 files, 276 names. Window APIs written by scripts: 57, of which 56 are in
  `globals.d.ts` (72 `Window` members declared there in total).

### 3.5 Modern JavaScript inventory (AST, 100 files, 39,577 lines)

| Feature | Uses | Files | Candidates for the modern form |
| --- | --- | --- | --- |
| `var` / `let` / `const` declarations | 0 / 502 / 4,462 | 0 / 68 / 98 | — (inline templates: 142 `var`) |
| arrow functions | 305 | 29 | 1,123 function-expression callbacks without `this`/`arguments` (93 files) |
| function expressions / declarations | 1,564 / 1,443 | 99 / 91 | 443 declarations at top level |
| classes, private `#x`, static blocks, class fields | 0 | 0 | — |
| optional chaining `?.` | 14 | 3 | 245 `a && a.b` (53 files) |
| nullish `??` | 43 | 7 | 34 null-check ternaries (16 files) |
| `??=` `\|\|=` `&&=` | 0 | 0 | — |
| `Array.prototype.at` | 0 | 0 | 5 `a[a.length - 1]` |
| `Object.hasOwn` | 0 | 0 | 1 `hasOwnProperty` |
| `structuredClone` | 0 | 0 | 0 JSON round-trip clones |
| `toSorted`/`toReversed`/`toSpliced`/`with` | 0 | 0 | 3 copy-then-sort |
| `Object.groupBy`/`Map.groupBy`, `Promise.withResolvers` | 0 | 0 | 1 `new Promise` |
| Set methods, iterator helpers | 0 | 0 | 9 `new Set`, 6 `new Map` |
| `AbortController` / `AbortSignal.timeout`,`any` | 1 / 0 | 1 / 0 | typeahead reads (FEA-10) |
| `fetch` / XHR | 61 / 0 | 34 / 0 | 56 raw GETs outside the core transport |
| `EventSource` / `WebSocket` | 1 / 1 | 1 / 1 | — |
| template literals (interpolated) | 64 (60) | 9 | 664 string concatenations with literals (82 files) |
| `async` functions / `await` / `.then()` | 65 / 87 / 190 | 17 / 17 / 45 | — |
| `for…of` / index loops over `.length` | 17 / 52 | 11 / 21 | — |
| destructuring object / array, spread | 0 / 14, 4 | – | — |
| `includes` / `indexOf` comparisons | 24 / 71 | 10 / 32 | — |
| ES modules, `type="module"`, import maps, dynamic `import()` | 0 | 0 | — |
| `'use strict'` | 42 files (function level) | – | 58 sloppy-mode files |
| `innerHTML` / `outerHTML` / `insertAdjacentHTML` / `document.write` | 72 / 0 / 0 / 0 | 23 | FEA-11 |
| `setTrustedHtml` / `replaceWithTrustedHtml` calls | 6 / 3 | 4 / 3 | — |
| `textContent` / `innerText` writes | 318 / 39 | 64 / 8 | — |
| `element.style.*` writes (of them `display`) | 188 (99) | 27 (display) | — |
| `replaceChildren` / `MutationObserver` / `ResizeObserver` / `IntersectionObserver` | 10 / 2 / 1 / 0 | – | 23 `innerHTML = ''` |
| `parseInt` without radix / global `isNaN` / `keyCode`,`which` | 52 / 57 / 3 | – / 23 / – | — |
| `window.onclick =` | 3 | 3 | FEA-14 |
| regex literals (named groups, `v` flag) | 92 (0, 0) | 29 | — |

Config: `frontend/eslint.config.mjs` — `js.configs.recommended` + `no-var`, `prefer-const`,
`object-shorthand`, `eqeqeq: smart`, `no-undef`, `no-empty` (allowEmptyCatch), `no-unused-vars`
(warn), `no-restricted-syntax` (raw fetch writes), `no-unsanitized/method` + `/property`;
`ecmaVersion: 2023`, `sourceType: script`. `frontend/tsconfig.json` — `allowJs`, `checkJs: false`,
`noEmit`, `strict`, `noImplicitAny: false`, `useUnknownInCatchVariables: false`, `target`/`lib`
ES2023 + DOM, `moduleDetection: legacy`. Tool versions (`frontend/package-lock.json`): TypeScript
7.0.2, ESLint 10.11.0, eslint-plugin-no-unsanitized 4.1.5, Stylelint 17.15.0, Prettier 3.9.9, HTMLHint
1.9.2; Node 24.21.0 (`gradle/libs.versions.toml:47`).

### 3.6 Templates

| Measure | Value |
| --- | --- |
| templates / pages / fragments | 120 / 90 / 30 |
| external `<script>` tags: total / `defer` / sync / `async` / `type=module` / with nonce | 121 / 120 / 1 (`krt-client-error.js`) / 0 / 0 / 121 |
| inline `<script>` blocks: total / `th:inline="javascript"` / plain / with nonce | 70 / 56 / 14 / 70 |
| inline lines: bootstrap / plain; templates with inline scripts | 1,357 / 503; 60 |
| inline top-level statements: data / logic (templates with logic) | 320 / 53 (17) |
| `var` inside inline scripts | 142 in 18 templates |
| scripts per page after fragment resolution (min–max) | 17 (`error.html`) – 26 (`inventory-my.html`) |
| `style=` / `on*=` / `<style>` / `javascript:` / `[(…)]` | 0 / 0 / 0 / 0 / 0 |
| `th:utext` (Markdown / bundle) | 30 (7 / 23) |
| `modal-wrapper` calls / `<dialog>` elements | 106 / 1 (in the wrapper) |
| `<template>` / `<details>` / `<datalist>` / `popover` / `inert` | 11 / 26 / 6 / 0 / 1 |
| `data-trigger` values distinct / uses | 291 / 528 |
| forms POST / GET / with `th:action` | 105 / 78 / 127 |
| `<button>` without `type` / `<img>` without `alt` | 37 / 0 |
| aria-* / role attributes | 827 / 140 |
| pages with the copied `<header>` block (variants) | 83 (5) |

Fragment reuse (top): `modal-wrapper :: modal` 106 calls in 43 templates, `head` 90, `sidebar` 83,
`toast` 39, `pagination` 18 + `pageSizePicker` 10, `scu-hint` 32, `components :: alert` 15,
`components :: filterToggle` 9, `org-chart-node :: ocNode` 12, `owner-picker` 5
(`python 40-frontend-assets-tplsum.py frags`).

### 3.7 CSS inventory (postcss, 64 files)

| Measure | Value |
| --- | --- |
| files / lines (`wc -l`) / bytes | 64 / 16,184 / 402,263 |
| root sheets / page sheets (lines) | 10 (10,976) / 54 (5,208) |
| `@layer` order statement / unlayered rules | 64 of 64 / 0 |
| rules per layer | page 1,566 · components 778 · migration 249 · utilities 2 · base 2 |
| `!important` | 21 (styles.css 19, inventory-admin/-my page sheets 1 each) |
| custom properties defined / `var()` uses / hex literals | 130 distinct (146 definitions) / 2,567 / 127 |
| `rgb()` / legacy `rgba()` | 107 / 48 (all in `pages/`) |
| `@media` / range syntax / `prefers-reduced-motion` / `prefers-color-scheme` / `forced-colors` | 74 / 73 / 1 / 0 / 0 |
| `:has` / `:where` / `:is` / `:focus-visible` / `:focus` / `outline: none\|0` | 29 / 18 / 1 / 34 / 38 / 18 |
| nesting / `@container` / `@scope` / `@supports` / `@property` / `@starting-style` | 0 / 0 / 0 / 1 / 0 / 0 |
| `color-mix` / `clamp` / `min`,`max` / `calc` / `oklch` / `light-dark` | 0 / 0 / 9 / 27 / 0 / 0 |
| logical properties / physical (side-specific margin, padding, offsets, borders / width-height family) | 10 / 862 / 749 |
| `display: flex` / `grid` / `inline-flex` | 381 / 42 / 82 |
| z-index declarations (distinct values) | 45 (32) |
| vendor-prefixed properties | 14 in 3 files |
| undefined tokens used | `--color-black` ×2 (styles.css:2999,3006), `--color-text` ×1 (bank.css:1240); `--krt-footer-height`, `--hint-shift` are set by scripts |
| unused tokens | 6 (FEA-08) |
| `krtm-*` classes defined / unused / uses in templates (distinct) / uses in JS | 251 / 8 / 692 (246) / 82 |
| comments | 4 (licence headers) |

### 3.8 Payload loaded on every page (raw bytes, lines; gzip = sum of per-file `gzip -9`)

`krt-fetch.js` 37,298 (938) · `krt-searchable-select.js` 27,458 (744) · `notifications.js` 22,567
(678) · `krt-live-sync.js` 13,711 (405) · `krt-client-error.js` 9,290 (282) · `krt-modal.js` 8,828 (251) ·
`scu-decimal-input.js` 8,209 (248) · `sidebar.js` 8,132 (209) · `krt-filter-panel.js` 7,720 (216) ·
`common-handlers.js` 6,939 (201) · `datetime-splitter.js` 6,893 (169) · `toast.js` 4,855 (145) ·
`krt-catalog-search.js` 3,027 (81) · `unsaved-changes.js` 2,858 (96) · `krt-bank-account-search.js`
2,050 (57) · `event-delegation.js` 2,025 (52) · `krt-user-search.js` 1,778 (53) ·
`inline-style-apply.js` 1,173 (37) · `safe-url.js` 561 (18) · `escape-html.js` 482 (21) — **175,854
bytes raw, 45,791 bytes gzip** (sidebar/unsaved-changes on 83 pages, toast on 39, the rest on all).
Stylesheets on every page: `styles.css` 129,597 (gzip 17,468), `inline-migration.css` 34,977 (gzip
5,135) before `minifyStaticCss` (97,484 / 27,340 after, from `frontend/build/generated/minified-css`,
build of 2026-09-27 — may be stale).

### 3.9 Duplication (`python 40-frontend-assets-clones.py <kind> <K>`)

| Kind | K | Non-trivial lines | In duplicated windows |
| --- | --- | --- | --- |
| JS | 6 | 26,369 | 2,018 (7.7 %) |
| JS | 4 | 26,369 | 3,256 (12.3 %) |
| CSS | 6 | 12,003 | 1,856 (15.5 %) |
| templates | 6 | 18,553 | 3,711 (20.0 %) |

Top pairs are listed in FEA-03. Non-trivial = whitespace-collapsed lines that are not blank, not pure
punctuation (`}`, `});`, …), not closing tags of common elements and not inside the licence header.

### 3.10 Security inventory

CSP directives (`SecurityHeaders.java:43-49`) and what the assets need:

| Directive | Value | Needed by the assets? |
| --- | --- | --- |
| `script-src` | `'nonce-…' 'strict-dynamic'` | nonce yes (121 external + 70 inline tags); `'strict-dynamic'` unused (0 dynamic scripts) |
| `style-src` | `'self' 'nonce-…'` | `'self'` yes; nonce no (0 `<style>`, 0 script-created styles) |
| `style-src-attr` | `'none'` | yes (0 `style=`; CSSOM writes are not governed) |
| `img-src` | `'self' data:` | `data:` yes (5 SVG data URIs in styles.css) |
| `font-src` | `'self' data:` | `data:` no (0 data fonts) |
| `base-uri` | `'self'` | no `<base>` → `'none'` possible |
| `object-src` / `frame-ancestors` | `'none'` / `'none'` | ✔ |
| reporting / Trusted Types | none / none | beacon `csp_violation` is the only signal |

Sinks and reads: FEA-10/FEA-11 (numbers from `python 40-frontend-assets-jssum.py sinks|fetch`).
Other: 34 `safeSameOriginUrl` calls; 21 `location.assign`/`replace`/`href =` navigations — literal
paths, `safeSameOriginUrl` results, the two gate helpers and four unchecked JSON `targetUrl`s
(FEA-10); 6 dynamic `href`s are `blob:` object URLs for downloads.

### 3.11 To verify (research agent) — browser support and tooling facts not established here

1. Minimum browser versions — especially iOS/iPadOS Safari — for features already shipped: `@layer`,
   `:has()`, media-query range syntax, `<dialog>.showModal()`, `:focus-visible`, `inert`,
   `replaceChildren`, `AbortController` → the implied de-facto floor.
2. Support for `Array.prototype.at`, `Object.hasOwn`, `structuredClone`, `toSorted`/`toReversed`/
   `toSpliced`/`with`, `Object.groupBy`/`Map.groupBy`, `Promise.withResolvers`, Set methods, iterator
   helpers, `AbortSignal.timeout`/`any`, RegExp `v` flag.
3. Trusted Types in Safari/WebKit and Firefox as of 2026-09; whether `el.innerHTML = ''` needs
   TrustedHTML under enforcement; `DOMParser.parseFromString` and `<template>.innerHTML` under
   enforcement; whether report-only violations fire in-page `securitypolicyviolation` events.
4. Import maps: engine support; inline import map under a nonce; nonce trust for static and dynamic
   imports under `'strict-dynamic'`; execution order of non-async `type="module"` scripts relative to
   classic `defer` scripts; whether module code can read `const`s declared by classic inline scripts.
5. CSS: relaxed native nesting, container queries, `@scope`, `color-mix()`, `@starting-style` +
   `transition-behavior: allow-discrete`, cross-document view transitions, Popover API and its
   interplay with a modal `<dialog>` (top-layer order, inertness), CSS anchor positioning,
   `<dialog closedby>`, `dvh`/`svh`.
6. Tooling: a Stylelint 17 core rule for unknown custom properties; `prefer-template` autofix safety
   with numeric operands; `eslint-plugin-no-unsanitized` `escape.taggedTemplates` semantics; an
   HTMLHint rule for button `type`; whether Spring Framework ships a `ResourceTransformer` that
   rewrites JS module import specifiers to versioned URLs; esbuild/Rollup installation with
   `ignore-scripts=true` (optional native binaries).

### 3.12 The September audit's asset findings, re-checked against the code

| Finding | Recommended then | State on `95e945326` | Verdict |
| --- | --- | --- | --- |
| FE-SEC-03 | writes via `krtFetch`, lint rule | 0 non-GET raw `fetch` outside the transport (AST); rule at `frontend/eslint.config.mjs:5-12`; blind to an init built elsewhere, `new Request`, XHR; 56 raw GET reads | done for writes; extend to reads (FEA-10) |
| FE-SEC-05 | one escaper, `no-unsanitized` | one escaper pair; plugin in `lintJs` (`eslint.config.mjs:52-65`); 1 `eslint-disable`; 72 `innerHTML` assignments by AST (the audit counted 96 sinks — methods may differ) | done; next step Trusted Types (FEA-11) |
| FE-SIMP-03 | `inventory-common.js`, strings in bundles | JS residual 69–167 lines; template duplication 608 + 194 lines; `I18nDictionaryCoverageTest` guards the strings | JS done, templates open (FEA-03) |
| FE-SIMP-04 / 04b | `krtModal`, native `<dialog>` | `showModal`/`krtm-modal-open` only in `krt-modal.js`; 106 wrapper calls; 3 `window.onclick` backdrop handlers | done; small residue (FEA-14) |
| FE-MOD-01 | drop `var(--x, #hex)` fallbacks, Stylelint rule | rule in `.stylelintrc.json:19-28` and `.stylelintrc.templates.json:8-17`; 3 undefined-token uses now resolve to initial/inherited values | done; add an existence check (FEA-08) |
| FE-MOD-02 | cascade layers | 64/64 layered, 0 unlayered (`CascadeLayerOrderTest`) | done |
| FE-MOD-03 | `prefer-const`, `object-shorthand`, `@ts-check` when touching | both rules on (`eslint.config.mjs:37-38`); 44/100 checked | done; ramp continues (FEA-06) |
| FE-PERF-02 | comments out, page `<style>` to files | 0 `<style>`; 54 page sheets — under a 2-rule lint | done; lint gap (FEA-08) |
| FE-PERF-05 | `defer`; optional minification | 120/121 `defer`; minification declined (ADR-0125 amendment) | done |

### 3.13 Commands and scripts

All scripts are in the scratchpad with the prefix `40-frontend-assets-`. The Node scripts load
espree 11.2.0, eslint-scope 9.1.2, eslint-visitor-keys, globals, htmlparser2 9.1.0, postcss 8.5.28 and
postcss-selector-parser 7.1.6 from `$MAIN_CHECKOUT/frontend/node_modules` as parser libraries
only (the worktree has no `node_modules`); every analysed file is read from the worktree.

1. `find static/js -type f | wc -l` (100), `find static/css -type f | wc -l` (64),
   `find templates -type f | wc -l` (120), `… | xargs wc -l` (39,577 / 16,184 / 24,073).
2. `node 40-frontend-assets-jsast.mjs 40-frontend-assets-jsast.json` — per-file AST metrics, sinks,
   fetch calls, window reads/writes, eslint-scope `through` references, top-level declarations,
   collisions, dependency graph.
3. `python 40-frontend-assets-jssum.py totals|comments|candidates|sinks|fetch|deps|collisions|toplevel|krtapi|events`.
4. `node 40-frontend-assets-tpl.mjs 40-frontend-assets-tpl.json` and
   `python 40-frontend-assets-tplsum.py totals|inline|pages|jsusage|cssusage|frags|triggers|toastgap`.
5. `node 40-frontend-assets-css.mjs 40-frontend-assets-css.json` and
   `python 40-frontend-assets-csssum.py totals|files`.
6. `python 40-frontend-assets-clones.py js 6|js 4|css 6|tpl 6`.
7. `python 40-frontend-assets-domains.py` (domain table, cross-domain lists).
8. Script-context markers: a regex over `<script>` blocks without `th:inline` for `[[` → only
   `members.html` (11).
9. `python 40-frontend-assets-xhrmarker.py` (raw GETs without a visible `X-Requested-With`).
10. Greps (worktree `static/js` unless noted): `@ts-check` (44 files, 7 not on line 1);
    `typeof window\.` (185); `if (!window.krtFetch|window.krtFetch &&` (133 in 64 files);
    `x.ok ? x.json()` shapes (15+); `window.onclick` (3); `Math.random` (jitter only);
    `parseInt(` without radix (52); `createElement('style')` (0); `<style` in templates (0);
    `data:` URLs in CSS (5); `<base` (0); `trusted.?types` in repo and vault (0);
    `browserslist|baseline|supported browsers` in repo and vault (0); header block in templates
    (83 pages, 5 variants, Python regex over `<header>…</header>` containing `hamburger`).
11. Sizes: `wc -c` and Python `gzip.compress(…, 9)` over the 20 every-page scripts and the two global
    stylesheets.
