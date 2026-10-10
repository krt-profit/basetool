# CLAUDE.md — frontend

Frontend-specific guidance. Loads when Claude works with files under `frontend/`. The
cross-cutting rules (requirements, i18n, Git, documentation) stay in the
[root `CLAUDE.md`](../CLAUDE.md).

## The knowledge base (HARD RULE)

**Read the Basetool Knowledge Base before you start, and update it with what you change.** Binding
on every AI agent, without exception. Full text: [root `CLAUDE.md`](../CLAUDE.md#the-knowledge-base-hard-rule--read-before-every-task).

It is the single source of truth about this project — the `basetool-knowledge` vault beside this
repository — and the frontend is also the **parity reference** the Android app is measured against,
so what is written about a screen here is what the app must reproduce.

- **Before**: read the notes for what you are touching (`Frontend`, `Session Lifecycle`,
  `Public Surface`, `Live Sync`, `Design System`, the domain note). They state what a screen
  does, who may see it, and which of its behaviours are load-bearing rather than incidental.
- **With every change**: a new route, a changed `permitAll`, a live-update wiring, a picker's
  remote source, a CSP or session behaviour — each moves with its notes, in the same unit of work.
- **It must never drift.** If a note is wrong, thin, or missing, fix or extend it there and then —
  including when the gap sits outside your task. The code is the authority when the two disagree,
  and the note gets corrected in the same session.
- **Never write a secret or personal data into the vault.**
- **If you cannot find the vault, ask the user where it is at the start of the session.** It is not
  a submodule, so a worktree or a fresh machine may not have it. Never guess a path and never treat
  its absence as the rule not applying.

## UI & design system

The UI is a **binding requirement**: follow the DAS KARTELL design system. The rules —
brand colours, Lato-only typography (headlines = Lato Bold + uppercase), the authoritative department colours, the
square-first sci-fi HUD style, "no native browser dialogs", and the four responsive device
classes — live in [`docs/specs/ui-design-system.md`](../docs/specs/ui-design-system.md). The
visual source of truth is the design skill at
[`.claude/skills/das-kartell-design/README.md`](../.claude/skills/das-kartell-design/README.md)
(its README.md is the source of truth for colours, typography and components).

**The design system is a git submodule (`github.com/krt-profit/design-system`) and MUST be
present before any UI work.** `git worktree add` does not populate submodules, so in a fresh
worktree `.claude/skills/das-kartell-design/` is empty and the source of truth above is
unreadable. A `SessionStart` hook (`.claude/settings.json`) materialises it automatically at
session start (offline-first from the module store, with a copy from the main worktree as
fallback). The hook is cross-platform via two self-guarding entries that dispatch on `command -v
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
- **Page CSS goes into `static/css/pages/<page>.css`**, linked with `<link rel="stylesheet">` where
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

Every external `<script>` is `defer` — head and page modules alike — except `krt-client-error.js`,
which stays the head's first, synchronous script (`REQ-FE-023`). Deferred scripts run in document
order after parsing, so a page module may use every head global at load.

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

- **Markup built in script:** ``krtHtml.set(el, krtHtml`<li data-id="${id}">${name}</li>`)``. Every
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
opens and closes **only** through `window.krtModal.open(el | id)` / `window.krtModal.close(el | id)`
— or the shared `data-trigger="open-modal-display"` / `"close-modal-display"` triggers, which call
it. Never write `overlay.style.display`, never toggle `is-open` yourself: the contract
calls `showModal()`, moves focus in and back, handles Escape, and keeps the class state that live
sync and the tests read. While a dialog is open the page behind it is inert, so anything you append
for the user to see or click — a toast, a confirm, a download link — goes into
`window.krtModal.layerRoot()`, not `document.body`.

**Never write a dialog shell by hand.** Every dialog is a call of `fragments/modal-wrapper :: modal`
(`SingleModalShapeTest` fails on `.krt-modal-overlay`, `.krt-modal`, `.krt-modal-head` or
`.krt-modal-close` anywhere else). The page supplies the body:

```html
<th:block th:replace="~{fragments/modal-wrapper :: modal(modalId='x-modal', titleKey='x.title',
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
| `closeTrigger` | The ✕'s `data-trigger`; default `close-modal-display`. A page handler must end in `window.krtModal.close`. `''` when a script binds the close by `closeClass`. |
| `closeClass` / `closeId` | Class / id on the close control for a page script. |

A condition or iteration goes on a `<th:block>` around the call. In a fragment file, name the body with its template
(`~{fragments/x :: x-modal-body}`). A new dialog id also needs `DialogA11yE2eTest` to reach it, or an
`UNREACHED` entry with the reason. Render a dialog under the same condition as its openers and the
script that drives it: a dialog nothing can open, or whose handlers were never loaded, is dead
markup (the promotion admin all-squadrons view and the bare admin blueprint page were).

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

**Live update and multi-user sync move with every feature — added, changed *or* removed.** Whenever
you add, change or remove a frontend surface that participates in live update or live multi-user
sync (a new editable section, a renamed/retired one, a new mutation on an existing section), you
**must** update its live-update and peer-sync wiring in the **same change** — never defer it to a
follow-up. For the multi-user sync in particular, a section key must stay consistent across **all**
its mirror points at once: the acting client's broadcast (the page's section/seam map), the server
relay's accept-list (`LiveSyncTopicClass.allowedSections`), and the receiving client's apply map. A key present
in one but missing from another **silently** leaves other viewers stale with no error — the
REQ-FE-010 defect that shipped when `objectives`/`frequencies` were added to the write seam but not
the receiver/relay. Prefer deriving these maps from a single source of truth so they cannot diverge;
where they can't share one, changing one **requires** changing the others in the same PR, and the
change is incomplete otherwise. `LiveSyncSectionMapParityTest` fails the build when the relay's
whitelist and a page's JS seam map disagree.

## Type checking (REQ-FE-018, ADR-0125)

The scripts under `static/js` are statically type-checked by `:frontend:typecheckJs`
(`tsc --noEmit`, strict, wired into `check`). **TypeScript is a checker here, not a language:**
the sources stay JavaScript, stay classic non-module `<script>` tags sharing one global scope
(ADR-0069), and nothing is compiled, bundled or renamed. Do **not** convert files to `.ts` — the
full migration is an unscheduled, costed option in
[`docs/TYPESCRIPT_MIGRATION_PLAN.md`](../docs/TYPESCRIPT_MIGRATION_PLAN.md), not a default.

**The module runs TypeScript 7, and the DTO declarations are emitted by us** — `openapi-typescript`
is gone (ADR-0130). Two consequences you will actually trip over:

- **`let x = null;` no longer self-types.** TS 7 grants such a declaration an *evolving* implicit
  `any` only when `noImplicitAny` is on, and this config deliberately keeps it **off**. So TS 7
  infers the literal type `null` and then rejects every later assignment (`TS2322`), every deref
  (`TS18047`) and every post-narrowing use (`TS2339` on `never`) — 179 errors across 12 files when
  the upgrade landed. **Annotate the declaration with the precise type**:
  `/** @type {HTMLInputElement | null} */`, `/** @type {number | null} */`, … `any` is reserved for
  genuinely untyped JSON (a `Map` lookup, an untyped list item) — **no element handle is `any`**,
  and re-introducing one would silently re-open the null-safety gap below. Two DOM-signature
  papercuts follow: `clearTimeout` takes `number | undefined`, so a `number | null` handle needs
  `clearTimeout(t ?? undefined)`; and a generic `getElementById` feeding a specifically-typed
  variable needs a cast at the assignment.
- **`| null` on a handle means the null case is now yours to handle.** Because `noImplicitAny` is
  off, these handles used to be `any` and their null dereferences went unchecked — the gap
  ADR-0125 believed it had closed. They are typed now, so a function that dereferences a handle
  must guard it, and the guard has to cover **every** handle it touches, not just the first one
  (`renderDetailHead` guarded one of six). Before adding a guard, check whether the real fix is
  upstream: a helper with no declared return type poisons everything downstream of it — typing
  `el()` alone fixed nine errors — and a `querySelector` whose result is used as a checkbox or
  text input wants one cast at the query, not a cast at every use.
- **`scripts/gen-api-types.mjs` is ours to extend.** It covers the flat-object subset springdoc
  emits today (`$ref`, `items`, `enum`, `additionalProperties`, `nullable`, primitives) and
  **fails the build** on `allOf` / `oneOf` / `anyOf` / `not` / `discriminator`. The realistic
  trigger is a backend model gaining `@JsonSubTypes`. When that build failure names a construct,
  extend the emitter — never weaken the guard, because a DTO degraded to `unknown` type-checks
  everywhere and silently removes the drift protection the whole mechanism exists for.

`noImplicitAny` stays **off**: turning it on also clears the evolving-`any` class, but costs 514
implicit-any errors — and TS 5.9.3 reported the identical 514, so that is pre-existing annotation
debt rather than anything TS 7 introduced.

- **The language level is ES2025** (ADR-0239, REQ-FE-018): the floor is Chrome 122, Firefox 131,
  Safari / iOS 18.4. `Promise.try`, `RegExp.escape` and `Float16Array` are in TypeScript's `ES2025`
  lib but above the floor, so ESLint rejects them.
- **Opt in per file** with a leading `// @ts-check`. A file that opts in **must** be error-free —
  there is no partial state. Prefer opting in any file you substantially touch.
- **Declare shared contracts in the same change.** A new `window.krt*` API, a new custom DOM event
  or a new Thymeleaf bootstrap constant goes into `frontend/types/globals.d.ts` or
  `frontend/types/thymeleaf-bootstrap.d.ts` as part of the change that introduces it. A bootstrap
  constant additionally goes into the module's `/* global */` header — ESLint's `no-undef` keeps
  the per-page visibility check that the global declaration cannot.
- **Never restate a backend DTO by hand.** Annotate with the generated aliases —
  `/** @param {ApiDto<'MaterialDto'>} row */`, `ApiPage<…>`, `ApiProblem`. The types come from
  `openapi.json` via `:frontend:generateApiTypes`; the generated file is build output and must not
  be committed.
- **In a checked file, JSDoc must be real JSDoc.** The Javadoc spellings used elsewhere in this
  repo — `{@code …}`, `{@link …}`, `@param name {shape}` — are parsed as type syntax and are hard
  errors. Convert them when you opt a file in.

## Package layout (REQ-FE-032, plan §5.9)

The Java code is packaged by domain: a new controller, view row or view helper goes into
`frontend.<domain>.web`, a DTO mirror, form or view model into `frontend.<domain>.model`, the
domain's backend calls into `frontend.<domain>.client`. A reference DTO several domains share, and
the other kernel types, go into `frontend.model`; the kernel packages (`service`, `config`,
`websocket`, `support`, …) take nothing domain-specific. There is no `controller`, `model.dto` or
`model.form` package any more, and `DomainPackageLayoutTest` fails on a class that puts one back.

- **Every DTO of the backend seam carries `@DtoMirror`** (`frontend.model.DtoMirror`): the DTO
  contract tests find mirrors by it, not by package. A `*Dto`, `*Request` or `*Response` type in a
  model package without it fails the build; anything else without it is invisible to those tests.
- **A flashed form or DTO keeps its exact name in `SessionTypeAllowList.SESSION_BOUND_TYPES`**:
  moving or renaming one changes the name its session values carry, so the entry moves in the same
  change (`SessionBoundTypeClosureTest`), and sessions written before the release drop that one
  flash attribute once (`docs/deployment.md` → *The per-domain package move*).
- Bean names are the simple class names or explicit (`moneyFormat`, `relativeDays`, `handles`,
  `markdown`); templates reference no domain class by its package (`T(…)` names only
  `support.Roles`), so a move inside the frontend touches no template.

## Backend calls: resilience & context propagation

- **WebClient** is centrally configured (base URL, default headers, connect/read/write timeouts) in `WebClientConfig`, and **nowhere else** (REQ-FE-029, `WebClientConfinementTest`): only `WebClientConfig` builds a client, and only the backend kernel holds one: `BackendApiClient`, and `BackendSideChannels` for the notification SSE relay and the live-sync probe, the two calls that deliberately skip the resilience pass. A controller calls the backend only through its domain's typed client — `<Domain>BackendClient` in `frontend.<domain>.client`, a thin `@Service` over `BackendApiClient` that owns the domain's paths and returns typed records, never a `Map` (plan F3, `TypedBackendClientTest`); a new backend call is a method there (`execute(…)` for an unusual shape), and a catalogue eviction goes through `CatalogueCacheEviction`. Every failure is mapped once, by `BackendErrorMapper`'s exhaustive switch over its sealed `Outcome`. A runtime value goes into a backend URI as a template variable of the verb's template overload (`post("/api/v1/x/{id}", body, T.class, id)`), never by concatenation, for every verb (REQ-SEC-051, `WriteUriTemplateTest`, `ReadUriTemplateTest`); a value with reserved characters (an `Instant`) stays a `UriComponentsBuilder.queryParam` and only the path variable is left in the template (`fromPath("/api/v1/x/{id}").queryParam(…).encode().build().toUriString()`), so the bytes sent do not change. Each typed client's requests are pinned in a `*BackendClientTest` over `BackendClientHarness` (MockWebServer). Paths are relative `/api/…`: every backend client refuses any origin but `app.backend-url`'s in its first filter, before the OAuth2 filter attaches the bearer — a test that wants a client to reach a local server sets the backend URL to that server instead of passing an absolute URI. A future HTTP-interface client is created over the `webClient` bean and takes no `URI`, `UriBuilderFactory` or `@CookieValue` parameter, names no absolute URL and carries no `@Cacheable`.
- **Resilience4j** wraps every call through `webClient` and `termsDocumentClient` (Timeout, Retry, CircuitBreaker, Bulkhead); the SSE relay's `sseWebClient` and the live-sync probe's `liveSyncAuthWebClient` deliberately carry none. State transitions are logged via `ResilienceEventLogger` so `SERVICE_UNAVAILABLE` / `BACKEND_TIMEOUT` always have a matching log line.
- **Reactor context propagation is mandatory for any new `ThreadLocal` you want to see inside `WebClient` exchange filters.** `WebClient.exchange()` runs on a Reactor-Netty worker thread, not the servlet thread; classic `ThreadLocal` values are not copied across threads. Register a `ThreadLocalAccessor` on `ContextRegistry.getInstance()` in [`ReactorContextPropagationConfig`](src/main/java/de/greluc/krt/profit/basetool/frontend/config/ReactorContextPropagationConfig.java) (which also enables `Hooks.enableAutomaticContextPropagation()` at startup). The existing accessors cover `ActiveSquadronContext` (active-OrgUnit pin → `X-Active-Org-Unit-Id` outbound header), `CorrelationContext` (correlation id propagation), Spring's `LocaleContextHolder` (user locale) and `ClientIpContext`. Forgetting the accessor means the holder is invisible on the worker thread and the outbound call silently drops whatever it carried. Register it inside `ReactorContextPropagationConfig#registerRelayAccessors`: `ParallelPageLoader` restores a `ContextSnapshot` of the same registry on its virtual threads, so the accessor reaches parallel page sections with no change to the loader (REQ-FE-030). Never hand-copy a holder in the loader again — that is how the locale went missing there.
- Use `MockWebServer` / WireMock to test error paths.

## Concurrency — the frontend half

- **Frontend DOM version sync** — when an entity is updated via AJAX (dropdown change, row reorder, etc.), the new `version` must propagate to **every** related DOM element in the same context (edit/action buttons, modals inside the same `<tr>` or container). A missed `data-version` attribute → 409 on the user's next click. A reload on success is **not** an escape hatch here: the Live update rule above forbids it, so a tangled update is re-rendered through a fragment swap instead.

The backend half — the `kernel.OptimisticLock` helper family, `Mission`'s manual section
counters, the `…WithinTransaction` pattern, bulk-updates-inside-loops and the find-or-create
retry — lives in [`backend/CLAUDE.md`](../backend/CLAUDE.md).
