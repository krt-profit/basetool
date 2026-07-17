# ADR-0105 — Server-side bank-account search for the account pickers + real pagination for the management table

- **Status:** Accepted
- **Date:** 2026-07-17
- **Deciders:** @greluc
- **Related:** ADR-0053 (searchable-combobox standard) · ADR-0089 (dedicated bank-audience user search,
  the direct precedent) · ADR-0085 (scale for ~5000 accounts) · REQ-FE-011 · REQ-FE-017 (new) ·
  REQ-BANK-010/-040/-047/-053 (new) · `BankAccountController.getAccounts` ·
  `BankProxyController.searchAccounts` · `krt-bank-account-search.js`

## Context

Every bank surface that needs a list of accounts fetched them through **one unbounded preload**:
`GET /api/v1/bank/accounts?size=500`, called from `BankPageController` (the transfer-destination
select, REQ-BANK-040), `BankGrantsPageController` (the grant target select + the per-account filter),
`BankManagePageController` (the account-management **table** rows) and
`BankRequestQueuePageController` (the direct-booking modal's source + destination selects). There was
no search, no pagination and no "more" indicator: **past 500 active accounts a transfer destination,
a grant target or a managed account silently became unreachable.** ADR-0085 explicitly plans for
~5000 members, so an org with hundreds of squadron/special-command accounts plus the special-account
long tail can cross 500. This is the account-side twin of the user-picker problem ADR-0089/#1193
already solved: at that scale a picker must not ship the whole roster as `<option>`s.

The account listing endpoint already exists, is already caller-scoped (management sees all, an
employee sees exactly their granted accounts, REQ-BANK-010) and already returns a `PageResponse`. So,
unlike the user case, there is **no authorization difference** between "the picker audience" and "the
table audience" — both are `BANK_EMPLOYEE`-gated and both resolve the same visibility. The only thing
the pickers need that the endpoint lacked was a **text filter** (and a status filter so the pickers
show active accounts while the management table shows every status).

## Decision

**One endpoint, extended in place — not a new one.** Add `query` (case-insensitive substring over
`name` **and** `accountNo`), repeatable `status`, and repeatable `type` parameters to the existing
`GET /api/v1/bank/accounts`. An absent `status`/`type` means "all" (the full enum set), so the
management table keeps listing every account while a picker narrows to `status=ACTIVE`. The filter is
pushed into the repository (`findAllFiltered` / `findGrantedToFiltered`, the management and
grant-scoped variants) as a `LOWER(name/accountNo) LIKE LOWER(CONCAT('%', :query, '%'))` predicate
plus `status IN :statuses AND type IN :types`. The query is a **bound parameter** (SQL-injection-safe)
and is deliberately **not** `LikePatterns`-escaped: plain `LIKE` does not honour the backslash escape
in this Hibernate/PostgreSQL setup (verified — an escaped `%` matches literally-nothing, which would
*hide* an account whose name contains a `%`), so a caller's `%`/`_` act as harmless LIKE wildcards on
this bank-employee-gated read. A blank query is normalised to the empty string → the match-all
`LIKE '%%'` (never a null bind, the "empty means all" convention shared with `UserRepository`).
Balances stay joined in the one grouped query, so the read remains statement-bounded regardless of
the account count (`BankReadNoNPlusOneTest`).

We rejected a **dedicated `/api/v1/bank/accounts/search`** twin (the shape ADR-0089 chose for users):
that separation existed only to keep two *authorization regimes* apart, and there is no such split
here. A second byte-identical endpoint would be pure duplication.

**The account pickers become `remoteSource` comboboxes (REQ-FE-017).** A new
`window.krtComboboxRemoteSources['remote-bank-accounts']` source (`krt-bank-account-search.js`,
loaded before the enhancer exactly like the user sources) fetches matching **active** accounts on
demand from the frontend proxy `GET /api/proxy/bank/accounts/search` (which forwards to the backend
with `status=ACTIVE&size=50&sort=name,asc`). Four pickers opt in by the `remote-bank-accounts` marker
— the transfer destination (`destinationAccountId`), the direct-booking source account
(`sourceAccountId`), the grant-create account and the grants per-account filter — so none of them
preloads a roster. Because combobox enhancement replaces the `<select>` with a value-only hidden
input, the per-option **justification mandate** the source-account picker needs (REQ-BANK-045) can no
longer ride on the `<option>`; the source records each fetched account's mandate in a small
`window.krtBankAccountMeta` map (keyed by account id, derived from the account type), which `bank.js`
reads. Delegated `change` handlers that were pinned to `select[data-role=…]` are relaxed to
`[data-role=…]` so they still fire on the enhancer's hidden input (the ADR-0089 combobox-delegation
rule).

**The management table gets real pagination.** `BankManagePageController` pages the accounts list
(`page`/`size`, name-sorted for a stable order, default 25) and renders the shared
`fragments/pagination` pager + page-size picker, re-rendered in place through the existing
`manageBody` fragment swap (REQ-FE-005) — the same krtFetch `bindSwap` pattern the booking-history
pager uses. The tab count switches from the current page's size to the page's `totalElements`. The
singleton `CARTEL` account the KRT-Freigaben tab needs (which may not sit on the current page) is
fetched by its own `?type=CARTEL&size=1` lookup rather than scanned out of the page.

## Consequences

- A transfer destination / grant target / managed account stays reachable at any account count: the
  pickers search server-side (debounced, capped) and the table pages. The 500-cap silent-truncation
  class is closed for the bank area.
- One extra frontend proxy read (`/api/proxy/bank/accounts/search`) and one JS source; the backend
  gains only three optional query parameters on an existing, already-caller-scoped, already-audited-
  free read. **No new audit event and no new business metric** — it is a read surface, and an
  authenticated one (no blackbox probe; blackbox monitoring covers public surfaces only), so
  REQ-OBS is unaffected.
- Two deliberate, minor UX deltas versus the preload, both backend-safe: the transfer-destination
  combobox may list the current account (a same-account transfer is still rejected by the backend,
  REQ-BANK-006), and the grants filter / grant-create picker search **active** accounts only
  (creating or filtering by a closed account is not an intended flow).
- `sort=name,asc` (with the `id` tiebreaker) makes the management table's pages stable; the visible
  A→Z-by-name ordering is preserved by the server sort instead of a client re-sort.
- REQ-FE-011/ADR-0053's `remoteSource` mechanism is now proven for a **second** entity type
  (accounts), confirming the registry generalises beyond users; REQ-FE-017 records the account
  pattern.

## Alternatives considered

- **A dedicated `/api/v1/bank/accounts/search` endpoint (ADR-0089's user shape).** Justified there by
  a role-gate difference; here there is none, so it would only duplicate `getAccounts`. Rejected.
- **Leave the pickers preloaded but paginate only the table.** The pickers are exactly the surfaces
  that go unreachable past 500 (you cannot pick what is not shipped), so this fixes the least-visible
  half and leaves the booking/grant flows broken at scale. Rejected.
- **Keep the source-account picker a plain `<select>` to preserve its `data-requires-justification`
  option metadata.** That surface is unbounded on the dashboard/requests (every active account), so
  it must convert too; the `window.krtBankAccountMeta` map carries the mandate without a component
  change and keeps the combobox generic. Rejected leaving it unconverted.

