# Frontend JavaScript: type checking and ES modules

Read this before writing or moving a script under `static/js`.

## Type checking (REQ-FE-018, ADR-0125)

Every script under `static/js` is statically type-checked by `:frontend:typecheckJs`
(`tsc --noEmit`, wired into `check`) with `strict`, `checkJs`, `noImplicitAny` and
`useUnknownInCatchVariables` all on. **TypeScript is a checker here, not a language:**
the sources stay JavaScript, stay ES modules served as they are (ADR-0250), and nothing is
compiled, bundled or renamed. Do **not** convert files to `.ts` — the archived
[`docs/archive/TYPESCRIPT_MIGRATION_PLAN.md`](../archive/TYPESCRIPT_MIGRATION_PLAN.md) records why
and under which conditions that would be reopened (a new ADR).

**The module runs TypeScript 7, and the DTO declarations are emitted by us** — `openapi-typescript`
is gone (ADR-0130). What that means when you write a script:

- **Every parameter, variable and returned shape carries a JSDoc type.** `@param {Type} name`,
  `@returns {Type}`, `/** @type {HTMLInputElement | null} */`. `any` is reserved for genuinely
  untyped JSON (a `Map` lookup, an untyped list item) — **no element handle is `any`**. An
  object literal that is extended later gets the full shape as a `@typedef` or an inline
  `@type {{…}}` with optional members.
- **`| null` on a handle means the null case is yours to handle.** A function that dereferences a
  handle must guard it, and the guard has to cover **every** handle it touches, not just the first
  one. Before adding a guard, check whether the real fix is upstream: a helper with no declared
  return type poisons everything downstream of it, and a `querySelector` whose result is used as a
  checkbox or text input wants one cast at the query, not a cast at every use. Two DOM-signature
  papercuts: `clearTimeout` takes `number | undefined`, so a `number | null` handle needs
  `clearTimeout(t ?? undefined)`; and a generic `getElementById` feeding a specifically-typed
  variable needs a cast at the assignment.
- **A caught value is `unknown`.** Narrow it where the code reads it
  (`err instanceof Error ? err.message : String(err)`).
- **`scripts/gen-api-types.mjs` is ours to extend.** It covers the flat-object subset springdoc
  emits today (`$ref`, `items`, `enum`, `additionalProperties`, `nullable`, primitives) and
  **fails the build** on `allOf` / `oneOf` / `anyOf` / `not` / `discriminator`. The realistic
  trigger is a backend model gaining `@JsonSubTypes`. When that build failure names a construct,
  extend the emitter — never weaken the guard, because a DTO degraded to `unknown` type-checks
  everywhere and silently removes the drift protection the whole mechanism exists for.
- **The language level is ES2025** (ADR-0239, REQ-FE-018): the floor is Chrome 122, Firefox 131,
  Safari / iOS 18.4. `Promise.try`, `RegExp.escape` and `Float16Array` are in TypeScript's `ES2025`
  lib but above the floor, so ESLint rejects them.
- **No pragma, no opt-in, no suppression.** A script is checked from its first commit and must be
  error-free. No script carries `@ts-nocheck`, `@ts-ignore` or `@ts-expect-error`.
- **The ratchet** (`frontend/typecheck-ratchet.json`, `:frontend:testTypecheckRatchet`): every
  script of `static/js` (subfolders included, `vendor/` excepted) is listed by its path under
  exactly one domain and lives in that domain's folder, so **a new script must be added to its
  domain's list** or the build fails. The test also fails when `tsconfig.json` turns `strict`,
  `checkJs`, `noImplicitAny` or `useUnknownInCatchVariables` off, hides scripts with `exclude`, or
  when a script suppresses the check. A new domain is added to `"domains"` only.
  A script that is too large to check is split first into modules by responsibility (see *ES modules*
  below), the first keeping the file name.
- **Declare shared contracts in the same change.** A new kernel API is an `export` of its module and is
  imported (it gets a `window.` property only when something outside the module graph reads it: a
  Playwright source, a node test, a classic script); a new custom DOM event, a `window.` property or a
  new Thymeleaf bootstrap constant goes into `frontend/types/globals.d.ts` or
  `frontend/types/thymeleaf-bootstrap.d.ts` as part of the change that introduces it. A bootstrap
  constant additionally goes into the module's `/* global */` header — ESLint's `no-undef` keeps
  the per-page visibility check that the global declaration cannot.
- **Never restate a backend DTO by hand.** Annotate with the generated aliases —
  `/** @param {ApiDto<'MaterialDto'>} row */`, `ApiPage<…>`, `ApiProblem`. The types come from
  `openapi.json` via `:frontend:generateApiTypes`; the generated file is build output and must not
  be committed.
- **JSDoc must be real JSDoc.** The Javadoc spellings used elsewhere in this
  repo — `{@code …}`, `{@link …}`, `@param name {shape}` — are parsed as type syntax and are hard
  errors.

## ES modules (REQ-FE-035, ADR-0250)

Every script under `static/js` is an ES module except the ones `frontend/module-ratchet.json` lists as
classic (today only `kernel/krt-client-error.js`; the list only shrinks). The rules:

- **Link it as `<script type="module" th:src="@{/js/<domain>/<file>.js}" th:attr="nonce=${cspNonce}">`**
  — no `defer`. `ModuleScriptsTest` fails on a module linked as a classic script and the other way round.
- **Import by the absolute asset path, statically**: `import { x } from '/js/bank/bank-messages.js';`.
  Only your own domain's `/js/<domain>/` and `/js/kernel/` — never another domain, never a relative
  path, never `import()` (`:frontend:lintJs`, `ModuleScriptsTest`, which also rejects a cycle). A
  script another domain's page needs is a page-level `<script>` tag, as before.
- **The head renders the page's import map** (`moduleImportMap` bean): the content-hashed URL of every
  module the page's scripts import, found by scanning the page template and its fragments. You do not
  write it; a module reachable only through a construct the scan cannot follow (a dynamic fragment
  name) would be fetched unhashed, so keep a page's script tags in its own file's `pageTail`.
- **A module has no hidden shared scope.** It reads server values from a Thymeleaf data bootstrap or
  a `data-*` attribute, imports the kernel API it uses (`krtFetch`, `krtModal`, `krtLiveSync`, `krtEvents`, the toasts, …) from
  `/js/kernel/`, and hands another module only what it `export`s. The few `window.` names that remain
  are listed in ADR-0250. A module that registers document listeners does it
  in an exported `init…` function the entry module calls, so the order of listeners per event type is
  visible in one place (`bank/bank.js` is the example).
- **No immediately invoked function, no `window` seam.** A body that opens with an early `return`
  is a named function and one call (`function initBankGrants() { … }`, then `initBankGrants();`). The
  parts of one page that offer something to each other at run time (a section writer, a dialog
  opener, a reload) set a member of the domain's hook registry (`mission/mission-hooks.js`,
  `joborder/joborder-hooks.js`, `operation/operation-hooks.js`, `inventory/inventory-hooks.js`,
  `kernel/krt-hooks.js` across the kernel boundary) where they used to set `window.<name>`, and the
  others read it where they need it: a registry has no effect and no imports, so the parts keep their
  tag order. A `window` name stays only where a Playwright source, a node test or the classic beacon
  reads it (ADR-0250).
