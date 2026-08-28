> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-07-10.
> **Owner area:** FE/UI · **Related ADRs:** ADR-0012, ADR-0013, ADR-0031, ADR-0053, ADR-0069, ADR-0071, ADR-0094

# Frontend AJAX mutations — krtFetch, krtCsrf & fragment swaps

## Context & goal

Working with data in the `frontend` module must not reload the page. Every create / update / delete
/ toggle / reorder / filter / paginate interaction updates the DOM **in place** through one shared
client foundation, so users keep scroll and focus and the app ships a small payload per edit instead
of re-rendering the whole document. This spec is the durable contract behind epic #571; the
architecture decision (and the rejected alternatives — htmx, cookie-CSRF, app-wide Alpine) is
ADR-0012. Phase 0 (#572) builds the foundation + exemplars; per-area conversions are the epic's
child issues (#573–#582).

The foundation lives in [`krt-fetch.js`](../../frontend/src/main/resources/static/js/krt-fetch.js),
loaded globally from `fragments/head.html`. It exposes `window.krtFetch` (`write`, `submitForm`,
`swap`, `syncVersion`, `sectionWrite`, `serialize`) and `window.krtCsrf` (`headers`, `token`, `refresh`). The toast/confirm infrastructure
(`showFrontendSuccessToast`, `showFrontendErrorToast`, `showKrtConfirm`) is the design-system-mandated
replacement for native dialogs (see [`ui-design-system.md`](ui-design-system.md)).

**Section-structured pages use the `krtFetch.sectionWrite(config)` factory** (ADR-0069, #924): from
a config of already-localized i18n lookups (a late-bound dict getter + key prefixes + fallbacks), a
section→`{container, fragmentValue}` map, a page-URL getter and an optional peer-broadcast closure,
it returns the page's `{write, refresh, notify}` seam — `write` decorates `krtFetch.write` with the
section's localized success/error/conflict strings, `refresh` re-renders one or more sections in
place via `krtFetch.swap` (broadcasting to peers first unless the refresh itself applies a peer
signal, `{broadcast: false}`), and `notify` is the broadcast-only sibling for handlers that patch
their own DOM surgically. Every lookup is late-bound (evaluated per call), because the i18n dicts
and the presence client are published by bootstraps that run after the module loads. mission-detail
is the canonical consumer (its `window.krtMissionWrite` / `window.krtRefreshMissionSection` /
`window.krtNotifyMissionChanged` aliases are the factory's three returns); new pages with per-section
writes reuse the factory instead of hand-rolling the wrapper trio.

## Requirements

### REQ-FE-001 — No full-page reload on a successful mutation

A create / update / delete / toggle / reorder interaction must update only the changed DOM nodes and
must not navigate the document (no `window.location.reload()`, no redirect-follow) on success. The
**only** sanctioned reloads are (1) the optimistic-lock conflict path, where the user explicitly
accepts a reload via `showKrtConfirm`, and (2) the bfcache history-restore refresh of REQ-FE-008 —
which is not a success-path reload but a freshness guarantee for a document the browser replays from
its back/forward cache.

**Acceptance**

- [ ] After the action, the URL and document are unchanged and the affected node(s) reflect the new
  state (badges, totals and status cells re-derived in the success handler, not by a reload).
- [ ] No `location.reload()` runs on the success path; the only success-path-adjacent reloads are a
  user-accepted optimistic-lock confirm and the bfcache history-restore refresh (REQ-FE-008).
- [ ] Derived UI that lives **outside** the swapped fragment is refreshed too — an emptied list
  restores its "no entries" placeholder, and a count or server-derived value shown in a separate
  modal/header reflects the new state (when a fragment swap cannot cover it, the handler patches it
  explicitly, e.g. the hangar home-location ship count re-rendered on modal open, the category/alias
  placeholder rebuilt on the last delete, the order-detail header re-pulled on **every** status
  change so a reactivated order's reassigned priority renders in place — not only on the terminal
  transition).
- [ ] A write that **replaces the entity identity** (delete-old + create-new — e.g. a full-amount
  inventory transfer that appends a new target item and deletes the source) re-keys the affected DOM
  row + its controls to the new id/version, so a follow-up action targets the live entity, not the
  removed one. An optimistic native control (checkbox / select) reverts to its prior state on a
  failed write, so the rendered state never diverges from the persisted value.
- [ ] The submit control is disabled for the duration of the in-flight write and re-enabled when it
  settles, so a double-click cannot fire a duplicate create or a stale-version delete. Enforced
  centrally: `krtFetch.write` auto-captures the triggering form's submit button and toggles it
  (raw-`fetch` write paths — order/refinery create, the mission-data helper — guard it explicitly).

**Enforced by:** per-area Playwright e2e (no-navigation assertion) +
`MaterialsCategoryEmptyStateInPlaceE2eTest` (empty-state restore) +
`MaterialCollectionTransferInPlaceE2eTest` (row re-keyed after a transfer) +
`JobOrderReactivatePriorityInPlaceE2eTest` (header refreshed on reactivate) · **Code:**
`krt-fetch.js`, the converted page handlers · **Issues:** #571, #572

### REQ-FE-002 — All writes go through `krtFetch` + `krtCsrf`

Mutations use `krtFetch.write(...)`; no page may hand-roll a `fetch` write or a CSRF-header read.
`krtCsrf` is the single source of truth, reading the freshest `meta[name="_csrf"]` /
`meta[name="_csrf_header"]` tags. Hard-coded header names (e.g. the former `members.html`
`X-CSRF-TOKEN`) are forbidden.

**Multipart / `FormData` writes use `krtFetch.submitForm(...)` (S10, #916).** A form-POST write — a
`FormData` body rather than a JSON payload — goes through `krtFetch.submitForm({form, ...})`, the
multipart twin of `write`. Both entry points share **one** request orchestration (`send`): the
CSRF header, the bare-403 refresh-and-retry-once (REQ-FE-004), the `X-Reauthenticate` redirect
(REQ-SEC-012), the guest-edit-token replay (REQ-SEC-018), `syncVersion` (REQ-FE-003), the
success toast, and the RFC 7807 `handleProblem` conflict UX. `submitForm` **must not** set a
`Content-Type` header on a `FormData` body — the browser sets `multipart/form-data` together with
its boundary itself; the CSRF token rides in the header, never in the form body. A page may
therefore **not** re-implement the CSRF-header + retry-on-403 + FormData loop by hand — that
hand-rolled loop (the historical root cause of most krtFetch deviations) is exactly what
`submitForm` replaces. Page-specific success/error behaviour hangs off the `onSuccess(body)` /
`onError(status, body, response)` / `onNetworkError(error)` hooks (e.g. navigate-after-AJAX per
REQ-FE-006, in-place `{field: message}` validation rendering per REQ-FE-007, a section re-swap, a
modal close, restoring an optimistic control when the transport itself fails); the
form keeps its `th:action`/`method=post` and each listener guards with `if (!window.krtFetch)
return;` (or `form.submit()`) so a script-disabled browser still gets the native POST → redirect
fallback. An **in-place** form write must not fall back to a full-page reload on success (the only
sanctioned in-place reloads remain the REQ-FE-003 conflict-confirm and the REQ-FE-008 bfcache
restore). A **navigate-away** form write (REQ-FE-006 — a create/edit that leaves the page) takes the
server-supplied `targetUrl` from the 2xx body and `window.location.assign`s it on success; it may
`window.location.reload()` **only** as the defensive fallback for a `targetUrl` that is unexpectedly
absent from an otherwise-successful response — never as its normal success path.

**Server-side relay helpers (S11, #917).** The page controllers' in-place AJAX write handlers relay
a backend failure to `krtFetch` as `application/problem+json` via the shared
`support.BackendErrorResponses.propagateBackendError(BackendServiceException)` — the single copy of
the `status`/`code`/`detail`/`correlationId` relay the controllers previously each duplicated, so
`krtFetch` branches on the conflict semantics identically everywhere; do not re-hand-roll the relay.
The residual non-AJAX Post/Redirect/Get handlers (e.g. the job-order drag-drop reorder and delete)
use `support.MutationResponseHelper.mutate(…)` for the `try → successToast / catch → errorToast →
redirect` flash pattern. Handler-specific form re-population and `409`/concurrency-conflict branching
stay in the handler (the helper carries only the generic toast).

**App-wide double-submit guard (#1133).** The shared submit orchestration disables the triggering
submit button for the whole in-flight round-trip and re-enables it when the request settles (success
/ error / network), so a double-click cannot fire a second duplicate write — a silent duplicate
finance-ledger row, a self-409 stale-version PUT, or a 404-toasting second DELETE over a succeeding
navigation. The button is captured on the capture-phase document `submit` listener and MUST be
consumed + disabled **synchronously** at the top of `write()` / `submitForm()`, during the same
submit-event dispatch — **before** `runSerialized` defers the send **and before** the capture
listener's clearing microtask runs. Consuming it later inside the deferred `send()` always loses the
FIFO microtask race to the clear and leaves the button enabled (the dead-guard regression #970
shipped and #1133 restored). A raw-`fetch` call site that does not go through `write`/`submitForm`
MUST pass an explicit `submitter` (e.g. the operation-detail delete form).

**Acceptance**

- [ ] A new write call site uses `krtFetch.write` (JSON) or `krtFetch.submitForm` (FormData) /
  `krtCsrf.headers()` — no bespoke CSRF block, no hand-rolled retry-on-403 FormData loop.
- [ ] No template or JS hard-codes the CSRF header name.
- [ ] A `submitForm` write never sets `Content-Type` on the `FormData` body, keeps its no-JS
  native-submit fallback, and does not full-page reload on success.
- [ ] A double-click on a submit button driving a `krtFetch.write` / `submitForm` fires exactly one
  request; the button is disabled synchronously on first submit and re-enabled when it settles.

**Enforced by:** code review + grep guard in review, per-area double-submit e2e · **Code:**
`krt-fetch.js` (`write`, `submitForm`, `send`, `resolveSubmitter`) · **Issues:** #572, #916, #1133

### REQ-FE-003 — `syncVersion` propagates the optimistic-lock version

On a successful write whose response carries a `version`, `krtFetch` writes that value to the
target container **and every descendant `[data-version]`**, so the user's next action on the same
aggregate sends the fresh version and does not 409. When propagation cannot be made complete in
place, the handler reloads deliberately (the conflict-confirm path) rather than leaving a stale
version. This is the client half of the `@Version` rules in `CLAUDE.md`.

**Backend obligation.** The contract above only holds if the mutation response carries the
_post-write_ version. A service (or class-`@Transactional` controller) that maps the response DTO
**inside the still-open transaction** must persist with `saveAndFlush`, not `save`: the `@Version`
increment is otherwise deferred to commit — _after_ the DTO is mapped — so the response carries the
**stale pre-flush** version, the client writes it back in place, and the next action 409s. This does
**not** apply to entities whose section version is a manual in-memory counter bumped before mapping
(e.g. `Mission.coreVersion` / `scheduleVersion` / `flagsVersion` / `partyLeadVersion`), which is
already current in the DTO regardless of flush timing, nor to writes whose handler re-renders the
fragment from a fresh server `GET` (the re-swap re-reads the committed version). See the
optimistic-locking rules in `CLAUDE.md`. (A 2026-06 area audit added
`JobOrderService.updateJobOrder` to the `saveAndFlush` set — its edit-modal version writeback
otherwise 409s the next consecutive order edit. A 2026-07 audit added the participant write path —
`MissionParticipantService.updateParticipantAttributes` / `checkIn` / `checkOut` — for the same
reason: `MissionController` is class-`@Transactional` and maps the slim participant DTO inside the
transaction, so a plain `save()` shipped a version stale by one to the S1 check-in/edit writeback,
and the participant's own next edit self-409'd. The mid-method payout flush was dropped into the
single end-of-method flush so the version bumps exactly once, #1135.)

**Every edit path MUST actually echo a version the backend can check (#1131).** `syncVersion`
propagating the fresh version is moot if the write DTO never carried one in the first place: because
the mission **unit** and **crew** full-form edits rewrite every field from the caller's snapshot, the
child `@Version`'s Hibernate WHERE clause always matches the freshly-loaded row and never fires on a
stale form — the client-echoed version is the only guard. `MissionUnitDto` / `MissionCrewDto` now
expose `version`, the unit/crew edit buttons render `th:data-version`, and the unit-edit, crew-modal
and crew quick-change payloads all echo it (`UpdateUnitRequest` / versioned `UpdateCrewRequest`), so a
stale save 409s instead of silently clobbering a concurrent edit; the fresh version rides back on the
`krtRefreshMissionSection('crew')` fragment re-render (the "re-render from a fresh GET" case above).

**Acceptance**

- [ ] A second consecutive action on the same row/aggregate after a successful write does not
  produce a 409.
- [ ] All related `[data-version]` attributes in the DOM context hold the new version after success.
- [ ] A write whose response feeds an in-place version writeback returns the flushed
  (post-increment) version — `saveAndFlush` wherever the DTO is mapped inside the transaction.
- [ ] The mission unit and crew edit forms echo the child `@Version`; a stale full-form unit/crew
  save returns `409`, and the follow-up edit after a successful save (fresh version from the
  fragment re-render) does not.
- [ ] A participant edit / check-in / check-out immediately followed by a second edit of the same
  participant does not 409 (the slim response carries the committed, post-flush `@Version`).

**Synchronous fan-out before the async refresh.** A handler that re-opens the same entity's modal
within the (load-inflated) fragment-swap window would snapshot the pre-write `data-version` still on
the row/button and self-409 on the second save. So — mirroring the check-in/out fan-out — the
**participant edit** modal (#1116), the **finance-entry** edit (#1144) and the **custom-frequency**
edit (#1144) now propagate the bumped `@Version` from the write response to their row/button (and
the finance/frequency hidden modal input) **synchronously in the `res.ok` block, before** the async
`krtRefreshMissionSection` swap lands. The **payout-preference** fan-out additionally guards each
write with a monotonic check (only advance, never regress, a container's `data-version`) so an older
payout response landing after a newer check-in cannot re-arm the stale-version 409 (#1145).

**Acceptance (fan-out)**

- [ ] Editing a participant / finance entry / custom frequency and re-opening the **same** entity's
  modal before the section swap lands and saving again does not 409.
- [ ] A payout-preference toggle whose response resolves after a newer same-participant write never
  lowers that participant's `data-version`.

**Enforced by:** per-area e2e "double-action" assertion, `MissionServiceCrewTest` (unit/crew
version-mismatch 409), `MissionServicePayoutTest` (check-in/out flush) · **Code:** `krt-fetch.js`
(`syncVersion`), `MissionStructureService`, `MissionUnitDto` / `MissionCrewDto`,
`MissionParticipantService`, `mission-detail.js` (participant-edit / finance / custom-frequency /
payout-preference response fan-out) · **Issues:** #571, #1131, #1135, #1116, #1144, #1145

### REQ-FE-004 — CSRF stays session/meta-based with transparent retry-on-403

The CSRF token repository and handler are unchanged (`HttpSessionCsrfTokenRepository` +
`XorCsrfTokenRequestAttributeHandler`). An authenticated `GET /csrf` returns `{headerName, token}`.
On a bare `403` from a write, `krtFetch` refetches the token once, updates the `_csrf` meta tags,
and retries the request exactly once before surfacing the error. `/csrf` rejects anonymous callers
(it sits under the `authenticated()` catch-all). This is additive, not a binding security-model
change (ADR-0012).

**Acceptance**

- [ ] `GET /csrf` returns the current header name + token for an authenticated session and does not
  serve an anonymous caller a token.
- [ ] A write with a stale token succeeds after exactly one transparent token-refresh + retry; a
  genuinely failing write still surfaces its error (no infinite retry).

**Enforced by:** `CsrfTokenControllerMvcTest` + forced-stale-token e2e · **Code:**
`CsrfTokenController`, `krt-fetch.js` (`krtCsrf.refresh`, retry path) · **Issues:** #572

### REQ-FE-005 — Lists / filters / pagination are in-place fragment swaps

Filtering, sorting and paginating a list use `krtFetch.swap`, which loads the controller's
`?fragment=results` HTML fragment into the results container and intercepts in-container pagination
/ sort anchors (`a.page-btn[href]` and opted-in `a[data-swap][href]`) so paging stays in place. This
fixes the regression where pagination anchors inside an AJAX results container triggered a full
navigation while filtering did not.

**Acceptance**

- [ ] Changing a filter swaps the results without navigating.
- [ ] Clicking a pagination/sort control inside the results container swaps in place (no full page
  load) and preserves the active filter query.
- [ ] A swap whose GET is **redirected** (e.g. an expired session bounced to the login page) or
  returns a **non-OK** status does not inject the body: `krtFetch.swap` bails, leaves the stale
  container untouched, surfaces an optional caller-supplied error toast, and never paints a whole
  page into the small results container. On a backend read failure the mission-detail fragment
  branch returns a **section-sized inline error fragment (HTTP 200)**, not a `redirect:/missions`.
- [ ] That inline error fragment is **inert on the normal full-page render**: it lives inside a
  `<template>`, so it can never paint a permanent "the section could not be refreshed" line on a
  page where nothing failed. A `th:if` on the fragment element itself is *not* the way to do this —
  it would suppress the intended fragment render too. Thymeleaf still resolves the `th:fragment`
  inside the `<template>`, and the fragment selector returns only the paragraph.

The same fragment-swap mechanism also re-renders **non-list page sections** after a sub-mutation
when an in-place DOM patch would be too fragile (structural add/delete, server-derived render state,
or a value duplicated across panes). The mission-detail page (#574) does this: a crew/finance/owner-
manager write re-renders just its section via `GET /missions/{id}?fragment={crew-board,finance,mgmt}`
into a stable `#…-results` container, and every per-element handler inside (drag-drop, action
buttons, role selects) is delegated on the persistent container so it survives the swap. The
order-detail page (#575) does the same: claim create/edit/withdraw, the inventory unlink and the edit
modal re-render the `header` / `materials` / `aggregated` sections via `GET /orders/{id}?fragment=…`
(a server-derived aggregate like the claims "Offen" amount would desync a partial patch), and the
order-list drag-drop priority reorder re-renders the whole queue the same way (the backend reshuffles
every sibling's priority). The fresh `data-version` carried by the re-rendered fragment satisfies
REQ-FE-003 for free, and on a backend read failure the fragment branch returns a section-sized error
fragment, never a redirect the swap would follow into the container.

The operations area (#576) combines the patterns through a set of `X-Requested-With` write twins
(`createOperationAjax` / `updateOperationAjax` / `deleteOperationAjax`) beside the classic
POST→redirect fallbacks. Creating or deleting from the list re-renders `#operations-results` via the
existing `GET /operations?fragment=results` swap (the page exposes `window.krtOperationsReload` so the
write handlers reuse the active filter query); editing on the detail page patches the version input
and the title in place from the twin's `{version, name, status}` (the backend PUT echoes the
persisted operation in-transaction, so no second round-trip can observe a concurrent writer's
`version+2` or mask an already-committed write — no navigation); deleting
from the detail page navigates back to the list (the entity is gone — REQ-FE-006). The payout
paid-out toggle was already in-place — its bespoke CSRF read now goes through `krtCsrf` (REQ-FE-002).

The inventory area (#577) is converted in two parts; **part A** does the create + metadata writes.
Book-in (`addInventoryItemAjax`, a multipart `X-Requested-With` twin beside the classic
POST→redirect) navigates to the source listing on success and keeps the form with a toast on a 422
(REQ-FE-006); the note and association edits (`inventory-my` / `inventory-admin`) drop their
hand-rolled CSRF reads and per-`[data-id]`/`[data-note-for]` version loops for `krtCsrf`
(retry-on-403) + `krtFetch.syncVersion` against the acting control's `.tree-row--leaf` container (so
the next edit on that row does not 409 — REQ-FE-003), and the note edits plus the bulk-selection
guards surface their success/error outcome through the shared `showFrontendSuccessToast` /
`showFrontendErrorToast` globals — the page-local `showInventoryToast(type, msg)` helper delegates to
them rather than to a non-existent page element; the material-collection owner/location transfer
and delivered toggle move onto `krtFetch.write` (per-row patch; a full-amount transfer deletes the
source item server-side and appends a new target item still linked to the job order, returning that
target DTO — the row is re-keyed in place to the new id/version rather than removed, and a failed
delivered toggle reverts the checkbox); and the admin delete-all clears the grouped table via the
existing
filter swap instead of a reload. **Part B** does the quantity-changing list writes. The single
**book-out** modal (`inventory-my` / `inventory-admin`, all three of DISCARD / TRANSFER / SELL)
submits in place through `krtFetch.write`, reusing the existing `POST /inventory/{id}/transfer`
proxy (the same backend book-out endpoint — equivalent for every type, since
`InventoryItemBookOutDto` only requires `amount` + `version`); on success it re-renders the grouped
table through the filter swap (`filterMyInventory` / `filterInventory`) because a book-out regroups
server-side, and `scu-decimal-input.js`'s capture-phase submit listener canonicalises + validates the
amount before the page handler runs (which respects `event.defaultPrevented`). **Bulk-checkout**
(personal inventory only) now posts to a new `POST /inventory/bulk-checkout` frontend proxy — the
page previously called the backend `/api/v1/inventory/bulk-checkout` path directly, which had no
matching frontend route, so the bulk action never reached the backend; the proxy relays the call and
`propagateBackendError`s a failure, and the client re-swaps the grouped table + resets the bulk bar
instead of reloading. Both reuse the shared `frontend.ajax.conflict.*` strings for the
OPTIMISTIC_LOCK reload-confirm.

The refinery **screenshot-extract import** (#591) applies the same fragment-swap idea to a
**multipart POST** rather than a GET: an `X-Requested-With` import twin
(`RefineryOrderPageController.importExtractAjax`) returns the pre-filled create-form fragment
(`refinery-orders-create :: refineryImportFormBody`), and a bespoke `fetch` swaps it into the stable
`#refineryImportFormContainer` then dispatches `krt:swapped` (`krtFetch.swap` is GET-only and cannot
carry the upload). Every branch — success and each error (invalid/oversized file, unparseable JSON,
backend reject) — renders inline in the swapped region, never a redirect; the classic
`POST→redirect` proxy stays the no-JS fallback. This required making `datetime-splitter.js`
**swap-safe**: an idempotent per-group `init` guarded by `data-krt-dt-initialized` plus a
`krt:swapped` auto-reinit, so the create form's date widget — and any future fragment-swapped
datetime group app-wide — re-initialises exactly once after a swap (no double-bound listeners, no
duplicate error div).

The asset-management area (#578) — **hangar**, **ship-data** and the **personal-inventory** /
**blueprints** pages — combines the twin + fragment-swap patterns through a set of `X-Requested-With`
write twins beside the classic `POST→redirect` fallbacks. **Hangar** create/edit (the modal form),
delete and the bulk home-location set submit through `krtFetch.write` to header-gated twins
(`addShipAjax` / `updateShipAjax` / `deleteShipAjax` / `setHomeLocationAjax`) and re-render the ship
table via the existing `GET /hangar?fragment=results` swap (the server multi-key sort makes a
client-side row insert too fragile); the import + delete-all flows drop their post-action
`location.reload()` for the same swap and their two hand-rolled CSRF reads move onto `krtCsrf` (the
multipart import keeps a bespoke `fetch` minus the JSON `Content-Type`). Because the action + per-row
edit buttons live inside the swapped `#hangar-results` fragment they are bound through `krtEvents`
`data-trigger` delegation (and the live ship-type filter is a delegated document listener) so they
survive every re-swap. **Ship-data** flips each visibility toggle in place (button label + secondary
style + dimmed opacity, the hidden input updated so the next toggle sends the opposite value) and the
admin reset-all-fitted toasts + closes its modal, all without navigating. **Personal-inventory**
add/edit/delete go through JSON twins that re-render the existing `#pi-results` list fragment.
**Blueprints** note-edit returns the fresh blueprint from its twin so the master row (note + version
+ note-marker badge) and the detail pane are patched in place (the selection and the loaded recipe
survive); remove, batch-add and import-apply re-render the new `#krt-bp-list` fragment (`recipe.js`
re-inits its master/detail wiring and `personal-inventory-blueprints.js` resyncs the header counts on
`krt:swapped`), and the variant CSRF helpers in `personal-inventory-blueprints.js` /
`-import.js` were replaced by `krtCsrf` / `krtFetch`. Every twin relays a backend failure as
`problem+json` (the shared `propagateBackendError` helper) so an `OPTIMISTIC_LOCK` drives the
sanctioned reload-confirm; a missing required field on a create twin is a `422` `VALIDATION`
`problem+json` rather than the 500 the frontend `@ControllerAdvice` would make of a `@Valid`
`@RequestBody` bind failure.

The bank area (#579) converts the last AJAX-then-`location.reload()` writes — money operations and
the account / holder / grant lifecycle — to in-place fragment swaps without touching the already
complete `BankProxyController`. The generic `bank.js` form dispatcher keeps its bespoke inline
field-error rendering (`.bank-field-error` slots + the `CODE_FIELD` overdraft / self-transfer /
holder-inactive mapping — bank 409s render at the field, never as a reload-confirm toast) but moves
its CSRF onto the shared `krtCsrf` with retry-on-403, and replaces the success reload with a server
re-render of the region named by the form's `data-refresh` attribute. The account-detail money writes
(deposit / withdraw / transfer / rebook / reverse) swap the whole `accountBody`
(`GET /bank/accounts/{id}?fragment=accountBody`) because the balance, the holder distribution and the
booking modals' distribution-derived holder selects are all backend aggregates a JS patch would
desync — and the money forms carry no `@Version` (the ledger is append-only), so an immediate second
booking cannot 409. The manage lifecycle writes swap `manageBody` (tab-nav + active panel together, so
the `.tab-count` aggregates and every trigger button's fresh `data-field-version` re-render
atomically, fixing the shared deactivate/reactivate-modal stale-version trap), and grant create /
revoke swap `grantsMatrix` honouring the active `view` / `accountId` / `userId` filter (#573). The one
genuinely isolated single-row write — a grant capability flag toggle — stays a precise dom-patch
(`button.on` + the row's `data-can-*` + `krtFetch.syncVersion` from the `BankGrantDto` response). If a
write succeeds but only its follow-up refresh GET bounces, the swap surfaces a dedicated "saved, but
reload" message rather than the generic "action failed" text, so a committed money booking is never
mistaken for a failure.

The **promotion** admin + management pages (#580) drop their last `AJAX-then-location.reload()`
writes for in-place fragment swaps. The two admin pages re-render their list region after every
mutation: **topics/categories** create / edit / delete and the up/down **reorder** swap
`promotion-admin-topics :: topicsResults` into `#pa-topics-results`, and the **rank-requirements**
create / edit / delete + group-delete swap `promotion-admin-rank-requirements :: ranksResults` into
`#ar-results`. A full server re-render is exactly what re-syncs every card's `@Version`, sort order
and first/last arrow state, so a second reorder can no longer 409 — and the reorder no longer relies
on a non-existent GET-by-id proxy route (it now reads the full DTO each PUT needs straight from the
card's edit-button data attributes). The **manage** matrix already saved grades in place through its
serialised save queue; #580 finishes it by recomputing each touched member's **eligibility chips**
once the queue drains, via a lightweight per-member `promotion-manage :: eligibilityCell` swap, and
by replacing the optimistic-lock **409 reload** with an in-place `promotion-manage :: matrixBody`
re-render that rebuilds every row with a fresh `@Version` (the client-side collapse / sort / filter
state is restored on `krt:swapped`). All three pages' bespoke `getCsrfToken` / `getCsrfHeader` +
`apiCall` helpers were retired onto `krtCsrf` (shared reader + retry-once-on-403); the client-side
rank filter and the manage CSV export are untouched.

The organisation / members / profile area (#581) converts the org-chart inline editor, the member
list and the profile + home-page writes. The org-chart position operations (add / reassign / rename /
vacate / remove) drop the `setTimeout(location.reload)` that followed each `send()` for a `chartBody`
fragment swap (`GET /org-chart?fragment=chartBody`): the chart is a flat, CSS-connected pre-order ARIA
tree whose add affordances and vacant/filled transitions are derived aggregate state, so a per-node
patch would desync the "+" buttons and the roving-tabindex order — the swap re-stamps every
`data-version` and the inline JS re-inits the tree keyboard navigation on `krt:swapped`; the bespoke
`csrfHeaders()` reader moves onto `krtCsrf` with retry-on-403. The chart's horizontal scroll is
captured before the focus-returning `closeModal()` and re-applied across animation frames until the
freshly-swapped tree's layout settles, so the offset survives the in-place refresh on every engine.
The member list converts the delete (a `@DeleteMapping` JSON twin whose success re-swaps the results
fragment so pagination and the Staffel + SK columns stay coherent); its filter `fragment` param
changes from `boolean` to `String` so it binds the `krtFetch.swap` helper's `fragment=results` value
(a `boolean` param silently 400'd that swap). The list-level per-flag Logistician / Mission-Manager
toggles were removed: per-Staffel flags (REQ-SEC-005) are now edited on the member-edit page, whose
two Staffel slots (REQ-ORG-017, up to two) each carry their own flags and save through the in-place
`{field: message}` membership-delta twin (REQ-FE-007).
The profile payout-preference form joins the description form on `krtFetch.write` (both echo the one
shared user-row version), and the home-page mark-announcement-read posts in place and removes its
control. The one reload deliberately kept is the sidebar active-OrgUnit switcher: switching the
org-unit re-scopes every list, count and entity on the page through `OwnerScopeService`, so the
existing controlled full navigation (`POST /me/active-org-unit` → `_referer` redirect) is the correct
UX — an "in-place" swap would amount to re-rendering the whole page anyway (REQ-ORG-\*).

The **admin CRUD** long tail (#582 — the last epic child) converts the remaining admin reference-data
pages. The list-level CRUD on **mission-data** (squadrons / job-types / frequency-types create / edit
/ delete / activate, plus the frequency-type drag-drop **reorder**) and **special-commands** (SK
create / edit / delete / activate; member add / remove / role-flag / lead-toggle on the detail page)
save through header-gated `X-Requested-With` twins and re-render the affected section fragment — the
same `?fragment=…` fragment the include-inactive filters already swap, plus a new
`special-command-detail :: membersResults` fragment for the member roster. A full server re-render is
exactly what re-syncs every row's `@Version`, the active / role / lead badges and the frequency
ordering, so a second action can no longer 409, and the reorder drops its `location.reload()`.
**announcement** (update / delete), **material-aliases** (create / update / delete), **material
categories** (create / delete) and **admin-settings** (the five-version save) patch their own
row / version inputs in place; settings validation failures and material-category conflicts come back
as `application/problem+json` so the client toasts the exact reason or offers the reload-confirm. The
**uex** three-state loading-dock / auto-load overrides and the terminal hidden toggle patch their
button group (and the terminal UEX-source chip) **deterministically from the clicked action** — these
overrides carry no `@Version`, so no fragment re-render is needed — and the **locations** visibility /
home-location toggles flip server-side off a fresh read and re-render the row's two buttons. **bank**
wipe-reset and **sync-reports** purge keep their type-to-confirm / confirm hurdles and report the
outcome as a toast; **p4k-import** (already AJAX) had its bespoke CSRF reader retired onto `krtCsrf`.
Every classic `POST`→redirect handler stays as the no-JS fallback.

**Enforced by:** lists/pagination e2e (#573) plus the mission-detail (#574), order-detail (#575),
refinery-import (#591), asset-management (#578), bank (#579), promotion (#580), org/members/profile
(#581) and admin-CRUD (#582) twin / fragment / endpoint MVC + e2e tests, plus
`OperationPageControllerMvcTest` (the error fragment is inert on the full page and still renders
for an unknown fragment name). **Issues:** the epic children
(#572) through (#591), most recently (#580), (#581) and (#582), the last child. **Code:**
`krt-fetch.js` (`swap`), `missions.js`, `operations.js`, `fragments/pagination.html`,
`mission-detail.html`,
`orders-index.html`, `orders-detail.html`, `refinery-orders-create.html`, `datetime-splitter.js`,
`hangar.html`, `ship-data.html`, `personal-inventory.html`, `personal-inventory-blueprints.html`,
`personal-inventory*.js`, `bank.js`, `bank-account-detail.html`, `bank-manage.html`,
`bank-grants.html`, `promotion-admin-topics.html`, `promotion-admin-rank-requirements.html`,
`promotion-manage.html`, `org-chart.html`, `members.html`, `member-edit.html`, `profile.html`,
`index.html`, and the #582 admin pages — `announcement.html`, `sync-reports.html`, `locations.html`,
`materials.html`, `material-aliases.html`, `admin-settings.html`, `uex.html`,
`fragments/admin-uex.html`, `mission-data.html`, `special-commands.html`,
`special-command-detail.html`, `p4k-import.js` — over `JobOrderPageController`,
`RefineryOrderPageController`, `HangarPageController`, `ShipDataPageController`,
`PersonalInventoryPageController`, `PersonalInventoryBlueprintsPageController`, `BankPageController`,
`BankManagePageController`, `BankGrantsPageController`, `PromotionPageController`,
`OrgChartPageController`, `MemberManagementController`, `ProfileController`, `HomeController`,
`AdminAnnouncementPageController`, `AdminSyncReportsPageController`, `AdminBankPageController`,
`AdminLocationsPageController`, `AdminMaterialsPageController`, `AdminMaterialAliasesPageController`,
`AdminSettingsPageController`, `AdminUexPageController`, `AdminMissionDataPageController` and
`AdminSpecialCommandsPageController`.

### REQ-FE-006 — Navigate-after-AJAX for create / finalize flows that legitimately land elsewhere

Some write flows finish by landing the user on a **different** page — creating an entity navigates
to its detail page or the list, and cancelling a refinery order returns to the refinery-order list
(a canceled order drops out of that list's default OPEN+IN_PROGRESS working set, so there is nothing
useful left to stay on). Refinery **save** and **store** used to navigate too; since #1238 they are
ordinary REQ-FE-001 in-place section swaps on the `refinery-order:{id}` seams, so a store now leaves
the user on the completed order rather than bouncing them to the list. For the flows that do still
navigate, the no-reload guarantee of REQ-FE-001 applies to the
**failure path**: a client-side validation error or a backend save error keeps the user on the page
with their entered data and shows an inline KRT toast, instead of the classic full reload that
discards a half-filled form. On success the handler deliberately navigates to the server-returned
`{"targetUrl": …}` JSON — the navigation **is** the user's intended outcome, so this is a refinement
of REQ-FE-001, not a violation (there is no in-place reload that would lose work).

The AJAX twin is routed by an `X-Requested-With=XMLHttpRequest` header (more specific than the classic
`@PostMapping`, so Spring dispatches header-bearing requests to it) and submits a `FormData` of the
real `<form>`, which lets the browser serialize the page's dynamic editors (order item lines, refinery
goods / store items) and omit the disabled inactive-mode controls without hand-rolled JSON. The
classic `POST→redirect` handler stays untouched as the no-JS fallback, and the twin reuses the same
DTO-building / backend call as the classic path. Optimistic-lock and other backend errors are
re-emitted as `application/problem+json` (the `propagateBackendError` helper) so the page-local
submit helper can surface them inline.

**Acceptance**

- [ ] A client-side or backend validation/save error on a create / refinery-finalize submit keeps the
  document on the same URL with the entered data intact and shows an inline toast — no full reload.
- [ ] On success a genuinely-navigating flow (create, refinery cancel) navigates exactly once to the
  server-supplied `targetUrl`; a refinery save / store instead re-renders its sections in place.
- [ ] With JavaScript disabled the classic form still `POST→redirect`s (the twin is header-gated).

**Enforced by:** create/refinery navigate-after-AJAX MVC tests (`X-Requested-With` twins return
`{targetUrl}` / `400`) · **Issues:** #575 · **Code:** `orders-create.html`, `refinery-orders-create.html`,
`refinery-orders-details.html`, `JobOrderPageController`, `RefineryOrderPageController` (`*Ajax`
twins, `propagateBackendError`).

### REQ-FE-007 — In-place form save with a `{field: message}` validation contract

A form whose server-side validation must stay inline (the mission core-edit `#mission-form`, #589)
saves through a header-gated AJAX twin (`X-Requested-With`) that returns one of three shapes, so the
page never reloads on a save:

- **success** → `200` JSON of the fresh optimistic-lock versions the form must echo back into its
  hidden inputs. The mission twin re-reads the mission after its three section PATCHes to capture the
  server-side `PLANNED→ACTIVE` auto-bump of the schedule version, then returns `{version, coreVersion,
  scheduleVersion, flagsVersion}`; the client writes all four back so a second consecutive save does
  not 409 (`syncVersion` is single-version, so this needs a bespoke handler).
- **validation failure** → `422` with a flat `{field: message}` JSON map whose keys are the bound
  field names and whose values are the messages resolved **exactly as `th:errors`**
  (`messageSource.getMessage(fieldError, locale)`); the client renders them into the matching
  always-present `.field-error[data-error-for="<field>"]` slots (an empty slot is hidden via
  `.field-error:empty`, and the GET render keeps them empty with a `th:text` ternary that never
  evaluates `th:errors` unbound). An unmapped key falls back to a toast so no message is dropped.
- **conflict / backend error** → `problem+json` via `propagateBackendError`, so an `OPTIMISTIC_LOCK`
  code drives the sanctioned reload-confirm and any other code a toast.

The classic `POST→redirect` handler (sharing the patch logic with the twin via a private
`applyMissionUpdate` helper) stays the no-JavaScript fallback, and its inline `th:errors` rendering is
the single source of truth the AJAX message text matches.

`applyMissionUpdate` must **round-trip the schedule datetimes losslessly**: a time field that is
rendered into its hidden input but never re-edited submits the value `formatInstant` produced (a
zoneless local datetime that may carry sub-second precision), and `parseToInstant` must parse it back
to the same instant rather than failing and nulling it. A broken round-trip silently clears
`meetingTime`/`plannedStartTime`/`plannedEndTime` on every save — and because `plannedStartTime` is a
`required` form field, the next page load can no longer submit at all.

**Acceptance**

- [ ] Editing core data saves in place (no navigation) and a second consecutive save does not 409.
- [ ] A `@Valid` failure renders the field message inline with no navigation; fixing + re-saving
  clears it.
- [ ] Saving core data preserves the schedule times the user did not re-edit (no silent nulling).
- [ ] With JavaScript disabled the classic form still `POST→redirect`s (the twin is header-gated).

**Enforced by:** `MissionCoreEditAjaxControllerTest` (four-version re-read, microsecond zoneless
schedule-time round-trip, 422 field map, 409 problem+json, fallback routing) +
`MissionCoreEditInPlaceE2eTest` (in-place save, double-save no-409, inline validation). **Code:**
`mission-detail.html`, `MissionPageController` (`updateMissionAjax`, `applyMissionUpdate`,
`parseToInstant`/`formatInstant`). **Issues:** #589.

### REQ-FE-008 — A bfcache history-restore renders fresh server state

A document restored from the browser's **back/forward cache (bfcache)** must reflect current server
state, not the stale in-memory snapshot the browser replays. A bfcache restore reinstates the DOM and
JS heap captured when the user navigated away — it does **not** re-run the GET — so any
server-rendered aggregate on an overview page (a bank account-card balance, a list count, a status
pill) shows its pre-edit value after the user edits the entity on a forward page and navigates back.
The in-place mutation foundation (REQ-FE-001…007) only keeps the **active** document fresh; it cannot
reach a sibling document the browser later replays. This is the gap behind the reported bank symptom:
deposit on the account-detail page (in-place, correct) → browser back → dashboard card still shows the
old balance until a manual reload.

A single global `pageshow` listener (in
[`common-handlers.js`](../../frontend/src/main/resources/static/js/common-handlers.js), loaded on
every page via `fragments/head.html`) calls `window.location.reload()` exactly when
`event.persisted` is true — the precise signal of a bfcache restore. It cannot loop: a fresh load
fires `pageshow` with `persisted === false`. This is the second sanctioned reload of REQ-FE-001 and is
deliberate (ADR-0013): a full reload, not a fragment swap, because the restored document is an
arbitrary overview with no single swap target, and Spring Security's `no-store` headers do not
reliably suppress bfcache across Chromium / Firefox / WebKit.

**Acceptance**

- [ ] A page restored from bfcache (browser back/forward into a cached document) re-runs its GET and
  renders current server state — a value edited elsewhere in the meantime is reflected without a
  manual reload.
- [ ] The reload fires only on a genuine bfcache restore (`event.persisted`), never on a normal load,
  so it does not loop and adds no navigation to ordinary page views.

**Enforced by:** `BfcacheRefreshE2eTest` (a synthetic `pageshow{persisted:true}` drives a reload that
discards a live-document marker) · **Code:** `common-handlers.js` · **ADR:** ADR-0013

### REQ-FE-009 — Multipart part-count headroom for `FormData` submits

Every in-place AJAX write submits its form as `multipart/form-data` via `FormData` (REQ-FE-002), so
**each form field is its own multipart part** — not only file uploads. Tomcat 11.0.8 lowered the
connector's `maxPartCount` default from 1000 to 10 as a DoS hardening, far below the field count of
the app's larger editors: a refinery order carries ~13 order-level fields plus ~5 per goods row, and
a job order grows with its line items, so a realistic save exceeds 10 parts and fails during
multipart parsing. It surfaces as `MaxUploadSizeExceededException` caused by Tomcat's
`FileCountLimitExceededException` — whose `"attachment"` text is a hardcoded Tomcat constant
(`FileUploadBase.ATTACHMENT`), **not** a form field name, so it must not be read as evidence of an
attachment upload.

The frontend therefore sets `server.tomcat.max-part-count: 1000` — the pre-11.0.8 default the
`FormData` writes were built against, generous for the largest legitimate editor (a refinery order
with ~30 goods is ~165 parts) yet still bounded against a flood; the
`spring.servlet.multipart.max-request-size` cap stays the real volume guard. A part-count or size
breach that still occurs must degrade to a clean, localized **413** — a JSON `{code:
UPLOAD_TOO_LARGE}` body for XHR callers and the `error/error` page otherwise — never the generic
500. Because Spring raises the exception during `DispatcherServlet` multipart resolution (before
handler selection), only the global `GlobalExceptionHandler` `@ControllerAdvice` can intercept it; a
controller-local `@ExceptionHandler` is bypassed.

**Acceptance**

- [ ] A refinery order (or any large `FormData` editor) with more than 10 form fields saves
  successfully — it is not rejected by the connector's part-count limit.
- [ ] A multipart submission that exceeds the configured part-count or size limit returns a
  localized 413 (JSON `UPLOAD_TOO_LARGE` for XHR, the error page otherwise), not a 500.

**Enforced by:** `GlobalExceptionHandlerTest` (the JSON + HTML branches of
`handleMaxUploadSizeExceeded`) · **Config:** `frontend application.yml`
`server.tomcat.max-part-count` · **Code:** `GlobalExceptionHandler.handleMaxUploadSizeExceeded`

### REQ-FE-010 — Live multi-user mission updates over the presence WebSocket

When several users have the same mission detail page open, a change one of them makes (a participant
joining, a crew move, a finance entry, a manager/owner change, a core/schedule/status/party-lead
edit, an Ablauf-step, Ziele-objective or frequency/custom-frequency edit) must appear on the
**others'** views without a manual reload. REQ-FE-001…007 keep the
**acting** user's own document fresh; they cannot reach a second user's already-rendered page. The gap
is the in-place sibling of the bfcache gap (REQ-FE-008): the other viewer's DOM is stale until they
reload.

> **Amendment (#1102 / ADR-0094):** the mission detail page is the **first instance of the
> tool-wide live-sync standard REQ-FE-015**. The transport described below now rides the
> `mission:{id}` topic room on the shared multiplexed socket (`/ws/sync`, `krt-live-sync.js` + the
> `mission-presence.js` adapter); the one-release legacy `/ws/missions/{id}/presence` alias was
> **removed in #1236** (`mission-presence.js` now subscribes on `/ws/sync` like every other surface).
> Everything else in this requirement — the opaque-keys rule, the three mirror points, the
> pill/coalesce contract, the abuse bounds — is unchanged and is what REQ-FE-015 generalizes.

The transport is the **`mission:{id}` topic room of the shared live-sync WebSocket** (REQ-FE-015;
`/ws/sync`, `krt-live-sync.js` + the `mission-presence.js` adapter) — no new backend module, no
Flyway migration. The acting client's `krtRefreshMissionSection(keys)` chokepoint, which already
runs after every successful mutation, additionally calls `missionPresence.sendChanged(keys)`; the
relay forwards a `{type:"changed","topic":"mission:…","sections":[…]}` frame to every **other**
socket subscribed to that mission (the originator is excluded — it already applied its own change).
Each peer turns the frame into a `krtRefreshMissionSection(keys, {broadcast: false})` —
`broadcast:false` stops the applied change from echoing back into a loop.

**Only opaque section keys travel over the socket — never mission data.** Every peer re-pulls the
affected fragment through its own authenticated, authorization-checked
`GET /missions/{id}?fragment=…`, so guest field-redaction and the member-only finance gate still
apply per viewer; a guest never receives privileged data via the push. The relay sanitises the
inbound `sections` array (keys outside the whitelist
{`crew`,`finance`,`mgmt`,`overview`,`steps`,`objectives`,`frequencies`,`organisation`} dropped,
count capped) so
a client can neither target an arbitrary fetch nor amplify one frame into an unbounded fan-out. The
whitelist, the acting client's broadcast and the peers' receiver all mirror the single
`MISSION_SECTIONS` seam map in `mission-detail.js` — a section key present in the seam map but
missing from the relay whitelist or the receiver leaves the peers' section stale until a manual
reload. **Binding:** these three mirror points must be kept in sync in the **same change** whenever a
live-synced section is **added, changed or removed** — the receiver derives its map from the seam
map so those two cannot diverge, and the server whitelist (which cannot share the client map) must be
edited alongside. A live-synced mutation added on one side but not propagated across all three is
incomplete; this is the REQ-FE-010 defect that shipped when `objectives`/`frequencies` were added to
the write seam but not the receiver or the whitelist. The
`overview` fragment (Tab-1 + a `#overview-head-meta` carrier that patches the sticky header title /
status pill / facts) is added by this requirement so core/schedule/status edits propagate too.

Three server-side guards bound the abuse surface the socket adds. Joining the mission room is
**authorized against mission access** — the topic authorizer issues the same authenticated
`GET /api/v1/missions/{id}` the page does (per REQ-FE-015 this happens at `subscribe` time on the
shared socket), so an authenticated user cannot
join the presence room of a mission they may not see (an explicit backend 403/404 refuses; a transient
backend error fails open so a blip never kills presence). Inbound `changed` frames are **rate-limited
per session** (a token bucket sized far above any human edit cadence), so a crafted client cannot
drive sustained re-fetch amplification even within a mission it can see. The **presence control
frames** (`focus` / `heartbeat` / `blur`) carry the same bounds (#1245, ported onto the generalized
handler): an over-length `sectionKey` is dropped, the frames share an equivalent per-session token
bucket, and `LiveSyncPresenceService` caps the number of distinct sections tracked per topic — so a
single authenticated socket cannot grow the per-topic presence map without limit, nor force the O(N²)
full-map snapshot rebuild-and-broadcast blow-up that looping `focus` frames with unique section keys
would otherwise drive.

An incoming refresh must **never yank a section out from under an active edit**: while a modal is open
(or focus sits inside the target section's container) the refresh is deferred behind a DS-styled
"Aktualisierungen verfügbar" pill (no native dialog) that applies the held-back sections on click.
Bursts are coalesced (a debounce window jittered like the reconnect backoff — `COALESCE_MS +
random()*COALESCE_MS` — so peers that all received the same `changed` frame within microseconds do
not fire their fragment refetches in one synchronized spike, #1125), and a dropped-then-reconnected
socket triggers a one-shot resync of every visible section to recover signals missed while offline.

**Multi-instance via Redis pub/sub (ADR-0094, ADR-0126).** The `changed` relay fans out across
frontend replicas through the shared Redis channel described in REQ-FE-015 (local-relay first, so a
Redis outage degrades to single-instance behaviour, never worse). Since #1237 the **presence dots
fan out too**, on a second channel and as full per-origin snapshots rather than deltas: a peer's
dots appear immediately on a focus/blur and within one 10 s gossip tick otherwise, and a replica
that goes silent stops contributing dots after 30 s. Losing Redis drops presence back to exactly
the earlier per-instance behaviour — never worse — because the local broadcast still happens first.

**Backpressure & registry consistency (#1109 Wave 6).** Every socket is wrapped in a
`ConcurrentWebSocketSessionDecorator` at registration (send-time + buffer-size bounded, TERMINATE on
overflow), so one slow/dead consumer — a suspended laptop whose TCP window is exhausted — is dropped
rather than blocking the serial broadcast loop on the caller's Tomcat thread (or the single shared
reaper) for up to Tomcat's ~20 s send timeout, which previously stalled every mission's presence /
relay org-wide (#1149). The per-mission session set is mutated atomically under the map entry's bin
lock (`compute` / `computeIfPresent`), so a viewer connecting at the same instant the last viewer
disconnects can no longer be stranded in an orphaned set — silently receiving no further peer edits
with the socket still open (no reconnect), the REQ-FE-010 staleness this rule forbids (#1150). The
notification SSE registry carries the same two fixes (#1157 / #1156, see REQ-NOTIF-010).

**Acceptance**

- [ ] With the same mission open in two sessions, a mutation by user A (participant add, crew move,
  finance entry, manager/owner change, core/schedule/status/party-lead edit, Ablauf-step,
  Ziele-objective or frequency/custom-frequency edit) appears on user B's view within a short delay
  without a manual reload — including the Verwaltung steps/objectives/frequencies editors, not only
  their Übersicht mirrors.
- [ ] No mission data crosses the socket — a guest viewer's auto-refresh still renders the
  guest-redacted fragment and the member-only finance section stays gated per viewer.
- [ ] An incoming change while user B has a modal open (or is editing the affected section) does not
  destroy their in-progress edit; it is deferred behind the "updates available" pill.
- [ ] Applying a pushed change does not re-broadcast it (no echo loop), and the originating session
  does not refresh twice.
- [ ] An authenticated user cannot open the presence socket for a mission the backend forbids
  (handshake refused), and a flood of `changed` frames from one session is rate-limited.
- [ ] A flood of presence `focus`/`heartbeat` frames from one session — including frames carrying
  unique or over-length `sectionKey`s — is rate-limited and cannot grow the per-mission presence map
  beyond its distinct-section cap.

Coverage note: `MissionLiveSyncE2eTest` exercises the representative path end-to-end twice — a
participant add propagating to a second viewer in place (no reload), and a Ziele-objective add
reaching a passive viewer's backgrounded editor (pinning a section key beyond the original four
across broadcast → relay whitelist → receiver); the remaining mutation kinds in the first bullet all
route through the same `krtRefreshMissionSection` / `krtNotifyMissionChanged` chokepoint, so they
inherit the same behaviour, and the per-viewer guest-redaction guarantee rests on the existing
authenticated fragment GET (covered by the mission fragment/redaction tests) rather than a dedicated
live-sync case.

**Enforced by:** `LiveSyncWebSocketHandlerTest` (relay to peers, origin exclusion, key
sanitising/dedup, full seam-map whitelist relay for the mission topic, no-op on empty, per-session
`changed` rate limit, per-session presence-frame rate limit + over-length `sectionKey` dropped —

# 1245) · `LiveSyncPresenceServiceTest` (distinct-section-per-topic cap) ·

`LiveSyncSubscriptionAuthorizerTest` (mission: allowed on authorized read, refused on 403/404,
fail-open on transient, malformed topic rejected) · `LiveSyncSectionMapParityTest` (seam map ↔
registry whitelist set-equality) · `MissionLiveSyncE2eTest` (two-context live participant-add
propagation + no-reload assertion) · `InventorySharedLagerLiveSyncE2eTest` (two-context shared-Lager
allocation-chip propagation + no-reload assertion, #1307) · **Code:** `LiveSyncWebSocketHandler`
(`allowChangedFrame` /
`allowPresenceFrame` / `MAX_SECTION_KEY_LENGTH`) / `LiveSyncTopicClass` (mission row),
`LiveSyncPresenceService` (`MAX_SECTIONS_PER_TOPIC`),
`mission-presence.js` (adapter: `sendChanged` / `krt:mission-changed` / `krt:mission-resync`),
`krt-live-sync.js` (shared receiver factory), `mission-detail.js` (`krtRefreshMissionSection`
broadcast + receiver config — its container map derived from the `MISSION_SECTIONS` seam map — with
flush-time busy re-check + finance-badge `krt:swapped` listener), `mission-detail.html`
(`overviewSection` fragment), `MissionPageController` (`overview` fragment case) · **ADR:** ADR-0031,
ADR-0069, ADR-0094

### REQ-FE-011 — User-selection fields are searchable comboboxes (username + display name)

As the member base grows, plain `<select>` dropdowns of users become unusable. **Every field that
lets a user pick a registered user/member must be a searchable combobox** rendered by
[`krt-searchable-select.js`](../../frontend/src/main/resources/static/js/krt-searchable-select.js),
filtering on **both** the user's `username` **and** their display name. The control is opted in with
the `data-krt-combobox` marker; each `<option>` carries the secondary search term in `data-search`
(the `username`) so a label that shows only the display name still matches the login handle, and vice
versa. The enhancer is loaded **globally** from `fragments/head.html` and auto-initialises every
`select[data-krt-combobox]` on `DOMContentLoaded` **and** on `krt:swapped` (so pickers inside swapped
fragments are upgraded); `window.krtEnhanceComboboxes(root)` upgrades pickers a page builds
dynamically (cloned modal/selector rows). Shared default labels live once in `window.krtComboboxI18n`
(head.html): the top-level default keeps the **user wording** (`userSelect.search.*` — every picker
opting in with a bare `data-krt-combobox` is a locally-populated user/holder picker), plus a
**`kinds` map keyed by the remote-source marker value** that gives each source kind its own
placeholder and no-results wording (`userSelect.search.*` for `remote-users`/`remote-bank-users`,
`materialSelect.search.*` / `locationSelect.search.*` / the item and bank-account strings for the
catalog and account sources) — a material picker must never greet the user with the user-picker
text. Per-control precedence: `data-combobox-*` attribute > `kinds[marker]` > top-level default.
The map is **gate-enforced**: `ComboboxKindsParityTest` asserts set-equality between the markers
registered in the remote-source registry files and the `kinds` keys in head.html, so registering a
source without its wording (or orphaning a `kinds` entry) is a red build. A new or changed
user-selection surface that ships a plain `<select>` or a hand-rolled picker is **incomplete**.

**Server-side search mode for the all-users pickers (#1193, ADR-0085/ADR-0089).** At the 5000-account
target, a picker that preloads the full (or admin-"all-squadrons") roster ships thousands of
`<option>`s. Every such picker now opts into the component's `remoteSource` mode **declaratively by
the marker value**: `data-krt-combobox="remote-users"` searches the squadron/admin-scoped
`/users/search`, and `data-krt-combobox="remote-bank-users"` searches the bank-audience
`/users/search-bank` (ADR-0089 — same query/scope, role gate widened to bank staff; backs the
register-holder / grant-Bank-Employee / approval-limit **and the deposit/withdrawal counterparty**
pickers). The enhancer looks the marker up in
the shared `window.krtComboboxRemoteSources` registry (`krt-user-search.js`), so no per-page JS is
needed; the roster is fetched on demand (debounced, paginated), not preloaded. **Edit-mode seeding**
is preserved: a picker with a current value renders exactly one seeded `<option>` for it (its display
name sourced from the aggregate the page already loads, or a single `/users/{id}` lookup) so the box
shows the name, not a raw id — the no-JS `<select>` fallback still submits it. The **counterparty**
picker is add-only (no edit-mode seed) and keeps its intertwined enable/disable JS — the "kein
Tool-Account" external toggle and the dependent org-unit loader drive the combobox's hidden value
input, and the enable/disable/clear routes through the shared combobox-aware helper so the visible
textbox mirrors the state (bank.js). Pickers bounded to a
**small scoped** set (a single mission's `${participants}`) stay in local-filter mode; the carve-outs
below are unchanged.

The combobox preserves the original control's `name`, `id` and generic `data-*` (incl. `data-role` /
`data-trigger`) onto its hidden input, so existing `getElementById` lookups, form submission and
change-delegation keep working unchanged; code that sets a value **after** enhancement (edit modals)
uses the control's `setValue` API (`getElementById(id).krtCombobox.setValue(v)`) so the visible label
and the submitted value stay in sync.

**The label and the value are one unit — every path must move both.** The control is split across two
elements: the hidden input carries the submitted value, the visible textbox carries `required` (a
`type=hidden` input is barred from constraint validation, so the browser only ever validates the
textbox). Any path that writes one half and not the other produces a control that **looks filled and
submits nothing**, and `required` will not catch it. This is not theoretical: the two abandon paths
(blur without a pick, `Escape` while open) used to restore `input.value = committedLabel` and clear
the custom validity while leaving `hidden.value` — emptied by `reconcile()` on the first
non-matching keystroke, and `focus` does `input.select()` so one keystroke replaces the whole label —
cleared. Both guards were dropped at once, and a bank employee confirming an over-limit withdrawal
submitted an empty `holderId` next to a visibly filled-in holder (the over-limit gate disables submit
until the approval checkbox is ticked, and that forced extra click is the blur). The committed
selection is therefore tracked as a **label / value / item triple**, and both abandon paths go through
the single `restoreCommitted()` helper that restores all three plus the mirrored option metadata, and
re-fires `change` when the value moves — `reconcile()` already fired one when it cleared, so without
the symmetric event dependent loaders keep the cleared state. Guarded by
`ComboboxBlurRestoresValueE2eTest`, which asserts the cleared intermediate state as well so a
regression that stops clearing (re-opening the "submits unresolved free text" hole) cannot pass.

**Carve-outs.** (1) Fields that must also accept a free-text **guest** name — the mission
participant-add and party-lead pickers — keep using the `/users/search`-backed autocomplete, which
already live-searches both username and display name and must keep accepting non-user names; the
strict combobox (which forces a pick from the list) does not fit them. (2) **Holder** pickers (bank
deposit / withdrawal / transfer / booking-confirm) select a bank holder by handle — holders may be
non-users and carry only a handle — so they are searchable comboboxes that filter the **handle** (no
separate username/display-name term). Deviation beyond these carve-outs needs prior approval by
@greluc and a spec amendment first.

**Optional pickers stay clearable — via two paths.** A picker whose backing `<select>` is **not**
`required` and has an empty-value option is optional: a committed value must be resettable back to
none — matching the native `<select>`, where the empty option is re-selectable. **Both** paths back
to empty are required:

- **Delete-to-clear** (always available for an optional picker): emptying the textbox clears the
  hidden value **and** drops the committed label, so blur no longer snaps the box back to the
  just-removed entry. This must work regardless of whether the empty option carries descriptive text
  — it is the path users take when they miss the dropdown row.
- **The "clear" row** (when the empty option carries descriptive text, e.g. the mission unit's
  responsible person "— automatisch: Schiffseigner —"): that text seeds a selectable row at the top
  of the unfiltered list (hidden while a query is typed; value `''`, so committing it clears the
  hidden value, the visible label and any mirrored option metadata) — the discoverable path.

A required picker keeps the blur snap-back so a stray keystroke never loses a mandatory selection,
and renders no clear row. Swallowing the empty option entirely (no clear row **and** blur restoring
the old value on delete) is the "can't remove the responsible person" defect and is incomplete.

**Acceptance**

- [ ] Every field that selects a registered user is a `krt-searchable-select` combobox (carries
  `data-krt-combobox`); typing a **username** finds a user whose display name differs, and typing a
  display name finds them too.
- [ ] The enhancer runs on initial load **and** after `krt:swapped`, so a picker inside a swapped
  fragment or a dynamically-cloned row (mission unit/finance modals, refinery store split rows,
  notification-rule selector rows) is searchable and pre-selects its value correctly.
- [ ] A converted picker submits the same value as the former `<select>` (the hidden input inherits
  `name`); code that pre-selects a value after enhancement shows the matching label, not a blank box.
- [ ] Guest-capable mission fields still accept a free-text non-user name; holder pickers filter by
  handle.
- [ ] An optional picker (non-required `<select>` with an empty option) can be cleared back to none
  by **both** paths: emptying the textbox (delete-to-clear — the box stays empty, not snapping back to
  the removed entry on blur) and, when the empty option has descriptive text, picking its "clear" row.

**Enforced by:** `AdminPersonalBlueprintsPageControllerMvcTest`
(`view_userPicker_isRemoteSearchCombobox_withoutRosterPreload` — the rendered picker carries the
`data-krt-combobox="remote-users"` marker and ships **no** preloaded option roster — and
`view_userPicker_seedsSelectedMemberInEditMode` — a selected member is seeded by name) ·
`UserAccessControlTest` (`/users/search-bank` allows bank staff, `/users/search` still 403s them) ·
`UserProxyControllerTest` (the `search-bank` + single-user `/users/{id}` proxies) · the
converted-picker flows drive the combobox end-to-end (open → pick → submit) in `BankBookingE2eTest`,
`BankOrgUnitRequestsE2eTest`, `MissionFinanceEntryE2eTest` and `RefineryOrderCreateE2eTest` (via
`E2eSupport.selectComboboxByValue` / `selectComboboxFirstOption`); `MissionUnitResponsibleClearE2eTest`
covers clearing an optional picker back to none via BOTH paths — the clear row
(`E2eSupport.clearCombobox`) and delete-to-clear (emptying the textbox + blur) · **Code:**
`krt-searchable-select.js` (`makeItem` + `data-search` local filter, the marker→`remoteSource`
registry lookup in `autoConfig`, the `optional`/`clearLabel` empty-option detection + selectable clear
row in `renderOptions`, the empty-value clear semantics in `commit` + the delete-to-clear label reset
in `reconcile`, global `enhanceWithin` on
`DOMContentLoaded` + `krt:swapped`, `id`/`data-*` passthrough, `setValue` API,
`window.krtEnhanceComboboxes`), `krt-user-search.js`
(the `remote-users` / `remote-bank-users` `window.krtComboboxRemoteSources` entries),
`fragments/head.html` (global load + `window.krtComboboxI18n`), `UserController.searchUsersForBank` /
`UserProxyController`, and the converted templates/selects · **ADR:** ADR-0053, ADR-0089

### REQ-FE-012 — A user's own back-to-back writes to one lock scope never self-collide

A version-carrying write that a user can re-fire — before its response returns — against the **same**
optimistic-lock scope (typing a Ziel title then immediately clicking "+" / the Klassifizierung
dropdown / a ▲▼ reorder; toggling two capability flags on one bank-grant row; changing both
association selects of one inventory row; a status change then a variant-counting toggle on one order)
must not 409 the user against **themselves**. The old failure mode: the second interaction's
`change`/`click` handler read the section version from the DOM and shipped it **concurrently** with
the first, still-in-flight write; the first bumped the version server-side, the second arrived stale
and 409'd, and "Aktuelle Werte laden" then reloaded and discarded the just-typed row. This is a
first-party race — distinct from the genuine two-user conflict that REQ-FE-003 / the OPTIMISTIC_LOCK
reload-confirm exist to handle.

`krtFetch` closes it with **per-scope write serialization plus a lazily-resolved version**:

- **`opts.serialize` (a lock-scope key).** Writes that share a key run **strictly one at a time in
  submission order**; the primitive is also exposed as `krtFetch.serialize(key, task)` for raw-`fetch`
  call sites. Distinct keys keep running concurrently, so a Ziele edit still never blocks a concurrent
  Ablauf / core / schedule edit — the REQ-ORG-018 fine-grained-lock invariant is preserved.
- **Lazy `url` / `payload`.** `opts.url` and `opts.payload` may be a value **or** a `() =>` thunk that
  `write` / `submitForm` evaluate at **send** time — after the queue lets the write proceed. A
  version-carrying inline write therefore reads its version from the DOM **when it is actually sent**,
  so a queued write picks up the version the write before it bumped, not the value captured when the
  handler first fired.
- **Awaited `onSuccess`.** `send` awaits a thenable `onSuccess`, so a serialized chain waits for the
  caller's fragment refresh (which rewrites the `data-*-version` holder the next write re-reads)
  before the next queued write starts.

The `sectionWrite` seam defaults `serialize` to the section key, so **every** section write (all of
mission-detail's Ziele / Ablauf / frequency / crew / … writes) is auto-serialized by section without a
per-call-site opt-in. A whole-payload lazy read is required where the payload carries sibling state
the prior write may have changed (the bank-grant toggle re-reads all three flags at send time, else a
serialized second toggle would silently revert the first).

**Acceptance**

- [ ] Typing a Ziel/Ablauf entry and immediately clicking add / a classification dropdown / a reorder
  saves both in order with **no** 409 conflict prompt, and the just-typed entry is never lost.
- [ ] The same holds for the other version-carrying inline editors swept in — the bank-grant flag
  matrix, the inventory job/mission association selects and the note edit, the order status +
  variant-counting toggles, the org-structure parent select, and the mission owning-org-unit
  reassignment (its `krtMissionWrite` payload reads `owningOrgUnitVersion` lazily and its `onSuccess`
  writes the bumped version back to `#owning-org-unit-row` before the serialized chain releases the
  next write, ADR-0078).
- [ ] Two **different** users editing the same entity still get the genuine OPTIMISTIC_LOCK
  reload-confirm (REQ-FE-003) — serialization only orders one client's own writes.
- [ ] Editing two **disjoint** sections/scopes concurrently does not serialize them against each
  other.

**Residual call sites swept in (2026-07).** A round-2 audit found four writers still outside the
contract: (a) the **actual-time** ("Jetzt") stamp and the **party-lead** set/clear baked their
section version into a **static** payload read at handler-fire, so a queued second write shipped the
pre-bump version — both now read the version in a **send-time thunk** (#1143); (b) the mission
**payout-preference** select-change ran with **no** `serialize` key, so two rapid toggles could commit
in reverse order and an older response could win the version fan-out — now serialized on
`section:participant` with a monotonic fan-out guard (#1145); (c) the **operation core-edit** save
snapshotted its version eagerly with no serialize key — now `serialize:'operation:core'` + a thunk
payload (#1117); (d) the mission **core-edit modal** hand-rolled a raw `fetch` (outside `krtFetch`,
unserialized against the actual-time writer that shares `scheduleVersion`) — now routed through
`krtFetch.submitForm` with `serialize:'section:schedule'`, so the FormData (and its version inputs) is
rebuilt at send time and the two never collide (#1118, which also drops the bespoke CSRF/403 loop per
REQ-FE-002).

- [ ] The actual-time "Jetzt" stamps (Beginn then Ende), the party-lead set-then-clear, a rapid
  payout-preference re-toggle, a double-submit of the operation core-edit, and a mission core-edit
  saved while a "Jetzt" stamp is in flight each save without a self-inflicted 409.

**Enforced by:** code review + the per-area double-action e2e assertions (REQ-FE-003) extended to the
type-then-add race · **Code:** `krt-fetch.js` (`runSerialized`, lazy `url`/`payload` in `write` /
`submitForm`, awaited `onSuccess`, the `sectionWrite` `serialize` default, exposed `krtFetch.serialize`),
`mission-detail.js` (`objectivesVersion` / `stepsVersion` lazy readers, actual-time / party-lead
thunks, payout-preference `section:participant`, core-edit `submitForm` on `section:schedule`),
`operation-detail.js` (`operation:core` serialize + thunk), `bank.js`, `inventory-my.js`,
`inventory-admin.js`, `inventory-note-modal.js`, `orders-detail.js` (`krtOrderWrite` serialize default
+ `_orderVersion`), `admin-org-structure.js`, `leitung.js` · **ADR:** ADR-0071 · **Issues:** #1143,

# 1145, #1117, #1118

### REQ-FE-013 — `krtFetch.swap` lands responses in issue order, not completion order

Several independent triggers overlap fragment swaps on **one** container: the local write's
`onSuccess` refresh, a peer's coalesced live-sync refresh (REQ-FE-010), the reconnect resync burst, a
debounced list filter, and a create/delete-driven reload. Under the load-induced latency variance that
caused the 2026-07 outage (150 ms vs multi-second), an **older** GET can resolve **last** and overwrite
a newer render with a staler DB snapshot — regressing the rows' `data-version` attributes (re-arming
the "stale version → 409 on next click" landmine of REQ-FE-003) and, with `history:true`, leaving the
address bar on whichever response landed last. `runSerialized` (REQ-FE-012) orders **writes**, never
these read-side swaps.

`swap` therefore carries a **per-container monotonic sequence guard**: each call claims the next
sequence number for its container (`container._krtSwapSeq`); when its response resolves it touches the
DOM (`innerHTML`), the `history.replaceState`, the `krt:swapped` dispatch and the loading indicator
**only if it is still the latest** swap for that container. A superseded response is dropped whole. The
previous in-flight request is `AbortController`-aborted so a slow older read stops wasting a backend
round-trip. Living in `krt-fetch.js`, the guard covers **every** consumer (missions, operations, and
any future stack) with no call-site change.

**Acceptance**

- [ ] Two swaps issued for one container with the **first** response delayed leave the container — and,
  for a `history:true` swap, the address bar — reflecting the **second** (last-issued) request.
- [ ] A superseded swap performs no `innerHTML` write, no `history.replaceState`, no `krt:swapped`
  dispatch, and does not hide the indicator out from under the newer in-flight swap.
- [ ] Disjoint containers are unaffected (the guard is per-container).

**Enforced by:** code review + e2e (a peer edit landing during a slow local refresh does not regress
the board) · **Code:** `krt-fetch.js` (`swap` sequence guard + `AbortController`) · **Issue:** #1151

### REQ-FE-014 — The mission edit modal saves only the sections the user changed

The mission edit form fans out to up to three section PATCHes (`schedule` → `core` → `flags`), each
carrying its own optimistic-lock counter. Sending **all three** on every save — regardless of which
fields changed — makes any concurrent schedule-section write by a peer 409 an unrelated edit: the
"Jetzt" actual-time stamp and a `PLANNED → ACTIVE` auto-transition both bump `scheduleVersion`, so a
name-only edit aborts at the (first-run) schedule PATCH before the core PATCH the user actually cares
about is attempted. It also silently re-writes untouched schedule/flags values — e.g. erasing an
auto-stamped `actualStartTime` on a later core-only save.

The save is therefore **dirty-section-aware**: the edit JS snapshots each header section's fields at
load and, at submit, sets the hidden `dirtyCore` / `dirtySchedule` / `dirtyFlags` inputs to whether the
section actually changed; `applyMissionUpdate` **skips the PATCH for any section flagged `false`**. A
`null` flag (the no-JavaScript classic fallback, or an older cached page) means "save this section", so
the classic path still saves everything. The `schedule → core` ordering is kept for saves that touch
both.

**Acceptance**

- [ ] A name-only edit issues **only** the core PATCH; a peer's concurrent `scheduleVersion` bump does
  not 409 it.
- [ ] The no-JavaScript classic `POST /missions/{id}` still saves every section (all flags default
  `true` / absent).
- [ ] A core-only save that triggers `PLANNED → ACTIVE` does not erase the server-auto-stamped
  `actualStartTime` (its schedule PATCH is skipped).

**Enforced by:** `MissionWriteControllerTest` (dirty-flag-gated PATCH fan-out) + e2e · **Code:**
`MissionForm` (`dirtyCore` / `dirtySchedule` / `dirtyFlags`), `MissionWriteController`
(`applyMissionUpdate`), `mission-detail.html` (hidden dirty inputs), `mission-detail.js` (section
snapshot + `markDirtySections`) · **Issue:** #1136

### REQ-FE-015 — Tool-wide live peer sync over topic rooms on one multiplexed WebSocket

REQ-FE-010's contract — a peer's change appears on every other viewer's screen without a manual
reload, with only opaque section keys on the wire — applies **tool-wide**, to every surface where
several users can see the same state. The transport is **one WebSocket per tab** (`/ws/sync`,
`krt-live-sync.js`) carrying `subscribe` / `changed` / presence frames that name a **topic**;
per-page sockets are forbidden for new surfaces (a second bespoke sync stack is the defect class
this requirement exists to prevent, #1102). Covered topics and their section whitelists:

|          Topic           |                                                           Sections                                                            | Presence dots |                                        Subscribe authorization                                        |
|--------------------------|-------------------------------------------------------------------------------------------------------------------------------|---------------|-------------------------------------------------------------------------------------------------------|
| `mission:{id}`           | crew, finance, mgmt, overview, steps, objectives, frequencies, organisation                                                   | yes           | `GET /api/v1/missions/{id}`                                                                           |
| `operation:{id}`         | overview, missions, payout, finance                                                                                           | no            | `GET /api/v1/operations/{id}`                                                                         |
| `order:{id}`             | header, materials, aggregated, items, item-stock, handovers, item-handovers, item-handover-lines, blueprint-owners, assignees | no            | `GET /api/v1/orders/{id}` (a requesting-owner is admitted; their re-fetches stay redacted)            |
| `orders` (global queue)  | queue                                                                                                                         | no            | capabilities `canViewJobOrders` (guests and requesters are refused)                                   |
| `refinery-order:{id}`    | order, store                                                                                                                  | no            | `GET /api/v1/refinery-orders/{id}`                                                                    |
| `bank:{accountId}`       | account, bookings, chart                                                                                                      | no            | staff account read, falling back to the org-unit account read; refused only when both explicitly deny |
| `bank` (staff-global)    | grid, requestQueue, manage, grants                                                                                            | no            | `ROLE_BANK_EMPLOYEE` (local check)                                                                    |
| `orgunit-bank` (global)  | orgUnitBank, orgUnitBankSettings                                                                                              | no            | member-or-above (the `/org-unit-bank` page gate, local check)                                         |
| `materialboard` (global) | board, requests                                                                                                               | no            | authenticated                                                                                         |
| `inventory` (global)     | stock                                                                                                                         | no            | authenticated (every viewer re-fetches its own owner/org-unit-scoped view)                            |
| `missions` (global list) | list                                                                                                                          | no            | authenticated (the `/missions` list gate; each viewer re-pulls its own scoped, guest-redacted page)   |
| `refinery` (global)      | queue                                                                                                                         | no            | authenticated (the `/refinery-orders` list gate; the `onlyMine` filter is applied per viewer)         |
| `members` (global)       | roster                                                                                                                        | no            | `ROLE_ADMIN` (local check — the `/members` class-level page gate)                                     |
| `org-structure` (global) | units, forms, chart                                                                                                           | no            | authenticated (the Organigramm is member-visible; the admin sections stay protected per fragment)     |

The `inventory` room is the squadron Lager (#1307/#1309): a single opaque `stock` section stands for
"the inventory changed". **All** inventory views subscribe and re-pull their own fragment on a peer's
write — the shared `/inventory/all` and personal `/inventory/my` grouped tables in **both their
Material and Items views** (REQ-INV-030: `filterInventory` / `filterMyInventory` rebuild the fragment
URL from the page's own filter state *including the `view=` parameter*, so a peer's change re-renders
whichever view is active; the lazily-loaded stack entries ride along, so a collapsed stack — material
or game-item — re-fetches its chips on the next expand), the aggregated `/inventory` overview (both
catalogs) and the per-catalog `/inventory/material/{id}` and `/inventory/game-item/{gameItemId}`
drilldowns (each `krtFetch.swap` its `?fragment=results` container). No new section keys were added
for the item views — the single `stock` seam covers both catalogs, so the three mirror points stay
untouched. Every inventory write (allocation add/change/remove, book-out, transfer, personal-rebook,
bulk-checkout, delete-all, note) broadcasts it, from whichever page made it. Because it is a global
room but each viewer's fragment is owner- and org-unit-scoped, a cross-scope peer refresh (another
squadron, or a personal-only change seen by a shared view) is a harmless no-op. The same inventory
writes also cross-publish to the `order:{id}` room — `materials`/`aggregated` (the order material
collection tracks the earmark roll-up) plus `item-stock` (the order-detail Item-Bestand panel,
REQ-ORDERS-028, refreshed when a Lager-side write (un)earmarks an item row; a page that does not
render a section's container skips it silently) — and to the `materialboard` room (a stock-reducing
write clamps an offer server-side), so those surfaces reflect inventory changes live too. The
affected order ids are read off the entry's leaf
chips before the write; the sole exception is the admin `DELETE /inventory/all` full wipe, which
cannot enumerate them client-side — its board and inventory rooms are still poked, but an open order
collection self-heals on the next interaction (an accepted limitation for that rare nuke). A further
cross-publisher is the production-booking modal on the order detail page: since Herstellung books
the produced item stock in (REQ-INV-032, the book-in section), its success handler additionally
pokes `inventory`/`stock` — the existing seam, so Lager viewers see the fresh stock live with no
seam-map change — and refreshes + broadcasts the order room's own `item-stock` section, because the
book-in auto-earmark changes the Item-Bestand panel (REQ-ORDERS-028); the panel's delivered toggle
likewise broadcasts `item-stock` on success.

The four **Phase-3 rooms** (#1235) close ADR-0094's tracked coverage gap; all four are global rooms
whose receivers re-fetch the viewer's *own* filter and page:

- **`missions`** — the `/missions` list. One opaque `list` key: a mission create, core edit
  (name / status / planned start) or delete re-renders every open list. Participants, units, crew and
  the party lead do **not** publish here — they do not surface on the list and ride the per-mission
  `mission:{id}` room. Distinct class from `mission:{id}` despite the shared wire stem; the
  `order`/`orders` disambiguation rule applies unchanged.
- **`refinery`** — the `/refinery-orders` list. One opaque `queue` key covering create, edit, store
  ("Einlagern") and cancel. Storing additionally cross-publishes `inventory`/`stock`, because the
  refined output is written into the shared Lager and an open Lager would otherwise sit stale.
  The refinery *detail* page (`/refinery-orders/{id}`) was the gap this rollout left open — a
  classic navigate-away surface with no `?fragment=` seam, so it published but could not receive.
  **#1238 closed it**, by converting the page to the fragment-swap standard first and then giving it
  the scoped `refinery-order:{id}` room described below.
- **`members`** — the `/members` Mitgliederverwaltung roster, the surface #1235 calls *Rollen*. One
  opaque `roster` key covering a member edit (rank, display name, Staffel membership and its
  LOGISTICIAN / MISSION_MANAGER flags), a delete and the manual Keycloak sync. It is the only
  Phase-3 room with a role gate, matching the page's own ADMIN restriction.
- **`org-structure`** — shared by the admin Organisationsstruktur editor (`units` = the unit +
  parent-edge table, `forms` = its create forms, whose Bereich/OL pickers go stale when a peer adds
  one) and the member-visible Organigramm (`chart`). One room because both render the same
  hierarchy: an admin's parent-edge change refreshes a peer's chart, and an org-chart position edit
  refreshes the editor. Subscribe is authenticated-only *by design* — gating the room on ADMIN would
  cut members off from their own chart; the admin-only fragments stay protected per-fragment, and a
  receiver silently skips a key whose container its page does not have. Because the two pages each
  render only part of the whitelist, their seam maps are parity-checked as **subsets** whose
  **union** must equal the registry whitelist — the union check is what catches an orphaned key no
  page renders. Each page pokes the other's key through a named constant that the same test pins
  against the registry.

The **publish side differs per room** and follows one rule: publish from the client when the acting
page stays open, server-side when it does not. `org-structure` broadcasts from the client
(`admin-org-structure.js` / `org-chart.js` — both surfaces are XHR-only and stay put, so the origin
session is excluded and the actor does not re-fetch its own change). `missions`, `refinery` and
`members` publish **server-side** through `LiveSyncLocalBus`: every one of their mutations navigates
away (a redirect, or an AJAX `targetUrl` the page immediately follows) or happens on a different page
than the list it invalidates (`/members/{id}/edit`), so a client broadcast would race the socket
teardown — and the server call site covers the no-JS form-POST fallback with the same line. A publish
sits only on the success path: a refused backend write must not make peers re-fetch a change that
never happened.

Rolling `org-structure` onto the admin editor also retired that page's `window.location.reload()`
(REQ-FE-001): it now re-renders through `?fragment=units` / `?fragment=forms`. Both create forms and
every parent select live inside those swapped fragments, so their handlers moved to `document`
delegation — a direct `addEventListener` would be lost on the first swap, the silent breakage the
reload previously hid. The `forms` receiver carries an extra busy test: the generic guard only holds
back a *focused* container, but an admin can type a name, tab away and still have unsaved input, so
any non-empty create input defers the section behind the "updates available" pill instead.

The `refinery-order:{id}` room (#1238) is the Phase-3 follow-up that closes the refinery detail gap
above, and it only works because the page was converted to REQ-FE-001 first. Two sections: `order`
(the edit form, the goods editor and the status-gated action row) and `store` (the Einlagern
dialog's rows, which are **derived from the order's output goods** and therefore change whenever the
goods do — which is why the dialog's source data is a section of its own rather than part of the main
seam). Save and store re-render both in place and broadcast them; **cancel is a REQ-FE-006
navigate-after-AJAX** — a canceled order drops out of the list's default OPEN+IN_PROGRESS working
set, so the acting client broadcasts and then leaves, while peers still holding the detail page
refresh into the canceled state. The receiver adds a page-specific `busyTest` for the Einlagern
dialog: it is an older `.modal`, which the default busy test (`.krt-modal-overlay` only) does not
recognise, so without it a peer's save would yank a half-filled store form out from under the user.

The wire prefix is `refinery-order` and the `topic_class` metric label `refinery_order`, keeping both
distinct from the global `refinery` / `refinery_queue` queue room — the same separation
`order`/`orders` and `mission`/`missions` carry. (`LiveSyncTopic.parse` could disambiguate a shared
`refinery` prefix by its id segment, as `bank` does; distinct metric labels are the part that
actually matters, so that the two never read as one duplicate series on the ops dashboard.) The
queue-side effects of a detail-page write need no client code: every refinery mutation, including the
AJAX twins this page calls, already pokes `refinery`/`queue` — and `inventory`/`stock` on a store —
**server-side** from `RefineryOrderWriteController`. The one thing the server cannot know is which
job orders the store dialog's rows earmarked to, so that single cross-publish stays client-side:
`order:{id}` `materials`/`aggregated` per picked job order, read off the row pickers *before* the
submit (the dialog is re-rendered on success). It carries no seam map of its own, so
`LiveSyncSectionMapParityTest` pins its keys against the `order` room's whitelist directly.

The standalone order **material-collection** page (`/orders/{id}/material-collection`) joins the same
`order:{id}` room in its own right (#1309): its per-row delivered toggle and owner/location moves
broadcast `order:{id}` `materials`/`aggregated`, and it re-fetches its `?fragment=results` collection
table fragment in place on any peer change (its three row controls delegate on `document` so they
survive the swap; the owner combobox is re-enhanced on `krt:swapped`). It renders a **subset** of the
ORDER sections (reusing the existing `materials` key), so — unlike `orders-detail.js`, whose seam map
must match the full ORDER whitelist — its `MATERIAL_COLLECTION_SECTIONS` is parity-checked as a
**subset** of `LiveSyncTopicClass.ORDER` (a stray/typo key still fails the build).

The server-side **topic-class registry** (the `LiveSyncTopicClass` enum) is the single source of
truth for this table. The REQ-FE-010 **three-mirror-points rule applies per topic**: the acting page's seam map,
the registry whitelist and the receiving page's apply map must change together in the same PR, and
`LiveSyncSectionMapParityTest` enforces seam-map ↔ registry set-equality at build time, so a key
added on one side without the other **fails the build** instead of silently stranding peers stale.
Receivers derive their container maps from the same seam-map object the write side uses; a section
key whose container does not exist on the receiving page (guest redaction, requester view, MATERIAL
vs ITEM orders, staff vs org-unit bank pages) is silently skipped — that asymmetry is the
authorization model, not an error.

A topic's sections are not always broadcast from a single page. The `operation:{id}` room is the
canonical cross-surface case: `overview`/`payout` are broadcast from the operation detail page
itself, while `missions` (the embedded child-missions table) and `finance` (the roll-up) are
**cross-published from the mission detail page** — a child mission's core/finance edit maps mission
`overview → missions` / `finance → finance` onto its parent `operation:{id}` (publishing needs no
subscription), so an operation viewer refreshes those two sections in place without a reload (#1241).
The mission page reads its parent operation id from `window.missionOperationId`; a mission with no
operation forwards nothing.

**Authorization is asymmetric by design (ADR-0094).** *Subscribing* to a topic requires the same
authenticated read the page itself performs (table above), checked asynchronously off the WS
container thread; an explicit 403/404 denies, transient failures and authorizer saturation fail
open (safe: no data rides the socket, every fragment re-fetch re-authorizes per viewer).
*Publishing* a `changed` frame requires only an authenticated socket, a known topic, the topic
class's section whitelist and the per-session token bucket — **no subscription**: a requester
creating an order must be able to signal the staff queue it may not read, and an org-unit owner
approval must reach the bank-staff rooms. Mutations that can happen with no authenticated socket at
all publish **server-side** through the same local bus the relay uses — the job-order create was the
example, and since ADR-0149 it needs a login; the server-side publish stays because a socket is not
implied by a session (a form submit from a page whose socket dropped is the ordinary case). The worst a malicious authenticated client can achieve is bounded re-fetch
amplification: whitelisted keys only, bucket-capped frames, and every receiver clamps via its
coalesce window regardless of publish rate. The `K sockets × rate × viewers` amplification lever is
bounded on all three multipliers (F2 / #1243): a **per-user socket cap** (20 concurrent `/ws/sync`
sockets — one per tab, far above real multi-tab use) bounds `K`; the **per-session token bucket**
bounds one socket's rate; and a **per-topic token bucket** (200 burst / 100 accepted frames/s, keyed
by room, on top of the per-session one) bounds a room's *aggregate* accepted-frame rate no matter
how many sockets publish to it. All three are sized deliberately generously — never to clip a real
200-user room, only a crafted flood — since the real backend protection is each receiver's coalesce
window (which clamps the fragment-refetch herd independent of the relay rate), not the relay bound.
Server-side and cross-replica deliveries bypass the per-topic bucket (trusted / already accepted). A
refused socket is closed with an app close code the client backs off on; every bound degrades to a
bounded re-fetch rate, never data loss.

**Close codes are a contract, and the two in use mean opposite things.** `4029` (socket cap, mirrors
HTTP `429`) is *transient*: the client jumps straight to the 30 s backoff and keeps probing until
another tab frees a slot. `4003` (Terms-of-Use consent missing, mirrors HTTP `403` — REQ-SEC-028) is
*terminal*: the client stops reconnecting permanently and navigates to the consent page named in the
close reason, because no amount of reconnecting can produce consent. This is the one refusal that
cannot be delivered by refusing the handshake — a non-`101` answer arrives as a bare `1006` the
client must read as "connection dropped", so the gate lets the upgrade complete and the handler
closes the socket at connect instead. A close code the client does not recognise falls through to
the generic reconnect path, so both numbers are pinned against `krt-live-sync.js` by
`LiveSyncCloseCodeWireParityTest` — the same mirror-point discipline as the section maps and the
subscribe-deny `reason`, and for the same reason: drift here fails silently.

**Pill, coalescing and resync follow REQ-FE-010 unchanged**, with one sizing addition (5000
accounts / ≥200 concurrent, ADR-0094): detail-topic receivers keep the 400 ms jittered coalesce
window; **every global-room receiver (`orders`, `bank`, `orgunit-bank`, `materialboard`, `inventory`,
`missions`, `refinery`, `members`, `org-structure`) uses 1500 ms** so a change seen by
up to ~200 viewers spreads its fragment re-fetch herd instead of spiking. Peer-driven re-fetches
always preserve the **peer's own** query state (filters, paging, view toggles — the page-URL getter
is late-bound per viewer); only the acting client's own refresh may deliberately reset paging.

**Cross-replica correctness rides Redis pub/sub** (`basetool:livesync:changed`): the relay delivers
locally first, then publishes `{v, topic, sections, origin}`; instances skip their own origin on
consume. A Redis outage therefore degrades to single-instance behaviour — never worse — and
delivery stays best-effort (reconnect triggers a per-topic resync; REQ-FE-013's per-container
sequence guard orders the resulting swaps). The backend notification SSE fan-out follows the same
pattern on `basetool:notify:published` (REQ-NOTIF-006 polling stays the correctness guarantee).

**Editor-presence dots cross replicas on their own channel** (`basetool:livesync:presence`; #1237,
ADR-0126) — today that is the `mission:{id}` room, the only presence-enabled class. Presence is
*state*, not a signal, so it is mirrored differently from `changed`: each instance publishes its
**complete** snapshot for a topic — `{v, topic, origin, sections:{key:[{userId, displayName}]}}` —
on every local presence change **and** re-gossips it on each 10 s reaper tick, and a receiver
replaces that `(topic, origin)` partition wholesale. Full snapshots instead of deltas are what make
the mirror converge with no delete frames, acknowledgements or ordering assumptions; an empty
`sections` map is a real message that drops the partition at once. Freshness is judged by
**arrival**, never by the publisher's clock: a partition expires after 30 s (three missed gossips),
so a replica that crashed or was scaled away stops contributing dots within half a minute. The
local half stays authoritative — 120 s heartbeat TTL, `focus`/`blur`, per-topic section cap
unchanged — the merged snapshot collapses a user present on two replicas to one dot, and consume
never re-publishes. Same degradation rule as above: with Redis down, presence falls back to exactly
per-instance dots, because the local broadcast happens before the publish. Consumed payloads are
re-validated (presence-enabled class only, section-key shape, ≤16 origins per topic, ≤32 editors per
section) rather than trusted. The browser wire format is unchanged — a peer's dots simply appear in
the same `presence` frame — so no client change was needed.

**Acceptance**

- [ ] On every covered surface, a mutation by user A appears on user B's view in place — including
  across pages (an order status change updates both a peer's order detail and a peer's queue; a
  bank confirm updates the staff queue, the account views and the org-unit tabs).
- [ ] A requester/guest cannot subscribe to the `orders` queue or the bank rooms, yet their
  (server-published) order create still refreshes staff viewers' queues.
- [ ] No entity data crosses the socket on any topic; every peer re-render is the peer's own
  authorized, redaction-applied fragment GET with fresh `data-version` attributes.
- [ ] With Redis stopped, same-instance peers keep syncing; with two instances and Redis up, a
  change on instance A reaches a viewer on instance B.
- [ ] With two instances and Redis up, two editors of the same mission served by **different**
  replicas see each other's presence dots; a replica killed mid-edit stops contributing dots within
  30 s; with Redis stopped, each instance still shows its own editors' dots.
- [ ] A section key added to a page seam map without the registry row (or vice versa) fails
  `:frontend:test`.
- [ ] Every surface REQ-FE-010 covers is on a room: no shared-state page is left reloading on
  success or leaving peers stale. The Phase-3 four (`missions`, `refinery`, `members`,
  `org-structure`) were the last gap and are closed (#1235).
- [ ] Storing a refinery order refreshes a peer's open Lager, not just the refinery queue.
- [ ] An admin's parent-edge change on `/admin/org-structure` refreshes a *member's* open
  `/org-chart`, and an org-chart position edit refreshes another admin's open editor.
- [ ] A backend write that failed publishes nothing — peers do not re-fetch for a change that
  never happened.

**Enforced by:** `LiveSyncWebSocketHandlerTest` (topic parsing, cross-room isolation, per-topic
whitelists, publish-without-subscription, per-session rate limit, topic cap, close cleanup, plus the
F2/#1243 abuse bounds: per-user socket cap accept/refuse/decrement/per-user, per-topic publish
throttle across publishers, idle-bucket reaping) ·
`LiveSyncTopicTest` + `LiveSyncSectionMapParityTest` (topic-class parsing/exhaustiveness + seam-map
parity) · `LiveSyncSubscriptionAuthorizerTest` (per-topic allow/deny/fail-open incl.
requester-refused queue + bank dual-auth matrix) · `RedisLiveSyncFanoutTest` +
`RedisLiveSyncFanoutIntegrationTest` (publish-once, origin skip, Redis-down degradation, plus the
presence channel: snapshot serialisation, channel-based dispatch, empty-snapshot forwarding,
separate error series) · `LiveSyncPresenceServiceTest` (the ADR-0126 mirror: local+remote merge,
one-dot collapse, wholesale replace, no-change-on-re-gossip, origin/editor caps, partition expiry) ·
`OperationLiveSyncE2eTest` / `JobOrderQueueLiveSyncE2eTest` / `BankRequestsLiveSyncE2eTest` /
`MissionOrganisationLiveSyncE2eTest` / `InventorySharedLagerLiveSyncE2eTest` /
`RefineryOrderLiveSyncE2eTest` (one two-context e2e per page family) ·
`MissionListLiveSyncPublishTest` + `RefineryOrderLiveSyncPublishTest` +
`MemberManagementControllerTest.RosterLiveSyncPublishTests` (the server-side publish fires on success
and **only** on success) · `AdminOrgStructurePageControllerMvcTest` (the `units` / `forms` fragment
seams render their own section and nothing else) · `RefineryOrderDetailFragmentMvcTest` (the
`refinery-order:{id}` sections render section-sized, gate their catalog lookups, and degrade to an
inline error rather than a redirect) · **Code:**
`LiveSyncWebSocketHandler`, `LiveSyncTopicClass`, `LiveSyncSubscriptionAuthorizer`,
`LiveSyncLocalBus`, `RedisLiveSyncFanout`, `LiveSyncPresenceService` (local + mirrored halves),
`LiveSyncProperties` (`app.livesync.redis.presence-channel`), `krt-live-sync.js`, the per-page seam
maps
(`MISSION_SECTIONS`, `OPERATION_SECTIONS`, `ORDER_SECTIONS`, orders-queue seam, bank
`BANK_ACCOUNT_SECTIONS` / `ORGUNIT_ACCOUNT_SECTIONS` / `BANK_STAFF_SECTIONS` /
`ORGUNIT_BANK_SECTIONS`, materialboard — two section keys: `board` (Angebote) and `requests`
(Gesuche, REQ-MARKET-018), broadcast by `materialboerse.js` / `materialgesuch-modal.js`, pinned by
`LiveSyncSectionMapParityTest`; Phase-3: `MISSIONS_SECTIONS`, `REFINERY_SECTIONS`,
`MEMBERS_SECTIONS`, `ORG_STRUCTURE_SECTIONS` + `ORG_CHART_SECTIONS`; `REFINERY_ORDER_SECTIONS`) ·
**ADR:** ADR-0094, ADR-0126 · **Issues:** #1102, #1115, #1120, #1237, #1235, #1238

### REQ-FE-016 — Catalog pickers (material / game item / location) are searchable comboboxes

The user-picker rule of REQ-FE-011 extends to **catalog** pickers: every field that selects a
**material**, a **game item** or a **booking-flow location** from the catalog must be a
`krt-searchable-select` combobox — a plain `<select>` over a full catalog is incomplete. Catalog
pickers search **server-side** (`remoteSource` mode): the page never preloads the catalog as
`<option>`s; the picker fetches the matching entries per (debounced) keystroke through the public
`/catalog/material-search` / `/catalog/location-search` relays onto the backend picker searches
(`GET /api/v1/materials/search` with `jobOrderOnly`/`rawOnly` narrowing, `GET
/api/v1/locations/search`), name-sorted. **No silent caps, ever:** every entry
stays reachable by typing a narrower term regardless of catalog size, and the complete-list
endpoints that other surfaces consume (`/api/v1/materials/lookup`, `/api/v1/locations/lookup`)
stay deliberately **unbounded** — a fixed bound on a complete-list surface silently hides the
tail, the defect class that forced the item picker onto server-side search (ADR-0100).

**A picker relay MUST fetch strictly more rows than its combobox renders (binding).** The page
sizes live in `PickerSearch` (frontend `support`); the render cap lives in the browser
(`krt-searchable-select.js`'s `maxResults` default, plus any per-kind `krtComboboxI18n.kinds`
override). `krt-searchable-select.js` decides whether to show the "keep typing to narrow the list"
hint with `matches.length > maxResults` — a strict comparison against the rows it *received* — so a
relay that fetches the render cap **or fewer** makes that condition unsatisfiable: the hint can
never render and every match past the fetched page is invisible with nothing on screen saying so.
"Reachable by typing" is then unknowable, and the cap is silent in exactly the sense the paragraph
above forbids. The extra row is an overflow sentinel, never rendered. This shipped broken and was
not theoretical: `/catalog/location-search` fetched 25 rows against a render cap of 50, so **28 of
the 53 visible locations** — MIC-L5, Patch City, New Babbage and Orison among them — could not be
selected at all when booking stock into the Lager; `/inventory/item-search` and the bank-account
relay fetched *exactly* the render cap, with the same silent effect from the 51st match on.
`PickerSearchLimitsParityTest` reads the shipped JS and `fragments/head.html` off the classpath and
pins both halves against the constants so they cannot drift apart again.

**A kind may raise its render cap when its catalog is small and bounded.** Only `remote-locations`
does (`maxResults: 200`, mirrored by `PickerSearch.LOCATION_RENDER_CAP`): one row per live UEX city
/ space station plus admin-curated entries is 53 visible rows today, and a user booking stock
expects to scroll that list rather than guess a search term. The open-ended catalogs (materials,
game items, bank accounts) keep the 50-row default and rely on the hint. The marker
values are registered in `krt-catalog-search.js`: `remote-materials`,
`remote-materials-joborder` (orders lines), `remote-materials-raw` (refinery inputs),
`remote-locations`, and `remote-game-items` (the inventory item mode's bookable-item picker — the
one **authenticated** relay, `GET /inventory/item-search`, onto the role-gated backend
`/api/v1/inventory/item-catalog`, REQ-INV-029). Each marker carries its kind-specific default
placeholder / no-results wording via the `krtComboboxI18n.kinds` map (REQ-FE-011) — the material and
location pickers say "Material/Ort suchen oder wählen…", never the user-picker text.
Server-rendered edit/redisplay states seed exactly
**one** selected `<option>` (gated `th:if`) so the label and its metadata survive enhancement;
programmatic fills use `krtCombobox.setValue(value, label, data?)` — in remote mode a bare
`setValue(value)` cannot resolve a label and clears the field, so every call site passes the label
(or resolves the entry via the search relay first). Converted sites: the inventory Einbuchen
material + location pickers and its item-mode game-item picker, the job-order create/edit material
lines (server-rendered **and** JS-built rows), the refinery create/details input-material pickers,
the Umbuchen target-location pickers, the production modal's book-in location picker
(REQ-INV-032), the `/inventory/material` navigate select and the admin material-alias pickers; the
orders item picker already used the component's `remoteSource` API.

**Free-text terms cross the frontend→backend hop as URI-template variables, encoded exactly once
(binding).** A page-controller relay that forwards a free-text search term (`q` / `query` /
`search`) to a backend endpoint must pass it as a WebClient URI-template variable — `…?q={q}` plus
the **raw** value through the 3-arg `backendApiClient.get(uriTemplate, type, uriVars…)` overload —
and must **not** `URLEncoder.encode` it into the URI string. Hand-encoding is double-encoded on the
hop: WebClient's default `TEMPLATE_AND_VALUES` mode re-encodes the `%`, so `Müller` reaches the
backend as the literal `M%C3%BC…` (and `John Doe` as `John%2520Doe`) → zero matches for any umlaut /
space / reserved-character term, while single-token ASCII queries slip through unnoticed and hide
the defect. This holds for **every** free-text relay, not only the catalog pickers above: the
operations and admin-blueprint list filters, the personal-inventory and default-blueprint
type-aheads, the admin owned-blueprint filter, and the personal/admin item-inventory `q` filters all
forward the raw term as a URI variable. Only UUID / enum / int / bool / sort params (carrying no `%XX` after encoding) are safe to
concatenate into the URI string; the wire encoding is pinned by `BackendApiClientHappyPathTest`, and
per-relay multi-word + umlaut regression `…passes{MultiWord,Umlaut}QueryAsUriVariable` MvcTests
guard each site.

**Option-metadata mirror (the load-bearing part).** Enhancing a select **removes** the native
`<option>` elements, so option-level metadata (`data-quantity-type` on material options,
`data-refined-id`/`-name` on refinery options) would vanish. The component therefore mirrors the
selected option's extra `data-*` (everything outside the combobox-owned keys) onto the hidden
input, and consumers read `hidden.dataset.*` instead of `selectedOptions[0].dataset.*`. The mirror
is **one shared helper invoked on every value-set path** — click/keyboard commit, enhance-time
preselect seeding, `reconcile()`'s typed-exact-match, and the programmatic `setValue()` API —
because covering only commit would leave typed or programmatic picks with stale metadata. It
removes previously-mirrored keys before applying the new option's map (an option lacking a key the
previous one carried must not inherit the old value) and never overwrites keys the enhancer copied
from the select itself (`data-role`, `data-trigger`, … are reserved). A `remoteSource` may return
an optional `data` map per option for the same purpose.

**Conversion checklist (binding).** Before adding the marker to any select, grep the page's JS for
`querySelector('select')`, `.selectedOptions`, `.selectedIndex`, `.options[`, direct `.value =`
writes and `cloneNode` on containers holding the picker — each hit is either re-pointed
(hidden-input dataset read / `element.krtCombobox.setValue()`, which never fires `change`; dispatch
one explicitly where the old flow relied on it) or the site must not be converted. Rows built by
cloning a **live** row must switch to an inert `<template>`/options-template source plus
`krtEnhanceComboboxes(row)` — a cloned enhanced combobox is dead (listeners dropped, duplicated
ARIA ids, no native select left to re-enhance).

**Bespoke pickers with a browse-everything mode.** One picker predates the `krt-searchable-select`
standard and stays bespoke: the personal-inventory (Mein Inventar) **UEX location typeahead**
(`static/js/personal-inventory.js` → `/personal-inventory/uex-search` →
`GET /api/v1/uex/locations/search`). It searches server-side like the converted pickers — typing
narrows the query and every UEX city / space station stays reachable — **but** it also renders on
empty-query focus, a "browse everything" mode that returns up to the backend clamp (2000 rows). In
that one mode "type a narrower term" cannot recover a hidden tail because the user has typed
nothing, so the no-silent-truncation rule is honoured differently: when a response **fills** the
requested cap the list appends a non-selectable truncation hint
(`personalInventory.field.location.more`, "more matches — refine your search") instead of
presenting the capped list as the whole universe. Any future bespoke picker with a
browse-everything mode carries the same hint.

**Acceptance**

- [ ] Every material / game-item / booking-location picker carries a `data-krt-combobox` marker
  bound to a registered remote source (or wires `remoteSource` via the direct API); typing
  fetches the matching entries server-side; the committed value submits under the original field
  name.
- [ ] An entry beyond any single response page (25 rows) is reachable by typing a narrower term —
  no picker, endpoint or template silently truncates the catalog.
- [ ] The bespoke UEX location typeahead's browse-everything mode appends a truncation hint when a
  response fills the requested cap, and drops it once a query narrows the result below the cap.
- [ ] Picking a PIECE material through the combobox switches the amount field to whole-number mode
  and back for SCU (the quantity-type mirror), including on edit-mode preselect and typed exact
  match — never a stale unit from the previously selected option.
- [ ] Dynamically added rows (order material lines, refinery goods rows) render a working
  searchable picker, and programmatic fills (SCMDB import, import-review suggestion chips,
  Umbuchen preselect) show the picked label, not a blank box.

**Enforced by:** the migrated picker flows in `InventoryOperationsE2eTest`,
`JobOrderCreateE2eTest`, `OrdersCreateScuHintRevealE2eTest` (quantity-type mirror + stale-key
removal end-to-end) and `RefineryOrderCreateE2eTest` (via `E2eSupport.selectComboboxByValue`) ·
`CatalogSearchControllerMvcTest` (relay mapping incl. refined metadata, fail-soft empty list,
anonymous reachability) · MockMvc view tests asserting the mode-bearing marker on every converted
select (`JobOrderPageControllerResponsiblePickerMvcTest`, `OrderHierarchyVisibilityTest`,
`InventoryPageControllerMvcTest`, `AdminMaterialAliasesPageControllerMvcTest`,
`OfficerRefineryAccessTest`) · the search unit tests in `LocationServiceTest` /
`MaterialServiceTest` (LIKE escaping, filter flags, complete unbounded lookups) · **Code:**
`krt-searchable-select.js` (`optionData` harvest, `mirrorItemData` on all four value-set paths,
reserved select-level keys, label-carrying `setValue`), `krt-catalog-search.js` (remote-source
registry), `CatalogSearchController`, the re-pointed consumers in `inventory-input.js`,
`orders-create.js` (`refreshMaterialUnit`, async `importFromScmdb`), `orders-detail.js`,
`refinery-orders-create.js` / `refinery-orders-details.js` (`updateOutputMaterial`, rebuilt
`addMaterialRow`, async suggestion chips), `refinery-yield-badge.js`; the bespoke browse-mode hint
in `personal-inventory.js` (`renderResults`, `SEARCH_LIMIT`) · **ADR:** ADR-0053
(follow-up note), ADR-0100

### REQ-FE-017 — Account-selection fields are server-side search comboboxes too

REQ-FE-011 established the searchable combobox and its `remoteSource` mode for **user** pickers; the
same mechanism now covers **bank-account** selection, so the pattern is proven for a second entity
type and the `window.krtComboboxRemoteSources` registry is confirmed generic (not user-specific).
**Every field that lets a user pick a bank account from a potentially-large set must be a
`remoteSource` combobox** that fetches matching accounts on demand rather than preloading a roster —
the account analogue of the 5000-scale switch (ADR-0085/ADR-0089), and the fix for the former
`?size=500` preload that silently truncated past 500 accounts (REQ-BANK-053).

The control opts in **declaratively by the marker value** `data-krt-combobox="remote-bank-accounts"`,
resolved through the shared registry to the source in
[`krt-bank-account-search.js`](../../frontend/src/main/resources/static/js/krt-bank-account-search.js)
(loaded from `fragments/head.html` **before** `krt-searchable-select.js`, exactly like the user
sources). The source queries the frontend proxy `GET /api/proxy/bank/accounts/search` (→ backend
`GET /api/v1/bank/accounts?status=ACTIVE&size=…&sort=name,asc`, caller-scoped, REQ-BANK-010) and maps
each row to a `<accountNo> — <name>` option. Four pickers convert: the transfer destination
(REQ-BANK-040), the direct-booking **source** account, the grant-create account and the grants
**per-account filter**; an edit-mode picker (the grants filter) seeds exactly its current account so
the box shows a name, not a raw id, while the add-only pickers seed only the placeholder.

Two combobox-conversion rules from REQ-FE-011/ADR-0089 apply and are load-bearing here:

- **Delegated `change` handlers match the attribute, not the tag.** Enhancement replaces the
  `<select>` with a value-only hidden `<input>` that inherits the control's `data-*` and re-dispatches
  `change` on commit, so a handler pinned to `select[data-role=…]` would stop firing. The
  source-account and grants-filter handlers use `[data-role=…]`.
- **Per-option metadata that must survive is carried out-of-band.** The direct-booking source
  account's per-account **Begründung mandate** (REQ-BANK-045) used to ride the `<option>`'s
  `data-requires-justification`; since the hidden input has no options, the search source records it
  in `window.krtBankAccountMeta` (id → boolean, derived from the account type) and `bank.js` reads it
  there — keeping the shared combobox component generic.

Pickers bounded to a genuinely small, page-scoped account set may stay in local-filter mode; deviation
beyond that needs prior approval by @greluc and a spec amendment first.

**Acceptance**

- [ ] Every bank-account selection field over a potentially-large set carries
  `data-krt-combobox="remote-bank-accounts"` and ships **no** preloaded account `<option>` roster;
  typing finds an account by **account number or name**, and the picked account's id submits.
- [ ] The account source is registered before the enhancer and upgrades pickers on initial load **and**
  after `krt:swapped` (the movement modal rides the account-detail `accountBody` and manage
  `manageBody` swaps).
- [ ] The grants per-account filter seeds its currently-filtered account by name; clearing it filters
  to all accounts.
- [ ] The source-account picker still marks the Begründung `required` for a CARTEL/CARTEL_BANK/SPECIAL
  source, reading the mandate from `window.krtBankAccountMeta`.

**Enforced by:** `BankInPlaceFragmentMvcTest` / `BankDashboardMovementModalMvcTest` /
`BankRequestQueuePageControllerMvcTest` (the converted pickers carry the `remote-bank-accounts` marker
and preload no roster), `BankProxyControllerTest` (the `/accounts/search` proxy forwards
active/name-sorted + unwraps content), `BankPageControllerTest` / `BankGrantsPageControllerTest` (no
account roster preloaded; the grants filter seeds only the selected account) · **Code:**
`krt-bank-account-search.js` (the `remote-bank-accounts` `krtComboboxRemoteSources` entry +
`window.krtBankAccountMeta`), `krt-searchable-select.js` (the marker→`remoteSource` lookup, reused),
`static/js/bank.js` (attribute-delegated source/filter handlers, metadata-map justification),
`controller/BankProxyController#searchAccounts`, `templates/fragments/bank-movement-modal.html`,
`templates/bank-grants.html`, `fragments/head.html` (script load order) · **ADR:** ADR-0053, ADR-0089,
ADR-0106 · **Issues:** —

### REQ-FE-018 — The browser scripts are type-checked, and the DTO types are generated from the OpenAPI spec

The hand-written scripts under `static/js` are **statically type-checked** by the TypeScript
compiler running in checker-only mode (`tsc --noEmit`, `frontend/tsconfig.json`). This is a
*checking* requirement, not a language one: the sources stay JavaScript, stay classic non-module
`<script>` tags sharing one global scope (ADR-0069), and nothing is compiled, bundled or renamed.
The decision and its rejected alternatives are ADR-0125.

**The gate.** `:frontend:typecheckJs` runs strictly (`ignoreExitValue = false`) and is wired into
`check` next to `lintCss` / `lintJs` / `lintHtml` / `prettierCheck`. A type error fails the build
exactly as an ESLint finding does. `moduleDetection` must stay `legacy`: `package.json` declares
`type: module`, and under the default `auto` every `.js` file would be classified as an ES module
with its own scope — which would both misrepresent the runtime and blind the checker to the
cross-file globals and redeclaration collisions it exists to catch.

**Opt-in per file.** Checking is enabled per file by a leading `// @ts-check`; `checkJs` stays
`false` globally so the ~40k pre-existing lines do not have to be fixed at once. **A file that
opts in must be error-free** — there is no partial state, and opting a file in is what makes its
errors a build failure. Coverage is expected to grow, and the sequencing lives in
[`TYPESCRIPT_MIGRATION_PLAN.md`](../TYPESCRIPT_MIGRATION_PLAN.md).

**Backend DTO shapes are never restated by hand.** `:frontend:generateApiTypes` derives
`build/generated/ts/api.d.ts` from `backend/src/main/resources/api/openapi.json` on every build,
and `typecheckJs` depends on it. The emitter is `frontend/scripts/gen-api-types.mjs`, a
dependency-free Node script (ADR-0130) — it emits `components.schemas` only, and **fails the
build** on an OpenAPI construct it cannot express (`allOf` / `oneOf` / `anyOf` / `not` /
`discriminator`) or a dangling `$ref`, because a DTO silently degraded to `unknown` type-checks
everywhere and removes exactly the protection this rule exists to give. The generated file is **build output and must not be committed**:
deriving it every time is what makes drift between the frontend's idea of a DTO and the published
contract structurally impossible. Annotations use the global aliases from `types/dto.d.ts`
(`ApiDto<'MaterialDto'>`, `ApiPage<…>`, `ApiProblem`) — never a hand-copied field list.

**The shared contracts are declared, not inferred.** Three hand-written files under
`frontend/types/` (outside `static/`, so nothing is served): `globals.d.ts` for the cross-file
runtime contract — the `window.krt*` APIs, the shared helpers, the custom DOM events —
`thymeleaf-bootstrap.d.ts` for the page constants a Thymeleaf bootstrap block injects, and
`dto.d.ts` for the generated aliases. **When you add a `window.krt*` API, a custom DOM event or a
bootstrap constant, declare it in the matching file in the same change** — an undeclared addition
silently degrades every consumer to `any` or breaks the gate.

**ESLint keeps the visibility half.** Bootstrap constants are declared globally for the checker
while at runtime each exists only on the page whose bootstrap declared it. ESLint's `no-undef`
against each module's `/* global */` header remains the check that a module only references what
its own page provides. Both checks are required; neither replaces the other, so a new bootstrap
constant is added to **both** the declaration file and the module's `global` header.

**JSDoc must be JSDoc.** In a checked file, `{@code …}` / `{@link …}` / `@param name {shape}` —
Javadoc spellings this repo uses elsewhere — are parsed as type syntax and are hard errors.
Convert them when opting a file in.

> **Verification** — **Gate:** `./gradlew :frontend:typecheckJs` (strict, in `check`) ·
> **Config:** `frontend/tsconfig.json` (`allowJs` + `noEmit` + `moduleDetection: legacy`),
> `frontend/build.gradle.kts` (`generateApiTypes`, `typecheckJs`) · **Code:**
> `frontend/types/globals.d.ts`, `frontend/types/thymeleaf-bootstrap.d.ts`,
> `frontend/types/dto.d.ts`, `frontend/scripts/gen-api-types.mjs`, the 32 files carrying
> `// @ts-check` · **ADR:** ADR-0125, ADR-0130 ·
> **Issues:** —

### REQ-FE-019 — The same live sync reaches the native app, in both directions

REQ-FE-010 promises that *on any surface where several users can see the same state, a peer's change
propagates to the others without a manual reload*. REQ-FE-015 delivers that promise for browser
tabs. The native app is a peer on those same surfaces and was outside it entirely, which cost the
promise twice over: a browser edit never reached the app, and — the worse half — an **app write left
every open browser stale**, breaking live sync on surfaces where it had been working.

The bridge (ADR-0143) closes both directions over the channel REQ-FE-015 already uses,
`basetool:livesync:changed`, with its payload unchanged (`{v, topic, sections, origin}`). Nothing in
the frontend is modified: it keeps publishing and consuming exactly as it did, and simply now has a
peer that is not a frontend instance.

**Receiving — `GET /api/v1/live-sync/stream?topics=…`.** **One stream per client, not per screen** —
it carries the *union* of every screen currently observing, reference-counted so a room leaves when
its last observer does, and reopened whenever that union changes. The topic set is named in the URL
and fixed for one stream's life, so the URL *is* the subscription and there is no subscribe protocol
to fall out of sync after a reconnect. Three events: `subscribed` once, naming the topics that were
**accepted**; `changed` per frame; and the same keep-alive heartbeat the notification stream sends,
for the same proxy and NAT reasons.

**The per-stream topic cap is therefore a per-client budget, and it is 16** —
`LiveSyncController.MAX_TOPICS_PER_STREAM`, matching `LiveSyncWebSocketHandler.MAX_TOPICS_PER_SESSION`
for the web relay's identical union shape. It was 8, sized against "what the busiest screen needs",
and the number was wrong because the shape was: screens left on the back stack keep their rooms, so a
member moving through the app accumulates them. In production one member crossed 8, and because the
endpoint refuses the **whole** request rather than the surplus, live sync went dead on *every* screen
at once and stayed dead — the client re-asked on its reconnect backoff and was refused each time.
Refusing rather than truncating is still right (a silently half-live screen has nothing to notice it
by), which is exactly why the budget has to fit the union and the two caps must not drift apart.

**A refusal of the request is a verdict the client must stop re-sending.** `400` and `403` both say
something about *this* request that an identical retry cannot change, so the client gives up after
two and tells its screens with an empty `subscribed` list, which is their cue to poll. The scope of
"gives up" is the union, not the app: a changed union opens a fresh stream with a fresh counter. A
`401` is deliberately not in that set — a stale token is precisely the refusal a retry fixes once the
token has been renewed.

**A topic the caller may not join is dropped from the set, not made fatal** — and the accepted list
is the first thing the client is told. A client must be able to distinguish "this room is live and
quiet" from "this room will never speak", because the two are identical on the wire and only the
second one means the screen has to poll. A stream where *nothing* was accepted is `403`.

**Emitting — `POST /api/v1/live-sync/changed`.** The app announces its own writes the way a tab
does, and the backend relays locally and then onto the shared channel. `202`, because the frame is a
signal and not a transaction: the mutation it follows has already committed, and a client that
treated a failure here as a failed write would show an error for a change that is in the database. A
`429` is to be **dropped, not retried** — the buckets exist to bound the re-fetch herd, and a retry
defeats the bound it just hit.

**The bounds are REQ-FE-015's, unchanged**: per-subject burst 40 / refill 20 per second, per-topic
burst 200 / refill 100 per second, and receiver-side coalescing of 400 ms for a per-resource room
and 1500 ms for a global one, full-jittered. Frames consumed from Redis bypass the per-topic bucket
— they were already accepted where they originated.

**Authorization is the real read, not a proxy for it.** Each room's gate is the gate of the fetch it
provokes (`ownerScopeService.canSeeMission`, `canSeeJobOrder`, `canViewJobOrders`, …), asked
synchronously of the backend's own data. There is no fail-open branch, because unlike the frontend's
authorizer there is no indeterminate verdict to resolve; a check that throws refuses the room.

**No presence crosses.** ADR-0094 fails the `mission` class closed precisely because a web subscribe
emits an editor-presence snapshot — pseudonymous ids and callsigns. This bridge emits `changed` and
nothing else, subscribes to no presence channel, and offers no way to ask for one, which is why the
mission room may be joined here at all. App members are therefore invisible as editor dots and see
none; ADR-0126's gossip is deliberately not bridged.

**Two registries, one gate.** The backend holds its own copy of the topic classes and section
whitelists, because the modules share no code. Drift between them produces this bridge's worst
failure shape — nothing throws, nothing logs, a screen just stops updating — so
`LiveSyncTopicRegistryParityTest` reads the frontend's `LiveSyncTopicClass` source and fails the
build when the backend names a prefix or a section the frontend does not. The frontend's staff-only
rooms (`bank` without an id, `members`, `org-structure`) are asserted **absent** from the backend
registry: the admin area is web-only permanently, so a room there would have no reader.

**Acceptance**

- [x] The published payload is field-for-field what a frontend instance sends (`RedisLiveSyncFanoutTest`).
- [x] A frontend frame is delivered to app streams; an own-origin frame is skipped
  (`RedisLiveSyncFanoutTest`).
- [x] A frame naming a room this backend does not serve is dropped and counted, not fatal
  (`RedisLiveSyncFanoutTest`).
- [x] A refused topic is dropped and the stream still opens; nothing accepted is `403`
  (`LiveSyncControllerTest`).
- [x] Too many topics is refused rather than truncated (`LiveSyncControllerTest`).
- [x] Local delivery precedes the fan-out, so a Redis outage costs peers only
  (`LiveSyncRelayServiceTest`).
- [x] Both buckets bound what they are meant to, and one member's flood does not cost another theirs
  (`LiveSyncRelayServiceTest`).
- [x] Each room's gate is its own read's gate; a throwing check refuses
  (`LiveSyncSubscriptionAuthorizerTest`).
- [x] The backend registry is a subset of the frontend's, staff rooms excluded
  (`LiveSyncTopicRegistryParityTest`).

> **Code:** `backend/…/controller/LiveSyncController`, `backend/…/service/LiveSyncStreamService`,
> `LiveSyncRelayService`, `LiveSyncSubscriptionAuthorizer`, `RedisLiveSyncFanout`,
> `LocalLiveSyncFanout`, `LiveSyncRedisConfig`, `backend/…/support/LiveSyncTopic`,
> `LiveSyncTopicClass`, `LiveSyncAuthorization` · **ADR:** ADR-0143 (ADR-0094 unchanged) ·
> **App side:** `basetool-android` `REQ-APP-SYNC-*`

## Out of scope

- The per-area conversions themselves (one issue per area, #573–#582) — this spec is the contract
  they each satisfy, not the work list.
- Converting the sources to TypeScript, and the inline `<script>` blocks still in the Thymeleaf
  templates — the checker of REQ-FE-018 reads `.js` files only. See ADR-0125 and
  [`TYPESCRIPT_MIGRATION_PLAN.md`](../TYPESCRIPT_MIGRATION_PLAN.md).
- Switching the CSRF token repository to cookie-based, and adopting htmx or app-wide Alpine — all
  explicitly rejected in ADR-0012.
- Live-collaboration features beyond the section-refresh sync of REQ-FE-010/-015
  (operational-transform text co-editing, server-pushed conflict resolution). Cross-replica fan-out
  via Redis pub/sub moved **in scope** with REQ-FE-015 / ADR-0094, and cross-replica **presence
  dots** followed in #1237 / ADR-0126. The native app moved in scope with REQ-FE-019 / ADR-0143 for
  `changed` frames only — **editor presence stays web-only**, deliberately, because it is the one
  part of the socket that carries cross-user identity data.
- A server-side trigger interceptor that publishes `changed` from the mutation itself rather than
  from the client. Strictly better and strictly more expensive; unchanged as the not-taken option
  since ADR-0031, and REQ-FE-019's publish endpoint would become redundant rather than wrong if it
  ever lands.
- Backend business-logic changes beyond adding JSON proxy endpoints that reuse existing backend
  APIs/DTOs.

## Open questions

- None open. The transitional `MissionSubresource` alias was **removed** in #574; mission-detail now
  calls `krtFetch.write` through a small page-local `krtMissionWrite` wrapper, so `krt-fetch.js`
  carries no page-specific code.
- **Resolved (#574 → #589):** the mission core-edit form (`#mission-form`) is now in-place — it saves
  through the `updateMissionAjax` twin with inline field-error rendering (see REQ-FE-007 below), so
  the whole mission-detail page is reload-free. The classic `POST→redirect` stays the no-JS fallback.
- **Resolved (#575 → #591):** the refinery **screenshot-extract import** carve-out is closed — it now
  swaps the pre-filled create-form fragment in place via the `importExtractAjax` twin (see REQ-FE-005
  above), and `datetime-splitter.js` was made swap-safe in the process. The whole refinery surface is
  now reload-free.

