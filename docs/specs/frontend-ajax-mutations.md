> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-07-03.
> **Owner area:** FE/UI · **Related ADRs:** ADR-0012, ADR-0013, ADR-0053, ADR-0069, ADR-0071

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
(#581) and admin-CRUD (#582) twin / fragment / endpoint MVC + e2e tests. **Issues:** the epic children
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
to its detail page or the list, and the refinery detail-page actions (save / store / cancel) redirect
to the refinery-order list. For these flows the no-reload guarantee of REQ-FE-001 applies to the
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
- [ ] On success the page navigates exactly once to the server-supplied `targetUrl`.
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

The transport is the **existing per-mission presence WebSocket** (`/ws/missions/{id}/presence`,
`mission-presence.js`) — no new channel, no new backend module, no Flyway migration. The acting
client's `krtRefreshMissionSection(keys)` chokepoint, which already runs after every successful
mutation, additionally calls `missionPresence.sendChanged(keys)`; the handler relays a
`{type:"changed","sections":[…]}` frame to every **other** socket on that mission (the originator is
excluded — it already applied its own change). Each peer turns the frame into a
`krtRefreshMissionSection(keys, {broadcast: false})` — `broadcast:false` stops the applied change
from echoing back into a loop.

**Only opaque section keys travel over the socket — never mission data.** Every peer re-pulls the
affected fragment through its own authenticated, authorization-checked
`GET /missions/{id}?fragment=…`, so guest field-redaction and the member-only finance gate still
apply per viewer; a guest never receives privileged data via the push. The relay sanitises the
inbound `sections` array (keys outside the whitelist
{`crew`,`finance`,`mgmt`,`overview`,`steps`,`objectives`,`frequencies`} dropped, count capped) so
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

Two server-side guards bound the abuse surface the socket adds. The handshake is **authorized against
mission access** — `MissionPresenceHandshakeAuthInterceptor` issues the same authenticated
`GET /api/v1/missions/{id}` the page does, so an authenticated user cannot join the presence room of a
mission they may not see (an explicit backend 403/404 refuses the handshake; a transient backend error
fails open so a blip never kills presence). Inbound `changed` frames are **rate-limited per session**
(a token bucket sized far above any human edit cadence), so a crafted client cannot drive sustained
re-fetch amplification even within a mission it can see.

An incoming refresh must **never yank a section out from under an active edit**: while a modal is open
(or focus sits inside the target section's container) the refresh is deferred behind a DS-styled
"Aktualisierungen verfügbar" pill (no native dialog) that applies the held-back sections on click.
Bursts are coalesced (a debounce window jittered like the reconnect backoff — `COALESCE_MS +
random()*COALESCE_MS` — so peers that all received the same `changed` frame within microseconds do
not fire their fragment refetches in one synchronized spike, #1125), and a dropped-then-reconnected
socket triggers a one-shot resync of every visible section to recover signals missed while offline.

**Single-instance, like presence.** The relay reuses the in-memory per-mission session map, so it is
correct only for a single frontend replica (the current deployment). Scaling the frontend out
horizontally requires moving both presence and this relay behind a Redis pub/sub fan-out — the
swap-out point is `MissionPresenceService` / `MissionPresenceWebSocketHandler` (ADR-0031).

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

Coverage note: `MissionLiveSyncE2eTest` exercises the representative path end-to-end twice — a
participant add propagating to a second viewer in place (no reload), and a Ziele-objective add
reaching a passive viewer's backgrounded editor (pinning a section key beyond the original four
across broadcast → relay whitelist → receiver); the remaining mutation kinds in the first bullet all
route through the same `krtRefreshMissionSection` / `krtNotifyMissionChanged` chokepoint, so they
inherit the same behaviour, and the per-viewer guest-redaction guarantee rests on the existing
authenticated fragment GET (covered by the mission fragment/redaction tests) rather than a dedicated
live-sync case.

**Enforced by:** `MissionPresenceWebSocketHandlerTest` (relay to peers, origin exclusion, key
sanitising/dedup, full seam-map whitelist relay, no-op on empty, per-session rate limit) ·
`MissionPresenceHandshakeAuthInterceptorTest`
(handshake allowed on authorized read, refused on 403/404, fail-open on transient, 400 on a malformed
path) · `MissionLiveSyncE2eTest` (two-context live participant-add propagation + no-reload assertion) ·
**Code:** `MissionPresenceWebSocketHandler.broadcastChanged` / `allowChangedFrame`,
`MissionPresenceHandshakeAuthInterceptor`, `mission-presence.js` (`sendChanged` / `krt:mission-changed`
/ `krt:mission-resync`), `mission-detail.js` (`krtRefreshMissionSection` broadcast + live-sync
receiver — its container map derived from the `MISSION_SECTIONS` seam map — with flush-time busy
re-check + finance-badge `krt:swapped` listener), `mission-detail.html` (`overviewSection` fragment),
`MissionPageController` (`overview` fragment case) · **ADR:** ADR-0031, ADR-0069

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
(`userSelect.search.*`). A new or changed user-selection surface that ships a plain `<select>` or a
hand-rolled picker is **incomplete**.

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

**Carve-outs.** (1) Fields that must also accept a free-text **guest** name — the mission
participant-add and party-lead pickers — keep using the `/users/search`-backed autocomplete, which
already live-searches both username and display name and must keep accepting non-user names; the
strict combobox (which forces a pick from the list) does not fit them. (2) **Holder** pickers (bank
deposit / withdrawal / transfer / booking-confirm) select a bank holder by handle — holders may be
non-users and carry only a handle — so they are searchable comboboxes that filter the **handle** (no
separate username/display-name term). Deviation beyond these carve-outs needs prior approval by
@greluc and a spec amendment first.

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

**Enforced by:** `AdminPersonalBlueprintsPageControllerMvcTest`
(`view_userPicker_isRemoteSearchCombobox_withoutRosterPreload` — the rendered picker carries the
`data-krt-combobox="remote-users"` marker and ships **no** preloaded option roster — and
`view_userPicker_seedsSelectedMemberInEditMode` — a selected member is seeded by name) ·
`UserAccessControlTest` (`/users/search-bank` allows bank staff, `/users/search` still 403s them) ·
`UserProxyControllerTest` (the `search-bank` + single-user `/users/{id}` proxies) · the
converted-picker flows drive the combobox end-to-end (open → pick → submit) in `BankBookingE2eTest`,
`BankOrgUnitRequestsE2eTest`, `MissionFinanceEntryE2eTest` and `RefineryOrderCreateE2eTest` (via
`E2eSupport.selectComboboxByValue` / `selectComboboxFirstOption`) · **Code:**
`krt-searchable-select.js` (`makeItem` + `data-search` local filter, the marker→`remoteSource`
registry lookup in `autoConfig`, global `enhanceWithin` on `DOMContentLoaded` + `krt:swapped`,
`id`/`data-*` passthrough, `setValue` API, `window.krtEnhanceComboboxes`), `krt-user-search.js`
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

## Out of scope

- The per-area conversions themselves (one issue per area, #573–#582) — this spec is the contract
  they each satisfy, not the work list.
- Switching the CSRF token repository to cookie-based, and adopting htmx or app-wide Alpine — all
  explicitly rejected in ADR-0012.
- Live-collaboration features beyond the section-refresh sync of REQ-FE-010 (operational-transform
  text co-editing, server-pushed conflict resolution, cross-replica fan-out via Redis pub/sub).
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

