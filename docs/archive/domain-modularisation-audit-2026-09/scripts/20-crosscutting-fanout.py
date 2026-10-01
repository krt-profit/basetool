"""Fan-in / fan-out of the cross-cutting hub classes over the jdeps class graph, grouped by heuristic domain."""
import collections
import importlib.util as U
import json
import os
import sys

spec = U.spec_from_file_location("common", os.path.join(os.path.dirname(os.path.abspath(__file__)), "20-crosscutting-common.py"))
common = U.module_from_spec(spec)
spec.loader.exec_module(common)

P = common.BACKEND_PKG + "."
HUBS = [
    "service.OwnerScopeService", "service.AccessGateService", "service.RequestScopeResolver",
    "service.OrgUnitStampingService", "service.OrgUnitCascadeService", "repository.ScopeSpecifications",
    "service.ScopePredicate", "service.AuthHelperService", "service.AuditService", "service.BankAuditService",
    "support.AuditDetails", "model.AuditEventType", "model.AuditDomain", "service.LiveSyncRelayService",
    "service.exchange.ExchangeLiveSync", "metrics.TaskMetrics", "metrics.ScheduledJob", "metrics.MetricNames",
    "task.BusinessMetricsCollector", "service.DataExportService", "service.PersonSearchService",
    "support.PersonSearchTargets", "support.HandleErasureCoverage", "service.HandleAnonymisationService",
    "service.UserDeletionService", "service.UserAccountMergeService", "service.AccountConsolidationService",
    "support.MissionPeerRedactor", "support.Roles", "support.Permissions", "service.NotificationCreationService",
    "service.RuleEvaluationService", "service.RecipientResolutionService", "service.NotificationEventListener",
    "service.MissionSecurityService", "service.BankSecurityService", "service.SpecialCommandSecurityService",
    "service.OrgRoleManagementSecurityService", "service.exchange.ExchangeGate", "service.CustomJwtGrantedAuthoritiesConverter",
    "support.LiveSyncTopicClass", "service.LiveSyncFanout", "support.ClientAttribution", "service.OrgUnitBankAccessService",
    "service.UserService", "model.User", "model.OrgUnit", "model.Squadron", "repository.UserRepository",
    "service.MasterDataCacheEvictionService", "service.DataExportReportService", "support.DataExportSections",
    "service.exchange.ExchangeJournalService", "service.exchange.ExchangeStockWriteService",
    "service.exchange.ExchangeShipWriteService", "service.exchange.ExchangeBlueprintWriteService",
    "service.exchange.ExchangeUndoService", "service.exchange.ExchangeDraftService",
    "service.exchange.ExchangeDemandService", "service.exchange.ExchangeStockFeedService",
    "service.exchange.ExchangeShipFeedService", "service.exchange.ExchangeBlueprintFeedService",
    "service.exchange.ExchangeCatalogService", "service.exchange.ExchangeDepartureService",
    "service.exchange.ExchangeBulkUndoService", "service.exchange.ExchangeMassChangeService",
    "service.exchange.ExchangeResolveService", "service.exchange.ExchangeAccountCheckService",
    "service.exchange.ExchangeInstallationService", "service.exchange.ConnectedAppsService",
]

def outer(c):
    return c.split("$", 1)[0]

def main():
    edges = set()
    for a, b in common.jdeps_edges("backend"):
        if a.startswith(P) and b.startswith(P):
            oa, ob = outer(a), outer(b)
            if oa != ob:
                edges.add((oa, ob))
    out_by = collections.defaultdict(set)
    in_by = collections.defaultdict(set)
    for a, b in edges:
        out_by[a].add(b)
        in_by[b].add(a)
    report = {}
    for h in HUBS:
        f = P + h
        outs = out_by.get(f, set())
        ins = in_by.get(f, set())
        od = collections.Counter(common.domain_of(x) for x in outs)
        idm = collections.Counter(common.domain_of(x) for x in ins)
        report[h] = {
            "out": len(outs), "in": len(ins),
            "out_domains": dict(od.most_common()), "in_domains": dict(idm.most_common()),
            "out_classes": sorted(x[len(P):] for x in outs), "in_classes": sorted(x[len(P):] for x in ins),
        }
    with open(os.path.join(common.SCRATCH, "20-crosscutting-fanout.json"), "w", encoding="utf-8") as fh:
        json.dump(report, fh, indent=1)
    sel = sys.argv[1:] or HUBS
    for h in sel:
        r = report[h]
        print("%s  out=%d in=%d" % (h, r["out"], r["in"]))
        print("   out domains:", r["out_domains"])
        print("   in  domains:", r["in_domains"])

if __name__ == "__main__":
    main()
