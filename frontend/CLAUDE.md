# CLAUDE.md — frontend

Frontend-specific guidance. Loads when Claude works with files under `frontend/`. The
cross-cutting rules (requirements, i18n, Git, documentation) stay in the
[root `CLAUDE.md`](../CLAUDE.md).

## UI & design system

The UI is a **binding requirement**: follow the DAS KARTELL design system. The rules —
brand colours, Lato-only typography (headlines = Lato Bold + uppercase), the authoritative department colours, the
sci-fi HUD style with a 4 px radius and the website palette, "no native browser dialogs", and the four responsive device
classes — live in [`docs/specs/ui-design-system.md`](../docs/specs/ui-design-system.md). The
visual source of truth is the design skill at
[`.claude/skills/das-kartell-design/README.md`](../.claude/skills/das-kartell-design/README.md)
(its README.md is the source of truth for colours, typography and components).

**The design system is a git submodule (`github.com/krt-profit/design-system`) and MUST be
present before any UI work.** `git worktree add` does not populate submodules, so in a fresh
worktree `.claude/skills/das-kartell-design/` is empty and the source of truth above is
unreadable. A `SessionStart` hook (`.claude/settings.json`) materialises it automatically at
session start (offline-first from the module store, with a copy from the main worktree as
fallback) **and then fast-forwards it to the design system's `origin/main`**, so a session always
reads the latest published design system (ADR-0259). It leaves the checkout alone when the fetch
fails, when it has local changes, or when it holds commits that are not on `origin/main`.
`.gitmodules` sets `ignore = all`: the moved submodule stays out of `git status` and `git add -A`,
and the recorded pin moves only on purpose (`git add --force .claude/skills/das-kartell-design`).
The hook is cross-platform via two self-guarding entries that dispatch on `command -v
pwsh`: Windows runs [`.claude/hooks/ensure-design-system.ps1`](../.claude/hooks/ensure-design-system.ps1),
Linux/macOS/CI run the portable
[`.claude/hooks/ensure-design-system.sh`](../.claude/hooks/ensure-design-system.sh) — exactly one
runs per host, with no error noise on the other. **If that directory is still empty when you start
UI work** — hooks disabled, or a worktree outside the harness — populate it yourself before
touching any frontend surface: `git submodule update --init .claude/skills/das-kartell-design`,
or, offline, copy it from the main worktree (find it via `git worktree list`). Never do UI work
against an empty design system and never treat its absence as "no design system applies".

### What a template may send

A rendered page carries no developer text and no inline page CSS (`REQ-UI-023`,
`TemplateCommentHygieneTest`):

- **No comments.** A template carries no comment of any kind — the code base keeps no comments
  besides Javadoc (ADR-0214); the reasoning goes into the commit message and the PR. A plain
  `<!-- … -->` would also be sent with every response, which `TemplateCommentHygieneTest` fails.
  The only `<!--/*/ … /*/-->` allowed is Thymeleaf's prototype-only markup, which is not a comment.
- **Page CSS goes into `static/css/pages/<domain>/<page>.css`**, linked with `<link rel="stylesheet">` where
  a `<style>` block would stand (in the `extraLinks` fragment for the head). No `<style>` element in
  a template. It is linted by `:frontend:lintCss` with the standard rule set, like every other
  stylesheet, and formatted by Prettier.
- **Colours and stacking go through tokens** (REQ-UI-001, ADR-0243): a colour token's value is
  `var(--color-x)`, its alpha variant `color-mix(in srgb, var(--color-x) N%, transparent)` — never a
  hand-written `rgb()`/hex copy (`ColourTokenCopyTest`). A `z-index` that competes across the page
  is a `--z-*` token from the scale on `:root` in `styles.css`; a literal stays below 50 and orders
  one component's parts only (`ZIndexScaleTest`).

### CSS: the layer decides, not the load order (binding)

Every stylesheet starts with `@layer base, components, page, utilities;` and puts every rule inside
one of those layers (`REQ-UI-024`, ADR-0212, `CascadeLayerOrderTest`). Between layers the order
decides, before specificity:

- `styles.css` owns `base` (fonts, tokens), `components` and `utilities`; every page or area
  stylesheet is `page`. `utilities` holds only the two state classes, `.is-hidden` and
  `.krt-modal-overlay.is-open`.
- A page rule beats a design-system rule as an ordinary rule. Do not bump specificity
  (`main .x`, `div.x`, `.x.x`) and do not add `!important` for it.
- A design-system declaration that has to beat page CSS goes into the `@layer page` block at the end
  of `styles.css`. Never into `utilities`, which would also beat the page rules that out-specify it.
- `inline-migration.css` and its `krtm-*` classes are retired (REQ-UI-027 phase 4): a one-off style
  is a design-system class or a page rule, never a new `krtm-*` class (`NoMigrationClassTest`).
  `data-krtm-width` with `inline-style-apply.js` stays — it is the CSP-safe width mechanism.
- A rule outside any layer beats every layer. That is why the test fails on one.

### Script load order (binding — it has regressed three times)

Every external `<script>` is a module (`type="module"`, no `defer` — a module is deferred) except
`krt-client-error.js`, which stays the head's first, synchronous classic script (`REQ-FE-023`,
`REQ-FE-035`). Module scripts run in document order after parsing, with the other deferred ones, so a
page module may use every head global at load.

**An inline script is a data bootstrap and nothing else** (ADR-0069). It is a
`th:inline="javascript"` block of `const` / `let` / `var NAME = literal` and `window.NAME = literal`
statements whose literals (strings, numbers, booleans, `null`, arrays and objects of those) come
from `/*[[…]]*/` natural-template values. A function, a call, a listener, a merge of two
dictionaries or a reference to another global goes into the page's module under `static/js`, where
it is linted and type-checked; a module that needs a server value reads it from such a bootstrap or
a `data-*` attribute. Never `'[[#{key}]]'` inside a string literal — the inliner adds its own
quotes. `InlineScriptDataOnlyTest` parses every inline block and fails the build on anything else;
the head's `krtEvents` stub is its one reviewed exception. `InlineScriptLoadOrderTest` and
`ScriptLoadOrderE2eTest` keep the load order.

### Trusted Types: every DOM sink takes a policy value (binding)

The CSP sends `require-trusted-types-for 'script'` (ADR-0239, REQ-FE-022, REQ-SEC-064) —
report-only by default, enforced with `APP_SECURITY_TRUSTED_TYPES=enforce`. Under enforcement a
string written into an HTML sink throws, even `innerHTML = ''`. So:

- **Markup built in script:** `import { krtHtml } from '/js/kernel/krt-html.js';`, then
  ``krtHtml.set(el, krtHtml`<li data-id="${id}">${name}</li>`)``. Every
  interpolation is escaped unless it is itself `krtHtml` markup; pass arrays of `krtHtml` pieces
  (`items.map((it) => krtHtml`…`)`) instead of concatenating strings, and never pre-escape a value.
  `krtHtml.set` writes anything that is not `krtHtml` markup as text.
- **A server fragment:** `krtFetch.setTrustedHtml`, `replaceWithTrustedHtml` or
  `parseTrustedDocument` — only with the body of a same-origin fragment response, never with markup
  built in script.
- **Clearing:** `el.replaceChildren()`.
- Never create a Trusted Types policy (there is no `default` one), never call `krtHtml` as a
  function, never use `eval`, `new Function`, a string timer or a created `<script>`.

`:frontend:lintJs` rejects every other sink write outside `krt-html.js` and `krt-fetch.js`, and the
dialog page walk (`DialogA11yE2eTest`) fails on any violation the browser reports.

### Dialogs: one contract (binding)

Every dialog is a `<dialog class="krt-modal-overlay">` > `.krt-modal` (REQ-UI-013, ADR-0177) and
opens and closes **only** through `krtModal.open(el | id)` / `krtModal.close(el | id)` (imported from
`/js/kernel/krt-modal.js`)
— or the shared `data-trigger="open-modal-display"` / `"close-modal-display"` triggers, which call
it. Never write `overlay.style.display`, never toggle `is-open` yourself: the contract
calls `showModal()`, moves focus in and back, handles Escape, and keeps the class state that live
sync and the tests read. While a dialog is open the page behind it is inert, so anything you append
for the user to see or click — a toast, a confirm, a download link — goes into
`krtModal.layerRoot()`, not `document.body`.

**Never write a dialog shell by hand.** Every dialog is a call of `fragments/modal-wrapper :: modal`
(`SingleModalShapeTest` fails on `.krt-modal-overlay`, `.krt-modal`, `.krt-modal-head` or
`.krt-modal-close` anywhere else). The page supplies the body:

```html
<th:block th:replace="~{kernel/fragments/modal-wrapper :: modal(modalId='x-modal', titleKey='x.title',
    variant='krt-modal--wide', body=~{::x-modal-body})}">
    <th:block th:ref="x-modal-body">
        <form …><div class="krt-modal-body">…</div><div class="krt-modal-foot">…</div></form>
    </th:block>
</th:block>
```

The fragment declares no signature; a call names only the parameters it needs:

| Parameter | Meaning |
| --- | --- |
| `modalId` | Required. The `<dialog>` id, also the close control's `data-modal-id`. |
| `titleKey` | Required. i18n key of the `<h2>` title. |
| `body` | Required. Fragment expression for everything below the head: `.krt-modal-body` and `.krt-modal-foot`, or a `<form>` wrapping both. |
| `variant` | Extra class on `.krt-modal`: `krt-modal--wide`, `--xwide`, `--danger`, or a page's own frame class. |
| `titleId` | Id of the `<h2>` for a script that retitles the dialog; the dialog is then labelled by the heading (`aria-labelledby`), otherwise it carries the title as `aria-label`. |
| `open` | `true` renders the dialog open (e.g. after a server-side validation error). |
| `closeTrigger` | The ✕'s `data-trigger`; default `close-modal-display`. A page handler must end in `krtModal.close`. `''` when a script binds the close by `closeClass`. |
| `closeClass` / `closeId` | Class / id on the close control for a page script. |

A condition or iteration goes on a `<th:block>` around the call. In a fragment file, name the body with its template
(`~{bank/fragments/x :: x-modal-body}`). A new dialog id also needs `DialogA11yE2eTest` to reach it, or an
`UNREACHED` entry with the reason. Render a dialog under the same condition as its openers and the
script that drives it: a dialog nothing can open, or whose handlers were never loaded, is dead
markup (the promotion admin all-squadrons view and the bare admin blueprint page were).

### Page templates: one layout fragment (binding)

Every page template is composed through `fragments/layout :: page` (REQ-FE-033, plan F5): the page
hands over its parts and **never calls the head, sidebar, header or toast fragment itself**
(`PageLayoutCompositionTest`). The shape of a page:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" th:replace="~{kernel/fragments/layout :: page(pageTitle=~{::title},
    pageLinks=~{::extraLinks}, pageContent=~{::pageContent}, pageToast=true, pageTail=~{::pageTail})}">
<head>
    <title th:text="#{x.title} + ' - ' + ${appTitle}">X</title>
    <th:block th:fragment="extraLinks">
        <link rel="stylesheet" th:href="@{/css/pages/bank/x.css}">
    </th:block>
</head>
<body>
<th:block th:fragment="pageContent"><main>…</main></th:block>
<th:block th:fragment="pageTail"><script type="module" th:src="@{/js/bank/x.js}" th:attr="nonce=${cspNonce}"></script></th:block>
</body>
</html>
```

`pageTitle` and `pageContent` are required; `pageLinks` and `pageTail` are left out when the page has
none. `pageToast=true` renders the toast between the content and the tail, so the page's own scripts
go into the tail to keep loading after `toast.js`. `pageBare=true` leaves out sidebar and header (the
error pages, pending approval, terms acceptance); `pageShowModal=${…}` sets the body's
`data-show-modal`. A different chrome is a new parameter of the layout, never a copy into a page, and
changing the layout means rewriting the golden file `src/test/resources/templates/page-chrome-snapshot.txt`
(`PageChromeSnapshotTest`, `-PupdateSnapshots`) and saying so in the review. A fragment a controller
returns (`view :: fragment`) is unaffected.

## Live update

**Live update is a binding requirement: every part of the frontend must support live update to
the current standard.** Every create / update / delete / toggle / reorder / filter / paginate
interaction updates the DOM **in place** through the shared `krtFetch` / `krtCsrf` / fragment-swap
foundation — **no full-page reload on success** (the only two sanctioned reloads are the
optimistic-lock conflict confirm and the bfcache history-restore of `REQ-FE-008`) — derived UI
outside the swapped fragment is refreshed too, and on any surface where several users can see the
same state a peer's change propagates to the others without a manual reload. The current standard,
its full `krtFetch`/fragment-swap contract and the live multi-user sync live in
[`docs/specs/frontend-ajax-mutations.md`](../docs/specs/frontend-ajax-mutations.md)
(`REQ-FE-001…010`, ADR-0012/0013/0031). A new or changed frontend surface that reloads the page on
success, leaves a sibling/peer view stale, or hand-rolls a `fetch`/CSRF write outside `krtFetch` is
incomplete — extend the standard to cover it, don't fall back to a reload.

**Reads go through `krtFetch` too (REQ-FE-031).** `krtFetch.getJson(url)` for JSON,
`krtFetch.get(url, {accept, headers, signal, key})` for a fragment, a blob or a status the caller
reads itself. Both send the background marker, hand a lost session or the consent gate to the
navigation, and refuse a redirected answer (`get` resolves null, `getJson` rejects). A raw `fetch`
or `XMLHttpRequest` fails `:frontend:lintJs` outside `krt-fetch.js` and `krt-client-error.js`, and
`BackgroundReadGateContractTest` fails one in an inline template script.

**Wire a section-structured page through `krtPageSections.create`** (`kernel/krt-page-sections.js`, ADR-0289): declare the `topicClass`, the sections, the topic and the receiver once and use its `write` / `refresh` / `notify` / `receiver`; do not hand-roll a `reswap*` / `refresh*` helper, a `*_SECTIONS` map, a `createReceiver` call, a `dataset.version =` write or a 409 branch in a page module. A cross-topic poke goes through `krtPageSections.publish`.

**Live update and multi-user sync move with every feature — added, changed *or* removed.** Whenever
you add, change or remove a frontend surface that participates in live update or live multi-user
sync (a new editable section, a renamed/retired one, a new mutation on an existing section), you
**must** update its live-update and peer-sync wiring in the **same change** — never defer it to a
follow-up. For the multi-user sync in particular, a section key must stay consistent across **all**
its mirror points at once: the acting client's broadcast (the page's section/seam map), the server
relay's accept-list (`LiveSyncTopicClass.allowedSections`), and the receiving client's apply map. A key present
in one but missing from another **silently** leaves other viewers stale with no error — the
REQ-FE-010 defect that shipped when `objectives`/`frequencies` were added to the write seam but not
the receiver/relay. The server renders the relay whitelist into the head (`meta[name=krt-live-sync-sections]`) and `krtPageSections.create({topicClass})` drops a wired key outside it, so the page never keeps its own list of allowed keys. Prefer deriving these maps from a single source of truth so they cannot diverge;
where they can't share one, changing one **requires** changing the others in the same PR, and the
change is incomplete otherwise. `LiveSyncSectionMapParityTest` fails the build when the relay's
whitelist and a page's JS seam map disagree.

## Type checking (REQ-FE-018, ADR-0125)

Every script under `static/js` is statically type-checked by `:frontend:typecheckJs`
(`tsc --noEmit`, wired into `check`) with `strict`, `checkJs`, `noImplicitAny` and
`useUnknownInCatchVariables` all on. **TypeScript is a checker here, not a language:**
the sources stay JavaScript, stay ES modules served as they are (ADR-0250), and nothing is
compiled, bundled or renamed. Do **not** convert files to `.ts` — the archived
[`docs/archive/TYPESCRIPT_MIGRATION_PLAN.md`](../docs/archive/TYPESCRIPT_MIGRATION_PLAN.md) records why
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

## Package layout (REQ-FE-032, plan §5.9)

The Java code is packaged by domain: a new controller, view row or view helper goes into
`frontend.<domain>.web`, a DTO mirror, form or view model into `frontend.<domain>.model`, the
domain's backend calls into `frontend.<domain>.client`. Cross-cutting code goes into the kernel
package of its concern — `kernel.backend` (the backend seam, relays, caches), `kernel.security`,
`kernel.session`, `kernel.layout`, `kernel.web` (error handling, binding, request contexts, template
beans), `kernel.livesync`, `kernel.observability`, `kernel.model` (shared types and the reference
DTOs several domains share). Nothing else exists: `DomainPackageLayoutTest` fails on a main class
outside these packages.

- **The kernel takes nothing domain-specific and reaches into no domain.** Its remaining reaches
  are frozen in `KernelDomainReachTest` and may only shrink: a new one fails the build. Invert it
  instead — move the shared type into `kernel.model`, or let the domain supply the value.
- **Every DTO of the backend seam carries `@DtoMirror`** (`frontend.kernel.model.DtoMirror`): the DTO
  contract tests find mirrors by it, not by package. A `*Dto`, `*Request` or `*Response` type in a
  model package without it fails the build; anything else without it is invisible to those tests.
- **A flashed form or DTO keeps its exact name in `SessionTypeAllowList.SESSION_BOUND_TYPES`**:
  moving or renaming one changes the name its session values carry, so the entry moves in the same
  change (`SessionBoundTypeClosureTest`), and sessions written before the release drop that one
  flash attribute once (`docs/deployment.md` → *The per-domain package move*).
- Bean names are the simple class names or explicit (`moneyFormat`, `relativeDays`, `handles`,
  `markdown`). Templates name one class by its package, `T(…kernel.security.Roles)`;
  `TemplateTypeReferenceTest` fails when it moves without them.

## Template and asset layout (REQ-FE-034, plan §5.9)

The folder is the domain, with the same names as the Java packages. A page template is
`templates/<domain>/<name>.html` (view name `<domain>/<name>`; the old `admin/` and `fragments/`
sub-folders stay below the domain), a script `static/js/<domain>/<name>.js` (the domain it is listed
under in `typecheck-ratchet.json`), an area stylesheet `static/css/<domain>/<name>.css` and a page
stylesheet `static/css/pages/<domain>/<page>.css`. What no single domain owns lives in `kernel/`:
the layout and chrome fragments, the dialog wrapper, the components, the transport and live-sync
scripts, `kernel/styles.css`. The legal pages, the licences page, the SC links page and the error
pages' stylesheets are the `shell` domain's.

- **Nothing sits flat.** `AssetLayoutTest` fails on a template, script or stylesheet outside a
  domain folder; only the five error templates Spring Boot resolves by name stay at
  `templates/error*`. A new domain folder is a new frontend package first.
- **Every link resolves.** `AssetReferenceResolutionTest` resolves each `th:src` / `th:href` to a
  file and fails on a script or stylesheet nothing links; the content-hashed URLs work for any
  sub-folder, so a move needs no change to the asset serving.
- **A moved file is a `git mv` plus a rewrite of its references** (view names in controllers and
  tests, `th:replace` paths, asset links, `inputs.file` paths in `build.gradle.kts`, the ESLint
  per-file settings), never a copy. The served URL changes with the folder; the content hash does not
  depend on it.
- A stylesheet another domain's page also links stays in its owner's folder (REQ-FE-034 lists them).
  A page that needs a domain's script from another domain is a design question, not a copy.

## Backend calls: resilience & context propagation

- **WebClient** is centrally configured (base URL, default headers, connect/read/write timeouts) in `WebClientConfig`, and **nowhere else** (REQ-FE-029, `WebClientConfinementTest`): only `WebClientConfig` builds a client, and only the backend kernel holds one: `BackendApiClient`, and `BackendSideChannels` for the notification SSE relay and the live-sync probe, the two calls that deliberately skip the resilience pass. A controller calls the backend only through its domain's typed client — `<Domain>BackendClient` in `frontend.<domain>.client`, a thin `@Service` over `BackendApiClient` that owns the domain's paths and returns typed records, never a `Map` (plan F3, `TypedBackendClientTest`); a new backend call is a method there (`execute(…)` for an unusual shape), and a catalogue eviction goes through `CatalogueCacheEviction`. Every failure is mapped once, by `BackendErrorMapper`'s exhaustive switch over its sealed `Outcome`. A runtime value goes into a backend URI as a template variable of the verb's template overload (`post("/api/v1/x/{id}", body, T.class, id)`), never by concatenation, for every verb (REQ-SEC-051, `WriteUriTemplateTest`, `ReadUriTemplateTest`); a value with reserved characters (an `Instant`) stays a `UriComponentsBuilder.queryParam` and only the path variable is left in the template (`fromPath("/api/v1/x/{id}").queryParam(…).encode().build().toUriString()`), so the bytes sent do not change. Each typed client's requests are pinned in a `*BackendClientTest` over `BackendClientHarness` (MockWebServer). A page whose data has section fallbacks, defaults or parallel loads reads it through a **page reader** above the typed client (`JobOrderPageReader` in `joborder.web`, ADR-0291, a pilot): one read per page returning an immutable record, with the defaults, the `SectionFallback` logging and the parallel load inside, explicit availability instead of `null`, and the controller only mapping the record into the model — no `SectionFallback.load(…, null)` and no re-defaulting in the controller. A page with one plain call keeps calling the typed client; the reader has no method that only forwards to it. Controller tests stub the reader, reader tests stub the typed client by method. Paths are relative `/api/…`: every backend client refuses any origin but `app.backend-url`'s in its first filter, before the OAuth2 filter attaches the bearer — a test that wants a client to reach a local server sets the backend URL to that server instead of passing an absolute URI. A future HTTP-interface client is created over the `webClient` bean and takes no `URI`, `UriBuilderFactory` or `@CookieValue` parameter, names no absolute URL and carries no `@Cacheable`.
- **Resilience4j** wraps every call through `webClient` and `termsDocumentClient` (Timeout, Retry, CircuitBreaker, Bulkhead); the SSE relay's `sseWebClient` and the live-sync probe's `liveSyncAuthWebClient` deliberately carry none. State transitions are logged via `ResilienceEventLogger` so `SERVICE_UNAVAILABLE` / `BACKEND_TIMEOUT` always have a matching log line.
- **Reactor context propagation is mandatory for any new `ThreadLocal` you want to see inside `WebClient` exchange filters.** `WebClient.exchange()` runs on a Reactor-Netty worker thread, not the servlet thread; classic `ThreadLocal` values are not copied across threads. Register a `ThreadLocalAccessor` on `ContextRegistry.getInstance()` in [`ReactorContextPropagationConfig`](src/main/java/de/greluc/krt/profit/basetool/frontend/kernel/backend/ReactorContextPropagationConfig.java) (which also enables `Hooks.enableAutomaticContextPropagation()` at startup). The existing accessors cover `ActiveSquadronContext` (active-OrgUnit pin → `X-Active-Org-Unit-Id` outbound header), `CorrelationContext` (correlation id propagation), Spring's `LocaleContextHolder` (user locale) and `ClientIpContext`. Forgetting the accessor means the holder is invisible on the worker thread and the outbound call silently drops whatever it carried. Register it inside `ReactorContextPropagationConfig#registerRelayAccessors`: `ParallelPageLoader` restores a `ContextSnapshot` of the same registry on its virtual threads, so the accessor reaches parallel page sections with no change to the loader (REQ-FE-030). Never hand-copy a holder in the loader again — that is how the locale went missing there.
- Use `MockWebServer` / WireMock to test error paths.

## Concurrency — the frontend half

- **Frontend DOM version sync** — when an entity is updated via AJAX (dropdown change, row reorder, etc.), the new `version` must propagate to **every** related DOM element in the same context (edit/action buttons, modals inside the same `<tr>` or container). A missed `data-version` attribute → 409 on the user's next click. A reload on success is **not** an escape hatch here: the Live update rule above forbids it, so a tangled update is re-rendered through a fragment swap instead.

The backend half — the `kernel.OptimisticLock` helper family, `Mission`'s manual section
counters, the `…WithinTransaction` pattern, bulk-updates-inside-loops and the find-or-create
retry — lives in [`backend/CLAUDE.md`](../backend/CLAUDE.md).
