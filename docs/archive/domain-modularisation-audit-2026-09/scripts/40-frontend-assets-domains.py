import json
import sys
from collections import defaultdict, Counter

sys.stdout.reconfigure(encoding="utf-8")
BASE = r"$SCRATCHPAD"
js = {r["file"]: r for r in json.load(open(BASE + r"\40-frontend-assets-jsast.json", encoding="utf-8"))["results"]}
css = {r["file"]: r for r in json.load(open(BASE + r"\40-frontend-assets-css.json", encoding="utf-8"))["results"]}
tpl = {r["file"]: r for r in json.load(open(BASE + r"\40-frontend-assets-tpl.json", encoding="utf-8"))}

JS = {
    "core": "krt-client-error escape-html safe-url event-delegation common-handlers krt-modal krt-fetch krt-live-sync krt-user-search krt-catalog-search krt-searchable-select datetime-splitter scu-decimal-input inline-style-apply krt-filter-panel sidebar unsaved-changes toast autocomplete",
    "mission": "mission-detail missions mission-presence",
    "operation": "operation-detail operations operations-index",
    "joborder": "orders-create orders-detail orders-index orders-index-reorder orders-material-demand item-collection material-collection",
    "inventory": "inventory-admin inventory-my inventory-common inventory-input inventory-index inventory-material inventory-game-item inventory-herkunft inventory-note-modal inventory-materialboerse",
    "personalinventory": "personal-inventory",
    "blueprint": "personal-inventory-blueprints personal-inventory-blueprints-import personal-inventory-blueprints-recipe blueprint-overview admin-blueprints admin-default-blueprints admin-personal-blueprints-purge",
    "hangar": "hangar hangar-squadron",
    "materialexchange": "materialboerse materialboerse-release materialgesuch-modal",
    "refinery": "refinery-orders-create refinery-orders-details refinery-orders-index refinery-yield-badge",
    "bank": "bank krt-bank-account-search",
    "notification": "notifications notification-rules",
    "catalogue": "materials material-detail materials-matrix materials-profit-calculation locations uex admin-materials admin-material-aliases ship-data mission-data p4k-import sync-reports",
    "audit": "audit-log",
    "promotion": "promotion-admin-rank-requirements promotion-admin-topics promotion-manage promotion-my-evaluations promotion-overview",
    "orgchart": "org-chart",
    "leadership": "leitung",
    "orgunit": "special-commands special-command-detail admin-org-structure members",
    "identity": "profile terms-accept admin-terms discord-registrations pending-approval admin-deletion-requests admin-person-search",
    "dashboard": "index announcement",
    "admin": "admin-settings",
    "exchange": "admin-exchange-clients connected-apps connected-apps-confirm",
}
js_dom = {}
for d, names in JS.items():
    for n in names.split():
        js_dom[n + ".js"] = d
missing = sorted(set(js) - set(js_dom))
assert not missing, missing

TPL = {
    "shared": ["fragments/components.html", "fragments/fankit.html", "fragments/footer.html", "fragments/head.html", "fragments/icons.html", "fragments/modal-wrapper.html", "fragments/pagination.html", "fragments/sidebar.html", "fragments/toast.html", "fragments/unsaved-modal.html", "fragments/scu-hint.html", "error.html", "error/403.html", "error/404.html", "error/500.html", "error/error.html"],
    "public/legal": ["impressum.html", "privacy.html", "licenses.html", "landing.html", "sc-links.html", "app-link-help.html", "terms.html"],
    "mission": ["mission-detail.html", "missions.html"],
    "operation": ["operation-detail.html", "operations-index.html"],
    "joborder": ["orders-create.html", "orders-detail.html", "orders-index.html", "orders-material-demand.html", "item-collection.html", "material-collection.html"],
    "inventory": ["inventory-admin.html", "inventory-game-item.html", "inventory-index.html", "inventory-input.html", "inventory-material.html", "inventory-my.html", "fragments/inventory-stack-entries.html", "fragments/inventory-stolen-mark.html"],
    "personalinventory": ["personal-inventory.html", "admin/personal-inventory.html"],
    "blueprint": ["personal-inventory-blueprints.html", "blueprint-overview.html", "admin/blueprints.html", "admin/default-blueprints.html", "admin/personal-blueprints.html"],
    "hangar": ["hangar.html", "hangar-squadron.html"],
    "materialexchange": ["materialboerse.html", "fragments/materialboerse-modal.html", "fragments/materialgesuch-board.html", "fragments/materialgesuch-modal.html"],
    "refinery": ["refinery-orders-create.html", "refinery-orders-details.html", "refinery-orders-index.html"],
    "bank": ["admin/bank.html", "bank-account-detail.html", "bank-dashboard.html", "bank-grants.html", "bank-holder-detail.html", "bank-manage.html", "bank-requests.html", "org-unit-bank-account-detail.html", "org-unit-bank.html", "fragments/bank-account-views.html", "fragments/bank-approval-limits.html", "fragments/bank-balance-chart.html", "fragments/bank-counterparty.html", "fragments/bank-movement-modal.html", "fragments/org-unit-bank-views.html"],
    "notification": ["notifications.html", "admin/notification-rules.html"],
    "catalogue": ["material-detail.html", "materials.html", "materials-overview.html", "materials-profit-calculation.html", "ship-data.html", "admin/locations.html", "admin/material-aliases.html", "admin/materials.html", "admin/mission-data.html", "admin/p4k-import.html", "admin/sync-reports.html", "admin/uex.html", "fragments/admin-uex.html", "fragments/material-amount.html", "fragments/material-card.html"],
    "audit": ["admin/audit-log.html"],
    "promotion": ["promotion-admin-rank-requirements.html", "promotion-admin-topics.html", "promotion-manage.html", "promotion-my-evaluations.html", "promotion-overview.html"],
    "orgchart": ["org-chart.html", "fragments/org-chart-node.html"],
    "leadership": ["organisation/leitung.html"],
    "orgunit": ["members.html", "member-edit.html", "organisation/special-command-detail.html", "admin/special-commands.html", "admin/org-structure.html", "fragments/orgunit-select.html", "fragments/owner-picker.html"],
    "identity": ["profile.html", "terms-accept.html", "pending-approval.html", "admin/terms.html", "admin/discord-registrations.html", "admin/deletion-requests.html", "admin/person-search.html", "fragments/profile-deletion-card.html", "fragments/terms-body.html"],
    "dashboard": ["index.html", "admin/announcement.html"],
    "admin": ["admin-settings.html"],
    "exchange": ["connected-apps.html", "connected-apps-confirm.html", "admin/exchange-clients.html"],
}
tpl_dom = {}
for d, names in TPL.items():
    for n in names:
        tpl_dom[n] = d
missing = sorted(set(tpl) - set(tpl_dom))
assert not missing, missing

CSS_ROOT = {"styles.css": "shared", "inline-migration.css": "shared", "bank.css": "bank", "leitung.css": "leadership", "materialboerse.css": "materialexchange", "materials-overview.css": "catalogue", "org-chart.css": "orgchart", "personal-inventory.css": "personalinventory", "promotion-admin.css": "promotion", "terms-accept.css": "identity"}
css_dom = {}
for f in css:
    if f in CSS_ROOT:
        css_dom[f] = CSS_ROOT[f]
    else:
        users = [t for t, r in tpl.items() if ("css/" + f) in r["links"]]
        doms = sorted(set(tpl_dom[u] for u in users))
        css_dom[f] = doms[0] if len(doms) == 1 else "shared"

agg = defaultdict(Counter)
for f, r in js.items():
    d = js_dom[f]
    a = agg[d]
    a["js_files"] += 1
    a["js_lines"] += r["lines"]
    if r["tsCheck"]:
        a["js_checked_files"] += 1
        a["js_checked_lines"] += r["lines"]
    a["sinks"] += len(r["sinks"])
    a["raw_get_fetch"] += len(r["fetchCalls"]) if f not in ("krt-fetch.js", "krt-client-error.js") else 0
    a["boot_names"] += len(r["unresolvedBoot"])
    a["toplevel_names"] += 0 if r["topLevel"]["wrappedInIife"] else len(r["declaredTopLevel"])
RES = r"$REPO\frontend\src\main\resources"

def wc_lines(path):
    with open(path, "rb") as fh:
        return fh.read().count(b"\n")

for f, r in css.items():
    d = css_dom[f]
    agg[d]["css_files"] += 1
    agg[d]["css_lines"] += wc_lines(RES + "\\static\\css\\" + f.replace("/", "\\"))
for f, r in tpl.items():
    d = tpl_dom[f]
    agg[d]["tpl_files"] += 1
    agg[d]["tpl_lines"] += wc_lines(RES + "\\templates\\" + f.replace("/", "\\"))
    for s in r["scripts"]:
        if not s["src"]:
            agg[d]["inline_lines"] += s["lines"]
            agg[d]["inline_logic_stmts"] += s["inline"]["logic"]

cross = []
for f, r in js.items():
    d = js_dom[f]
    for dep, names in r["deps"].items():
        dd = js_dom[dep]
        if dd not in (d, "core"):
            cross.append((f, d, dep, dd, names))
css_cross = []
for t, r in tpl.items():
    d = tpl_dom[t]
    for l in r["links"]:
        f = l.replace("css/", "")
        if f in css_dom and css_dom[f] not in (d, "shared"):
            css_cross.append((t, d, f, css_dom[f]))
js_cross_tpl = []
for t, r in tpl.items():
    d = tpl_dom[t]
    for s in r["scripts"]:
        if s["src"]:
            f = s["src"].replace("js/", "")
            if f in js_dom and js_dom[f] not in (d, "core") and not (d == "shared"):
                js_cross_tpl.append((t, d, f, js_dom[f]))

order = ["core", "shared", "public/legal"] + [k for k in JS if k != "core"]
print("| Domain | JS files | JS lines | @ts-check files (lines %) | CSS files | CSS lines | Templates | Template lines | Inline JS lines (logic stmts) | innerHTML sinks | raw GET fetch | bootstrap names used | global top-level names |")
print("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
seen = set()
for d in order:
    if d in seen:
        continue
    seen.add(d)
    a = agg.get(d, Counter())
    pct = (100.0 * a["js_checked_lines"] / a["js_lines"]) if a["js_lines"] else 0
    print(f"| {d} | {a['js_files']} | {a['js_lines']} | {a['js_checked_files']} ({pct:.0f}%) | {a['css_files']} | {a['css_lines']} | {a['tpl_files']} | {a['tpl_lines']} | {a['inline_lines']} ({a['inline_logic_stmts']}) | {a['sinks']} | {a['raw_get_fetch']} | {a['boot_names']} | {a['toplevel_names']} |")
tot = Counter()
for a in agg.values():
    tot.update(a)
print(f"| **total** | {tot['js_files']} | {tot['js_lines']} | {tot['js_checked_files']} ({100.0*tot['js_checked_lines']/tot['js_lines']:.0f}%) | {tot['css_files']} | {tot['css_lines']} | {tot['tpl_files']} | {tot['tpl_lines']} | {tot['inline_lines']} ({tot['inline_logic_stmts']}) | {tot['sinks']} | {tot['raw_get_fetch']} | {tot['boot_names']} | {tot['toplevel_names']} |")
print()
print("cross-domain JS deps (excluding core):")
for c in cross:
    print("  ", c)
print("cross-domain CSS links:")
for c in css_cross:
    print("  ", c)
print("cross-domain JS script tags in templates:")
for c in js_cross_tpl:
    print("  ", c)
print("css domain map (non-root):", {k: v for k, v in css_dom.items() if k not in CSS_ROOT and v == "shared"})
