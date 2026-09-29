> **Doc type:** Appendix of the living plan [Domain modularisation plan](../DOMAIN_MODULARISATION_PLAN.md) — measured on `origin/main` `95e945326` on 2026-09-29; counts are exact for that commit, line numbers drift.

# Evidence and data

The tables behind the plan. Where the adversarial verification round narrowed or refuted a figure,
the verified figure is used and a line marked **Verified** says so. Where two scans with different
methods give different figures, the table says which one it uses.

**Conventions.** Paths are relative to the repository root. `…backend.` stands for the package
prefix `de.greluc.krt.profit.basetool.backend.` (likewise `…frontend.`, `…ingest.`), and `…/` in a
Java path stands for `src/main/java/de/greluc/krt/profit/basetool/<module>/`, so
`backend/…/service/OwnerScopeService.java` is
`backend/src/main/java/de/greluc/krt/profit/basetool/backend/service/OwnerScopeService.java`. A bare
`Name.java:line` is the backend main class of that name (all 1,389 backend simple names are unique);
`ArchitectureTest.java` without a module is the backend test class. Frontend, ingest and
keycloak-spi classes are named with their module where the context does not make it clear.

**Sections.** [How the numbers were produced](#how-the-numbers-were-produced) ·
[Backend module inventory](#backend-module-inventory) · [Domain coupling](#domain-coupling) ·
[Cross-domain write families](#cross-domain-write-families) ·
[JPA associations across domains](#jpa-associations-across-domains) · [Hubs](#hubs) ·
[Authorization inventory](#authorization-inventory) ·
[ArchUnit rules under a package move](#archunit-rules-under-a-package-move) ·
[Path-keyed and name-keyed controls](#path-keyed-and-name-keyed-controls) ·
[Database coupling](#database-coupling) · [Frontend](#frontend) ·
[Modern language inventory](#modern-language-inventory) ·
[Ingest, keycloak-spi and build](#ingest-keycloak-spi-and-build) · [Sources](#sources)

## How the numbers were produced

| What | How | Scope |
| --- | --- | --- |
| Class dependency graph | `jdeps -verbose:class -filter:none` over `<module>/build/classes/java/main` (built from this commit); `-filter:none` keeps same-package edges. Nested classes folded into their top-level class (`Outer$Inner` → `Outer`), MapStruct `XMapperImpl` → `XMapper`, self-edges dropped | backend: 5,696 class edges between 1,389 top-level types; frontend and ingest likewise |
| Domain map | Every backend top-level type assigned by an ordered rule set: explicit table, external-feed packages, 30 name-prefix rules, infrastructure layer packages. A dependency-majority fallback exists and decided none | 1,389 types; rules in [Domain map](#domain-map) |
| Annotations and bytecode | `javap -v -p` reads annotation values (so constants that javac inlines are resolved); `javap -c` counts invocations | 1,765 backend, 791 frontend, 102 ingest main classes, nested included |
| Java idioms | A lexer that blanks comments and literal contents but keeps every offset, with regexes and bracket matching on top; switches cross-checked against bytecode; probes with javac 25 (`--release 25` and `21`), Checkstyle 14.3.0 with `config/checkstyle/google_checks.xml`, google-java-format 1.36.1 | 3,295 Java files (main 2,044, test 1,136, e2e 115) |
| Frontend assets | ASTs from espree and eslint-scope (JavaScript), postcss (CSS) and htmlparser2 (templates), used as parser libraries only | 100 scripts, 64 stylesheets, 120 templates |
| Schema | Replay of every versioned migration in statement order: tables, foreign keys, `ALTER … ADD/DROP`, triggers | 256 migrations |
| Write paths | Source scan for calls into another module's services, repository write and lock methods and entity setters, with the caller's transactional mode | backend main |
| SpEL, paths, allow-lists | Annotation values from bytecode, cross-checked by a source scan that resolves string constants; path matchers re-parsed with the semantics of the tests that own them (`ExternalContractTest`) | backend main, the edge allow-list |
| Web sources | Read on 2026-09-29 | [Sources](#sources) |
| Verification | The load-bearing and security-relevant figures were re-derived independently: an ArchUnit 1.5.1 probe on the compiled classes, exact minimum feedback-arc sets, javac probes, the Spring, Hibernate and ArchUnit sources at the pinned versions | lines marked **Verified** |

Limits:

- jdeps reads bytecode. SpEL strings, constants that javac inlines (the `Roles.*` values in
  `@PreAuthorize`, the `ScopeSpecifications` JPQL fragments), query strings and database triggers
  are invisible to it; each was counted by its own scan (sections below).
- The write scan follows field-typed calls and local setters. Writes through method chains or
  `ObjectProvider` lookups can be missed; the orgunit → bank responsibility hop is one.
- Counts marked *heuristic* assign classes to domains by simple-name prefix only. They show shape,
  not exact per-domain totals; every other per-domain count uses the domain map.
- Public-method counts are two-space-indented `public` members.
- Test classes were counted from source; their bytecode was stale.
- No production or testing host was read.

## Backend module inventory

Categories are today's 23 domain categories plus `shared-kernel` and `infrastructure`. Ce and Ca
count class edges to and from other categories, excluding `shared-kernel` and `infrastructure`.

| Category | Classes | LOC | Endpoints | JPA types¹ | Ce (edges → modules) | Ca (edges ← modules) | Target module (rank) |
| --- | ---: | ---: | ---: | ---: | --- | --- | --- |
| catalogue | 263 | 28,952 | 90 | 36 | 15 → 6 | 337 ← 10 | catalogue (2) |
| exchange | 169 | 19,185 | 35 | 10 | 197 → 13 | 11 ← 6 | exchange (12) |
| bank | 129 | 17,667 | 65 | 11 | 104 → 6 | 18 ← 5 | bank (11) |
| identity | 101 | 14,642 | 59 | 5 | 130 → 15 | 204 ← 17 | identity (3); 31 GDPR classes to privacy (13, transitional) |
| joborder | 95 | 11,761 | 39 | 10 | 263 → 9 | 39 ← 5 | joborder (10) |
| mission | 81 | 10,089 | 53 | 9 | 131 → 8 | 56 ← 7 | mission (9) |
| inventory | 54 | 9,168 | 27 | 3 | 156 → 9 | 70 ← 8 | inventory (8) |
| blueprint | 59 | 6,951 | 24 | 3 | 71 → 9 | 37 ← 5 | blueprint (7) |
| orgunit | 56 | 6,855 | 38 | 8 | 27 → 8 | 279 ← 16 | orgunit (4) |
| materialexchange | 34 | 5,001 | 21 | 4 | 87 → 8 | 11 ← 4 | materialexchange (10) |
| promotion | 41 | 4,717 | 34 | 5 | 39 → 4 | 1 ← 1 | promotion (7) |
| notification | 44 | 4,321 | 13 | 3 | 10 → 4 | 70 ← 5 | notification (1) |
| access | 16 | 3,798 | 0 | 0 | 52 → 9 | 126 ← 18 | platform access core (0), scope (5) |
| refinery | 26 | 3,497 | 14 | 2 | 99 → 9 | 23 ← 7 | refinery (10) |
| operation | 22 | 2,631 | 12 | 2 | 56 → 7 | 11 ← 2 | operation (11) |
| hangar | 20 | 2,578 | 15 | 1 | 56 → 7 | 29 ← 5 | hangar (7) |
| orgchart | 19 | 2,425 | 5 | 1 | 21 → 3 | 2 ← 1 | orgchart (7) |
| audit | 15 | 2,377 | 4 | 1 | 6 → 3 | 227 ← 15 | audit (1) |
| livesync | 13 | 2,002 | 2 | 0 | 4 → 2 | 3 ← 1 | livesync (1) |
| personalinventory | 12 | 1,172 | 10 | 1 | 8 → 2 | 1 ← 1 | personalinventory (7) |
| leadership | 12 | 1,039 | 3 | 0 | 33 → 3 | 6 ← 2 | merged into orgunit (4) |
| admin | 12 | 623 | 6 | 1 | 0 → 0 | 4 ← 3 | admin (6) |
| dashboard | 6 | 364 | 4 | 1 | 0 → 0 | 0 ← 0 | dashboard (6) |
| infrastructure | 71 | — | 1 | — | 44 → domains | 171 ← domains | platform (0); four classes to app (14) |
| shared-kernel | 19 | — | — | 1 | 6 → domains | 438 ← domains | kernel (0) |
| **Total** | **1,389** | **174,597** | **574** | **118** | | | |

¹ `@Entity` classes plus the two `@Embeddable` ids (`BankAccountGrantId`, `OrgUnitMembershipId`) and
the `@MappedSuperclass` `AbstractEntity`; there are 115 `@Entity` classes. Endpoints are handler
mappings (574 in 99 controllers, 572 of them documented in
`backend/src/main/resources/api/openapi.json`). LOC are physical lines (87,227 non-comment,
non-blank in total). 1,383 of the 1,389 types are `public`.

### Target modules and ranks

A module may depend on modules of lower rank (plan §5.1). Class counts assume the behaviour-free
re-homings of plan §7.3: leadership into orgunit, access split into the platform access core and
scope, the 31 GDPR classes into privacy, `HandleAnonymisation` into the kernel, `PayoutPreference`
into identity, the composition root into app.

| Rank | Module | Classes | Made of |
| ---: | --- | ---: | --- |
| 0 | kernel | 20 | shared-kernel 19 + `HandleAnonymisation` |
| 0 | platform | 75 | infrastructure 67 + access core 8 (`AuthHelperService`, `AuthenticatedSubject`, `SubjectAuthentication`, `Roles`, `Permissions`, `OrgUnitContextualAuthority`, two properties records) |
| 1 | audit · notification · livesync | 15 · 44 · 13 | unchanged |
| 2 | catalogue | 263 | unchanged, incl. the recipe graph (`Blueprint` and 7 child entities) |
| 3 | identity | 70 | identity 101 − 31 privacy − `HandleAnonymisation` + `PayoutPreference` |
| 4 | orgunit | 68 | orgunit 56 + leadership 12 |
| 5 | scope | 8 | `RequestScopeResolver`, `ScopePredicate`, `OrgUnitStampingService`, `OwnerScopeService`, `AccessGateService`, `ScopeSpecifications`, `CustomJwtGrantedAuthoritiesConverter`, `OwnerOrgUnitRequiredException` |
| 6 | admin · dashboard | 12 · 6 | unchanged |
| 7 | orgchart · promotion · personalinventory · hangar · blueprint | 19 · 41 · 12 · 20 · 59 | unchanged |
| 8 | inventory | 54 | unchanged |
| 9 | mission | 80 | mission − `PayoutPreference` |
| 10 | refinery · joborder · materialexchange | 26 · 95 · 34 | unchanged |
| 11 | operation · bank | 22 · 129 | unchanged |
| 12 | exchange | 169 | unchanged |
| 13 | privacy (transitional) | 31 | deletion, merge, consolidation, Art. 15 export, person search, handle anonymisation, registries |
| 14 | app | 4 | `BackendApplication`, `SecurityConfig`, `DataInitializer`, `BusinessMetricsCollector` |

Result of the layering: 2,088 downward class edges are allowed; 20 are same-rank edges
(infrastructure → access core 7, infrastructure → shared-kernel 6, shared-kernel → infrastructure 2,
joborder → materialexchange 3, refinery → joborder 2), acyclic except the shared-kernel ⇄
infrastructure pair; and **150 edges in 48 pairs point upward** ([backlog](#decoupling-backlog)).

### Module cards

Aggregates · proposed API · difficulty of making it a gated module.

- **kernel** — no aggregate · everything it holds is API: `AbstractEntity`, `OptimisticLock`,
  `Entities`, `PageResponse`, the generic exceptions, `StringNormalization`, `LikePatterns`, the
  validation constraints, `HandleAnonymisation`, new `UserRef`/`OrgUnitRef` value types, SCU
  rounding · low. Used by 21 modules.
- **platform** — no aggregate · web helpers, error handling, metric names (split per module),
  `AuthHelperService`, `AuthenticatedSubject`, `Roles`, `Permissions` · low–medium. Upward edges
  to remove: `CorrelationIdFilter → OwnerScopeService`, `ClientAttribution →
  IngestGatewayProperties`/`KnownExchangeClients`, `ApiClientMetricsFilter → ActingMemberHeader`,
  `AuthHelperService → OwnerScopeService` (service locator).
- **app** — composition root · depends on everything, nothing depends on it; queue gauges can move
  into modules as `MeterBinder`s · low.
- **audit** — `AuditEvent` · `AuditRecorder.record` (`MANDATORY`, returns nothing), `AuditDetails`,
  `AuditEventType`, `AuditDomain`; SPIs `ActorHandleResolver`, `RetentionParticipant`; endpoints
  `/api/v1/audit/**` · low.
- **notification** — `Notification`, `NotificationRule`, `NotificationRuleSelector` ·
  `NotificationEvent`, `OrgUnitRef`, `NotificationType`, `NotificationEventType`,
  `NotificationContextRole`, `MailService`/`MailMessage`, `NotificationCommands.markRead`; SPI
  `RecipientDirectory` · low–medium.
- **livesync** — no aggregate · `LiveSyncRelayService`, `LiveSyncTopic`; SPI
  `LiveSyncTopicAuthorizer` · low.
- **catalogue** — 36 JPA types in the sub-areas universe (66 classes), import (49), material (48),
  recipe (35), reference (24), ship (21), item (18), other (2) · reference DTOs and mappers,
  read-only queries per area, the quantity validation contract; entities stay association targets ·
  medium (size, master-data caches, import engine).
- **identity** — `User`, `Role`, `UserApprovalEvent`, `TermsAcceptance` (`DeletionRequest` in
  privacy) · `UserDirectory`, `UserReference`, `RoleCatalogue`, events `MemberDepartedEvent` and
  `UserRegistered`, the GDPR participant SPIs · high (GDPR fan-out; `syncUser` on the
  authentication path).
- **orgunit** — `OrgUnit` (`Squadron`, `SpecialCommand`, `Bereich`, `Organisationsleitung`),
  `OrgUnitMembership`, `KommandoGroup` · `OrgUnitRef`, `OrgUnitKind`, `MembershipRole`,
  `MembershipQueries`, `OrgUnitCascade`, reference DTOs and mappers; SPI
  `MembershipChangeObserver` · medium (foundation fan-in, appointment gates of REQ-ROLE-004).
- **scope** — no aggregate · `ScopePredicate`, `currentScopePredicate()` and the other scope
  vectors, the stamping resolvers, `OwnerOrgUnitRequiredException`, the authorities converter;
  endpoint `/api/v1/me/active-org-unit` · high (security core; the per-aggregate gates leave it).
- **admin** — `SystemSetting` · `SystemSettingService` · low.
- **dashboard** — `Announcement` · plus the read marker now under
  `/users/me/read-announcement/{id}` · trivial.
- **orgchart** — `OrgChartPosition` · implements `MembershipChangeObserver` · low–medium (the mirror
  stays in-transaction, REQ-ROLE-006).
- **promotion** — `PromotionTopic`, `PromotionCategory`, `PromotionLevelContent`,
  `RankRequirement`, `MemberEvaluation` · a GDPR participant only · low.
- **personalinventory** — `PersonalInventoryItem` · no published API needed · low.
- **hangar** — `Ship` · `ShipCommands` (add, update, delete for a client, lock), ship reference and
  summary for mission, `ShipAccessPolicy`, ship-deleted observer SPI · medium.
- **blueprint** — `PersonalBlueprint`, `DefaultBlueprint`, `BlueprintExternalAlias` ·
  `PersonalBlueprintCommands`, owners-per-product queries, product search, import preview, a
  `StockSliceProvider` SPI for craftability · medium.
- **inventory** — `InventoryItem`, `InventoryJobOrderAllocation`, `InventoryMissionAllocation` ·
  `StockCommands`, `PersonalStockLots`, stock queries (`StockSlice`, lots, earmarks),
  `InventoryAccessPolicy`; SPIs `EarmarkTargetPolicy`, `StockChangeObserver`, `StockSoldForTarget` ·
  high (concurrency-hot, ADR-0229, four writers today).
- **mission** — `Mission` and 8 children · mission reference and summary, `isParticipant`,
  `MissionFinanceQueries`, command `detachFromOperation`, observers for ship deleted, job type
  designated and stock sold, `MissionAccessPolicy`; SPIs `OperationSummaryProvider`,
  `MissionFinanceContributor` · high (section counters, three cycles, peer redaction, FQCN-keyed
  rules).
- **refinery** — `RefineryOrder`, `RefineryGood` · the draft import, profit queries for operation;
  implements `MissionFinanceContributor` and `StockSliceProvider`; `RefineryAccessPolicy` · medium.
- **joborder** — `JobOrder` and 8 children, `MaterialClaim` · job-order reference, an anonymised
  open-demand query for the exchange; implements `EarmarkTargetPolicy`; `JobOrderAccessPolicy`;
  events `JobOrderCreated`, `JobOrderUpdatedByRequester` · high (handover and production
  concurrency, bulk update after the loop, pessimistic reorder).
- **materialexchange** — 4 JPA types · implements `StockChangeObserver` (the offer ratchet), a GDPR
  participant · medium.
- **operation** — `Operation`, `OperationPayoutStatus` · implements `OperationSummaryProvider`;
  `OperationAccessPolicy` · low–medium (the `REQUIRES_NEW` payout-status retry stays inside).
- **bank** — 11 JPA types · participants (GDPR, retention, recipient directory), an account view
  query for live sync, the membership observer; endpoints `/api/v1/bank/**`,
  `/api/v1/org-units/bank/**` and the bank member search · medium (the ADR-0020 seam).
- **exchange** — 10 JPA types · `ChangeSource` for the transaction manager, a client directory for
  display names; 35 endpoints (14 frozen relay operations under `/api/v1/exchange/**`, 7 under
  `/connected-apps`, 14 admin) · low–medium once inventory has `StockCommands`; the first Gradle
  candidate.

### Domain map

Every top-level backend type is assigned by the first matching step.

| Step | Rule | Types |
| ---: | --- | ---: |
| 1 | Explicit table (below), each entry with a recorded reason | 152 |
| 2 | External feed DTO packages `…backend.dto.uex` (21), `…backend.dto.scwiki` (20), `…backend.dto.p4k` (7) → catalogue | 48 |
| 3 | Outbound catalogue clients in `…backend.integration` and `…backend.integration.scwiki` → catalogue | 2 |
| 4 | Ordered name-prefix rules on the simple name (below; an `Impl` suffix is ignored except for MapStruct) | 1,138 |
| 5 | Infrastructure layer packages: `config` 30, `filter` 5, `logging` 4, `metrics` 4, `web` 4, `annotation` 1, `interceptor` 1 | 49 |
| 6 | Dependency majority (fallback) | 0 |
| | **Total** | **1,389** |

Name-prefix rules, first match wins:

```text
 1  bank               ^OrgUnitBank
 2  bank               ^(Bank|CreateBank|CancelBank|ConfirmBank|RegisterBank|RejectBank|RenameBank|ReverseBank|SetBank|UpdateBank|SetCartel|OrgUnitBalanceTarget)
 3  exchange           ^(Exchange|ConnectedApp|FirstPartyClientIds|RedisExchange|DisabledExchange|KnownExchangeClients|ActingMember|IngestGateway)
 4  materialexchange   ^(MaterialExchange|MaterialRequest|MaterialItemRequest)
 5  joborder           ^(MaterialClaim|ClaimBucket|ClaimDto|CreateClaim|MaterialDemand)
 6  joborder           ^(JobOrder|CreateJobOrder|UpdateJobOrder|HandoverReport)
 7  personalinventory  ^PersonalInventory
 8  inventory          ^(Inventory|BulkCheckout|BulkRebook|BulkStolenMark|BulkOrgUnitChange|AggregatedInventory|GroupedInventory|AllocationReduction|StockViewerAccess|OwnedStockSlice)
 9  blueprint          ^(PersonalBlueprint|DefaultBlueprint|BlueprintImport|BlueprintExport|BlueprintCraftability|Craftability|BlueprintOverview|BlueprintProduct|BlueprintUploadPreview|BlueprintFuzzy|BlueprintOwner|BlueprintModifierMath|BlueprintVariant|BlueprintSource|BlueprintExternalAlias)
10  catalogue          ^(Blueprint)
11  mission            ^(Mission|AddCrew|AddExternalParticipant|AddParticipantById|AddUnit|JoinMission|UpdateCrew|UpdateParticipant|UpdatePayoutPreference|UpdateUnit|AddCustomFrequency|AddFrequency|UpdateCustomFrequency|CreateMission|PatchMission|ReorderMission|SetPartyLead|ToggleMissionStep|UpdateMission|AddMission|ParticipantTargetResolver)
12  operation          ^Operation
13  refinery           ^(Refinery|ImportIssue|ImportSuggestion)
14  catalogue          ^Refining
15  hangar             ^(Hangar|Ship(?!Type)|Fleet|Starjump|Shiplist|SquadronShip|SetHomeLocation)
16  notification       ^(Notification|LocalNotification|RedisNotification|RuleEvaluation|RecipientResolution|SelectorKind|OrgRelativeRole|Mail|SmtpMail)
17  audit              ^Audit
18  promotion          ^(Promotion|RankRequirement|MemberEvaluation)
19  orgchart           ^(OrgChart|BereichChart|CommandChart|OlChart|SpecialCommandChart|SquadronChart)
20  leadership         ^(Leitung|OrgRoleManagement|AddBereichLeader|AddOlMember|SquadronRole|AssignSquadronRank)
21  orgunit            ^(OrgUnit|Squadron|SpecialCommand|Bereich|Organisationsleitung|KommandoGroup|CreateKommandoGroup|UpdateKommandoGroup|OrgHierarchy|Membership)
22  identity           ^(User|Role|Registration|PendingRegistration|ApproveRegistration|RejectRegistration|ReopenRegistration|LinkRegistration|MyRegistration|Discord|Keycloak|Terms|DeletionRequest|CreateDeletionRequest|DecideDeletionRequest|DataExport|AdminDataExport|AccountDeletion|AccountConsolidation|ConsolidateAccount|MergeAccount|Handle|PersonSearch|AdminPersonSearch|AdminTerms|AdminDeletion|RejectedRegistration|ApprovalDecision|ApprovalStatus|AdminController$)
23  personalinventory  ^AdminPersonalInventory
24  blueprint          ^(AdminPersonalBlueprint|AdminDefaultBlueprint)
25  exchange           ^(AdminExchange)
26  catalogue          ^(AdminP4k)
27  dashboard          ^Announcement
28  admin              ^(SystemSetting|AppVersionPolicy|AndroidClient)
29  livesync           ^(LiveSync|LocalLiveSync|RedisLiveSync)
30  catalogue          ^(City|Location|StarSystem|SpaceStation|Outpost|Poi|Terminal|Planet|Moon|Orbit|Faction|Jurisdiction|Material|Manufacturer|ShipType|JobType|FrequencyType|GameItem|Uex|ScWiki|P4k|Sync|ExternalSync|MasterData|EvictAllMaterialCaches|QuantityType|LookupTable|PairKeyRef|StalePriceSweep|CachedEntityGraphs)
```

Explicit entries (step 1) by the category they decide:

| Category | n | Entries |
| --- | ---: | --- |
| access | 16 | `AccessGateService`, `AuthHelperService`, `AuthenticatedSubject`, `AuthoritiesCacheProperties`, `CustomJwtGrantedAuthoritiesConverter`, `OrgUnitContextualAuthority`, `OrgUnitStampingService`, `OwnerOrgUnitRequiredException`, `OwnerScopeService`, `PartialRoleScopeProperties`, `Permissions`, `RequestScopeResolver`, `Roles`, `ScopePredicate`, `ScopeSpecifications`, `SubjectAuthentication` |
| admin | 2 | `PingResponse`, `SystemController` |
| audit | 1 | `AuditLogPdfFormat` |
| bank | 5 | `BankAmounts`, `BankBalanceChart`, `BankConflictException`, `BankPdfFormat`, `CounterpartySnapshot` |
| blueprint | 1 | `BlueprintPackTags` |
| catalogue | 23 | `BlueprintController`, `BlueprintDismantleReturnDto`, `BlueprintDto`, `BlueprintIdNameRow`, `BlueprintIngredientKind`, `BlueprintMapper`, `BlueprintOutputNameOverrides`, `BlueprintProductRow`, `BlueprintReferenceDto`, `BlueprintRepository`, `BlueprintService`, `BlueprintSummaryPropertyDto`, `MaterialMatrixItemDto`, `MaterialPieceTypeLookup`, `ProfitCalculationController`, `ProfitCalculationDto`, `ProfitCalculationService`, `QuantityAware`, `RefineryYield`, `RefineryYieldRepository`, `UexCategory`, `ValidQuantityAmount`, `ValidQuantityAmountValidator` |
| exchange | 8 | `ChangeSource`, `ChangeSourceProperties`, `ChangeSourceTransactionManager`, `ConnectedInstallationDto`, `DatabaseActingMemberAuthorities`, `ExchangeProblemException`, `SandboxProfileGuard`, `TransactionManagerConfig` |
| hangar | 1 | `ShipTypeMatcher` |
| identity | 15 | `DataExportPdfFormat`, `DiscordSpiPrecheckProperties`, `KeycloakSyncProperties`, `KeycloakTrustSupport`, `MeController`, `MemberDepartedEvent`, `PendingApprovalAccessFilter`, `PendingRegistrationMailEventListener`, `PendingRegistrationMailService`, `RejectedRegistrationRetentionTask`, `TermsAcceptanceAccessFilter`, `TermsConsentCheck`, `UserApprovalMailEventListener`, `UserApprovalMailService`, `UserSyncTask` |
| infrastructure | 22 | `ApiClientMetricsProperties`, `AppProblemProperties`, `BackendApplication`, `BasetoolErrorController`, `BusinessMetricsCollector`, `CentralMapperConfig`, `ClientAttribution`, `DataInitializer`, `ErrorDisclosurePolicy`, `GlobalExceptionHandler`, `KeycloakHealthIndicator`, `KrtPdfSupport`, `ProblemResponseFactory`, `RateLimitProperties`, `RedisJsonFanout`, `RefusedSubjectWindow`, `RequestBodyLimitProperties`, `RequestMemo`, `ResilientRedisMessageListenerContainer`, `SseSendFailureCause`, `UserZone`, `UserZoneArgumentResolver` |
| inventory | 11 | `AllocationReductions`, `BulkRebookMode`, `CheckoutType`, `InventoryAllocations`, `InventoryCatalog`, `InventoryProperties`, `JobOrderAllocationDto`, `MaterialCollectionEntryDto`, `MissionAllocationDto`, `OverAllocationException`, `UpdateDeliveredRequest` |
| joborder | 9 | `AggregatedMaterialDto`, `DerivedMaterialDto`, `ItemDerivationDto`, `JobOrderInventoryOwnerRedactor`, `JobOrderItemStockController`, `MaterialCollectionController`, `ProductionAllocationException`, `QualityRequirement`, `SubAssemblySuggestionDto` |
| leadership | 2 | `BereichLeadershipRole`, `GrandAdmiralRequest` |
| livesync | 1 | `LiveSyncChangedRequest` |
| mission | 4 | `FinanceEntryAggregate`, `FinanceType`, `MissionFinanceGroupAggregate`, `PayoutPreference` |
| notification | 2 | `NotificationEvent`, `OrgUnitRef` |
| orgchart | 1 | `AreaLeadershipDto` |
| orgunit | 4 | `Department`, `MembershipRole`, `OrgUnitLabels`, `StaffelMembershipResolver` |
| personalinventory | 2 | `UexLocationController`, `UexLocationDto` |
| refinery | 3 | `MissionParticipantRequiredException`, `RefineryImportProperties`, `RefineryMissionProfitAggregate` |
| shared-kernel | 19 | `AbstractEntity`, `AppException`, `AppExceptionKind`, `BadRequestException`, `BusinessConflictException`, `DtoConstraints`, `DuplicateEntityException`, `Entities`, `EntityInUseException`, `ExternalServiceException`, `LikePatterns`, `NotFoundException`, `OnUpdate`, `OptimisticLock`, `PageResponse`, `ReportGenerationException`, `StringNormalization`, `WholeNumber`, `WholeNumberValidator` |

42 classes carry a recorded ambiguity. The decisions that shape the module cut:

| Class | Decided | Alternative | Why |
| --- | --- | --- | --- |
| `AccessGateService`, `OwnerScopeService` | access (scope) | split per domain | hold six aggregates' gates |
| `CustomJwtGrantedAuthoritiesConverter` | access (scope) | identity | needs identity sync and the orgunit cascade |
| `MeController` | identity | app shell | composite of identity, orgunit, notification and inventory flags |
| `SquadronRoleController`, `OrgRoleManagementSecurityService`, `BereichLeadershipRole`, `GrandAdmiralRequest` | leadership | orgunit | appointments are membership-row writes; merge recommended |
| `Blueprint` (+ 7 recipe entities), `BlueprintRepository`, `BlueprintMapper`, `BlueprintService`, `BlueprintController` | catalogue | blueprint | synced reference data read by four modules |
| `BlueprintProductService` | blueprint | catalogue | product search also reads personal blueprints |
| `BlueprintNameNormalizer` | catalogue | blueprint | used by the recipe sync and the import |
| `MaterialCollectionController`, `JobOrderItemStockController` | joborder | inventory | job-order pages composed from inventory reads |
| `MaterialCollectionEntryDto`, `JobOrderAllocationDto`, `MissionAllocationDto`, `AllocationReductions` | inventory | joborder or mission | earmark slices of inventory rows |
| `QualityRequirement` | joborder | catalogue | quality floor of job-order buckets and claims |
| `QuantityType` | catalogue | kernel | property of `Material`; six modules read it |
| `FinanceType`, `PayoutPreference` | mission | identity | `User.defaultPayoutPreference` points to identity |
| `RefineryYield` | catalogue | refinery | UEX matrix written by `UexRefinerySyncService` |
| `ShipTypeMatcher` | hangar | catalogue | written for the hangar import |
| `UexLocationController` | personalinventory | catalogue | served by `PersonalInventoryItemService` |
| `ProfitCalculation*` | catalogue | an own trade module | prices × ship types |
| `MailService` | notification | platform | REQ-NOTIF-013 seam |
| `ChangeSourceTransactionManager` | exchange | platform | app-wide transaction manager for the change feed |
| `PendingApprovalAccessFilter`, `TermsAcceptanceAccessFilter` | identity | platform | enforce identity rules |
| `OrgUnitRef` (event record) | notification | orgunit | part of the event contract |
| `DataInitializer`, `SecurityConfig`, `BusinessMetricsCollector` | infrastructure (→ app) | — | composition root |
| `UserMapper`, `UserDtoRedaction` | identity | split | derive or redact other modules' data |

### Size

Classes over 600 lines (36):

| Module | Classes (lines) |
| --- | --- |
| inventory | `InventoryCheckoutService` 1,213 · `InventoryItemRepository` 1,188 · `InventoryItemService` 1,147 · `InventoryItemController` 1,104 · `InventoryAggregationService` 1,090 |
| catalogue | `P4kImportService` 1,531 · `UexUniverseSyncService` 936 · `ScWikiItemSyncService` 924 · `ScWikiClient` 729 · `ScWikiBlueprintSyncService` 642 |
| bank | `OrgUnitBankAccessService` 1,753 · `BankBookingRequestService` 942 · `BankLedgerService` 794 · `BankAccountService` 627 |
| joborder | `JobOrderController` 1,126 · `JobOrderService` 1,074 · `MaterialClaimService` 635 |
| mission | `MissionController` 1,516 · `MissionService` 1,308 · `MissionParticipantService` 617 |
| access | `OwnerScopeService` 723 · `RequestScopeResolver` 680 · `AccessGateService` 677 |
| exchange | `ExchangeStockWriteService` 1,100 · `ExchangeShipWriteService` 887 · `ExchangeResolveService` 757 |
| identity | `KeycloakService` 914 · `UserController` 884 |
| infrastructure | `MetricNames` 1,015 · `GlobalExceptionHandler` 1,005 |
| refinery | `RefineryOrderService` 834 · `RefineryImportService` 703 |
| audit · materialexchange · orgchart · orgunit | `AuditEventType` 748 · `MaterialExchangeService` 618 · `OrgChartService` 843 · `OrgUnitMembershipService` 1,107 |

Most public methods — services: `OwnerScopeService` 56, `MissionService` 47,
`OrgUnitBankAccessService` 31, `InventoryItemService` 29, `AccessGateService` 28,
`InventoryAggregationService` 26, `RequestScopeResolver` 25, `OrgUnitMembershipService` 21,
`UserService` 19, `JobOrderService` 18; controllers: `MissionController` 47,
`JobOrderController` 34, `OrgUnitBankController` 29, `UserController` 27,
`InventoryItemController` 27. Most final-field dependencies: `UserDeletionService` 21,
`ExchangeStockWriteService` 19, `ExchangeUndoService` 17, `OrgUnitBankAccessService` 16,
`InventoryItemService`, `MissionService` and `ExchangeBulkUndoService` 14 each.

## Domain coupling

### Strongly connected components

- **Domain graph** (edge A → B when any class of A depends on a class of B; 23 non-kernel
  categories; 1,565 cross-domain class edges): **one SCC of 21**. Outside it: `admin`, `dashboard`.
- Without access, audit, notification and livesync: one SCC of 17. Additionally without identity,
  orgunit and catalogue (leadership then drops out by itself): **one SCC of 9** — blueprint,
  exchange, hangar, inventory, joborder, materialexchange, mission, operation, refinery.
- **Verified**, robust to the cut: dropping the 42 ambiguous classes gives 21 and 9; keeping only
  edges into services, repositories or entities gives 20 (leadership out) and 9; dropping edges
  into DTO, enum, exception and event types gives 20 and 9; entity → entity edges alone give two
  SCCs, {mission, operation, refinery} and {inventory, joborder}.
- **Verified — the SCC is a property of the domain (package-quotient) graph, not of the class
  graph.** The folded class graph has 8 SCCs of more than one class (sizes 14, 12, 11, 7, 5, 3, 2,
  2); only three cross domains: the sealed `AppException` family (14 classes in 7 categories), 12
  JPA entities of inventory ⇄ joborder, and 11 JPA entities of mission ⇄ operation ⇄ refinery. Every
  other domain cycle is a package tangle that re-homing or inverting single class edges can break.
- **Verified — size of the tangle.** Making the 21-domain SCC acyclic needs at least **136 class
  edges in 43 pairs** (exact minimum feedback-arc set over its 1,561 inner edges); the weighted
  Eades–Lin–Smyth ordering first used gives 211 in 52 pairs, an upper bound. The 9-domain core
  needs at least **46 edges in 9 pairs**, with the optimal order hangar < mission < inventory <
  blueprint < materialexchange < joborder < refinery < operation < exchange. The target ranks
  applied to the core without re-homing cost 61 edges in 11 pairs (plus 5 same-rank edges); the
  difference is mainly inventory ranked below mission (13 edges inventory → mission, 0 back), which
  the plan inverts through the earmark SPI and the stock-sold observer.
- **Verified — what keeps the core cycle alive.** Its 29 domain arcs (242 class edges) contain 3
  Hamiltonian cycles, so as few as 9 class edges keep all nine strongly connected, e.g.
  `BlueprintUploadPreviewService → ExchangeDraftService`, `ExchangeEntryLabels → Ship`,
  `HangarService → Mission`, `MissionMapper → Operation`, `OperationFinanceService → RefineryOrder`,
  `RefineryOrderService → InventoryItem`, `InventoryItemMapper → JobOrder`,
  `JobOrderHandoverService → MaterialExchangeOfferRatchet`, `MaterialExchangeService →
  BlueprintProductService`. The cheapest cuts detach one domain each: 4 edges (all of hangar's
  outbound edges: `ShipRepository → ExchangeShipRow`, `HangarService → Mission`, `→ MissionUnit`,
  `→ MissionUnitRepository`), 6 (all edges into exchange), 7 (all edges out of blueprint).
- Replaying 13 candidate moves never splits the SCC before the core pairs are inverted.
- The only cycle gate today, `backendPackagesShouldBeFreeOfDependencyCycles`
  (`ArchitectureTest.java:603-612`), slices on `…backend.(*)..` — the layer packages.

### Two-way pairs

41 domain pairs depend on each other in both directions. A → B is the heavier direction.

| # | Pair (A ⇄ B) | A → B | B → A | Sum |
| ---: | --- | ---: | ---: | ---: |
| 1 | joborder ⇄ inventory | 38 | 17 | 55 |
| 2 | refinery ⇄ catalogue | 49 | 1 | 50 |
| 3 | exchange ⇄ catalogue | 38 | 3 | 41 |
| 4 | joborder ⇄ identity | 35 | 6 | 41 |
| 5 | bank ⇄ orgunit | 37 | 1 | 38 |
| 6 | mission ⇄ identity | 31 | 6 | 37 |
| 7 | bank ⇄ identity | 27 | 9 | 36 |
| 8 | identity ⇄ audit | 33 | 3 | 36 |
| 9 | leadership ⇄ orgunit | 30 | 5 | 35 |
| 10 | exchange ⇄ blueprint | 29 | 4 | 33 |
| 11 | access ⇄ orgunit | 29 | 3 | 32 |
| 12 | operation ⇄ mission | 22 | 9 | 31 |
| 13 | identity ⇄ notification | 27 | 3 | 30 |
| 14 | mission ⇄ catalogue | 28 | 1 | 29 |
| 15 | identity ⇄ orgunit | 21 | 4 | 25 |
| 16 | exchange ⇄ access | 23 | 1 | 24 |
| 17 | identity ⇄ access | 18 | 6 | 24 |
| 18 | hangar ⇄ catalogue | 21 | 2 | 23 |
| 19 | inventory ⇄ identity | 19 | 2 | 21 |
| 20 | bank ⇄ notification | 16 | 4 | 20 |
| 21 | inventory ⇄ orgunit | 18 | 1 | 19 |
| 22 | joborder ⇄ access | 13 | 5 | 18 |
| 23 | materialexchange ⇄ identity | 16 | 1 | 17 |
| 24 | orgchart ⇄ orgunit | 15 | 2 | 17 |
| 25 | refinery ⇄ identity | 16 | 1 | 17 |
| 26 | exchange ⇄ identity | 14 | 1 | 15 |
| 27 | mission ⇄ hangar | 12 | 3 | 15 |
| 28 | exchange ⇄ inventory | 13 | 1 | 14 |
| 29 | refinery ⇄ mission | 9 | 5 | 14 |
| 30 | exchange ⇄ hangar | 12 | 1 | 13 |
| 31 | hangar ⇄ identity | 12 | 1 | 13 |
| 32 | materialexchange ⇄ inventory | 6 | 5 | 11 |
| 33 | inventory ⇄ access | 8 | 2 | 10 |
| 34 | mission ⇄ access | 8 | 2 | 10 |
| 35 | bank ⇄ audit | 7 | 2 | 9 |
| 36 | operation ⇄ access | 6 | 2 | 8 |
| 37 | refinery ⇄ access | 4 | 3 | 7 |
| 38 | hangar ⇄ access | 4 | 2 | 6 |
| 39 | promotion ⇄ identity | 4 | 1 | 5 |
| 40 | blueprint ⇄ identity | 2 | 2 | 4 |
| 41 | blueprint ⇄ refinery | 1 | 1 | 2 |

### Coupling matrix

Class edges; the row depends on the column.

```text
         SK  INF  cat  idn  org  acc  aud  ntf  lsy  adm  dsh lead  och  prm pinv  hng   bp  inv  msn  ref   op  job  mxc  bnk  xch
SK        .    2    .    .    .    1    .    .    .    .    .    .    .    .    .    .    .    1    .    1    .    1    .    1    1
INF       6    .    2   10    2   12    .    .    .    .    .    .    .    .    .    .    .    .    .    2    2    2    4    2    6
cat      83   28    .    .    .    1    7    .    .    .    .    .    .    .    .    2    .    .    1    1    .    .    .    .    3
idn      31   26    .    .   21   18   33   27    .    .    .    .    .    1    1    1    2    2    6    1    .    6    1    9    1
org      23    3    3    4    .    3    8    .    .    .    .    5    2    .    .    .    .    1    .    .    .    .    .    1    .
acc       3    1    .    6   29    .    .    .    .    .    .    .    .    .    .    2    .    2    2    3    2    5    .    .    1
aud       3    8    .    3    .    1    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    2    .
ntf       8   12    .    3    2    1    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    4    .
lsy       .    9    .    .    .    2    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    2    .
adm       3    1    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .
dsh       3    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .
lead      .    1    .    1   30    2    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .
och       5    .    .    5   15    .    .    .    .    .    .    1    .    .    .    .    .    .    .    .    .    .    .    .    .
prm      22    7    .    4   12    7   16    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .
pinv      6    3    4    .    .    .    4    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .
hng      10    2   21   12    7    4    8    .    .    .    .    .    .    .    .    .    .    .    3    .    .    .    .    .    1
bp       16    8   41    2    1    2   16    .    .    2    .    .    .    .    .    .    .    2    .    1    .    .    .    .    4
inv      26    1   55   19   18    8   20    .    .    .    .    .    .    .    .    .    .    .   13    .    .   17    5    .    1
msn      36    2   28   31   18    8   20    .    .    .    .    .    .    .    .   12    .    .    .    5    9    .    .    .    .
ref      10    2   49   16    8    4    4    .    .    .    .    .    .    .    .    .    1    6    9    .    .    2    .    .    .
op       10    1    .    6    6    6    8    .    .    1    .    .    .    .    .    .    .    .   22    7    .    .    .    .    .
job      45    8   82   35   52   13   28    9    .    .    .    .    .    .    .    .    3   38    .    .    .    .    3    .    .
mxc      19    .   16   16   20    7   12    8    .    .    .    .    .    .    .    .    2    6    .    .    .    .    .    .    .
bnk      53   22    .   27   37   16    7   16    .    1    .    .    .    .    .    .    .    .    .    .    .    .    .    .    .
xch      23   26   38   14    3   23   36   10    3    .    .    .    .    .    .   12   29   13    .    5    .    9    2    .    .
```

SK shared-kernel · INF infrastructure · cat catalogue · idn identity · org orgunit · acc access ·
aud audit · ntf notification · lsy livesync · adm admin · dsh dashboard · lead leadership · och
orgchart · prm promotion · pinv personalinventory · hng hangar · bp blueprint · inv inventory · msn
mission · ref refinery · op operation · job joborder · mxc materialexchange · bnk bank · xch
exchange.

### Decoupling backlog

Under the target ranks, 150 class edges in 48 pairs point upward. By source module: inventory 36,
identity 34, scope 17, mission 14, notification 9, blueprint 7, catalogue 7, kernel 6 (the sealed
`AppException`), platform 4, orgunit 4, hangar 4, audit 4, livesync 3, access core 1.

| From (rank) → to (rank) | Edges | Source classes → targets |
| --- | ---: | --- |
| identity (3) → orgunit (4) | 21 | `MeController` → OrgUnitMembershipOptionDto, OrgUnitMembershipQueryService; `UserController` → MembershipDeltaRequest, MembershipDeltaResponse, OrgUnitMembershipOptionDto, SquadronReferenceDto, OrgUnitMembershipQueryService; `UserDto` → SquadronReferenceDto; `UserDtoRedaction` → SquadronReferenceDto; `UserMapper` → OrgUnitKind, OrgUnitMembership, OrgUnitMembershipId, Squadron, SquadronReferenceDto, OrgUnitMembershipRepository, StaffelMembershipResolver; `UserService` → OrgUnitMembership, MembershipDeltaRequest, MembershipFlagsPatchRequest, OrgUnitMembershipQueryService, OrgUnitMembershipService |
| inventory (8) → joborder (10) | 17 | `AllocationReductions` → JobOrder; `InventoryAggregationService` → JobOrder, JobOrderItem, JobOrderItemStockEntryDto, JobOrderItemStockGroupDto, JobOrderRepository; `InventoryAllocations` → JobOrder; `InventoryAuditLabels` → JobOrder; `InventoryCheckoutService` → JobOrder; `InventoryItemMapper` → JobOrder; `InventoryItemRepository` → JobOrderGameItemStockRow, JobOrderMaterialStockRow; `InventoryItemService` → JobOrder, JobOrderItemStockGroupDto, JobOrderRepository, JobOrderItemService; `InventoryJobOrderAllocation` → JobOrder |
| inventory (8) → mission (9) | 13 | `AllocationReductions` → Mission; `InventoryAllocations` → Mission; `InventoryAuditLabels` → Mission; `InventoryCheckoutService` → FinanceType, Mission, MissionFinanceEntry, MissionParticipant, MissionFinanceEntryRepository, MissionParticipantRepository; `InventoryItemMapper` → Mission; `InventoryItemService` → Mission, MissionRepository; `InventoryMissionAllocation` → Mission |
| mission (9) → operation (11) | 9 | `Mission` → Operation; `MissionDto` → OperationDto; `MissionListDto` → OperationDto; `MissionMapper` → OperationMapper, Operation, OperationDto; `MissionPeerRedactor` → OperationDto; `MissionService` → Operation, OperationRepository |
| identity (3) → privacy (13) | 7 | `DiscordRegistrationAdminController` → MergeAccountRequest, UserAccountMergeService; `RejectedRegistrationRetentionService` → UserDeletionService; `UserController` → ConsolidateAccountRequest, AccountConsolidationService, UserDeletionService; `UserRegistrationService` → UserDeletionService |
| inventory (8) → materialexchange (10) | 5 | `InventoryCheckoutService` → MaterialExchangeOfferRepository, MaterialExchangeOfferRatchet; `InventoryStolenMarkService` → MaterialExchangeOffer, MaterialExchangeOfferStatus, MaterialExchangeOfferRepository |
| mission (9) → refinery (10) | 5 | `Mission` → RefineryOrder; `MissionFinanceEntryService` → RefineryOrder, RefineryOrderRepository; `MissionFinanceSummaryDto` → RefineryOrderDto; `MissionService` → RefineryOrder |
| scope (5) → joborder (10) | 5 | `AccessGateService` → JobOrder, JobOrderHandoverRepository, JobOrderItemHandoverRepository, JobOrderRepository; `OwnerScopeService` → JobOrder |
| blueprint (7) → exchange (12) | 4 | `BlueprintUploadPreviewService` → ExchangeBlueprintDraftDto, ExchangeDraftService; `PersonalBlueprintService` → ExchangeClientDisplayName, ExchangeClientRepository |
| notification (1) → bank (11) | 4 | `RecipientResolutionService` → BankAccountGrant, BankAccountGrantId, BankAccountGrantRepository, OrgUnitBankResponsibilityService |
| catalogue (2) → exchange (12) | 3 | `BlueprintRepository` → ExchangeBlueprintKeyRow; `GameItemRepository` → ExchangeItemKeyRow; `LocationRepository` → ExchangeLocationRow |
| hangar (7) → mission (9) | 3 | `HangarService` → Mission, MissionUnit, MissionUnitRepository |
| platform (0) → exchange (12) | 3 | `ApiClientMetricsFilter` → ActingMemberHeader; `ClientAttribution` → IngestGatewayProperties, KnownExchangeClients |
| notification (1) → identity (3) | 3 | `NotificationRuleService` → Role, RoleRepository; `RecipientResolutionService` → UserRepository |
| scope (5) → refinery (10) | 3 | `AccessGateService` → RefineryOrder, RefineryOrderRepository; `OwnerScopeService` → RefineryOrder |
| audit (1) → bank (11) | 2 | `AuditRetentionService` → BankAuditEventRepository, BankAuditService |
| audit (1) → identity (3) | 2 | `AuditService` → User, UserRepository |
| blueprint (7) → inventory (8) | 2 | `BlueprintCraftabilityService` → OwnedStockSlice, InventoryItemService |
| catalogue (2) → hangar (7) | 2 | `LocationService` → ShipRepository; `ShipTypeController` → ShipMapper |
| identity (3) → scope (5) | 2 | `MeController` → OwnerScopeService; `UserService` → OwnerScopeService |
| livesync (1) → bank (11) | 2 | `LiveSyncSubscriptionAuthorizer` → OrgUnitBankAccountDetailDto, OrgUnitBankAccessService |
| notification (1) → orgunit (4) | 2 | `OrgUnitRef` → OrgUnitKind; `RecipientResolutionService` → OrgUnitMembershipRepository |
| orgunit (4) → orgchart (7) | 2 | `KommandoGroupService` → OrgChartService; `OrgUnitMembershipService` → OrgChartService |
| scope (5) → hangar (7) | 2 | `AccessGateService` → Ship, ShipRepository |
| scope (5) → inventory (8) | 2 | `AccessGateService` → InventoryItem, InventoryItemRepository |
| scope (5) → mission (9) | 2 | `AccessGateService` → Mission, MissionRepository |
| scope (5) → operation (11) | 2 | `AccessGateService` → Operation, OperationRepository |
| access core (0) → scope (5) | 1 | `AuthHelperService` → OwnerScopeService |
| blueprint (7) → refinery (10) | 1 | `BlueprintCraftabilityService` → RefineryOrderService |
| catalogue (2) → mission (9) | 1 | `JobTypeService` → MissionParticipantRepository |
| catalogue (2) → refinery (10) | 1 | `LocationService` → RefineryOrderRepository |
| hangar (7) → exchange (12) | 1 | `ShipRepository` → ExchangeShipRow |
| identity (3) → bank (11) | 1 | `UserSyncService` → BankHolderReconciliationService |
| identity (3) → blueprint (7) | 1 | `UserReconciliationService` → DefaultBlueprintProvisioningService |
| identity (3) → inventory (8) | 1 | `MeController` → InventoryProperties |
| identity (3) → joborder (10) | 1 | `UserDtoRedaction` → JobOrderAssigneeDto |
| platform (0) → scope (5) | 1 | `CorrelationIdFilter` → OwnerScopeService |
| inventory (8) → exchange (12) | 1 | `InventoryItemRepository` → ExchangeStockLotRow |
| livesync (1) → scope (5) | 1 | `LiveSyncSubscriptionAuthorizer` → OwnerScopeService |
| orgunit (4) → bank (11) | 1 | `OrgUnitMembershipService` → OrgUnitBankResponsibilityService |
| orgunit (4) → inventory (8) | 1 | `OrgUnitMembershipService` → InventoryOrgUnitReconciler |
| scope (5) → exchange (12) | 1 | `CustomJwtGrantedAuthoritiesConverter` → IngestGatewayProperties |
| kernel (0) → bank (11) | 1 | `AppException` → BankConflictException |
| kernel (0) → exchange (12) | 1 | `AppException` → ExchangeProblemException |
| kernel (0) → inventory (8) | 1 | `AppException` → OverAllocationException |
| kernel (0) → joborder (10) | 1 | `AppException` → ProductionAllocationException |
| kernel (0) → refinery (10) | 1 | `AppException` → MissionParticipantRequiredException |
| kernel (0) → scope (5) | 1 | `AppException` → OwnerOrgUnitRequiredException |

### Edge kinds

The 1,565 cross-domain edges by source role → target role. The last column excludes access, audit,
notification and livesync as source or target (1,071 edges).

| Kind | All | Between business modules only |
| --- | ---: | ---: |
| service → foreign entity | 341 | 253 |
| service → foreign service | 215 | 76 |
| service → foreign repository | 206 | 182 |
| service → foreign enum | 125 | 63 |
| service → foreign support | 105 | 21 |
| dto → foreign dto | 77 | 77 |
| service → foreign dto | 72 | 71 |
| entity → foreign entity | 71 | 71 |
| mapper → foreign entity | 48 | 48 |
| mapper → foreign dto | 38 | 38 |
| event → foreign enum | 34 | 0 |
| controller → foreign service | 29 | 11 |
| controller → foreign dto | 26 | 25 |
| event → foreign event | 25 | 0 |
| mapper → foreign mapper | 23 | 23 |
| service → foreign mapper | 18 | 18 |
| support → foreign dto | 17 | 17 |
| other kinds (≤ 11 each) | 95 | 77 |

61 edges involve an event class. The only cross-domain listeners are `NotificationEventListener`
(all 14 `NotificationEvent` types, published by identity, bank, joborder, materialexchange and
exchange) and `ExchangeDepartureService` (`MemberDepartedEvent` from identity).

## Cross-domain write families

### Totals

- The write scan finds **363 cross-module write sites**:
  - **203 `AuditService.record` calls** from outside the audit module. Bytecode counts 206 calls in
    57 classes; the other 3 are inside audit. All are `@Transactional(propagation = MANDATORY)`
    (`AuditService.java:80`). `BankAuditService.record` has 51 calls in 14 classes, 1 of them from
    identity (`BankAuditService.java:86`, also `MANDATORY`).
  - **151 other writes in 57 caller methods**: 62 foreign service writes, 45 foreign repository
    writes, 37 foreign entity mutations, 7 foreign lock acquisitions. Two of the repository writes
    are handle anonymisations of audit rows, so **149 target non-audit modules**.
  - 9 seeding writes of `DataInitializer` (composition root) into identity and orgunit.
- Not counted as sites: `UserAccountMergeService.merge` (`@Transactional`,
  `UserAccountMergeService.java:190`) re-points 28 `table.column`s in 14 modules by native
  `UPDATE … SET col = :target` (`:321`) and a conflict `DELETE` (`:300`), bypassing `@Version`.
- Concurrency inventory of the backend main code: 31 `MANDATORY` methods in 15 classes (33
  `Propagation.MANDATORY` occurrences); 17 `REQUIRES_NEW` in 11 files; 12 `ObjectProvider<Self>`
  self-proxies, each calling back into its own class; 15 `@Lock` in 8 repositories
  (`PESSIMISTIC_WRITE` 13, `OPTIMISTIC_FORCE_INCREMENT` 2); 1 advisory-lock query; 87 `@Modifying`
  methods.
- Bean-cycle workarounds that mark hidden domain cycles: `AuthHelperService.java:230`
  `getBean(OwnerScopeService.class)`; `ObjectProvider<OrgUnitBankResponsibilityService>` in
  `OrgUnitMembershipService` and `UserDeletionService`.

### The sixteen families

Twelve need the caller's transaction, four may run after commit. "Sites" are rows of the write scan.

| # | Caller → callee | What is written (sites) | Why the caller's transaction is needed, or not | Mechanism in the plan |
| ---: | --- | --- | --- | --- |
| 1 | every module → audit | `AuditService.record` (203 sites) and one `BankAuditService.record` | REQ-AUDIT-001: the audit row is part of the business transaction, and an audit-insert failure rolls the mutation back | Direct call to `audit.api.AuditRecorder` (`MANDATORY`); never an event |
| 2 | joborder, refinery, exchange → inventory | New `InventoryItem` rows outside the owner (`ExchangeStockWriteService.java:799`, `JobOrderItemProductionService.java:383`, `RefineryOrderService.java:631`), row deletes and saves, `mergeStockIfRequested` and `bookOutForClient` (`MANDATORY`), allocation bulk deletes (`JobOrderService.java:298,458,750,753,854,967`) (51: joborder 27, exchange 15, refinery 9) | `PESSIMISTIC_WRITE` row locks (`JobOrderHandoverService.java:162`, `JobOrderItemHandoverService.java:237`, `JobOrderItemProductionService.java:205`, `ExchangeStockWriteService.java:521,527`) and the ADR-0229 advisory lot lock serialise check-then-decrement; append-only rule and stack merge; `clearAutomatically` bulk deletes run once after the loop. **Verified** on `JobOrderHandoverService.createHandover`: lock, validate, delete or reduce, one bulk delete, `completeJobOrderWithinTransaction` (`MANDATORY`), ratchet and audit, all in one transaction; after commit two handovers could double-consume | Command API `inventory.api.StockCommands` (`MANDATORY`); `PersonalStockLots` owns the ADR-0229 protocol |
| 3 | inventory, joborder, identity, exchange → materialexchange (offer ratchet) | `MaterialExchangeOfferRatchet.lower`, `beforeDelete`, `beforeWipe`, `beforeUserPurge` (16: inventory 9, joborder 6, identity 1); the exchange consumes the returned `Effects` through `bookOutForClient` | **Verified**: all four methods are `MANDATORY`; `beforeDelete` records `MARKET_OFFER_REMOVED` for offers that the FK `ON DELETE CASCADE` (`V210__add_material_exchange.sql:18`) deletes with the stock row, so after commit those audit rows would be lost; `lower` after commit leaves offers above the stock (REQ-MARKET-013); the ratchet runs before the delete it reacts to | Observer SPI `StockChangeObserver` owned by inventory, implemented by materialexchange; identity's purge through the GDPR participant |
| 4 | orgunit → orgchart | `OrgChartService.mirror*`, 9 methods (12) | **Verified**: all 9 are `MANDATORY` (`OrgChartService.java:272-437`); REQ-ROLE-006 requires the mirror in the same transaction as the rank change, and the mirror is the single writer of account-linked seats; no scheduled reconcile exists | Observer SPI `MembershipChangeObserver` owned by orgunit, implemented by orgchart |
| 5 | orgunit → inventory | `InventoryOrgUnitReconciler.onUserGainedFirstOrgUnit`, `onUserLostLastOrgUnit` (4) | Stock visibility follows the membership change in the same transaction | `MembershipChangeObserver`, implemented by inventory |
| 6 | orgunit → bank | `OrgUnitBankResponsibilityService` through `ObjectProvider` (`OrgUnitMembershipService.java:101,158`; missed by the scan) | The responsibility cleanup and its bank audit belong to the membership change | `MembershipChangeObserver`, implemented by bank |
| 7 | inventory → mission | New `MissionFinanceEntry` rows in `InventoryCheckoutService.createSaleFinanceEntries` (`:445-455`) (6) | The sale entry exists exactly when the stock left | Observer SPI for stock sold for a mission (`StockSoldForTarget`), implemented by mission |
| 8 | hangar → mission | `HangarService.detachFromMissionUnits` sets `MissionUnit.ship = null` (`:461-462`) (2) | Foreign-key order: the unit drops the ship before the ship row goes | Observer SPI "ship deleted" owned by hangar, implemented by mission |
| 9 | catalogue → mission | `JobTypeService.applyMissionLeadDesignation` bulk-clears participants' lead flags (`:214,226`, `@Modifying`) (2) | The designation and the cleared flags commit together | Observer SPI "job type designated" owned by catalogue, implemented by mission |
| 10 | operation → mission | `OperationService.deleteOperation` sets `mission.setOperation(null)` (`:270`); no mission section counter is bumped (1) | Foreign-key order before the operation row is deleted | Command `mission.detachFromOperation(operationId)` |
| 11 | identity (privacy) → all | `UserDeletionService.deleteUser` writes 12 foreign repositories plus the ratchet (`:199-289`); `HandleAnonymisationService.anonymise` runs `@Modifying` anonymisations on bank, job-order and audit tables and one bank audit record (`:141-189`); the native merge above (24 sites, the ratchet counted in family 3) | Foreign-key order (reassign, unlink, delete) inside one transaction; the deletion and anonymisation audit rows (REQ-AUDIT-001) | Identity-owned GDPR SPIs (`UserErasureParticipant` with phases REASSIGN, UNLINK, DELETE; `UserReassignmentParticipant`; `HandleAnonymisationParticipant`; `PersonalDataExportSection`; `PersonMentionSource`), `MANDATORY` inside the orchestrator's transaction |
| 12 | exchange → hangar, blueprint | `ExchangeShipWriteService.execute`, `lockOwn` (`:452-594`), `ExchangeUndoService.restoreShip`, `restoreBlueprint`, `ExchangeBlueprintWriteService.execute` (`:343,370`) through `HangarService` and `PersonalBlueprintService`; `ShipRepository.lockOwnedById` (`PESSIMISTIC_WRITE`) (12: hangar 8, blueprint 4) | The exchange journal and the change-feed rows (V252 triggers with change-source attribution) are written with the aggregate | Command APIs `ShipCommands` (hangar) and `PersonalBlueprintCommands` (blueprint) |
| 13 | identity → blueprint | `UserReconciliationService.syncUser` → `grantDefaultsToUser` (`:219,324`), inside `syncUser` on the authentication path (`CustomJwtGrantedAuthoritiesConverter.java:276`) (2) | Not needed: `DefaultBlueprintProvisioningTask` self-heals a missed grant | After-commit event (`UserRegistered`); the write leaves the login path |
| 14 | identity → bank | `UserSyncService.syncFromKeycloak` → `BankHolderReconciliationService.reconcileAll` (`:111`) (1) | Not needed: the nightly sync reconciles | After-commit event |
| 15 | identity → exchange (departure) | `ExchangeDepartureService.onDeparture` on `MemberDepartedEvent` (`:114`) | Already after commit (`fallbackExecution = true`, writes through `REQUIRES_NEW`); the role and approval gates refuse a role-less acting member meanwhile (REQ-XCH-009) | After-commit event, as today |
| 16 | identity, bank, joborder, materialexchange, exchange → notification | `NotificationEventListener` (`:56-57`) for the 14 `NotificationEvent` types; the registration and approval mail listeners | Already `@Async` + `AFTER_COMMIT` (REQ-NOTIF-002) | After-commit event, as today |

Families 2, 3 and 4 were re-derived independently and confirmed; the 12/16 split was not
re-derived as a whole.

### Other cross-module write rows

Single capability calls outside the families (18 sites).

| Caller → callee | Caller method | What | In the plan |
| --- | --- | --- | --- |
| access → identity | `CustomJwtGrantedAuthoritiesConverter.assembleAuthorities` (`:276`) | `UserReconciliationService.syncUser`; authentication path, bounded retry on 409 | identity API called downward from scope; family 13 leaves this path |
| audit → bank | `AuditRetentionService.purgeOlderThan` (`:71`) | `BankAuditService.purgeBefore` | `RetentionParticipant` SPI, implemented by bank |
| blueprint → admin | `DefaultBlueprintBootstrap.markSeeded` (`:157-161`) | a `SystemSetting` row | `SystemSettingService` (downward) |
| exchange → identity | `ConnectedAppsService.endSessions` (`:260-262`), `ExchangeDepartureService.onDeparture` (`:124`) | Keycloak consent and session calls (the departure after commit) | identity API (downward) |
| exchange → notification | `ConnectedAppsService.markSeen` (`:183`) | `NotificationRepository.markReadOfTypeAndEntity` (`@Modifying`) | `NotificationCommands.markRead` |
| identity → orgunit | `UserService.applyMembershipDelta`, `applySpecialCommandChange` (`:486`, `:508-521`) | `OrgUnitMembershipService.*`, membership flags | the membership endpoints and this code move into orgunit |
| leadership → orgunit | `SquadronRoleController.assignRank`, `removeRank` (`:76`, `:101`) | `OrgUnitMembershipService.*SquadronRankDto` | internal after the leadership merge |

### The 21 cross-domain `MANDATORY` hops

A hop is a pair of a `MANDATORY` target method and a calling module (31 `MANDATORY` methods in 15
classes).

| Target method | Caller → target | Hops | Call sites |
| --- | --- | ---: | ---: |
| `InventoryCheckoutService.bookOutForClient` (`:154`) | exchange → inventory | 1 | 3 for both inventory methods |
| `InventoryCheckoutService.mergeStockIfRequested` (`:668`) | exchange, joborder → inventory | 2 | (above) |
| `InventoryOrgUnitReconciler.onUserGainedFirstOrgUnit`, `onUserLostLastOrgUnit` (`:64`, `:85`) | orgunit → inventory | 2 | 4 |
| `MaterialExchangeOfferRatchet.lower`, `beforeDelete` (`:62`, `:94`) | inventory, joborder → materialexchange | 4 | 16 for all four ratchet methods |
| `MaterialExchangeOfferRatchet.beforeWipe` (`:109`) | inventory → materialexchange | 1 | (above) |
| `MaterialExchangeOfferRatchet.beforeUserPurge` (`:124`) | identity → materialexchange | 1 | (above) |
| `OrgChartService.mirror*`, 6 methods (`:272-391`) | orgunit (`OrgUnitMembershipService`) → orgchart | 6 | 12 for all nine mirror methods |
| `OrgChartService.mirror*KommandoGroup`, 3 methods (`:402-437`) | orgunit (`KommandoGroupService`) → orgchart | 3 | (above) |
| `BankAuditService.record` (`:86`) | identity (`HandleAnonymisationService`) → bank | 1 | 1 |
| **Total** | | **21** | **36** |

Not counted, because it is the platform recorder: `AuditService.record` (`:80`), called from every
module.

## JPA associations across domains

- **185 association fields**; **74 cross a domain**: 69 `@ManyToOne`, 2 inverse `@OneToMany`, 3
  `@ManyToMany`.
- Targets of the 74: `User` 24; orgunit 18 (`OrgUnit` 13, `Squadron` 4, `KommandoGroup` 1);
  catalogue 24; business entities 8.
- No cascade crosses a domain.
- **Verified**: all 148 `@ManyToOne`/`@OneToOne` annotations declare `LAZY`, and the one `EAGER`
  mapping of any kind in the backend model is the cross-domain `@ManyToMany`
  `MissionParticipant.orgUnits` → `OrgUnit` (`MissionParticipant.java:86`, with `@BatchSize(50)`).
  `toOneAssociationsAreDeclaredLazy` covers to-one mappings only; no rule covers collections.
- `org_unit.grand_admiral_user_id` is a plain id column, not an association.
- Other scans count differently: 187 association annotations over 115 entities (annotation grep),
  and 183 association fields with 96 crossing domains when classes are assigned to domains by
  simple-name prefix only. The per-field inventory above uses the domain map.

### Associations by module pair

Line numbers are in `backend/…/model/<Entity>.java`. M:1 `@ManyToOne`, 1:M `@OneToMany`, M:M
`@ManyToMany`; all `LAZY` unless marked. Bold rows are the eight business associations.

| Owner → target | n | Associations |
| --- | ---: | --- |
| joborder → catalogue | 6 | `JobOrderHandoverItem.material` M:1 Material @59; `JobOrderItem.gameItem` M:1 GameItem @72; `JobOrderItem.blueprint` M:1 Blueprint @81; `JobOrderItemMaterial.material` M:1 Material @67; `JobOrderMaterial.material` M:1 Material @60; `MaterialClaim.material` M:1 Material @71 |
| mission → identity | 6 | `Mission.owner` @250; `Mission.partyLeadUser` @270; `Mission.managers` M:M @284; `MissionOwnership.owner` @68; `MissionParticipant.user` @71; `MissionUnit.responsibleUser` @85 |
| joborder → orgunit | 5 | `JobOrder.responsibleOrgUnit` @79; `JobOrder.requestingOrgUnit` @87; `JobOrderHandover.executingSquadron` Squadron @83; `JobOrderItemHandover.executingSquadron` Squadron @92; `MaterialClaim.claimingOrgUnit` @89 |
| mission → catalogue | 5 | `MissionCrew.jobTypes` M:M JobType @64; `MissionFrequency.frequencyType` @73; `MissionParticipant.desiredMissionJobType` @96; `MissionParticipant.plannedMissionJobType` @101; `MissionUnit.shipType` @63 |
| joborder → identity | 4 | `JobOrderAssignee.user` @79; `JobOrderHandover.executingUser` @75; `JobOrderItemHandover.executingUser` @83; `MaterialClaim.claimedByUser` @103 |
| materialexchange → identity | 4 | `MaterialExchangeInterest.interestedUser` @66; `MaterialExchangeOffer.owner` @116; `MaterialExchangeRequest.owner` @130; `MaterialExchangeRequestInterest.interestedUser` @69 |
| refinery → catalogue | 4 | `RefineryGood.inputMaterial` @54; `RefineryGood.outputMaterial` @63; `RefineryOrder.location` @66; `RefineryOrder.refiningMethod` @80 |
| bank → identity | 3 | `BankAccountGrant.user` @69; `BankAccountGrant.grantedBy` @105; `BankHolder.user` @66 |
| blueprint → catalogue | 3 | `BlueprintExternalAlias.outputItem` @89; `DefaultBlueprint.outputItem` @83; `PersonalBlueprint.outputItem` @92 |
| inventory → catalogue | 3 | `InventoryItem.material` @79; `InventoryItem.gameItem` @90; `InventoryItem.location` @95 |
| hangar → catalogue | 2 | `Ship.shipType` @54; `Ship.location` @65 |
| materialexchange → orgunit | 2 | `MaterialExchangeOffer.owningOrgUnit` @124; `MaterialExchangeRequest.owningOrgUnit` @138 |
| mission → orgunit | 2 | `Mission.owningOrgUnit` @304; `MissionParticipant.orgUnits` M:M @86 **EAGER** |
| orgchart → orgunit | 2 | `OrgChartPosition.orgUnit` @86; `OrgChartPosition.kommandoGroup` KommandoGroup @142 |
| promotion → orgunit | 2 | `PromotionTopic.owningSquadron` Squadron @76; `RankRequirement.owningSquadron` Squadron @75 |
| bank → orgunit | 1 | `BankAccount.orgUnit` @93 |
| exchange → identity | 1 | `ExchangeInstallation.user` @63 |
| hangar → identity | 1 | `Ship.owner` @72 |
| hangar → orgunit | 1 | `Ship.owningOrgUnit` @83 |
| inventory → identity | 1 | `InventoryItem.user` @69 |
| **inventory → joborder** | 1 | `InventoryJobOrderAllocation.jobOrder` @75 |
| **inventory → mission** | 1 | `InventoryMissionAllocation.mission` @75 |
| inventory → orgunit | 1 | `InventoryItem.owningOrgUnit` @155 |
| materialexchange → catalogue | 1 | `MaterialExchangeRequest.requestedMaterial` @83 |
| **materialexchange → inventory** | 1 | `MaterialExchangeOffer.inventoryItem` @85 |
| **mission → hangar** | 1 | `MissionUnit.ship` @67 |
| **mission → operation** | 1 | `Mission.operation` @245 |
| **mission → refinery** | 1 | `Mission.refineryOrders` 1:M @239 (inverse, `mappedBy = mission`) |
| operation → identity | 1 | `OperationPayoutStatus.paidOutByUser` @112 |
| **operation → mission** | 1 | `Operation.missions` 1:M @67 (inverse, `mappedBy = operation`) |
| operation → orgunit | 1 | `Operation.owningOrgUnit` @78 |
| orgchart → identity | 1 | `OrgChartPosition.user` @97 |
| orgunit → identity | 1 | `OrgUnitMembership.user` @72 |
| refinery → identity | 1 | `RefineryOrder.owner` @61 |
| **refinery → mission** | 1 | `RefineryOrder.mission` @71 |
| refinery → orgunit | 1 | `RefineryOrder.owningOrgUnit` @129 |

### The eight business associations

Only these become id references (plan §5.6), each when its pair is decoupled.

| Association | Pair | Replaced by |
| --- | --- | --- |
| `InventoryJobOrderAllocation.jobOrder` | inventory → joborder | earmark target `(targetKind, targetId)` behind `EarmarkTargetPolicy` |
| `InventoryMissionAllocation.mission` | inventory → mission | the same |
| `MaterialExchangeOffer.inventoryItem` | materialexchange → inventory | the item id; the ratchet runs through `StockChangeObserver` |
| `MissionUnit.ship` | mission → hangar | the ship id; ship summary for mission; the ship-deleted observer |
| `Mission.operation` | mission → operation | `operation_id` as a UUID; `OperationSummaryProvider` |
| `Operation.missions` (inverse) | operation → mission | dropped; mission queries by operation id |
| `Mission.refineryOrders` (inverse) | mission → refinery | dropped; `MissionFinanceContributor` implemented by refinery |
| `RefineryOrder.mission` | refinery → mission | the mission id |

Entity coupling without an association: three job-order entities call the static
`InventoryItem.roundToScuScale` (`JobOrderMaterial.java:86`, `JobOrderHandoverItem.java:86`,
`MaterialClaim.java:116`); five modules reach SCU rounding through `InventoryItem.java:181`, which
is why it becomes a kernel value type.

## Hubs

### Top 30 classes by dependent modules

| # | Class | Module | Role | Modules | Classes | LOC | Verdict |
| ---: | --- | --- | --- | ---: | ---: | ---: | --- |
| 1 | `User` | identity | entity | 16 | 93 | 197 | foundation entity; publish `UserReference`; stays an association target |
| 2 | `AuthHelperService` | access core | service | 16 | 42 | 232 | platform API; drop its 4 scope delegations |
| 3 | `AuditDetails` | audit | support | 15 | 56 | 155 | audit API |
| 4 | `AuditEvent` | audit | entity | 14 | 56 | 127 | **hide**: exposed only as `record()`'s return type |
| 5 | `AuditEventType` | audit | enum | 14 | 56 | 748 | audit API; one closed vocabulary of 202 values |
| 6 | `AuditService` | audit | service | 14 | 56 | 218 | audit API (`MANDATORY`) |
| 7 | `UserRepository` | identity | repository | 13 | 35 | 502 | **split**: `UserDirectory` query API |
| 8 | `OwnerScopeService` | scope | service | 13 | 32 | 723 | **split** into per-module access policies |
| 9 | `OrgUnit` | orgunit | entity | 12 | 65 | 140 | foundation entity; `OrgUnitRef` value type |
| 10 | `OrgUnitKind` | orgunit | enum | 10 | 27 | 54 | orgunit API |
| 11 | `OrgUnitMembershipRepository` | orgunit | repository | 9 | 14 | 217 | **hide** behind `MembershipQueries` |
| 12 | `ScopePredicate` | scope | value | 9 | 12 | 69 | scope API |
| 13 | `SquadronReferenceDto` | orgunit | dto | 8 | 37 | 28 | orgunit API |
| 14 | `OrgUnitRepository` | orgunit | repository | 8 | 17 | 130 | **hide** behind a query API |
| 15 | `UserService` | identity | service | 7 | 14 | 553 | **split**: current-user seam versus internals |
| 16 | `UserMapper` | identity | mapper | 7 | 13 | 298 | **split**: reference mapping versus the Staffel-enriched view |
| 17 | `SquadronMapper` | orgunit | mapper | 7 | 12 | 82 | orgunit API |
| 18 | `Material` | catalogue | entity | 6 | 37 | 245 | foundation entity |
| 19 | `Location` | catalogue | entity | 6 | 24 | 78 | foundation entity |
| 20 | `QuantityType` | catalogue | enum | 6 | 16 | 26 | catalogue API |
| 21 | `InventoryItemRepository` | inventory | repository | 6 | 14 | 1,188 | **hide** behind `StockCommands` and stock queries |
| 22 | `MaterialRepository` | catalogue | repository | 6 | 13 | 248 | **hide** behind material queries |
| 23 | `OrgUnitMembershipQueryService` | orgunit | service | 6 | 10 | 463 | orgunit API |
| 24 | `GameItem` | catalogue | entity | 5 | 34 | 283 | foundation entity |
| 25 | `UserReferenceDto` | identity | dto | 5 | 24 | 26 | identity API (`UserReference`) |
| 26 | `Mission` | mission | entity | 5 | 17 | 376 | **hide** behind the mission API |
| 27 | `InventoryItem` | inventory | entity | 5 | 15 | 187 | **hide** behind the inventory API |
| 28 | `OrgUnitRef` | notification | event record | 5 | 15 | 32 | notification contract |
| 29 | `Roles` | access core | constants | 5 | 15 | 111 | platform API |
| 30 | `NotificationContextRole`, `NotificationEventType` | notification | enum | 5 | 14 | 34 / 116 | notification contract |

Kernel and platform hubs: `AbstractEntity` 19 modules, `OptimisticLock` 19, `Entities` 18,
`BadRequestException` 17, `PageResponse` 16, `PaginationUtil` 15, `NotFoundException` 11,
`StringNormalization` 11, `MetricNames` 8 (1,015 lines, 182 constants, 59 dependent classes; split
per module with byte-identical values), `CurrentUserId` 8, `ScheduledJob` 8, `TaskMetrics` 8.

### The scope and gate hub

Fan-in and fan-out from the class graph; domains here are *heuristic*.

| Class | Out (classes) | Out domains | In (classes) | In domains |
| --- | ---: | --- | ---: | --- |
| `OwnerScopeService` | 10 | security 4, orgunit 2, refinery, identity, joborder | 34 | 14 domains: joborder 7, promotion 6, inventory 3; operation, refinery, mission, identity, materialexchange, hangar 2 each; bank, blueprint, livesync, security, exchange 1 each |
| `AccessGateService` | 24 | orgunit 5, security 4, joborder 4, mission 2, refinery 2, operation 2, hangar 2, inventory 2, identity 1 | 3 | — |
| `RequestScopeResolver` | 14 | orgunit 8, security 4 | 3 | — |
| `OrgUnitStampingService` | 12 | orgunit 6, security 3, identity 1 | 1 | — |
| `ScopeSpecifications` | 0 | — (constants inlined) | 0 | — |
| `ScopePredicate` | 0 | — | 15 | 10 domains |
| `AuthHelperService` | 3 | security | 46 | 17 domains (bank 9, identity 5, …) |
| `AuditService` | 12 | — | 59 | 16 domains |
| `AuditEventType` | — | — | 63 | 16 domains |
| `AuditDetails` | — | — | 58 | 17 domains |
| `BankAuditService` | — | — | 16 | bank 14, audit 1, identity 1 |
| `BusinessMetricsCollector` | 21 | 9 repositories of 7 domains | 0 | — |
| `UserDeletionService` | 28 | 15 repositories of 10 domains | 5 | identity |
| `HandleAnonymisationService` | 16 | bank 7, audit 5, joborder 3 | — | — |
| `RecipientResolutionService` | 8 | bank 4 (`BankAccountGrant*`, `OrgUnitBankResponsibilityService`), orgunit, identity | — | — |
| `OrgUnitBankAccessService` | 56 | bank 39 | — | — |

`AccessGateService` (677 lines, 28 public methods, 11 final-field dependencies) holds 9
repositories of 7 domains (`AccessGateService.java:66-74`: `MissionRepository`,
`JobOrderRepository`, `JobOrderHandoverRepository`, `JobOrderItemHandoverRepository`,
`InventoryItemRepository`, `RefineryOrderRepository`, `OperationRepository`, `ShipRepository`,
`OrgUnitMembershipRepository`). `OwnerScopeService` (723 lines) has 56 public signatures with 55
distinct names over 9 domains.

The `support` package holds 63 classes: 43 belong to a domain module (exchange 10, identity 8,
catalogue 4, inventory 4, livesync 4, mission 3, notification 3, audit 2, joborder 2, orgunit 2,
materialexchange 1), 7 to the platform access core, 9 to the platform, 4 to the kernel (with
`HandleAnonymisation`). Among the domain helpers: `MissionPeerRedactor`, `MissionSectionVersions`,
`MissionViewerAccess`, `InventoryAllocations`, `InventoryAuditLabels`, `JobOrderAuditLabel`,
`JobOrderInventoryOwnerRedactor`, `StockViewerAccess`, `StaffelMembershipResolver`, and 18 of the 27
`@ConfigurationProperties` records.

## Authorization inventory

### Counts

| Module | `@PreAuthorize` | On | Distinct expressions | SpEL beans |
| --- | ---: | --- | ---: | --- |
| backend | 431 (378 method, 53 class) | 98 `@RestController`s (8 in `controller.exchange`), 1 `@Controller`, 7 services | 72 | 8 beans, 166 references |
| frontend | 245 (189 method, 56 class) | 88 controllers | 13 | none (role checks only) |
| ingest | 18 | 2 classes in `web` | 2 | none |

A source grep finds 432 `@PreAuthorize(` in backend main; the bytecode count is 431. Every one of
the 574 backend mappings carries a method- or class-level `@PreAuthorize`; 196 carry only
`isAuthenticated()` at annotation level (`OrgUnitBankController` 29, `InventoryItemController` 17,
`UserController` 12, `PersonalBlueprintController` 10, …).

### Where a gate can live

| Layer | Where | Size |
| --- | --- | --- |
| 1 URL rules | `SecurityConfig.java:362-440` | 31 `/api` path patterns on 25 lines; first match wins |
| 2 Controller `@PreAuthorize` | 99 controllers | 413 (53 class-level, 360 method-level); 214 of 574 mappings rely on the class-level one |
| 3 Service `@PreAuthorize` | `MemberEvaluationService`, `PromotionCategoryService`, `PromotionLevelContentService`, `PromotionTopicService`, `RankRequirementService`, `PromotionEligibilityService.evaluateAllForUserAsAdmin`, `MissionFinanceEntryService.updateEntry`/`deleteEntry` | 18 in 7 classes, none class-level |
| 4 Imperative service checks | `OrgUnitBankAccessService.requireCan*` (pinned by `ArchitectureTest.java:406-424`), `AccessDeniedException` throws in the promotion services | — |
| 5 Imperative controller checks | `RefineryOrderController.java:171-186` (owner override), `:197-220` (logistician reassignment) | — |

**Verified** for layer 3: exactly 18 annotations; no self-invocation of a gated method exists today.
For `PUT` and `DELETE /api/v1/finance-entries/{entryId}` and the promotion-category writes the
service gate is the only gate beyond authentication (the controllers say `isAuthenticated()`).

### Expressions

| Expression | Uses |
| --- | ---: |
| `isAuthenticated()` | 117 |
| `hasRole('ADMIN')` | 113 |
| `@missionSecurityService.canManageMission(#id, authentication)` | 28 |
| `hasAnyRole('ADMIN','OFFICER')` | 19 |
| `hasRole('BANK_MANAGEMENT')` | 9 |
| `isAuthenticated() and @ownerScopeService.canEditInventoryItem(#id)` | 9 |
| `hasRole('BANK_EMPLOYEE')` | 7 |
| `permitAll()` | 4 |
| 64 further expressions | — |

`support.Roles` constants appear 339 times in backend main source (`HAS_ROLE_ADMIN` 107, `ADMIN` 58,
`OFFICER` 41, `LOGISTICIAN` 26, `ADMIN_OR_OFFICER` 19, `KRT_MEMBER` 19, `BANK_EMPLOYEE` 16, …)
against a class-graph in-degree of 20, because javac inlines them. 247 mappings use a `Roles.*`
constant in their effective `@PreAuthorize`; 145 annotations start with one.

### SpEL bean references

**Verified**: 166 references to 8 beans, the same counts from bytecode annotation values and from a
source scan that resolves string constants.

| Bean | References | Distinct methods | Calling domains | Bean name | Mappings gated |
| --- | ---: | ---: | --- | --- | ---: |
| `ownerScopeService` | 66 | 18 | joborder 31, inventory 9, operation 8, refinery 8, mission 7, hangar 2, blueprint 1 | default | 67 |
| `missionSecurityService` | 40 | 6 | mission | default | 38 |
| `authHelperService` | 17 | 1 | mission | default | 17 |
| `exchangeGate` | 14 | 2 | exchange | explicit (`ExchangeGate.java:63`) | 14 |
| `orgRoleManagementSecurityService` | 13 | 8 | orgunit 10, leadership 3 | default | 13 |
| `bankSecurityService` | 10 | 5 | bank | default | 10 |
| `specialCommandSecurityService` | 5 | 1 | orgunit | default | 5 |
| `connectedAppsGate` | 1 | 1 | exchange | explicit (`ConnectedAppsGate.java:35`) | 7 |

"Mappings gated" comes from the REST mapping scan, which applies class-level annotations to every
handler; the backend-domain endpoint scan counts 66 for `ownerScopeService` and 8 for
`orgRoleManagementSecurityService`, and 165 in total. A scan of string literals only finds 113
annotations with a bean reference in 25 files, because it misses expressions built from constants.
The two `missionSecurityService` references without a mapping are the service-level gates of
`MissionFinanceEntryService`.

Domain-owned policy beans already exist: `MissionSecurityService`, `BankSecurityService` (bank only,
org-unit-blind by `ArchitectureTest.java:1794`), `SpecialCommandSecurityService`,
`OrgRoleManagementSecurityService`, `ExchangeGate`, `ConnectedAppsGate`. `AuthHelperService` (14
public methods) is used by 46 classes in 17 domains.

### `ownerScopeService` methods referenced from SpEL

18 of 55 public method names.

| Method | Refs | Method | Refs |
| --- | ---: | --- | ---: |
| `canEditJobOrder` | 12 | `canViewJobOrders` | 2 |
| `canSeeJobOrder` | 12 | `canSeeOperation` | 2 |
| `canEditInventoryItem` | 9 | `canViewOwnJobOrders` | 1 |
| `canSeeMission` | 7 | `canSeeJobOrderAsRequester` | 1 |
| `canEditRefineryOrder` | 5 | `canSeeJobOrderBlueprintOwners` | 1 |
| `canSeeOperationLedger` | 3 | `canAccessBlueprintOverview` | 1 |
| `canEditOperation` | 3 | `canSeeRefineryOrder` | 1 |
| `canEditShip` | 2 | `canViewUserRefineryOrders` | 1 |
| `canEditJobOrderAsRequester` | 2 | `canManageUserRefineryOrders` | 1 |

`LiveSyncSubscriptionAuthorizer` switches on the authorization kind (`:85-98`) into
`ownerScopeService.canSeeMission`, `canSeeOperation`, `canSeeJobOrder`, `canViewJobOrders`,
`canSeeRefineryOrder`, into `OrgUnitBankAccessService` and into `authHelperService`.

### URL-rule-only role gates

For 15 mappings the URL rule is the only role gate; the annotations say only `isAuthenticated()`
and the services check scope, not role.

| Mapping | Controller | URL rule |
| --- | --- | --- |
| `GET /api/v1/inventory/aggregated` | `InventoryItemController` | `hasAnyRole(ADMIN, OFFICER, LOGISTICIAN, KRT_MEMBER)` (`SecurityConfig.java:427-428`) |
| `GET /api/v1/inventory/material/{materialId}` | `InventoryItemController` | same |
| `GET /api/v1/inventory/game-item/{gameItemId}` | `InventoryItemController` | same |
| `GET /api/v1/inventory/all` | `InventoryItemController` | same |
| `GET /api/v1/inventory/mission/{missionId}` | `InventoryItemController` | same |
| `GET /api/v1/inventory/all/grouped` | `InventoryItemController` | same |
| `GET /api/v1/inventory/all/stack/entries` | `InventoryItemController` | same |
| `GET /api/v1/inventory/item-catalog` | `InventoryItemController` | same |
| `POST /api/v1/inventory` | `InventoryItemController` | same |
| `POST /api/v1/inventory/bulk-checkout` | `InventoryItemController` | same |
| `POST /api/v1/inventory/bulk-org-unit` | `InventoryItemController` | same |
| `POST /api/v1/inventory/bulk-stolen` | `InventoryItemController` | same |
| `POST /api/v1/inventory/bulk-rebook` | `InventoryItemController` | same |
| `GET /api/v1/hangar/squadron-overview` | `HangarController` | `hasAnyAuthority(HANGAR_READ, HANGAR_WRITE, ROLE_ADMIN)` (`SecurityConfig.java:419-423`) |
| `POST /api/v1/hangar/ships/home-location` | `HangarController` | same |

**Verified**: the list is exact. Narrowed to defence in depth: the role hierarchy
(`SecurityConfig.java:207-218`) makes nothing imply `KRT_MEMBER`, but by realm design no account
holds only a bank or mission-manager role (REQ-SEC-053), and bank roles carry no hangar permission.
No test pairs a bank-only role with these paths.

### Failure mode of a broken bean reference

**Verified**: `BeanFactoryResolver` turns a missing bean into an `AccessException`, SpEL wraps it in
a `SpelEvaluationException`, and Spring Security 7.1.1's `ExpressionUtils` throws
`IllegalArgumentException("Failed to evaluate expression …")` on every call, never at start-up.
`GlobalExceptionHandler.java:594-611` answers **400 `ILLEGAL_ARGUMENT`**, logged at WARN without a
stack trace; `basetool_http_error_total` is not incremented and no 5xx or Loki rule fires. The web
frontend counts backend 4xx as `basetool_backend_client_errors_total{reason="backend_4xx"}`, so a
burst on a web-used endpoint can trip `BackendCallFailureSustained` (> 0.5/s for 10 min); traffic of
the app on the API vhost is not counted. No test resolves the bean names: the only
`SpelExpressionParser` use in tests is `JacksonRecordTest`.

### `@PreAuthorize` by domain (heuristic)

| Domain | Sites | Domain | Sites | Domain | Sites |
| --- | ---: | --- | ---: | --- | ---: |
| catalogue | 60 | promotion | 24 | orgchart | 5 |
| bank | 56 | exchange | 17 | dashboard | 4 |
| mission | 55 | inventory | 16 | personalinventory | 2 |
| identity | 53 | hangar | 14 | materialexchange | 2 |
| joborder | 39 | refinery | 14 | notification | 2 |
| orgunit | 36 | operation | 12 | audit | 1 |
| admin | 6 | blueprint | 6 | livesync | 1 |
| leadership | 5 | unclassified | 1 | | |

## ArchUnit rules under a package move

- The backend `ArchitectureTest` has **43 rules**; the frontend has 7 and ingest 4, all keyed on
  annotations or the root package, so a package move does not affect them.
- Keys in the backend rules: 47 `..backend.<layer>..` package patterns, 4
  `getPackageName().contains(".backend.<layer>")` filters, 35 FQN string literals, 8 simple-name
  matches and 2 simple-name sets (12 services, 7 controllers). A grep over all test sources finds
  the same 47 `"..x.."` patterns and 54 quoted `de.greluc.krt.profit.basetool.…` literals, 37 of
  them in the backend `ArchitectureTest`.
- ArchUnit 1.5.1 fails an ArchRule whose selection is empty: `archRule.failOnEmptyShould` defaults
  to `TRUE` (bytecode of `AllowEmptyShould$3`), and the repository has no `archunit.properties`.
  Two rules opt out with `allowEmptyShould(true)` (`ArchitectureTest.java:718`, `:1693`). The check
  applies to ArchRules only: hand-rolled loops and AssertJ checks never fail on emptiness, and a
  rule whose *target* is an FQN string that no longer resolves passes silently.

Two independent classifications of the 43 rules under a move to `…backend.<domain>.…`:

- *Class 1*: V = can turn vacuous, W = weaker under partial moves, S = changes meaning, L = loud or
  robust.
- *Class 2* (step by step): silent = passes while checking less, partial = part loud and part
  silent, loud = the move fails the build, safe = the key does not depend on the package, stronger
  = the rule catches more.
- ★ = security-relevant (authorization, tenancy, redaction, the exchange's reduced authority).

| # | Rule (`ArchitectureTest.java` line) | Keyed on | Class 1 | Class 2 | ★ | Guard before moving |
| ---: | --- | --- | --- | --- | --- | --- |
| 1 | `serviceLayerShouldNotReachIntoSecurityContext` :202 | `..backend.service..` + FQN regex | W | silent | ★ | select `@Service`; selection floor |
| 2 | `controllerLayerShouldNotReachIntoSecurityContext` :219 | `..backend.controller..` | W | silent | ★ | select `@RestController` |
| 3 | `identityMustBeReadThroughTheSeamNotTheAuthenticationType` :234 | global; seam FQNs | L | loud | | class literals |
| 4 | `mapperLayerShouldNotReachIntoSecurityContext` :253 | `..backend.mapper..` | W | silent | ★ | select `@Mapper` |
| 5 | `controllerMethodsShouldNotReturnJpaEntities` :268 | `..controller..` | W | silent | | select `@RestController` |
| 6 | `toOneAssociationsAreDeclaredLazy` :284 | annotations | L | safe | | — |
| 7 | `controllersMustNotInjectTheLazyMembershipMapper` :298 | target FQN string | V | silent | | class literal |
| 8 | `everyRestControllerShouldDeclareAtLeastOneAuthorisationAnnotation` :316 | annotation | L | safe | | — |
| 9 | `permitAllIsDeclaredOnlyOnTheFourPublicEndpoints` :345 | loop over `contains(".backend.controller")`, no floor | V | silent | ★ | iterate `@RestController`/`@Controller`; floor |
| 10 | `readEndpointsMustDeclareAnAuthorisationAnnotation` :389 | `..controller..` | W | silent | ★ | `@RestController` + floor |
| 11 | `orgUnitBankSettingsMutationsMustCallAnAuthorizationHelper` :406 | simple name + return FQN | L | loud | | class literal |
| 12 | `controllerLayerShouldNotDependOnRepositoryLayer` :427 | layer packages on both sides | V | silent | ★ | `areAssignableTo(Repository)` |
| 13 | `controllerLayerMustNotWriteAuditRowsDirectly` :442 | `..controller..`; target by class literal | W | silent | ¹ | `@RestController` |
| 14 | `everyExchangeControllerMethodCarriesTheExchangeGate` :475 | `..controller.exchange..` | W | loud | ★ | module marker + floor |
| 15 | `exchangeControllersCallExchangeServicesOnly` :506 | target `contains(".backend.service")` | V | partial | ★ | "exchange → `*.api` only" module rule |
| 16 | `exchangeDtosStayInTheExchangeLayer` :525 | packages (selection and accessors) | L | partial | | — |
| 17 | `exchangeServicesNeverUseAdminGatesOrTheAdminScope` :542 | `..service.exchange..`; simple name `OwnerScopeService` | V | partial | ★ | re-key on the scope kernel API |
| 18 | `supportPackageMustStayADependencyLeaf` :577 | deny-list of layer packages | V | silent | ¹ | allow-list (`onlyDependOnClassesThat`) |
| 19 | `backendPackagesShouldBeFreeOfDependencyCycles` :603 | `backend.(*)..` slices | S | stronger | | add `backend.(*).(*)..` for layers inside modules |
| 20 | `mapperLayerShouldNotDependOnServiceLayer` :616 | packages | V | silent | | annotations |
| 21 | `integrationLayerShouldNotDependOnServiceLayer` :631 | packages | V | silent | | annotations |
| 22 | `eventLayerShouldNotDependOnServiceLayer` :646 | packages | V | silent | | annotations |
| 23 | `validationLayerMustStayADependencyLeaf` :661 | packages | V | silent | | — |
| 24 | `controllerMethodsShouldNotExposeJpaEntitiesInGenericWrappers` :677 | `..controller..` | W | silent | | `@RestController` |
| 25 | `mutatingServiceMethodsInReadOnlyClassesNeedExplicitTransactional` :692 | `..service..` | W | silent | | `@Service` |
| 26 | `repositoriesMustNotDeclareNoArgFindAll` :705 | `..repository..` + `allowEmptyShould(true)` | V | silent | | `areAssignableTo(Repository)`; drop `allowEmptyShould` |
| 27 | `writeEndpointsMustDeclareAnAuthorisationAnnotation` :737 | `..controller..` | W | silent | ★ | `@RestController` + floor |
| 28 | `staffelScopedServicesMustWireOwnerScopeOrAuthHelper` :978 | 12 simple names; field FQNs | W | partial | ★ | `@TenantScoped` marker (G-05) |
| 29 | `staffelScopedWriteEndpointsMustGateOnOwnerScopeService` :1036 | 7 simple names; SpEL substrings | W | silent | ★ | G-05; accept registered policy bean names |
| 30 | `peerReadableMissionEndpointsMustRedactPii` :1129 | `contains(".backend.controller")`; 3 DTO FQN strings; floor ≥ 10 | W | loud | ★ | floor = today's 22; `@RestController` |
| 31 | `responseOnlyDtosMustNotBeAcceptedAsRequestBodyOnWriteEndpoints` :1252 | `..controller..` + DTO FQN string | V | silent | ★ | class literal; structural rule (G-06) |
| 32 | `missionWriteRequestDtosMustNotCarryServerManagedFields` :1381 | FQN selection | L | loud | | class literal |
| 33 | `missionParticipantsCollectionMustExcludeOptimisticLock` :1479 | FQN | L | loud | | class literal |
| 34 | `promotionTopicOwningSquadronMustStayTypedSquadronNotOrgUnit` :1500 | FQN selection and compare | L | loud | | class literal |
| 35 | `missionServiceAddParticipantMustNotSaveMission` :1551 | selection FQNs; target FQN `MissionRepository` | V | partial | | class literals |
| 36 | `scWikiIntegrationClassesMustWireScWikiClient` :1659 | package + `allowEmptyShould(true)` | V | silent | | floor |
| 37 | `noNewJoinColumnReferencingSquadronIdOutsideGrandfatheredEntities` :1703 | `backend.model..` | W | silent | | `@Entity` |
| 38 | `bankClassesMustStaySeasonAndProfitIndependent` :1763 | simple-name prefixes (`Bank`, `Mission`, …) | V (renames) | safe | | module membership |
| 39 | `bankClassesMustNotConsultOrgUnitScope` :1794 | `Bank*` prefix + FQN `OwnerScopeService` | V | silent | ★ | re-key on module membership and class literals (ADR-0020) |
| 40 | `cascadeServiceMustNotConsultTheSecurityContext` :1812 | FQN `AuthHelperService` | V | silent | ★ | class literal |
| 41 | `delegatedRoleAuthoriserMustNotConsultOwnerScope` :1833 | FQN `OwnerScopeService` | V | silent | ★ | class literal + scope kernel |
| 42 | `orgUnitAwareBankSeamIsContainedToOneClass` :1853 | FQNs `OwnerScopeService` + `BankAccountRepository` | V (after the policy split) | loud | ★ | re-key; bridge set exactly `{OrgUnitBankAccessService}` |
| 43 | `bankLedgerRepositoriesMustStayInsertOnly` :1892 | FQN set; call-target FQN in clause 2 | V (clause 2) | partial | | class literals |

Totals: class 1 — 20 V, 14 W, 1 S, 8 L; class 2 — 25 silent, 6 partial, 8 loud, 3 safe, 1
stronger. 18 of the 34 V/W rules are ★. ¹ Class 2 also counts :442 (audit writes) and :577 (the
support leaf) as security-relevant.

### Verified reading of the disputed rules

The two classifications differ on exactly five rules.

| Rule | Class 1 | Class 2 | Verified |
| --- | --- | --- | --- |
| :475 `everyExchangeControllerMethodCarriesTheExchangeGate` | W | loud | depends on the move granularity: loud when the whole selection moves, weaker on a partial move |
| :1129 `peerReadableMissionEndpointsMustRedactPii` | W | loud | the same; today it selects **22** endpoints (`MissionController` 20, `MissionFinanceEntryController` 2), so 12 can leave unnoticed, and new or split controllers are never selected |
| :525 `exchangeDtosStayInTheExchangeLayer` | L | partial | the same lens question in reverse: loud on a whole-domain move, partial on a partial move |
| :1763 `bankClassesMustStaySeasonAndProfitIndependent` | V | safe | **safe** under a package move: selection `haveSimpleNameStartingWith("Bank")` and target prefixes are package-independent; only a rename can empty it |
| :1853 `orgUnitAwareBankSeamIsContainedToOneClass` | V | loud | **loud**: the selection is "depends on both FQN strings"; moving either target empties it and `failOnEmptyShould` fails the rule |

**Verified totals**, assuming the 38 rules both classifications agree on are right (4 of them were
re-checked: :345, :427, :577, :1794): on a whole-domain move **30 rules lose coverage without
failing** (silent or partial), 12 are loud or safe, 1 is stronger; on a partial or split move the
figures are 33, 9 and 1.

Selections today, from an ArchUnit 1.5.1 probe on the compiled classes:

- :345 visits 126 classes (package contains `.backend.controller`) and finds the 4 permitted
  methods: `AppVersionPolicyController.versionPolicy()`, `BasetoolErrorController.handleError(…)`,
  `DiscordAccountExistenceController.checkAccountExistence(…)`,
  `TermsDocumentController.document(…)`. After a whole-domain move it visits 0 classes and passes.
- :427 selects 126 controller classes against 123 repository classes. It stays silent while any
  controller remains in `..controller..`, is loud only when the last one leaves, and is vacuous if
  repositories move first.
- :577 selects 76 classes; its deny-list matches 781 of the 1,765 imported classes, and a
  `backend.<domain>.*` package matches none of its patterns.
- :1794 selects 111 `Bank*` classes (107 top-level) in 13 packages; the target FQN resolves and no
  class violates it today. Once `OwnerScopeService` leaves `backend.service`, the target matches
  nothing while the selection stays non-empty.

## Path-keyed and name-keyed controls

### Path-keyed controls

What a re-cut of `/api/v1` must carry along.

| # | Control | Where | Keyed on | If not updated | Silent | Guard |
| ---: | --- | --- | --- | --- | --- | --- |
| 1 | CSRF exemption | `SecurityConfig.java:111`, `CSRF_EXEMPT_PATHS = {"/api/v1/**", "/internal/**"}` | `/api/v1/**` | bearer writes outside `/api/v1` answer 403 in `prod` | yes in unit and integration tests: the `test` profile disables CSRF (`:315-317`) | G-07 |
| 2 | No-store families | `NoStoreApiScopes.java:42-55` | 14 path patterns | a sensitive `GET` gets `no-cache, must-revalidate` instead of `private, no-store` (REQ-SEC-031) | yes | G-07 |
| 3 | URL-matrix role gates | `SecurityConfig.java:362-440` | prefixes: `/api/v1/admin/**`, `/api/v1/bank/admin/**`, `/api/v1/audit/**` → ADMIN; `/api/v1/users/**` ADMIN catch-all; `/api/v1/inventory/**` roles; `/api/v1/hangar/**` authorities | layer 1 lost; the only role gate for the 15 mappings above | yes | G-02, G-03 |
| 4 | Edge admission | `docker/edge/include/api-allowlist.conf`: 172 rules (93 exact, 79 regex), two prefix rules `^/api/v1/terms/` (line 2) and `^/api/v1/me/` (line 3) | `$uri` | a moved app path answers 404; a path moved under `/me/` or `/terms/` becomes public | no for 404; yes for the prefix rules | G-08 |
| 5 | Edge read-only family | same file, line 177 (16 families) with 47 per-path resets and a `PUT`-only carve-out | family prefixes | a write moved into an allow-listed read family passes the edge's write refusal | yes | G-08 |
| 6 | Pending and terms allow-lists | `PendingApprovalAccessFilter.java:79,86`; `TermsAcceptanceAccessFilter.java:85-88` | exact paths + `/api/v1/terms/**` | a new endpoint under `/terms/` is reachable without consent; moved paths strand pending users | partly | G-07 |
| 7 | `/api/**` scopes | `ApiClientMetricsFilter.java:52`, `PendingApprovalAccessFilter.java:95`, `TermsAcceptanceAccessFilter.java:77`, `SubjectRateLimitingFilter.java:78`, `ApiCacheControlFilter.java:50`, `StreamAwareShallowEtagHeaderFilter.java:45`, `RateLimitProperties.java:56` | `/api/**` | a mapping outside `/api/**` loses the pending gate, terms gate, subject limiter, cache control, client metrics and ETag scoping at once | yes | G-07: no mapping outside `/api/**`, `/internal/**`, `/error` |
| 8 | Subject budget | `SubjectRateLimitingFilter.java:78-95` | 2 stream paths + 6 export segments (`export`, `export.json`, `statement`, `report`, `pdf`, `three-month-report`) | a re-cut stream loses its connect budget; an export escapes the 10/min bucket | yes | G-07 |
| 9 | Per-path rate-limit rules | `backend/src/main/resources/application.yml:180-227` | `/api/**` plus `mission-create`, `order-create`, `finance-entry-create`, `participant-mutations` (6 path patterns) | a moved create path loses its dedicated limit | yes | G-07 |
| 10 | Stream exclusions | `StreamAwareShallowEtagHeaderFilter.java:53-54`, `RequestLoggingFilter.java:48`, `NotificationStreamObservationPredicate.java:39` | stream paths | SSE buffered by the ETag filter, or logged | partly | G-07 |
| 11 | Request-body cap | `RequestBodyLimitProperties.java:42` | `/api/v1/refinery-orders/import-extract` | cap lost | yes | G-07 |
| 12 | Exchange relay routes | `ActingMemberFilter.java:98-115` | 13 exact patterns + the prefix | the exchange breaks | no | frozen tier T0; G-18 |
| 13 | Frozen app operations | `ExternalContractTest.java:304` (235 entries, 234 distinct verb + path pairs in 30 families) | path + verb | the test fails | no | G-23 |
| 14 | `openapi.json` | CI `git diff --exit-code` (`.github/workflows/ci.yml:67`) | paths | CI fails | no | — |
| 15 | Frontend backend calls | 259 distinct `/api/v1` literals in 87 frontend main files | literals | 404 at run time; unit tests mock the old string | partly | G-14 |
| 16 | Frontend live-sync probes | frontend `LiveSyncTopicClass` (7 probe paths) | paths | 404 → DENY (live updates silently stop); 405 or any other status except 403/404 → ALLOW for non-presence classes | yes | G-14 |
| 17 | Frontend terms, role and layout relays | `TermsAcceptanceGateFilter.java:98` (fails open, the backend still enforces), `BackendRoleSyncFilter.java:271,352`, `LayoutContextLoader.java:64` | exact paths | the UX gate is off; the backend still enforces | yes | G-14 |
| 18 | E2E suite | 63 distinct paths in 38 files | literals | E2E red | no | — |
| 19 | Blackbox probe | `monitoring/prometheus/prometheus.yml:136` | `/api/v1/terms/status` | false API-down | no | update with the path |
| 20 | Prometheus and Grafana `uri` selectors | none on backend API paths (`business.yml:523` lists frontend URIs) | — | — | — | — |
| 21 | Alloy and Loki | no API path matching | — | — | — | — |

**Verified** for row 1: the E2E stack runs the backend with the `dev` profile, where CSRF is on, so
a moved write that the E2E suite exercises fails there (E2E runs only on `e2e`-labelled pull
requests); and `POST /actuator/loggers/**` (`SecurityConfig.java:370-371`, management port) is
already outside the exemption, so the documented bearer-only log-level write is expected to answer
403 in production (not checked against production).

**Verified** for rows 4 and 5: 259 documented operations pass the `$uri` gate, 11 of them are then
refused 405 by the read-only family, and 248 reach the backend: the 234 frozen operations plus 14.
Ten of the 14 are called by the app v0.3.1 (gaps in the frozen contract), 4 are unused; all 14 stay
gated in the backend.

**Verified** for row 16: the fail-open covers every status other than 403 and 404 (400, 401, 405,
409, 429, 5xx), timeouts, a missing token and executor saturation; the capability class `orders`
fails open even on 403 and 404. The payload is section keys only (ADR-0094).

### Name-keyed controls (string-bound class names)

What a package move or rename must carry along by hand; no refactoring tool rewrites them.

| Item | Count and where | On a move or rename | Silent | Guard |
| --- | --- | --- | --- | --- |
| SpEL bean names | 8 beans, 166 references, 6 default-named | 400 `ILLEGAL_ARGUMENT` on every gated call | yes (fail-closed, WARN only) | G-04 |
| ArchUnit package patterns and FQN strings | 47 package patterns and 54 package strings in tests; 35 FQN literals and 8 simple names in the backend rules | rules turn vacuous or weaker | yes | G-01 |
| Simple-name whitelists | 12 services, 7 controllers in the staffel-scope rules | new or split classes are not selected | yes | G-05 |
| `ScopeSpecifications` constants | 6 package-private fragments, 29 splices in 7 repositories | compile error when a repository leaves `repository` | no | fragment moves with its access policy (plan §5.4); differential verdict test |
| FQCNs in `@Query` | 93 in 68 queries of 27 repositories (47 constructor expressions, 42 enum literals, 4 other) | start-up failure | no | a context test that boots all repositories; move checklist |
| Cross-domain JPQL entity names | 18 in 6 repositories (e.g. `OperationRepository` → `MissionParticipant`, `JobOrderRepository` → `SpecialCommand`) | start-up failure | no | the same |
| Thymeleaf `T(…frontend.support.Roles)` | 172 in 22 templates, all inside `sec:authorize` | render-time 500 on the affected pages | partly (only rendered pages are tested) | G-15 |
| Template bean references | `@moneyFormat` 70, `@handles` 44, `@markdown` 6 | explicit bean names, robust | — | — |
| Frontend session allow-list | prefix `…frontend.model.` (`SessionTypeAllowList.java:86-87`) | refused and dropped under `enforce` | yes | G-16 |
| Session type ids in Redis | `@class` FQCNs of stored values | values written before a rename are dropped (ADR-0157) | yes (metric only) | deploy note |
| `spring.factories` | `…config.SandboxProfileGuard` in 3 modules | start-up failure | no | move checklist |
| SpotBugs excludes | 2 (`EI_EXPOSE_REP*` on `…backend.model.*`, `BasetoolErrorController`) in `config/spotbugs/exclude.xml:7-18` | new findings | no | re-key with the move |
| PIT targets | `…${project.name}.service.*` (`build.gradle.kts:267-268`) | moved services are not mutated; zero mutations fail | partly | G-20 |
| JaCoCo floors and test heap | keyed on `project.name` (`build.gradle.kts:183-185`, `:228-241`) | a new Gradle module gets 0.50/0.40 and 1024 MB | yes | G-20 |
| Frontend DTO contract tests | `FrontendDtoContractTest:56` (floor > 100), `DtoOpenApiContractTest:57`, `GeneratedDtoAgreementTest:48` (floor > 200), keyed on `frontend.model.dto` | moved mirrors drop out above the floors | yes | marker annotation (plan §5.9); G-20 |
| `DtoMirrorConsistencyTest` | skips a frontend DTO whose backend twin is not in `backend/…/model/dto` (`:97-101`) | pairs drop out one by one; fails only when none is left | yes | G-20 |
| Non-recursive frontend tests | `I18nDictionaryCoverageTest:47,70` (`static/js`), `TemplateCommentHygieneTest:124` (pages) | per-domain folders narrow them | yes | G-20 |
| Cross-module test inputs by path | 13 (`crossModuleParitySources`, `liveSyncTopicRegistrySource`, `apiVhostAllowList`, `backendDtoMirrorSources`, `exchangeContractFixtures`, …) | the missing input fails the build | no | G-20: every `inputs.file(s)` path exists |
| `PUBLIC_BY_DESIGN` simple names | frontend `ArchitectureTest.java:213-222` | existence check fails | no | — |
| `TermsDocumentClientUsageTest` | field name `termsDocumentClient`, simple name `BackendApiClient` | fails | no | — |
| Live-sync parity tests | `LiveSyncTopicRegistryParityTest` (frontend enum by source path), `LiveSyncSectionMapParityTest` (about 30 fixed `/static/js/*.js` paths) | a moved file fails; new scripts are unchecked | partly | — |
| Persisted and alerted enum names | `AuditEventType` (202) and `AuditDomain` (12) in `audit_event`; `NotificationEventType` (14) in `notification_rule`; `ScheduledJob` (14) as the `task` label; `LiveSyncTopicClass` as `topic_class` | a rename breaks stored rows or silently retargets alerts | yes | names byte-identical; label-value parity test |
| E2E source paths and generator package | `frontend/build.gradle.kts:315,512`; `…frontend.contract.model` | build failure | no | move checklist |
| Request-attribute and memo keys | 6 + 5 from `Class.getName()` | consistent at run time | — | — |
| Cache names and Redis channels | 17 + 4 explicit strings | robust | — | — |
| Monitoring selectors on Java names | 0 | — | — | — |
| Duplicate simple names | 0 top-level in backend and frontend; 14 nested in backend (`Outcome` × 4, …) | springdoc schema collisions, caught by the CI diff | no | G-09 (simple names unique) |

OpenRewrite `ChangePackage` and `ChangeType` rewrite imports, fully qualified types, class-name
*values* in `application*.yml` and `.properties`, Spring beans XML and `META-INF/services`; they do
not touch SpEL, Thymeleaf, Java string literals, YAML keys or persisted type ids.

**Verified** for the session allow-list: the dropped unit is the whole session attribute, and Spring
keeps all pending flash maps in one attribute (`SessionFlashMapManager.FLASH_MAPS`), so one refused
form class drops every pending flash map of that session, the success and error toasts included.
There is no 500 and no sign-out. The repository default is `report`; production `enforce` is
documented (since 2026-09-25), not observable from the repository; the E2E stack runs `enforce`.

## Database coupling

- **117 tables, 195 foreign keys**, 105 of them between domains when tables are assigned to domains
  by name (heuristic); 256 versioned migrations up to `V258` in one directory, checked by a
  single-directory script (`scripts/check-flyway-migrations.sh:5`).
- Tables without an entity: `exchange_client_capability`, `mission_crew_job_types`,
  `mission_managers`, `mission_participant_org_unit`, `role_permissions`, `user_roles`.
- `audit_event.event_type` and `domain` store enum names and carry no `CHECK` constraint
  (`V179__create_audit_event.sql:10`).

### Foreign keys by target

| Target | Cross-domain FKs | All FKs to the target |
| --- | ---: | ---: |
| `app_user` | 46 | 53 |
| `org_unit`, `squadron` | 22 | 23 |
| catalogue tables | 29 | — |
| business tables | 8 | — |
| **Total** | **105** | |

### Cross-domain foreign keys by module pair (heuristic)

| Pair | FKs | Pair | FKs |
| --- | ---: | --- | ---: |
| bank → identity | 12 | inventory → catalogue | 3 |
| blueprint → catalogue | 7 | mission → orgunit | 3 |
| exchange → identity | 7 | bank → orgunit | 3 |
| mission → identity | 6 | hangar → catalogue | 2 |
| refinery → catalogue | 6 | orgunit → identity | 2 |
| joborder → orgunit | 6 | promotion → orgunit | 2 |
| mission → catalogue | 5 | materialexchange → orgunit | 2 |
| joborder → catalogue | 5 | notification → identity | 2 |
| joborder → identity | 4 | further pairs | 1 each |
| materialexchange → identity | 4 | | |

### Business → business foreign keys

| FK | Pair | JPA side |
| --- | --- | --- |
| `refinery_order.mission_id` | refinery → mission | `RefineryOrder.mission`; inverse `Mission.refineryOrders` |
| `mission.operation_id` | mission → operation | `Mission.operation`; inverse `Operation.missions` |
| `mission_unit.ship_id` | mission → hangar | `MissionUnit.ship` |
| `material_exchange_offer.inventory_item_id` | materialexchange → inventory | `MaterialExchangeOffer.inventoryItem`; `ON DELETE CASCADE` (`V210__add_material_exchange.sql:18`) |
| `inventory_item_job_order_allocation.job_order_id` | inventory → joborder | `InventoryJobOrderAllocation.jobOrder` |
| `inventory_item_mission_allocation.mission_id` | inventory → mission | `InventoryMissionAllocation.mission` |
| `job_order_item.blueprint_id` | joborder → recipe graph | `JobOrderItem.blueprint`; the domain map assigns the recipe graph to catalogue |
| `exchange_ship_link.ship_id` | exchange → hangar | no JPA association (plain id column) |

Foreign keys stay: they are integrity, not coupling (plan §5.6).

### Triggers

18 triggers survive the migration replay.

| Migration | Table | Triggers | Crosses modules |
| --- | --- | ---: | --- |
| V252 | `personal_blueprint`, `default_blueprint`, `inventory_item`, `ship` — write `exchange_change` and read `current_setting('basetool.change_source')` | 4 | yes: an exchange migration on blueprint, inventory and hangar tables (`V252__create_exchange_change_feed.sql:67,88,126,144`) |
| V101 | `promotion_topic` guard, reads `org_unit.kind` | 1 | yes (promotion → orgunit) |
| V135 | `rank_requirement` guard, reads `org_unit.kind` | 1 | yes (promotion → orgunit) |
| V185 | `kommando_group` guards, read `org_unit.kind` | 4 | no under the domain map (the table-name heuristic counted them as cross-domain) |
| V98, V164, V165 | `org_unit_membership` | 6 | no |
| V164 | `org_unit` | 2 | no |

A column rename in a fed table is not caught by Flyway: plpgsql bodies are checked at execution,
so every insert into the table fails at run time (guard G-10).

### Query strings

- 363 `@Query` (180 as text blocks, 47 built by `+` concatenation), 29 native; 6 dynamic-query sites
  (`createQuery`, `createNativeQuery`, `jdbcTemplate`).
- 93 FQCNs in 68 queries of 27 repositories; 18 cross-domain entity names in 6 repositories.
- `ScopeSpecifications` (`…backend.repository`) declares six package-private `static final String`
  fragments (`:48,64,79,92,105,119`); javac inlines them, so the class has 0 class-graph edges:

| Repository | Fragment | Splices |
| --- | --- | ---: |
| `InventoryItemRepository` | `INVENTORY_ITEM_SCOPE_TRIPLE` | 12 |
| `ShipRepository` | `SHIP_SCOPE_TRIPLE` | 5 |
| `RefineryOrderRepository` | `REFINERY_ORDER_SCOPE_TRIPLE` | 4 |
| `OperationRepository` | `OPERATION_SCOPE_PREDICATE` (embeds `MissionParticipant`, `:55`) | 3 |
| `MissionRepository` | `MISSION_SCOPE_PREDICATE` | 2 |
| `JobOrderRepository` | `JOB_ORDER_SCOPE_PREDICATE` (embeds `TYPE(o.responsibleOrgUnit) = SpecialCommand`, `:123`) | 2 |
| `MaterialExchangeOfferRepository` | reuses `INVENTORY_ITEM_SCOPE_TRIPLE` | 1 |

At least one query hand-writes a deliberately narrowed variant instead of splicing a fragment:
`MissionRepository.java:151-157`, the next-mission banner. 15 service classes consume
`ScopePredicate`; only `JobOrderScopeQueryIntegrationTest` checks a list query against its gate.

### Native-SQL registries

| Registry | Size | Modules named |
| --- | --- | ---: |
| `DataExportSections.SECTIONS` (`:61`) | 36 sections | 16 |
| `PersonSearchTargets` (`:47,83`) | 80 targets, 19 areas, 39 tables | 18 |
| `HandleErasureCoverage.COVERAGE` (`:80`) | 79 `table.column` keys in 42 tables | 16 |
| `UserAccountMergeService` (`:90`, `:134`) | 28 `FOLLOWS_THE_MEMBER` and 27 `STAYS_WITH_THE_ACT` `table.column`s | 14 |
| `HandleAnonymisationService` (`:141-189`) | `@Modifying` anonymisations on bank, job-order and audit tables | 3 |

Schema sweeps that hold these registries against `information_schema`, independent of packages:
`PersonSearchCoverageTest`, `HandleErasureCoverageTest`, `DataExportScrubCoverageTest`,
`UserAccountMergeCoverageTest`, `UserIdentityColumnForeignKeyTest`, `ForeignKeyIndexCoverageTest`,
`HandleSpellingCoverageTest`.

## Frontend

### Packages

`frontend/src/main/java`: 554 classes, 66,858 lines (34,716 non-blank, non-comment).

| Package | Classes | Lines | Non-blank, non-comment |
| --- | ---: | ---: | ---: |
| `controller` | 103 | 33,630 | 21,659 |
| `model.dto` | 292 | 11,906 | 2,648 |
| `config` | 62 | 8,994 | 4,551 |
| `websocket` | 9 | 3,226 | 1,813 |
| `service` | 11 | 2,047 | 1,039 |
| `model.form` | 30 | 1,611 | 515 |
| `logging` | 14 | 1,540 | 810 |
| `support` | 13 | 1,118 | 400 |
| `exception` | 2 | 740 | 489 |
| `metrics` | 3 | 627 | 148 |
| `health` | 3 | 535 | 282 |
| `oss` | 5 | 413 | 138 |
| `model` (root) | 3 | 246 | 151 |
| `validation` | 2 | 113 | 32 |
| `view` | 1 | 67 | 25 |
| (root) | 1 | 45 | 16 |

`model.dto` holds 279 records, 12 enums and 1 annotation; `model.form` 17 records and 13 classes;
the `model` root holds `PayoutPreference` (used by mission, operation and identity), `ScLink`,
`ScLinkCategory`. All 62 `config` classes are cross-cutting: security and authentication flow 20,
session 12, web/MVC/i18n/binding 8, observability 7, backend hop 6 (`AppBackendProperties`,
`AppHttpProperties`, `CacheConfig`, `ReactorContextPropagationConfig`,
`Resilience4jMetricsConfig`, `WebClientConfig`), layout model 6, live sync 3.

### Controllers by domain

| Domain | View | REST | Helper | Controller lines | Backend call sites | DTOs | Forms | Controllers |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| catalogue | 10 | 2 | 1 | 4,508 | 92 | 32 | 4 | `MaterialsPageController`, `MaterialProxyController`, `ProfitCalculationPageController`, `CatalogSearchController`, `AdminMaterialsPageController`, `AdminMaterialAliasesPageController`, `AdminLocationsPageController`, `AdminUexPageController`, `AdminP4kImportPageController`, `AdminSyncReportsPageController`, `AdminMissionDataPageController`, `ShipDataPageController`; helper `PlanetColorResolver` |
| mission | 3 | 0 | 1 | 3,262 | 81 | 20 | 5 | `MissionPageController`, `MissionWriteController`, `MissionFinancePageController`; helper `MissionDetailModelBuilder` |
| bank | 6 | 3 | 5 | 3,203 | 42 | 25 | 0 | `BankPageController`, `BankProxyController`, `BankReportProxyController`, `BankGrantsPageController`, `BankManagePageController`, `BankRequestQueuePageController`, `AdminBankPageController`, `OrgUnitBankPageController`, `OrgUnitBankProxyController`; helpers `BankAccountDetailSupport`, `BankAccountOrder`, `BankBalanceChart`, `BankDashboardViewAssembler`, `BankSparkline` |
| joborder | 5 | 1 | 0 | 3,132 | 61 | 41 | 4 | `JobOrderPageController`, `JobOrderWriteController`, `JobOrderHandoverReportProxyController`, `JobOrderMaterialDemandPageController`, `ItemCollectionPageController`, `MaterialCollectionPageController` |
| inventory | 2 | 3 | 0 | 2,771 | 33 | 24 | 2 | `InventoryPageController`, `InventoryWriteController`, `InventoryDeleteAllProxyController`, `InventoryOrgUnitChangeProxyController`, `InventoryStolenMarkProxyController` |
| identity | 10 | 3 | 0 | 2,747 | 54 | 19 | 5 | `ProfileController`, `ProfileRsiHandleProxyController`, `DeletionRequestProxyController`, `DataExportProxyController`, `MemberManagementController`, `UserProxyController`, `AdminDiscordRegistrationsPageController`, `AdminDeletionRequestsPageController`, `AdminPersonSearchPageController`, `PendingApprovalPageController`, `TermsAcceptancePageController`, `TermsController`, `AdminTermsPageController` |
| refinery | 3 | 0 | 0 | 1,925 | 25 | 11 | 4 | `RefineryOrderPageController`, `RefineryOrderWriteController`, `RefineryImportProxyController` |
| blueprint | 5 | 1 | 0 | 1,746 | 33 | 31 | 0 | `PersonalInventoryBlueprintsPageController`, `PersonalBlueprintImportProxyController`, `BlueprintOverviewPageController`, `AdminBlueprintsPageController`, `AdminDefaultBlueprintsPageController`, `AdminPersonalBlueprintsPageController` |
| orgunit | 4 | 2 | 0 | 1,455 | 26 | 13 | 3 | `AdminOrgStructurePageController`, `AdminSpecialCommandsPageController`, `SpecialCommandMembersPageController`, `SpecialCommandAdminProxyController`, `SquadronAdminProxyController`, `MeFrontendController` |
| hangar | 1 | 2 | 0 | 1,043 | 12 | 5 | 1 | `HangarPageController`, `HangarImportProxyController`, `HangarDeleteAllProxyController` |
| exchange | 3 | 3 | 0 | 1,003 | 21 | 19 | 0 | `AdminExchangeClientsPageController`, `AdminExchangeClientsRelayController`, `ConnectedAppsPageController`, `ConnectedAppsRelayController`, `ConnectedAppsConfirmController`, `ConnectedAppsConfirmRelayController` |
| audit | 1 | 1 | 0 | 832 | 3 | 2 | 0 | `AdminAuditLogPageController`, `AuditReportProxyController` |
| notification | 2 | 0 | 0 | 793 | 12 | 9 | 0 | `NotificationPageController`, `AdminNotificationRulePageController` |
| promotion | 1 | 1 | 0 | 790 | 24 | 7 | 0 | `PromotionPageController`, `PromotionProxyController` |
| materialexchange | 1 | 0 | 0 | 767 | 21 | 4 | 0 | `MaterialboersePageController` |
| personalinventory | 2 | 0 | 0 | 714 | 15 | 4 | 1 | `PersonalInventoryPageController`, `AdminPersonalInventoryPageController` |
| operation | 1 | 0 | 0 | 708 | 20 | 8 | 1 | `OperationPageController` |
| dashboard | 2 | 0 | 0 | 423 | 12 | 0 | 0 | `HomeController`, `AdminAnnouncementPageController` |
| settings | 1 | 0 | 0 | 395 | 13 | 2 | 0 | `AdminSettingsPageController` |
| leadership | 1 | 0 | 0 | 371 | 13 | 5 | 0 | `LeitungPageController` |
| orgchart | 1 | 0 | 0 | 191 | 5 | 7 | 0 | `OrgChartPageController` |
| shell | 5 | 4 | 0 | 851 | 0 | 0 | 0 | `CsrfTokenController`, `ClientErrorReportController`, `AppLinkController`, `AssetLinksController`, `WebAppManifestController`, `ImpressumController`, `PrivacyController`, `OssLicensesController`, `ScLinksPageController` |
| kernel (config, service) | — | — | — | — | 6 | 4 | 0 | — |
| **Total** | **70** | **26** | **7** | **33,630** | **624** | **292** | **30** | |

535 handlers (533 mapping annotations); 202 `@ResponseBody` handlers in `@Controller` classes
(ratchet 215 with `HttpEntity` returns, frontend `ArchitectureTest.java:192`); 13 handlers have
neither an own nor a class gate — 11 in the 8 `PUBLIC_BY_DESIGN` controllers plus `GET /org-chart`
and `GET /ship-data`, both still behind login (frontend `SecurityConfig.java:207-208`). The
authorization rule checks per class, not per handler, and no route snapshot exists.

### Cross-domain coupling

- 1,177 frontend-internal class edges, 173 of them cross-domain (80 DTO → DTO, mostly to reference
  types such as `SquadronReferenceDto`, `UserReferenceDto`, `MaterialReferenceDto`); **0
  cross-domain controller → controller edges**; 30 domain controllers read other domains' types
  (`MissionPageController` 7 foreign domains, `JobOrderPageController` 6,
  `RefineryOrderPageController` 6, `InventoryPageController` 5, `RefineryOrderWriteController` 4).
- Seven two-way domain pairs: joborder ⇄ inventory (5/5), mission ⇄ operation (5/3), mission ⇄
  refinery (2/5), mission ⇄ identity (7/2), mission ⇄ inventory (1/2), orgchart ⇄ leadership
  (2/1), catalogue ⇄ personalinventory (1/1).
- Top cross-domain edges: refinery → catalogue 15, joborder → catalogue 14, joborder → orgunit 9,
  mission → identity 7, inventory → catalogue 7, refinery → identity 6, hangar → catalogue 6,
  mission → orgunit 6, identity → orgunit 6, inventory → identity 5, inventory → joborder 5,
  joborder → inventory 5, mission → operation 5, refinery → mission 5, mission → catalogue 5,
  joborder → identity 5, refinery → orgunit 4, bank → orgunit 4, inventory → orgunit 3, mission →
  hangar 3.
- 11 kernel → domain edges: the layout advices and loader (`OrgUnitMembershipOptionDto`,
  `SquadronDto`), `OrgUnitContextAdvice` and `ActiveSquadronContextFilter` reading a constant from
  `MeFrontendController` (`:54`), the access gates (`UserDto`, `RegistrationStatusDto`,
  `TermsStatusDto`), `BackendApiClient` (`TermsDocumentDto`). The kernel `LiveSyncTopicClass` holds
  probe paths of 5 domains; `CachedCatalog` holds paths of 4.
- Layout model: 7 `@ControllerAdvice` — 5 scoped to `@UsesLayoutModel` (`AppVersionAdvice`,
  `CapabilityFlagsAdvice`, `LayoutMiscAdvice`, `OrgUnitContextAdvice`, `SafeCsrfAdvice`; 30
  `@ModelAttribute` methods), 2 global (`GlobalBindingAdvice`, `GlobalExceptionHandler`); one
  backend read per page, `GET /api/v1/me/layout` (`LayoutContextLoader.java:64`).

### The backend seam

`BackendApiClient` has 14 public methods (`BackendApiClient.java:91-377`): `get` in three shapes
(only one takes URI variables), `getCached` twice, `evict`, `evictAllCatalogues`,
`clearStaticDataCache`, `post`, `put`, `delete` twice, `patch`, `getTermsDocumentAnonymously`. It
has no multipart, bytes-with-headers, streaming, per-call header or timeout method.

| `WebClient` bean | Pool | OAuth2 filter | Relays | Resilience4j | Used by |
| --- | --- | --- | --- | --- | --- |
| `webClient` | `frontend-pool`, HTTP/2 | yes | correlation, org unit, locale, client IP | `backendApi` | `BackendApiClient` + 11 controllers |
| `termsDocumentClient` | `frontend-terms-pool` | no | correlation, locale, client IP | `backendApi` | `BackendApiClient` only (guarded by a test) |
| `sseWebClient` | `frontend-sse-pool`, HTTP/1.1 | no (bearer set by the caller) | all four | none | `NotificationPageController` |
| `liveSyncAuthWebClient` | `frontend-livesync-probe-pool` | no (bearer set by the caller) | correlation, locale, client IP | none | `LiveSyncSubscriptionAuthorizer` |

The eleven controllers that inject `webClient` directly and so bypass `BackendApiClient`'s error
mapping:

| Controller | Why it uses the `WebClient` |
| --- | --- |
| `AdminP4kImportPageController` | multipart upload, `bodyToFlux` job list, job read |
| `AdminPersonalBlueprintsPageController` | multipart import |
| `AuditReportProxyController` | report downloads, `DELETE` returning bytes |
| `BankReportProxyController` | PDF download with `X-User-Time-Zone` |
| `DataExportProxyController` | export download, extended response timeout, no retry |
| `HangarDeleteAllProxyController` | body-less `DELETE`, no visible reason |
| `HangarImportProxyController` | streamed multipart, 8 MiB cap; relays the deprecated fleetview import |
| `InventoryDeleteAllProxyController` | body-less `DELETE`, no visible reason |
| `JobOrderHandoverReportProxyController` | PDF downloads and preview; frontend route `/api/v1/orders` |
| `OrgUnitBankProxyController` | statement PDF with `X-User-Time-Zone` (one handler) |
| `PersonalBlueprintImportProxyController` | multipart import |

**Verified**: exactly these 11. Each maps a `WebClientResponseException` to
`ResponseStatusException(status, message)` (the RFC 7807 `code` is replaced) and any other
exception, `ClientAuthorizationException` included, to a 500; `GlobalExceptionHandler` would not
rescue an escaped `ClientAuthorizationException`. The calls show in `http_client_requests_seconds`,
not in `basetool_backend_client_errors_total` or its alert. The terms gate usually discovers a dead
token first, because it calls through `BackendApiClient` when its cached verdict is older than 60 s.
No ArchUnit rule restricts `WebClient` (the frontend has 7 rules).

### URI construction

624 `BackendApiClient` call sites (253 `get`, 134 `post`, 99 `put`, 84 `delete`, 23 `patch`, 30
`getCached`, 1 anonymous terms read).

| Style | Sites |
| --- | ---: |
| concatenation | 350 |
| plain literal | 148 |
| cached catalogue (`getCached`) | 30 |
| URI template with variables (`get` with three arguments; 16 of them with a concatenated prefix) | 26 |
| constant | 25 |
| helper call (`uri.toString()`, `…build().toUriString()`) | 16 |
| `UriComponentsBuilder` inline / through a local | 6 / 9 |
| local variable built by concatenation / by a helper | 6 / 6 |
| other (ternary) / no-argument anonymous terms read | 1 / 1 |

Built by concatenation: 356 (350 direct + 6 through a local); through URI templates or
`UriComponentsBuilder`: 41. Only `get` takes URI variables; `post`, `put`, `patch` and `delete`
take a finished string, so every write with a variable path is concatenated. Per domain (sites,
concatenated): catalogue 92 (65), mission 81 (66), joborder 61 (41), identity 54 (20), bank 42 (9),
blueprint 33 (14), inventory 33 (14), orgunit 26 (21), refinery 25 (11), promotion 24 (13),
exchange 21 (14), materialexchange 21 (11), operation 20 (15), personalinventory 15 (8), settings
13 (1), leadership 13 (12), dashboard 12 (3), notification 12 (8), hangar 12 (4), orgchart 5 (3),
audit 3 (0), kernel 6 (0). Operand types concatenated into URIs: 366 `UUID`, 23 number or boolean,
36 `String`, 9 unresolved, 13 other.

Four handlers in three controllers bind a browser-supplied id as `String` where the backend takes a
`UUID` (REQ-SEC-051). **Verified**: only the `HomeController` read-announcement handlers and the
`PromotionPageController` eligibility read can append query parameters or path segments, and none
can leave its path prefix (the backend firewall refuses `..` with 400);
`AdminDefaultBlueprintsPageController` and the `PersonalInventoryPageController` sort parameter
cannot, but double-encode their value (a correctness defect).

Untyped relays: 72 of 623 call sites decode the answer as raw `Map`, `Object` or `JsonNode`
(`MissionWriteController` 23, `MaterialboersePageController` 13, `LeitungPageController` 12,
`PromotionProxyController` 9); 78 handlers in 15 controllers accept `@RequestBody Map<…>` (53 are
typed). URL literals: 646 `/api/v1` literals in main (539 in the owning domain's classes, 71 in
other domains', 36 in the kernel); 368 test files, 194 of them mock `BackendApiClient` with 1,758
`when(`/`verify(` lines (665 `eq("/api/v1…")` matchers).

### Session-bound types

| Flash value type | `addFlashAttribute` calls |
| --- | ---: |
| `String` | 248 (+5 through `String` locals, +11 through `classifyError`/`failureToastKey`) |
| `RefineryOrderForm` | 12 (+1 through `toForm`) |
| `JobOrderItemForm` | 8 |
| `Boolean` | 5 |
| number or count expressions | 4 |
| `PersonalInventoryForm`, `ShipForm`, `InventoryForm`, `ParticipantForm` | 2 each |
| `ImportIssueDto` list or map | 2 |
| `BankWipeResetResultDto` | 1 |
| **Total** | **305** |

About 11 application types enter the session, about 20 with their nested members
(`RefineryGoodForm`, `RefineryOrderStatus`, the `JobOrderItemForm` line and material forms,
`InventoryForm.AllocationRow`, `PersonalInventoryLocationType`, `ImportIssueCode`,
`ImportIssueSeverity`, `ImportSuggestionDto`). The allow-list admits `org.springframework.security.`
and `…frontend.model.` — 325 application classes today. 29 flash sites store a form (28 directly,
1 through `toForm`); `FlashAttributeTypesTest` rejects `BindingResult`s. The other
`session.setAttribute` writes hold JDK types only.

### Templates

120 templates: 62 at the root (15,924 lines), `admin/` 22 (4,095, ten domains), `fragments/` 30
(3,458, shell and domain fragments mixed), `organisation/` 2 (450), `error/` 4 (146).

| Measure | Value |
| --- | --- |
| pages / fragments | 90 / 30 |
| `T(…frontend.support.Roles)` | 172 in 22 templates, all inside `sec:authorize`: ADMIN 91, LOGISTICIAN 32, OFFICER 29, MISSION_MANAGER 6, KRT_MEMBER 6, BANK_MANAGEMENT 6, BANK_EMPLOYEE 2; no other FQCN |
| SpEL bean references | `@moneyFormat` 70, `@handles` 44, `@markdown` 6 |
| fragment references | 486; shell: `modal-wrapper` 106, `head` 90, `sidebar` 83, `toast` 39, `scu-hint` 32, `components` 29, `pagination` 28 |
| view-name strings `"view :: fragment"` in Java | 105 |
| pages with the copied `<header>` block | 83, in 5 variants |
| external `<script>` tags: total / `defer` / sync / `type=module` / with nonce | 121 / 120 / 1 (`krt-client-error.js`) / 0 / 121 |
| inline `<script>` blocks: total / `th:inline="javascript"` / plain | 70 / 56 / 14; 1,860 lines in 60 templates, 142 `var` |
| inline top-level statements: data / logic | 320 / 53 (in 17 templates) |
| scripts per page after fragment resolution | 17 (`error.html`) to 26 (`inventory-my.html`) |
| `style=` / `on*=` / `<style>` / `javascript:` | 0 / 0 / 0 / 0 |
| `th:utext` (Markdown / bundle) | 30 (7 / 23) |
| `data-trigger` values distinct / uses | 291 / 528 |
| forms `POST` / `GET` / with `th:action` | 105 / 78 / 127 |
| `<button>` without `type` / `<img>` without `alt` | 37 / 0 |

Proposed domain folders for the root templates: mission 2, operation 2, joborder 6, inventory 6,
personalinventory 1, blueprint 2, hangar 2, materialexchange 1, refinery 3, bank 8, notification 1,
catalogue 5, promotion 5, orgchart 1, identity 6, exchange 2, settings 1, shell 8. Domain fragments:
bank 6, inventory 2, materialexchange 3, catalogue 3, orgchart 1, identity 2; the rest is shell.

### Assets by domain

*Core* scripts are loaded by `fragments/head.html` (16, two of them domain scripts marked †), the
sidebar fragment (`sidebar.js`, `unsaved-changes.js`; 83 pages), the toast fragment (`toast.js`; 39
pages), plus `autocomplete.js` (6 pages).

| Domain | Scripts |
| --- | --- |
| core (19) | krt-client-error (sync, first), escape-html, safe-url, event-delegation, common-handlers, krt-modal, krt-fetch, krt-live-sync, krt-user-search, krt-catalog-search, krt-searchable-select, datetime-splitter, scu-decimal-input, inline-style-apply, krt-filter-panel, sidebar, unsaved-changes, toast, autocomplete |
| mission (3) | mission-detail, missions, mission-presence |
| operation (3) | operation-detail, operations, operations-index |
| joborder (7) | orders-create, orders-detail, orders-index, orders-index-reorder, orders-material-demand, item-collection, material-collection |
| inventory (10) | inventory-admin, inventory-my, inventory-common, inventory-input, inventory-index, inventory-material, inventory-game-item, inventory-herkunft, inventory-note-modal, inventory-materialboerse |
| personalinventory (1) | personal-inventory |
| blueprint (7) | personal-inventory-blueprints, -import, -recipe, blueprint-overview, admin-blueprints, admin-default-blueprints, admin-personal-blueprints-purge |
| hangar (2) | hangar, hangar-squadron |
| materialexchange (3) | materialboerse, materialboerse-release, materialgesuch-modal |
| refinery (4) | refinery-orders-create, refinery-orders-details, refinery-orders-index, refinery-yield-badge |
| bank (2) | bank (9 pages), krt-bank-account-search † |
| notification (2) | notifications †, notification-rules |
| catalogue (12) | materials, material-detail, materials-matrix, materials-profit-calculation, locations, uex, admin-materials, admin-material-aliases, ship-data, mission-data (mixed), p4k-import, sync-reports |
| audit (1) | audit-log |
| promotion (5) | promotion-admin-rank-requirements, promotion-admin-topics, promotion-manage, promotion-my-evaluations, promotion-overview |
| orgchart / leadership (1 / 1) | org-chart / leitung |
| orgunit (4) | special-commands, special-command-detail, admin-org-structure, members |
| identity (7) | profile, terms-accept, admin-terms, discord-registrations, pending-approval, admin-deletion-requests, admin-person-search |
| dashboard (2) / admin (1) | index, announcement / admin-settings |
| exchange (3) | admin-exchange-clients, connected-apps, connected-apps-confirm |

Stylesheets: 8 shared (`styles.css`, `inline-migration.css`, `pages/toast.css`, five
`pages/error*.css`), 1 public (`pages/sc-links.css`), 55 domain sheets; every other `pages/*.css` is
linked by exactly one template (`TemplateCommentHygieneTest`). Templates: 16 shared, 7 public, 97
domain.

| Domain | JS files | JS lines | `@ts-check` files (lines) | CSS files | CSS lines | Templates | Template lines | Inline JS lines | `innerHTML` sinks | Raw `GET` `fetch` | Top-level globals |
| --- | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| core | 19 | 4,297 | 13 (71 %) | 0 | 0 | 0 | 0 | 0 | 2 | 2 | 3 |
| shared | 0 | 0 | – | 8 | 6,934 | 16 | 1,059 | 117 | 0 | 0 | 0 |
| public/legal | 0 | 0 | – | 1 | 72 | 7 | 482 | 0 | 0 | 0 | 0 |
| mission | 3 | 3,581 | 0 (0 %) | 1 | 898 | 2 | 1,758 | 112 | 0 | 2 | 14 |
| operation | 3 | 701 | 0 (0 %) | 2 | 482 | 2 | 588 | 33 | 0 | 1 | 11 |
| joborder | 7 | 4,319 | 1 (14 %) | 4 | 335 | 6 | 2,272 | 184 | 23 | 10 | 146 |
| inventory | 10 | 5,476 | 6 (74 %) | 6 | 266 | 8 | 2,784 | 199 | 4 | 10 | 142 |
| personalinventory | 1 | 494 | 0 (0 %) | 1 | 1,236 | 2 | 416 | 36 | 3 | 1 | 0 |
| blueprint | 7 | 3,091 | 4 (73 %) | 2 | 214 | 5 | 1,046 | 166 | 9 | 6 | 0 |
| hangar | 2 | 653 | 1 (16 %) | 2 | 211 | 2 | 458 | 18 | 1 | 0 | 1 |
| materialexchange | 3 | 2,212 | 0 (0 %) | 1 | 462 | 4 | 786 | 102 | 8 | 4 | 0 |
| refinery | 4 | 1,626 | 2 (55 %) | 2 | 236 | 3 | 833 | 63 | 2 | 3 | 54 |
| bank | 2 | 2,457 | 1 (2 %) | 1 | 1,423 | 15 | 3,612 | 224 | 2 | 3 | 0 |
| notification | 2 | 1,170 | 2 (100 %) | 1 | 38 | 2 | 254 | 0 | 3 | 5 | 0 |
| catalogue | 12 | 3,497 | 1 (15 %) | 11 | 1,066 | 15 | 2,222 | 118 | 15 | 4 | 51 |
| audit | 1 | 265 | 0 (0 %) | 0 | 0 | 1 | 227 | 0 | 0 | 1 | 0 |
| promotion | 5 | 1,528 | 2 (9 %) | 6 | 1,237 | 5 | 1,273 | 56 | 0 | 0 | 92 |
| orgchart | 1 | 661 | 0 (0 %) | 1 | 574 | 2 | 534 | 20 | 0 | 0 | 2 |
| leadership | 1 | 379 | 0 (0 %) | 1 | 91 | 1 | 301 | 15 | 1 | 0 | 0 |
| orgunit | 4 | 503 | 3 (62 %) | 4 | 129 | 7 | 1,250 | 328 | 0 | 0 | 3 |
| identity | 7 | 1,044 | 5 (43 %) | 4 | 128 | 9 | 1,010 | 39 | 0 | 1 | 0 |
| dashboard | 2 | 156 | 0 (0 %) | 1 | 13 | 2 | 165 | 13 | 0 | 0 | 2 |
| admin | 1 | 178 | 0 (0 %) | 1 | 24 | 1 | 216 | 17 | 0 | 0 | 1 |
| exchange | 3 | 1,289 | 3 (100 %) | 3 | 115 | 3 | 527 | 0 | 0 | 3 | 1 |
| **Total** | **100** | **39,577** | **44 (38 %)** | **64** | **16,184** | **120** | **24,073** | **1,860** | **73** | **56** | **523** |

Line counts use `wc -l` semantics; "`innerHTML` sinks" includes the one `parseFromString`.

### Cross-domain asset couplings

The complete list from the dependency graph:

| From | To | Mechanism |
| --- | --- | --- |
| inventory (`inventory-materialboerse.js`, `inventory-my.html`) | materialexchange | `window.krtMaterialRelease`; `materialboerse.css`; `fragments/materialboerse-modal` |
| audit (`admin/audit-log.html:6`) | bank | links `bank.css`, uses 9 of its 14 `bank-*` classes |
| catalogue (`admin/materials.html:6`) | promotion | links `promotion-admin.css` for `.form-row`, `.form-input`, `.form-hint` |
| blueprint (`admin/default-blueprints`, `admin/personal-blueprints`, `personal-inventory-blueprints`) | personalinventory | link `personal-inventory.css` |
| core (`common-handlers.js:157-167`) | catalogue | calls `window.filterTable`, defined in three catalogue scripts |
| core head (`fragments/head.html:144`) | bank | loads `krt-bank-account-search.js` on every page |
| catalogue page `admin/mission-data` | orgunit, catalogue | squadrons, job types and frequency types in one page and script |

- The coupling mechanism is the shared global scope: 50 non-IIFE scripts declare 523 top-level
  names (443 function declarations); 23 names are declared in 2–3 files, none loaded by the same
  template; 36 files import 276 names through `/* global */`; 34 files read 213 distinct Thymeleaf
  bootstrap constants; 185 `typeof window.X` checks and 133 `window.krtFetch` presence guards
  compensate for undeclared dependencies. 57 `Window` APIs are written by scripts, 56 of them
  declared in `globals.d.ts`.
- Most used shared APIs: `krtEvents.on` 272 references, `krtFetch` 174 (+111 `.write`, 83 `.swap`,
  15 `.submitForm`), `krtI18nText` 171, `krtModal.close`/`.open` 72/58, `krtLiveSync.sendChanged`
  54, `krtLiveSync.createReceiver` 51.
- Loaded on every page: 20 scripts, 175,854 bytes raw and 45,791 bytes gzip; `styles.css` 129,597
  bytes and `inline-migration.css` 34,977 bytes before minification.
- Duplicated non-trivial lines: JavaScript 7.7 % (window 6) and 12.3 % (window 4) of 26,369; CSS
  15.5 % of 12,003; templates 20.0 % of 18,553.

## Modern language inventory

### Java idioms

Old form against modern form per module and source set.

| Idiom | backend main | frontend main | ingest main | keycloak-spi main | logging-support main | test-support main | tests + e2e |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| records declared | 663 | 357 | 26 | 2 | 0 | 1 | 29 |
| switch: arrow form / colon form | 63 / 0 | 18 / 0 | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 | 8 / 0 |
| `instanceof` type pattern | 58 | 79 | 26 | 1 | 0 | 0 | 23 |
| `instanceof` + cast (old) | 3 | 1 | 0 | 0 | 0 | 0 | 1 |
| `instanceof` without binding (legitimate) | 10 | 25 | 5 | 0 | 0 | 0 | 7 |
| if/else `instanceof` chain (≥ 2 arms) | 3 | 1 | 0 | 0 | 0 | 0 | 1 |
| if/else enum `==` chain (≥ 2 arms) | 8 | 0 | 0 | 0 | 0 | 0 | 1 |
| unnamed `_` used | 2 | 0 | 0 | 0 | 0 | 0 | 0 |
| unused lambda parameters | 67 | 33 | 7 | 0 | 0 | 0 | 280 |
| unused catch parameters (non-empty body) | 55 | 58 | 15 | 5 | 0 | 0 | 26 |
| empty catch blocks | 2 | 18 | 0 | 0 | 0 | 0 | 23 |
| text blocks | 234 | 1 | 4 | 0 | 0 | 0 | 180 |
| multi-line literal concatenation (≥ 3 literals) | 143 | 11 | 4 | 2 | 0 | 0 | 223 |
| `@Query` with `+` concatenation | 47 | 0 | 0 | 0 | 0 | 0 | 0 |
| `@Query` as a text block | 180 | 0 | 0 | 0 | 0 | 0 | 0 |
| `String.format(` | 2 | 3 | 0 | 0 | 0 | 0 | 38 |
| `.formatted(` | 5 | 0 | 0 | 0 | 0 | 0 | 78 |
| `.trim()` | 105 | 32 | 2 | 4 | 0 | 1 | 29 |
| `.strip*()` | 8 | 6 | 3 | 0 | 0 | 2 | 13 |
| `.isBlank()` | 167 | 140 | 21 | 8 | 1 | 1 | 19 |
| `var` declarations | 37 | 19 | 2 | 0 | 0 | 0 | 124 |
| explicitly typed local declarations (approx.) | 4,629 | 1,723 | 332 | 60 | 7 | 30 | 21,233 |
| `.get(0)` | 0 | 5 | 4 | 0 | 0 | 0 | 521 |
| `getFirst`/`getLast`/`removeFirst`/… | 34 | 6 | 0 | 0 | 0 | 0 | 231 |
| `.iterator().next()` | 5 | 0 | 0 | 0 | 0 | 0 | 50 |
| `Stream.toList()` | 260 | 44 | 4 | 1 | 0 | 1 | 173 |
| `Collectors.toList()` | 2 | 0 | 0 | 0 | 0 | 0 | 0 |
| `Collectors.toSet()` | 21 | 4 | 0 | 0 | 0 | 0 | 23 |
| `List.of(` | 204 | 207 | 23 | 2 | 0 | 6 | 4,445 |
| `Arrays.asList(` | 1 | 2 | 2 | 0 | 0 | 0 | 6 |
| `Collections.empty*(` | 20 | 26 | 0 | 0 | 0 | 0 | 432 |
| `Collections.unmodifiable*(` (views) | 17 | 4 | 0 | 0 | 0 | 0 | 1 |
| `Optional` `isPresent()` then `get()` | 11 | 0 | 2 | 0 | 0 | 0 | 0 |
| `Optional.orElseThrow()` | 14 | 0 | 0 | 0 | 0 | 0 | 312 |
| `Optional` parameter | 4 | 0 | 0 | 0 | 0 | 0 | 0 |
| `mapMulti` | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| `flatMap(… Stream.of/empty …)` | 1 | 0 | 0 | 0 | 0 | 0 | 1 |
| `Math.clamp` | 1 | 0 | 1 | 0 | 0 | 0 | 0 |
| `Math.max(Math.min(…))` clamp candidates | 10 | 3 | 1 | 1 | 0 | 0 | 0 |
| `HexFormat` | 4 | 2 | 3 | 0 | 0 | 2 | 5 |
| `Integer`/`Long.toHexString` | 1 | 5 | 1 | 0 | 0 | 0 | 0 |
| `new ThreadLocal` / `withInitial` | 1 | 4 | 0 | 0 | 0 | 0 | 1 |
| `synchronized` block | 2 | 2 | 0 | 0 | 0 | 0 | 1 |
| `synchronized` method | 1 | 0 | 1 | 0 | 0 | 0 | 2 |
| `Thread.sleep` | 2 | 0 | 0 | 1 | 0 | 0 | 26 |
| `Executors.*(` | 2 | 2 | 0 | 0 | 0 | 0 | 17 |
| `serialVersionUID` | 1 | 1 | 5 | 0 | 0 | 0 | 2 |
| `@Serial` | 0 | 0 | 4 | 0 | 0 | 0 | 0 |
| `ReflectionTestUtils.setField` | 0 | 0 | 0 | 0 | 0 | 0 | 36 |
| hand-written `equals(Object)` | 4 | 1 | 0 | 0 | 0 | 0 | 0 |
| Javadoc blocks `/** */` | 8,187 | 2,769 | 757 | 102 | 13 | 46 | 5,080 |
| Markdown doc lines `///` | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| JetBrains-annotated files | 594 | 248 | 59 | 15 | 2 | 5 | 74 |
| sealed type declarations | 4 | 0 | 0 | 0 | 0 | 0 | 0 |
| explicit `super(args)` as the first constructor statement | 27 | 4 | 9 | 2 | 0 | 0 | 9 |

Totals for main: 1,049 records; 164 type patterns against 4 `instanceof` + cast; 310
`Stream.toList()` against 2 `Collectors.toList()` (both downstream collectors); 107 unused lambda
parameters, 133 unused catch parameters, 20 empty catches; 42 explicit `super(args)`; 0 legacy date
or collection APIs. All 89 switches (81 in main, 8 in tests) use the arrow form. 0 prose comments
in any source set (the lexer and `grep -E '^\s*//'` agree); licence headers in 3,295 of 3,295
files. Further counts (all source sets unless stated): `Set.of` 1,445;
`Map.of`/`ofEntries`/`entry` 666; `List`/`Set`/`Map.copyOf` 98; `EnumSet.*` 51;
`Optional.orElse(null)` 148; `yield` 22 (main); `case null` 0; `when` guards 0; record patterns 0;
`volatile` 14. Type kinds in backend main: 634 classes, 87 enums, 190 interfaces, 663 records.
Lombok on backend entities: `@Getter` 113, `@Setter` 113, `@NoArgsConstructor` 114,
`@AllArgsConstructor` 88, `@ToString` 86, `@Builder` 42; `@Data` on non-entity classes: backend 3,
frontend 18 forms, 1 row and 1 properties class, ingest 1 properties class; Lombok `@Value` 0;
`@Builder` on records 16.

Tenancy decisions on `OrgUnitKind` are written as `==`/`!=` comparisons 43 times in 19 files
(42 lines). Five type patterns on the lazily proxied `OrgUnit` hierarchy skip `Hibernate.unproxy`;
**Verified**: only `OrgUnitMembershipService.java:340` can receive a proxy on current call paths
(data-dependent; the effect is display-only).

### Switches

81 switches in main, joined with bytecode.

| Kind | Expression, no `default` | Expression, `default` | Statement, `default` |
| --- | ---: | ---: | ---: |
| enum (56; 55 typed by `$SwitchMap$`, 1 on `this` inside `OperationStatus`) | 35 | 7 (4 × library `HttpStatus`; 3 partial) | 14 (9 cover every constant, so the `default` is dead; 5 partial) |
| `String` (16, 13 in the frontend: request parameters such as fragment names; untrusted input needs the `default`) | 0 | 11 | 5 |
| pattern (2: `SseSendFailureCause.java:49` with `_`; `ExchangeShipWriteService.java:438` over the sealed `Planned`) | 1 | 1 | 0 |
| constant, non-enum (4: `BLUEPRINTS`/`STOCK` in `ExchangeMassChangeService.java:224/279/316`; `ExchangeShipChangeSet.LINK`/`UPSERT` at `ExchangeShipWriteService.java:218`) | 0 | 4 | 0 |
| `int` (3, frontend) | 0 | 2 | 1 |

All 20 switch statements carry a `default` because Checkstyle's `MissingSwitchDefault`
(`config/checkstyle/google_checks.xml:172`, `maxWarnings = 0`) forces it. Enum types switched on in
main: `BankAccountType` 6, `OrgUnitKind` 6, `BankAccountViewGranteeKind` 5, `HttpStatus` 4,
`InventoryAllocationDimension` 3, `BereichLeadershipRole` 3, `ExchangeResource` 3, `AuditDomain` 2,
`BankBookingRequestType` 2, `BankTransactionType` 2, `BlueprintImportStatus` 2, `SelectorKind` 2,
`MembershipRole` 2, `ExchangeResolveResponse.Status` 2, and one each of eleven more. Bytecode shows
36 compiler-inserted `MatchException`s (exhaustive switches without `default`) and 2
`SwitchBootstraps.typeSwitch` sites. Security-relevant exhaustive switches already in place:
`LiveSyncSubscriptionAuthorizer.java:90`, `OrgRoleManagementSecurityService.java:167`,
`OrgUnitBankAccessService.java:1047…1481` (9), `RecipientResolutionService.java:73`,
`AuditService.java:188`, `AuditReportService.java:176`.

### Tool probes

javac 25 (Zulu 25+36), Checkstyle 14.3.0 with the repository configuration, google-java-format
1.36.1.

| Probe | javac `--release 25` | javac `--release 21` | Checkstyle 14.3.0 | google-java-format |
| --- | --- | --- | --- | --- |
| `_` in lambda, for-each, catch, try resource, record pattern | compiles | "unnamed variables are not supported in -source 21" | only `EmptyCatchBlock` on an empty `catch (… _) {}` | unchanged |
| enum statement without `default`; `case null` statement without `default`; expression without `default` | compiles | compiles | `MissingSwitchDefault` only on the plain statement | unchanged |
| the same with one constant missing | the plain statement compiles; the `case null` statement and the expression fail: "does not cover all possible input values" | same | — | — |
| `///` Markdown doc on a public class and method | compiles | compiles | `MissingJavadocType`, `MissingJavadocMethod` (not treated as Javadoc) | unchanged |
| statements before `super(…)` | compiles | "flexible constructors is not supported in -source 21" | clean | unchanged |
| `Gatherers`, `java.lang.classfile`, KDF | compiles | cannot find symbol / package does not exist | — | — |
| `ScopedValue`, `Arena` | compiles | "is a preview API and is disabled by default" | — | — |
| Java 21 features (sequenced collections, `clamp`, `StringBuilder.repeat`, record patterns, guards) | compiles | compiles | clean | unchanged |
| `import module java.base;` | compiles | "module imports are not supported in -source 21" | `AvoidModuleImport` | unchanged |
| sealed interface permitting a class in another package | "class … in unnamed module cannot extend a sealed class in a different package" | same | — | — |
| control: empty `catch (… ignored) {}` | compiles | compiles | clean | unchanged |

**Verified** for the sealed probe: javac 25 reports
`compiler.err.class.in.unnamed.module.cant.extend.sealed.in.diff.package` for a class and for an
interface; the same two packages compile inside a named module; `AppException` permits 13 `public
final` subclasses, six of them domain classes, and no code switches over them or reads
`getPermittedSubclasses`. A subclass compiled against an unsealed base fails to load against the
sealed base with `IncompatibleClassChangeError`. A pinning probe (`parallelism=1`,
`maxPoolSize=1`) finished 8 virtual threads × 200 ms inside monitors in 205 ms.

### `toString` exposure

| Kind | Credential-named, printed | Credential-named, redacted | Personal-named, printed |
| --- | --- | --- | --- |
| records | 1 real (keycloak-spi `DiscordGuildRoleGateAuthenticator.Brokered`: access token, username, e-mail) + 6 false positives (`refillTokens`, `ipRefillTokens`) | 5 (`DiscordSpiPrecheckProperties`, `KeycloakSyncProperties`, backend `MonitoringScrapeProperties`, ingest `ServiceAccountProperties`, `CachedToken`) + `MemberLookup` (body) | 112 (DTOs, events, projections with handles, user names, display names, e-mail) |
| Lombok `@Data`/`@ToString` classes | 2 (frontend and ingest `MonitoringScrapeProperties`, `password`) | — | 21 (entities, incl. `User`: username, display name, e-mail, Discord user id; `rsiHandle` excluded) |

`@ConfigurationProperties` types: records in the backend 27 of 27, frontend 7 of 8, ingest 7 of 8.
`PiiMasker` masks JWTs, e-mail addresses and values after `bearer`, `token`, `session-id` or
`authorization`, not `password=`, `secret=` or user names. **Verified**: nothing prints these
objects today, and actuator `configprops` is neither exposed nor unmasked; the keyword pattern has
no word boundary, so `accessToken=` is masked; keycloak-spi does not use `logging-support` at all,
so a `Brokered` in a Keycloak log would be unmasked.

Event records carry personal data too. **Verified**: nine records of the central `event` package do
— `UserApprovalDecidedEvent` (e-mail, name, reason), `AccountDeletionRequestedEvent` (handle),
`DiscordRegistrationPendingEvent` (username), `JobOrderCreatedEvent` and
`JobOrderUpdatedByRequesterEvent` (handle), `BankBookingRequestCreatedEvent` (requester handle),
`MaterialExchangeInterestRegisteredEvent` (interested user name),
`MaterialRequestFulfillmentSignalledEvent` (fulfiller name), `BankBookingRequestRejectedEvent`
(free-text reason). The `NotificationEvent` ones are persisted in `notification.params`, one row per
recipient; none is logged.

### JavaScript

AST counts over 100 files, 39,577 lines.

| Feature | Uses | Files | Candidates for the modern form |
| --- | --- | --- | --- |
| `var` / `let` / `const` declarations | 0 / 502 / 4,462 | 0 / 68 / 98 | — (inline templates: 142 `var`) |
| arrow functions | 305 | 29 | 1,123 function-expression callbacks without `this`/`arguments` (93 files) |
| function expressions / declarations | 1,564 / 1,443 | 99 / 91 | 443 declarations at top level |
| classes, private `#x`, static blocks, class fields | 0 | 0 | — |
| optional chaining `?.` | 14 | 3 | 245 `a && a.b` (53 files) |
| nullish `??` | 43 | 7 | 34 null-check ternaries (16 files) |
| `??=` `\|\|=` `&&=` | 0 | 0 | — |
| `Array.prototype.at` | 0 | 0 | 5 `a[a.length - 1]` |
| `Object.hasOwn` | 0 | 0 | 1 `hasOwnProperty` |
| `structuredClone` | 0 | 0 | 0 JSON round-trip clones |
| `toSorted` / `toReversed` / `toSpliced` / `with` | 0 | 0 | 3 copy-then-sort |
| `Object.groupBy`, `Map.groupBy`, `Promise.withResolvers` | 0 | 0 | 1 `new Promise` |
| Set methods, iterator helpers | 0 | 0 | 9 `new Set`, 6 `new Map` |
| `AbortController` / `AbortSignal.timeout`, `any` | 1 / 0 | 1 / 0 | typeahead reads |
| `fetch` / XHR | 61 / 0 | 34 / 0 | 56 raw `GET`s outside the core transport |
| `EventSource` / `WebSocket` | 1 / 1 | 1 / 1 | — |
| template literals (interpolated) | 64 (60) | 9 | 664 string concatenations with literals (82 files) |
| `async` functions / `await` / `.then()` | 65 / 87 / 190 | 17 / 17 / 45 | — |
| `for…of` / index loops over `.length` | 17 / 52 | 11 / 21 | — |
| destructuring object / array, spread | 0 / 14, 4 | – | — |
| `includes` / `indexOf` comparisons | 24 / 71 | 10 / 32 | — |
| ES modules, `type="module"`, import maps, dynamic `import()` | 0 | 0 | — |
| `'use strict'` | 42 files (function level) | – | 58 sloppy-mode files |
| `innerHTML` / `outerHTML` / `insertAdjacentHTML` / `document.write` | 72 / 0 / 0 / 0 | 23 | 23 clears, 6 static, 22 escaped builders, 21 local markup variables |
| `setTrustedHtml` / `replaceWithTrustedHtml` calls | 6 / 3 | 4 / 3 | — |
| `textContent` / `innerText` writes | 318 / 39 | 64 / 8 | — |
| `element.style.*` writes (of them `display`) | 188 (99) | 27 (display) | — |
| `replaceChildren` / `MutationObserver` / `ResizeObserver` / `IntersectionObserver` | 10 / 2 / 1 / 0 | – | 23 `innerHTML = ''` |
| `parseInt` without radix / global `isNaN` / `keyCode`, `which` | 52 / 57 / 3 | – / 23 / – | — |
| `window.onclick =` | 3 | 3 | — |
| regex literals (named groups, `v` flag) | 92 (0, 0) | 29 | — |

No `eval`, `Function`, string timers or script-URL sinks.

- `frontend/eslint.config.mjs`: `js.configs.recommended` plus `no-var`, `prefer-const`,
  `object-shorthand`, `eqeqeq: smart`, `no-undef`, `no-empty` (`allowEmptyCatch`),
  `no-unused-vars` (warn), `no-restricted-syntax` (raw fetch writes), `no-unsanitized`;
  `ecmaVersion: 2023`, `sourceType: script`.
- `frontend/tsconfig.json`: `allowJs`, `checkJs: false`, `noEmit`, `strict`,
  `noImplicitAny: false`, `target`/`lib` ES2023 + DOM. `@ts-check` covers 44 of 100 files and
  37.8 % of the lines; the three largest scripts (`mission-detail.js` 3,249, `bank.js` 2,400,
  `orders-detail.js` 2,325 lines — 20 % of all JavaScript) are unchecked.
- Tool versions (`frontend/package-lock.json`): TypeScript 7.0.2, ESLint 10.11.0,
  eslint-plugin-no-unsanitized 4.1.5, Stylelint 17.15.0, Prettier 3.9.9, HTMLHint 1.9.2; Node
  24.21.0 (`gradle/libs.versions.toml:47`).

### CSS

postcss over 64 files.

| Measure | Value |
| --- | --- |
| files / lines / bytes | 64 / 16,184 / 402,263 |
| root sheets / page sheets (lines) | 10 (10,976) / 54 (5,208) |
| `@layer` order statement / unlayered rules | 64 of 64 / 0 |
| rules per layer | page 1,566 · components 778 · migration 249 · utilities 2 · base 2 |
| `!important` | 21 (`styles.css` 19, the inventory admin and my page sheets 1 each) |
| custom properties defined / `var()` uses / hex literals | 130 distinct (146 definitions) / 2,567 / 127 |
| `rgb()` / legacy `rgba()` | 107 / 48 (all in `pages/`) |
| `@media` / range syntax / `prefers-reduced-motion` / `prefers-color-scheme` / `forced-colors` | 74 / 73 / 1 / 0 / 0 |
| `:has` / `:where` / `:is` / `:focus-visible` / `:focus` / `outline: none\|0` | 29 / 18 / 1 / 34 / 38 / 18 |
| nesting / `@container` / `@scope` / `@supports` / `@property` / `@starting-style` | 0 / 0 / 0 / 1 / 0 / 0 |
| `color-mix` / `clamp` / `min`, `max` / `calc` / `oklch` / `light-dark` | 0 / 0 / 9 / 27 / 0 / 0 |
| logical properties / physical margin, padding, offset and border sides / width-height family | 10 / 862 / 749 |
| `display: flex` / `grid` / `inline-flex` | 381 / 42 / 82 |
| z-index declarations (distinct values) | 45 (32) |
| vendor-prefixed properties | 14 in 3 files |
| undefined tokens used | `--color-black` × 2 (`styles.css:2999,3006`), `--color-text` × 1 (`bank.css:1240`); `--krt-footer-height` and `--hint-shift` are set by scripts |
| unused tokens / `krtm-*` classes defined, unused | 6 / 251, 8 |

The 54 page stylesheets run under a two-rule Stylelint set, not the standard configuration.

### Browser baseline

From web-features 3.40.0 (npm, published 2026-09-24). Features the app already ships imply a floor
of **Chrome/Edge 105, Firefox 121, Safari/iOS Safari 16.4** (`:has()` for Chromium and Firefox, the
range syntax for Safari).

| Feature | Baseline | Chrome/Edge | Firefox | Safari |
| --- | --- | --- | --- | --- |
| CSS `@layer` | widely (2024-09-14) | 99 | 97 | 15.4 |
| `:has()` | widely (2026-06-19) | 105 | 121 | 15.4 |
| media-query range syntax | widely (2025-09-27) | 104 | 102 | 16.4 |
| `<dialog>`, `showModal()` | widely (2024-09-14) | 37 (Edge 79) | 98 | 15.4 |
| `:focus-visible` | widely (2024-09-14) | 86 | 85 | 15.4 |
| `inert` | widely (2025-10-11) | 102 | 112 | 15.5 |
| `Element.replaceChildren` | widely (2023-04-20) | 86 | 78 | 14 |
| `AbortController` | widely (2021-09-25) | 66 (Edge 16) | 57 | 12.1 |

| Candidate | Baseline | Newly available | Widely available | Chrome | Firefox | Safari |
| --- | --- | --- | --- | --- | --- | --- |
| `Array.prototype.at` | widely | 2022-03-14 | 2024-09-14 | 92 | 90 | 15.4 |
| `Object.hasOwn` | widely | 2022-03-14 | 2024-09-14 | 93 | 92 | 15.4 |
| `structuredClone` | widely | 2022-03-14 | 2024-09-14 | 98 | 94 | 15.4 |
| `toSorted`, `toReversed`, `toSpliced`, `with` | widely | 2023-07-04 | 2026-01-04 | 110 | 115 | 16 |
| `Object.groupBy`, `Map.groupBy` | widely | 2024-03-05 | 2026-09-05 | 117 | 119 | 17.4 |
| `Promise.withResolvers` | widely | 2024-03-05 | 2026-09-05 | 119 | 121 | 17.4 |
| `Array.fromAsync` | widely | 2024-01-25 | 2026-07-25 | — | — | — |
| Set methods | newly | 2024-06-11 | projected 2026-12-11 | 122 | 127 | 17 |
| iterator helpers | newly | 2025-03-31 | projected 2027-09-30 | 122 | 131 | 18.4 |
| `AbortSignal.timeout()` | newly | 2024-04-18 | — | 124 | 100 | 16 |
| `AbortSignal.any()` | widely | 2024-03-19 | 2026-09-19 | 116 | 124 | 17.4 |
| RegExp `v` flag | widely | 2023-09-18 | 2026-03-18 | 112 | 116 | 17 |
| CSS nesting | widely | 2023-12-11 | 2026-06-11 | 120 | 117 | 17.2 |
| container size queries | widely | 2023-02-14 | 2025-08-14 | 105 | 110 | 16 |
| container style queries | newly | 2026-05-19 | — | 111 | 151 | 18 |
| `@scope` | newly | 2026-03-24 | — | 143 | 146 | 26.4 |
| `color-mix()` | widely | 2023-05-09 | 2025-11-09 | 111 | 113 | 16.2 |
| `@starting-style` | newly | 2024-08-06 | — | 117 | 129 | 17.5 |
| same-document view transitions | newly | 2025-10-14 | — | 111 | 144 | 18 |
| cross-document view transitions | not Baseline | — | — | 126 | — | 18.2 |
| popover | newly | 2025-01-27 | projected 2027-07-27 | 116 | 125 | 17 (iOS 18.3) |
| `<dialog closedby>` | not Baseline | — | — | 134 | 141 | — |
| `dvh`, `svh`, `lvh` | widely | — | 2025-06-05 | 108 | 101 | 15.4 |
| Trusted Types | newly | 2026-02-24 | about 2028-08 (projected) | 83 | 148 | 26 |
| import maps | widely | — | 2025-09-27 | 89 | 108 | 16.4 |

"Projected" adds 30 months to the newly-available date, matching the dataset's own pairs. Under
Trusted Types enforcement `innerHTML = ''` throws (no empty-string exemption;
`trustedTypes.emptyHTML` exists for it), and report-only violations do fire the in-page
`securitypolicyviolation` event.

### ADR-0223 against the sources

| ADR-0223 statement | Finding |
| --- | --- |
| Java 25 brought eight developer-facing JEPs, four final | statuses right, but JEP 510 (Key Derivation Function API, final) and JEP 470 (PEM Encodings, preview) are missing |
| "JDK 26 and 27 add no final language or library feature" | language: right; library: wrong — JEP 517 adds `HttpClient.Version.HTTP_3`, JEP 504 removes the Applet API, JEP 500 is a core-libs reflection change |
| Final JEPs 500, 504, 516, 517, 522 in 26 and 523, 527, 534, 536 in 27 | right and complete |
| Tooling passes Checkstyle 14.1.0 | the pin is 14.3.0 (bumped on 2026-09-27) |
| 34 explicit `super(args)` calls | 42 in main |
| The module-import and compact-source bans are not gated | Checkstyle enforces `AvoidModuleImport` and `CompactSourceFileNotAllowed` in `main` since `49a3dcd4e` (not in `test`/`e2e`); no `--enable-preview` gate exists |
| The frontend holds the only `ThreadLocal`s | a fifth `ThreadLocal`, backend `ChangeSource.ON_BEHALF`, was added the same day and fits `ScopedValue` |

## Ingest, keycloak-spi and build

### Modules

| Module | Main classes | Main lines | Test files | Build script lines | Plugins in the module script | JaCoCo floor instruction / branch |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| backend | 1,389 | 174,597 | 674 | 136 | java, checkstyle, jacoco, idea, boot, dependency management, cyclonedx, licensee, spotbugs-base, pitest, spotless | 0.82 / 0.65 |
| frontend | 554 | 66,858 | 368 | 684 | as backend minus idea, plus node and openapi-generator | 0.60 / 0.46 |
| ingest | 75 | 12,553 | 78 | 78 | as backend minus idea | 0.93 / 0.85 |
| keycloak-spi | 16 | about 2,000 | 9 | 39 | java, checkstyle, jacoco, cyclonedx, licensee, spotbugs-base, spotless; `release 21` | 0.66 / 0.60 |
| logging-support | 4 | 275 | 4 | 25 | java-library, checkstyle, jacoco, dependency management, spotbugs-base, spotless | default 0.50 / 0.40 |
| test-support | 6 | 886 | 3 | 35 | java-library, checkstyle, spotless, dependency management | none |

### Ingest packages

| Package | Classes | Lines |
| --- | ---: | ---: |
| `exchange` | 26 | 5,416 |
| `config` | 21 | 2,295 |
| `web` | 8 | 2,122 |
| `filter` | 8 | 1,060 |
| `service` | 3 | 674 |
| `metrics` | 3 | 527 |
| `logging` | 2 | 254 |
| `ratelimit` | 1 | 84 |
| `model.dto` | 2 | 68 |
| (root) | 1 | 53 |

- Nine two-way package pairs (class pairs each way): config ⇄ exchange 13/11, config ⇄ filter 1/8,
  config ⇄ logging 1/4, config ⇄ metrics 1/1, config ⇄ web 2/8, exchange ⇄ filter 2/1, exchange ⇄
  web 4/17, filter ⇄ web 4/2, service ⇄ web 1/3.
- `exchange` mixes six concerns: DPoP and token gate (8 classes), registry gate (8, incl. the
  mirror-age gauge), limits and budget (3), idempotency (2), relay and request context (2), contract
  (1), plus observability (2). Relay concerns also sit in `config` (`RestClientConfig`,
  `ResponseSizeLimitInterceptor`) and `service` (`ServiceAccountTokenProvider`).
- Largest classes: `ExchangeController` 1,076 lines (11 collaborators, `:140-150`), `ExchangeRelay`
  596, `ExchangeBudget` 561, `DpopProofReplayStore` 478, `ExchangeIdempotencyFilter` 429.
- Redis users: `ExchangeBudget`, `ExchangeIdempotency`, `ExchangeQuotas`,
  `ExchangeRegistryReader`, `ExchangeRevocationReader`, `HandoffStagingService`. `RestClient`
  users: `ResponseSizeLimitInterceptor`, `RestClientConfig`, `ExchangeRelay`,
  `ServiceAccountTokenProvider`.
- The ingest `ArchitectureTest` has 4 rules (no JPA, `@PreAuthorize` on controllers, `GET`, `POST`)
  and no cycle rule. The order of the six exchange filters inside the Spring Security chain
  (ingest `SecurityConfig.java:271-310`) is untested; `FilterOrderTest` pins only the five servlet
  filters ahead of it. PIT targets 3 ingest classes and ingest is not in the PIT matrix.

### Exchange relay routes

The ingest relay calls exactly 14 backend operations on 13 paths (ingest
`ExchangeController.java:134`, `BACKEND = "/api/v1/exchange"`). All 14 re-check the capability
with `@exchangeGate`.

| # | Public route (frozen) | Backend operation | Request DTO | Response DTO | Capability | Body reaches the client |
| ---: | --- | --- | --- | --- | --- | --- |
| 1 | `GET /exchange/v1` | `GET /api/v1/exchange/me/installation` | – | `ExchangeInstallationDto` | `exchange.connect` | only `installationId` (the gateway builds the service document) |
| 2 | `POST /exchange/v1/me/installation` | `POST /api/v1/exchange/me/installation` | `ExchangeInstallationLabelRequest` | `ExchangeInstallationDto` | `exchange.connect` | yes |
| 3 | `POST /exchange/v1/me/account-check` | `POST /api/v1/exchange/me/account-check` | `ExchangeAccountCheckRequest` | `ExchangeAccountCheckDto` | `exchange.connect` | yes |
| 4 | `POST /exchange/v1/catalog/resolve` | `POST /api/v1/exchange/catalog/resolve` | `ExchangeResolveRequest` | `ExchangeResolveResponse` | `allowsAny` | yes (+ warnings) |
| 5 | `GET /exchange/v1/catalog/locations` | `GET /api/v1/exchange/catalog/locations` | – | `ExchangeLocationListDto` | `allowsAny` | yes |
| 6 | `GET /exchange/v1/me/blueprints?cursor&limit` | `GET /api/v1/exchange/me/blueprints?cursor&limit` | – | `ExchangeBlueprintPageDto` | `exchange.blueprints.read` | yes |
| 7 | `POST /exchange/v1/me/blueprints/changes` | `POST /api/v1/exchange/me/blueprints/changes` | `ExchangeBlueprintChangeSet` | `ExchangeChangeResultDto` | `exchange.blueprints.write` | yes (+ warnings) |
| 8 | `GET /exchange/v1/me/stock` | `GET /api/v1/exchange/me/stock` | – | `ExchangeStockPageDto` | `exchange.stock.read` | yes |
| 9 | `POST /exchange/v1/me/stock/changes` | `POST /api/v1/exchange/me/stock/changes` | `ExchangeStockChangeSet` | `ExchangeChangeResultDto` | `exchange.stock.write` | yes |
| 10 | `GET /exchange/v1/me/ships` | `GET /api/v1/exchange/me/ships` | – | `ExchangeShipPageDto` | `exchange.hangar.read` | yes |
| 11 | `POST /exchange/v1/me/ships/changes` | `POST /api/v1/exchange/me/ships/changes` | `ExchangeShipChangeSet` | `ExchangeChangeResultDto` | `exchange.hangar.write` | yes |
| 12 | `GET /exchange/v1/me/org-demand` | `GET /api/v1/exchange/me/org-demand` | – | `ExchangeOrgDemandDto` | `exchange.demand.read` | yes |
| 13 | `POST /exchange/v1/me/drafts/blueprints` | `POST /api/v1/exchange/me/drafts/blueprints` | `ExchangeBlueprintDraftDto` | **`BlueprintImportPreviewDto`** (web DTO) | `exchange.drafts.blueprints` | no — staged in Redis for the frontend |
| 14 | `POST /exchange/v1/me/drafts/refinery-orders` | `POST /api/v1/exchange/me/drafts/refinery-orders` | **`RefineryExtractDto`** (web DTO) | **`RefineryImportDraftDto`** (web DTO) | `exchange.drafts.refinery` | no — staged in Redis |

- `GET /exchange/v1/openapi.json` and `GET /exchange/v1/schemas/{name}` never reach the backend.
  The frozen contract has 16 operations (`ingest/src/main/resources/api/exchange-v1.openapi.json`),
  28 schemas (`ingest/src/main/resources/exchange/v1/schemas/`) and 101 fixtures
  (`docs/exchange/examples/v1/`); only ingest tests read them.
- Identity: the gateway's own client-credentials token, never the member's (ADR-0129), plus five
  relay headers (`X-Ingest-On-Behalf-Of`, `X-Exchange-Client`, `X-Exchange-Capabilities`,
  `X-Exchange-Installation`, optional `X-Exchange-Connected-At`). The backend honours the
  on-behalf-of header only for an `azp` in `app.security.ingest-gateway.client-ids` and only on the
  13 patterns of `ActingMemberFilter.EXCHANGE_PATHS`.
- A refusal passes the gateway only if its `code` is in the gateway's tables: 4 translated codes
  (`ACCESS_DENIED` → `NOT_PERMITTED`, `VALIDATION_FAILED`/`BAD_REQUEST` → `SCHEMA_INVALID`,
  `OPTIMISTIC_LOCK` → `VERSION_CONFLICT`) and 16 pass-through codes, 7 of them gate codes with one
  exact status; anything else becomes `502 BACKEND_RELAY_FAILED`. The codes come from backend-wide
  components (`GlobalExceptionHandler`, `BasetoolErrorController`, `TermsAcceptanceAccessFilter`,
  `PendingApprovalAccessFilter`, `ActingMemberFilter`, `ExchangeProblemException`).
- Only 3 of the 37 files in `backend/…/model/dto/exchange` carry
  `@JsonIgnoreProperties(ignoreUnknown = true)`; the rest rely on the global mapper, whose Jackson
  3.1.5 default ignores unknown properties.
- **Verified**: none of the 198 distinct `/api/v1` paths in the `CONTRACT` list of
  `ExternalContractTest` is an exchange path, and no backend or frontend test validates against the
  schemas or fixtures. Only the two draft operations touch frozen app schemas (12 web DTOs reached
  through `BlueprintImportPreviewDto` and `RefineryImportDraftDto`), incidentally, and their bodies
  never reach the client.

### Wire identifiers shared across modules

| Identifier | Declared in | Pinned by a build-time test |
| --- | --- | --- |
| 5 relay headers | ingest `ExchangeRelay.java:78-100`; backend `ActingMemberHeader.java:31-57` | 1 of 5 (`OnBehalfOfHeaderParityTest`) |
| 7 gate codes with exact statuses | ingest `ExchangeRefusals.java:57-75`, `ExchangeRelay.GATE_STATUSES` (`:137-152`); backend `ExchangeProblemException.java:46-64,104-171` | 0 |
| 16 pass-through and 4 translated codes | ingest `ExchangeRelay.java:122-191`; 6 backend classes | 0 |
| 14 relay targets | ingest `ExchangeController` literals; backend `ActingMemberFilter.java:98-112` and 8 `@RequestMapping`s | 0 (E2E only) |
| 10 capability scopes | backend `ExchangeCapability`; ingest `ExchangeRoutes.java:46-64`; `exchange-v1.openapi.json` | ingest ⇄ OpenAPI yes (`ExchangeRoutesContractTest`); backend enum no |
| Registry mirror document | backend `ExchangeRegistryMirrorDocument`, `ExchangeRegistrySnapshot.Client`; ingest `ExchangeRegistryReader.java:159-202` | 0 |
| Revocation key prefixes | backend `RedisExchangeRevocationMirror.java:40,43`; ingest `ExchangeRevocationReader.java:39,42` | 0 |
| Handoff key prefix, `StagedHandoff`, `HandoffKind` | ingest `HandoffStagingService.java:49`, `model/dto`; frontend `IngestHandoffService.java:50`, `model/dto` | 0 (E2E `IngestHandoffE2eTest`) |
| Landing pages in exchange answers | ingest `IngestProperties.java:57-58`, `ExchangeController.java:116` | listed in `FrontendPageRoutes`, no test links them |
| keycloak-spi precheck: path, `X-KRT-SPI-Secret`, JSON fields | backend `DiscordAccountExistenceController.java:54,61`; keycloak-spi `BackendAccountChecker.java:51,132-199` | 0 |
| keycloak-spi admin extension `basetool-exchange` | backend `KeycloakService.java:88,726`; keycloak-spi `ExchangeClientSessionResourceProviderFactory.java:37` | 0 |

**Verified** for the registry mirror: the reader fails closed on `schemaVersion`, `enabled`,
`status` and `capabilities`, but a renamed `minClientVersion` means no minimum version, and renamed
`requestsPerMinute`/`writesPerDay` fall back to 120 and 500. A rename therefore loosens a tighter
per-client override and tightens a looser one.

### keycloak-spi

- 16 classes in one flat package (about 2,000 lines): Discord federation and gate (10), backend
  precheck (`BackendAccountChecker`, `BackendTrustSupport`), exchange and consent
  (`ExchangeClientSession*` × 3, `DeviceConsentLoginForms*` × 2).
- Six service registration files under `keycloak-spi/src/main/resources/META-INF/services/`; one
  (`LoginFormsProviderFactory`) is read back by a test.
- Java 21 bytecode (`options.release.set(21)`, `keycloak-spi/build.gradle.kts:13`); `@JBossLog`
  enforced by `keycloak-spi/lombok.config`; no project dependency.
- Compiles `compileOnly` against Keycloak 26.7.4 internals (`gradle/libs.versions.toml:48`),
  while the runtime image is pinned to a minor tag by digest; nothing compares the two.

### Build logic

- Root `build.gradle.kts` 527 lines; `allprojects {}` at `:14`; `subprojects {}` spans `:122-475`
  (354 lines) with nine `plugins.withId` blocks: java 71 lines, Spring Boot 14, JaCoCo 53, PIT 23,
  Checkstyle 12, Spotless 35, CycloneDX 81, SpotBugs 22, Licensee 33.
- Module scripts: backend 136, frontend 684, ingest 78, keycloak-spi 39, logging-support 25,
  test-support 35 lines.
- Keyed on the project name: the test heap map (`:183-185`, 3 names, default 1024 MB), JaCoCo floors
  (`:228-241`, 4 names, default 0.50/0.40), the PIT target package (`:267`), the SBOM output path
  (`:352-353`).
- Isolated Projects blockers: `allprojects {}` and `subprojects {}`, and 22 cross-project
  `rootProject.(file|fileTree|layout|extra)` accesses in module scripts (backend 7, frontend 13,
  test-support 2) plus 5 in the root script; `rootProject.extra[...]` in
  `frontend/build.gradle.kts:169-172`.
- Version catalog: 10 plugins, 42 versions, 42 libraries. `settings.gradle.kts` (33 lines) declares
  repositories once with `FAIL_ON_PROJECT_REPOS`; `gradle.properties` (5 lines) holds build cache,
  parallel, tooling parallel, daemon and `-Xmx2g`; wrapper 9.8.0. No `buildSrc`, no `build-logic`,
  no `java-test-fixtures`, no `module-info.java`, no `maxParallelForks`.
- CI builds with `--configuration-cache` (`.github/workflows/ci.yml:63,76`) but pins
  `gradle/actions/setup-gradle` v6.3.0 without `cache-encryption-key`
  (`.github/actions/setup-jdk-gradle/action.yml:33`), so configuration-cache entries are never
  restored in a later run.
- Module lists outside Gradle: 22 files with 144 lines name a module directory or `:project`
  (`image_reuse_plan.py:44`, `check_sbom_coverage.py:31-45`, `pitest.yml:23`,
  `release-images.yml:451`, `promote.yml:67,181`, …).
- Dependency verification (`gradle/verification-metadata.xml`): 608,414 bytes, 9,622 lines,
  `verify-metadata` true, `verify-signatures` false, 2 trusted-artifact rules (sources, javadoc),
  1,308 components, 2,331 SHA-256 entries (POM 1,220, JAR 767, Gradle module 342, gz 1, zip 1), 303
  groups, 11 `org.keycloak*` components. An included `build-logic` is covered by the root file.

### Registrations a new Gradle module needs

| Where | What a new module needs | If forgotten |
| --- | --- | --- |
| `settings.gradle.kts` | `include` | not built |
| `docker/app/Dockerfile:13-18` | `COPY <module>/build.gradle.kts` | image build fails — loud |
| `docker/app/Dockerfile:26-27` | `COPY <module>/src/main/` for backend-carried modules | backend image fails to compile — loud |
| `docker/sandbox/keycloak/Dockerfile:6-11` | `COPY <module>/build.gradle.kts` | sandbox image fails — loud |
| `.github/scripts/image_reuse_plan.py:54-56` | `MODULE_OWN` entry or a prefix rule | every change rebuilds all three images — safe, about 3× the jobs |
| `.github/scripts/check_sbom_coverage.py:31-45` | `SHIPPED_INSIDE` with carrier `backend` | repo-lint fails — loud |
| `.github/workflows/sandbox-images.yml:8-14` | path entry | sandbox images not rebuilt — **silent** |
| JaCoCo floors (`build.gradle.kts:228-241`) | entry | defaults 0.50/0.40 — **silent** |
| PIT (`build.gradle.kts:267`, `pitest.yml:23`) | target pattern and matrix | not mutated — **silent**, or loud when empty |
| test heap (`build.gradle.kts:183-185`) | entry | 1024 MB default |
| Flyway check (`scripts/check-flyway-migrations.sh:5`) | only if migrations are split | numbering not checked — **silent** |

### Test contexts

| Measure | backend | frontend | ingest |
| --- | ---: | ---: | ---: |
| `@SpringBootTest` classes | 231 | 161 | 21 |
| `@ActiveProfiles` files | 191 | 42 | 0 |
| `@MockitoBean` files | 95 | 160 | 21 |
| `@DirtiesContext` | 0 | 0 | 0 |
| distinct context keys (static proxy; singletons) | 49 (40) | 26 (17) | 13 (9) |

The two largest backend groups (94 and 16 classes) differ only by `@ActiveProfiles("test")`,
although the Gradle test task forces the profile (`build.gradle.kts:186`). The September audit
measured 38 context keys against Spring's default cache of 32 and `:backend:test` at about 8.5
minutes.

## Sources

### Web sources

All read on 2026-09-29.

| # | Source | Used for |
| ---: | --- | --- |
| 1 | https://openjdk.org/projects/jdk/ and https://openjdk.org/projects/jdk/24/, /25/, /26/, /27/, /28/ | JEP lists, GA dates, cadence |
| 2 | https://openjdk.org/jeps/NNN for 456, 467, 483, 485, 491, 500, 504, 513, 514, 516, 517, 522, 523, 527, 534, 536, 542, 544 | JEP status and content; AOT cache constraints |
| 3 | https://www.oracle.com/java/technologies/java-se-support-roadmap.html | LTS cadence, next LTS, support dates |
| 4 | https://github.com/spring-projects/spring-modulith/releases; https://spring.io/projects/spring-modulith | Spring Modulith versions and dates |
| 5 | https://docs.spring.io/spring-modulith/reference/ (fundamentals, verification, testing, events, documentation, production-ready, runtime, appendix; docs 2.1.1) | Spring Modulith features |
| 6 | https://github.com/spring-projects/spring-modulith at tag 2.1.1: `ApplicationModule.java`, `NamedInterface.java`, `Modulithic.java`, `ApplicationModuleListener.java`, `schemas/v2/schema-postgresql.sql`, `JdbcEventPublicationRepository.java` | module attributes, listener semantics, registry DDL, handling of unknown event types |
| 7 | https://repo1.maven.org/maven2/ — `spring-modulith-core` 2.1.1 and the starter POMs and jars | dependency tree, JPMS manifests |
| 8 | https://github.com/xmolecules/jmolecules; https://github.com/xmolecules/jmolecules-integrations (tag 0.33.0); the `jmolecules-bom` 2025.0.2 POM on Maven Central | jMolecules |
| 9 | https://www.archunit.org/userguide/html/000_Index.html; https://github.com/TNG/ArchUnit/releases (v1.5.0, v1.5.1) | ArchUnit features and versions |
| 10 | Maven Central: `spring-boot-dependencies` 4.1.1 and `spring-data-bom` 2026.0.1 POMs | Boot-managed versions |
| 11 | https://docs.spring.io/spring-boot/system-requirements.html; https://docs.spring.io/spring-boot/reference/io/rest-client.html; https://docs.spring.io/spring-boot/reference/features/spring-application.html | Boot 4.1.1 requirements, HTTP service clients, virtual threads |
| 12 | https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Release-Notes, …/Spring-Boot-4.1-Release-Notes, …/Spring-Boot-4.0-Migration-Guide; https://spring.io/blog/2025/10/28/modularizing-spring-boot | Boot release notes and modular starters |
| 13 | https://github.com/spring-projects/spring-boot at tag v4.1.1: `WebClientCustomizerHttpServiceGroupConfigurer.java`, `ReactiveHttpServiceClientAutoConfiguration.java`, `TomcatWebServer.java`, `JavaPluginAction.java`, `JacksonAutoConfiguration.java` | HTTP service groups, keep-alive, `bootJar` classpath, Jackson defaults |
| 14 | https://docs.spring.io/spring-framework/reference/ — integration/rest-clients, web/webmvc-versioning, core/resilience, core/null-safety, testing/resttestclient, web/webflux-webclient/client-attributes, client-builder (7.0.9) | Framework 7 features |
| 15 | https://github.com/spring-projects/spring-framework at tag v7.0.9: `BeanReference.java`, `BeanFactoryResolver.java`, `build.gradle`, `gradle/spring-module.gradle`, framework-docs `overview.adoc`, `core/aop/proxying.adoc`, `core/beans/classpath-scanning.adoc`, `core/resources.adoc`, the HTTP-interface and `ClientRequest` sources | SpEL failure chain, NullAway use, JPMS, scanning, HTTP interfaces |
| 16 | https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html (7.1.1); https://github.com/spring-projects/spring-security at tag 7.1.1: `authorization/method/ExpressionUtils.java`, `access/expression/ExpressionUtils.java` | method security |
| 17 | https://docs.openrewrite.org/reference/gradle-plugin-configuration; https://docs.openrewrite.org/recipes/java/changepackage, …/java/changetype, …/java/shortenfullyqualifiedtypereferences, …/java/migrate/upgradetojava25, …/java/spring/boot4/upgradespringboot_4_0-community-edition, …/java/testing/junit6/junit5to6migration; https://github.com/openrewrite/rewrite (main): `ChangeType.java`, `ChangePackage.java` and the reference classes | OpenRewrite distribution, licences, coverage |
| 18 | https://plugins.gradle.org/ (`org.openrewrite.rewrite` metadata); https://repo1.maven.org/maven2/ (`rewrite-java`, `rewrite-java-25`, `rewrite-recipe-bom` metadata) | last freely published OpenRewrite versions |
| 19 | https://errorprone.info/docs/installation; https://github.com/google/error-prone/releases; https://github.com/uber/NullAway/wiki/JSpecify-Support; https://github.com/uber/NullAway/wiki/Supported-Annotations; https://github.com/uber/NullAway/releases/tag/v0.14.0; https://github.com/spring-gradle-plugins/nullability-plugin; https://spring.io/blog/2025/11/12/null-safe-applications-with-spring-boot-4/ | Error Prone and NullAway |
| 20 | https://docs.gradle.org/current/userguide/configuration_cache.html, isolated_projects.html, best_practices_structuring_builds.html, dependency_verification.html, version_catalogs.html, java_testing.html, jvm_test_suite_plugin.html (9.8.0); https://github.com/gradle/gradle/issues/15383 | Gradle |
| 21 | https://hibernate.org/orm/releases/7.4/; https://docs.hibernate.org/orm/7.4/whats-new/whats-new.html; https://docs.hibernate.org/orm/7.0/whats-new/whats-new.html; https://docs.hibernate.org/orm/7.4/repositories/html_single/; https://docs.hibernate.org/orm/7.4/javadocs/org/hibernate/annotations/UuidGenerator.Style.html | Hibernate |
| 22 | https://github.com/spring-projects/spring-data-commons/wiki/Spring-Data-2025.1-Release-Notes, …/Spring-Data-2026.0-Release-Notes | Spring Data |
| 23 | https://www.postgresql.org/about/news/postgresql-18-released-3142/; https://www.postgresql.org/docs/18/release-18.html, ddl-generated-columns.html, functions-uuid.html, sql-createtable.html, sql-createindex.html; https://www.postgresql.org/support/versioning/ | PostgreSQL 18 |
| 24 | https://registry.npmjs.org/web-features (3.40.0, `data.json`) | Baseline status, browser versions |
| 25 | https://www.rfc-editor.org/rfc/rfc9745.html | the `Deprecation` header format |
| 26 | https://github.com/spring-projects/spring-framework/issues/18079, /26159, /29782, /30276, /32671; https://github.com/spring-projects/spring-boot/issues/26578, /41203, /41204, /41218 | JPMS position, advice ordering on HTTP-interface proxies |
| 27 | https://github.com/gradle/actions (main): `setup-gradle/action.yml`, `docs/setup-gradle.md`, `sources/src/cache-service.ts`; releases v6.3.0, v6.4.0 | configuration-cache reuse in CI |
| 28 | https://docs.github.com/en/actions/reference/runners/github-hosted-runners | runner size |
| 29 | https://github.com/flyway/flyway at tag flyway-13.8.0: `ClassPathScanner.java`, `ChecksumCalculator.java`, `MigrationInfoImpl.java`, `CompositeMigrationResolver.java`; releases | one `classpath:` location across jars, checksums |
| 30 | https://docs.spring.io/spring-boot/reference/data/sql.html | entity scanning across jars |
| 31 | https://github.com/FasterXML/jackson-databind at tag jackson-databind-3.1.5: `DeserializationFeature.java` | the unknown-property default |
| 32 | https://github.com/OpenAPITools/openapi-generator at tag v7.25.0: `docs/generators/java.md`, `docs/generators/spring.md` | generator options |
| 33 | https://github.com/micrometer-metrics/context-propagation at tag v1.2.1: `ContextSnapshotFactory.java`, `ContextSnapshot.java`, `ContextRegistry.java` | context capture and restore |
| 34 | https://github.com/docker/metadata-action at v6.2.0: `README.md`, `src/meta.ts` | the default `org.opencontainers.image.revision` label |
| 35 | https://github.com/containers/skopeo (main): `docs/skopeo-inspect.1.md`, `docs/skopeo.1.md`; release v1.24.1 | labels of a multi-arch index |
| 36 | https://docs.oracle.com/javase/specs/jls/se25/html/jls-14.html (§14.11.1) | several unnamed patterns in one case label |
| 37 | https://w3c.github.io/trusted-types/dist/spec/ (§3.4); https://w3c.github.io/webappsec-csp/ (§5.5); https://developer.mozilla.org/en-US/docs/Web/API/SecurityPolicyViolationEvent/disposition | Trusted Types, report-only events |
| 38 | https://html.spec.whatwg.org/multipage/scripting.html, webappapis.html, interaction.html (§6.3.1) | import maps, nonces, script order, inertness |
| 39 | https://github.com/stylelint/stylelint at tag 17.15.0: `lib/rules/no-unknown-custom-properties`, `CHANGELOG.md` | the unknown-custom-property rule |

### JDK releases 24–28

Read from `openjdk.org/projects/jdk/NN/`. Bold marks developer-facing features.

| Release | GA | Final JEPs | Non-final JEPs |
| --- | --- | --- | --- |
| 24 | 2025-03-18 | 472 Prepare to Restrict JNI · 475 Late Barrier Expansion for G1 · 479 Remove Windows 32-bit x86 Port · 483 AOT Class Loading & Linking · 484 Class-File API · **485 Stream Gatherers** · 486 Permanently Disable the Security Manager · 490 ZGC: Remove Non-Generational Mode · **491 Synchronize Virtual Threads without Pinning** · 493 Linking Run-Time Images without JMODs · 496 ML-KEM · 497 ML-DSA · 498 Warn upon Use of Memory-Access Methods in `sun.misc.Unsafe` · 501 Deprecate the 32-bit x86 Port for Removal | 404 Generational Shenandoah (Experimental) · 450 Compact Object Headers (Experimental) · 478 KDF API (Preview) · 487 Scoped Values (4th Preview) · 488 Primitive Types in Patterns (2nd Preview) · 489 Vector API (9th Incubator) · 492 Flexible Constructor Bodies (3rd Preview) · 494 Module Import Declarations (2nd Preview) · 495 Simple Source Files (4th Preview) · 499 Structured Concurrency (4th Preview) |
| 25 (LTS) | 2025-09-16 | 503 Remove 32-bit x86 Port · **506 Scoped Values** · **510 Key Derivation Function API** · **511 Module Import Declarations** · **512 Compact Source Files and Instance Main Methods** · **513 Flexible Constructor Bodies** · 514 AOT Command-Line Ergonomics · 515 AOT Method Profiling · 518 JFR Cooperative Sampling · 519 Compact Object Headers · 520 JFR Method Timing & Tracing · 521 Generational Shenandoah | 470 PEM Encodings (Preview) · 502 Stable Values (Preview) · 505 Structured Concurrency (5th Preview) · 507 Primitive Types in Patterns (3rd Preview) · 508 Vector API (10th Incubator) · 509 JFR CPU-Time Profiling (Experimental) |
| 26 | 2026-03-17 | **500 Prepare to Make Final Mean Final** · 504 Remove the Applet API · 516 AOT Object Caching with Any GC · **517 HTTP/3 for the HTTP Client API** · 522 G1: Improve Throughput by Reducing Synchronization | 524 PEM (2nd Preview) · 525 Structured Concurrency (6th Preview) · 526 Lazy Constants (2nd Preview) · 529 Vector API (11th Incubator) · 530 Primitive Types (4th Preview) |
| 27 | 2026-09-15 | 523 G1 the Default GC in All Environments · 527 Post-Quantum Hybrid Key Exchange for TLS 1.3 · 534 Compact Object Headers by Default · 536 JFR In-Process Data Redaction | 531 Lazy Constants (3rd Preview) · 532 Primitive Types (5th Preview) · 533 Structured Concurrency (7th Preview) · 537 Vector API (12th Incubator) · 538 PEM (3rd Preview) |
| 28 | in development, no GA date | 542 PEM Encodings (completed); 544 Ahead-of-Time Code Compilation proposed to target; 535 Shenandoah generational by default · 541 Deprecate the macOS/x64 port | 401 Value Objects (Preview) · 539 Strict Field Initialization in the JVM (Preview) · 540 Simple JSON API (Incubator) |

| JEP | Status |
| --- | --- |
| 456 Unnamed Variables & Patterns | final in 22 |
| 467 Markdown Documentation Comments | final in 23 |
| 485 Stream Gatherers | final in 24; built-in `fold`, `mapConcurrent`, `scan`, `windowFixed`, `windowSliding` |
| 491 Synchronize Virtual Threads without Pinning | final in 24; pinning remains while loading a class, in class initialisers and while waiting for another thread to initialise a class |
| 513 Flexible Constructor Bodies | final in 25; the prologue may assign uninitialised fields, not read `this` |
| 500 Prepare to Make Final Mean Final | delivered in 26; `--illegal-final-field-mutation=allow\|warn\|debug\|deny`, default `warn`, `deny` announced as a future default; opt-in per module with `--enable-final-field-mutation` |

Cadence and LTS: a feature release every six months; LTS releases 8, 11, 17, 21 and 25, then every
two years — the next planned LTS is Java 29 in September 2027 (marked subject to change). Java 25
has Premier Support until September 2030 and Extended Support until September 2033; Java 27
(non-LTS) Premier Support until March 2027. Spring Boot 4.1.1 supports Java 17 up to and including
26.

### Versions

| Component | Facts (read 2026-09-29) |
| --- | --- |
| Spring Modulith | 2.1.1 GA 2026-08-26 on Boot 4.1.1 and Framework 7.0.9; 2.1.0 (2026-06-11) moved to Boot 4.1; 2.0.8 is the Boot 4.0 line, 1.4.13 the Boot 3.5 line; 2.2.0-M1 (2026-08-26) and 2.2.0-M2 (Maven Central, 2026-09-24) target Boot 4.2. `spring-modulith-core` 2.1.1 depends on ArchUnit 1.4.2 (compile scope) and jspecify 1.0.1; the starters put `spring-modulith-core` and ArchUnit on the runtime classpath; JDBC schema initialisation of the event registry defaults to `true` |
| Spring Boot | 4.1.1 (2026-08-20), 4.1.0 (2026-06-10), 4.2.0-M2 (2026-09-24) |
| Spring Framework | 7.0.9 (2026-08-20), 7.1.0-M2 (2026-09-24) |
| Hibernate ORM | 7.4.5.Final managed by Boot 4.1.1; latest 7.4 patch 7.4.11.Final (2026-09-27); 7.4 supports Java 17, 21, 25 and 26 |
| Flyway | 12.4.0 managed by Boot; the project overrides with 13.8.0; latest 13.8.1 (2026-09-29) |
| PostgreSQL | 18 released 2025-09-25; current minor 18.6, supported until 2030-11-14; 19 at Beta 4 (2026-09-24) |
| Gradle | 9.8.0 (2026-09-24), used by the project; 9.7.1 (2026-08-19); Isolated Projects incubating since 9.7.0; the configuration cache becomes the default in Gradle 10 |
| ArchUnit | 1.5.1 (2026-09-25), used by the project; 1.5.0 (2026-08-04) added Java 27 class files, `JavaClass.isSealed()`, dependencies from caught exceptions |
| OpenRewrite | Gradle Plugin Portal ends at `org.openrewrite.rewrite` 7.41.0 (2026-08-26); 7.42.0 (2026-09-09) and 7.43.0 (2026-09-23) are published only to an authenticated repository; `rewrite-java` and `rewrite-java-25` 8.90.4 (2026-08-24) on Maven Central; `rewrite-recipe-bom` 3.38.0; `ChangePackage`/`ChangeType` Apache 2.0; `UpgradeToJava25`, `UpgradeSpringBoot_4_0`, `JUnit5to6Migration` under the Moderne Source Available License |
| Error Prone | 2.50.0 (2026-06-10); runs on JDK 21 or newer; needs about ten `--add-exports`/`--add-opens` javac flags |
| NullAway | 0.14.2 (2026-09-25); 0.14.0 (2026-08-21); JSpecify mode needs JDK 22+; recognises any `@Nullable`/`@NotNull` by simple name |
| Gradle plugins for them | `net.ltgt.errorprone` 5.1.1 and `net.ltgt.nullaway` 3.2.0 (both 2026-08-25); `io.spring.nullability` 0.0.14 is used by Framework 7.0.9, latest 0.0.16 (2026-09-01) |
| jMolecules | BOM 2025.0.2 (2025-12-19): jMolecules 2.0.1 (2025-11-20), integrations 0.33.0 (2025-12-19) |
| openapi-generator | 7.25.0 (2026-08-24); no record output in the `java` generator |
| context-propagation | 1.2.1 (resolved by the project; latest) |
| `gradle/actions/setup-gradle` | v6.4.0 (2026-09-28); the project pins v6.3.0 |
| `docker/metadata-action` | v6.2.0 (pinned); emits `org.opencontainers.image.revision` by default |
| skopeo | v1.24.1 (2026-09-16) |
| web-features | 3.40.0 (2026-09-24) |

Managed by Spring Boot 4.1.1:

| Library | Version |
| --- | --- |
| Spring Framework | 7.0.9 |
| Spring Security | 7.1.1 |
| Spring Data BOM | 2026.0.1 (JPA, Commons, JDBC and Redis 4.1.1) |
| Spring Session | 4.1.1 |
| Hibernate | 7.4.5.Final |
| Hibernate Validator | 9.1.3.Final |
| Jackson 3 / Jackson 2 | 3.1.5 / 2.21.5 |
| Tomcat | 11.0.24 |
| Micrometer / Micrometer Tracing | 1.17.1 / 1.7.1 |
| JUnit Jupiter | 6.0.3 (the project overrides with 6.1.3) |
| Mockito | 5.23.0 (the project uses 5.24.0) |
| Flyway | 12.4.0 (the project overrides with 13.8.0) |
| jspecify | 1.0.1 |
| PostgreSQL JDBC | 42.7.13 |
| Testcontainers | 2.0.5 |

No Spring, Boot, Modulith or Hibernate jar ships a `module-info.class`; they carry only an
`Automatic-Module-Name` (checked for spring-core, spring-context, spring-webmvc 7.0.9, spring-boot
and spring-boot-autoconfigure 4.1.1, spring-security-core 7.1.1, spring-data-jpa 4.1.1,
spring-modulith-core 2.1.1, hibernate-core 7.4.5.Final); jackson-databind 3.1.5 and jspecify 1.0.1
do ship one.
