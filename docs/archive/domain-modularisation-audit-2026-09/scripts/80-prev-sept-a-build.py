"""Builds 80-prev-sept-a.json and 80-prev-sept-a.md from 80-prev-sept-a-selected.json and
80-prev-sept-a-data.py."""
import importlib.util
import json
import os
import re
from collections import Counter

BASE = os.path.dirname(os.path.abspath(__file__))

spec = importlib.util.spec_from_file_location("d", os.path.join(BASE, "80-prev-sept-a-data.py"))
d = importlib.util.module_from_spec(spec)
spec.loader.exec_module(d)
E = d.E

with open(os.path.join(BASE, "80-prev-sept-a-selected.json"), encoding="utf-8") as f:
    selected = json.load(f)

ids = [s["id"] for s in selected]
assert len(ids) == 76, len(ids)
assert set(ids) == set(E), (set(ids) ^ set(E))

STATUSES = ["DONE", "PARTIAL", "OPEN", "SUPERSEDED", "REGRESSED", "NOT-VERIFIABLE"]
VERDICTS = ["CONFIRMED", "ADJUSTED", "SUPERSEDED-BY-MODULARISATION", "DROPPED", "REPRIORITISED"]

out = []
for s in selected:
    e = E[s["id"]]
    assert e["status"] in STATUSES, (s["id"], e["status"])
    assert e["verdict"] in VERDICTS, (s["id"], e["verdict"])
    out.append({
        "id": s["id"],
        "area": s["area"],
        "prio_old": "P" + str(s["prio"]),
        "title_de": s["title"],
        "status": e["status"],
        "evidence": e["evidence"],
        "verdict": e["verdict"],
        "prio_new": e["prio_new"],
        "reasoning": e["reasoning"],
        "domain_effect": e["domain_effect"],
        "security_note": e["security_note"],
    })

with open(os.path.join(BASE, "80-prev-sept-a.json"), "w", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, indent=1)

st = Counter(o["status"] for o in out)
vd = Counter(o["verdict"] for o in out)
area = Counter(o["area"] for o in out)
cross = Counter((o["status"], o["verdict"]) for o in out)

def prs(ev):
    found = []
    for m in re.findall(r"#(\d{4})", ev):
        if m not in found:
            found.append(m)
    return ", ".join("#" + x for x in found[:3]) if found else ("674e55bd7" if "674e55bd7" in ev else "-")

def tag(de):
    return de.split(" ")[0].rstrip(",;")

def esc(t):
    return t.replace("|", "/")

L = []
A = L.append
A("# Re-evaluation of the September audit — Backend, Ingest, Keycloak, Build (76 findings)")
A("")
A("Agent `80-prev-sept-a`, 2026-09-29. Read-only. Code authority: worktree "
  "`claude/basetool-refactor-modularization-68184f` = `origin/main` `95e945326` (checked with "
  "`git rev-parse HEAD origin/main`). Input: `sept_audit_findings.json`, areas Backend (37), Build (22), "
  "Ingest (12), Keycloak (5). Machine-readable twin: `80-prev-sept-a.json`.")
A("")
A("Path shorthand used below and in the JSON: `be:` = `backend/src/main/java/de/greluc/krt/profit/basetool/backend/`, "
  "`be-test:` = the backend test root, `in:` = `ingest/src/main/java/de/greluc/krt/profit/basetool/ingest/`, "
  "`in-test:` = the ingest test root, `fe:` = `frontend/src/main/java/de/greluc/krt/profit/basetool/frontend/`, "
  "`kc:` = `keycloak-spi/src/main/java/de/greluc/krt/profit/basetool/keycloak/spi/`. Other paths are repository-relative. "
  "PR numbers were resolved to merge commits with `git log --oneline --grep=\"(#NNNN)\" origin/main`.")
A("")
A("## 1. Summary")
A("")
A("### Ten conclusions")
A("")
C = [
    "**66 of 76 are done and hold on `main`**; 7 are partial, 1 regressed through later code, 1 open, 1 superseded as recorded (§2; merge commits in §6). The vault's Done table names 73 of the 76: it omits BE-SIMP-04/-05 (done in #1994/#1996) and BLD-PERF-03 (open), and it records APPSEC-02, BE-MOD-02, ING-MOD-02 and THEME-SIMP-01 as done although each left a part undone (PSA-05).",
    "**The only untouched item is BLD-PERF-03** (test contexts): no commit mentions it; 191 backend `@SpringBootTest` classes carry `@ActiveProfiles(\"test\")` and 40 do not although Gradle sets the profile for all (`build.gradle.kts:186`), and a static approximation finds about 49 distinct backend context keys (`80-prev-sept-a-contexts.py`). Unify the profile now; design module-scoped test slices with the modularisation.",
    "**Production's Keycloak admin client and the internal JWKS fetch run without timeouts**: the pinned factory `be:config/KeycloakTrustSupport.java:74-75` replaces the 5 s/30 s client of `be:config/RestClientConfig.java:92-97` (BE-MOD-02 residual, P1; PSA-02).",
    "**New code regressed two September fixes by following the easy default**: a new trim-to-null copy (`be:service/exchange/ExchangeRegistryService.java:413-416`) and reference-only callers of the role-graphed `UserRepository.findById` (`be:service/exchange/ExchangeStockWriteService.java:798`, `ExchangeAccountCheckService.java:68`, `JobOrderItemProductionService.java:371`) — the expensive variant still carries the default name (`be:repository/UserRepository.java:302-305`).",
    "**The error kernel blocks package-per-domain as it stands**: the sealed `AppException` permits domain exceptions and `AppExceptionKind` holds domain codes (`be:exception/AppException.java:37-50`, `AppExceptionKind.java:37-171`); javac 25 refuses a sealed class in the unnamed module that permits a class in another package (`compiler.err.class.in.unnamed.module.cant.extend.sealed.in.diff.package`; same-package control compiles). APPSEC-02 added one entry to each (PSA-01).",
    "**Some September fixes coupled domains more tightly**: APPSEC-02 added the edge refinery → `MissionParticipantRepository` (0 references before #1985), BE-PERF-01 added hangar → `UserMapper` and made other domains prime an identity mapper's memo, BE-PERF-15 added a second memo of the caller's memberships beside `RequestScopeResolver`'s, and APPSEC-01 is the third copy of an inventory rule in a foreign domain.",
    "**The `support` package grew as a domain-helper hub** (63 classes; `CachedEntityGraphs`, three retention records, 18 of 27 properties records), and the ArchUnit leaf rule's own message tells authors to put shared logic there (`be-test ArchitectureTest.java:596-598, 609-611, 624-626`) (PSA-04).",
    "**The build conventions help a Gradle split, with two traps**: JaCoCo floors and test heap are keyed on `project.name`, so code moved out of `backend` silently falls from 0.82/0.65 to 0.50/0.40 (`build.gradle.kts:184, 228-241`), and the one Dockerfile enumerates subprojects and copies only `${MODULE}/src/main` (`docker/app/Dockerfile:12-27`) (BLD-SIMP-06, IMG-SIMP-14).",
    "**Two guards depend on the current layout**: the peer-redaction rule is selected by package `.backend.controller` and hard-coded DTO names with a floor of 10 (`be-test ArchitectureTest.java:1129-1173`) and must be re-keyed in the same commit as any mission move; the `Entities.require` ratchet scans only `backend/src/main/java` and must be widened for a Gradle split. The lazy-association and export-budget sweeps are annotation- and type-based and survive either target.",
    "**Ingest's findings were reshaped by the exchange epic and their invariants survived** (token invalidation in `in:exchange/ExchangeRelay.java:430-437`, surface pinned to 16 exchange routes in `IngestEndpointSurfaceTest`), but one credential-hygiene leftover remains: ingest's and the frontend's `MonitoringScrapeProperties` are `@Data` with the password in `toString`, unlike the backend record (ING-MOD-02 → P1; PSA-03).",
]
for i, c in enumerate(C, 1):
    A(f"{i}. {c}")
A("")
A("### Counts")
A("")
A("| Status | Count |")
A("| --- | ---: |")
for s_ in STATUSES:
    A(f"| {s_} | {st.get(s_, 0)} |")
A(f"| **Total** | **{sum(st.values())}** |")
A("")
A("| Verdict | Count |")
A("| --- | ---: |")
for v in VERDICTS:
    A(f"| {v} | {vd.get(v, 0)} |")
A(f"| **Total** | **{sum(vd.values())}** |")
A("")
A("NOT-VERIFIABLE is never the primary status: three items are done in code but have a production half only a host or Prometheus read can confirm (APPSEC-04 ACL state, ING-SEC-04 leaves in use, ING-SEC-03 gauge value); each says so in its evidence, and the vault records the rollouts.")
A("")
A("| Area | Findings | Not DONE |")
A("| --- | ---: | ---: |")
for a in ["Backend", "Build", "Ingest", "Keycloak"]:
    nd = sum(1 for o in out if o["area"] == a and o["status"] != "DONE")
    A(f"| {a} | {area[a]} | {nd} |")
A("")
A("## 2. All 76 findings")
A("")
A("Domain effect: *helps* = eases the domain-modular target, *hinders* = adds or keeps cross-domain coupling, *neutral*, *interacts* = changes shape with the target. Full text per finding in the JSON.")
A("")
A("| ID | Area | Old | Title (DE) | Status | Verdict | New | PRs | Domain |")
A("| --- | --- | --- | --- | --- | --- | --- | --- | --- |")
for o in out:
    A(f"| {o['id']} | {o['area']} | {o['prio_old']} | {esc(o['title_de'])} | {o['status']} | {o['verdict']} | {esc(o['prio_new'])} | {prs(o['evidence'])} | {tag(o['domain_effect'])} |")
A("")
A("## 3. Non-trivial re-evaluations")
A("")
A("Every finding whose status is not DONE, or whose verdict is not CONFIRMED, in full. Order: security and correctness first, then modularisation relevance.")
A("")
ORDER = [
    "BE-MOD-02", "ING-MOD-02", "BLD-PERF-03", "APPSEC-02", "APPSEC-01", "BE-SIMP-05", "BE-SIMP-09",
    "BE-PERF-01", "BE-PERF-12", "BE-PERF-11", "BLD-SIMP-06", "IMG-SIMP-14", "BE-PERF-15", "BE-SIMP-08",
    "BE-SIMP-10", "BLD-PERF-04", "SEC-16", "TST-18", "ING-SEC-04", "THEME-SIMP-01", "ING-PERF-01",
]
byid = {o["id"]: o for o in out}
nontrivial = [o for o in out if o["status"] != "DONE" or o["verdict"] != "CONFIRMED"]
assert set(ORDER) == {o["id"] for o in nontrivial}, ({o["id"] for o in nontrivial} ^ set(ORDER))
for i in ORDER:
    o = byid[i]
    A(f"### {o['id']} — {o['title_de']}")
    A("")
    A(f"- **Status:** {o['status']} · **Verdict:** {o['verdict']} · **Priority:** {o['prio_old']} → {o['prio_new']}")
    A(f"- **Evidence:** {o['evidence']}")
    A(f"- **Re-evaluation:** {o['reasoning']}")
    A(f"- **Domain separation:** {o['domain_effect']}")
    A(f"- **Security:** {o['security_note']}")
    A("")
A("### Done and confirmed, with a note the modularisation needs")
A("")
NOTE = ["BE-SIMP-04", "BE-SIMP-11", "BE-SIMP-01", "BE-PERF-04", "IMG-PERF-12", "BE-MOD-05", "BE-MOD-05b",
        "XMOD-SIMP-01", "BLD-CI-09", "SEC-17", "BE-PERF-10", "BE-MOD-03", "BE-MOD-04", "BE-SIMP-02",
        "BE-SIMP-06", "APPSEC-06", "ING-SEC-05", "SEC-15"]
for i in NOTE:
    o = byid[i]
    A(f"- **{o['id']}** ({o['domain_effect']}): {o['reasoning']}")
A("")
A("## 4. New observations made while verifying (not among the 76)")
A("")
PSA = [
    ("PSA-01", "The error kernel enumerates domain errors, and Java forbids a sealed class to permit subclasses in other packages",
     "`be:exception/AppException.java:37-50` permits 13 subclasses, among them `BankConflictException`, `ExchangeProblemException`, `MissionParticipantRequiredException`, `OverAllocationException`, `OwnerOrgUnitRequiredException`, `ProductionAllocationException`; `be:exception/AppExceptionKind.java:37-171` has 11 constants including the domain ones; #1985 added one of each. Experiment: `scratchpad/80-prev-sept-a-sealed` (kernel/AppException permits mission.MissionProblem) → javac 25 (Zulu 25) compile error at `kernel/AppException.java:3:83`, diagnostic key `compiler.err.class.in.unnamed.module.cant.extend.sealed.in.diff.package` (`-XDrawDiagnostics`); the same two classes in one package compile (exit 0), so the package boundary alone causes it. Problem codes are a wire contract without a schema: the frontend mirrors them by hand (`fe:service/BackendServiceException.java:85`), the gateway translates them (`in:exchange/ExchangeRelay.java:122, 415`), and `openapi.json` lists none (grep count 0).",
     "Blocks target A as it stands: domain exceptions cannot move into domain packages while the kernel stays sealed over them, and each new domain error edits two shared files.",
     "Keep a sealed kernel of generic kinds plus one `non-sealed abstract` domain-problem base in the kernel that domain packages extend; replace the enum by a `ProblemKind` interface (code, status, title key) implemented by per-domain enums; a registry test collects every implementation and asserts unique codes against a committed list.",
     "Domains own their errors; the kernel stops growing; codes become an explicit, tested contract.",
     "Loses the one-enum overview; adds a registry test.",
     "Security: `GlobalExceptionHandler`/`ErrorDisclosurePolicy` must keep deciding what is echoed (APPSEC-06: no raw messages). Regression: renaming a code silently breaks the frontend toast keys and the exchange translation. Guards: the registry test, `GlobalExceptionHandlerTest`, the frontend `BackendServiceException` tests, the exchange relay tests.",
     "M", "A new ADR on the error model; REQ-API-004 wording; before the first domain package move."),
    ("PSA-02", "The pinned Keycloak client has no timeouts on the production authentication path",
     "`be:config/KeycloakTrustSupport.java:74-75` builds `HttpClient.newBuilder().sslContext(...).build()` and `new JdkClientHttpRequestFactory(httpClient)` without connect or read timeout; it replaces the builder's factory in `be:service/KeycloakService.java:122-125` and backs the internal JWKS decoder in `be:config/SecurityConfig.java:177-181`. The intended values are `be:config/RestClientConfig.java:92-97` (5 s, 30 s, HTTP/1.1). The internal JWKS is on in production since 2026-09-25 (vault Backend.md:405-411).",
     "None on domain separation; availability of authentication (JWKS refresh) and of the user sync.",
     "Give `trustedRequestFactory` the same connect/read timeouts and HTTP version (backend; the ingest copy `in:config/KeycloakTrustSupport.java:73-74` for consistency), or build the pinned factory through `RestClientConfig`'s factory with an SSL-context parameter.",
     "Bounded failure instead of a hang; one client shape (ADR-0204).",
     "A read timeout that is too short could fail a large admin listing; use the 30 s the other clients use (the sync already skips a failed run).",
     "Security: trust pinning and hostname verification unchanged. During a Keycloak outage requests fail fast (401/503) instead of hanging. Guards: a new test asserting the pinned factory's timeouts, `SecurityConfigInternalJwksDecoderTest`, `KeycloakServiceTest`.",
     "S", "None."),
    ("PSA-03", "Hand-mirrored platform classes have already diverged across the three apps",
     "Same-named classes in backend, frontend and ingest whose bodies all differ (md5 of the non-import lines): `ManagementPortSecurityConfig` (73/63/60 lines), `MonitoringScrapeProperties` (67/61/60; the backend record redacts the password, the frontend and ingest `@Data` classes print it in `toString`), `TracingEnabledMetric`, `CorrelationIdFilter` (235/210/82), `StartupBannerListener`; `KeycloakTrustSupport` in backend and ingest differs only in Javadoc (diff).",
     "Neutral for domain separation; it is the cross-app drift risk the July audit named for security contracts.",
     "Short term: ING-MOD-02 (record + redacting `toString` in ingest and frontend) and a parity test for the security-relevant ones. Medium term: evaluate a second scope-closed module for platform concerns (management-port chain, scrape credentials, trust support, tracing gauge) under its own ADR, since ADR-0205 closes `logging-support` to log hygiene.",
     "One implementation of security-relevant infrastructure.",
     "A shared module ties the three apps' release cadence together; some classes are app-specific by role (the correlation filters differ for good reasons).",
     "Security: a shared management-port chain must keep each app's actuator exposure; guard `ManagementPortIsolationTest` in every app.",
     "M", "New ADR (platform module) or a documented decision to keep mirrors with parity tests."),
    ("PSA-04", "September fixes grew `support` into a domain-helper hub",
     "`be:support` holds 63 classes (ls), among them domain helpers (`MissionPeerRedactor`, `MissionSectionVersions`, `MissionViewerAccess`, `InventoryAllocations`, `InventoryAuditLabels`, `JobOrderAuditLabel`, `JobOrderInventoryOwnerRedactor`, `StockViewerAccess`, `StaffelMembershipResolver`) and 18 of the 27 `@ConfigurationProperties` records. September added `RequestMemo` (#2011, generic), `CachedEntityGraphs` (#2030, catalogue-specific) and the three retention records (#1989). The leaf rule permits support → model/repository only and its messages tell authors to put shared logic in support (`be-test ArchitectureTest.java:577-599, 609-611, 624-626`).",
     "Hinders: support is acyclic by rule but collects domain logic, so it becomes a de-facto shared domain module.",
     "At the first module step split support into a small shared kernel (RequestMemo, StringNormalization, OptimisticLock, LikePatterns, ProblemResponseFactory, Roles/Permissions) and per-domain internal packages; change the ArchUnit messages to point at the owning module.",
     "Clear ownership; smaller kernel.",
     "Many moves at once; the acyclicity guarantee must be carried over.",
     "Security: the redaction and viewer-access helpers (MissionPeerRedactor, JobOrderInventoryOwnerRedactor, UserDtoRedaction, MissionViewerAccess, StockViewerAccess) carry REQ-SEC-007 and owner redaction; their ArchUnit rules must be re-keyed in the same commit. Guards: `backendPackagesShouldBeFreeOfDependencyCycles`, the redaction rules, `MissionDataLeakTest`.",
     "M", "Part of the module steps; ADR-0047 wording on the support leaf."),
    ("PSA-05", "Vault drift found while verifying (read-only here; for the vault owner)",
     "1) `80 Plans/Improvement Audit 2026-09.md`'s Done table has no row for BE-SIMP-04 and BE-SIMP-05 (both done: #1994 introduced ParticipantTargetResolver, #1996 finished the redaction helper) nor for BLD-PERF-03 (open, and not marked open either). 2) `30 Roles and Permissions/Security.md:880-881` says the suppressions file's header explains renewal — the header is gone since #2074 and no document describes it (SEC-16). 3) `80 Plans/Improvement Audit 2026-09.md:118` marks APPSEC-02 done — the picker restriction was deferred by #1985 and is open (`fe:controller/RefineryOrderPageController.java:814`). 4) same note :120 lists THEME-SIMP-01 as done — the shared-font part is not. 5) same note :136: BE-SIMP-10's frontend half (482 names) is open and unstated. 6) `10 Systems/Backend.md:368-369` 'All 16 @ConfigurationProperties are records' — 27 today (still all records); :373-374 '`trimToNull` exists once' — a second copy since a2e82ff34; :344-347 the findPlainById caller list — three reference-only callers use the graphed `findById`. 7) `10 Systems/Ingest.md:713-714` 'the configuration properties are records' — `MonitoringScrapeProperties` is still `@Data`.",
     "Stale notes read as authoritative (vault CLAUDE rule: correct and date).",
     "Correct each note, dated, in the same session as the implementing change.",
     "Vault matches `main` again.", "None.", "None; no secret or personal data involved.", "S", "None."),
]
for p in PSA:
    pid, title, ev, impact, change, pros, cons, risks, effort, prereq = p
    A(f"### {pid} — {title}")
    A("")
    A(f"- **Evidence:** {ev}")
    A(f"- **Impact on domain separation / modern features:** {impact}")
    A(f"- **Proposed change:** {change}")
    A(f"- **Pros:** {pros}")
    A(f"- **Cons:** {cons}")
    A(f"- **Risks, regressions, security and guard:** {risks}")
    A(f"- **Effort:** {effort} · **Prerequisites:** {prereq}")
    A("")
A("## 5. Re-evaluation against the current framework — cross-cutting remarks")
A("")
A("- **ADR-0223 (final features only):** every Java construct the September fixes introduced is final — records (BE-MOD-04, ING-MOD-02), `getFirst`/`getLast` and pattern-matching `switch` (BE-MOD-06), virtual threads (BE-PERF-13), the JDK 25 AOT cache (IMG-MOD-11, ADR-0209:40-41 names JEP 483/514/515). No `--enable-preview` anywhere (ADR-0223 decision 1). The sealed error hierarchy is final Java too, but its package rule constrains target A (PSA-01).")
A("- **ADR-0214 (no comments):** three findings were about comments (APPSEC-09, ING-SEC-01, DOC-20); their object no longer exists. One durable fact was lost in the sweep — the OWASP suppression renewal procedure (SEC-16) — and needs a document.")
A("- **Security posture:** no re-evaluation weakens a control. Every recommendation above either keeps the existing guard (listed per finding) or adds one (timeouts, redacting `toString`, registry test for problem codes, explicit coverage floors). The two items that move security checks (APPSEC-01 into an inventory API, BE-SIMP-05 into the mission read API) keep the check before any lookup and keep their tests.")
A("- **Target options:** option A (packages) is blocked only by PSA-01 and must re-key the package-based guards (BE-SIMP-05, PSA-04); option B/C additionally needs BLD-SIMP-06's floor fix, IMG-SIMP-14's Dockerfile list, the `Entities.require` ratchet's source roots (BE-SIMP-01) and `verification-metadata.xml` regeneration for any new plugin (SEC-15).")
A("")
A("## 6. Data appendix")
A("")
A("### Merge commits of the implementing PRs (`git log --oneline --grep=\"(#NNNN)\" origin/main`)")
A("")
A("| PR | Commit | IDs in scope |")
A("| --- | --- | --- |")
PRT = [
    ("#1985", "160e0dba4", "APPSEC-02"),
    ("#1989", "3340c1211", "APPSEC-01, -06, -08, -09, -10, BE-MOD-03"),
    ("#1990", "26d1c1223", "ING-SEC-01/-02/-03/-05, ING-SIMP-01/-02/-03, ING-PERF-02, ING-MOD-02, KC-CI-01, KC-PERF-01, KC-SIMP-01, THEME-SEC-01, THEME-SIMP-01, TS-SIMP-01"),
    ("#1994", "df111eb1a", "BE-SIMP-02 (migration), BE-SIMP-03, BE-SIMP-04, BE-SIMP-05 (part)"),
    ("#1996", "5ab7ff01e", "BE-SIMP-02 (deletion), BE-SIMP-05 (rest)"),
    ("#2004", "ccc886d28", "BE-PERF-01, -02, -03, -10, -12, -15"),
    ("#2008", "cc3c5d090", "BE-MOD-01, BE-MOD-02, ING-MOD-01, ING-PERF-01 (moot)"),
    ("#2009", "d90dc295c", "BE-PERF-04, BE-PERF-09"),
    ("#2011", "fc85cfb7f", "BE-SIMP-01, -06, -07, -08, -09, -10 (backend), -11, BE-MOD-04, -05, -05b, -06"),
    ("#2015", "ba54a2c31", "BE-SIMP-01, BE-MOD-05b (follow-ups)"),
    ("#2016", "e2567f18b", "XMOD-SIMP-01"),
    ("#2017", "28e6113d5", "BE-PERF-08"),
    ("#2019", "b49547283", "BLD-PERF-02, -04, -07, -08, -10, BLD-SIMP-06, BLD-CI-09, SEC-16, SEC-17, TST-18, TST-19, DOC-20"),
    ("#2023", "8893acfcb", "APPSEC-04"),
    ("#2024", "df3fe12df", "BE-PERF-13"),
    ("#2025", "135f4c9bb", "SEC-15 (+ #2040 6644d6421)"),
    ("#2027", "4af1a2065", "BE-PERF-14"),
    ("#2029", "6f419c55e", "BLD-PERF-01, IMG-SIMP-14, IMG-MOD-11, IMG-PERF-12, IMG-CI-13 (+ #2050 1842ddbdf)"),
    ("#2030", "c3f522f0c", "BE-PERF-11"),
    ("#2034/#2036", "85c773061 / 35350483b", "ING-SEC-04"),
    ("—", "674e55bd7", "DOC-21"),
    ("#2270", "7866985ed", "later: removed the legacy /v1 ingest routes; reshaped ING-SEC-02/-03/-05, ING-SIMP-02, ING-MOD-02"),
    ("—", "a2e82ff34", "later: exchange registry; introduced the BE-SIMP-08 regression"),
]
for r in PRT:
    A(f"| {r[0]} | {r[1]} | {r[2]} |")
A("")
A("### Commands and scripts")
A("")
A("- Selection: `python 80-prev-sept-a-extract.py` (filters `sept_audit_findings.json` to the four areas → 76; writes `80-prev-sept-a-selected.json`).")
A("- Inline FQNs (BE-SIMP-10): `python 80-prev-sept-a-fqn.py backend/src/main/java frontend/src/main/java ingest/src/main/java keycloak-spi/src/main/java backend/src/test/java frontend/src/test/java ingest/src/test/java` → 19 / 482 / 3 / 0 / 1214 / 1051 / 23 (comments, strings, imports excluded).")
A("- Test contexts (BLD-PERF-03): `python 80-prev-sept-a-contexts.py backend/src/test/java frontend/src/test/java ingest/src/test/java` → backend 231 classes / ~49 keys (40 used once, 35 with mock beans); frontend 161 / ~26; ingest 21 / ~13. Static approximation: no meta-annotations or context customizers evaluated. Profile split: `grep -rl @SpringBootTest <root> | xargs grep -L @ActiveProfiles | wc -l` → backend 40, frontend 119, ingest 21.")
A("- Sealed-class rule (PSA-01): in `80-prev-sept-a-sealed/` with javac 25 (Zulu 25): `javac -XDrawDiagnostics -d out3 kernel/AppException.java mission/MissionProblem.java` → `compiler.err.class.in.unnamed.module.cant.extend.sealed.in.diff.package`, exit 1; control `javac -d out2 same/AppException.java same/MissionProblem.java` → exit 0.")
A("- Properties records: loop over `grep -rl @ConfigurationProperties backend/src/main/java` checking `public record` → 27 records, 0 classes; ingest 7 records + `MonitoringScrapeProperties` (`@Data`).")
A("- Lazy associations: `grep -rn` for `@ManyToOne` or `@OneToOne` under `backend/src/main/java/.../backend/model`, then `grep -c LAZY` → 150 hits, 148 with `FetchType.LAZY`; the other two are Javadoc text (`OrgUnitMembershipId.java:55`, `TermsAcceptance.java:58`).")
A("- New cross-domain edges: `git show <merge>^1:<file> | grep -c <Type>` before vs `grep -c` today (RefineryOrderService/MissionParticipantRepository 0 → present; HangarController/UserMapper 0 → 2; OrgRoleManagementSecurityService caller memo 0 → 6 before #2011).")
A("- jdeps edges: `jdeps-backend.txt` (coordinator), e.g. `grep \"service.RefineryOrderService -> \" jdeps-backend.txt`.")
A("- No Gradle run (briefing rule); no host or Prometheus access. Output assembled by `python 80-prev-sept-a-build.py` from `80-prev-sept-a-data.py`.")
A("")

with open(os.path.join(BASE, "80-prev-sept-a.md"), "w", encoding="utf-8") as f:
    f.write("\n".join(L))

print("status", dict(st))
print("verdict", dict(vd))
print("cross", dict(cross))
print("nontrivial", len(nontrivial))
