"""Assign every controller and every operation to a business domain and measure the API per domain.

Controller -> owning domain is a manual map (documented below). Each operation is additionally
classified by the domain its path *content* belongs to (keyword rules), so operations that sit in
one domain's controller or prefix while serving another domain are listed.
"""
import csv
import json
import os
import re
import sys
from collections import Counter, defaultdict

OUT = r"$SCRATCHPAD"
REPO = r"$REPO"

CONTROLLER_DOMAIN = {
    "MissionController": "mission", "MissionFinanceEntryController": "mission",
    "OperationController": "operation",
    "JobOrderController": "joborder", "JobOrderItemStockController": "joborder",
    "MaterialClaimController": "joborder", "MaterialCollectionController": "joborder",
    "InventoryItemController": "inventory",
    "PersonalInventoryController": "personalinventory", "AdminPersonalInventoryController": "personalinventory",
    "PersonalBlueprintController": "blueprint", "PersonalBlueprintOverviewController": "blueprint",
    "BlueprintController": "blueprint", "BlueprintProductController": "blueprint",
    "AdminPersonalBlueprintController": "blueprint", "AdminDefaultBlueprintController": "blueprint",
    "HangarController": "hangar",
    "MaterialExchangeController": "materialexchange", "MaterialRequestController": "materialexchange",
    "RefineryOrderController": "refinery", "RefineryImportController": "refinery",
    "BankAccountController": "bank", "BankAdminController": "bank", "BankBookingController": "bank",
    "BankDashboardController": "bank", "BankExportController": "bank", "BankGrantController": "bank",
    "BankHolderController": "bank", "BankRequestController": "bank", "OrgUnitBankController": "bank",
    "NotificationController": "notification", "NotificationRuleController": "notification",
    "MaterialController": "catalogue", "ProfitCalculationController": "catalogue",
    "MaterialCategoryController": "catalogue", "MaterialExternalAliasController": "catalogue",
    "LocationController": "catalogue", "UexLocationController": "catalogue", "CityController": "catalogue",
    "OutpostController": "catalogue", "PoiController": "catalogue", "SpaceStationController": "catalogue",
    "StarSystemController": "catalogue", "TerminalController": "catalogue", "ShipTypeController": "catalogue",
    "ManufacturerController": "catalogue", "RefiningMethodController": "catalogue",
    "JobTypeController": "catalogue", "FrequencyTypeController": "catalogue",
    "AdminP4kImportController": "catalogue", "SyncReportController": "catalogue",
    "AuditAdminController": "audit",
    "PromotionCategoryController": "promotion", "PromotionEligibilityController": "promotion",
    "PromotionLevelContentController": "promotion", "PromotionTopicController": "promotion",
    "RankRequirementController": "promotion", "MemberEvaluationController": "promotion",
    "OrgChartController": "orgchart", "LeitungController": "leadership",
    "OrgUnitController": "orgunit", "OrgHierarchyController": "orgunit", "SquadronController": "orgunit",
    "SquadronMembershipController": "orgunit", "SquadronRoleController": "orgunit",
    "SpecialCommandController": "orgunit", "SpecialCommandMembershipController": "orgunit",
    "KommandoGroupController": "orgunit",
    "UserController": "identity", "MeController": "identity", "MyRegistrationStatusController": "identity",
    "DiscordRegistrationAdminController": "identity", "AdminController": "identity",
    "AdminPersonSearchController": "identity", "DataExportController": "identity",
    "AdminDataExportController": "identity", "DeletionRequestController": "identity",
    "AdminDeletionRequestController": "identity", "TermsController": "identity",
    "TermsDocumentController": "identity", "AdminTermsController": "identity",
    "DiscordAccountExistenceController": "identity",
    "AnnouncementController": "dashboard",
    "SystemSettingController": "admin-system", "SystemController": "admin-system",
    "AppVersionPolicyController": "admin-system", "BasetoolErrorController": "admin-system",
    "LiveSyncController": "livesync",
    "ExchangeAccountCheckController": "exchange", "ExchangeBlueprintController": "exchange",
    "ExchangeCatalogController": "exchange", "ExchangeDemandController": "exchange",
    "ExchangeDraftController": "exchange", "ExchangeInstallationController": "exchange",
    "ExchangeShipController": "exchange", "ExchangeStockController": "exchange",
    "AdminExchangeRegistryController": "exchange", "AdminExchangeBulkUndoController": "exchange",
    "ConnectedAppsController": "exchange",
}

CONTENT_RULES = [
    (r"/search-bank|/org-units/bank|/bank/", "bank"),
    (r"/payout-preference", "mission"),
    (r"/blueprint-sharing|/item-blueprint-owners|/blueprints|/blueprint-variant|item-catalog/blueprints", "blueprint"),
    (r"/read-announcement", "dashboard"),
    (r"/memberships|/pickable-org-units|/org-unit-ids|/active-org-unit|/me/org-units|/org-units/active", "orgunit"),
    (r"/capabilities|/me/layout", "identity"),
    (r"/unit-ship-options", "hangar"),
    (r"/inventory/mission/|/refinery-orders/mission/", "mission"),
    (r"/orders/\{[^}]+\}/materials/\{[^}]+\}/inventory|/orders/\{[^}]+\}/inventory|/inventory/\{id\}/delivered", "inventory"),
    (r"/item-catalog|/refinery-orders/locations/|/materials/job-order|/locations/refineries|/locations/home-locations", "catalogue"),
    (r"/material-exchange/items/", "inventory"),
    (r"/admin/personal-inventory", "personalinventory"),
    (r"/admin/personal-blueprints|/admin/default-blueprints", "blueprint"),
    (r"/admin/exchange|/connected-apps|/exchange/", "exchange"),
    (r"/admin/registrations|/admin/deletion-requests|/admin/users|/admin/person-search|/admin/terms|/admin/roles", "identity"),
    (r"/admin/import/p4k", "catalogue"),
]

def content_domain(path, owner):
    for pat, dom in CONTENT_RULES:
        if re.search(pat, path):
            return dom
    return owner

def main():
    with open(os.path.join(OUT, "90-rest-api-mappings.csv"), encoding="utf-8") as fh:
        rows = list(csv.DictReader(fh))
    with open(os.path.join(OUT, "90-rest-api-consumers.csv"), encoding="utf-8") as fh:
        cons = {(r["verb"], r["path"]): r["consumers"] for r in csv.DictReader(fh)}
    with open(os.path.join(REPO, "backend", "src", "main", "resources", "api", "openapi.json"), encoding="utf-8") as fh:
        oas = json.load(fh)
    missing = sorted(set(r["class"] for r in rows) - set(CONTROLLER_DOMAIN))
    print("controllers without a domain:", missing)
    per = defaultdict(lambda: Counter())
    prefixes = defaultdict(set)
    tags = defaultdict(set)
    ctrl = defaultdict(set)
    foreign = []
    for r in rows:
        d = CONTROLLER_DOMAIN[r["class"]]
        key = (r["verb"], r["path"])
        per[d]["ops"] += 1
        ctrl[d].add(r["class"])
        parts = [p for p in r["path"].split("/") if p]
        if len(parts) >= 3 and parts[0] == "api":
            prefixes[d].add("/" + "/".join(parts[:3]))
        op = oas["paths"].get(r["path"], {}).get(r["verb"].lower())
        if op:
            for t in op.get("tags", []):
                tags[d].add(t)
        c = cons.get(key, "")
        if "app" in c:
            per[d]["frozen(app)"] += 1
        if "web" in c:
            per[d]["web"] += 1
        if "exchange" in c:
            per[d]["exchange-relay"] += 1
        if r["verb"] != "GET":
            per[d]["writes"] += 1
        if r["effective_preauth"].strip() == "isAuthenticated()":
            per[d]["only isAuthenticated()"] += 1
        if r["tx"]:
            per[d]["@Transactional in controller"] += 1
        if r["has_operation"] != "True":
            per[d]["no @Operation"] += 1
        cd = content_domain(r["path"], d)
        if cd != d:
            foreign.append((d, cd, r["verb"], r["path"], r["class"]))
    print()
    hdr = ["domain", "controllers", "ops", "writes", "frozen(app)", "web", "exchange-relay", "only isAuthenticated()", "@Transactional in controller", "no @Operation", "prefixes", "tags"]
    print(" | ".join(hdr))
    table = []
    for d in sorted(per, key=lambda x: -per[x]["ops"]):
        c = per[d]
        line = [d, str(len(ctrl[d])), str(c["ops"]), str(c["writes"]), str(c["frozen(app)"]), str(c["web"]), str(c["exchange-relay"]), str(c["only isAuthenticated()"]), str(c["@Transactional in controller"]), str(c["no @Operation"]), " ".join(sorted(p.replace("/api/v1/", "") for p in prefixes[d])), str(len(tags[d]))]
        table.append(line)
        print(" | ".join(line))
    print()
    print("operations whose path content belongs to another domain than the owning controller:", len(foreign))
    fc = Counter((a, b) for a, b, *_ in foreign)
    for (a, b), n in fc.most_common():
        print(f"   {a:18s} -> {b:18s} {n}")
    for f in foreign:
        print("   ", f)
    with open(os.path.join(OUT, "90-rest-api-domain-table.json"), "w", encoding="utf-8") as fh:
        json.dump({"header": hdr, "rows": table, "foreign": foreign}, fh, indent=1)

if __name__ == "__main__":
    sys.exit(main())
