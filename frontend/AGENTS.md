# AGENTS.md — frontend

Frontend rules, on top of the [root `AGENTS.md`](../AGENTS.md). Each section summarises a reference
file under [`docs/agents/`](../docs/agents/README.md); **read that file before the work it covers.**

## UI & design system

The UI is a **binding requirement**: follow the DAS KARTELL design system. The rules — brand
colours, Lato-only typography, the department colours, the HUD style with a 4 px radius, no native
browser dialogs, the four device classes — are in
[`docs/specs/ui-design-system.md`](../docs/specs/ui-design-system.md); the visual source of truth
is the design skill's
[`README.md`](../.claude/skills/das-kartell-design/README.md).

**The design system is a git submodule and MUST be present before any UI work.** A fresh worktree
leaves `.claude/skills/das-kartell-design/` empty. Claude Code's `SessionStart` hook populates it
and fast-forwards it to the design system's `main` (ADR-0259); without that hook, run
`git submodule update --init .claude/skills/das-kartell-design` before touching any frontend
surface. Never treat an empty directory as "no design system applies". Details (the hook, the
offline fallback, the pin): [`docs/agents/frontend-design-system.md`](../docs/agents/frontend-design-system.md).

## Templates, CSS, scripts and dialogs (binding)

Read [`docs/agents/frontend-templates-and-assets.md`](../docs/agents/frontend-templates-and-assets.md)
before changing a template, a stylesheet, a script tag, a DOM write or a dialog. Its rules:

- **No plain HTML comment and no `<style>` in a template** (REQ-UI-023); page CSS goes into
  `static/css/pages/<domain>/<page>.css`; colours and stacking go through tokens (REQ-UI-001).
- **Every stylesheet puts every rule into a cascade layer** (`base, components, page, utilities`;
  REQ-UI-024): no specificity bumps or `!important` to win, no new `krtm-*` class.
- **Every external script is a module** except `krt-client-error.js`; **an inline script is a data
  bootstrap and nothing else** (ADR-0069, `InlineScriptDataOnlyTest`).
- **Trusted Types: every DOM sink takes a policy value** — `krtHtml` for markup built in script,
  `krtFetch.setTrustedHtml` and friends for a server fragment, `replaceChildren()` to clear.
- **One dialog contract**: a call of `fragments/modal-wrapper :: modal`, opened and closed only
  through `krtModal` (REQ-UI-013); never a hand-written dialog shell.
- **One layout fragment**: every page is composed through `fragments/layout :: page` (REQ-FE-033).

## Live update (binding)

**Every part of the frontend supports live update to the current standard.** Every mutating,
filtering or paginating interaction updates the DOM **in place** through `krtFetch` / `krtCsrf` /
fragment swap, with no full-page reload on success (the two sanctioned reloads are in
`REQ-FE-008`); reads go through `krtFetch` too (REQ-FE-031); a section-structured page is wired
through `krtPageSections.create` (ADR-0289). **Live update and multi-user sync move with every
feature added, changed or removed**, and a section key stays consistent across the broadcast, the
relay's `LiveSyncTopicClass.allowedSections` and the receiver in the same change
(`LiveSyncSectionMapParityTest`). Read
[`docs/agents/frontend-live-update.md`](../docs/agents/frontend-live-update.md) and
[`docs/specs/frontend-ajax-mutations.md`](../docs/specs/frontend-ajax-mutations.md) before any
mutation, read or live-synced section.

## JavaScript: type checking and ES modules

Read [`docs/agents/frontend-javascript.md`](../docs/agents/frontend-javascript.md) before writing or
moving a script under `static/js`. Its rules:

- **Every script is type-checked by `:frontend:typecheckJs`** (strict `checkJs`, REQ-FE-018,
  ADR-0125); the sources stay JavaScript — never convert to `.ts`. Every parameter, variable and
  return carries a JSDoc type, no element handle is `any`, a caught value is `unknown`, no
  `@ts-ignore`; DTO types come from the generated `ApiDto<…>` aliases, never restated by hand.
- **A new script is added to its domain's list in `typecheck-ratchet.json`.**
- **Every script is an ES module** (REQ-FE-035, ADR-0250): linked as `type="module"`, imports by
  absolute path from its own domain or `/js/kernel/` only, no `import()`, no IIFE, no new `window`
  seam — a page's parts meet in the domain's hook registry.

## Package, template and asset layout

Read [`docs/agents/frontend-layout.md`](../docs/agents/frontend-layout.md) before adding or moving a
class, template, script or stylesheet. Java is packaged by domain (`frontend.<domain>.web|model|client`,
cross-cutting code in a `kernel.*` package, REQ-FE-032); the kernel reaches into no domain; every
backend-seam DTO carries `@DtoMirror`. Templates and assets sit in a domain folder with the same
name (REQ-FE-034); a move is a `git mv` plus a rewrite of every reference.

## Backend calls: resilience & context propagation

Read [`docs/agents/frontend-backend-calls.md`](../docs/agents/frontend-backend-calls.md) before
adding a backend call or a `ThreadLocal` an outbound call needs. Its rules: only `WebClientConfig`
builds a `WebClient` (REQ-FE-029); a controller calls the backend only through its domain's typed
`<Domain>BackendClient`; runtime values go into a URI as template variables (REQ-SEC-051);
Resilience4j wraps every `webClient` call; **every new `ThreadLocal` an exchange filter needs gets a
`ThreadLocalAccessor` in `ReactorContextPropagationConfig`**. Test error paths with
`MockWebServer` / WireMock.

## Concurrency — the frontend half

- **Frontend DOM version sync** — when an entity is updated via AJAX (dropdown change, row reorder, etc.), the new `version` must propagate to **every** related DOM element in the same context (edit/action buttons, modals inside the same `<tr>` or container). A missed `data-version` attribute → 409 on the user's next click. A reload on success is **not** an escape hatch here: the Live update rule above forbids it, so a tangled update is re-rendered through a fragment swap instead.

The backend half is in [`docs/agents/backend-concurrency.md`](../docs/agents/backend-concurrency.md).
