> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-11-08.
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

### REQ-BXCH-006 — The bank switch, the limits and the refusal codes

> [!note] Implemented (#3156; ADR-0354). The bank routes themselves come with REQ-BXCH-002…005.

**The bank switch.** `exchange_settings.bank_enabled` is a second switch beside the global exchange
switch (REQ-XCH-003), with its own version counter `bank_version`, independent of the global one.

- It is **off by default**; the bank family goes live only when an admin switches it on.
- **Admins only** flip it, on the „Verbundene Apps" admin page, through
  `PUT /api/v1/connected-apps/admin/settings/bank {enabled, version}`; `GET` on the same path reads
  it. A stale `version` answers `409`. Flipping either switch never makes the other's version stale.
- Switching off reaches the gateway's registry mirror **before** the commit or not at all, as the
  global switch does; switching on reaches it after the commit. The gateway reads the mirror through
  its five-second cache. A mirror document without the bank switch reads as off.

**The answer while it is off.** Every path at or below `/exchange/v1/bank` answers
`503 BANK_EXCHANGE_DISABLED` with `Retry-After: 300`, whether a route serves it or not; with the
switch on, a bank path no route serves is `404 NOT_FOUND`.

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
  `409`, and neither switch stales the other (`AdminExchangeRegistryControllerTest`,
  `ExchangeRegistryMirrorIntegrationTest`).
- [x] Switching the bank off reaches the mirror before the commit, switching on only after it
  (`ExchangeRegistryMirrorIntegrationTest`, `ExchangeRegistrySnapshotTest`).
- [x] While it is off, every bank path answers `503 BANK_EXCHANGE_DISABLED` with `Retry-After: 300`
  on the gateway and in the backend gate; the global switch wins; other routes and look-alike paths
  are untouched (ingest and backend `ExchangeGateTest`, `ExchangeRelayTest`).
- [x] Every change is audited and notifies the admins and the Bankleitung
  (`ExchangeRegistryNoticeTest`, `SeededNotificationRulesIntegrationTest`).
- [x] The open-request cap is 20 unless configured and refuses a value outside 1…1000
  (`BankExchangePropertiesTest`).
- [ ] `409 BANK_OPEN_REQUEST_CAP` is answered by the request writes (REQ-BXCH-004).

**Enforced by:** `ExchangeGate`, `BankExchangeRoutes`, `ExchangeRegistryService#updateBankSwitch`,
`ExchangeRegistrySnapshot`, `BankExchangeProperties` (backend) · `ExchangeGateFilter`,
`ExchangeRoutes#isBank`, `ExchangeRelay` (gateway) · `ExchangeSeam.BANK_PATH`,
`ExchangeSeam.GATE_STATUSES` (shared seam) · V361 · **Related:** REQ-XCH-003, REQ-XCH-023,
REQ-XCH-025, REQ-AUDIT-001, REQ-OBS-024 · **Issues:** #3121, #3156
