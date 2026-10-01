"""Generate 82-prev-july-other.json: prior-audit items re-evaluated (July modularity audit + earlier focused audits)."""
import json
from pathlib import Path

OUT = Path(__file__).with_name("82-prev-july-other.json")
BE = "backend/src/main/java/de/greluc/krt/profit/basetool/backend"
FE = "frontend/src/main/java/de/greluc/krt/profit/basetool/frontend"
AT = "backend/src/test/java/de/greluc/krt/profit/basetool/backend/ArchitectureTest.java"
J = "PR #1256 (2026-07-11 modularity audit)"

E = []

def add(source, id_, title, kind, status, evidence, verdict, reasoning, domain_effect, security_note):
    """Append one re-evaluated item."""
    E.append({
        "source": source, "id": id_, "title": title, "kind": kind, "status": status,
        "evidence": evidence, "verdict": verdict, "reasoning": reasoning,
        "domain_effect": domain_effect, "security_note": security_note,
    })

add(J, "J-DIAG", "Diagnosis: architecture sound, debt is size + duplication inside correct layers", "finding",
    "Partly outdated: size debt not reduced relative to growth; cross-domain coupling was never measured.",
    "Vault 10 Systems/Backend.md:297-303; July base f5703ab1: 49 of 1477 main Java files > 600 physical lines; HEAD: 53 of 2044 (82-prev-july-other-size-at.py); OwnerScopeService in=34, AuthHelperService in=46 classes (jdeps, 82-prev-july-other-fanin.py); AuditService.record called in 57 files (grep).",
    "SUPERSEDED-BY-MODULARISATION",
    "The July goal was layer-internal size/duplication; the current goal is domain separation. Layer-internal read/write splits do not reduce cross-domain coupling, which is concentrated in a few cross-cutting beans and in the package-by-layer layout itself.",
    "High: every domain still spans controller/service/repository/model/dto/mapper/support packages.",
    "Neutral; the diagnosis itself changes nothing security-relevant.")
add(J, "J-QW1", "PageResponse.of factory at list endpoints", "finding",
    "Done and held.",
    f"Only one 'new PageResponse<' left, inside the factory ({BE}/model/dto/PageResponse.java:49); PageResponse.of used 78x in 46 files (grep).",
    "CONFIRMED", "No residue; nothing to do.",
    "PageResponse becomes a shared-kernel API type every module's list endpoint uses.", "None.")
add(J, "J-QW2", "Frontend AJAX error relay consolidated on BackendErrorResponses", "finding",
    "Done and extended later (FE-SIMP-01, #2010).",
    f"relay statically imported in 28 files; 112 relay( calls in 29 controller files; 125 'catch (BackendServiceException' remain in 40 controller files (grep); {FE}/support/BackendErrorResponses.java:72,95,119.",
    "CONFIRMED", "The shared relay is the frontend error-handling kernel; remaining catch blocks are bespoke branches (vault Frontend.md:1464-1467).",
    "Frontend shared web kernel; per-domain controllers keep using it.",
    "Keeps problem+json, correlationId and status consistent; no raw upstream body is relayed.")
add(J, "J-QW3", "Leaf dedups: InventoryAuditLabels, OrgUnitLabels, UexValues, QuantityTypeRounding, MapPayloadValues, material alias guards / @EvictAllMaterialCaches", "finding",
    "Done; all helpers in use.",
    f"UexValues. 149x/5 files, QuantityTypeRounding. 14x/6, InventoryAuditLabels. 15x/7, OrgUnitLabels. 10x/2, MapPayloadValues. 40x/4, @EvictAllMaterialCaches 3x (grep); {BE}/support/UexValues.java:84,98 keep the two flag semantics apart.",
    "ADJUSTED",
    "The dedup stays; the location does not. The helpers were put into the cross-domain 'support' leaf, which now holds 63 classes of which 43 carry a domain prefix (ls + regex).",
    "Re-home: InventoryAuditLabels -> inventory, OrgUnitLabels -> orgunit API, UexValues/@EvictAllMaterialCaches -> catalogue internal, QuantityTypeRounding -> shared kernel, MapPayloadValues -> frontend web kernel.",
    "InventoryAuditLabels renders REQ-AUDIT-001 subjects; moving it must keep the label format byte-identical (audit viewer, exports).")
add(J, "J-QW4", "ObservationPrivacyFilter cross-module parity guard", "finding",
    "Done; three 106-line copies still exist and are guarded.",
    f"{BE}/config/ObservationPrivacyFilter.java, frontend and ingest copies (106 lines each); backend/src/test/.../config/ObservationPrivacyFilterMirrorParityTest.java; logging-support is logback-only with no beans (logging-support/build.gradle.kts dependencies; ADR-0205 decision 1).",
    "CONFIRMED",
    "ADR-0205's logging-support cannot absorb a Micrometer ObservationFilter @Component without amending its scope; the parity test remains the right guard.",
    "None (infrastructure, not domain).",
    "Prevents one module leaking observation tags the others redact.")
add(J, "J-S10", "ProblemResponseFactory centralises RFC 7807 assembly", "finding",
    "Done.",
    f"{BE}/support/ProblemResponseFactory.java:40,60,86; used by 9 classes (filters, error controller, GlobalExceptionHandler) (grep).",
    "CONFIRMED", "One assembly point for every problem body.",
    "Shared web kernel; modules throw shared-kernel exception types and never build problem bodies themselves.",
    "Single sanitisation point for error bodies (no stack traces / raw messages).")
add(J, "J-S10b", "Split GlobalExceptionHandler per exception family (prerequisite done in July)", "deferred",
    "Not done.",
    f"{BE}/exception/GlobalExceptionHandler.java: 1005 lines, 20 @ExceptionHandler (grep).",
    "REPRIORITISED",
    "A split by exception family is fine; a split by domain (one @RestControllerAdvice per module) is not, because handler precedence becomes order-dependent and the disclosure rules (M-7 sanitised echo, APPSEC-06 IllegalStateException) would be duplicated.",
    "Modules share exception types from a kernel; one central advice maps them.",
    "Central handler is a security property (information disclosure); keep one ordering and one sanitisation path.")
add(J, "J-RELAY", "BackendErrorResponses.relay wrapper (July: adopted only in JobOrderWriteController)", "deferred",
    "Adoption completed later.",
    "relay imported in 28 frontend controller files (grep); vault Frontend.md:1464-1467 (81 handlers, #2010/#2014).",
    "DROPPED", "Done by FE-SIMP-01.", "None.", "None.")
add(J, "J-REDACT", "MissionGuestRedactor extraction (explicit full-field reconstruction kept)", "finding",
    "Renamed MissionPeerRedactor on 2026-09-06 (member-peer tier only, ADR-0159).",
    f"{BE}/support/MissionPeerRedactor.java:56,72,118,140,161,163,190,194 (214 lines); {AT}:1129-1162 rule keyed on '.backend.controller' + cleanup...ForPeer names + size >= 10 (:1144).",
    "ADJUSTED",
    "Keep the class and its explicit constructors (see J-R07); move it to the mission module's web/API layer, not a shared leaf.",
    "Mission module owns its redaction policy; identity owns UserDtoRedaction.",
    "REQ-SEC-007/-040. The rule fails loudly on a move because of its size assertion; completeness of nested records is not tested (see S1672-040).")
add(J, "J-ROLES", "Roles.HAS_ROLE_* compile-time @PreAuthorize constants", "finding",
    "Done.",
    f"{BE}/support/Roles.java:83-110; Roles.HAS_ROLE_ used 126x in 51 files; 12 compound splices remain by design (grep).",
    "CONFIRMED", "Byte-identical SpEL; ArchUnit reads the same literal.",
    "Roles is part of the shared security kernel used by every module.",
    "Security-neutral by construction (JLS constant inlining).")
add(J, "J-S15", "Four view-assembler extractions (BankDashboardViewAssembler, MissionDetailModelBuilder, SecurityHeaders, HangarPageModelLoader)", "finding",
    "Three done, HangarPageModelLoader never built.",
    f"{FE}/controller/BankDashboardViewAssembler.java (186), {FE}/controller/MissionDetailModelBuilder.java (402), {FE}/config/SecurityHeaders.java (135, CSP at :43); HangarPageModelLoader missing; HangarPageController.java 703 lines (82-prev-july-other-locate.py).",
    "ADJUSTED",
    "Assemblers move with their domain in a per-domain frontend package; SecurityHeaders stays central (REQ-SEC-064). HangarPageModelLoader only if the hangar package needs it.",
    "Frontend per-domain packages (bank, mission, hangar) each own their view assembly.",
    "SecurityHeaders must stay the single CSP/header policy; a per-domain header writer would fork the CSP.")
add(J, "J-S16", "Import-engine split: QuantityTypeRounding, ShipTypeMatcher, FleetExportParser, BlueprintExportParser, generic BlueprintFuzzyMatcher", "finding",
    "Done.",
    f"HangarImportService 851 -> 208 lines today; ShipTypeMatcher 294, FleetExportParser 333, BlueprintExportParser 201, BlueprintFuzzyMatcher 254 (topMatches at {BE}/service/BlueprintFuzzyMatcher.java:97); used by RefineryImportService.java:109.",
    "ADJUSTED",
    "BlueprintFuzzyMatcher is now a generic text matcher used by the refinery import; its name and location tie it to the blueprint domain.",
    "Rename/relocate to a shared text-matching kernel (or catalogue); parsers stay in their domains (hangar, blueprint).",
    "The parsers carry the 8 MB pre-parse caps (#783) and entry-count caps (#1724); they must move with them.")
add(J, "J-SCWIKI", "ScWikiOrphanSweep gates the tombstone sweep on a non-empty seen-set", "finding",
    "Done.",
    f"{BE}/service/scwiki/ScWikiOrphanSweep.java:58; referenced in 3 files (grep).",
    "CONFIRMED", "Data-safety gate that cannot be forgotten.", "Catalogue module internal.", "Data integrity only.")
add(J, "J-CACHEDCAT", "CachedCatalogListLoader; refinery/inventory/mission adoption left as 'mechanical follow-up'", "deferred",
    "Adoption never happened.",
    f"CachedCatalogListLoader only used by HangarPageController ({FE}/controller/HangarPageController.java:88); 28 getCached call sites remain in 10 controllers (grep).",
    "REPRIORITISED",
    "Fold into the per-domain frontend client facades (PRV-07) instead of a separate sweep.",
    "Each domain facade exposes typed catalogue reads through the one CachedCatalog allowlist.",
    "Keep the CachedCatalog enum as the only cache key (FE-CACHE-1, per-principal leak unrepresentable).")
add(J, "J-T7-1", "Split OrgUnitMembershipQueryService from OrgUnitMembershipService", "finding",
    "Done.",
    "OrgUnitMembershipQueryService 463 lines, in=12 classes from identity, orgunit, bank, inventory, joborder, promotion, mission (jdeps); OrgUnitMembershipService 1107 lines.",
    "CONFIRMED", "It is already the de-facto published read API of the orgunit domain.",
    "Seed of the orgunit module's public query API.", "Backs REQ-ORG-017 gates; keep read-only transaction.")
add(J, "J-T7-2", "Split JobOrderQueryService from JobOrderService", "finding",
    "Done.",
    f"JobOrderQueryService 367 lines, in=1 (JobOrderController); whitelisted at {AT}:989; JobOrderService 1074 lines.",
    "CONFIRMED", "Joborder-internal read side.", "Stays internal to the joborder module.",
    "Carries OwnerScope scoping (SK-public vs squadron-private); stays on the scoped-service guard.")
add(J, "J-T7-3", "Split OrgChartReadService from OrgChartService", "finding",
    "Done.",
    f"OrgChartReadService 333 lines; OrgChartService 843 lines keeps 9 MANDATORY mirror hooks ({BE}/service/OrgChartService.java:272-437) called from OrgUnitMembershipService and KommandoGroupService.",
    "CONFIRMED", "Read split is fine; the mirror hooks are a cross-domain in-transaction coupling (see J-R02).",
    "orgchart <- orgunit coupling via MANDATORY hooks.", "Descriptive chart, grants nothing.")
add(J, "J-T7-4", "Split SquadronContextAdvice into OrgUnitContext/CapabilityFlags/LayoutMisc advices (#1257)", "finding",
    "Done; later complemented by one shared LayoutContextLoader read (FE-PERF-01, #2020).",
    f"{FE}/config/OrgUnitContextAdvice.java (190), CapabilityFlagsAdvice.java (158, LayoutContextLoader at :41), LayoutMiscAdvice.java (108), LayoutContextLoader.java:58.",
    "ADJUSTED", "The backend-call pattern moved to LayoutContextLoader; the three advices are thin now.",
    "Layout kernel of the frontend, not a domain.", "Capability flags are UX only; backend gates decide.")
add(J, "J-T7-5", "Split OperationPayoutService (+OperationPayoutCalculator) from OperationService (#1259)", "finding",
    "Done; since July the payout service became org-unit aware (ADR-0150).",
    f"{BE}/service/OperationPayoutService.java:103 (OwnerScopeService), :112 (self ObjectProvider), :245 (canSeeOperationLedger), :302 (REQUIRES_NEW), :359 (resolveTransferFeeRate); not in the staffel-scoped whitelist ({AT}:980-992).",
    "ADJUSTED",
    "July said the payout service is deliberately not org-unit scoped; it now reduces escape-only callers to their own row. The whitelist guard does not know it.",
    "Operation module internal.",
    "Add OperationPayoutService to the scoped-service guard (or make that guard package-based, PRV-12) so dropping the scope dependency fails the build.")
add(J, "J-T7-6", "Split UserDeletion/UserRegistration/UserReconciliation out of UserService (#1261)", "finding",
    "Done.",
    f"UserDeletionService 369 (out=28 classes), UserRegistrationService 432 (stampNewPendingRegistration MANDATORY at {BE}/service/UserRegistrationService.java:101), UserReconciliationService 573, UserService 553 (jdeps, wc).",
    "CONFIRMED",
    "Identity-internal split is sound. UserDeletionService reaches into every domain (FK-ordered erasure).",
    "In a modular target, erasure needs an explicit, ordered per-module contribution (not events): the FK order is load-bearing.",
    "GDPR erasure completeness (HandleErasureCoverage) and the fail-safe PENDING gate (REQ-SEC-017) must not regress.")
add(J, "J-T7-7", "Split BankPostingWriter + BankBookingGuards out of BankLedgerService (#1260)", "finding",
    "Done.",
    f"BankPostingWriter 204 lines, MANDATORY at {BE}/service/BankPostingWriter.java:57; BankBookingGuards 196; BankLedgerService 794 lines.",
    "CONFIRMED", "Bank-internal; the MANDATORY writer is the point of the split.", "Bank module internal.",
    "Insert-only ledger (REQ-BANK-004) and overdraft guard (REQ-BANK-006) unchanged.")
add(J, "J-T7-8", "Extract OrgUnitBankResponsibilityService (OwnerScope-free slice) (#1262)", "finding",
    "Done.",
    "OrgUnitBankResponsibilityService 256 lines, in=3 (OrgUnitMembershipService, RecipientResolutionService, UserDeletionService), no OwnerScopeService dependency (jdeps).",
    "CONFIRMED", "Respects ADR-0020 containment.", "Bank module's published 'responsible holders' read.",
    "Does not bridge OwnerScope and bank accounts, so it stays outside the ADR-0020 seam rule.")
add(J, "J-T7-9", "Split MaterialExchangeBoardService (reads + redaction) (#1258)", "finding",
    "Done.",
    f"MaterialExchangeBoardService 562 lines, detailDto at {BE}/service/MaterialExchangeBoardService.java:305; write service projects through it at MaterialExchangeService.java:173,233,278,328.",
    "CONFIRMED", "One redaction path for reads and write responses.", "Materialexchange module internal.",
    "REQ-MARKET-006 interessenten anonymity keeps a single implementation.")

add(J, "J-R01", "Rejected: replace Mission per-section version counters with one aggregate lock", "rejection",
    "Counters in place and DB-enforced.",
    f"{BE}/model/Mission.java:64 @DynamicUpdate, 36x @OptimisticLock(excluded = true); MissionRepository.java:240-324 seven bump*VersionIfMatches; {BE}/support/MissionSectionVersions.java:49,166,176 (Math.addExact); callers only in MissionService, MissionParticipantService, MissionTimelineService; {AT}:1479.",
    "REJECTION-HOLDS",
    "Coarse locking is a defect by project rule; nothing in a domain split needs it.",
    "Entirely inside the mission module; MissionSectionVersions moves from support to mission-internal.",
    "Protects against lost updates; the ArchUnit guard is FQN-keyed and fails loudly on a move.")
add(J, "J-R02", "Rejected: simplify the ...WithinTransaction / MANDATORY hops", "rejection",
    "31 MANDATORY methods in 15 classes; 21 method-level hops cross proposed domain boundaries.",
    "82-prev-july-other-mandatory.py: exchange->inventory (InventoryCheckoutService.bookOutForClient, mergeStockIfRequested), joborder->inventory (mergeStockIfRequested), orgunit->inventory (InventoryOrgUnitReconciler x2), inventory/joborder/identity->materialexchange (MaterialExchangeOfferRatchet x4), orgunit->orgchart (OrgChartService mirror x9), identity->bank-audit (BankAuditService.record from HandleAnonymisationService), all domains->AuditService.record (57 files).",
    "REJECTION-HOLDS",
    "The hops exist because the invariant must commit atomically with the caller (audit row, offer ratchet, chart mirror, stock merge). An after-commit or new-transaction mechanism would break that. A design that honours the reason AND fixes dependency direction: synchronous in-transaction domain events (listener runs in the publisher's transaction, MANDATORY, no own save/flush) or explicitly published 'in-transaction port' methods on the target module's API.",
    "Defines the first cross-module API contracts; the direction orgunit->inventory/orgchart and inventory->materialexchange should be inverted via in-transaction events.",
    "REQ-AUDIT-001 requires the audit row in the same transaction; converting AuditService.record to an async/after-commit event would allow an audited mutation without its audit row.")
add(J, "J-R03", "Rejected: remove the find-or-create REQUIRES_NEW self-proxy retry", "rejection",
    "17 REQUIRES_NEW methods; 9 self ObjectProviders; all intra-class.",
    f"OperationPayoutService.java:112,302; MaterialClaimService.java:96,102 (MAX_UPSERT_ATTEMPTS=5),262; MaterialExchangeService.java:112,399; MaterialRequestService.java:114,313; DeletionRequestService.java:84,128; Uex*/ScWiki* sync services (82-prev-july-other-mandatory.py REQUIRES_NEW).",
    "REJECTION-HOLDS",
    "Postgres aborts the transaction on the constraint/version failure; only a fresh transaction can retry. A domain split never crosses these (self-proxy), but a class split must retype the ObjectProvider as #1259 did.",
    "None across modules; keep inside each module.",
    "Maps persistent races to a truthful 409, never 500.")
add(J, "J-R04", "Rejected: simplify bulk-update-after-loop and the pessimistic lock before summing material claims", "rejection",
    "In place.",
    f"{BE}/service/JobOrderHandoverService.java:154 (Set of ids), :252 (single bulk delete after the loop), :264 (complete...WithinTransaction); JobOrderRepository.java:271 lockForClaimUpsert used at MaterialClaimService.java:280 (REQ-ORDERS-024, ADR-0092).",
    "REJECTION-HOLDS", "Both prevent real, shipped bugs (detached siblings, cross-squadron overclaim).",
    "Joborder-internal; the bulk update touches inventory rows (InventoryItemRepository) -> joborder writes inventory tables directly today, a boundary to formalise.",
    "Overclaim prevention is an integrity control across squadrons.")
add(J, "J-R05", "Rejected: split BackendApiClient (reason: fragments the one Resilience4j pass)", "rejection",
    "Resilience lives in the WebClient filter, not in the class; BackendApiClient is not the only path either.",
    f"ADR-0032 (2026-06-21, before July): single pass at WebClientConfig#resilienceFilter ({FE}/config/WebClientConfig.java:311-345, applied at :483-485); BackendApiClient Javadoc {FE}/service/BackendApiClient.java:58-61; 83 classes inject BackendApiClient (78 controllers); 11 controllers inject the 'webClient' bean directly (e.g. BankReportProxyController.java:55) plus sseWebClient and liveSyncAuthWebClient; arc42 04-solution-strategy.md:9 claims 'exactly one class'; no ArchUnit rule guards it (frontend ArchitectureTest has 7 rules).",
    "REJECTION-REVISIT",
    "The reason holds for per-domain WebClient beans or per-domain Resilience4j instances, not for per-domain facades. Per-domain typed facades over the one transport core (or @HttpExchange proxies built from the same 'webClient' bean) keep one filter chain: bearer relay, org-unit relay, client-IP, locale, correlation id, resilience.",
    "Enables frontend domain packages that depend only on their own facade.",
    "Must keep: one WebClient bean per trust level, CachedCatalog allowlist in the core, TermsDocumentClientUsageTest (re-keyed from field name to bean). Add an ArchUnit rule that only WebClientConfig builds WebClient/RestClient.")
add(J, "J-R06", "Rejected: generic CRUD or sync base template", "rejection",
    "No base template exists.",
    f"Only abstract classes: 4 MapStruct mappers, AbstractEntity, OrgUnit (grep 'abstract class'); auditService.record 205 calls in 57 files, bankAuditService.record 51 in 14 (grep); no audit-completeness gate beyond {AT}:442 (controllers must not write audit rows).",
    "REJECTION-HOLDS",
    "A template hides the audit call and couples every module to one base class. Modularisation makes a better guard possible: a package-keyed rule that every mutating public method of a service in an audited module reaches AuditService.record (PRV-11).",
    "Keeps modules independent.",
    "REQ-AUDIT-001: an audited mutation without its event is the failure mode.")
add(J, "J-R07", "Rejected: convert peer redaction to wither DTOs", "rejection",
    "Explicit constructors kept.",
    f"{BE}/support/MissionPeerRedactor.java:72 new MissionDto(, :163 new MissionParticipantDto(, :194 new UserDto(; MissionDto has no @With/@Builder (grep).",
    "REJECTION-HOLDS",
    "Compiler-enforced exhaustiveness forces a redaction decision on every new field. Gap: nested records forwarded by reference are not covered (REQ-SEC-040); add a reflective completeness test (PRV-10).",
    "Stays in the mission module; cross-module DTO embedding (e.g. identity user summaries) raises the stakes.",
    "Core PII control for members below Logistician.")
add(J, "J-R08", "Rejected: refactor the bank org-unit access seam at will (ADR-0020)", "rejection",
    "Seam is one class of 1753 lines with 56 distinct dependencies.",
    f"docs/adr/0020-bank-org-unit-aware-access-seam.md:23-33; {AT}:1794 (Bank* prefix + FQN OwnerScopeService), :1853 (FQN pair), :406; OrgUnitBankAccessService in=3 (BankBookingController, OrgUnitBankController, LiveSyncSubscriptionAuthorizer) (jdeps).",
    "REJECTION-HOLDS",
    "The containment is a security invariant (REQ-BANK-008). The domain target strengthens it: 'no class in the bank package except the seam may depend on the orgunit scope API' with class literals instead of a name-prefix loophole and FQN strings.",
    "The seam becomes the bank module's single adapter to the orgunit scope API.",
    "Today bankClassesMustNotConsultOrgUnitScope would pass vacuously if OwnerScopeService moved (FQN string target).")
add(J, "J-R09", "Rejected: coarser locks for simplicity (fine-grained locking kept)", "rejection",
    "In place.", "Root CLAUDE.md 'Lock as fine-grained as the data allows'; Mission counters (J-R01); per-account bank row locks (BankAccountRepository.java:54,65).",
    "REJECTION-HOLDS", "Project rule; unrelated to package structure.", "None.", "Lost-update protection.")
add(J, "J-R10", "Rejected: @PreAuthorize meta-annotations instead of inline SpEL constants", "rejection",
    "Inline constants kept.",
    f"Commit 425c08e7c9 message; ArchUnit reads the direct annotation value ({AT}:355-360, :1090-1093); 155 SpEL bean references (@ownerScopeService 68, @missionSecurityService 41, @authHelperService 18, @orgRoleManagementSecurityService 13, @bankSecurityService 10, @specialCommandSecurityService 5) (grep).",
    "REJECTION-HOLDS",
    "Meta-annotations would move the SpEL off the method and blind the SpEL-substring rules.",
    "Per-module security beans keep their bean names; a rename breaks SpEL only at runtime (PRV-02).",
    "No test resolves SpEL bean references against the context today.")
add(J, "J-R11", "Rejected: extract OrgUnitBankSettingsAssembler (#1262)", "rejection",
    "Not extracted.",
    f"{BE}/service/OrgUnitBankAccessService.java:241-244 (toSettingsDto calls canSetTarget/canConfigureVisibility/canConfigureApprovalLimits, OwnerScope-backed).",
    "REJECTION-HOLDS", "Still scope-backed core authorization.", "Stays inside the seam.", "Moving it would leak scope logic out of the ADR-0020 seam.")
add(J, "J-R12", "Rejected: parity assertion for MonitoringScrapeProperties, NotificationStreamObservationPredicate, StringNormalization", "rejection",
    "Twins still exist with legitimate differences.",
    "MonitoringScrapeProperties 60/61/67 lines (ingest/frontend/backend), NotificationStreamObservationPredicate 62/59, StringNormalization 103/107 (82-prev-july-other-locate.py classes).",
    "REJECTION-HOLDS", "Per-module differences are real.", "None (infrastructure).", "None.")
add(J, "J-R13", "Rejected: route ScWikiCommodity/ScWikiItem sweeps through ScWikiOrphanSweep", "rejection",
    "Kept inline.", "ScWikiOrphanSweep referenced in 3 files only (grep); commit 8a64acb3ed.",
    "REJECTION-HOLDS", "Different sweep shapes; over-parameterising would hide the gate.", "Catalogue internal.", "Data safety.")
add(J, "J-R14", "Rejected: collapse the two UEX flag semantics into one helper", "rejection",
    "Kept apart.", f"{BE}/support/UexValues.java:84 asBooleanOrFalse, :98 asBooleanOrNull.",
    "REJECTION-HOLDS", "The fork is semantic (null -> false vs null -> null).", "Catalogue internal.", "None.")
add(J, "J-R15", "Rejected: move fee resolution into OperationPayoutCalculator", "rejection",
    "Kept in the service.", f"{BE}/service/OperationPayoutService.java:147,359 (resolveTransferFeeRate reads system settings).",
    "REJECTION-HOLDS", "Not a pure function.", "Operation internal; reads the admin/system-settings API.", "Money-affecting configuration stays uncached (see C1002-R-SYSSET).")

add("Cross-audit (ArchitectureTest)", "X-ARCH", "Guards of earlier audit fixes are keyed on package names and FQN strings", "risk-accepted",
    "Open risk for any package move. Of 43 backend ArchUnit tests: 3 key-independent, 8 fail loudly on a move, 1 gets stronger, 25 can pass silently, 6 partially silent (manual classification of each rule's selection and target keys).",
    f"{AT} (43 @Test; rule list in 82-prev-july-other-archkeys.py output); archunit 1.5.1 AllowEmptyShould$3 reads archRule.failOnEmptyShould with default TRUE (javap on the Gradle-cache jar) - only empty SELECTIONS fail, a FQN string TARGET that no longer exists passes; allowEmptyShould(true) at {AT}:718,1693.",
    "CONFIRMED",
    "Security-critical silent rules: permitAll allow-list (:345, '.backend.controller' filter at :348), read/write endpoint gates (:389, :737), SecurityContextHolder rules (:202, :219, :253), audit-write rule (:442), mass-assignment rule (:1252), bank org-unit blindness (:1794), cascade and delegated-role rules (:1812, :1833), support leaf (:577), staffel write gate (:1036).",
    "Blocking prerequisite for Option A/B/C: re-key before the first package moves (PRV-01).",
    "Without re-keying, a moved controller could declare permitAll() or drop @PreAuthorize with a green build.")

S = "ANALYSIS.md 2026-05-11 (removed by #103, 552ae0e19e^)"
add(S, "A-COUNT", "13 findings + 8 best-practice suggestions", "finding",
    "All 13 findings resolved; suggestions 5.2-5.8 resolved or superseded.",
    "PROJECT_REVIEW.md s.6 lists 11 resolved by 2026-05-12; 1.2 promotion tests: 10 Promotion*Test files; 1.3 H2 -> backend/src/test/resources/application-test.yml:7,22-23 (Testcontainers Postgres 18, Flyway, validate); 2.4 -> MessageSource in GlobalExceptionHandler.java:121,144; 3.5 -> TimezoneSerializationTest.java; 5.6 -> OpenApiGeneratorTest.java; 5.8 -> README.md:310,383.",
    "DROPPED", "Nothing left.", "None.", "None.")
add(S, "A-5.1", "Static analysis beyond SpotBugs/Checkstyle (PMD, Error Prone)", "deferred",
    "Not adopted.", "gradle/libs.versions.toml has spotbugs 6.5.12 and checkstyle 14.3.0 only (grep: no pmd/errorprone/nullaway).",
    "REPRIORITISED",
    "Relevant again for the new goals: NullAway could enforce the JetBrains nullness annotations that CLAUDE.md says nothing gates. Compatibility with org.jetbrains.annotations: UNKNOWN from the repo; settle with the tool documentation.",
    "Framework decision for the whole codebase, not per domain.", "Nullness gates reduce NPE-driven 500s; no direct security effect.")
S = "PROJECT_REVIEW.md 2026-05-12 (removed by #103)"
add(S, "P-COUNT", "28 items (4 high, 9 medium, 9 low, 6 suggestions)", "finding",
    "23 resolved, 5 open/partial/unknown.",
    "1.1 -> ArchitectureTest.java:219,253; 1.2 -> no 'throw new RuntimeException(' in backend main (one in frontend WebClientConfig.java:307); 1.3 -> Testcontainers; 1.4 -> :389,:427,:737; 2.1 -> build.gradle.kts:512 failBuildOnCVSS=7.0; 2.2 -> SecurityHeaders.java:47-49 (no script-src-attr unsafe-inline); 2.3 -> frontend SecurityConfig.java:179-208 anyRequest authenticated; 2.4 -> ADR-0032 time limiter on every verb; 2.5 -> backend/build.gradle.kts:6 jacoco + coverage gate build.gradle.kts:242,261; 2.6 -> docker-compose.yml:48-49 etc., quadlet/systemd/*.container Memory=; 2.7 -> docker/app/Dockerfile:48 temurin 25-jre-alpine digest-pinned; 3.1-3.5, 3.8, 3.9, 5.1, 5.3, 5.5, 5.6 verified (see Markdown appendix).",
    "DROPPED", "Resolved items need no action; open ones listed separately.", "Several fixes are ArchUnit rules keyed on packages (see X-ARCH).", "See X-ARCH.")
add(S, "P-2.8", "Magic numbers: quantity epsilon", "finding",
    "Constants exist but are duplicated.",
    f"QUANTITY_EPSILON = 1e-4 in InventoryCheckoutService.java:98, JobOrderHandoverService.java:68, JobOrderItemHandoverService.java:78, JobOrderItemProductionService.java:85 ({BE}/service).",
    "ADJUSTED", "Four copies across two domains.", "Belongs to a shared quantity kernel with QuantityTypeRounding.", "None.")
add(S, "P-2.9", "Optional.get() after isPresent()", "deferred",
    "UNKNOWN exact count.",
    "64 '.isPresent()' occurrences in 27 backend main files (Grep count; a proxy, not a count of the anti-pattern).",
    "REPRIORITISED", "Modern-Java cleanup (orElseThrow / ifPresentOrElse / pattern matching); low priority; settle with an IDE or Error Prone inspection.", "None.", "None.")
add(S, "P-3.7", "System.out.println in tests", "risk-accepted",
    "Kept where deliberate.", "58 'System.out.print' matches in 15 test/e2e files (e.g. BackendSeeder.java:2446, DtoMirrorConsistencyTest.java:135) (grep).",
    "DROPPED", "Diagnostic output of e2e/seeder; logging-facade gate excludes src/test and src/e2e by design.", "None.", "None.")
add(S, "P-5.2", "Pre-commit hooks", "deferred",
    "Superseded by CI gates.", ".github/workflows/repo-lint.yml, ci.yml (26 workflows listed); no hook directory in the repo root (ls).",
    "DROPPED", "CI covers it.", "None.", "None.")

S = "PR #150 security audit 2026-05-20"
add(S, "S150-COUNT", "43 findings (4 C, 11 H, 17 M, 11 L): 38 fixed, 5 deferred", "finding",
    "All five deferred items are resolved or moot today.", "PR #150 body; entries below.",
    "DROPPED", "Nothing open.", "Fixes C-1, C-3, C-4 are ArchUnit rules (see X-ARCH).", "See S150-AT-RISK.")
add(S, "S150-M11", "Swagger UI in prod (M-11 / L-9)", "deferred",
    "Resolved: no Swagger UI is shipped and api-docs are off in prod.",
    f"gradle/libs.versions.toml:75 springdoc-openapi-starter-webmvc-api (no UI); backend/src/main/resources/application-prod.yml:36-38 api-docs enabled false; {BE}/config/SecurityConfig.java:366-367 /v3/api-docs ADMIN.",
    "DROPPED", "Moot.", "None.", "Closed.")
add(S, "S150-L4", "ConstraintViolation message echoed into the problem body", "risk-accepted",
    "Still echoed; no template interpolates the validated value.",
    f"{BE}/exception/GlobalExceptionHandler.java:497-505; no 'validatedValue' in ValidationMessages*.properties or backend main (grep).",
    "CONFIRMED",
    "Safe while templates never use ${validatedValue}; with more modules owning validation messages, a guard test is cheap.",
    "Per-module validation messages multiply the surface.",
    "Add a test that fails on ${validatedValue} in any ValidationMessages bundle (input echo / XSS in problem bodies).")
add(S, "S150-L6", "First-party GitHub Actions major-version pinning", "deferred",
    "Resolved: every action is SHA-pinned.", "109 SHA-pinned 'uses:' and 0 tag-pinned in .github/workflows and .github/actions (grep).",
    "DROPPED", "Moot.", "None.", "Supply chain hardened.")
add(S, "S150-L10", "/api/v1/announcement CSRF-ignore consistency", "deferred",
    "Moot: the backend chain is stateless and exempts /api/v1/** as a whole; the frontend keeps CSRF on everything.",
    f"{BE}/config/SecurityConfig.java:108-111 CSRF_EXEMPT_PATHS; {FE}/config/SecurityConfig.java:177 csrf(withDefaults()).",
    "DROPPED", "Moot.", "None.", "Bearer-only backend; cookie CSRF protection lives in the frontend.")
add(S, "S150-AT-RISK", "Fixed items a domain split could weaken: C-1 peer redaction rule, C-3 response-DTO-as-request-body rule, C-4 request-record field rule, H-6 CSRF, H-7 WS origins, H-8 rate limit", "finding",
    "C-1 fails loudly on a move (size assertion); C-3 silent (package + FQN string); C-4 loud (FQN selection); H-6/H-7/H-8 path/config based, unaffected.",
    f"{AT}:1129-1162 (>=10 at :1144), :1252-1291 (RESPONSE_ONLY_DTOS FQN at :131-132), :1381-1396.",
    "CONFIRMED", "Re-key C-3 before moving mission DTOs/controllers.", "Mission module.", "Mass-assignment guard (owner/org-unit stamp forgery) could go silent.")

S = "PR #393 security audit 2026-06-03"
add(S, "S393-COUNT", "21 findings (3 H, 8 M, 10 L): 16 fixed, 3 verified no change, 2 by design", "finding",
    "Report kept local ('SECURITY_AUDIT_2026-06-03.md ... intentionally not committed', PR #393 body).", "PR #393 body and commit 1a012a92af.",
    "DROPPED", "Fixed items hold.", "H-1 email policy is a central mapper rule (see S393-H1).", "See S393-H1.")
add(S, "S393-L2L6", "L-2 and L-6 'documented as by-design (no change)'", "risk-accepted",
    "UNKNOWN content.", "PR #393 body 'L-2 / L-6 documented as by-design'; CHANGELOG diff of e9c1f14d39 names neither.",
    "UNKNOWN", "Cannot be re-evaluated without the local report; settle by reading SECURITY_AUDIT_2026-06-03.md.", "UNKNOWN.", "UNKNOWN.")
add(S, "S393-M678", "M-6/M-7/M-8 verified no change (sumAmount scoping, sync mirrors, book-out lock + payout unique constraint)", "risk-accepted",
    "Still true for M-8.", f"InventoryItemRepository PESSIMISTIC_WRITE at .../repository/InventoryItemRepository.java:851,959,971,1000,1126,1160; payout toggle retry OperationPayoutService.java:302.",
    "CONFIRMED", "No change needed.", "Inventory and operation internal.", "Integrity.")
add(S, "S393-L1", "Opt-in JWT aud validator", "deferred",
    "Superseded by a fail-closed startup check in prod.",
    f"{BE}/config/JwtAudienceStartupCheck.java + JwtAudienceStartupCheckTest.java:28-31 (REQ-SEC-024), introduced by 1df02fa31 (2026-09-22); issue #1247 closed 2026-08-28.",
    "DROPPED", "Done.", "None.", "Closed.")
add(S, "S393-H1", "Email is a profile-only field (UserMapper never maps it)", "finding",
    "Fixed and central.", f"{BE}/mapper/UserMapper.java:83-90 (@Mapping(target = \"email\", ignore = true)); UserDto still has an email component.",
    "ADJUSTED",
    "The guarantee depends on every projection going through this mapper; a module-local user projection could map email. A split into a self-only DTO (with email) and a peer DTO (without the field) makes the leak unrepresentable, the same philosophy as J-R07.",
    "Identity module publishes a user summary type without email.", "PII (email) leak prevention.")

S = "PR #783 security review 2026-06-21"
add(S, "S783-COUNT", "2 exploitable (H1, M1) + ~10 low/info hardening + ADR-0034; 2 deferred", "finding",
    "Fixed items hold; M1 guest token and ADR-0034 are moot since ADR-0159.",
    "GuestParticipantTokenService/GuestEditTokenContext missing (locate); docs/adr/0034-anonymous-outsider-mission-visibility.md:3 superseded by ADR-0159; MissionSecurityService.java:144 canEditFinanceEntry.",
    "DROPPED", "Nothing open besides S783-PAGECAP.", "canEditFinanceEntry lives in the mission security bean (SpEL @missionSecurityService, 41 refs).", "See PRV-02.")
add(S, "S783-PAGECAP", "Lower the 100000 pagination ceiling", "deferred",
    "Still 100000.",
    f"{BE}/web/PaginationUtil.java:45 MAX_PAGE_SIZE = 100_000; module-local ceilings drift: MaterialExchangeQueryParams.java:37 (500), SyncReportController.java:61 (200).",
    "REPRIORITISED",
    "Internet-reachable API (ADR-0135); new module list endpoints inherit the global ceiling. A shared page policy with a lower default and explicit, tested opt-outs belongs to the API kernel.",
    "Each module's list endpoints use the kernel page policy.",
    "Amplification/DoS: one GET can request 100000 rows; per-subject budgets do not cover plain reads (S1672-BUDGET).")
add(S, "S783-AUD", "JWT audience validation stays opt-in", "deferred",
    "Superseded (see S393-L1).", "JwtAudienceStartupCheck (1df02fa31).", "DROPPED", "Done.", "None.", "Closed.")

S = "PR #1672 API security audit 2026-08-25"
add(S, "S1672-COUNT", "5 confirmed and fixed (REQ-SEC-039..042, REQ-SEC-031 amended); 2 refuted; 1 consistency item not done", "finding",
    "Fixes hold.", "PR #1672 body and commit 795be2efd9.", "DROPPED", "See entries below.", "REQ-SEC-031 is a path list (S1672-031).", "See S1672-031, S1672-040.")
add(S, "S1672-AUDASSERT", "Fail-closed @AssertTrue startup invariant for expected audiences", "deferred",
    "Implemented later.", "JwtAudienceStartupCheck (1df02fa31, APPSEC-08).", "DROPPED", "Done.", "None.", "Closed.")
add(S, "S1672-BUDGET", "Per-subject GET budget for aggregation endpoints", "risk-accepted",
    "Partly addressed: per-subject buckets cover writes, SSE/live-sync connects and export segments; other reads stay per-IP.",
    f"{BE}/config/SubjectRateLimitingFilter.java:57-63 (REQ-SEC-033), :72; RateLimitingFilter per-client buckets.",
    "CONFIRMED",
    "Accepted risk still bounded by statement timeout, pool size and edge cap. Expensive new module reads must carry an EXPORT_SEGMENTS path segment to be budgeted - a path convention the module API rules must state.",
    "Module API design rule for expensive reads.", "DoS surface on the internet-facing API.")
add(S, "S1672-LIKE", "Route BlueprintProductService.searchProducts through LikePatterns.escape", "deferred",
    "Not done.",
    f"{BE}/service/BlueprintProductService.java:86 passes the raw trimmed query to findActiveProductRows (:317); BlueprintRepository.java:125-126,219 LIKE CONCAT('%', :q, '%') unescaped; LikePatterns.escape used 10x in 7 other files (grep).",
    "CONFIRMED", "Consistency fix, low risk: a wildcard only broadens matching of a global catalogue.", "Blueprint/catalogue module.", "Low (no data exposure beyond the public catalogue).")
add(S, "S1672-031", "REQ-SEC-031 no-store families are a hand-kept path list", "finding",
    "Fixed for the 14 listed families; many API families are unclassified.",
    f"{BE}/filter/NoStoreApiScopes.java:42-55 (14 patterns); docs/specs/security-and-access.md:1798-1806; not listed: /api/v1/admin (12 controllers incl. audit and admin views of holdings), /exchange (8), /orders (4), /material-exchange, /material-requests, /leitung, /org-chart (RequestMapping grep). Backend ETags still active (EtagConfig.java:45-60), so the #1672 reason for not inverting the default still holds.",
    "CONFIRMED",
    "Sensitivity of the unlisted families: UNKNOWN (needs a per-family review). New module endpoints silently get the storable directive.",
    "Each module must classify its GET families; add a completeness test (PRV-06).",
    "Storable responses of sensitive data on shared devices/proxies/app HTTP caches.")
add(S, "S1672-040", "Redaction completeness for nested user records (REQ-SEC-040)", "finding",
    "Fixed for assignedUnits[].ship.owner; completeness not tested.",
    f"{BE}/support/MissionPeerRedactor.java:118-160 (cleanupUnitForPeer/cleanupShipForPeer); MissionPeerRedactorTest.java:49-167 case-by-case, no reflective record walk (grep).",
    "ADJUSTED", "The ArchUnit rule checks that a cleanup method is called, not that every nested UserDto is reached.", "Mission module; embedding DTOs from other modules makes it riskier.", "PII leak to members below Logistician.")

S = "PR #1724 API security audit 2026-08-30"
add(S, "S1724-COUNT", "28 raw findings: 17 confirmed and fixed, 13 refuted (report not committed)", "finding",
    "Fixed items hold; refuted list UNKNOWN.", "PR #1724 body ('full audit report is available on request').", "DROPPED", "See entries below.", "Central predicates (S1724-PRED).", "See S1724-PRED.")
add(S, "S1724-MEMBERTIER", "Member tier still received the full nested UserDto on GET /missions/{id}", "deferred",
    "Resolved by the peer redaction of 2026-09-06.",
    f"{BE}/controller/MissionController.java:248 redactForPeer(...); MissionPeerRedactor.java:190-213 cleanupUserForPeer nulls roles/permissions/memberships.",
    "DROPPED", "Done.", "Mission module.", "Closed.")
add(S, "S1724-INGESTAUD", "Ingest ran APP_SECURITY_JWT_EXPECTED_AUDIENCES=basetool-backend", "deferred",
    "Resolved in production by hand (per vault; host not re-read).", "Vault 10 Systems/Ingest.md:130-148; 70 Reference/Reference.md:142.",
    "DROPPED", "Done.", "None.", "Closed per vault.")
add(S, "S1724-SCOPE", "REQUIRED_SCOPE / ALLOWED_TOOLS unset on ingest", "deferred",
    "Moot: the /v1 routes and the azp/scope/tool gates were removed (#2092, 2026-09-28).", "Vault 10 Systems/Monitoring.md:311-320.",
    "DROPPED", "Moot.", "None.", "Only the audience gate remains.")
add(S, "S1724-PRED", "One on-behalf predicate for four write paths (canManageUserInventory / canManageUserRefineryOrders) and canSeeOperationLedger", "finding",
    "Fixed as central predicates used by four domains.",
    f"AccessGateService/OwnerScopeService; users: InventoryItemService, JobOrderItemProductionService, OperationPayoutService, RefineryOrderService, OperationController, RefineryOrderController (grep).",
    "CONFIRMED",
    "The audit's lesson was that a fix in one path was never generalised to its siblings; per-domain copies of the predicate would recreate exactly that.",
    "Keep the predicate in the scope kernel; modules call it, never re-implement it.", "Cross-Staffel member-ledger writes (REQ-SEC-005).")

S = "Performance audit 2026-05-20 (PRs #155-#160, #175)"
add(S, "PERF-COUNT", ">= 24 labelled findings (H-1..7, M-1..10, L-1..7): 17 fixed in #175, parts 1-6 in #155-#160; H-2 already present; M-2, M-8 false positives", "finding",
    "Label mapping for H-4, L-3, L-5, L-6 UNKNOWN (not named in any PR title/body).", "PR #175 title/body; PRs #155-#160 bodies.", "DROPPED", "Fixed items hold unless listed.", "None.", "None.")
add(S, "PERF-V91", "Drop the V91 two-column index 'in a future cleanup pass'", "deferred",
    "Superseded.", "backend/src/main/resources/db/migration/V103__drop_legacy_owning_squadron_columns.sql:39 drops idx_mission_owning_squadron_internal; V209__restore_mission_status_covering_index.sql.",
    "DROPPED", "Done by the org-unit migration.", "None.", "None.")
add(S, "PERF-CONC", "Switch large-table index builds to CREATE INDEX CONCURRENTLY when needed", "deferred",
    "Conditional; no trigger evidenced.", "PR #156 body (risk section).", "REPRIORITISED", "Only if table size demands it.", "None.", "None.")
add(S, "PERF-FORKS", "maxParallelForks reverted; move WebClientResilienceTest to virtual time", "deferred",
    "Not done.", "No maxParallelForks in gradle.properties or build scripts (grep); WebClientResilienceTest.java:61 real 400 ms time limiter.",
    "REPRIORITISED",
    "Becomes relevant if Option B (Gradle subprojects) multiplies parallel test tasks: timing-based tests will flake first.", "Prerequisite for Option B.", "None.")
add(S, "PERF-M6", "Rejected: loading=lazy on images", "rejection",
    "Holds.", "PR #175 reviewer notes; 96 '<img ' in 92 templates, mostly the header logo (Grep count).",
    "REJECTION-HOLDS", "Above-the-fold images.", "None.", "None.")
add(S, "PERF-M7", "Redis persistence off (--save \"\" --appendonly no)", "finding",
    "Reversed later: production Redis persists again.", "quadlet/systemd/redis.container:20 and docker-compose.yml:244 '--save \"60 1\" --appendonly yes --appendfsync everysec'.",
    "DROPPED", "Superseded by a later decision (Redis now also holds live-sync and ingest state).", "None.", "Session durability.")
add(S, "PERF-L7", "Presence heartbeat 60 s and TTL 120 s changed in lockstep", "finding",
    "Holds, but unguarded and mis-documented.",
    f"frontend/src/main/resources/static/js/mission-presence.js:4 HEARTBEAT_MS = 60000; {FE}/service/LiveSyncPresenceService.java:58 ENTRY_TTL = 120 s; its Javadoc :54-56 names krt-live-sync.js, which has no heartbeat constant (grep).",
    "ADJUSTED", "Add a parity test (like LiveSyncSectionMapParityTest) and fix the Javadoc pointer.", "Mission presence JS moving into a per-domain folder could break the pairing unnoticed.", "None.")

S = "Issue #1002 caching audit 2026-07-05"
add(S, "C1002-COUNT", "4 latent findings, 11 expansion candidates (5 adopted, 6 rejected), 22 architecture suggestions (9 adopted, 10 rejected, rest folded), missed-eviction audit 9 gaps / 6 real", "finding",
    "Adopted items implemented (#1004, #1007, #1009, #1011, #1014).", "Issue #1002 body and comments.", "DROPPED", "See entries below.", "Catalogue module owns most caches.", "See C1002-L4.")
add(S, "C1002-L4", "@Version safety of cached catalogue mutators holds only through AOP self-invocation", "finding",
    "Invariant now carried by nothing: the audit's fix was a code comment, which ADR-0214 removed; no test, not in REQ-DATA-007.",
    f"{BE}/service/CityService.java:69 @Cacheable getCity returns the entity; :84 setLoadingDockOverride self-invokes getCity; 33 @Cacheable in 16 backend services (grep); no test/spec mentions the invariant (grep 'self-invocation').",
    "ADJUSTED",
    "A Thema-7-style query/command split of a cached catalogue service routes getX through the proxy: the mutator then modifies the cached instance in place and saves a detached entity; with @CacheEvict(beforeInvocation=false) a failed write leaves the mutated object in the cache.",
    "High for any catalogue-module restructuring.",
    "Integrity of master data; write-path correctness. Guard: forbid @Cacheable methods returning @Entity types (cache DTOs), or a test per cached service (PRV-04).")
for rid, title, why in [
    ("C1002-R-SPACESTATION", "Rejected: cache the space-station catalogue", "@Version collision; the sync writer does not evict."),
    ("C1002-R-ALIAS", "Rejected: cache material external aliases", "@Version collision and LazyInitializationException."),
    ("C1002-R-SYSSET", "Rejected: cache system-setting values", "Money-affecting config wants fast propagation; single PK lookup."),
    ("C1002-R-RULES", "Rejected: cache notification rules", "Fan-out uses a dedicated query; low value."),
    ("C1002-R-S3", "Rejected: key-scoped backend eviction", "Coarse eviction is safer; no cross-domain blast radius."),
    ("C1002-R-STAMPEDE", "Rejected: refreshAfterWrite (backend and frontend)", "No herd at one replica; frontend getCached uses sync=true (BackendApiClient.java:182)."),
    ("C1002-R-WARM", "Rejected: startup warming", "Boot fragility for a one-time miss."),
    ("C1002-R-HTTP", "Rejected: FE If-None-Match shim, real max-age, static-asset change (FE-CACHE-3, HTTP-CACHE-3, STATIC-ASSETS-4)", "Only consumer ignores Cache-Control; assets already immutable."),
]:
    add(S, rid, title, "rejection", "Holds.", "Issue #1002 section B/D.", "REJECTION-HOLDS", why, "Catalogue/admin modules; unchanged by a package split.", "None beyond integrity.")
add(S, "C1002-R-ORGCHART", "Rejected: cache org chart / org hierarchy (eviction scattered over 3+ services)", "rejection",
    "Holds today.", "Issue #1002 section B.", "REJECTION-REVISIT",
    "The reason is scattered eviction; once the orgunit module publishes change events for its own reasons, the orgchart module could evict on them. Do not build events for caching alone.",
    "Symptom of orgunit -> orgchart coupling (see J-R02).", "None.")
add(S, "C1002-R-CACHE03", "Rejected as ~zero impact: entity-aliasing guard (CACHE-03)", "rejection",
    "Holds today only because nothing restructures cached services.", "Issue #1002 section D; see C1002-L4.", "REJECTION-REVISIT",
    "The impact rises with modularisation (C1002-L4); implement the guard before splitting catalogue services.", "Catalogue module.", "Integrity.")
add(S, "C1002-DIST02", "ADR-0074: defer Redis pub/sub cache eviction until >1 replica", "deferred",
    "Deferred by decision.", "docs/adr/0074-cache-invalidation-per-instance-caffeine.md:3 Accepted.", "REJECTION-HOLDS",
    "Valid for Options A-C (one process per app). Option D (separate services/replicas) would invalidate the single-instance cache precondition (REQ-DATA-007).", "Constraint on Option D.", "Stale authorization-relevant catalogues (e.g. squadrons) across replicas.")
add(S, "C1002-FECACHE1", "Fixed: CachedCatalog allowlist enum makes per-principal cache keys unrepresentable", "finding",
    "Holds.", f"{FE}/service/BackendApiClient.java:182-209 getCached(CachedCatalog, ...) only; no String overload (grep).",
    "CONFIRMED", "Per-domain frontend facades must not introduce their own @Cacheable with URI keys.", "Frontend client core keeps the one allowlist.", "Cross-user data leak via shared cache keys.")

S = "Issue #1109 concurrency & scale hardening 2026-07-07"
add(S, "K1109-COUNT", "48 sub-issues (#1110-#1128, #1130-#1158): all closed as completed; 7 candidates refuted", "finding",
    "Done.", "82-prev-july-other-states.py output (all closed/completed); #1115 and #1120 closed 2026-07-11 by #1240.",
    "DROPPED", "Nothing open.", "Fixes live in mission, operation, notification, live-sync and frontend kernel code.", "See K1109-AT-RISK.")
add(S, "K1109-REFUTED", "7 refuted candidates (SSE fan-out starvation, refresh stripe-lock timeout, operation /finances N+1, NAT/VPN shared budget, BackendRoleSyncFilter double reads, ...)", "risk-accepted",
    "Accepted as non-issues; only examples are named.", "Issue #1109 body.", "CONFIRMED", "No new evidence against.", "None.", "None.")
add(S, "K1109-AT-RISK", "Fixed items a domain split could weaken: ParallelPageLoader context relay (#1130), authz fragment (#1139), saveAndFlush version echo (#1135), push-after-commit (#1152)", "finding",
    "Hold; one gap of the #1130 class is visible: the locale is not relayed on parallel reads.",
    f"{FE}/service/ParallelPageLoader.java:68-73 captures squadron, correlation, client IP, security, request attributes, MDC but not LocaleContextHolder; {FE}/config/ReactorContextPropagationConfig.java:106-119 registers a locale accessor; {FE}/logging/UserLocaleRelayFilter.java:51-57 adds no Accept-Language when no locale is bound; NotificationEventListener.java:57 AFTER_COMMIT.",
    "ADJUSTED",
    "Impact of the locale gap UNKNOWN (only localized backend text on parallel reads); settle with a MockWebServer test asserting Accept-Language on a loadAsync call. Add a parity test between ReactorContextPropagationConfig accessors and ParallelPageLoader captures.",
    "Per-domain frontend clients must keep using ParallelPageLoader and the shared WebClient bean.",
    "The same class of gap dropped the client IP (REQ-SEC-011) in July; the parity test prevents the next one.")

S = "PR #548 permission-gate audit 2026-06-11"
add(S, "G548-COUNT", "3 documentation gaps + 2 precision fixes in ROLES_AND_PERMISSIONS.md", "finding",
    "Fixed; later the matrix was rebuilt from 88 controllers (#1981). Today: 98 backend @RestController classes (Grep count).",
    "PR #548 body; PR #1981 body; Grep '^@RestController' = 98 files.",
    "ADJUSTED", "Whether the 98 vs 88 difference is new controllers or counting method: UNKNOWN; settle by diffing the matrix against the controllers.", "Role matrix should become per-domain sections.", "Stale matrix hides gate changes.")
S = "PR #1984 post-cutover ops audit 2026-09-22"
add(S, "O1984-COUNT", "9 code/config gaps fixed", "finding",
    "Fixed; host-side parts reach production only via the Ansible role.", "PR #1984 body; vault 10 Systems/Host Provisioning.md:175 (production role run with the owner's yes).",
    "DROPPED", "Whether every #1984 host item is live: not re-read on the host (UNKNOWN).", "None.", "Monitoring coverage of host services.")
S = "PR #1981 documentation audit 2026-09-22"
add(S, "D1981-ADR", "29 ADRs flipped Proposed -> Accepted pending owner ratification", "deferred",
    "UNKNOWN whether ratified; ADR-0020, -0032, -0047, -0205 read 'Accepted'.", "PR #1981 'Needs your decision'; docs/adr/0020...md:3, 0032...md:3, 0047...md:3, 0205...md:3.",
    "UNKNOWN", "Settle with the owner.", "The refactor leans on these ADRs.", "None.")
add(S, "D1981-TERMS", "Terms text still mentions guests and lists five areas", "deferred",
    "Open.", "backend/src/main/resources/messages_de.properties:234,236,238 (terms.p_1_2, p_2_1, p_3_1).",
    "CONFIRMED", "Owner decision: changing the text changes the content hash and forces re-consent (ADR-0127).", "None.", "Legal accuracy, not a vulnerability.")
add(S, "D1981-PRIVACY", "privacy.p_3_8_1 describes IP logging", "deferred",
    "UNKNOWN whether revisited.", "frontend/src/main/resources/messages_de.properties:1411.", "UNKNOWN", "Owner decision.", "None.", "Privacy notice accuracy.")
add(S, "D1981-INGEST007", "REQ-INGEST-007 promises a 'remember me' opt-in the extractor lacks", "deferred",
    "Spec unchanged; extractor not checked (separate repository).", "docs/specs/desktop-ingest.md:515-522.", "UNKNOWN", "Settle in basetool-sc-extractor.", "None.", "Refresh-token storage consent.")
add(S, "D1981-REQIDS", "Duplicated REQ ids", "deferred", "Resolved: 18 ids renumbered (d9a7d53bb3).", "Commit d9a7d53bb3 in PR #1981.", "DROPPED", "Done.", "None.", "None.")
add(S, "D1981-RESTORE", "RestoreDrillStaleOrMissing used a 35-day window", "deferred", "Resolved: 8 days.", "monitoring/prometheus/alerts/ops-automation.yml:56.", "DROPPED", "Done.", "None.", "None.")
add(S, "D1981-KCHARDEN", "Keycloak hardening steps 2 (SMTP / forgot password), 11 (OTP for Admin), 12 (session windows) open", "risk-accepted",
    "Open.", "docs/KEYCLOAK_HARDENING_RUNBOOK.md:26,39,47,48,50.", "CONFIRMED", "Independent of domain separation; step 11 is the weightiest.", "None.", "Admin accounts without a second factor.")
add(S, "D1981-OAUTH", "Frontend confidential OAuth2 client migration not carried out", "deferred",
    "Done in production 2026-09-25.", "docs/OAUTH2_CONFIDENTIAL_CLIENT_MIGRATION.md:8-10.", "DROPPED", "Done.", "None.", "Closed.")
add(S, "D1981-TS", "TypeScript migration plan", "rejection",
    "Deliberately unscheduled (ADR-0125).", "docs/TYPESCRIPT_MIGRATION_PLAN.md status section.", "REJECTION-HOLDS",
    "Per-domain JS folders work with checkJs; no need to convert.", "Frontend JS per domain.", "None.")
S = "PRs #174/#177 design-consistency audit 2026-05-21"
add(S, "UI174-COUNT", "18 findings: 17 implemented, C6 deferred; #177 reverted 2 of the 17 (body flex layout, modal inner max-height)", "finding",
    "Mostly superseded by the DAS KARTELL design system and the one-dialog contract.", "PR #174/#177 bodies.", "DROPPED", "See below.", "None.", "None.")
add(S, "UI174-C6", "--color-gray-4/-5 naming asymmetry", "deferred", "Moot: only --color-gray-1..4 exist.", "frontend/src/main/resources/static/css/styles.css:38-41 (grep).", "DROPPED", "Moot.", "None.", "None.")
add(S, "UI174-MODALS", "Three modal patterns harmonised (toggle differences documented in a CSS comment)", "finding",
    "Superseded by ADR-0177 (one dialog shape, SingleModalShapeTest); CSS comments banned by ADR-0214.", "docs/adr/0177-the-app-has-exactly-one-dialog-shape.md; frontend/CLAUDE.md dialog contract.",
    "DROPPED", "Moot.", "None.", "None.")
add(S, "UI177-FOOTER", "Footer relocated by JS instead of flex body", "finding",
    "Still in place.", "frontend/src/main/resources/static/js/sidebar.js:102-103.", "CONFIRMED", "Low relevance.", "Layout kernel.", "None.")

OUT.write_text(json.dumps(E, ensure_ascii=False, indent=2), encoding="utf-8")
json.loads(OUT.read_text(encoding="utf-8"))
kinds = {}
verdicts = {}
for e in E:
    kinds[e["kind"]] = kinds.get(e["kind"], 0) + 1
    verdicts[e["verdict"]] = verdicts.get(e["verdict"], 0) + 1
print(len(E), kinds, verdicts)
