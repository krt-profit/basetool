"""Shared helpers for the 20-crosscutting audit scripts: paths, domain classifier, loaders."""
import importlib.util
import json
import os
import re

ROOT = r"$REPO"
SCRATCH = os.path.dirname(os.path.abspath(__file__))
BACKEND_PKG = "de.greluc.krt.profit.basetool.backend"
FRONTEND_PKG = "de.greluc.krt.profit.basetool.frontend"
INGEST_PKG = "de.greluc.krt.profit.basetool.ingest"

DOMAIN_RULES = [
    ("exchange", r"^(Exchange|ConnectedApps|FirstPartyClientIds|DisabledExchange|RedisExchange|AdminExchange)"),
    ("personalinventory", r"^(PersonalInventory|AdminPersonalInventory)"),
    ("blueprint", r"^(PersonalBlueprint|DefaultBlueprint|AdminPersonalBlueprint|AdminDefaultBlueprint|Blueprint|ScWikiBlueprint)"),
    ("joborder", r"^(JobOrder|MaterialClaim|MaterialCollection)"),
    ("inventory", r"^(Inventory|StockViewer|AllocationReductions|MyInventory)"),
    ("materialexchange", r"^(MaterialExchange|MaterialRequest|MarketOffer|Market)"),
    ("operation", r"^(Operation)"),
    ("mission", r"^(Mission|ParticipantTarget|Participant)"),
    ("refinery", r"^(RefineryOrder|RefineryImport|RefineryGood|Refinery)"),
    ("audit", r"^(Audit)"),
    ("bank", r"^(Bank|OrgUnitBank|CounterpartySnapshot)"),
    ("notification", r"^(Notification|RuleEvaluation|RecipientResolution|MailMessage|MailService|SmtpMail|PendingRegistrationMail|UserApprovalMail|LocalNotification|RedisNotification|SelectorKind)"),
    ("livesync", r"^(LiveSync|LocalLiveSync|RedisLiveSync|RedisJsonFanout|SseSendFailureCause)"),
    ("promotion", r"^(Promotion|MemberEvaluation|RankRequirement)"),
    ("orgchart", r"^(OrgChart)"),
    ("leadership", r"^(Leitung|KommandoGroup|OrgRoleManagement)"),
    ("security", r"^(OwnerScope|AccessGate|RequestScopeResolver|AuthHelper|ScopePredicate|ScopeSpecifications|CustomJwt|OrgUnitContextualAuthority|AuthenticatedSubject|ActingMember|DatabaseActingMember|SquadronScope|OrgUnitStamping|Roles$|Permissions$|StaffelMembershipResolver|MissionViewerAccess)"),
    ("orgunit", r"^(OrgUnit|Squadron|SpecialCommand|OrgHierarchy|StaffelMembership)"),
    ("identity", r"^(User|Registration|Discord|Terms|DataExport|DeletionRequest|PersonSearch|HandleAnonymisation|HandleErasure|AccountConsolidation|Keycloak|Me[A-Z]|MyRegistration|AdminDataExport|AdminDeletionRequest|AdminPersonSearch|AdminTerms|RejectedRegistration|Role[A-Z]|RoleService|Role$|Permission$|ApprovalStatus)"),
    ("dashboard", r"^(Announcement|Dashboard)"),
    ("catalogue", r"^(ShipType|Material|Location|StarSystem|SpaceStation|City|Outpost|Poi|Terminal|Manufacturer|RefiningMethod|JobType|FrequencyType|Uex|ScWiki|P4k|AdminP4k|SyncReport|SyncCoordinator|SyncChunkWriter|MasterData|ProfitCalculation|PriceLine|Commodity|Season|Item|GameItem|QuantityType|QualityRequirement)"),
    ("hangar", r"^(Hangar|Ship|Fleet)"),
    ("admin", r"^(Admin|SystemSetting|System|AppVersionPolicy)"),
]
_COMPILED = [(d, re.compile(p)) for d, p in DOMAIN_RULES]

def simple_name(fqcn):
    name = fqcn.rsplit(".", 1)[-1]
    return name.split("$", 1)[0]

def domain_of(fqcn):
    """Heuristic domain of a class, from its package and outer simple name."""
    if ".exchange" in fqcn.rsplit(".", 1)[0]:
        return "exchange"
    name = simple_name(fqcn)
    for d, rx in _COMPILED:
        if rx.search(name):
            return d
    return "?"

def load_module(name):
    path = os.path.join(SCRATCH, name)
    spec = importlib.util.spec_from_file_location(name.replace("-", "_").replace(".py", ""), path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod

def load_annotations():
    with open(os.path.join(SCRATCH, "20-crosscutting-annotations.json"), encoding="utf-8") as fh:
        return json.load(fh)

def source_path(module, fqcn):
    outer = fqcn.split("$", 1)[0]
    rel = outer.replace(".", os.sep) + ".java"
    return os.path.join(ROOT, module, "src", "main", "java", rel)

def rel(path):
    return os.path.relpath(path, ROOT).replace(os.sep, "/")

def iter_java(module, sourceset="main"):
    base = os.path.join(ROOT, module, "src", sourceset, "java")
    for dirpath, _, files in os.walk(base):
        for f in files:
            if f.endswith(".java"):
                yield os.path.join(dirpath, f)

def read(path):
    with open(path, encoding="utf-8", errors="replace") as fh:
        return fh.read()

def jdeps_edges(module):
    """Yield (from, to) class edges from the coordinator's jdeps dump."""
    path = os.path.join(SCRATCH, "jdeps-%s.txt" % module)
    with open(path, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            m = re.match(r"\s+(\S+)\s+->\s+(\S+)", line)
            if m:
                yield m.group(1), m.group(2)
