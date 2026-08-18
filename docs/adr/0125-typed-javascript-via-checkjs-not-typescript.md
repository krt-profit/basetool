# ADR-0125 — Type-check the browser scripts with `checkJs` + JSDoc instead of migrating to TypeScript

- **Status:** Accepted — DTO type generation and the TypeScript 5.x pin superseded by ADR-0130
- **Date:** 2026-08-02
- **Deciders:** Repository owner (@greluc)
- **Related:** spec REQ-FE-018 ([`frontend-ajax-mutations.md`](../specs/frontend-ajax-mutations.md)) · ADR-0069 (inline-JS page-module extraction) · ADR-0012/0013 (the krtFetch foundation) · [`docs/TYPESCRIPT_MIGRATION_PLAN.md`](../TYPESCRIPT_MIGRATION_PLAN.md)

## Context

The frontend carries **40 023 lines of hand-written JavaScript across 87 files** under
`static/js`, plus roughly **2 245 lines still inline** in 58 Thymeleaf templates. None of it
is type-checked. ESLint (5 rules beyond `js.configs.recommended`) and Prettier cover style and
a handful of correctness rules; nothing checks that a value has the shape its consumer expects.

Three properties of this codebase shape the decision:

1. **The scripts are classic, non-module `<script>` tags sharing one global lexical
   environment**, and that is load-bearing, not incidental. ADR-0069 fixed it deliberately: no
   IIFE wrapping, cross-block consumption of bare identifiers, `typeof` self-references, and a
   preserved end-of-body load position so parse-time DOM lookups and the ordering against
   `event-delegation.js` / `krt-fetch.js` stay unchanged. 106 `th:src` tags depend on it.
2. **There is no build step for JavaScript at all.** The assets are served as authored.
3. **A large part of every page module's input is invisible to any checker**: the Thymeleaf
   bootstrap block declares the localized dictionaries and server values as globals in `.html`,
   and the ~2 245 remaining inline lines can never be checked by a tool that reads `.js`.

Meanwhile the highest-value uncovered surface is the **DTO boundary**. The backend publishes a
1.8 MB `openapi.json` with 253 DTO schemas from which the frontend derives nothing: every
`krtFetch` response body is handled as untyped JSON, so a backend field rename surfaces in a
Playwright run or in production rather than at build time.

A full TypeScript migration would resolve the DTO problem but collides head-on with (1): real ES
modules mean `type="module"`, which implies `defer`, which changes execution order across all 106
script tags — precisely the invariant ADR-0069 preserved. Keeping classic scripts under TypeScript
(`module: "none"`) pays the cost of a build step while forfeiting imports, encapsulation and most
of what TypeScript is for.

## Decision

**We will type-check the existing JavaScript with the TypeScript compiler in checker-only mode,
and we will not convert the sources to TypeScript.**

- **`tsc --noEmit` as a linter.** `frontend/tsconfig.json` sets `allowJs` with `noEmit`. Nothing
  is compiled, no bundle is produced, no `<script>` tag changes and no file is renamed. The
  checker runs through the existing node-gradle toolchain as `:frontend:typecheckJs`, wired into
  `check` alongside the three asset linters and strict (`ignoreExitValue = false`).

- **`moduleDetection: "legacy"`, deliberately.** `package.json` declares `type: module`, so under
  the default `auto` TypeScript would treat every `.js` file as an ES module and give each its own
  scope. `legacy` classifies a file as a module only when it actually contains `import`/`export`,
  which reproduces the real classic-script semantics — and is what lets the checker see cross-file
  globals *and* cross-file redeclaration collisions.

- **Opt-in per file via a leading `// @ts-check`.** `checkJs` stays `false`. The gate was green
  from the first commit against all 40k pre-existing lines, and coverage grows file by file with
  no big-bang migration. **27 of 87 files are opted in**, including the entire shared foundation:
  `krt-fetch.js`, `event-delegation.js`, `krt-live-sync.js`, `krt-searchable-select.js`,
  `escape-html.js`, `safe-url.js`, `toast.js`, `krt-catalog-search.js` and `krt-user-search.js`.

- **Three hand-written declaration files under `frontend/types/`** — outside `static/`, so nothing
  is served to browsers:

  - `globals.d.ts` — the cross-file runtime contract (the `window.krt*` APIs, the shared helpers,
    the custom DOM events, element expandos). This is the contract that previously existed only as
    names in the `/* global */` ESLint headers, now carrying types.
  - `thymeleaf-bootstrap.d.ts` — the 202 page constants injected by Thymeleaf bootstrap blocks.
  - `dto.d.ts` — global aliases (`ApiDto<'MaterialDto'>`, `ApiPage<…>`, `ApiProblem`) over the
    generated schemas.
- **DTO types are generated at build time and never committed.** `:frontend:generateApiTypes`
  runs `openapi-typescript` over `backend/src/main/resources/api/openapi.json` into
  `build/generated/ts/api.d.ts`, and `typecheckJs` depends on it. Because the file is derived on
  every build there is no committed copy to go stale, and therefore no drift gate to maintain.
- **The per-page visibility check stays with ESLint.** The bootstrap constants are declared
  *globally* in `thymeleaf-bootstrap.d.ts` while at runtime each exists only on its own page.
  ESLint's `no-undef` against each module's `/* global */` header keeps enforcing that scoping;
  TypeScript supplies the type. The two are complementary and both must stay.
- **TypeScript is pinned to the 5.x line.** TypeScript 7's native compiler drops the `ts.factory`
  compiler API that `openapi-typescript` builds on.

  > **Superseded by [ADR-0130](0130-own-openapi-dts-emitter-and-typescript-7.md) (2026-08-09).**
  > The generator was replaced with a dependency-free emitter of our own, which removed the reason
  > for the pin; the module now runs TypeScript 7. Everything else in this ADR still stands.

## Consequences

- A whole class of defect becomes a build error instead of a runtime surprise: DTO field renames,
  wrong option names on `krtFetch` calls, null dereferences of `getElementById`, and misuse of the
  shared `window.krt*` APIs. The checker paid for itself while being introduced — it found a
  latent cross-file collision (`inventory-my.js` and `inventory-admin.js` declared the same eight
  script-scope names; harmless only because no template loads both, an instant `SyntaxError` if
  one ever did — **fixed in this change** by prefixing the admin module's eight names, verified by
  TS6200 dropping from 2 to 0), and it caught six wrong assumptions in the first draft of the
  declarations themselves, including an invented option name (`versionContainer` for what the API
  actually calls `containerSelector`), `showKrtConfirm` typed with two of its four parameters, and
  a `refresh()` return type that would have mis-documented a live 403-retry path.

- The `window.krt*` cross-file API is now written down with types for the first time. That is a
  documentation win independent of the checking.

- **Accepted costs.** Coverage is partial by construction: 27 of 87 files today, and the ~2 245
  inline template lines remain permanently out of reach of this mechanism. `noImplicitAny` is off,
  so an un-annotated parameter is still `any`. Checking a file requires JSDoc comments to be valid
  *JSDoc* — this codebase writes Javadoc (`{@code …}`, `{@link …}`, `@param name {shape}`), which
  TypeScript parses as type syntax and rejects; five files use those tags and must be converted as
  they are opted in. Narrowing DOM types costs explicit `/** @type {…} */` casts at call sites,
  which is churn on otherwise-working code.

- The remaining 60 files are not a TODO left in the tree — they are simply not yet opted in, and
  the sequencing is [`TYPESCRIPT_MIGRATION_PLAN.md`](../TYPESCRIPT_MIGRATION_PLAN.md), which also
  records under what conditions the "no TypeScript" half of this decision should be revisited.

## Alternatives considered

- **Full migration to TypeScript now.** Rejected on cost and risk against the current architecture,
  not on merit. Real ES modules change script execution order across 106 tags (the ADR-0069
  invariant); classic-script TypeScript keeps the ordering but pays for a build step while giving
  up imports and encapsulation. Neither variant can reach the inline template JS. Revisit when the
  frontend moves to a bundled architecture for independent reasons — at that point the build step
  already exists and the arithmetic reverses. The full path is written up in the migration plan so
  the option stays open and costed rather than rediscovered.

- **`checkJs: true` globally, fixing everything at once.** Measured: **3 909 errors**, dominated by
  DOM narrowing (`EventTarget` lacking `closest`, `Element` lacking `value`). Fixing them means
  thousands of inline casts in one unreviewable diff over code that works. Rejected in favour of
  the opt-in ramp.

- **Committing the generated `api.d.ts`.** 53 749 lines of derived code in the tree, plus a drift
  gate to detect when it no longer matches `openapi.json`. Build-time generation removes both the
  diff noise and the drift class outright.

- **Type declarations inside `static/js`.** Rejected: everything under `static/` is served, so the
  declarations would ship to browsers as dead weight and expose the API surface unnecessarily.

- **Doing nothing and relying on E2E.** The Playwright suite catches DTO drift only where a test
  walks the affected path, and reports it as a UI failure far from the cause. It remains the
  behavioural gate; it is not a substitute for a contract check.

