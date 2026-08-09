# CLAUDE.md — frontend

Frontend-specific guidance. Loads when Claude works with files under `frontend/`. The
cross-cutting rules (requirements, i18n, Git, documentation) stay in the
[root `CLAUDE.md`](../CLAUDE.md).

## UI & design system

The UI is a **binding requirement**: follow the DAS KARTELL design system. The rules —
brand colours, Lato-only typography (headlines = Lato Bold + uppercase), the authoritative department colours, the
square-first sci-fi HUD style, "no native browser dialogs", and the four responsive device
classes — live in [`docs/specs/ui-design-system.md`](../docs/specs/ui-design-system.md). The
visual source of truth is the design skill at
[`.claude/skills/das-kartell-design/README.md`](../.claude/skills/das-kartell-design/README.md)
(README.md = Quelle der Wahrheit für Farben, Typografie, Komponenten).

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
relay's accept-list (`BROADCASTABLE_SECTIONS`), and the receiving client's apply map. A key present
in one but missing from another **silently** leaves other viewers stale with no error — the
REQ-FE-010 defect that shipped when `objectives`/`frequencies` were added to the write seam but not
the receiver/relay. Prefer deriving these maps from a single source of truth so they cannot diverge;
where they can't share one, changing one **requires** changing the others in the same PR, and the
change is incomplete otherwise.

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

- **Frontend DOM version sync** — when an entity is updated via AJAX (dropdown change, row reorder, etc.), the new `version` must propagate to **every** related DOM element in the same context (edit/action buttons, modals inside the same `<tr>` or container). A missed `data-version` attribute → 409 on the user's next click. If targeted updates are too tangled, just `window.location.reload()` on success.

The backend half — the `support.OptimisticLock` helper family, `Mission`'s manual section
counters, the `…WithinTransaction` pattern, bulk-updates-inside-loops and the find-or-create
retry — lives in [`backend/CLAUDE.md`](../backend/CLAUDE.md).
