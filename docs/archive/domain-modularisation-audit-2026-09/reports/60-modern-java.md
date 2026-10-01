# 60 — Modern Java features across all modules and source sets (prefix `JAVA`)

Scope: every `*.java` under `backend`, `frontend`, `ingest`, `keycloak-spi`, `logging-support` and
`test-support`, source sets `main`, `test` and the frontend's `e2e` — **3,295 files** (main 2,044 ·
test 1,136 · e2e 115) at `95e945326`. Read-only. Method: a Python lexer that blanks comments and
literal contents while keeping every offset (asserted equal length and equal newline count for all
3,295 files), regex and bracket-level structure on top, cross-checked against the compiled `main`
classes with `javap` (compiled 2026-09-29 15:34, newer than HEAD), plus throwaway tool probes with
the real toolchain: javac 25 (`--release 25` and `--release 21`), Checkstyle **14.3.0** run with the
repository's own `config/checkstyle/google_checks.xml`, google-java-format **1.36.1**, the JDK 25
`javadoc` tool and two JVM behaviour probes. All scripts and raw outputs are listed in Appendix F.
"To verify" marks tool or library facts I could not prove offline. JEP numbers other than those the
repository itself cites (500 in `backend/CLAUDE.md`; 502–513 in ADR-0223) are given for orientation
only — to verify; every finality claim here rests on the javac probes, not on the numbers.

## 1. Summary — the ten most important conclusions

1. **The code is already on modern final idioms almost everywhere**, so the value left is in
   exhaustiveness and `toString` hygiene, not in syntax sweeps: 1,049 records in `main`, 89 of 89
   switches in arrow form (0 colon form), 164 type patterns vs 4 `instanceof`+cast in `main`, 310
   `Stream.toList()` vs 2 `Collectors.toList()` (both downstream collectors), 0 legacy
   date/collection APIs in `main`, 0 prose comments (Appendix A; `60-modern-java-scan.py`).
2. **Two `@ConfigurationProperties` JavaBeans print a credential**: frontend and ingest
   `MonitoringScrapeProperties` are Lombok `@Data` classes whose generated `toString()` includes
   `password`, while the backend's twin is a record that redacts it — and the log masker does not
   mask `password=` (JAVA-01; `frontend/.../config/MonitoringScrapeProperties.java:33-49`,
   `logging-support/.../PiiMasker.java:42-49`).
3. **The keycloak-spi `Brokered` record prints a Discord access token, username and e-mail** in its
   generated `toString`, in a JVM that has no PII masker, next to a sibling record that redacts its
   own (JAVA-01; `DiscordGuildRoleGateAuthenticator.java:280`, `DiscordMembershipChecker.java:172`).
4. **Checkstyle's `MissingSwitchDefault` forces a `default` into every switch statement** (20 of 20
   in `main`); on 9 enum switches that already cover every constant it is dead code that hides the
   next constant. A probe proves `case null ->` makes such a statement compiler-checked for
   exhaustiveness *and* Checkstyle-clean (JAVA-02; `google_checks.xml:172`; probes b/b2).
5. **Sealed types are consumed non-exhaustively in the exchange write path**: two `Planned` sealed
   interfaces are read with `if/else if` and no `else`, and three switches let `default` stand in
   for the last `ExchangeResource`/status constant (JAVA-03; `ExchangeStockWriteService.java:221`,
   `ExchangeUndoService.java:431`).
6. **The exchange mass-change capability check decides on a string**: `capability(String)` maps
   `resource` with `default -> HANGAR_WRITE` although a typed `ExchangeResource` enum exists with a
   different spelling — two vocabularies for one domain concept (JAVA-04;
   `ExchangeMassChangeService.java:182`, `:278-284`, `model/ExchangeResource.java:23`).
7. **Tenancy rules keyed on `OrgUnitKind` are re-derived 43 times in 19 files with `==`/`!=`**, and
   payout totals use `if/else if` over `FinanceType`; exhaustive switch-based predicates on the
   enums (precedent `OperationStatus.canTransitionTo`) turn a new constant into a compile error and
   give the shared kernel a small API (JAVA-05).
8. **Five type patterns on the lazily proxied `OrgUnit` hierarchy skip the `Hibernate.unproxy` that
   six other sites use**; Hibernate 7.4.5 ships `@ConcreteProxy` for exactly this, and a JVM probe
   shows proxies can never subclass a sealed entity (JAVA-06; `OrgUnitMembershipService.java:340`,
   `:462`).
9. **In these classpath (unnamed-module) applications a sealed type's permitted subclasses must share
   its package** (javac probe f), so the 13-subclass `AppException` and any cross-domain event
   contract cannot be sealed across per-domain packages — a hard constraint for target options A–C
   (JAVA-07; `backend/.../exception/AppException.java:37-50`).
10. **ADR-0223 and the vault are already stale** (Checkstyle has enforced `AvoidModuleImport` since
    49a3dcd4e, 42 not 34 `super(args)` calls, a fifth `ThreadLocal` — backend
    `ChangeSource.ON_BEHALF` — that is a clean `ScopedValue` fit); JEP 467 Markdown comments are
    blocked by Checkstyle 14.3.0; `_` is tool-clean everywhere except `EmptyCatchBlock` and
    `keycloak-spi`'s `--release 21` (JAVA-08/-09/-10/-12/-17).

## 2. Findings

Every block states the security impact explicitly. "Main" means `src/main`; counts are per the
scripts in Appendix F.

### JAVA-01 — Credentials (and personal data) printed by generated `toString()`

- **Evidence.**
  - `frontend/src/main/java/.../frontend/config/MonitoringScrapeProperties.java:33` `@Data`, `:35`
    class, `:49` `private String password = "";` — and the same in
    `ingest/src/main/java/.../ingest/config/MonitoringScrapeProperties.java:32/34/48`. Lombok
    `@Data` generates a `toString()` over all fields; the vault records that exactly this printed a
    client secret before (`10 Systems/Backend.md:369-371`, "the old `KeycloakSync` `@Data` class
    printed its client secret there").
  - The backend twin is a record with a redacting `toString()`
    (`backend/.../config/MonitoringScrapeProperties.java:38`, `:59`; asserted by
    `MonitoringScrapePropertiesTest.java:77`). Every other `@ConfigurationProperties` type is a
    record: backend 27/27, frontend 7/8, ingest 7/8 (grep in Appendix F). The vault's claim that
    ingest's "configuration properties are records" (`10 Systems/Ingest.md:713-714`) is therefore
    inaccurate for this one class.
  - `keycloak-spi/.../DiscordGuildRoleGateAuthenticator.java:280-281`
    `record Brokered(@Nullable String accessToken, @Nullable String username, @Nullable String email)`
    has no `toString` override; its sibling `DiscordMembershipChecker.MemberLookup` overrides one
    precisely so "the member body can never reach a log line" (`DiscordMembershipChecker.java:154`,
    `:172`). Today no log statement prints `brokered` (`:104-130` read), so this is latent.
  - `PiiMasker` masks JWTs, e-mail addresses and values after `bearer`/`token`/`session-id`/
    `authorization` (`logging-support/.../PiiMasker.java:42-49`); `password=`, `secret=` and
    usernames are not masked. `keycloak-spi` logs through JBoss logging inside Keycloak and has no
    masker at all.
  - Records print every component: 112 records in `main` carry personal-data names (handle,
    username, displayName, email) and 12 carry credential-looking names — 5 override `toString`
    (3 backend properties, ingest `ServiceAccountProperties` and `CachedToken`), 6 are false
    positives (`refillTokens`/`ipRefillTokens` of the rate-limit properties) and 1 is `Brokered`
    — `60-modern-java-sensitive-out.txt`.
    A heuristic scan of log statements found no DTO or entity passed whole to a logger.
- **Impact.** Modern-features: a Lombok→record conversion silently removes the option of
  `@ToString.Exclude`; every such conversion must re-audit `toString`. Domain separation: none.
- **Proposed change.** (1) Convert both `MonitoringScrapeProperties` to records with
  `@DefaultValue("")` components and the backend's redacting `toString`, port `isConfigured()`,
  and extend the existing frontend/ingest `MonitoringScrapePropertiesTest` (today it only drives
  `isConfigured()` through setters) with the backend's `toString` assertion. (2) Give `Brokered` a
  `toString` that prints presence only. (3) Add a ratchet test per module: enumerate the `main`
  classes (ArchUnit's class importer in
  backend/frontend/ingest; plain reflection in keycloak-spi), collect every record component and
  every field of a class with a generated `toString` whose name matches
  `(?i)secret|password|passphrase|token|credential|apikey|privatekey`, and fail unless the owning
  type is on a reviewed list of types that redact it. Lombok-generated members carry
  `@lombok.Generated` (`lombok.config:3`), so their field reads are visible in bytecode; how to tell
  javac's record `toString` from a hand-written one in bytecode is to verify — the reviewed list
  avoids needing it.
- **Pros.** Closes the only credential-in-`toString` exposures found; the ratchet catches the next
  one — including one created by a Lombok→record conversion — at test time.
- **Cons.** Three copies of one small record (sharing needs a module; `logging-support` must stay
  log hygiene only, ADR-0205).
- **Risks & regressions.** Record constructor binding changes the accessor names
  (`getPassword()` → `password()`); the compiler finds every caller in
  `MonitoringScrapeSecurityConfig` and the setter-based tests. **Security:** strictly positive; the
  scrape endpoint must stay fail-closed when blank (REQ-OBS-005). **Guard:** the existing
  `MonitoringScrapeSecurityConfigTest` and `MonitoringScrapeSecurityFailClosedTest` in both modules,
  the extended properties tests, the new ratchet test.
- **Effort.** S. **Prerequisites.** None; correct the vault's Ingest note.

### JAVA-02 — Dead `default` branches in enum switch statements hide new constants

- **Evidence.**
  - `config/checkstyle/google_checks.xml:172` `MissingSwitchDefault`; severity `warning` with
    `maxWarnings = 0` (`build.gradle.kts:288-294`) makes it a gate. All 20 switch statements in
    `main` carry a `default` (`60-modern-java-switches-out.txt`).
  - Probe b2 (`60-modern-java-probes-out.txt`): javac 25 accepts an arrow **statement** over an enum
    with a constant missing (line 26, no error) but rejects the same switch as an **expression**
    (line 56) and as a statement with a `case null` label (line 41): "the switch statement does not
    cover all possible input values". Probe b: Checkstyle 14.3.0 reports `MissingSwitchDefault` for
    the plain statement (b:26) and nothing for the `case null` statement (b:42) or the expression
    (b:58). Works identically with `--release 21`.
  - Enum switches in `main`: 56 (typed from bytecode `$SwitchMap$` tables for 55 of them — Appendix
    B). 35 expressions without `default` are already exhaustive (javac inserted 36 `new
    MatchException` defaults: 35 of these plus one sealed pattern switch). **9 statements cover
    every constant and still have `default`:**

    | Site | Enum | default does |
    |---|---|---|
    | `BankAccountService.java:247` | `BankAccountType` | `throw BadRequestException` |
    | `BankApprovalLimitService.java:167` | `BankAccountViewGranteeKind` | `{}` (limit silently missing from the admin view) |
    | `BlueprintImportService.java:148` | `BlueprintImportStatus` | `{}` |
    | `InventoryItemService.java:589`, `:657`, `:706` | `InventoryAllocationDimension` | `throw IllegalStateException` |
    | `NotificationRuleService.java:182` | `SelectorKind` | `throw IllegalArgumentException` |
    | `OrgHierarchyService.java:206` | `OrgUnitKind` | `throw IllegalStateException` |
    | `UserService.java:506` | `MembershipDeltaRequest…Action` | `throw IllegalArgumentException` |

    5 further statements handle a deliberate subset and keep a meaningful `default`
    (`OrgChartService.java:353` and `OrgUnitMembershipService.java:982`, 4 of 10 `MembershipRole`,
    throw = allow-list; `OrgChartService.java:788`, 6 of 13 `OrgChartPositionType`; the two
    `ExchangeResource` ones are JAVA-03).
  - Security-relevant enums: roles — `MembershipRole` 2 (the fail-closed allow-lists above),
    `BereichLeadershipRole` 3 and `OrgRelativeRole` 1, all exhaustive; authorization —
    `LiveSyncAuthorization` 1, exhaustive; audit — `AuditDomain` 2, exhaustive; bank —
    `BankAccountType` 6 (5 exhaustive, 1 dead default) and `BankAccountViewGranteeKind` 5 (4 + 1);
    tenancy — `OrgUnitKind` 6 (5 + 1). No switch over a permission or capability enum exists; the
    exchange capability is chosen by a string switch (JAVA-04), and `AuditEventType` is never
    switched on.
- **Impact.** Modern-features: uses switch exhaustiveness checking (available on 21 and 25, probe
  b2) where the project currently gets none.
  Domain separation: when enums become part of domain APIs, exhaustive consumers make an enum
  change a compile-time, cross-domain signal instead of a runtime 400/ISE.
- **Proposed change.** For the 9 sites: convert to a switch expression where every arm yields or
  assigns one value; otherwise keep the statement and replace `default -> throw …` by
  `case null -> throw new IllegalArgumentException(…)`. Write the policy down (ADR + `CLAUDE.md`
  Java conventions): *no `default` in a switch over a project enum unless it handles a deliberate
  subset*. Optional later gate: Error Prone's enum-switch checks (to verify).
- **Pros.** Adding a constant to `BankAccountType`, `OrgUnitKind`, `SelectorKind`, … breaks the
  build at each decision site instead of shipping a 400, an ISE or a silent no-op.
- **Cons.** `case null ->` is an unfamiliar idiom; a null selector would raise the chosen
  exception instead of an NPE (all 9 selectors are validated or non-null in practice — confirm per
  site).
- **Risks & regressions.** None of the 9 grants anything by default: they throw or do nothing, so
  the change cannot widen access. **Security:** Bank account creation (audited area) and org-unit
  parenting (tenancy) keep their rejections for invalid input; only the unreachable branch goes.
  **Guard:** the compiler; the existing service tests.
- **Effort.** S. **Prerequisites.** An ADR for the switch/default policy (it overrides a Checkstyle
  rationale by design); `CLAUDE.md` Java conventions line.
- **Automatable.** Partly (IDE "convert to switch expression"; OpenRewrite — to verify).

### JAVA-03 — Sealed `Planned` read by non-exhaustive `if/else`, and `default` standing in for the last constant

- **Evidence.** `ExchangeStockWriteService.java:1076` and `ExchangeBlueprintWriteService.java:422`
  `private sealed interface Planned permits Change, Skip {}` are consumed by
  `if (plan.get(i) instanceof Change change) {…} else if (plan.get(i) instanceof Skip skip) {…}`
  with no `else` (`ExchangeStockWriteService.java:221-235`, `ExchangeBlueprintWriteService.java:167-173`).
  `ExchangeShipWriteService.java:438` already switches exhaustively over its own sealed `Planned`
  (5 variants, no `default`; javac inserted a `MatchException` default — bytecode event).
  `default` as the last constant: `ExchangeUndoService.java:431-435` (`default ->
  liveSync.hangarChanged(member)` = SHIP), `ExchangeEntryLabels.java:74` (default handles SHIP),
  `ExchangeShipWriteService.java:557-561` (default = `UNMATCHED`, the third
  `ExchangeResolveResponse.Status`); `BankManagementReportService.java:290` and
  `BankStatementReportService.java:290` render 3 of 6 `BankTransactionType`s and `""` for the rest
  (the same switch, at the same line, in two services).
- **Impact.** Modern-features: sealed types without exhaustive consumers give no compile-time
  benefit. Domain separation: the exchange domain's internal closed sets are exactly where sealing
  is legitimate (private nested types, one package — see JAVA-07).
- **Proposed change.** Replace the two `if/else` chains by `switch (plan.get(i)) { case Change c ->
  …; case Skip s -> …; }`; name the last constant instead of `default` in the three exchange
  switches; give the bank-report label switch explicit arms (and one shared helper — a duplication
  note for the simplification agents).
- **Pros.** A new plan variant can no longer be dropped silently (today: no result row and no write
  for the external client's op); a fourth resource can no longer refresh the hangar topic.
- **Cons.** None material.
- **Risks & regressions.** Control flow only; the advisory-lock order (ADR-0229) and the journal
  writes are untouched. **Security:** neutral-positive (no silent drop of an external write).
  **Guard:** the compiler; the exchange write controller tests.
- **Effort.** S. **Prerequisites.** None.

### JAVA-04 — Stringly-typed discriminators in the exchange capability decision

- **Evidence.** `ConnectedAppMassChangeRequestDto.java:41`
  `@Pattern(regexp = "^(blueprints|stock|ships)$") String resource`; `ExchangeMassChangeService.java:77-78`
  private `String` constants; `:182` `!client.getCapabilities().contains(capability(request.resource()))`;
  `:278-284` `capability(String)` with `default -> ExchangeCapability.HANGAR_WRITE`; `run()` (`:224`)
  with `default` → ship change set; `ExchangeShipChangeSet.java:71`
  `@Pattern(regexp = "^(link|upsert|remove)$") String op` and `ExchangeShipWriteService.java:218-222`
  `default -> planRemove(…)`. The change set is validated after a manual Jackson read
  (`ExchangeMassChangeService.java:258-270`, `validator.validate`), and ops carry
  `List<@NotNull @Valid Op>` (`ExchangeShipChangeSet.java:39`), so today an unknown value is
  refused before it can reach a `default`. Meanwhile `model/ExchangeResource.java:23-31` defines
  `BLUEPRINT, STOCK, SHIP` (journal, feeds), `ExchangeLiveSync.java:44/50` a third set of string
  constants, and `ExchangeBulkUndoService.java:375` parses the upper-case spelling with
  `ExchangeResource.valueOf`.
- **Impact.** Domain separation: the exchange domain has no single typed vocabulary for "resource";
  a clean domain API would expose one enum and map wire spellings at the boundary. Modern-features:
  exhaustive switches cannot apply to strings.
- **Proposed change.** Map each wire spelling to `ExchangeResource` once at the request boundary
  (explicit mapping, unknown → 400), keep every wire string unchanged (additive contract,
  ADR-0219), and make `capability()` and the handler selection exhaustive switch expressions over
  the enum; the same for the ship op (`LINK`/`UPSERT`/`REMOVE`).
- **Pros.** The capability ↔ handler mapping becomes compile-checked and lives in one place.
- **Cons.** Mapping code for two spellings ("blueprints" and "BLUEPRINT") must stay.
- **Risks & regressions.** Must not change the external contract or error codes. **Security:**
  keep Bean Validation as the first line; an unknown resource must still be refused before any
  capability lookup (fail-closed), and no path may default to a capability. **Guard:**
  `backend/api/ExternalContractTest`, per-resource capability tests, a test that an unknown value
  never reaches `capability()`.
- **Effort.** S–M. **Prerequisites.** None (REQ-XCH wording unchanged).

### JAVA-05 — Enum decisions written as `==` chains instead of exhaustive behaviour on the enum

- **Evidence.** `OrgUnitKind` is compared with `==`/`!=` **43 times on 42 lines in 19 files** of
  backend `main` (`grep -o`, Appendix F; e.g. `AccessGateService.java:255` and `:332` — SK-responsible job
  orders are public; `BankAccountService.java:257-258`, `:274`, `:292`;
  `OrgUnitMembershipQueryService.java:179-180`; `MaterialClaimService.java:500`, `:583`;
  `JobOrderService.java:1016-1038`; `LeitungViewService.java:116-124`), and `OrgUnitKind.java` has
  no behaviour method. `FinanceType { INCOME, EXPENSE }` drives payout arithmetic with `if/else if`
  and no `else` (`OperationPayoutCalculator.java:62-66`; `OperationFinanceService.java:118`,
  `:233`). `JobOrderService.java:276-279` derives "terminal" twice with `==`. The codebase's own
  precedent: `OperationStatus.canTransitionTo` is an exhaustive switch on `this`
  (`OperationStatus.java:33-42`), and the live-sync authorizer decides with an exhaustive switch
  (`LiveSyncSubscriptionAuthorizer.java:90`).
- **Impact.** Domain separation: `OrgUnitKind` is shared kernel (orgunit) consumed by bank,
  joborder, materials, org chart and Leitung; predicates on the enum become its public API and stop
  each domain from re-deriving tenancy rules. Modern-features: exhaustive switch expressions.
- **Proposed change.** Add small predicates implemented as `switch (this)` without `default`
  (e.g. `isTenantUnit()`, a named predicate for the SK-public job-order rule,
  `JobOrderStatus.isTerminal()`), replace the call sites one domain at a time, and turn the
  `FinanceType` chains into `switch` expressions. For each predicate add a test that iterates
  `values()` and asserts the expected set, so a new constant fails a test even outside switches.
- **Pros.** A fifth org-unit kind or a third finance type becomes a compile error where the
  semantics live; call sites read as intent.
- **Cons.** Naming the predicates is a domain decision that must match the tenancy spec
  (`docs/specs/org-unit-tenancy.md`); M effort across 19 files.
- **Risks & regressions.** Each site's current fail-open/fail-closed behaviour must be preserved
  exactly — a generalised predicate could widen visibility. **Security:** tenancy scoping
  (`OwnerScopeService`, `AccessGateService`) — keep one predicate per rule, never merge two rules
  into one name. **Guard:** the existing access-gate and scope tests; ArchUnit unchanged; the new
  `values()` tests.
- **Effort.** M. **Prerequisites.** Tenancy-spec review of the predicate names.

### JAVA-06 — Type patterns on the lazily proxied `OrgUnit` hierarchy; never seal entities

- **Evidence.** `OrgUnit` is `@Entity @Inheritance(SINGLE_TABLE)` and `abstract`
  (`model/OrgUnit.java:51-59`), with 7 `LAZY` to-one associations to it (grep, Appendix F). Five
  sites pattern-match on its subclasses without unproxying:
  `OrgHierarchyController.java:366` (`u instanceof Bereich b`), `LeitungViewService.java:199`,
  `OrgChartReadService.java:101`, `OrgUnitMembershipService.java:340` (clears the Grand Admiral
  appointment when the member leaves the OL) and `:462` (`requireOrganisationsleitung`). Six other
  sites unproxy `OrgUnit` values with `Hibernate.unproxy(ou, OrgUnit.class)`, one of them directly
  before a type pattern (`StaffelMembershipResolver.java:123`; also
  `JobOrderHandoverService.java:148`, `JobOrderItemHandoverService.java:295`,
  `OrgUnitStampingService.java:109`, `:284`, `RequestScopeResolver.java:560`). Hibernate ORM is
  7.4.5.Final (`gradle/verification-metadata.xml:6258`); its jar contains
  `org.hibernate.annotations.ConcreteProxy` (`@Incubating`, since 6.6), whose source Javadoc
  (hibernate-core-7.4.5.Final-sources.jar) says that with it lazy associations "can safely be used
  with instanceof checks and type-casts" — i.e. without it they cannot. JVM probe
  (`60-modern-java-sealedload.py`): a class not listed in a sealed class's `PermittedSubclasses`
  fails to load — `IncompatibleClassChangeError: Failed listed permitted subclass check` — so a
  runtime-generated proxy can never subclass a sealed entity (or a sealed CGLIB-proxied bean).
  Whether these five flows actually receive a proxy is **UNKNOWN**: it depends on whether the
  persistence context already holds a proxy for that id (e.g. from `membership.getOrgUnit()`
  earlier in the same transaction).
- **Impact.** Modern-features: pattern matching is unsafe on proxied entity hierarchies; sealing
  entities is impossible. Domain separation: the orgunit kernel should offer one safe
  "as-subtype" helper instead of each domain pattern-matching entities.
- **Proposed change.** First a test that reproduces the proxy case (load an OL membership, touch
  `getOrgUnit()`, then run the removal path; assert `grandAdmiralUserId` cleared). If reachable:
  unproxy at the five sites (current practice), or dispatch on `getKind()` (works through a
  proxy), or annotate `OrgUnit` with `@ConcreteProxy` after measuring the extra discriminator join
  on the 7 lazy associations. Write down: no type pattern on an entity hierarchy without unproxy;
  no sealed entity classes.
- **Pros.** Removes a class of silent wrong answers (stale appointment; spurious "is not the
  Organisationsleitung" 400).
- **Cons.** `@ConcreteProxy` is incubating and may add a join per lazy load.
- **Risks & regressions.** **Security:** `grandAdmiralUserId` is read for display and the
  appointment flows (`OrgUnitMembershipService.java:375`, `:410`, `LeitungViewService.java:199`,
  `OrgChartReadService.java:102`) and by no authorization check (grep), so the impact is data
  integrity of a top-level appointment, not privilege. **Guard:** the new integration test; an
  ArchUnit rule on `instanceof` checks against `OrgUnit` subtypes outside an allow-listed helper
  (ArchUnit `InstanceofCheck` support — to verify).
- **Effort.** S. **Prerequisites.** None.

### JAVA-07 — Sealed hierarchies and package-by-domain are in conflict on the classpath

- **Evidence.** Probe f (`60-modern-java-probes-out.txt`), javac 25 and `--release 21`: "class Shape
  in unnamed module cannot extend a sealed class in a different package". All three applications
  run on the classpath (unnamed module). `AppException` is `abstract sealed` with 13 permitted
  subclasses, all in `backend.exception` (`AppException.java:37-50`, sealed since `8bd9c63bb`, #933);
  six are domain-specific (`BankConflictException`, `ExchangeProblemException`,
  `MissionParticipantRequiredException`, `OverAllocationException`,
  `ProductionAllocationException`, `OwnerOrgUnitRequiredException`). The cross-domain
  `NotificationEvent` contract is implemented by 10 records in `backend.event`,
  `BankBookingRequestEvent` by 4. The three sealed `Planned` types are private and domain-internal.
  Side note: the field Javadoc at `AppException.java:53` says "the ten subtypes that have one"; 11
  subtypes pass a fixed `AppExceptionKind` (grep) — a small Javadoc drift.
- **Impact.** Domain separation: under option A (packages per domain) and B/C (Gradle subprojects
  are still unnamed modules), a sealed type can only close over one package. Any plan that moves
  domain exceptions or domain events next to their domain must either keep the sealed base and all
  its subtypes in a shared-kernel package or unseal it.
- **Proposed change.** Decide in the target-architecture ADR: `AppException` + `AppExceptionKind`
  + the error-code/disclosure contract are shared kernel (one package, stays sealed); domains throw
  kernel subtypes. Do not seal cross-domain contracts (`NotificationEvent`); seal only closed sets
  inside one domain package (the `Planned` pattern). Do not seal interfaces that tests implement
  or whose implementations are proxied beans (`SubjectAuthentication`: 1 main and 6 test
  implementations; frontend `LiveSyncFanout`: 2 main and 1 test implementation — grep).
- **Pros.** Avoids a refactor dead end; keeps error disclosure (`ErrorDisclosurePolicy`,
  `AppException.java:172`) reviewed in one place.
- **Cons.** Domain exceptions do not live with their domain.
- **Risks & regressions.** **Security:** unsealing would make the exception base an open extension
  point for disclosure behaviour; keeping it sealed keeps new subtypes a reviewed kernel change.
  **Guard:** javac itself; ArchUnit package rules of the new layout.
- **Effort.** S (a decision). **Prerequisites.** Part of the modularisation ADR.

### JAVA-08 — Backend `ChangeSource.ON_BEHALF` is a `ThreadLocal` that fits `ScopedValue`

- **Evidence.** `backend/.../support/ChangeSource.java:43` `private static final ThreadLocal<String>
  ON_BEHALF`; `:55` `asClient(clientId, installationKey, Supplier<T> write)` sets it, runs the
  write and restores it in `finally`; `current()` is read by `ChangeSourceTransactionManager.java:69`
  (`setParameter("source", changeSource.current())`); one caller
  (`ExchangeMassChangeService.java:137`). The `ThreadLocal` arrived in `71a5ae641`
  (2026-09-27 17:14), after ADR-0223 was merged (`1e9bbb772`, 09:18), whose inventory names only
  the four frontend holders (ADR-0223:32-41) and whose decision 4 covers only them (:79-81). The
  Reactor-relay reasoning does not apply: the backend has no WebFlux (ADR-0204). Probe e2:
  `ScopedValue` compiles without preview on `--release 25`, and is a preview API on 21.
  Main `ThreadLocal`s: backend 1, frontend 4 (the ADR's four), others 0.
- **Impact.** Modern-features: the one legitimate JEP 506 use in the repository. Domain
  separation: `ChangeSource` is exchange-attribution infrastructure; a scoped binding makes its
  contract ("only inside this block") explicit for whichever module owns it.
- **Proposed change.** Amend ADR-0223 (inventory and decision 4 scope) and bind the value with
  `ScopedValue.where(ON_BEHALF, …)` around the write (exact JDK 25 call API — to verify).
- **Pros.** Immutable for the block, no manual restore, cannot leak into a reused thread.
- **Cons.** Small API change; tests that set the value must use the block form. The value is not
  inherited by other threads — the same as today.
- **Risks & regressions.** **Security:** the label attributes exchange writes to a client in the
  change feed (ADR-0224); a leaked binding would mis-attribute later writes on that thread, which
  the scoped form rules out. **Guard:** `ChangeSourceTransactionManagerIntegrationTest`, the
  exchange mass-change tests.
- **Effort.** S. **Prerequisites.** ADR-0223 amendment.

### JAVA-09 — ADR-0223 and the vault drifted; decisions 1 and 3 are half-enforced

- **Evidence.** ADR-0223:91-93 "Nothing enforces decisions 1 and 3 in CI yet" — but
  `google_checks.xml:80-86` has carried the `CompactSourceFileNotAllowed` XPath ban and
  `AvoidModuleImport` since `49a3dcd4e` (Checkstyle 14.2.0 bump, 2026-09-27 17:17, eight hours after
  the ADR; its commit message lists both); probe e6 shows `AvoidModuleImport` firing. Only
  `checkstyleMain` uses that config; test and e2e tasks run `javadoc_position.xml` alone
  (`build.gradle.kts:295-298`), so `import module` is still unguarded there. ADR-0223:50 counts
  "34 explicit `super(args)` calls"; there are 42 today (none validates around it — the JEP 513
  conclusion stands). The vault repeats "Neither rule is gated in CI" (`40 Decisions/Decisions.md:618`,
  ADR-0223 section). No gate exists for `--enable-preview` (`build.gradle.kts:189-192`).
- **Impact.** Modern-features governance; no domain impact.
- **Proposed change.** Dated amendment of ADR-0223 (enforcement, count, five `ThreadLocal`s,
  JEP 467 note from JAVA-12) and the vault note; add `AvoidModuleImport` to
  `javadoc_position.xml`; fail the build when any `JavaCompile`/`Test`/`JavaExec` argument contains
  `--enable-preview` (configuration-cache compatible check in the root `subprojects` block, or a
  `repo-lint` grep).
- **Pros.** The ADR states what is actually gated; decision 1 gets a gate.
- **Cons.** A build-script change must pass the configuration-cache checks.
- **Risks & regressions.** **Security:** none; the preview gate prevents semantics changing under a
  JDK update. **Guard:** `./gradlew help --configuration-cache` twice (per `CLAUDE.md`).
- **Effort.** S. **Prerequisites.** ADR amendment (`javadoc_position.xml` is repo-owned, not the
  vendored file, so no dependency-pins note).

### JAVA-10 — Unnamed variables (`_`, JEP 456): tool-clean, largely unused

- **Evidence.** Used once: `SseSendFailureCause.java:50-51` (`case IOException _`, from #2011).
  Candidates in `main`: **107 unused lambda parameters** (by name: `k` 36, `key` 17, `status` 9,
  `ignored` 9, `id` 5, `unused` 2, …; e.g. `JobOrderQueryService.java:239` `unused -> new
  HashMap<>()`, `BankSecurityService.java:82` `g -> true`, `SecurityConfig.java:312` `userId ->
  true`), **133 unused catch parameters** in non-empty bodies (66 named `e`), **20 empty catches**
  named `ignored` (frontend 18: 6 in `MissionPageController`), one unused pattern binding
  `case Skip ignored` (`ExchangeShipWriteService.java:438` switch). Tests: 280 lambda, 26 catch,
  23 empty catches. Tooling (probe a): javac 25 accepts `_` in lambdas, for-each, catch,
  try-with-resources and record-pattern components; `--release 21` rejects it ("unnamed variables
  are not supported in -source 21"); Checkstyle 14.3.0 accepts it everywhere (the naming rules
  already allow `_`, `google_checks.xml:233-265`) **except** `EmptyCatchBlock` on an empty
  `catch (X _) {}` (a:40), because `exceptionVariableName` is `^(ignored|expected)$`
  (`google_checks.xml:445-447`, the ADR-0214 convention); google-java-format 1.36.1 leaves all of
  it unchanged. Five catches rethrow a new exception without the cause
  (`AdminP4kImportController.java:185`, `BlueprintUploadPreviewService.java:109`,
  `UserService.java:275`, `CurrentUserArgumentResolver.java:78`,
  `AdminP4kImportPageController.java:215`).
- **Impact.** Modern-features only.
- **Proposed change.** Use `_` for unused lambda parameters and pattern bindings, and for unused
  catch parameters whose name carries no meaning (`e`, `ex`, `ignored`). Keep descriptive names
  (`notFound`, `absent`, `malformed`, `notAnId`) — with comments banned (ADR-0214) the name is the
  only in-code statement of intent. Empty catches only after widening `exceptionVariableName` to
  `^(_|ignored|expected)$`, amending ADR-0214 and recording the third deliberate edit of the
  vendored config (the 14.2.0 commit documents exactly two). Not in `keycloak-spi`. Chain the cause
  in the five rethrows instead of cementing the drop with `_`.
- **Pros.** The compiler forbids accidental use; intent is visible.
- **Cons.** ~260 edits in `main` (+ ~330 in tests); `_` cannot be inspected in a debugger.
- **Risks & regressions.** **Security:** neutral. Chaining causes is safe only if no problem body
  renders `getCause()` — disclosure is built centrally by `GlobalExceptionHandler`
  (`ErrorDisclosurePolicy`); verify before chaining. **Guard:** compile + Checkstyle; nothing
  detects new unused names (Error Prone `UnusedVariable` — to verify).
- **Effort.** S–M. **Prerequisites.** ADR-0214 amendment for empty catches; one line in the Java
  conventions. **Automatable.** IDE inspection / OpenRewrite (to verify).

### JAVA-11 — Records: finish the last carriers, copy collections only where values are shared

- **Evidence.** Records: backend 663, frontend 357, ingest 26, keycloak-spi 2, test-support 1 in
  `main`; 12 compact constructors in `main`, 1 validating. Collection components: backend 262 in
  170 records, **0** defensively copied; frontend 5 of 189; ingest 3 of 3. Lombok `@Value`: 0.
  Remaining non-entity `@Data` carriers: backend `AnnouncementController.AnnouncementRequest`
  (`:106`), `UserController.UserAttributesRequest` (`:727`, whose admin twin
  `AdminController.AdminUserAttributesRequest`, `AdminController.java:136`, is already a record),
  `UserDescriptionRequest` (`:742`);
  frontend `InventoryForm.AllocationRow` and 18 `*Form` classes (Spring MVC / `th:field` binding —
  keep as JavaBeans); the two properties classes of JAVA-01; one private nested carrier with a
  hand-written all-args constructor (`ShipTypeMatcher.java:285` `TokenView`). The other 27 of the
  28 "final-field carriers" the scan found in `main` are accumulators with mutable collections or
  behaviour objects (interceptors, handlers, guards). Session rule:
  `RedisSessionConfig.java:70-76` — "application code must not write final types" as session
  values; a record inside a flash map already works in production (`AdminBankPageController.java:102`
  puts the record `BankWipeResetResultDto` into a flash attribute; ADR-0206 Amendment 1 explains
  why containers carry the type id). The July audit's principle: `MissionPeerRedactor` keeps an
  explicit canonical construction so every new field forces a redaction decision
  (`10 Systems/Backend.md:319-323`).
- **Impact.** Domain separation: records are the right currency for domain-facade DTOs, commands and
  events; value objects that cross a domain boundary should be deeply immutable.
- **Proposed change.** Convert the three backend request classes and `TokenView` to records. Do not add blanket
  `List.copyOf` to DTO records. Add null-tolerant defensive copies (`x == null ? List.of() :
  List.copyOf(x)`) only to records that become cached, shared or domain-API values in the refactor.
  Keep redaction DTOs on canonical constructors (no `withX` helpers there). Never make a record a
  top-level session attribute in the frontend.
- **Pros.** One fewer mutable request type per endpoint; shared values cannot be mutated by a
  caller.
- **Cons.** `List.copyOf` rejects `null` elements — a JSON `[null]` would turn from accepted into a
  deserialisation failure.
- **Risks & regressions.** **Security:** records print all components (JAVA-01 guard);
  `displayName` is already printed by `@Data`, so neutral; any exception inside a record
  constructor during deserialisation must still map to 400, not 500. **Guard:**
  `FrontendDtoContractTest` / `GeneratedDtoAgreementTest`, `openapi.json` diff, the session
  allow-list parity test.
- **Effort.** S. **Prerequisites.** None.

### JAVA-12 — Markdown documentation comments (JEP 467) are blocked by Checkstyle

- **Evidence.** 16,954 `/** */` blocks (11,874 in `main`), 0 `///` lines. Probe c: Checkstyle 14.3.0
  with the repo config reports `MissingJavadocType` and `MissingJavadocMethod` for a
  `///`-documented public class and method, i.e. it does not see them as Javadoc, and
  `maxWarnings = 0` would fail `checkstyleMain`. google-java-format 1.36.1 leaves them unchanged
  (its POM depends on `org.commonmark:commonmark:0.28.0`; without it on the classpath the formatter
  fails in `javadoc.MarkdownPositions`, first probe run). The JDK 25 `javadoc`
  tool renders them (`<code>code</code>` in the output), also with `--release 21`.
- **Impact.** None on domains; no practical gain for 17k existing blocks.
- **Proposed change.** Do not adopt; note in ADR-0223; re-check at each Checkstyle bump (the config
  refresh routine of `49a3dcd4e`).
- **Pros (of adopting).** Markdown is easier to write than HTML-flavoured Javadoc.
- **Cons.** Fails `checkstyleMain` today; two documentation syntaxes side by side.
- **Risks & regressions.** **Security:** none. **Effort.** none (no change). **Prerequisites.** A
  Checkstyle release whose Javadoc checks accept `///` (to verify at each bump).

### JAVA-13 — JetBrains annotations versus JSpecify

- **Evidence.** 997 files import `org.jetbrains.annotations` (923 of 2,044 `main` files, 45%);
  uses: `@NotNull` 7,410, `@Nullable` 1,756, `@Contract` 92, `@Unmodifiable` 88,
  `@UnmodifiableView` 16. JSpecify imports: 0; `org.springframework.lang` imports: 0. In spring-core
  7.0.9 (the version in `verification-metadata.xml:8035`) `org.springframework.lang.Nullable` is
  `@Deprecated(since = "7.0")` and `org.springframework.util`'s `package-info` carries
  `@org.jspecify.annotations.NullMarked` (javap); spring-core's POM has a compile dependency on
  `org.jspecify:jspecify:1.0.0`. ADR-0192 rejected a second vocabulary (`:225-227`) and documents
  that a field's JetBrains `@NotNull` makes Lombok emit runtime null checks (`:37-40`, `:135-140`).
- **Impact.** Domain separation: a per-package `@NullMarked` fits naturally into the
  `package-info.java` each new domain package gets — if it is ever adopted, do it during the package
  re-cut, not as a separate sweep. Modern-features: aligns with Spring 7's own nullness model.
- **Proposed change.** Keep JetBrains now. Decide together with the Error Prone/NullAway evaluation
  (research agent; NullAway support for JetBrains annotations — to verify) in an ADR-0192
  amendment.
- **Pros (of migrating).** One standard vocabulary with the framework; tooling support.
- **Cons.** ~1,000-file churn; no JSpecify equivalent for `@Contract`/`@Unmodifiable`; Lombok's
  field null checks would disappear under a package default (a behaviour change ADR-0192 warns
  about).
- **Risks & regressions.** **Security:** annotations are compile-time only; a wrong default can hide
  NPE paths but cannot change authorization. **Guard:** SpotBugs `NP_*` (the check that caught
  ADR-0192's mistake). **Effort.** L. **Prerequisites.** ADR-0192 amendment.

### JAVA-14 — Usernames pseudonymised with `String.hashCode()` in frontend logs

- **Evidence.** `BackendRoleSyncFilter.java:150` `String.format("u-%08x", name.hashCode())`;
  `JobOrderPageController.java:1116`, `JobOrderWriteController.java:1436`,
  `RefineryOrderPageController.java:1006` and `:1026` log
  `Integer.toHexString(Objects.hashCode(principal.getName()))`. The principal name is
  `preferred_username` (`frontend/src/main/resources/application.yml:77`). A 32-bit
  non-cryptographic hash of a name from a known member list is reversible by dictionary. The same
  module already fingerprints session ids with SHA-256 + `HexFormat`
  (`frontend/support/SessionIdFingerprint.java:62`). (`IngestHandoffService`/`HandoffStagingService`
  hash high-entropy ids the same way — acceptable there.)
- **Impact.** Modern-features: `HexFormat` already has 6 call sites in `main`; these five are the
  remaining hand-rolled hex. No domain impact.
- **Proposed change.** Drop the name from these lines and rely on the MDC user id (verify that it
  holds the Keycloak subject, not the username), or use a keyed HMAC rendered with `HexFormat`.
- **Pros.** Removes a reversible pseudonym of personal data from the logs.
- **Cons.** The lines lose their per-user tag if the MDC id is absent on that code path.
- **Risks & regressions.** **Security:** positive (REQ-OBS-004 "never log names"). **Guard:** a
  log-capturing test asserting no `u-` token derived from the username. **Effort.** S.
  **Prerequisites.** None — forwarded to the security audit.

### JAVA-15 — Small idiom remnants (each S, mostly automatable)

| Idiom | Evidence | Proposed change | Risk / guard | Automatable |
|---|---|---|---|---|
| `Math.clamp` | 2 used (`ExchangeFeedReader.java:69`, `ExchangeBudget.java:458`); 15 `max(min(..))` candidates in `main` (e.g. `NotificationService.java:86`, `UexLocationController.java:72`, `RefineryOrderService.java:735`) | Migrate the 11 whose bounds are constants or provably ordered | `Math.clamp` throws if `min > max`; leave the runtime/config-bound `BankBalanceChart.java:204`, `MaterialExchangeBoardService.java:526`, `:547` and keycloak-spi `DiscordMembershipChecker.java:203` | IDE |
| `get(0)` → `getFirst()` | 9 in `main`: frontend 5, ingest 4 (backend already 0 — BE-MOD-06) | Finish BE-MOD-06 outside the backend | none (all guarded by `isEmpty`) | OpenRewrite (to verify) |
| `Environment.matchesProfiles` | `Arrays.asList(getActiveProfiles())` + `contains` in `WebClientConfig.java:190`, `BackendHealthIndicator.java:208`, ingest `RestClientConfig.java:210` | Same as the backend's BE-MOD-06 change | profile-gated TLS trust — test both profiles | manual |
| `isPresent()` then `get()` | 13 in `main` (`P4kImportService.java:307…1097`, `UexManufacturerService.java:215/220`, `ExchangeIdempotencyFilter.java:158/217`) | `or(...)`/`map`/`ifPresentOrElse` chains | none | IDE |
| `@Query` concatenation → text block | 47 of 363 queries (`MissionRepository` 9, `InventoryItemRepository` 4, `JobOrderRepository` 4); 180 already text blocks | Text blocks | JPQL is validated at bootstrap; removes missing-space bugs | OpenRewrite (to verify) |
| JSON in tests | 631 literals with ≥ 4 `\"` (`KeycloakServiceTest` 45, `BlueprintImportServiceTest` 41) | Text blocks | tests only | OpenRewrite (to verify) |
| Multi-line concatenations in `main` | 160 runs, mostly OpenAPI `description`s reflowed by google-java-format's `reflowLongStrings()` (`build.gradle.kts:307`; `MissionController.java:188-190`) | Leave — a text block would change `openapi.json` strings | churn in generated `openapi.json` | — |
| `trim()` vs `strip()` | 144 / 19 in `main` | Leave: `StringNormalization` uses both on purpose (`:81-82` strip, `:101` trim removes control characters) | a mechanical swap lets edge control characters through | — |
| `var` | 182 vs ~28,000 explicitly typed local declarations | No sweep; allowed where the type is on the right-hand side | readability only | — |
| `Collections.unmodifiable*` | 21 in `main`, all views over entity collections (`Mission.java:318`, `Blueprint.java:216`) | Keep — `copyOf` would copy and initialise lazy collections (ADR-0192 `@UnmodifiableView`) | — | — |
| `Arrays.asList` | 5 in `main`; `ExchangeResolveService.java:455` holds possibly-`null` lookups | Keep there — `List.of` throws on `null` | NPE | — |
| `@Serial` | 7 `serialVersionUID` in `main`, `@Serial` on 4 (ingest) | Add `@Serial` to `OrgUnitMembershipId.java:51`, `SessionTypeAllowList.java:263`, `RateLimitBuckets.java:71` | none | IDE |
| Inline fully-qualified names | frontend `main` 482, backend `main` 19, tests ~2,300 (lexer count, `60-modern-java-fqn.py`) | Finish BE-SIMP-10's frontend half | none | OpenRewrite `ShortenFullyQualifiedTypeReferences` (named by the Sept. audit; to verify) |
| `mapMulti`, gatherers (JEP 485) | 0 uses; 1 `flatMap(Stream.of…)` in `main`; chunk loops (`SyncChunkWriter.java:92`, `StalePriceSweep.java:66`, `ExchangeDraftService.java:73`) run one transaction per chunk | No adoption — a gatherer is not clearer than a transactional loop | — | — |

Pros for the migrate rows: consistency with the 90 %+ of the code that already uses the modern
form, fewer hand-written bounds/index checks. Cons: diff churn that competes with the domain
refactor for review time — batch them into the files the refactor touches anyway. Security for the
whole table: neutral, except the `Arrays.asList` and `trim()` rows, which are "do not migrate"
precisely because the modern form changes null/whitespace handling of input.

### JAVA-16 — Concurrency and legacy APIs: confirmed sound, keep

- **Evidence.** `spring.threads.virtual.enabled: true` in all three base `application.yml`
  (backend `:28-30`, frontend `:38-40`, ingest `:16-18`); no profile file overrides it. Virtual
  threads also back `LiveSyncStreamService.java:105` and `ParallelPageLoader.java:55` (which copies
  the request context by hand, ADR-0223). `synchronized`: 4 blocks + 2 methods in `main`. Pinning
  probe on this JDK 25 (`60-modern-java-probe/PinningProbe.java`, one carrier): 8 virtual threads
  each sleeping 200 ms inside their own monitor finished in 205 ms (pinned would be ≥ 1,600 ms) —
  so no `synchronized` → `ReentrantLock` migration is needed. `Thread.sleep` in `main`: 3
  (`ScWikiClient.java:587`, `CustomJwtGrantedAuthoritiesConverter.java:289` on the request path,
  keycloak-spi `DiscordMembershipChecker.java:206` — Keycloak's JVM is JDK 21, pinning there is
  not covered by the probe, and the sleep is not inside a monitor). `CompletableFuture.allOf` in 8
  page controllers stays (Structured Concurrency is preview, ADR-0223). Legacy APIs in `main`:
  `java.util.Date`/`Calendar`/`SimpleDateFormat`/`Vector`/`Hashtable`/`Stack`/`StringBuffer`/
  `finalize`/`new URL`/`new Locale`/boxing constructors: 0 (Date appears in 6 test files, `java.sql`
  time types in 6 backend test files). JEP 500: all 36 `ReflectionTestUtils.setField` calls in tests
  target non-final fields (`60-modern-java-reflect.py`: MapStruct `@Autowired protected` fields,
  `@Value` fields, entity `version`), and the one `setAccessible(true)` is on a method
  (`GlobalExceptionHandlerTest.java:967`) — the conversion recorded in `backend/CLAUDE.md` is
  complete and has not regressed. Hand-written `equals`/`hashCode`: 5 in `main`, all justified by
  ADR-0192 (proxy-aware `AbstractEntity`, two composite ids, identity-equal
  `LiveSyncStreamService.Subscription`, a `ByteArrayResource` subclass).
- **Proposed change.** None now. When the toolchain moves past 25, arm
  `--illegal-final-field-mutation=deny` on the `Test` tasks (already written down in
  `backend/CLAUDE.md`).
- **Pros / cons.** Keeping avoids churn with no measured gain; the cost is none.
- **Risks & regressions.** **Security:** none; the JEP 500 gate protects test integrity only.
  **Guard:** the pinning probe can be re-run on a toolchain change. **Effort.** none.
  **Prerequisites.** A toolchain above 25 for the JEP 500 flag.

### JAVA-17 — `keycloak-spi` on `--release 21`: what it cannot use, and what that costs

- **Evidence.** `keycloak-spi/build.gradle.kts:13` `options.release.set(21)` for every compile task
  (tests included); class files are major 65 (javap), `logging-support`'s are 69. Probes with
  `--release 21`: **rejected** — unnamed variables (JEP 456), statements before `super` (JEP 513),
  module imports, `java.util.stream.Gatherers`, `javax.crypto.KDF`, the `java.lang.classfile`
  package (not in the 21 API), `ScopedValue` and `java.lang.foreign.Arena` (preview APIs on 21);
  **accepted** — records, record patterns, pattern `switch` with guards, sequenced collections,
  `Math.clamp`, `StringBuilder.repeat`. Module usage: 16 `main` files, 2 records, 1 type pattern,
  0 switches, 5 unused catch parameters, 1 clamp-shaped expression whose maximum comes from
  configuration (`DiscordMembershipChecker.java:203` — leave, see JAVA-15).
- **Impact.** The only thing lost today is `_` on 5 catch parameters. No domain impact.
- **Proposed change.** None beyond JAVA-01 (`Brokered`). Keep the release in lockstep with
  Keycloak's JDK (vault `10 Systems/Keycloak SPI.md:49-53`).
- **Pros / cons.** Raising the release is not possible while Keycloak runs JDK 21; staying costs
  only `_` on 5 catches.
- **Risks & regressions.** **Security:** logging there has no masker, which is why JAVA-01's
  `toString` fix matters most in this module. **Guard:** Keycloak refuses a class file above its
  JDK (`UnsupportedClassVersionError`, vault note). **Effort.** none. **Prerequisites.** —

## 3. Data appendix

### A. Idiom inventory (old form vs modern form)

Counts from `60-modern-java-tables.py` over `60-modern-java-data.json`.

| Idiom | backend main | frontend main | ingest main | keycloak-spi main | logging-support main | test-support main | all tests+e2e |
|---|---|---|---|---|---|---|---|
| Records declared | 663 | 357 | 26 | 2 | 0 | 1 | 29 |
| switch: arrow form / colon form | 63 / 0 | 18 / 0 | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 | 8 / 0 |
| instanceof type pattern | 58 | 79 | 26 | 1 | 0 | 0 | 23 |
| instanceof + cast (old) | 3 | 1 | 0 | 0 | 0 | 0 | 1 |
| instanceof without binding (legit) | 10 | 25 | 5 | 0 | 0 | 0 | 7 |
| if/else instanceof chain (>=2 arms) | 3 | 1 | 0 | 0 | 0 | 0 | 1 |
| if/else enum == chain (>=2 arms) | 8 | 0 | 0 | 0 | 0 | 0 | 1 |
| unnamed `_` used | 2 | 0 | 0 | 0 | 0 | 0 | 0 |
| lambda params unused | 67 | 33 | 7 | 0 | 0 | 0 | 280 |
| catch param unused (non-empty body) | 55 | 58 | 15 | 5 | 0 | 0 | 26 |
| catch block empty | 2 | 18 | 0 | 0 | 0 | 0 | 23 |
| text blocks | 234 | 1 | 4 | 0 | 0 | 0 | 180 |
| multi-line literal concatenation (>=3 literals) | 143 | 11 | 4 | 2 | 0 | 0 | 223 |
| @Query with `+` concatenation | 47 | 0 | 0 | 0 | 0 | 0 | 0 |
| @Query as text block | 180 | 0 | 0 | 0 | 0 | 0 | 0 |
| String.format( | 2 | 3 | 0 | 0 | 0 | 0 | 38 |
| .formatted( | 5 | 0 | 0 | 0 | 0 | 0 | 78 |
| .trim() | 105 | 32 | 2 | 4 | 0 | 1 | 29 |
| .strip*() | 8 | 6 | 3 | 0 | 0 | 2 | 13 |
| .isBlank() | 167 | 140 | 21 | 8 | 1 | 1 | 19 |
| var declarations | 37 | 19 | 2 | 0 | 0 | 0 | 124 |
| explicitly typed local decl. (approx.) | 4629 | 1723 | 332 | 60 | 7 | 30 | 21233 |
| .get(0) | 0 | 5 | 4 | 0 | 0 | 0 | 521 |
| getFirst/getLast/removeFirst/... | 34 | 6 | 0 | 0 | 0 | 0 | 231 |
| .iterator().next() | 5 | 0 | 0 | 0 | 0 | 0 | 50 |
| Stream.toList() | 260 | 44 | 4 | 1 | 0 | 1 | 173 |
| Collectors.toList() | 2 | 0 | 0 | 0 | 0 | 0 | 0 |
| Collectors.toSet() | 21 | 4 | 0 | 0 | 0 | 0 | 23 |
| List.of( | 204 | 207 | 23 | 2 | 0 | 6 | 4445 |
| Arrays.asList( | 1 | 2 | 2 | 0 | 0 | 0 | 6 |
| Collections.empty*( | 20 | 26 | 0 | 0 | 0 | 0 | 432 |
| Collections.unmodifiable*( (views) | 17 | 4 | 0 | 0 | 0 | 0 | 1 |
| Optional isPresent() then get() | 11 | 0 | 2 | 0 | 0 | 0 | 0 |
| Optional.orElseThrow() | 14 | 0 | 0 | 0 | 0 | 0 | 312 |
| Optional parameter | 4 | 0 | 0 | 0 | 0 | 0 | 0 |
| mapMulti | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| flatMap(... Stream.of/empty ...) | 1 | 0 | 0 | 0 | 0 | 0 | 1 |
| Math.clamp | 1 | 0 | 1 | 0 | 0 | 0 | 0 |
| Math.max(Math.min(..)) clamp candidates | 10 | 3 | 1 | 1 | 0 | 0 | 0 |
| HexFormat | 4 | 2 | 3 | 0 | 0 | 2 | 5 |
| Integer/Long.toHexString | 1 | 5 | 1 | 0 | 0 | 0 | 0 |
| new ThreadLocal / withInitial | 1 | 4 | 0 | 0 | 0 | 0 | 1 |
| synchronized block | 2 | 2 | 0 | 0 | 0 | 0 | 1 |
| synchronized method | 1 | 0 | 1 | 0 | 0 | 0 | 2 |
| Thread.sleep | 2 | 0 | 0 | 1 | 0 | 0 | 26 |
| Executors.*( | 2 | 2 | 0 | 0 | 0 | 0 | 17 |
| serialVersionUID | 1 | 1 | 5 | 0 | 0 | 0 | 2 |
| @Serial | 0 | 0 | 4 | 0 | 0 | 0 | 0 |
| ReflectionTestUtils.setField | 0 | 0 | 0 | 0 | 0 | 0 | 36 |
| hand-written equals(Object) | 4 | 1 | 0 | 0 | 0 | 0 | 0 |
| Javadoc blocks /** */ | 8187 | 2769 | 757 | 102 | 13 | 46 | 5080 |
| Markdown doc lines /// | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| JetBrains-annotated files | 594 | 248 | 59 | 15 | 2 | 5 | 74 |
| sealed type declarations | 4 | 0 | 0 | 0 | 0 | 0 | 0 |
| explicit super(args) as first ctor stmt | 27 | 4 | 9 | 2 | 0 | 0 | 9 |

Further counts (all source sets unless stated): `Set.of` 1,445; `Map.of/ofEntries/entry` 666;
`List/Set/Map.copyOf` 98; `Collections.singleton*` 50 (tests); `EnumSet.*` 51; `Optional.orElse(null)`
148; `orElseThrow(supplier)` 91; `.ifPresentOrElse` 0; `Optional.or` 2; `Objects.requireNonNullElse` 1;
Optional-returning method that returns `null`: 0; `yield` 22 (main); `case null` 0; `when` guards 0;
record patterns 0; `Collectors.teeing` 0; `.parallelStream()`/`.parallel()` 7 (frontend tests);
`@Async` 7 and `@Scheduled` 16 (backend main); `ReentrantLock` 3; `volatile` 14; prose `//` and
`/* */` comments in Java: 0 (lexer; cross-checked with `grep -E '^\s*//'` → 0 lines); license
headers 3,295/3,295. Type kinds in backend main: 634 classes, 87 enums, 190 interfaces, 663 records.
Lombok on classes (backend main entities): `@Getter` 113, `@Setter` 113, `@NoArgsConstructor` 114,
`@AllArgsConstructor` 88, `@ToString` 86, `@Builder` 42; `@Data` on non-entity classes: backend 3,
frontend 18 forms + 1 row + 1 properties, ingest 1 properties; Lombok `@Value`: 0; `@Builder` on
records: 16 (`60-modern-java-lombok-out.txt`).

Top files per idiom (`main`): unused catch parameters — `KeycloakService.java` 13,
`JobOrderWriteController.java` 6, `ExchangeIdempotencyFilter.java` 5; empty catches —
`MissionPageController.java` 6 (`catch (Exception ignored) {}` ×6), `ProfileController.java` 3,
`BackendServiceException.java` 3; multi-line concatenations — `MissionController.java` 18,
`OperationController.java` 11, `DataExportSections.java` 8; `@Query` concatenations —
`MissionRepository.java` 9; `isPresent`+`get` — `P4kImportService.java` 5; `var` —
`MissionController.java` 25; type patterns — `LiveSyncWebSocketHandler.java` 10,
`KeycloakService.java` 8.

### B. Switch inventory (`main`, 81 switches)

| Kind | Expression, no default | Expression, default | Statement, default |
|---|---|---|---|
| Enum (56; 55 typed by `$SwitchMap$`, 1 on `this` inside `OperationStatus`) | 35 | 7 (4 × library `HttpStatus`; 3 partial) | 14 (9 full coverage = dead default; 5 partial) |
| String (16, 13 in frontend — request parameters such as fragment names; `default` required for untrusted input) | 0 | 11 | 5 |
| Pattern (2: `SseSendFailureCause.java:49` with `_`; `ExchangeShipWriteService.java:438` over sealed `Planned`) | 1 | 1 | 0 |
| Constant, non-enum (4: String constants `BLUEPRINTS`/`STOCK` in `ExchangeMassChangeService.java:224/279/316`; `ExchangeShipChangeSet.LINK/UPSERT` at `ExchangeShipWriteService.java:218`) | 0 | 4 | 0 |
| int (3, frontend) | 0 | 2 | 1 |

Enum types switched on in `main` (bytecode): `BankAccountType` 6, `OrgUnitKind` 6,
`BankAccountViewGranteeKind` 5, `HttpStatus` 4, `InventoryAllocationDimension` 3,
`BereichLeadershipRole` 3, `ExchangeResource` 3, `AuditDomain` 2, `BankBookingRequestType` 2,
`BankTransactionType` 2, `BlueprintImportStatus` 2, `SelectorKind` 2, `MembershipRole` 2,
`ExchangeResolveResponse.Status` 2, and 1 each of `ApprovalStatus`, `LiveSyncRelayService.Outcome`,
`BulkRebookMode`, `LiveSyncAuthorization`, `OrgChartScope`, `OrgChartPositionType`,
`BankRequestApprover`, `PersonalInventoryLocationType`, `OrgRelativeRole`,
`MembershipDeltaRequest…Action`, `ExchangeCatalogKind`. Bytecode also shows 36 compiler-inserted
`new MatchException` (exhaustive switches without `default`) and 2 `SwitchBootstraps.typeSwitch`
call sites. Every bytecode event matched a source switch; 15 matched one line above the `switch`
keyword (assignment on the previous line). Security-relevant exhaustive switches already in place:
`LiveSyncSubscriptionAuthorizer.java:90`, `OrgRoleManagementSecurityService.java:167`,
`OrgUnitBankAccessService.java:1047…1481` (9), `RecipientResolutionService.java:73`,
`AuditService.java:188`, `AuditReportService.java:176`.

### C. Tool probes (`60-modern-java-probes-out.txt`, sources under `60-modern-java-probe/src/`)

| Probe | javac 25 `--release 25` | javac 25 `--release 21` | Checkstyle 14.3.0 (`google_checks.xml`) | gjf 1.36.1 |
|---|---|---|---|---|
| a — `_` in lambda, for-each, catch, try resource, record pattern | compiles | "unnamed variables are not supported in -source 21" | only `EmptyCatchBlock` on the empty `catch (… _) {}` (a:40) | unchanged |
| b — enum statement w/o default; `case null` statement w/o default; expression w/o default | compiles | compiles | `MissingSwitchDefault` only on the plain statement (b:26) | unchanged |
| b2 — same, one constant missing | plain statement compiles (b2:26); `case null` statement (b2:41) and expression (b2:56): "does not cover all possible input values" | same | — | — |
| c — `///` Markdown doc on public class/method | compiles | compiles | `MissingJavadocType`, `MissingJavadocMethod` | unchanged |
| d — statements before `super(…)` | compiles | "flexible constructors is not supported in -source 21" | clean | unchanged |
| e1 `Gatherers` / e3 `java.lang.classfile` / e7 `KDF` | compiles | cannot find symbol / package does not exist | — | — |
| e2 `ScopedValue` / e4 `Arena` | compiles | "is a preview API and is disabled by default" | — | — |
| e5 — Java 21 features (sequenced, clamp, `StringBuilder.repeat`, record patterns, guards) | compiles | compiles | clean | unchanged |
| e6 — `import module java.base;` | compiles | "module imports are not supported in -source 21" | `AvoidModuleImport` | unchanged |
| f — sealed interface permitting a class in another package | "class Shape in unnamed module cannot extend a sealed class in a different package" | same | — | — |
| g — control: empty `catch (… ignored) {}` | compiles | compiles | clean | unchanged |

(`PackageDeclaration` findings in the probe run come from the probe's directory layout and are
ignored.) Further probes: `javadoc` (JDK 25) renders `///` Markdown with and without `--release 21`;
`60-modern-java-sealedload.py` — a subclass compiled against an unsealed base fails to load against
the sealed base with `IncompatibleClassChangeError: Failed listed permitted subclass check`;
`PinningProbe.java` — `elapsedMs=205/206` with `jdk.virtualThreadScheduler.parallelism=1`,
`maxPoolSize=1` on Zulu 25+36.

### D. `toString` exposure inventory (`main`, `60-modern-java-sensitive-out.txt`)

| Kind | Credential-named, printed | Credential-named, redacted | Personal-named, printed |
|---|---|---|---|
| Records | 1 real (`Brokered`) + 6 false positives (`refillTokens`, `ipRefillTokens`) | 5 (`DiscordSpiPrecheckProperties`, `KeycloakSyncProperties`, backend `MonitoringScrapeProperties`, ingest `ServiceAccountProperties`, `CachedToken`) + `MemberLookup` (body) | 112 (DTOs, events, projections: handles, usernames, display names, e-mail) |
| Lombok `@Data`/`@ToString` classes | 2 (frontend and ingest `MonitoringScrapeProperties`) | — | 21 (entities incl. `User`: username, displayName, email, discordUserId printed; `rsiHandle` excluded) |

### E. Re-evaluation of earlier audit items and repository statements

| Item | Then | Now (evidence) | Verdict |
|---|---|---|---|
| Sept. BE-MOD-06 "small idiom remnants" (`get(0)`, `matchesProfiles`, `causeTag` switch) | backend | backend `main`: 0 `get(0)`; `causeTag` became the `_` pattern switch (`SseSendFailureCause.java:49`, #2011); frontend/ingest still 9 `get(0)` and 3 profile-array checks | done for backend; extend (JAVA-15) |
| Sept. BE-SIMP-10 "~970 inline FQNs (backend 551, frontend 420)" | both | backend `main` 19 left; frontend `main` 482 | frontend half open (JAVA-15) |
| Sept. IMG-MOD-11 AOT cache | — | done (ADR-0209, vault audit plan row) | out of scope here |
| July 2026 modularity audit: `MissionPeerRedactor` keeps explicit canonical construction | — | still `new MissionDto(…)` (`MissionPeerRedactor.java:72`, `:163`) | keep; records make it compiler-checked (JAVA-11) |
| ADR-0192: Lombok maximised, JetBrains nullness where provable | 2026-09-21 | Lombok `@Value` 0; non-entity `@Data` only on the request/form/properties classes listed above; JetBrains imports in 923 of 2,044 `main` files (ADR said 722 of 1,763); the logger/accessor claims were not re-checked here | consistent |
| ADR-0223 statements | 2026-09-27 | enforcement, `super(args)` count, ThreadLocal inventory drifted | amend (JAVA-08, JAVA-09) |
| `backend/CLAUDE.md` JEP 500 conversion (31 sites) | — | 36 remaining `setField` calls, all non-final | holds (JAVA-16) |
| Vault `10 Systems/Ingest.md:713-714` "configuration properties are records" | 2026-09-22 | `MonitoringScrapeProperties` is `@Data` | correct the note (JAVA-01) |
| Vault `40 Decisions/Decisions.md:618` (ADR-0223 section) "Neither rule is gated in CI" | 2026-09-27 | `AvoidModuleImport` gates `main` since `49a3dcd4e` | correct the note (JAVA-09) |
| Vault `40 Decisions/Decisions.md` ADR-0223 table: frontend `ThreadLocal`s only; "34 explicit `super(args)`" | 2026-09-27 | backend `ChangeSource.ON_BEHALF` added the same afternoon; 42 `super(args)` | correct with the ADR (JAVA-08, JAVA-09) |

### F. Commands and scripts (all in the scratchpad, prefix `60-modern-java-`)

- `60-modern-java-lexer.py` — lexer (blanks comments and literal contents, keeps offsets),
  bracket matching, modifier/annotation walker, file iteration over 6 modules × 3 source sets.
- `python 60-modern-java-bytecode.py` → `60-modern-java-bytecode.json`: `javap -c -p -l` over
  `*/build/classes/java/main` (classes pre-filtered by the constant-pool strings `$SwitchMap$`,
  `SwitchBootstraps`, `java/lang/MatchException`; 72 classes), mapping switch instructions to source
  lines via `LineNumberTable`.
- `python 60-modern-java-scan.py` → `60-modern-java-data.json`, `60-modern-java-scan-out.txt`
  (all idiom counts, examples, switches joined with bytecode, records, carriers, sensitive
  components, sealed candidates, catches, chains).
- `python 60-modern-java-switches.py` → `60-modern-java-switches-out.txt`;
  `python 60-modern-java-details.py <enumcov|sealed|records|carriers|sensitive|lambda|catch|jep513|conc|reflect|misc|text>`;
  `python 60-modern-java-tables.py` (Appendix A).
- `python 60-modern-java-catch.py` (what unused-catch bodies do), `60-modern-java-reflect.py`
  (JEP 500 targets), `60-modern-java-lombok.py` → `60-modern-java-lombok-out.txt`,
  `60-modern-java-authz.py` (enum comparison styles; its `name_eq` column is a regex artefact and
  was not used), `60-modern-java-fqn.py` (inline FQNs).
- `python 60-modern-java-probes.py` → `60-modern-java-probes-out.txt`: javac 25 (Zulu 25+36) with
  `--release 25/21 -parameters -Xlint:unchecked -Xlint:deprecation`; Checkstyle 14.3.0 CLI
  (`com.puppycrawl.tools.checkstyle.Main -c config/checkstyle/google_checks.xml`, classpath from the
  Gradle cache); google-java-format 1.36.1 CLI with `commonmark` 0.28.0; `python
  60-modern-java-sealedload.py`; `java -Djdk.virtualThreadScheduler.parallelism=1
  -Djdk.virtualThreadScheduler.maxPoolSize=1 60-modern-java-probe/PinningProbe.java`; `javadoc -d …
  src/c/MarkdownDoc.java` (with and without `--release 21`).
- Library evidence: `javap -v` on `spring-core-7.0.9.jar` (`org.springframework.lang.Nullable`
  `Deprecated since 7.0`; `org.springframework.util.package-info` `@NullMarked`);
  `spring-core-7.0.8.pom` (`org.jspecify:jspecify:1.0.0`, compile);
  `hibernate-core-7.4.5.Final-sources.jar!/org/hibernate/annotations/ConcreteProxy.java`;
  `google-java-format-1.36.1.pom` (commonmark); `checkstyle-14.3.0.pom`.
- Git: `git log -S "AvoidModuleImport" -- config/checkstyle/google_checks.xml` → `49a3dcd4e`;
  `git log -S "ThreadLocal<String> ON_BEHALF"` → `71a5ae641`; `git log -S "case IOException _"` →
  `a38695416` (squashed as `fc85cfb7f`, #2011); `git log -S "sealed class AppException"` →
  `8bd9c63bb` (#933).
- Grep: `@ConfigurationProperties` record/class split per module; `threads:` in
  `application*.yml`; `LAZY` before `private OrgUnit`; `Hibernate.unproxy`; `getActiveProfiles()`;
  `grep -ro -E "OrgUnitKind\.[A-Z_]+\s*[!=]=|[!=]=\s*OrgUnitKind\.[A-Z_]+" backend/src/main` → 43
  matches (42 lines, 19 files).

**Limitations.** The scanner is lexical, not a compiler: statement/expression classification uses the
preceding token (cross-checked for all enum switches against bytecode); "unused" ignores shadowing;
the explicit-local-declaration count is an approximation used only for the `var` ratio; `Collectors`
mutation analysis was not needed (2 sites). Test classes were not used for bytecode (compiled
2026-09-27, stale).
