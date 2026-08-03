> **Doc type:** Living plan — not yet executed. Last reviewed: 2026-08-02.
> **Owner area:** FE/UI · **Related ADRs:** ADR-0125 (the current decision), ADR-0069, ADR-0012/0013 · **Spec:** REQ-FE-018

# TypeScript migration plan

## Status

**Not started, and deliberately not scheduled.** The current decision (ADR-0125) is to type-check
the existing JavaScript with `tsc --noEmit` + JSDoc rather than to convert it. This document exists
so the full migration stays a *costed option* instead of something rediscovered from scratch, and so
the conditions under which it becomes the right call are written down rather than argued each time.

Nothing here is a commitment. Phase 0 is done; phases 1–6 are the plan.

## Why not now

Three properties of the codebase, all measured rather than assumed:

|                                            |                              |
|--------------------------------------------|------------------------------|
| Hand-written JS                            | 40 023 lines in 87 files     |
| Still inline in Thymeleaf templates        | ~2 245 lines in 58 templates |
| `<script th:src>` tags                     | 106                          |
| JS build step today                        | none                         |
| Files under `// @ts-check`                 | 28 of 87                     |
| Backend DTO schemas available from OpenAPI | 253                          |

The blocking constraint is **ADR-0069**. The static scripts are classic non-module `<script>` tags
that share one global lexical environment, and that was fixed deliberately: no IIFE wrapping,
cross-block consumption of bare identifiers, `typeof` self-references, and a preserved end-of-body
load position so parse-time DOM lookups and the ordering against `event-delegation.js` /
`krt-fetch.js` stay unchanged.

TypeScript's value is concentrated in modules. Getting it means `type="module"`, which implies
`defer`, which changes execution order across all 106 script tags — the exact invariant ADR-0069
preserved. Avoiding that means compiling to classic scripts (`module: "none"`), which pays for a
build step while forfeiting imports, encapsulation and tree-shaking. Neither variant reaches the
~2 245 inline template lines.

Meanwhile the single highest-value gap — untyped backend DTOs — is **already closed** by ADR-0125
without any of that cost, via build-time generation from `openapi.json`.

## Triggers — when to revisit

Execute this plan when **any** of these becomes true. Absent all of them, the cost/benefit does not
support it:

1. **A bundler arrives for independent reasons** — a component framework, code splitting, or an npm
   runtime dependency that cannot be vendored. The build step then already exists and the main cost
   line disappears. *This is the most likely trigger.*
2. **The inline template JS reaches zero.** With ADR-0069's extraction finished, the "a checker can
   never see part of the code" objection dies, and whole-codebase coverage becomes reachable.
3. **`@ts-check` coverage passes ~80% of files** with the JSDoc annotation burden visibly exceeding
   what equivalent TypeScript syntax would cost. At that point JSDoc is being used as a worse
   spelling of TypeScript and the conversion is mostly mechanical.
4. **Sustained defect pressure that types would have caught** — recurring incidents whose root cause
   is a shape mismatch inside a single module, not at the DTO boundary the generated types already
   guard.

## Phases

Each phase is independently shippable and independently revertible. **No phase may change runtime
behaviour**; the Playwright E2E suite is the behavioural gate throughout, exactly as it was for
ADR-0069's extraction.

### Phase 0 — Checked JavaScript *(done, ADR-0125)*

`tsc --noEmit` in the build gate, opt-in `// @ts-check`, hand-written declarations for the
cross-file and bootstrap contracts, and DTO types generated from `openapi.json` at build time.
Everything below builds on this rather than replacing it.

### Phase 1 — Finish `@ts-check` coverage of `static/js`

Take the remaining 60 files to green, largest-value first. Not busywork: this is where the real
migration cost is discovered per file, and every fix survives into the TypeScript version.

- Known work per file, measured on the 27 already converted: DOM narrowing casts
  (`EventTarget` → `Element`, `Element` → `HTMLInputElement`) dominate; Javadoc tags (`{@code …}`,
  `{@link …}`, `@param name {shape}`) must be converted to real JSDoc in the 5 files that use them.
- Opting both inventory modules in restores the automated guard against the cross-file
  redeclaration class (TS6200). The one instance that existed — `inventory-my.js` and
  `inventory-admin.js` declaring the same eight script-scope names — was fixed with ADR-0125 by
  prefixing the admin module's copies, but until both files are checked, nothing stops a third
  page module from reintroducing it.
- **Exit criteria:** all 87 files carry `// @ts-check`; `checkJs` flipped to `true` globally and the
  per-file comments removed; gate green.

### Phase 2 — Eliminate the inline template JavaScript

Finish ADR-0069's extraction for the remaining ~2 245 lines across 58 templates, using the
established bootstrap-dict + verbatim-module recipe. Until this lands, no migration can claim
whole-codebase coverage.

- **Exit criteria:** templates contain only `th:inline="javascript"` bootstrap blocks (interpolated
  constants and dictionaries), no logic.

### Phase 3 — Tighten the checker to near-TypeScript strictness

Raise the bar while still in JavaScript, so the eventual conversion is syntax-only rather than
syntax *and* semantics.

- Enable `noImplicitAny`, then `strictFunctionTypes` and `noImplicitThis`.
- Replace the `unknown` placeholders in `globals.d.ts` (`krtSearchableSelect`, `krtHerkunft`,
  `krtRefineryYield`, the `createReceiver` config) with real shapes.
- **Exit criteria:** the only distance left to TypeScript is syntax.

### Phase 4 — Introduce the build step *(the point of no return)*

The first phase that changes how assets are produced, and the one that must be justified by a
trigger above rather than by tidiness.

- Choose the bundler and wire it as a Gradle task feeding `build/resources/main/static/js`,
  with `processResources` ordering and a dev-mode watch that keeps `bootRun` usable.
- Emit **classic scripts, not modules**, at this stage: same filenames, same load order, same
  end-of-body position. Nothing about the 106 `th:src` tags changes yet.
- Add source maps so stack traces stay readable.
- **Risk:** a broken or missing build step silently ships stale assets. Mitigate with a
  content-hash check in CI that fails when committed output and freshly-built output diverge.
- **Exit criteria:** byte-comparable behaviour, full E2E green, `bootRun` ergonomics unchanged.

### Phase 5 — Convert `.js` → `.ts`, still as classic scripts

Mechanical, file by file, with `allowJs` still on so the two can coexist.

- JSDoc type comments become type annotations; `/** @type {X} */ (expr)` becomes `expr as X`.
- `types/*.d.ts` stay exactly as they are — they already describe the global contract.
- **Exit criteria:** no `.js` left under `static/js`; behaviour unchanged; E2E green.

### Phase 6 — Modules *(optional, and the genuinely risky one)*

Only worth doing if bundling into a small number of entry points is the actual goal. This is where
the ADR-0069 invariant is finally traded away, so it needs its own ADR and its own justification.

- Replace the shared global scope with real `import`/`export`; retire the `/* global */` headers and
  most of `globals.d.ts`.
- The Thymeleaf bootstrap constants become a typed `window.__KRT_BOOTSTRAP__` payload read once at
  entry, rather than ~200 loose globals.
- **The ordering hazard is the whole risk.** `type="module"` defers execution, so every parse-time
  DOM lookup and every ordering assumption against `event-delegation.js` / `krt-fetch.js` must be
  re-verified. The `event-delegation.js` bootstrap stub in `fragments/head.html` exists precisely
  because load-order races were a real, repeated defect here.
- **Exit criteria:** a new ADR superseding the relevant part of ADR-0069; per-page entry bundles;
  full E2E green including the load-order-sensitive mission and orders pages.

## What does *not* change

- **The DTO types keep coming from `openapi.json`.** Generation is orthogonal to the source
  language and stays exactly as ADR-0125 built it, in every phase.
- **ESLint, Prettier, Stylelint and HTMLHint stay.** TypeScript checks types, not style.
- **Server-rendered Thymeleaf stays.** Nothing in this plan moves the frontend toward an SPA; that
  would be a different decision with a different ADR.
- **`krtFetch` and the live-update contract stay.** REQ-FE-001…010 are behavioural requirements and
  are indifferent to the source language.

## Cost estimate

Rough, for planning only — deliberately not precise, since the discovery in Phase 1 is what makes
the later numbers real.

| Phase |               Scope               |                 Rough effort                  |                    Risk                     |
|-------|-----------------------------------|-----------------------------------------------|---------------------------------------------|
| 1     | 59 files to `@ts-check` green     | Large, but incremental and shippable per file | Low                                         |
| 2     | ~2 245 inline lines, 58 templates | Large                                         | Medium — behaviour-preserving extraction    |
| 3     | Strictness ratchet                | Medium                                        | Low                                         |
| 4     | Build step                        | Medium                                        | **High** — first change to asset production |
| 5     | 87 files `.js` → `.ts`            | Large but mechanical                          | Low once Phase 4 holds                      |
| 6     | Modules                           | Medium                                        | **High** — script-ordering semantics        |

Phases 1–3 are worth doing on their own merits and improve the codebase whether or not the
migration ever happens. Phases 4–6 only pay off with a trigger.

## Open questions

- Which bundler, if Phase 4 is ever reached? Not worth deciding before the trigger exists — the
  answer depends on what caused it.
- Should Phase 6's per-page entry bundles be one bundle per Thymeleaf page or a shared core plus
  page chunks? Depends on measured cache behaviour at that point, not on principle.

