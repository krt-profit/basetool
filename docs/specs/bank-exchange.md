> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-11-09.
> **Owner area:** BXCH · **Related ADRs:** [ADR-0354](../adr/0354-bank-resources-on-the-exchange.md),
> [ADR-0216](../adr/0216-the-exchange-api-is-a-separate-contract-on-the-ingest-gateway.md),
> ADR-0352 (one explicit bank access policy)

# The Kartellbank on the exchange

## Context & goal

Members want to see their Kartellbank accounts and booking requests in a connected application such
as VerseKit, and raise a request from there, without opening the Basetool. The exchange
([`external-exchange.md`](external-exchange.md)) carries the bank as its own route family,
`/exchange/v1/bank/**` on the ingest gateway and `/api/v1/exchange/bank/**` on the backend
([ADR-0354](../adr/0354-bank-resources-on-the-exchange.md)).

This spec is the single home of the bank on the exchange. The bank itself, its accounts, ledger,
requests and staff decisions, stays in [`bank.md`](bank.md); the exchange's gateway, registry,
limits and error contract stay in [`external-exchange.md`](external-exchange.md) and
[`docs/exchange/errors.md`](../exchange/errors.md).

The guiding rules:

- **No money moves through a client, and no bank-staff action.** A client reads the member's bank
  data and raises, corrects or cancels the member's own requests; every staff decision stays in the
  Basetool.
- **The bank can be shut off on the exchange alone.** An admin switches the bank family off without
  stopping a client's other areas.
- **Nothing reveals whether an account exists or who is bank staff** beyond what the member may see
  in the Basetool.

## Requirements

### REQ-BXCH-001 — The bank scopes, their consent and the one decision point

> [!note] Implemented (#3157; ADR-0354, ADR-0352). The queue route came with REQ-BXCH-005 (#3160),
> the routes of `exchange.bank.request` with REQ-BXCH-004 (#3159).

**Three capabilities.** The bank has three scopes of its own (`ExchangeCapability`, the
`ck_exchange_client_capability` check, the provisioner's `EXCHANGE_SCOPES`, the sandbox and E2E
realms, the admin client page and the client application form):

| Scope | What a client may do | Consent line (DE) |
|---|---|---|
| `exchange.bank.read` | read the accounts the member may see in the Basetool, their bookings and balance series, and the member's own requests | „Die Bankkonten lesen, die du im Basetool sehen darfst, samt Verlauf, und deine eigenen Buchungsanträge" |
| `exchange.bank.request` | raise, correct and cancel the member's own booking requests | „Eigene Buchungsanträge stellen, korrigieren und stornieren; gebucht wird erst, wenn die Bank sie im Basetool bestätigt" |
| `exchange.bank.queue` | read, as bank staff, the open requests the member may confirm or approve | „Als Bankmitarbeiter die offenen Buchungsanträge lesen, die du bestätigen oder freigeben darfst, mit dem Handle des Antragstellers" |

The queue has a scope of its own, so a staff member's ordinary login carries no staff view. No
client is granted a bank scope until its approval amendment (REQ-BXCH-007).

**One decision point.** Every bank decision of the exchange is made by the bank access policy
(ADR-0352), never by exchange code:

- the actor is `BankActors.forExchange(member)`: the member's stored roles **without Admin**,
  their memberships and grants. An administrator acts through a client only by their bank roles,
  grants and memberships (REQ-XCH-009); seeing the whole bank needs Bank Management;
- the exchange passes no active-org-unit pin;
- exchange code reaches the bank only through `BankActors` with `BankActor`, the ports
  `BankAccountReads`, `BankOwnRequestReads`, `BankStaffQueueReads` and `BankOwnRequestWrites` with
  their value types, the `BankBookingRequestType` and `BankRequestApprover` they answer, and the
  refusal `BankOpenRequestCapException`; each port asks the policy itself
  (`BankAccessPolicyRulesTest.theExchangeReachesTheBankOnlyThroughThePolicy`).

No money moves through a client and no bank-staff action is taken through one (ADR-0354).

**Acceptance**

- [x] The three scopes exist in the registry, the realm, the consent screen (DE/EN), the admin
  client page and the application form; no client is granted them (`ExchangeRelaySeamParityTest`
  in backend and gateway, `provision-keycloak-realm.test.sh` section 13).
- [x] Every read of REQ-BXCH-002 answers as the web's characterisation matrix recorded it, with
  the Admin role stripped as the one expected difference for administrators and a closed account
  hidden (`BankExchangeParityTest`).
- [x] Exchange code using a bank type outside the actor adapter, the ports and their values fails
  the build (`BankAccessPolicyRulesTest`).

**Enforced by:** `ExchangeCapability`, `ExchangeBankReadService`, `BankActors#forExchange`,
`BankAccessPolicyRulesTest` · V364 · **Related:** REQ-XCH-005, REQ-XCH-009 · **Issues:** #3113,
#3114, #3122, #3157

### REQ-BXCH-002 — Accounts, bookings and balance series

> [!note] Implemented (#3157).

Three snapshot routes under `exchange.bank.read`, relayed from `/exchange/v1/bank/…` to
`/api/v1/exchange/bank/…`. No `since`, no ledger delta feed and no ETag: every call is decided anew,
so a withdrawn grant hides everything at once.

| Route | Paging and parameters |
|---|---|
| `GET /exchange/v1/bank/accounts` | `cursor` + `limit` (1…1000, default 500); the KRT account first, then the rest by account number |
| `GET /exchange/v1/bank/accounts/{id}/bookings` | newest first; `cursor` + `limit`; optional inclusive `from` / `to` (`date-time`) |
| `GET /exchange/v1/bank/accounts/{id}/balance-series` | not paged; `from` and `to` required, `from ≤ to`, at most 365 days, otherwise `400 SCHEMA_INVALID`; one point per UTC day |

**The accounts** are the active accounts the member views (REQ-BANK-064), each with `accountId`,
`accountNo`, `name`, `type`, `orgUnit { id, name, shorthand, kind }` or `null`, `balance`,
`balanceTarget` or `null`, `delta30d`, `sparkline` (30 end-of-day balances, oldest first, ending with
the current balance) and three facts about the member from the policy: `requestable` (may raise a
withdrawal or transfer here), `ownLimit` (the applicable limit above which an approval is needed)
or `null`, and `approvalExempt`, which tells a `null` limit of the responsible holder, who never
needs an approval, from one where every request needs it. `approvalExempt` is additive and not
required by the schema; the server always sends it. No holders, custody, limit tables, ceilings,
`version` or staff capabilities.

**The bookings** carry the member detail page's redaction (REQ-BANK-038): `postingId`,
`transactionId`, `type`, `amount` (this account's signed leg), `createdAt`, `note`, `justification`,
`reversedTransactionId`, `counterAccount { no, name }`, `intraAccount`, `transferFee`,
`counterpartyHandle` and `counterpartyOrgUnitName`, each `null` where it does not apply. No holder
handles and no staff note. `type` is any ledger type, `HOLDER_TRANSFER` included: the KRT account
carries the fee leg of every custody handover that bears a fee.

**The balance series** is `points [{ date, balance }]` and `balanceTarget` or `null`.

Money is a JSON integer of whole aUEC; the enumerations are UPPER_SNAKE. A page carries `removed: []`.
A cursor the server did not issue, or an account cursor whose account the member no longer views,
answers `410 CURSOR_EXPIRED`.

A `from` or `to` is an RFC 3339 `date-time` whose UTC instant lies in the years 0000 to 9999; any
other value, a signed or five-digit year included, answers `400 SCHEMA_INVALID` at the gateway.

**The shared `404`.** An unknown account, an account the member may not view and a closed account
answer the same `404 NOT_FOUND`, on the bookings and the balance series alike; the gateway passes
it on with a fixed detail. A malformed account id answers `400 SCHEMA_INVALID` at the gateway.

**Counted.** Every read served counts `basetool_exchange_bank_reads_total{client_id, route}`, every
`404` `basetool_exchange_bank_refused_total{reason="not_found"}` (REQ-OBS-024).

**Acceptance**

- [x] The accounts come KRT first, carry exactly the allow-listed fields with the three facts about
  the member, and page with an opaque cursor; a foreign cursor is `410`; the responsible holder's
  `null` limit comes with `approvalExempt = true` (`ExchangeBankReadsTest`).
- [x] The bookings come newest first with the redaction, page and are bounded by `from` / `to`
  (`ExchangeBankReadsTest`).
- [x] The KRT account's holder-transfer fee leg is served as `HOLDER_TRANSFER` within the published
  schema and relayed by the gateway (`ExchangeBankReadsTest`, `ExchangeGoldenAnswerTest`).
- [x] The balance series has one point per day and refuses a missing bound, an inverted period and
  more than 365 days (`ExchangeBankReadsTest`, `ExchangeBankRouteTest`).
- [x] A bound outside the four-digit years is refused before the relay (`BankQueryTest`,
  `ExchangeBankRouteTest`).
- [x] A hidden, a closed and an unknown account answer the same `404 NOT_FOUND`, counted
  (`ExchangeBankReadsTest`, `ExchangeBankRouteTest`).
- [x] Every answer validates against its published schema; the routes, schemas, fixtures and goldens
  are part of the frozen contract (`ExchangeBankReadsTest`, `ExchangeWireContractTest`,
  `ExchangeGoldenAnswerTest`, `ExchangeContractTest`).

**Enforced by:** `ExchangeBankController`, `ExchangeBankReadService`, `BankExchangeCursor`,
`BankAccountReadService` (backend) · `ExchangeBankController`, `BankQuery`,
`ExchangeRelayResponses`, `ExchangeRoutes` (gateway) · `bank-account`, `bank-booking`, `bank-balance-series` and
`page.schema.json#/$defs/bankAccountPage|bankBookingPage` · **Related:** REQ-BANK-038, REQ-BANK-049,
REQ-BANK-064, REQ-BANK-066, REQ-XCH-013 · **Issues:** #3118, #3157

### REQ-BXCH-003 — The member's own requests and their change feed

> [!note] Implemented (#3158). `client` names the connected application that raised the request
> since the request writes (REQ-BXCH-004, #3159).

`GET /exchange/v1/bank/requests` under `exchange.bank.read`, relayed to
`/api/v1/exchange/bank/requests`: every booking request the member raised, whichever channel raised
it — the web's requests too — as a snapshot followed by the change feed of REQ-XCH-013.

- **Snapshot.** `cursor` + `limit` (1…1000, default 500), in request-id order; the last page hands
  over a feed cursor at the position the snapshot was taken. The index
  `(requested_by, id)` (V366) serves every page in that order without a sort.
- **Feed.** A new `ExchangeResource` `BANK_REQUEST`, keyed by the requester, with the request id as
  its key. The `bank_booking_request` trigger sequences every insert, update and delete; a change
  of an account's number, name or owning unit, and of the owning unit's shorthand, re-announces the
  requests on and to that account. A feed page answers each changed request once with its current
  state, and a request that no longer exists as a tombstone in `removed`. A cursor past the horizon
  or not issued by the server answers `410 CURSOR_EXPIRED`.
- **Allow-list.** `id`, `version`, `status`, `createdAt`, `decidedAt`, `type`, `amount`, `note`,
  `justification`, `account { id, no, name, orgUnitShorthand }`, `targetAccount { id, no }`,
  `requiresOwnerApproval`, `requiredApprover`, `ownerApprovalGranted`, `rejectReason`, `client`
  (the registry id of the application that raised it, `null` for the Basetool), `splitEnabled`,
  `splitPercent`, each `null` where it does not apply. Money and the split percent
  are JSON integers. No staff note, no staff identities (requester, decider or approver handles), no
  counterparty, no purpose and no `callerMayConfirm`.
- **The cap.** Every page, snapshot and feed alike, carries `openRequestCap`, the configured
  `app.bank.exchange.open-request-cap` (REQ-BXCH-004); the client counts the `PENDING` items
  itself. The field is additive and not required by the schema, so a tolerant reader ignores it;
  the server always sends it.
- **Retention.** The member's own data: a client may persist it (REQ-BXCH-007).
- **Counted.** Every page served counts `basetool_exchange_bank_reads_total{client_id,
  route="requests"}` (REQ-OBS-024).

**Acceptance**

- [x] The snapshot lists every own request, the web-raised ones too, with exactly the allow-listed
  fields, and no other member's; it pages with an opaque cursor that ends at the feed; a foreign
  cursor is `410` (`ExchangeBankRequestsTest`).
- [x] A snapshot page is read in index order without a sort
  (`BankOwnRequestKeysetPlanIntegrationTest`).
- [x] A request raised, decided or removed after the snapshot follows in the feed, a removal as a
  tombstone; another member's request never does; a cursor past the horizon is `410`
  (`ExchangeBankRequestFeedTest`).
- [x] Snapshot and feed pages carry `openRequestCap` as the configured cap
  (`ExchangeBankRequestsTest`, `ExchangeBankRequestFeedTest`).
- [x] Every write of a request, a renamed account and a new unit shorthand are sequenced under the
  requester (`ExchangeChangeFeedTriggerIntegrationTest`).
- [x] Every answer validates against `page.schema.json#/$defs/bankRequestPage`; the route, schema,
  fixtures and golden are part of the frozen contract (`ExchangeBankRequestsTest`,
  `ExchangeBankRequestRouteTest`, `ExchangeGoldenAnswerTest`, `ExchangeContractTest`).

**Enforced by:** `ExchangeBankRequestController`, `ExchangeBankRequestService`,
`BankOwnRequestReadService` (backend) · `ExchangeBankRequestController`, `ExchangeRoutes`
(gateway) · `bank-request.schema.json`, `page.schema.json#/$defs/bankRequestPage` · V365 ·
**Related:** REQ-BANK-022, REQ-XCH-013, REQ-BXCH-007 · **Issues:** #3118, #3119, #3158

### REQ-BXCH-004 — Raising, correcting and cancelling the member's own requests

> [!note] Implemented (#3159).

Three write routes under `exchange.bank.request`, relayed from `/exchange/v1/bank/requests…` to
`/api/v1/exchange/bank/requests…`. Each is a write: the `Idempotency-Key` is mandatory
(REQ-XCH-020) and each call is one unit of the daily write quota (REQ-XCH-023).

| Route | Body | Answer |
|---|---|---|
| `POST /exchange/v1/bank/requests` | `type`, `sourceAccountId`, `targetAccountId` (transfer), `amount`, `note`, `justification`, `splitEnabled` / `splitPercent` (deposit) | `201 { id, status, version, amount }` |
| `POST /exchange/v1/bank/requests/{id}/correction` | `amount`, `note`, `justification`, `targetAccountId`, `version` | `200 { id, status, version, amount }` |
| `POST /exchange/v1/bank/requests/{id}/cancel` | `{ version }` | `200 { id, status, version }` |

- **The web's rules, through the bank policy.** `BankOwnRequestWrites` runs the web's bean
  validation (`CreateBankBookingRequest`, `UpdateBankBookingRequest`) and the web's raise and
  correction paths of `OrgUnitBankRequestService` for the actor `BankActors.forExchange` without a
  pin: approval routing (limits, KRT bands, exemption), the justification guard, self-transfer and
  re-routing on correction. The exchange adds no rule of its own. A deposit may target any active
  account (REQ-BANK-042); a withdrawal or transfer needs an account the policy admits.
- **Which requests.** Correction and cancel act on every own pending request, the web's included.
  A correction keeps the counterparty a web request names, with its stored org unit, even after the
  counterparty left that unit. A purpose-bound request keeps its
  amount and destination too: a correction that changes them answers `400 SCHEMA_INVALID`
  (REQ-BANK-056). A correction is routed as its raise was: a deposit never needs an approval. No
  counterparty, no purpose-bound and no KRT direct-booking request is raised through a client.
- **Open-request cap.** A member who holds `app.bank.exchange.open-request-cap` (20) `PENDING`
  requests, whichever channel raised them, gets `409 BANK_OPEN_REQUEST_CAP` for the next raise;
  the own requests answer the cap as `openRequestCap` (REQ-BXCH-003), so a client can tell first.
  The raise counts under a per-member transaction lock (`pg_advisory_xact_lock`), so two concurrent
  raises of one member never take them past the cap.
- **The explicit actor.** A raise is admitted for and stamped with the actor the exchange built,
  and correction and cancel check ownership for it, down every call; none reads who calls from the
  security context
  (`BankAccessPolicyRulesTest.theExchangeOwnRequestWritesDecideForTheExplicitActor`).
- **Origin.** A raised request records the registry client the exchange gate admitted in
  `bank_booking_request.client_id` (V366); the own requests (REQ-BXCH-003) and the staff queue
  (REQ-BXCH-005) answer it as `client`, the Basetool badges it (REQ-BANK-067). The column, the
  trail's `bank_audit_event.client_id` and both schemas' `client` hold up to 63 characters, the
  longest id the registry admits.
- **A retried raise raises nothing twice.** The gateway relays a raise's `Idempotency-Key` as
  `X-Exchange-Idempotency-Key`, and the request keeps it in `bank_booking_request.idempotency_key`
  (V366), unique per client, member and key. A raise under a key the client already raised a
  request of this member with answers that request with `201` and its current state, even when the
  gateway's answer cache missed it (a `5xx` after the commit is never cached) and even at the cap.
  The lookup runs under the member's open-request lock, so two retries never both insert.
- **Live.** Every applied raise, correction and cancel refreshes the staff queue, the org-unit bank
  and the request page in place, as the web's request forms do (REQ-XCH-030, REQ-BANK-068).
- **No exchange journal and no undo.** A request books nothing; a client cancels it instead. The
  trail is `bank_audit_event` with its `client_id` (ADR-0153).
- **Refusals.** `404 NOT_FOUND` for an unknown, closed or hidden account and for another member's
  request (the web answers `403` or `409 BANK_ACCOUNT_CLOSED` there); `400 SCHEMA_INVALID` for a
  body outside the schema or the bean rules (the backend's `CONSTRAINT_VIOLATION`) and for a
  missing or superfluous destination (`BAD_REQUEST`); `409 BANK_JUSTIFICATION_REQUIRED`,
  `BANK_SELF_TRANSFER`, `BANK_REQUEST_NOT_PENDING`, `BANK_REQUEST_ALREADY_APPROVED`,
  `BANK_OPEN_REQUEST_CAP` and `VERSION_CONFLICT` (the backend's `OPTIMISTIC_LOCK`); the gateway's
  own idempotency and quota codes.
- **Counted.** Every write counts `basetool_exchange_writes_total{client_id,
  resource="bank_request", outcome=applied|rejected}`, every shared `404`
  `basetool_exchange_bank_refused_total{reason="not_found"}` and every cap refusal
  `{reason="open_request_cap"}` (REQ-OBS-024).

**Acceptance**

- [x] A deposit on an account the member does not view is raised with the terse `201`; a
  withdrawal is routed as on the web; the request records the client, which the own requests and
  the staff queue answer (`ExchangeBankRequestWritesTest`).
- [x] A hidden, a closed and an unknown account, a closed destination and another member's request
  answer the same counted `404` (`ExchangeBankRequestWritesTest`).
- [x] The bank's refusals, the bean rules, a stale version and the cap at 20 pending requests answer
  as listed (`ExchangeBankRequestWritesTest`).
- [x] A correction keeps the stored counterparty and its org unit after the counterparty left the
  unit (`ExchangeBankRequestWritesTest`).
- [x] A raise racing the member's last free slot waits for the other raise and is refused with the
  cap (`BankOpenRequestCapConcurrencyTest`).
- [x] A raise retried under the same idempotency key answers the request raised first, also at the
  cap, and a new key raises a new request (`ExchangeBankRequestWritesTest`); the gateway relays the
  key on the raise only (`ExchangeBankRequestWriteRouteTest`, `ExchangeGoldenAnswerTest`).
- [x] A raise, correction or cancel path that reaches the security context fails the build
  (`BankAccessPolicyRulesTest`).
- [x] Every raise, correction and cancel case of the access matrix answers as the web recorded it,
  with `201` for a raise and the shared `404` for a refusal (`BankExchangeParityTest`).
- [x] The gateway checks the body and the request id before the relay, answers `201`, passes the
  bank's codes on and needs the scope; the routes, schema, fixtures and goldens are part of the
  frozen contract (`ExchangeBankRequestWriteRouteTest`, `ExchangeGoldenAnswerTest`,
  `ExchangeContractTest`, `ExchangeWireContractTest`).

**Enforced by:** `ExchangeBankRequestController`, `ExchangeBankRequestWriteService`,
`BankOwnRequestWriteService`, `OrgUnitBankRequestService` (backend) · `ExchangeBankRequestController`,
`ExchangeRoutes`, `ExchangeRelay` (gateway) ·
`bank-request-write.schema.json` · V366 · **Related:** REQ-BANK-022, REQ-BANK-039…042,
REQ-BANK-056, REQ-BANK-067, REQ-XCH-020, REQ-XCH-023 · **Issues:** #3113, #3119, #3121, #3159

### REQ-BXCH-005 — The read-only bank-staff queue

> [!note] Implemented (#3160). `client` names the application that raised a request since the
> request writes (REQ-BXCH-004, #3159).

`GET /exchange/v1/bank/queue` under `exchange.bank.queue`, relayed to `/api/v1/exchange/bank/queue`.
A snapshot, decided anew on every call by the bank access policy (`BankAccessPolicy#staffRights`,
read through `BankStaffQueueReads`) with the actor `BankActors.forExchange`: a withdrawn grant or
role hides everything on the next call, and the Admin role has no effect. The Bankleitung sees the
whole bank by Bank Management.

**Two sections, oldest first,** with the request id as the tiebreaker within one instant:

- **`open`** is the web staff queue's set (REQ-BANK-023): every pending request on an account the
  member holds a grant on, every pending request of the bank for the Bankleitung. Each item says in
  `mayConfirm` whether the member's grant covers the request type and four-eyes does not bar them
  (REQ-BANK-062); the member's own requests stay listed with `mayConfirm = false`.
- **`toApprove`** is exactly the set of the "approval needed" notices for the member (REQ-NOTIF-033):
  pending, over the limit, not yet approved in the Basetool, not raised by the member, and routed to
  an approver class the member belongs to — the responsible holder over the limit, the Bankleitung
  on the middle KRT band, the Organisationsleitung above `T2`. An attested confirm in the browser
  stays possible; the queue enforces nothing (REQ-BANK-041).

**Paging.** `cursor` and `limit` (1…500 per section, default 500; above that `400 SCHEMA_INVALID` at
the gateway). One opaque cursor carries the position of both sections; a cursor the server did not
issue answers `410 CURSOR_EXPIRED`. `hasMore` is true while either section continues.

**The answer.** `oldestOpenAt` (the oldest request in either whole section, or `null`),
`oldestOverdue` (true from 48 hours on, the threshold of `BankBookingApprovalOverdue`), `open`,
`toApprove`, `nextCursor`, `hasMore`. Each item carries `id`, `type`, `amount`, `createdAt`,
`ageHours`, `account { id, no, name, orgUnitShorthand }`, `targetAccount { id, no }` or `null`,
`requesterHandle`, `justification` and `note` as plain text, `requiresOwnerApproval`,
`requiredApprover` or `null`, `ownerApprovalGranted`, `mayConfirm`, `accountBalance`, `client` and
`link`:

- `requesterHandle` is `null` when the requester withholds their name (REQ-XCH-044);
- `accountBalance` is set for the Bankleitung on every account and for an employee on a granted
  account only, otherwise `null`;
- `link` is the absolute `https://profit-base.online/bank/requests/{id}`, the request's page
  (REQ-BANK-068); the server builds it, a client builds no links.

Left out: the staff note, the holder fields, custody balances and every handle but the requester's.

**Refusal.** A member with no bank role, no grant and no request waiting for their approval gets
`403 NOT_BANK_STAFF`, which the gateway passes on.

**Counted.** Every snapshot served counts `basetool_exchange_bank_reads_total{route="queue"}`, every
refusal `basetool_exchange_bank_refused_total{reason="not_bank_staff"}` (REQ-OBS-024).

**Retention.** Foreign bank data: a client keeps it in memory only, for at most 7 days (REQ-BXCH-007).

**Acceptance**

- [x] An employee sees the pending requests on their granted accounts with `mayConfirm` by grant
  flag, the Bankleitung the whole bank; an own request is listed with `mayConfirm = false` and never
  waits for its requester (`ExchangeBankQueueTest`).
- [x] `toApprove` holds the responsible holder's, the Bankleitung's middle-band and the OL's top-band
  requests, and nothing for anyone else; an approver without a bank role sees only that section,
  without balances (`ExchangeBankQueueTest`).
- [x] A member and an administrator without bank roles, grants or approvals get `403 NOT_BANK_STAFF`,
  counted (`ExchangeBankQueueTest`).
- [x] The item carries exactly the allow-listed fields, the link and `client` (`null` for a request
  raised in the Basetool); a withholding
  requester has no handle; the oldest request is overdue from 48 hours; both sections page with one
  cursor; a foreign cursor is `410` (`ExchangeBankQueueTest`).
- [x] The gateway checks the limit before the relay and the answer against `bank-queue.schema.json`;
  the route, schema, fixtures and golden are part of the frozen contract (`ExchangeBankRouteTest`,
  `ExchangeGoldenAnswerTest`, `ExchangeContractTest`, `ExchangeWireContractTest`).

**Enforced by:** `ExchangeBankQueueController`, `ExchangeBankQueueService`, `BankExchangeCursor`,
`BankStaffQueueReadService` (backend) · `ExchangeBankQueueController`, `ExchangeRoutes` (gateway) ·
`bank-queue.schema.json` · **Related:** REQ-BANK-023, REQ-BANK-041, REQ-BANK-062, REQ-BANK-068,
REQ-NOTIF-033, REQ-XCH-044 · **Issues:** #3120, #3116, #3160

### REQ-BXCH-006 — The bank switch, the limits and the refusal codes

> [!note] Implemented (#3156; ADR-0354). The bank routes themselves come with REQ-BXCH-002…005.

**The bank switch.** `exchange_settings.bank_enabled` is a second switch beside the global exchange
switch (REQ-XCH-003), with its own version counter `bank_version`, independent of the global one.

- It is **off by default**; the bank family goes live only when an admin switches it on.
- **Admins only** flip it, on the „Verbundene Apps" admin page, through
  `PUT /api/v1/connected-apps/admin/settings/bank {enabled, version}`; `GET` on the same path reads
  it. A stale `version` answers `409`. Flipping either switch never makes the other's version stale,
  and each keeps its own change time: the bank switch's in `bank_updated_at`, the global switch's in
  the row's `updated_at`, which a bank flip leaves alone.
- Switching off reaches the gateway's registry mirror **before** the commit or not at all, as the
  global switch does; switching on reaches it after the commit. The gateway reads the mirror through
  its five-second cache. A mirror document without the bank switch reads as off.

**The answer while it is off.** Every path at or below `/exchange/v1/bank` answers
`503 BANK_EXCHANGE_DISABLED` with `Retry-After: 300`, whether a route serves it or not; with the
switch on, a bank path no route serves is `404 NOT_FOUND`.

- Membership is judged on the **decoded** path segments, as routing matches them, so a
  percent-encoded spelling such as `/exchange/v1/b%61nk/accounts` is a bank path too; gateway and
  backend judge it alike.
- Both answers come before the daily write quota: a bank write the switch refuses, and a bank path
  no route serves, count no write.

- The global switch wins: while the exchange is off, a bank path answers `503 EXCHANGE_DISABLED`
  with `Retry-After: 30`.
- The backend's exchange gate re-checks the switch for every path at or below
  `/api/v1/exchange/bank`, with the same code and status (REQ-XCH-025), and the gateway passes that
  refusal on with `Retry-After: 300`.
- A client treats the answer as "wait, then retry" for the bank area only and keeps syncing its
  other areas.

**Audit and notice.** Every change of the bank switch writes `BANK_EXCHANGE_SWITCH_CHANGED` with
`{enabled}` in the „Verbundene Anwendungen" audit area (`CONNECTED_APPS`, REQ-AUDIT-001), and
publishes `BANK_EXCHANGE_SWITCHED_ON` or `BANK_EXCHANGE_SWITCHED_OFF`. The default rules notify the
admins and the Bankleitung (`ROLE ADMIN`, `ROLE BANK_MANAGEMENT`), never the acting admin; each
notice replaces the previous one, so a recipient sees the latest state only.

**Limits.**

- **Reads** use the exchange's per-minute buckets: 120 a minute per client and member, 1200 a
  minute per client (REQ-XCH-023).
- **Writes** count against the shared daily write quota per client and member (500, overridable per
  client), one unit per bank write; the bank has no quota share of its own.
- **Open-request cap.** A member holds at most `app.bank.exchange.open-request-cap` (default 20,
  validated between 1 and 1000) `PENDING` booking requests, whichever channel raised them; over it a
  client's next request answers `409 BANK_OPEN_REQUEST_CAP`.

**Refusal codes.** Besides the exchange's gate contract (`404 NOT_FOUND`, `403 SCOPE_MISSING`,
`403 CLIENT_SUSPENDED`, `401 CLIENT_REVOKED` / `INSTALLATION_REVOKED`, `429 RATE_LIMITED` /
`QUOTA_EXCEEDED`, `503 EXCHANGE_DISABLED`), the bank family adds exactly
`503 BANK_EXCHANGE_DISABLED`, `409 BANK_OPEN_REQUEST_CAP` and `403 NOT_BANK_STAFF`. A hidden, unknown
or closed account answers the same `404`.

**Acceptance**

- [x] The switch starts off, is set by an admin only and has its own version; a stale version is
  `409`, and neither switch stales the other nor moves its change time
  (`AdminExchangeRegistryControllerTest`, `ExchangeRegistryMirrorIntegrationTest`).
- [x] Switching the bank off reaches the mirror before the commit, switching on only after it
  (`ExchangeRegistryMirrorIntegrationTest`, `ExchangeRegistrySnapshotTest`).
- [x] While it is off, every bank path answers `503 BANK_EXCHANGE_DISABLED` with `Retry-After: 300`
  on the gateway and in the backend gate; the global switch wins; other routes and look-alike paths
  are untouched (ingest and backend `ExchangeGateTest`, `ExchangeRelayTest`).
- [x] A percent-encoded bank path is a bank path on both sides, and neither it nor a bank path no
  route serves counts a write (ingest `ExchangeGateTest`, `BankExchangeRoutesTest`).
- [x] Every change is audited and notifies the admins and the Bankleitung
  (`ExchangeRegistryNoticeTest`, `SeededNotificationRulesIntegrationTest`).
- [x] The open-request cap is 20 unless configured and refuses a value outside 1…1000
  (`BankExchangePropertiesTest`).
- [x] `409 BANK_OPEN_REQUEST_CAP` is answered by the request writes (REQ-BXCH-004,
  `ExchangeBankRequestWritesTest`).

**Enforced by:** `ExchangeGate`, `BankExchangeRoutes`, `ExchangeRegistryService#updateBankSwitch`,
`ExchangeRegistrySnapshot`, `BankExchangeProperties` (backend) · `ExchangeGateFilter`,
`ExchangeRoutes#isBank`, `ExchangeRelay` (gateway) · `ExchangeSeam.BANK_PATH`,
`ExchangeSeam.GATE_STATUSES` (shared seam) · V361 · **Related:** REQ-XCH-003, REQ-XCH-023,
REQ-XCH-025, REQ-AUDIT-001, REQ-OBS-024 · **Issues:** #3121, #3156

### REQ-BXCH-007 — Retention classes, the onboarding criterion and name withholding

> [!note] Implemented for the reads of REQ-BXCH-002 (#3157), the own requests of REQ-BXCH-003
> (#3158) and the staff queue of REQ-BXCH-005 (#3160).

**Retention classes.** Every bank route names its class in the gateway's OpenAPI document and in
[`docs/exchange/resources/bank.md`](../exchange/resources/bank.md):

- **Foreign bank data** — the accounts, bookings and balance series, and the staff queue: a client
  keeps it in memory only, for at most 7 days.
- **The member's own data** — the member's own requests: a client may persist them.

**Onboarding criterion.** A client that asks for a bank scope is approved only if it keeps foreign
bank data in memory only, for at most 7 days ([`docs/exchange/onboarding.md`](../exchange/onboarding.md)).
The approval names the criterion in `docs/legal/approved-clients.md`; that amendment is its own pull
request, after the bank switch, the cap and the monitoring.

**Name withholding.** A member who withholds their name from connected applications (REQ-XCH-044)
is left out of the bank answers: `counterpartyHandle` in a booking and `requesterHandle` in a queue
item are `null`; the booking and the request themselves stay.

**Privacy.** A11 „Connected applications" of the records of processing names the bank data on a
client and its legal bases, the DPIA threshold assessment is reassessed, and the privacy notice
„Verbundene Anwendungen" names the bank scopes.

**Acceptance**

- [x] The gateway's OpenAPI document and the client page name the retention class of every bank
  read route and of the staff queue.
- [x] A counterparty who withholds their name has no handle in a booking, and the booking is still
  listed (`ExchangeBankReadsTest`).
- [x] The onboarding criterion is part of `docs/exchange/onboarding.md`; A11, the DPIA threshold
  assessment and the privacy notice name the bank data.

**Related:** REQ-XCH-044, REQ-BXCH-002, REQ-BXCH-003 · **Issues:** #3122, #3157, #3158
