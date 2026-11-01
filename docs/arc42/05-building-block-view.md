# 5. Building block view

## 5.1 Level 1 — the whitebox of the whole system

```
  internet ── :80 / :443 ──► haproxy (host service, TCP only)
                                 │ PROXY protocol v2 → loopback :8080 / :8443
                                 ▼
      ┌──────────────────────────────────────────────────────────────────┐
      │ edge (nginx-unprivileged) — TLS for the three public names       │
      └───┬──────────────────┬────────────────┬────────────────────────┬─┘
          │ profit-base      │ profit-base    │ ingest.*               │ grafana.*
          │ .online          │ .online/auth   │                        │
          ▼                  ▼                ▼                        ▼
    ┌──────────┐      ┌─────────────┐    ┌────────┐               ┌─────────┐
    │ frontend │      │  keycloak   │    │ ingest │               │ grafana │
    └────┬─────┘      │ SPI · theme │    └───┬────┘               └─────────┘
         │ WebClient  └──────┬──────┘        │ service            + prometheus · loki · tempo
         │ + bearer          │ OIDC · SPI    │ token                alertmanager · blackbox
         ▼                   ▼               ▼                      exporters — the monitoring plane
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

The connections run over **seventeen** container networks, each named for what it joins
(`net-proxy-*`, `net-db-*`, `net-redis-*`, `net-backend-*`, `net-edge-ingress`, `net-acme-egress`,
`net-monitoring-core`, `net-monitoring-scrape`, `net-blackbox-v6`). Apart from the two monitoring
networks each joins a small named set — usually one pair, plus a data store's exporter — and a
container reaches exactly the containers it shares a network with. The units are in
[`quadlet/systemd/`](../../quadlet/systemd/).

| Building block | Responsibility | Deliberately does **not** |
| --- | --- | --- |
| **haproxy** (host service) | Bind the public `:80`/`:443`, forward bytes to the edge's loopback ports, and state the client address in a PROXY v2 header (ADR-0187) | Terminate TLS or hold a key |
| **edge** (nginx-unprivileged) | TLS termination for three vhosts plus Keycloak under `/auth`, per-vhost rate limits, deny rules, header rewriting, the `X-Forwarded-*` family; trusts the PROXY header only from its own five pinned addresses | Hold any application logic or state |
| **acme** (lego) | Issue and renew the certificates the edge serves | Serve traffic |
| **frontend** | Render the UI, hold session state, drive live update | Talk to PostgreSQL or the Keycloak Admin API; contain business rules |
| **backend** | The whole domain: REST API, persistence, authorisation, scheduled work | Serve HTML; be reachable from the internet at all — its callers are the frontend, the ingest gateway and the Keycloak SPI (ADR-0268) |
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
| `service` | Business rules, transactions, audit recording; the **scoping** hub (`OwnerScopeService`) lives in the `scope` module since P4-6 |
| `repository` | Spring Data JPA; fetch strategies that keep the no-N+1 rule |
| `model` | JPA entities, `@Version`, the OrgUnit hierarchy |
| `dto` / `mapper` | Records on the boundary, MapStruct between them and entities |
| `task` | Scheduled jobs |
| `integration` | Outbound third parties — `UexClient`, `scwiki` — on the blocking `RestClient` from `platform.api.RestClientConfig` (JDK HTTP client, no WebFlux; ADR-0204), through its external builder that refuses non-external addresses (REQ-SEC-081); the primary builder is `KeycloakService`'s |
| `metrics` / `health` / `logging` | `basetool_*` business metrics, health indicators, MDC enrichment |
| `filter` / `annotation` / `validation` / `util` / `web` / `exception` / `config` | The usual Spring surface |

These layer packages were cut into domain modules (the
[domain modularisation plan](../archive/DOMAIN_MODULARISATION_PLAN.md), §5.1); since 2026-10-12 every domain
class lies in a module package and the table above describes the roles the layers had. The business gauges are published per module (`platform.api.BusinessGaugeSource`, one `…BusinessGauges` bean in each owning module's `internal`); `app.internal.BusinessMetricsCollector` only schedules them. The
**domain map** [`backend/src/test/resources/architecture/domain-map.txt`](../../backend/src/test/resources/architecture/domain-map.txt)
is the source of truth for its target module and that module's rank: every class is assigned there
by the first matching rule, and a class no rule assigns fails the build
([`module-boundaries.md`](../specs/module-boundaries.md), REQ-MOD-001/002).

The first module package is `audit.api`, the audit module's published interface (plan §5.3): the
`AuditRecorder` every other module records through, and the vocabulary `AuditEventType`,
`AuditDomain` and `AuditDetails`. Recording stays a direct call inside the business transaction
(`MANDATORY`), never an event. The recorder's implementation `AuditService`, the `AuditEvent`
entity, its repository, the viewer and the retention purge lie in the module's `api`, `internal` and
`web` packages (umbrella step 2).

The domain events live in their publisher's `<module>.api.events` package (plan §5.2, §5.3); there
is no central `event` package. Every listener runs after commit (`@TransactionalEventListener`),
so the reaction may happen later or fail on its own, and none records an audit row for the mutation
that published the event (REQ-AUDIT-007). Thirteen modules publish today:

| Package | Events |
| --- | --- |
| `notification.api.events` | `NotificationEvent`, the contract every notification-producing event implements, the `OrgUnitRef` it carries, `NoticeEvent`, the plain event the newer producers build from their parts, and the `ActorRef` that names who acted |
| `identity.api.events` | `DiscordRegistrationPendingEvent`, `DiscordRegistrationDecidedEvent`, `UserApprovalDecidedEvent`, `MemberDepartedEvent` (consumed by the exchange departure) |
| `privacy.api.events` | the three `AccountDeletionRequest…Event`s (transitional module, plan §7.6) |
| `bank.api.events` | `BankBookingRequestEvent` and its created, updated, confirmed, rejected, cancelled and notices-reconciled records; `BankAccountResponsibleAssignedEvent`; and `BankNotices`, the factory of the approval, grant, direct-booking and holder notices (REQ-BANK-057…060) |
| `joborder.api.events` | `JobOrderCreatedEvent`, `JobOrderUpdatedByRequesterEvent`, `JobOrderClosedEvent`, and `JobOrderNotices`, the factory of the reassigned, finished, assignee and withdrawn-claim notices (REQ-ORDERS-041…044) |
| `supply.api.events` | `SupplyNotices`, the factory of the donation pledged, received, declined and withdrawn notices (REQ-SUP-016) |
| `purchasing.api.events` | `PurchasingNotices`, the factory of the need released / closed, delivery recorded / decided, settlement overdue, pause / resume and delegate appointed / revoked notices (REQ-PUR-024) |
| `materialexchange.api.events` | `MaterialExchangeInterestRegisteredEvent`, `MaterialRequestFulfillmentSignalledEvent`, and `MarketNotices`, the factory of the offer-gone and request-withdrawn notices (REQ-MARKET-021, -022) |
| `exchange.api.events` | `ExchangeInstallationConnectedEvent`, `ExchangeBulkUndoAppliedEvent`, and `ExchangeNotices`, the factory of the suspended, activated, update-required, capability-removed and switched-off notices (REQ-XCH-040, -041) |
| `mission.api.events` | `MissionNotices`, the factory of the mission notices: rescheduled, cancelled, deleted, reminder, started, checked in, participant added / removed / left, never ended, end recorded, responsibility assigned (REQ-MISSION-021…027), a unit's ship assigned or unassigned (REQ-HANGAR-005) |
| `operation.api.events` | `OperationNotices`, the factory of the payout paid out / unmarked and operation completed notices (REQ-MISSION-028, -029) |
| `inventory.api.events` | `InventoryTransferredToUserEvent`, `InventoryTransferredFromUserEvent` and the `TransferredLot` they list (REQ-INV-055), and `InventoryNotices`, the factory of the booked-out-by-somebody-else notice (REQ-INV-056) and of the low-stock notice a timed producer raises (REQ-NOTIF-030) |
| `orgunit.api.events` | `OrgNotices`, the factory of the seat-holder-without-KRT-Member and member-departed notices (REQ-ORG-029, -030) |
| `admin.api.events` | `HangarNotices`, the factory of the hangar and blueprint notices: a deleted ship dropping out of a mission, fitted marks reset, hangar or blueprints changed by an admin (REQ-HANGAR-006…008) |
| `refinery.api.events` | `RefineryNotices`, the factory of the order-ready, order-ready-cleared and changed-by-somebody-else notices (REQ-REFINERY-023, -024) |

An event either notifies, supersedes the notices of its entity (REQ-NOTIF-018), or reconciles one notice for named members (REQ-NOTIF-023, ADR-0244). The publishing services and the listeners still sit in `service`. `OrgUnitRef` stays with the
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
(for example `inventory.api.StockRow`, `livesync.api.LiveSyncTopic`,
`catalogue.api.QuantityTypeRounding`, `orgunit.api.StaffelMembershipResolver`). `mission`
publishes one command (`MissionCommands`); its section-version counters and peer redaction are
internal, and the mapper's viewer-access seam `MissionViewerAccess` sits beside `MissionMapper` in
`mapper`. The helpers stay
dependency leaves — they may use only each other, the entity model and the repositories
(`ArchitectureTest.LEAF_HELPER_CLASSES`).

Phase 2 (plan §7.4) moves whole domains out of the layer packages, each into
`<module>.api` (what other modules use), `<module>.internal` (entities, repositories, services,
mappers no other module needs) and `<module>.web` (controllers and their REST DTOs):

| Module | `api` | `internal` | `web` |
| --- | --- | --- | --- |
| `dashboard` | — (publishes nothing) | `Announcement`, `AnnouncementRepository`, `AnnouncementService` | `AnnouncementController`, `AnnouncementDto`, `AnnouncementMapper` |
| `admin` | `SystemSettings` (setting read, system flag write) | `SystemSetting`, its repository, `SystemSettingService`, `SystemSettingMapper`, the setting records | `SystemSettingController`, `SystemController`, `PingResponse` |
| `exchange` | `ActingMemberAuthorities`, `IngestGatewayProperties`, the problem codes and the two events | the relay services, gates, mirrors and feed readers, the 14 entities and 10 repositories, `ActingMemberFilter`, the change-source transaction manager, the sandbox guard, the retention tasks; the relay wire records in `internal.dto` | the eight relay controllers under `/api/v1/exchange`, the registry and bulk-undo administration, `ConnectedAppsController` |
| `personalinventory` | — (publishes nothing; the account deletion reaches the module through its `UserErasureParticipant`) | `PersonalInventoryItem`, `PersonalInventoryLocationType`, the repository, `PersonalInventoryItemService`, the mapper and records, `UexLocationDto` | `PersonalInventoryController`, `AdminPersonalInventoryController`, `UexLocationController` |
| `navigation` | — (a leaf: publishes nothing, since the frontend reaches it over REST; the account deletion reaches the rows through the `app_user` cascade) | `NavigationPreferences` (the per-member root), the repository, `NavigationPreferencesService` and its transactional `Writer`, the metrics and gauge, the export section and merge participant, the request and response records | `NavigationPreferencesController` |
| `bank` | `BankConflictException`, `BankProblemCode`, `BankAmounts`, `BankBookingRequestType`, the booking-request events | the Kartellbank (entities, ledger, booking requests, audit trail, PDF formats, ledger task) and the org-unit side behind `OrgUnitBankAccessService` | the eight Kartellbank controllers, `OrgUnitBankController` |
| `supply` | the need and donation status enums, `SupplyProblemCode` and its exception, `NeedPriceAudience` (the SPI `purchasing` implements) and the notices | `SupplyNeed`, `SupplyDonation`, `SupplyUnitSetting`, their repositories, the need, donation, settings, unit-setting and stats services, `SupplyAccessPolicy`, `SupplyMapper`, `SupplyBusinessGauges` and the export, erasure, merge and person-search participants | `SupplyNeedController`, `SupplyDonationController`, `SupplyStatsController`, `SupplyStateController`, `SupplyAdminController` |
| `purchasing` | the deal, tranche, delivery, partner, risk-class and pause vocabulary, `PurchasingProblemCode` and its exception, and the notices | the settings, delegate, partner, pause, deal, tranche, delivery, settlement, state and report services, `PurchasingAccessPolicy`, `PurchasingDealAssembler`, the nine entities and their repositories, the bank and need observers, `SettlementOverdueNoticeProducer`, `PurchasingBusinessGauges` and the export, merge and person-search participants | the deal, partner, delegate, report, state and admin controllers under `/api/v1/purchasing` |
| `materialexchange` | the two after-commit events | the offer and request entities, repositories and DTOs, the board services, `MaterialExchangeOfferRatchet` (the Lager's `StockChangeObserver`) and `MaterialExchangeStockOfferLookup` (its `StockOfferLookup`) | `MaterialExchangeController`, `MaterialRequestController` |
| `refinery` | `RefineryDraftBuilder` (the exchange's refinery draft), the screenshot-extract and draft records, the problem codes | `RefineryOrderService` (also the blueprint craftability's `CraftabilityYieldSource`), `RefineryImportService`, `RefineryAccessPolicy`, the import settings and the store requests, `RefineryMissionFinance` (the mission's `MissionFinanceContributor`), `MissionRefineryOrderRelease` (the mission's `MissionDeletionObserver`) and `RefineryOrderMissionCache` (the per-transaction memo behind `mapper.RefineryOrderMissionLookup`) | `RefineryOrderController`, `RefineryImportController` |
| `joborder` | the problem codes, `ProductionAllocationException`, `JobOrderAuditLabel`, the order events | the order, item, production, handover, claim (also the stock projection's `ClaimBucketSource`) and demand services, `JobOrderEditor` (the edit module every order edit runs through, ADR-0287), `JobOrderAccessPolicy` (also the Lager's `EarmarkTargetPolicy`), the integrity task, its own repositories and the request DTOs | `JobOrderController`, `MaterialClaimController`, `MaterialCollectionController`, `JobOrderItemStockController` |
| `mission` | `MissionCommands` (`detachFromOperation`), the read SPI `OperationSummaryProvider` (the operation module implements it), `MissionFinanceContributor` and `MissionDeletionObserver` (the refinery module implements them), `MissionReferences` (the refinery reads a mission's reference through it) | the mission, structure, participant, timeline and finance services, `MissionEditor` (the edit module every timeline, roster and structure edit runs through, ADR-0287), `MissionAccessPolicy`, the viewer-access service, the section counters, the peer redaction, the hangar's `ShipDeletionObserver` and the catalogue's `JobTypeDesignationObserver` implementations, `MissionOperationSummaryCache` (the per-transaction memo behind `mapper.MissionOperationLookup`), its own repositories and the request DTOs | `MissionController` (calls the mission, timeline, participant and structure services for their sections, ADR-0286), `MissionFinanceEntryController` |
| `orgchart` | — (publishes nothing; orgunit reaches it through `orgunit.api.MembershipChangeObserver`) | `OrgChartPosition` and its repository and enums, `OrgChartService`, `OrgChartReadService`, `LeitungViewService`, `OrgChartPositionMapper`, the chart and Leitung DTOs | `OrgChartController`, `LeitungController` |
| `promotion` | — (publishes nothing; the account deletion reaches the module through its `UserErasureParticipant`) | the entities, repositories, services, mappers and records, `PromotionAccessPolicy` | the six promotion controllers |

`OrgChartService` mirrors the leadership ranks inside the transaction of the rank change. The org
chart's DTOs stay in `internal`, because its services return them: a moved domain's `web` depends on
its `internal`, never the reverse ([`module-boundaries.md`](../specs/module-boundaries.md),
REQ-MOD-006).

The exchange is the first module whose lower neighbours had to stop naming it: the security
configuration takes the acting-member filter from `platform.api.ActingMemberFilterProvider`, the
blueprint upload preview hands the exchange envelope to `blueprint.api.BlueprintEnvelopeReader`, and the
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
rows itself. Since P3-8a `Mission` references its operation by id (`Mission.operationId`, the
same `operation_id` column and foreign key): what a mission DTO shows of the operation comes
through the mission-owned read SPI `mission.api.OperationSummaryProvider`, which
`operation.internal.OperationSummaryReader` implements with one projection query, and the
per-transaction memo `MissionOperationSummaryCache`. The SPI's record `MissionOperationSummary` and
the memo's interface `MissionOperationLookup` sit in `model.dto` and `mapper`, because the mapper
reads them and a `mapper -> mission` edge would close a package cycle with the moved mission
services (as `MissionViewerAccess` does). So a page of missions costs one statement
for its operations whatever its size. `Operation.missions` is a read-only join-column view. Since
P4-4 the mission embeds the operation as its own record `MissionOperationDto` (the components of
`OperationDto`, built by `MissionMapper` from the summary), so the `Operation` entity, its status,
repository, mapper and DTOs sit in `operation.internal` and `OperationAccessPolicy` owns the JPQL
scope fragment of the operation queries; the OpenAPI document still refers to `OperationDto` from
the mission DTOs.

The refinery side follows the same pattern since P3-8b. `RefineryOrder` references its mission by id
(`RefineryOrder.missionId`, the same `mission_id` column and foreign key) and `Mission` no longer
holds its orders. What an order shows of its mission comes from the mission module's read API
`mission.api.MissionReferences` through the per-transaction memo `RefineryOrderMissionCache`, so a
page of orders costs one statement for its missions whatever its size. What the mission's finance
totals take from the orders comes through the mission-owned `MissionFinanceContributor`, which
`RefineryMissionFinance` implements, and deleting a mission releases its orders through the mission's
`MissionDeletionObserver` (`MANDATORY`). Since P4-3 the refinery's entities, repository, mapper
and request records sit in `refinery.internal` and the order DTOs in `refinery.api`. The
operation, which ranks above the refinery, reads the orders for its finance and payout views
through the refinery-owned `refinery.api.RefineryOrderFinanceReader` (the orders' figures and DTOs,
and the profit per mission), implemented by `RefineryOrderFinanceReads` with the repository and
mapper it always used; `MissionFinanceSummaryDto` lives in `operation.internal`. The catalogue asks
whether a location is in use through its own `catalogue.api.LocationUsage`, which the hangar (ships)
and the refinery (orders) implement, so it names neither repository. `RefineryAccessPolicy` owns
the JPQL scope fragment of the order queries.

The catalogue moved in part since P4-5 (plan §7.6). Its thirteen cached services map the entity
to its wire record inside their own read-only transaction and cache that record, so no cache holds
an entity of the catalogue. The six records no model class embeds (`CityDto`, `OutpostDto`,
`PoiDto`, `TerminalDto`, `StarSystemDto`, `RefiningMethodDto`) and the two name helpers other
modules call (`BlueprintNameNormalizer`, `MaterialNameCanonicalizer`) sit in `catalogue.api`; the
sync infrastructure (`SyncCoordinator`, `SyncChunkWriter`, `SyncReportService`), the UEX, SC Wiki
and P4K feed records (`catalogue.internal.uex`, `.scwiki`, `.p4k`), the sync report entity and its
repository and the integration properties sit in `catalogue.internal`. The kernel types
`AbstractEntity` and `PageResponse` lie in `kernel` since 2026-10-12, and the catalogue's entities,
enums, DTOs, repositories and validation types followed (P4-5 part 2): 69 in `catalogue.api`, which
publishes the entities as the foundation modules do (§5.2 of the plan), and 32 in
`catalogue.internal`; whatever an `api` type depends on lies in `api` too, because the layers inside
a module must stay acyclic. The module is whole since P4-5 part 3: the 21 controllers sit in
`catalogue.web`, the services, mappers and the two HTTP clients (`UexClient`, `ScWikiClient`) in
`catalogue.internal` (the ones other modules call, such as `MaterialMapper`, `LocationMapper` and
`QualityTierService`, in `catalogue.api`). Three platform types left the layer packages for
`platform.api` on the way: `CentralMapperConfig`, `ResponseSizeLimitInterceptor` and
`AuthHelperService`.

The inventory (the Lager) moved since P4-1: `InventoryItemController` sits in `inventory.web`, and
`inventory.internal` holds the Lager services (item, aggregation, checkout, org-unit change, stolen
mark), `InventoryAccessPolicy` (also the `BookInPolicy`), `InventoryStockCommands` (the `StockCommands`
implementation), `InventoryStockReads` (the `StockReads` implementation), `InventoryEarmarkTargetCache`,
`StockViewerAccessService`, `InventoryStockDecisions` (each stock rule of a book-out, an Umbuchung, a
split and a lot rebooking as a pure function returning a refusal value, which the web services and the
published commands both adapt; REQ-INV-065, ADR-0284), `LotRows` (the one primitive that splits a
part off a row and lowers or deletes the source, keeping the earmarks, donations and the
Materialbörse observer in step; REQ-MARKET-013),
`InventoryCraftableStock` (the blueprint's `CraftableStockSource`),
`InventoryOrgUnitReconciler` (the org-unit module's `FirstMembershipObserver`), its GDPR participants, the
three repositories, `InventoryItemMapper`, `InventoryItemCatalogService` (the `StockItemCatalogue`) and the
request and read DTOs. `inventory.api` publishes what other modules use: `StockCommands`,
`RebookCommands` (a member's own lot between personal and a pool, implemented by
`InventoryRebookCommands`; REQ-INV-060, ADR-0277), `StockReads`
(the exchange's lots, the stock earmarked to a job order as `LinkedStockRow` or Lager DTOs, the rows the
Materialbörse offers), `StockItemCatalogue`, `InventoryItemDto` with its two allocation DTOs and the
projection rows. The other modules see a Lager row as `StockRow`, a read-only live view of the managed row (getters, the two reservation sums and the order slice), and change it only through `StockCommands`, which take the row's id and act on the managed instance the caller already holds, so locks, the persistence context and the optimistic version are the row's own. The three entities (`InventoryItem`, which implements `StockRow`, and the two allocations) lie in `inventory.internal` with `InventoryAllocations` and `InventoryAuditLabels` (P4-1 part 3, option A2, confirmed by the owner 2026-10-12; `StockWriteOwnershipTest`, `StockRowViewCharacterisationTest`, `ExchangeStockWritePathCharacterisationTest`). Four more
seams lie in `inventory.api` as well: `StockViewerAccess` (the caller's `canEdit`, read by `JobOrderMapper`), `OrderLinkedStockRows` (the material rows
the job order's stock projection reads), and the records the job-order and mission repositories return, `JobOrderEarmark` and `MissionEarmark`.

The blueprint moved in part since P4-2: the five controllers sit in `blueprint.web`, and
`blueprint.internal` holds the personal and default blueprint services, the command service behind
`PersonalBlueprintCommands`, the import, the craftability, the product resolution and the variant
families, `BlueprintAccessPolicy`, the default-blueprint grant observer and provisioning task, the two
mappers, the default-blueprint and alias entities and repositories, the DTOs only the blueprint
controllers use, and its four GDPR participants. No layer class names any of them, so the move closed
no package cycle. `PersonalBlueprint`, `PersonalBlueprintRepository`, `BlueprintSource`, the import
preview, entry, suggestion and status DTOs, the create request and response and `BlueprintOwnerProduct` lie in
`blueprint.api` (umbrella step 10), because the exchange (feed, write, undo and demand services, the draft and resolve
services) and the job order's blueprint-owner service read them directly; the repository is published there rather than
behind a read API (plan §7.6).

The scope kernel moved in part since P4-6 (plan §7.6). `scope.api` holds the request-scope kernel and
the scope hub: `RequestScopeResolver`, `ScopePredicate`, `OrgUnitStampingService`,
the scope kernel's JPQL fragments no longer live here: each aggregate's fragment sits beside its repository in the module's `api` (`MissionScope`, `ShipScope`, `JobOrderScope`, `InventoryScope`) or in its access policy (refinery, operation), and `ScopeFragmentsGoldenTest` pins all six, `OwnerScopeService` (bean `ownerScopeService`) and `AccessGateService`,
beside the problem codes. `scope.internal` holds `ScopeActiveOrgUnitProvider`, which implements the
`platform.api.ActiveOrgUnitProvider` SPI for the logging MDC. `OrgUnitCascadeService` moved to
`orgunit.api` as a prerequisite, because the scope kernel calls it. `CustomJwtGrantedAuthoritiesConverter`
stays in `service` until the identity module takes `UserReconciliationService`, which the converter
calls to reconcile the user behind each token.

The identity module moved in part since P4-7 slice a (plan §7.6). `identity.api` publishes the Keycloak
client (`KeycloakService`, `KeycloakTrustSupport`, `KeycloakUserDto`), the two access filters
`SecurityConfig` registers (`PendingApprovalAccessFilter`, `TermsAcceptanceAccessFilter`) and the
registration request and result records beside the events; `identity.web` holds the role, terms and
Discord-existence controllers, `identity.internal` their services, mappers and DTOs. `RestClientConfig`
lies in `platform.api`, which `KeycloakTrustSupport` needs. `UserService`, `UserRegistrationService`,
`UserReconciliationService` now lie in `identity.api` (P4-7 slice c); `User`, `Role`, their repositories and `UserDto` / `UserReferenceDto`
followed with the org-unit module (umbrella steps 6 and 7). The calls out of the identity module are inverted through identity-owned SPIs:  `UserService` reaches the org-unit
membership services only through `identity.api.MembershipOperations`, which
`orgunit.internal.OrgUnitMembershipOperations` implements as a pass-through (it also sets the initial flags of a
new Spezialkommando member; the seam names no org-unit type and has no type parameter),
`identity.api.AccountErasure` (the registration flow's deletion, implemented by the GDPR orchestrator) and
`identity.api.SquadronVisibility` (the two scope questions of the user queries, implemented by
`OwnerScopeService`). `CustomJwtGrantedAuthoritiesConverter` lies in `scope.api`, because the exchange calls it.

The org-unit module (rank 4) owns the Staffeln, Spezialkommandos, Bereiche, the Organisationsleitung, Kommandogruppen and the memberships:
the entities, repositories, DTOs and services other modules call lie in `orgunit.api`, the departure notifier, the membership operations
and the recipient directory in `orgunit.internal`, the eight controllers in `orgunit.web`. Two placements follow the dependency direction
rather than the names: `UserMapper` lies in `orgunit.api` (it reads memberships, and `orgunit` may depend on `identity`, not the reverse),
and `OrgUnitKind` and `SquadronReferenceDto` lie in the kernel, which lets `notification` and `identity` name them. `MeController` lies in the composition root (`app.web`), which may depend on every module it reads; the former `UserController` is split by the module that owns the data (`UserProfileController` and `MemberMembershipController` in `orgunit.web`, the announcement read marker in `dashboard.web`, the bank member searches in `bank.web`, the payout preference in `mission.web`, the blueprint sharing in `blueprint.web`, the account removal in `privacy.web`, the identity-only rest in `identity.web`), with the shared searches in `orgunit.api.UserDirectoryService` and `identity.api.UserReferenceSearchService`; the registration queue (`DiscordRegistrationAdminController`) lies in `identity.web` and the rejected-registration retention in `identity.internal`, which deletes accounts through the `AccountErasure` SPI; only the merge endpoint (`DiscordRegistrationMergeController`) lies in `privacy.web`. The module baseline is empty. `privacy.api` holds the GDPR services, the deletion-request entity and the data-export and person-search
types; the module is dissolved into `identity` with the GDPR SPIs, not before.

The platform modules reach the domains only through SPIs they own and the domains implement
(plan §5.3); Spring injects the implementations, so the platform names no domain class:

| SPI (owner) | Asked for | Implemented by |
| --- | --- | --- |
| `audit.api.ActorHandleResolver` | the actor handle an audit row snapshots | identity (`UserActorHandleResolver`) |
| `audit.api.RetentionParticipant` | the audit trails outside the audit module that the retention run also purges, after the activity domains | bank (`BankAuditService`) |
| `notification.api.RoleRecipientDirectory` | global-role holders, role holders within an org unit, the catalogue spelling of a role code | identity (`UserRoleRecipientDirectory`) |
| `notification.api.OrgUnitRecipientDirectory` | the Lead, Logistician and Mission Manager flags of an org unit | orgunit (`OrgUnitMembershipRecipientDirectory`) |
| `notification.api.AccountRecipientDirectory` | grant holders and responsible holders of a bank account | bank, org-unit side (`OrgUnitBankRecipientDirectory`) |
| `platform.api.ActiveOrgUnitProvider` (platform) | the org unit for the `orgUnitId` MDC field | scope (`ScopeActiveOrgUnitProvider`) |
| `platform.api.ClientDirectory` | whether a client id is a configured ingest gateway or a registered exchange client, for the bounded client label | exchange (`ExchangeClientDirectory`) |
| `platform.api.UserReassignmentParticipant` | a module's part of the admin account merge: the member-referencing columns it holds, those that follow the member (with their unique-key remainder) and those that stay with the act; it can refuse the merge, and the rows it moves feed the `USER_MERGED` audit row in participant order | inventory, hangar, refinery, personalinventory, navigation, blueprint, orgunit (two), orgchart, mission, joborder, materialexchange, bank, supply, notification, promotion, exchange; audit, operation, identity declare staying columns only |
| `orgunit.api.FirstMembershipObserver` | a member's first org-unit membership gained or last one lost, inside the membership change | inventory (`InventoryOrgUnitReconciler`, which re-stamps the member's shared Lager rows) |
| `bank.api.BookingRequestPurposeObserver` | how a purpose-bound booking request ended (confirmed, rejected, cancelled), inside the bank's transaction (REQ-BANK-063) | purchasing (`PurchasingRequestObserver`) |
| `supply.api.NeedLifecycleObserver` | a need released for purchase, withdrawn or fulfilled, inside the supply transaction | purchasing (`PurchasingNeedObserver`) |
| `supply.api.NeedPriceAudience` | who may read a need's maximum price and purchase progress beyond the raising unit | purchasing (`PurchasingNeedPriceAudience`) |
| `catalogue.api.LocationUsage` | whether any row of a module still references a location, so the catalogue refuses to delete it; the first usage by position that says yes names the refusal | hangar (`ShipLocationUsage`), refinery (`RefineryLocationUsage`) |
| `platform.api.UserErasureParticipant` | a module's part of the account deletion: its rows removed, reassigned to the fallback admin or unlinked, by phase (`DELETE`, `REASSIGN`, `UNLINK`) and position, inside the deletion's one transaction; the counts it returns feed the deletion's audit rows | materialexchange, inventory, hangar, personalinventory, blueprint, notification, promotion (`DELETE`); mission, refinery (`REASSIGN`); mission, joborder, supply (`UNLINK`) |
| `platform.api.HandleAnonymisationParticipant` | a module's part of the granted Art. 17 handle anonymisation: the handle snapshots of its tables replaced by the sentinel, by position, inside the anonymisation's one transaction; the counts it returns feed the marker rows, and a participant records its own marker | audit (`AuditHandleAnonymisation`), bank (`BankHandleAnonymisation`), joborder (`JobOrderHandleAnonymisation`) |
| `platform.api.PersonalDataExportSection` | a module's part of the Art. 15 / Art. 20 data export: the `Section` records (position, key, legal basis, statement, scrubbed and exempted columns) that select the member's own rows; `PersonalDataExportRegistry` (privacy) orders them by position and `DataExportService` runs them | identity, orgunit, orgchart, promotion, inventory, hangar, exchange, personalinventory, navigation, blueprint, notification, mission, refinery, joborder, materialexchange, bank, supply, audit (`*ExportSections`) |
| `platform.api.PersonMentionSource` | a module's part of the admin person search: the text columns of its tables that can name a person (`Target`: area, table, column, id column, link kind) and the ones it exempts; `PersonMentionRegistry` (privacy) orders the union and `PersonSearchService` builds its one statement from it | every module that owns a text column (`*MentionSource`), and the platform's `SchemaHistoryMentionSource` |

> **The `navigation` module** (ADR-0298, map #2800). It holds a user's deliberate navigation and
> dashboard choices under one root row per member and a version counter per section, in
> `navigation.internal` and `navigation.web` and without an `api` package (REQ-UI-032,
> REQ-UI-034). Shipped: the favourites, the hidden pages, the appearance (desktop mode), the phone tab bars and the member dashboard (two tables per mode: the layout with its version and the instances with their option versions); the other sections and the admin dashboard are planned. It depends
> on no domain module: it stores entry keys and checks only their format, while the frontend's
> entry registry knows each key's permission and asks for the deletion of keys the user may no
> longer see. Account deletion removes the rows through the `app_user` foreign key
> (`ON DELETE CASCADE`) of the root, not through `identity` or a `UserErasureParticipant`; it takes
> part in the data export and the account merge as a `PersonalDataExportSection` and a
> `UserReassignmentParticipant` (REQ-SEC-058, REQ-SEC-046), both in `platform.api`. Access is
> owner-only and not audited. Its declaration follows REQ-MOD-006
> ([`module-boundaries.md`](../specs/module-boundaries.md)).
## 5.3 Level 2 — inside `frontend`

The frontend is packaged by domain (plan §5.9, F4, REQ-FE-032): each of the 21 domains of the
evidence table plus `shell` (landing, legal pages, manifest, client-error beacon, licence report)
has up to three packages, and the kernel has eight. `DomainPackageLayoutTest` fails on a main class
anywhere else.

| Package | What lives there |
| --- | --- |
| `<domain>.web` | The domain's Thymeleaf page and fragment endpoints, AJAX mutation endpoints that return fragments, and its view shaping (`MissionDetailModelBuilder`, `BankDashboardViewAssembler`, view rows such as `RefineryListRow`); every controller lives here |
| `<domain>.model` | The domain's hand-mirrored DTO records, marked `@DtoMirror` so the DTO contract tests find them wherever they live, its form objects and view models |
| `<domain>.client` | One typed backend client per domain (`AuditBackendClient`, `NotificationBackendClient`, …): a thin `@Service` over `BackendApiClient` that owns its domain's backend paths, passes every runtime value as a URI-template variable and returns typed records; a controller reaches the backend only through its domain's client (plan F3, `TypedBackendClientTest`) |
| `<domain>.web` page reader | Pilot in `joborder.web` (`JobOrderPageReader`, ADR-0291): one read per page above the typed client, returning an immutable page record with the defaults, the logged `SectionFallback` fallbacks and the parallel load inside; the controller only maps the record into the model (`REQ-FE-036`) |
| `kernel.backend` | The backend seam — `BackendApiClient` with its catalogue cache and URI-template verbs for every verb, `BackendErrorMapper` (the one mapping of a failed call, a sealed `Outcome`), `BackendSideChannels` (the SSE relay and the live-sync probe), `CatalogueCacheEviction`, `WebClientConfig` with Resilience4j, the outbound relays (org unit, client IP, locale, logging), Reactor context propagation, `ParallelPageLoader`, the ingest handoff |
| `kernel.security` | `SecurityConfig`, the access gates (role sync, terms gate), `Roles`, CSP, Trusted Types and security headers, the login and logout handlers, the monitoring and management port security |
| `kernel.session` | `RedisSessionConfig`, `SessionTypeAllowList`, the fault-tolerant serializer and same-request repair, session metrics and cookie settings |
| `kernel.layout` | `UsesLayoutModel`, `LayoutContextLoader`, the layout advices and `ModuleImportMap`, which renders each page's import map of content-hashed module URLs (REQ-FE-035) |
| `kernel.web` | `GlobalExceptionHandler`, `BackendErrorResponses`, `RelayParams`, binding and string normalisation, the request contexts (correlation id, client IP, active org unit), MVC and locale configuration, the template beans `moneyFormat`, `relativeDays`, `markdown` and `handles` |
| `kernel.livesync` | `/ws/sync`, the handler, the Redis fanout and presence |
| `kernel.observability` | Request logging, log masking, the startup banner, metric names, tracing and observation filters |
| `kernel.model` | `PageResponse`, `PayoutPreference`, the ingest handoff types, `BackendEnumAsString`, `DtoMirror` and the reference DTOs several domains share (`UserReferenceDto`, `SquadronReferenceDto`, `MaterialReferenceDto`, …) |

The kernel still reaches into three domains in ten places, frozen by `KernelDomainReachTest` in a
list that may only shrink (§11.9): the layout advices and loader read `orgunit.model`
(`SquadronDto`, `OrgUnitMembershipOptionDto`), the access gates read `identity.model` (`UserDto`,
`RegistrationStatusDto`, `TermsStatusDto`), `BackendApiClient` reads `TermsDocumentDto` and
`QualityTierCatalog` reads `catalogue.model.QualityTierDto`.

*Corrected 2026-09-29:* this table called `BackendApiClient` "the single seam" (see §4.1), placed
view-shaping services in `service` and view models in `view`; the view shaping lived in
`controller`, and `view` held one class. *Updated 2026-10-10:* every layer package is gone (plan
F4).

**The page chrome is one template fragment** (plan F5, REQ-FE-033, 2026-10-11). Each of the 90
Thymeleaf page templates that carry the chrome hands `fragments/layout :: page` its title, its own
stylesheet links, its content and its tail, and the layout supplies the `<html>` element and the
`head`, `sidebar`, `header` and `toast` fragments around them; the variants (a toast, no app shell,
the hangar's body attribute) are parameters of the layout. No page calls those fragments itself
(`PageLayoutCompositionTest`), and `PageChromeSnapshotTest` pins the rendered chrome of every page.

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
- **`build-logic`** — an included build, not a module of the product and never shipped: the
  `basetool.*` precompiled convention plugins (toolchain, annotation wiring, test and compile
  conventions, Checkstyle, Spotless, JaCoCo, PIT, SpotBugs, CycloneDX, Licensee, Spring Boot and
  dependency management, Error Prone) that every module applies by id, the typed `basetool { }`
  extension and the reader of each module's `build-settings.properties` (REQ-OPS-046, ADR-0248). It is
  the place a Gradle module extracted later (plan §5.8) gets its conventions from. A library module of
  an application is named `<app>-<name>`: the image plan, the SBOM check and the application's JaCoCo
  floors read that name (REQ-OPS-047, ADR-0249).

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
| backend | `ExchangeChangeSetRunner`, write services for blueprints, stock, ships and the org stock (ship links `V254`) | The runner plans a change set through the resource's write service, asks `ExchangeMassChangeGuard`, counts every op and builds the result (ADR-0285); the write service writes and journals each entry; `ExchangeLiveSync` after commit. Ships and blueprints are written through the hangar's and the blueprint domain's own services; stock takes the Lager's lot locks through `InventoryItemRepository` and books out through the Lager's book-out, but books in by creating the `InventoryItem` row and its audit event itself |
| backend | `ExchangeUndoService`, the resource journals (`ExchangeBlueprintJournal`, `ExchangeStockJournal`, `ExchangeShipJournal`, `ExchangeOrgStockJournal`), `ExchangeMassChangeService` | The member's undo, dispatched to the journal class that owns each resource's typed journal state and restores it (ADR-0285), and the confirmation of a held mass change |
| backend | Bulk undo (`ExchangeBulkUndoService`, `ExchangeBulkUndoRunner`, `ExchangeBulkUndoStep`, `AdminExchangeBulkUndoController`, `V256`) | An admin's undo of one client for every member: suspends the client, then one member per transaction on the single-thread `exchangeBulkUndoExecutor`; runs and skipped entries kept 90 days (ADR-0227) |
| backend | `ExchangeResolveService`, `ExchangeDemandService`, `ExchangeDraftService` | Catalogue resolve through the web import's matching, the anonymised org demand, review drafts |
| frontend | „Verbundene Anwendungen" (`/connected-apps`, `/connected-apps/confirm`) | The member's clients, installations and activity; disconnect, undo, confirm a mass change — over `/api/v1/connected-apps`, member session only |
| frontend | Admin „Verbundene Anwendungen" (`/admin/exchange-clients`) | The registry and the global switch; a client's undo for every member with its runs |
| keycloak-spi | `ExchangeClientSessionResourceProviderFactory` (`basetool-exchange`) | Ends one client inside a member's shared user sessions when the member disconnects it (ADR-0226, REQ-XCH-008) |
| keycloak-spi | `DeviceConsentLoginFormsProviderFactory` (`krt-freemarker`) | Hands a device login's user code to the consent page, beside the phishing warning (ADR-0228, REQ-XCH-005) |

*Corrected 2026-09-29:* the write-services row said every exchange write goes "through the domain's
own services". The stock book-in does not: it is a second write path into the Lager, which the
[domain modularisation plan](../archive/DOMAIN_MODULARISATION_PLAN.md) (§5.3, §7.5) routes through the
inventory module's command API.

## 5.6 The monitoring plane

Generated from its own compose file (`docker-compose.monitoring.yml`); it reaches the application
through `net-monitoring-scrape` and the exporters' data-store networks, and is reached from outside
only through the edge's `net-proxy-grafana`: **prometheus**,
**grafana**, **loki**, **tempo**, **alertmanager**, **blackbox-exporter**, two
**postgres-exporters** and a **redis-exporter** as containers — and **node-exporter**, **alloy** and
**podman-exporter** as *host* services. §7.3 says why those three are not containers, and what was
deleted instead of being carried across. Every container that shares a network with an application
reaches its management port, so health there is open and everything else answers only to the scrape
identity Prometheus sends (ADR-0265, REQ-OBS-005). Rules, dashboards and probes:
[`monitoring/`](../../monitoring/README.md).
