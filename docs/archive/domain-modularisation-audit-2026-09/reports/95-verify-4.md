# 95-verify-4 — Adversarial check of 8 architecture claims

Worktree `95e945326`, read-only, no Gradle. My own helper scripts and their outputs are
`95-verify-4-*.py` / `*.out.txt`, `95-verify-4-sealed/` (javac probe),
`95-verify-4-archprobe/ArchProbe.java` (ArchUnit probe) and `95-verify-4-src/` (library sources
extracted from the Gradle cache). No script imports another agent's code.

## Verdicts

| # | Claim | Verdict | Core reason |
|---|---|---|---|
| 1 | DOM-01: 21/23 domains form one SCC; 9-domain core | **NARROWED** | The numbers reproduce exactly and survive every robustness cut. They hold for the **domain (package-quotient) graph** only: the class graph has just 3 cross-domain cycles. The DOM's heuristic of 211 back edges is 136 when computed exactly. |
| 2 | PSA-01/JAVA-07: sealed across packages is impossible on the classpath | **CONFIRMED** | Reproduced with javac 25: exact error key, 13 permits, no `module-info.java` anywhere |
| 3 | FE-04/RES-05: proxy uses the given WebClient; the registry builds a fresh builder | **CONFIRMED** | Holds for the `webClient` bean only. Boot's group customizers are not on the classpath. |
| 4 | A `URI` parameter redirects the request, and the bearer token follows | **CONFIRMED (+2 vectors)** | A `UriBuilderFactory` parameter or an absolute annotation URL do the same. The OAuth2 filter has no host check. |
| 5 | MB-01: exchange ops are not in the frozen set, and no schema test exists | **CONFIRMED** | 0 of 198 CONTRACT paths; 0 backend tests. Only the 2 draft ops touch frozen schemas, and only incidentally. |
| 6 | XC-13 vs PRV-01 ArchUnit counts | **NARROWED** | `failOnEmptyShould` = TRUE is confirmed. The two agents agree on 4 of the 5 rules. July's count is closer for a package move: XC misclassifies :1763 and :1853. |
| 7 | API-40: `.env` floor applied through deploy.sh | **NARROWED (one link REFUTED)** | env.d is **not** rendered on every tick. A render alone does not restart the backend. |
| 8 | DOM-08: 3 families need the caller's transaction | **CONFIRMED (3/3)** | Each family depends on MANDATORY hops, a row lock plus bulk update, or a cascade-before-audit order. The 12/16 total was not re-derived. |

---

## 1. DOM-01 — NARROWED

**Recomputed** (`95-verify-4-scc.py`, own loader):

- The raw file has 7145 edge lines. Folded, they give 5696 class edges, with 0 endpoints missing
  from the CSV.
- 1565 cross-domain edges run between the 23 non-kernel categories, in 41 two-way pairs.
- **SCC = 21**, with `admin` and `dashboard` outside. This is identical to DOM.
- (a) Without access, audit, notification and livesync: **SCC 17**.
- (b) Additionally without identity, orgunit and catalogue: **SCC 9** = {blueprint, exchange,
  hangar, inventory, joborder, materialexchange, mission, operation, refinery}. `leadership` drops
  out by itself, so removing it too changes nothing.

**Robustness** (result on the full node set / after step b):

| Variant | Full node set | After (b) |
|---|---|---|
| The 42 `ambiguous` classes dropped | 21 | 9 |
| Only edges into services, repositories or entities | 20 (leadership drops out) | 9 |
| Only edges into services or repositories | 20 | 9 |
| Edges into DTO, enum, exception or event types dropped | 20 | 9 |
| Only entity→entity edges | {mission, operation, refinery} + {inventory, joborder} | — |

**Where the claim breaks: the class graph itself has no such SCC.** Tarjan over the 1389 folded
classes (`95-verify-4-classscc.out.txt`) finds only six SCCs with more than two classes, and just
three of them cross domains:

1. The **sealed `AppException` family**, 14 classes. The `permits` clause makes the base reference
   every subclass, which is claim 2.
2. **12 JPA entities, inventory⇄joborder**: `InventoryItem`, `InventoryJobOrderAllocation`,
   `InventoryMissionAllocation`, `JobOrder*`.
3. **11 JPA entities, mission⇄operation⇄refinery**: `Mission*`, `Operation`, `RefineryOrder`,
   `RefineryGood`.

Every other domain cycle is a *package tangle*: A.x→B.y and B.z→A.w with no path y⇝z. Such a
cycle can be broken by re-homing or inverting single class edges.

**Exact size of the tangle** (`95-verify-4-fas.py`, exact subset DP):

- **21-domain SCC: the minimum feedback set is 136 class edges in 43 pairs.** The DOM's Eades
  heuristic gives 211 in 52 pairs on the same 1561 inner edges (`10-backend-domains-graph.out.txt:53`).
- **9-domain core: the minimum is 46 edges in 9 pairs.** The optimal order is hangar < mission <
  inventory < blueprint < materialexchange < joborder < refinery < operation < exchange.
- DOM's §5.5 ranks, applied to the core without re-homing, cost **61 edges in 11 pairs** (plus 5
  same-rank edges). The main extra cost is ranking inventory below mission, although
  mission→inventory has 0 edges and inventory→mission has 13 (`InventoryCheckoutService→
  MissionFinanceEntryRepository`, `InventoryItemMapper→Mission`, …). Ranking blueprint below
  inventory adds 2 more.

**What keeps the 9-cycle alive.** The core has 29 domain arcs backed by 242 class edges, and 3
Hamiltonian cycles exist. So the **minimum is 9 class edges**, for example:

- `BlueprintUploadPreviewService→ExchangeDraftService`
- `ExchangeEntryLabels→Ship`
- `HangarService→Mission`
- `MissionMapper→Operation`
- `OperationFinanceService→RefineryOrder`
- `RefineryOrderService→InventoryItem`
- `InventoryItemMapper→JobOrder`
- `JobOrderHandoverService→MaterialExchangeOfferRatchet`
- `MaterialExchangeService→BlueprintProductService`

The cheapest cuts go the other way and detach one domain each (`95-verify-4-cutedges.out.txt`):

- **4 edges** detach hangar. These are all of hangar's outbound edges: `ShipRepository→
  ExchangeShipRow`, and `HangarService→Mission`, `→MissionUnit`, `→MissionUnitRepository`.
- **6 edges**, all of those into exchange, detach exchange.
- **7 edges**, all of those out of blueprint, detach blueprint.

**What the plan should say instead:**

- "21 of the 23 non-kernel domains form one SCC of the *domain (package-quotient) graph*. At the
  class level only three cross-domain cycles exist: the sealed `AppException` family, the
  inventory⇄joborder entities, and the mission⇄operation⇄refinery entities."
- "Layering the 21 domains without re-homing requires re-homing or inverting at least **136**
  class edges in 43 pairs. The figure 211 is a heuristic upper bound."
- "The business core needs at least 46. The §5.5 ranks spend 15 more there by placing inventory
  under mission, so either justify that rank or flip it."
- The conclusion that Option B is infeasible while the SCC exists stands.

## 2. PSA-01 / JAVA-07 — CONFIRMED

- `AppException.java:37-50` is declared `public abstract sealed class AppException extends
  RuntimeException permits` with **13** subclasses. All 13 are `public final` in
  `backend/.../exception/`.
- Six of them are domain classes in `10-backend-domains-classes.csv`: `BankConflictException`
  (bank), `ExchangeProblemException` (exchange), `MissionParticipantRequiredException`
  (**refinery**), `OverAllocationException` (inventory), `OwnerOrgUnitRequiredException` (access)
  and `ProductionAllocationException` (joborder).
- `git ls-files | grep -c module-info.java` returns 0, so every module runs as the unnamed module.
- **javac 25 probe** (`95-verify-4-sealed/`):

  | Case | Result |
  |---|---|
  | `p1.Base permits p1.Same, p2.Sub`, unnamed module | `Base.java:3:79: compiler.err.class.in.unnamed.module.cant.extend.sealed.in.diff.package: p1.Base`, exit 1 |
  | Same package only | exit 0 |
  | The same two packages inside a named module (`module-info.java`) | exit 0 |
  | Sealed **interface** across packages | Same error key, exit 1 |

- Nothing exploits the sealing. `git grep` over `backend/src` finds no `case <Sub>Exception`
  pattern and no `getPermittedSubclasses`/`isSealed`. Keeping the type sealed or opening it
  (a kernel-owned `non-sealed` domain base) therefore costs no switch rewrite.

## 3. FE-04 / RES-05 (filters and registry) — CONFIRMED

Sources: spring-webflux / spring-web 7.0.9 sources jars, which are Boot 4.1.1's
`spring-framework.version` (`70-research-boot-4.1.1-deps.pom:196`).

**The proxy runs every request through the injected WebClient:**

- `WebClientAdapter.java:107-120`: `newRequest` calls `this.webClient.method(httpMethod)`.
  `create(WebClient)` is at `:171-173`.
- `DefaultWebClient.java:185-188` applies the builder's `defaultRequest` consumer to every
  `method()` spec. That is the OAuth2 attribute population installed by `oauth2Configuration()`.
- `DefaultWebClientBuilder.java:312-313` reduces the filter list with `andThen`.

**Which filters sit on which bean** (`WebClientConfig.java`):

| Bean | Lines | What it has |
|---|---|---|
| `webClient` | :456-489 | Everything: OAuth2 `.apply(oauth2Client.oauth2Configuration())` :477, correlation :478, org-unit :479, locale :480, client IP :481, call logging :482, `resilienceFilter("backendApi")` :483-485, Accept :486, baseUrl :487 |
| `termsDocumentClient` | :510-535 | No OAuth2, no org-unit relay |
| `sseWebClient` | :547-559 | No OAuth2, no resilience |
| `liveSyncAuthWebClient` | :569-579 | No OAuth2, no resilience, no org-unit relay |

So the claim is true for proxies built on `webClient`.

**The registry creates a fresh builder per group:**

- `HttpServiceProxyRegistryFactoryBean.java:222-227`: `getClientBuilder()` calls
  `groupAdapter.createClientBuilder()`.
- `WebClientHttpServiceGroupAdapter.java:33-36` returns `WebClient.builder()`, and `:44-46` builds
  it into a new `WebClientAdapter`.
- A configurer can supply the builder only through an `InitializingClientCallback` (`:208-211`),
  which must run before any other configurer touches the group. Otherwise it fails with
  `"Client builder already initialized"`.
- Boot's group customizers are not on the classpath: `gradle/verification-metadata.xml` has no
  `spring-boot-webclient` entry. RES-05's remark that Boot 4.1.1 applies `WebClientCustomizer`s
  therefore presupposes adding that module.
- No `@HttpExchange`, `HttpServiceProxyFactory` or `WebClientAdapter` exists in the codebase today
  (`git grep` returns 0).

**Plan note:**

- State that only the `webClient` bean carries the full chain.
- `AbstractReactorHttpExchangeAdapter.blockTimeout` defaults to null (`:40`, `:79-80`), so sync
  proxy methods are bounded only by the TimeLimiter in `resilienceFilter`.

## 4. `URI` parameter → foreign host → bearer token — CONFIRMED, and wider than stated

**The `URI` parameter:**

- `UrlArgumentResolver.resolve` calls `requestValues.setUri((URI) argument)`. The resolver is
  registered by default (`HttpServiceProxyFactory.java:272`).
- `WebClientAdapter.java:125-127` then calls `spec.uri(values.getUri())`.
- `DefaultWebClient.java:258-261` stores that URI, and `initUri()` (`:497-498`) uses it as is.
  **The base URL is ignored.**

**The token follows:**

- In `ServletOAuth2AuthorizedClientExchangeFilterFunction` (spring-security-oauth2-client 7.1.1),
  `filter` (`:399-420`) calls `bearer()` (`:565-568`), which runs `headers.setBearerAuth(token)`
  with **no host check**.
- `setDefaultOAuth2AuthorizedClient(true)` (`WebClientConfig.java:465`) attaches the current
  member's token.
- The org-unit, locale, client-IP and correlation headers go along too.

**Two more vectors with the same effect:**

1. A `UriBuilderFactory` parameter: `UriBuilderFactoryArgumentResolver` is a default resolver
   (`:273`), and `WebClientAdapter.java:129-134` expands the template against the caller's
   factory.
2. An **absolute URL** in `@HttpExchange` or `@GetExchange`: `DefaultUriBuilderFactory.java:289-292`
   ignores the base URL whenever the template has a host.

The same exposure exists today for any absolute URI passed to `webClient`: no host-pinning filter
exists, and `WebClientLoggingFilter.java:84` reads the host only to log it.

**Guards to add:**

- An ArchUnit rule: no `@HttpExchange` type declares a `URI` or `UriBuilderFactory` parameter, and
  no exchange annotation value contains `://`.
- A host-pinning `ExchangeFilterFunction` on `webClient` that refuses any request whose origin is
  not `backendUrl`, before the exchange.

## 5. MB-01 — CONFIRMED

Script: `95-verify-4-contract*.py`.

**No exchange operation is in the frozen set:**

- `ExternalContractTest.java`: the `CONTRACT` list (`:304-1833`) holds **198** distinct `/api/v1`
  path literals, **none** under `/api/v1/exchange/**`. The 7 `material-exchange` paths belong to a
  different domain.
- Signatures and the previous-release diff iterate `CONTRACT` only: `contractSignatures`
  `:2284-2299`, `theContractTypesMatchThePreviousRelease` `:2234-2276`, and the frozen record
  `frozen-contract-types.txt` (1819 lines).
- The only `/api/v1/exchange` literals (`:2620-2653`, `:2731`) belong to the edge test
  `theExchangeStaysOffTheApiVhost`.

**Nuance:**

- `openapi.json` has 14 operations on 13 paths under `/api/v1/exchange/**`.
- None of the 12 operations whose body reaches the client (rows 1–12) touches a frozen schema.
- Only the two draft operations touch frozen schemas: 12 web DTOs reached through
  `BlueprintImportPreviewDto` and `RefineryImportDraftDto`. They are frozen **incidentally**,
  because Android operations use them. Those two bodies never reach the client (they are staged in
  Redis).

**No schema test in the backend:**

- `git grep -l -i -E "networknt|JsonSchema|json-schema|exchange/v1/schemas|examples/v1|schema\.json"
  -- backend/src/test frontend/src/test` finds **0 files**. All 10 tests that read the exchange
  schemas are in ingest.
- `json-schema-validator` appears only as an ingest `implementation` dependency
  (`ingest/build.gradle.kts:33`), i.e. runtime validation in the gateway.
- The backend `test` task reads ingest's `ExchangeRelay.java` (`backend/build.gradle.kts:103-116`)
  only for `OnBehalfOfHeaderParityTest`, not for schemas.

## 6. XC-13 vs PRV-01 — NARROWED (`failOnEmptyShould` confirmed; July's count is closer)

**The default, from the archunit-1.5.1.jar bytecode (`javap -c`):**

- `AllowEmptyShould$3.isAllowed()` reads `archRule.failOnEmptyShould` with the default
  `TRUE.toString()` and returns `equalsIgnoreCase("FALSE")`.
- `ArchRule$Factory$SimpleArchRule.evaluate` calls `verifyNoEmptyShouldIfEnabled`, which throws
  `AssertionError("Rule '%s' failed to check any classes…")`.
- The repository has no `archunit.properties` and no `failOnEmptyShould` system property. The only
  opt-outs are `allowEmptyShould(true)` at `ArchitectureTest.java:718` and `:1693`.
- The check applies to ArchRules only. Hand-rolled loops and AssertJ checks never fail on
  emptiness.

**The five rules.** Today's selections come from `95-verify-4-archprobe.out.txt`: the ArchUnit
1.5.1 probe, run on `backend/build/classes/java/main` with the rules' own predicates.

| Rule | Keyed on | Today | Whole-domain move | Partial or split move | XC / July | Mine |
|---|---|---|---|---|---|---|
| `permitAllIsDeclaredOnlyOnTheFourPublicEndpoints` :345 | Loop over `getPackageName().contains(".backend.controller")` plus AssertJ `isEmpty()` (not an ArchRule) | 126 classes, 4 permitAll methods | Visits 0 classes → passes | Moved controllers are unchecked | V / SILENT | **Silent** — both right |
| `controllerLayerShouldNotDependOnRepositoryLayer` :427 | ArchRule, `..backend.controller..` → `..backend.repository..` | 126 / 123 classes | Silent while any controller remains; **loud only when the last one leaves** (empty selection); vacuous if repositories move first | Weaker | V / SILENT | **Silent during the migration** — both right |
| `supportPackageMustStayADependencyLeaf` :577 | Deny-list of 11 layer patterns | 76 selected; the deny-list matches 781 of 1765 | `backend.<domain>.*` matches no pattern → silent | Silent | V / SILENT | **Silent** — both right |
| `peerReadableMissionEndpointsMustRedactPii` :1129 | Package filter + 3 DTO FQN strings + AssertJ floor ≥ 10 | **22** (MissionController 20, MissionFinanceEntryController 2) | Controllers or DTOs leave → 0 < 10 → **loud** | 12 endpoints can leave unnoticed; new or split controllers are never selected | W / LOUD | **Depends on the move granularity.** XC's "≈23 / 13 headroom" is really 22 / 12. |
| `bankClassesMustNotConsultOrgUnitScope` :1794 | `Bank*` simple names (111 classes, package-independent) + FQN string of `OwnerScopeService` | Resolves; 0 violators | Once `OwnerScopeService` leaves `backend.service`, the target matches nothing while the selection stays non-empty → **silent** | Same | V / SILENT | **Silent** — both right |

**Why the totals differ** (XC: 20 V + 14 W + 1 S + 8 L; July: 25 + 6 silent/partial, 8 + 3
loud/safe, 1 stronger). Exactly five rules are classified differently, and I read each of them:

- **:475 `everyExchangeControllerMethod…` and :1129** — XC says W, July says LOUD. It depends on
  the move granularity: an empty selection is loud on a whole-domain move, while a partial move
  leaves them weaker.
- **:525 `exchangeDtosStayInTheExchangeLayer`** — XC says L, July says PARTIAL. The same lens
  question, in reverse.
- **:1763 `bankClassesMustStaySeasonAndProfitIndependent`** — XC says V (renames), July says SAFE.
  **XC is wrong for a package move**: the selection is `haveSimpleNameStartingWith("Bank")` and
  the target is simple-name prefixes plus `getPackageName().startsWith("de.greluc.krt.profit.basetool.backend")`,
  so both are package-independent. Renaming is a different refactor.
- **:1853 `orgUnitAwareBankSeamIsContainedToOneClass`** — XC says V, July says LOUD. **XC is
  wrong**: the selection is "depends on both FQN strings". Moving either target empties it, and
  `failOnEmptyShould` then fails the rule. It is never vacuous.

**Consistent totals.** These assume that the 38 rules both agents classify alike are classified
correctly. I re-verified only 4 of them (:345, :427, :577, :1794).

| Lens | Silent / partial | Loud / safe | Stronger |
|---|---|---|---|
| Whole-domain move | 30 | 12 | 1 |
| Partial or split move | 33 | 9 | 1 |

Against the whole-domain lens, July's 31/11/1 is off by one rule (:525). XC's 34/8/1 is off by
four, two of which (:1763, :1853) are wrong under either lens. Under the partial lens, XC's total
is off by only one, but three of its rules are misclassified (:525, :1763, :1853).

**What the plan should say:**

- "About 30–34 of the 43 rules lose coverage without failing during a package-by-domain migration."
- "Re-key every one of them before the first move." (XC-13's proposed change stands.)
- "The loud rules protect only against whole-selection moves, never against new or split classes
  in new packages."

## 7. API-40 — NARROWED (one link REFUTED)

**Confirmed links:**

- `application.yml:172-175` binds `app.android.minimum-version-code:
  ${APP_ANDROID_MINIMUM_VERSION_CODE:0}`.
- `docker-compose.yml:213-215` passes the variable.
- `quadlet/env.d/backend.env.tmpl:11-13` (the floor is on `:12`) renders it.
- `backend.container:25` sets `EnvironmentFile=/var/iri/code/env.d/backend.env` under
  `[Container]`, so the file is re-read at each container start.
- `AndroidClientProperties.java:39-45` is an immutable `@Validated @ConfigurationProperties` record,
  bound once at startup. There is no refresh mechanism: `git grep` for
  `RefreshScope|spring-cloud` finds nothing.
- It is served by `AppVersionPolicyController.java:49,60,69`, which is `permitAll`.

**REFUTED: "deploy.sh renders env.d on every tick":**

- The render at `deploy.sh:268-278` sits inside `install_quadlet_units`. That function is called
  only at `:1300`, inside `if CONFIG_CHANGED` (`:1238-1303`), and at `:352` for a rollback.
- `CONFIG_CHANGED` is true only for a new config-bundle digest (`:962-965`), `--reapply`
  (`:970-972`) or a host without units (`:977-985`).
- An unchanged tick exits at `:1062-1068` before any render.
- `docs/deployment.md:211` says so itself: env.d is "generated on every config change".

**NARROWED: "applied by the next backend recreate":**

- Even a deploy that renders env.d restarts the backend only if its unit file changed
  (`deploy.sh:285-289`) or its digest pin changed (`container-runtime.sh:279-288`).
- `rt_apply_stack` stops only `RT_CHANGED_SERVICES` and merely `start`s the rest
  (`container-runtime.sh:199-218`, `:237-251`).
- A health heal, or an image change without a config change, therefore restarts the backend on
  the **stale** env.d.

**What the plan should say instead:**

- Raising the floor is runbook step **S8** (`docs/EXCHANGE_GO_LIVE_RUNBOOK.md:533-559`), a
  production write that needs the owner's per-action yes. The steps are:
  1. `env_set` both codes.
  2. Run `render-env-d.py`.
  3. `systemctl --user restart backend.service`.
  4. Start `ingest` and `frontend` again. Both have `Requires=backend.service`
     (`frontend.container:4`, `ingest.container:4`).
- This means about one minute of web and app outage.
- Run S8 **after** the re-cut release is verified healthy. If the `.env` change rides on the
  release deploy instead, a health-gate rollback re-renders env.d from the same `.env`
  (`deploy.sh:352`). The old backend would then come back with the raised floor, and no app
  version would work until S8's rollback runs.
- Whether every release carries a new config digest is **UNKNOWN**. It is likely, because
  metadata-action's default labels include revision and version
  (`70-research-metadata-action-README.md:238-246`). Comparing two consecutive `basetool-config`
  digests in GHCR would settle it.

## 8. DOM-08 spot checks — CONFIRMED (3 of 3 families)

**joborder → inventory** (`JobOrderHandoverService.createHandover`, `@Transactional`
`:123-124`):

- The flow runs in one transaction, in this order:
  1. It takes a **`PESSIMISTIC_WRITE`** lock on each row via `findByIdForUpdate`
     (`:160-163`; `InventoryItemRepository.java:959-962`).
  2. It validates against the earmarked slice (`:165-186`).
  3. For each row, it either runs `offerRatchet.beforeDelete` and then `delete`, or reduces the
     row and saves it (`:221-233`).
  4. After the loop it runs the bulk delete once (`:251-254`). That query is
     `@Modifying(clearAutomatically = true, flushAutomatically = true)`
     (`InventoryItemRepository.java:929-937`).
  5. It re-fetches the job order and calls `completeJobOrderWithinTransaction`, which is
     MANDATORY (`:256-265`; `JobOrderService.java:952-953`).
  6. It calls the ratchet's `lower` and `auditService.record` n+1 times (`:267-290`).
- **After-commit would break this in two ways.**
  - The lock is what serialises the check-then-decrement. A separate transaction would re-read
    unlocked stock, so two handovers could both pass and double-consume.
  - A failed after-commit decrement would leave a committed (possibly auto-completed) handover
    with stock that was never decremented. The audit must also sit in the same transaction
    (REQ-AUDIT-001).

**orgunit → orgchart mirror:**

- All **9** `mirror*` methods are `@Transactional(propagation = MANDATORY)`
  (`OrgChartService.java:272, 296, 313, 346, 378, 391, 402, 419, 437`).
- There are **12** call sites: `OrgUnitMembershipService.java:160, 222, 252, 304, 336, 603, 684,
  743, 787` and `KommandoGroupService.java:114, 143, 165`.
- REQ-ROLE-006 (`docs/specs/role-model.md:227-229`) requires the mirror to be written "in the same
  transaction as the rank change", and the mirror is the **single writer** of account-linked seats
  (`:237-242`). No scheduled reconcile exists (grep).
- **After-commit would break this in two ways.**
  - A lost or failed event means permanent chart drift that the editor cannot repair.
  - The singleton-seat reassignment (`uq_org_chart_one_bereichsleiter_per_bereich`, Javadoc
    `:261-266`) would race outside the membership transaction.
- No security impact: the chart grants nothing (REQ-ORG-010).

**inventory → materialexchange ratchet:**

- All 4 public methods are MANDATORY (`MaterialExchangeOfferRatchet.java:62, 94, 109, 124`). The
  class Javadoc (`:41-43`) says "must run in the stock-changing transaction".
- `beforeDelete` records `MARKET_OFFER_REMOVED` for offers that the FK `ON DELETE CASCADE`
  (`V210__add_material_exchange.sql:18`, never altered since) deletes together with the row.
  **After commit, the offers are gone, so the audit rows would be lost** (REQ-AUDIT-001).
- `lower` clamps the offers to the new stock (`:82-83`) and audits them. After commit, active
  offers would exceed the stock in the gap, or permanently if the step fails (REQ-MARKET-013).
- There are 16 call sites: InventoryCheckoutService 9, the three joborder services 6, and
  UserDeletionService 1. Every `beforeDelete` comes immediately before its delete (e.g.
  `InventoryCheckoutService.java:264-265, 626-627, 876-877, 1151-1152`).

---

## Appendix — how each result was produced

| Result | Command / file |
|---|---|
| Domain SCCs, robustness, class SCCs, core arcs, Hamiltonian cycles, cuts | `python 95-verify-4-scc.py` → `95-verify-4-scc.out.txt` |
| Exact feedback sets (21, 9) and the cost of the §5.5 ranks | `python 95-verify-4-fas.py` → `95-verify-4-fas.out.txt` (subset DP; the 9-node result agrees with the pure-Python DP in the scc script) |
| Cut edges and inventory/mission edge lists | `python 95-verify-4-cutedges.py` → `.out.txt` |
| Class-level SCC members | `python 95-verify-4-classscc.py` → `.out.txt` |
| Sealed probe | `javac -XDrawDiagnostics -d out … unnamed/p1/*.java unnamed/p2/Sub.java` (and samepkg / named / iface) in `95-verify-4-sealed/` |
| ArchUnit default | `javap -c -p -cp archunit-1.5.1.jar 'com.tngtech.archunit.lang.AllowEmptyShould$3'` and `'…ArchRule$Factory$SimpleArchRule'` |
| Rule selections | `java -cp "archunit-1.5.1.jar;slf4j-api-2.0.17.jar" 95-verify-4-archprobe/ArchProbe.java <backend/build/classes/java/main>` → `95-verify-4-archprobe.out.txt` |
| Spring sources | `python 95-verify-4-extract.py` (spring-web and spring-webflux 7.0.9 sources jars); plus spring-security-oauth2-client 7.1.1 and `DefaultUriBuilderFactory`, extracted to `95-verify-4-src/` |
| Contract coverage | `python 95-verify-4-contract.py`, `python 95-verify-4-contract2.py` → `.out.txt` |
