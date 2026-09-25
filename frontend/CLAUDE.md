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
  a template. It is linted by `:frontend:lintCssInline` with the tiny template rule set, and
  formatted by Prettier.

### CSS: the layer decides, not the load order (binding)

Every stylesheet starts with `@layer base, components, page, migration, utilities;` and puts every
rule inside one of those layers (`REQ-UI-024`, ADR-0212, `CascadeLayerOrderTest`). Between layers the
order decides, before specificity:

- `styles.css` owns `base` (fonts, tokens) and `components`; every page or area stylesheet is
  `page`; `inline-migration.css` is `migration`; `krtm-hidden` / `krtm-modal-open` are `utilities`.
- A page rule beats a design-system rule as an ordinary rule. Do not bump specificity
  (`main .x`, `div.x`, `.x.x`) and do not add `!important` for it.
- A design-system declaration that has to beat page CSS goes into the `@layer page` block at the end
  of `styles.css`. Never into `utilities`, which would also beat
  the page rules that out-specify it and every migrated inline class.
- A migrated `krtm-*` class beats page and component rules, like the inline style it replaced. To
  restyle such an element, remove the migrated class from the markup; do not fight it.
- A rule outside any layer beats every layer. That is why the test fails on one.

### Script load order (binding — it has regressed three times)

Every external `<script>` is `defer` — head and page modules alike — except `krt-client-error.js`,
which stays the head's first, synchronous script (`REQ-FE-023`). Deferred scripts run in document
order after parsing, so a page module may use every head global at load. An **inline** script runs
earlier, during parsing: at its top level it may only declare constants, functions and `window.*`
dictionaries, look up elements above it and register listeners / `window.krtEvents.on(...)`;
whatever it has to run goes into `document.addEventListener('DOMContentLoaded', …)`. A top-level
`bindX()` or IIFE that touches `window.krtFetch` silently does nothing. `InlineScriptLoadOrderTest`
fails the build on it, `ScriptLoadOrderE2eTest` checks it in the browser.

### Dialogs: one contract (binding)

Every dialog is a `<dialog class="krt-modal-overlay">` > `.krt-modal` (REQ-UI-013, ADR-0177) and
opens and closes **only** through `window.krtModal.open(el | id)` / `window.krtModal.close(el | id)`
— or the shared `data-trigger="open-modal-display"` / `"close-modal-display"` triggers, which call
it. Never write `overlay.style.display`, never toggle `krtm-modal-open` yourself: the contract
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

## Concurrency — the frontend half

- **Frontend DOM version sync** — when an entity is updated via AJAX (dropdown change, row reorder, etc.), the new `version` must propagate to **every** related DOM element in the same context (edit/action buttons, modals inside the same `<tr>` or container). A missed `data-version` attribute → 409 on the user's next click. A reload on success is **not** an escape hatch here: the Live update rule above forbids it, so a tangled update is re-rendered through a fragment swap instead.

The backend half — the `support.OptimisticLock` helper family, `Mission`'s manual section
counters, the `…WithinTransaction` pattern, bulk-updates-inside-loops and the find-or-create
retry — lives in [`backend/CLAUDE.md`](../backend/CLAUDE.md).
