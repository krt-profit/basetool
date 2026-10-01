# 81-prev-sept-b — Re-evaluation of the 2026-09-22 Improvement Audit: Frontend, CI, Operations

Scope: the 80 findings of `sept_audit_findings.json` whose `area` is **Frontend** (30), **CI** (29)
or **Betrieb** (21), verified against the worktree `$REPO`
(branch `claude/basetool-refactor-modularization-68184f` = `origin/main` = `95e945326`, read 2026-09-29)
and against the live repository settings through read-only `gh api` GET calls (listed in the
appendix). The 20 sibling-repository findings (Android 11, Extractor 7, P4K Reader 2) are listed
OUT-OF-SCOPE with the status the vault note `80 Plans/Improvement Audit 2026-09.md` records, without
verification. Nothing was changed in the repository, the vault or GitHub.

Status = what the code, workflows, units and settings show today. Verdict = the re-evaluation under
the current framework (domain separation first; ADR-0223 final Java only; ADR-0214 no comments; no
security weakening; new frameworks only with evidence). `†` = the code is done, but a host-side part
(rollout, host configuration) is NOT-VERIFIABLE from here — those seven are APPSEC-05, APPSEC-07, CI-SEC-01, OPS-SEC-01, OPS-PRIV-01, OPS-SIMP-03, OPS-SEC-05.

## 1. Summary

### Counts (in-scope, 80 findings)

| Status | Count |
|---|---|
| DONE | 73 |
| PARTIAL | 7 |
| OPEN | 0 |
| SUPERSEDED | 0 |
| REGRESSED | 0 |
| NOT-VERIFIABLE | 0 |

| Verdict | Count |
|---|---|
| CONFIRMED | 66 |
| ADJUSTED | 12 |
| SUPERSEDED-BY-MODULARISATION | 1 |
| REPRIORITISED | 1 |
| DROPPED | 0 |

| Remaining priority | Count |
|---|---|
| P0 | 0 |
| P1 | 5 |
| P2 | 7 |
| P3 | 2 |
| closed | 66 |

| Area | In scope | DONE | PARTIAL |
|---|---|---|---|
| Frontend | 30 | 27 | 3 |
| CI | 29 | 26 | 3 |
| Betrieb | 21 | 20 | 1 |

Sibling findings listed OUT-OF-SCOPE: 20 (Android 11, Extractor 7, P4K Reader 2); per the vault 19 are done and 1 (SIB-SEC-01) is decided as won't-do.

### The ten most important conclusions

1. **The September work landed.** 73 of 80 in-scope findings are DONE on `origin/main`, 7 are
   PARTIAL, none is OPEN or REGRESSED; every implementation PR the vault names is on `main`
   (`git log --oneline --grep="(#NNNN)" origin/main`, appendix A).
2. **CI-03 (PIT) is not closed on CI.** The only scheduled run since the fix (35827836688,
   2026-09-23) had `PIT (backend)` cancelled at the 60-minute limit after 13 minion timeouts, and the
   gate step still passed on a partial `mutations.xml` (9,481 mutations, job log line 983); PIT's
   `targetClasses` are keyed on `…service.*` (`build.gradle.kts:267-268`) and must be re-keyed by a
   package-by-domain move (PSB-04).
3. **FE-SIMP-02's open half is the frontend half of the domain split.** 623 `backendApiClient`
   call sites in 81 files, 362 with concatenated URIs; Spring 7.0.9 already ships
   `@HttpExchange`/`WebClientAdapter`/`ImportHttpServices`, so per-domain typed clients over the one
   filtered `WebClient` need no new dependency — and arc42 §4.1's "exactly one class" is already
   untrue (13 classes besides `BackendApiClient` inject a `WebClient` bean) (PSB-01).
4. **The session allow-list is a modularisation hazard.** `SessionTypeAllowList.java:86-87` admits
   application classes by the prefix `frontend.model.`; moving session-held forms/DTOs into
   per-domain packages breaks `enforce`, and widening the prefix would weaken it. The code default is
   still `report` (`RedisSessionConfig.java:89`) although production enforces (PSB-02).
5. **`/api/v1/me/layout` (FE-PERF-01) is a composition hub by design.** `MeController.java:104-115`
   computes blueprint, job-order, bank and inventory capability flags itself; in the modular target
   it becomes a shell module fed by per-domain capability queries (PSB-03).
6. **Two repo-lint jobs are not required checks.** `Self-tests` and `Container checks` (promtool
   rule unit tests, monitoring-config validation, deploy-seam self-tests) are outside the nine
   required checks of ruleset 16482244 — a PR can merge with a broken alert rule (PSB-06).
7. **Several CI gates key on today's six Gradle modules and flat folders.** The app Dockerfile,
   `image_reuse_plan.py` (ADR-0210), the SBOM-coverage gate, the sandbox path filter, the Flyway
   check and PIT — neutral for Option A, a blocker list for Option B/C; two frontend gates list files
   non-recursively and would silently narrow under per-domain folders (PSB-05).
8. **OPS-SEC-03 still fails open.** When `db-backend.container` cannot be read, backup and restore
   drill continue with the unpinned `postgres:18-alpine` — the helper that reads the keystore,
   internal TLS and Redis ACL (`backup.sh:29,95-96`; `restore-drill.sh:19,78-79`) (PSB-07).
9. **The only crash-loop alert has no absence guard.** `ContainerRestartLoop` now reads the podman
   exporter, but neither of its series is in `REQUIRED_CONTAINER_SERIES`
   (`check-conformance.py:74-81`) or behind an `absent()` rule (PSB-08).
10. **Small remainders on three CI security items.** CI-01's cache guard is not met (9.39 GiB of
    10 GiB on 2026-09-29, now driven by Gradle caches and Code Quality's CodeQL cache), CI-SEC-12
    still has unhashed pip/Ansible/npx fetches (one added 2026-09-27), and CI-SEC-16's App key is not
    confined to a `main`-only environment (PSB-11…13).

## 2. All 100 findings

| # | ID | Area | Prio (09-22) | Status | Verdict | Prio now | Note |
|---|---|---|---|---|---|---|---|
| 1 | APPSEC-03 | Frontend | P1 | DONE | CONFIRMED | closed | 8 MiB cap before any read, upload streamed (HangarImportProxyController.java:64,115,128) |
| 2 | APPSEC-11 | Frontend | P1 | DONE | CONFIRMED | closed | ResponseStatusException handler keeps the backend status (GlobalExceptionHandler.java:415-454) |
| 3 | APPSEC-12 | Frontend | P1 | DONE | CONFIRMED | closed | Logs carry a 12-hex SHA-256 fingerprint, never the session id |
| 4 | BE-PERF-05 | Frontend | P1 | DONE | CONFIRMED | closed | Roster read removed (25430fd7e, in #2004) |
| 5 | CI-SEC-18 | Frontend | P1 | DONE | CONFIRMED | closed | npm ci + ignore-scripts (build.gradle.kts:472, frontend/.npmrc:1) |
| 6 | FE-MOD-04 | Frontend | P1 | DONE | CONFIRMED | closed | Jackson 3 in MissionWriteController; Thymeleaf serializer config is the last Jackson-2 user |
| 7 | FE-PERF-04 | Frontend | P1 | DONE | CONFIRMED | closed | Only 7 basetool-* logos left (24,987 bytes) |
| 8 | FE-PERF-06 | Frontend | P1 | DONE | CONFIRMED | closed | Six named reloads gone; 19 left are fallbacks or the sanctioned conflict confirm |
| 9 | FE-SEC-01 | Frontend | P1 | DONE | CONFIRMED | closed | Template variables, Instant binding, status allow-list; the remaining concatenation class goes to FE-SIMP-02 |
| 10 | FE-SEC-02 | Frontend | P1 | DONE | CONFIRMED | closed | Same-origin path check and UUID binding (MeFrontendController.java:86-98) |
| 11 | FE-SEC-04 | Frontend | P1 | DONE | CONFIRMED | closed | csp_violation beacon kind, metric, dashboard and alert text |
| 12 | BE-PERF-06 | Frontend | P2 | DONE | CONFIRMED | closed | Paged /users/search/references with UserReferenceDto, same gates |
| 13 | FE-MOD-01 | Frontend | P2 | DONE | CONFIRMED | closed | 0 hex fallbacks; a Stylelint rule forbids them |
| 14 | FE-MOD-03 | Frontend | P2 | PARTIAL | ADJUSTED | P2 | ESLint rules done; 44 of 100 scripts type-checked, none of the 3 largest |
| 15 | FE-PERF-01 | Frontend | P2 | DONE | ADJUSTED | P2 | One /me/layout read; the endpoint is a cross-domain composition that belongs in a shell module |
| 16 | FE-PERF-02 | Frontend | P2 | DONE | CONFIRMED | closed | No comments or `<style>` in templates; 54 page stylesheets; sprite still inline |
| 17 | FE-PERF-03 | Frontend | P2 | DONE | CONFIRMED | closed | ETag filter only on the manifest and assetlinks |
| 18 | FE-PERF-05 | Frontend | P2 | DONE | CONFIRMED | closed | Every script defer except krt-client-error.js; optional minification dropped |
| 19 | FE-PERF-07 | Frontend | P2 | DONE | CONFIRMED | closed | Tomcat thread keys gone; panel reads http_server_requests_active |
| 20 | FE-SEC-03 | Frontend | P2 | DONE | CONFIRMED | closed | Lint bans non-GET raw fetch; 0 raw writes left (55 raw GETs) |
| 21 | FE-SEC-05 | Frontend | P2 | DONE | CONFIRMED | closed | no-unsanitized lint, one escape helper, one sanctioned HTML sink |
| 22 | FE-SIMP-01 | Frontend | P2 | DONE | CONFIRMED | closed | 111 relay() uses in 28 controllers; one deliberate bespoke block |
| 23 | FE-SIMP-02 | Frontend | P2 | PARTIAL | SUPERSEDED-BY-MODULARISATION | P2 | exchange() done; typed @HttpExchange clients open = the frontend half of the domain split |
| 24 | FE-SIMP-03 | Frontend | P2 | PARTIAL | ADJUSTED | P2 | Shared Lager script done; mission-detail.js keeps 7 German literal fallbacks the gate cannot see |
| 25 | FE-SIMP-04 | Frontend | P2 | DONE | CONFIRMED | closed | window.krtModal and the modal wrapper everywhere |
| 26 | APPSEC-05 | Frontend | P3 | DONE† | ADJUSTED | P1 | Allow-list done, prod enforce per docs; code default still report; package-prefix hazard for per-domain packages |
| 27 | APPSEC-07 | Frontend | P3 | DONE† | CONFIRMED | closed | Confidential client whenever the secret is set; PKCE always |
| 28 | FE-MOD-02 | Frontend | P3 | DONE | CONFIRMED | closed | All 64 stylesheets in cascade layers (ADR-0212) |
| 29 | FE-SEC-06 | Frontend | P3 | DONE | CONFIRMED | closed | `__Host-SESSION` cookie |
| 30 | FE-SIMP-04b | Frontend | P3 | DONE | CONFIRMED | closed | Native `<dialog>` in the one wrapper; ADR-0177 amended |
| 31 | CI-01 | CI | P0 | DONE | ADJUSTED | P2 | Advanced CodeQL only; Actions cache still 9.39 of 10 GiB (Gradle and Code Quality caches) |
| 32 | CI-02 | CI | P0 | DONE | CONFIRMED | closed | Scan finishes again since the NVD JSON 2.0 switch; the failed scheduled run opened #2262 |
| 33 | CI-03 | CI | P0 | PARTIAL | ADJUSTED | P1 | Gate added, but the backend PIT leg timed out at 60 min and passed on a partial report |
| 34 | CI-09 | CI | P1 | DONE | CONFIRMED | closed | One setup composite in 9 workflows; no chmod |
| 35 | CI-11 | CI | P1 | DONE | CONFIRMED | closed | Image-pin self-test wired before the check |
| 36 | CI-12 | CI | P1 | DONE | CONFIRMED | closed | e2e-smoke dispatch-only; CodeQL active |
| 37 | CI-13 | CI | P1 | DONE | CONFIRMED | closed | github-actions updates grouped |
| 38 | CI-16 | CI | P1 | DONE | CONFIRMED | closed | Crons off the hour; late starts documented |
| 39 | CI-SEC-01 | CI | P1 | DONE† | CONFIRMED | closed | All identity regexps anchored plus a gate; host copy not verifiable |
| 40 | CI-SEC-02 | CI | P1 | DONE | CONFIRMED | closed | Tag ruleset creation/update (owner + App bypass); ref-guard with ancestor check |
| 41 | CI-SEC-03 | CI | P1 | DONE | CONFIRMED | closed | production/testing main-only; promotions refuse other refs |
| 42 | CI-SEC-05 | CI | P1 | DONE | CONFIRMED | closed | contents: read in ci.yml and refresh-versions.yml |
| 43 | CI-SEC-06 | CI | P1 | DONE | CONFIRMED | closed | 36 of 36 checkouts persist-credentials: false |
| 44 | CI-SEC-08 | CI | P1 | DONE | CONFIRMED | closed | Dependency graph holds 944 pkg:maven entries |
| 45 | CI-SEC-10 | CI | P1 | DONE | ADJUSTED | P1 | 9 required checks; the Self-tests and Container checks jobs are still not required |
| 46 | CI-SEC-11 | CI | P1 | DONE | CONFIRMED | closed | gitleaks config from the base commit; non-provider/validity checks unavailable on the plan |
| 47 | CI-SEC-12 | CI | P1 | PARTIAL | ADJUSTED | P2 | Binaries hashed; PyYAML, Ansible and an npx fetch still unhashed |
| 48 | CI-SEC-14 | CI | P1 | DONE | CONFIRMED | closed | SHA pinning required, selected owners, approval for external contributors |
| 49 | CI-SEC-15 | CI | P1 | DONE | CONFIRMED | closed | zizmor (hash-pinned) inside the required Linters job |
| 50 | CI-SEC-17 | CI | P1 | DONE | CONFIRMED | closed | Session material stripped from the smoke artifacts |
| 51 | OPS-CI-01 | CI | P1 | DONE | CONFIRMED | closed | promtool/amtool/alloy/loki config checks in CI (non-required job) |
| 52 | OPS-CI-02 | CI | P1 | DONE | CONFIRMED | closed | Path filter covers scripts/lib/** and render-env-d.py |
| 53 | CI-04 | CI | P2 | DONE | CONFIRMED | closed | repo-lint is four jobs |
| 54 | CI-06 | CI | P2 | DONE | CONFIRMED | closed | E2E images built once, one browser per cell (ADR-0200) |
| 55 | CI-08 | CI | P2 | DONE | CONFIRMED | closed | retag-verified-digest composite shared by both promotions |
| 56 | CI-SEC-04 | CI | P2 | DONE | CONFIRMED | closed | Per-job permissions; the SPI is compiled without id-token |
| 57 | CI-SEC-13 | CI | P2 | DONE | CONFIRMED | closed | BuildKit pinned by digest through a carrier Dockerfile |
| 58 | CI-SEC-16 | CI | P2 | PARTIAL | ADJUSTED | P2 | PATs deleted, App tokens; the App key is not confined to a main-only environment |
| 59 | CI-07 | CI | P3 | DONE | ADJUSTED | P3 | Per-module re-tag (ADR-0210); input derivation must change before any Gradle-subproject split |
| 60 | OPS-MON-01 | Betrieb | P0 | PARTIAL | ADJUSTED | P1 | Alert reads the podman series now; no absence guard or conformance entry for them |
| 61 | OPS-PERF-01 | Betrieb | P0 | DONE | CONFIRMED | closed | StopTimeout and TimeoutStopSec for all 9 units that declare a grace |
| 62 | OPS-REL-01 | Betrieb | P0 | DONE | CONFIRMED | closed | Changed monitoring units restarted; acme applied |
| 63 | OPS-SEC-01 | Betrieb | P0 | DONE† | CONFIRMED | closed | dnf-automatic security-only, runtime excluded, three alerts |
| 64 | OPS-MON-02 | Betrieb | P1 | DONE | CONFIRMED | closed | DeployHeartbeatStale with an absent() leg |
| 65 | OPS-MON-03 | Betrieb | P1 | DONE | CONFIRMED | closed | PostgresDown, RedisDown, ContainerUnhealthy |
| 66 | OPS-MON-04 | Betrieb | P1 | DONE | CONFIRMED | closed | absent() leg for the cgroup collector |
| 67 | OPS-MON-05 | Betrieb | P1 | DONE | CONFIRMED | closed | Restore-drill threshold 8 days |
| 68 | OPS-PRIV-01 | Betrieb | P1 | DONE† | CONFIRMED | closed | Journal 31d / 4G; REQ-OBS-010 amended |
| 69 | OPS-SEC-02 | Betrieb | P1 | DONE | CONFIRMED | closed | Snapshot via wget inside prometheus; small argv residual accepted |
| 70 | OPS-SEC-03 | Betrieb | P1 | DONE | ADJUSTED | P1 | Pinned helper image, but the fail-open fallback to postgres:18-alpine remains |
| 71 | OPS-SEC-04 | Betrieb | P1 | DONE | CONFIRMED | closed | No realm mount; theme and providers :ro |
| 72 | OPS-SEC-07 | Betrieb | P1 | DONE | CONFIRMED | closed | --web.enable-lifecycle removed |
| 73 | OPS-MOD-01 | Betrieb | P2 | DONE | CONFIRMED | closed | Quadlet keys where they exist; --cpus and --oom-score-adj remain |
| 74 | OPS-SEC-06 | Betrieb | P2 | DONE | CONFIRMED | closed | Conformance compares host agent versions with the compose pins |
| 75 | OPS-SEC-08 | Betrieb | P2 | DONE | REPRIORITISED | P3 | Plain statement now in ansible/README.md:43; optional sudo wrapper still open |
| 76 | OPS-SIMP-01 | Betrieb | P2 | DONE | CONFIRMED | closed | No Docker branch left in the runtime seam |
| 77 | OPS-SIMP-02 | Betrieb | P2 | DONE | CONFIRMED | closed | cAdvisor and NPM leftovers gone; ADR-0203 |
| 78 | OPS-SIMP-03 | Betrieb | P2 | DONE† | CONFIRMED | closed | One sandbox drop-in, drill paths narrowed; no host run evidenced |
| 79 | OPS-SIMP-04 | Betrieb | P2 | DONE | CONFIRMED | closed | scripts/lib/common.sh shared by four scripts |
| 80 | OPS-SEC-05 | Betrieb | P3 | DONE† | CONFIRMED | closed | Internal=true on the five data networks; testing rollout pending |
| 81 | SIB-SEC-06 | Android | P1 | OUT-OF-SCOPE | OUT-OF-SCOPE | - | Done - basetool-android #178, #179, #180, #181 |
| 82 | SIB-SEC-07 | Android | P1 | OUT-OF-SCOPE | OUT-OF-SCOPE | - | Done - basetool-android #178, #179, #180, #181 |
| 83 | SIB-SEC-08 | Android | P1 | OUT-OF-SCOPE | OUT-OF-SCOPE | - | Done - basetool-android #178, #179, #180, #181 |
| 84 | SIB-SIMP-03 | Android | P1 | OUT-OF-SCOPE | OUT-OF-SCOPE | - | Done - basetool-android #178, #179, #180, #181 |
| 85 | SIB-CI-01 | Android | P2 | OUT-OF-SCOPE | OUT-OF-SCOPE | - | Done - basetool-android #178-#181; runs on a path filter and nightly because no instrumented label exists |
| 86 | SIB-CI-02 | Android | P2 | OUT-OF-SCOPE | OUT-OF-SCOPE | - | Done - basetool-android #178, #179, #180, #181 |
| 87 | SIB-CI-05 | Android | P2 | OUT-OF-SCOPE | OUT-OF-SCOPE | - | Done - basetool-android #178, #179, #180, #181 |
| 88 | SIB-MOD-01 | Android | P2 | OUT-OF-SCOPE | OUT-OF-SCOPE | - | Done - basetool-android #178, #179, #180, #181 |
| 89 | SIB-SIMP-01 | Android | P2 | OUT-OF-SCOPE | OUT-OF-SCOPE | - | Done - basetool-android #178, #179, #180, #181 |
| 90 | SIB-SIMP-02 | Android | P2 | OUT-OF-SCOPE | OUT-OF-SCOPE | - | Done - basetool-android #178, #179, #180, #181 |
| 91 | SIB-SIMP-04 | Android | P2 | OUT-OF-SCOPE | OUT-OF-SCOPE | - | Done - basetool-android #178, #179, #180, #181 |
| 92 | SIB-CI-03 | Extractor | P1 | OUT-OF-SCOPE | OUT-OF-SCOPE | - | Done for the extractor - basetool-sc-extractor #58, #59; the reader half does not apply |
| 93 | SIB-MOD-02 | Extractor | P1 | OUT-OF-SCOPE | OUT-OF-SCOPE | - | Done for the extractor - basetool-sc-extractor #58, #59; the reader half does not apply |
| 94 | SIB-SEC-02 | Extractor | P1 | OUT-OF-SCOPE | OUT-OF-SCOPE | - | Done for the extractor - basetool-sc-extractor #58, #59; the reader half does not apply |
| 95 | SIB-SEC-05 | Extractor | P1 | OUT-OF-SCOPE | OUT-OF-SCOPE | - | Done for the extractor - basetool-sc-extractor #58, #59 |
| 96 | SIB-SEC-09 | Extractor | P1 | OUT-OF-SCOPE | OUT-OF-SCOPE | - | Done for the extractor - basetool-sc-extractor #58, #59 |
| 97 | SIB-PERF-01 | Extractor | P2 | OUT-OF-SCOPE | OUT-OF-SCOPE | - | Done for the extractor - basetool-sc-extractor #58, #59 |
| 98 | SIB-SEC-04 | Extractor | P3 | OUT-OF-SCOPE | OUT-OF-SCOPE | - | Decided 2026-09-22 (non-exportable CNG key, members sign in once) and done for the extractor - basetool-sc-extractor #58, #59; the spec wording is basetool #1998 (08c27e1ce, on main) |
| 99 | SIB-CI-04 | P4K Reader | P2 | OUT-OF-SCOPE | OUT-OF-SCOPE | - | Done 2026-09-23 in sc-file-reader (local, unversioned, no PR by design): the extractor's package-msi.ps1 ported with a checksum-pinned WiX 7 bootstrap and a JDK preflight; install, upgrade and uninstall verified on the workstation |
| 100 | SIB-SEC-01 | P4K Reader | P3 | OUT-OF-SCOPE | OUT-OF-SCOPE | - | Decided 2026-09-22: the P4K Reader stays local and gets no repository; its CI recommendations do not apply |

Full evidence, reasoning, domain effect and security note for every row: `81-prev-sept-b.json`.

## 3. The non-trivial re-evaluations

Stable IDs `PSB-nn`. Each block: title · evidence · impact on domain separation / modern features ·
proposed change · pros · cons · risks and regressions (security first) with the guard · effort ·
prerequisites.

### PSB-01 — Per-domain typed backend clients over the single filter chain (FE-SIMP-02, absorbs the rest of FE-SEC-01)

- **Evidence.** One private `exchange(...)` carries every verb (`BackendApiClient.java:395-407`), but
  the client is still one 539-line class with eight public verb overloads plus `getCached`
  (`:143-377`). 623 call sites in 81 files, 362 with a concatenated first argument, 17 with a URI
  template (script `81-prev-sept-b-apicalls.py`); a type heuristic flags 30 concatenations of a
  `String` variable, and seven spot checks were all safe (validated id at
  `ConnectedAppsRelayController.java:70`, template-bound search in `HangarPageController.java:120`,
  backend UUIDs in `PromotionPageController.java:151-422`) — correct by review, not by construction.
  Four `WebClient` beans (`WebClientConfig.java:457,511,548,570`, `resilienceFilter` `:311` applied
  at `:484`, `:530`); 13 classes besides `BackendApiClient` and `WebClientConfig` inject a `WebClient`
  bean directly — 11 the filtered `webClient` (streaming and multipart relays), plus the SSE relay
  and the live-sync probe on their two unfiltered beans (`:536-578`) — while arc42 `04-solution-strategy.md:9` says the frontend "reaches the backend through
  exactly one class". The frontend `ArchitectureTest` has no rule about `WebClient` use.
  `spring-web` and `spring-webflux` 7.0.9 (`gradle/verification-metadata.xml:8155,8166`) contain
  `HttpExchange`, `HttpServiceProxyFactory`, `ImportHttpServices`, `WebClientAdapter` and
  `WebClientHttpServiceGroupConfigurer` (`unzip -l` of the cached jars, appendix B).
- **Impact.** Domain separation: today every frontend domain depends on one client class; the change
  gives each domain package its own interface plus its DTO mirrors, which is Option A's frontend
  counterpart. Modern features: Spring Framework 7 HTTP interface clients — already on the classpath.
- **Proposed change.** (1) Move the failure semantics out of `BackendApiClient` — problem+json →
  `BackendServiceException`, `ReauthenticationRequiredException`, the access-gate log suppression
  (`:136-140`) and `basetool_backend_client_errors_total` (`:106-123`) — into an
  `ExchangeFilterFunction` (or one reusable adapter) on the authenticated `webClient` bean.
  (2) Build one `HttpServiceProxyFactory` from `WebClientAdapter.create(webClient)` in a single
  configuration class. (3) Per domain, an interface such as `MissionBackendClient` with
  `@GetExchange("/api/v1/missions/{id}")` methods; migrate that domain's call sites, then the next.
  (4) Keep `getCached`/page-walk in a small catalogue facade. (5) ArchUnit: `HttpServiceProxyFactory`
  only in that class; `WebClient` injection only in an allow-listed set of streaming relays.
  (6) Amend ADR-0032 and arc42 §4.1: the seam is the filter chain; `BackendApiClient` shrinks and
  finally goes.
- **Pros.** URI encoding by construction (removes the FE-SEC-01 class for good); typed signatures;
  per-domain ownership and review; per-client MockWebServer tests; an interface can be diffed against
  the domain's slice of `openapi.json`.
- **Cons.** A large mechanical migration; two call styles during it; the failure-semantics move is
  delicate; a blocking return type makes the adapter block, whose timeout must be aligned with the
  Resilience4j time limiter.
- **Risks and guards.** *Security:* a proxy built on `termsDocumentClient` or a fresh `WebClient`
  would drop the bearer relay or the `X-Active-Org-Unit-Id` header → ArchUnit rule plus a MockWebServer
  test per client asserting `Authorization` and the org-unit header; the terms-client isolation stays
  pinned by `TermsDocumentClientUsageTest` (REQ-SEC-052); retries stay idempotent-only (ADR-0032) →
  existing resilience tests per verb. *Behaviour:* error-mapping drift → port `BackendApiClient*Test`
  to the filter; double encoding (vault `Frontend.md:717`, "A relayed free-text query gets encoded
  twice") → MockWebServer asserts the raw request path; metric labels stay bounded (REQ-OBS-011).
  UNKNOWN: whether the HTTP service registry (`@ImportHttpServices` + group configurer) reuses an
  existing bean's filters or builds its own per group — avoid the question by building the proxies
  from the existing bean, or settle it with a spike test.
- **Effort.** L overall; M for the filter move, then S–M per domain.
- **Prerequisites.** The backend domain APIs (interfaces should mirror stable per-domain
  controllers); an ADR amending ADR-0032 and arc42 §4.1; ordering after PSB-02 for any domain whose
  session-held forms move package.

### PSB-02 — Session allow-list: `enforce` by default, and per-domain packages designed before any move (APPSEC-05)

- **Evidence.** `SessionTypeAllowList.java:86-87` `ALLOWED_PREFIXES` = `org.springframework.security.`
  and `de.greluc.krt.profit.basetool.frontend.model.`; default `report`
  (`RedisSessionConfig.java:89`, `docker-compose.yml:303`); E2E runs `enforce`
  (`docker-compose.e2e.yml:51`); production `enforce` since 2026-09-25 per `docs/adr/README.md:276`
  and `docs/deployment.md:905-932` (host value NOT-VERIFIABLE here). The frontend is package-by-layer:
  `model` holds 325 classes, `controller` 103 (`find … -name '*.java' | wc -l`).
- **Impact.** Domain separation: a per-domain frontend layout (`frontend.mission.…`) would move
  session-held forms and DTO mirrors out of the allowed prefix; under `enforce` they are refused and
  the attribute dropped (ADR-0157) — a silent functional regression.
- **Proposed change.** (a) Default `enforce` in `RedisSessionConfig`, the compose file and the env
  template; `report` only as an explicit opt-in; ADR-0206 amendment. (b) Before moving any
  session-held class: fix the per-domain model package names and let the allow-list enumerate them
  (name matching, as today — no class loading to test a marker interface), pinned by a test that every
  class `SessionSerializerRoundTripTest` stores lies in an allowed package.
- **Pros.** Fail-closed by default on every host; the frontend refactor gets a safe path.
- **Cons.** One more list to keep in step with the package layout.
- **Risks and guards.** *Security:* widening the prefix to `frontend.` would admit every frontend
  class to the deserializer — explicitly ruled out. Flipping the default is low-risk: the report
  counter on production has been zero (vault `Frontend.md:1504-1510`) and E2E already enforces.
  Guards: `SessionSerializerRoundTripTest`, `RedisSessionImportFlashRoundTripTest`,
  `FaultTolerantSessionSerializerTest`, alert `SessionTypeOutsideAllowList`, a new default-mode test.
- **Effort.** S (default) / M (package design).
- **Prerequisites.** ADR-0206 amendment; the frontend package plan.

### PSB-03 — The layout read becomes a shell module fed by domain capability queries (FE-PERF-01)

- **Evidence.** One `/api/v1/me/layout` read per request (`LayoutContextLoader.java:48-64,109`),
  70 `@UsesLayoutModel` classes, ratchet rules in the frontend `ArchitectureTest.java:131-166`.
  Backend `MeController.java:158-178` composes active org unit, pinnable units, capability flags and
  the unread count; the flags (`:104-115`) come from `OwnerScopeService.canAccessBlueprintOverview /
  canViewJobOrders / canViewOwnJobOrders`, `AuthHelperService` role checks for bank, logistics and
  mission, and `InventoryProperties.stolenMarkingEnabled()`.
- **Impact.** The endpoint is a legitimate composition point, but as written it encodes four
  domains' access rules in the identity/shell controller and makes `OwnerScopeService` a carrier of
  domain predicates.
- **Proposed change.** A `shell` (application-level) module owns the layout endpoint and composes
  per-domain capability queries (`JobOrderAccess.canView(caller)`, `BankAccess.isStaff(caller)`,
  `InventorySettings.stolenMarkingEnabled()`, …) published in each domain's API; module rules allow
  shell → domain API and forbid the reverse (ArchUnit or Spring Modulith). Keep one read-only
  transaction.
- **Pros.** Domain rules stay in their domain; the frontend contract (`MeLayoutResponse`) is
  unchanged.
- **Cons.** A few more API methods per domain.
- **Risks and guards.** *Security:* the flags steer menus only; every endpoint keeps its
  `@PreAuthorize`. Guard: `LayoutModelScopeMvcTest` (call count), the backend role-matrix tests, and a
  rule that no server-side decision reads a layout DTO.
- **Effort.** M, inside the modularisation step that introduces domain APIs.
- **Prerequisites.** The domain API shape; ROLES_AND_PERMISSIONS.md unchanged (no rule changes).

### PSB-04 — PIT must finish, and later run per domain (CI-03)

- **Evidence.** Gate `pitest.yml:45-66`; job timeout 60 min (`:19`); run 35827836688 (2026-09-23):
  `PIT (backend)` cancelled after 13 `Minion exited abnormally due to TIMED_OUT`, gate step green on a
  partial report (log line 983, "PIT (backend): 9481 mutations"); the vault's local complete run had
  9,547 mutations in about 30 minutes (`Testing.md:101-109`). `targetClasses` /
  `targetTests` = `…${project.name}.service.*` (`build.gradle.kts:267-268`), `threads` 4 (`:269`).
- **Impact.** A package-by-domain backend moves every service out of `…backend.service`, so PIT's
  scope silently becomes empty — the gate then fails on "no mutations", which is the desired loud
  failure. Afterwards the natural shape is a matrix of per-domain shards.
- **Proposed change.** Now: raise the backend leg's timeout (or PIT's `timeoutConstant`) and gate on
  PIT's completion line ("Generated N mutations …", the grep the vault already uses at
  `Testing.md:99`) instead of a non-empty XML, which PIT writes incrementally. With the domain
  packages: `-P`-selected `targetClasses` per domain in a matrix; per-domain kill-rate in the summary.
- **Pros.** The badge means "complete"; per-domain signal later. **Cons.** More runner minutes for
  shards.
- **Risks and guards.** None for security. Guard: the next scheduled runs show the completion line for
  every leg.
- **Effort.** S (now) / M (shards). **Prerequisites.** None now; the package layout for shards.

### PSB-05 — CI structures that key on today's modules and folders (CI-07, CI-06, CI-03 and neighbours)

| Structure | Where | Assumption | Option A | Option B/C |
|---|---|---|---|---|
| App image build | `docker/app/Dockerfile:13-18,26-27` | copies each module's `build.gradle.kts` by name, `${MODULE}/src/main` + `logging-support/src/main` | unchanged | every subproject must be copied |
| Image reuse (ADR-0210) | `.github/scripts/image_reuse_plan.py:44-56,92-112` | inputs from `COPY` lines; `${MODULE}` = own, everything else = shared | unchanged | `backend-*` sources count as shared → any domain change rebuilds all three images unless `MODULE_OWN` maps them |
| SBOM coverage gate | `.github/scripts/check_sbom_coverage.py:31-52` | every `include()` is SBOM-wired or listed `SHIPPED_INSIDE` / `NOT_SHIPPED` | unchanged | every subproject needs an entry (carrier `backend`) |
| Sandbox images | `.github/workflows/sandbox-images.yml:8-11` | path filter `backend/src/main/**` etc. | unchanged | filter misses subprojects → stale public sandbox images |
| Flyway numbering | `scripts/check-flyway-migrations.sh:5` | one migration directory | unchanged while migrations stay in `backend` | per-domain Flyway locations need a multi-directory, single-sequence check |
| PIT | `pitest.yml:23`, `build.gradle.kts:267-268` | modules backend/frontend, `service.*` | re-key (PSB-04) | per subproject |
| i18n dictionary gate | `I18nDictionaryCoverageTest.java:47,70` | `Files.list` of `static/js` (non-recursive) | per-domain JS folders silently narrow it | same |
| Template/page CSS gate | `TemplateCommentHygieneTest.java:124` | `Files.list` of the pages directory | per-domain CSS folders silently narrow it | same |
| Lint / typecheck | `frontend/build.gradle.kts:570-572`, `tsconfig.json:40-43` | `**/*.js` recursive | fine | fine |
| Frontend ArchUnit | `ArchitectureTest.java:96-166` | selects by `@Controller`/`@RestController` | fine | fine |
| OWASP path filter | `dependency-check.yml:10-11` | `**/build.gradle.kts` | fine | fine |

- **Proposed change.** Before the first split, derive module ownership from one source (the Gradle
  project graph or a module manifest read by the Dockerfile generator, the reuse plan, the SBOM gate
  and the sandbox filter), add a gate that fails when a new Gradle module is unclassified, and switch
  the two non-recursive tests to `Files.walk`.
- **Risks and guards.** *Security:* a wrong reuse decision ships an image built from older code
  under a new tag — keep every reuse gate (signature, both architectures, 168 h age,
  `release-images.yml:150-188`) and add a plan test for a subproject change. Guard: the reuse plan's
  `--selftest` (`repo-lint.yml:375-377`) extended with a subproject case.
- **Effort.** S (tests to `Files.walk`) / M (single ownership source). **Prerequisites.** The choice
  between A and B/C; nothing is needed for A beyond the two tests and PIT.

### PSB-06 — Make `Self-tests` and `Container checks` required (CI-SEC-10)

- **Evidence.** Required checks (ruleset 16482244): Build, Test & Lint; Analyze (java-kotlin);
  Analyze (javascript-typescript); Verify Signed-off-by on every commit; Check migration version
  numbering; Linters; gitleaks; Validate Gradle Wrapper; Repository gates. Not required: `Self-tests`
  (`repo-lint.yml:306-377`) and `Container checks` (`:379-456`), which hold the promtool rule syntax
  and unit tests, the monitoring-config validation (OPS-CI-01), `check-keycloak-issuer`, the edge
  nginx check and the container-runtime/conformance/render-env-d self-tests; also `deploy.sh
  self-tests` (path-filtered, `deploy-script.yml`).
- **Proposed change.** Add both job names to the ruleset; give `deploy-script.yml` an always-running
  no-op leg so it can become required too.
- **Pros.** Monitoring sync (REQ-OBS-005…011) and the deploy seam become merge-blocking.
  **Cons.** Two more checks on every PR (both already run on every PR).
- **Risks and guards.** Strengthening only. Guard: a test PR shows `mergeStateStatus` BLOCKED until
  both pass.
- **Effort.** S (repository setting — the owner's write). **Prerequisites.** None.

### PSB-07 — Backup helper fails closed (OPS-SEC-03)

- **Evidence.** `backup.sh:91-97` / `restore-drill.sh:74-80` resolve db-backend's pinned image via
  `rt_unit_image` (`container-runtime.sh:423-435`), else fall back to the unpinned
  `docker.io/library/postgres:18-alpine` (`backup.sh:29,95-96`; `restore-drill.sh:19,78-79`); the helper
  mounts the keystore, the internal TLS directory and the Redis ACL file (`backup.sh:177-219`).
- **Proposed change.** Refuse to run without a digest-pinned image (`@sha256:` required in
  `rt_unit_image`); the missing backup is then reported by `BackupStaleOrMissing`.
- **Pros.** The pin holds in exactly the situation it exists for. **Cons.** A broken unit directory
  now stops the backup instead of degrading it — which the alert surfaces.
- **Risks and guards.** Strengthening. Guard: `deploy.test.sh` / backup stub test for the refusal;
  restore drill 7/7 after rollout (host write needs the owner's yes).
- **Effort.** S.

### PSB-08 — An absence guard for the crash-loop alert (OPS-MON-01)

- **Evidence.** `ContainerRestartLoop` = `changes(basetool:container:start_time_seconds[15m]) > 3`
  (`infrastructure.yml:60-61`) over `podman_container_started_seconds * on(id) group_left(name)
  podman_container_info` (`containers-runtime.yml:53-57`); neither series is in
  `REQUIRED_CONTAINER_SERIES` (`check-conformance.py:74-81`) or behind `absent()`;
  `ContainerMetricsMissing` (`infrastructure.yml:69-78`) watches the cgroup collector while its text
  says the restart-loop alert is blind.
- **Proposed change.** `absent(podman_container_started_seconds) or absent(podman_container_info)`
  as a warning (or both series in the conformance list), and correct the description.
- **Guard.** A promtool case with the series missing. **Effort.** S. **Security.** Availability
  signal only.

### PSB-09 — Finish the literal-fallback class and fix the gate's blind spots (FE-SIMP-03)

- **Evidence.** `mission-detail.js` keeps 7 `typeof MSG_X !== 'undefined' ? MSG_X : '<German>'`
  fallbacks (`:2088`, `:2150`, `:2240`, `:2249`, `:2252`, `:2293`, `:2334`) and two German console
  messages (`:2026`, `:2035`); `I18nDictionaryCoverageTest` parses only `krtI18nText(...)` and
  `sectionWrite` keys and lists `static/js` non-recursively (`:47,70`), so the vault's "No literal
  defaults in scripts" (`Frontend.md:1478-1484`) overstates it. The shared Lager script is loaded only
  by the two Lager pages.
- **Proposed change.** Move `mission-detail.js` onto `window.krtI18nText`; extend the test to the
  `typeof MSG_…` shape and to `Files.walk`.
- **Security.** Removes `err.message` from two user toasts (`:2150`, `:2240`). **Effort.** S–M.

### PSB-10 — A per-domain type-check ratchet (FE-MOD-03)

- **Evidence.** 44 of 100 scripts `// @ts-check`; 56 unchecked files with 24,621 of 39,577 lines,
  among them `mission-detail.js` (3,249), `bank.js` (2,400), `orders-detail.js` (2,325). ESLint
  targets `ecmaVersion: 2023` (`eslint.config.mjs:26`).
- **Proposed change.** A gate that the number of unchecked files never rises; when scripts move into
  per-domain folders, a folder is "migrated" only when fully checked; split the three large files by
  section first. Raising `ecmaVersion` is a separate question — UNKNOWN which syntax the members'
  browsers need; settle against the E2E browser matrix (chromium, firefox, webkit, `e2e.yml:105`)
  before changing it.
- **Security.** Typed element handles close silent null paths. **Effort.** M (spread over domains).

### PSB-11 — Actions cache budget (CI-01)

- **Evidence.** 9.39 GiB of 10 GiB on 2026-09-29 (`gh api …/actions/cache/usage`); 17
  `gradle-dependencies` entries = 5,535 MiB, all younger than 24 h, which `cache-janitor.yml:64-70`
  keeps; one 1,199 MiB `codeql-dependencies` cache read daily by the GitHub "CodeQL - Code Quality"
  dynamic workflow; NVD (W40) and three Playwright caches present today.
- **Proposed change.** Keep only the newest `gradle-dependencies` entry per key family (or let only
  `ci.yml` write `main` caches) and alert in the janitor summary above 8 GiB.
- **Security.** Keep the janitor's fork guard (`cache-janitor.yml:24`). **Effort.** S.

### PSB-12 — Hash the remaining CI tool fetches (CI-SEC-12)

- **Evidence.** Hash-pinned: actionlint, hadolint, gitleaks, zizmor. Version-only: PyYAML
  (`repo-lint.yml:108,393`, `dependabot-compose.yml:39`), ansible-core/ansible-lint
  (`repo-lint.yml:277-278`); ranges: Ansible collections (`ansible/requirements.yml:3-6`); lockfile-less:
  `npx --yes markdownlint-cli2@0.23.3` (`exchange-docs.yml:58`, added 2026-09-27).
- **Proposed change.** Requirements files with `--require-hashes`; exact collection versions;
  markdownlint from a lockfile.
- **Security.** Supply-chain hardening; the jobs have no write token, which limits the blast radius.
  **Effort.** S.

### PSB-13 — Confine the release App key to `main` (CI-SEC-16)

- **Evidence.** Secrets: `NVD_API_KEY`, `RELEASE_APP_PRIVATE_KEY` only; environments: github-pages,
  production, testing — no `release` environment; `release-publish.yml:3-6` runs on
  `pull_request: closed`.
- **Proposed change.** A `release` environment restricted to `main`, declared by the three
  token-minting jobs. UNKNOWN whether a `main`-only branch policy admits a `pull_request`-triggered
  job (its ref is the PR merge ref); settle by one dry run, or move publishing to a push-on-`main`
  trigger that detects the release commit.
- **Security.** Strengthening; today the exposure needs a writer's branch (only the owner). **Effort.** S–M.

### PSB-14 — Optional sudo wrapper for `deploy` (OPS-SEC-08, now P3)

The documentation half is done (`ansible/README.md:43`: `podman *` lets `deploy` run any code as
`iri`, not as root). A wrapper that admits only the podman sub-commands `rt_*` uses
(`scripts/lib/container-runtime.sh`) is the only way to make `deploy` narrower than `iri`. Low
urgency; do it after the seam stops changing. Effort M (every new `rt_*` call must be added).

### PSB-15 — Name the frontend shared kernel the September work created (FE-SIMP-01/04, FE-SEC-03/05, APPSEC-11/12, FE-SEC-04)

- **Evidence.** JS globals by users (script `81-prev-sept-b-jsglobals.py`): `krtFetch` 68 scripts,
  `krtModal` 35, `krtEvents` 32, `krtLiveSync` 29, `krtI18nText` 27 (defined in
  `krt-client-error.js`), `escapeHtml`/`escapeAttr` 10; domain globals stay inside their domain
  (`krtInventory` and `krtHerkunft` only in Lager scripts, `krtRefineryYield` only in refinery
  scripts) with one deliberate cross-domain link: `krtMaterialRelease` (Materialbörse) used by the
  Lager page (`inventory-materialboerse.js`). Java: `BackendErrorResponses` (111 `relay(` uses),
  `GlobalExceptionHandler`, `SessionIdFingerprint`, `LayoutContextLoader` and the advices,
  `WebClientConfig`.
- **Proposed change.** Declare these the frontend kernel (`frontend.web` / `static/js/kernel/`):
  domains may use the kernel, not each other; a cross-domain UI embed such as `krtMaterialRelease`
  becomes an explicitly published UI API of the owning domain. Enforce with ArchUnit (Java) and an
  ESLint `no-restricted-globals`-style rule per folder (JS). Move `krtI18nText` out of the error
  beacon into its own kernel script (cohesion).
- **Security.** The kernel carries CSRF, re-auth, escaping and the sanctioned HTML sink — keeping it
  single is what keeps those controls uniform. **Effort.** M, part of the frontend modularisation.

## 4. Drift found (read-only audit — for the owner/coordinator to correct)

- Vault `10 Systems/Testing.md:101-109` calls CI-03 fixed and the badge meaningful; the only CI run
  since (35827836688) timed out and passed its gate on a partial report.
- Vault `10 Systems/Frontend.md:1478-1484` says no script keeps a literal default and the test fails
  on one; `mission-detail.js` keeps seven, invisible to `I18nDictionaryCoverageTest`.
- Vault `80 Plans/Improvement Audit 2026-09.md:117` marks CI-01 done; its guard (cache below 8 GB for
  a week) is not met on 2026-09-29.
- arc42 `docs/arc42/04-solution-strategy.md:9` — "exactly one class", and "every outbound call
  passes one place where timeouts, retries, circuit breaking and bulkheads are configured" — is
  contradicted by 13 direct `WebClient` injections outside `BackendApiClient`: 11 use the filtered
  `webClient` bean, while the SSE relay (`NotificationPageController.java:113`, `sseWebClient`) and the
  live-sync probe (`LiveSyncSubscriptionAuthorizer.java:106`, `liveSyncAuthWebClient`) use beans that
  by design carry no Resilience4j chain (`WebClientConfig.java:536-578`). The design is sound; the
  chapter's wording is not.
- Alert text `monitoring/prometheus/alerts/infrastructure.yml:78` (`ContainerMetricsMissing`) names
  the restart-loop alert as blinded by the cgroup collector; that alert reads the podman exporter.

## 5. Data appendix

### A. Implementation commits on `origin/main` (git log --oneline --grep="(#N)" origin/main)

| PR | Commit | Subject (abridged) |
|---|---|---|
| #1982 | 3634ac073 | codeql advanced setup with python |
| #1984 | 0076546ec | ops: nine post-cutover gaps (OPS-MON-01, OPS-REL-01) |
| #1986 | cd43c3ba5 | prune Actions caches |
| #1987 | 9d3ffa86c | anchor cosign identity, sign only from main, least-privilege jobs |
| #1992 | c2bc0914a | ops audit: stop grace, security updates, snapshot, pins, alerts |
| #1993 | 417c46c15 | fold repo-lint, E2E images once, PIT and OWASP report failures |
| #1995 | 5abf93cd2 | release PRs and tags with the basetool-release App |
| #1996 | 5ab7ff01e | delete the 17 deprecated mission endpoints |
| #2001 | 450653df0 | drop the Docker runtime from scripts and monitoring |
| #2002 | 0ac813048 | frontend security and UX findings |
| #2003 | 2db517236 | prettier fix on mission-detail.js |
| #2004 | ccc886d28 | batch lookups, slim picker search, one-call layout read (contains 25430fd7e) |
| #2010 | 0c8b2fba1 | frontend simplification batch |
| #2014 | 93d43832f | owner decisions on #2010 |
| #2018 / #2026 / #2043 | 1fe0fd0fa / a89685de6 / 8af553ae8 | session allow-list and follow-ups |
| #2019 | b49547283 | configuration cache, module conventions, hygiene |
| #2020 | 7a171d054 | one layout read, lean pages, deferred scripts |
| #2021 | dba63b880 | edge gzip for CSS/JS/JSON |
| #2028 | c5de95349 | confidential OAuth2 client |
| #2031 / #2033 / #2046 | d4d7ae9b6 / c2229024a / d4af003fd | CI-07 re-tag, per module, skip superseded |
| #2042 / #2051 | 73a177c9e / b8c6533be | dialog contract on native `<dialog>`, modal wrapper |
| #2049 / #2052 | 07aa989ad / 8b0716768 | cascade layers and touch-floor fix |
| #2074 | e929a3a81 | code carries no comments besides Javadoc (ADR-0214) |
| 2026-09-28 | 1d4199cde, 13ccd55b5, 9215d49d9 | OWASP: NVD JSON 2.0 data feeds, ceilings, cache room |

### B. Commands and scripts (all read-only; scripts in the scratchpad with prefix `81-prev-sept-b-`)

- `81-prev-sept-b-list.py` — extracts the 100 findings from `sept_audit_findings.json`.
- `81-prev-sept-b-rawfetch.py` — raw `fetch(` calls outside `krt-fetch.js`/`krt-client-error.js`:
  55, 0 with a non-GET method, 3 with a helper-built init.
- `81-prev-sept-b-apicalls.py` — `backendApiClient.<verb>(` call sites: 623 in 81 files (get 253,
  getCached 30, post 134, put 99, delete 84, patch 23); 362 concatenated first arguments; 17 URI
  templates; 0 `@HttpExchange` users.
- `81-prev-sept-b-concat-types.py` — heuristic typing of concatenated URIs: 299 typed ids only, 30
  with a `String` variable, 6 with an encoder, 27 other.
- `81-prev-sept-b-relay.py` — 111 `relay(` uses in 28 files; 1 hand-written relay block.
- `81-prev-sept-b-wfperms.py` — permissions per workflow and job (26 workflows, 48 jobs, 27 with job
  permissions).
- `81-prev-sept-b-caches.py` — Actions caches grouped by prefix (97 caches, 9.39 GiB).
- `81-prev-sept-b-jsglobals.py` — `window.krt*` globals, defining file and users.
- `81-prev-sept-b-vaultids.py` — which vault notes mention each finding id.
- `81-prev-sept-b-firstpr.py <sha>` — first first-parent `main` commit containing a commit.
- Shell counts: `grep -c "docker)" scripts/lib/container-runtime.sh` → 0; `grep -rn "uses:
  actions/checkout@"` → 36 and `persist-credentials: false` → 36; `grep -c "^StopTimeout="
  quadlet/systemd/*.container` → 9 of 18; `grep "^// @ts-check"` over `static/js` → 44 of 100;
  `grep -rn "<style" templates` → 0; `grep -rln "WebClient webClient|private final WebClient "
  frontend/src/main/java` → 15 files (13 besides `WebClientConfig` and `BackendApiClient`); `unzip -l` of
  `~/.gradle/caches/modules-2/files-2.1/org.springframework/spring-web/7.0.9/…/spring-web-7.0.9.jar`
  and `spring-webflux-7.0.9.jar` for the HTTP-interface classes.
- Job logs saved as data: `81-prev-sept-b-pit-backend.log` (job 107073405423),
  `81-prev-sept-b-owasp.log` (job 108931587258).

### C. Every `gh api` call (GET only, 2026-09-29)

1. `gh api repos/krt-profit/basetool` (basics; `.security_and_analysis`)
2. `gh api repos/krt-profit/basetool/rulesets`; `…/rulesets/16482938`; `…/rulesets/16482244`
3. `gh api repos/krt-profit/basetool/environments`; `…/environments/production/deployment-branch-policies`; `…/environments/testing/deployment-branch-policies`
4. `gh api repos/krt-profit/basetool/actions/permissions`; `…/actions/permissions/selected-actions`; `…/actions/permissions/fork-pr-contributor-approval`
5. `gh api repos/krt-profit/basetool/code-scanning/default-setup`
6. `gh api repos/krt-profit/basetool/actions/cache/usage`; `…/actions/caches?per_page=100&page=N` (paged by `81-prev-sept-b-caches.py`); `…/actions/caches?per_page=100&sort=size_in_bytes&direction=desc`; `…/actions/caches?per_page=100&key=gradle-dependencies`
7. `gh api repos/krt-profit/basetool/actions/workflows/{dependency-check.yml,pitest.yml,codeql.yml,e2e.yml}/runs?per_page=6&event=schedule`; `…/dependency-check.yml/runs?per_page=8`; `…/pitest.yml/runs?per_page=5`
8. `gh api repos/krt-profit/basetool/actions/runs/{36417643623,35827836688,36530415779}/jobs`
9. `gh api --allow-escape-sequences repos/krt-profit/basetool/actions/jobs/{107073405423,108931587258}/logs`
10. `gh api repos/krt-profit/basetool/issues/2262`
11. `gh api repos/krt-profit/basetool/dependency-graph/sbom` (two jq projections)
12. `gh api repos/krt-profit/basetool/actions/secrets` (names only)
13. `gh api repos/krt-profit/basetool/actions/workflows?per_page=100`; `…/actions/workflows/{343226723,276305301}/runs?per_page=3`

Also run: `gh auth status` (read-only). No production or testing host was accessed.
