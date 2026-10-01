# 20 — Cross-cutting concerns as module APIs, and the refactor-safety inventory

Agent prefix `20-crosscutting` · finding IDs `XC-NN` · read-only analysis of the worktree
`$REPO` at `95e945326` (= `origin/main`),
2026-09-29. Compiled classes from `*/build/classes/java/main` (built 2026-09-29 15:34, `compile.log`).
Vault read first: `Request Authorization`, `Scoping`, `Permissions`, `Security`, `Audit`,
`Benachrichtigungen`, `Live Sync`, `Observability`. Repo read: ADR-0047, -0059, -0065, -0135, -0136,
-0144, -0184, -0206, specs `external-exchange.md` (REQ-XCH-009), the three `ArchitectureTest`s.
Every count below names the script or command that produced it (appendix J). Domain assignment of
classes is heuristic (by simple-name prefix, `20-crosscutting-common.py`), good for fan-out shape, not
for exact per-domain totals.

Owner update 2026-09-29 (API may be re-cut; Android adaptable; exchange contract frozen) is covered by
XC-22 and referenced wherever a finding touches a path.

---

## 1. Summary — the ten most important conclusions

1. **The scope/gate hub knows nine domains and is the main cycle-maker.** `OwnerScopeService`
   (55 public method names over 9 domains) is injected by 34 classes in 14 domains, and
   `AccessGateService` holds 9 repositories of 7 domains (`AccessGateService.java:66-74`); per-domain
   access-policy beans over a domain-free scope kernel remove it (XC-01).
2. **Six of the eight SpEL beans behind 166 `@PreAuthorize` references are named after their class**,
   and a rename turns every gated call into **HTTP 400 `ILLEGAL_ARGUMENT`** — Spring Security 7.1.1
   wraps the SpEL failure in `IllegalArgumentException`, which `GlobalExceptionHandler.java:594` maps
   to 400 — so no 5xx or ERROR alert notices; name the beans and add a resolution test before any move
   (XC-02).
3. **Package-by-domain disarms most of the backend ArchUnit gate silently.** Of 43 rules, 20 can turn
   vacuous, 14 can weaken and 1 changes meaning. 18 of those 34 guard authorisation, tenancy, redaction
   or the exchange's reduced authority. ArchUnit 1.5.1 does fail an empty `that()` by default, but that
   only catches a *fully* emptied selection (XC-13, appendix F).
4. **A large share of the coupling is invisible to jdeps, ArchUnit and Spring Modulith:** 29 spliced
   scope-JPQL fragments (`ScopeSpecifications` has 0 class-file edges), 166 SpEL bean references,
   93 FQCNs inside 68 `@Query` strings, 172 Thymeleaf `T()` references, 195 native-SQL GDPR registry
   entries and 4 cross-domain change-feed triggers. An `ApplicationModules.verify()` would pass over all
   of it (XC-15).
5. **Audit must stay a synchronous in-transaction call, not an event.** There are 206
   `AuditService.record` calls in 57 classes and 51 `BankAuditService.record` calls in 14 classes, all
   `MANDATORY` (`AuditService.java:80`), against one 202-constant `AuditEventType` whose names are
   persisted and alerted on. Promote that enum into an `audit.api` package instead of splitting it
   (XC-05).
6. **Events are all after-commit, but they close a bank ⇄ notification cycle and carry PII.** There are
   22 `publishEvent` sites in 9 classes of 5 domains. `RecipientResolutionService` reads
   `BankAccountGrantRepository`, and four event records carry names or e-mail addresses
   (`UserApprovalDecidedEvent.java:39-44`). Spring Modulith 2.1.1's registry persists the serialized
   payload and the event class in `EVENT_PUBLICATION`, so adopting it would create a new PII store
   (XC-06).
7. **The exchange is a second write path into four domains.** Its services use 14 foreign repositories
   and 22 foreign services. For example `ExchangeStockWriteService.java:809` saves an `InventoryItem`
   itself and records the INVENTORY audit event, while the ArchUnit guard covers only
   `controller.exchange` (`ArchitectureTest.java:506`) (XC-09).
8. **The schema is a star, not a mesh.** It has 195 FKs, 105 of them cross-domain: 46 point to
   `app_user`, 22 to `org_unit` and 29 to catalogue tables, and only **8** connect business domains.
   On top come 18 triggers, 4 of which write the exchange feed from 3 domains' tables (V252) (XC-21).
9. **A re-cut of `/api/v1` touches 20 path-keyed controls (appendix H), and the silent ones are
   security controls:** the CSRF exemption `/api/v1/**` (tests run with CSRF off,
   `SecurityConfig.java:315`), the no-store families (`NoStoreApiScopes.java:42-55`), the URL-matrix
   prefix gates, the edge read-only-family `405` rule, and the pending/terms allow-lists (XC-22).
10. **Moving frontend form/DTO classes out of `frontend.model` silently drops session values.** The
    session deserializer admits by the prefix `de.greluc.krt.profit.basetool.frontend.model.`
    (`SessionTypeAllowList.java:87`), 28 flash sites store forms, and production runs `enforce` since
    2026-09-25 (XC-16).

---

## 2. Findings

### Half 1 — cross-cutting concerns and their module APIs

#### XC-01 — Authorization: the central scope/gate hub knows every scoped aggregate

- **Evidence.**
  - **Method security.** 431 `@PreAuthorize` in the backend: 378 method-level and 53 class-level, on
    98 `@RestController`s, 1 `@Controller` and 7 services, with 72 distinct expressions (appendix A).
    There are 166 SpEL bean references to 8 beans:

    | Bean | References | Distinct methods |
    | --- | --- | --- |
    | `ownerScopeService` | 66 | 18 |
    | `missionSecurityService` | 40 | 6 |
    | `authHelperService` | 17 | 1 |
    | `exchangeGate` | 14 | 2 |
    | `orgRoleManagementSecurityService` | 13 | 8 |
    | `bankSecurityService` | 10 | 5 |
    | `specialCommandSecurityService` | 5 | 1 |
    | `connectedAppsGate` | 1 | 1 |

  - **`OwnerScopeService`** (`OwnerScopeService.java:53-56`) is a facade (ADR-0065) with 56 public
    signatures and 55 distinct names. Its gates cover inventory, job order, mission, operation,
    refinery, ship, org unit/Staffel, promotion and the blueprint overview. It is injected by 34 classes
    in 14 domains (jdeps fan-in, appendix B).
  - **`AccessGateService`** (`AccessGateService.java:59-74`) holds `MissionRepository`,
    `JobOrderRepository`, `JobOrderHandoverRepository`, `JobOrderItemHandoverRepository`,
    `InventoryItemRepository`, `RefineryOrderRepository`, `OperationRepository`, `ShipRepository` and
    `OrgUnitMembershipRepository` — 24 outgoing class edges in 9 domains.
  - **`AuthHelperService`** (14 public methods) is used by 46 classes in 17 domains.
  - **Domain-owned policy beans already exist:** `MissionSecurityService` (mission only),
    `BankSecurityService` (bank only, org-unit-blind by `ArchitectureTest.java:1794`),
    `SpecialCommandSecurityService`, `OrgRoleManagementSecurityService`, `ExchangeGate` and
    `ConnectedAppsGate`, the last two explicitly named (`ExchangeGate.java:63`,
    `ConnectedAppsGate.java:35`).
- **Impact.** Every scoped domain depends on the hub, and the hub depends on every scoped domain's
  repository — a hard cycle for any module graph (Option A cycle check, Option B compiler). This is the
  single largest cross-cutting obstacle to domain separation.
- **Proposed change.** Keep one module-free *kernel* and move every per-aggregate question into the
  owning domain.
  1. **The kernel** (`…backend.security` / shared): `AuthHelperService`, `Roles`, `Permissions`,
     `RequestScopeResolver`, `ScopePredicate`, `OrgUnitCascadeService` and `OrgUnitStampingService`.
     It depends only on org-unit membership reads — today `RequestScopeResolver` already has only
     orgunit + security out-edges (appendix B). It publishes `currentScopePredicate()`, the
     oversight/unit-overview scopes, membership predicates and `isCurrentUserOwner`.
  2. **One access-policy bean per scoped domain**, each with an explicit bean name:
     `@Component("missionAccess")`, `jobOrderAccess`, `inventoryAccess`, `refineryAccess`,
     `shipAccess`, `operationAccess`, `promotionGate`, `blueprintOverviewAccess`. Each carries the
     `canSee*`/`canEdit*` logic now in `AccessGateService`, using its own repository plus the kernel —
     the pattern `MissionSecurityService` already follows.
  3. **Transition without a big bang.** (a) Name `ownerScopeService` explicitly (XC-02). (b) Add the
     domain policy beans as pure delegates to `AccessGateService`. (c) Re-point the SpEL one domain per
     PR. (d) Move the bodies. (e) Delete the facade methods.
  - Each domain policy must own **both** its JPQL fragment (XC-03) **and** its per-row predicate, so the
    "lists and per-row gates widen together" property of `Scoping` survives by construction.
- **Pros.**
  - The hub disappears; module edges point domain → kernel only.
  - SpEL references become domain-local and readable (`@jobOrderAccess.canSee(#id)`).
  - Each domain's escapes (Operation participant escape, JobOrder SK-queue, requester escape) live next
    to its data.
- **Cons.**
  - 66 SpEL expressions are rewritten, and the existing `OwnerScopeServiceTest` family has to be split.
  - Eight new beans.
  - Duplication risk if two domains need the same escape (e.g. Operation's `MissionParticipant`
    escape).
- **Risks (security first) and guards.**
  - **Wrong bean or wrong method in rewritten SpEL.** It fails closed as a 400 (XC-02) or, worse, calls
    a *different* method of the same arity. **Guard:** the SpEL resolution test (XC-02) plus a
    per-endpoint gate snapshot — the resolved `@PreAuthorize` string per `(verb, path)`, reviewed as a
    diff.
  - **A lost escape or a changed verdict** means silent data loss or leak (layer 4 fails silently).
    **Guard:** before each re-point, a differential test that evaluates the old `AccessGateService`
    method and the new policy over a fixture matrix and asserts identical verdicts. The matrix covers
    admin unpinned/pinned, members of 0/1/2 units, Bereich cascade, owner escape, ownerless personal
    row, Operation participant escape, JobOrder SK/requester escapes, and SK-lead.
  - **Tenancy ArchUnit rules keyed on the string `ownerScopeService`**
    (`ArchitectureTest.java:1093-1103`) must accept the new bean names in the same PR, or they go
    vacuous (XC-14).
  - **Bank seam rules keyed on `OwnerScopeService`** (XC-04).
- **Effort** L (8 domains × one PR each after a prep PR).
- **Prerequisites.** Amend ADR-0065 (facade split continued) plus a new ADR "per-domain access policies
  over a scope kernel". REQ-SEC-002 still holds, since authorisation stays in `@PreAuthorize`. Do XC-02
  and XC-13 first; XC-04 needs the owner's approval.

#### XC-02 — SpEL bean names derive from class names; a rename fails as a silent 400

- **Evidence.**
  - 8 SpEL beans; only `exchangeGate` and `connectedAppsGate` carry an explicit name (javap stereotype
    scan, appendix A). The other six take Spring's default decapitalised class name.
  - On an unresolvable bean or method, `org.springframework.security.authorization.method.ExpressionUtils`
    (spring-security-core 7.1.1 bytecode, offsets 127-144) catches the `EvaluationException` and throws
    `IllegalArgumentException("Failed to evaluate expression …")`.
  - `GlobalExceptionHandler.handleIllegalArgument` (`GlobalExceptionHandler.java:594-611`) answers
    **400 `ILLEGAL_ARGUMENT`**.
  - Result: after a class rename, every endpoint gated on that bean answers 400 — fail-closed, no data
    — but none of the 5xx or ERROR-rate signals (`Http5xxRateHigh`, `LogbackErrorSpike`, vault
    `Observability`) fire.
  - Tests catch it only for endpoints a context test actually calls. `@MockitoBean` replaces a bean by
    type; 95 backend test files use it.
- **Impact.** Every rename or move of a policy class is a potential silent outage of gated endpoints.
  The per-domain policies of XC-01 multiply the number of such beans.
- **Proposed change.**
  1. Give every SpEL-referenced bean an explicit, stable name (`@Service("ownerScopeService")` etc.).
  2. Add `PreAuthorizeBeanReferenceTest` (a Spring context test):
     - enumerate `RequestMappingHandlerMapping` handler methods and all `@PreAuthorize`-annotated bean
       methods;
     - parse `@name.method(` tokens;
     - assert `context.containsBean(name)` and that a public method `method` with that arity exists on
       the bean's type;
     - assert the number of checked expressions ≥ today's 166 references (ratchet).
  3. Optionally make `SpelEvaluationException` inside method security a 500 with ERROR, so it is
     alerted on (a behaviour change of REQ-API problem codes — owner decision).
- **Pros.** Turns a runtime-only, alert-blind failure into a build failure; costs about one test class.
- **Cons.** Explicit names duplicate the class name until the classes move.
- **Risks (security) and guard.** None added: names only become explicit. Guard: the new test itself,
  plus the existing `everyExchangeControllerMethodCarriesTheExchangeGate` (already name-stable).
- **Effort** S. **Prerequisites** none; do it **first**.

#### XC-03 — Tenancy: scope JPQL fragments are constant-inlined, package-private and cross-domain

- **Evidence.**
  - `repository/ScopeSpecifications.java:48,64,79,92,105,119` declares six **package-private**
    `static final String` fragments.
  - They are spliced 29× into 7 repositories:

    | Repository | Fragment | Uses |
    | --- | --- | --- |
    | InventoryItem | `INVENTORY_ITEM_SCOPE_TRIPLE` | 12 |
    | Ship | `SHIP_SCOPE_TRIPLE` | 5 |
    | RefineryOrder | `REFINERY_ORDER_SCOPE_TRIPLE` | 4 |
    | Operation | `OPERATION_SCOPE_PREDICATE` | 3 |
    | Mission | `MISSION_SCOPE_PREDICATE` | 2 |
    | JobOrder | `JOB_ORDER_SCOPE_PREDICATE` | 2 |
    | MaterialExchangeOffer | `INVENTORY_ITEM_SCOPE_TRIPLE` (reuses inventory's triple) | 1 |

  - Because javac folds constants, `ScopeSpecifications` has **0** incoming and outgoing class edges
    (jdeps, appendix B).
  - The Operation fragment embeds the mission entity `MissionParticipant` (`:55`); the JobOrder fragment
    embeds `TYPE(o.responsibleOrgUnit) = SpecialCommand` (`:123`).
  - 15 service classes consume `ScopePredicate` (grep). The 7 repositories carry 99 lines naming the
    triple's parameters, mostly `@Param` bindings (grep). At least one query hand-writes a deliberately
    narrowed variant instead of splicing a fragment: `MissionRepository.java:151-157`, the next-mission
    banner — no admin all-scope, no public escape.
- **Impact.**
  - Moving repositories out of `repository` breaks compilation (package-private), which invites
    "make it public" or copy-paste. Copies are exactly the drift `REQ-DATA-010` exists to prevent: "an
    escape … has to be mirrored across three scoped queries and a canSee gate".
  - The cross-domain entity names in the fragments are invisible to any module checker (XC-15).
- **Proposed change.**
  - Give each domain its own `…ScopeQueries` holder with **public** constants, next to its repositories
    and its access policy (XC-01). Leave in the kernel only the documented parameter contract
    (`isAdminAllScope`, `activeOrgUnitId`, `memberOrgUnitIds`, `viewerUserId`,
    `viewerIsMemberOrAbove`).
  - Operation's participant escape keeps its JPQL but references the mission module's entity through an
    **allowed** dependency (operation → mission). It is already a DB edge (`mission.operation_id`).
  - MaterialExchangeOffer's reuse of the inventory triple becomes an explicit dependency
    (materialexchange → inventory). Offers already FK `inventory_item`.
- **Pros.** Each fragment sits with the only code that must agree with it; package moves stop forcing
  visibility hacks.
- **Cons.** Six more public constant holders; the kernel cannot template the alias (compile-time
  constants), so each domain keeps its own triple text.
- **Risks (security) and guards.**
  - **Silent under-scoping or over-scoping** if a copy diverges. **Guard (new, before the move):** a
    *list/gate agreement* integration test per aggregate. It runs every scoped repository method and the
    matching per-row gate over the XC-01 fixture matrix and asserts `list ⊆ {row | canSee(row)}` and
    `{row | canSee(row)} ∩ fixtures ⊆ list`. Today only `JobOrderScopeQueryIntegrationTest` exists
    (file list, appendix J).
  - **A repository query that drops the triple.** **Guard (new):** a test that reads every `@Query`
    value (resolved constants via reflection) whose root entity is a tenant-scoped aggregate and asserts
    it binds `:isAdminAllScope` **or** `:activeOrgUnitId`, or carries an `@Unscoped("reason")` marker.
    The `:activeOrgUnitId` alternative admits deliberate narrowed variants such as the next-mission
    banner.
- **Effort** M. **Prerequisites** REQ-DATA-010 wording ("one place" becomes "one place per aggregate")
  and XC-01.

#### XC-04 — The bank's org-unit seam rules are keyed on the class that XC-01 dissolves

- **Evidence.**
  - `orgUnitAwareBankSeamIsContainedToOneClass` (`ArchitectureTest.java:1853-1885`) selects classes that
    depend on both the FQN strings `…service.OwnerScopeService` and `…repository.BankAccountRepository`.
  - `bankClassesMustNotConsultOrgUnitScope` (`:1794-1805`) forbids `Bank*`-named classes from depending
    on `…service.OwnerScopeService`.
  - ADR-0020/0028 pin the seam to `OrgUnitBankAccessService`. The vault (`Security`) records that it
    "cannot be refactored at will … anything further needs the ADR amended first, with the owner's
    approval".
- **Impact.** As soon as the bank seam reads the scope kernel (XC-01) instead of `OwnerScopeService`,
  both rules stop matching anything relevant — vacuous — while a second bridge could appear unseen. A
  `bank` module whose classes drop the `Bank` prefix (e.g. `bank.internal.LedgerService`) also escapes
  the prefix rule.
- **Proposed change.**
  - Re-key both rules **before** XC-01 touches the bank.
  - "Bank classes" = classes in the bank module, or carrying a `@BankModule` marker — not a name
    prefix.
  - "Org-unit scope" = any class of the scope kernel **or** the `OwnerScopeService` facade, via class
    literals.
  - Keep "exactly one bridging class".
- **Pros.** Keeps the most sensitive invariant (REQ-BANK-008) true through the refactor.
- **Cons.** Needs owner approval (ADR-0020 amendment).
- **Risks (security).** Weakening REQ-BANK-008 unnoticed. **Guard:** the re-keyed rules, plus a
  size-assertion that the selected bank set is ≥ today's `Bank*` class count and that the bridge set is
  exactly `{OrgUnitBankAccessService}`.
- **Effort** S. **Prerequisites** ADR-0020 amendment (owner approval); must precede XC-01's bank step.

#### XC-05 — Audit: keep the in-transaction recorder, promote the event catalogue into an audit API

- **Evidence.**
  - **Call sites (javap -c).** 206 `AuditService.record` invocations in 57 classes and 51
    `BankAuditService.record` invocations in 14 classes. Both methods are
    `@Transactional(propagation = MANDATORY)` (`AuditService.java:80`, `BankAuditService.java:86`).
  - **The catalogue.** `AuditEventType` has 202 constants, each bound to one of the 12 `AuditDomain`s:

    | Domain | Constants | Domain | Constants |
    | --- | --- | --- | --- |
    | MISSION | 35 | BLUEPRINT | 13 |
    | INVENTORY | 30 | REFINERY | 12 |
    | JOB_ORDER | 23 | HANGAR | 9 |
    | ROLE | 22 | PERSONAL_INVENTORY | 6 |
    | PROMOTION | 17 | OPERATION | 6 |
    | CONNECTED_APPS | 15 | | |
    | MARKET | 14 | | |

    `AuditEventType` has fan-in 63 classes; `AuditDetails` 58.
  - **Cross-domain writes** (writer domain → audit domain, named constants): orgunit→ROLE 19,
    identity→ROLE 12, catalogue→REFINERY 5 (`RefiningMethodService`, `UexRefinerySyncService`),
    joborder→INVENTORY 4, exchange→INVENTORY 1, hangar→MISSION 1, refinery→INVENTORY 1, and
    identity→BLUEPRINT/INVENTORY/PERSONAL_INVENTORY/REFINERY (purges) (appendix C).
  - **Names are a persistence and monitoring contract.** `audit_event.event_type`/`domain` store enum
    names and are deliberately not CHECK-constrained (`V179__create_audit_event.sql:10`).
    `basetool_audit_events_total{domain}` uses `AuditDomain.name()` (`AuditService.java:105-107`), and
    `AuditDomainSilenceAnomaly` exempts domains by name (vault `Audit`).
  - **Frontend mirrors.** `AuditDomains.ALL` (13 tabs), `EVENT_TYPES_BY_DOMAIN` pinned to the
    `openapi.json` enum by `AdminAuditLogPageControllerTest:259`, and 202 `admin.audit.event.*` keys.
    PDF titles per domain are pinned by `AuditPdfTitleKeysTest`.
- **Impact.** Audit is a legitimate platform dependency. Every domain *must* depend on the recorder,
  because REQ-AUDIT-001 requires the insert in the business transaction. The enum is a central registry,
  but splitting it per domain would break the persisted names' global uniqueness, the openapi enum, the
  frontend pin and the PDF keys for no gain in separation.
- **Proposed change.**
  - Create `…backend.audit.api` holding `AuditRecorder` (an interface implemented by `AuditService`,
    keeping `MANDATORY`), `BankAuditRecorder`, `AuditEventType`, `AuditDomain` and `AuditDetails`. This
    is a package move; names are unchanged.
  - Domain modules depend on `audit.api` only; `audit.internal` (repository, report, retention, PDF) is
    closed.
  - Keep **direct calls**. Do not turn audit into domain events: an async or after-commit listener
    breaks "an audit-insert failure rolls the mutation back". A synchronous `@EventListener` would work
    but removes the compile-time link between a mutation and its record.
  - Replace the cross-domain writes that are really "other domain's state changed" with a call to that
    domain's API, which records its own event. Example: `HangarService.deleteShip` recording
    `MISSION_UNIT_UPDATED` → `missionApi.detachShip(shipId)` records it.
- **Pros.** Clear owner, no behaviour change, no data migration, openapi unchanged (schemas use simple
  names).
- **Cons.** `AuditEventType` stays a shared, frequently edited file. That is acceptable: append-only,
  one reviewer, dependencies point to the platform.
- **Risks (security/audit) and guards.**
  - **A service split that loses a `record` call.** **Guard (new):** an ArchUnit ratchet
    (`FreezingArchRule`). A public method whose name starts with one of `MUTATING_METHOD_PREFIXES`
    (`ArchitectureTest.java:155-191`), in a class that writes an audited aggregate's repository, must
    reach `AuditRecorder.record` within its class. Today's exceptions are frozen, and new ones fail.
  - **Keep `controllerLayerMustNotWriteAuditRowsDirectly`,** re-keyed on `@RestController`
    (XC-13).
  - **Keep enum names byte-identical.** Guard: `AdminAuditLogPageControllerTest`, `AuditPdfTitleKeysTest`,
    the CI openapi diff.
- **Effort** M (move plus ratchet). **Prerequisites** none (package move); REQ-AUDIT-001 unchanged.

#### XC-06 — Events and notifications: an SPI that exists, a hidden bank ⇄ notification cycle, and PII payloads

- **Evidence.**
  - **Publishing: 22 `publishEvent` sites in 9 classes.**

    | Class | Sites |
    | --- | --- |
    | `UserReconciliationService` | 6 |
    | `BankBookingRequestService` | 4 |
    | `DeletionRequestService` | 4 |
    | `JobOrderService` | 2 |
    | `UserRegistrationService` | 2 |
    | `MaterialExchangeService` | 1 |
    | `MaterialRequestService` | 1 |
    | `ExchangeInstallationService` | 1 |
    | `ExchangeBulkUndoStep` | 1 |

  - **Event types.** 16 event records, the value record `OrgUnitRef` and the interfaces
    `NotificationEvent` and `BankBookingRequestEvent` sit in the central `event` package (19 files).
    14 records implement the notification SPI `NotificationEvent` (`NotificationEvent.java:41`) —
    exactly one per `NotificationEventType` — providing `eventType`, `actorSub`, `contextOrgUnits`,
    `contextAccountId`, directed recipient and params. `MemberDepartedEvent` and
    `UserApprovalDecidedEvent` do not implement it.
  - **Listeners (appendix D).**

    | Listener | Consumes | Phase |
    | --- | --- | --- |
    | `NotificationEventListener` | every `NotificationEvent` | `@Async(notificationExecutor)` + AFTER_COMMIT (`:56-58`) |
    | `PendingRegistrationMailEventListener` | `DiscordRegistrationPendingEvent` | `@Async(mailExecutor)` + AFTER_COMMIT |
    | `UserApprovalMailEventListener` | `UserApprovalDecidedEvent` | `@Async(mailExecutor)` + AFTER_COMMIT |
    | `ExchangeDepartureService.onDeparture` | `MemberDepartedEvent` | synchronous AFTER_COMMIT with `fallbackExecution = true`; writes via REQUIRES_NEW (`ExchangeDepartureService.java:114`) |

  - Three more after-commit hooks are hand-rolled with `TransactionSynchronization` (`ExchangeLiveSync`,
    `TermsAcceptanceService`, `ExchangeJournalService`).
  - **Rule engine.** `NotificationEventType` has 14 constants, stored in `notification_rule.event_type`;
    `SelectorKind` has 6.
  - **The cycle.** `RecipientResolutionService` depends on `BankAccountGrantRepository`,
    `OrgUnitBankResponsibilityService`, `OrgUnitMembershipRepository`, `UserRepository` and `Roles`
    (jdeps). The bank publishes events implementing the notification SPI, and notification reads bank
    tables: a domain cycle.
  - **PII in payloads.**
    - `UserApprovalDecidedEvent(userId, approved, recipientEmail, recipientName, reason)`
      (`UserApprovalDecidedEvent.java:39-44`)
    - `AccountDeletionRequestedEvent(userId, handle)`
    - `DiscordRegistrationPendingEvent(userId, username)`
    - `JobOrderCreatedEvent(…, handle, …)` — the order contact, often an outsider (vault `Audit`)
  - **Spring Modulith 2.1.1** (local Gradle cache, pom read 2026-09-29) compiles against Spring Boot
    4.1.1 / Framework 7.0.9. Its `JpaEventPublication` stores `serializedEvent` (String) and `eventType`
    (Class) in table `EVENT_PUBLICATION` (javap of `spring-modulith-events-jpa-2.1.1.jar`).
    `@ApplicationModuleListener` is meta-annotated `@Async`, `@Transactional(propagation =
    REQUIRES_NEW)` and `@TransactionalEventListener` (javap of `spring-modulith-events-api-2.1.1.jar`).
  - **Spring's guard on listener transactions.** spring-tx 7.0.9's
    `RestrictedTransactionalEventListenerFactory` throws `IllegalStateException` for a
    `@TransactionalEventListener` that is `@Transactional` with any propagation other than
    REQUIRES_NEW or NOT_SUPPORTED (bytecode).
- **Impact.**
  - The notification SPI is already a good module API. What breaks separation is (a) the central
    `event` package holding every domain's events, and (b) the notification → bank read.
  - A persistent event registry would store names and e-mails at rest. It would also make every
    event-class move a data migration, because the class name is persisted.
- **Proposed change.**
  1. `notification.api` exports `NotificationEvent`, `NotificationEventType` (kept central: persisted in
     rules and pinned by `AdminNotificationRuleOptionListsTest`), `OrgUnitRef` and a
     **`RecipientDirectory` port**, e.g. `Set<UUID> holdersOfGrant(UUID accountId)` and
     `Set<UUID> responsibleHolders(UUID accountId)`. The bank implements the port, which inverts the
     cycle. Organisation reads go to the orgunit API.
  2. Each publishing domain keeps its event records in its own `api.events` package.
  3. Keep AFTER_COMMIT + `@Async` (REQ-NOTIF-002).
  4. Before any durable publication registry: make payloads **id-only** (listeners resolve e-mail or
     handle by id at send time — better for Art. 17 too). Persist only `MemberDepartedEvent`-style
     id+reason events.
  5. Unify the three hand-rolled after-commit hooks on one small `AfterCommit` helper or on
     `@TransactionalEventListener`.
- **Pros.** Breaks the bank ⇄ notification cycle; each domain owns its events; aligns with Modulith's
  `@ApplicationModuleListener`, which is `@Async` + REQUIRES_NEW + AFTER_COMMIT — the same shape as
  today's notification and mail listeners.
- **Cons.** The rule table still needs a central list of event types. Id-only payloads cost one read
  per mail.
- **Risks (security) and guards.**
  - **PII at rest in a new table.** **Guard:** `PersonSearchCoverageTest` already sweeps
    `information_schema` and would fail on a new text column (ADR-0184). Keep it mandatory; add
    retention and the erasure path.
  - **A departure event lost between commit and listener** leaves a member's exchange consent
    unrevoked. This is mitigated today because the backend's role/approval gates refuse a role-less
    acting member (REQ-XCH-009 text); a durable registry would close the gap.
  - **Listener phase regressions.** A synchronous AFTER_COMMIT listener writing through a REQUIRED
    method silently writes nothing. **Guard:** spring-tx 7.0.9 already refuses the annotation shape
    (`@TransactionalEventListener` + `@Transactional` other than REQUIRES_NEW/NOT_SUPPORTED), but not a
    programmatic write. Keep using `TransactionTemplate(REQUIRES_NEW)` as `ExchangeDepartureService`
    does. Add an ArchUnit rule: a method annotated `@TransactionalEventListener` must not call a
    repository `save*` except through a REQUIRES_NEW boundary.
  - **Event names are persisted in rules.** Guard: `AdminNotificationRuleOptionListsTest` (pinned to
    openapi).
- **Effort** M. **Prerequisites** ADR for the event/module API; REQ-NOTIF-002 unchanged; Modulith is
  optional (XC-15).

#### XC-07 — Synchronous cross-domain reactions must stay in-transaction; make them module APIs, not events

- **Evidence (callers from jdeps).**
  - `MaterialExchangeOfferRatchet` (clamps offers when stock drops and records `MARKET_OFFER_REDUCED` or
    `MARKET_OFFER_REMOVED`) is called from `ExchangeStockWriteService`, `InventoryCheckoutService`,
    `JobOrderHandoverService`, `JobOrderItemHandoverService`, `JobOrderItemProductionService` and
    `UserDeletionService`.
  - `InventoryOrgUnitReconciler` is called from `OrgUnitMembershipService`.
  - `OrgUnitBankResponsibilityService` is called from `OrgUnitMembershipService`,
    `RecipientResolutionService` and `UserDeletionService`.
  - `MasterDataCacheEvictionService` is called from `P4kImportJobRunner`, `ScWikiScheduler` and
    `UexScheduler`.
  - The vault (`Audit`) records that the offer reduction must be in the same transaction as the stock
    change: PR #2162.
- **Impact.** These look like event candidates ("stock changed → board reacts"). An after-commit event
  would split one audited change across two transactions (REQ-AUDIT-001), and a failure would leave an
  offer larger than its stock.
- **Proposed change.** Expose each as a published API of the **owning** domain:
  `materialexchange.api.OfferStockGuard.onStockLowered(itemId, newAmount, reason)`,
  `inventory.api.OrgUnitRestamp`, `bank.api.ResponsibilityCleanup`. Callers keep calling them
  synchronously. If an event style is preferred, use a synchronous `@EventListener` with
  `@Transactional(MANDATORY)` in the listener — never AFTER_COMMIT.
- **Pros.** Makes today's hidden obligations visible module edges.
- **Cons.** Keeps compile-time coupling (inventory → materialexchange API). That is honest: the business
  rule couples them.
- **Risks and guards.** Moving to AFTER_COMMIT by mistake loses offer clamping on failure. **Guard:**
  `MaterialExchangeOfferRatchetDataTest` (exists), plus an ArchUnit rule that these API methods are
  `@Transactional(propagation = MANDATORY)`.
- **Effort** S–M. **Prerequisites** none.

#### XC-08 — Live sync: a platform module with a per-domain authorisation SPI

- **Evidence.**
  - **Backend.**
    - `support.LiveSyncTopicClass` — 13 topic classes.
    - `LiveSyncRelayService.publishFromServer` (`:150`).
    - `LiveSyncStreamService` — the app SSE bridge.
    - `LiveSyncSubscriptionAuthorizer` switches on the authorisation kind (`:85-98`) into
      `ownerScopeService.canSeeMission/canSeeOperation/canSeeJobOrder/canViewJobOrders/canSeeRefineryOrder`,
      `OrgUnitBankAccessService` and `authHelperService`.
    - The **only backend domain publisher** is `ExchangeLiveSync` (`:115-123`, after commit).
  - **Frontend.**
    - `websocket.LiveSyncTopicClass` — 17 topic classes, including 7 hardcoded backend probe paths
      (`LiveSyncTopicClass.java:54,68,92,106,119,133,135`).
    - `LiveSyncLocalBus` — 24 server-side publish sites in 4 write controllers (grep:
      `RefineryOrderWriteController` 10, `MemberManagementController` 6, `JobOrderWriteController` 4,
      `MissionWriteController` 4).
    - Browser broadcasts via `krt-live-sync.js`.
  - **Parity tests.**
    - `LiveSyncTopicRegistryParityTest` reads the frontend enum by source path; it is a declared Gradle
      input, `backend/build.gradle.kts`.
    - `LiveSyncSectionMapParityTest` reads about 30 fixed `/static/js/*.js` resource paths.
- **Impact.** Live sync is already topic-agnostic (a new room needs no handler change: vault
  `Live Sync`). Its domain knowledge is concentrated in the authorizer's switch and the two
  topic-class enums.
- **Proposed change.**
  - `livesync.api` exposes `LiveSyncPublisher.afterCommit(topic, sections)`, generalising
    `ExchangeLiveSync.afterCommit`, and `LiveSyncTopicAuthorizer`, an SPI implemented by each domain's
    access policy (XC-01) and looked up by topic class.
  - Topic classes stay one registry per module, pinned by the parity tests, because they are a
    monitoring contract: `topic_class` label, REQ-OBS-011.
- **Pros.** Removes the authorizer → hub dependency; lets domain modules announce their own server-side
  writes without calling the relay's internals.
- **Cons.** The two enums stay mirrored by design (frontend/backend separation, ADR-0205).
- **Risks (security) and guards.**
  - **The fail-open/fail-closed rule** (`presenceEnabled ? DENY : ALLOW`, only MISSION presence-enabled)
    must survive the SPI. **Guard:** existing authorizer tests, plus a test that every
    presence-enabled class's SPI returns DENY on exceptions.
  - **API re-cut (XC-22).** A probe path that 404s makes resource probes DENY: live sync silently stops
    for that room. **Guard (new):** every frontend probe path exists in `openapi.json` as a GET.
- **Effort** M. **Prerequisites** ADR-0094/0143 note; XC-01.

#### XC-09 — Exchange: a second write path into four domains; the guard stops at the controllers

- **Evidence (jdeps, service.exchange → non-exchange classes, appendix E).**
  - **14 foreign repositories:** `InventoryItemRepository`, `ShipRepository`,
    `PersonalBlueprintRepository`, `MaterialExchangeOfferRepository`, `JobOrderRepository`,
    `OrgUnitMembershipRepository`, `NotificationRepository`, `UserRepository`, `BlueprintRepository`,
    `GameItemRepository`, `LocationRepository`, `MaterialRepository`, `MaterialExternalAliasRepository`,
    `ShipTypeRepository`.
  - **22 foreign services**, e.g. `HangarService`, `InventoryCheckoutService`,
    `InventoryStolenMarkService`, `MaterialExchangeOfferRatchet`, `PersonalBlueprintService`, six
    `Blueprint*` services plus `DefaultBlueprintKeyService`, `JobOrderStockProjectionService`,
    `RefineryImportService`, `OwnerScopeService`, `AuditService`.
  - **Direct aggregate writes.** `ExchangeStockWriteService` creates `InventoryItem` rows itself
    (`:798-818`: `inventoryRepository.save(item)` plus an INVENTORY audit event). It holds lot-lock
    queries on `InventoryItemRepository` (`:483-527`, ADR-0229).
  - **ArchUnit covers only the controller side.** `exchangeControllersCallExchangeServicesOnly`
    (`ArchitectureTest.java:506-522`) constrains controllers, and its "foreign service" predicate is
    `getPackageName().contains(".backend.service")`, which a domain package like
    `backend.inventory.service` does not match (XC-13).
  - **DB coupling.** Four V252 triggers on `personal_blueprint`, `default_blueprint`, `inventory_item`
    and `ship` write `exchange_change` and read `current_setting('basetool.change_source')`
    (XC-21).
- **Impact.** Inventory, hangar and blueprint invariants (audit event, offer clamp, stack merge, lock
  order) are implemented twice — once in the domain service and once in the exchange. A domain module
  cannot own its aggregate while the exchange writes it directly.
- **Proposed change.** Define the exact API the exchange needs from each domain. Every method takes the
  acting member from the exchange caller, never as a free parameter, so "own data only" (REQ-XCH-009)
  holds by construction.

  | Domain | API the exchange needs |
  | --- | --- |
  | hangar | `OwnShips`: list with link keys, lock owned, upsert/remove by key |
  | inventory | `PersonalStock`: `lockLots(member, keys)` in ADR-0229 order, lots by member, book-in/set-quantity/book-out/mark-stolen on own personal lots; it calls the offer guard of XC-07 itself |
  | blueprint | `OwnBlueprints`: feed, set/remove own products, default keys |
  | joborder | `DemandQuery` for the member's direct memberships |
  | catalogue | `CatalogueLookup`: materials, items, locations, ship types by name or alias |
  | refinery | `RefineryDraft` |
  | identity | `MemberStatus` |
  | notification | `MarkInstallationSeen` |

  The exchange then depends on those APIs only; the ArchUnit or Modulith rule becomes "`exchange` →
  `*.api` only".
- **Pros.** One implementation per invariant; the domain owns its locks and audit.
- **Cons.** Large. The exchange went live 2026-09-28 and its contract is frozen: behaviour must stay
  byte-identical.
- **Risks (security, concurrency) and guards.**
  - **Lock-order change** means deadlocks or lost lots. **Guard:** `ExchangeStockLookupPlanIntegrationTest`,
    concurrency tests on `lockLots`.
  - **Admin acting through the exchange.** Guard: `ExchangeAdminActingMemberTest` (REQ-XCH-009) and
    `exchangeServicesNeverUseAdminGatesOrTheAdminScope` — re-keyed per XC-13, because it matches
    `currentScopePredicate` on the simple name `OwnerScopeService`.
  - **Contract drift.** Guard: `ExchangeContractTest`, `ExchangeRoutesContractTest` (ingest), the
    frozen `exchange-v1.openapi.json`.
  - **Changed audit events.** Guard: `AdminAuditLogPageControllerTest` pin plus an explicit assertion
    of the event sequence per exchange write.
- **Effort** L–XL. **Prerequisites** XC-01, XC-07; REQ-XCH-009 amendment ("services" → "domain APIs");
  owner decision on sequencing after go-live.

#### XC-10 — GDPR and other all-domain iterators: keep the registries central, SPI the erasure steps

- **Evidence.**
  - **Native-SQL registries** over every domain's tables:

    | Registry | Size |
    | --- | --- |
    | `DataExportSections.SECTIONS` | 36 sections (`DataExportSections.java:61`) |
    | `PersonSearchTargets` | 80 `new Target(` entries, 19 areas, 39 tables (`:47,83`) |
    | `HandleErasureCoverage.COVERAGE` | 79 `table.column` keys in 42 tables (`:80`) |
    | `UserAccountMergeService` | native SQL |

    All are read via `EntityManager.createNativeQuery` — 6 dynamic-query sites in total (grep).
  - **Code-level fan-out.** `UserDeletionService` depends on 15 repositories of 10 domains plus
    `AuditService`, `KeycloakService`, `MaterialExchangeOfferRatchet` and
    `OrgUnitBankResponsibilityService`. `HandleAnonymisationService` depends on 7 bank classes, 5 audit
    classes and 3 job-order classes.
  - **Schema sweeps, independent of packages:** `PersonSearchCoverageTest`,
    `HandleErasureCoverageTest`, `DataExportScrubCoverageTest`, `UserAccountMergeCoverageTest`,
    `UserIdentityColumnForeignKeyTest`, `ForeignKeyIndexCoverageTest`, `HandleSpellingCoverageTest`.
- **Impact.** The registries are *data declarations* (a legal register, ADR-0184: "one reviewable list
  is a feature") and are already gated against the schema. The real code coupling is the deletion and
  merge orchestration.
- **Proposed change.**
  - Keep the export, search and erasure registries central in an `identity.privacy` module.
  - Introduce a `MemberDataEraser` SPI: each module implements `eraseOrDetach(userId)` in the order the
    orchestrator defines, inside `UserDeletionService`'s single transaction. The same applies to account
    merge.
- **Pros.** Removes 15 foreign repository dependencies from identity; each module handles its own FKs to
  `app_user`.
- **Cons.** Ordering is now spread across beans (`@Order`), and a missing implementation is silent
  unless guarded.
- **Risks (security, GDPR) and guards.**
  - **A module without an eraser** means personal data retained after erasure. **Guard (new):** every
    FK to `app_user` (53 today, appendix I) is owned by exactly one registered eraser; this extends
    `UserIdentityColumnForeignKeyTest`.
  - **Keep the seven schema sweeps unchanged** — they are the package-independent safety net.
- **Effort** M. **Prerequisites** REQ-SEC-062 / ADR-0183 unchanged; XC-05 (audit API).

#### XC-11 — Scheduled jobs and business metrics: move jobs home, keep the label vocabulary

- **Evidence.**
  - **15 `@Scheduled` methods** (appendix D): 11 task classes in the central `task` package, plus
    `UexScheduler`, `ScWikiScheduler` and the two SSE heartbeats.
  - **Label vocabularies.** `ScheduledJob` has 14 values, the bounded `task` label
    (`ScheduledJob.java:36-102`) that alert rules select by name (`business.yml:52,61,132,141,150,239`).
  - **`BusinessMetricsCollector`** is one class with one `@Scheduled` refresh. It polls 9 repositories
    of 7 domains for queue gauges (`:113-136`): bank booking requests, deletion requests, users, job
    orders, material-exchange offers and requests, operations, P4K import jobs, refinery orders.
  - **Metric names.** `MetricNames` has 182 constants and 59 dependents.
  - **No annotation-derived metrics.** There is no `@Timed`, `@Observed` or `@Counted` anywhere
    (javap), and no rule or dashboard selects `code_namespace`/`code_function` (grep of `monitoring/`).
- **Impact.** Jobs and gauges are the one place where "a new feature must add monitoring"
  (REQ-OBS-005…011) is centralised. That is convenient, but every domain feature edits `task` and
  `BusinessMetricsCollector`.
- **Proposed change.**
  - Each module owns its tasks and a `MeterBinder` or `QueueGaugeSource` for its gauges, with
    **identical** names and labels.
  - `ScheduledJob` stays the central bounded vocabulary (a monitoring contract), or becomes per-module
    enums implementing `ScheduledJobName` with a uniqueness test.
  - `MetricNames` constants move to the emitting module.
- **Pros.** Domain changes stay in the domain; the monitoring contract is unchanged.
- **Cons.** A cross-module list of `task` values is still needed for the alerts.
- **Risks and guards.**
  - **A silently lost task.** Under Option B a module outside the scan root drops its `@Scheduled` with
    no error (XC-23). **Guard (new):** a context test asserting the `ScheduledTaskHolder` task set
    equals the expected list, plus promtool tests (exist) and `TaskMetricsTest`.
  - **A renamed metric.** **Guard (new):** a snapshot of registered meter names.
- **Effort** S–M. **Prerequisites** REQ-OBS-011 unchanged.

#### XC-12 — Service-level `@PreAuthorize` makes a service split a potential self-invocation bypass

- **Evidence.** 18 `@PreAuthorize` sit on service methods (7 classes):
  - `hasAnyRole('ADMIN','OFFICER')` (`Roles.ADMIN_OR_OFFICER`, e.g. `PromotionTopicService.java:126,167`)
    on `create/update/delete` of `PromotionTopicService`, `PromotionCategoryService`,
    `PromotionLevelContentService` and `RankRequirementService`, on `listAll/upsert/delete` of
    `MemberEvaluationService`, and on `PromotionEligibilityService.evaluateAllForUserAsAdmin`;
  - `@missionSecurityService.canEditFinanceEntry` on `MissionFinanceEntryService.updateEntry/deleteEntry`
    (appendix A).
- **Impact.** These gates only work through the Spring proxy. A refactor that merges a facade with its
  delegate, or moves a caller into the same class, turns them into self-invocations and skips the check
  without any error.
- **Proposed change.** Either move these gates to the controllers (the project's norm, REQ-SEC-002), or
  add an ArchUnit rule: no method may call a `@PreAuthorize`-annotated method of its own class.
- **Pros.** Removes a refactor-only bypass class. **Cons.** Moving gates changes which layer refuses:
  still 403.
- **Risks (security) and guard.** Guard: the new rule plus the existing promotion MockMvc tests.
- **Effort** S. **Prerequisites** none.

### Half 2 — refactor-safety inventory (what breaks silently)

#### XC-13 — ArchUnit: most rules are keyed on layer packages; `failOnEmptyShould` only catches total emptiness

- **Evidence.**
  - **Rule counts.** Backend `ArchitectureTest` has 43 `@Test` rules, frontend 7, ingest 4 (grep).
  - **Keys in the backend file** (appendix F):
    - 47 `..backend.<layer>..` package patterns;
    - 4 `getPackageName().contains(".backend.<layer>")` filters;
    - 35 FQN string literals, including one package pattern and four method-FQN prefixes;
    - 8 simple-name matches;
    - 2 simple-name sets (12 services, 7 controllers).
  - **Empty-selection behaviour.** No `archunit.properties` in any module (find). ArchUnit 1.5.1
    `AllowEmptyShould.AS_CONFIGURED` reads `archRule.failOnEmptyShould` with default `TRUE` (bytecode
    of `AllowEmptyShould$3.isAllowed`), and `SimpleArchRule.evaluate` → `verifyNoEmptyShouldIfEnabled`
    throws on an empty selection (archunit-1.5.0 sources). Two rules opt out with
    `allowEmptyShould(true)` (`ArchitectureTest.java:718,1693`).
  - **Classification under a move to `backend.<domain>.…` (appendix F):**

    | Class of behaviour | Rules |
    | --- | --- |
    | Can turn **vacuous** | 20 |
    | **Weaker** under partial moves | 14 |
    | Changes **meaning** (`slices().matching("…backend.(*)..")` becomes a domain-cycle rule) | 1 |
    | Robust or loud | 8 |

    The vacuous cases arise where the target side is a package or FQN string, the rule is a
    hand-rolled loop, or it uses `allowEmptyShould`. Examples:
    - `permitAllIsDeclaredOnlyOnTheFourPublicEndpoints` (`:345`) filters
      `getPackageName().contains(".backend.controller")` with no floor, so a `permitAll()` in
      `backend.mission.web` passes;
    - `controllerLayerShouldNotDependOnRepositoryLayer` (`:427`): once repositories move, nothing
      matches;
    - `cascadeServiceMustNotConsultTheSecurityContext` (`:1812`) and
      `delegatedRoleAuthoriserMustNotConsultOwnerScope` (`:1833`) name their target by FQN string.
  - **Floor headroom.** `peerReadableMissionEndpointsMustRedactPii` (`:1129`) asserts ≥ 10 selected
    endpoints. By a javap approximation of its predicate, today it selects about 23:
    `MissionController` 20, `MissionFinanceEntryController` 3. About 13 endpoints could leave the
    `controller` package unnoticed.
- **Impact.** Package-by-domain is precisely the change that disarms this gate while the build stays
  green. 18 of the 34 weakenable rules guard authorisation, tenancy, redaction or the exchange's reduced
  authority.
- **Proposed change — before moving any class.**
  1. **Select by role, not package.**

     | Role | Selector |
     | --- | --- |
     | controllers | `areAnnotatedWith(RestController)` |
     | services | `areAnnotatedWith(Service)` |
     | repositories | `areAssignableTo(org.springframework.data.repository.Repository)` |
     | mappers | `areAnnotatedWith(org.mapstruct.Mapper)` |
     | entities | `@Entity` |

  2. **Name targets by class literal**, not FQN string. The file already does this for
     `AuditService.class` (`:464`). A move then becomes a compile-time edit instead of a vacuous rule.
  3. **Add selection ratchets** to every rule: assert the size of the selected set ≥ today's count, or
     equal to the set derived from annotations.
  4. **Replace `allowEmptyShould(true)`** with explicit floors, and convert hand-rolled loops into
     ArchRules or add floors.
  5. **Add a second slices rule** for intra-domain layers (`…backend.(*).(*)..`) once domains exist.
  6. **Adopt `FreezingArchRule` only for baselining new rules**, never for the security rules
     (ADR-0047 rejected freezing).
- **Pros.** Makes the gate move-proof; the cost is one PR touching one test file.
- **Cons.** Ratchets must be lowered deliberately when classes are deleted.
- **Risks (security).** Rewriting a security rule can itself weaken it (vault `Security`: "a guard
  whose selection is wrong reads as a guard that passes"). **Guard:** mutation-test each rewritten rule
  once — plant a violating class in a test fixture package and assert the rule fails — the "prove the
  gate can fail" pattern the repo already uses for `check-alloy-log-masking.py`.
- **Effort** M. **Prerequisites** none; **must precede every package move**.

#### XC-14 — Simple-name whitelists exempt every new or split controller and service

- **Evidence.**
  - `staffelScopedWriteEndpointsMustGateOnOwnerScopeService` (`:1036-1118`) checks only
    `MissionController`, `OperationController`, `HangarController`, `InventoryItemController`,
    `RefineryOrderController`, `SpecialCommandController` and `SpecialCommandMembershipController`, and
    matches the SpEL by substring (`ownerScopeService`, `missionSecurityService`, …).
  - `staffelScopedServicesMustWireOwnerScopeOrAuthHelper` (`:978-1025`) checks 12 service names.
  - All names exist today (checked).
- **Impact.** The owner update allows controllers and paths to be re-cut per domain. A new
  `MissionCrewController` or `JobOrderMaterialController` is **not** selected, so a `{id}` write without
  a scope gate compiles, passes ArchUnit and allows cross-Staffel writes (layer-4 failure, silent).
- **Proposed change.**
  - Mark tenant-scoped aggregates (`@TenantScoped`, or jMolecules `@AggregateRoot` plus an attribute).
  - Select write endpoints by "a `@PathVariable UUID` whose resolved aggregate is tenant-scoped", or
    simply every `{id}` write in a controller of a tenant-scoped module, with an explicit
    `@ScopeExempt("reason")`.
  - Accept any *registered* policy bean name (XC-01) instead of substrings.
- **Pros.** New controllers are covered automatically. **Cons.** Needs a marker per aggregate or module.
- **Risks (security).** The marker itself can be forgotten. **Guard:** a test that every entity with an
  `owningOrgUnit`/`responsibleOrgUnit` field carries `@TenantScoped`.
- **Effort** S–M. **Prerequisites** XC-13; REQ-ORG-006 wording.

#### XC-15 — Couplings that no class-graph checker (jdeps, ArchUnit, Spring Modulith) can see

- **Evidence.**

  | Coupling | Count | Where |
  | --- | --- | --- |
  | Scope JPQL constants inlined by javac | 29 splices, 7 repositories | `ScopeSpecifications` (0 jdeps edges) |
  | SpEL bean references in `@PreAuthorize` | 166 to 8 beans | appendix A |
  | FQCNs inside `@Query` strings | 93 in 68 queries, 27 repositories (47 constructor expressions, 42 enum literals) | appendix G |
  | JPQL entity names across domains | 18, e.g. `OperationRepository` → `MissionParticipant`, `JobOrderRepository` → `SpecialCommand` | `20-crosscutting-jpql.py` |
  | Native queries | 29 | same |
  | Thymeleaf `T(de.greluc…frontend.support.Roles)` | 172 in 22 templates | grep |
  | Template bean refs (`@moneyFormat` 70, `@handles` 44, `@markdown` 6) | explicitly named, robust | grep |
  | Native-SQL GDPR registries | 36 + 80 + 79 entries | XC-10 |
  | DB triggers spanning domains | 4 change-feed + 6 guard triggers | XC-21 |
  | `Roles` constants folded into `@PreAuthorize` | 339 source refs vs 20 jdeps edges | grep / jdeps |

- **Impact.** `ApplicationModules.verify()` (Spring Modulith 2.1.1, built on ArchUnit: its pom depends
  on archunit 1.4.2) and any jdeps metric will report these modules as separated while they are not.
  A Modulith-only gate would be a green check that checked nothing.
- **Proposed change.** Pair any module verifier with targeted tests for each row:
  - the SpEL resolution test (XC-02);
  - a `@Query` resolution test — Spring Data validates JPQL at context start already, so keep a context
    test that bootstraps all repositories;
  - a Thymeleaf `T()` scan (XC-17);
  - the list/gate agreement test (XC-03);
  - the schema sweeps (XC-10);
  - a trigger/column test (XC-21).
- **Pros.** Honest boundaries. **Cons.** More tests than a single `verify()`.
- **Risks.** Believing the verifier. **Guard:** document this list in the ADR that introduces modules.
- **Effort** S (documentation) plus the tests referenced. **Prerequisites** none.

#### XC-16 — Frontend session allow-list and stored type ids make frontend packaging a session-safety change

- **Evidence.**
  - `SessionTypeAllowList.ALLOWED_PREFIXES` = `org.springframework.security.` and
    `de.greluc.krt.profit.basetool.frontend.model.` (`:86-87`). Production runs `enforce` since
    2026-09-25 (ADR-0206 status line).
  - Default typing writes `@class` FQCNs into Redis session hashes (ADR-0206 context).
  - 28 `addFlashAttribute(…, form)` sites (grep), 12 of them in `RefineryOrderWriteController`, store
    forms from `frontend.model.form` (30 classes). `frontend.model.dto` holds 292 classes.
- **Impact.**
  - Moving forms or DTOs into `frontend.<domain>.…` makes every such session value outside the list
    **refused and dropped** under `enforce`: a flash message or form re-population lost. It is visible
    only as `basetool_session_type_refused_total` and `SessionTypeOutsideAllowList`, not as a failing
    build.
  - Separately, any rename or move of a session-stored class makes values written before the deploy
    unreadable. Those are dropped and repaired per ADR-0157.
- **Proposed change.**
  - Widen the prefix to per-domain model packages via a naming convention (`frontend.*.model.`) —
    **not** to `frontend.`, which would admit config and service classes.
  - Or keep all session-bound types under `frontend.model` by rule.
  - Add a test: every class passed to `addFlashAttribute` or `session.setAttribute` (ArchUnit call-site
    scan) is matched by the allow-list.
- **Pros.** Keeps ADR-0206's narrowing. **Cons.** A convention the team must follow.
- **Risks (security).** Widening the prefix too far re-opens the gadget surface ADR-0206 closed.
  **Guard:** the existing negative test (setter never runs), the new call-site test, and the E2E stack
  running `enforce` (ADR-0206 decision 4).
- **Effort** S. **Prerequisites** ADR-0206 amendment if the prefix changes.

#### XC-17 — Thymeleaf `T()` role references are evaluated only at render time

- **Evidence.** 172 `T(de.greluc.krt.profit.basetool.frontend.support.Roles)` in 22 templates (ADR-0059
  mechanism). No test resolves them statically: grep of `frontend/src/test` for the FQCN finds none.
  MVC tests render some pages (the sidebar is on every page).
- **Impact.** Moving `frontend.support.Roles` is a `TemplateProcessingException` at render time on every
  template not covered by an MVC test — loud in production (500), silent in CI.
- **Proposed change.** Add a template scan test: extract `T(…)` and `@bean` tokens from all templates and
  assert `Class.forName` succeeds and the bean exists. Keep `Roles` in a stable shared package.
- **Pros.** Cheap and complete. **Cons.** None.
- **Risks and guard.** None; the test is the guard. **Effort** S. **Prerequisites** none.

#### XC-18 — Build and tooling configuration keyed on package or project names

- **Evidence and behaviour.**

  | Item | Where | Effect of a move |
  | --- | --- | --- |
  | PIT `targetClasses`/`targetTests` = `de.greluc.krt.profit.basetool.${project.name}.service.*` | `build.gradle.kts:267-268` | moved services are no longer mutated; `pitest.yml:61-64` fails only at **zero** mutations — **silent**; a new Gradle module (`backend-mission`) yields an invalid package and is absent from the matrix `[backend, frontend]` (`pitest.yml:23`) — **silent** |
  | JaCoCo floors keyed on `project.name` (`backend` 0.82/0.65) | `build.gradle.kts:228-241` | a new Gradle module falls back to 0.50/0.40 — **silent** weaker gate (Option B/C) |
  | SpotBugs `EI_EXPOSE_REP*` exclusion for `~…backend\.model\..*` | `config/spotbugs/exclude.xml:8` | entities or DTOs leaving `model` produce findings — **loud** (fail-safe) |
  | SpotBugs `BasetoolErrorController` exclusion | `exclude.xml:16` | moved class fails the build — **loud** |
  | Checkstyle | `config/checkstyle/*.xml` | no class-keyed suppressions — robust |
  | OWASP suppressions | `config/owasp/…` | no class names — robust |
  | `META-INF/spring.factories` `…backend.config.SandboxProfileGuard` (three modules) | `spring.factories:1` | startup failure — **loud** |
  | Backend test inputs reading other modules' sources by path | `backend/build.gradle.kts` (`crossModuleParitySources`, `liveSyncTopicRegistrySource`, `apiVhostAllowList`, `backendQuadletEnvTemplate`) | missing input fails the build — **loud** |
  | Frontend contract tests scanning `frontend.model.dto` | `FrontendDtoContractTest:56` (floor > 100), `DtoOpenApiContractTest:57`, `GeneratedDtoAgreementTest:48` (floor > 200) | DTO mirrors moved to `frontend.<domain>.dto` drop out of the check — **silent** until the floor |
  | `LiveSyncSectionMapParityTest` fixed `/static/js/*.js` paths | test `:55-450` | moved JS fails — loud; new JS unchecked (existing gap) |
  | `@SpringBootApplication` / `@ConfigurationPropertiesScan` at each module root; no `@EntityScan` or `@EnableJpaRepositories` | javap | robust within the root package; Option B with another root — see XC-23 |

- **Proposed change.**
  - Glob PIT on the module root with excludes (`de.greluc.krt.profit.basetool.backend.*`, excluding
    `*.model.*`, `*.config.*`), or keep an explicit list and assert it non-empty per domain.
  - Default JaCoCo floors for `backend-*` modules to the backend values.
  - Frontend contract tests: scan the whole frontend root for records annotated or suffixed as mirrors.
- **Pros.** Removes the silent ones. **Cons.** PIT runtime grows if the glob widens.
- **Risks.** Weaker mutation or coverage gates unnoticed. **Guard:** assert per-module mutation counts
  ≥ a ratchet in `pitest.yml`.
- **Effort** S. **Prerequisites** none.

#### XC-19 — FQCNs and entity names inside JPQL strings: loud, but expensive

- **Evidence.**
  - 93 FQCN references in 68 `@Query` annotations across 27 repositories: 47 constructor expressions
    (`SELECT new de.greluc…model.dto.…(`), 42 enum literals (15× `model.OrgUnitKind.SQUADRON`,
    7× `MaterialExchangeOfferStatus.ACTIVE`, …), 4 others.
  - Entity names default to simple class names (115 `@Entity`, none renamed via `name=` — javap).
  - `@DiscriminatorValue` stores `SQUADRON`/`SPECIAL_COMMAND`/`BEREICH`/`ORGANISATIONSLEITUNG`,
    independent of class names.
- **Impact.** Every move of an enum, DTO or projection, or rename of an entity, is a startup failure —
  loud in context tests, but a large mechanical diff that IDE refactoring handles only with "search in
  strings".
- **Proposed change.**
  - Move projections and enums used in JPQL together with their repository's domain.
  - Prefer interface-based projections (no FQCN in JPQL).
  - For enums, bind parameters (`:status`) instead of literals where the query shape allows.
  - Keep one context test that bootstraps all repositories.
- **Pros.** Fewer string-borne FQCNs. **Cons.** Parameter binding changes query plans rarely;
  measure (PG 18).
- **Risks.** None silent. **Effort** S–M, incremental. **Prerequisites** none.

#### XC-20 — Monitoring: no selector depends on Java names; enum names are the real contract

- **Evidence.**
  - Grep of `monitoring/` for `de.greluc`, `code_namespace`, `code_function` and `tasks_scheduled`:
    **0** hits.
  - Java simple names appear only in alert **descriptions** and dashboard descriptions: 14 names, e.g.
    `SecurityConfig`, `SessionTypeAllowList`, `ExchangeGate` (`20-crosscutting-monitoring.py`).
  - Label values that **are** code-derived and selected by rules:
    - `task` (the `ScheduledJob` strings);
    - `domain` (`AuditDomain.name()`, exempted by name in `AuditDomainSilenceAnomaly`);
    - `topic_class` (`LiveSyncTopicClass` metric labels);
    - `exception` on `http_server_requests`, which is the exception's simple name — no rule selects it
      today.
  - Loki rules match JVM wording, pinned by `scripts/check-loki-rule-signatures.py`, and not logger
    names. `logback-spring.xml` names only the root `de.greluc.krt.profit.basetool`.
- **Impact.** Class moves are monitoring-neutral. Renaming those **enum constants** is not: it would
  silently retarget alerts.
- **Proposed change.**
  - Treat `AuditDomain`, `ScheduledJob`, `LiveSyncTopicClass` metric labels and `NotificationEventType`
    names as frozen.
  - Add a test that the set of label values used in `monitoring/prometheus/alerts/*.yml` for `task`,
    `domain` and `topic_class` is a subset of the enum values. This follows the pattern of the existing
    parity tests.
  - Update the prose descriptions when classes are renamed (cosmetic).
- **Pros.** Protects alerts against the one rename that matters. **Cons.** One more parity test.
- **Risks.** A silently dead alert. **Guard:** the new test and the promtool tests.
- **Effort** S. **Prerequisites** none.

#### XC-21 — Database coupling: a star around identity, org units and the catalogue, plus cross-domain triggers

- **Evidence (`20-crosscutting-fk.py`, replaying all versioned migrations).**
  - **Scale.** 117 tables, 195 FKs, 105 of them across heuristic domains.
  - **Where the cross-domain FKs point:**

    | Target | FKs |
    | --- | --- |
    | `app_user` | 46 (53 including identity's own) |
    | `org_unit` | 22 |
    | catalogue tables | 29 |
    | **business → business** | **8** |

  - **The 8 business → business FKs:** `refinery_order.mission_id`, `mission.operation_id`,
    `mission_unit.ship_id`, `job_order_item.blueprint_id`, `material_exchange_offer.inventory_item_id`,
    `inventory_item_job_order_allocation.job_order_id`, `inventory_item_mission_allocation.mission_id`,
    `exchange_ship_link.ship_id`.
  - **Triggers.** 18 survive:
    - 4 exchange change-feed triggers on `personal_blueprint`, `default_blueprint`, `inventory_item` and
      `ship`, all writing `exchange_change` (V252);
    - 2 promotion guards (`promotion_topic`, `rank_requirement`) and 4 kommando-group guards that read
      `org_unit.kind`;
    - 8 triggers internal to org units.
  - **JPA associations.** 183 association fields, 96 crossing heuristic domains.
  - **Repositories used from another domain:** 56 of 101 (230 foreign user classes).
    `InventoryItemRepository` is used by 14 foreign classes, among them joborder 6 and exchange 3.
- **Impact.**
  - Most cross-domain FKs point at kernel-like tables (users, org units, catalogue). A shared kernel for
    those three is natural; only 8 FKs need a deliberate decision per module pair.
  - The triggers are the hardest coupling: a column rename in `inventory_item` is not caught by Flyway,
    because plpgsql bodies are checked at execution. Every insert into the table then fails at runtime.
- **Proposed change.**
  1. Write a schema ownership map (table → module) into the data-persistence spec, and a test that
     every table has exactly one owner.
  2. Keep FKs. They are integrity, not coupling to remove; Option A keeps one schema.
  3. For the 8 business FKs, decide the allowed module dependency direction, which matches the entity
     associations (e.g. operation ← mission).
  4. Add a **trigger-column test** in the Testcontainers suite: insert, update and delete one row in
     each of the 4 fed tables and assert an `exchange_change` row. This catches a renamed column inside
     a trigger body.
- **Pros.** Makes DB ownership reviewable; catches the only silent DB break.
- **Cons.** A map to maintain.
- **Risks (security).** The change feed carries attribution (`basetool.change_source`); a write path
  outside `ChangeSourceTransactionManager` records `system`. **Guard:** the same test with a client
  source.
- **Effort** S–M. **Prerequisites** REQ-DATA amendment.

#### XC-22 — API path re-cut (owner update): everything keyed on `/api/v1` paths

- **Evidence.** Appendix H lists 20 path-keyed controls with their guards. The silent and
  security-relevant ones:
  1. **CSRF exemption** `CSRF_EXEMPT_PATHS = {"/api/v1/**", "/internal/**"}` (`SecurityConfig.java:111`).
     A path outside `/api/v1/**` (e.g. `/api/v2/…`) is CSRF-enforced in `prod`, so every bearer write
     gets 403. The `test` profile disables CSRF (`:315-317`), so **no test can see it** — ADR-0144's
     own incident.
  2. **No-store families** (`NoStoreApiScopes.java:42-55`, 14 patterns). A sensitive GET re-cut
     outside them gets `no-cache, must-revalidate` instead of `private, no-store`
     (`ApiCacheControlFilter.java`): REQ-SEC-031 is violated silently. `NoStoreApiScopesTest` checks
     fixed sample URIs only.
  3. **URL-matrix prefix gates** (`SecurityConfig.java:364-440`): `/api/v1/admin/**`,
     `/api/v1/bank/admin/**` and `/api/v1/audit/**` → ADMIN; `/api/v1/users/**` → ADMIN catch-all;
     `/api/v1/inventory/**` roles; `/api/v1/hangar/**` authorities. An admin endpoint re-cut to
     `/api/v1/<domain>/admin/…` silently loses layer 2. Layer 3 still gates — defence in depth lost,
     not access.
  4. **Edge api vhost** (`docker/edge/include/api-allowlist.conf`): 172 allow rules plus the
     *read-only-family* regex `^/api/v1/(missions|operations|notifications|announcement|hangar|inventory|orders|org-units|uex|blueprints|ship-types|locations|materials|users|refining-methods|settings)`,
     which answers **405** to non-GET/HEAD unless excepted. A write moved to a new family that is
     allow-listed for reads (e.g. `/api/v1/stock/…`) passes the edge's write refusal **silently**.
     A path not allow-listed is a loud 404 for the app.
  5. **Pending/consent allow-lists**: `PendingApprovalAccessFilter.java:79,86` and
     `TermsAcceptanceAccessFilter.java:85-88` (`/api/v1/terms/**`, registration status, version
     policy). A new endpoint placed under `/api/v1/terms/…` is reachable by unconsented callers
     (silent widening). Moving these three paths strands pending users (loud).
  6. **Per-subject budget** (`SubjectRateLimitingFilter.java:78-95`): `/api/**` writes, the two stream
     paths, and export segments `export`, `export.json`, `statement`, `report`, `pdf`,
     `three-month-report`. A re-cut stream path loses the connect budget; an export without those
     segments escapes the 10/min export bucket — both silent.
  7. **Everything outside `/api/**`** loses the pending gate, terms gate, subject limiter,
     cache-control, API-client metrics and ETag scoping at once. All of them use `/api/**`
     (`ApiClientMetricsFilter.java:52`, `PendingApprovalAccessFilter.java:95`,
     `TermsAcceptanceAccessFilter.java:77`, `SubjectRateLimitingFilter.java:78`,
     `ApiCacheControlFilter.java:50`, `StreamAwareShallowEtagHeaderFilter.java:45`,
     `RateLimitProperties.java:56`).
  8. **Stream-path special cases:** `StreamAwareShallowEtagHeaderFilter.java:53-54` (ETag buffering
     would break SSE), `RequestLoggingFilter.java:48`, `NotificationStreamObservationPredicate.java:39`.
  9. **Frozen shipped-client contract**: `ExternalContractTest` pins **235** operations in 30 families
     (`ExternalContractTest.java:304`). ADR-0136 requires `/api/v2` + `@ApiDeprecation` with a sunset
     for a retired operation, never an in-place move.
  10. **Frontend.**
      - 259 distinct `/api/v1/…` literals in 87 main files.
      - 7 live-sync probe paths (XC-08).
      - `TermsAcceptanceGateFilter.java:98`, which **fails open** on backend failure (`:283-306`) — the
        backend gate still enforces.
      - `BackendRoleSyncFilter.java:271,352` and `LayoutContextLoader.java:64`.
      - The E2E suite: 63 distinct paths in 38 files.
  11. **Exchange relay routes** are frozen: `ActingMemberFilter.java:100-115`, 13 exchange routes plus
      the prefix.
  12. **Monitoring.** One API probe (`prometheus.yml:136`, `/api/v1/terms/status`, 2xx-or-401) and the
      `edge-deny-probe` table (151 probe rows, synced to the allow-list by
      `.github/scripts/check_probe_against_allowlist.py`). No Prometheus rule selects a backend API
      `uri`; `SsePushChannelDead` lists **frontend** URIs (`business.yml:523`). Alloy and Loki match no
      API paths.
- **Impact.** A per-domain API re-cut is feasible, but six of these controls fail silently, and three of
  those (CSRF, no-store, read-only family) are security controls.
- **Proposed change — guards before the first re-cut.**
  - **(a) CSRF.** Assert every non-GET path from `EndpointEnumeration.mappings(context)` (test-support)
    matches `CSRF_EXEMPT_PATHS`; better, derive the exemption from `/api/**`.
  - **(b) No-store.** Invert to `private, no-store` by default for `/api/**`, with an explicit
    cacheable allow-list (catalogue reads), or test every GET mapping of a sensitive module against
    `NoStoreApiScopes`.
  - **(c) URL matrix vs method gates.** Assert every handler whose `@PreAuthorize` is ADMIN-only sits
    under an ADMIN URL prefix, or record the exemption.
  - **(d) Edge.** Parse the allow-list and the read-only-family regex and assert that every allow-listed
    non-GET mapping is either in a read-only family with an explicit exception, or listed as a write —
    an extension of the existing `ExternalContractTest` allow-list parser (`:2667`).
  - **(e) Pending/terms allow-lists.** A test that the paths are exactly today's set.
  - **(f) Stream and export classification.** Assert every `text/event-stream` handler is in
    `SSE_CONNECT`/`LIVE_SYNC_CONNECT`, and every handler producing PDF or JSON downloads matches
    `EXPORT_SEGMENTS`.
  - **(g) Frontend.** Extend `DeprecatedBackendEndpointCallGuardTest` to assert every resolved
    `backendApiClient` call matches an **existing** operation in `openapi.json` (today it checks only
    deprecated ones), and the same for the 7 live-sync probe paths.
  - **Re-cut shape.** Stay under `/api/v1/` for new per-domain paths where possible. Frozen operations
    move only via `/api/v2` + `@ApiDeprecation` + sunset, with the app transition per ADR-0136.
- **Pros.** Turns the six silent failures into build failures. **Cons.** About six small tests.
- **Risks (security).** Covered above. **Effort** M. **Prerequisites** REQ-SEC-031/-037/-044/-052,
  REQ-API-009 unchanged; any `/api/v2` needs ADR-0136's process; the edge allow-list changes are
  production writes, gated per action.

#### XC-23 — Option B/C hazards specific to cross-cutting code

- **Evidence.**
  - Component, entity and repository scanning default to each `@SpringBootApplication` package (javap:
    no `scanBasePackages`, `@EntityScan` or `@EnableJpaRepositories`).
  - JaCoCo and PIT keying (XC-18).
  - `verification-metadata.xml` strict verification (ADR-0208).
  - Spring Modulith core 2.1.1 depends on ArchUnit **1.4.2** while the catalog pins **1.5.1**
    (`libs.versions.toml:28`); Gradle would resolve 1.5.1 — compatibility **UNKNOWN**, settle by
    running Modulith's `verify()` under 1.5.1 in a spike.
- **Impact.** A Gradle module whose packages sit outside `de.greluc.krt.profit.basetool.backend` loses
  every bean nobody injects — `@TransactionalEventListener`s, `@Scheduled` tasks, `ApplicationListener`s,
  `@ControllerAdvice`s, `Converter`s — **without an error**. Coverage and PIT gates weaken silently.
- **Proposed change.** Keep one root package across all backend Gradle modules. Add a context-shape
  ratchet test: counts of `@Scheduled` tasks (15), transactional event listeners (4), controllers
  (99), `SecurityFilterChain`s and exception handlers, compared to committed numbers.
- **Pros and cons.** Cheap insurance for Option C. **Effort** S. **Prerequisites** ADR choosing A/B/C.

#### XC-24 — Previous audits, re-evaluated for cross-cutting concerns

| Finding | Status today | Re-evaluation |
| --- | --- | --- |
| July 2026 modularity audit (PR #1256): "architecture sound, debt is size inside layers"; load-bearing: peer-redaction full-field reconstruction, bank seam pinned | Both still enforced (`ArchitectureTest.java:1129,1853`) | Still valid for correctness. Under domain separation the bank seam rule **must be re-keyed first** (XC-04), and the redaction rule's `.backend.controller` filter must become annotation-based (XC-13) |
| ADR-0047 follow-up: frontend, ingest and keycloak-spi not cycle-gated | Still open: frontend 7 and ingest 4 rules, no `slices()` (grep) | Add a frontend `slices()` rule **before** frontend per-domain packaging (S) |
| Sept CI-03 (PIT green while failing) | Fixed (#1993): `pitest.yml` fails on zero mutations | The partial loss of targets after package moves is still silent (XC-18) |
| Sept APPSEC-05 (session allow-list) | Done, `enforce` in production | Now constrains frontend packaging (XC-16) |
| Sept APPSEC-10 (export budget) | Done: path-segment classification | Path re-cuts must keep the segments (XC-22) |
| Sept BE-SIMP-11 (`RedisJsonFanout`) | Done (#2011) | Good platform seam for a live-sync/notification module (XC-08) |
| Sept BE-PERF-13 (async SSE delivery) | Done (#2024) | Unaffected |
| Sept FE-SIMP-02 (`@HttpExchange` later) | One `exchange` helper done (#2010); typed clients open | Per-domain typed clients must reuse the `WebClientConfig` beans (only 4 `WebClient.builder()` sites, all in `WebClientConfig`) and the single resilience filter (ADR-0032); add guard (g) of XC-22 |
| Sept ING-SEC-05 (ingest endpoints under /v1) | Done (`IngestEndpointSurfaceTest`) | The exchange relay route list is part of the frozen contract (XC-22 item 11) |

---

## 3. Data appendix

### A. Method security inventory (`20-crosscutting-authz.py` over javap annotations)

| Module | `@PreAuthorize` | Classes | Distinct expressions | SpEL beans |
| --- | --- | --- | --- | --- |
| backend | 431 (378 method, 53 class) | 106: 91 `controller`, 8 `controller.exchange`, 7 `service` | 72 | 8 (166 refs) |
| frontend | 245 | 88 controllers | 13 | 0 (role checks only) |
| ingest | 18 | 2 (`web`) | 2 | 0 |

- **Backend expression frequencies:**

  | Expression | Uses |
  | --- | --- |
  | `isAuthenticated()` | 117 |
  | `hasRole('ADMIN')` | 113 |
  | `@missionSecurityService.canManageMission(#id, authentication)` | 28 |
  | `hasAnyRole('ADMIN','OFFICER')` | 19 |
  | `hasRole('BANK_MANAGEMENT')` | 9 |
  | `isAuthenticated() and @ownerScopeService.canEditInventoryItem(#id)` | 9 |
  | `hasRole('BANK_EMPLOYEE')` | 7 |
  | `permitAll()` | 4 |
  | … | 64 more expressions |

- **SpEL references by bean, with the calling domains:**

  | Bean | References | Calling domains |
  | --- | --- | --- |
  | `ownerScopeService` | 66 | joborder 31, inventory 9, operation 8, refinery 8, mission 7, hangar 2, blueprint 1 |
  | `missionSecurityService` | 40 | mission |
  | `authHelperService` | 17 | mission |
  | `exchangeGate` | 14 | exchange |
  | `orgRoleManagementSecurityService` | 13 | orgunit 10, leadership 3 |
  | `bankSecurityService` | 10 | bank |
  | `specialCommandSecurityService` | 5 | orgunit |
  | `connectedAppsGate` | 1 | exchange |

- **`@ownerScopeService` methods referenced from SpEL** (18 of 55 public names):

  | Method | Refs | Method | Refs |
  | --- | --- | --- | --- |
  | `canEditJobOrder` | 12 | `canViewJobOrders` | 2 |
  | `canSeeJobOrder` | 12 | `canSeeOperation` | 2 |
  | `canEditInventoryItem` | 9 | `canViewOwnJobOrders` | 1 |
  | `canSeeMission` | 7 | `canSeeJobOrderAsRequester` | 1 |
  | `canEditRefineryOrder` | 5 | `canSeeJobOrderBlueprintOwners` | 1 |
  | `canSeeOperationLedger` | 3 | `canAccessBlueprintOverview` | 1 |
  | `canEditOperation` | 3 | `canSeeRefineryOrder` | 1 |
  | `canEditShip` | 2 | `canViewUserRefineryOrders` | 1 |
  | `canEditJobOrderAsRequester` | 2 | `canManageUserRefineryOrders` | 1 |

- **Backend `@PreAuthorize` by heuristic domain:**

  | Domain | Sites | Domain | Sites |
  | --- | --- | --- | --- |
  | catalogue | 60 | leadership | 5 |
  | bank | 56 | orgchart | 5 |
  | mission | 55 | dashboard | 4 |
  | identity | 53 | personalinventory | 2 |
  | joborder | 39 | materialexchange | 2 |
  | orgunit | 36 | notification | 2 |
  | promotion | 24 | audit | 1 |
  | exchange | 17 | livesync | 1 |
  | inventory | 16 | unclassified | 1 |
  | hangar | 14 | | |
  | refinery | 14 | | |
  | operation | 12 | | |
  | admin | 6 | | |
  | blueprint | 6 | | |

- **Roles constants.** `support.Roles` source references: `HAS_ROLE_ADMIN` 107, `ADMIN` 58,
  `OFFICER` 41, `LOGISTICIAN` 26, `ADMIN_OR_OFFICER` 19, `KRT_MEMBER` 19, `BANK_EMPLOYEE` 16, … — 339
  in total (`grep -rhoE "\bRoles\.[A-Z_]+" backend/src/main/java | wc -l`), against jdeps in-degree 20
  (constant folding).
- **Controllers.** 98 `@RestController` plus 1 `@Controller` in the backend.

### B. Hub fan-in and fan-out (`20-crosscutting-fanout.py`, jdeps, outer classes, heuristic domains)

| Class | Out (classes) | Out domains | In (classes) | In domains |
| --- | --- | --- | --- | --- |
| `OwnerScopeService` | 10 | security 4, orgunit 2, refinery, identity, joborder | 34 | joborder 7, promotion 6, inventory 3, operation, refinery, mission, identity, materialexchange, hangar 2 each; bank, blueprint, livesync, security, exchange 1 each |
| `AccessGateService` | 24 | orgunit 5, security 4, joborder 4, mission 2, refinery 2, operation 2, hangar 2, inventory 2, identity 1 | 3 | — |
| `RequestScopeResolver` | 14 | orgunit 8, security 4 | 3 | — |
| `OrgUnitStampingService` | 12 | orgunit 6, security 3, identity 1 | 1 | — |
| `ScopeSpecifications` | 0 | — | 0 | — (constant-folded) |
| `ScopePredicate` | 0 | — | 15 | 10 domains |
| `AuthHelperService` | 3 | security | 46 | 17 domains (bank 9, identity 5, …) |
| `AuditService` | 12 | — | 59 | 16 domains |
| `AuditEventType` | — | — | 63 | 16 domains |
| `AuditDetails` | — | — | 58 | 17 domains |
| `BankAuditService` | — | — | 16 | bank 14, audit 1, identity 1 |
| `MetricNames` | — | — | 59 | — |
| `ScheduledJob` | — | — | 16 | — |
| `TaskMetrics` | — | — | 15 | — |
| `BusinessMetricsCollector` | 21 | 9 repositories of 7 domains | 0 | — |
| `UserDeletionService` | 28 | 15 repositories of 10 domains | 5 | identity |
| `HandleAnonymisationService` | 16 | bank 7, audit 5, joborder 3 | — | — |
| `RecipientResolutionService` | 8 | bank 4 (`BankAccountGrant*`, `OrgUnitBankResponsibilityService`), orgunit, identity | — | — |
| `OrgUnitBankAccessService` | 56 | bank 39 | — | — |
| `User` | — | — | 115 | 20 domains |
| `OrgUnit` | — | — | 82 | — |
| `UserRepository` | — | — | 47 | — |

### C. Audit call sites (`20-crosscutting-audit.py`)

- **Bytecode.** `AuditService.record` 206 calls in 57 classes; `BankAuditService.record` 51 calls in 14
  classes.
- **Source expressions by writer domain (audit/bank recorder):**

  | Domain | Calls | Domain | Calls |
  | --- | --- | --- | --- |
  | bank (bank recorder) | 50 | promotion | 14 |
  | mission | 42 | blueprint | 11 |
  | joborder | 28 | hangar | 8 |
  | inventory | 19 | catalogue | 5 |
  | orgunit | 16 | refinery | 5 |
  | identity | 15 + 1 bank | operation | 4 |
  | materialexchange | 15 | personalinventory | 4 |
  | exchange | 14 | leadership | 3 |
  | | | audit | 3 + 1 bank |

- **Largest writers:**

  | Class | Calls | Class | Calls |
  | --- | --- | --- | --- |
  | `OrgUnitMembershipService` | 16 | `OrgUnitBankVisibilityService` | 8 (bank) |
  | `JobOrderService` | 14 | `BankBookingRequestService` | 7 |
  | `MissionService` | 13 | `BankLedgerService` | 7 |
  | `MissionTimelineService` | 11 | `HangarService` | 7 |
  | `InventoryCheckoutService` | 9 | `MaterialExchangeService` | 7 |
  | `MissionParticipantService` | 9 | | |
  | `OrgUnitBankApprovalLimitService` | 8 (bank) | | |

- **Event types.** `AuditEventType` has 202 constants and `AuditDomain` 12. `audit_event.event_type`
  and `domain` are not CHECK-constrained (`V179:10`).

### D. Events, listeners and scheduling (javap annotations; grep `publishEvent(`)

| Publisher (sites) | Events |
| --- | --- |
| `BankBookingRequestService` (4) | BankBookingRequest Created/Cancelled/Confirmed/Rejected |
| `DeletionRequestService` (4) | AccountDeletionRequested / Resolved ×2 / Declined |
| `UserReconciliationService` (6) | MemberDeparted ×4, DiscordRegistrationPending ×2 |
| `UserRegistrationService` (2) | UserApprovalDecided ×2 |
| `JobOrderService` (2) | JobOrderCreated, JobOrderUpdatedByRequester |
| `MaterialExchangeService` (1) | MaterialExchangeInterestRegistered |
| `MaterialRequestService` (1) | MaterialRequestFulfillmentSignalled |
| `ExchangeInstallationService` (1) | ExchangeInstallationConnected |
| `ExchangeBulkUndoStep` (1) | ExchangeBulkUndoApplied |

| Listener | Annotation / phase |
| --- | --- |
| `NotificationEventListener.onNotificationEvent` | `@Async("notificationExecutor")` + AFTER_COMMIT |
| `PendingRegistrationMailEventListener.onDiscordRegistrationPending` | `@Async("mailExecutor")` + AFTER_COMMIT |
| `UserApprovalMailEventListener.onUserApprovalDecided` | `@Async("mailExecutor")` + AFTER_COMMIT |
| `ExchangeDepartureService.onDeparture` | AFTER_COMMIT, `fallbackExecution = true`, sync; REQUIRES_NEW writes |
| 6× `@EventListener(ApplicationReadyEvent)` | `StartupBannerListener`, `DefaultBlueprintKeyService`, `ExchangeBulkUndoService`, `ExchangeRegistryMirrorClosure`, `P4kImportJobService`, `ExchangeRegistryReconcileTask` |
| Hand-rolled `afterCommit` | `ExchangeLiveSync:115`, `TermsAcceptanceService:170`, `ExchangeJournalService:109` |

**`@Scheduled` (15):** the backend `task` package holds `AuditRetentionTask`, `BankLedgerIntegrityTask`,
`BusinessMetricsCollector`, `DefaultBlueprintProvisioningTask`, `ExchangeChangeRetentionTask`,
`ExchangeConnectionRetentionTask`, `ExchangeRegistryReconcileTask`, `JobOrderIntegrityTask`,
`NotificationRetentionTask`, `RejectedRegistrationRetentionTask` and `UserSyncTask`. Outside it:
`UexScheduler`, `ScWikiScheduler`, `LiveSyncStreamService.heartbeat` and
`NotificationStreamService.heartbeat`. Ingest has 1 (`ExchangeRegistryMirrorAge.refresh`). There are
no `@Timed`, `@Observed` or `@Counted` annotations.

### E. Exchange → non-exchange dependencies (jdeps, `service.exchange.*` → other packages)

| Category | Count | Items |
| --- | --- | --- |
| Repositories | 14 non-exchange (of 24 used; the other 10 are exchange repositories) | see XC-09 |
| Services | 22 | Audit, AuthHelper, `Blueprint*` ×6, `DefaultBlueprintKey`, `Hangar`, `InventoryCheckout`, `InventoryStolenMark`, `JobOrderMaterialRequirementResolver`, `JobOrderStockProjection`, `Keycloak`, `LiveSyncRelay`, `MaterialExchangeOfferRatchet`, `MaterialNameCanonicalizer`, `OwnerScope`, `PersonalBlueprint`, `RefineryImport`, `ShipTypeMatcher` |
| Model classes | 67 (incl. DTOs of inventory, blueprint, ship, refinery) | — |
| `support` classes | 19 | — |

### F. ArchUnit rules (V = can turn vacuous, W = weaker under partial moves, S = semantics change, L = loud or robust; ★ = security)

| # | Rule (`ArchitectureTest.java:line`) | Keyed on | Class | Guard before moving |
| --- | --- | --- | --- | --- |
| 1 | `serviceLayerShouldNotReachIntoSecurityContext` :202 ★ | `..backend.service..` + FQN regex | W | select `@Service` + floor |
| 2 | `controllerLayerShouldNotReachIntoSecurityContext` :219 ★ | `..backend.controller..` | W | select `@RestController` |
| 3 | `identityMustBeReadThroughTheSeamNotTheAuthenticationType` :234 | global; seam FQNs | L | class literals |
| 4 | `mapperLayerShouldNotReachIntoSecurityContext` :253 ★ | `..backend.mapper..` | W | select `@Mapper` |
| 5 | `controllerMethodsShouldNotReturnJpaEntities` :268 | `..controller..` | W | `@RestController` |
| 6 | `toOneAssociationsAreDeclaredLazy` :284 | annotations | L | — |
| 7 | `controllersMustNotInjectTheLazyMembershipMapper` :298 | target FQN string | V | class literal |
| 8 | `everyRestControllerShouldDeclareAtLeastOneAuthorisationAnnotation` :316 | annotation | L | — |
| 9 | `permitAllIsDeclaredOnlyOnTheFourPublicEndpoints` :345 ★ | loop, `contains(".backend.controller")`, no floor | V | iterate `@RestController`/`@Controller`; floor |
| 10 | `readEndpointsMustDeclareAnAuthorisationAnnotation` :389 ★ | `..controller..` | W | `@RestController` + floor |
| 11 | `orgUnitBankSettingsMutationsMustCallAnAuthorizationHelper` :406 | simple name + return FQN | L | class literal |
| 12 | `controllerLayerShouldNotDependOnRepositoryLayer` :427 ★ | both sides packages | V | `areAssignableTo(Repository)` |
| 13 | `controllerLayerMustNotWriteAuditRowsDirectly` :442 | `..controller..`; target by class literal | W | `@RestController` |
| 14 | `everyExchangeControllerMethodCarriesTheExchangeGate` :475 ★ | `..controller.exchange..` | W | module marker + floor |
| 15 | `exchangeControllersCallExchangeServicesOnly` :506 ★ | `contains(".backend.service")` target | V | module API rule (XC-09) |
| 16 | `exchangeDtosStayInTheExchangeLayer` :525 | packages (selection and accessors) | L | — |
| 17 | `exchangeServicesNeverUseAdminGatesOrTheAdminScope` :542 ★ | `..service.exchange..`; simple name `OwnerScopeService` | V | re-key on the kernel API (XC-01) |
| 18 | `supportPackageMustStayADependencyLeaf` :577 | deny-list of layer packages | V | allow-list (`onlyDependOnClassesThat`) |
| 19 | `backendPackagesShouldBeFreeOfDependencyCycles` :603 | `backend.(*)..` | S | add `backend.(*).(*)..` |
| 20 | `mapperLayerShouldNotDependOnServiceLayer` :616 | packages | V | annotations |
| 21 | `integrationLayerShouldNotDependOnServiceLayer` :631 | packages | V | annotations |
| 22 | `eventLayerShouldNotDependOnServiceLayer` :646 | packages | V | annotations |
| 23 | `validationLayerMustStayADependencyLeaf` :661 | packages | V | — |
| 24 | `controllerMethodsShouldNotExposeJpaEntitiesInGenericWrappers` :677 | `..controller..` | W | `@RestController` |
| 25 | `mutatingServiceMethodsInReadOnlyClassesNeedExplicitTransactional` :692 | `..service..` | W | `@Service` |
| 26 | `repositoriesMustNotDeclareNoArgFindAll` :705 | `..repository..` + `allowEmptyShould(true)` | V | `areAssignableTo(Repository)`, drop `allowEmpty` |
| 27 | `writeEndpointsMustDeclareAnAuthorisationAnnotation` :737 ★ | `..controller..` | W | `@RestController` + floor |
| 28 | `staffelScopedServicesMustWireOwnerScopeOrAuthHelper` :978 ★ | 12 simple names; field FQNs | W | `@TenantScoped` (XC-14) |
| 29 | `staffelScopedWriteEndpointsMustGateOnOwnerScopeService` :1036 ★ | 7 simple names; SpEL substrings | W | XC-14 |
| 30 | `peerReadableMissionEndpointsMustRedactPii` :1129 ★ | `contains(".backend.controller")`; floor ≥ 10 (23 today) | W | floor = 23; `@RestController` |
| 31 | `responseOnlyDtosMustNotBeAcceptedAsRequestBodyOnWriteEndpoints` :1252 ★ | `..controller..` + DTO FQN string | V | class literal |
| 32 | `missionWriteRequestDtosMustNotCarryServerManagedFields` :1381 | FQN selection | L | class literal |
| 33 | `missionParticipantsCollectionMustExcludeOptimisticLock` :1479 | FQN | L | class literal |
| 34 | `promotionTopicOwningSquadronMustStayTypedSquadronNotOrgUnit` :1500 | FQN selection and compare | L | class literal |
| 35 | `missionServiceAddParticipantMustNotSaveMission` :1551 | selection FQNs; **target FQN `MissionRepository`** | V | class literals |
| 36 | `scWikiIntegrationClassesMustWireScWikiClient` :1659 | package + `allowEmptyShould(true)` | V | floor |
| 37 | `noNewJoinColumnReferencingSquadronIdOutsideGrandfatheredEntities` :1703 | `backend.model..` | W | `@Entity` |
| 38 | `bankClassesMustStaySeasonAndProfitIndependent` :1763 | simple-name prefixes (`Bank`, `Mission`, …) | V (renames) | module membership |
| 39 | `bankClassesMustNotConsultOrgUnitScope` :1794 ★ | `Bank*` prefix + FQN `OwnerScopeService` | V | XC-04 |
| 40 | `cascadeServiceMustNotConsultTheSecurityContext` :1812 ★ | FQN `AuthHelperService` | V | class literal |
| 41 | `delegatedRoleAuthoriserMustNotConsultOwnerScope` :1833 ★ | FQN `OwnerScopeService` | V | class literal + kernel |
| 42 | `orgUnitAwareBankSeamIsContainedToOneClass` :1853 ★ | FQNs `OwnerScopeService` + `BankAccountRepository` | V (after XC-01) | XC-04 |
| 43 | `bankLedgerRepositoriesMustStayInsertOnly` :1892 | FQN set; second clause call-target FQN | V (clause 2) | class literals |

**Totals:** 20 V · 14 W · 1 S · 8 L = 43. ★ marks the 18 security-relevant rules among the 34 V/W.

- **Frontend (7 rules):** annotation- or root-package-based (`:55,69,96,118,131,146,166`). All survive a
  package move; the `PUBLIC_BY_DESIGN` existence check (`:118`) makes renames loud. **No** `slices()`
  rule.
- **Ingest (4 rules):** annotation-based (`:50,61,73,85`) and robust.
- **Other ArchUnit users.** `TermsDocumentClientUsageTest` (field name plus simple name
  `BackendApiClient`, loud) and `OpenApiDerivedPropertyTest` (simple names against openapi schemas,
  robust).
- **Empty selections.** `archRule.failOnEmptyShould` default TRUE (archunit-1.5.1 bytecode); opt-outs at
  `:718` and `:1693`.

### G. Other name-keyed items (see XC-16 … XC-20)

| Item | Count | Behaviour on move/rename |
| --- | --- | --- |
| SpEL bean names | 8 beans / 166 refs; 6 implicit | 400 `ILLEGAL_ARGUMENT` at runtime (XC-02) |
| JPQL FQCNs | 93 refs / 68 queries / 27 repos | startup failure (loud) |
| JPQL cross-domain entity names | 18 (6 repos) | startup failure (loud) |
| `ScopeSpecifications` package-private constants | 6 / 29 splices | compile error when leaving `repository` (loud) |
| Thymeleaf `T()` | 172 / 22 templates | render-time 500 (XC-17) |
| Session allow-list prefix | 1 prefix; 28 flash forms | silent drop under `enforce` (XC-16) |
| `spring.factories` | 3 modules | startup failure |
| SpotBugs excludes | 2 | loud (new findings) |
| PIT / JaCoCo | keyed on `service.*` / `project.name` | silent (XC-18) |
| Frontend DTO contract tests | 3, keyed on `frontend.model.dto` | silent above floors |
| Request-attribute/memo keys from `Class.getName()` | 6 + 5 | runtime-consistent, robust |
| Cache names / Redis channels | 17 + 4 | explicit strings, robust |
| Monitoring selectors on Java names | 0 | robust; enum-name labels are the contract (XC-20) |
| Duplicate simple names | 0 top-level backend/frontend; 14 nested backend (`Outcome` ×4, …) | openapi collisions caught by the CI diff (`ci.yml:67`) |

### H. Path-keyed controls for an API re-cut (owner update 2026-09-29)

| # | Control | Where | Keyed on | Failure if not updated | Silent? | Guard |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | CSRF exemption | `SecurityConfig.java:111` | `/api/v1/**` | prod 403 on writes outside `/api/v1` | **yes** (tests disable CSRF) | XC-22 (a) |
| 2 | No-store families | `NoStoreApiScopes.java:42-55` | 14 prefixes | sensitive GET storable | **yes** | XC-22 (b) |
| 3 | URL matrix role gates | `SecurityConfig.java:364-440` | prefixes | layer 2 lost | **yes** (layer 3 remains) | XC-22 (c) |
| 4 | Edge allow-list | `api-allowlist.conf` (172 rules) | exact and regex paths | 404 for the app | no (app breaks) | `ExternalContractTest:2667`, `edge-deny-probe.yml` |
| 5 | Edge read-only family (405) | same file | 16 family prefixes | write passes the edge | **yes** | XC-22 (d) |
| 6 | Pending / terms allow-lists | `PendingApprovalAccessFilter:79,86`; `TermsAcceptanceAccessFilter:85-88` | exact paths + `/terms/**` | widening under `/terms/`; pending users stranded | partly | XC-22 (e); `AnonymousSurfaceSweepTest` covers pending/no-role everywhere |
| 7 | `/api/**` scopes (pending, terms, subject limit, cache-control, API-client metrics, ETag, IP limit) | 7 classes | `/api/**` | all lost outside `/api/` | **yes** | forbid mappings outside `/api/**`, `/internal/**`, `/error` (dispatcher test) |
| 8 | Subject budget streams and export segments | `SubjectRateLimitingFilter:78-95` | 2 paths + 6 segments | budget escape | **yes** | XC-22 (f) |
| 9 | SSE ETag / log / observation exclusions | `StreamAwareShallowEtagHeaderFilter:53-54`, `RequestLoggingFilter:48`, `NotificationStreamObservationPredicate:39` | stream paths | SSE buffered or logged | partly | XC-22 (f) |
| 10 | Request-body cap | `RequestBodyLimitProperties:42` | `/api/v1/refinery-orders/import-extract` | cap lost | **yes** | include in (f) |
| 11 | Exchange relay routes | `ActingMemberFilter:100-115` | 13 routes + prefix | exchange broken | no | frozen; `ExchangeRoutesContractTest`, `ActingMemberFilterPathMatchingTest` |
| 12 | Frozen shipped-client ops | `ExternalContractTest:304` (235 ops) | path + verb | test fails | no | ADR-0136 `/api/v2` process |
| 13 | `openapi.json` | CI `git diff --exit-code` (`ci.yml:67`) | paths | CI fails | no | — |
| 14 | Frontend calls | 259 literals / 87 files | literals | 404 at runtime | partly (unit tests mock the backend) | XC-22 (g) |
| 15 | Frontend live-sync probes | frontend `LiveSyncTopicClass` 7 paths | paths | room DENY, no live update | **yes** | XC-08 guard |
| 16 | Frontend terms / role / layout relays | `TermsAcceptanceGateFilter:98` (fails open), `BackendRoleSyncFilter:271,352`, `LayoutContextLoader:64` | exact paths | UX gate off; backend still enforces | **yes** | XC-22 (g) |
| 17 | E2E suite | 63 paths / 38 files | literals | E2E red | no | — |
| 18 | Blackbox probe | `prometheus.yml:136` | `/api/v1/terms/status` | false API-down | no | update with the path |
| 19 | Prometheus / Grafana `uri` selectors | none on backend API (`business.yml:523` is frontend) | — | — | — | — |
| 20 | Alloy / Loki | no API path matching | — | — | — | — |

### I. Database coupling (`20-crosscutting-fk.py`)

- **Scale.** 117 tables; 195 FKs; cross-domain 105; to `app_user` 53 (46 cross-domain); to
  `org_unit`/`squadron` 23 (22 cross-domain); to catalogue 29.
- **Cross-domain edges** (FKs, source → target):

  | Edge | FKs | Edge | FKs |
  | --- | --- | --- | --- |
  | bank → identity | 12 | hangar → catalogue | 2 |
  | blueprint → catalogue | 7 | orgunit → identity | 2 |
  | exchange → identity | 7 | promotion → orgunit | 2 |
  | mission → identity | 6 | materialexchange → orgunit | 2 |
  | refinery → catalogue | 6 | notification → identity | 2 |
  | joborder → orgunit | 6 | … | 1 each |
  | mission → catalogue | 5 | | |
  | joborder → catalogue | 5 | | |
  | joborder → identity | 4 | | |
  | materialexchange → identity | 4 | | |
  | inventory → catalogue | 3 | | |
  | mission → orgunit | 3 | | |
  | bank → orgunit | 3 | | |

- **Business → business FKs:** the 8 listed in XC-21.
- **Surviving triggers (18).** V252 × 4 (exchange feed on `personal_blueprint`, `default_blueprint`,
  `inventory_item`, `ship`); V101 `promotion_topic` guard; V135 `rank_requirement` guard; V185 × 4
  `kommando_group`; V98/V164/V165 × 6 `org_unit_membership`; V164 × 2 `org_unit`.
- **Tables without an entity:** `exchange_client_capability`, `mission_crew_job_types`,
  `mission_managers`, `mission_participant_org_unit`, `role_permissions`, `user_roles`.
- **JPQL.** 363 `@Query`, 29 native, 18 cross-domain entity-name references (`20-crosscutting-jpql.py`).

### J. Commands and scripts (all in the scratchpad, run 2026-09-29)

**Scripts:**

| Script | What it does |
| --- | --- |
| `python 20-crosscutting-javap.py` | `javap -v -p` over all 1765 + 791 + 102 main classes into `20-crosscutting-annotations.json` (class and member annotations with decoded values) |
| `python 20-crosscutting-authz.py` | inventory of `@PreAuthorize/@PostAuthorize/@PreFilter/@PostFilter/@Secured/@RolesAllowed`, SpEL `@bean.method(` extraction, stereotype bean-name resolution → `20-crosscutting-authz.json` |
| `python 20-crosscutting-fanout.py` | hub fan-in/out over `jdeps-backend.txt` → `20-crosscutting-fanout.json` |
| `python 20-crosscutting-audit.py` | `javap -c` count of `AuditService.record`/`BankAuditService.record` invocations + source call-site parse → `20-crosscutting-audit.json` |
| `python 20-crosscutting-fk.py` | replay of all `V*__*.sql` (CREATE TABLE inline and table FKs, ALTER ADD/DROP CONSTRAINT/COLUMN, DROP TABLE, triggers in statement order) → `20-crosscutting-fk.json` |
| `python 20-crosscutting-jpql.py` | cross-domain entity names in `@Query` JPQL → `20-crosscutting-jpql.json` |
| `python 20-crosscutting-monitoring.py` | Java simple names referenced in `monitoring/`, `docker/`, `scripts/`, `.github/` → `20-crosscutting-monitoring.json` |

**Greps and local checks:**

| Command or check | Result |
| --- | --- |
| `grep -rn "publishEvent(" …/src/main/java` | 22 sites |
| `grep -rhoE "T\(([a-zA-Z0-9_.$]+)\)" frontend/src/main/resources/templates` | 172 × `…frontend.support.Roles`, 22 files |
| `grep -rhoE '"/api/v1/[^"]*"' frontend/src/main/java \| sort -u \| wc -l` | 259 (87 files) |
| same over `frontend/src/e2e` | 63 (38 files) |
| `grep -c "krt_api_allowed 1" docker/edge/include/api-allowlist.conf` | 172 |
| `ExternalContractTest` CONTRACT parse (Python regex over `new ContractOperation("…", "…"`) | 235 operations, 30 families |
| `grep -c "@Test"` | 3 `ArchitectureTest`s: 43 / 7 / 4 |
| `grep -oE` keys in the backend `ArchitectureTest` | 47 `..backend.x..` patterns, 4 `contains` filters, 35 FQN literals, 8 simple-name matches |
| `find . -name "archunit*.properties"` | none |
| `javap -c -p -constants` of `com.tngtech.archunit.lang.AllowEmptyShould$3` (archunit-1.5.1.jar) | default `TRUE` |
| `javap -c` of `org.springframework.security.authorization.method.ExpressionUtils` (spring-security-core 7.1.1) | `EvaluationException` → `IllegalArgumentException` |
| `javap -p` of `org.springframework.modulith.events.jpa.JpaEventPublication` and `DefaultJpaEventPublication` (spring-modulith-events-jpa 2.1.1, `@Table(name="EVENT_PUBLICATION")`) | fields `serializedEvent`, `eventType`; spring-modulith-core 2.1.1 pom: Spring Boot 4.1.1, Framework 7.0.9, ArchUnit 1.4.2 |
| `grep -rn "createQuery(\|createNativeQuery(\|jdbcTemplate\." backend/src/main/java` | 6 dynamic-query sites |
| `grep -c` in the GDPR registries | 36 `new Section(`, 80 `new Target(`, 79 distinct `table.column` keys in `HandleErasureCoverage` |

### K. Vault statements the code contradicts (for the coordinator; agents are read-only)

- **`30 Roles and Permissions/Request Authorization.md` § Layer 3.** It says "Counted on 2026-09-22
  across **88** `@RestController`s and **440** `@PreAuthorize`". Code on `95e945326`: **431**
  annotations on 98 `@RestController`s, 1 `@Controller` and **7 services** — the 18 service-level gates
  of XC-12 are not mentioned anywhere in the note.
- **The same note: "Some forty-five distinct `@ownerScopeService.*` predicates are referenced".** SpEL
  references **18** distinct `ownerScopeService` methods; the service has 55 distinct public method
  names.
- **`30 Roles and Permissions/Security.md` § Invariants.** It describes the ArchUnit gate as what "keeps
  the model from eroding". It does not record that most rules are package-keyed and would go vacuous
  under package-by-domain (XC-13) — worth a callout once the refactor is decided.
