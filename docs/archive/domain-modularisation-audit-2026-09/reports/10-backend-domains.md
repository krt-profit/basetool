# 10 — Backend domain map and cross-domain coupling (prefix DOM)

Scope: `backend/src/main/java` of the worktree at `95e945326` — 1389 top-level types, 174,597 physical
LOC (87,227 non-comment, non-blank). Class graph: `jdeps-backend.txt` (7155 lines), folded
`Outer$Inner → Outer`, MapStruct `XMapperImpl → XMapper`, self-edges dropped: **5696 class edges**,
of which 3464 stay inside one category and 2232 cross categories; **1565** cross between categories
other than `shared-kernel` and `infrastructure`. Every number below is produced by a script listed in
§5.11; every class's domain and the rule that decided it are in `10-backend-domains-classes.csv`,
every domain×domain cell with its edge kinds and examples in `10-backend-domains-matrix.csv`.

Owner update 2026-09-29 is applied: the `/api/v1` REST surface may be re-cut per domain, so the
module cards (§3) record which endpoints belong to a module *by responsibility*; the exchange
contract (`ingest/src/main/resources/api/exchange-v1.openapi.json`) is treated as frozen.

---

## 1. Summary

1. **The layer graph is acyclic (ADR-0047), the domain graph is not:** 21 of the 23 non-kernel
   categories form one strongly connected component — only `admin` and `dashboard` sit outside it —
   across 1565 cross-domain class edges and 41 two-way domain pairs (§5.3, §5.4;
   `10-backend-domains-graph.py`, `10-backend-domains-pivot.py`).
2. **The entanglement reaches the business core, not only the platform:** with access, audit,
   notification, livesync, identity, orgunit and catalogue removed, blueprint, exchange, hangar,
   inventory, joborder, materialexchange, mission, operation and refinery still form one 9-domain
   cycle (§5.4).
3. **The whole decoupling backlog is 150 class edges in 48 module pairs** once behaviour-free
   re-homings are applied and a target layering is fixed; it is concentrated in inventory (36),
   identity (34), the per-aggregate scope gates (17) and mission (14) (§5.5,
   `10-backend-domains-layering.py`).
4. **Authorization is domain-coupled and invisible to the compiler:** `AccessGateService` reads six
   business domains' repositories, and 66 endpoints in seven domains name `@ownerScopeService` in
   SpEL strings that neither jdeps nor javac check (DOM-03, §5.10).
5. **GDPR orchestration ties identity to 14 domains:** `UserDeletionService` has 21 collaborators,
   `UserAccountMergeService` re-points 28 `table.column`s by native SQL
   (`UserAccountMergeService.java:90`, `:321`), and the export, person-search and erasure registries
   name tables of up to 18 modules (DOM-04).
6. **Inventory has no command API:** Lager rows are created outside their owner in three modules
   (`ExchangeStockWriteService.java:799`, `JobOrderItemProductionService.java:383`,
   `RefineryOrderService.java:631`), and three foreign modules take `PESSIMISTIC_WRITE` or advisory
   locks on them — the APPSEC-01 defect of the September audit was exactly such a foreign write
   (DOM-05).
7. **Cross-domain writes need the caller's transaction:** 149 non-audit write sites in 57 caller
   methods plus 205 same-transaction `AuditService.record` calls; 12 of the 16 write-path families
   need the caller's transaction (`MANDATORY` hops, row/advisory locks, FK order or same-transaction
   audit), so module APIs must be synchronous in-process contracts — which rules out Option D and an
   async-event-first design (DOM-08, §5.8).
8. **Only 8 of the 74 cross-domain JPA associations have to become id references for the cut:**
   185 associations, 74 cross a domain (24 → `User`, 18 → orgunit, 24 → catalogue, 8 business →
   business), none cascades across a domain, one is EAGER (`MissionParticipant.java:86`) (DOM-09,
   §5.6).
9. **Today's gates would break or go quiet under a package move:** 44 ArchUnit selectors are keyed on
   layer packages, six security/ledger invariants compare FQCN strings and would pass vacuously after
   a move, the cycle rule would turn into a failing domain-cycle rule, and the sealed `AppException`
   cannot permit subclasses in other packages (DOM-12).
10. **Recommendation — Option C:** first a package-per-module monolith gated by ArchUnit 1.5.1's
    `modules()` rules (already in the catalog; verified in the cached jar) plus a `FreezingArchRule`
    ratchet, extracted leaves first; then Gradle modules for `exchange` (11 inbound edges) and `bank`
    (18) once their boundaries hold. Option B alone is impossible while the SCC exists (Gradle forbids
    cyclic project dependencies); Option D is rejected (DOM-20).

---

## 2. Findings

### DOM-01 — The domain graph is one strongly connected component, hidden by package-by-layer

- **Evidence.** Tarjan SCC over the domain graph (edge A→B when any class of A depends on a class of
  B): one SCC of 21 categories; acyclic singletons only `admin`, `dashboard` (§5.4). 41 domain pairs
  depend on each other in both directions (§5.4). A weighted Eades–Lin–Smyth ordering still needs
  **211 back edges in 52 pairs** (`10-backend-domains-graph.out.txt`). The only cycle gate,
  `backendPackagesShouldBeFreeOfDependencyCycles`
  (`backend/src/test/java/.../ArchitectureTest.java:603-612`), slices on
  `de.greluc.krt.profit.basetool.backend.(*)..` — the *layer* packages — so it cannot see domains.
  1383 of 1389 top-level types are `public` (`grep -L "^public …"`), so no domain can hide anything.
- **Impact.** No domain boundary is observable, let alone enforced; any class may reach any domain's
  repository (206 cross-domain `service → repository` edges, §5.3). This is the precondition every
  target option needs fixed first.
- **Proposed change.** Make this audit's domain map a checked-in artefact and gate it *before any
  class moves*: ArchUnit 1.5.1 `ModuleRuleDefinition.modules().definedBy(DescribedFunction<JavaClass,
  ArchModule.Identifier>)` reading the map, `.should().respectTheirAllowedDependencies(...)` with the
  target layering of §5.5, wrapped in `FreezingArchRule.freeze(...)` so today's 150 violations are
  recorded and may only shrink (API verified with `javap` against
  `archunit-1.5.1.jar`: `ModuleRuleDefinition$Creator.definedBy`, `definedByPackages`,
  `definedByAnnotation`; `ModulesShould.respectTheirAllowedDependencies`,
  `onlyDependOnEachOtherThroughClassesThat`, `beFreeOfCycles`; `FreezingArchRule.freeze`). Switch
  `definedBy(map)` to `definedByPackages("..backend.(*)..")` module by module as packages move.
- **Pros.** Immediate visibility, zero runtime change, no new dependency, no dependency-verification
  churn; every PR can only reduce coupling.
- **Cons.** The map needs upkeep until classes move; the freeze store is a reviewed artefact;
  ADR-0047 explicitly rejected freezing for the nine *layer* cycles, so this needs a new ADR.
- **Risks and guard.** A wrong map entry would let a forbidden edge in → add a rule "every class
  belongs to exactly one module" (fails on unmapped classes) and review freeze-store diffs.
  Security: none at runtime; the gate only adds checks.
- **Effort.** S–M. **Prerequisites.** New ADR amending ADR-0047 (owner approval, it changes an
  endorsed gate); decide the target layering (§5.5).

### DOM-02 — The preliminary taxonomy needs six refinements the code demands

- **Evidence** (`10-backend-domains-classes.csv`, column `rule`/`ambiguous`; 42 ambiguous classes
  listed with reasons):
  1. **`leadership` has no aggregate.** 12 classes, 0 entities; 30 of its 33 outbound edges go to
     orgunit; its only writes are `SquadronRoleController → OrgUnitMembershipService.assign/removeSquadronRankDto`
     (`SquadronRoleController.java:76,101`); orgunit's `OrgHierarchyController` uses four leadership
     DTOs. → **merge into `orgunit`**.
  2. **`access` is two things.** `access-core` (8 classes: `AuthHelperService`, `AuthenticatedSubject`,
     `SubjectAuthentication`, `Roles`, `Permissions`, `OrgUnitContextualAuthority`, two properties) has
     no domain dependency except one service-locator call (`AuthHelperService.java:229-231`
     `getBean(OwnerScopeService.class)`); `scope` (`RequestScopeResolver`, `ScopePredicate`,
     `OrgUnitStampingService`, `OwnerScopeService`, `AccessGateService`, `ScopeSpecifications`,
     `CustomJwtGrantedAuthoritiesConverter`, `OwnerOrgUnitRequiredException`) depends on orgunit and
     identity and — via the gates — on six business domains.
  3. **`identity` hides a `privacy` orchestration layer.** 31 of its 101 classes (deletion, merge,
     consolidation, Art. 15 export, person search, handle anonymisation, the registries) account for
     most of identity's 15 outbound domains.
  4. **The recipe graph is catalogue, not blueprint.** `model.scwiki.Blueprint` and its 7 child
     entities are synced reference data (`ScWikiBlueprintSyncService`, `P4kImportService`) read by
     blueprint, joborder, inventory and exchange; `blueprint` keeps ownership (personal/default),
     craftability, import and overview.
  5. **Platform services sit below the business domains.** audit (14 domains call
     `AuditService.record`), notification (14 event types in 5 domains implement its
     `NotificationEvent`), livesync — but they depend upward through 4, 9 and 3 class edges (DOM-16).
  6. **A composition root exists.** `SecurityConfig`, `DataInitializer`, `BusinessMetricsCollector`
     (the last reads seven domains' repositories) are the only infrastructure classes that need
     domain modules; they form an `app` module at the top.
- **Proposed target modules (25).** Platform: `shared-kernel`, `platform` (infrastructure +
  access-core), `app`. Platform services: `audit`, `notification`, `livesync`. Foundation:
  `catalogue`, `identity`, `orgunit` (+leadership), `scope`. Business: `admin`, `dashboard`,
  `orgchart`, `promotion`, `personalinventory`, `hangar`, `blueprint`, `inventory`, `mission`,
  `refinery`, `joborder`, `materialexchange`, `operation`, `bank`. Adapter: `exchange`. (`privacy`
  dissolves into identity-owned SPIs, DOM-04.)
- **Pros.** Every module then has an aggregate or a clear platform role; re-homing is
  behaviour-free. **Cons.** `catalogue` stays large (263 classes, 28,952 LOC; sub-areas universe 66,
  import 49, material 48, recipe 35, reference 24, ship 21, item 18, other 2) — split later if needed.
- **Risks and guard.** None at runtime; the leadership merge touches the `orgRoleManagementSecurityService`
  SpEL bean name only if the class is renamed — don't rename. **Effort.** S. **Prerequisites.** None.

### DOM-03 — Per-aggregate authorization gates are centralised and coupled through SpEL strings

- **Evidence.** `AccessGateService` (677 LOC, 28 public methods, 11 final-field dependencies) depends
  on `Mission/MissionRepository`, `JobOrder` + three job-order repositories, `InventoryItem(Repository)`,
  `RefineryOrder(Repository)`, `Operation(Repository)`, `Ship(Repository)`; the `OwnerScopeService`
  facade (723 LOC, **56** public methods) additionally names `JobOrder`, `RefineryOrder`
  (`10-backend-domains-neighbors.py AccessGateService OwnerScopeService`). SpEL in `@PreAuthorize`
  (`10-backend-domains-endpoints.py`): `@ownerScopeService` in **66** endpoints of blueprint (2),
  hangar (2), inventory (9), joborder (31 method refs), mission (7), operation (8), refinery (8);
  `@missionSecurityService` 38, `@authHelperService` 17, `@exchangeGate` 14, `@bankSecurityService`
  10, `@orgRoleManagementSecurityService` 8, `@connectedAppsGate` 7,
  `@specialCommandSecurityService` 5. `LiveSyncSubscriptionAuthorizer.java:92-96` switches over topic
  classes into the same gates; `StockViewerAccessService` delegates to `AccessGateService`. Gates are
  pinned by `staffelScopedServicesMustWireOwnerScopeOrAuthHelper` (`ArchitectureTest.java:978`,
  12-name whitelist) and `staffelScopedWriteEndpointsMustGateOnOwnerScopeService` (`:1036`).
- **Impact.** 17 of the 150 backlog edges; the reason the scope module is in a cycle with every
  scoped aggregate. The list query (`ScopeSpecifications` fragment in the aggregate's repository) and
  the per-row gate (in `AccessGateService`) that "must agree" (vault `Scoping`, Operation's four
  places) live in two modules.
- **Proposed change.** One access-policy bean per scoped aggregate, owned by its module
  (`missionAccessPolicy`, `jobOrderAccessPolicy`, `inventoryAccessPolicy`, `refineryAccessPolicy`,
  `operationAccessPolicy`, `shipAccessPolicy`), each built on the scope kernel
  (`RequestScopeResolver.currentScopePredicate()`, `ScopePredicate.permits`) and its own repository,
  bodies moved verbatim (ADR-0065 precedent). `OwnerScopeService` keeps the kernel methods plus
  deprecated one-line delegations so SpEL strings move **one module per PR**; the aggregate's JPQL
  fragment moves next to its policy. LiveSync gets a `LiveSyncTopicAuthorizer` SPI implemented per
  topic class by the owning module.
- **Pros.** Gate and list query of an aggregate sit together; the scope kernel becomes a small,
  stable foundation (8 classes); the 56-method facade disappears over time.
- **Cons.** 66 SpEL references to rewrite; ArchUnit rules to re-express; a transitional period with
  delegations.
- **Risks and guard (security-critical).**
  1. *A misspelt bean or method in SpEL is not a compile error* — it fails at request time. **New
     guard:** a test that collects every `@PreAuthorize` on every controller method, parses it with
     Spring's expression parser and asserts that every `@bean.method(args)` resolves to a bean in the
     context with a public method of that arity. Nothing like it exists today (grep for
     `SpelExpressionParser` in `backend/src/test` finds only `JacksonRecordTest`).
  2. *Gate semantics drift during the move* → verbatim bodies; existing `OwnerScopeServiceTest`,
     `*ControllerSecurityTest`, `UserMembershipsSecurityTest` suites unchanged in substance.
  3. *Gate rules weakened by re-keying* → express the two ArchUnit rules against an annotation such
     as `@ScopedAccessPolicy` on the new beans; keep the whitelist of scoped services explicit.
  4. *Bank org-unit blindness* — `bankClassesMustNotConsultOrgUnitScope` (`:1794`) and
     `delegatedRoleAuthoriserMustNotConsultOwnerScope` (`:1833`) name `OwnerScopeService` by FQCN
     string; they must be widened to "any scope API or access policy", or a `Bank*` class could
     consult a new policy bean unnoticed (ADR-0020, REQ-BANK-008, REQ-ROLE-004).
- **Effort.** L. **Prerequisites.** ADR-0065 amendment; owner approval (it changes an endorsed,
  security-load-bearing pattern); DOM-12's FQCN guard first; the SpEL resolution test first.

### DOM-04 — GDPR orchestration couples identity to 14 domains

- **Evidence.** `UserDeletionService` has 21 final-field dependencies (`10-backend-domains-size.py`)
  and writes 12 foreign repositories plus the offer ratchet in one `@Transactional`
  (`UserDeletionService.java:199-289`: `InventoryItemRepository.deleteByUserId`,
  `ShipRepository.deleteByOwnerId`, `PersonalInventoryItemRepository`, `PersonalBlueprintRepository`,
  `NotificationRepository`, `NotificationRuleRepository`, `MemberEvaluationRepository`,
  `MissionRepository.updateOwner/removeManager`, `MissionOwnershipRepository`,
  `MissionParticipantRepository.unlinkUser`, `RefineryOrderRepository.updateOwner`,
  `JobOrderRepository.removeAssignee`, `MaterialClaimRepository.unlinkClaimedByUser`,
  `MaterialExchangeOfferRatchet.beforeUserPurge` [MANDATORY], and `OrgUnitBankResponsibilityService`
  via `ObjectProvider`). `HandleAnonymisationService.java:141-189` runs `@Modifying` anonymisations on
  bank, job-order and audit tables. `UserAccountMergeService.java:90` lists **28**
  `FOLLOWS_THE_MEMBER` table.columns in 14 domains and `:134` **27** `STAYS_WITH_THE_ACT`; `merge`
  (`:190-192`) re-points them with native `UPDATE … SET col = :target` (`:321`) and
  conflict `DELETE` (`:300`), bypassing JPA `@Version`. `DataExportSections` (16 modules),
  `HandleErasureCoverage` (16) and `PersonSearchTargets` (18) name other modules' tables
  (`10-backend-domains-sqlrefs.out.txt`).
  Identity flows call these orchestrators 7 times (backlog pair identity→privacy).
- **Impact.** identity cannot be a foundation module; every new table with a user column edits
  identity (the coverage tests make sure of it — which is good, but it is the wrong owner).
- **Proposed change.** Keep the orchestrators in identity (`identity.privacy` package) but invert:
  identity owns SPIs — `UserErasureParticipant` (ordered phases: REASSIGN, UNLINK, DELETE, to keep
  today's FK order), `UserReassignmentParticipant` (each module lists its own `OwnedRows`),
  `HandleAnonymisationParticipant`, `PersonalDataExportSection`, `PersonMentionSource` — implemented
  in each owning module and invoked inside the orchestrator's single transaction
  (participants `@Transactional(propagation = MANDATORY)`).
- **Pros.** Each module owns the personal data it stores and argues its own "follows the member /
  stays with the act" lists; identity drops to a foundation module.
- **Cons.** One flow spans N beans; ordering must be explicit; five registries become five SPIs.
- **Risks and guard (security, legal).** A module without a participant leaves personal data behind
  or breaks deletion on an FK. Guard: the existing live-schema tests —
  `UserAccountMergeCoverageTest`, `UserIdentityColumnForeignKeyTest`,
  `UserDeletionForeignKeyIntegrityTest`, `HandleErasureCoverageTest`, `PersonSearchCoverageTest`,
  `DataExportScrubCoverageTest`, `HandleSpellingCoverageTest` — rewritten to compare the **union of
  registered participants** against `information_schema`, so an unclaimed user column still fails the
  build. Atomicity: one outer transaction, participants MANDATORY. Audit: the deletion/anonymisation
  audit rows (REQ-AUDIT-001) stay in the orchestrator.
- **Effort.** L–XL. **Prerequisites.** ADR; `docs/privacy` and the data-protection specs reviewed for
  wording; DOM-12 first.

### DOM-05 — Inventory has no command API; three modules create, lock and bulk-edit its rows

- **Evidence.** `new InventoryItem()` outside its owner: `ExchangeStockWriteService.java:799`
  (also records `INVENTORY_ITEM_CREATED` itself, `:811`, and calls
  `InventoryCheckoutService.mergeStockIfRequested` [MANDATORY], `:818`),
  `JobOrderItemProductionService.java:383`, `RefineryOrderService.java:631`. Foreign
  `PESSIMISTIC_WRITE` on inventory rows: `JobOrderHandoverService.java:162`,
  `JobOrderItemHandoverService.java:237`, `JobOrderItemProductionService.java:205`,
  `ExchangeStockWriteService.java:521,527`. ADR-0229's advisory lot lock: the query lives in
  inventory (`InventoryItemRepository.java:1148`), the protocol in exchange
  (`ExchangeStockWriteService.java:483`). Foreign `@Modifying` allocation deletes:
  `JobOrderService.java:298,458,750,753,854,967`. `docs/specs/audit.md:87-93` lists the "cross-area
  writers" that emit inventory-area audit events from refinery and job-order services.
  `InventoryItemRepository` (1188 LOC) is used by 6 domains. September audit APPSEC-01 (fixed in
  #1989): production book-in let a logistician write stock into any member's ledger — a foreign write
  that bypassed the owner check `InventoryItemService` already had.
- **Impact.** The append-only rule (ADR-0003), merge, SCU rounding, org-unit stamping, the offer
  ratchet and the audit payload are re-implemented in three modules; the next change to any of them
  must be mirrored in four places, and a missed mirror is a security or audit defect (APPSEC-01).
- **Proposed change.** `inventory.api.StockCommands`, every method
  `@Transactional(propagation = MANDATORY)` so callers keep their transaction:
  `bookIn(source ∈ {REFINERY, PRODUCTION, EXCHANGE, MANUAL}, owner, lot, amount)`,
  `consume(rows | lot, amount, reason ∈ {HANDOVER, PRODUCTION, EXCHANGE})`,
  `lockRowsForUpdate(...)`, `releaseEarmarks(targetKind, targetId[, material|gameItem])`, and
  `PersonalStockLots.lock(member, lotKeys)` owning ADR-0229's protocol. Each command records its
  inventory audit event and performs the owner/scope check (or takes an explicit, typed caller
  decision).
- **Pros.** One owner for inventory invariants and audit; exchange, refinery and joborder shrink.
- **Cons.** Large surface to design; four call sites to migrate with concurrency-hot code.
- **Risks and guard (concurrency and security).** Preserve: lock order (advisory first, ascending by
  key; rows by lot key), MANDATORY hops, **bulk-update-after-loop** (the `clearAutomatically`
  allocation deletes must still run once after the loop — the API must say so or stop clearing the
  context), the `FORCE_INCREMENT` version echo (`InventoryAllocations.forcedNextVersion`). Guards:
  existing concurrency/E2E tests (e.g. `MaterialCollectionDeliveredInPlaceE2eTest`), the ADR-0229
  load-test cases, a new ArchUnit rule "only `inventory` writes `InventoryItem`" (no constructor call,
  no repository write method outside the module), an audit-contract test per command (event type +
  payload keys, REQ-AUDIT-001), and a MockMvc 403 test per foreign entry point (the APPSEC-01 test
  shape).
- **Effort.** L. **Prerequisites.** ADR-0229 amendment (owner of the lock protocol); REQ-INV-*
  and `docs/specs/audit.md` coverage list updated in the same PR.

### DOM-06 — Earmarks tie inventory to joborder and mission in both directions

- **Evidence.** `InventoryJobOrderAllocation.java:75` → `JobOrder`, `InventoryMissionAllocation.java:75`
  → `Mission` (`@ManyToOne`); target checks `InventoryItemService.java:519,535,592,617`;
  requirement checks through `JobOrderItemService.requiredMaterialIds/requiredGameItemIds`
  (`InventoryItemService.java:915,934`); inventory builds job-order DTOs
  (`InventoryAggregationService → JobOrderItemStockEntryDto/GroupDto`) and its repository returns
  `JobOrderMaterialStockRow/JobOrderGameItemStockRow`; `InventoryCheckoutService.createSaleFinanceEntries`
  writes `MissionFinanceEntry` (`InventoryCheckoutService.java:445-455`). Backlog: inventory→joborder
  17, inventory→mission 13 edges.
- **Impact.** inventory ⇄ joborder is the heaviest two-way pair (17/38 edges).
- **Proposed change.** Earmark target by `(targetKind, targetId)`; an `EarmarkTargetPolicy` SPI in
  inventory (exists? label? required materials/items?) implemented by joborder and mission; job-order
  stock panels assembled in joborder from neutral inventory `StockSlice` queries; sale finance entry
  through a `StockSoldForTarget` observer implemented by mission (synchronous, same transaction).
- **Pros/cons.** Inventory becomes a leaf below mission and joborder; costs one SPI and a DTO
  relocation.
- **Risks and guard (security).** Order-linked stock crosses org units *inside the order*
  (`findByJobOrderIdOrdered` deliberately ungated; `JobOrderInventoryOwnerRedactor`, REQ-ORDERS-029);
  that query must stay callable only from the joborder module behind its gate. Guard: ArchUnit
  "the ungated earmark query is called only from `joborder`", existing redaction tests. Concurrency:
  allocation writes keep `FORCE_INCREMENT` + version echo inside inventory.
- **Effort.** M–L. **Prerequisites.** DOM-05.

### DOM-07 — Mission's cycles with operation, refinery and hangar

- **Evidence.** Associations: `Mission.operation` (`Mission.java:245`), inverse `Operation.missions`
  (`Operation.java:67`), inverse `Mission.refineryOrders` (`Mission.java:239`), `RefineryOrder.mission`
  (`RefineryOrder.java:71`), `MissionUnit.ship` (`MissionUnit.java:67`). Foreign writes:
  `OperationService.deleteOperation` sets `mission.setOperation(null)` (`OperationService.java:269-272`);
  `HangarService.detachFromMissionUnits` sets `MissionUnit.ship = null` and saves
  (`HangarService.java:458-462`); `JobTypeService` bulk-clears participants' lead flags
  (`JobTypeService.java:214,226` → `MissionParticipantRepository.java:85-89`). mission→operation 9
  edges (`MissionMapper → OperationMapper/OperationDto`, `MissionService → OperationRepository`),
  mission→refinery 5 (`MissionFinanceEntryService → RefineryOrderRepository`,
  `MissionFinanceSummaryDto → RefineryOrderDto`).
- **Proposed change.** Direction operation → mission, refinery → mission, mission → hangar. Mission
  keeps `operation_id` as a UUID and gets the operation summary through an
  `OperationSummaryProvider` SPI; the refinery part of the mission finance summary comes from a
  `MissionFinanceContributor` SPI implemented by refinery (drop the inverse collection); ship deletion
  and job-type designation become synchronous observer calls handled inside mission; operation
  deletion calls a mission command `detachFromOperation(operationId)`.
- **Risks and guard (concurrency, security).** `Mission` is `@DynamicUpdate` with every scalar and
  association `@OptimisticLock(excluded = true)` and per-section counters (backend/CLAUDE.md); today
  none of these foreign writes bumps a section counter — keep that verbatim or decide it in an ADR.
  `MissionPeerRedactor` reconstructs `MissionDto` field by field and redacts the embedded
  `OperationDto`/`ShipDto` today; provider-supplied summaries must pass through it
  (`peerReadableMissionEndpointsMustRedactPii`, `ArchitectureTest.java:1129`, whose DTO names are FQCN
  strings, `:1172-1174`). Guards: mission concurrency suites, `LazyToOneReadPathsTest` statement
  counts, E2E.
- **Effort.** M–L. **Prerequisites.** DOM-12 (class literals), REQ-MISSION/REQ-ORG-018 unaffected.

### DOM-08 — Cross-domain write paths: most need the caller's transaction

- **Evidence.** `10-backend-domains-writes.csv` (§5.8): 363 sites, of which 205 are
  `AuditService.record` (`@Transactional(propagation = MANDATORY)`, `AuditService.java:80`) and 149
  others in 57 caller methods (62 foreign service writes, 43 foreign repository writes, 37 foreign
  entity mutations, 7 foreign lock acquisitions). Cross-domain MANDATORY targets:
  `MaterialExchangeOfferRatchet.lower/beforeDelete/beforeWipe/beforeUserPurge` (16 sites: inventory
  9, joborder 6, identity 1; exchange consumes its `Effects` through `bookOutForClient`),
  `OrgChartService.mirror*` (9 methods, 12 sites from
  orgunit), `InventoryOrgUnitReconciler.onUserGained/LostOrgUnit` (4), `InventoryCheckoutService.
  mergeStockIfRequested/bookOutForClient` (3), `BankAuditService.record` (1). The event bus carries
  only two cross-domain reactions (`NotificationEventListener.java:56-57` `@Async` +
  `AFTER_COMMIT`; `ExchangeDepartureService.java:114`).
- **Classification of the 16 families** (§5.8): *synchronous, same transaction required* —
  audit; joborder/refinery/exchange → inventory (locks, append-only, merge); inventory/joborder/
  identity/exchange → offer ratchet (audits every lowered offer; returns `Effects` that exchange
  reports); orgunit → orgchart mirror (REQ-ROLE-006, "without a second step"); orgunit → inventory
  re-stamp (visibility); orgunit → bank responsibility audit; inventory → mission sale entries;
  hangar → mission detach (FK); catalogue → mission designation; operation → mission detach;
  identity(privacy) → all (FK order); exchange → hangar/blueprint (journal and change feed in the same
  write). *After-commit sufficient* — identity → blueprint default grant (self-healed by
  `DefaultBlueprintProvisioningTask`), identity → bank holder reconciliation (nightly), exchange
  departure (already), notification fan-out (already).
- **Proposed change.** Three mechanisms, chosen per family: (a) a **module command API** when the
  caller uses the callee's capability (joborder/refinery/exchange → inventory, operation → mission,
  exchange → hangar/blueprint); (b) an **observer SPI owned by the lower module**, implemented by the
  upper one and called synchronously (`StockChangeObserver` for the ratchet, `MembershipChangeObserver`
  for orgchart/inventory/bank, ship-deleted, job-type-designated, stock-sold); (c) **after-commit
  events** only for the four families above. Plain Spring `@EventListener` would also run in the
  publisher's transaction, but an SPI states order and return values explicitly.
- **Pros.** Compile-time direction inverted while atomicity, audit and locks stay as they are.
- **Cons.** More interfaces; the order of observers must be specified (the ratchet runs *before* the
  delete it reacts to).
- **Risks and guard.** An observer wired as `@TransactionalEventListener` (default phase
  `AFTER_COMMIT`) silently moves a hook after commit — a chart seat or an offer that no longer
  matches the stock. Guard: ArchUnit "implementations of observer SPIs are
  `@Transactional(propagation = MANDATORY)`" and "no `@TransactionalEventListener` on in-transaction
  event types"; existing `MaterialExchangeOfferRatchetTest`, org-chart mirror tests.
- **Effort.** M per family. **Prerequisites.** DOM-01 gate; one ADR on "module interaction styles".

### DOM-09 — JPA associations across domains: keep the foundation ones, convert eight

- **Evidence.** `10-backend-domains-jpa.csv` (§5.6): 185 associations; 74 cross a domain (69 `@ManyToOne`,
  2 inverse `@OneToMany`, 3 `@ManyToMany`); targets `User` 24, orgunit 18 (`OrgUnit` 13, `Squadron`
  4, `KommandoGroup` 1), catalogue 24, business entities 8; **no cross-domain cascade**; all 147
  `@ManyToOne` annotations declare `FetchType.LAZY`, and one cross-domain **EAGER** `@ManyToMany`
  remains, `MissionParticipant.orgUnits → OrgUnit` (`MissionParticipant.java:86`), outside
  `toOneAssociationsAreDeclaredLazy`'s reach (to-one only).
  Eight business→business associations: `InventoryJobOrderAllocation.jobOrder`,
  `InventoryMissionAllocation.mission`, `MaterialExchangeOffer.inventoryItem`, `MissionUnit.ship`,
  `Mission.operation`, `Mission.refineryOrders` (inverse), `Operation.missions` (inverse),
  `RefineryOrder.mission`. `org_unit.grand_admiral_user_id` is already a plain id column
  (`UserAccountMergeService.java:99` moves it by column; no association in the entity scan).
- **Proposed change.** Keep associations into `identity`, `orgunit`, `catalogue` for now — they point
  down the target layering and carry the scope JPQL (`m.owningOrgUnit …`) and the fetch graphs.
  Convert the eight business→business associations to id columns with owner-side lookups, as each
  pair is decoupled (DOM-06, DOM-07). Make `MissionParticipant.orgUnits` LAZY with an entity graph or
  record why it must stay eager.
- **Risks and guard.** JPQL navigation and fetch graphs change (`Mission.findById` fetches
  participants' users; `MissionRepository.fetchAssignedUnitGraph`); N+1 risk. Guards:
  `LazyToOneReadPathsTest`, `UserMappingNoNPlusOneTest`, the statement-count assertions, E2E.
  Security: none directly; scope fragments keep their associations.
- **Effort.** M (the eight) — converting all 74 would be XL and is not recommended.
  **Prerequisites.** DOM-06/07.

### DOM-10 — Hubs: which to publish, which to split

- **Evidence.** §5.7 (top 30 by number of other domains depending on the class). Highlights: `User`
  (16 domains, 93 classes), `AuthHelperService` (16), `AuditDetails` (15), `AuditEvent`,
  `AuditEventType`, `AuditService` (14 each), `UserRepository` (13, 502 LOC), `OwnerScopeService`
  (13), `OrgUnit` (12), `OrgUnitMembershipRepository` (9), `UserMapper` (7, 298 LOC),
  `InventoryItemRepository` (6, 1188 LOC). Infrastructure/kernel hubs: `AbstractEntity`,
  `OptimisticLock` (19 each), `MetricNames` (8 domains, 1015 LOC, 182 constants).
- **Proposed change.** *Publish as module API:* `AuthHelperService`, `AuditService`, `AuditDetails`,
  `AuditEventType` (202 values across 12 `AuditDomain`s — keep it central as audit's published
  vocabulary, the unified viewer's filter and i18n labels need the closed set), `ScopePredicate`,
  `OrgUnitMembershipQueryService`, reference DTOs. *Hide:* `AuditEvent` (14 domains depend on it only
  because `record()` returns the entity — return nothing or the id). *Split:* `UserRepository` behind
  an identity `UserDirectory` query API; `UserMapper` into a reference mapper (identity) and the
  Staffel-enriched view (orgunit/app); `OwnerScopeService`/`AccessGateService` (DOM-03);
  `InventoryItemRepository` behind inventory commands/queries (DOM-05); `MetricNames` per module.
- **Risks and guard.** Metric renames break dashboards and alerts → constants move, values stay
  byte-identical (REQ-OBS-011); a test comparing the union of per-module constants with today's set.
- **Effort.** M overall. **Prerequisites.** None.

### DOM-11 — Coupling that bytecode analysis cannot see

- **Evidence.** (1) SpEL: 165 bean references in `@PreAuthorize` strings (DOM-03). (2) `Roles.*`
  constants in 247 endpoints' `@PreAuthorize` — compile-time constants inlined, so jdeps shows no
  controller→`Roles` edge. (3) `ScopeSpecifications` is an isolated node in jdeps although seven
  repositories concatenate its fragments (`10-backend-domains-sqlrefs.out.txt`). (4) Native SQL table
  lists (DOM-04). (5) Database triggers: `V252__create_exchange_change_feed.sql:67,88,126,144` install
  the exchange change-feed triggers on `personal_blueprint`, `default_blueprint`, `inventory_item`,
  `ship` — tables of blueprint, inventory and hangar owned by an exchange migration. (6) Path-keyed
  security lists: 30 `requestMatchers` in `SecurityConfig`, 14 families in
  `NoStoreApiScopes.java:42-55`, the import path in `RequestBodyLimitProperties.java:42`.
- **Impact.** Any module gate built on bytecode alone (ArchUnit, Spring Modulith, Gradle) certifies a
  boundary that SpEL, SQL, triggers and path lists still cross.
- **Proposed change.** Complementary guards: the SpEL resolution test (DOM-03); a table-ownership
  registry test (every table in `information_schema` owned by exactly one module; native SQL and
  triggers may touch a foreign table only through a listed, reviewed exception such as V252); keep
  **one** Flyway location and a linear history (257 migrations) — per-module migration folders would
  break ordering and checksums.
- **Effort.** S–M. **Prerequisites.** None.

### DOM-12 — Option A traps in today's gates and language rules

- **Evidence.** (1) 44 ArchUnit selectors of the form `"..backend.<layer>.."`
  (`grep -c '"\.\.backend\.' ArchitectureTest.java`) stop matching once packages become
  `…backend.<module>.<layer>`. (2) 36 FQCN string literals in `ArchitectureTest`; six invariants test
  a *should*-side FQCN or a predicate over FQCNs and therefore stop matching after a move without
  failing: `controllersMustNotInjectTheLazyMembershipMapper` (`:305`, ADR-0067),
  `bankClassesMustNotConsultOrgUnitScope` (`:1800`, REQ-BANK-008),
  `cascadeServiceMustNotConsultTheSecurityContext` (`:1818`, REQ-ORG-015),
  `delegatedRoleAuthoriserMustNotConsultOwnerScope` (`:1839`, REQ-ROLE-004),
  `orgUnitAwareBankSeamIsContainedToOneClass` (`:1854-1856`, ADR-0020) and the delete-call half of
  `bankLedgerRepositoriesMustStayInsertOnly` (`:1895-1930`, REQ-BANK-004). Whether an emptied `that()`
  would at least fail depends on ArchUnit's `failOnEmptyShould` default — the repo has no
  `archunit.properties`, so the library default applies; **UNKNOWN** here, settle by reading the
  ArchUnit 1.5.1 user guide. (3) The cycle rule would slice on modules and fail immediately (DOM-01).
  (4) `AppException` is `abstract sealed … permits` 13 subclasses (`AppException.java:37-50`); in an
  unnamed module every permitted subclass must be in the same package (JEP 409), so module-owned
  exceptions (`BankConflictException`, `ExchangeProblemException`, `OverAllocationException`,
  `ProductionAllocationException`, `MissionParticipantRequiredException`,
  `OwnerOrgUnitRequiredException`) cannot move into their modules as long as the base stays sealed.
  (5) Positive: all 1389 simple names are unique, so Spring bean names (simple-name based) and
  springdoc schema names — hence `openapi.json` and the frontend's `FrontendDtoContractTest` /
  `GeneratedDtoAgreementTest`, which key on schema names — survive package moves unchanged.
  (6) `ArchitectureTest` has 43 `@Test` methods (last changed `94f9236a4`, 2026-09-27); the vault's
  `10 Systems/Backend.md` still says 38 — vault drift to correct.
- **Proposed change.** A preparatory PR before any move: replace every FQCN string with a class
  literal (`OwnerScopeService.class.getName()` or `dependOnClassesThat().belongToAnyOf(...)`), add a
  meta-test asserting that any remaining FQCN string resolves with `Class.forName`, re-key layer
  selectors to `"..controller.."`-style patterns (layer *inside* a module), split the cycle rule into
  "layer cycles per module" and "module cycles (frozen)", keep the `AppException` family in the shared
  kernel's exception package (the kinds stay a closed set) — or, by ADR, add one `non-sealed`
  domain-exception base. Add a rule "simple names unique" so bean and schema names stay stable.
- **Pros.** Every later move is mechanical and fails loudly. **Cons.** One PR of test churn.
- **Risks and guard.** Security: this PR *is* the guard against silently disabled invariants.
- **Effort.** S. **Prerequisites.** None — do it first.

### DOM-13 — Exchange is the best first Gradle extraction, after two fixes

- **Evidence.** exchange: 169 classes, 19,185 LOC, 35 endpoints; Ce 197 edges → 13 modules, Ca only
  **11** edges ← 6 modules (§5.1): row projections typed as exchange DTOs inside other modules'
  repositories (`BlueprintRepository → ExchangeBlueprintKeyRow`, `GameItemRepository →
  ExchangeItemKeyRow`, `LocationRepository → ExchangeLocationRow`, `ShipRepository → ExchangeShipRow`,
  `InventoryItemRepository → ExchangeStockLotRow`), `PersonalBlueprintService →
  ExchangeClientRepository/ExchangeClientDisplayName`, `BlueprintUploadPreviewService →
  ExchangeDraftService`, `IngestGatewayProperties` read by `CustomJwtGrantedAuthoritiesConverter` and
  `UserDeletionService`. arc42 §5.5 (`docs/arc42/05-building-block-view.md:142`) says the write
  services "write through the domain's own services" — true for blueprints
  (`ExchangeBlueprintWriteService → PersonalBlueprintService.add/delete`) and ships
  (`ExchangeShipWriteService → HangarService`), **not for stock** (DOM-05). Four exchange ArchUnit
  rules are package-keyed (`ArchitectureTest.java:475-575`). `ChangeSourceTransactionManager` is the application-wide JPA
  transaction manager (`TransactionManagerConfig`).
- **Proposed change.** Move the five row records into their owners' API packages; invert the blueprint
  edges (blueprint API returns the client id, the display name is composed by exchange or the web);
  route stock book-in through `StockCommands` (DOM-05); then a `backend-exchange` Gradle module that
  depends only on module APIs. The transaction manager stays in `app`/platform (it serves every
  write).
- **Risks and guard (security).** `@exchangeGate` on every method, `ActingMemberFilter`, capability
  checks, revocations unchanged; the external contract must stay byte-identical — guards:
  `IngestEndpointSurfaceTest`, the exchange contract/schema tests in ingest, the backend exchange
  ArchUnit rules re-keyed (never dropped), the ADR-0229 concurrency tests.
- **Effort.** M–L. **Prerequisites.** DOM-05, DOM-12.

### DOM-14 — Bank is the second extraction candidate; its seam rules are name-keyed

- **Evidence.** bank: 129 classes, 17,667 LOC, 65 endpoints, 11 entities, own audit trail
  (`BankAuditService`, 39 `BankAuditEventType` values), own security bean (10 endpoints
  `@bankSecurityService`). Ca only 18 edges: identity 9 (`HandleAnonymisationService` 7,
  `UserDeletionService → OrgUnitBankResponsibilityService` 1, `UserSyncService →
  BankHolderReconciliationService` 1), notification 4
  (`RecipientResolutionService.java:93,109`), livesync 2 (`LiveSyncSubscriptionAuthorizer.java:115`),
  audit 2 (`AuditRetentionService`), orgunit 1 (`OrgUnitMembershipService.java:101,158` via
  `ObjectProvider`). All outbound edges point down (orgunit 37, identity 27, access 16, notification
  16, audit 7, admin 1). ADR-0020/0028 rules keyed on names/FQCNs (`ArchitectureTest.java:1763-1930`).
- **Proposed change.** Invert the 18 inbound edges (recipient-selector SPI, livesync topic authorizer,
  retention participant, GDPR participant, membership observer), then package + Gradle module.
  `OrgUnitBankAccessService` stays the single seam (ADR-0020); express the seam rules against the
  scope API, not FQCN strings.
- **Risks and guard (security).** The org-unit-blind invariant (REQ-BANK-008) and the one-class seam
  must survive — guards: the re-keyed ArchUnit rules, `BankControllerSecurityTest`,
  `OrgUnitBankAccessServiceTest`, ledger integrity task tests; insert-only ledger rule re-keyed.
- **Effort.** L. **Prerequisites.** DOM-04, DOM-12, DOM-16; ADR-0020 wording ("one class") unchanged.

### DOM-15 — Identity core points upward; its REST surface hosts other modules' endpoints

- **Evidence.** identity→orgunit 21 edges: `UserMapper` derives Staffel data from memberships
  (`OrgUnitMembershipRepository`, `StaffelMembershipResolver`, `Squadron`), `UserController` and
  `MeController` call `OrgUnitMembershipQueryService`; `User.defaultPayoutPreference` is typed with
  mission's `PayoutPreference` (`User.java:115`); `UserDtoRedaction → JobOrderAssigneeDto`;
  `UserReconciliationService.java:219` grants default blueprints inside `syncUser`, which runs on the
  authentication path (`CustomJwtGrantedAuthoritiesConverter.java:276`); `UserSyncService.java:111 →
  BankHolderReconciliationService`. Endpoints: of the 33 `/api/v1/users/**` endpoints, six are
  memberships (`/users/{id}/memberships`, `…/detail`, `/users/me/memberships`,
  `/users/me/org-unit-ids`, `/users/me/pickable-org-units`, `PATCH /users/{id}/memberships`), two are
  the bank's counterparty search
  (`/users/search-bank`, `…/references`), one is the dashboard's read marker
  (`/users/me/read-announcement/{id}`), two the blueprint-sharing preference; `/api/v1/me/layout`
  and `/me/capabilities` are composite reads for the web shell (`MeController.java:104,171`).
- **Proposed change.** Identity publishes `UserDirectory` (plain lookups, batch display names,
  `UserReference`); the Staffel-enriched user view moves to orgunit (or `app`); `PayoutPreference`
  moves to identity (mission and operation then depend down); `UserDtoRedaction`'s job-order part
  moves to joborder; the default grant becomes an after-commit `UserRegistered` event (self-heal task
  covers failures, and a write leaves the login path); REST ownership as in §3.
- **Risks and guard (security).** Every moved `/users/**` or `/me/**` path must keep its
  `NoStoreApiScopes` family (`NoStoreApiScopes.java:44-45`) and its `SecurityConfig` matcher
  (12 of the path strings in `SecurityConfig`'s 30 `requestMatchers` calls start with
  `/api/v1/users`), or a sensitive body becomes cacheable or falls to the default rule; capability flags keep REQ-SEC-030 semantics. Guard: a test that every controller path
  is covered by a no-store family or explicitly listed as cacheable; `ApiVhostAnonymousSurfaceTest`.
  Android: old paths as deprecated aliases (`annotation/ApiDeprecation`, `DeprecationInterceptor`,
  Sunset headers) for installed app versions (ADR-0136).
- **Effort.** M. **Prerequisites.** DOM-12; the REST re-cut agent's plan.

### DOM-16 — Platform services and platform code depend upward (21 backlog edges)

- **Evidence.** `AuditService.java:63,89` reads `UserRepository` only to snapshot the actor's display
  name; `AuditRetentionService → BankAuditService/BankAuditEventRepository`;
  `RecipientResolutionService.java:93,109` → bank grants and responsible holders, `→ UserRepository`,
  `→ OrgUnitMembershipRepository`; `NotificationRuleService → Role/RoleRepository`;
  `LiveSyncSubscriptionAuthorizer.java:92-96,115` → scope gates and `OrgUnitBankAccessService`;
  `CorrelationIdFilter.java:79 → OwnerScopeService` (MDC `orgUnitId`);
  `AuthHelperService.java:188-231` delegates four gates to `OwnerScopeService` via `getBean` "to avoid
  a bean cycle" — the same cycle papered over by `ObjectProvider<OrgUnitBankResponsibilityService>` in
  `OrgUnitMembershipService` and `UserDeletionService`.
- **Proposed change.** SPIs owned by the platform module: `ActorHandleResolver` (identity),
  `RetentionParticipant` (bank), `RecipientSelectorProvider` (bank, identity, orgunit),
  `LiveSyncTopicAuthorizer` (per module), `ActiveOrgUnitProvider` (scope → logging MDC); delete
  `AuthHelperService`'s four delegations (callers use the scope API directly); keep
  `NotificationEvent` as notification's published contract (14 implementations in 5 domains already
  depend down on it).
- **Risks and guard (security).** Recipient resolution decides who is told about a bank booking
  (least privilege) and room authorization must equal the read gate — implementations reuse the
  module's policy bean; guards: live-sync authorization tests, notification rule tests, a rule
  "platform modules depend only on platform and kernel".
- **Effort.** M. **Prerequisites.** None — early, cheap, breaks many cycles.

### DOM-17 — Catalogue is almost a clean foundation

- **Evidence.** catalogue: 263 classes, 28,952 LOC, 90 endpoints, 36 entities; Ca 337 edges from 10
  modules, Ce only 15: audit 7 (fine), exchange rows 3 (DOM-13), `ShipTypeController →
  ShipMapper.shipTypeToDto` (`ShipTypeController.java:59,76,87,101` — the ship-type mapping lives in
  hangar's mapper), `LocationService` in-use checks through `ShipRepository.existsByLocationId` and
  `RefineryOrderRepository.existsByLocationId` (`LocationService.java:193,197`), `JobTypeService →
  MissionParticipantRepository` (DOM-07), `MaterialExternalAliasService → AuthHelperService`
  (platform; fine).
- **Proposed change.** A catalogue `ShipTypeMapper`; in-use checks via the FK violation →
  `EntityInUseException` or a `ReferenceProbe` SPI; designation as an observer. Sub-packages per
  area now; sub-modules only if the import engine (`P4kImportService` 1531 LOC, UEX/SC Wiki syncs)
  needs its own build unit.
- **Effort.** S–M. **Prerequisites.** None.

### DOM-18 — Size hotspots coincide with the coupling hubs; the existing facades are ready-made APIs

- **Evidence.** 36 classes over 600 LOC (§5.9): inventory 5, catalogue 5, bank 4, joborder 3,
  mission 3, access 3, exchange 3, identity 2, infrastructure 2, refinery 2, audit/materialexchange/
  orgchart/orgunit 1 each. Most public methods: `OwnerScopeService` 56, `MissionService` 47,
  `OrgUnitBankAccessService` 31 (1753 LOC, ADR-0020-locked), `InventoryItemService` 29,
  `AccessGateService` 28; most dependencies: `UserDeletionService` 21, `ExchangeStockWriteService` 19,
  `ExchangeUndoService` 17, `OrgUnitBankAccessService` 16.
- **Proposed change.** Treat the delegating facades from ADR-0061/0062/0063/0065 (`MissionService`,
  `JobOrderService`, `InventoryItemService`, `OwnerScopeService`) as the modules' command APIs and
  make their sub-services package-private after the move; do not split further before the move.
- **Effort.** — (part of the moves). **Prerequisites.** DOM-01.

### DOM-19 — REST ownership by responsibility (owner update)

- **Evidence.** 574 endpoints in 99 controllers, 55 path families (`10-backend-domains-endpoints.csv`);
  every endpoint has a method- or class-level `@PreAuthorize`. Endpoints whose body mainly serves
  another module: 9 (§5.10); 41 bodies call two or more modules. `/api/v1/admin/**` holds 51
  endpoints of five modules (identity 18, exchange 14, blueprint 11, catalogue 4, personalinventory 4).
  Path-keyed consumers: `SecurityConfig` (30 matchers), `NoStoreApiScopes` (14 families),
  `RequestBodyLimitProperties`, the edge's `api.*` allow-list (ADR-0135), blackbox probes, E2E,
  `openapi.json`, the frontend's `BackendApiClient`, the Android app (ADR-0136), path-tagged metrics.
- **Proposed change.** Record ownership per module (§3); re-cut only where a path hides its owner
  (memberships under `/users`, bank search under `/users`, the announcement read marker, the web-shell
  composites under `/me`); decide once whether admin endpoints move under `/api/v1/<module>/admin/**`
  or keep the `/admin` prefix per module; old paths live on as `@ApiDeprecation` aliases for installed
  Android versions. The 14 `/api/v1/exchange/**` relay endpoints stay byte-identical.
- **Risks and guard (security).** A moved path that loses its no-store family or its `SecurityConfig`
  matcher weakens caching or authorization; the edge deny rules must follow. Guards as in DOM-15,
  plus e2e and blackbox updates in the same PR.
- **Effort.** M (for this module's part). **Prerequisites.** The REST re-cut design (separate agent).

### DOM-20 — Options A/B/C/D against the data; recommended cut, order and moves

| Criterion | A — packages in `backend` | B — Gradle project per module | C — A, then Gradle for proven modules | D — services |
| --- | --- | --- | --- | --- |
| Feasible from today's graph | yes, with a frozen baseline (DOM-01) | **no** — Gradle forbids cyclic project dependencies; 21-node SCC | yes | no |
| Boundary enforcement | ArchUnit `modules()` + freeze, test time | compiler | test time, then compiler where proven | network |
| New dependencies | none (ArchUnit 1.5.1 in catalog) | none external; 25–50 projects (one, or an api/impl pair, per module) × Spotless/Checkstyle/SpotBugs/JaCoCo/CycloneDX, configuration cache | as A, later as B for 2+ modules | images, units, networks (18 today) |
| Transaction semantics | unchanged | unchanged | unchanged | **broken**: 205 in-tx audit writes, 12 synchronous families, row/advisory locks across modules |
| Security surface | unchanged at runtime; re-keying risks (DOM-12) | as A | as A | + service auth, secrets, segments |
| Reversibility | high | low | medium | very low |

- **Recommendation: C.** Modules as in DOM-02; shared kernel = `AbstractEntity`, `PageResponse`, the
  `AppException` family, `Entities`, `OptimisticLock`, `StringNormalization`, `LikePatterns`,
  `DtoConstraints`/`OnUpdate`/`WholeNumber`, `HandleAnonymisation`, plus two new value types: a
  `UserRef`/`OrgUnitRef` pair (id + label snapshot) and the SCU amount rounding now reached through
  `InventoryItem.roundToScuScale` by five modules (`InventoryItem.java:181`; joborder entities,
  materialexchange, refinery, exchange). Like `logging-support` (ADR-0205) it must stay free of domain
  meaning.
- **Order (leaves first; the inbound edges to invert before each move in brackets).**
  *Phase 0* guards: DOM-12 PR, SpEL resolution test, domain-map module rule + freeze, table-ownership
  test. *Phase 1* cheap inversions and re-homings: leadership→orgunit, `PayoutPreference`,
  `HandleAnonymisation`, exchange row records, `ShipTypeMapper`, `AuthHelperService` delegations,
  `AuditService.record` return type, platform-service SPIs (DOM-16). *Phase 2* top leaves: dashboard
  [0], admin [4], promotion [1], personalinventory [1], orgchart [orgunit mirror], operation [mission
  9, access 2], **exchange** [11] → Gradle, **bank** [18] → Gradle. *Phase 3* the core:
  materialexchange [ratchet], refinery [23], joborder [39], mission [56], inventory [70] with
  `StockCommands` and the earmark SPI, hangar [29], blueprint [37]; each move takes its access policy
  out of `AccessGateService` (DOM-03). *Phase 4* foundation: scope kernel, orgunit, identity with the
  GDPR SPIs (DOM-04), catalogue. *Phase 5* further Gradle modules where the rule has been green for
  some releases.
- **Moves and their guards** are in DOM-03…DOM-17; the programme-wide guards: module rule + freeze
  (G1), "every class mapped" (G2), class literals + FQCN meta-test (G3), SpEL resolution (G4),
  table ownership (G5), GDPR union coverage (G6), "only the owner writes its aggregate" (G7), observer
  SPIs MANDATORY (G8), audit contract per command (G9), path coverage for no-store and security
  matchers (G10), and the existing concurrency, E2E, statement-count and access-control suites (G11).
- **Spring Modulith / jMolecules.** Not needed for enforcement (ArchUnit already has module rules).
  Modulith's other features (event publication registry, module-sliced tests, generated docs) would
  add a dependency plus verification metadata; their compatibility with Boot 4.1.1 is **UNKNOWN** in
  this audit (no web access) — settle with the Spring Modulith release notes before Phase 3.
- **Effort.** XL overall, as ~25–35 PRs of S–L each. **Prerequisites.** Owner decision on the target
  layering and the ADRs named above.

### DOM-21 — The previous audits under today's goal

- **July 2026 modularity audit (#1256).** Its diagnosis — "the architecture is sound; the debt is size
  and duplication inside correct layers" — holds for layers (ADR-0047's gate is green) but not for
  domains (DOM-01). Its list of rejected simplifications stays binding and shapes the module APIs:
  Mission section counters (DOM-07), `…WithinTransaction` MANDATORY hops (DOM-08), `REQUIRES_NEW`
  self-proxy retries (untouched: all 12 `ObjectProvider<Self>` call back into their own class), bulk update
  after the loop (DOM-05), `BackendApiClient` as the single frontend seam (frontend scope), no generic
  CRUD template (audit completeness). Its "Thema 7" facade splits are the seeds of module APIs
  (DOM-18).
- **September 2026 audit (176 findings).** No finding addresses domain modularity; APPSEC-01 (fixed
  #1989) is evidence for DOM-05; BE-PERF-11 (all to-one LAZY, #2030) removes an obstacle to id
  references (DOM-09); XMOD-SIMP-01 (`logging-support`, ADR-0205) is the template for a closed shared
  kernel.

### Security impact of every proposal (explicit)

Legend: **—** no effect by construction; **R** risk handled by the named guard; all proposals keep
runtime behaviour identical except where a finding says otherwise.

| Proposal | `@PreAuthorize` / SpEL / ArchUnit gates | Tenancy scoping (`OwnerScopeService`, stamping) | Redaction (`MissionPeerRedactor`, owner redaction) | Audit completeness (REQ-AUDIT-001) | CSRF / CSP | Session allow-list (ADR-0206) | Secrets | Input validation | Rate / size limits, no-store cache headers |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| DOM-01 module gate + freeze | — (adds a gate) | — | — | — | — | — | — | — | — |
| DOM-02 re-homings | R: SpEL bean names unchanged because simple names stay (G4) | — | — | — | — | — | — | — | — |
| DOM-03 gate split | **R**: 66 SpEL refs → SpEL resolution test (G4), re-keyed gate rules | **R**: verbatim bodies, list+gate co-located, existing access suites | — | — | — | — | — | — | — |
| DOM-04 GDPR SPIs | — | — | R: scrubbers (`HandleScrubber`) move per module, `DataExportScrubCoverageTest` | **R**: deletion/anonymisation audit rows stay in the orchestrator | — | — | — | — | — |
| DOM-05 `StockCommands` | R: owner/scope check inside each command (APPSEC-01 test shape) | **R**: stamping inside the command | R: `JobOrderInventoryOwnerRedactor` stays in joborder | **R**: inventory events emitted once, by the owner (G9) | — | — | — | R: `ValidQuantityAmount` on the command DTOs | — |
| DOM-06 earmark SPI | — | **R**: ungated order-linked stock query callable only from joborder | R: REQ-ORDERS-029 redaction tests | R: allocation audit unchanged | — | — | — | — | — |
| DOM-07 mission inversions | — | — | **R**: provider summaries pass through `MissionPeerRedactor` (FQCN rule → class literals) | R: link changes keep their events | — | — | — | — | — |
| DOM-08 observer SPIs | — | R: re-stamp stays in-transaction (visibility) | — | **R**: ratchet/mirror audits stay in-transaction (G8) | — | — | — | — | — |
| DOM-09 id references | — | R: scope JPQL keeps orgunit associations | — | — | — | — | — | — | — |
| DOM-10 hub splits | R: `UserDirectory` must not widen what `/users/search*` and `/users/lookup` return (their `SecurityConfig` matchers and service checks stay) | — | R: `UserDtoRedaction` peer projection unchanged | R: `record()` return type only | — | — | — | — | — |
| DOM-11 hidden-coupling guards | — (adds guards) | — | — | — | — | — | — | — | — |
| DOM-12 test preparation | — (restores gate strength) | — | — | — | — | — | — | — | — |
| DOM-13 exchange module | **R**: `@exchangeGate` rule re-keyed, never dropped | R: acting-member scope unchanged | — | R: journal + audit in the same write | — | — | R: `IngestGatewayProperties` stay app config | R: frozen contract schemas | R: ingest quotas unchanged |
| DOM-14 bank module | **R**: ADR-0020 seam + REQ-BANK-008 rules re-keyed | **R**: bank stays org-unit-blind | — | R: `bank_audit_event` trail unchanged | — | — | — | — | — |
| DOM-15 identity + REST ownership | R: `SecurityConfig` matchers move with paths (G10) | — | R: REQ-SEC-030 capability flags | — | **R**: new paths must stay under the CSRF-exempt `/api/v1/**` (`SecurityConfig.java:111`) | — (backend is stateless; the frontend's allow-list is untouched by backend moves) | R: credential-redacting properties move unchanged | — | R: `RequestBodyLimitProperties` path list follows a re-cut |
| DOM-16 platform SPIs | R: livesync room authorization reuses the module's read gate | R: recipient selection least-privilege | — | R: actor handle snapshot unchanged | — | — | — | — | — |
| DOM-17 catalogue fixes | — | — | — | — | — | — | — | — | — |
| DOM-19 REST re-cut | **R**: `SecurityConfig` matchers, edge allow-list (ADR-0135) | — | — | — | **R**: CSRF exemption is path-keyed | — | — | — | **R**: `NoStoreApiScopes` families (G10), body-size paths |

---

## 3. Module cards

Legend — *In*: modules that depend on it (class edges) and through which classes; *Out*: its own
dependencies; *API*: the minimal published surface proposed; *Endpoints*: by responsibility,
including ones whose path sits elsewhere today; *Difficulty*: of making it a gated module.

**shared-kernel** — 20 classes. *In*: 21 modules (`AbstractEntity`, `OptimisticLock`, `Entities`,
`BadRequestException`, `PageResponse`, `NotFoundException`, `StringNormalization`). *Out*: none except
the sealed `AppException` naming six module exceptions (DOM-12). *API*: everything. *Difficulty*: low.

**platform (infrastructure + access-core)** — 67 + 8 classes. *In*: 20 modules (`PaginationUtil` 15,
`MetricNames` 8, `CurrentUserId`, `TaskMetrics`, `AuthHelperService` 16, `Roles` 5). *Out*: to be none;
today `CorrelationIdFilter → OwnerScopeService`, `ClientAttribution → IngestGatewayProperties/KnownExchangeClients`,
`ApiClientMetricsFilter → ActingMemberHeader`, `AuthHelperService → OwnerScopeService` (getBean).
*API*: web helpers, error handling, metric names (split per module), `AuthHelperService`,
`AuthenticatedSubject`, `Roles`, `Permissions`. *Difficulty*: low–medium (MetricNames split).

**app** — `BackendApplication`, `SecurityConfig`, `DataInitializer`, `BusinessMetricsCollector`.
Composition root; depends on everything, nothing depends on it. Queue gauges could move into modules as
`MeterBinder`s. *Difficulty*: low.

**audit** — `AuditEvent`; 15 classes, 2,377 LOC, 4 endpoints (`AuditAdminController`). *In*: 14
modules through `AuditService.record` (205 sites), `AuditDetails`, `AuditEventType`, `AuditEvent`
(return type only). *Out*: identity (actor name), bank (retention), access-core. *API*:
`AuditService.record` (MANDATORY, returns nothing), `AuditDetails`, `AuditEventType`, `AuditDomain`,
SPIs `ActorHandleResolver`, `RetentionParticipant`. *Endpoints*: `/api/v1/audit/**`. *Difficulty*: low.

**notification** — `Notification`, `NotificationRule`, `NotificationRuleSelector`; 44 classes, 4,321 LOC,
13 endpoints. *In*: identity 27 (mail seam, events), bank 16, exchange 10, joborder 9, materialexchange 8
(event contract types). *Out*: bank 4, identity 3, orgunit 2 (recipient resolution). *API*:
`NotificationEvent`, `OrgUnitRef` (event record), `NotificationType`, `NotificationEventType`,
`NotificationContextRole`, `MailService`/`MailMessage`, `NotificationCommands.markRead(type, entity)`
(exchange uses the repository today), SPI `RecipientSelectorProvider`. *Endpoints*:
`/api/v1/notifications/**`, `/api/v1/notification-rules/**`; the unread count inside `/api/v1/me/layout`
by responsibility. *Difficulty*: low–medium.

**livesync** — no entity; 13 classes, 2,002 LOC, 2 endpoints. *In*: exchange 3 (`ExchangeLiveSync`).
*Out*: bank 2, scope 1 (subscription authorization). *API*: `LiveSyncRelayService`, `LiveSyncTopic`,
SPI `LiveSyncTopicAuthorizer`. *Difficulty*: low.

**catalogue** — 36 entities (universe, material, item, ship, recipe, reference, import); 263 classes,
28,952 LOC, 90 endpoints (19 controllers incl. `AdminP4kImportController`, `ProfitCalculationController`,
`SyncReportController`). *In*: joborder 82, inventory 55, refinery 49, blueprint 41, exchange 38,
mission 28, hangar 21, materialexchange 16, personalinventory 4, orgunit 3 — through 13 repositories
(`MaterialRepository` 6 modules, `LocationRepository` 5, `BlueprintRepository`, `GameItemRepository`,
`ShipTypeRepository`…), 17 entities, 15 DTOs, `MaterialMapper`, `QuantityTypeRounding`,
`ValidQuantityAmount`. *Out*: 15 edges (DOM-17). *API*: reference DTOs and mappers, read-only query
services per area (material, location, game item, ship type, recipe), the quantity validation contract;
entities may stay association targets (DOM-09). *Endpoints*: all current catalogue controllers;
`/api/v1/uex/…` location typeahead is personalinventory's by responsibility. *Difficulty*: medium (size;
the master-data caches and `MasterDataCacheEvictionService`; the import engine).

**identity** — `User`, `Role`, `UserApprovalEvent`, `TermsAcceptance` (`DeletionRequest` via the
privacy package); 101 classes, 14,642 LOC, 59 endpoints. *In*: 17 modules — `User` (93 classes),
`UserRepository` (35 classes in 13 modules), `UserService` (7), `UserMapper` (7), `UserReferenceDto`,
`UserDto`, `HandleAnonymisation`, `MemberDepartedEvent`. *Out*: 15 modules (DOM-04, DOM-15). *API*:
`UserDirectory` (plain lookup, batch names, current user), `UserReference`, `RoleCatalogue`, events
`MemberDepartedEvent`/`UserRegistered`, GDPR participant SPIs. *Endpoints*: `/api/v1/users/**` except
memberships (→ orgunit), bank search (→ bank), read marker (→ dashboard), blueprint sharing (→ blueprint
or kept as a profile preference); `/api/v1/me` composites → app shell; registration, terms, Discord
check, data export, deletion requests, person search, admin role/attribute edits. *Difficulty*: high
(GDPR fan-out; authentication hot path `syncUser`).

**orgunit (+ leadership)** — `OrgUnit` (+ `Squadron`, `SpecialCommand`, `Bereich`, `Organisationsleitung`),
`OrgUnitMembership`, `KommandoGroup`; 68 classes, ~7,900 LOC, 41 endpoints. *In*: joborder 52, bank 37,
access 29, identity 21, materialexchange 20, mission 18, inventory 18, orgchart 15, promotion 12 —
through `OrgUnit` (12 modules), `OrgUnitKind`, `OrgUnitMembershipRepository` (9), `OrgUnitRepository`
(8), `OrgUnitMembershipQueryService`, `SquadronReferenceDto`/`SquadronMapper`, `OrgUnitCascadeService`.
*Out*: audit, identity (`OrgUnitMembership.user`), orgchart/inventory/bank hooks (to invert).
*API*: `OrgUnitRef`, `OrgUnitKind`, `MembershipRole`, `MembershipQueries` (the query service),
`OrgUnitCascade`, reference DTOs/mappers, SPI `MembershipChangeObserver`. *Endpoints*: squadrons,
special commands, hierarchy, Kommandogruppen, Leitung, squadron ranks, plus the membership endpoints
under `/users` and the switcher options under `/me/org-units`. *Difficulty*: medium (foundation
fan-in; appointment gates REQ-ROLE-004).

**scope** — no entity; `RequestScopeResolver`, `ScopePredicate`, `OrgUnitStampingService`,
`OwnerScopeService`, `AccessGateService`, `ScopeSpecifications`, `CustomJwtGrantedAuthoritiesConverter`.
*In*: 18 modules; 66 endpoints via SpEL. *Out*: orgunit 29, identity 6, six business modules 17
(gates). *API*: `ScopePredicate`, `currentScopePredicate()` and the other scope vectors,
stamping resolvers, `OwnerOrgUnitRequiredException`, the authorities converter. *Endpoints*:
`/api/v1/me/active-org-unit`. *Difficulty*: high (security core; DOM-03).

**admin** — `SystemSetting`; 12 classes, 623 LOC, 6 endpoints (system settings, version policy, ping).
*In*: blueprint 2 (`DefaultBlueprintBootstrap` writes a marker row), bank 1, operation 1.
*Out*: none. *API*: `SystemSettingService`. *Difficulty*: low.

**dashboard** — `Announcement`; 6 classes, 364 LOC, 4 endpoints. *In/Out*: none. *Endpoints*: plus the
read marker `/users/me/read-announcement/{id}`. *Difficulty*: trivial.

**orgchart** — `OrgChartPosition`; 19 classes, 2,425 LOC, 5 endpoints. *In*: orgunit 2 (12 MANDATORY
mirror calls). *Out*: orgunit 15, identity 5. *API*: implements `MembershipChangeObserver`.
*Difficulty*: low–medium (keep the mirror in-transaction, REQ-ROLE-006).

**promotion** — `PromotionTopic`, `PromotionCategory`, `PromotionLevelContent`, `RankRequirement`,
`MemberEvaluation`; 41 classes, 4,717 LOC, 34 endpoints. *In*: identity 1 (deletion). *Out*: audit 16,
orgunit 12, access 7, identity 4. *API*: GDPR participant only. *Endpoints*: `/api/v1/promotion/**`
(incl. `/evaluations/members`, which reads identity's directory). *Difficulty*: low.

**personalinventory** — `PersonalInventoryItem`; 12 classes, 1,172 LOC, 10 endpoints (incl.
`UexLocationController`). *In*: identity 1. *Out*: audit 4, catalogue 4. *Difficulty*: low.

**hangar** — `Ship`; 20 classes, 2,578 LOC, 15 endpoints. *In*: exchange 12 (`HangarService`,
`ShipRepository.lockOwnedById`), mission 12 (`Ship`, `ShipDto`, `ShipMapper`), catalogue 2, access 2.
*Out*: catalogue 21, identity 12, audit 8, orgunit 7, access 4, mission 3 (detach), exchange 1 (row).
*API*: `ShipCommands` (add/update/delete for client, lock), `ShipReference`/summary for mission,
`ShipAccessPolicy`, ship-deleted observer SPI. *Difficulty*: medium.

**blueprint** — `PersonalBlueprint`, `DefaultBlueprint`, `BlueprintExternalAlias`; 59 classes, 6,951 LOC,
24 endpoints. *In*: exchange 29, joborder 3, identity 2, materialexchange 2, refinery 1 —
`PersonalBlueprintService`, `PersonalBlueprintRepository` (3 modules), `BlueprintProductService`,
`BlueprintFuzzyMatcher`, `BlueprintVariantFamilyResolver`, `DefaultBlueprintKeyService`, import DTOs.
*Out*: catalogue 41, audit 16, exchange 4, inventory 2 and refinery 1 (craftability). *API*:
`PersonalBlueprintCommands`, `BlueprintOwnership` queries (owners per product for joborder/exchange),
product search, import preview, a `StockSliceProvider` SPI for craftability (implemented by inventory and
refinery). *Endpoints*: current ones plus the blueprint-sharing preference. *Difficulty*: medium.

**inventory** — `InventoryItem`, `InventoryJobOrderAllocation`, `InventoryMissionAllocation`; 54 classes,
9,168 LOC, 27 endpoints. *In*: joborder 38, exchange 13, materialexchange 6, refinery 6, identity 2,
access 2, blueprint 2 — `InventoryItemRepository` (6 modules), `InventoryItem`, `InventoryCheckoutService`,
`InventoryItemService`, `AllocationReductions`, `InventoryAllocations`, `InventoryAuditLabels`.
*Out*: catalogue 55, audit 20, identity 19, orgunit 18, joborder 17, mission 13, access 8,
materialexchange 5. *API*: `StockCommands`, `PersonalStockLots`, stock queries (`StockSlice`, lots,
earmarks), `InventoryAccessPolicy`, SPIs `EarmarkTargetPolicy`, `StockChangeObserver`,
`StockSoldForTarget`. *Endpoints*: `/api/v1/inventory/**`; `/me/capabilities`' Lager flag by
responsibility. *Difficulty*: **high** (concurrency-hot, ADR-0229, four writers today).

**mission** — `Mission` and 8 children; 81 classes, 10,089 LOC, 53 endpoints. *In*: operation 22,
inventory 13, refinery 9, identity 6, hangar 3, access 2, catalogue 1 — `Mission`, `MissionRepository`
(5 modules), `MissionParticipantRepository` (4), `MissionFinanceEntryRepository`, `MissionMapper`,
`MissionReferenceDto`, `MissionFinanceSummaryDto`, `PayoutPreference`, `FinanceType`. *Out*: identity 31,
catalogue 28, audit 20, orgunit 18, hangar 12, operation 9, access 8, refinery 5. *API*:
`MissionReference`/summary, participation query (`isParticipant`), `MissionFinanceQueries` for payout,
commands `detachFromOperation`, observers for ship-deleted / job-type-designated / stock-sold,
`MissionAccessPolicy` (+ `missionSecurityService`), SPIs `OperationSummaryProvider`,
`MissionFinanceContributor`. *Endpoints*: `/api/v1/missions/**`, `/api/v1/finance-entries/**`.
*Difficulty*: **high** (section counters, three cycles, peer redaction, FQCN-keyed rules).

**refinery** — `RefineryOrder`, `RefineryGood`; 26 classes, 3,497 LOC, 14 endpoints. *In*: operation 7
(profit aggregate, mapper), exchange 5 (draft import), mission 5, access 3, identity 1, catalogue 1,
blueprint 1. *Out*: catalogue 49, identity 16, mission 9, orgunit 8, inventory 6, access 4, audit 4,
joborder 2, blueprint 1. *API*: `RefineryImport` (draft), `RefineryProfitQueries` for operation,
implements `MissionFinanceContributor` and `StockSliceProvider`, `RefineryAccessPolicy`.
*Difficulty*: medium.

**joborder** — `JobOrder` and 8 children + `MaterialClaim`; 95 classes, 11,761 LOC, 39 endpoints.
*In*: inventory 17, exchange 9 (demand), identity 6, access 5, refinery 2. *Out*: catalogue 82, orgunit
52, inventory 38, identity 35, audit 28, access 13, notification 9, blueprint 3, materialexchange 3.
*API*: `JobOrderReference`, `OpenDemand` query (for exchange; anonymised), implements
`EarmarkTargetPolicy`, `JobOrderAccessPolicy`, events `JobOrderCreated/UpdatedByRequester`.
*Endpoints*: `/api/v1/orders/**` incl. material collection and item stock panels. *Difficulty*: high
(handover/production concurrency, bulk-after-loop, pessimistic reorder).

**materialexchange** — 4 entities; 34 classes, 5,001 LOC, 21 endpoints. *In*: inventory 5 + joborder 3
(ratchet), exchange 2, identity 1. *Out*: orgunit 20, identity 16, catalogue 16, audit 12, notification 8,
access 7, inventory 6, blueprint 2. *API*: implements `StockChangeObserver` (the ratchet), GDPR
participant. *Difficulty*: medium (ratchet semantics).

**operation** — `Operation`, `OperationPayoutStatus`; 22 classes, 2,631 LOC, 12 endpoints. *In*:
mission 9, access 2. *Out*: mission 22, audit 8, refinery 7, orgunit 6, access 6, identity 6, admin 1.
*API*: implements `OperationSummaryProvider`; `OperationAccessPolicy`. *Difficulty*: low–medium
(REQUIRES_NEW payout-status retry stays inside).

**bank** — 11 entities; 129 classes, 17,667 LOC, 65 endpoints. *In*: 18 edges (DOM-14). *Out*: all down.
*API*: participants (GDPR, retention, recipient selectors), `BankAccountViewQuery` for livesync,
membership observer; endpoints `/api/v1/bank/**`, `/api/v1/org-units/bank/**`, plus the bank user search
now under `/users`. *Difficulty*: medium (ADR-0020 seam).

**exchange** — 10 entities; 169 classes, 19,185 LOC, 35 endpoints (14 frozen relay endpoints
`/api/v1/exchange/**`, 7 `/connected-apps`, 14 admin). *In*: 11 edges (DOM-13). *Out*: 197 edges to 13
modules, all down. *API*: `ChangeSource` for the transaction manager, `ExchangeClientDirectory` (display
names). *Difficulty*: low–medium after DOM-05; first Gradle candidate.

---

## 4. Ambiguous classifications (decisions and reasons)

42 classes carry a reason in the `ambiguous` column of `10-backend-domains-classes.csv`; the decisions
that shape the module cut:

| Class | Decided | Alternative | Why |
| --- | --- | --- | --- |
| `AccessGateService`, `OwnerScopeService` | access (scope) | split per domain | holds six aggregates' gates; DOM-03 |
| `CustomJwtGrantedAuthoritiesConverter` | access (scope) | identity | needs identity sync + orgunit cascade |
| `MeController` | identity | app shell | composite of identity, orgunit, notification, inventory flags |
| `SquadronRoleController`, `OrgRoleManagementSecurityService`, `BereichLeadershipRole`, `GrandAdmiralRequest` | leadership | orgunit | appointments are membership-row writes; merge recommended |
| `Blueprint` (+7 recipe entities), `BlueprintRepository/Mapper/Service/Controller` | catalogue | blueprint | synced reference data read by four modules |
| `BlueprintProductService` | blueprint | catalogue | product search also reads personal blueprints |
| `BlueprintNameNormalizer` | catalogue | blueprint | used by the recipe sync and the import |
| `MaterialCollectionController`, `JobOrderItemStockController` | joborder | inventory | job-order pages composed from inventory reads |
| `MaterialCollectionEntryDto`, `JobOrderAllocationDto`, `MissionAllocationDto`, `AllocationReductions` | inventory | joborder/mission | earmark slices of inventory rows |
| `QualityRequirement` | joborder | catalogue | quality floor of job-order buckets and claims |
| `QuantityType` | catalogue | shared-kernel | property of `Material`; six modules read it |
| `FinanceType`, `PayoutPreference` | mission | identity | `User.defaultPayoutPreference` suggests identity (DOM-15) |
| `RefineryYield` | catalogue | refinery | UEX matrix written by `UexRefinerySyncService` |
| `ShipTypeMatcher` | hangar | catalogue | written for the hangar import |
| `UexLocationController` | personalinventory | catalogue | served by `PersonalInventoryItemService` |
| `ProfitCalculation*` | catalogue | own "trade" module | prices × ship types |
| `MailService` | notification | infrastructure | REQ-NOTIF-013 seam |
| `ChangeSourceTransactionManager` | exchange | platform | app-wide tx manager for the change feed |
| `PendingApprovalAccessFilter`, `TermsAcceptanceAccessFilter` | identity | platform | enforce identity rules |
| `OrgUnitRef` (event record) | notification | orgunit | part of the event contract |
| `DataInitializer`, `SecurityConfig`, `BusinessMetricsCollector` | infrastructure (→ app) | — | composition root |
| `UserMapper`, `UserDtoRedaction` | identity | split | derive/redact other modules' data |

---

## 5. Data appendix

### 5.1 Module inventory (classes, LOC, endpoints, coupling)

Ce/Ca count class edges to/from categories other than shared-kernel and infrastructure.

| module | classes | LOC | endpoints | entities | Ce edges → modules | Ca edges ← modules |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| catalogue | 263 | 28952 | 90 | 36 | 15 → 6 | 337 ← 10 |
| exchange | 169 | 19185 | 35 | 10 | 197 → 13 | 11 ← 6 |
| bank | 129 | 17667 | 65 | 11 | 104 → 6 | 18 ← 5 |
| identity | 101 | 14642 | 59 | 5 | 130 → 15 | 204 ← 17 |
| joborder | 95 | 11761 | 39 | 10 | 263 → 9 | 39 ← 5 |
| mission | 81 | 10089 | 53 | 9 | 131 → 8 | 56 ← 7 |
| inventory | 54 | 9168 | 27 | 3 | 156 → 9 | 70 ← 8 |
| blueprint | 59 | 6951 | 24 | 3 | 71 → 9 | 37 ← 5 |
| orgunit | 56 | 6855 | 38 | 8 | 27 → 8 | 279 ← 16 |
| materialexchange | 34 | 5001 | 21 | 4 | 87 → 8 | 11 ← 4 |
| promotion | 41 | 4717 | 34 | 5 | 39 → 4 | 1 ← 1 |
| notification | 44 | 4321 | 13 | 3 | 10 → 4 | 70 ← 5 |
| access | 16 | 3798 | 0 | 0 | 52 → 9 | 126 ← 18 |
| refinery | 26 | 3497 | 14 | 2 | 99 → 9 | 23 ← 7 |
| operation | 22 | 2631 | 12 | 2 | 56 → 7 | 11 ← 2 |
| hangar | 20 | 2578 | 15 | 1 | 56 → 7 | 29 ← 5 |
| orgchart | 19 | 2425 | 5 | 1 | 21 → 3 | 2 ← 1 |
| audit | 15 | 2377 | 4 | 1 | 6 → 3 | 227 ← 15 |
| livesync | 13 | 2002 | 2 | 0 | 4 → 2 | 3 ← 1 |
| personalinventory | 12 | 1172 | 10 | 1 | 8 → 2 | 1 ← 1 |
| leadership | 12 | 1039 | 3 | 0 | 33 → 3 | 6 ← 2 |
| admin | 12 | 623 | 6 | 1 | 0 → 0 | 4 ← 3 |
| dashboard | 6 | 364 | 4 | 1 | 0 → 0 | 0 ← 0 |
| infrastructure | 71 | — | 1 | — | 44 → domains | 171 ← domains |
| shared-kernel | 19 | — | — | 1 | 6 → domains | 438 ← domains |

Classification: all 1389 types decided by an explicit entry (with reason) or a name-prefix rule; the
dependency-majority fallback decided none (`10-backend-domains-classify.py`).

### 5.2 Domain × domain matrix (class edges; row depends on column)

```
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

Abbreviations: SK shared-kernel, INF infrastructure, cat catalogue, idn identity, org orgunit, acc
access, aud audit, ntf notification, lsy livesync, adm admin, dsh dashboard, lead leadership, och
orgchart, prm promotion, pinv personalinventory, hng hangar, bp blueprint, inv inventory, msn mission,
ref refinery, op operation, job joborder, mxc materialexchange, bnk bank, xch exchange. Per-cell edge
kinds and up to 12 example edges: `10-backend-domains-matrix.csv`.

### 5.3 Edge kinds of the 1565 cross-domain edges (source role → target role)

| kind | all | between business modules only* |
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

\* excluding access, audit, notification, livesync as source or target (1071 edges).
Event/listener edges: 61 edges involve an event class; the only cross-domain listeners are
`NotificationEventListener` (all 14 `NotificationEvent` types from identity, bank, joborder,
materialexchange, exchange) and `ExchangeDepartureService` (`MemberDepartedEvent` from identity).

### 5.4 Cycles

- SCC over the 23 non-kernel categories: **one SCC of 21** — access, audit, bank, blueprint,
  catalogue, exchange, hangar, identity, inventory, joborder, leadership, livesync, materialexchange,
  mission, notification, operation, orgchart, orgunit, personalinventory, promotion, refinery;
  singletons admin, dashboard.
- Without access/audit/notification/livesync: one SCC of 17. Additionally without
  identity/orgunit/leadership/catalogue: one SCC of 9 — blueprint, exchange, hangar, inventory,
  joborder, materialexchange, mission, operation, refinery.
- Two-way pairs (41; heavier / lighter direction): inventory⇄joborder 17/38, mission⇄operation
  9/22, bank⇄identity 27/9, identity⇄mission 6/31, identity⇄joborder 6/35, access⇄identity 6/18,
  mission⇄refinery 5/9, leadership⇄orgunit 30/5, inventory⇄materialexchange 5/6, access⇄joborder
  5/13, identity⇄orgunit 21/4, blueprint⇄exchange 4/29, bank⇄notification 16/4,
  identity⇄notification 27/3, hangar⇄mission 3/12, catalogue⇄exchange 3/38, audit⇄identity 3/33,
  access⇄refinery 3/4, access⇄orgunit 29/3, orgchart⇄orgunit 15/2, identity⇄inventory 2/19,
  catalogue⇄hangar 2/21, blueprint⇄identity 2/2, audit⇄bank 2/7, access⇄operation 2/6,
  access⇄mission 2/8, access⇄inventory 2/8, access⇄hangar 2/4, inventory⇄orgunit 18/1,
  identity⇄refinery 1/16, identity⇄promotion 1/4, identity⇄materialexchange 1/16, hangar⇄identity
  12/1, exchange⇄inventory 13/1, exchange⇄identity 14/1, exchange⇄hangar 12/1, catalogue⇄refinery
  1/49, catalogue⇄mission 1/28, blueprint⇄refinery 1/1, bank⇄orgunit 37/1, access⇄exchange 1/23.
- The class edges that close the cycles are exactly the backlog of §5.5 (under the target layering);
  the heaviest are operation→mission (22, e.g. `Operation → Mission`, `OperationFinanceService →
  MissionFinanceEntryRepository`), inventory→joborder (17, e.g. `InventoryJobOrderAllocation →
  JobOrder`), identity→orgunit (21, `UserMapper → OrgUnitMembershipRepository`), mission→operation
  (9, `Mission → Operation`), inventory→mission (13, `InventoryCheckoutService →
  MissionFinanceEntryRepository`).
- Replaying 13 candidate moves (`10-backend-domains-simulate.py`) never splits the SCC before the core
  pairs are inverted: no single cheap move breaks it — it takes the backlog.

### 5.5 Target layering and the decoupling backlog

Ranks (lower = more foundational): 0 shared-kernel, infrastructure, access-core · 1 audit,
notification, livesync · 2 catalogue · 3 identity · 4 orgunit (+leadership) · 5 scope · 6 admin,
dashboard · 7 orgchart, promotion, personalinventory, hangar, blueprint · 8 inventory · 9 mission ·
10 refinery, joborder, materialexchange · 11 operation, bank · 12 exchange · 13 privacy (GDPR
orchestration, to be dissolved into SPIs) · 14 app. Pre-applied behaviour-free re-homings: leadership →
orgunit; access → access-core/scope; the 31 GDPR classes → privacy; `HandleAnonymisation` →
shared-kernel; `PayoutPreference` → identity; `SecurityConfig`, `DataInitializer`,
`BusinessMetricsCollector`, `BackendApplication` → app.

Result: 2088 downward edges allowed, 20 same-rank edges (acyclic except the kernel/infrastructure pair),
**150 violating class edges in 48 pairs**; by source: inventory 36, identity 34, scope 17, mission 14,
notification 9, blueprint 7, catalogue 7, shared-kernel 6 (sealed `AppException`), infrastructure 4,
orgunit 4, hangar 4, audit 4, livesync 3, access-core 1.

| from (rank) → to (rank) | edges | source classes → targets |
| --- | ---: | --- |
| identity(3) → orgunit(4) | 21 | `MeController`, `UserController` → membership DTOs + `OrgUnitMembershipQueryService`; `UserDto`, `UserDtoRedaction` → `SquadronReferenceDto`; `UserMapper` → `OrgUnitKind`, `OrgUnitMembership(Id)`, `Squadron`, `SquadronReferenceDto`, `OrgUnitMembershipRepository`, `StaffelMembershipResolver`; `UserService` → `OrgUnitMembershipService/QueryService` |
| inventory(8) → joborder(10) | 17 | `AllocationReductions`, `InventoryAllocations`, `InventoryAuditLabels`, `InventoryCheckoutService`, `InventoryItemMapper`, `InventoryJobOrderAllocation` → `JobOrder`; `InventoryAggregationService` → `JobOrder`, `JobOrderItem`, `JobOrderItemStock*Dto`, `JobOrderRepository`; `InventoryItemRepository` → `JobOrder*StockRow`; `InventoryItemService` → `JobOrderRepository`, `JobOrderItemService` |
| inventory(8) → mission(9) | 13 | `InventoryCheckoutService` → `FinanceType`, `Mission`, `MissionFinanceEntry(Repository)`, `MissionParticipant(Repository)`; `InventoryItemService` → `MissionRepository`; allocation helpers, mapper, `InventoryMissionAllocation` → `Mission` |
| mission(9) → operation(11) | 9 | `Mission` → `Operation`; `MissionDto`, `MissionListDto`, `MissionPeerRedactor` → `OperationDto`; `MissionMapper` → `OperationMapper`; `MissionService` → `OperationRepository` |
| identity(3) → privacy(13) | 7 | `DiscordRegistrationAdminController` → `UserAccountMergeService`; `RejectedRegistrationRetentionService`, `UserRegistrationService`, `UserController` → `UserDeletionService`; `UserController` → `AccountConsolidationService` |
| inventory(8) → materialexchange(10) | 5 | `InventoryCheckoutService` → `MaterialExchangeOfferRatchet`, `…OfferRepository`; `InventoryStolenMarkService` → `MaterialExchangeOffer(Repository/Status)` |
| mission(9) → refinery(10) | 5 | `Mission` → `RefineryOrder`; `MissionFinanceEntryService` → `RefineryOrderRepository`; `MissionFinanceSummaryDto` → `RefineryOrderDto` |
| scope(5) → joborder/refinery/hangar/inventory/mission/operation | 5+3+2+2+2+2 | `AccessGateService`, `OwnerScopeService` → aggregates and repositories (DOM-03) |
| blueprint(7) → exchange(12) | 4 | `BlueprintUploadPreviewService` → `ExchangeDraftService`; `PersonalBlueprintService` → `ExchangeClientRepository` |
| notification(1) → bank/identity/orgunit | 4+3+2 | `RecipientResolutionService`, `NotificationRuleService`, `OrgUnitRef → OrgUnitKind` |
| catalogue(2) → exchange/hangar/mission/refinery | 3+2+1+1 | row records; `LocationService`, `ShipTypeController`; `JobTypeService` |
| hangar(7) → mission(9), exchange(12) | 3+1 | `HangarService` → `MissionUnit(Repository)`; `ShipRepository` → `ExchangeShipRow` |
| audit(1) → bank/identity | 2+2 | `AuditRetentionService`; `AuditService` → `User(Repository)` |
| blueprint(7) → inventory/refinery | 2+1 | `BlueprintCraftabilityService` |
| identity(3) → scope/bank/blueprint/inventory/joborder | 2+1+1+1+1 | `MeController`, `UserService` → `OwnerScopeService`; `UserSyncService`; `UserReconciliationService`; `MeController` → `InventoryProperties`; `UserDtoRedaction` |
| livesync(1) → bank/scope | 2+1 | `LiveSyncSubscriptionAuthorizer` |
| orgunit(4) → orgchart/bank/inventory | 2+1+1 | `OrgUnitMembershipService`, `KommandoGroupService` |
| infrastructure(0) → exchange/scope | 3+1 | `ApiClientMetricsFilter`, `ClientAttribution`; `CorrelationIdFilter` |
| scope(5) → exchange; access-core(0) → scope; inventory(8) → exchange | 1+1+1 | `CustomJwtGrantedAuthoritiesConverter`; `AuthHelperService`; `InventoryItemRepository` |
| shared-kernel(0) → six modules | 6 | sealed `AppException permits …` (DOM-12) |

Full per-edge list: `10-backend-domains-layering.out.txt`.

### 5.6 Cross-domain JPA associations (74 of 185; all LAZY unless marked; no cross-domain cascade)

| owner → target | n | associations (`Entity.field` kind → Target @line) |
| --- | ---: | --- |
| joborder → catalogue | 6 | `JobOrderHandoverItem.material` M:1→Material @59; `JobOrderItem.gameItem` M:1→GameItem @72; `JobOrderItem.blueprint` M:1→Blueprint @81; `JobOrderItemMaterial.material` @67; `JobOrderMaterial.material` @60; `MaterialClaim.material` @71 |
| mission → identity | 6 | `Mission.owner` @250; `Mission.partyLeadUser` @270; `Mission.managers` M:M @284; `MissionOwnership.owner` @68; `MissionParticipant.user` @71; `MissionUnit.responsibleUser` @85 |
| joborder → orgunit | 5 | `JobOrder.responsibleOrgUnit` @79; `JobOrder.requestingOrgUnit` @87; `JobOrderHandover.executingSquadron` →Squadron @83; `JobOrderItemHandover.executingSquadron` →Squadron @92; `MaterialClaim.claimingOrgUnit` @89 |
| mission → catalogue | 5 | `MissionCrew.jobTypes` M:M @64; `MissionFrequency.frequencyType` @73; `MissionParticipant.desiredMissionJobType` @96; `…plannedMissionJobType` @101; `MissionUnit.shipType` @63 |
| joborder → identity | 4 | `JobOrderAssignee.user` @79; `JobOrderHandover.executingUser` @75; `JobOrderItemHandover.executingUser` @83; `MaterialClaim.claimedByUser` @103 |
| materialexchange → identity | 4 | `MaterialExchangeInterest.interestedUser` @66; `MaterialExchangeOffer.owner` @116; `MaterialExchangeRequest.owner` @130; `MaterialExchangeRequestInterest.interestedUser` @69 |
| refinery → catalogue | 4 | `RefineryGood.inputMaterial` @54; `.outputMaterial` @63; `RefineryOrder.location` @66; `.refiningMethod` @80 |
| bank → identity | 3 | `BankAccountGrant.user` @69; `.grantedBy` @105; `BankHolder.user` @66 |
| blueprint → catalogue | 3 | `BlueprintExternalAlias.outputItem` @89; `DefaultBlueprint.outputItem` @83; `PersonalBlueprint.outputItem` @92 |
| inventory → catalogue | 3 | `InventoryItem.material` @79; `.gameItem` @90; `.location` @95 |
| hangar → catalogue | 2 | `Ship.shipType` @54; `Ship.location` @65 |
| materialexchange → orgunit | 2 | `MaterialExchangeOffer.owningOrgUnit` @124; `MaterialExchangeRequest.owningOrgUnit` @138 |
| mission → orgunit | 2 | `Mission.owningOrgUnit` @304; `MissionParticipant.orgUnits` M:M @86 **EAGER** |
| orgchart → orgunit | 2 | `OrgChartPosition.orgUnit` @86; `.kommandoGroup` →KommandoGroup @142 |
| promotion → orgunit | 2 | `PromotionTopic.owningSquadron` →Squadron @76; `RankRequirement.owningSquadron` →Squadron @75 |
| bank → orgunit | 1 | `BankAccount.orgUnit` @93 |
| exchange → identity | 1 | `ExchangeInstallation.user` @63 |
| hangar → identity / orgunit | 1+1 | `Ship.owner` @72; `Ship.owningOrgUnit` @83 |
| inventory → identity / orgunit | 1+1 | `InventoryItem.user` @69; `InventoryItem.owningOrgUnit` @155 |
| **inventory → joborder** | 1 | `InventoryJobOrderAllocation.jobOrder` @75 |
| **inventory → mission** | 1 | `InventoryMissionAllocation.mission` @75 |
| materialexchange → catalogue | 1 | `MaterialExchangeRequest.requestedMaterial` @83 |
| **materialexchange → inventory** | 1 | `MaterialExchangeOffer.inventoryItem` @85 |
| **mission → hangar** | 1 | `MissionUnit.ship` @67 |
| **mission → operation** | 1 | `Mission.operation` @245 |
| **mission → refinery** | 1 | `Mission.refineryOrders` 1:M @239 (inverse, mappedBy=mission) |
| operation → identity / orgunit | 1+1 | `OperationPayoutStatus.paidOutByUser` @112; `Operation.owningOrgUnit` @78 |
| **operation → mission** | 1 | `Operation.missions` 1:M @67 (inverse, mappedBy=operation) |
| orgchart → identity | 1 | `OrgChartPosition.user` @97 |
| orgunit → identity | 1 | `OrgUnitMembership.user` @72 |
| refinery → identity / orgunit | 1+1 | `RefineryOrder.owner` @61; `RefineryOrder.owningOrgUnit` @129 |
| **refinery → mission** | 1 | `RefineryOrder.mission` @71 |

Bold = the eight business→business associations to convert (DOM-09). Line numbers are in
`backend/src/main/java/.../model/<Entity>.java`; full rows with fetch, cascade, orphanRemoval, mappedBy
and join column: `10-backend-domains-jpa.csv`. Not associations but entity→entity coupling: three
job-order entities call the static `InventoryItem.roundToScuScale` (`JobOrderMaterial.java:86`,
`JobOrderHandoverItem.java:86`, `MaterialClaim.java:116`) — a shared-kernel value type candidate.

### 5.7 Hubs — top 30 classes by the number of other modules depending on them

| # | class | module | role | modules | classes | LOC | verdict |
| ---: | --- | --- | --- | ---: | ---: | ---: | --- |
| 1 | `User` | identity | entity | 16 | 93 | 197 | foundation entity; publish `UserReference`; keep association targets (DOM-09) |
| 2 | `AuthHelperService` | access-core | service | 16 | 42 | 232 | platform API; drop its 4 scope delegations |
| 3 | `AuditDetails` | audit | support | 15 | 56 | 155 | audit API |
| 4 | `AuditEvent` | audit | entity | 14 | 56 | 127 | hide — only exposed as `record()`'s return type |
| 5 | `AuditEventType` | audit | enum | 14 | 56 | 748 | audit's published vocabulary (202 values); keep central |
| 6 | `AuditService` | audit | service | 14 | 56 | 218 | audit API (MANDATORY) |
| 7 | `UserRepository` | identity | repository | 13 | 35 | 502 | **split**: `UserDirectory` query API |
| 8 | `OwnerScopeService` | access (scope) | service | 13 | 32 | 723 | **god class**: per-module policies (DOM-03) |
| 9 | `OrgUnit` | orgunit | entity | 12 | 65 | 140 | foundation entity; `OrgUnitRef` value type |
| 10 | `OrgUnitKind` | orgunit | enum | 10 | 27 | 54 | orgunit API (kernel candidate) |
| 11 | `OrgUnitMembershipRepository` | orgunit | repository | 9 | 14 | 217 | **hide** behind `MembershipQueries` |
| 12 | `ScopePredicate` | access (scope) | value | 9 | 12 | 69 | scope API |
| 13 | `SquadronReferenceDto` | orgunit | dto | 8 | 37 | 28 | orgunit API |
| 14 | `OrgUnitRepository` | orgunit | repository | 8 | 17 | 130 | **hide** behind a query API |
| 15 | `UserService` | identity | service | 7 | 14 | 553 | **split**: current-user seam vs internals |
| 16 | `UserMapper` | identity | mapper | 7 | 13 | 298 | **split**: reference mapping vs Staffel-enriched view |
| 17 | `SquadronMapper` | orgunit | mapper | 7 | 12 | 82 | orgunit API (reference mapping) |
| 18 | `Material` | catalogue | entity | 6 | 37 | 245 | foundation entity |
| 19 | `Location` | catalogue | entity | 6 | 24 | 78 | foundation entity |
| 20 | `QuantityType` | catalogue | enum | 6 | 16 | 26 | catalogue API |
| 21 | `InventoryItemRepository` | inventory | repository | 6 | 14 | 1188 | **god repository**: `StockCommands`/queries (DOM-05) |
| 22 | `MaterialRepository` | catalogue | repository | 6 | 13 | 248 | hide behind material queries |
| 23 | `OrgUnitMembershipQueryService` | orgunit | service | 6 | 10 | 463 | orgunit API |
| 24 | `GameItem` | catalogue | entity | 5 | 34 | 283 | foundation entity |
| 25 | `UserReferenceDto` | identity | dto | 5 | 24 | 26 | identity API (`UserReference`) |
| 26 | `Mission` | mission | entity | 5 | 17 | 376 | hide behind mission API (DOM-07) |
| 27 | `InventoryItem` | inventory | entity | 5 | 15 | 187 | hide behind inventory API (DOM-05) |
| 28 | `OrgUnitRef` | notification | event | 5 | 15 | 32 | notification contract |
| 29 | `Roles` | access-core | constants | 5 | 15 | 111 | platform API |
| 30 | `NotificationContextRole` / `NotificationEventType` | notification | enum | 5 | 14 | 34/116 | notification contract |

Kernel/infrastructure hubs: `AbstractEntity` 19 modules, `OptimisticLock` 19, `Entities` 18,
`BadRequestException` 17, `PageResponse` 16, `PaginationUtil` 15, `NotFoundException` 11,
`StringNormalization` 11, `MetricNames` 8 (split per module), `CurrentUserId` 8, `ScheduledJob` 8,
`TaskMetrics` 8. Foreign-used classes per module with their users: `10-backend-domains-foreignrepo.out.txt`.

### 5.8 Cross-domain write paths (non-audit; 149 sites, 57 caller methods)

`caller_tx` = the caller method's transactional mode (`private(caller-tx)` runs in its caller's
transaction). Not in the table because it runs through native SQL: `UserAccountMergeService.merge`
(`@Transactional`, `UserAccountMergeService.java:190`) re-pointing 28 table.columns in 14 modules.
The 205 `AuditService.record` sites (MANDATORY, 14 modules) are omitted.

| caller → target | caller method (tx) | lines | what it writes | pattern |
| --- | --- | --- | --- | --- |
| access → identity | `CustomJwtGrantedAuthoritiesConverter.assembleAuthorities` | :276 | `UserReconciliationService.syncUser` | auth hot path, bounded retry on 409 |
| audit → bank | `AuditRetentionService.purgeOlderThan` | :71 | `BankAuditService.purgeBefore` | - |
| blueprint → admin | `DefaultBlueprintBootstrap.markSeeded` | :157-161 | `SystemSetting` + save | - |
| catalogue → mission | `JobTypeService.applyMissionLeadDesignation` | :214,226 | `MissionParticipantRepository.clearMissionLeadFlagForJobType` | @Modifying |
| exchange → blueprint | `ExchangeBlueprintWriteService.execute`, `ExchangeUndoService.restoreBlueprint` | :343,370 / :299,302 | `PersonalBlueprintService.add/delete` | via owner service |
| exchange → hangar | `ExchangeShipWriteService.execute/lockOwn`, `ExchangeUndoService.restoreShip` | :452-594 / :342-372 | `HangarService.add/update/delete`, `ShipRepository.lockOwnedById` | PESSIMISTIC_WRITE |
| exchange → identity | `ConnectedAppsService.endSessions`, `ExchangeDepartureService.onDeparture` | :260-262 / :124 | `KeycloakService` consent/session calls | external Keycloak calls (departure: after commit) |
| exchange → inventory | `ExchangeStockWriteService.bookIn/bookOut/lockRows/markRows` | :799-818, :847, :521,527, :678 | new `InventoryItem` + save, `mergeStockIfRequested`, `bookOutForClient`, lot locks, `InventoryStolenMarkService.mark` | MANDATORY, PESSIMISTIC_WRITE, advisory (ADR-0229) |
| exchange → notification | `ConnectedAppsService.markSeen` | :183 | `NotificationRepository.markReadOfTypeAndEntity` | @Modifying |
| hangar → mission | `HangarService.detachFromMissionUnits` | :461-462 | `MissionUnit.setShip(null)` + save | FK order |
| identity → bank | `HandleAnonymisationService.anonymise`; `UserSyncService.syncFromKeycloak` | :148-151,189; :111 | 4 `@Modifying` anonymisations, `BankAuditService.record`; `BankHolderReconciliationService.reconcileAll` | @Modifying, MANDATORY |
| identity → blueprint | `UserDeletionService.deleteUser`; `UserReconciliationService.syncUser` | :237; :219,324 | `PersonalBlueprintRepository.deleteAllByOwnerUserId`; `grantDefaultsToUser` | @Modifying |
| identity → hangar/inventory/personalinventory/promotion/refinery/notification | `UserDeletionService.deleteUser` | :233-272 | `deleteBy…`/`updateOwner` | @Modifying, FK order |
| identity → joborder | `HandleAnonymisationService.anonymise`; `UserDeletionService.deleteUser` | :158-160; :287,289 | anonymise handles; remove assignee, unlink claims | @Modifying |
| identity → materialexchange | `UserDeletionService.deleteUser` | :232 | `MaterialExchangeOfferRatchet.beforeUserPurge` | MANDATORY |
| identity → mission | `UserDeletionService.deleteUser` | :242,284,286,288 | owner/ownership/manager/participant updates | @Modifying |
| identity → orgunit | `UserService.applyMembershipDelta/applySpecialCommandChange` | :486, :508-521 | `OrgUnitMembershipService.*`, membership flags | - |
| inventory → materialexchange | `InventoryCheckoutService.bookOut/bookOutTransfer/bulkCheckout/deleteAllGlobalInventory/rebookPersonal/rebookWholeRow` | :264,282,370,380,626,634,823,876,1151 | `MaterialExchangeOfferRatchet.beforeDelete/lower/beforeWipe` | MANDATORY |
| inventory → mission | `InventoryCheckoutService.createSaleFinanceEntries` | :445-455 | new `MissionFinanceEntry` + save | - |
| joborder → inventory | `JobOrderHandoverService.createHandover`; `JobOrderItemHandoverService.consumeEarmarkedItemStock`; `JobOrderItemProductionService.bookProduction/bookProducedStockIn`; `JobOrderService.completeJobOrderWithinTransaction` (MANDATORY), `replaceMaterialsWithinTransaction`, `unlinkMaterial`, `updateItemJobOrderAsRequester`, `updateJobOrderStatus` | :162-252; :237-268; :205-265, :384-394; :967, :458, :854, :750-753, :298 | row locks, delete/save rows, new `InventoryItem`, `mergeStockIfRequested`, allocation bulk deletes | PESSIMISTIC_WRITE, MANDATORY, @Modifying (clearAutomatically → after the loop) |
| joborder → materialexchange | handover, item handover, production | :222,269; :187,263; :256,274 | ratchet `beforeDelete/lower` | MANDATORY |
| leadership → orgunit | `SquadronRoleController.assignRank/removeRank` | :76, :101 | `OrgUnitMembershipService.*SquadronRankDto` | - |
| operation → mission | `OperationService.deleteOperation` | :270 | `mission.setOperation(null)` | no section counter bumped |
| orgunit → inventory | `OrgUnitMembershipService.addMember/removeMember/reconcileStaffelMemberships` | :130, :163, :650-652 | `InventoryOrgUnitReconciler.onUserGained/LostOrgUnit` | MANDATORY |
| orgunit → orgchart | `KommandoGroupService.create/update/deleteGroup`; `OrgUnitMembershipService` (8 methods) | :114,143,165; :160-787 | `OrgChartService.mirror*` (9 methods) | MANDATORY |
| refinery → inventory | `RefineryOrderService.storeRefineryOrder` | :631-647 | new `InventoryItem` + save (audits `INVENTORY_RECEIVED_FROM_REFINERY`) | - |

Full rows with every line: `10-backend-domains-writes.csv` and `10-backend-domains-tables.out.md`.
Concurrency inventory (grep): 33 `Propagation.MANDATORY` in 15 files; 17 `Propagation.REQUIRES_NEW`
in 11 files; 12 `ObjectProvider<Self>` self-proxies, each calling back into its own class; 15 `@Lock` in 8
repositories (`PESSIMISTIC_WRITE` ×13, `OPTIMISTIC_FORCE_INCREMENT` ×2); 1 advisory lock query; 87
`@Modifying` methods. Bean-cycle workarounds that mark hidden domain cycles:
`AuthHelperService.java:230` `getBean(OwnerScopeService.class)`, `ObjectProvider<OrgUnitBankResponsibilityService>`
in `OrgUnitMembershipService` and `UserDeletionService`.

### 5.9 Size

Classes over 600 LOC (36): inventory — `InventoryCheckoutService` 1213, `InventoryItemRepository` 1188,
`InventoryItemService` 1147, `InventoryItemController` 1104, `InventoryAggregationService` 1090;
catalogue — `P4kImportService` 1531, `UexUniverseSyncService` 936, `ScWikiItemSyncService` 924,
`ScWikiClient` 729, `ScWikiBlueprintSyncService` 642; bank — `OrgUnitBankAccessService` 1753,
`BankBookingRequestService` 942, `BankLedgerService` 794, `BankAccountService` 627; joborder —
`JobOrderController` 1126, `JobOrderService` 1074, `MaterialClaimService` 635; mission —
`MissionController` 1516, `MissionService` 1308, `MissionParticipantService` 617; access —
`OwnerScopeService` 723, `RequestScopeResolver` 680, `AccessGateService` 677; exchange —
`ExchangeStockWriteService` 1100, `ExchangeShipWriteService` 887, `ExchangeResolveService` 757;
identity — `KeycloakService` 914, `UserController` 884; infrastructure — `MetricNames` 1015,
`GlobalExceptionHandler` 1005; refinery — `RefineryOrderService` 834, `RefineryImportService` 703;
audit — `AuditEventType` 748; materialexchange — `MaterialExchangeService` 618; orgchart —
`OrgChartService` 843; orgunit — `OrgUnitMembershipService` 1107.

Most public methods — services: `OwnerScopeService` 56, `MissionService` 47,
`OrgUnitBankAccessService` 31, `InventoryItemService` 29, `AccessGateService` 28,
`InventoryAggregationService` 26, `RequestScopeResolver` 25, `OrgUnitMembershipService` 21,
`UserService` 19, `JobOrderService` 18; controllers: `MissionController` 47, `JobOrderController` 34,
`OrgUnitBankController` 29, `UserController` 27, `InventoryItemController` 27, `HangarController` 15,
`RefineryOrderController` 13. Most final-field dependencies: `UserDeletionService` 21,
`ExchangeStockWriteService` 19, `ExchangeUndoService` 17, `OrgUnitBankAccessService` 16,
`InventoryItemService`/`MissionService`/`ExchangeBulkUndoService` 14.

### 5.10 Endpoints, SpEL and hidden coupling

- 574 endpoints, 99 controllers, 55 path families; all 574 carry a method- or class-level
  `@PreAuthorize`; 247 use `Roles.*` constants (inlined, invisible to jdeps).
- SpEL bean references (endpoints): `ownerScopeService` 66, `missionSecurityService` 38,
  `authHelperService` 17, `exchangeGate` 14, `bankSecurityService` 10,
  `orgRoleManagementSecurityService` 8, `connectedAppsGate` 7, `specialCommandSecurityService` 5.
  `ownerScopeService` methods by controller module: joborder `canEditJobOrder` 12, `canSeeJobOrder`
  12, `canEditJobOrderAsRequester` 2, `canViewJobOrders` 2, `canSeeJobOrderAsRequester`,
  `canSeeJobOrderBlueprintOwners`, `canViewOwnJobOrders` 1 each; inventory `canEditInventoryItem` 9;
  refinery `canEditRefineryOrder` 5 + three others; operation `canEditOperation` 3,
  `canSeeOperationLedger` 3, `canSeeOperation` 2; mission `canSeeMission` 7; hangar `canEditShip` 2;
  blueprint `canAccessBlueprintOverview` 2.
- Endpoints whose body mainly serves another module (9): `MeController` `GET /api/v1/me/capabilities`
  (inventory flag), `/me/layout` (notification), `/me/org-units` (orgunit); `UserController`
  `/users/me/memberships`, `/users/{id}/memberships`, `/users/{id}/memberships/detail` (orgunit);
  `SquadronRoleController` `PUT/DELETE /squadrons/{id}/ranks/{userId}` (orgunit);
  `MemberEvaluationController` `/promotion/evaluations/members` (identity directory).
  41 endpoints call two or more modules (full list: `10-backend-domains-endpoints.out.txt`).
- `/api/v1/admin/**`: 51 endpoints — identity 18, exchange 14, blueprint 11, catalogue 4,
  personalinventory 4. `/api/v1/users/**`: 33 endpoints (see DOM-15).
- Query-string coupling (`10-backend-domains-sqlrefs.py`): `UserAccountMergeService`,
  `DataExportSections`, `HandleErasureCoverage`, `PersonSearchTargets`, `HandleAnonymisationService`
  name tables of up to 20 modules; `ScopeSpecifications` fragments are concatenated by
  `InventoryItemRepository`, `JobOrderRepository`, `MaterialExchangeOfferRepository`,
  `MissionRepository`, `OperationRepository`, `RefineryOrderRepository`, `ShipRepository`. Single-word
  table matches (`mission`, `ship`, `operation`) in other files are noisy and not relied on.
- Triggers: `V252__create_exchange_change_feed.sql` on 4 tables of 3 modules.

### 5.11 Scripts and commands (all in the scratchpad, run with `python <file>`)

| Script | Produces |
| --- | --- |
| `10-backend-domains-inventory.py` | `10-backend-domains-inventory.json` — every top-level type: FQCN, layer, kind, stereotypes, LOC, NCLOC, Javadoc summary |
| `10-backend-domains-common.py` | shared loaders (jdeps parsing, folding, inventory) |
| `10-backend-domains-classify.py` | **`10-backend-domains-classes.csv`** — domain, sub-area, deciding rule, ambiguity reason |
| `10-backend-domains-graph.py` | **`10-backend-domains-matrix.csv`**, `10-backend-domains-graph.json`, `…graph.out.txt` — matrix, edge kinds, SCC, feedback edges, hubs, instability |
| `10-backend-domains-pivot.py` | the §5.2 matrix and the two-way pairs |
| `10-backend-domains-pairs.py domA:domB` | every class edge of a pair, both directions |
| `10-backend-domains-neighbors.py Name…` | folded in/out neighbours of classes |
| `10-backend-domains-generated.py` | graph-only nodes (49 MapStruct impls) and 19 isolated types |
| `10-backend-domains-jpa.py` | `10-backend-domains-jpa.csv`, `…jpa.out.txt` |
| `10-backend-domains-writes.py` | `10-backend-domains-writes.csv`, `…writes.out.txt` |
| `10-backend-domains-sqlrefs.py` | `…sqlrefs.out.txt` — entity/table names in query strings |
| `10-backend-domains-endpoints.py` | `10-backend-domains-endpoints.csv`, `…endpoints.out.txt` — mappings, responsibility, SpEL |
| `10-backend-domains-size.py` | `…size.out.txt` |
| `10-backend-domains-foreignrepo.py` | `…foreignrepo.out.txt` — per module, the classes other modules use |
| `10-backend-domains-simulate.py` | `…simulate.out.txt` — SCC after 13 candidate moves |
| `10-backend-domains-layering.py` | `…layering.out.txt` — the §5.5 backlog |
| `10-backend-domains-cards.py` | `10-backend-domains-cards.json`, `…cards.out.txt` |
| `10-backend-domains-tables.py`, `-tables2.py` | the Markdown tables of §5.1, §5.5, §5.6, §5.8 |

Greps used for single facts: `grep -rn "Propagation.MANDATORY\|REQUIRES_NEW"`, `grep -rn "@Lock("`,
`grep -c '"\.\.backend\.' ArchitectureTest.java` (44), `grep -A1 "@Test" … | grep void | wc -l` (43),
`grep -LE "^public (…)(class|interface|enum|record|@interface)"` (6 non-public),
`grep -cE "^  [A-Z_]+\(AuditDomain\." AuditEventType.java` (202), `javap -cp archunit-1.5.1.jar
…ModuleRuleDefinition$Creator …ModulesShould …FreezingArchRule`.

Caveats: jdeps sees bytecode only (no SpEL, no inlined constants, no SQL, no triggers — covered by
§5.10's separate scans); the write scan is heuristic (field-typed calls, local-variable setters) and may
miss writes through method chains; "public methods" counts 2-space-indented `public` members.
Vault drift noticed (read-only here, for the coordinator): `10 Systems/Backend.md` says
`ArchitectureTest` holds 38 rules — the code has 43 `@Test` methods; arc42 §5.5 says the exchange write
services write through the domains' own services, which is not true for stock (DOM-05).
