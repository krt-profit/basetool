# 82-prev-july-other — Earlier audits re-evaluated for a domain-modular target

Scope: Part 1 is the 2026-07-11 whole-app modularity and maintainability audit (PR #1256 and its
follow-ups #1257–#1262, issues #1250–#1255). Part 2 covers the earlier focused audits: security
(#150, #393, #783, #1672, #1724), performance (#155–#160, #175), caching (#1002), concurrency
(#1109), permission gates (#548), post-cutover ops (#1984), documentation (#1981), design (#174,
#177), and the removed `ANALYSIS.md` / `PROJECT_REVIEW.md`. Each item is re-evaluated against
the current rules: domain separation first, ADR-0223, ADR-0214, no weakening of security.

Machine-readable companion: `82-prev-july-other.json` (110 items, fields `source, id, title, kind,
status, evidence, verdict, reasoning, domain_effect, security_note`).

All paths are relative to the worktree `$REPO`
at `95e945326`. `BE` = `backend/src/main/java/de/greluc/krt/profit/basetool/backend`,
`FE` = `frontend/src/main/java/de/greluc/krt/profit/basetool/frontend`,
`AT` = `backend/src/test/java/de/greluc/krt/profit/basetool/backend/ArchitectureTest.java`.

---

## 1. Summary — the ten conclusions that matter

1. **The July audit report itself was never committed.** Its finding list is rebuilt here from the
   28 commits of PR #1256, issues #1250–#1255, PRs #1257–#1262 and the vault. Commit messages cite
   items QW1–QW4, #10, #14 (Thema 7), #15 and #16 (Thema 11). Anything the report listed under
   other numbers is UNKNOWN: no issue, commit, vault note or session transcript names it (two
   transcript searches, appendix A.4).
2. **Every July finding shipped and is still in the code**, with three gaps. All nine Thema-7
   splits exist (`82-prev-july-other-locate.py classes`). Two follow-ups never happened:
   `CachedCatalogListLoader` has one consumer (`FE/controller/HangarPageController.java:88`) and
   `HangarPageModelLoader` does not exist. The `GlobalExceptionHandler` split is still open: the
   class has 1005 lines and 20 handlers.
3. **July's diagnosis — that the debt is size and duplication inside sound layers — is superseded
   by the domain-separation goal.**
   - Main-source files over 600 lines went from 49 to 53 while main files grew from 1477 to 2044
     (`82-prev-july-other-size-at.py` at `f5703ab1` and at HEAD).
   - Cross-domain coupling is concentrated in a few beans: `OwnerScopeService` is used by 34
     classes, `AuthHelperService` by 46, and `AuditService.record` is called from 57 files.
   - The `support` leaf holds 63 classes, and 43 of them carry a domain name.
4. **14 of the 15 July rejections still hold under a domain-modular target. The exception is
   `BackendApiClient`.**
   - July's stated reason was that splitting the class would fragment "the one Resilience4j
     pass". But that pass lives in the filter chain of the `webClient` bean (ADR-0032, dated
     before July; `FE/config/WebClientConfig.java:311-345,483-485`).
   - Eleven controllers already inject that bean directly.
   - So per-domain facades keep the one pass, and the rejection should be revisited
     (REJECTION-REVISIT).
5. **The `…WithinTransaction` / `MANDATORY` rejection holds, but 21 method-level `MANDATORY` hops
   cross the proposed domain boundaries.**
   - They are orgunit→inventory, orgunit→orgchart (9 hops), inventory/joborder/identity→
     materialexchange, exchange/joborder→inventory, and identity→bank-audit.
   - Separately, `AuditService.record` is called from every domain (57 files).
   - These hops are the first in-transaction module APIs. They must not become after-commit
     events (`82-prev-july-other-mandatory.py`).
6. **The largest risk to earlier security fixes is the guard layer.**
   - Of 43 backend ArchUnit tests, 25 would pass silently after a package-by-domain move, and 6
     more partly.
   - Among them are the `permitAll` allow-list (`AT:345`, filter at `:348`), the read/write
     endpoint gates (`:389`, `:737`), the audit-write rule (`:442`) and bank org-unit blindness
     (`:1794`).
   - The cause is ArchUnit's default: `failOnEmptyShould` is TRUE (read with `javap` from
     archunit-1.5.1), but it only catches an empty *selection*. It does not catch a
     fully-qualified target name that no longer exists.
   - Re-keying these guards is a blocking prerequisite for any move (PRV-01).
7. **The caching audit's L4 invariant is now carried by nothing.**
   - The invariant: cached catalogue mutators are safe only because they self-invoke their own
     `@Cacheable` getter.
   - The audit fixed it with a code comment. ADR-0214 removed all comments, and there is no test
     and no spec text.
   - A query/command split of catalogue services would break it (`BE/service/CityService.java:69,84`).
8. **Still open from the security audits:**
   - the 100000 page-size ceiling (`BE/web/PaginationUtil.java:45`, #783);
   - the unescaped `LIKE` search behind `BlueprintProductService.searchProducts`
     (`BlueprintRepository.java:125-126`, #1672);
   - no per-subject budget for plain reads (accepted risk; `SubjectRateLimitingFilter.java:57-63`);
   - REQ-SEC-031's no-store rule is a hand-kept list of 14 path families
     (`BE/filter/NoStoreApiScopes.java:42-55`), which leaves `/api/v1/admin`, `/exchange`,
     `/orders` and others unclassified;
   - Keycloak hardening steps 2, 11 and 12.
9. **Most other deferred items are resolved or moot:**
   - Swagger UI is not shipped, and the GitHub Actions are SHA-pinned (109 SHA-pinned `uses:`,
     0 tag-pinned).
   - Audience enforcement is fail-closed at startup (`JwtAudienceStartupCheck`, `1df02fa31`).
   - Member-tier redaction is done, and V103 dropped the V91 index.
   - The Redis persistence switch-off was reversed, the restore-drill window is 8 days, and the
     confidential OAuth2 client is live.
10. **Several documents and one relay drift from the code:**
    - arc42 §4.1 (`docs/arc42/04-solution-strategy.md:9`) and the vault (`10 Systems/Frontend.md:67`)
      say "exactly one class" talks to the backend.
    - The vault's `Bank.md:639,681` line counts (921 and ~2030) do not match 794 and 1753 lines.
    - The `LiveSyncPresenceService` Javadoc (`:54-56`) points at the wrong JS file.
    - `ParallelPageLoader` does not relay the user locale (`FE/service/ParallelPageLoader.java:68-73`
      against `ReactorContextPropagationConfig.java:106-119`). This is the same class of gap as
      #1130; its impact is UNKNOWN.

---

## 2. Sources and method

- **GitHub (GET only):** PR #1256 with body, 28 commits, review comments and reviews; issues
  #1250–#1255 with timelines; PRs #1257–#1262. For Part 2: PRs #150, #393, #783, #1672, #1724,
  #155–#160, #175, #548, #1984, #1981, #174, #177 and #103; issues #1002, #1109 and #1110–#1158
  (states); #1247 (state). Searches over PRs and issues created 2026-07-08…2026-07-20 for "audit",
  "modularity", "Thema", "maintainability", "review", "#14" and "refactor". The only audit
  artefacts found are #1250–#1262.
- **Removed documents:** `git show 552ae0e19e^:PROJECT_REVIEW.md` and `…:ANALYSIS.md`. #103 was
  squash-merged as `552ae0e19e`, and the branch commit `f63dc32a17` is not in the repository.
  Copies are in the scratchpad as `82-prev-july-other-PROJECT_REVIEW.md` and `-ANALYSIS.md`.
- **Vault (read-only):** `10 Systems/Backend.md` §"Load-bearing constructs that must not be
  simplified" (`:297-329`), `30 Roles and Permissions/Security.md:266-289`, `20 Domains/Bank.md:633-690`,
  `10 Systems/Frontend.md`, `Ingest.md`, `Monitoring.md`, `Host Provisioning.md`,
  `80 Plans/Improvement Audit 2026-09.md` (for cross-reference only).
- **Code:** greps and the helper scripts listed in the appendix; `jdeps-*.txt` from the coordinator.
- **Verdicts:**
  - CONFIRMED: still valid, keep or finish.
  - ADJUSTED: valid, but location or shape must change for the domain target.
  - SUPERSEDED-BY-MODULARISATION: the domain refactor replaces it.
  - DROPPED: done or moot.
  - REPRIORITISED: priority changes.
  - REJECTION-HOLDS / REJECTION-REVISIT: for rejected proposals.
  - UNKNOWN: used where the evidence is not in reach, always with the step that would settle it.

---

## 3. Part 1 — the 2026-07-11 modularity audit

### 3.1 What it was

"A whole-app modularity audit ran on 2026-07-11 over roughly 1430 Java files (23-agent workflow,
shipped as PR #1256)" (vault `Backend.md:299-300`). PR #1256 (merged 2026-07-11 15:51 UTC,
159 files) carried the actionable findings as 26 behaviour-preserving commits plus a main-merge and
a code-quality fix. The remaining six Thema-7 splits became issues #1250–#1255 and were merged the
same afternoon as PRs #1257–#1262. At the audit's base commit `f5703ab1` there were 1477 main Java
files, of which 49 exceeded 600 physical lines. The vault reports "roughly 30 classes over 600 LOC",
evidently a different LOC metric. The report never entered the repository or the vault.

### 3.2 Findings and their status today

| ID | Finding (July numbering) | Delivered | Status today (evidence) | Verdict |
| --- | --- | --- | --- | --- |
| J-DIAG | Architecture sound; debt = size + duplication inside correct layers | — | Size did not shrink relative to growth (49/1477 → 53/2044 files > 600 lines). Cross-domain fan-in is concentrated: `OwnerScopeService` in=34, `AuthHelperService` in=46 (jdeps) | SUPERSEDED-BY-MODULARISATION |
| J-QW1 | `PageResponse.of` at list endpoints | #1256 `ed65cfec` | One `new PageResponse<` left, in the factory (`BE/model/dto/PageResponse.java:49`); `.of` used 78× in 46 files | CONFIRMED |
| J-QW2 | AJAX error relay on `BackendErrorResponses` | #1256 `288e50f1` | Extended by FE-SIMP-01: `relay` imported in 28 files; 125 bespoke `catch (BackendServiceException` remain in 40 controllers | CONFIRMED |
| J-QW3 | Leaf dedups (`InventoryAuditLabels`, `OrgUnitLabels`, `UexValues`, `QuantityTypeRounding`, `MapPayloadValues`, alias guards, `@EvictAllMaterialCaches`) | #1256 | All used (e.g. `UexValues.` 149×; `UexValues.java:84,98` keep both flag semantics). They live in `support`, which has 63 classes, 43 domain-named | ADJUSTED (re-home per domain / kernel) |
| J-QW4 | `ObservationPrivacyFilter` 3-module parity guard | #1256 `1e4b3720` | Three 106-line copies + `ObservationPrivacyFilterMirrorParityTest`. ADR-0205's `logging-support` is logback-only and holds no beans, so it cannot take the filter without an amendment | CONFIRMED |
| J-S10 | `ProblemResponseFactory` (#10) | #1256 `d4c3860e` | `BE/support/ProblemResponseFactory.java:40,60,86`; 9 users | CONFIRMED |
| J-S10b | Split `GlobalExceptionHandler` per exception family (prerequisite laid) | not done | 1005 lines, 20 `@ExceptionHandler` | REPRIORITISED: by family yes, **never per domain** |
| J-RELAY | `BackendErrorResponses.relay` (adopted only in `JobOrderWriteController`) | #1256 `210b6569` | Adopted widely later (#2010/#2014) | DROPPED (done) |
| J-REDACT | `MissionGuestRedactor` extraction, explicit reconstruction kept | #1256 `ee34c23c` | Now `MissionPeerRedactor` (`BE/support/MissionPeerRedactor.java:56-213`); rule `AT:1129-1162` | ADJUSTED (move into mission module) |
| J-ROLES | `Roles.HAS_ROLE_*` constants | #1256 `425c08e7` | `BE/support/Roles.java:83-110`; 126 uses in 51 files; 12 compound splices by design | CONFIRMED |
| J-S15 | Four view assemblers (#15) | 3 of 4 | `BankDashboardViewAssembler`, `MissionDetailModelBuilder`, `SecurityHeaders` present; `HangarPageModelLoader` never built | ADJUSTED |
| J-S16 | Import-engine split (#16/Thema 11) | #1256 | `HangarImportService` 851→208 lines; `BlueprintFuzzyMatcher.topMatches` (`:97`) is used by the refinery import (`RefineryImportService.java:109`) | ADJUSTED (rename/relocate the generic matcher) |
| J-SCWIKI | `ScWikiOrphanSweep` gate | #1256 `8a64acb3` | `ScWikiOrphanSweep.java:58` | CONFIRMED |
| J-CACHEDCAT | `CachedCatalogListLoader` + "mechanical follow-up" adoptions | Hangar only | 1 consumer; 28 `getCached` sites in 10 controllers | REPRIORITISED (fold into PRV-07) |
| J-T7-1 | `OrgUnitMembershipQueryService` | #1256 | 463 lines; used by 12 classes across 7 domains | CONFIRMED (seed of the orgunit read API) |
| J-T7-2 | `JobOrderQueryService` | #1256 | 367 lines; whitelisted `AT:989` | CONFIRMED |
| J-T7-3 | `OrgChartReadService` | #1256 | 333 lines; `OrgChartService` keeps 9 `MANDATORY` mirror hooks (`:272-437`) called by orgunit | CONFIRMED |
| J-T7-4 | Split `SquadronContextAdvice` into three advices | #1257 | Complemented by `LayoutContextLoader` (FE-PERF-01, #2020) | ADJUSTED |
| J-T7-5 | `OperationPayoutService` + `OperationPayoutCalculator` | #1259 | Now org-unit aware (`:103`, `:245` `canSeeOperationLedger`, ADR-0150) but **not** in the scoped-service whitelist (`AT:980-992`) | ADJUSTED (guard it) |
| J-T7-6 | `UserDeletionService` / `UserRegistrationService` / `UserReconciliationService` | #1261 | 369/432/573 lines; `UserDeletionService` depends on 28 classes across all domains | CONFIRMED |
| J-T7-7 | `BankPostingWriter` + `BankBookingGuards` | #1260 | `BankPostingWriter.java:57` `MANDATORY`; `BankLedgerService` 794 lines | CONFIRMED |
| J-T7-8 | `OrgUnitBankResponsibilityService` (OwnerScope-free) | #1262 | 256 lines, no `OwnerScopeService` | CONFIRMED |
| J-T7-9 | `MaterialExchangeBoardService` | #1258 | `detailDto` `:305`, used by write responses `MaterialExchangeService.java:173,233,278,328` | CONFIRMED |

The PR #1256 review contains only two `github-code-quality` notes about unread locals in
`JobOrderQueryService`, fixed in `df7b7d33`. Neither PR #1256 nor #1250–#1262 has issue comments
that add findings.

### 3.3 The rejected proposals, re-evaluated

The vault records eight constructs as "verified as load-bearing … each was proposed for
simplification and rejected" (`Backend.md:305-329`). The commits add seven narrower rejections.

| ID | Rejected proposal | Evidence today | Does the reason hold under a domain-modular target? | Verdict |
| --- | --- | --- | --- | --- |
| J-R01 | One aggregate lock instead of Mission per-section counters | `BE/model/Mission.java:64` `@DynamicUpdate`, 36× `@OptimisticLock(excluded = true)`; `MissionRepository.java:240-324`; `BE/support/MissionSectionVersions.java:49,166,176` | Yes. All use sites are mission-internal (`MissionService`, `MissionParticipantService`, `MissionTimelineService`). Move `MissionSectionVersions` from `support` into the mission module | REJECTION-HOLDS |
| J-R02 | Simplify `…WithinTransaction` / `MANDATORY` hops | 31 `MANDATORY` methods in 15 classes; 21 cross-domain hops (§3.4) | Yes. Atomicity is the point. A design that honours it *and* fixes dependency direction: synchronous in-transaction events or published in-transaction ports (PRV-03) | REJECTION-HOLDS |
| J-R03 | Remove the find-or-create `REQUIRES_NEW` self-proxy retry | 17 `REQUIRES_NEW` methods, 9 self-`ObjectProvider`s, all intra-class (e.g. `OperationPayoutService.java:112,302`, `MaterialClaimService.java:96,102,262`) | Yes. Never crosses a module. A class split must retype the provider, as #1259 did | REJECTION-HOLDS |
| J-R04 | Simplify bulk-update-after-loop / lock-before-sum | `JobOrderHandoverService.java:154,252,264`; `JobOrderRepository.java:271` used at `MaterialClaimService.java:280` | Yes. Note that the joborder handover bulk-deletes inventory allocation rows directly, a boundary to formalise | REJECTION-HOLDS |
| J-R05 | Split `BackendApiClient` ("fragments the one Resilience4j pass") | See the list after this table | **No, as stated.** The reason holds for per-domain WebClient beans or per-domain resilience instances. It does not hold for per-domain facades or `@HttpExchange` proxies built on the one `webClient` bean (PRV-07) | **REJECTION-REVISIT** |
| J-R06 | Generic CRUD or sync base template | No base service exists (only 4 MapStruct abstract mappers, `AbstractEntity`, `OrgUnit`); `auditService.record` 205× in 57 files, `bankAuditService.record` 51× in 14 | Yes. A template would couple every module and hide the audit call. Modules enable the better guard: an audit-completeness rule per audited package (PRV-11) | REJECTION-HOLDS |
| J-R07 | Wither DTOs in peer redaction | `MissionPeerRedactor.java:72,163,194` explicit constructors; `MissionDto` has no `@With`/`@Builder` | Yes. Add a reflective completeness test for nested records (REQ-SEC-040 gap, PRV-10) | REJECTION-HOLDS |
| J-R08 | Refactor the ADR-0020 bank seam at will | `OrgUnitBankAccessService` 1753 lines, 56 distinct dependencies, in=3; `AT:406,1794,1853`; ADR-0020 `:23-33` | Yes. Modules *strengthen* it: "no class in the bank package except the seam depends on the orgunit scope API", written with class literals instead of the `Bank*` naming loophole | REJECTION-HOLDS |
| J-R09 | Coarser locks | Root `CLAUDE.md` rule; `BankAccountRepository.java:54,65` per-account locks | Yes | REJECTION-HOLDS |
| J-R10 | `@PreAuthorize` meta-annotations | Commit `425c08e7`; ArchUnit reads the direct value (`AT:355-360`, `:1090-1093`); 155 SpEL bean references (`@ownerScopeService` 68, `@missionSecurityService` 41, …) | Yes. But no test resolves those bean references; a rename during a module move breaks gates only at runtime (PRV-02) | REJECTION-HOLDS |
| J-R11 | Extract `OrgUnitBankSettingsAssembler` (#1262) | `OrgUnitBankAccessService.java:241-244` is scope-backed | Yes | REJECTION-HOLDS |
| J-R12 | Parity assertion for the other cross-module twins | `MonitoringScrapeProperties` 60/61/67, `NotificationStreamObservationPredicate` 62/59, `StringNormalization` 103/107 lines | Yes | REJECTION-HOLDS |
| J-R13 | Route the commodity/item SC-Wiki sweeps through `ScWikiOrphanSweep` | 3 users only | Yes | REJECTION-HOLDS |
| J-R14 | Collapse the two UEX flag semantics | `UexValues.java:84,98` | Yes | REJECTION-HOLDS |
| J-R15 | Move fee resolution into `OperationPayoutCalculator` | `OperationPayoutService.java:147,359` | Yes | REJECTION-HOLDS |

Evidence for J-R05:

- ADR-0032 is dated 2026-06-21, before July. It moved all resilience into
  `WebClientConfig#resilienceFilter` (`FE/config/WebClientConfig.java:311-345`, applied at
  `:483-485`).
- 83 classes depend on `BackendApiClient`, 78 of them controllers.
- 11 controllers inject the `webClient` bean directly (e.g. `BankReportProxyController.java:55`),
  and two more beans exist (`sseWebClient`, `liveSyncAuthWebClient`).
- No ArchUnit rule guards any of this; the frontend has 7 rules.

Two narrower July decisions also stand. The MaterialExchange shared redaction (the write service
injects the board service, #1258) holds: one redaction path. The one-way `…Query` splits
(write→read, no cycles) hold as well.

### 3.4 The 21 cross-domain `MANDATORY` hops (input for the module API)

Produced by `82-prev-july-other-mandatory.py`. The heuristic caller match was confirmed by reading
the call sites cited here. The domain mapping follows the briefing's preliminary taxonomy.

| Target method (`BE/service`) | Caller domain → target domain | Hops |
| --- | --- | --- |
| `InventoryCheckoutService.bookOutForClient` (`:154`) | exchange → inventory | 1 |
| `InventoryCheckoutService.mergeStockIfRequested` (`:668`) | exchange, joborder → inventory | 2 |
| `InventoryOrgUnitReconciler.onUserGainedFirstOrgUnit` / `onUserLostLastOrgUnit` (`:64,:85`) | orgunit → inventory | 2 |
| `MaterialExchangeOfferRatchet.lower` / `beforeDelete` (`:62,:94`) | inventory, joborder → materialexchange | 4 |
| `MaterialExchangeOfferRatchet.beforeWipe` (`:109`) | inventory → materialexchange | 1 |
| `MaterialExchangeOfferRatchet.beforeUserPurge` (`:124`) | identity → materialexchange | 1 |
| `OrgChartService.mirror*` (6 methods, `:272-391`) | orgunit (`OrgUnitMembershipService`) → orgchart | 6 |
| `OrgChartService.mirror*KommandoGroup` (3 methods, `:402-437`) | orgunit (`KommandoGroupService`) → orgchart | 3 |
| `BankAuditService.record` (`:86`) | identity (`HandleAnonymisationService`) → bank audit | 1 |
| **Kernel, not counted:** `AuditService.record` (`:80`) | every domain → audit (57 files) | — |

Consequences for the target design:

- The **upstream→downstream** hops are candidates for synchronous in-transaction domain events:
  orgunit→inventory, orgunit→orgchart, and inventory/joborder/identity→materialexchange. The
  upstream module publishes the event and the downstream module's listener runs in the same
  transaction, keeps the `…WithinTransaction` discipline (no own `save`/`flush` of shared
  entities), and is annotated `MANDATORY`.
- The codebase already uses `@TransactionalEventListener(AFTER_COMMIT)` for notifications and mail
  (`NotificationEventListener.java:57`, `PendingRegistrationMailEventListener.java:51`,
  `UserApprovalMailEventListener.java:54`). Those semantics are **wrong** for these hops: an
  after-commit listener cannot keep the offer ratchet, the chart mirror or the audit row atomic
  with the change that triggered them.
- `AuditService.record` stays a synchronous kernel call. REQ-AUDIT-001 needs the audit row in the
  business transaction.

---

## 4. Part 2 — the earlier focused audits

### 4.0 Cross-audit: guards keyed on package names and FQN strings (X-ARCH)

Many earlier fixes are enforced by `AT`. ArchUnit 1.5.1 fails a rule whose **selection** is empty
by default: `AllowEmptyShould$3` reads `archRule.failOnEmptyShould` with default `TRUE`, and there
is no `archunit.properties` in the repository. It does **not** fail a rule whose **target** is a
fully-qualified-name string that no longer exists. That rule simply passes. Two rules also set
`allowEmptyShould(true)` (`AT:718`, `:1693`). Classification of all 43 tests for a
package-by-domain move done step by step:

| Behaviour on a move | Count | Rules (line in `AT`) |
| --- | --- | --- |
| SAFE: key does not depend on the package | 3 | `toOneAssociationsAreDeclaredLazy` 284, `everyRestControllerShouldDeclareAtLeastOneAuthorisationAnnotation` 316, `bankClassesMustStaySeasonAndProfitIndependent` 1763 |
| LOUD: a move fails the build (empty selection, size assertion, FQN field-type mismatch) | 8 | `identityMustBeRead…` 234, `orgUnitBankSettingsMutations…` 406, `everyExchangeControllerMethod…` 475, **`peerReadableMissionEndpointsMustRedactPii` 1129** (size ≥ 10 at 1144), `missionWriteRequestDtos…` 1381, `missionParticipantsCollection…` 1479, `promotionTopicOwningSquadron…` 1500, **`orgUnitAwareBankSeamIsContainedToOneClass` 1853** |
| STRONGER: slices become domain slices and expose cross-domain cycles | 1 | `backendPackagesShouldBeFreeOfDependencyCycles` 603 |
| PARTIAL: part LOUD, part silent | 6 | `exchangeControllersCallExchangeServicesOnly` 506, `exchangeDtosStayInTheExchangeLayer` 525, `exchangeServicesNeverUseAdminGates…` 542, `staffelScopedServicesMustWireOwnerScopeOrAuthHelper` 978 (silent for split or renamed services), `missionServiceAddParticipantMustNotSaveMission` 1551, `bankLedgerRepositoriesMustStayInsertOnly` 1892 (call-target half) |
| SILENT: passes while checking less | 25 | Security-relevant: **`serviceLayer…SecurityContext` 202, `controllerLayer…SecurityContext` 219, `mapperLayer…SecurityContext` 253, `permitAllIsDeclaredOnlyOnTheFourPublicEndpoints` 345, `readEndpointsMust…` 389, `controllerLayerMustNotWriteAuditRowsDirectly` 442, `supportPackageMustStayADependencyLeaf` 577, `writeEndpointsMust…` 737, `staffelScopedWriteEndpointsMustGateOnOwnerScopeService` 1036, `responseOnlyDtosMustNot…` 1252, `bankClassesMustNotConsultOrgUnitScope` 1794, `cascadeServiceMustNotConsultTheSecurityContext` 1812, `delegatedRoleAuthoriserMustNotConsultOwnerScope` 1833.** Others: 268, 298, 427, 616, 631, 646, 661, 677, 692, 705, 1659, 1703 |

The `supportPackageMustStayADependencyLeaf` rule is silent for a special reason. It lists
*forbidden* target packages (`config, controller, event, filter, health, integration, interceptor,
mapper, service, task, web`). A new `..backend.mission..` package is not on the list, so `support`
could depend on it without a violation.

By contrast, the ingest rules (`ingest/src/test/.../ArchitectureTest.java:61-85`) and all seven
frontend rules are annotation-based, so a move does not affect them. So is `callsAuditRecord`,
which uses class literals (`AT:464`). The fix is PRV-01.

### 4.1 `ANALYSIS.md` (2026-05-11) and `PROJECT_REVIEW.md` (2026-05-12), removed by #103

Counts:

- **`ANALYSIS.md`:** 13 findings and 8 suggestions. All findings are resolved.
- **`PROJECT_REVIEW.md`:** 28 items. 23 are resolved and 5 are open, partial or unknown.

Verified resolutions:

- SecurityContextHolder rules: `AT:202,219,253`.
- No `RuntimeException` is thrown in backend main; one remains in the frontend,
  `WebClientConfig.java:307`, at start-up.
- The test profile runs on Testcontainers Postgres 18 with Flyway and `validate`
  (`backend/src/test/resources/application-test.yml:7,22-23`).
- OWASP gate: `failBuildOnCVSS = 7.0` (`build.gradle.kts:512`).
- CSP: no `script-src-attr 'unsafe-inline'` (`FE/config/SecurityHeaders.java:47-49`).
- Frontend: `anyRequest().authenticated()` (`FE/config/SecurityConfig.java:179-208`).
- The time limiter covers every verb (ADR-0032).
- Backend JaCoCo and its coverage gate: `backend/build.gradle.kts:6`; `build.gradle.kts:242,261`.
- Container memory limits in compose and Quadlet (`docker-compose.yml:48-49`;
  `quadlet/systemd/backend.container:33`).
- Runtime image `eclipse-temurin:25-jre-alpine`, digest-pinned (`docker/app/Dockerfile:48`).
- ArchUnit is in the version catalog, and there are no `_` wildcard versions.
- `InventoryItem` uses `@ToString.Exclude`.
- `KeycloakService` has a `BEARER_PREFIX` constant (`:72`).
- There are real concurrency tests: 80 uses of `CountDownLatch`/`ExecutorService` in 15 backend
  test files.
- The backend resolves problem details through `MessageSource` (`GlobalExceptionHandler.java:121,144`).
- `OpenApiGeneratorTest`, `TimezoneSerializationTest` and 10 promotion tests exist.
- `keycloak-theme` is documented in `README.md:310,383`.

| ID | Item | Status today | Verdict |
| --- | --- | --- | --- |
| A-5.1 | Error Prone / PMD beyond SpotBugs + Checkstyle | Not adopted (catalog: spotbugs 6.5.12, checkstyle 14.3.0 only). NullAway could enforce the JetBrains annotations nothing gates today; its compatibility with `org.jetbrains.annotations` is UNKNOWN (settle with the tool documentation) | REPRIORITISED (hand to the frameworks evaluation) |
| P-2.8 | Magic numbers | Constants exist, but `QUANTITY_EPSILON = 1e-4` is duplicated 4× (`InventoryCheckoutService.java:98`, `JobOrderHandoverService.java:68`, `JobOrderItemHandoverService.java:78`, `JobOrderItemProductionService.java:85`) | ADJUSTED (shared quantity kernel with `QuantityTypeRounding`) |
| P-2.9 | `Optional.get()` after `isPresent()` | Exact count UNKNOWN; proxy: 64 `.isPresent()` in 27 files | REPRIORITISED (modern-Java cleanup, low) |
| P-3.7 | `System.out` in tests | 58 matches in 15 test/e2e files, deliberate diagnostics | DROPPED |
| P-5.2 | Pre-commit hooks | Replaced by CI gates (26 workflows, `repo-lint.yml`) | DROPPED |

At risk from modularisation: fixes 1.1 and 1.4 are package-keyed ArchUnit rules (X-ARCH).

### 4.2 Security audit 2026-05-20 (PR #150): 43 findings, 38 fixed, 5 deferred

| ID | Deferred item | Status today | Verdict |
| --- | --- | --- | --- |
| S150-M11 (= L-9) | Swagger UI in prod | No UI dependency (`gradle/libs.versions.toml:75` `…webmvc-api`); `springdoc.api-docs.enabled: false` in prod (`application-prod.yml:36-38`); `/v3/api-docs` is ADMIN (`BE/config/SecurityConfig.java:366-367`) | DROPPED |
| S150-L4 | `ConstraintViolation` message echo | Still echoed (`GlobalExceptionHandler.java:497-505`). No bundle or code uses `${validatedValue}` | CONFIRMED as accepted risk, plus a cheap guard test (more modules means more message bundles) |
| S150-L6 | Actions major-version pinning | 109 SHA-pinned `uses:`, 0 tag-pinned | DROPPED |
| S150-L10 | Announcement CSRF-ignore consistency | Backend is stateless and exempts `/api/v1/**` (`SecurityConfig.java:108-111`); frontend CSRF is global (`FE/config/SecurityConfig.java:177`) | DROPPED |

At risk from modularisation:

- **C-1** peer redaction: `AT:1129` fails LOUD thanks to its size assertion.
- **C-3** response-DTO-as-request-body: `AT:1252` is SILENT; it is keyed on `..backend.controller..`
  and the FQN string `MissionDto` (`:131-132`).
- **C-4** request-record fields: `AT:1381` is LOUD.
- **H-6/H-7/H-8** (CSRF, WS origins, rate limit) are path- or config-based and unaffected.

### 4.3 Security audit 2026-06-03 (PR #393): 21 findings, 16 fixed, 3 verified no change, 2 by design

| ID | Item | Status today | Verdict |
| --- | --- | --- | --- |
| S393-L2L6 | L-2, L-6 "documented as by-design" | Content not in the PR, the CHANGELOG or the repo. The report "is kept local and intentionally not committed" | UNKNOWN; settle with the local `SECURITY_AUDIT_2026-06-03.md` |
| S393-M678 | Verified no change (M-6, M-7, M-8) | M-8 still true: pessimistic book-out locks (`InventoryItemRepository.java:851,959,971,1000,1126,1160`) | CONFIRMED |
| S393-L1 | Opt-in JWT `aud` validator | Now fail-closed at prod start-up (`BE/config/JwtAudienceStartupCheck.java`, test `:28-31`, commit `1df02fa31`) | DROPPED |
| S393-H1 (fixed, at risk) | Email is profile-only | `BE/mapper/UserMapper.java:83-90` ignores `email`, but `UserDto` still has the component, so a module-local projection could map it | ADJUSTED: split self vs peer DTO so the leak cannot compile (PRV-10) |

### 4.4 Security review 2026-06-21 (PR #783): 2 exploitable + ~10 hardening fixed, 2 deferred

| ID | Item | Status today | Verdict |
| --- | --- | --- | --- |
| S783-PAGECAP | Lower the 100000 page ceiling | Unchanged (`BE/web/PaginationUtil.java:45`). Module-local ceilings drift (`MaterialExchangeQueryParams.java:37` = 500, `SyncReportController.java:61` = 200) | REPRIORITISED: a shared page policy in the API kernel (PRV-08). The API is internet-reachable (ADR-0135) |
| S783-AUD | `aud` stays opt-in | Superseded by the start-up check | DROPPED |
| M1 guest token, ADR-0034 (fixed) | — | Removed by ADR-0159 (`GuestParticipantTokenService` gone; `docs/adr/0034-…md:3` superseded) | DROPPED |

H1 (`MissionSecurityService.canEditFinanceEntry`, `:144`) and REQ-SEC-020 (member evaluation)
live behind SpEL bean names. That is a runtime-only failure mode on rename (PRV-02).

### 4.5 API security audit 2026-08-25 (PR #1672): 5 fixed, 2 refuted, 1 consistency item left

| ID | Item | Status today | Verdict |
| --- | --- | --- | --- |
| S1672-AUDASSERT | Fail-closed audience start-up invariant | Implemented later (`JwtAudienceStartupCheck`, APPSEC-08) | DROPPED |
| S1672-BUDGET | Per-subject GET budget for aggregation reads | Partly: per-subject buckets for writes, SSE/live-sync connects and export segments (`BE/config/SubjectRateLimitingFilter.java:57-63`, REQ-SEC-033); plain reads stay per-IP | CONFIRMED as accepted risk; new expensive module reads must use an export path segment (module API rule) |
| S1672-LIKE | `searchProducts` through `LikePatterns.escape` | Not done (`BlueprintProductService.java:86`, query at `BlueprintRepository.java:125-126,219`) | CONFIRMED (low: a wildcard only broadens matching of the global catalogue) |
| S1672-031 (fixed, at risk) | REQ-SEC-031 no-store families | 14 path patterns (`BE/filter/NoStoreApiScopes.java:42-55`, spec `security-and-access.md:1798-1806`). Unclassified: `/api/v1/admin` (12 controllers incl. audit log and admin views of holdings), `/exchange` (8), `/orders` (4), `/material-exchange`, `/material-requests`, `/leitung`, `/org-chart`. Backend ETags are still active (`EtagConfig.java:45-60`), so #1672's reason for not inverting the default still holds | CONFIRMED; sensitivity of the unlisted families UNKNOWN (needs review); completeness test (PRV-06) |
| S1672-040 (fixed, at risk) | Nested-record redaction (REQ-SEC-040) | `MissionPeerRedactor.java:118-160`; tests are case-by-case (`MissionPeerRedactorTest.java:49-167`), with no reflective walk | ADJUSTED (PRV-10) |

### 4.6 API security audit 2026-08-30 (PR #1724): 28 raw, 17 fixed, 13 refuted (list not committed)

| ID | Item | Status today | Verdict |
| --- | --- | --- | --- |
| S1724-MEMBERTIER | Full nested `UserDto` for members on `GET /missions/{id}` | Fixed: `BE/controller/MissionController.java:248` `redactForPeer`; `MissionPeerRedactor.java:190-213` | DROPPED |
| S1724-INGESTAUD | Ingest audience set to `basetool-backend` | Fixed by hand in production, per vault `Ingest.md:130-148` (host not re-read) | DROPPED |
| S1724-SCOPE | `REQUIRED_SCOPE` / `ALLOWED_TOOLS` unset | Moot: the `/v1` routes and the azp/scope/tool gates were removed (#2092, vault `Monitoring.md:311-320`) | DROPPED |
| S1724-PRED (fixed, at risk) | One on-behalf predicate (`canManageUserInventory` / `…RefineryOrders`) and `canSeeOperationLedger` | Central in `AccessGateService`/`OwnerScopeService`; used by inventory, joborder production, operation payout and refinery | CONFIRMED. The audit's lesson was a fix "never generalised to its siblings"; per-module copies would recreate it. Keep it in the scope kernel |

### 4.7 Performance audit 2026-05-20 (PRs #155–#160, #175): ≥ 24 labelled findings

17 were fixed in #175 and six parts in #155–#160. H-2 was already present; M-2 and M-8 were false
positives. The mapping of H-4, L-3, L-5 and L-6 to PRs is UNKNOWN.

| ID | Item | Status today | Verdict |
| --- | --- | --- | --- |
| PERF-V91 | Drop V91 two-column index "in a future cleanup pass" | Dropped by `V103__…sql:39`; status index restored by `V209` | DROPPED |
| PERF-CONC | `CREATE INDEX CONCURRENTLY` "if needed" | No trigger evidenced | REPRIORITISED (conditional) |
| PERF-FORKS | `maxParallelForks` reverted; move `WebClientResilienceTest` to virtual time | Not done (no `maxParallelForks`; real 400 ms limiter at `WebClientResilienceTest.java:61`) | REPRIORITISED: a prerequisite if Option B multiplies parallel test tasks |
| PERF-M6 | Rejected `loading=lazy` | Header logo pattern (96 `<img ` in 92 templates) | REJECTION-HOLDS |
| PERF-M7 | Redis persistence off | Reversed: `quadlet/systemd/redis.container:20` and `docker-compose.yml:244` persist (`--save "60 1" --appendonly yes --appendfsync everysec`) | DROPPED |
| PERF-L7 (fixed, at risk) | Heartbeat 60 s and TTL 120 s changed in lockstep | `static/js/mission-presence.js:4` and `FE/service/LiveSyncPresenceService.java:58`. No parity test, and the Javadoc (`:54-56`) names `krt-live-sync.js`, which has no heartbeat | ADJUSTED (parity test, Javadoc fix) |

### 4.8 Caching audit (issue #1002)

Counts: 4 latent findings, 11 expansion candidates (5 adopted, 6 rejected), 22 architecture
suggestions (9 adopted, 10 rejected), and a missed-eviction audit with 9 gaps, 6 of them real and
fixed.

| ID | Item | Status today | Verdict |
| --- | --- | --- | --- |
| C1002-L4 | `@Version` safety of cached mutators "holds only by AOP self-invocation"; the fix was a comment | Comment gone (ADR-0214), no test, not in REQ-DATA-007. Example: `CityService.java:69` `@Cacheable getCity` returns the entity, and `:84` self-invokes it in the mutator; 33 `@Cacheable` in 16 services | **ADJUSTED, high priority before any catalogue restructuring** (PRV-04). After a query/command split the mutator edits the cached instance in place, and with the default after-invocation eviction a failed write leaves it in the cache |
| C1002-R-SPACESTATION, -ALIAS, -SYSSET, -RULES, -S3, -STAMPEDE, -WARM, -HTTP | Rejected expansion and architecture options | Reasons unchanged; the frontend `getCached` already uses `sync = true` (`BackendApiClient.java:182`) | REJECTION-HOLDS |
| C1002-R-ORGCHART | Org chart / hierarchy caching (eviction scattered over 3+ services) | Holds today | REJECTION-REVISIT only if the orgunit module publishes change events anyway; never build events just for caching |
| C1002-R-CACHE03 | Entity-aliasing guard "~zero impact" | Impact rises with modularisation (see L4) | REJECTION-REVISIT |
| C1002-DIST02 | ADR-0074: defer Redis pub/sub eviction | Accepted | REJECTION-HOLDS for Options A–C; Option D would break the single-instance precondition of REQ-DATA-007 |
| C1002-FECACHE1 (fixed, at risk) | `CachedCatalog` allowlist makes per-principal keys unrepresentable | `BackendApiClient.java:182-209`, no `String` overload | CONFIRMED: per-domain facades must not add URI-keyed `@Cacheable` |

### 4.9 Concurrency hardening (issue #1109)

All 48 sub-issues are closed as completed; the 7 refuted candidates are recorded in #1109.

- **K1109-REFUTED:** accepted non-issues. Verdict: CONFIRMED.
- **K1109-AT-RISK:** the fixes hold, and all of them live in modules or kernels that a split would
  touch:
  - #1130 `ParallelPageLoader` context relay;
  - #1139 the authorization fragment (`MissionRepositoryAuthorizationFragment`);
  - #1135 `saveAndFlush` version echo;
  - #1152 push-after-commit (`NotificationEventListener.java:57`).
- **A gap of the #1130 class is visible now:**
  - `ParallelPageLoader.java:68-73` does not capture the user locale.
  - `ReactorContextPropagationConfig.java:106-119` does register a locale accessor.
  - `UserLocaleRelayFilter.java:51-57` sends no `Accept-Language` when no locale is bound.
  - Impact is UNKNOWN; settle with a MockWebServer test. Verdict: ADJUSTED (PRV-05).

### 4.10 Permission-gate audit (PR #548)

3 documentation gaps and 2 precision fixes, all fixed. The matrix was rebuilt from 88 controllers
in #1981; the backend has 98 `@RestController` classes today. Whether that gap is new controllers
or a counting difference is UNKNOWN; settle by diffing the matrix against the controllers.
Verdict: ADJUSTED — the role matrix should get per-domain sections.

### 4.11 Post-cutover ops audit (PR #1984) and documentation audit (PR #1981)

- **#1984:** 9 code and configuration gaps, all fixed. Host-side parts depend on the Ansible role;
  the vault records a production role run with the owner's yes (`Host Provisioning.md:175`).
  Whether every #1984 host item is live was not re-read on the host (UNKNOWN). Domain effect: none.
- **#1981 open decisions:**

| ID | Item | Status | Verdict |
| --- | --- | --- | --- |
| D1981-ADR | 29 ADRs flipped Proposed→Accepted without ratification | UNKNOWN; the ADRs this refactor relies on (0020, 0032, 0047, 0205) read "Accepted" | UNKNOWN (owner) |
| D1981-TERMS | Terms mention guests and list five areas | Open: `backend/src/main/resources/messages_de.properties:234,236,238` | CONFIRMED (owner; re-consent per ADR-0127) |
| D1981-PRIVACY | `privacy.p_3_8_1` on IP logging | `frontend/…/messages_de.properties:1411`; unchanged? | UNKNOWN |
| D1981-INGEST007 | "Remember me" promised, extractor has no opt-in | Spec unchanged (`docs/specs/desktop-ingest.md:515-522`); extractor repository not read | UNKNOWN |
| D1981-REQIDS | Duplicated REQ ids | Renumbered (`d9a7d53bb3`) | DROPPED |
| D1981-RESTORE | 35-day restore-drill window | 8 days (`monitoring/prometheus/alerts/ops-automation.yml:56`) | DROPPED |
| D1981-KCHARDEN | Keycloak hardening steps 2, 11 (OTP for Admin), 12 | Open (`docs/KEYCLOAK_HARDENING_RUNBOOK.md:39,47,48,50`) | CONFIRMED (independent of domains; step 11 matters most) |
| D1981-OAUTH | Confidential frontend client | Done in production 2026-09-25 (`docs/OAUTH2_CONFIDENTIAL_CLIENT_MIGRATION.md:8-10`) | DROPPED |
| D1981-TS | TypeScript migration | Deliberately unscheduled (ADR-0125) | REJECTION-HOLDS (per-domain JS folders work with `checkJs`) |

### 4.12 Design audits (PRs #174, #177)

18 findings: 17 implemented, and #177 reverted 2 of them. C6 was deferred.

| ID | Item | Status today | Verdict |
| --- | --- | --- | --- |
| UI174-C6 | `--color-gray-4/-5` naming | Only `--color-gray-1..4` exist (`static/css/styles.css:38-41`) | DROPPED |
| UI174-MODALS | Three modal patterns | Superseded by ADR-0177 and `SingleModalShapeTest` | DROPPED |
| UI177-FOOTER | Footer moved by JS | Still `static/js/sidebar.js:102-103` | CONFIRMED (low) |

### 4.13 Fixed items a domain split could put at risk — consolidated

| Fix (audit) | Where it lives | How a split could weaken it | Guard that keeps it safe |
| --- | --- | --- | --- |
| No `permitAll` beyond 4 endpoints (REQ-SEC-052) | `AT:345` | Moved controllers are not scanned | PRV-01 (select by `@RestController`) |
| Every read/write endpoint gated (#150, PROJECT_REVIEW 1.4) | `AT:389,737` | Same | PRV-01 (ingest's annotation rules as the model) |
| No `SecurityContextHolder` outside the seam (PROJECT_REVIEW 1.1) | `AT:202,219,253` | Services, controllers and mappers outside the old packages go unchecked | PRV-01 |
| Mass-assignment block (#150 C-3) | `AT:1252` | FQN string of `MissionDto`; package filter | PRV-01 (class literals) |
| Bank stays org-unit-blind (ADR-0020, REQ-BANK-008) | `AT:1794,1853` | `:1794` passes vacuously if `OwnerScopeService` moves | PRV-01; package-keyed containment |
| Scope wiring of tenant services (#783-era) | `AT:978,1036` | Name whitelists miss split or renamed classes; `OperationPayoutService` is already missing | PRV-12 |
| Peer PII redaction (#150 C-1, #1672 REQ-SEC-040) | `MissionPeerRedactor`, `AT:1129` | Nested records from other modules | Size assertion (exists) + PRV-10 |
| One on-behalf predicate (#1724) | `AccessGateService` | Per-module copies | Keep in the scope kernel; the rule in PRV-12 |
| No-store API families (#1672 REQ-SEC-031) | `NoStoreApiScopes` | New module paths default to storable | PRV-06 |
| Client-IP / locale relay on parallel reads (#1109 P0-5) | `ParallelPageLoader` | New `ThreadLocal`s not captured | PRV-05 |
| Single resilience + relay chain (ADR-0032) | `webClient` bean | A module builds its own `WebClient` | PRV-07 ArchUnit rule |
| Per-principal-safe frontend cache (#1002 FE-CACHE-1) | `CachedCatalog` | Facade-level `@Cacheable` | PRV-07 rule "no `@Cacheable` outside the client core" |
| Cached catalogue writes (#1002 L4) | catalogue services | Query/command split | PRV-04 |
| SpEL gates on security beans (all audits) | 155 `@bean` references | Rename or move of a bean breaks gates at runtime | PRV-02 |
| Audit row in the business transaction (REQ-AUDIT-001) | `AuditService.record` `MANDATORY` | Conversion to after-commit events | PRV-03 guard rule |
| Terms-document client used once (REQ-SEC-052) | `TermsDocumentClientUsageTest` (field name `termsDocumentClient`) | Injection under another field name is invisible | Re-key the test on the bean or qualifier (PRV-07) |

---

## 5. Derived proposals

**PRV-01 — Re-key the ArchUnit guards before the first package move (blocking)**

- **Evidence:** §4.0 (25 silent + 6 partial of 43); ArchUnit default
  `failOnEmptyShould = TRUE`; ingest's annotation-based rules; class literals at `AT:464`.
- **Impact:** without this, Option A/B/C can remove security gates with a green build.
- **Change:**
  1. Select by role, not package: `@RestController`, `@Service`, Spring Data
     `Repository`-assignable, MapStruct `@Mapper`.
  2. Replace FQN strings with class literals (a move becomes a compile error).
  3. Add a meta-test that every remaining FQN string resolves in `CLASSES`, plus minimum-size
     assertions like `AT:1144`.
  4. Remove `allowEmptyShould(true)` where a move could empty the selection.
  5. Later, add per-module rules (ArchUnit layered per module or Spring Modulith
     `ApplicationModules.verify()` — not on the classpath today; catalog has no entry).
- **Pros:** makes silent weakening loud; test-only change.
- **Cons:** about 31 rules to rewrite; role predicates can select more than before (for example
  `@Component` helpers).
- **Risks and guard:** a re-keyed rule may flag legitimate code, so it must run green on today's
  code before any move. Security: strictly strengthens.
- **Effort:** M.
- **Prerequisites:** none; amend the ADR-0047 and REQ-SEC-003 wording.

**PRV-02 — Resolve every `@PreAuthorize` SpEL bean reference in a context test**

- **Evidence:** 155 references to 6 security beans; `AT:1093` only checks substrings.
- **Change:** parse every `@PreAuthorize` value with Spring's SpEL parser, collect the bean
  references, and assert that each bean exists and has the method with that arity.
- **Pros:** a renamed or moved security bean fails the build instead of 500-ing (fail-closed but an
  outage) at runtime.
- **Cons:** needs a context-level test.
- **Risk:** none to production.
- **Effort:** S.

**PRV-03 — Make the 21 cross-domain `MANDATORY` hops explicit module ports**

- **Evidence:** §3.4.
- **Change:**
  - Document target-module API methods as "joins the caller's transaction".
  - For orgunit→inventory/orgchart and inventory/joborder/identity→materialexchange, use
    synchronous in-transaction events. The listener is `@Transactional(MANDATORY)` and must not be
    `@TransactionalEventListener` or `@Async`.
  - `AuditService.record` stays a synchronous kernel call.
- **Pros:** correct dependency direction, atomicity kept, visible contracts.
- **Cons:** implicit control flow; listener ordering must be explicit.
- **Risks and guard:**
  - An "upgrade" to after-commit silently loses atomicity: an audit row goes missing, or offers
    exceed stock.
  - Guard: an ArchUnit rule on the listener annotations, plus the existing
    `MaterialExchangeOfferRatchetTest` and `InventoryCheckoutServiceAuditTest`.
- **Effort:** M–L.
- **Prerequisites:** PRV-01; an ADR for in-transaction module ports.

**PRV-04 — Guard the cached-entity invariant before restructuring catalogue services**

- **Evidence:** C1002-L4.
- **Change:**
  - Interim: a test per cached service proving the mutator does not operate on the cached
    instance; write the invariant into REQ-DATA-007 and the vault.
  - Target: cache DTOs or ids, not entities, then an ArchUnit rule that forbids `@Cacheable`
    returning `@Entity` or wrappers of it. The vault has already learned this: "caching entities
    is … a change of lifetime" (`Backend.md:248-250`).
- **Pros:** removes a latent integrity bug class.
- **Cons:** the target option touches 33 cached methods.
- **Risk:** cache hit ratio changes (monitoring: the `cache_*` meters, REQ-OBS-005).
- **Effort:** S (interim) / L (target).

**PRV-05 — One context snapshot for parallel page loads**

- **Evidence:** §4.9.
- **Change:** capture and restore `LocaleContextHolder` as well, and add a parity test between
  `ReactorContextPropagationConfig`'s registered accessors and what `ParallelPageLoader` captures.
  Better: capture through the already-present Micrometer context-propagation registry (the exact
  snapshot API must be checked in that library's documentation).
- **Constraint:** ADR-0223 keeps these holders `ThreadLocal`; no `ScopedValue`, no preview
  structured concurrency.
- **Security:** prevents the next REQ-SEC-011-class relay gap.
- **Effort:** S.

**PRV-06 — Completeness test for REQ-SEC-031**

- **Change:** enumerate every backend `GET` mapping and require each path to match either
  `NoStoreApiScopes` or an explicit, reviewed "revalidatable" list. Review `/admin`, `/exchange`,
  `/orders`, `/material-exchange`, `/material-requests`, `/leitung` and `/org-chart`.
- **Pros:** new module endpoints must classify themselves.
- **Cons:** a list to maintain.
- **Risk:** marking catalogue families no-store costs their ETag revalidation.
- **Effort:** S–M.
- **Prerequisites:** owner decision; REQ-SEC-031 amendment.

**PRV-07 — Per-domain frontend facades on the one transport core (J-R05 revisited)**

- **Change:**
  - `BackendApiClient` stays the core: one `exchange(...)`, RFC 7807 mapping, metrics,
    `CachedCatalog`.
  - Each domain gets a typed facade, and controllers in domain X may depend only on X's facade.
  - Only `WebClientConfig` may build a `WebClient`/`RestClient`, with the two health indicators
    allow-listed.
  - The 11 streaming proxies go behind a core streaming method or onto a ratcheted allowlist.
  - `TermsDocumentClientUsageTest` is re-keyed on the bean.
  - Optional later step: `@HttpExchange` proxies from the same bean (the September audit's FE-SIMP-02
    named this).
  - Correct arc42 §4.1 and ADR-0032 wording to "one filter chain".
- **Pros:** frontend domain separation; typed URIs.
- **Cons:** 83 dependents to migrate incrementally.
- **Risks and guard:** a facade that builds its own client loses the bearer, org-unit, IP and
  locale relays and resilience; the ArchUnit rule catches it. URI-keyed caching is prevented by a
  rule "no `@Cacheable` outside the core".
- **Effort:** L.
- **Prerequisites:** ADR-0032 amendment.

**PRV-08 — Shared page policy**

- **Change:** replace the 100000 ceiling with a kernel page policy (lower default ceiling, explicit
  and tested opt-outs), and fold the module-local 500/200 caps into it.
- **Security:** reduces amplification on the internet-facing API.
- **Risk:** clients that page-walk large catalogues (REQ-ADMIN-003) need opt-outs; the frontend
  `PAGE_WALK` catalogues must be checked.
- **Effort:** M.
- **Prerequisites:** a REQ-API amendment.

**PRV-09 — Split `support` into a shared kernel and domain-internal code**

- **Evidence:** 43 of 63 `support` classes are domain-named.
- **Change:** the kernel keeps `OptimisticLock`, `LikePatterns`, `RequestMemo`, `Roles`,
  `ProblemResponseFactory`, `StringNormalization`, `AuthenticatedSubject` and similar; everything
  else moves into its module.
- **Pros:** ADR-0047's leaf rule stops being a back door (§4.0).
- **Cons:** churn in imports and tests.
- **Risk:** cycles surface. That is intended, and `backendPackagesShouldBeFreeOfDependencyCycles`
  will show them.
- **Effort:** M.
- **Prerequisites:** PRV-01.

**PRV-10 — Make PII leaks unrepresentable**

- **Change:**
  - A reflective test that fills every nested `UserDto` inside `MissionDto` with sentinel PII and
    asserts `cleanupMissionForPeer` removes it all.
  - The identity module publishes a user summary without an `email` component; only `/me` returns
    the self profile.
- **Honours J-R07:** explicit construction, now with compile-time absence of the field.
- **Effort:** S (test) / M (DTO split; contract freeze `ExternalContractTest` and the frontend DTO
  mirrors move with it).

**PRV-11 — Audit-completeness guard per audited module (replaces the rejected base template)**

- **Change:** once the audited domains are packages, add a rule: every public method with a
  mutating prefix (the set already in `AT:155-191`) on a service in an audited module reaches
  `AuditService.record` or `BankAuditService.record`, directly or via a same-module helper, or is
  on a reviewed exemption list.
- **Pros:** REQ-AUDIT-001 becomes build-enforced.
- **Cons:** heuristic; needs exemptions for internal helpers.
- **Effort:** M.
- **Prerequisites:** PRV-01, Option A.

**PRV-12 — Replace the scoped-service whitelists with a module-scoped rule**

- **Evidence:** 12 whitelisted names (`AT:980-992`) against 34 classes that depend on
  `OwnerScopeService`; `OperationPayoutService` is scoped but not listed.
- **Change:** services of tenant-scoped modules (mission, operation, inventory, joborder, refinery,
  hangar, …) must depend on the scope API. Keep the #1724 predicates in the scope kernel.
- **Effort:** S–M.

**PRV-13 — Correct the documentation drift found here** (read-only report; the owner's session
must apply these)

- arc42 `04-solution-strategy.md:9` and vault `Frontend.md:67`: "exactly one class" is false (11
  direct `webClient` users, plus SSE and live-sync clients).
- Vault `Bank.md:639` (921 LOC) and `:681` (~2030 LOC) against 794 and 1753 lines today.
- `LiveSyncPresenceService.java:54-56` points at `krt-live-sync.js`.
- ArchUnit messages that still ask for "a code comment" (`AT:1458`, `:1521-1522`, `:1747-1748`)
  contradict ADR-0214.

**PRV-14 — Small leftovers**

- Rename and relocate `BlueprintFuzzyMatcher` into a shared text-matching kernel (J-S16).
- Split `GlobalExceptionHandler` by exception family, keeping one advice (J-S10b).
- `LikePatterns.escape` in the blueprint search (S1672-LIKE).
- Presence heartbeat parity test (PERF-L7).
- A test forbidding `${validatedValue}` (S150-L4).
- Keycloak hardening step 11 on the owner's list (D1981-KCHARDEN).

---

## Appendix A — Commands, scripts and API calls (all read-only)

**A.1 GitHub (GET only):**

- `gh pr view 1256 --json …`
- `gh api repos/krt-profit/basetool/pulls/1256/comments`
- `…/issues/1256/comments`
- `…/pulls/1256/reviews`
- `82-prev-july-other-fetch-issues.py` (`gh api` for `issues/{n}`, `issues/{n}/comments`,
  `pulls/{n}`, `pulls/{n}/comments`, `pulls/{n}/reviews`, `pulls/{n}/commits`) for 1250–1262, 150,
  393, 783, 1672, 1724, 160, 175, 155–159, 1002, 1109, 548, 1984, 1981, 174, 177, 103. Output is in
  `82-prev-july-other-gh/*.txt`.
- `82-prev-july-other-timeline.py` (`gh api …/issues/{n}/timeline`) for 1250–1255.
- `gh search issues|prs --repo krt-profit/basetool --created 2026-07-08..2026-07-20 <term>`.
- `82-prev-july-other-states.py` (`gh api …/issues/{n}`) for 1110–1128 and 1130–1158.
- `gh issue view 1247`.

**A.2 Git:**

- `git remote get-url origin`, `git log -1`.
- `git log --all --grep` for audit, QW, Thema and structural-item terms.
- `git grep -n -i "modularity …"`.
- `git show 552ae0e19e --stat`, `git show 552ae0e19e^:PROJECT_REVIEW.md`, `…^:ANALYSIS.md`.
- `git show e9c1f14d39 -- CHANGELOG.md`.
- `git log -S JwtAudienceStartupCheck`.
- `git log -- …/SubjectRateLimitingFilter.java`.
- `git ls-tree -r` and `git cat-file --batch` inside `82-prev-july-other-size-at.py` for
  `f5703ab1` and `HEAD`.

**A.3 Helper scripts (scratchpad, prefix `82-prev-july-other-`):**

- `locate.py`: class to path and line count, and a regex counter.
- `size.py` and `size-at.py`: files over 600 lines.
- `archkeys.py`: package and name keys per ArchUnit rule.
- `mandatory.py`: `MANDATORY`/`REQUIRES_NEW` methods and their callers.
- `fanin.py`: jdeps fan-in and fan-out.
- `sept.py`: keyword search in the September findings.
- `genjson.py`: writes the JSON file.
- Extracted copies: `PROJECT_REVIEW.md`, `ANALYSIS.md`, `gh/`.

**A.4 Other reads:**

- `javap -c -p -constants` on `com.tngtech.archunit.lang.AllowEmptyShould$3` from the Gradle
  cache's `archunit-1.5.1.jar` (the default for `archRule.failOnEmptyShould` is `TRUE`).
- `mcp__ccd_session_mgmt__search_session_transcripts` for "Thema 7" (no hit) and "modularity"
  (one unrelated hit): the July report is not recoverable from session transcripts.
- Vault notes read-only, as listed in §2.
- Grep/Read over the worktree. One accidental empty `python -` invocation started an interactive
  REPL; it was stopped with `TaskStop`, changed nothing, and its output was discarded.

**A.5 Limitations:**

- The July audit's full numbered list is UNKNOWN beyond the items commits and issues cite.
- The contents of #393 L-2 and L-6 and of #1724's 13 refuted findings are UNKNOWN; those reports
  were never committed.
- Production state is taken from the vault, not re-read on the host.
- The ArchUnit behaviour classification comes from reading each rule's selection and targets. It
  was not tested by moving packages, because the brief forbids running Gradle.
