"""Measure the blast radius of the proposed per-domain re-cut of /api/v1.

Each rule maps current (verb, path) operations to a proposed path (or to DELETE). For every rule
the script reports: operations moved, how many the Android app freezes (ExternalContractTest set),
api-vhost allow-list rules that admit the old path, nightly edge-deny-probe lines naming it,
frontend main / e2e occurrences of the old path prefix, backend path-keyed infrastructure entries
that match it (NoStoreApiScopes, rate-limit rules, filter exemptions, SecurityConfig), and two
security deltas for the new path: a change of the Cache-Control bucket (no-store vs revalidate) and
admission by an existing prefix rule on the public vhost.
"""
import csv
import json
import os
import re
import sys
from collections import defaultdict

REPO = r"$REPO"
OUT = r"$SCRATCHPAD"
UUID = "00000000-0000-4000-8000-000000000000"

NO_STORE = ["/api/v1/bank/**", "/api/v1/org-units/bank/**", "/api/v1/users/**", "/api/v1/me/**",
            "/api/v1/notifications/**", "/api/v1/finance-entries/**", "/api/v1/missions/*/finance-entries/**",
            "/api/v1/operations/**", "/api/v1/personal-inventory/**", "/api/v1/personal-blueprints/**",
            "/api/v1/inventory/**", "/api/v1/hangar/**", "/api/v1/refinery-orders/**", "/api/v1/promotion/**"]
RATE_RULES = [("POST", "/api/v1/missions"), ("POST", "/api/v1/orders"), ("POST", "/api/v1/orders/items"),
              ("POST", "/api/v1/finance-entries"), ("POST|PUT|DELETE", "/api/v1/missions/*/participants"),
              ("POST|PUT|DELETE", "/api/v1/missions/*/participants/**")]
FILTER_PATHS = ["/api/v1/users/me/registration-status", "/api/v1/app/version-policy", "/api/v1/terms/document",
                "/api/v1/terms", "/api/v1/terms/**", "/api/v1/notifications/stream", "/api/v1/live-sync/stream"]

RULES = [
    ("identity->mission", r"^/api/v1/users/me/payout-preference$", "/api/v1/missions/me/payout-preference"),
    ("identity->blueprint", r"^/api/v1/users/me/blueprint-sharing$", "/api/v1/blueprints/me/sharing"),
    ("identity->dashboard", r"^/api/v1/users/me/read-announcement/\{announcementId\}$", "/api/v1/announcements/{announcementId}/read"),
    ("identity->bank", r"^/api/v1/users/search-bank(/references)?$", "/api/v1/bank/members/search\\1"),
    ("identity->orgunit", r"^/api/v1/users/me/memberships$", "/api/v1/org-units/me/memberships"),
    ("identity->orgunit", r"^/api/v1/users/me/pickable-org-units$", "/api/v1/org-units/me/pickable"),
    ("identity->orgunit", r"^/api/v1/users/me/org-unit-ids$", "/api/v1/org-units/me/ids"),
    ("identity->orgunit", r"^/api/v1/users/\{id\}/memberships(/detail)?$", "/api/v1/org-units/members/{id}/memberships\\1"),
    ("identity->orgunit", r"^/api/v1/me/active-org-unit$", "/api/v1/org-units/me/active"),
    ("identity->orgunit", r"^/api/v1/me/org-units$", "/api/v1/org-units/me/switchable"),
    ("identity admin", r"^/api/v1/admin/(registrations|deletion-requests|person-search)(.*)$", "/api/v1/users/admin/\\1\\2"),
    ("identity admin", r"^/api/v1/admin/users/\{userId\}/export(.*)$", "/api/v1/users/admin/{userId}/export\\1"),
    ("identity admin", r"^/api/v1/admin/roles(.*)$", "/api/v1/roles\\1"),
    ("identity admin (DANGER example)", r"^/api/v1/admin/terms(.*)$", "/api/v1/terms/admin\\1"),
    ("identity delete dup", r"^/api/v1/admin/users/\{id\}/attributes$", "DELETE"),
    ("bank", r"^/api/v1/org-units/bank/(.*)$", "/api/v1/bank/org-units/\\1"),
    ("orgunit", r"^/api/v1/org-hierarchy/org-units(.*)$", "/api/v1/org-units\\1"),
    ("orgunit", r"^/api/v1/org-hierarchy/(bereiche|organisationsleitung)(.*)$", "/api/v1/org-units/\\1\\2"),
    ("mission", r"^/api/v1/finance-entries$", "/api/v1/missions/{missionId}/finance-entries"),
    ("mission", r"^/api/v1/finance-entries/\{entryId\}$", "/api/v1/missions/{missionId}/finance-entries/{entryId}"),
    ("mission slim", r"^(/api/v1/missions/.*)/slim$", "\\1"),
    ("mission search fold", r"^/api/v1/missions/search$", "/api/v1/missions"),
    ("mission delete legacy", r"^/api/v1/missions/\{id\}/participants/add$", "DELETE"),
    ("inventory->filter", r"^/api/v1/inventory/mission/\{missionId\}$", "/api/v1/inventory/allocations"),
    ("refinery->filter", r"^/api/v1/refinery-orders/mission/\{missionId\}$", "/api/v1/refinery-orders"),
    ("refinery->catalogue", r"^/api/v1/refinery-orders/locations/\{locationId\}/yields$", "/api/v1/locations/{locationId}/refinery-yields"),
    ("joborder->catalogue", r"^/api/v1/orders/item-catalog$", "/api/v1/game-items"),
    ("inventory->catalogue", r"^/api/v1/inventory/item-catalog$", "/api/v1/game-items"),
    ("joborder->catalogue", r"^/api/v1/orders/item-catalog/\{gameItemId\}/blueprints$", "/api/v1/game-items/{gameItemId}/blueprints"),
    ("joborder->blueprint", r"^/api/v1/orders/item-catalog/blueprints/\{blueprintId\}/derivation$", "/api/v1/blueprints/{blueprintId}/derivation"),
    ("joborder naming", r"^/api/v1/orders/\{id\}/inventory/orphaned$", "/api/v1/orders/{id}/allocations/orphaned"),
    ("joborder naming", r"^/api/v1/orders/\{jobOrderId\}/inventory/\{inventoryItemId\}/unlink$", "/api/v1/orders/{jobOrderId}/allocations/{inventoryItemId}"),
    ("materialexchange", r"^/api/v1/material-requests(.*)$", "/api/v1/material-exchange/requests\\1"),
    ("hangar admin", r"^/api/v1/hangar/users/(.*)$", "/api/v1/hangar/admin/users/\\1"),
    ("hangar delete deprecated", r"^/api/v1/hangar/import/fleetview$", "DELETE"),
    ("notification admin", r"^/api/v1/notification-rules(.*)$", "/api/v1/notifications/admin/rules\\1"),
    ("dashboard naming", r"^/api/v1/announcement(.*)$", "/api/v1/announcements/current\\1"),
    ("orgchart naming", r"^/api/v1/leitung/view$", "/api/v1/org-chart/leadership"),
    ("admin-system delete demo", r"^/api/v[12]/system/ping$", "DELETE"),
    ("catalogue admin", r"^/api/v1/admin/import/p4k(.*)$", "/api/v1/catalog/admin/import/p4k\\1"),
    ("personalinventory admin", r"^/api/v1/admin/personal-inventory(.*)$", "/api/v1/personal-inventory/admin\\1"),
    ("blueprint admin", r"^/api/v1/admin/personal-blueprints(.*)$", "/api/v1/personal-blueprints/admin\\1"),
    ("blueprint admin", r"^/api/v1/admin/default-blueprints(.*)$", "/api/v1/blueprints/admin/defaults\\1"),
]

def glob_rx(p):
    s = re.escape(p).replace(r"/\*\*", "(/.*)?").replace(r"\*", "[^/]+")
    return re.compile("^" + s + "$")

def probe(path):
    for k, v in {"{roleCode}": "KRT_MEMBER", "{enabled}": "true", "{key}": "job_order.age_red_days", "{domain}": "bank", "{name}": "ADMIN"}.items():
        path = path.replace(k, v)
    return re.sub(r"\{[^}]+\}", UUID, path)

def load_allowlist():
    with open(os.path.join(REPO, "docker", "edge", "include", "api-allowlist.conf"), encoding="utf-8") as fh:
        lines = fh.read().splitlines()
    rules = []
    for i, ln in enumerate(lines, 1):
        m = re.match(r'^\s*if \(\$uri (=|~|~\*) "([^"]*)"\)\s*\{\s*set \$krt_api_allowed 1;', ln)
        if m:
            op, operand = m.group(1), m.group(2)
            if op == "=":
                rules.append((i, operand, lambda u, o=operand: u == o, False))
            else:
                rx = re.compile(operand, re.I if op == "~*" else 0)
                rules.append((i, operand, lambda u, r=rx: r.search(u) is not None, not operand.endswith("$")))
    return rules

def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()

def walk_text(root, exts):
    out = {}
    for dp, dns, fs in os.walk(root):
        if os.sep + "build" + os.sep in dp + os.sep:
            continue
        for f in fs:
            if f.endswith(exts):
                out[os.path.join(dp, f)] = read(os.path.join(dp, f))
    return out

def main():
    with open(os.path.join(OUT, "90-rest-api-mappings.csv"), encoding="utf-8") as fh:
        rows = [r for r in csv.DictReader(fh) if r["path"].startswith("/api/")]
    with open(os.path.join(OUT, "90-rest-api-consumers.csv"), encoding="utf-8") as fh:
        cons = {(r["verb"], r["path"]): r["consumers"] for r in csv.DictReader(fh)}
    allow = load_allowlist()
    probe_yml = read(os.path.join(REPO, ".github", "workflows", "edge-deny-probe.yml")).splitlines()
    fe_main = walk_text(os.path.join(REPO, "frontend", "src", "main"), (".java", ".js", ".html"))
    fe_e2e = walk_text(os.path.join(REPO, "frontend", "src", "e2e"), (".java",))
    existing = set(r["path"] for r in rows)
    nostore = [glob_rx(p) for p in NO_STORE]
    total = defaultdict(int)
    moved_ops = {}
    for label, pat, repl in RULES:
        rx = re.compile(pat)
        hits = [r for r in rows if rx.search(r["path"])]
        if not hits:
            print(f"[{label}] {pat}: NO MATCH")
            continue
        paths = sorted(set(r["path"] for r in hits))
        ops = [(r["verb"], r["path"]) for r in hits]
        frozen = [o for o in ops if "app" in cons.get(o, "")]
        web = [o for o in ops if "web" in cons.get(o, "")]
        allow_lines = sorted(set(a[0] for a in allow for p in paths if a[2](probe(p))))
        prefix_old = sorted(set(a[1] for a in allow for p in paths if a[3] and a[2](probe(p))))
        probe_lines = []
        for p in paths:
            tpl = re.sub(r"\{[^}]+\}", "", p).rstrip("/")
            stem = tpl.split("//")[0]
            for i, ln in enumerate(probe_yml, 1):
                if re.search(r"\bprobe\b|for p in", ln) and stem and stem in ln.replace('"$nil"', "").replace("$nil", ""):
                    probe_lines.append(i)
        probe_lines = sorted(set(probe_lines))
        fe_hits = 0
        e2e_hits = 0
        for p in paths:
            stem = re.split(r"/\{", p)[0]
            if stem.count("/") < 3:
                continue
            for t in fe_main.values():
                fe_hits += t.count('"' + stem) + t.count("'" + stem)
            for t in fe_e2e.values():
                e2e_hits += t.count(stem)
        new_paths = []
        cache_changes = []
        new_prefix_admit = []
        collisions = []
        for p in paths:
            if repl == "DELETE":
                continue
            np_ = rx.sub(repl, p)
            new_paths.append((p, np_))
            old_ns = any(x.match(probe(p)) for x in nostore)
            new_ns = any(x.match(probe(np_)) for x in nostore)
            if old_ns != new_ns:
                cache_changes.append(f"{p} -> {np_}: {'no-store' if old_ns else 'revalidate'} -> {'no-store' if new_ns else 'revalidate'}")
            for a in allow:
                if a[3] and a[2](probe(np_)) and not a[2](probe(p)):
                    new_prefix_admit.append(f"{np_} admitted by prefix rule line {a[0]} ({a[1]})")
            if np_ in existing and np_ != p:
                collisions.append(np_)
        infra = []
        for p in paths:
            for g in NO_STORE:
                if glob_rx(g).match(probe(p)):
                    infra.append("NoStoreApiScopes " + g)
            for verbs, g in RATE_RULES:
                if glob_rx(g).match(probe(p)):
                    infra.append("rate-limit " + verbs + " " + g)
            for g in FILTER_PATHS:
                if glob_rx(g).match(probe(p)):
                    infra.append("filter exemption " + g)
        print(f"[{label}] ops={len(ops)} frozen(app)={len(frozen)} web={len(web)} allowlistLines={allow_lines} "
              f"probeLines={probe_lines[:12]}{'...' if len(probe_lines) > 12 else ''} feLiterals~{fe_hits} e2e~{e2e_hits}")
        if frozen:
            print("    frozen:", ", ".join(v + " " + p.replace("/api/v1", "") for v, p in frozen))
        if prefix_old:
            print("    old path admitted by prefix rule:", prefix_old)
        if sorted(set(infra)):
            print("    backend path-keyed infra:", sorted(set(infra)))
        for c in cache_changes:
            print("    CACHE BUCKET CHANGE:", c)
        for n in sorted(set(new_prefix_admit)):
            print("    NEWLY ADMITTED ON PUBLIC VHOST:", n)
        if collisions:
            print("    collides with existing path:", sorted(set(collisions)))
        for p, n in new_paths[:4]:
            print("    e.g.", p.replace("/api/v1", ""), "->", n.replace("/api/v1", ""))
        for o in ops:
            moved_ops[o] = label
        total["ops"] += len(ops)
        total["frozen"] += len(frozen)
    print()
    print("TOTAL operations touched:", total["ops"], "of which frozen for the app:", total["frozen"])
    print("distinct operations touched:", len(moved_ops))
    with open(os.path.join(OUT, "90-rest-api-recut.json"), "w", encoding="utf-8") as fh:
        json.dump({f"{k[0]} {k[1]}": v for k, v in moved_ops.items()}, fh, indent=1)

if __name__ == "__main__":
    sys.exit(main())
