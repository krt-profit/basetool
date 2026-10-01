"""Replay a sequence of decoupling moves on the class graph and report the domain SCCs after each step.

A move either re-homes classes into another (possibly new) domain, or inverts a dependency: every
class edge matching (source domain or class set) -> (target domain) is removed and, when 'flip' is
set, replaced by an edge in the opposite direction (the SPI implementation moves to the upper
domain, which then depends on the lower one). Counts are class edges between categories other than
shared-kernel and infrastructure.
"""
import importlib.util
import os
import re
from collections import defaultdict

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("graph", os.path.join(here, "10-backend-domains-graph.py"))
graph = importlib.util.module_from_spec(spec)
spec.loader.exec_module(graph)

NON = {"shared-kernel", "infrastructure"}

PRIVACY = ["UserDeletionService", "HandleAnonymisationService", "UserAccountMergeService", "AccountConsolidationService",
           "DataExportService", "DataExportReportService", "DataExportSections", "DataExportPdfFormat", "PersonSearchService",
           "PersonSearchTargets", "PersonSearchHitDto", "HandleErasureCoverage", "HandleScrubber", "HandleSpellings",
           "DataExportController", "AdminDataExportController", "AdminPersonSearchController", "DeletionRequestService",
           "DeletionRequestController", "AdminDeletionRequestController", "DeletionRequest", "DeletionRequestStatus",
           "DeletionRequestRepository", "DeletionRequestDto", "CreateDeletionRequestRequest", "DecideDeletionRequestRequest",
           "AccountDeletionRequestDeclinedEvent", "AccountDeletionRequestResolvedEvent", "AccountDeletionRequestedEvent",
           "ConsolidateAccountRequest", "MergeAccountRequest"]
GATES = ["AccessGateService", "OwnerScopeService", "ScopeSpecifications"]

STEPS = [
    ("S1 privacy (GDPR orchestration) becomes its own top module; HandleAnonymisation -> shared-kernel",
     {"rehome": dict([(c, "privacy") for c in PRIVACY] + [("HandleAnonymisation", "shared-kernel")])}),
    ("S2 per-aggregate gates move into their domains (access keeps scope/stamping/auth kernel)",
     {"drop": [(GATES, d) for d in ("mission", "joborder", "inventory", "refinery", "operation", "hangar", "bank", "blueprint", "exchange", "materialexchange")]}),
    ("S3 leadership merged into orgunit",
     {"rehome_domain": {"leadership": "orgunit"}}),
    ("S4 notification recipient selectors for bank/identity via SPI (flip)",
     {"flip": [(["RecipientResolutionService", "NotificationRuleService"], "bank"), (["RecipientResolutionService", "NotificationRuleService"], "identity")]}),
    ("S5 exchange projection rows move into owning modules (catalogue/hangar/inventory -> exchange dropped)",
     {"drop": [(["BlueprintRepository", "GameItemRepository", "LocationRepository", "ShipRepository", "InventoryItemRepository"], "exchange")]}),
    ("S6 audit keeps only ids (AuditService -> User) and bank registers its own retention (flip)",
     {"drop": [(["AuditService"], "identity"), (["AuditReportService"], "identity")], "flip": [(["AuditRetentionService"], "bank")]}),
    ("S7 PayoutPreference re-homed to identity (a member preference on User)",
     {"rehome": {"PayoutPreference": "identity"}}),
    ("S8 catalogue in-use checks and job-type designation via SPI (flip)",
     {"flip": [(["LocationService", "ShipTypeController", "JobTypeService", "MaterialExternalAliasService"], d) for d in ("hangar", "refinery", "mission", "access")]}),
    ("S9 orgunit membership hooks (orgchart mirror, inventory re-stamp, bank responsibility) as in-transaction events (flip)",
     {"flip": [(["OrgUnitMembershipService", "KommandoGroupService"], d) for d in ("orgchart", "inventory", "bank")]}),
    ("S10 identity core stops calling blueprint/bank/orgunit writers (reconciliation hooks as events, flip)",
     {"flip": [(["UserReconciliationService", "UserSyncService", "UserService", "UserController", "MeController", "UserDtoRedaction"], d) for d in ("blueprint", "bank", "orgunit", "inventory", "notification", "joborder")]}),
    ("S11 blueprint stops reaching up (upload preview via exchange, craftability via inventory/refinery query API)",
     {"flip": [(["BlueprintUploadPreviewService", "PersonalBlueprintService"], "exchange")]}),
    ("S12 access core stops naming identity entities (User -> UserRef id)",
     {"drop": [(["AccessGateService", "OrgUnitStampingService", "OwnerScopeService"], "identity")], "flip": [(["CustomJwtGrantedAuthoritiesConverter"], "identity"), (["CustomJwtGrantedAuthoritiesConverter"], "exchange")]}),
    ("S13 orgunit -> access controller checks via orgunit's own security bean; orgchart/orgunit/identity via ids",
     {"drop": [(["SpecialCommandController", "SquadronController", "SpecialCommandSecurityService"], "access"),
               (["OrgUnitMembership", "OrgUnitMembershipMapper", "OrgUnitMembershipService", "OrgChartPosition", "OrgChartPositionMapper", "OrgChartReadService", "OrgChartService", "LeitungViewService"], "identity")]}),
]

def main():
    cls, folded = graph.load()
    dom = {f: r["domain"] for f, r in cls.items()}
    sim = {f: r["simple"] for f, r in cls.items()}
    edges = defaultdict(int)
    for a, b in folded:
        edges[(a, b)] += 1

    def dom_edges():
        w = defaultdict(int)
        for (a, b), n in edges.items():
            da, db = dom[a], dom[b]
            if da != db and da not in NON and db not in NON:
                w[(da, db)] += n
        return w

    def report(label):
        w = dom_edges()
        nodes = {x for k in w for x in k} | {d for d in set(dom.values()) if d not in NON}
        adj = defaultdict(set)
        for (x, y) in w:
            adj[x].add(y)
        sccs = [c for c in graph.tarjan(nodes, adj) if len(c) > 1]
        total = sum(w.values())
        biggest = max((len(c) for c in sccs), default=1)
        print("\n%s\n   cross-domain class edges=%d, SCCs>1: %d, largest=%d" % (label, total, len(sccs), biggest))
        for c in sccs:
            sub = {k: v for k, v in w.items() if k[0] in c and k[1] in c}
            order = graph.eades_order(c, sub)
            pos = {d: i for i, d in enumerate(order)}
            back = sorted(((a, b, n) for (a, b), n in sub.items() if pos[a] > pos[b]), key=lambda t: -t[2])
            print("   SCC(%d): %s" % (len(c), " > ".join(order)))
            print("      back edges (%d pairs, %d class edges): %s" % (len(back), sum(t[2] for t in back), ", ".join("%s->%s:%d" % t for t in back[:30])))

    report("S0 current")
    for label, mv in STEPS:
        for c, d in mv.get("rehome", {}).items():
            for f in dom:
                if sim[f] == c:
                    dom[f] = d
        for old, new in mv.get("rehome_domain", {}).items():
            for f in dom:
                if dom[f] == old:
                    dom[f] = new
        for srcs, tdom in mv.get("drop", []):
            for (a, b) in list(edges):
                if sim[a] in srcs and dom[b] == tdom:
                    del edges[(a, b)]
        for srcs, tdom in mv.get("flip", []):
            for (a, b) in list(edges):
                if sim[a] in srcs and dom[b] == tdom:
                    n = edges.pop((a, b))
                    edges[(b, a)] += n
        report(label)

if __name__ == "__main__":
    main()
