> **Doc type:** Living spec — requirements accepted by the owner, implementation pending (epic
> [#2078](https://github.com/krt-profit/basetool/issues/2078)). Last reviewed: 2026-09-26.
> **Owner area:** XCH · **Related ADRs:** [ADR-0216](../adr/0216-the-exchange-api-is-a-separate-contract-on-the-ingest-gateway.md),
> [ADR-0217](../adr/0217-third-party-clients-are-public-device-grant-clients-in-a-db-registry.md),
> [ADR-0218](../adr/0218-exchange-sync-semantics.md),
> [ADR-0219](../adr/0219-the-exchange-contract-grows-additively-under-a-path-major-version.md),
> [ADR-0220](../adr/0220-external-clients-see-only-anonymised-org-demand-and-the-location-list.md),
> [ADR-0221](../adr/0221-redis-grows-to-768-mb-and-holds-a-bounded-exchange-partition.md)

# External client exchange

## Context & goal

Approved external client software — VerseKit first, our own SC Extractor second — lets a member keep
blueprints, the personal part of the Lager and their ships in step between that program and the
Basetool, and read what their own units still need. It does so only through the **exchange API**
on the ingest gateway, never through the backend API. This spec states what must hold; the ADRs
above say why. The work is tracked in epic #2078; each requirement names the work package (WP) and
sub-issue that implements it.

> [!note] Status of every requirement below: **planned**
> Nothing of this spec is built yet. Each requirement carries its work package; its status line
> changes to *implemented* in the PR that lands it, together with its **Enforced by** test.
> Requirements of other specs that this one changes carry a *planned amendment* callout in their
> own spec until the change ships.

## Requirements

### REQ-XCH-001 — One exchange surface on the ingest gateway

The exchange API is served by the ingest gateway at `/exchange/v1/**`
(`https://ingest.profit-base.online/exchange/v1`). It offers exactly these routes:

| Method & path | Scope | Purpose |
| --- | --- | --- |
| `GET /exchange/v1` | `exchange.connect` | service document: API version, capabilities granted to this token, limits, deprecations, docs URL, minimum client version |
| `GET /exchange/v1/openapi.json` | anonymous | the committed OpenAPI 3.1 document, served as a static file |
| `GET /exchange/v1/schemas/<name>.schema.json` | anonymous | the committed JSON Schemas, served at their `$id` (REQ-XCH-011) |
| `POST /exchange/v1/me/installation` | `exchange.connect` | label this installation (REQ-XCH-007) |
| `POST /exchange/v1/me/account-check` | `exchange.connect` | RSI-handle check (REQ-XCH-031) |
| `POST /exchange/v1/catalog/resolve` | any exchange scope | resolve item references (REQ-XCH-012) |
| `GET /exchange/v1/catalog/locations` | any exchange scope | the Lager's non-hidden locations (REQ-XCH-018) |
| `GET /exchange/v1/me/blueprints` · `POST …/changes` | `exchange.blueprints.read` / `.write` | REQ-XCH-015 |
| `GET /exchange/v1/me/stock` · `POST …/changes` | `exchange.stock.read` / `.write` | REQ-XCH-016 |
| `GET /exchange/v1/me/ships` · `POST …/changes` | `exchange.hangar.read` / `.write` | REQ-XCH-017 |
| `GET /exchange/v1/me/org-demand` | `exchange.demand.read` | REQ-XCH-018 |
| `POST /exchange/v1/me/drafts/blueprints` · `…/drafts/refinery-orders` | `exchange.drafts.blueprints` / `.refinery` | REQ-XCH-019 |

Every route is deny-by-default, uses only `GET` or `POST` (the ingest bot filter admits nothing
else), and no route path collides with a prefix or suffix the bot filter blocks. The backend's
exchange layer (`/api/v1/exchange/**`) is reachable only from the gateway's service identity; the
member controls (`/api/v1/connected-apps/**`) only from the member's own browser session. Neither
is ever on the `api.*` allowlist (ADR-0135), and nothing of the exchange lives under `/api/v1/me/`.

**Acceptance**

- [ ] `IngestEndpointSurfaceTest` pins the exchange routes and methods; a test checks every route
  against `BotProtectionFilter`'s method, prefix and suffix lists.
- [ ] A test proves the gateway identity cannot reach `/api/v1/connected-apps/**`, and a browser
  session cannot reach `/api/v1/exchange/**`.
- [ ] The `api.*` allowlist test fails if an exchange or connected-apps path is added.

**Status:** planned — WP 3.2 (#2082), WP 3.1 (#2083)

### REQ-XCH-002 — A client is approved publicly, for capabilities, case by case

A client is approved by a public issue plus a PR that adds it to `docs/legal/approved-clients.md`
(created with WP 4.6); the merge is the approval. The
criteria are applied case by case (closed source possible): token storage per REQ-XCH-027, DPoP
per REQ-XCH-006, a published privacy statement and a security contact. Code signing of releases is
recommended, not required. A reported token-handling flaw must be fixed within **7 days**, or the
client is suspended. The list document sits outside the terms' consent hash, so list changes
need no new consent (REQ-SEC-028).

**Acceptance**

- [ ] `docs/legal/approved-clients.md` exists, is linked from the terms clause (REQ-SEC-027) and
  lists client id, product, maintainer contact, capabilities and the approval issue and PR.
- [ ] `docs/exchange/onboarding.md` states the criteria, the issue template and the fix deadline.
- [ ] The privacy notice (the frontend's `privacy.*` keys, DE and EN) states which data flows to an
  approved client on the member's own device, that the client's own privacy statement governs it
  there, and how to disconnect and undo; it changes with the go-live.

**Status:** planned — WP 4.6 (#2090), WP 6 (#2092)

### REQ-XCH-003 — The client registry lives in the backend database and is mirrored fail-closed

The registry (`exchange_client`: client id, display name, status `ACTIVE`/`SUSPENDED`, granted
capabilities, minimum client version, rate-limit overrides, contact URL, `version`) and the global
exchange switch are stored in the backend database and managed by `ADMIN` only. The backend
mirrors them into Redis under `exchange:*` with a version and a timestamp, rewrites the mirror on
startup and reconciles it every 60 s. Restrictive changes (suspend, capability removal, global
switch off, revocations) are written to Redis **before** the database commit and fail the action if
the mirror write fails; permissive changes are written **after** the commit. The gateway reads the
mirror through a cache of at most 5 s and refuses every exchange request when it cannot read it
(`503 REGISTRY_UNAVAILABLE`) or when the switch is off (`503 EXCHANGE_DISABLED`).

**Acceptance**

- [ ] Tests for both write orders, a failed mirror write, the reconcile healing a divergence, and
  the gateway's fail-closed read.
- [ ] Every registry change writes an audit event in „Verbundene Anwendungen" and fires the
  `ExchangeRegistryChanged` alert.

**Status:** planned — WP 3.1 (#2083), WP 3.2 (#2082), WP 4.5 (#2087)

### REQ-XCH-004 — Capabilities are OAuth scopes, enforced at the gateway and re-checked at the backend

The capabilities are `exchange.connect` (base: service document, installation label, account
check), `exchange.blueprints.read` / `.write`, `exchange.stock.read` / `.write`,
`exchange.hangar.read` / `.write`, `exchange.demand.read`, `exchange.drafts.blueprints` and
`exchange.drafts.refinery`. A request passes only if the route's scope is in the token **and**
granted to the client in the registry (`403 SCOPE_MISSING`); the backend's exchange layer checks
the relayed capabilities again (`@PreAuthorize("@exchangeGate.allows(…)")`). Each scope stamps the
`basetool-ingest` audience, and the gateway enforces that audience on exchange routes in code, not
only by property.

**Acceptance**

- [ ] Gate tests for a scope missing from the token, a scope not granted in the registry, and a
  wrong audience with the audience property blank.
- [ ] ArchUnit: every exchange controller method carries the exchange gate.

**Status:** planned — WP 2.2 (#2081), WP 3.1 (#2083), WP 3.2 (#2082)

### REQ-XCH-005 — Every third-party client is a public, consent-gated device-grant client

Each product has its own public Keycloak client: device grant only, `consentRequired`,
`fullScopeAllowed` off, no PII protocol mappers and none of the realm's default `profile`, `email`
or `roles` scopes, `exchange.connect`, `offline_access` and every capability scope optional, the
device code living 600 s at a pinned polling interval, and `dpop.bound.access.tokens` on. Clients
request `offline_access`, because a device login joins the member's browser SSO session and a web
logout would otherwise disconnect every client (owner decision 2026-09-26). Consent is shown in German, per capability. The consent and device
pages use the Basetool theme; the device page warns to enter only codes created on one's own PC.
The clients are created by `scripts/provision-keycloak-realm.py`, never by hand.

**Acceptance**

- [ ] The provisioner's self-test covers the third-party template and the SC Extractor's exchange
  scopes, and removes `extractor-ingest` from the extractor client only after its migration.
- [ ] The theme renders both pages with the phishing warning.
- [x] Keycloak 26.7.4's behaviour is observed (WP 0.4, 2026-09-26, a throwaway local Keycloak of the
  pinned image, owner decision to observe locally): a device login joins the browser SSO session
  (same `sid`); a web logout ends it and the next refresh fails `invalid_grant` unless the client
  holds an offline session; removing the consent removes the client from the session, or deletes
  its offline session, at once; an admin logout makes offline tokens stale; the device flow shows
  the consent page on every login, also when consent exists; access and refresh tokens carry
  `cnf.jkt`, and a refresh without a DPoP proof is refused.

**Status:** behaviour observed — WP 0.4; provisioning planned — WP 2.2 (#2081)

### REQ-XCH-006 — DPoP is required on every exchange route

A request to an exchange route without a valid DPoP proof bound to the token's `cnf.jkt` is refused
(`401 DPOP_REQUIRED` / `401 DPOP_INVALID`). The legacy `/v1/*` routes keep today's behaviour
(`REQ-INGEST-012`) until they end (REQ-XCH-033).

**Acceptance**

- [ ] Tests for a bearer token, a proof for another key, a replayed proof and a missing nonce.

**Status:** planned — WP 3.2 (#2082)

### REQ-XCH-007 — Installations are identified by their DPoP key and labelled by the client

An installation is one client on one PC, identified by the thumbprint of its DPoP key. A client
labels it with `POST /exchange/v1/me/installation {label}`. The label has at most 40 characters of
letters, digits, space, `-`, `_` and `.`, is never logged and never written to audit details, and
is always shown after the registered client name. The backend keeps `exchange_installation`
(client, member, thumbprint, label, first and last seen).

**Acceptance**

- [ ] Label validation tests, including control, bidi and homoglyph-only input.
- [ ] Log-capture test: the label never appears in any log line.

**Status:** planned — WP 3.2 (#2082), WP 3.3 (#2083)

### REQ-XCH-008 — Revocation takes effect on the next request

Disconnecting **one installation** puts its key thumbprint on a persistent deny list (database,
mirrored to Redis, kept at least as long as a client session can live); every token bound to that
key is refused (`401 INSTALLATION_REVOKED`) whatever its `iat`, and reconnecting needs a new key.
Disconnecting **a whole client** removes the member's Keycloak consent for it (for a first-party
client without consent: ends its client and offline sessions) and stores a revocation timestamp per (client,
member); a token issued before it is refused (`401 CLIENT_REVOKED`), and a new connection afterwards
works at once. When a member leaves the org (disabled, deleted, membership lost), their exchange
sessions and consents end — an admin logout, which also makes offline tokens stale — and
revocations are written at once, not at the next roster sync. The
gateway reads the deny list and the timestamps per request, bypassing its cache.

**Acceptance**

- [ ] A revoked installation is refused after a token refresh; another installation of the same
  client keeps working.
- [ ] A revoked client is refused, and a fresh connection right after works.
- [ ] A departed member is refused on the next request.

**Status:** planned — WP 3.1 / 3.3 (#2083), WP 3.2 (#2082), WP 4.5 (#2087)

### REQ-XCH-009 — The acting member holds a reduced authentication and sees own data only

On `/api/v1/exchange/**` the acting member holds an exchange role, the relayed capability
authorities and the memberships the demand feed needs — never their stored roles, permissions or
contextual grants. Exchange reads and writes touch only the member's own blueprints, own personal
Lager rows and own ships; they never use the admin all-scope or an admin pin. The membership,
pending-approval and terms gates apply unchanged. No exchange response carries personal data of
anyone.

**Acceptance**

- [ ] An `ADMIN` member reads and writes only own rows and holds no admin authority on exchange
  paths.
- [ ] ArchUnit: exchange services never call an admin-gated method; exchange controllers call
  exchange services only.

**Status:** planned — WP 3.1 (#2083)

### REQ-XCH-010 — The relay names the external client, and only the gateway may

The gateway relays under ADR-0129 (service account plus `X-Ingest-On-Behalf-Of`) and adds
`X-Exchange-Client` and `X-Exchange-Capabilities`. The backend honours both only from the
gateway's service identity and refuses and counts them from any other caller. The acting
authentication carries the external client, so audit rows and client metrics name it (for example
`versekit`) instead of `none`; the known-client vocabulary comes from the registry. This attribution
is live before the first registry entry exists.

**Acceptance**

- [ ] Forged-header tests from a browser session and from the app.
- [ ] An exchange write's audit row carries the external client id.

**Status:** planned — WP 3.1 (#2083)

### REQ-XCH-011 — The v1 data formats are published JSON Schemas

The formats are JSON Schema 2020-12 files. Their source is
`ingest/src/main/resources/exchange/v1/schemas/`; the gateway serves each one anonymously at its
permanent `$id`, `https://ingest.profit-base.online/exchange/v1/schemas/<name>.schema.json` (owner
decision 2026-09-26), and a `$id` is never changed once published. The schemas are:
`item-ref` (precedence `bt` › `scRecord` › `scGuid` › `uexId` › `locKey` › `name` + `nameLocale`),
`quantity` (`{amount, unit: SCU|PIECE}`, SCU ≤ 3 decimals, PIECE whole), `quality` (integer
0–1000; trade goods fixed 0), `location-ref`, `provenance` (`log|manual|import|default|other`,
`observedAt`), `material-kind` (`RAW|REFINED|NO_REFINE` plus `commodity`), `blueprint`, `stock-lot`
(material, location, quality, `stolen`, quantity — no org unit, no row id), `ship` (with required
`version`), `org-demand`, `location`, `installation`, `account-check`, `change-set` (at most 500
ops), `change-result` (compact, at most 32 KiB), `page`, `service-document`, `problem` and the
offline-file `envelope` (`format`, `formatVersion`, `generator`, `generatedAt`, `items`,
`extensions`; no handle, player, source folder or file path). One OpenAPI 3.1 document,
`ingest/src/main/resources/api/exchange-v1.openapi.json`, is authoritative for the exchange routes.

**Acceptance**

- [x] CI validates every conformance fixture in `docs/exchange/examples/v1/` against its schema, and
  every schema the OpenAPI document names exists and has valid and invalid fixtures.
- [ ] A test fails when a served route and the OpenAPI document diverge (with the routes, WP 3.2).

**Enforced by:** `ExchangeContractTest` · **Status:** schemas, OpenAPI document and fixtures
committed and validated — WP 0.2 (#2080); served by the gateway with WP 3.2 (#2082)

### REQ-XCH-012 — Names resolve through the web import's own matching

`catalog/resolve` answers `resolved`, `ambiguous` or `unmatched` per reference. `scRecord` is
compared case-insensitively with `blueprint.scwiki_key`, which is not unique; `scGuid` is matched
against blueprint records and output items, and duplicates resolve `ambiguous`. Names resolve
through `BlueprintImportService.resolve()` — REQ-INV-006, REQ-INV-019, REQ-INV-021, REQ-INV-050 —
never through a second logic. `locKey` resolves once the catalogue carries name keys and until then
falls through to the name with a warning. Places resolve against the Lager's `location` table; a
place without a row is `LOCATION_UNKNOWN`.

**Acceptance**

- [ ] The anonymised corpus fixture
  (`backend/src/test/resources/fixtures/blueprint-corpus/game-log-corpus-v1.json`) resolves the same
  through the exchange and through the web import.

**Status:** planned — WP 3.3 (#2083)

### REQ-XCH-013 — Each resource has a snapshot and a database-sequenced change feed

`GET /exchange/v1/me/<resource>` returns a snapshot or, with `cursor`, the changes since it. The
feed is sequenced at the database level, so writes that bypass the services — default-grant
provisioning, „delete all", admin purge, user deletion, org re-stamping, owner reassignment and a
change of the default blueprint set — appear in it. Removals leave tombstones with `removedBy`
(`web`, `app`, `client` with client id and installation id, `system`) and `removedAt`, kept 90 days
and purged nightly. A cursor older than the tombstones answers `410 CURSOR_EXPIRED`.

**Acceptance**

- [ ] A test fails for any write path to the synced tables that bypasses the sequence.
- [ ] A default-set change emits entries for every affected member.

**Status:** planned — WP 3.3 (#2083)

### REQ-XCH-014 — A client never re-adds what the member removed elsewhere

An `add` of an entry that has a live tombstone is refused per op with `REMOVED_ELSEWHERE`,
whichever installation or channel removed it. A client may send `override: true` only after asking
the member; the override is journaled.

**Acceptance**

- [ ] One installation removes, another tries to re-add: refused; with override: applied and
  journaled.

**Status:** planned — WP 3.3 (#2083), WP 4.1 (#2084)

### REQ-XCH-015 — Blueprints sync as a set

Ops are `add` and `remove` of products. Default-granted blueprints cannot be removed
(`DEFAULT_NOT_REMOVABLE`). A blueprint's `note` is read-only in v1. Writes are audited in the
Blueprints domain with the external client.

**Acceptance**

- [ ] Round trip: the corpus fixture added through the exchange appears in „Meine Blueprints" and
  in the feed of another installation.

**Status:** planned — WP 4.1 (#2084)

### REQ-XCH-016 — Stock syncs as lots, booked like the web

A lot is material + location + quality + stolen over the member's personal rows, across org-unit
pools. `set-quantity` carries `expectedQuantity`; the server compares under row locks and answers
`409 VERSION_CONFLICT` on a difference, otherwise books the delta in or out through the Lager's
services. Book-ins carry no org unit (REQ-ORG, own stamping path); book-outs take rows without a
unit first, then the oldest. Linked Materialbörse offers follow a book-out as in the web; the result
reports `offersReduced` and `offersRemoved`, and each change writes its `MARKET_*` audit event.
Trade goods are stored at quality 0. Writes are audited in the Lager domain with the external
client.

**Acceptance**

- [ ] Concurrent `set-quantity` on one lot: one applies, the other gets `VERSION_CONFLICT`.
- [ ] A book-out below an offered amount lowers the offer and records the audit event.

**Status:** planned — WP 4.2 (#2085)

### REQ-XCH-017 — Ships sync with a link step before the first create

`link` attaches a client ship to an existing server ship; `upsert` and `remove` carry the ship's
`version`. A client links before it creates, so a Fleetview import is never duplicated. Purchase
data is never sent. Detaching a ship from a mission by removal is reported in
`detachedFromMissions` and audited (`MISSION_UNIT_UPDATED`). Writes are audited in the Hangar domain
with the external client.

**Acceptance**

- [ ] First sync against a Fleetview-imported hangar creates no duplicate.

**Status:** planned — WP 4.4 (#2086)

### REQ-XCH-018 — Org demand is anonymised and membership-scoped; locations are the non-hidden list

`GET /exchange/v1/me/org-demand` lists the open demand of the units the member belongs to, never
units the member merely oversees or administers: `materials[]` (material, `rawRefs[]`, open
quantity per `minQuality`, `source: material-order|item-order`) and `items[]` (item, open quantity,
`craftableByMe`), plus `updatedAt`. It carries no requester, assignee, order title, free text or
per-order breakdown and no low-count suppression; a client may cache it for up to 7 days.
`GET /exchange/v1/catalog/locations` lists the non-hidden `location` rows with their UEX link.

**Acceptance**

- [ ] An overseer who is not a member of a unit does not see its demand.
- [ ] The response schema admits no name or free-text field.

**Status:** planned — WP 4.3 (#2095), WP 3.3 (#2083)

### REQ-XCH-019 — Drafts keep review-before-commit

`drafts/blueprints` and `drafts/refinery-orders` stage an upload for review in the browser, as
REQ-INGEST-004 requires today; nothing is written until the member confirms.

**Acceptance**

- [ ] The SC Extractor's draft flows pass unchanged through the exchange routes.

**Status:** planned — WP 3.2 (#2082), WP 5.1 (#2088)

### REQ-XCH-020 — Writes are idempotent per client and member

Every write carries an `Idempotency-Key` (`400 IDEMPOTENCY_KEY_MISSING`), kept 24 h and keyed per
(client, member, key). Authentication, gates and rate limits run before the lookup; only results
produced after them are cached — never `401`, `403`, `429`, `503`,
`MASS_CHANGE_CONFIRMATION_REQUIRED` or a `5xx`. A duplicate in flight gets
`409 IDEMPOTENCY_IN_PROGRESS`; a reused key with a different body `422 IDEMPOTENCY_KEY_REUSED`.

**Acceptance**

- [ ] Replay, cross-member key, in-flight duplicate, uncached `429`.

**Status:** planned — WP 3.2 (#2082)

### REQ-XCH-021 — Mass changes are confirmed by the member in the browser

Per client, member and resource over a rolling 24 h, a batch that takes the window above 25
removals, or above 20 % of (current count + entries removed in the window) with at least 5, is
staged and answered with `MASS_CHANGE_CONFIRMATION_REQUIRED` and a confirmation URL under
„Verbundene Anwendungen". Removals are `remove`, a quantity set to 0, a lot's reductions
accumulated to ≥ 90 % within the window, and a ship update that changes name and type; a move
within one batch is not a removal. Only the member's browser session can confirm.

**Acceptance**

- [ ] One test per counting rule, including repeated 89 % cuts and a move.

**Status:** planned — WP 3.3 (#2083), WP 3.2 (#2082), WP 4.5 (#2087)

### REQ-XCH-022 — Every exchange write is journaled and can be undone

Each exchange write is journaled for 90 days. The member can undo a client's writes since a point
in time from „Verbundene Anwendungen"; undo is version-checked, skips and reports rows the member
changed afterwards or a merge removed, and does not restore Materialbörse offers.

**Acceptance**

- [ ] Undo after a later web edit skips that row and reports it.

**Status:** planned — WP 3.3 (#2083), WP 4.5 (#2087)

### REQ-XCH-023 — Rate limits, quotas and a hard Redis budget

Per-minute buckets per (client, member) and per client run in-process; daily write quotas live in
Redis (`ingest:xch:quota:*`). All gateway-written exchange data in Redis is bounded to 1 MiB per
client and member, 16 MB per client and 64 MB in total, counted exactly; above a limit the gateway
answers `503 EXCHANGE_BUDGET_EXHAUSTED`. A batch holds at most 500 ops (`413 BATCH_TOO_LARGE`).
Responses carry `RateLimit` and `Retry-After` headers. The account check has its own tight limit.

**Acceptance**

- [ ] A load test fills one member's budget, then one client's; sessions and other members keep
  working.

**Status:** planned — WP 3.2 (#2082), WP 2.1 (#2092)

### REQ-XCH-024 — A minimum client version can be enforced

The registry holds a minimum version per client. A request whose `User-Agent`
(`<Product>/<semver> (+url)`) names an older version is refused with
`403 CLIENT_VERSION_UNSUPPORTED`. The gate is cooperative: it stops honest old releases, not a
client that lies.

**Status:** planned — WP 3.2 (#2082)

### REQ-XCH-025 — Errors are problem+json with a stable code

Every error is RFC 9457 problem+json with a `code` from the registry in `docs/exchange/errors.md`,
each with its HTTP status and the client action it requires. Codes are never reused or repurposed;
the gateway-side codes are the `reason` labels of the exchange metrics.

**Enforced by:** `ExchangeContractTest` (the registry's codes are unique and carry error
statuses) · **Status:** registry published — WP 0.2 (#2080); the metric labels follow with the
gateway, WP 3.2 (#2082)

### REQ-XCH-026 — The contract grows additively under `/exchange/v1`

Within `v1` only additive changes are allowed; a breaking change is `v2`, served in parallel for at
least 12 months with `Deprecation` and `Sunset` headers. Readers are tolerant both ways (unknown
fields ignored and reported as `warnings`, unknown enum values `UNKNOWN`), published schemas stay
open, extensions are namespaced, identifiers and cursors are opaque (ADR-0219).

**Acceptance**

- [x] A contract test fails a change that removes or narrows anything in a v1 schema: CI copies
  the latest release's schemas to `ingest/build/exchange-baseline/` and
  `ExchangeContractTest.theSchemasOnlyGrewSinceThePreviousRelease` compares them with
  `SchemaCompatibility`, whose rules `SchemaCompatibilityTest` pins. Until a release carries the
  v1 schemas the comparison has nothing to compare and is skipped.

**Enforced by:** `ExchangeContractTest`, `SchemaCompatibilityTest` · **Status:** implemented —
WP 0.2 (#2080)

### REQ-XCH-027 — Approved clients meet the client security requirements

A client stores tokens only in the platform's secret store (Windows Credential Manager / DPAPI;
Linux Secret Service, with a `0600` file fallback and a visible hint), keeps the DPoP private key
non-exportable where the platform allows, never writes a token into logs, backups, diagnostics or a
problem-report channel, pins the production issuer and allows another only through a developer
environment variable, and sends a descriptive `User-Agent`. The checklist is
`docs/exchange/client-security.md`.

**Status:** planned — WP 4.6 (#2090), WP 5.1 (#2088), WP 5.2 (#2089)

### REQ-XCH-028 — The exchange is observable per client

The gateway and the backend export per-client request, error, write and budget metrics under
bounded labels (REQ-OBS-011), alert on a registry change, on 80 % of the Redis budget and on error
spikes per client, and a blackbox probe checks `GET /exchange/v1` for `401`. The nightly purge of
tombstones and journal reports task metrics.

**Status:** planned — every WP, tracked by #2091

### REQ-XCH-029 — Third parties get a local sandbox

Public sandbox images and a `sandbox` compose profile run the ingest gateway, the backend and a
Keycloak realm with a test client and seeded data on a developer's machine. The sandbox issuer is
`http://host.docker.internal:18080/auth/realms/iri`. No production credential or artefact enters it.

**Status:** planned — WP 2.3 (#2099)

### REQ-XCH-030 — Exchange writes appear live

After each committed exchange write the backend publishes live-sync frames on the topics and
sections the web pages and the app listen on (Lager, Blueprints, Hangar, and the Materialbörse when
offers changed).

**Status:** planned — WP 3.3 (#2083)

### REQ-XCH-031 — The account check answers match, mismatch or unknown — never the handle

`POST /exchange/v1/me/account-check {handle}` compares the handle with the optional RSI handle on
the member's profile (REQ-SEC-072, stored since WP 1.4), case-insensitively, and answers `match`, `mismatch` or `unknown` (no handle
stored). It never returns or logs the stored handle and is rate-limited tightly.

**Status:** planned — WP 3.4 (#2106)

### REQ-XCH-032 — „Verbundene Anwendungen" shows and controls every connection

The web page „Verbundene Anwendungen" lists the member's connected clients with their capabilities,
installations (label, first and last seen) and recent activity, and lets the member disconnect one
installation or a whole client, undo, and confirm a staged mass change. Every new connection or
installation raises a notification and stays highlighted until seen. `ADMIN` manages the registry
on an admin page with a suspend switch. The page is web-only; the app links to it.

**Status:** planned — WP 4.5 (#2087)

### REQ-XCH-033 — The legacy extractor endpoints end at the go-live

Behind `app.ingest.legacy-endpoints.enabled` (default `true`), `/v1/refinery-extract` and
`/v1/blueprint-preview` answer `410 LEGACY_ENDPOINT_GONE` with a German update hint once the flag is
`false` at the go-live. While it is `true` their behaviour is unchanged.

**Status:** planned — WP 3.2 (#2082), WP 6 (#2092)

## Threat model

| Threat | Countered by |
| --- | --- |
| Stolen refresh or access token | DPoP binding of both (REQ-XCH-005/-006); tokens only in the platform secret store (REQ-XCH-027) |
| Device-code phishing (RFC 8628 §5.4) | themed device page warning, notification and highlight of every new connection, 600 s code lifespan (REQ-XCH-005/-032) — countered, not prevented |
| A revoked installation refreshing its way back | persistent `jkt` deny list (REQ-XCH-008) |
| A member who leaves keeping access | departure revocations (REQ-XCH-008) |
| Malicious client update, compromised maintainer account | capability scoping, own-data-only, journal and undo, guard, suspension; signing recommended (REQ-XCH-002/-009/-021/-022) — accepted residual risk |
| Compromised admin account (no capability ceiling) | audit area „Verbundene Anwendungen", `ExchangeRegistryChanged` alert, suspension — accepted risk (ADR-0217) |
| An admin's client running with admin authority | reduced exchange authentication and ArchUnit rule (REQ-XCH-009) |
| Confused deputy on the relay hop; forged `X-Exchange-*` headers | headers honoured only from the gateway identity; explicit relay route list (REQ-XCH-010, REQ-XCH-001) |
| Replay and cross-member idempotency replay | idempotency keyed per client and member, gates before cache (REQ-XCH-020) |
| Enumeration through resolve or account check | resolve returns catalogue data only; account check never returns the handle and is tightly limited (REQ-XCH-012/-031) |
| DoS against Redis (shared with sessions, `noeviction`) or the backend | hard byte budgets, quotas, batch cap, larger Redis (REQ-XCH-023, ADR-0221) |
| Guard evasion by batching, near-zero cuts or overwriting updates | window counting rules (REQ-XCH-021) |
| Silent removal of Materialbörse offers by a sync book-out | reported and audited, not undoable — accepted (REQ-XCH-016/-022) |
| The version gate bypassed by a manipulated client | cooperative by design — accepted (REQ-XCH-024) |
| Data poisoning of org-wide views | own personal rows only, validated through the domain services (REQ-XCH-009/-016) |
| Token leakage via backups, diagnostics or a problem-report webhook | client security requirements (REQ-XCH-027) |
| A switchable issuer used for phishing | only through a developer environment variable, never in the UI (REQ-XCH-027) |
| The installation label as a spoofing channel or PC-name leak | length and character limits, always after the client name, never logged or audited (REQ-XCH-007) |

## Out of scope

- The backend API for the web and the app ([`api-conventions.md`](api-conventions.md)).
- Recipes, mining, shops, selling and salvage data — reference data on both sides; only identifiers
  must agree.
- A client's own data that has no Basetool counterpart (VerseKit's mission log, wish list, overlay
  settings).

## Open questions

None; every decision of epic #2078 §8 is taken.
