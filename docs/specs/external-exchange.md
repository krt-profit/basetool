> **Doc type:** Living spec — requirements accepted by the owner, built except where a status line
> says otherwise (epic [#2078](https://github.com/krt-profit/basetool/issues/2078)). Last reviewed:
> 2026-10-03.
> **Owner area:** XCH · **Related ADRs:** [ADR-0216](../adr/0216-the-exchange-api-is-a-separate-contract-on-the-ingest-gateway.md),
> [ADR-0217](../adr/0217-third-party-clients-are-public-device-grant-clients-in-a-db-registry.md),
> [ADR-0218](../adr/0218-exchange-sync-semantics.md),
> [ADR-0219](../adr/0219-the-exchange-contract-grows-additively-under-a-path-major-version.md),
> [ADR-0220](../adr/0220-external-clients-see-only-anonymised-org-demand-and-the-location-list.md),
> [ADR-0221](../adr/0221-redis-grows-to-768-mb-and-holds-a-bounded-exchange-partition.md)

# External client exchange

## Context & goal

Approved external client software — VerseKit first, our own SC Extractor second — lets a member keep
blueprints, their stock in the Lager and their ships in step between that program and the
Basetool, and read what their own units still need. It does so only through the **exchange API**
on the ingest gateway, never through the backend API. This spec states what must hold; the ADRs
above say why. The work is tracked in epic #2078; each requirement names the work package (WP) and
sub-issue that implements it.

> [!note] Each requirement's own status line is authoritative
> Each requirement carries its work package; its status line says what is built and what remains,
> and moves in the PR that lands the change, together with its **Enforced by** test. What remains
> is chiefly the clients' migrations (WP 5.1, #2088; WP 5.2, #2089), the app (#2097) and the go-live
> (WP 6, #2092). Requirements of other specs that this one changes state it in their own text; a
> callout there marks only what is still planned. *Corrected 2026-09-27: this note said nothing was
> built yet, long after most of the requirements had landed.* *Corrected 2026-09-28: it still named
> the sandbox (WP 2.3, #2099) as open after #2099 had closed; the end-to-end runs the requirements
> had deferred to it — an installation revoke, a reconnect, a departure, the account check, the
> corpus round trip, a confirmed mass change and an admin suspension — are now built on the E2E
> stack (`ExchangeConnectionsE2eTest`, `ExchangeSyncE2eTest`, `ExchangeDepartureE2eTest`).*

## Requirements

### REQ-XCH-001 — One exchange surface on the ingest gateway

The exchange API is served by the ingest gateway at `/exchange/v1/**`
(`https://ingest.profit-base.online/exchange/v1`). It offers exactly these routes:

| Method & path | Scope | Purpose |
| --- | --- | --- |
| `GET /exchange/v1` | `exchange.connect` | service document: API version, capabilities granted to this token, limits, deprecations, docs URL (the documentation site, `app.exchange.docs-url`, default `https://krt-profit.github.io/basetool/`), minimum client version |
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

- [x] `IngestEndpointSurfaceTest` pins the exchange routes and methods; a test checks every route
  against `BotProtectionFilter`'s method, prefix and suffix lists (`ExchangeRouteBotCompatibilityTest`,
  every route of the committed OpenAPI document and every schema URL).
- [x] A test proves the gateway identity cannot reach `/api/v1/connected-apps/**`, and a browser
  session cannot reach `/api/v1/exchange/**` (`ConnectedAppsControllerTest`: the gateway and the app
  are refused; `ExchangeCatalogControllerTest`: an `ADMIN` browser session is refused, and so is the
  gateway without an acting member).
- [x] The `api.*` allowlist test fails if an exchange or connected-apps path is added
  (`ExternalContractTest.theExchangeStaysOffTheApiVhost`). A line that touches `krt_api_allowed` in a
  form the test cannot evaluate — another variable, a negation, an unquoted or multi-line rule —
  fails it instead of being skipped; `~*` counts as case-insensitive (security review G5, I6).

**Status:** built — WP 3.2 (#2082), WP 3.1 (#2083)

### REQ-XCH-002 — A client is approved publicly, for capabilities, case by case

A client is approved by a public issue plus a PR that adds it to `docs/legal/approved-clients.md`
(created with WP 4.6); the merge is the approval. The
criteria are applied case by case (closed source possible): token storage per REQ-XCH-027, DPoP
per REQ-XCH-006, a published privacy statement and a security contact. Code signing of releases is
recommended, not required. A reported token-handling flaw must be fixed within **7 days**, or the
client is suspended. The list document sits outside the terms' consent hash, so list changes
need no new consent (REQ-SEC-028).

**Acceptance**

- [x] `docs/legal/approved-clients.md` exists, is linked from the terms clause (REQ-SEC-027) and
  lists client id, product, maintainer contact, capabilities and the approval issue and PR.
  *The terms clause links it since the go-live (2026-09-28, #2092), and its first entry is the SC
  Extractor, `basetool-sc-extractor`, from 2.10.0 with `connect`, `drafts.*` and `blueprints.*`
  (owner decision 2026-09-28: the web interface and the Android app are part of the platform, every
  other program is listed). It records the approved capabilities, not the registry's runtime state.*
- [x] `docs/exchange/onboarding.md` states the criteria, the issue template and the fix deadline.
  *The template is `.github/ISSUE_TEMPLATE/exchange-client-application.yml`.*
- [x] The privacy notice (the frontend's `privacy.*` keys, DE and EN) states which data flows to an
  approved client on the member's own device, that the client's own privacy statement governs it
  there, and how to disconnect and undo. *Section „Verbundene Anwendungen" (`privacy.h2_3_10`,
  `privacy.p_3_10_1`–`_5`) since 2026-09-28, with the retention of disconnected installations and
  client revocations (90 days after the disconnection) and the extractor's direct sync in
  `privacy.p_3_7_1`; `PrivacyControllerTest#rendersTheConnectedApplicationsSection`. The records
  under `docs/privacy/` carry it as A11.*

The third-party pages are `docs/exchange/`, published on GitHub Pages at
`https://krt-profit.github.io/basetool/` for developers only, and entirely in English — no German at
all (owner decision 2026-09-27): Basetool pages and controls go by the English web app's names
(„Verbundene Anwendungen" is *Connected applications*, the Lager the *warehouse*, the Materialbörse
the *Material Exchange*), and German test data stays only in the conformance fixtures under
`examples/`. `.github/workflows/exchange-docs.yml`, on every change to the pages, the OpenAPI
document or the schemas, checks that each relative link stays on the site and resolves — an anchor
to one of the target page's headings, with the id kramdown's GFM parser gives it — and that no
umlaut, sharp s or German low quotation mark appears in the site's sources, the OpenAPI document or
the schemas outside those fixtures (`check_exchange_docs_links.py`), lints the Markdown, renders the OpenAPI document into a
static reference from the committed schemas (`prepare_exchange_reference.py` and the Redoc bundle of
a pinned `redoc` release, checked against its npm integrity), copies the document and the schemas
beside it and builds the site with Jekyll, whose edit links point at `docs/exchange/` on `main`; a pull request builds, only
`main` deploys, and only the deploy job holds `pages: write` and `id-token: write`.

The site wears the DAS KARTELL design system (REQ-UI-001, REQ-UI-003, REQ-UI-019), not a stock
Jekyll theme: its own layouts under `docs/exchange/_layouts/` put every page — the generated schema
index and the reference included — under one header with the Basetool mark and links to the docs,
the reference and the repository, a section navigation from `docs/exchange/_data/navigation.yml`
(a sidebar on wide screens, a „Contents" drawer from 1024 px down), heading anchors and scrollable
tables, and a footer with „Improve this page" (hidden on generated pages), the licence, the security
policy and the issue tracker. It is dark-only and loads nothing from another origin: the Lato WOFF2
files and the `basetool-*` marks are copied from the frontend at build time, so the repository holds
one copy of each. The reference is a Jekyll page (`layout: reference`) whose Redoc theme is built at
runtime from the stylesheet's design tokens (`docs/exchange/assets/js/reference.js`).
**One owner-approved deviation from REQ-UI-004** (@greluc, 2026-09-27): code blocks (`pre`) and
Redoc's JSON and code samples use the generic system `monospace` (the `--font-code` token), because
column-aligned code does not read in a proportional face; inline code and everything else stay
Lato. `check_exchange_docs_links.py` also fails a navigation entry whose page does not exist, and the
preparation script's self-test fails when the reference layout stops loading the bundle or the theme
reads a token the stylesheet does not define.

**Status:** the list, the onboarding page, the application template and the documentation site with
its overview, formats, errors, versioning, changelog and authentication pages, and the MIT-licensed
DPoP reference `docs/exchange/dpop-reference/` (stdlib Python, CNG and OpenSSL 3 through `ctypes`,
its tests run by `exchange-docs.yml`), the resource pages, the sync guide, the sandbox page and the
quick start are built — WP 4.6 (#2090), WP 2.3 (#2099); the terms link, the SC Extractor's list
entry and the privacy notice — WP 6 (#2092), in force from 2026-09-28

### REQ-XCH-003 — The client registry lives in the backend database and is mirrored fail-closed

The registry (`exchange_client`: client id, display name, status `ACTIVE`/`SUSPENDED`, granted
capabilities, minimum client version, rate-limit overrides, contact URL, `version`) and the global
exchange switch are stored in the backend database and managed by `ADMIN` only. The backend
mirrors them into Redis under `exchange:*` with a version and a timestamp, rewrites the mirror on
startup and reconciles it every 60 s. Restrictive changes (suspend, capability removal, global
switch off, revocations) are written to Redis **before** the database commit and fail the action if
the mirror write fails; permissive changes are written **after** the commit. The gateway reads the
mirror through a cache of at most 5 s and refuses every exchange request when it cannot read it or
the revocations (`503 REGISTRY_UNAVAILABLE`) or when the switch is off (`503 EXCHANGE_DISABLED`),
each with `Retry-After: 30`. Within that cache's five seconds the gateway may still admit a request
for a client just suspended, unregistered or cut off by the switch; the backend's `@exchangeGate`
then refuses it with the **same** code and status the gateway would have answered
(`403 CLIENT_SUSPENDED`, `403 CLIENT_NOT_ALLOWED`, `503 EXCHANGE_DISABLED`), and the gateway relays
it unchanged (REQ-XCH-025), so a client never sees one situation under two codes. *Corrected
2026-09-28: the backend answered these as a generic `403`, which reached the client as
`NOT_PERMITTED` (E2E run 36388920243).*

**How the backend keeps the mirror** (WP 3.1). The tables are `exchange_client`,
`exchange_client_capability` and the single-row `exchange_settings` (`V248`); the switch starts
**off**. The mirror is **one JSON document** under `exchange:registry`:
`{schemaVersion: 1, revision, writtenAt, enabled, clients: {<clientId>: {displayName, status,
capabilities[], minClientVersion, requestsPerMinute, writesPerDay}}}`, the capabilities as their
scope strings, sorted. `revision` comes from the database sequence `exchange_registry_revision_seq`,
so it grows across restarts. Every registry change and every mirror write first takes the row lock
on `exchange_settings`, so a mirror write never overtakes an open change. A change that takes
access away writes the **restrictive combination** of before and after (switch on only if on in
both, a client suspended in either is suspended, capabilities intersected, a new client left out)
before its commit; a failed write fails the change with `502` and changes nothing. After the
transaction completes — committed or rolled back — the committed state is written again; a failure
there is counted and left to the reconcile, which compares the content (not `revision` and
`writtenAt`) at startup and every 60 s (`app.exchange.mirror.reconcile-interval`) and rewrites a
differing, missing or unreadable document. The mirror is written only while
`APP_EXCHANGE_MIRROR_ENABLED=true`. While it is off nothing is mirrored, and a start switches off a
document an earlier run left behind (`ExchangeRegistryMirrorClosure`, once when the application is
ready): the document has no expiry, so it would otherwise keep admitting clients. It is rewritten
with the same clients, `enabled: false` and a new revision, through the backend user's own `GET` and
`SET`, so the gateway refuses every exchange request `503 EXCHANGE_DISABLED` — or `503
REGISTRY_UNAVAILABLE` when there never was a document. A failed attempt is counted as
`basetool_exchange_mirror_writes_total{phase="switched_off",outcome="failed"}`
(`ExchangeMirrorWriteFailed`) and does not stop the start. A document's age is no signal here: the
backend rewrites it only when the registry changes, so `writtenAt` can be days old on a healthy
mirror, and the gateway's `basetool_exchange_registry_mirror_age_seconds` measures its last
successful read instead. The backend's own gate needs none of this: it reads the switch, the
client, the installation and the client revocations from the database (REQ-XCH-008). *Corrected
2026-09-28 (security review G5, L2): this said the gateway refuses every exchange request while
mirroring is off, which held only until a document had once been written.*

**What a registry entry may hold** (security review 2, L6). The display name reaches Keycloak's
consent page, the member page and the connection notification, so it is Latin letters, ASCII digits,
the plain space and the punctuation `.,:;!?'&()+/_-`, starts with a letter or digit and is already in
NFKC form — no control, format (`Cf`) or bidi character, no foreign-script look-alike, no full-width
letters — and it never contains „Basetool", compared without case, accents, spaces or punctuation
(`400`, „Der Anzeigename darf nicht „Basetool" enthalten …"). `requestsPerMinute` is at most **1200**
and `writesPerDay` at most **5000**, ten times the gateway defaults of 120 and 500 (REQ-XCH-023); the
admin form carries the same bounds. A client id equal to one of the Basetool's own clients, as the
backend is configured with them — the ingest gateway (`app.security.ingest-gateway.client-ids`), the
web login (`app.exchange.connected-apps.web-client-ids`, `app.exchange.change-source.web-client-ids`),
the app (`app.exchange.change-source.app-client-ids`, `app.security.partial-role-scope.client-ids`) and
the backend's Keycloak admin client (`app.keycloak.sync.client-id`) — is refused `400` (the
provisioner reserves the same ids). The SC Extractor's `basetool-sc-extractor` is deliberately not
reserved: it joins the exchange as a registry client of its own at the go-live.

**Acceptance**

- [x] The display-name rules, the limit bounds and the first-party ids are refused with a localized
  `detail` (`ExchangeDisplayNamesTest`, `FirstPartyClientIdsTest`,
  `AdminExchangeRegistryControllerTest`).
- [x] Tests for both write orders, a failed mirror write, the reconcile healing a divergence, and
  the gateway's fail-closed read. *Backend: `ExchangeRegistryMirrorIntegrationTest` against a real
  Redis under the backend's ACL user, and `ExchangeRegistrySnapshotTest` (WP 3.1). Gateway:
  `ExchangeRegistryReaderTest` — a missing document, an unknown `schemaVersion`, garbage and an
  unreachable Redis all fail closed, a failed read is not cached — and `ExchangeGateTest`, which
  answers them `503 REGISTRY_UNAVAILABLE` with `Retry-After` (WP 3.2).*
- [x] A start with mirroring off switches off a document left behind, keeping its clients, under
  the backend's ACL user; a missing or already switched-off document is left alone, and a refused
  read is counted without failing the start. *`ExchangeRegistryMirrorClosureTest` (security review
  G5, L2).*
- [x] Every registry change writes an audit event in „Verbundene Anwendungen" and fires the
  `ExchangeRegistryChanged` alert.
- [x] The admin page *Administration → Verbundene Anwendungen* (`/admin/exchange-clients`)
  registers, edits, suspends and activates clients and flips the switch in place; suspending, either
  direction of the switch and granting a client more capabilities each ask for confirmation first.
  *`AdminExchangeClientsPageControllerMvcTest`, `AdminExchangeClientsE2eTest`; that the gateway
  follows — `403 CLIENT_SUSPENDED` after a suspension on the page, answered again after the
  reactivation — `ExchangeConnectionsE2eTest.anAdminSuspensionAndReactivationReachTheGateway`,
  which requires the first refusal and every refusal until the reactivation reaches the gateway
  to be `403 CLIENT_SUSPENDED`, whichever side gave it.*
- [x] The backend's gate refuses a suspended or unregistered client and a switched-off exchange
  with the gateway's code and status, and the gateway relays them unchanged. *Backend
  `ExchangeGateTest`, `ExchangeCatalogControllerTest`; gateway `ExchangeRelayTest`,
  `ExchangeControllerTest`.*
- [x] Each client shows its connected members and last activity, counted over live installations
  only (`GET /api/v1/admin/exchange-clients/usage`: not revoked, and not seen last before the
  member disconnected the client); the error rate per client is linked in Grafana
  (`APP_GRAFANA_OPERATIONS_DASHBOARD_URL`, owner decision 2026-09-27). *`AdminExchangeClientUsageTest`,
  `AdminExchangeClientsPageControllerMvcTest`.*

**Enforced by:** `ExchangeRegistryMirrorIntegrationTest`, `ExchangeRegistryMirrorClosureTest`,
`ExchangeRegistrySnapshotTest`,
`AdminExchangeRegistryControllerTest`, `AdminExchangeClientsE2eTest`, `ExchangeConnectionsE2eTest`,
`RedisAclBackendIntegrationTest`,
`RedisAclIngestIntegrationTest`, `monitoring/prometheus/tests/exchange_registry_alerts_test.yml` ·
**Code:** `ExchangeRegistryService`, `ExchangeRegistryMirrorSync`, `RedisExchangeRegistryMirror`,
`ExchangeRegistryReconcileTask`, `AdminExchangeRegistryController` · **Status:** registry, admin
API and mirror built — WP 3.1 (#2083); the gateway's read — `ExchangeRegistryReader`, a
five-second cache (`app.exchange.registry-cache-ttl`, validated to lie between 0 and 5 s so a
configuration cannot stretch a suspension's delay, `ExchangeGatewayPropertiesTest`) of the
`app.exchange.registry-key` document — built with WP 3.2 (#2082); the admin page built — WP 4.5 (#2087)

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

- [x] Gate tests for a scope missing from the token, a scope not granted in the registry, and a
  wrong audience with the audience property blank. *The gateway requires `aud` ∋ `basetool-ingest`
  on exchange routes in code (`ExchangeDpopGateTest`); a route passes only when its capability is
  both in the token and granted in the registry, and a route for "any exchange scope" needs at
  least one such capability (`ExchangeGateTest`). `ExchangeRoutes` holds the route table,
  `ExchangeRoutesContractTest` pins it to the committed OpenAPI document route for route and scope
  for scope, and a path or method it does not list is `404 NOT_FOUND`.*
- [x] ArchUnit: every exchange controller method carries the exchange gate
  (`ArchitectureTest.everyExchangeControllerMethodCarriesTheExchangeGate`).

**How the backend checks** (WP 3.1). `@exchangeGate.allows('<scope>', authentication)` — or
`allowsAny(authentication)` for a route any exchange scope serves — passes only an acting member
relayed for an external client whose authorities hold `ROLE_EXCHANGE_MEMBER`, while the global
switch is on, the client is in the registry and `ACTIVE`, neither the installation nor — after the
token was issued — the client is disconnected (REQ-XCH-008), and the scope was both relayed (the
`XCH_CAPABILITY:<scope>` authority) and granted to the client. Every refusal is counted as
`basetool_exchange_gate_refused_total{reason}`. A relayed request it refuses is answered as an
`ExchangeProblemException` with the code and status the gateway's `ExchangeGateFilter` gives the
same situation — `503 EXCHANGE_DISABLED`, `403 CLIENT_NOT_ALLOWED`, `403 CLIENT_SUSPENDED`,
`401 INSTALLATION_REVOKED`, `401 CLIENT_REVOKED`, `403 SCOPE_MISSING`, and `503
REGISTRY_UNAVAILABLE` for revocations it cannot read (REQ-XCH-025); only a caller that is not a
relayed acting member gets the generic `403`.

**Status:** built — the scopes (WP 2.2, #2081), the registry's per-client grants
(`ExchangeCapability`, WP 3.1) and the backend's `ExchangeGate` (WP 3.1, #2083), and the gateway
check (`ExchangeGateFilter`, WP 3.2, #2082)

### REQ-XCH-005 — Every third-party client is a public, consent-gated device-grant client

Each product has its own public Keycloak client: device grant only, `consentRequired`,
`fullScopeAllowed` off, no PII protocol mappers and none of the realm's default `profile`, `email`
or `roles` scopes, `exchange.connect`, `offline_access` and every capability scope optional, the
device code living 600 s at a pinned polling interval, and `dpop.bound.access.tokens` on. Clients
request `offline_access`, because a device login joins the member's browser SSO session and a web
logout would otherwise disconnect every client (owner decision 2026-09-26). Every session of such a
client, offline or online, ends after 30 days without use and 90 days at the latest: the provisioner
pins `client.offline.session.*` and `client.session.*` on the client, so a client that omits
`offline_access` does not inherit the realm's 180-day SSO session (owner decision 2026-09-28,
ADR-0217 amendment). An offline token needs the member to hold the `offline_access` realm role
within the client's scope, and these clients have `fullScopeAllowed` off: the realm's default role
`default-roles-iri` therefore carries `offline_access` as a composite, and the `offline_access`
client scope maps the role, both converged by the provisioner (owner decision 2026-09-28, ADR-0202
amendment 5). Without the composite the device grant's token request answers `400 not_allowed`.
Of the first-party clients only `basetool-sc-extractor` is offered `offline_access`; the provisioner
withholds it from the others and from the realm's default client scopes, so the realm-wide role
opens offline sessions to the exchange clients alone (owner decision 2026-09-28).
Consent is shown in German, per capability. The consent and device
pages use the Basetool theme; the device page warns to enter only codes created on one's own PC.
A `verification_uri_complete` link skips the device page, so for a device login the consent page
carries the same warning and shows the user code for the member to compare with the one on their PC,
and clients show only the bare `verification_uri` (REQ-XCH-027; owner decision 2026-09-27, security
review 2 of #2092, M1). The page after a successful device login does not end the member's way:
it offers „Zum Basetool" to the public origin and „Tab schließen", and when the browser refuses to
close a tab no script opened, it says to close it by hand (owner decision 2026-09-28). The clients
are created by `scripts/provision-keycloak-realm.py`, never by hand.

The first-party SC Extractor is held to the same shape once it has migrated (security finding H1,
owner decision 2026-09-27): its client `basetool-sc-extractor` requires consent, binds access and
refresh tokens to DPoP, carries only `basic` by default and offers only its exchange scopes and
`offline_access`. It loses both ingest scopes, so no extractor token carries `aud=basetool-backend`
any more; before, a phished device code yielded an unbound, refreshable bearer token the backend API
accepted. The provisioner applied this on production only **after** the legacy switch-off
(REQ-XCH-033), because released extractors up to 2.9.1 still needed `extractor-ingest-only` on
`/v1/*`; the go-live did it at S15, and the `/v1` routes are removed since (2026-09-28).

**Acceptance**

- [x] The provisioner's self-test covers the third-party template (withheld scopes removed from an
  existing client too, 30/90-day offline session, owner decision 2026-09-26) and the SC Extractor's
  exchange scopes (`scripts/provision-keycloak-realm.test.sh`, sections 13–15). The extractor
  requests `offline_access` too and gets the same 30/90-day offline session pinned on its client
  (owner decision 2026-09-27). Both clients' online sessions are pinned at 30/90 days as well, and
  section 13 fails without the pin (owner decision 2026-09-28).
- [x] Every member can be issued an offline token by these clients: the provisioner plans, applies
  and verifies `offline_access` as a composite of `default-roles-iri` and as the role mapped on the
  `offline_access` client scope, and sends neither write to a realm holding both (self-test section
  18, which fails without it; owner decision 2026-09-28, ADR-0202 amendment 5). *Production lacked
  the composite until the owner added it by hand on 2026-09-28, after SC Extractor 2.10.0's sign-in
  was refused `400 not_allowed`; the scope mapping was already there.*
- [x] Neither `basetool-sc-extractor` nor a third-party client keeps a client-level protocol mapper:
  the provisioner plans and applies the removal of any it finds, so the verify pass cannot pass with
  a hand-added `basetool-backend` audience mapper in place (owner decision 2026-09-28, G5-L4 of
  #2092, ADR-0202 amendment 4; self-test section 17).
- [x] The extractor client loses `extractor-ingest` once the extractor has migrated (WP 5.1 / go-live).
  *`basetool-sc-extractor` requires consent, has DPoP-bound tokens, only `basic` by default and
  withholds both ingest scopes and every non-exchange scope; section 16 of the self-test converges a
  client in the former production shape to it. Applied on production with the go-live's provisioner
  run after the legacy switch-off (S15, #2092, 2026-09-28).*
- [x] The theme renders the device page with the phishing warning
  (`login-oauth2-device-verify-user-code.ftl`). *Corrected 2026-09-27:* this item said „both pages",
  but `login-oauth-grant.ftl` carries no warning, and a `verification_uri_complete` link goes
  straight to it (security review 2 of #2092, M1).
- [x] The success page of a device login offers „Zum Basetool" (`krtHomeUrl`, `/`) and „Tab
  schließen" (`info.ftl`, `js/krt-close-tab.js`, no inline handler); the button stays hidden without
  script, and the hand-close hint appears only when the tab is still open after the click. The E2E
  device login asserts the link, the visible button and the hidden hint on every exchange connection.
- [x] For a device login the consent page shows the phishing warning and the user code, asserted on
  both pages by a theme test. *Keycloak 26.7.4 hands the consent page no user code
  (`OAuthGrantBean` holds the session code, the client and the scopes), so `keycloak-spi`'s login
  forms provider `krt-freemarker` adds it as `krtDeviceUserCode` (ADR-0228,
  `DeviceConsentLoginFormsProviderTest`); `scripts/sandbox-smoke.py` asserts the warning on both
  pages and the code on the consent page against the sandbox Keycloak image, and the same run was
  made through a `verification_uri_complete` link on 2026-09-27.* The E2E device login
  (`ExchangeE2eSupport.approveOnTheDevicePage`) opens the bare `verification_uri`, types the
  `user_code` as a member does, and asserts `#krt-device-phishing-warning` on the code page and
  `#krt-device-consent-warning` with exactly that code in `#krt-device-user-code` on the consent
  page, on every exchange E2E connection (G5-I5 of #2092).
- [x] The consent page's intro claims only what the page lists („Die Anwendung erhält nur die unten
  aufgeführten Rechte."), never that the application does not learn name, e-mail or roles, which
  the template cannot know for every client (owner decision 2026-09-28, G5-L1 of #2092, ADR-0228
  amendment 1).
- [x] The client documentation tells clients to show the bare `verification_uri` with the
  `user_code` and never `verification_uri_complete` (`docs/exchange/authentication.md`,
  `client-security.md`, `quickstart.md`, the application template).
- [x] Keycloak 26.7.4's behaviour is observed (WP 0.4, 2026-09-26, a throwaway local Keycloak of the
  pinned image, owner decision to observe locally): a device login joins the browser SSO session
  (same `sid`); a web logout ends it and the next refresh fails `invalid_grant` unless the client
  holds an offline session; removing the consent removes the client from the session, or deletes
  its offline session, at once; an admin logout makes offline tokens stale; the device flow shows
  the consent page on every login, also when consent exists; access and refresh tokens carry
  `cnf.jkt`, and a refresh without a DPoP proof is refused.

**Status:** behaviour observed — WP 0.4; template, scopes and theme pages — WP 2.2 (#2081); the extractor's `extractor-ingest` removal — provisioner and extractor built (#2201, basetool-sc-extractor #69–#71), production apply with WP 6 (#2092); the consent page's warning and user code — built (#2092, M1, ADR-0228)

### REQ-XCH-006 — DPoP is required on every exchange route

A request to an exchange route without a valid DPoP proof bound to the token's `cnf.jkt` is refused
(`401 DPOP_REQUIRED` / `401 DPOP_INVALID`). *(The legacy `/v1/*` routes, which accepted a bearer
token too, are removed since 2026-09-28, REQ-XCH-033.)*

Spring's proof verifier checks `htm`, `htu`, `iat` (30 s skew), the binding to `cnf.jkt`, `ath` and
a replayed `jti`. On exchange routes the gateway also requires a **server nonce** (RFC 9449 §8): a
proof without a current one is answered `401 DPOP_INVALID` with `WWW-Authenticate: DPoP …,
error="use_dpop_nonce"` and a fresh `DPoP-Nonce`, and the client retries once with it. Every answer
past the authentication filter carries the current nonce (`ExchangeTokenGateFilter`, and
`SecurityProblemResponseHandler` for a refused token or proof); the answers written before it — the
bot filter's, the per-IP `429 RATE_LIMITED`, `413 PAYLOAD_TOO_LARGE` and the identity provider's
`503` — and the anonymous contract documents carry none. The nonce check runs after Spring's proof
decoder has parsed the proof and verified its header and signature, and before every claim check: a
proof with a wrong `typ`, an unsupported `alg`, a missing or private `jwk` or a bad signature is
`invalid_dpop_proof` without a challenge. *Corrected 2026-09-28: this paragraph said every exchange
response carried the nonce, and the developer docs said the challenge came before every proof check;
the code has behaved as described here since the nonce was built.* A nonce is stateless — a
five-minute window and its HMAC under a key drawn at startup — and holds for its window and the
next; a restart invalidates them all, which costs a client one retry. A bearer-scheme request, or a
token without `cnf.jkt`, is `401 DPOP_REQUIRED` with the DPoP challenge.

**Which proofs need the nonce.** Only a proof whose target has a readable path outside `/exchange` —
since 2026-09-28 a path the gateway does not serve — skips it; an unparseable target, a target without a path and `/exchange`
itself count as exchange routes (fail closed). A proof without the nonce is refused before the replay
check, so it takes no room in the `jti` cache.

**The `jti` replay cache is partitioned.** Spring's default is one in-memory cache per process of
100 000 entries, shared with the legacy routes, which a hundred member tokens proofing a thousand
times a minute could fill so that every DPoP request was refused (security review 2, L10). The
gateway instead keeps one `DpopProofReplayStore` for the exchange routes and one for every other
path (`path_scope="other"`; `legacy` until the `/v1` routes were removed on 2026-09-28), and inside each counts the live proofs per member (the access token's `sub`; a token without
one by its proof key). A member holds at most `app.exchange.limits.dpop-proofs-per-member` (**600**)
live proofs, a store at most `app.exchange.limits.dpop-proofs-total` (**100 000**). A proof is kept
until its `iat` plus 30 s, so a client at the default 120 requests a minute holds about 60 at once
and 600 covers several clients of one member; filling a store takes more than 160 members at their
cap. A registry `requestsPerMinute` far above the default may need a larger per-member cap.

**The cap has its own answer** (owner decision 2026-09-27). On an exchange route a member over its
cap gets `429 DPOP_PROOF_LIMIT` with `Retry-After`: the whole seconds until the member's earliest
live proof no longer counts, rounded up and at least 1. Before checking the cap, the store drops
that member's expired proofs, so the answer is exact and does not wait for the ten-second sweep.
The refused proof takes no room. The seam: the store's member view remembers why it refused a
proof, and `ExchangeDpopProofValidation.capRefusals` turns Spring's generic replay error into a
`DpopProofLimitError` when that reason is the cap; `SecurityProblemResponseHandler` finds it in the
cause chain.

**A full store has its own answer too** (owner decision 2026-09-28). On an exchange route a proof
refused because the store holds its total cap gets `503 SERVICE_UNAVAILABLE` with `Retry-After`:
the whole seconds until the store's earliest live proof no longer counts, rounded up and at least 1,
read after a sweep of the expired proofs. The refused proof takes no room. It travels the same seam
as a `DpopProofStoreFullError`. A replayed proof stays `401 DPOP_INVALID` with
`error="invalid_dpop_proof"`; every other path, which the exchange error registry does not govern,
keeps that answer for both caps. Refusals are counted as
`basetool_ingest_dpop_replay_refused_total{path_scope,reason}` (`replayed`, `member_cap`, `full`)
and shown on the Exchange and operations dashboards; `IngestDpopReplayCacheFull` fires on any
`full`. The auth-failure counter records the exchange cap as `dpop_proof_limit` and the full store
as `dpop_store_full`, apart from `invalid_dpop_proof`, so `ExchangeDpopProofsFailing` fires on
neither. Sustained cap refusals raise `ExchangeDpopProofLimitSustained` (warning, owner decision
2026-09-28): more than 3 a minute over 10 minutes, for 15 minutes, which a single burst cannot
reach.

**Acceptance**

- [x] Tests for a bearer token, an unbound token, a proof for another key, a replayed proof and a
  missing nonce, and that the retry with the nonce passes (`ExchangeDpopGateTest`,
  `ExchangeDpopNoncesTest`).
- [x] A member at the cap is refused while another member and the other path scope still pass; a
  proof without the nonce stores nothing; an unreadable or pathless target needs the nonce
  (`DpopProofReplayStoreTest`).
- [x] Through the whole gateway, a member over the cap gets `429 DPOP_PROOF_LIMIT` with
  `Retry-After`, a replayed proof still gets `401 DPOP_INVALID`, and another member still passes
  (`ExchangeDpopMemberCapTest`); the store answers the seconds until the member's earliest proof
  expires, drops the member's expired proofs before the cap check and takes no room for a refused
  proof (`DpopProofReplayStoreTest`).
- [x] A full store answers the seconds until its earliest proof expires and takes no room, the
  verifier reports it as `DpopProofStoreFullError` while a replay stays `invalid_dpop_proof`
  (`DpopProofReplayStoreTest`), and the entry point writes `503 SERVICE_UNAVAILABLE` with that
  `Retry-After`, the nonce and no challenge (`SecurityProblemResponseHandlerTest`).

**Enforced by:** `ExchangeDpopGateTest`, `DpopProofReplayStoreTest`, `ExchangeDpopMemberCapTest`, `SecurityProblemResponseHandlerTest` · **Status:** built — WP 3.2
(#2082); the partitioned replay cache and the fail-closed nonce scope — security review 2 (#2092)

### REQ-XCH-007 — Installations are identified by their DPoP key and labelled by the client

An installation is one client on one PC, identified by the thumbprint of its DPoP key. A client
labels it with `POST /exchange/v1/me/installation {label}`. The label has at most 40 characters of
letters, digits, space, `-`, `_` and `.`, is never logged and never written to audit details, and
is always shown after the registered client name. The backend keeps `exchange_installation`
(client, member, thumbprint, label, first and last seen) and gives each installation an opaque id —
never the thumbprint — which the installation response and the service document return, so a
client recognises its own removals in a tombstone's `removedBy.installationId` (asked by the VerseKit
author, owner decision 2026-09-26).

**How it is built** (WP 3.1). The gateway relays the verified key thumbprint as
`X-Exchange-Installation` (honoured like the other relay headers; an exchange call without a
well-formed one is refused as `exchange_installation_invalid`). The backend creates
`exchange_installation` on first sight, moves `last_seen_at` forward at most every five minutes after
each admitted exchange request, and serves `GET` / `POST /api/v1/exchange/me/installation` (the
opaque `installationId`, never the thumbprint). Leading and trailing spaces and controls are trimmed
by the global JSON normalisation; what is left must match the schema's rule after NFC. Homoglyph-only
labels are letters and are accepted: the label is always shown after the registered client name.

**Acceptance**

- [x] Label validation tests, including control, bidi and homoglyph-only input
  (`ExchangeInstallationControllerTest`).
- [x] Log-capture test: the label never appears in any log line (`ExchangeInstallationControllerTest`).
- [x] The installation response and the service document carry the same `installationId`, and a
  tombstone written by that installation names it. *The gateway half is in: `POST
  /exchange/v1/me/installation` checks the label against `installation.schema.json` before the relay
  (a rule-breaking label never reaches the backend), and the service document takes
  `installationId` from the backend's installation of the relayed key (`ExchangeControllerTest`).*
  *The backend keeps the id across first sight and labelling
  (`ExchangeInstallationControllerTest.theInstallationIsCreatedOnFirstSightAndKeepsItsIdWhenLabelled`),
  the service document names it
  (`ExchangeControllerTest.theServiceDocumentNamesTheGrantsLimitsAndInstallation`), and a tombstone's
  `removedBy.installationId` is the removing installation's id
  (`ExchangeBlueprintControllerTest.theFeedAnswersAnAdditionAndATombstoneNamingTheRemovingInstallation`).
  Ticked 2026-09-28.*

**Status:** built — gateway routes WP 3.2 (#2082), the backend's installations WP 3.1 (#2083),
tombstones WP 3.3 (#2083)

### REQ-XCH-008 — Revocation takes effect on the next request

Disconnecting **one installation** puts its key thumbprint on a persistent deny list (database,
mirrored to Redis, kept 90 days — longer than any session of an exchange client can live, online or
offline, since both are capped at 90 days, REQ-XCH-005, ADR-0217 amendments); every token bound to that
key is refused (`401 INSTALLATION_REVOKED`) whatever its `iat`, and reconnecting needs a new key.
Disconnecting **a whole client** removes the member's Keycloak consent for it — which ends its
offline sessions and, for a client with consent, its online sessions — deletes the member's online
sessions that hold only that client, ends the client inside the sessions it shares with other
clients without signing the member out of those, and **then** stores a revocation timestamp per
(client, member),
read after Keycloak answered. A token of an earlier connection is refused (`401 CLIENT_REVOKED`): an
offline token (scope `offline_access`) issued at or before the timestamp, and any other token whose
`auth_time` — the sign-in it descends from, which a refresh keeps — is at or before it, or which
carries no `auth_time`. A new connection afterwards works at once — the member's disconnect
answers only once the backend clock has passed the revocation's second, so a connection started
after it carries a later token time (ADR-0217 amendment of 2026-09-28) — and one without `offline_access`
needs a sign-in after the disconnect, because a device login that joins an older browser session
keeps that session's `auth_time`. When a member leaves the org (disabled, deleted, membership lost), their exchange
sessions and consents end — an admin logout, which also makes offline tokens stale — and then
revocations are written at once, not at the next roster sync. The
gateway reads the deny list and the timestamps per request, bypassing its cache. *Corrected
2026-09-28: the deny list was said to be kept „as long as a client session can live", but only the
offline session was capped at 90 days; the online session of a client without `offline_access` could
live the realm's 180 days, so a denied key could be refreshed past its 90-day mirror entry.*

**How it is built** (WP 3.1). A revoked installation row is the deny-list entry for its key; a
member's disconnect of a whole client is a row in `exchange_client_revocation` (V249). Both reach the
Redis mirror before the commit — `exchange:deny:<thumbprint>` and
`exchange:revoked:<clientId>:<member>`, each holding the revocation's epoch second and expiring 90 days
after it — and a failed write fails the disconnect with `502`. Disconnecting a client first removes
the member's Keycloak consent for it, which revokes its offline tokens (Keycloak does so with or
without a consent, and ends the client's online sessions only when a consent existed), then deletes
every online session of the member whose only client it is. A session the client shares with
another, such as the member's web login, cannot be ended alone through the stock Admin API, so the
`keycloak-spi` adds (ADR-0226) the admin extension `DELETE /admin/realms/{realm}/basetool-exchange/users/{id}/
clients/{client}/sessions`: it detaches the client from every online session of the member, which
fails its refresh tokens, and revokes its offline sessions, while the member's other clients stay
signed in; it needs `manage-users` over the member, which `backend-service` already holds. A
Keycloak without the extension answers `404`, which the backend logs and passes over, leaving the
shared sessions to the gateway's `auth_time` check. Any other Keycloak failure fails the disconnect
with `502` before anything
is written, and the timestamp is read only after Keycloak answered, so no token refreshed in between
carries a later `iat`. The 60-second reconcile writes
back any enforced entry the mirror lacks. The backend's `@exchangeGate` refuses a revoked installation
itself (`installation_revoked`), and re-checks the client revocation the way the gateway does, so a
gateway that missed it is caught behind it (security review 2026-09-27): the gateway relays the
connection time it compared — an offline token's `iat`, any other token's `auth_time`
(`ExchangeGateFilter.connectionTime`) — as `X-Exchange-Connected-At` (honoured like the other relay
headers, REQ-XCH-010), the gate reads `exchange:revoked:<client>:<member>` from the mirror **and**
the member's row in `exchange_client_revocation` on every exchange request and refuses a connection
made at or before the later of the two seconds (`client_revoked`); a request relayed without a
connection time counts as connected before it, as a token without the claim does at the gateway.
Both sides therefore compare the same time, and the stored row keeps the check working while the
mirror is off or behind. *Corrected 2026-09-28 (security review G5, L2): the gate read only the
mirror, so with mirroring switched off a whole-client disconnect went unseen by it.* A mirror the
backend cannot read fails closed: the request is refused `503 REGISTRY_UNAVAILABLE`
(`revocations_unreadable`), as the gateway refuses revocations it cannot read, with the gateway's
`Retry-After: 30`. Every refusal of the backend's gate carries the gateway's code for the same
situation — `401 INSTALLATION_REVOKED`, `401 CLIENT_REVOKED` — and passes the gateway unchanged
(REQ-XCH-025). *Corrected 2026-09-28: the backend refused these as a generic `403` and an
unreadable mirror as `502`, which reached the client as `403 NOT_PERMITTED` and `502
BACKEND_RELAY_FAILED`.* The backend's Redis user already holds `GET` on
`exchange:*`. The member's controls are `/api/v1/connected-apps` (list,
`DELETE /{clientId}`, `DELETE /installations/{id}`), reachable only from the member's own web session.

**Acceptance**

- [x] A revoked installation is refused after a token refresh; another installation of the same
  client keeps working. *Backend: `ExchangeInstallationControllerTest`,
  `ExchangeRevocationMirrorIntegrationTest`. Gateway: it reads `exchange:deny:<jkt>` on every
  request, bypassing its cache, and refuses a listed key `401 INSTALLATION_REVOKED` whatever the
  token's `iat` (`ExchangeGateTest`). End to end:
  `ExchangeConnectionsE2eTest.revokingOneInstallationLeavesTheOtherWorking` — the member disconnects
  one of two installations on „Verbundene Anwendungen" in place, the gateway refuses that key on
  the next request and again after a token refresh, and the other installation keeps working.*
- [x] A revoked client is refused, and a fresh connection right after works. *The gateway half is
  in: it reads `exchange:revoked:<client>:<member>` per request and refuses `401 CLIENT_REVOKED` an
  offline token issued at or before that second and any other token signed in at or before it or
  without `auth_time` — so a token refreshed after the disconnect from an older sign-in is refused —
  while an offline token issued after it and a token of a later sign-in pass (`ExchangeGateTest`).
  The backend removes the consent, deletes the client's own sessions and ends the client inside
  shared ones before it reads the time, and writes nothing when Keycloak fails
  (`ConnectedAppsServiceTest`, `KeycloakServiceTest`); the extension leaves the member's other
  clients signed in and needs `manage-users` over the member (`ExchangeClientSessionResourceTest`).
  The backend re-checks it from the relayed connection time against the later of the stored row and
  the mirror, so a disconnect the mirror lacks is still refused, and refuses an unreadable mirror
  (backend `ExchangeGateTest`, `ExchangeCatalogControllerTest`; the compared time in the gateway's
  `ExchangeGateTest`, the relay header in `ExchangeRelayTest`). The disconnect answers only in a
  second after the revocation's, holding no transaction
  (`ExchangeRevocationSecondTest`, `ConnectedAppsControllerTest`). *Changed 2026-09-28: it answered
  at once, so a device login finished in the revocation's own second got `401 CLIENT_REVOKED`
  (E2E run 36388920243, chromium 1280x800); the `<=` comparison stays, so a refresh of the old
  session in that second is still refused.* End to end:
  `ExchangeConnectionsE2eTest.aNewConnectionAfterAWholeClientDisconnectWorksAtOnce` — after the
  member disconnects the client on „Verbundene Anwendungen", the gateway refuses it `401
  CLIENT_REVOKED` on the next request, and the first call of a new device login with
  `offline_access` is answered.*
- [x] A departed member is refused on the next request. *The backend half is in (WP 3.1): the roster
  sync and the login sync publish `MemberDepartedEvent` when an active member is disabled, loses
  every role or disappears from Keycloak, and `ExchangeDepartureService` then — after the sync's
  commit, only while the registry holds a client — removes the member's consent for each client and
  logs them out of every session, and only then reads the time and writes a revocation for every
  client (mirror and database), also when a Keycloak step failed, so no token refreshed meanwhile
  carries a later `iat`; it audits `EXCHANGE_MEMBER_DEPARTED`. A failed step is counted and alerts
  (`ExchangeDepartureIncomplete`) instead of failing the sync (`ExchangeDepartureServiceTest`,
  `ExchangeDepartureIntegrationTest`, `UserReconciliationServiceTest`).
  The gateway refuses the member through the per-client revocations those steps write
  (`ExchangeGateTest`). End to end: `ExchangeDepartureE2eTest` — the member loses every realm role
  in Keycloak, their next sign-in reconciles the departure through the login sync, and the
  client's next request is refused `401 CLIENT_REVOKED`. The roster sync's trigger is covered by
  `UserReconciliationServiceTest`; the E2E run leaves it alone because it reconciles every account
  of the shared stack.*

**Status:** built — WP 3.1 / 3.3 (#2083), WP 3.2 (#2082), WP 4.5 (#2087); end to end on the E2E
stack (`ExchangeConnectionsE2eTest`, `ExchangeDepartureE2eTest`)

### REQ-XCH-009 — The acting member holds a reduced authentication and sees own data only

On `/api/v1/exchange/**` the acting member holds an exchange role and the relayed capability
authorities — never their stored roles, permissions or contextual grants. Exchange reads and
writes touch only the member's own blueprints, own Lager rows (personal and shared, ADR-0230) and
own ships; they never
use the admin all-scope or an admin pin. The membership, pending-approval and terms gates apply
unchanged. No exchange response carries personal data of
anyone.

**How it is built** (WP 3.1). `ActingMemberFilter` keeps an explicit list of exchange routes
next to the two ingest routes. On an exchange route the member gets
`ActingMemberAuthorities.exchangeAuthoritiesFor`: `ROLE_EXCHANGE_MEMBER` plus one
`XCH_CAPABILITY:<scope>` per relayed known scope, and nothing else; a member the approval or role
gate refuses keeps exactly that gate's marker, so the gates refuse as they do for the web. The
demand feed reads the member's memberships itself (`ExchangeDemandService`, REQ-XCH-018).
*Corrected 2026-09-27: this requirement said the acting member would also hold the memberships the
demand feed needs, arriving with it; the feed was built without any membership authority.*

**Acceptance**

- [x] An `ADMIN` member reads and writes only own rows and holds no admin authority on exchange
  paths. *The authority half: `ExchangeCatalogControllerTest` — an `ADMIN` member's exchange
  request holds only `ROLE_EXCHANGE_MEMBER` and the relayed capabilities. The own-rows half:
  `ExchangeAdminActingMemberTest` — an `ADMIN` acting through the exchange, with an admin pin on
  the request, reads only their own blueprints, stock lots and ships beside another member's; a
  blueprint `remove` of the other's product is `unchanged`, a `set-quantity` on a lot only the
  other holds is `VERSION_CONFLICT`, and a ship `remove` or `upsert` of the other's ship is
  `unmatched`, each leaving the other's rows as they were.*
  *Corrected 2026-09-28: this box said the own-rows half was proven by each route as it shipped;
  no route test used an `ADMIN` member until then (epic #2078 plan audit).*
- [x] ArchUnit: exchange services never call an admin-gated method or the admin scope predicate;
  exchange controllers call exchange services only; exchange DTOs stay in the exchange layer
  (`ArchitectureTest`).

**Status:** relay and reduced authentication built — WP 3.1 (#2083); the data routes built with
WP 3.3 and WP 4.1–4.4; the `ADMIN` own-rows test built (`ExchangeAdminActingMemberTest`)

### REQ-XCH-010 — The relay names the external client, and only the gateway may

The gateway relays under ADR-0129 (service account plus `X-Ingest-On-Behalf-Of`) and adds
`X-Exchange-Client` and `X-Exchange-Capabilities`. The backend honours both only from the
gateway's service identity and refuses and counts them from any other caller. The acting
authentication carries the external client, so audit rows and client metrics name it (for example
`versekit`) instead of `none`; the known-client vocabulary comes from the registry. This attribution
is live before the first registry entry exists.

**How it is built** (WP 3.1). `X-Exchange-Client` / `X-Exchange-Capabilities` — and with them
`X-Exchange-Installation` and `X-Exchange-Connected-At` (REQ-XCH-007, REQ-XCH-008) — are honoured only
when the gateway acts for a member on an exchange route; from anyone else — or from the gateway on
an ingest route — the request is refused with `403 ACTING_MEMBER_REFUSED` and counted as
`basetool_on_behalf_of_refused_total{reason="forged_exchange_header"}`, and an exchange call without
a well-formed client as `exchange_client_invalid`. On the exchange layer (`/api/v1/exchange/…`) the
refusal's title and `detail` speak of an exchange request that could not be attributed to a valid
member and application, elsewhere of an import (`ActingMemberFilterRefusalTextTest`); every reason
still yields the same body per route kind. The acting authentication carries the client;
`ClientAttribution` names it when the registry holds it (else `other`), for the audit row and —
read from the header before the identity swap, for the gateway only — for
`basetool_api_client_requests_total`. The audit viewer offers the registry's clients by their
product names.

**Acceptance**

- [x] Forged-header tests from a browser session and from the app (`ActingMemberFilterChainTest`).
- [x] An exchange write's audit row carries the external client id. *`ClientAttributionTest`;
  `ExchangeBlueprintWriteControllerTest`, `ExchangeStockWriteControllerTest` and
  `ExchangeShipWriteControllerTest` read the client from the written audit rows.*

**Status:** built — WP 3.1 (#2083)

### REQ-XCH-011 — The v1 data formats are published JSON Schemas

The formats are JSON Schema 2020-12 files. Their source is
`ingest/src/main/resources/exchange/v1/schemas/`; the gateway serves each one anonymously at its
permanent `$id`, `https://ingest.profit-base.online/exchange/v1/schemas/<name>.schema.json` (owner
decision 2026-09-26), and a `$id` is never changed once published. The schemas are:
`item-ref` (precedence `bt` › `scRecord` › `scGuid` › `uexId` › `locKey` › `name` + `nameLocale`),
`quantity` (`{amount, unit: SCU|PIECE}`, PIECE whole; an SCU amount is not limited in its decimals — the backend rounds it half-up to three, and so does every comparison with `expectedQuantity`), `quality` (integer
0–1000, stored as sent for every material), `location-ref`, `provenance` (`log|manual|import|default|other`,
`observedAt`), `material-kind` (`RAW|REFINED|NO_REFINE` plus `commodity`, and the optional UEX
flags `mineral`, `harvestable`, `raw`, `refined`, `buyable`, `sellable`), `blueprint`, `stock-lot`
(material, location, quality, `stolen`, quantity — no org unit, no row id), `ship` (with required
`version`), `org-demand`, `location`, `installation`, `account-check`, `change-set` (at most 500
ops), `change-result` (compact, at most 32 KiB; its optional `cursor` is reserved and never sent in
v1 — a client reads the feed after a push), `page`, `service-document`, `problem` and the
offline-file `envelope` (`format`, `formatVersion`, `generator`, `generatedAt`, `items`,
`extensions`; no handle, player, source folder or file path). One OpenAPI 3.1 document,
`ingest/src/main/resources/api/exchange-v1.openapi.json`, is authoritative for the exchange routes.

**Acceptance**

- [x] CI validates every conformance fixture in `docs/exchange/examples/v1/` against its schema, and
  every schema the OpenAPI document names exists and has valid and invalid fixtures.
- [x] A test fails when a served route and the OpenAPI document diverge: `ExchangeRoutesContractTest`
  holds the gate's route table equal to the document, route for route and scope for scope, and
  `IngestEndpointSurfaceTest` fails for any served exchange route outside that table.

The gateway checks each request body against its schema before relaying it and answers a violation
`400 SCHEMA_INVALID` with `errors[]` (JSON Pointer and the violated keyword, at most 50); it checks
the backend's answer against the response schema too, and an answer that breaks it is `502
BACKEND_RELAY_FAILED`, never passed on (`ExchangeSchemas`, `ExchangeSchemasTest` over every
conformance fixture, `ExchangeControllerTest`). The validator is `com.networknt:json-schema-validator`,
the library the contract test already used, without its YAML module.

The gateway serves both anonymously and unchanged, with `Cache-Control: public, max-age=3600`:
`GET /exchange/v1/openapi.json` and `GET /exchange/v1/schemas/<name>.schema.json` (as
`application/schema+json`; an unknown name is `404 NOT_FOUND`). The extractor's own OpenAPI document
does not list them.

**Enforced by:** `ExchangeContractTest`, `ExchangeDocumentsControllerTest` · **Status:** schemas,
OpenAPI document and fixtures committed and validated — WP 0.2 (#2080); served by the gateway since
WP 3.2 (#2082)

### REQ-XCH-012 — Names resolve through the web import's own matching

`catalog/resolve` answers `resolved`, `ambiguous` or `unmatched` per reference. `scRecord` is
compared case-insensitively with `blueprint.scwiki_key`, which is not unique; `scGuid` is matched
against blueprint records and output items, and duplicates resolve `ambiguous`. Names resolve
through `BlueprintImportService.resolve()` — REQ-INV-006, REQ-INV-019, REQ-INV-021, REQ-INV-050 —
never through a second logic. `locKey` is compared case-insensitively with the catalogue's
`name_key` — the `global.ini` name key without its `@`, which the P4K import stores for items,
materials and ship types (migration `V250`; `LOC_` placeholders are not stored), and which a
blueprint takes from its output item; a `locKey` that resolves to no single entry falls through to
the name with a warning. Places resolve against the Lager's `location` table; a
place without a row is `LOCATION_UNKNOWN`.

A reference takes the first of its fields, in the order `bt`, `scRecord`, `scGuid`, `uexId`,
`locKey`, `name`, that resolves to exactly one entry; if none does, the first that resolved to
several is the answer, so an ambiguous `scRecord` still yields to a name that resolves. `ambiguous`
lists at most ten candidates as `{bt, name}`; fuzzy suggestions are always `ambiguous`, even when
there is only one, because they are never taken without the member. `LOC_KEY_UNRESOLVED` points at
`/refs/<i>/locKey` of every reference that carries a `locKey` and whose keys, `locKey` included, did
not resolve; a response carries at most 50 warnings. Per catalogue:

| `kind` | `bt` | `scRecord` | `scGuid` | `uexId` | `locKey` | `name` |
| --- | --- | --- | --- | --- | --- | --- |
| `BLUEPRINT` | the product key (normalized output name) | `blueprint.scwiki_key` | the blueprint's Wiki or game-file UUID, or its output item's | the output item's UEX id | the output item's `name_key` | the web import's chain: exact, alias, pack-tag strip, fuzzy |
| `ITEM` | the item id | `class_name` | Wiki or game-file UUID | UEX item id | `name_key` | exact, case-insensitive; duplicates `ambiguous` |
| `MATERIAL` | the material id | `scwiki_key` | Wiki or game-file UUID | UEX commodity id | `name_key` | exact, canonical (`MaterialNameCanonicalizer`), external alias, fuzzy — visible materials only |
| `SHIP_TYPE` | the ship type id | `class_name` | Wiki UUID | UEX vehicle id | `name_key` | the hangar import's `ShipTypeMatcher` |

The backend answers on `POST /api/v1/exchange/catalog/resolve` for any exchange capability; the
gateway reports unknown request fields as `UNKNOWN_FIELD` warnings (WP 3.2).

**Acceptance**

- [x] The anonymised corpus fixture
  (`backend/src/test/resources/fixtures/blueprint-corpus/game-log-corpus-v1.json`) resolves the same
  through the exchange and through the web import (`ExchangeResolveCorpusTest`).

**Enforced by:** `ExchangeResolveCorpusTest`, `ExchangeResolveServiceTest`,
`ExchangeResolveControllerTest` · **Status:** backend built — WP 3.1 (#2083); served by the gateway
with WP 3.2 (#2082)

### REQ-XCH-013 — Each resource has a snapshot and a database-sequenced change feed

`GET /exchange/v1/me/<resource>` returns a snapshot or, with `cursor`, the changes since it. The
feed is sequenced at the database level, so writes that bypass the services — default-grant
provisioning, „delete all", admin purge, user deletion, org re-stamping, owner reassignment and a
change of the default blueprint set — appear in it. Removals leave tombstones with `removedBy`
(`web`, `app`, `client` with client id and installation id, `system`) and `removedAt`, kept 90 days
and purged nightly. A cursor older than the tombstones answers `410 CURSOR_EXPIRED`.

The sequence is `exchange_change` (ADR-0224): an `AFTER` row trigger on every synced table records
`(member, resource, key)` with its writing transaction's id and a sequence number, and the feed reads
each changed key's current state, or a tombstone when it is gone. A feed position is `(transaction id,
seq)`, and a reader passes only transactions below the oldest one still running, so an entry committed
late can never land behind a position a client has already passed. Who wrote it comes from the transaction variable
`basetool.change_source`, which the backend's transaction manager sets at the start of every writing
transaction (`web`, `app`, `client|<id>|<installation key>`, otherwise `system`). A nightly job
(`exchange_change_retention`, 03:30 UTC) purges entries older than 90 days and records the highest
purged position as the horizon, below which a cursor has expired.

**Acceptance**

- [x] A test fails for any write path to the synced tables that bypasses the sequence.
  *`ExchangeChangeFeedTriggerIntegrationTest` pins the synced tables — `personal_blueprint`,
  `default_blueprint`, `inventory_item` (the member's rows, personal and shared, keyed by lot) and
  `ship` — to their triggers and runs bulk deletes, owner reassignment, a move to another place, a
  rebooking between personal and shared that leaves the lot unchanged, and user deletion through
  them. `V260` announced every lot holding shared rows once, as a `system` change, so a cursor
  taken before it still sees them (ADR-0230).*
- [x] A default-set change emits entries for every affected member.
  *`ExchangeChangeFeedTriggerIntegrationTest`.*
- [x] Every writing transaction is attributed to its channel. *`ChangeSourceTransactionManagerIntegrationTest`.*
- [x] A writer that commits after a later one stays ahead of the readers' watermark.
  *`ExchangeChangeWatermarkIntegrationTest`.*

A snapshot pages by row id and ends with the feed cursor it was taken at, so nothing written during
it is lost. A feed page answers each key changed after the cursor once, with its current state or a
tombstone whose `installationId` is the removing installation's id. A feed page that reaches the end
moves the cursor up to the watermark, so an idle client's cursor never falls behind the horizon.
Cursors are `s1.<tx>.<seq>.<id>` and `f1.<tx>.<seq>` and stay opaque to clients; one the server did not
issue also answers `CURSOR_EXPIRED`.

Once an exchange write has committed, the backend raises the live-sync frames the member's web
pages listen on, so they refresh without a reload: `hangar:{member}` after a ship write,
`blueprints:{member}` after a blueprint write, `inventory` after a stock write and `materialboard`
when that write lowered or removed an offer (REQ-FE-015). A rolled-back write raises none.

**Status:** sequence, attribution and retention built for blueprints, stock and ships — WP 3.3
(#2083); the blueprint, stock and ship feeds and their gateway routes built — WP 4.1 (#2084), WP 4.2
(#2085), WP 4.4 (#2086)

### REQ-XCH-014 — A client never re-adds what the member removed elsewhere

An `add` of an entry that has a live tombstone is refused per op with `REMOVED_ELSEWHERE`,
whichever installation or channel removed it. A client may send `override: true` only after asking
the member; the override is journaled.

**Acceptance**

- [x] One installation removes, another tries to re-add: refused; with override: applied and
  journaled.

A tombstone is live while the key's latest change-log entry — its removal — is within the
retention; the same installation may re-add what it removed itself. *`ExchangeBlueprintWriteControllerTest`
also covers a removal in the web.*

**Status:** built for blueprints, stock and ships — WP 4.1 (#2084), WP 4.2 (#2085), WP 4.4 (#2086)

### REQ-XCH-015 — Blueprints sync as a set

Ops are `add` and `remove` of products. Default-granted blueprints cannot be removed
(`DEFAULT_NOT_REMOVABLE`). A blueprint's `note` is read-only in v1. Writes are audited in the
Blueprints domain with the external client. An `add` records the client and the source its
`provenance` names, and the feed publishes a recorded source as `provenance.source` (REQ-INV-054).

A blueprint's `key` and its `ref.bt` are the same value: the normalised product key, or `h:` and
its SHA-256 in hex when that is longer than 128 characters. The display name is cut to 200. The
resolver (REQ-XCH-012) answers a blueprint with the same `bt` and accepts it back.

**Acceptance**

- [x] Round trip: the corpus fixture added through the exchange appears in „Meine Blueprints" and
  in the feed of another installation.
  *`ExchangeSyncE2eTest.theCorpusReachesMeineBlueprintsAndTheFeedOfAnotherInstallation` on the E2E
  stack: every name of `game-log-corpus-v1.json` is resolved in one call against the twelve
  products `ExchangeResolveCorpusTest` uses (`exchange-corpus-e2e-seed.sql`), the resolved ones are
  added through one installation, and each appears in the feed of a second installation, read on
  from its snapshot cursor, and on „Meine Blueprints" with the client as its source.*
- [x] The feed marks default-granted blueprints and follows a change of the default set.
  *`ExchangeBlueprintControllerTest`.*

The backend applies a change set at `POST /api/v1/exchange/me/blueprints/changes`
(`exchange.blueprints.write`) in one transaction: it resolves every reference in one resolver call,
plans the ops in order — an add of an owned product and a remove of a missing one are `unchanged`, a
remove of a default `rejected DEFAULT_NOT_REMOVABLE`, an add against another's tombstone `rejected
REMOVED_ELSEWHERE` — then asks the mass-change guard, and writes through the web's own add and
delete, so the Blueprints audit names the client; each written entry is journaled. `dryRun` plans
only. `basetool_exchange_writes_total{resource,outcome}` counts the ops.

**Status:** read and write sides built in the backend, and the gateway's read route (`GET
/exchange/v1/me/blueprints`) and write route (`POST …/changes`) — WP 4.1 (#2084); the corpus round
trip built on the E2E stack (`ExchangeSyncE2eTest`)

### REQ-XCH-016 — Stock syncs as lots, booked like the web

A lot is material + location + quality + stolen over **every row the member holds, personal and
shared**, across org-unit pools — what the member's „Mein Lager" shows (ADR-0230). `set-quantity`
carries `expectedQuantity`; the server compares under row locks and answers `409 VERSION_CONFLICT`
on a difference, otherwise books the delta in or out through the Lager's services. Book-ins are
personal rows without an org unit (REQ-ORG, own stamping path); book-outs take the personal rows
first, then rows without a unit, then the oldest, and never the part of a row reserved for a job
order or mission. Linked Materialbörse offers follow a book-out as in the web; the result reports
`offersReduced` and `offersRemoved`, and each change writes its `MARKET_*` audit event. **Every
material lot keeps the quality the client sends**, whatever the material's kind; an item lot has
quality 0. `materialKind` classifies a material for the client's own lists — `type`, `commodity`
(listed in UEX's commodity catalogue, ores and refined metals included) and the UEX flags
`mineral`, `harvestable`, `raw`, `refined`, `buyable`, `sellable` where UEX knows them — and never
decides the quality. Writes are audited in the Lager domain with the external client.

*Amended 2026-10-02 (ADR-0230, owner decision of the same day):* a lot covered the member's personal
rows only, and a material carrying a UEX commodity id — nearly the whole catalogue, every ore and
refined metal included — was stored at quality 0 whatever the client sent. Three lots of one
mineral at qualities 561, 682 and 371 became one lot without a quality, and a lot the feed showed
at its quality could not be written at it. Until the amendment, every client stock write in
production had landed at quality 0 (203 journal entries, 36 lots).

**Acceptance**

- [x] Concurrent `set-quantity` on one lot: one applies, the other gets `VERSION_CONFLICT`, for a
  lot with rows and for an empty one. *`ExchangeStockWriteConcurrencyIntegrationTest` (PostgreSQL):
  two installations send the same rise while the first holds its locks; `ExchangeStockWriteControllerTest`:
  an optimistic-lock failure a write meets anyway reaches the client as `409 VERSION_CONFLICT`, not
  as a relay failure. Corrected 2026-09-28 (load test, finding 7): the row locks alone let the
  second rise apply too — it did not see the row the first booked in, and an empty lot had no row
  to lock — so a lot set `5 → 6` twice ended at 7.*
- [x] Two installations of one member sending sets over the same lots in opposite orders both
  finish; neither deadlocks. *`ExchangeStockWriteConcurrencyIntegrationTest` (PostgreSQL).
  Corrected 2026-09-28 (load test, finding 1): the lots were locked in the order the ops arrived,
  and such sets deadlocked (`40P01`).*
- [x] Each op's two lookups stay index lookups however long the member's history is: the latest
  change-log entry of the lot key (`idx_exchange_change_user_key`, V258) and the lock of the lot's
  rows (the stack-key index on every column, quality compared as stored). *`ExchangeStockLookupPlanIntegrationTest`
  (PostgreSQL plans). Corrected 2026-09-28 (load test, finding 2): both filtered a member's whole
  journal or every row of the material at the place, and a 500-op set slowed from 1.5 s to 5 s as
  the history grew.*
- [x] A book-out below an offered amount lowers the offer and records the audit event.
  *`ExchangeStockWriteControllerTest`.*
- [x] A lot sums the member's personal and shared rows across pools, leaves other members' rows
  out, stays unchanged when a row is rebooked between personal and shared, and becomes a tombstone
  when its rows are gone. *`ExchangeStockControllerTest`. Amended 2026-10-02 (ADR-0230): the lot
  left shared rows out, and a rebooking to the shared pool was a tombstone.*
- [x] A material lot is stored at the quality the op sends, a UEX commodity included; three
  qualities of one material stay three lots; a lot booked in the web is written at its own quality.
  *`ExchangeStockWriteControllerTest`.*
- [x] A fall takes personal rows before shared ones and never the reserved part of a row, which
  answers `STOCK_EARMARKED` when the free stock is short. *`ExchangeStockWriteControllerTest`.*
- [x] `materialKind` carries the UEX flags the material has and leaves the unknown ones out.
  *`ExchangeStockControllerTest`.*
- [x] Moving stock to the lot's stolen twin marks the rows — a part split off, a whole lot flipped —
  and a piece book-in joins the existing row. *`ExchangeStockWriteControllerTest`.*

The feed's lot key is the one the change log records, `m:<material>|l:<location>|q:<quality>|s:<0|1>`
or `i:<item>|…`; a snapshot pages lots by their lowest row id. The material reference carries the
material's or item's id as `bt`, an item lot has quality 0 and counts whole pieces, and an SCU amount
is rounded to three decimals. The backend bounds every `quantity` and `expectedQuantity` amount to 0…10⁹
like `quantity.schema.json`, so a change set that bypasses the gateway's schema check is still
refused `400` (`ExchangeStockChangeSetValidationTest`, `ExchangeStockWriteControllerTest`). Game items stay in the stock sync beside materials (owner decision
2026-09-27).

The backend applies a change set at `POST /api/v1/exchange/me/stock/changes`
(`exchange.stock.write`) in one transaction. Each op resolves its material — a material first, an
item otherwise — and its place, the UEX link first, then the exact name of a non-hidden location
(`LOCATION_UNKNOWN`), checks both units against the material's (`UNIT_MISMATCH`); then every lot
the batch names is locked before any op compares `expectedQuantity` (`VERSION_CONFLICT`): first a
transaction-scoped advisory lock per member and lot key, all of them in ascending order of the
lock key, then the lots' rows in the order of the lots' keys, each lot's rows in id order
(ADR-0229). So two sets of one member over the same lots never deadlock whatever order their ops
come in, and the one that waited sees what the other booked, an empty lot included. A material
lot is addressed at the quality the op sends. A
lot emptied by another channel or installation is refilled only with `override`
(`REMOVED_ELSEWHERE`); stock reserved for a job order or mission is never taken (`STOCK_EARMARKED`,
owner decision 2026-09-27; the reservations sit on the lot's shared rows, and a book-out takes
from each row only what its reservations leave free);
a stolen lot waits for `APP_INVENTORY_STOLEN_MARKING_ENABLED` (`STOLEN_MARKING_DISABLED`). The
mass-change guard counts a lot set to 0 or cut to a tenth of what it held when the client's window
opened, unless the batch's rises of the same material cover the whole fall (REQ-XCH-021). A fall and
a rise of the lot's stolen or not-stolen twin — same material, place and quality — are a marking,
not a book-out and a book-in: as much as both allow is marked through the Lager's own marking
(REQ-INV-053, a part split off as a new row, `INVENTORY_STOLEN_MARKED` / `_UNMARKED`), leaving
out rows backing a Materialbörse offer and the earmarked part of a row; the rest is booked (owner
decision 2026-09-27). A book-in is a new personal row without an org unit
(`INVENTORY_ITEM_CREATED`), which piece-counted stock then joins to the existing row as a book-in in
the Lager does (REQ-INV-026, owner decision 2026-09-27); a book-out runs the Lager's own
`DISCARD` book-out over the personal rows first, then the rows without an org unit, then the
oldest, and every offer it
lowers or removes is audited by that book-out (`MARKET_OFFER_REDUCED`, `MARKET_OFFER_REMOVED`,
`reason=stock`, REQ-MARKET-013) and counted in
`offersReduced` / `offersRemoved`. Each changed lot is journaled.

**Status:** read and write sides built in the backend, and the gateway's read route (`GET
/exchange/v1/me/stock`) and write route (`POST …/changes`) — WP 4.2 (#2085)

### REQ-XCH-017 — Ships sync with a link step before the first create

`link` attaches a client ship to an existing server ship; `upsert` and `remove` carry the ship's
`version`. A client links before it creates, so a Fleetview import is never duplicated. Purchase
data is never sent. Detaching a ship from a mission by removal is reported in
`detachedFromMissions` and audited (`MISSION_UNIT_UPDATED`). Writes are audited in the Hangar domain
with the external client. A ship's `name` is optional and up to 255 characters, as in the web: an
unnamed ship is sent without it, and an upsert may leave it out (owner decision 2026-09-27). An
`upsert` sets the ship as sent: a `name` or `location` it leaves out is cleared, and only an absent
`fitted` keeps its value. A ship a client creates belongs to the member's only direct org unit, or to
none when the member has none or several, because a client names no unit; the member assigns it
later in the web, as a stock book-in (owner decision 2026-09-27, `HangarService.addShipForClient`).
*Corrected 2026-09-27: a member of several units had the whole batch refused with
`OWNER_ORG_UNIT_REQUIRED`.*

**Acceptance**

- [x] First sync against a Fleetview-imported hangar creates no duplicate.
  *`ExchangeShipWriteControllerTest`.*
- [x] A batch naming another member's ship never locks its row: the owner's concurrent edit does not
  hold it up. *`ExchangeShipWriteControllerTest`.*
- [x] The feed carries the member's own ships only, without purchase data, and answers a ship given
  to another member as a tombstone. *`ExchangeShipControllerTest`.*

The ship feed keys a ship by its id and sends its `version`, the ship type's id as `shipType.bt`,
insurance as `LTI` or a number of months, and the location when it has one. A ship stored without
insurance, which the web's validation does not allow, reads as zero months.

The backend applies a change set at `POST /api/v1/exchange/me/ships/changes`
(`exchange.hangar.write`) in one transaction; the feed carries each ship's `externalId` for the
calling installation. A link belongs to one **installation** (owner decision 2026-09-27): each
installation links its own ids, so two installations with separate local databases never mistake
each other's ids, and a server ship is linked at most once per installation (`LINK_TARGET_TAKEN`);
re-linking an id moves it. `link` needs the member's own ship; `upsert` without `shipId` creates the
ship through the Hangar's own create and links it, unless the id is already linked, which answers
`VERSION_CONFLICT` so the client pulls first; `upsert` with `shipId` requires the ship's `version`
(`VERSION_CONFLICT`), writes through the Hangar's own update and links the id if it is not yet. An
`upsert` naming a ship the server no longer has brings it back as a new ship only when the calling
installation removed it or with `override` after asking the member (`REMOVED_ELSEWHERE`, owner
decision 2026-09-27); a ship the member never had is `unmatched`. `remove` requires the `version`,
detaches the ship from its mission units through the Hangar's delete (`MISSION_UNIT_UPDATED`) and
reports the count as `detachedFromMissions`. The ship type resolves through `catalog/resolve`, the
place like a stock lot's; an absent `fitted` keeps the ship's. Every write is audited in the Hangar
area with the client and journaled. Only the member's own ships are row-locked
(`ShipRepository.lockOwnedById`, owner in the `WHERE`): a ship id of another member, like an
unknown one, is `unmatched` and its row is never locked, so a batch cannot hold up other members'
web edits. *Corrected 2026-09-27 (security review L6): the ship was locked before the owner check,
so a batch could keep up to 500 foreign rows locked for its transaction.*

**Status:** built in the backend, and the gateway's read route (`GET /exchange/v1/me/ships`) —
WP 4.4 (#2086), and its write route (`POST …/changes`)

### REQ-XCH-018 — Org demand is anonymised and membership-scoped; locations are the non-hidden list

`GET /exchange/v1/me/org-demand` lists the open demand of the units the member belongs to, never
units the member merely oversees or administers: `materials[]` (material, `rawRefs[]`, open
quantity per `minQuality`, `source: material-order|item-order`) and `items[]` (item, open quantity,
`craftableByMe`), plus `updatedAt`. It carries no requester, assignee, order title, free text or
per-order breakdown and no low-count suppression; a client may cache it for up to 7 days.
`GET /exchange/v1/catalog/locations` lists the non-hidden `location` rows with their UEX link.

The backend serves the location list as `GET /api/v1/exchange/catalog/locations` (any exchange
scope), one query, a city link winning over a space-station link.

The backend serves the demand as `GET /api/v1/exchange/me/org-demand` (`exchange.demand.read`): the
open and in-progress orders a unit of the member's memberships is responsible for. A material line
is the gap per material, quality floor (650 for „gut", else 0) and source, computed exactly as the
Materialbedarf computes it: required and booked are summed over the orders of one responsible unit,
each rounded to the material's precision, and the difference is clamped at 0 once — so stock booked
beyond one order's need offsets another order's gap in that unit — then the units are added up
(owner decision 2026-09-27; `ExchangeDemandParityTest`). *Corrected 2026-09-27: the feed clamped
per order and rounded the difference, and so disagreed with the Materialbedarf in both cases.* `rawRefs` are the materials whose refined material it is. An item
line sums `max(0, ordered − delivered − earmarked)` per game item, and `craftableByMe` matches the
member's blueprints the way the order's blueprint coverage does (variant family when the order counts
variants). Lines with nothing open are left out; `bt` is the material's or game item's id.

Only a member who passes the web's job-order gate gets the demand: `ExchangeDemandService` asks
`OwnerScopeService.canViewJobOrders()` — the same rule that opens the Aufträge area and the
Materialbedarf (REQ-ORDERS-034) — before reading any order. Any other member gets `200` with two
empty lists and `reason: "NOT_PERMITTED"`; a permitted member's answer carries no `reason`, even
when nothing is open. The rule is evaluated with the exchange's reduced authorities (REQ-XCH-009),
so it reduces to "a member, or a leadership seat above a member, of at least one profit-eligible
unit" — an `ADMIN` role does not open it. It runs on every request, so a unit that loses its profit
eligibility while its orders stay open stops showing its demand to members who have no other
eligible unit. As on the web, the gate is per member: a permitted member sees the demand of every
unit they belong to (owner decision 2026-09-28, #2095). `reason` is an optional field of
`org-demand.schema.json` with the one value `NOT_PERMITTED`; the gateway refuses any other value as
a relay failure. *Corrected 2026-09-28: #2095 promised this gate and the `reason`, but the feed was
built without either, so a member of a unit that lost its profit eligibility kept seeing its open
demand.*

**Acceptance**

- [x] A member who fails `canViewJobOrders` gets empty lists with `reason: NOT_PERMITTED` and no
  order is read; a member who passes it gets the demand without a `reason`; a unit that loses its
  profit eligibility stops showing its demand. *`ExchangeDemandServiceTest`,
  `ExchangeDemandControllerTest` (against the real gate and database), `ExchangeDemandParityTest`
  (the web's Materialbedarf is withheld by the same gate); `ExchangeOrgDemandRouteTest` relays the
  withheld answer and refuses an unknown `reason`; fixtures for both shapes under
  `docs/exchange/examples/v1/org-demand/`.*
- [x] An overseer who is not a member of a unit does not see its demand.
  *`ExchangeDemandServiceTest` — only the member's own units are asked.*
- [x] The feed's open quantities equal the Materialbedarf's gaps for the same orders.
  *`ExchangeDemandParityTest`.*
- [x] The response schema admits no name or free-text field.
  *`ExchangeOrgDemandRouteTest` pins the schema's field sets and the `reason` enum; the only names
  are catalogue names.*

**Status:** the backend location list is built — WP 3.1 (#2083); the backend's demand and the
gateway's demand route (`GET /exchange/v1/me/org-demand`) are built — WP 4.3 (#2095); the
job-order gate with `reason: NOT_PERMITTED` is built (#2095 follow-up)

### REQ-XCH-019 — Drafts keep review-before-commit

`drafts/blueprints` and `drafts/refinery-orders` stage an upload for review in the browser, as
REQ-INGEST-004 requires today; nothing is written until the member confirms.

**Acceptance**

- [ ] The SC Extractor's draft flows pass unchanged through the exchange routes. *As of 2026-09-28
  both sides are merged — the server routes (#2175) and the extractor's exchange client
  (basetool-sc-extractor #69–#71); the box closes with the extractor's 2.10.0 release at the go-live
  (#2088, #2092).*
- [x] A draft is checked against its schema, relayed, staged and answered with its handoff; a
  refused one stages nothing. *`ExchangeDraftRouteTest`.*
- [x] A client's drafts evict only its own oldest drafts for that member, never the extractor's
  uploads or another client's drafts. *`HandoffStagingServiceTest`.*
- [x] The backend previews a blueprint draft as an upload would and writes nothing; each draft
  needs its own capability. *`ExchangeDraftControllerTest`.*
- [x] A blueprint envelope of a `formatVersion` major other than 1 is refused by the gateway with
  `errors[]` at `/formatVersion` before the relay, and by the backend on the draft route and in the
  web import; any `1.x` passes. *`ExchangeDraftRouteTest`, `ExchangeDraftControllerTest`.*

The gateway checks a draft against `blueprint-draft.schema.json` or `refinery-draft.schema.json`
(`SCHEMA_INVALID`). **A blueprint envelope's `formatVersion` must have the major `1`** (owner
decision 2026-09-28): every `1.x` is read, a later minor only adds optional fields, and any other
major — the part before the dot must be exactly `1` — is refused. The schema is not narrowed, since
narrowing a `pattern` would break the v1 promise that schemas only grow; its description states the
rule, and the gateway checks it right after the schema, answering `400 SCHEMA_INVALID` with
`errors[{pointer:"/formatVersion", message:"unsupported major version"}]` before anything is
relayed. The backend checks it again: `ExchangeBlueprintDraftDto.formatVersion` carries
`@Pattern("^1\.[0-9]+$")` (a relayed refusal reaches the client as `SCHEMA_INVALID`), and the web
import refuses a well-formed other major with the localised
`error.personalBlueprint.formatVersionUnsupported` (REQ-INV-014). The gateway relays the draft to `POST /api/v1/exchange/me/drafts/blueprints` or
`…/refinery-orders` (`exchange.drafts.blueprints` / `exchange.drafts.refinery`). The backend builds
exactly what the extractor's upload builds: for blueprints it resolves each `ref` as
`catalog/resolve` does and previews a resolved ref under its product's name and any other under the
name sent — or its first key when it has none — so it lands among the unmatched rows for a manual
pick; repeats collapse to the earliest `acquiredAt`. For refinery orders it is the refinery import's
draft. A draft the backend refuses as malformed is `400 SCHEMA_INVALID`. The gateway stages the
answer as a handoff (`HandoffKind.BLUEPRINT` / `REFINERY`, at most `app.ingest.max-handoff-bytes`,
measured as the staged value with its handoff wrapper; a larger one is `413 PAYLOAD_TOO_LARGE`,
checked before staging and never cached), counts it against the exchange's byte budget and answers
`draft-result` with the `frontendUrl` of the blueprint import review or the refinery create form.
The handoffs sit in slots of their own per client and member — at most
`app.exchange.store.max-drafts-per-client-member` (10) live drafts, the oldest evicted — so a client
with a drafts scope can never evict another client's drafts (security review 2026-09-27). *(The
legacy extractor uploads' per-member slots they were kept apart from are removed since
2026-09-28.)* A draft that cannot be staged is `503 SERVICE_UNAVAILABLE` and counted as
`basetool_ingest_handoff_errors_total{reason="staging_unavailable"}` (`IngestStagingUnavailable`). As
write routes they take an `Idempotency-Key` and count against the daily quota.

The web blueprint import reads the same `basetool.blueprints` envelope as an upload, so a client's
offline file and its draft end in the same review (owner decision 2026-09-27, REQ-INV-014).
*Corrected 2026-09-27: the owner's answer of 2026-09-26 kept the envelope to the draft route; it was
revisited once the SC Extractor was to write its offline files in that format.*

**Status:** built in the backend and the gateway — WP 3.2 (#2082); the extractor's side is WP 5.1
(#2088)

### REQ-XCH-020 — Writes are idempotent per client and member

Every write carries an `Idempotency-Key` (`400 IDEMPOTENCY_KEY_MISSING`), kept 24 h and keyed per
(client, member, key). Authentication, gates and rate limits run before the lookup; only results
produced after them are cached — never `401`, `403`, `429`, `503`,
`MASS_CHANGE_CONFIRMATION_REQUIRED` or a `5xx`. A duplicate in flight gets
`409 IDEMPOTENCY_IN_PROGRESS`; a reused key with a different request — another method, path or
body — `422 IDEMPOTENCY_KEY_REUSED`.
These two and `400 IDEMPOTENCY_KEY_MISSING` are the filter's own answers about the key, written
before anything is claimed, and are never cached; the cached statuses below are those of the route
behind the filter (`ExchangeIdempotencyFilter.cacheable`). An answer above
`app.exchange.store.max-result-bytes` (32 KiB), one the byte budget cannot take or one whose store
write fails is not cached, and a retry under its key runs again.

The key is 8 to 128 characters of `[A-Za-z0-9._~-]` and is stored only as a hash, under
`ingest:xch:idem:<client>:<member>:<sha256>`; a request's fingerprint is the SHA-256 of method, path
and body. The same request under a known key is answered from the cache with `Idempotency-Replayed:
true`. Cached are the answers `2xx`, `400`, `404`, `409`, `410` and `422`, and never a staged mass
change or the `400 SCHEMA_INVALID` for a body that is not a JSON document: `GlobalExceptionHandler`
marks that request `ExchangeIdempotencyFilter.NOT_REPLAYABLE`, since a truncated body retried whole
under its key must run, not meet `422`. A body of another media type is `415
UNSUPPORTED_MEDIA_TYPE`, which is not cached either. The lock of a key in flight lives two minutes,
so a crashed request cannot block a key for the day. A store Redis cannot reach is `503
SERVICE_UNAVAILABLE`, never an unguarded write. That holds on every exchange route and for every
kind of Redis failure — a lost connection, a timeout, a refused command while staging a draft or a
mass change, or a store failure escaping a route — each answers `503 SERVICE_UNAVAILABLE` with
`Retry-After: 60`, never a `500`. Two Redis reads answer otherwise: an unreadable registry or
revocation mirror is `503 REGISTRY_UNAVAILABLE` (REQ-XCH-003) and a write quota that cannot be
counted is `503 SERVICE_UNAVAILABLE` (REQ-XCH-023), both with `Retry-After: 30`; a full byte budget
is `503 EXCHANGE_BUDGET_EXHAUSTED` with a `Retry-After` read from the budget (REQ-XCH-023).
*Changed 2026-09-28: it was a fixed `Retry-After: 60`.* *Corrected 2026-09-27: this paragraph
said every Redis failure answered `Retry-After: 60`; the registry and quota reads have answered 30
since they were built.*

The lock is `ingest:xch:idem-lock:<client>:<member>:<sha256>`, taken with `SET NX` and a random
per-request token. Holding it, the gateway reads the cache again: a duplicate that looked before the
first request stored its answer and took the lock after it was released replays that answer (or is
refused with `422` for another body) instead of running the write twice. The lock is released by a
compare-and-delete script only while it still holds the request's own token, so a request that
outlived the two minutes never frees the lock of the request that took the key after it.

**Acceptance**

- [x] Replay, cross-member key, in-flight duplicate, uncached `429`. *The gates, limits and quota run
  before the cache, so a refused request never reaches it (`ExchangeIdempotencyFilterTest`: replay,
  reused key, in-flight duplicate, the cached and uncached statuses, a store that fails; the namespace
  holds the client and member, so one member's key can never answer another's).*
- [x] A duplicate that raced the first request's answer replays it; a lock is freed only by its
  holder. *`ExchangeIdempotencyFilterTest` (the lookup under the lock, replay and `422`);
  `ExchangeStoreRedisIntegrationTest` against a real Redis under the ingest ACL user: a duplicate held
  between its lookup and its lock replays instead of writing again, sixteen parallel duplicates run
  the write once per key, and an expired holder's token does not release the next holder's lock.*

**Enforced by:** `ExchangeIdempotencyFilterTest`, `ExchangeStoreRedisIntegrationTest`,
`ExchangeChangeRouteTest` (a body that is no JSON and one of another media type are never cached) ·
**Status:** built — WP 3.2 (#2082)

### REQ-XCH-021 — Mass changes are confirmed by the member in the browser

Per client, member and resource over a rolling 24 h, a batch that takes the window above 25
removals, or above 20 % of (current count + entries removed in the window) with at least 5, is
staged and answered with `MASS_CHANGE_CONFIRMATION_REQUIRED` and a confirmation URL under
„Verbundene Anwendungen". Removals are `remove`, a quantity set to 0, a lot's reductions
accumulated to ≥ 90 % within the window, and a ship update that changes name and type; a move
within one batch is not a removal. Only the member's browser session can confirm.

**Acceptance**

- [x] One test per counting rule, including repeated 89 % cuts and a move. *The thresholds
  (`ExchangeMassChangeGuardTest.theWindowTripsAbove25OrAboveAFifthWithAtLeastFive`), the 24 h window
  (`…theWindowIsTheLast24HoursOfTheClientsRemovals`), `remove`
  (`ExchangeBlueprintWriteControllerTest.aBatchThatRemovesTooMuchIsHeldBackWholly`), a quantity set to 0
  and the exempt move (`ExchangeStockWriteControllerTest.emptyingEveryLotIsHeldBackButAMoveIsNot`),
  repeated cuts — 100 → 50 → 11 passes, → 10 trips
  (`…repeatedCutsCountOnlyOnceTheyReachNinetyPercentOfTheWindowStart`) — and a ship's name-and-type
  change (`ExchangeShipWriteControllerTest.anUpdateThatChangesNameAndTypeCountsAsARemoval`). A ship's
  `remove` has no test of its own; it shares `ExchangeShipWriteService.isRemoval` with the name-and-type
  case. Ticked 2026-09-28.*
- [x] A batch is not confirmed after the client or installation was disconnected, or the client
  suspended, since its staging, nor past its 30-minute staging lifetime, and a kept session entry
  expires with it. *`ExchangeMassChangeControllerTest`, `ConnectedAppsConfirmControllerMvcTest`.*
- [x] A held batch replaces only the same client's older held batch for that member, never another
  client's or an extractor draft. *`HandoffStagingServiceTest`, `ExchangeChangeRouteTest`.* The
  confirmation page keeps consumed batches by handoff id and the backend confirms each on its own,
  so neither needed a change.

The counting rule is `ExchangeMassChangeGuard`: over the journal's live removals of the client,
member and resource in the last 24 hours plus the batch's, a batch trips above 25, or when that total
is at least 5 and more than a fifth of the current count plus the window's removals. Each resource's
write service decides what in its batch is a removal.

A stock lot counts as removed when it is set to 0 or cut to at most a tenth of what it held when the
client's window opened, taken from the lot's first journal entry in the window. A fall is a move and
does not count when the batch's rises of the same material or item still cover all of it, the falls
taken in the batch's order — a single piece added elsewhere does not hide an emptied lot (owner
decision 2026-09-27).

A ship counts as removed by `remove`, and by an `upsert` that changes both its name and its type.
Nothing compares a ship with its state when the window opened, so a batch that retypes every ship,
or clears every name and location, counts no removal. The owner kept that rule on 2026-09-27
(security review 2 of #2092, L2) and accepted the gap: the journal records every such write and the
member's undo restores it; the threat model lists it as an accepted risk.

When the backend answers `MASS_CHANGE_CONFIRMATION_REQUIRED`, the gateway stages the change set with
its client, installation, resource and `stagedAt` (the gateway's clock) in the handoff staging
(`HandoffKind.MASS_CHANGE`, one slot per client and member, `ingest:handoff-index:mass:<client>:<sub>`,
apart from the drafts — a client's newer held batch replaces its own older one, never another
client's (owner decision 2026-09-27) — at most `app.exchange.store.max-mass-change-bytes`,
512 KiB, counted against the exchange's Redis budget) and answers `409` with a `confirmationUrl` to
`/connected-apps/confirm?handoff=<id>`. A change set too large to hold — measured, like a draft, as
the staged value with its wrapper — is `413 BATCH_TOO_LARGE`, checked before staging and never
cached.

The confirmation link opens `/connected-apps/confirm?handoff=…`. As ADR-0110 requires, loading the
page consumes nothing: its script strips the id from the address bar and consumes the staged batch
with an explicit request, after which the batch waits in the member's server session and the
browser names it only by its handoff id, so it cannot alter the batch or its client. The staged
entry is keyed by the member's subject (`ingest:handoff:<sub>:<id>`), so only that member can open
it; the developer site still tells a client to treat the link like a draft's `frontendUrl`, as a
secret it never logs or shares (`docs/exchange/sync-guide.md`). The change set and its `stagedAt`
come only from the gateway's staged entry and travel from the member's server session through the
frontend to the backend; no browser request field is read for either (the endpoints take a handoff id
alone, `ConnectedAppsConfirmControllerMvcTest.aBrowserSuppliedChangeSetOrStagingTimeIsNeverBelieved`),
and the backend's `/api/v1/connected-apps/**` answers only the web client's own token
(`ConnectedAppsGate`), which the member never holds. The batch
carries its `stagedAt` through the frontend to the backend, and the staging lifetime of 30 minutes
counts from it everywhere: the frontend neither loads nor applies a batch older than that (`404`)
and drops expired session entries, and the backend refuses it (`403`; a `stagedAt` more than a
minute ahead of its clock is `400`). The backend re-checks what the gateway checked — the global
switch, the client active with the write capability, the client not suspended since `stagedAt`
(an `EXCHANGE_CLIENT_SUSPENDED` audit event at or after it refuses even a client active again), the
client not disconnected by the member at or after `stagedAt`, the installation not disconnected —
then previews the batch as a dry run and, on „Bestätigen", applies it without asking the guard
again, in one transaction recorded in the change log as the installation's own write and audited as
`EXCHANGE_MASS_CHANGE_CONFIRMED` (`POST /api/v1/connected-apps/mass-changes/preview|confirm`,
member session only). „Verwerfen" drops it; a batch confirmed or dropped once is gone.
*Corrected 2026-09-27 (security review L3): the session kept a loaded batch as long as the session
lived and the backend only looked for disconnects in the 30 minutes before the confirmation, so a
batch loaded before a disconnect could still be confirmed hours later.*

**Status:** built — WP 3.3 (#2083), WP 4.1 (#2084), WP 4.2 (#2085), WP 4.4 (#2086), WP 3.2 (#2082),
WP 4.5 (#2087)

### REQ-XCH-022 — Every exchange write is journaled and can be undone

Each exchange write is journaled for 90 days. The member can undo a client's writes since a point
in time from „Verbundene Anwendungen"; undo is version-checked, skips and reports rows the member
changed afterwards or a merge removed, and does not restore Materialbörse offers. An admin can run
the same undo for every member of one client at once (REQ-XCH-034).

**Acceptance**

- [x] Undo after a later web edit skips that row and reports it. *`ExchangeUndoControllerTest`.*
- [x] An undo started while a client write of the same lot runs waits for it and skips the lot as
  `CHANGED_AFTERWARDS`. *`ExchangeStockWriteConcurrencyIntegrationTest` (PostgreSQL). Corrected
  2026-09-28: the undo checked before it locked, and set the lot back over the write.*
- [x] An entry without a change-log entry is skipped, the reach follows the configured retention, a
  removed ship of a member of several units comes back without a unit, and a replaced link is not
  put back on another member's ship. *`ExchangeUndoControllerTest`.*

The journal is `exchange_journal`: one row per written entry with the client, installation, change
set, resource, key, action, whether it counts as a removal, the entry before and after as JSON, the
writing transaction's id and the time. It is written in the write's own transaction, purged with the
change feed after 90 days by `exchange_change_retention`, exported under Art. 15, stays with the
source account on a merge, and its states are searched by the Personensuche.

The member undoes from „Verbundene Anwendungen" with `POST /api/v1/connected-apps/{clientId}/undo
{since}` (member session only), reaching back at most as far as the configured journal and
change-log retention (`app.exchange.change-retention.max-age`, 90 days by default). Each entry the
client wrote in the span goes back to its state before the client's first write there — a blueprint
added or removed, a lot set back through the Lager's own book-in and book-out, a ship deleted,
updated back or recreated under a new id without its mission units — unless the entry's latest
change-log entry is not the client's last write, or is missing, then it is skipped as
`CHANGED_AFTERWARDS`: without the change-log entry nothing proves that nobody changed the entry
since. One that no longer belongs to the member or names something gone is skipped as `GONE`. A
ship is locked only when it is still the member's; one given to another member is skipped without
locking its row. The lots to restore are locked like a stock write's (ADR-0229) before any entry is
checked, and restored in the order of their keys, so an undo meeting a running write waits for it
and then skips its lot as `CHANGED_AFTERWARDS`. A recreated ship is stamped like a client's create (REQ-XCH-017): the member's
only direct org unit, or none for a member of several. Links the client made are taken back, and a
link one of them replaced is put back only while the ship is still the member's. The restored
entries' journal rows are marked undone, the undo is audited as `EXCHANGE_CHANGES_UNDONE` (restored
and skipped counts) and counted in `basetool_exchange_undo_total{resource,outcome}`, and the
member's pages refresh live. *Corrected 2026-09-27 (second security review L9): the reach was a
fixed 90 days whatever the retention, an entry without a change-log entry was restored unchecked, a
removed ship of a member of several units failed the whole undo with `OWNER_ORG_UNIT_REQUIRED`, and
a replaced link was put back on a ship that merely still existed.*

**Status:** journal and undo built — WP 3.3 (#2083), WP 4.1 (#2084), WP 4.2 (#2085), WP 4.4 (#2086),
WP 4.5 (#2087)

### REQ-XCH-023 — Rate limits, quotas and a hard Redis budget

Per-minute buckets per (client, member) and per client run in-process; daily write quotas live in
Redis (`ingest:xch:quota:*`). All gateway-written exchange data in Redis is bounded to 1 MiB per
client and member, 16 MB per client and 64 MB in total, counted per stored value with a fixed
per-entry overhead (an estimate of Redis's own bookkeeping, not a measurement of its memory — see
*The byte budget* below); above a limit the gateway
answers `503 EXCHANGE_BUDGET_EXHAUSTED` with a `Retry-After` of when enough of the budget expires
(below) and gives the write's daily quota count back. A batch holds at most 500 ops (`413 BATCH_TOO_LARGE`).
Responses carry `RateLimit` and `Retry-After` headers. The account check has its own tight limit.

**The limits** (owner decision 2026-09-27; `app.exchange.limits.*`):

| Limit | Default | Where |
| --- | --- | --- |
| requests per client and member | 120 per minute — a registry client's `requestsPerMinute` overrides it, at most 1200 | in-process bucket |
| requests per client, over all its members | 1200 per minute | in-process bucket |
| account checks per client and member | 10 per hour | in-process bucket |
| write requests per client and member | 500 per UTC day — `writesPerDay` overrides it, at most 5000 | Redis, `ingest:xch:quota:<client>:<member>:<day>`, created with its expiry by `SET NX EX`, then `INCR`; kept until the end of the following UTC day |
| live DPoP proofs per member (`dpop-proofs-per-member`) | 600, per path scope | in-process `jti` replay cache (REQ-XCH-006) |
| live DPoP proofs in total (`dpop-proofs-total`) | 100 000, per path scope | in-process `jti` replay cache (REQ-XCH-006) |
| change sets of more than 100 ops relayed at once, over all clients and members | 4 | in-process bulkhead `exchangeLargeChangeSets` (`resilience4j.bulkhead.instances.…max-concurrent-calls`) |

The write routes are the five `…/changes` and `…/drafts/…` routes (`ExchangeRoutes`). A request over
a per-period limit is `429 RATE_LIMITED`, over the quota `429 QUOTA_EXCEEDED`, each with
`Retry-After` (the quota's until the next UTC day); a quota that cannot be counted is `503
SERVICE_UNAVAILABLE` with `Retry-After: 30`, never a free pass. Every attempt at a write route
counts, retries, replays and refusals included, except a `503 EXCHANGE_BUDGET_EXHAUSTED`: the
limit filter notes the counter on the request (`ExchangeQuotas.COUNTED`) and a budget refusal gives
that one count back (a Lua `GET` and `SET KEEPTTL` that never goes below zero or creates a
counter). Before, a client that honoured the refusal's `Retry-After` burned its 500 writes in
about eight hours of refusals (load test of 2026-09-28, finding 5). Every admitted answer carries
`RateLimit-Policy: <limit>;w=60` and `RateLimit: limit=…, remaining=…, reset=…` for the member's
bucket. The in-process buckets live per gateway instance and are bounded (least recently used out).

**The relay's capacity** (load test of 2026-09-28, finding 3). The exchange relay has its own JDK
client, circuit breaker (`exchange`) and bulkhead. *(They were kept apart from the extractor's
handoff relay — `BackendImportClient`, breaker `backend` — which is removed since 2026-09-28.)* A change set of more than 100 ops takes one of four slots while it is
relayed; without a free slot it is not relayed but answered `503 RELAY_BUSY` with
`Retry-After: 10`, counted as `relay_busy`, and — a `5xx` — never cached for its key; like a
budget refusal it gives its daily write-quota count back (owner decision 2026-09-28). A set of at
most 100 ops needs no slot. The relay's read timeout is **30 s** (the removed extractor relay's was
15 s): a
500-op stock set took up to 10.6 s at p99 with four in flight on a member with about 15 000
journal rows (4.2 s on fresh data), and past the timeout the gateway answered `502` while the
backend still committed, so the client's retry met `VERSION_CONFLICT` on its own write. The
timeout stays below the idempotency claim's two minutes and the edge proxy's 90 s. Why four:
one to four concurrent 500-op sets kept the p50 at 2.1 s, eight rose to 2.9 s (and on a member
with history reached the old 15 s timeout), sixteen collapsed to 12.4 s with the backend at its
3-CPU limit. The breaker's and the bulkhead's meters, `ExchangeLargeChangeSetsBusy` and the
Exchange dashboard's *Relay capacity* row watch it (REQ-XCH-028).

**In front of them**, before the token is read, the ingest-wide per-IP bucket
(`RateLimitingFilter`, REQ-INGEST-005; `app.rate-limit.ip-capacity` / `ip-refill-tokens`, 120 a
minute) covers every `/v1` and `/exchange` request, the anonymous schema reads included. Its
`429 RATE_LIMITED` carries `Retry-After` but no `RateLimit` headers and no `DPoP-Nonce`, and every
member and client behind one address shares it, so the per-client 1200 a minute cannot be reached
from a single address.

**Per instance, not per deployment.** The three in-process buckets — requests per client and member,
per client, and the ten account checks an hour — and Spring's DPoP `jti` replay cache (REQ-XCH-006)
are held in each gateway process's memory. A second gateway instance behind the edge would therefore
multiply every per-minute and per-hour limit by the number of instances and let a proof replayed to
the other instance pass its `jti` check within the 30-second `iat` window; the nonce key is drawn per
process too, so instances would also reject each other's nonces. The daily write quota, the
idempotency keys and the byte budget live in Redis and hold across instances. **Production runs
exactly one gateway**: one `ingest.container` Quadlet unit (`ContainerName=ingest`) and one `ingest`
compose service (`container_name: ingest`) with no replicas, checked 2026-09-27. Scaling it out
needs these moved to Redis first — or accepted as a new risk — and this requirement changed with it.

**The byte budget** (`app.exchange.store.*`): every value the gateway stores for the exchange
registers `<key>|<charge>` in three sorted sets — `ingest:xch:budget:m:<client>:<member>`,
`…:c:<client>` and `…:all` — scored by its expiry, and each set keeps its running total beside it
(`ingest:xch:budget-sum:m:…`, `…:c:…`, `…:all`). An entry's charge is the value's bytes plus a fixed
512 bytes for its three set members and the key's own bookkeeping in Redis. One Lua script does each
step atomically: it prunes up to 1000 expired entries per set (subtracting them from the total),
checks all three limits and records the entry — so parallel writes cannot overshoot a budget, and no
step reads a whole set. A total that is missing is rebuilt from its set once; the sets and totals
expire together, never before their longest entry.

Before a write runs, the gateway reserves the largest cacheable answer (32 KiB) plus its lock against
all three budgets under the lock's key and refuses with `503 EXCHANGE_BUDGET_EXHAUSTED` when one
would overflow, so a full budget stops writes before they reach the backend. A cacheable answer then
takes the reservation's place in one step, or is not cached when it does not fit; otherwise the
reservation is freed. A staged draft or mass change reserves its exact staged size under an
`ingest:xch:pending:<uuid>` name before it is written and is settled on its handoff key afterwards.
A quota counter is recorded on every write, before the counter is touched and without a limit check
— the write still needs its own reservation. Its entry has a fixed name and expiry, so recording it
again counts it once; the counter itself is created with that expiry in one command (`SET NX EX`)
and only then incremented, so no crash between two commands can leave it without an expiry or
outside the budget. `basetool_ingest_exchange_budget_used_ratio` reports the total's use and
`ExchangeBudgetHigh` fires above 80 % of it;
`basetool_ingest_exchange_client_budget_used_ratio{client_id}` reports each registry client's use
of its own budget and `ExchangeClientBudgetHigh` fires above 80 % of that. The second is the one that warns with a single client: its 16 MiB are a
quarter of the total, so the total's gauge reads 25 % at most while that client's writes are all
refused (load test of 2026-09-28, finding 4). Both are the value at the last write.

**When a refused write may retry.** The sets are scored by expiry, so the refusal's
`Retry-After` is read from them: for every scope the write's charge would overflow, a read-only
script walks up to 1000 entries in expiry order until the bytes expiring cover the overflow, and
the longest such wait is the answer, in whole seconds rounded up — at least 1 and at most 3600,
the cap also when the entries read never free enough, and 60 when Redis cannot be read. A full
budget frees only as cached answers expire, up to 24 hours; the cap makes a client ask again at
least hourly instead of parking it for a day.

What the budget counts, and what stays an estimate:

| Counted | How |
| --- | --- |
| a write in flight | its 32 KiB reservation plus its lock, recorded (not only checked) under the lock's key |
| a cached answer | its serialized value and key, replacing the reservation |
| a staged draft or mass change | the staged value with its handoff wrapper, the same size the cap is checked against |
| a quota counter | its key plus 20 bytes for the number |
| the sorted sets and totals themselves | the fixed 512 bytes per entry — an estimate, not measured |

The budget therefore bounds the exchange's data within a known margin of Redis's real memory use;
it is not a byte-exact measurement of it.

The ingest ACL user runs these scripts with `EVAL`/`EVALSHA`; Redis checks every command a script
issues against the same user's key patterns and commands (REQ-SEC-068).

**Acceptance**

- [x] A load test fills one member's budget, then one client's; sessions and other members keep
  working. *`ExchangeStoreRedisIntegrationTest` fills one member's and then one client's budget in a
  real Redis under the ingest ACL user; other members and clients keep fitting, and expired entries
  free their bytes. Sessions live under keys the ingest user cannot reach at all. End to end through
  the gateway, `scripts/sandbox-load.py budget` fills the member budget at its 1 MiB default and the
  client and total budgets lowered to 2.5 MiB, checking every admission against the live sets
  (REQ-XCH-029).*
- [x] The exchange relay does not share the extractor relay's breaker, and more than four large
  change sets at once are refused before the backend. *`ExchangeRelayTest` (an open `backend`
  breaker leaves the relay working; a busy bulkhead refuses `RELAY_BUSY` without calling the
  backend; a failed set frees its slot), `ExchangeChangeRouteTest` (101 ops take a slot, 100 do
  not; `RELAY_BUSY` carries `Retry-After: 10`, is not cached and gives its quota count back, a
  relayed set keeps it), `RestClientConfigTest` (the
  exchange client waits past 15 s; its timeout ends within the claim),
  `Resilience4jMetricsConfigTest`, `exchange_relay_capacity_alerts_test.yml`.*
- [x] Parallel writes never overshoot a budget. *`ExchangeStoreRedisIntegrationTest`: sixteen
  parallel reservations on one member admit exactly what fits, and across two clients exactly what
  the total holds; a reservation settles on its value or stays when the value does not fit; a missing
  total is rebuilt. `RedisAclIngestIntegrationTest`: a script under the ingest user reaches no key the
  user could not.*
- [x] A budget refusal gives its quota count back and says when enough of the budget expires; each
  client's use is a gauge with its own alert. *`ExchangeStoreRedisIntegrationTest` under the
  ingest ACL user: a refusal restores the counter and answers `Retry-After: 600` when the blocking
  answer expires in ten minutes; the wait is the expiry that frees enough bytes; a refund keeps the
  counter's expiry, stops at zero and creates nothing; each client's gauge reads its scope.
  `ExchangeIdempotencyFilterTest`, `ExchangeChangeRouteTest`, `ExchangeDraftRouteTest`;
  `exchange_client_budget_alert_test.yml`.*

**Status:** built — WP 3.2 (#2082); the production Redis size and ACL follow with the go-live,
WP 2.1 (#2092)

### REQ-XCH-024 — A minimum client version can be enforced

The registry holds a minimum version per client. A request whose `User-Agent`
(`<Product>/<semver> (+url)`) names an older version is refused with
`403 CLIENT_VERSION_UNSUPPORTED`. The gate is cooperative: it stops honest old releases, not a
client that lies.

A missing or unparseable `User-Agent` counts as older, and a pre-release of the minimum itself
(`2.4.0-beta.1` against `2.4.0`) is below it, while build metadata (`2.4.0+7`) is not.

**Enforced by:** `ClientVersionsTest`, `ExchangeGateTest` · **Status:** built — WP 3.2 (#2082)

### REQ-XCH-025 — Errors are problem+json with a stable code

Every error is RFC 9457 problem+json with a `code` from the registry in `docs/exchange/errors.md`,
each with its HTTP status and the client action it requires. Codes are never reused or repurposed.
The gateway's gates count their refusals on `basetool_ingest_exchange_refused_total` with the code
as the `reason` label (`ExchangeRefusals.CODES`); the routes' own answers (`SCHEMA_INVALID`,
`BATCH_TOO_LARGE`, `PAYLOAD_TOO_LARGE`, `CURSOR_EXPIRED`, `BACKEND_RELAY_FAILED`, a staging store's
`503`) and those written before the token is read are not counted there; `RELAY_BUSY`, the relay's
refusal of a large change set without a free slot (REQ-XCH-023), is, as `relay_busy`. *Corrected 2026-09-28: this
said every gateway-side code was such a label.*

No answer on an exchange route falls outside the registry. A body that is not a JSON document is
`400 SCHEMA_INVALID` with one error at the pointer `""` (on any other path it stays `BAD_REQUEST`); a body of another media type is `415 UNSUPPORTED_MEDIA_TYPE`; the bot filter
answers an exchange path it blocks with `404 NOT_FOUND`, as the gate answers an unknown route or
method, and a query parameter without a name with `400 SCHEMA_INVALID` at `/`; an unexpected
failure is the generic `500 INTERNAL_ERROR`. *Changed 2026-09-28: the first two answered
`400 BAD_REQUEST` and a `415` without `code`, and the bot filter a bare `405`, `404` or `400`.*

A backend refusal reaches a client with its registry code and a **fixed English detail per code**
(`ExchangeRelay.DETAILS`); the backend's own `detail` is never relayed. The backend's
`@exchangeGate` re-checks what the gateway's registry gate checked and refuses with the
gateway's own codes (`EXCHANGE_DISABLED`, `CLIENT_NOT_ALLOWED`, `CLIENT_SUSPENDED`,
`INSTALLATION_REVOKED`, `CLIENT_REVOKED`, `SCOPE_MISSING`, `REGISTRY_UNAVAILABLE`); the relay
passes each of them only with the status the gateway's gate answers it with
(`ExchangeRelay.GATE_STATUSES`, a `503` included), with the gateway gate's `detail` and, for the
two `503`s, its `Retry-After: 30`, and the service document answers such a refusal instead of
a document. Any other status for such a code, and any other `5xx`, stays `502
BACKEND_RELAY_FAILED`. A `401` or `403` without a passed-through code refuses the gateway's own
service-account token (ADR-0129): it is answered `502 BACKEND_RELAY_FAILED` too, and the relay
drops the cached token so the next call mints a fresh one (owner decision 2026-09-28). So a request the gateway admitted from its five-second registry cache and
the backend refused reads exactly as the gateway's own refusal (security of REQ-XCH-003/-008). The security review of
2026-09-27 audited what the backend puts there on the passed-through codes. The gate filters
(`TERMS_NOT_ACCEPTED`, `PENDING_APPROVAL`, `NO_ROLE`, `ACTING_MEMBER_REFUSED`), `ACCESS_DENIED`,
`VALIDATION_FAILED`, `OPTIMISTIC_LOCK` and the exchange layer's own `ExchangeProblemException` codes
write fixed or bundle texts, and every exchange-reachable throw site found wrote a fixed text or a
message key. But nothing makes that hold: `GlobalExceptionHandler` answers an `AppException` of
kind `BAD_REQUEST` — which relays as `SCHEMA_INVALID` — with the exception's message verbatim
whenever it is no bundle key, a `ResponseStatusException` with its reason, and a Spring
`ErrorResponseException` with a body that names request parameters and headers. The exchange writes
run through the Hangar, Lager and Blueprint services, whose messages can grow a name, an id or a
value at any time, so the gateway replaces the detail rather than trusting every present and future
message. `ExchangeRelayTest` feeds each passed-through and translated code a detail with a name,
another member's id, SQL and a class name and checks none of it arrives.

**The fields** (`Problems.of`, `problem.schema.json`, listed in `docs/exchange/errors.md`): `status`,
`code`, `title` and `detail` always; `correlationId` always, equal to the `X-Correlation-Id` response
header, which `CorrelationIdFilter` takes from the request when it is 1–128 characters of
`[A-Za-z0-9._-]` and mints as a UUID otherwise; `instance` (the path) on the answers the routes
build, not on the filters'; `errors` with the gateway's `SCHEMA_INVALID`; `confirmationUrl` with
`MASS_CHANGE_CONFIRMATION_REQUIRED`. `type` and `retryAfterSeconds` are never sent — the schema
reserves the latter, and `Retry-After` is the header. The schema allowed a `correlationId` of at most
64 characters while the gateway echoes one of up to 128; the schema was widened to 128 on
2026-09-27 (widening is compatible within v1, REQ-XCH-026).

**Enforced by:** `ExchangeContractTest` (the registry's codes are unique and carry error statuses,
and every code the gateway answers on its own is registered), `ExchangeRelayTest` (no backend detail
reaches a client; the gate codes pass with their status only), backend `ExchangeGateTest` and
`ExchangeCatalogControllerTest` (the backend's gate answers the gateway's codes), `ExchangeChangeRouteTest` and `BotProtectionFilterTest` (the unreadable body, the
media type and the bot filter's answers on exchange paths) · **Status:** registry published — WP 0.2
(#2080); the gateway's refusal metrics carry the codes as `reason` labels (`ExchangeRefusals`) — WP
3.2 (#2082)

### REQ-XCH-026 — The contract grows additively under `/exchange/v1`

Within `v1` only additive changes are allowed; a breaking change is `v2`, served in parallel for at
least 12 months with `Deprecation` and `Sunset` headers. Readers are tolerant both ways (unknown
fields ignored and reported as `warnings`, unknown enum values `UNKNOWN`), published schemas stay
open, extensions are namespaced, identifiers and cursors are opaque (ADR-0219).

The gateway finds the fields a request body carries that its schema does not declare — at any
depth, following `$ref`, `allOf`, `anyOf` and `oneOf`, and leaving objects that accept any field
(`extensions`) alone — and reports each as an `UNKNOWN_FIELD` warning with its JSON Pointer in answers
that carry `warnings` (the resolve and change results); elsewhere they are ignored. A warning's
pointer holds at most 200 characters, so a body whose undeclared field's pointer is longer is
refused before the relay with `400 SCHEMA_INVALID`, `errors[]` naming the field's parent — otherwise
a write the backend had committed would have ended in a `502` for an answer breaking its own schema,
and never been cached. Every `errors[]` pointer is likewise shortened to its longest ancestor of at
most 200 characters.

**Acceptance**

- [x] A contract test fails a change that removes or narrows anything in a v1 schema: CI copies
  the latest release's schemas to `ingest/build/exchange-baseline/` and
  `ExchangeContractTest.theSchemasOnlyGrewSinceThePreviousRelease` compares them with
  `SchemaCompatibility`, whose rules `SchemaCompatibilityTest` pins. Until a release carries the
  v1 schemas the comparison has nothing to compare and is skipped.
- [x] A narrowing the owner accepts as a correction is listed by its exact comparison message, with
  its reason, in `ExchangeContractTest.ACCEPTED_NARROWINGS`; any other narrowing still fails, and the
  entry is proven to match exactly what the comparison reports for it.

> [!note] 2026-10-03 — `refinery-draft` requires at least one order
> `refinery-draft.schema.json` gains `"minItems": 1` on `orders`, and the fixture
> `refinery-draft/valid/minimal.json` (`"orders": []`) moves to `invalid/no-orders.json`. This
> narrowing aligns the published schema with behaviour the backend has always had: its extract
> carries `@NotEmpty` on `orders`, so no request with an empty `orders` ever succeeded — it was
> answered `400`, relayed to the client as `SCHEMA_INVALID`. It is therefore not treated as a
> breaking change and does not open `v2`. Decided by the owner on 2026-10-03. The client still gets
> `400 SCHEMA_INVALID`; the refusal now comes from the gateway before the relay, with `errors[]`
> naming `/orders`, instead of the backend's relayed refusal without `errors[]`.

**Enforced by:** `ExchangeContractTest`, `SchemaCompatibilityTest` · **Status:** implemented —
WP 0.2 (#2080)

### REQ-XCH-027 — Approved clients meet the client security requirements

A client stores tokens only in the platform's secret store (Windows Credential Manager / DPAPI;
Linux Secret Service, with a `0600` file fallback in a `0700` directory and a visible hint), keeps the DPoP private key
non-exportable where the platform allows, never writes a token into logs, backups, diagnostics or a
problem-report channel, pins the production issuer and allows another only through a developer
environment variable, shows the device login's `user_code` with the bare `verification_uri` and
never opens, shows or sends `verification_uri_complete` (owner decision 2026-09-27, security review
2 of #2092, M1), and sends a descriptive `User-Agent`. It also syncs as the sync guide
requires: each resource an opt-in, pull before push, an add-only first sync, removals only from a
diff, no re-add of what the member removed elsewhere without asking, ships linked before created,
and the account check before a new game account's first sync. Three more are approval criteria
(owner decision 2026-09-27, from the review of the VerseKit requirements, #2089): the baseline, the
ship links and the cursors are kept per installation and idempotency keys are random; the member
is shown `detachedFromMissions`, `offersReduced` and `offersRemoved` when a sync reports them; and
every ship `upsert` sends the ship's current `name` and `location`, since an omitted one is cleared.
Two numbers are binding too (owner decision 2026-09-27): a retry backs off from **5 s**, doubling up
to at most **5 min**, with random jitter, and never waits less than the answer's `Retry-After`; a
client syncs on start and after a local change, and a timed sync runs at most every **5 min**.
A sandbox demonstration is recommended, not a criterion. The checklist is
`docs/exchange/client-security.md`; the application template asks for each point.

**Status:** the checklist `docs/exchange/client-security.md` is written — WP 4.6 (#2090); the
clients' implementations with WP 5.1 (#2088), WP 5.2 (#2089)

### REQ-XCH-028 — The exchange is observable per client

The gateway and the backend export per-client request, error, write and budget metrics under
bounded labels (REQ-OBS-011), alert on a registry change, on 80 % of the Redis budget and on error
spikes per client, and a blackbox probe checks `GET /exchange/v1` for `401`. The nightly purge of
tombstones and journal reports task metrics.

- [x] The gateway's refusal and relay counters carry a `client_id` bounded by the registry, the
  operations dashboard shows them per client, and `ExchangeClientRefusalsSpike` alerts on one client's
  refusals outside its own limits. *`ExchangeRefusalsTest`, `ExchangeGateTest`,
  `exchange_gateway_alerts_test.yml`.* The admin page links there instead of showing an error rate
  itself (owner decision 2026-09-27).
- [x] Registry changes and the Redis budget alert (`ExchangeRegistryChanged`, `ExchangeBudgetHigh`,
  and per client `ExchangeClientBudgetHigh`).
- [x] A blackbox probe checks `GET /exchange/v1` for exactly `401` (`blackbox-http-401`, module
  `http_401`; `BlackboxProbeFailed` covers it).
- [x] Per-client write metrics with the WP 3.3 journal, and the tombstone and journal purge task
  metrics. *The writes, undo, confirmation, removal and installation counters carry `client_id`;
  `ExchangeRemoveSpike`, `ExchangeGuardStorm`, `ExchangeInstallationSurge` and `ExchangeUnknownClient`
  alert on them (`exchange_write_alerts_test.yml`); `exchange_change_retention` purges feed and
  journal under `ScheduledJobStale`.*
- [x] Every gateway log line of an exchange request carries the registry-bounded client label and
  the route template (`exchangeClientId`, `exchangeRoute`; Loki structured metadata `client_id`,
  `route`). *`ExchangeGateTest`, `CorrelationIdFilterTest`.*
- [x] `basetool_exchange_clients{status}` counts the registry clients per status from the snapshot
  the mirror sync reads, without a query per scrape. *`ExchangeClientGaugesTest`,
  `ExchangeRegistryMirrorIntegrationTest`.*
- [x] `basetool_exchange_registry_mirror_age_seconds` is the time since the gateway's last good read
  of the mirror, which it reads every 30 s; `ExchangeRegistryMirrorStaleAtGateway` alerts above
  5 minutes while the mirror is enabled. The document's `writtenAt` is not used, because the backend
  rewrites the mirror only on a change. *`ExchangeRegistryReaderTest`,
  `exchange_mirror_age_alerts_test.yml`.*
- [x] A dedicated Grafana dashboard „Exchange" (`15-exchange.json`) shows all of the above per
  client, with the gateway's log lines filtered by client.
- `basetool_ingest_gate_enforcing` is not extended to the exchange gates, since they cannot be
  switched off (owner decision 2026-09-27).

**Status:** built — WP 3.3 (#2083), #2091 (the monitoring extras of 2026-09-27 included); the
runbooks live in the knowledge base

### REQ-XCH-029 — Third parties get a local sandbox

Public sandbox images and a `sandbox` compose profile run the ingest gateway, the backend and a
Keycloak realm with a test client and seeded data on a developer's machine. The sandbox issuer is
`http://host.docker.internal:18080/auth/realms/iri`. No production credential or artefact enters it.

The sandbox is `docker-compose.sandbox.yml` on the test stack, started, reset and stopped by
`scripts/sandbox.sh` / `scripts/sandbox.ps1`, with the committed throwaway values of
`docker/sandbox/sandbox.env` and the committed test TLS. Its realm is generated by
`scripts/build-sandbox-realm.py` from the shared test realm base
(`scripts/keycloak/test-realm-base.json`) and the production provisioner's scopes, realm settings,
ingest gateway client and third-party template (`sandbox-client`, `sandbox-suspended-client`,
synthetic members with fixed ids), and `repo-lint.yml` fails when the committed realm is stale. A
one-shot `sandbox-seed` applies `docker/sandbox/seed.sql` after the backend is healthy,
idempotently. It publishes loopback ports only ([`docs/exchange/sandbox.md`](../exchange/sandbox.md)).

The E2E stack runs the same Keycloak image and the ingest gateway (ADR-0225): the same generator
writes the E2E realm (`frontend/src/e2e/resources/realm-export.e2e.json`) from the same base with the
E2E accounts, production's realm settings and login theme, and the third-party client
`e2e-exchange-client`; `E2eStackExtension` starts `ingest-dev` beside the other six services, and
`ExchangeRoundTripE2eTest` runs the exchange round trip of REQ-XCH-032 in every browser × device
cell of `e2e.yml`. `ExchangeConnectionsE2eTest` (installation revoke, reconnect, account check,
admin suspension), `ExchangeSyncE2eTest` (corpus round trip, confirmed mass change) and
`ExchangeDepartureE2eTest` run the same way, each as its own account (`test-exchange-2`,
`test-exchange-3`, `test-exchange-departed`), with the steps they share in `ExchangeE2eSupport`.

- [x] Profile, provisioned realm, seed, one command and the docs page — WP 2.3 part 1.
- [x] The public sandbox images, their publishing pipeline and secret scan.
      *`.github/workflows/sandbox-images.yml` builds `basetool-sandbox-{backend,frontend,ingest}`
      from the unchanged `docker/app/Dockerfile` plus the marker `docker/sandbox/SANDBOX`
      (`docker/sandbox/app.Dockerfile`) and `basetool-sandbox-keycloak` from
      `docker/sandbox/keycloak/Dockerfile`, all four through `docker buildx bake` on
      `docker-compose.sandbox-build.yml`, for `linux/amd64` and `linux/arm64`, each natively on
      its own runner. Before publishing it proves, on each platform's image, that an application
      image refuses the `prod` profile (`SandboxProfileGuard`, which fails the start of any image
      carrying the marker under `prod`) and fails on any secret Trivy finds. Every image carries
      `org.opencontainers.image.revision` (the commit) and `org.opencontainers.image.created`; the
      publish step joins both platforms under one tag and fails unless both carry the run's
      commit. It publishes `edge` on every push to `main` that changes what the images contain
      (a newer run cancels an older one), the version and `latest` on a release tag, and by hand
      from either; the production packages stay private and untouched. The packages' public
      visibility is set once by the owner. Its `ref-guard` job, which every build waits for,
      refuses to publish from a tag that is not `vMAJOR.MINOR.PATCH` or whose commit is not on
      `main`, the same gate as `release-images.yml` (security review G5, L3).*
- [x] An image built from a checkout (`--build`) is built like the published one and refuses
      `prod` too. *`docker-compose.sandbox-build.yml` builds each application image from
      `docker/sandbox/app.Dockerfile` on top of a build-only `<module>-base` service
      (`additional_contexts: service:`), the same file CI builds from.*
- [x] Every refusal a client must handle can be provoked in the sandbox. *The sandbox gateway
      allows 6000 requests a minute per address (`APP_RATE_LIMIT_IP_CAPACITY` /
      `APP_RATE_LIMIT_IP_REFILL_TOKENS` in `docker-compose.sandbox.yml`; production keeps 120),
      so one member's flood reaches `429 DPOP_PROOF_LIMIT`; the seed puts the Basetool's eight
      default blueprints into the catalogue, so removing one by name answers
      `DEFAULT_NOT_REMOVABLE` instead of `UNMATCHED` (by its feed key it already did). The smoke
      test checks both (`--proof-limit` for the flood).*
- [x] The sandbox Keycloak image refuses to run as anything but `start-dev`, since its realm
      carries published throwaway secrets (security review 2, L4). *Its entrypoint
      `docker/sandbox/keycloak/entrypoint.sh` passes only `start-dev …` on to `kc.sh` and ends
      every other command — `start`, `start --optimized`, `build` — with exit code 64 and a message
      naming the reason; the image's default command is `start-dev --import-realm --cache=local`,
      which the sandbox compose passes too. `sandbox-images.yml` proves the refusal before
      publishing. An explicit `--entrypoint` override is outside what an image can prevent.*
- [x] The CI job that pulls them anonymously and runs the conformance fixtures and the
      device-grant + DPoP smoke test.
      *`.github/workflows/sandbox-smoke.yml` runs after every publish (called by
      `sandbox-images.yml`), weekly and by hand, with `contents: read` only, so every image is
      pulled without a registry login. It starts the sandbox with `scripts/sandbox.sh up` and runs
      `scripts/sandbox-smoke.py`: device login with DPoP through the sandbox Keycloak, a token bound
      to the key (`cnf.jkt`) for `basetool-ingest`, the service document, the installation, every
      read resource, a resolve per kind, one blueprint, stock and ship sync, the refused removal of
      a default blueprint, with `--conformance` every change-set fixture of
      `docs/exchange/examples/v1` (valid ones as dry runs accepted, invalid ones refused) and with
      `--proof-limit` a flood until `429 DPOP_PROOF_LIMIT`; then the same without the fixtures and
      the flood as the second member, whose org demand must be withheld (`--demand withheld`).*
- [x] The E2E stack with the sandbox Keycloak and the ingest gateway, and the exchange round trip on
      it. *`E2eStackExtension`, `docker-compose.e2e.yml`, `ExchangeRoundTripE2eTest`, the
      `build-stack` job of `e2e.yml`; `E2ePrebuiltImageParityTest`, `build-sandbox-realm.py
      --selftest` / `--check` (ADR-0225).*
- [x] A load test of the gateway and its Redis budget runs against the sandbox, never production
      (#2092). *The realm and the seed carry sixteen synthetic members `sandbox-load-01` …
      `sandbox-load-16` without data. `scripts/sandbox-load.py throughput` signs them in and drives
      snapshots in cursor pages, feed pages and change sets up to the 500-op cap and near the 32 KiB
      result cap at a set rate, and reports latency percentiles, status and code counts, the
      exchange metrics, Redis memory and the budget totals; `budget --scope member|client|total`
      fills one budget and checks every admission against the live budget sets. The device login
      and the DPoP-signed call it shares with the smoke test live in `scripts/sandbox_client.py`.
      `docker-compose.sandbox-load.yml` adds the management ports, which the metrics need, and
      makes the budget and the answers' lifetime settable.*

**Status:** built — local sandbox, the image pipeline, its smoke job and the E2E stack with the
gateway, WP 2.3 (#2099)

### REQ-XCH-030 — Exchange writes appear live

After each committed exchange write the backend publishes live-sync frames on the topics and
sections the web pages and the app listen on (Lager, Blueprints, Hangar, and the Materialbörse when
offers changed) — `ExchangeLiveSync`, see REQ-XCH-013.

**Status:** built for the web pages — `ExchangeLiveSync`, the frames of REQ-XCH-013 — WP 3.3
(#2083), WP 4.1 (#2084), WP 4.2 (#2085), WP 4.4 (#2086)

### REQ-XCH-031 — The account check answers match, mismatch or unknown — never the handle

`POST /exchange/v1/me/account-check {handle}` compares the handle with the optional RSI handle on
the member's profile (REQ-SEC-072, stored since WP 1.4), case-insensitively, and answers `match`, `mismatch` or `unknown` (no handle
stored). It never returns or logs the stored handle and is rate-limited tightly.

The backend answers the relayed call at `POST /api/v1/exchange/me/account-check` under
`exchange.connect`, validates the handle with the profile's own pattern (`^[A-Za-z0-9_-]{3,60}$`,
`400` otherwise, without echoing it) and counts every answer in
`basetool_exchange_account_checks_total{outcome}`.

The gateway checks the body against `account-check-request.schema.json` — a value that is no RSI
handle is `400 SCHEMA_INVALID` naming only the pointer, never the value — relays it to the backend's
`POST /api/v1/exchange/me/account-check`, and passes on only an answer that matches
`account-check-response.schema.json`. The route is no write: it needs no `Idempotency-Key` and does
not count against the daily quota, but it has its own limit of ten per hour per client and member
(REQ-XCH-023).

- [x] Match, mismatch and unknown, the match case-insensitive; the stored handle is in no answer and
  neither handle in a log line. *`ExchangeAccountCheckControllerTest`.*
- [x] The gateway relays the route inside its own hourly limit; a value that is no handle is neither
  relayed, echoed nor logged. *`ExchangeControllerTest`, `ExchangeLimitFilterTest`.*
- [x] End to end on the E2E stack, which runs the sandbox Keycloak and the gateway (ADR-0225).
  *`ExchangeConnectionsE2eTest.theAccountCheckAnswersUnknownMatchAndMismatchWithoutTheHandle`:
  `unknown` before the member stores a handle on the profile, a case-insensitive `match` and a
  `mismatch` after, each answer carrying `result` only.*

**Status:** backend and gateway relay built — WP 3.4 (#2106); end to end on the E2E stack
(`ExchangeConnectionsE2eTest`)

### REQ-XCH-032 — „Verbundene Anwendungen" shows and controls every connection

The web page „Verbundene Anwendungen" lists the member's connected clients with their capabilities,
installations (label, first and last seen) and recent activity, and lets the member disconnect one
installation or a whole client, undo, and confirm a staged mass change. Every new connection or
installation raises a notification and stays highlighted until seen. The page links the public
list of approved clients (REQ-XCH-002, REQ-SEC-027). `ADMIN` manages the registry on an admin page
with a suspend switch. The page is web-only; the app links to it.

The page is `/connected-apps` (sidebar *Persönlich*, every member), over `/api/v1/connected-apps`.
Its header links `docs/legal/approved-clients.md` on GitHub
(`https://github.com/krt-profit/basetool/blob/main/docs/legal/approved-clients.md`) in a new tab,
the address the developer site's onboarding page links as well.
An installation is always named as `‹client name› – „‹label›"`, the client-supplied label escaped
and never first, so a label cannot pose as the Basetool. Both disconnects ask first and re-swap the
`connected-apps :: apps` fragment; the page is the member's own and joins no peer sync.

The notification is the rule-engine event `EXCHANGE_INSTALLATION_CONNECTED` (seed `V251`,
`EVENT_RECIPIENT`), published when the installation upsert reports that it created the row, so two
concurrent first calls announce one installation once. It names the client by its registry display
name only: the client-supplied label arrives with a later call and could pose as the Basetool. An
installation counts as unseen while its notification is unread, and `GET /api/v1/connected-apps`
says so per installation (`unseen`). Opening the page marks nothing: the row stays highlighted, under
a warning to disconnect a connection the member did not start, until the member acknowledges that
row with „Gesehen" (`POST /api/v1/connected-apps/installations/{id}/seen`, which marks only that
installation's notification read) or reads the notification — a notification change only, not
audited.

- [x] List the clients with their capabilities and installations (label, first and last seen), and
  disconnect one installation or a whole client. *`ConnectedAppsPageControllerMvcTest`.*
- [x] The admin registry page. *See REQ-XCH-003.*
- [x] The page links the public list of approved clients, opening in a new tab.
  *`ConnectedAppsPageControllerMvcTest.thePageLinksThePublicListOfApprovedApplicationsInANewTab`.*
  *Corrected 2026-09-28: #2087 required the link, but this requirement did not name it and the
  page shipped without it (epic #2078 plan audit).*
- [x] A new installation notifies its member once, by the client's name; the list reports it
  unseen until marked seen. *`ExchangeInstallationServiceTest`, `ExchangeInstallationControllerTest`,
  `ConnectedAppsControllerTest`.*
- [x] The page highlights an unseen installation („Neu") with a warning and reports it seen only
  when the member acknowledges its row („Gesehen"), one installation at a time; loading the page
  marks nothing. *`ConnectedAppsPageControllerMvcTest`, `ConnectedAppsControllerTest`.*
  *Corrected 2026-09-27:* this item first read „and then reports it seen; the highlight ends with
  the next load" — the page marked every new connection seen on its first load, which made the
  highlight a weak phishing signal (security review 2 of #2092).
- [x] Undo a client's changes since a chosen span, with the skipped entries listed.
  *`ConnectedAppsPageControllerMvcTest`, `ExchangeUndoControllerTest`.*
- [x] Confirm or discard a staged mass change. *`ExchangeMassChangeControllerTest`,
  `ConnectedAppsConfirmControllerMvcTest`; end to end
  `ExchangeSyncE2eTest.aHeldMassRemovalAppliesOnlyOnceTheMemberConfirmsIt` — a batch emptying five
  lots is answered `409 MASS_CHANGE_CONFIRMATION_REQUIRED` and writes nothing until the member
  confirms it on the page its `confirmationUrl` opens.*
- [x] Recent activity: each client's last ten writes to the member's data, newest first, named by
  blueprint, material or item, or ship type, undone ones marked. *`ConnectedAppsControllerTest`,
  `ConnectedAppsPageControllerMvcTest`.*
- [x] The end-to-end run: a client connects through the device grant, syncs a blueprint and a
  stock lot, and the member sees it with its installation, the „Neu" highlight and its activity,
  undoes both writes and disconnects it in place, after which the gateway answers `401
  CLIENT_REVOKED`. *`ExchangeRoundTripE2eTest` on the E2E stack with the sandbox Keycloak and the
  gateway (ADR-0225).*

Each client in `GET /api/v1/connected-apps` carries `activity`: its last ten journal rows for the
member, newest first, each with the time, resource, action, the entry's name (read in one lookup per
catalogue) and whether it was undone.

**Status:** built — WP 4.5 (#2087); end to end on the E2E stack — WP 2.3 (#2099),
`ExchangeConnectionsE2eTest`, `ExchangeSyncE2eTest`

### REQ-XCH-033 — The legacy extractor endpoints end at the go-live

> **Amended 2026-09-28 — removed (#2092 step 9, owner decision of the same day).** Production ran
> with the switch off from the go-live (S14), and extractor 2.10.0 uses only `/exchange/v1`. The
> routes are now removed entirely, **without** a `410` stub: `LegacyEndpointGoneFilter`, the flag
> `app.ingest.legacy-endpoints.enabled` (`IRI_INGEST_LEGACY_ENDPOINTS_ENABLED`),
> `LegacyClientGateGuard`, the client-identity gate of REQ-INGEST-011 and the two metrics are gone.
> A request under `/v1` gets what any unknown path of the gateway gets — `404` with a token the
> decoder accepts, `401` with one it refuses, `403` from the CSRF filter without a token
> (`RemovedExtractorRoutesTest`) — so a 2.9.1 extractor shows its generic send error, not the German
> update hint. `LEGACY_ENDPOINT_GONE` stays in the error registry as retired, never to be reused. The
> go-live runbook's S14 rollback („Legacy flag back") is impossible from this release on; going
> back to `/v1` means rolling the gateway back to a release before it. The text below is the
> record.

Behind `app.ingest.legacy-endpoints.enabled` (default `true`), `/v1/refinery-extract` and
`/v1/blueprint-preview` answer `410 LEGACY_ENDPOINT_GONE` with a German update hint once the flag is
`false` at the go-live. While it is `true` their behaviour is unchanged, and under `prod` the gateway
refuses to start with an empty or audit-only client-id allowlist, so an exchange client's token can
never reach these relays, which run with the member's stored authorities (REQ-INGEST-011,
`LegacyClientGateGuard`; production sets the allowlist and enforces it, so the next deploy is
unaffected). The routes also refuse every client the exchange registry lists unless the allowlist
names it too (`exchange_client`, review 2 L1); a registry that cannot be read skips that check.

The switch is `IRI_INGEST_LEGACY_ENDPOINTS_ENABLED` on the host. The refusal runs before the security
chain, so an outdated extractor sees the hint (*„Diese Schnittstelle wurde abgeschaltet. Bitte
aktualisiere den SC Extractor auf die neueste Version."*) whether or not its token is still
accepted. `basetool_ingest_legacy_endpoints_enabled` reports the switch and
`basetool_ingest_legacy_gone_total` counts the refusals.

**Acceptance**

- [x] With the flag off both legacy routes answered `410 LEGACY_ENDPOINT_GONE` with the German
  hint, before authentication (`LegacyEndpointGoneFilterTest`, removed with the routes).
- [x] The flag was switched off on production at the go-live (S14, 2026-09-28).
- [x] The routes, the flag and the stub are removed; nothing under `/v1` is routed
  (`IngestEndpointSurfaceTest`, `RemovedExtractorRoutesTest`).

**Status:** switch built — WP 3.2 (#2082); switched off with WP 6 (#2092, S14); removed with #2092
step 9 (2026-09-28)

### REQ-XCH-034 — An admin can undo one client's writes for every member

An `ADMIN` undoes one client's writes since a chosen time for **all** members at once — after a
malicious or faulty release — from *Administration → Verbundene Anwendungen*. The run first
suspends the client through the registry's own path, then undoes member by member with the
member's undo semantics (REQ-XCH-022), lists what it left alone, notifies every member whose data
it changed, and is audited and instrumented. Re-activating the client stays a separate admin action
(owner decisions 2026-09-27; ADR-0227).

**Acceptance**

- [x] Starting suspends an active client through `ExchangeRegistryService.suspendClient` (mirror
  first, audited as `EXCHANGE_CLIENT_SUSPENDED`), then undoes every member's writes in scope with the
  member's undo semantics: an entry changed afterwards is skipped as `CHANGED_AFTERWARDS` and listed.
  *`ExchangeBulkUndoControllerTest`.*
- [x] A run can be limited to one installation and/or one resource; the rest stays untouched.
  *`ExchangeBulkUndoControllerTest`.*
- [x] A member that cannot be undone rolls back only its own transaction; the run ends `FAILED`, the
  other members stay undone, and `ExchangeBulkUndoFailed` alerts. *`ExchangeBulkUndoControllerTest`,
  `exchange_write_alerts_test.yml`.*
- [x] One run per client at a time (`409`); a run a restart cut short is marked `FAILED` at the next
  start. *`ExchangeBulkUndoControllerTest`.*
- [x] A run the executor refuses is ended `FAILED` at once and counted as a failed run, the start
  answers `409`, and the client stays suspended. *`ExchangeBulkUndoRejectionTest`.*
- [x] Each member whose data the run changed gets one notification per run; the admin page lists the
  runs, refreshes while one runs and shows a run's skipped entries.
  *`ExchangeBulkUndoControllerTest`, `AdminExchangeClientsPageControllerMvcTest`.*
- [x] Another admin's open page follows a start and every registry write through the page's
  `exchange-clients` live-sync room, and then the run's progress and end (REQ-FE-015).
  *`LiveSyncSectionMapParityTest`, `LiveSyncTopicTest`, `LiveSyncTopicRegistryParityTest`.*

**The scope.** `POST /api/v1/admin/exchange-clients/{id}/undo {since, installationId?, resource?}`
(`ADMIN`, answers `202` with the run) undoes the client's journal entries recorded at or after
`since`, clamped to the configured retention (`app.exchange.change-retention.max-age`), that are not
undone yet; `resource` is `BLUEPRINT`, `STOCK` or `SHIP`, `installationId` one installation of that
client. A `since` in the future is `400`. `…/undo/preview` answers the members and entries in scope
and whether the client is still active, writing nothing; `…/undo/installations?since=` lists the
client's installations with writes in the span (member name, last seen, disconnected, count — never
the member-given label), at most 500, for choosing one. `GET /api/v1/admin/exchange-undo-runs` lists
the last twenty runs, `…/{runId}` one run with its first 200 skipped entries, failed members first.

**The run.** Starting checks that no run of the client is `RUNNING` (also a partial unique index),
suspends the client unless it is suspended already, records the run with the members in scope and
audits `EXCHANGE_BULK_UNDO_STARTED` (run, since, resource, installation, members). The run then works
on a single-thread executor (`exchangeBulkUndoExecutor`) under the starting admin's authentication,
so every write, the change log (`web`) and the audit name that admin. Each member is one
transaction: `ExchangeUndoService.undoWithinRun` restores the member's entries in scope exactly as
the member's own undo would, audits `EXCHANGE_CHANGES_UNDONE` for that member with the run id, keeps
the skipped entries by journal id and reason (no entry name is copied), adds the member's counts to
the run with one atomic update, and publishes `EXCHANGE_BULK_UNDO_APPLIED` when it restored anything.
A member whose undo throws is rolled back alone and recorded as `FAILED`. At the end the run is
`COMPLETED`, or `FAILED` when a member failed, and `EXCHANGE_BULK_UNDO_FINISHED` records the status
and the totals. A run left `RUNNING` by a restart is marked `FAILED` (`interrupted=true`) at the next
start; the admin starts it again, which touches only what is not undone yet. A run the executor
refuses because its queue of ten is full is ended `FAILED` at once (`EXCHANGE_BULK_UNDO_FINISHED`,
`interrupted=false`), counted as a failed `exchange_bulk_undo` run (`ExchangeBulkUndoFailed`) and
answered `409` with a localized detail; the client stays suspended until an admin activates it, and
the page reloads the registry and the run list in place. There is no admin notification for bulk
undo runs, so the answer and the alert are the signal (owner decision 2026-09-28; security review
G5, I2 — until then such a run stayed `RUNNING` and blocked every new run of the client until a
restart).

**Notification.** The rule-engine event `EXCHANGE_BULK_UNDO_APPLIED` (seed `V257`, selector
`EVENT_RECIPIENT`) tells each member once per run how many entries the administration took back and
names the client by its registry display name only.

**Retention.** Runs and their skipped entries are purged with the exchange change feed and journal,
90 days after they ended; the skipped entries are part of the member's Art. 15 export
(`exchangeBulkUndoSkips`).

**Observability.** The run is the on-demand job `exchange_bulk_undo` of `TaskMetrics`
(`basetool_scheduled_job_executions_total{task,outcome}`, duration, items = members processed); its
outcome counters are registered at zero so the first failure is an increase, and
`ExchangeBulkUndoFailed` alerts on any failed run within the hour. The restored and skipped entries
count in `basetool_exchange_undo_total{client_id,resource,outcome}` like a member's undo. The
„Exchange" dashboard shows the runs per day by outcome.

**Status:** built (#2092 follow-up)

### REQ-XCH-035 — Disconnected installations and client revocations are deleted after 90 days

A disconnected installation — with the label the member gave it — and a member's whole-client
revocation are kept **90 days** after the disconnect and then deleted, not for the life of the
account (owner decision 2026-09-28, storage limitation, Art. 5(1)(e) GDPR). 90 days is as long as
any session of an exchange client can live, online or offline (REQ-XCH-005, ADR-0217), and as long as
the Redis deny and revocation entries already live (REQ-XCH-008), so no token issued before a
disconnect outlives the entry that refuses it. A live installation is never touched.

**How it is built.** The nightly job `exchange_connection_retention` (`ExchangeConnectionRetentionTask`,
03:45 UTC) calls `ExchangeConnectionRetentionService.purgeBefore(now − max-age)`, which deletes in one
transaction and in this order:

1. every installation revoked on its own before the cutoff (`revoked_at < cutoff`);
2. every installation not revoked on its own that a whole-client disconnect before the cutoff ended —
   one last seen at or before that revocation. A whole-client disconnect marks no installation; the
   member's page and the client gauges hide such an installation only through the revocation row,
   so deleting the revocation alone would show it as connected again;
3. then every client revocation older than the cutoff.

The retention is `app.exchange.connection-retention.max-age` (`ExchangeConnectionRetentionProperties`,
default `P90D`, a floor of 90 days — the session cap — below which the backend does not start;
`…enabled` turns the job off). The job also refuses to start when it is shorter than
`app.exchange.change-retention.max-age`, so an installation outlives the journal and change-feed
entries that name it: the admin bulk undo's per-installation filter (REQ-XCH-034) and a tombstone's
`removedBy.installationId` (REQ-XCH-007) still resolve. Nothing references a deleted row by key:
`exchange_bulk_undo_run.installation_id` is `ON DELETE SET NULL`, and the journal, the change feed and
the ship links hold the installation's key as a string; a staged mass change of a deleted installation
is refused like a revoked one (REQ-XCH-021).

**Audit.** A run that deleted at least one row records one `EXCHANGE_CONNECTIONS_PURGED` in
„Verbundene Anwendungen" with the installation and revocation counts and the cutoff — no subject, no
target, no label, no user id; a run that deletes nothing records nothing.

**Observability.** The job's `TaskMetrics` series carry `task="exchange_connection_retention"` (items =
installations plus revocations deleted), and `ScheduledJobStale` watches it in both halves (stopped
succeeding, never succeeded).

**Acceptance**

- [x] An installation disconnected 91 days ago is deleted, one disconnected 89 days ago kept; a client
  revocation made 91 days ago is deleted with the installations it ended, one made 89 days ago kept
  with them; a live installation, an installation reconnected after an old revocation and another
  member's installation of the same client stay; the member's page shows no ended installation again;
  a second run deletes and records nothing. *`ExchangeConnectionRetentionServiceTest`.*
- [x] A run that deleted rows records one audit event with the counts only.
  *`ExchangeConnectionRetentionServiceTest`.*
- [x] The cutoff is the configured retention before now; a failure is counted and swallowed; a
  retention shorter than the change retention, or than 90 days, refuses to start.
  *`ExchangeConnectionRetentionTaskTest`, `BackendPropertiesValidationTest`.*
- [x] `ScheduledJobStale` fires for the job when it stopped or never succeeded.
  *`scheduled_job_never_succeeded_test.yml`.*

**Status:** built (#2092)

### REQ-XCH-036 — The frozen exchange is pinned at build time

The exchange contract and the backend surface the gateway relays to keep their behaviour
byte-identical (D-05 of the domain modularisation plan): the 14 relay operations on 13 backend
paths, their parameters, request and answer shapes, the relayed codes and statuses, and the
gateway's security filters. Four build-time guards hold it, so a drift fails a build instead of
surfacing in production as a `502 BACKEND_RELAY_FAILED` or a `400 SCHEMA_INVALID` for a body the
contract allows.

- **Backend wire contract.** `ExchangeWireContractTest` sends every published `valid/` request
  fixture of a relayed route to the backend as the gateway relays it and expects `200`; every
  answer, and the answers of every read on seeded data (one blueprint, one stock lot at a UEX city,
  one ship, and the tombstones after their removal), must validate against the published schema
  the gateway checks it with. Every published answer fixture survives a round trip through the
  backend's answer type. One named exception is pinned, not hidden: the gateway's own `warnings`
  and the reserved `cursor` of a change result are members the backend never writes. The published
  `refinery-draft/invalid/no-orders.json` (`"orders": []`), which the schema refuses, is refused
  by the backend too, with `400 VALIDATION_FAILED`. Of `GET /api/v1/exchange/me/installation` the gateway takes only
  `installationId` into the service document, so only that member is checked.
- **Golden answers.** `ExchangeGoldenAnswerTest` records, for every `/exchange/v1` route and for a
  relayed refusal, a translated code, a relay failure, an answer that breaks its schema, a draft
  its schema refuses before the relay and a staged mass change, the client's request, the backend request the relay sent with every header,
  and the status, every header and the body the client got — under a fixed backend stub answering
  with the published fixtures — and compares it byte for byte with the committed goldens under
  `ingest/src/test/resources/exchange/golden/`. Only named volatile values are normalised (DPoP
  key thumbprint, connection time, correlation id, DPoP nonce, idempotency key, trace context,
  the rate-limit counters). A difference writes the observed answer under
  `ingest/build/exchange-golden/` for review; a deliberate change replaces the golden in the same
  pull request. The test also asserts that the backend stub received exactly the 14 relay
  operations.
- **Security filter order.** `SecurityFilterChainOrderTest` and
  `SecurityFilterChainProductionShapeTest` pin, through `FilterChainProxy`, the gateway's chains in
  their order, each chain's matcher and the exact ordered list of its filters — the exchange gates
  `ExchangeTokenGateFilter`, `ExchangeGateFilter`, `ExchangeLimitFilter` and
  `ExchangeIdempotencyFilter` after authentication — in the test profile and with the management
  port and the scrape credentials set.
- **The `e2e` label.** A pull request that changes the exchange path — the backend's exchange
  controllers, services and DTOs, `ActingMemberFilter`, `ActingMemberHeader`,
  `ExchangeProblemException`, `ExchangeCapability`, the ingest's `src/main`, the published
  fixtures or the shared seam definition — fails the `Exchange changes carry the e2e label` job of
  `e2e.yml` until it carries the `e2e` label, so the full E2E suite runs on it
  (`.github/scripts/check_exchange_e2e_label.py`, self-tested in `repo-lint.yml`).

Every guard is proven able to fail by a planted violation in its own test.

**Acceptance**

- [x] Every published request fixture of a relayed route is accepted by the backend; every backend
  answer validates against its published schema.
  *`ExchangeWireContractTest`.*
- [x] Every `/exchange/v1` route answers byte for byte as recorded, and the relay sends exactly the
  seam's 14 operations. *`ExchangeGoldenAnswerTest`.*
- [x] The gateway's security filter chains hold exactly the pinned filters in order.
  *`SecurityFilterChainOrderTest`, `SecurityFilterChainProductionShapeTest`.*
- [x] A pull request touching the exchange path without the `e2e` label fails.
  *`check_exchange_e2e_label.py --selftest`.*

**Enforced by:** the tests above · **Status:** implemented (plan guard G-18)

### REQ-XCH-037 — Backend, gateway and frontend share one seam definition

Every identifier the modules exchange on the relay seam is declared once, in
`test-support`'s `ExchangeSeam`, and each module's parity test asserts its own declarations against
it, so a rename on one side alone fails that side's build:

- the 14 relay operations with their gateway route, backend path, capability, paging and published
  schemas — against the backend's eight exchange controllers and their `@exchangeGate`
  expressions, `ActingMemberFilter`'s 13 exact paths, the gateway's `ExchangeRoutes` and its relay
  prefix (`ExchangeRelaySeamParityTest` in both modules), and the paths the relay actually calls
  (`ExchangeGoldenAnswerTest`);
- the five relay headers, the ten capability scopes, the seven gate codes with their statuses, the
  codes the gateway passes through and the four backend codes it translates;
- the registry mirror document: the backend's writer must produce the seam's sample document, and
  the gateway's reader must read every member of it — renaming any member it reads changes what it
  returns, so a rename can no longer fall back silently (`writtenAt` is the one member the gateway
  does not read) (`ExchangeMirrorSeamParityTest`, `ExchangeRelaySeamParityTest`);
- the revocation keys `exchange:deny:<thumbprint>` and `exchange:revoked:<client>:<member>` with the
  epoch second as value, the handoff key `ingest:handoff:<member>:<id>`, the staged handoff's
  members and kinds and the members of a staged mass change (`IngestHandoffSeamParityTest` in the
  frontend).

**Acceptance**

- [x] Each module's parity test passes on today's code and fails on a planted rename.
  *`ExchangeRelaySeamParityTest` (backend and ingest), `ExchangeMirrorSeamParityTest`,
  `IngestHandoffSeamParityTest`.*

**Enforced by:** the tests above · **Status:** implemented (plan guard G-18)

### REQ-XCH-038 — The refinery draft route has its own request type

`POST /api/v1/exchange/me/drafts/refinery-orders` binds `ExchangeRefineryDraftRequest`, not the web
import's `RefineryExtractDto`, so a change to the web import cannot change the frozen exchange
route. Its JSON shape, types, nullability and constraints are those of the web import's extract
today; `ExchangeDraftService` copies it field by field into the extract the import builds the
draft from. A later change of the web import's extract adapts that copy and leaves the exchange
request as it is.

**Acceptance**

- [x] Both records declare the same components, types and constraints; every published refinery
  draft fixture and every one-field mutation of it reads to the same JSON, the same validation
  outcome and the same extract through both types; the committed `openapi.json` describes both
  request bodies alike. *`ExchangeRefineryDraftRequestIdentityTest`.*

**Enforced by:** `ExchangeRefineryDraftRequestIdentityTest` · **Status:** implemented (plan guard
G-18)

## Threat model

| Threat | Countered by |
| --- | --- |
| Stolen refresh or access token | DPoP binding of both (REQ-XCH-005/-006); tokens only in the platform secret store (REQ-XCH-027) |
| Filling the DPoP `jti` replay cache to lock every client out | one cache per path scope, a per-member cap and a total cap; the nonce is checked first (REQ-XCH-006) |
| Device-code phishing (RFC 8628 §5.4) | the warning on the device page and, with the user code to compare, on the consent page an attacker's `verification_uri_complete` link leads to (ADR-0228); clients showing only the bare `verification_uri`; notification and a highlight of every new connection until the member acknowledges it; 600 s code lifespan (REQ-XCH-005/-027/-032) — countered, not prevented |
| A revoked installation refreshing its way back | persistent `jkt` deny list (REQ-XCH-008) |
| A member who leaves keeping access | departure revocations (REQ-XCH-008) |
| Malicious client update, compromised maintainer account | capability scoping, own-data-only, journal and undo, guard, suspension, and the admin's undo of the client for every member at once; signing recommended (REQ-XCH-002/-009/-021/-022/-034) — accepted residual risk. *Changed 2026-09-27: undo was per member only, which the owner no longer accepts.* |
| Compromised admin account (no capability ceiling) | audit area „Verbundene Anwendungen", `ExchangeRegistryChanged` alert, suspension; display names that cannot pose as the Basetool, bounded limits, no first-party client ids (REQ-XCH-003) — accepted risk (ADR-0217) |
| An admin's client running with admin authority | reduced exchange authentication and ArchUnit rule (REQ-XCH-009) |
| Confused deputy on the relay hop; forged `X-Exchange-*` headers | headers honoured only from the gateway identity; explicit relay route list (REQ-XCH-010, REQ-XCH-001) |
| Replay and cross-member idempotency replay | idempotency keyed per client and member, gates before cache (REQ-XCH-020) |
| Enumeration through resolve or account check | resolve returns catalogue data only; account check never returns the handle and is tightly limited (REQ-XCH-012/-031) |
| DoS against Redis (shared with sessions, `noeviction`) or the backend | hard byte budgets, quotas, batch cap, larger Redis (REQ-XCH-023, ADR-0221) |
| Guard evasion by batching, near-zero cuts or overwriting stock updates | window counting rules against each lot's state at window start (REQ-XCH-021) |
| Guard evasion by overwriting ship updates (retyping every ship, clearing names and locations) | counted only when one `upsert` changes both name and type, with no comparison to the window start; journal and undo restore the ships (REQ-XCH-021/-022) — accepted risk (owner decision 2026-09-27) |
| A malicious release changing many members' data at once | journal and each member's own undo, suspension, and the admin's audited undo of the client for every member at once, which suspends it first (REQ-XCH-022/-034); Materialbörse offers and mission units it removed stay reported, not undone (rows below). *Changed 2026-09-27: the admin bulk undo is built; the row still said it was being addressed.* |
| A single open order recognisable in the org demand feed | withheld unless the member passes the web's job-order gate (`canViewJobOrders`, under the reduced authorities); membership-only scope, catalogue fields only, no requester, title or free text; no low-count suppression, 7-day client cache (REQ-XCH-018) — accepted risk (ADR-0220 and its 2026-09-28 amendment) |
| Silent removal of Materialbörse offers by a sync book-out | reported and audited, not undoable — accepted (REQ-XCH-016/-022) |
| A ship removal through the exchange detaching the ship from its mission units, which are org data | reported (`detachedFromMissions`) and audited (`MISSION_UNIT_UPDATED`), not undoable: undo recreates the ship under a new id without its mission units — accepted (REQ-XCH-017/-022) |
| The version gate bypassed by a manipulated client | cooperative by design — accepted (REQ-XCH-024) |
| Data poisoning of org-wide views | own rows only, validated through the domain services; a client books in only personal rows, so it can add nothing to an org-wide view, and lowers the member's own shared rows only as far as their reservations allow, journaled and undoable (REQ-XCH-009/-016/-021/-022, ADR-0230) |
| A client booking out shared stock the org counts on | the member's own rows only, as the member may in the web; reserved stock never taken (`STOCK_EARMARKED`); offers lowered and audited; mass-change guard, journal and undo (REQ-XCH-016/-021/-022) — accepted (owner decision 2026-10-02, ADR-0230) |
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
