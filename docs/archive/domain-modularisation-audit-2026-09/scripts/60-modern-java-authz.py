"""How do authorization/scope services decide on enum values: switch, ==, sets or ordinals?"""

import collections
import importlib.util
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("mjlex", os.path.join(HERE, "60-modern-java-lexer.py"))
L = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(L)

ENUMS = ["MembershipRole", "OrgUnitKind", "ExchangeCapability", "BankAccountType", "BankAccountViewGranteeKind",
         "BankRequestApprover", "BereichLeadershipRole", "OrgRelativeRole", "ApprovalStatus", "MissionStatus",
         "OperationStatus", "JobOrderStatus", "AuditDomain", "AuditEventType", "LiveSyncAuthorization",
         "OrgChartScope", "ExchangeResource", "SelectorKind", "BankTransactionType", "BankBookingRequestType",
         "BankBookingRequestStatus", "RefineryOrderStatus", "DeletionRequestStatus", "MaterialExchangeOfferStatus"]
SEC_FILES = re.compile(r"(Security|Access|Auth|Gate|Scope|Authorizer|Permission|Redactor|Stamping)\w*\.java$")

rows = collections.Counter()
ex = collections.defaultdict(list)
for m, s, p, rel in L.iter_java():
    if s != "main":
        continue
    code, _st, _cm = L.lex(L.read(p))
    li = L.LineIndex(code)
    sec = bool(SEC_FILES.search(rel))
    for e in ENUMS:
        pats = {
            "eq": r"(?:==|!=)\s*%s\.[A-Z_]+|%s\.[A-Z_]+\s*(?:==|!=)" % (e, e),
            "set": r"(?:EnumSet|Set|List)\.of\s*\(\s*%s\.[A-Z_]+" % e,
            "ordinal_cmp": r"\.ordinal\s*\(\s*\)\s*[<>]=?|compareTo\s*\(\s*%s\." % e,
            "name_eq": r"\.name\s*\(\s*\)\s*\.equals|%s\.valueOf\s*\(" % e,
        }
        for k, rx in pats.items():
            for mm in re.finditer(rx, code):
                if k == "ordinal_cmp" and e not in code[max(0, mm.start() - 200):mm.end() + 50]:
                    continue
                rows[(e, k, "security-file" if sec else "other")] += 1
                if len(ex[(e, k)]) < 6:
                    ex[(e, k)].append(f"{rel.split('/java/')[-1].replace('de/greluc/krt/profit/basetool/', '')}:{li.line(mm.start())}")
for k in sorted(rows, key=lambda k: (-rows[k], k)):
    print(rows[k], k, ex[(k[0], k[1])][:4])
