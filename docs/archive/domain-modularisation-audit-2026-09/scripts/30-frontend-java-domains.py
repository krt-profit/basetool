"""Domain map for every frontend main class, plus a cross-domain coupling analysis over
jdeps-frontend.txt (inner classes folded into their outer class). Read-only.

Usage: python 30-frontend-java-domains.py
"""

import io
import json
import os
import re
import sys
from collections import Counter, defaultdict

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
HERE = os.path.dirname(os.path.abspath(__file__))
BASE = "de.greluc.krt.profit.basetool.frontend."

def words(s):
    return s.split()

DTO = {
    "audit": words("AuditEventDto AuditRowView"),
    "bank": words(
        "BankAccountDetailDto BankAccountDto BankAccountRefDto BankApprovalLimitUserDto "
        "BankApprovalLimitsDto BankAuditEventDto BankBalancePointDto BankBalanceSeriesDto BankBookingDto "
        "BankBookingRequestDto BankCapabilitiesDto BankDashboardAccountDto BankDashboardDto "
        "BankDashboardTotalsDto BankGrantDto BankHolderBookingDto BankHolderDto BankTransferFeeRateDto "
        "BankWipeResetResultDto ConsolidateAccountRequest MergeAccountRequest OrgUnitBankAccountDetailDto "
        "OrgUnitBankAccountSettingsDto OrgUnitBankBalanceDto OrgUnitBankViewUserDto"),
    "blueprint": words(
        "BlueprintCraftabilityDto BlueprintDismantleReturnDto BlueprintDto BlueprintImportApplyRequest "
        "BlueprintImportEntryDto BlueprintImportPreviewDto BlueprintImportResolutionDto "
        "BlueprintImportResultDto BlueprintImportStatus BlueprintImportSuggestionDto "
        "BlueprintOverviewEntryDto BlueprintOverviewOwnerDto BlueprintProductDto BlueprintReferenceDto "
        "BlueprintRequirementGroupDto BlueprintRequirementIngredientDto BlueprintRequirementModifierDto "
        "BlueprintRequirementModifierSegmentDto BlueprintSummaryPropertyDto CraftabilityGroupDto "
        "CraftabilityMaterialDto DefaultBlueprintAddResultDto DefaultBlueprintAddSelectionRequest "
        "DefaultBlueprintCreateRequest DefaultBlueprintDto PersonalBlueprintBatchCreateRequest "
        "PersonalBlueprintBatchResultDto PersonalBlueprintBulkDeleteResultDto PersonalBlueprintDto "
        "PersonalBlueprintRecipeDto PersonalBlueprintUpdateRequest"),
    "catalogue": words(
        "CityDto DerivedMaterialDto GameItemReferenceDto ItemDerivationDto JobTypeDto LocationDto "
        "LocationReferenceDto ManufacturerDto MaterialCategoryDto MaterialCreateAjaxRequest MaterialDto "
        "MaterialExternalAliasDto MaterialExternalAliasWriteRequest MaterialMatrixItemDto MaterialPriceDto "
        "MaterialPriceOverviewDto MaterialReferenceDto MaterialUpdateAjaxRequest MatrixGridDto OutpostDto "
        "P4kImportJobDto P4kImportResultDto PoiDto RefiningMethodDto ShipTypeDto SpaceStationDto "
        "StarSystemDto SubAssemblySuggestionDto SyncReportDto SyncReportPurgeResultDto TerminalDto "
        "UexLocationDto"),
    "exchange": words(
        "ConnectedAppActivityDto ConnectedAppDto ConnectedAppMassChangeRequestDto "
        "ConnectedAppMassChangeResultDto ConnectedInstallationDto ExchangeBulkUndoInstallationDto "
        "ExchangeBulkUndoPreviewDto ExchangeBulkUndoRequest ExchangeBulkUndoRunDetailDto "
        "ExchangeBulkUndoRunDto ExchangeClientCreateRequest ExchangeClientDto ExchangeClientStatusRequest "
        "ExchangeClientUpdateRequest ExchangeClientUsageDto ExchangeSettingsDto "
        "ExchangeSettingsUpdateRequest ExchangeUndoRequestDto ExchangeUndoResultDto"),
    "hangar": words("SetHomeLocationRequestDto ShipDto ShipRequestDto SquadronShipDetailDto "
                    "SquadronShipOverviewDto"),
    "identity": words(
        "AdminDeletionRequestDto ApproveRegistrationRequest LinkRegistrationRequest MyRsiHandleResponse "
        "PendingRegistrationDto PersonSearchHitDto PersonSearchResultDto RegistrationStatusDto "
        "RejectRegistrationRequest ReopenRegistrationRequest TermsAcceptanceStatusDto TermsClauseDto "
        "TermsDocumentDto TermsSectionDto TermsStatusDto UserAttributesUpdateDto UserDto "
        "UserReferenceDto UserSyncResultDto"),
    "inventory": words(
        "AggregatedInventoryDto AllocationReductionDto BulkCheckoutRequest BulkOrgUnitChangeRequest "
        "BulkOrgUnitChangeResultDto BulkRebookMode BulkRebookRequest BulkRebookResultDto "
        "BulkStolenMarkRequest BulkStolenMarkResultDto CheckoutType GroupedInventoryDto "
        "InventoryAllocationDimension InventoryAllocationInput InventoryAllocationWriteDto "
        "InventoryGameItemReferenceDto InventoryItemBookOutDto InventoryItemCreateDto InventoryItemDto "
        "InventoryItemNoteUpdateRequest InventoryItemOrgUnitChangeDto InventoryItemPersonalRebookDto "
        "InventoryItemStolenMarkDto InventoryStackDto"),
    "joborder": words(
        "AggregatedMaterialDto ClaimBucketDto ClaimDto CreateClaimDto CreateJobOrderDto "
        "CreateJobOrderItemLineDto CreateJobOrderItemMaterialDto CreateJobOrderItemRequestDto "
        "CreateJobOrderMaterialDto JobOrderAllocationDto JobOrderAssigneeDto JobOrderBlueprintOwnerDto "
        "JobOrderDto JobOrderGameItemNeedDto JobOrderHandoverCreateDto JobOrderHandoverDto "
        "JobOrderHandoverItemCreateDto JobOrderHandoverItemDto JobOrderItemBlueprintOwnersDto "
        "JobOrderItemDto JobOrderItemHandoverCreateDto JobOrderItemHandoverDto "
        "JobOrderItemHandoverEntryCreateDto JobOrderItemHandoverEntryDto JobOrderItemMaterialDto "
        "JobOrderItemProductionConsumptionDto JobOrderItemProductionCreateDto JobOrderItemStockEntryDto "
        "JobOrderItemStockGroupDto JobOrderMaterialDto JobOrderMaterialNeedDto JobOrderReferenceDto "
        "JobOrderRequiredBlueprintDto MaterialCollectionEntryDto MaterialDemandGroupDto "
        "MaterialDemandOrderShareDto MaterialDemandOverviewDto MaterialDemandRowDto "
        "UpdateDeliveredRequest UpdateJobOrderBlueprintCountingDto UpdateJobOrderStatusDto"),
    "leadership": words("AreaLeadershipDto KommandoGroupDto LeitungMemberDto LeitungUnitDto LeitungViewDto"),
    "materialexchange": words("MaterialExchangeCountsDto MaterialExchangeOfferDto "
                              "MaterialExchangeReleasableItemDto MaterialRequestDto"),
    "mission": words(
        "CreateMissionRequest FinanceType MissionActualTimeUpdateRequest MissionAllocationDto "
        "MissionCrewDto MissionDto MissionFinanceEntryCreateDto MissionFinanceEntryDto "
        "MissionFinanceEntryUpdateDto MissionFinanceSummaryDto MissionFinanceTotalsDto "
        "MissionFrequencyDto MissionListDto MissionObjectiveDto MissionObjectiveKind "
        "MissionParticipantDto MissionReferenceDto MissionStepDto MissionUnitDto "
        "UpdatePayoutPreferenceRequest"),
    "notification": words(
        "NotificationBulkResultDto NotificationCountResponse NotificationDto NotificationPageSliceDto "
        "NotificationRuleDto NotificationRuleSelectorDto NotificationRuleSelectorWriteRequest "
        "NotificationRuleWriteRequest NotificationViewDto"),
    "operation": words(
        "OperationDto OperationFinanceSummaryDto OperationMissionFinanceDto OperationPayoutDto "
        "OperationPayoutStatusDto OperationPayoutStatusUpdateDto OperationPayoutSummaryDto "
        "OperationReferenceDto"),
    "orgchart": words("BereichChartDto CommandChartDto OlChartDto OrgChartDto OrgChartNodeDto "
                      "SpecialCommandChartDto SquadronChartDto"),
    "orgunit": words(
        "BereichCreateRequest MembershipDeltaRequest MembershipDeltaResponse OrgUnitKind "
        "OrgUnitMembershipDto OrgUnitMembershipOptionDto OrgUnitNodeDto OrgUnitParentUpdateRequest "
        "OrgUnitReferenceDto OrganisationsleitungCreateRequest SpecialCommandDto SquadronDto "
        "SquadronReferenceDto"),
    "personalinventory": words("PersonalInventoryItemCreateRequest PersonalInventoryItemDto "
                               "PersonalInventoryItemUpdateRequest PersonalInventoryLocationType"),
    "promotion": words("MemberEvaluationDto PromotionCategoryDto PromotionEligibilityDto "
                       "PromotionLevelContentDto PromotionRequirementCheckDto PromotionTopicDto "
                       "RankRequirementDto"),
    "refinery": words("ImportIssueCode ImportIssueDto ImportIssueSeverity ImportSuggestionDto "
                      "RefineryGoodDto RefineryImportDraftDto RefineryOrderDto RefineryOrderListDto "
                      "RefineryOrderStatus RefineryOrderStoreDto RefineryOrderStoreItemDto"),
    "settings": words("SystemSettingDto SystemSettingUpdateDto"),
    "kernel": words("PageResponse BackendEnumAsString HandoffKind StagedHandoff"),
}

FORM = {
    "mission": words("CrewForm MissionFinanceEntryForm MissionForm ParticipantForm UnitForm"),
    "operation": words("OperationForm"),
    "catalogue": words("FrequencyTypeForm JobTypeForm ManufacturerForm ShipTypeForm"),
    "inventory": words("InventoryBookOutForm InventoryForm"),
    "joborder": words("JobOrderForm JobOrderHandoverForm JobOrderItemForm JobOrderItemHandoverForm"),
    "identity": words("MemberEditForm ProfileBlueprintSharingForm ProfileDescriptionForm "
                      "ProfilePayoutPreferenceForm ProfileRsiHandleForm"),
    "orgunit": words("MembershipFlagsForm SpecialCommandForm SquadronForm"),
    "personalinventory": words("PersonalInventoryForm"),
    "refinery": words("RefineryGoodForm RefineryOrderForm RefineryOrderStoreForm RefineryOrderStoreItemForm"),
    "hangar": words("ShipForm"),
}

CONTROLLER = {
    "mission": words("MissionPageController MissionWriteController MissionFinancePageController "
                     "MissionDetailModelBuilder"),
    "operation": words("OperationPageController"),
    "joborder": words("JobOrderPageController JobOrderWriteController JobOrderHandoverReportProxyController "
                      "JobOrderMaterialDemandPageController ItemCollectionPageController "
                      "MaterialCollectionPageController"),
    "inventory": words("InventoryPageController InventoryWriteController InventoryDeleteAllProxyController "
                       "InventoryOrgUnitChangeProxyController InventoryStolenMarkProxyController"),
    "personalinventory": words("PersonalInventoryPageController AdminPersonalInventoryPageController"),
    "blueprint": words("PersonalInventoryBlueprintsPageController PersonalBlueprintImportProxyController "
                       "BlueprintOverviewPageController AdminBlueprintsPageController "
                       "AdminDefaultBlueprintsPageController AdminPersonalBlueprintsPageController"),
    "hangar": words("HangarPageController HangarImportProxyController HangarDeleteAllProxyController"),
    "materialexchange": words("MaterialboersePageController"),
    "refinery": words("RefineryOrderPageController RefineryOrderWriteController RefineryImportProxyController"),
    "bank": words("BankPageController BankProxyController BankReportProxyController BankGrantsPageController "
                  "BankManagePageController BankRequestQueuePageController AdminBankPageController "
                  "OrgUnitBankPageController OrgUnitBankProxyController BankAccountDetailSupport "
                  "BankAccountOrder BankBalanceChart BankDashboardViewAssembler BankSparkline"),
    "notification": words("NotificationPageController AdminNotificationRulePageController"),
    "catalogue": words("MaterialsPageController MaterialProxyController ProfitCalculationPageController "
                       "CatalogSearchController AdminMaterialsPageController AdminMaterialAliasesPageController "
                       "AdminLocationsPageController AdminUexPageController AdminP4kImportPageController "
                       "AdminSyncReportsPageController AdminMissionDataPageController PlanetColorResolver "
                       "ShipDataPageController"),
    "audit": words("AdminAuditLogPageController AuditReportProxyController"),
    "promotion": words("PromotionPageController PromotionProxyController"),
    "orgchart": words("OrgChartPageController"),
    "leadership": words("LeitungPageController"),
    "orgunit": words("AdminOrgStructurePageController AdminSpecialCommandsPageController "
                     "SpecialCommandMembersPageController SpecialCommandAdminProxyController "
                     "SquadronAdminProxyController MeFrontendController"),
    "identity": words("ProfileController ProfileRsiHandleProxyController DeletionRequestProxyController "
                      "DataExportProxyController MemberManagementController UserProxyController "
                      "AdminDiscordRegistrationsPageController AdminDeletionRequestsPageController "
                      "AdminPersonSearchPageController PendingApprovalPageController "
                      "TermsAcceptancePageController TermsController AdminTermsPageController"),
    "dashboard": words("HomeController AdminAnnouncementPageController"),
    "settings": words("AdminSettingsPageController"),
    "exchange": words("AdminExchangeClientsPageController AdminExchangeClientsRelayController "
                      "ConnectedAppsPageController ConnectedAppsRelayController "
                      "ConnectedAppsConfirmController ConnectedAppsConfirmRelayController"),
    "shell": words("CsrfTokenController ClientErrorReportController AppLinkController AssetLinksController "
                   "WebAppManifestController ImpressumController PrivacyController OssLicensesController "
                   "ScLinksPageController"),
}

MODEL_ROOT = {"mission": ["PayoutPreference"], "shell": ["ScLink", "ScLinkCategory"]}

def build_map(classes):
    dmap = {}
    for dom, names in DTO.items():
        for n in names:
            dmap["model.dto." + n] = dom
    for dom, names in FORM.items():
        for n in names:
            dmap["model.form." + n] = dom
    for dom, names in CONTROLLER.items():
        for n in names:
            dmap["controller." + n] = dom
    for dom, names in MODEL_ROOT.items():
        for n in names:
            dmap["model." + n] = dom
    for c in classes:
        key = (c["package"] + "." + c["name"]) if c["package"] != "(root)" else c["name"]
        if key in dmap:
            continue
        pkg = c["package"]
        if pkg in ("service", "support", "config", "websocket", "logging", "exception", "health",
                   "metrics", "validation", "view", "oss", "(root)"):
            dmap[key] = "kernel:" + pkg
        else:
            dmap[key] = "UNMAPPED"
    return dmap

def main():
    data = json.load(open(os.path.join(HERE, "30-frontend-java-scan.json"), encoding="utf-8"))
    classes = data["classes"]
    dmap = build_map(classes)
    unmapped = [k for k, v in dmap.items() if v == "UNMAPPED"]
    print("classes:", len(classes), "unmapped:", unmapped)
    for pkgname, table in (("model.dto", DTO), ("model.form", FORM), ("controller", CONTROLLER)):
        listed = [n for names in table.values() for n in names]
        dupes = [n for n, k in Counter(listed).items() if k > 1]
        present = {c["name"] for c in classes if c["package"] == pkgname}
        print(pkgname, "listed", len(listed), "present", len(present), "dupes", dupes,
              "missing-from-map", sorted(present - set(listed)), "stale", sorted(set(listed) - present))

    per_domain = defaultdict(Counter)
    loc = defaultdict(Counter)
    for c in classes:
        key = (c["package"] + "." + c["name"]) if c["package"] != "(root)" else c["name"]
        dom = dmap[key]
        pk = c["package"]
        cat = {"model.dto": "dto", "model.form": "form"}.get(pk, pk)
        if pk == "controller":
            a = c["annotations"]
            cat = "rest-ctrl" if a["RestController"] else ("view-ctrl" if a["Controller"] else "ctrl-helper")
        per_domain[dom][cat] += 1
        loc[dom][cat] += c["lines"]
    print()
    print(f'{"domain":20s} {"view":>5s} {"rest":>5s} {"help":>5s} {"dto":>5s} {"form":>5s} {"ctrlLOC":>8s} {"dtoLOC":>7s}')
    tot = Counter()
    for dom in sorted(per_domain, key=lambda d: (d.startswith("kernel"), d)):
        c = per_domain[dom]
        ctrl_loc = loc[dom]["view-ctrl"] + loc[dom]["rest-ctrl"] + loc[dom]["ctrl-helper"]
        print(f'{dom:20s} {c["view-ctrl"]:5d} {c["rest-ctrl"]:5d} {c["ctrl-helper"]:5d} {c["dto"]:5d} '
              f'{c["form"]:5d} {ctrl_loc:8d} {loc[dom]["dto"] + loc[dom]["form"]:7d}  other={ {k: v for k, v in c.items() if k not in ("view-ctrl", "rest-ctrl", "ctrl-helper", "dto", "form")} }')
        tot.update(c)
    print("totals", dict(tot))

    edges = set()
    for line in open(os.path.join(HERE, "jdeps-frontend.txt"), encoding="utf-8", errors="replace"):
        m = re.match(r"\s+(\S+)\s+->\s+(\S+)\s+", line)
        if not m:
            continue
        a, b = m.group(1), m.group(2)
        if not (a.startswith(BASE) and b.startswith(BASE)):
            continue
        a = a[len(BASE):].split("$")[0]
        b = b[len(BASE):].split("$")[0]
        if a != b:
            edges.add((a, b))
    print()
    print("folded frontend-internal edges:", len(edges))

    def dom_of(k):
        return dmap.get(k, "UNMAPPED:" + k)

    by_src = defaultdict(set)
    for a, b in edges:
        by_src[a].add(b)
    multi = []
    for a, targets in by_src.items():
        da = dom_of(a)
        if not a.startswith("controller.") and not a.startswith("config.") and not a.startswith("service.") \
                and not a.startswith("websocket.") and not a.startswith("support."):
            continue
        doms = Counter()
        for b in targets:
            db = dom_of(b)
            if db.startswith("kernel") or db == "shell":
                continue
            doms[db] += 1
        foreign = {d: n for d, n in doms.items() if d != da}
        if foreign:
            multi.append((a, da, dict(doms), foreign))
    multi.sort(key=lambda x: (-len(x[3]), x[0]))
    print("classes (controller/config/service/websocket/support) depending on another domain's types:",
          len(multi))
    for a, da, doms, foreign in multi:
        print(f"  {a:60s} [{da}] foreign={foreign}")

    dd = Counter()
    for a, b in edges:
        da, db = dom_of(a), dom_of(b)
        if da != db and not da.startswith("kernel") and not db.startswith("kernel") and da != "shell" and db != "shell":
            dd[(da, db)] += 1
    print()
    print("domain -> domain class edges (non-kernel):", sum(dd.values()))
    for (a, b), n in dd.most_common():
        print(f"  {a:18s} -> {b:18s} {n}")

    kernel_to_domain = Counter()
    k2d_detail = defaultdict(set)
    for a, b in edges:
        da, db = dom_of(a), dom_of(b)
        if da.startswith("kernel") and not db.startswith("kernel"):
            kernel_to_domain[(da, db)] += 1
            k2d_detail[(da, db)].add(a.split(".")[-1] + "->" + b.split(".")[-1])
    print()
    print("kernel -> domain edges:", sum(kernel_to_domain.values()))
    for (a, b), n in kernel_to_domain.most_common():
        print(f"  {a:22s} -> {b:18s} {n}  {sorted(k2d_detail[(a, b)])[:8]}")

    dto_cross = Counter()
    dto_detail = defaultdict(set)
    for a, b in edges:
        if a.startswith("model.") and b.startswith("model."):
            da, db = dom_of(a), dom_of(b)
            if da != db and db != "kernel":
                dto_cross[(da, db)] += 1
                dto_detail[(da, db)].add(a.split(".")[-1] + "->" + b.split(".")[-1])
    print()
    print("model -> model cross-domain edges:", sum(dto_cross.values()))
    for (a, b), n in dto_cross.most_common():
        print(f"  {a:18s} -> {b:18s} {n}  {sorted(dto_detail[(a, b)])[:6]}")

    ctrl_ctrl = [(a, b) for a, b in edges if a.startswith("controller.") and b.startswith("controller.")]
    print()
    print("controller -> controller edges:", len(ctrl_ctrl))
    for a, b in sorted(ctrl_ctrl):
        print(f"  {a.split('.')[-1]:45s} -> {b.split('.')[-1]:40s} [{dom_of(a)} -> {dom_of(b)}]")

    json.dump({"domain_map": dmap}, open(os.path.join(HERE, "30-frontend-java-domainmap.json"), "w"), indent=1)

if __name__ == "__main__":
    main()
