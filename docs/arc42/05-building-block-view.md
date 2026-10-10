# 5. Building block view

## 5.1 Level 1 — the whitebox of the whole system

```
  internet ── :80 / :443 ──► haproxy (host service, TCP only)
                                 │ PROXY protocol v2 → loopback :8080 / :8443
                                 ▼
      ┌──────────────────────────────────────────────────────────────────┐
      │ edge (nginx-unprivileged) — TLS for the four public names        │
      └───┬──────────────────┬────────────────┬──────────────┬─────────┬─┘
          │ profit-base      │ profit-base    │ ingest.*     │ api.*   │ grafana.*
          │ .online          │ .online/auth   │              │         │
          ▼                  ▼                ▼              │         ▼
    ┌──────────┐      ┌─────────────┐    ┌────────┐          │    ┌─────────┐
    │ frontend │      │  keycloak   │    │ ingest │          │    │ grafana │
    └────┬─────┘      │ SPI · theme │    └───┬────┘          │    └─────────┘
         │ WebClient  └──────┬──────┘        │ service       │    + prometheus · loki · tempo
         │ + bearer          │ OIDC · SPI    │ token         │      alertmanager · blackbox
         ▼                   ▼               ▼               ▼      exporters — the monitoring plane
    ┌─────────────────────────────────────────────────────────────────┐
    │ backend                                                         │
    └──────┬──────────────────────────┬───────────────────────────────┘
           ▼                          ▼
    ┌────────────┐             ┌────────────┐              ┌─────────────┐
    │ db-backend │             │   redis    │              │ db-keycloak │
    └────────────┘             └────────────┘              └─────────────┘

    acme (lego): writes edge-certs, answers HTTP-01 from edge-acme-webroot
```

The drawing shows the request path only. Beside it: the frontend and ingest use redis too; the
frontend and backend reach keycloak (OIDC, the admin API) and keycloak's SPI calls the backend back;
keycloak alone owns `db-keycloak`.

The connections run over **eighteen** container networks, each named for what it joins
(`net-proxy-*`, `net-db-*`, `net-redis-*`, `net-backend-*`, `net-edge-ingress`, `net-acme-egress`,
`net-monitoring-core`, `net-monitoring-scrape`, `net-blackbox-v6`). Apart from the two monitoring
networks each joins a small named set — usually one pair, plus a data store's exporter — and a
container reaches exactly the containers it shares a network with. The units are in
[`quadlet/systemd/`](../../quadlet/systemd/); the segment-by-segment reasoning is the knowledge
base's Topology note.

| Building block | Responsibility | Deliberately does **not** |
| --- | --- | --- |
| **haproxy** (host service) | Bind the public `:80`/`:443`, forward bytes to the edge's loopback ports, and state the client address in a PROXY v2 header (ADR-0187) | Terminate TLS or hold a key |
| **edge** (nginx-unprivileged) | TLS termination for four vhosts plus Keycloak under `/auth`, per-vhost rate limits, deny rules, header rewriting, the `X-Forwarded-*` family; trusts the PROXY header only from its own six pinned addresses | Hold any application logic or state |
| **acme** (lego) | Issue and renew the certificates the edge serves | Serve traffic |
| **frontend** | Render the UI, hold session state, drive live update | Talk to PostgreSQL or the Keycloak Admin API; contain business rules |
| **backend** | The whole domain: REST API, persistence, authorisation, scheduled work | Serve HTML; be reachable from the internet except through the `api` vhost |
| **ingest** | Gate, limit and relay the exchange API for approved clients, the SC Extractor included (§5.5); stage a returned draft in Redis for a one-time browser pickup | Own a database or save anything itself |
| **keycloak** | Identity, OIDC tokens, the Discord provider and guild/role gate, the KRT theme, the device-grant consent for exchange clients and the `basetool-exchange` admin extension | Store domain data |
| **db-backend / db-keycloak** | Two separate PostgreSQL instances | Share a cluster — a Keycloak upgrade must not be able to touch domain data |
| **redis** | Spring Session store, the live-sync and notification pub/sub fanout, the ingest handoffs, the exchange registry mirror and the gateway's byte-bounded exchange partition (ADR-0221) — one instance on three separate networks | Be a cache of record for anything that matters |

## 5.2 Level 2 — inside `backend`

Layered, with the direction enforced by ArchUnit rather than by convention:

`controller` → `service` → `repository` → `model`

| Package | What lives there |
| --- | --- |
| `controller` | REST endpoints, `@PreAuthorize`, `@Valid`, RFC 7807 problem responses |
| `service` | Business rules, transactions, **scoping** (`OwnerScopeService`), audit recording |
| `repository` | Spring Data JPA; fetch strategies that keep the no-N+1 rule |
| `model` | JPA entities, `@Version`, the OrgUnit hierarchy |
| `dto` / `mapper` | Records on the boundary, MapStruct between them and entities |
| `task` | Scheduled jobs |
| `integration` | Outbound third parties — `UexClient`, `scwiki` — on the blocking `RestClient` from `config.RestClientConfig` (JDK HTTP client, no WebFlux; ADR-0204), which `KeycloakService` shares |
| `metrics` / `health` / `logging` | `basetool_*` business metrics, health indicators, MDC enrichment |
| `filter` / `interceptor` / `annotation` / `validation` / `util` / `web` / `exception` / `config` | The usual Spring surface |

These layer packages are being cut into domain modules (the
[domain modularisation plan](../DOMAIN_MODULARISATION_PLAN.md), §5.1). Until a class has moved, the
**domain map** [`backend/src/test/resources/architecture/domain-map.txt`](../../backend/src/test/resources/architecture/domain-map.txt)
is the source of truth for its target module and that module's rank: every class is assigned there
by the first matching rule, and a class no rule assigns fails the build
([`module-boundaries.md`](../specs/module-boundaries.md), REQ-MOD-001/002).

The first module package is `audit.api`, the audit module's published interface (plan §5.3): the
`AuditRecorder` every other module records through, and the vocabulary `AuditEventType`,
`AuditDomain` and `AuditDetails`. Recording stays a direct call inside the business transaction
(`MANDATORY`), never an event. The recorder's implementation `AuditService`, the `AuditEvent`
entity, its repository, the viewer and the retention purge are the module's internals and still sit
in the layer packages.

The domain events live in their publisher's `<module>.api.events` package (plan §5.2, §5.3); there
is no central `event` package. Every listener runs after commit (`@TransactionalEventListener`),
so the reaction may happen later or fail on its own, and none records an audit row for the mutation
that published the event (REQ-AUDIT-007). Eight modules publish today:

| Package | Events |
| --- | --- |
| `notification.api.events` | `NotificationEvent`, the contract every notification-producing event implements, and the `OrgUnitRef` it carries |
| `identity.api.events` | `DiscordRegistrationPendingEvent`, `UserApprovalDecidedEvent`, `MemberDepartedEvent` (consumed by the exchange departure) |
| `privacy.api.events` | the three `AccountDeletionRequest…Event`s (transitional module, plan §7.6) |
| `bank.api.events` | `BankBookingRequestEvent` and its created, confirmed, rejected and cancelled records |
| `joborder.api.events` | `JobOrderCreatedEvent`, `JobOrderUpdatedByRequesterEvent` |
| `materialexchange.api.events` | `MaterialExchangeInterestRegisteredEvent`, `MaterialRequestFulfillmentSignalledEvent` |
| `exchange.api.events` | `ExchangeInstallationConnectedEvent`, `ExchangeBulkUndoAppliedEvent` |
| `inventory.api.events` | `InventoryTransferredToUserEvent`, `InventoryTransferredFromUserEvent` and the `TransferredLot` they list (REQ-INV-055) |

The publishing services and the listeners still sit in `service`. `OrgUnitRef` stays with the
notification contract rather than the kernel, because it carries the org-unit module's
`OrgUnitKind`.

The Phase 1 re-homings (plan §7.3) added three more: `kernel` (handle anonymisation, the handle
scrubber and the generic `FuzzyNameMatcher`), `orgunit.api` (`BereichLeadershipRole` and the observer SPI `MembershipChangeObserver`) and
`orgunit.web` (the Bereich-leader and Grand-Admiral request bodies). The org chart mirrors the
leadership ranks as that observer, inside the transaction of the rank change, so the org-unit
services no longer know `OrgChartService`. The exchange's row records are nested in the
repositories that produce them, the catalogue's `ShipTypeMapper` maps ship types, and
`AuthHelperService` no longer delegates to `OwnerScopeService`: callers ask the scope API
directly.

The former `support` package is gone (plan §7.3, P1-9). Its domain-free helpers are kernel
types (`OptimisticLock`, `StringNormalization`, `LikePatterns`, `Quality`, `RequestMemo`, `Roles`,
`Permissions`, `ProblemResponseFactory` and its `AppProblemProperties`); the access core and the
request settings form the `platform` module (`platform.api`: `AuthenticatedSubject`,
`SubjectAuthentication`, `OrgUnitContextualAuthority`, `ClientAttribution`, the refused-subject
window, the resilient Redis listener container and the shared rate-limit, role-scope and
authorities-cache settings; `platform.internal`: the metric client list and the body limit);
every other helper sits in `<module>.internal`, or in `<module>.api` when another module uses it
(for example `inventory.api.InventoryAllocations`, `livesync.api.LiveSyncTopic`,
`catalogue.api.QuantityTypeRounding`, `orgunit.api.StaffelMembershipResolver`). `mission` is the
first module that publishes nothing yet: its section-version counters, peer redaction and
viewer-access SPI are internal, and no other module may depend on it. The helpers stay
dependency leaves — they may use only each other, the entity model and the repositories
(`ArchitectureTest.LEAF_HELPER_CLASSES`).

Phase 2 (plan §7.4) moves whole domains out of the layer packages, each into
`<module>.api` (what other modules use), `<module>.internal` (entities, repositories, services,
mappers no other module needs) and `<module>.web` (controllers and their REST DTOs):

| Module | `api` | `internal` | `web` |
| --- | --- | --- | --- |
| `dashboard` | — (publishes nothing) | `Announcement`, `AnnouncementRepository`, `AnnouncementService` | `AnnouncementController`, `AnnouncementDto`, `AnnouncementMapper` |
| `admin` | `SystemSettings` (setting read, system flag write) | `SystemSetting`, its repository, `SystemSettingService`, `SystemSettingMapper`, the setting records, `AndroidClientProperties`, `AndroidVersionPolicyReport` | `SystemSettingController`, `AppVersionPolicyController`, `SystemController`, `AppVersionPolicyDto`, `PingResponse` |
| `exchange` | `ActingMemberAuthorities`, `IngestGatewayProperties`, the problem codes and the two events | the relay services, gates, mirrors and feed readers, the 14 entities and 10 repositories, `ActingMemberFilter`, the change-source transaction manager, the sandbox guard, the retention tasks; the relay wire records in `internal.dto` | the eight relay controllers under `/api/v1/exchange`, the registry and bulk-undo administration, `ConnectedAppsController` |
| `personalinventory` | `PersonalInventoryErasure` (the GDPR deletion's bulk delete) | `PersonalInventoryItem`, `PersonalInventoryLocationType`, the repository, `PersonalInventoryItemService`, the mapper and records, `UexLocationDto` | `PersonalInventoryController`, `AdminPersonalInventoryController`, `UexLocationController` |
| `orgchart` | — (publishes nothing; orgunit reaches it through `orgunit.api.MembershipChangeObserver`) | `OrgChartPosition` and its repository and enums, `OrgChartService`, `OrgChartReadService`, `LeitungViewService`, `OrgChartPositionMapper`, the chart and Leitung DTOs | `OrgChartController`, `LeitungController` |
| `promotion` | — (publishes nothing; the GDPR deletion calls `service.MemberEvaluationErasure`, which the module implements) | the entities, repositories, services, mappers and records, `PromotionAccessPolicy` | the six promotion controllers |

`OrgChartService` mirrors the leadership ranks inside the transaction of the rank change. The org
chart's DTOs stay in `internal`, because its services return them: a moved domain's `web` depends on
its `internal`, never the reverse ([`module-boundaries.md`](../specs/module-boundaries.md),
REQ-MOD-006).

The exchange is the first module whose lower neighbours had to stop naming it: the security
configuration takes the acting-member filter from `platform.api.ActingMemberFilterProvider`, the
blueprint upload preview hands the exchange envelope to `service.BlueprintEnvelopeReader`, and the
token converter, the account deletion and the blueprint source names ask
`platform.api.ClientDirectory`; all three SPIs are implemented in `exchange.internal`. The relay
headers are platform vocabulary (`platform.api.ActingMemberHeader`). The relay surface itself is
frozen (D-05): `exchange-relay.openapi.json` and the wire records did not change.

`operation` followed: `OperationController` in `operation.web`; the operation, finance and payout
services, the payout-status entity and repository, the finance and payout DTOs and
`OperationAccessPolicy` in `operation.internal`. The policy (bean `operationAccessPolicy`) is the
first access policy taken out of the scope hub (ADR-0236): the operation endpoints, the operation
live-sync room and the payout ledger check ask it instead of `OwnerScopeService`. Deleting an
operation detaches its missions through the mission module's first published command,
`mission.api.MissionCommands#detachFromOperation` (`MANDATORY`), instead of writing `Mission`
rows itself. The `Operation` entity, its repository and mapper and the DTOs the mission module
embeds stay in the layer packages until `Mission` references its operation by id (plan §7.5), so
the operation's JPQL scope fragment still sits in `ScopeSpecifications`.

The platform modules reach the domains only through SPIs they own and the domains implement
(plan §5.3); Spring injects the implementations, so the platform names no domain class:

| SPI (owner) | Asked for | Implemented by |
| --- | --- | --- |
| `audit.api.ActorHandleResolver` | the actor handle an audit row snapshots | identity (`UserActorHandleResolver`) |
| `audit.api.RetentionParticipant` | the audit trails outside the audit module that the retention run also purges, after the activity domains | bank (`BankAuditService`) |
| `notification.api.RoleRecipientDirectory` | global-role holders, role holders within an org unit, the catalogue spelling of a role code | identity (`UserRoleRecipientDirectory`) |
| `notification.api.OrgUnitRecipientDirectory` | the Lead, Logistician and Mission Manager flags of an org unit | orgunit (`OrgUnitMembershipRecipientDirectory`) |
| `notification.api.AccountRecipientDirectory` | grant holders and responsible holders of a bank account | bank, org-unit side (`OrgUnitBankRecipientDirectory`) |
| `livesync.api.LiveSyncTopicAuthorizer` | whether the caller may join a room, through the owning module's read gate; one implementation per kind, checked at startup | mission, operation, joborder, refinery, bank (`OrgUnitBankLiveSyncTopicAuthorizer`); the member and self rooms stay with livesync |
| `service.ActiveOrgUnitProvider` (platform) | the org unit for the `orgUnitId` MDC field | scope (`ScopeActiveOrgUnitProvider`) |
| `platform.api.ClientDirectory` | whether a client id is a configured ingest gateway or a registered exchange client, for the bounded client label | exchange (`ExchangeClientDirectory`) |

## 5.3 Level 2 — inside `frontend`

The frontend is packaged by domain (plan §5.9, F4, REQ-FE-032): each of the 21 domains of the
evidence table plus `shell` (landing, legal pages, manifest, client-error beacon, licence report)
has up to three packages, and the kernel keeps its own.

| Package | What lives there |
| --- | --- |
| `<domain>.web` | The domain's Thymeleaf page and fragment endpoints, AJAX mutation endpoints that return fragments, and its view shaping (`MissionDetailModelBuilder`, `BankDashboardViewAssembler`, view rows such as `RefineryListRow`); `DomainPackageLayoutTest` keeps every controller here |
| `<domain>.model` | The domain's hand-mirrored DTO records, marked `@DtoMirror` so the DTO contract tests find them wherever they live, its form objects and view models |
| `<domain>.client` | One typed backend client per domain (`AuditBackendClient`, `NotificationBackendClient`, …): a thin `@Service` over `BackendApiClient` that owns its domain's backend paths, passes every runtime value as a URI-template variable and returns typed records; a controller reaches the backend only through its domain's client (plan F3, `TypedBackendClientTest`) |
| `model` | The kernel model: `PageResponse`, `PayoutPreference`, the ingest handoff types, `BackendEnumAsString`, `DtoMirror` and the reference DTOs several domains share (`UserReferenceDto`, `SquadronReferenceDto`, `MaterialReferenceDto`, …) |
| `service` | The backend kernel — `BackendApiClient` with its catalogue cache and URI-template verbs for every verb, `BackendErrorMapper` (the one mapping of a failed call, a sealed `Outcome`), `BackendSideChannels` (the SSE relay and the live-sync probe), `CatalogueCacheEviction` (the evictions a controller triggers after an admin write) — plus `ParallelPageLoader`, the ingest handoff, live-sync presence, Markdown rendering |
| `view` | The template beans `moneyFormat` and `relativeDays` |
| `websocket` | `/ws/sync`, the handler and the Redis fanout |
| `config` | WebClient, Resilience4j, Redis session, Reactor context propagation, security, the layout model |
| `support` / `validation` / `exception` / `health` / `logging` / `metrics` | Shared helpers (`Roles` among them) and the usual Spring surface |

The kernel still reaches into three domains in twelve places: the layout advices and loader read
`orgunit.model` (`SquadronDto`, `OrgUnitMembershipOptionDto`) and the active-org-unit session key
of `orgunit.web.MeFrontendController`, the access gates read `identity.model` (`UserDto`,
`RegistrationStatusDto`, `TermsStatusDto`), `BackendApiClient` reads `TermsDocumentDto` and
`QualityTierCatalog` reads `catalogue.model.QualityTierDto`.

*Corrected 2026-09-29:* this table called `BackendApiClient` "the single seam" (see §4.1), placed
view-shaping services in `service` and view models in `view`; the view shaping lived in
`controller`, and `view` held one class. *Updated 2026-10-10:* the layer packages `controller`,
`model.dto`, `model.form` and `oss` are gone (plan F4).

**One trap lives here and is worth naming in an architecture document**, because it is invisible
from the code that suffers from it: `WebClient.exchange()` runs on a Reactor-Netty worker thread,
not the servlet thread, so a plain `ThreadLocal` is not visible inside an exchange filter. Anything
that has to cross that boundary — the active-OrgUnit pin, the correlation id — needs a registered
`ThreadLocalAccessor`. Forgetting it does not fail; the value simply arrives empty.

## 5.4 Level 2 — the other modules

- **`ingest`** — a gateway in front of the exchange API: DPoP authentication (`REQ-INGEST-012`,
  REQ-XCH-006), the registry gate, per-IP and per-client limits, payload size limits, idempotency,
  a relay to the backend under the gateway's own service identity, and the single-use Redis handoff
  (§5.5). Its contract is the committed `exchange-v1.openapi.json`; it has no springdoc and no
  generated document since the extractor's `/v1` routes were removed on 2026-09-28. Its two
  outbound calls — the relay and its own token grant — are blocking `RestClient`s on the JDK HTTP
  client (`relay.RestClientConfig`, ADR-0204); the module has no WebFlux and no Reactor Netty, so
  the worker-thread trap of §5.3 does not exist there. It is packaged by concern (table below).
  Specification: [`desktop-ingest.md`](../specs/desktop-ingest.md).
- **`keycloak-spi`** — a provider JAR, deliberately free of the application stack: no Spring Boot,
  Java-21 bytecode for the Keycloak JVM, its own Lombok pin, `@JBossLog` rather than `@Slf4j`. It
  holds the Discord identity provider and its mappers, the guild/role gate authenticator, the
  guild-nickname reader, the backend account checker, the `basetool-exchange` admin extension
  that ends one client inside a member's shared sessions (ADR-0226, REQ-XCH-008), and the
  `krt-freemarker` login forms provider that hands a device login's user code to the consent page
  (ADR-0228, REQ-XCH-005); every Discord call
  goes through one shared HTTP client, and a first login reads the guild-member object once. Analysed by SpotBugs +
  FindSecBugs and held to its own JaCoCo floor like the applications (since 2026-09-22;
  `build-settings.properties`, REQ-OPS-037). Shipped as its
  own signed artifact (ADR-0055). Three sub-packages by concern: `discord` (the identity provider,
  its two mappers, the shared Discord client and the nickname reader), `gate` (the first-login
  authenticator, the membership checker and the backend account precheck with its truststore) and
  `exchange` (the `basetool-exchange` admin extension and the `krt-freemarker` login forms); `gate`
  uses `discord`, nothing else crosses. `ServiceRegistrationsTest` pins the six
  `META-INF/services` registrations, and `repo-lint.yml` ties the compile version to the image
  (REQ-OPS-040).
- **`keycloak-theme/krt-theme`** — not a Gradle module: the `login` and `account` themes in the
  organisation's design, shipped inside the config bundle.
- **`logging-support`** — a plain library, **shipped** inside the three application JARs: the
  one `LogSafe` and the PII maskers every `logback-spring.xml` names (ADR-0205). Closed to domain
  meaning — no DTO, no validation rule, no bean — so it cannot become the shared-code module the
  frontend/backend split deliberately avoids.
- **`test-support`** — a test-only library, never shipped: endpoint enumeration and the frontend
  page-route inventory behind the backend and frontend anonymous-surface sweeps, and behind ingest's
  `IngestEndpointSurfaceTest`, which pins the gateway's routed surface to the exchange route table
  and fails on any mapping under `/v1`; and `ContextShape`, the bean count each application's
  `ContextShapeTest` ratchets (REQ-OPS-038).

### Inside `ingest`

The gateway is packaged by concern, not by kind (REQ-INGEST-013). The table is in dependency
order: each package uses only packages listed before it. `ConcernPackageRulesTest` fails on a
package cycle and on a class outside these packages, on an outbound HTTP client outside `relay` and
on Redis outside `registry`, `store` and `handoff` (REQ-INGEST-014); the order itself is not
pinned beyond being cycle-free.

| Package | What lives there |
| --- | --- |
| `config` | The `@ConfigurationProperties` records (`app.ingest`, `app.exchange.*`, `app.logging`, `app.rate-limit`, the service account, the scrape credentials); they use nothing else |
| `contract` | The committed contract: the route table (`ExchangeRoutes`), the v1 schemas and the served documents |
| `registry` | Reading the registry mirror and the revocations the backend writes to Redis; fails closed |
| `observability` | Metric names, the refusal counter and its codes, the exchange MDC fields, the mirror-age gauge, the `userId` MDC filter, the observation privacy filter, the tracing and Resilience4j meters |
| `problem` | The one RFC 7807 builder, the filter-level writer and the two exceptions mapped to problems |
| `edge` | The servlet filters ahead of Spring Security (bot protection, correlation id, access log, payload cap, per-IP buckets) and the exchange path scope |
| `auth` | The token gate, DPoP nonces, proof validation and replay stores, the `htu` converter, the IdP-unavailable filter and the 401/403 problem handler |
| `gate` | The registry gate filter, the client-version check and the admitted request's context |
| `store` | The gateway's own Redis state under `ingest:xch:*`: the byte budget, the daily write quotas and the idempotency cache (ADR-0221) |
| `limits` | The limit filter (per-minute buckets, the daily quota check) |
| `idempotency` | The idempotency filter |
| `relay` | Every outbound HTTP call: the relay to the backend, the service-account token grant, the two `RestClient`s, the Keycloak trust, the size cap and the call log |
| `handoff` | The single-use browser handoffs in Redis |
| `web` | The exchange and document controllers and the global exception handler |
| `assembly` | The three security filter chains, the startup guards, the gate-posture gauge and the startup banner |

## 5.5 Level 2 — the external client exchange

Four modules share it (ingest, backend, frontend, keycloak-spi); the contract, the routes and
every rule are in [`external-exchange.md`](../specs/external-exchange.md) (`REQ-XCH-*`), the
decisions in ADR-0216 … ADR-0221 and ADR-0224 … ADR-0228.

| Where | Building block | Responsibility |
| --- | --- | --- |
| ingest | `ExchangeTokenGateFilter` | DPoP-bound token with a proof and a server nonce, the `basetool-ingest` audience checked in code |
| ingest | `ExchangeGateFilter` (`ExchangeRoutes`, `ExchangeRegistryReader`, `ExchangeRevocationReader`) | Deny-by-default route table; global switch, client status, revocations (read uncached), route scope ∩ registry grant, minimum client version; fail-closed on an unreadable mirror |
| ingest | `ExchangeLimitFilter`, `ExchangeQuotas`, `ExchangeBudget` | Per-minute buckets in-process, the daily write quota and the hard byte budget in Redis (`ingest:xch:*`) |
| ingest | `ExchangeIdempotencyFilter` | `Idempotency-Key` on every write, answers cached per client, member and key |
| ingest | `web.ExchangeController`, `ExchangeSchemas`, `ExchangeRelay` | Schema check of request and answer, relay under the gateway's service identity naming member, client, capabilities and installation; staging of drafts and of a held mass change (`HandoffKind.MASS_CHANGE`) for the browser |
| backend | Registry (`ExchangeRegistryService`, `AdminExchangeRegistryController`, `ExchangeRegistryMirrorSync`, `ExchangeRegistryReconcileTask`) | `exchange_client` and the switch, `ADMIN` only; the Redis mirror written restrictive-first, reconciled every 60 s |
| backend | `exchange.internal.ActingMemberFilter`, `ExchangeGate`, `ExchangeInstallationService`, `ExchangeConnectionRetentionTask` | The acting member's reduced authentication, `@exchangeGate` re-checking every capability, installations, revocations and departures; disconnected installations and revocations deleted after 90 days (REQ-XCH-035) |
| backend | Change feed (`V252`, `ChangeSourceTransactionManager`, `ExchangeFeedReader`) | Trigger-written key log with the writer, tombstones, cursors, the 90-day retention (ADR-0224) |
| backend | Journal (`V253`, `ExchangeJournalService`) | Every written entry before and after, 90 days |
| backend | Write services for blueprints, stock and ships (ship links `V254`) | Plan a change set, ask `ExchangeMassChangeGuard`, write, journal each entry; `ExchangeLiveSync` after commit. Ships and blueprints are written through the hangar's and the blueprint domain's own services; stock takes the Lager's lot locks through `InventoryItemRepository` and books out through the Lager's book-out, but books in by creating the `InventoryItem` row and its audit event itself |
| backend | `ExchangeUndoService`, `ExchangeMassChangeService` | The member's undo, and the confirmation of a held mass change |
| backend | Bulk undo (`ExchangeBulkUndoService`, `ExchangeBulkUndoRunner`, `ExchangeBulkUndoStep`, `AdminExchangeBulkUndoController`, `V256`) | An admin's undo of one client for every member: suspends the client, then one member per transaction on the single-thread `exchangeBulkUndoExecutor`; runs and skipped entries kept 90 days (ADR-0227) |
| backend | `ExchangeResolveService`, `ExchangeDemandService`, `ExchangeDraftService` | Catalogue resolve through the web import's matching, the anonymised org demand, review drafts |
| frontend | „Verbundene Anwendungen" (`/connected-apps`, `/connected-apps/confirm`) | The member's clients, installations and activity; disconnect, undo, confirm a mass change — over `/api/v1/connected-apps`, member session only |
| frontend | Admin „Verbundene Anwendungen" (`/admin/exchange-clients`) | The registry and the global switch; a client's undo for every member with its runs |
| keycloak-spi | `ExchangeClientSessionResourceProviderFactory` (`basetool-exchange`) | Ends one client inside a member's shared user sessions when the member disconnects it (ADR-0226, REQ-XCH-008) |
| keycloak-spi | `DeviceConsentLoginFormsProviderFactory` (`krt-freemarker`) | Hands a device login's user code to the consent page, beside the phishing warning (ADR-0228, REQ-XCH-005) |

*Corrected 2026-09-29:* the write-services row said every exchange write goes "through the domain's
own services". The stock book-in does not: it is a second write path into the Lager, which the
[domain modularisation plan](../DOMAIN_MODULARISATION_PLAN.md) (§5.3, §7.5) routes through the
inventory module's command API.

## 5.6 The monitoring plane

Generated from its own compose file (`docker-compose.monitoring.yml`); it reaches the application
through `net-monitoring-scrape` and the exporters' data-store networks, and is reached from outside
only through the edge's `net-proxy-grafana`: **prometheus**,
**grafana**, **loki**, **tempo**, **alertmanager**, **blackbox-exporter**, two
**postgres-exporters** and a **redis-exporter** as containers — and **node-exporter**, **alloy** and
**podman-exporter** as *host* services. §7.3 says why those three are not containers, and what was
deleted instead of being carried across. Rules, dashboards and probes:
[`monitoring/`](../../monitoring/README.md).
