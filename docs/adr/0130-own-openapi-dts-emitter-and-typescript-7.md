# ADR-0130 — Emit the DTO declarations ourselves, and take TypeScript 7

- **Status:** Accepted
- **Date:** 2026-08-09
- **Deciders:** Repository owner (@greluc)
- **Related:** spec REQ-FE-018 ([`frontend-ajax-mutations.md`](../specs/frontend-ajax-mutations.md)) · supersedes the DTO-generation and TypeScript-version parts of ADR-0125 · ADR-0069 (inline-JS page-module extraction)

## Context

ADR-0125 introduced `checkJs` + JSDoc type checking and derived the backend DTO shapes from
`openapi.json` with [`openapi-typescript`](https://www.npmjs.com/package/openapi-typescript). It
also recorded a constraint that has since become the binding one:

> **TypeScript is pinned to the 5.x line.** TypeScript 7's native compiler drops the `ts.factory`
> compiler API that `openapi-typescript` builds on.

That constraint is real and was verified against 7.0.2, not assumed. In TypeScript 7 the package's
`.` export is `./lib/version.cjs`: `require('typescript')` returns `{ version }` and nothing more,
so `ts.factory` and `ts.createPrinter` are `undefined`. `openapi-typescript` does
`import ts from 'typescript'` and calls `ts.factory` 68 times, `ts.SyntaxKind` 21 times, plus
`createPrinter` / `createSourceFile` / `ScriptTarget` / `NodeFlags`. It declares
`peerDependencies: { "typescript": "^5.x" }` and no release admits 7 (latest 7.13.0, Feb 2026).
The AST API still exists, relocated behind `typescript/unstable/ast/*`, but upstream states the
*stable* programmatic API arrives in TypeScript 7.1 — so the port cannot happen before then.

Two further facts turned this from "wait for upstream" into "stop depending on upstream":

1. **We consume almost none of the output.** The generated `api.d.ts` was **54 334 lines / 1.7 MB**,
   and the aliases it feeds are used by **three annotations in one file**
   (`krt-catalog-search.js`: `MaterialDto`, `LocationReferenceDto`, `GameItemReferenceDto`).
   `ApiPage`, `ApiProblem`, `ApiPaths`, `ApiOperations` and `ApiSchemas` had **zero** usages —
   the `paths` and `operations` maps are essentially all of the 51 000 surplus lines.
2. **Our spec is trivial to emit.** springdoc produces flat object schemas: all 398 schemas are
   `type: object`, and the document contains **no `allOf` / `oneOf` / `anyOf` at all**. The
   constructs actually present are `$ref` (262), `items` (246), `enum` (106) and
   `additionalProperties` (2).

Emitting a `.d.ts` is printing text. Routing that through a compiler API is what coupled the
module's TypeScript version to a third party's release schedule.

## Decision

**We will emit the DTO declarations ourselves and move to TypeScript 7.**

- `frontend/scripts/gen-api-types.mjs` — a dependency-free Node script (~90 lines) — replaces
  `openapi-typescript`. `:frontend:generateApiTypes` runs it as a `NodeTask`. Output stays build
  output and is still never committed, so REQ-FE-018's "drift is structurally impossible" property
  is unchanged.
- It emits **only `components.schemas`**, plus empty `paths` / `operations` interfaces for source
  compatibility with `types/dto.d.ts`. Output: **3 046 lines / 96 KB** (18× smaller).
- It **fails the build** on `allOf` / `oneOf` / `anyOf` / `not` / `discriminator` and on a dangling
  `$ref`, rather than degrading the affected DTO to `unknown`. A silently-untyped DTO would remove
  exactly the protection the file exists to provide.
- `typescript` moves to `^7.0.2`. `openapi-typescript` is removed from `package.json`
  (22 transitive packages dropped).
- `scripts/**/*.mjs` joins the ESLint and Prettier globs, with its own Node-ESM config block.

## Consequences

**Easier.** The frontend no longer tracks a third party's TypeScript support. The generated file is
18× smaller. One npm dependency and its 22 transitives are gone. `tsc` over the project drops from
1.06 s to 0.30 s.

**Harder — and knowingly accepted.** We now own ~90 lines of emitter. The realistic trigger for
extending it is a backend model gaining `@JsonSubTypes`, which would put `allOf`/`oneOf` in the
spec; the guard above turns that into a build failure naming the construct and the JSON path, so
the failure mode is loud rather than silent.

**The TypeScript 7 migration surfaced 179 errors in 12 files, from one root cause.** TypeScript 7
grants `let x = null;` an *evolving* implicit `any` only when `noImplicitAny` is on, and this
project deliberately sets it **off** (REQ-FE-018 / ADR-0125: opt-in checking over a 40 k-line
codebase). TypeScript 5 therefore gave those variables plain `any`; TypeScript 7 infers the literal
type `null` and rejects every later assignment (`TS2322`), dereference (`TS18047`) and
post-narrowing use (`TS2339` on `never`). Resolved by annotating all 54 declarations with a
**precise** type — `HTMLElement | null`, `HTMLInputElement | null`, `HTMLButtonElement | null`,
`WebSocket | null`, `EventSource | null`, `number | null`, `KrtSectionWriter | null`. Only four
remain `any`, and all four are untyped *JSON* rather than element handles (map lookups and
untyped list items); no element handle is `any`.

Three call-site changes follow from the DOM signatures and are runtime-identical:
`clearTimeout(t ?? undefined)` (the signature takes `number | undefined`, our handles are
`number | null`; both values are no-ops), casts where a generic element lookup feeds a
specifically-typed variable, and making `isOpen()`'s implication that `ws != null` explicit at the
one send site.

**Precise element types then surfaced 40 further errors — the null-safety ADR-0125 promised and
never got.** `noImplicitAny: false` had made every `getElementById` handle `any`, so
"null dereferences of `getElementById`" — listed in ADR-0125 as a defect class the checker
catches — was in fact unchecked. Closing it needed real changes, not annotations:

- **Guards where a function dereferenced handles it never checked.** `renderDetailHead` guarded
  one of the six handles it uses; `showError` / `renderRecipe` guarded none. These now return
  early. That is a behaviour change — `TypeError` becomes a no-op — reachable only when the
  template has already failed to render the pane, so it converts a broken page into a quiet one.
- **Root causes over symptoms.** `el()` had no declared return type, which made every node it
  produced un-narrowable and cascaded through the `aside`/`badge` find-or-create chain; giving it
  (and `clear()`) a real signature fixed nine errors at once. `rows()` now declares `HTMLElement[]`
  because its callers use `.hidden`, `.tabIndex` and `.focus()`.
- **Casts at the query, not at each use**, where a `querySelector` result is a checkbox or a text
  input (`.checked`, `.value`, `.disabled`).
- **`vis.indexOf(activeRow)`** became `activeRow ? vis.indexOf(activeRow) : -1` — identical, since
  `indexOf(null)` already yields `-1`, but now expressed in the type system.

No runtime behaviour changed except the early returns above, each of which replaces a throw on an
already-broken DOM.

**`noImplicitAny` stays off** nonetheless — see below. The tightening above closes the null-safety
gap for the *element handles*; enabling the flag globally is a separate, larger question.

**`noImplicitAny` stays off.** Turning it on also clears the 179 — but costs 514 implicit-any
errors, and TypeScript 5.9.3 reports *the same 514*. That is pre-existing annotation debt, not a
TypeScript 7 problem, and is out of scope here.

## Alternatives considered

- **Wait for TypeScript 7.1 and an `openapi-typescript` port.** Rejected: it puts the module's
  compiler version on someone else's schedule, for a generator whose output we use three
  annotations of. The waiting has no end date we control.
- **Keep `openapi-typescript` and stay on TypeScript 5.x.** Defensible — the cap costs 0.77 s of
  build time. Rejected because the dependency, not the speed, is the liability: it is the single
  thing that pinned the module, and it re-pins us at every future TypeScript major.
- **A maintained generator without a `typescript` dependency** (`json-schema-to-typescript`,
  `quicktype-core`, `orval` — all verified to have none). Rejected for this spec: they expect JSON
  Schema and would need OpenAPI massaging plus their own `$ref` mapping, which is more adapter code
  than the whole emitter, and re-introduces a dependency to track. Worth revisiting if the spec
  ever grows polymorphism.
- **Hand-write the three DTO shapes and drop generation entirely.** Rejected: hand-maintained DTO
  copies are precisely what REQ-FE-018 forbids, and it would forfeit drift protection to save a
  90-line script.
- **Turn on `noImplicitAny` to clear the 179 errors.** Rejected: 514 pre-existing errors, unrelated
  to this decision, and a change to the opt-in model ADR-0125 chose deliberately.

