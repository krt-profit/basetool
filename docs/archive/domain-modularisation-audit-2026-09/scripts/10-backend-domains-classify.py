"""Classify every top-level backend type into one domain, 'shared-kernel' or 'infrastructure'.

Order of decision: explicit table -> name-prefix rules -> infrastructure layers -> dependency majority.
Writes 10-backend-domains-classes.csv (the data file) and prints the ambiguous and fallback-decided rows.
"""
import csv
import importlib.util
import os
import re
from collections import Counter

spec = importlib.util.spec_from_file_location("common", os.path.join(os.path.dirname(os.path.abspath(__file__)), "10-backend-domains-common.py"))
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)

ACCESS = "access"
EXPLICIT = {
    "AccessGateService": (ACCESS, "per-aggregate can* gate slab for mission/joborder/inventory/refinery/operation/ship (ADR-0065)"),
    "OwnerScopeService": (ACCESS, "scope facade referenced by 64+ SpEL strings (ADR-0065)"),
    "RequestScopeResolver": (ACCESS, "request-scoped pin and ScopePredicate resolution (ADR-0065)"),
    "OrgUnitStampingService": (ACCESS, "create-time owner stamping behind OwnerScopeService (REQ-ORG-004)"),
    "ScopePredicate": (ACCESS, "scope vector returned by OwnerScopeService#currentScopePredicate"),
    "ScopeSpecifications": (ACCESS, "compile-time JPQL scope fragments per aggregate (REQ-DATA-010)"),
    "AuthHelperService": (ACCESS, "the single SecurityContextHolder reader (ArchUnit rule)"),
    "CustomJwtGrantedAuthoritiesConverter": (ACCESS, "JWT -> authorities, incl. contextual org-unit authorities"),
    "AuthenticatedSubject": (ACCESS, "which subject a request acts as, token or token-less"),
    "SubjectAuthentication": (ACCESS, "token-less Authentication carrying a subject"),
    "OrgUnitContextualAuthority": (ACCESS, "ROLE_X@orgUnit authority value"),
    "Roles": (ACCESS, "role-code constants"),
    "Permissions": (ACCESS, "permission-string constants"),
    "AuthoritiesCacheProperties": (ACCESS, "authorities-cache tuning of the JWT converter"),
    "PartialRoleScopeProperties": (ACCESS, "clients with partial role claims (REQ-SEC-036)"),
    "OwnerOrgUnitRequiredException": (ACCESS, "thrown by the stamping resolvers (REQ-ORG-023)"),
    "DatabaseActingMemberAuthorities": ("exchange", "acting member's reduced exchange authorities (ADR-0129)"),
    "ChangeSourceTransactionManager": ("exchange", "attributes writes for the change-feed triggers (ADR-0224)"),
    "TransactionManagerConfig": ("exchange", "installs the change-source transaction manager (ADR-0224)"),
    "ChangeSource": ("exchange", "writer attribution for the change-feed triggers"),
    "ChangeSourceProperties": ("exchange", "change-feed writer attribution clients"),
    "SandboxProfileGuard": ("exchange", "sandbox image guard (REQ-XCH-029)"),
    "ExchangeProblemException": ("exchange", "exchange error registry"),
    "MissionAllocationDto": ("inventory", "mission slice of an inventory entry (REQ-INV-027), mapped by InventoryItemMapper"),
    "MaterialCollectionEntryDto": ("inventory", "produced by InventoryAggregationService"),
    "UpdateDeliveredRequest": ("inventory", "delivered toggle of an inventory entry's job-order slice (REQ-INV-027)"),
    "InventoryCatalog": ("inventory", "inventory read-family discriminator"),
    "AllocationReductions": ("inventory", "deduct-from plans for inventory writes (REQ-INV-027)"),
    "InventoryAllocations": ("inventory", "allocation slice helpers (REQ-INV-027)"),
    "OverAllocationException": ("inventory", "inventory over-allocation (REQ-INV-027)"),
    "CheckoutType": ("inventory", "book-out type"),
    "BulkRebookMode": ("inventory", "bulk rebooking mode (REQ-INV-036)"),
    "InventoryProperties": ("inventory", "Lager switches"),
    "JobOrderInventoryOwnerRedactor": ("joborder", "redacts order-linked stock (REQ-ORDERS-029)"),
    "ProductionAllocationException": ("joborder", "production booking error (REQ-ORDERS-025)"),
    "QualityRequirement": ("joborder", "quality floor of item-order material buckets and claims"),
    "AggregatedMaterialDto": ("joborder", "item-order material view row"),
    "DerivedMaterialDto": ("joborder", "item-order derivation preview row"),
    "ItemDerivationDto": ("joborder", "item-order derivation preview"),
    "SubAssemblySuggestionDto": ("joborder", "item-order sub-assembly suggestion"),
    "MissionParticipantRequiredException": ("refinery", "thrown when a refinery order links a mission (REQ-SEC-042)"),
    "RefineryYield": ("catalogue", "UEX refinery-yield matrix row, written by UexRefinerySyncService"),
    "RefineryYieldRepository": ("catalogue", "repository of the UEX yield matrix"),
    "RefineryImportProperties": ("refinery", "refinery screenshot import tuning"),
    "ShipTypeMatcher": ("hangar", "ShipType resolver for the hangar import"),
    "UexLocationController": ("personalinventory", "UEX location picker served by PersonalInventoryItemService"),
    "UexLocationDto": ("personalinventory", "UEX location picker row of the personal inventory"),
    "MaterialCollectionController": ("joborder", "material collection overview of a job order"),
    "JobOrderItemStockController": ("joborder", "order-detail item stock panel"),
    "MeController": ("identity", "the caller's own profile surface (/me)"),
    "UserZone": ("infrastructure", "web argument binding"),
    "UserZoneArgumentResolver": ("infrastructure", "web argument binding"),
    "UserApprovalMailEventListener": ("identity", "after-commit identity mail listener"),
    "PendingRegistrationMailEventListener": ("identity", "after-commit identity mail listener"),
    "UserApprovalMailService": ("identity", "identity mail composition"),
    "PendingRegistrationMailService": ("identity", "identity mail composition"),
    "PendingApprovalAccessFilter": ("identity", "enforces the approval gate (REQ-SEC-017)"),
    "TermsAcceptanceAccessFilter": ("identity", "enforces the terms gate (REQ-SEC-028)"),
    "TermsConsentCheck": ("identity", "terms acceptance check (REQ-SEC-028)"),
    "RefusedSubjectWindow": ("infrastructure", "refused-subject metric window"),
    "DiscordSpiPrecheckProperties": ("identity", "Discord SPI precheck secret (REQ-SEC-022)"),
    "KeycloakSyncProperties": ("identity", "Keycloak user-sync configuration"),
    "KeycloakTrustSupport": ("identity", "Keycloak admin-client truststore"),
    "KeycloakHealthIndicator": ("infrastructure", "health probe"),
    "UserSyncTask": ("identity", "Keycloak user-sync trigger"),
    "RejectedRegistrationRetentionTask": ("identity", "rejected-registration purge (REQ-SEC-057)"),
    "MemberDepartedEvent": ("identity", "member left the organisation (identity provider)"),
    "DataInitializer": ("infrastructure", "startup seed of roles and the IRIDIUM squadron"),
    "OrgUnitRef": ("notification", "org-unit reference carried by NotificationEvent"),
    "NotificationEvent": ("notification", "contract every notification-producing event implements"),
    "SseSendFailureCause": ("infrastructure", "SSE failure tag shared by notification and live sync"),
    "RedisJsonFanout": ("infrastructure", "Redis pub/sub transport shared by notification and live sync"),
    "ResilientRedisMessageListenerContainer": ("infrastructure", "Redis listener plumbing"),
    "KrtPdfSupport": ("infrastructure", "shared PDF design layer"),
    "AuditLogPdfFormat": ("audit", "audit PDF format"),
    "BankBalanceChart": ("bank", "bank PDF chart"),
    "BankPdfFormat": ("bank", "bank PDF format"),
    "DataExportPdfFormat": ("identity", "data export PDF (Art. 15)"),
    "BankAmounts": ("bank", "bank amount formatting"),
    "BusinessMetricsCollector": ("infrastructure", "cross-domain queue-depth gauges (REQ-OBS-011)"),
    "ClientAttribution": ("infrastructure", "bounded client label for metrics and audit rows"),
    "CentralMapperConfig": ("infrastructure", "MapStruct shared config"),
    "PageResponse": ("shared-kernel", "paging envelope used by every list endpoint"),
    "AbstractEntity": ("shared-kernel", "JPA base class of every entity"),
    "OptimisticLock": ("shared-kernel", "version-check helper family"),
    "StringNormalization": ("shared-kernel", "inbound text normalisation primitives"),
    "LikePatterns": ("shared-kernel", "LIKE escaping"),
    "RequestMemo": ("infrastructure", "request-scoped memo"),
    "DtoConstraints": ("shared-kernel", "shared validation constants"),
    "OnUpdate": ("shared-kernel", "validation group"),
    "WholeNumber": ("shared-kernel", "validation constraint"),
    "WholeNumberValidator": ("shared-kernel", "validation constraint"),
    "QuantityAware": ("catalogue", "material/item amount validation contract"),
    "ValidQuantityAmount": ("catalogue", "material/item amount constraint"),
    "ValidQuantityAmountValidator": ("catalogue", "material/item amount constraint"),
    "MaterialPieceTypeLookup": ("catalogue", "material piece-type lookup SPI (ADR-0047)"),
    "AppException": ("shared-kernel", "sealed base of domain exceptions"),
    "AppExceptionKind": ("shared-kernel", "RFC 7807 identities of AppException"),
    "BadRequestException": ("shared-kernel", "generic domain exception"),
    "BusinessConflictException": ("shared-kernel", "generic domain exception"),
    "DuplicateEntityException": ("shared-kernel", "generic domain exception"),
    "Entities": ("shared-kernel", "fetch-or-404 helper"),
    "EntityInUseException": ("shared-kernel", "generic domain exception"),
    "NotFoundException": ("shared-kernel", "generic domain exception"),
    "ExternalServiceException": ("shared-kernel", "generic upstream failure"),
    "ReportGenerationException": ("shared-kernel", "generic report failure"),
    "GlobalExceptionHandler": ("infrastructure", "RFC 7807 handler"),
    "ErrorDisclosurePolicy": ("infrastructure", "RFC 7807 disclosure policy"),
    "BankConflictException": ("bank", "bank problem codes"),
    "BackendApplication": ("infrastructure", "Spring Boot entry point"),
    "LiveSyncChangedRequest": ("livesync", "app live-sync relay payload"),
    "PingResponse": ("admin", "liveness probe payload of SystemController"),
    "SystemController": ("admin", "liveness/version probe"),
    "StaffelMembershipResolver": ("orgunit", "resolves SQUADRON memberships to squadrons"),
    "OrgUnitLabels": ("orgunit", "org-unit audit label"),
    "Department": ("orgunit", "Bereich brand colour"),
    "MembershipRole": ("orgunit", "rank on an OrgUnitMembership"),
    "BereichLeadershipRole": ("leadership", "Bereichsleitung role of an appointment"),
    "GrandAdmiralRequest": ("leadership", "Grand Admiral appointment (REQ-ORG-021)"),
    "AreaLeadershipDto": ("orgchart", "org chart tier"),
    "FinanceType": ("mission", "mission finance entry type"),
    "PayoutPreference": ("mission", "participant payout preference"),
    "RefineryMissionProfitAggregate": ("refinery", "refinery profit per mission projection"),
    "FinanceEntryAggregate": ("mission", "mission finance aggregate projection"),
    "MissionFinanceGroupAggregate": ("mission", "mission finance aggregate projection"),
    "ProfitCalculationController": ("catalogue", "trade margin over prices and ship types"),
    "ProfitCalculationService": ("catalogue", "trade margin over prices and ship types"),
    "ProfitCalculationDto": ("catalogue", "trade margin row"),
    "MaterialMatrixItemDto": ("catalogue", "material price matrix row"),
    "BlueprintOutputNameOverrides": ("catalogue", "recipe sync corrections (SC Wiki)"),
    "BlueprintRepository": ("catalogue", "recipe graph repository"),
    "BlueprintMapper": ("catalogue", "recipe graph mapper"),
    "BlueprintService": ("catalogue", "admin read of the synced recipe graph"),
    "BlueprintController": ("catalogue", "admin read of the synced recipe graph"),
    "BlueprintDto": ("catalogue", "recipe graph DTO"),
    "BlueprintDismantleReturnDto": ("catalogue", "recipe graph DTO"),
    "BlueprintSummaryPropertyDto": ("catalogue", "recipe graph DTO"),
    "BlueprintIdNameRow": ("catalogue", "recipe repository projection"),
    "BlueprintProductRow": ("catalogue", "recipe repository projection"),
    "BlueprintReferenceDto": ("catalogue", "recipe reference DTO"),
    "BlueprintIngredientKind": ("catalogue", "recipe graph enum"),
    "UexCategory": ("catalogue", "UEX category reference row"),
    "JobOrderAllocationDto": ("inventory", "job-order slice of an inventory entry (REQ-INV-027), mapped by InventoryItemMapper"),
    "BlueprintPackTags": ("blueprint", "log-name clean-up used only by BlueprintImportService"),
    "BasetoolErrorController": ("infrastructure", "container error dispatch as RFC 7807"),
    "ConnectedInstallationDto": ("exchange", "connected-apps installation row"),
    "CounterpartySnapshot": ("bank", "bank counterparty snapshot (REQ-BANK-044)"),
    "ApiClientMetricsProperties": ("infrastructure", "client-id metric label allow-list"),
    "AppProblemProperties": ("infrastructure", "RFC 7807 base URI"),
    "ProblemResponseFactory": ("infrastructure", "RFC 7807 body builder"),
    "RateLimitProperties": ("infrastructure", "rate-limit filter configuration"),
    "RequestBodyLimitProperties": ("infrastructure", "request-body limit configuration"),
}

AMBIGUOUS = {
    "AccessGateService": "holds gates for six aggregates; classified to the access refinement, belongs split into each domain",
    "OwnerScopeService": "scope facade plus per-aggregate gates; access refinement, split candidate",
    "CustomJwtGrantedAuthoritiesConverter": "authorities need identity (sync) and orgunit (cascade); access refinement rather than identity",
    "MeController": "composite /me endpoints serve identity, orgunit, notification and inventory flags",
    "UserController": "a quarter of its endpoints are orgunit memberships, bank user search, dashboard read marker",
    "SquadronRoleController": "rank appointment = membership-row write; leadership vs orgunit",
    "OrgRoleManagementSecurityService": "appointment ladder gates; leadership vs orgunit",
    "BereichLeadershipRole": "DTO enum used by orgunit's controller and service; leadership vs orgunit",
    "GrandAdmiralRequest": "appointment payload on OrgHierarchyController; leadership vs orgunit",
    "Blueprint": "synced recipe graph (SC Wiki/P4K) used by blueprint, joborder, exchange; catalogue vs blueprint",
    "BlueprintRepository": "recipe repository used by blueprint, joborder, inventory, exchange; catalogue vs blueprint",
    "BlueprintProductService": "product search over recipes that also reads personal blueprints; blueprint vs catalogue",
    "BlueprintNameNormalizer": "product-key normaliser used by the recipe sync and the import; catalogue vs blueprint",
    "BlueprintFuzzyMatcher": "generic matcher used by blueprint import and refinery import; blueprint vs shared",
    "MaterialCollectionController": "job-order page that reads only inventory; joborder vs inventory",
    "JobOrderItemStockController": "job-order panel that reads only inventory; joborder vs inventory",
    "MaterialCollectionEntryDto": "produced by inventory for the job-order view; inventory vs joborder",
    "JobOrderAllocationDto": "earmark slice of an inventory row; inventory vs joborder",
    "MissionAllocationDto": "earmark slice of an inventory row; inventory vs mission",
    "AllocationReductions": "earmark reduction plans used by inventory and joborder writes",
    "QualityRequirement": "quality floor used by job-order buckets, claims and exchange demand; joborder vs catalogue",
    "QuantityType": "measurement unit enum used by six domains; catalogue vs shared-kernel",
    "FinanceType": "mission finance entry type also used by inventory sale and operation payout",
    "PayoutPreference": "field of User (identity) typed with a mission enum; mission vs identity",
    "RefineryYield": "UEX yield matrix read by refinery orders; catalogue vs refinery",
    "ShipTypeMatcher": "ship-type resolver for the hangar import, also used by exchange resolve; hangar vs catalogue",
    "UexLocationController": "UEX location typeahead served by the personal-inventory service; personalinventory vs catalogue",
    "ProfitCalculationService": "trade margins over prices and ship types; catalogue vs an own trade domain",
    "MailService": "transactional mail seam (REQ-NOTIF-013) used only by identity; notification vs infrastructure",
    "ChangeSourceTransactionManager": "installed as the app-wide transaction manager for the exchange change feed; exchange vs infrastructure",
    "PendingApprovalAccessFilter": "security filter enforcing an identity rule; identity vs infrastructure",
    "TermsAcceptanceAccessFilter": "security filter enforcing an identity rule; identity vs infrastructure",
    "OrgUnitRef": "org-unit reference in the notification event contract; notification vs orgunit",
    "HandleAnonymisation": "sentinel value used by audit, bank and joborder reports; identity vs shared-kernel",
    "Department": "Bereich brand colour used by orgunit, orgchart and bank dashboards",
    "KommandoGroup": "Staffel sub-structure drawn by orgchart; orgunit vs orgchart",
    "DatabaseActingMemberAuthorities": "acting-member authorities for the exchange relay; exchange vs access",
    "DataInitializer": "seeds roles (identity) and the IRIDIUM squadron (orgunit); composition root",
    "SecurityConfig": "wires identity and exchange filters into the chain; composition root",
    "BusinessMetricsCollector": "queue gauges over seven domains' repositories; composition root",
    "UserMapper": "identity mapper that derives Staffel data from orgunit memberships",
    "UserDtoRedaction": "identity redaction helper that also redacts a job-order DTO",
}

PREFIX_RULES = [
    (r"^OrgUnitBank", "bank"),
    (r"^(Bank|CreateBank|CancelBank|ConfirmBank|RegisterBank|RejectBank|RenameBank|ReverseBank|SetBank|UpdateBank|SetCartel|OrgUnitBalanceTarget)", "bank"),
    (r"^(Exchange|ConnectedApp|FirstPartyClientIds|RedisExchange|DisabledExchange|KnownExchangeClients|ActingMember|IngestGateway)", "exchange"),
    (r"^(MaterialExchange|MaterialRequest|MaterialItemRequest)", "materialexchange"),
    (r"^(MaterialClaim|ClaimBucket|ClaimDto|CreateClaim|MaterialDemand)", "joborder"),
    (r"^(JobOrder|CreateJobOrder|UpdateJobOrder|HandoverReport)", "joborder"),
    (r"^PersonalInventory", "personalinventory"),
    (r"^(Inventory|BulkCheckout|BulkRebook|BulkStolenMark|BulkOrgUnitChange|AggregatedInventory|GroupedInventory|AllocationReduction|StockViewerAccess|OwnedStockSlice)", "inventory"),
    (r"^(PersonalBlueprint|DefaultBlueprint|BlueprintImport|BlueprintExport|BlueprintCraftability|Craftability|BlueprintOverview|BlueprintProduct|BlueprintUploadPreview|BlueprintFuzzy|BlueprintOwner|BlueprintModifierMath|BlueprintVariant|BlueprintSource|BlueprintExternalAlias)", "blueprint"),
    (r"^(Blueprint)", "catalogue"),
    (r"^(Mission|AddCrew|AddExternalParticipant|AddParticipantById|AddUnit|JoinMission|UpdateCrew|UpdateParticipant|UpdatePayoutPreference|UpdateUnit|AddCustomFrequency|AddFrequency|UpdateCustomFrequency|CreateMission|PatchMission|ReorderMission|SetPartyLead|ToggleMissionStep|UpdateMission|AddMission|ParticipantTargetResolver)", "mission"),
    (r"^Operation", "operation"),
    (r"^(Refinery|ImportIssue|ImportSuggestion)", "refinery"),
    (r"^Refining", "catalogue"),
    (r"^(Hangar|Ship(?!Type)|Fleet|Starjump|Shiplist|SquadronShip|SetHomeLocation)", "hangar"),
    (r"^(Notification|LocalNotification|RedisNotification|RuleEvaluation|RecipientResolution|SelectorKind|OrgRelativeRole|Mail|SmtpMail)", "notification"),
    (r"^Audit", "audit"),
    (r"^(Promotion|RankRequirement|MemberEvaluation)", "promotion"),
    (r"^(OrgChart|BereichChart|CommandChart|OlChart|SpecialCommandChart|SquadronChart)", "orgchart"),
    (r"^(Leitung|OrgRoleManagement|AddBereichLeader|AddOlMember|SquadronRole|AssignSquadronRank)", "leadership"),
    (r"^(OrgUnit|Squadron|SpecialCommand|Bereich|Organisationsleitung|KommandoGroup|CreateKommandoGroup|UpdateKommandoGroup|OrgHierarchy|Membership)", "orgunit"),
    (r"^(User|Role|Registration|PendingRegistration|ApproveRegistration|RejectRegistration|ReopenRegistration|LinkRegistration|MyRegistration|Discord|Keycloak|Terms|DeletionRequest|CreateDeletionRequest|DecideDeletionRequest|DataExport|AdminDataExport|AccountDeletion|AccountConsolidation|ConsolidateAccount|MergeAccount|Handle|PersonSearch|AdminPersonSearch|AdminTerms|AdminDeletion|RejectedRegistration|ApprovalDecision|ApprovalStatus|AdminController$)", "identity"),
    (r"^AdminPersonalInventory", "personalinventory"),
    (r"^(AdminPersonalBlueprint|AdminDefaultBlueprint)", "blueprint"),
    (r"^(AdminExchange)", "exchange"),
    (r"^(AdminP4k)", "catalogue"),
    (r"^Announcement", "dashboard"),
    (r"^(SystemSetting|AppVersionPolicy|AndroidClient)", "admin"),
    (r"^(LiveSync|LocalLiveSync|RedisLiveSync)", "livesync"),
    (r"^(City|Location|StarSystem|SpaceStation|Outpost|Poi|Terminal|Planet|Moon|Orbit|Faction|Jurisdiction|Material|Manufacturer|ShipType|JobType|FrequencyType|GameItem|Uex|ScWiki|P4k|Sync|ExternalSync|MasterData|EvictAllMaterialCaches|QuantityType|LookupTable|PairKeyRef|StalePriceSweep|CachedEntityGraphs)", "catalogue"),
]

INFRA_LAYERS = {"config", "filter", "logging", "metrics", "health", "interceptor", "annotation", "web", "(root)"}

CATALOGUE_SUB = [
    (r"^(Blueprint|ScWikiBlueprint)", "recipe"),
    (r"^(City|Location|StarSystem|SpaceStation|Outpost|Poi|Terminal|Planet|Moon|Orbit|Faction|Jurisdiction|UexCity|UexMoon|UexOrbit|UexOutpost|UexPlanet|UexPoi|UexSpaceStation|UexStarSystem|UexTerminal|UexFaction|UexJurisdiction|UexUniverse)", "universe"),
    (r"^(Material|UexCommodity|ScWikiCommodity|QuantityType|ValidQuantity|QuantityAware|StalePrice|ProfitCalculation|EvictAllMaterial)", "material"),
    (r"^(GameItem|UexItem|ScWikiItem|UexCategory)", "item"),
    (r"^(ShipType|Manufacturer|UexVehicle|UexManufacturer|ScWikiVehicle|ScWikiManufacturer|UexCompany)", "ship"),
    (r"^(JobType|FrequencyType|Refining|RefineryYield|UexRefin)", "reference"),
]

def layer_of(row):
    return row["layer"]

def decide(row):
    name = row["simple"]
    if name in EXPLICIT:
        dom, why = EXPLICIT[name]
        return dom, "explicit: " + why
    if row["layer"] == "dto-external":
        if name.startswith("ScWikiBlueprint"):
            return "catalogue", "external DTO (SC Wiki recipe feed)"
        return "catalogue", "external DTO package dto.%s (import feed)" % row["subpackage"].split(".")[-1]
    if row["subpackage"] in ("integration", "integration.scwiki"):
        return "catalogue", "outbound catalogue client"
    base = name[:-4] if name.endswith("Impl") and not name.endswith("MapperImpl") else name
    for rx, dom in PREFIX_RULES:
        if re.match(rx, base):
            return dom, "name prefix /%s/" % rx.strip("^")[:60]
    if row["layer"] in INFRA_LAYERS:
        return "infrastructure", "layer %s" % row["layer"]
    return None, None

def main():
    inv = common.load_inventory()
    _raw, edges = common.load_edges()
    out, _inc = common.adjacency(edges)
    decided = {}
    for fqcn, row in inv.items():
        decided[fqcn] = decide(row)
    for _ in range(3):
        for fqcn, row in inv.items():
            if decided[fqcn][0] is not None and not decided[fqcn][1].startswith("dependency majority"):
                continue
            votes = Counter()
            for dst in out.get(fqcn, ()):
                d = decided.get(dst, (None,))[0]
                if d and d not in ("shared-kernel", "infrastructure", "UNDECIDED"):
                    w = 3 if inv[dst]["layer"] == "model" else 1
                    votes[d] += w
            if votes:
                dom, n = votes.most_common(1)[0]
                decided[fqcn] = (dom, "dependency majority (%s: weight %d of %d)" % (dom, n, sum(votes.values())))
            else:
                decided[fqcn] = ("UNDECIDED", "no rule and no domain dependency")
    with open(common.CLASSES_CSV, "w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["fqcn", "simple", "package", "layer", "kind", "stereotypes", "domain", "subdomain", "rule", "ambiguous", "loc", "ncloc", "public_methods", "path"])
        for fqcn in sorted(inv):
            row = inv[fqcn]
            dom, why = decided[fqcn]
            sub = ""
            if dom == "catalogue":
                for rx, s in CATALOGUE_SUB:
                    if re.match(rx, row["simple"]):
                        sub = s
                        break
                if not sub:
                    sub = "import" if re.match(r"^(Uex|ScWiki|P4k|Sync|ExternalSync|MasterData|LookupTable|PairKeyRef)", row["simple"]) or row["layer"] == "dto-external" or row["subpackage"].startswith("integration") else "other"
            w.writerow([fqcn, row["simple"], row["package"], row["layer"], row["kind"], "|".join(row["stereotypes"]), dom, sub, why, AMBIGUOUS.get(row["simple"], ""), row["loc"], row["ncloc"], row["public_methods"], row["path"]])
    cnt = Counter(d for d, _ in decided.values())
    print("classes:", len(decided))
    for d, n in sorted(cnt.items(), key=lambda x: -x[1]):
        print("  %-18s %4d" % (d, n))
    print("\nDecided by dependency majority or undecided:")
    for fqcn in sorted(inv):
        dom, why = decided[fqcn]
        if why.startswith("dependency") or dom == "UNDECIDED":
            print("  %-45s %-10s %-16s %s" % (inv[fqcn]["simple"], inv[fqcn]["layer"], dom, why))

if __name__ == "__main__":
    main()
