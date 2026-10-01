# 95-verify-3 — adversarial verification of ten claims

Worktree `$REPO` at `95e945326` (= origin/main).
Read-only. `gh` GET calls made (2026-09-29): `gh run view 35827836688 [--json … | --job 107073405423 --log]`,
`gh run list --workflow pitest.yml`, `gh run view 35065963345 [--job 104696183786 --log]`,
`gh api repos/krt-profit/basetool/rulesets`, `…/rulesets/16482244`, `…/rules/branches/main`,
`…/branches/main/protection`. Library behaviour was read from the sources jars in the Gradle cache
(Spring Boot actuator-autoconfigure 4.1.1, Hibernate ORM 7.4.5.Final, spring-context/spring-tx 7.0.9).
Helper scripts: `95-verify-3-events.py`, `95-verify-3-cache.py`, `95-verify-3-assoc.py`; the fetched log is
in `95-verify-3-pit-backend.log`, and the ruleset JSON is in `95-verify-3-ruleset-main.json`.

## Verdicts

| # | Claim | Verdict | Severity (honest) |
|---|---|---|---|
| 1 | JAVA-01 password in Lombok `toString()`, `Brokered` record, PiiMasker gaps | **CONFIRMED** (code facts). **NARROWED** risk: nothing prints these objects today, and actuator `configprops` is neither exposed nor unmasked | Low (latent) |
| 2 | XC-06 four event records carry personal data | **CONFIRMED, but not exhaustive**: at least 5 more records do the same. Persisted in `notification.params`, never logged | Low (privacy hygiene) |
| 3 | MB-03 mirror reader falls back silently on `minClientVersion`, `requestsPerMinute`, `writesPerDay` | **CONFIRMED**. "Tighter limits" holds in one direction only | Low–Medium |
| 4 | FE-16 per-module `:stable` re-tag and independent resolution, so a mixed release is possible | **CONFIRMED** | Medium (operational) |
| 5 | CI-03/PSB-04 PIT gate passed on a partial `mutations.xml` (9,481) | **CONFIRMED** | Low (CI signal) |
| 6 | PSB-06/CI-SEC-10 `Self-tests` and `Container checks` are not required checks | **CONFIRMED** | Low–Medium |
| 7 | OPS-SEC-03/PSB-07 unpinned `postgres:18-alpine` fallback in backup and restore drill | **CONFIRMED, with corrections**: the backup helper mounts far more than stated, the drill mounts nothing (it gets the full dumps), and the fallback is an edge case | Low (defence in depth) |
| 8 | C1002-L4 mutators are safe only through self-invocation. A split would edit the cached entity, and a failed write would leave it cached | **CONFIRMED**. Eviction ordering does not rescue it | Low–Medium (data correctness) |
| 9 | JAVA-06 five OrgUnit pattern matches skip `Hibernate.unproxy` | **NARROWED**: all five exist, but only `OrgUnitMembershipService:340` can receive a proxy (data-dependent). The other four cannot on current call paths | Low (one display-only bug) |
| 10 | DOM-09 exactly one EAGER cross-domain association, `MissionParticipant.orgUnits` | **CONFIRMED**: it is the only EAGER mapping of any kind in the backend model | n/a |

## 1 — JAVA-01 (CONFIRMED / NARROWED)

- `frontend/.../config/MonitoringScrapeProperties.java:33` `@Data`, `:49` `private String password`. The ingest class has the same at `:32`/`:48`. Root `lombok.config` has no `toString` settings.
- `javap -c -p` on both compiled classes shows that the generated `toString()` concatenates `getUsername()` and `getPassword()`.
- The backend twin is a record whose `toString()` redacts the password (`backend/.../config/MonitoringScrapeProperties.java:37-67`). It is pinned by `MonitoringScrapePropertiesTest.thePasswordIsNeverPrinted` (backend test `:73-80`). The frontend and ingest tests have no `toString` test.
- `DiscordGuildRoleGateAuthenticator.java:280-281` `record Brokered(accessToken, username, email)` has no override. It is built at `:196-197` and never logged: the log calls at `:104`, `:112`, `:120` and `:126` print constants or an enum only.
- `PiiMasker.java:42-51` masks JWTs, e-mails, and the value after `bearer`, `token`, `session[-_]?id` or `authorization`. It does not mask `password=`, `secret=` or `username=`.
  - Nuance: the keyword regex has no word boundary, so `accessToken=` **is** masked (it matches `Token=`).
  - Nuance: keycloak-spi does not use logging-support at all. There is no `project(...)` dependency in `keycloak-spi/build.gradle.kts`, and the maskers are wired only in the three apps' `logback-spring.xml`. A `Brokered` that reached a Keycloak log would therefore be completely unmasked.
- **Sinks today: none.**
  - No code logs either object (grep over frontend and ingest `src/main`; no security `debug`).
  - The web exposure list is `health, prometheus, loggers` in all three apps (backend `application.yml:232-233`, frontend `:122-123`, ingest `:100-101`).
  - JMX default exposure is `health` (`EndpointExposure.JMX("health")`, actuator-autoconfigure 4.1.1).
  - Even if exposed, `configprops` defaults to `showValues = Show.NEVER` (`ConfigurationPropertiesReportEndpointProperties`, 4.1.1) and serialises bean properties, not `toString()`.

## 2 — XC-06 (CONFIRMED, undercounted)

- Confirmed records:
  - `UserApprovalDecidedEvent.java:39-44` (`userId, approved, recipientEmail, recipientName, reason`)
  - `AccountDeletionRequestedEvent(UUID userId, String handle)`
  - `DiscordRegistrationPendingEvent(UUID userId, String username)`
  - `JobOrderCreatedEvent(…, String handle, …)`, whose handle is `JobOrder.handle` via `JobOrderService.java:220-228`
- **More records do the same** (script `95-verify-3-events.py`, confirmed by reading each record's `renderParams()`):

  | Record | Field | Notification param |
  |---|---|---|
  | `JobOrderUpdatedByRequesterEvent` | `handle` | `handle` |
  | `BankBookingRequestCreatedEvent` | `requesterHandle` | `requester` |
  | `MaterialExchangeInterestRegisteredEvent` | `interestedUserName` | `interessent` |
  | `MaterialRequestFulfillmentSignalledEvent` | `fulfillerName` | `lieferant` |
  | `BankBookingRequestRejectedEvent` | `reason` (user free text) | `reason` |

  Not counted: `MemberDepartedEvent.reason` is a constant, and `Exchange*Event.clientName` is an app name.
- **Persisted:** yes, for every `NotificationEvent`. `renderParams()` is serialised into `notification.params` TEXT, one row per recipient (`NotificationCreationService.java:81-97`, `Notification.java:76-77`). It is also sent transiently in `NotificationSignal` over Redis pub/sub (`:103-108`).
- `UserApprovalDecidedEvent` is not a `NotificationEvent`. Only `UserApprovalMailEventListener` consumes it (after commit), which puts it into a mail. It is not persisted.
- No event-publication registry exists: no Modulith or jMolecules in `libs.versions.toml`.
- **Logged:** no. The log lines carry the event type plus the entity UUID (`NotificationEventListener.java:63-67`, `NotificationCreationService.java:77-78,98-102`), the user UUID (`UserApprovalMailEventListener.java:59`, `PendingRegistrationMailService.java:64`), a count (`:82`), or the mail subject (`SmtpMailService.java:76-106`).

## 3 — MB-03 (CONFIRMED)

- **Fail-closed fields:** `ExchangeRegistryReader.parse/client` (`:159-202`).
  - `schemaVersion` is strict (`:160-163`).
  - A missing `enabled` reads as false (`:175`).
  - A missing `status` reads as not ACTIVE (`:197`).
  - Missing `capabilities` read as the empty set (`:186-194`).
- **Silent-fallback fields:** `minClientVersion`, `requestsPerMinute` and `writesPerDay` (`:199-201`) become `null` when absent, renamed or of the wrong type (`text()`/`integer()` at `:211-226`).
- **What the fallbacks mean:**
  - `minClientVersion == null` means **no minimum**: `ClientVersions.meets` returns true (`ClientVersions.java:53-56`).
  - `requestsPerMinute == null` falls back to `memberPerMinute` = **120** (`ExchangeLimitFilter.java:122-123`, `ExchangeLimitProperties` `@DefaultValue("120")`).
  - `writesPerDay == null` falls back to **500** (`ExchangeLimitFilter.java:183-184`, `@DefaultValue("500")`).
- **Direction nuance.** Overrides may be lower, or up to 10× higher (≤1200 and ≤5000, per spec REQ-XCH-003). A rename therefore loosens a *tighter* override but *tightens* a looser one, which is an availability effect, not a security one.
- The minimum-version gate is "cooperative by design — accepted" (`docs/specs/external-exchange.md:2034`).
- Side effect: the frozen service document would silently omit `limits.requestsPerMinute/writesPerDay` and send `minClientVersion: null` (`ExchangeController.java:191-201`).
- **Backend writer.** The JSON field names are the `ExchangeRegistrySnapshot.Client` record component names (`:67-73`). They are serialised by a default `JsonMapper.builder().build()` (`RedisExchangeRegistryMirror.java:46,64-73`) with no `@JsonProperty`. The backend enforces neither the minimum version nor the per-client limits itself.
- **No test pins the shape across modules:**
  - The ingest test uses JSON literals (`ExchangeRegistryReaderTest.java:53`) and even pins the lenient fallback (`oddShapesDegradeToSafeDefaults`, `:152-170`).
  - The backend `RedisAclBackendIntegrationTest` round-trips through its own record (`:168-186`), so a rename would not fail it.
  - `ExchangeRegistryMirrorClosureTest` reads only `clients.versekit.status`.
  - `ExchangeContractTest` covers only the external contract.
  - The shape exists only as prose in REQ-XCH-003.

## 4 — FE-16 (CONFIRMED)

- **Promote job** (`promote.yml:169-202`): a matrix over the five modules with `fail-fast: true` (`:179`). Each leg independently cosign-verifies and re-tags (`.github/actions/retag-verified-digest`). There is no rollback job, and `sync-testing` needs `promote`, so it is skipped when promotion fails.
- **`:testing` sync** resolves all five digests first (`:296-312`) and then re-tags them in a loop (`:314-320`). The resolution is all-or-nothing; the writes are not.
- **deploy.sh** resolves each `:stable` separately (`:929-938`). Missing `config` and `keycloak-spi` tags are tolerated (`|| …_DIGEST=""`).
  - `EXPECTED_MARKER` only concatenates the digests.
  - Grep for `revision|opencontainers|compatib|release marker` finds no cross-image check, only the per-image cosign check. Each image of a mixed set is validly signed, so the signatures do not catch mixing.
  - The units reference `:stable` (`quadlet/systemd/backend.container:8`), and the host pin comes from the resolved digests.
- **Timer:** `iri-deploy.timer` `OnUnitActiveSec=5min`.
  - After a failed promotion, a mixed set stays until someone fixes it.
  - Even a *successful* promotion has a short window between the parallel re-tags; the next tick heals it.
  - The only remaining guard is the health gate with rollback.

## 5 — CI-03 / PSB-04 (CONFIRMED)

- **Run** `35827836688` (schedule, 2026-09-23T06:40Z): run conclusion `cancelled`. Job `PIT (backend)` `107073405423` ran 06:41:14→07:41:36, conclusion `cancelled`.
  - Step "Run PIT mutation analysis": `cancelled`.
  - Step "Fail when PIT produced no result": **`success`**.
  - Step "Upload PIT report": `success`.
- **Log** (`95-verify-3-pit-backend.log`):
  - `:951` `##[error]The operation was canceled.`
  - `:983` `PIT (backend): 9481 mutations in backend/build/reports/pitest/mutations.xml`
  - PIT had logged "Created 215 mutation test units" at 06:44:20, then 14 "Minion exited abnormally due to TIMED_OUT", and no final statistics. The report is therefore partial.
- **Workflow** `pitest.yml`: `timeout-minutes: 60` (`:19`), and the PIT step has `continue-on-error: true` (`:37-38`). The `if: always()` gate (`:44-63`) checks only for `PitHelpError`, a non-empty file and more than 0 mutations. It never checks `steps.pit.outcome`.
- The gate is unchanged at HEAD: the only change since `cc3c5d09` is #2074, which stripped comments.
- Nuance: the run is shown as cancelled, not green. The worse case is a non-`PitHelpError` PIT failure after a partial XML: the job would then be green.
- Mutation Testing is not a required check.

## 6 — PSB-06 / CI-SEC-10 (CONFIRMED)

- The ruleset `main` (id 16482244, active, `~DEFAULT_BRANCH`, strict) requires exactly these checks:
  - Build, Test & Lint
  - Analyze (java-kotlin)
  - Analyze (javascript-typescript)
  - Verify Signed-off-by on every commit
  - Check migration version numbering
  - Linters
  - gitleaks
  - Validate Gradle Wrapper
  - Repository gates
- It has a bypass actor (one User, `always`).
- `rules/branches/main` shows no other (org) source. Classic protection returns `404 Branch not protected`.
- **repo-lint.yml jobs:**

  | Job | Line | Required? |
  |---|---|---|
  | Linters | `:18-19` | yes |
  | Repository gates | `:93-94` | yes |
  | **Self-tests** | `:306-307` | **no** (conformance, env renderer, Redis ACL renderer, internal-TLS minter, image-reuse plan) |
  | **Container checks** | `:379-380` | **no** (Keycloak issuer, Prometheus rules and unit tests, monitoring configs, edge nginx) |

## 7 — OPS-SEC-03 / PSB-07 (CONFIRMED with corrections)

- The fallback is confirmed in both scripts: `backup.sh:29` and `:91-97`; `restore-drill.sh:19` and `:74-80`.
- **The backup helper sees far more than the claim lists.** `rt_read_mount` is `run --rm -v src:/src:ro image` (`container-runtime.sh:413-417`) with default network and no pull policy. It mounts:
  - the edge-certs, edge-acme-state and edge-acme-webroot volumes (`:172-184`)
  - the Redis ACL directory (`:188`)
  - the **whole `/var/iri/secrets` directory** (`dirname KEYSTORE_PATH`, `:203`)
  - the internal TLS directory (`:215`)
  - `COMPOSE_DIR`, including `.env` and `realm-export.json` (`:225`)
  - the monitoring secrets, certs, Grafana DB and Alertmanager state (`:255-267`), plus the Prometheus snapshots (`:283`)
- **The restore-drill container mounts nothing** (`:148-149`). Instead it receives both full database dumps, backend and Keycloak, via `rt_cp_to` (`:160-170`).
- **Reachability.** The fallback fires only when neither `${RT_UNIT_DIR}/db-backend.container` nor `${COMPOSE_DIR}/quadlet/systemd/db-backend.container` yields an `Image=` line (`container-runtime.sh:423-436`).
  - The repo's unit pins a digest (`quadlet/systemd/db-backend.container:6`), and the config bundle has shipped `quadlet/systemd` since 2026-09-18 (`deploy.sh:261`).
  - So the fallback is an edge case: a missing or renamed unit, or a manual run with a different `COMPOSE_DIR`.
  - The `IRI_BACKUP_HELPER_IMAGE` and `IRI_DRILL_IMAGE` overrides take precedence.

## 8 — C1002-L4 (CONFIRMED)

- **Cache setup.**
  - `CacheConfig.java:44` has a plain `@EnableCaching` (proxy mode, default order).
  - `:149-173` builds a `CaffeineCacheManager`, which is in-heap and stores **references**.
  - There are no `@CachePut`, no `beforeInvocation` and no transaction-aware decorator (grep: 0 hits).
- **Self-invocation inventory** (script `95-verify-3-cache.py`): 12 cached single-entity getters, all returning JPA entities. There are 26 in-class self-invocations from `@Transactional @CacheEvict` mutators.
  - **CityService:** `getCity` is `@Cacheable` at `:69-72`. It is called at `:85` and `:101` (the claim's `:84` is the signature line).
  - **MaterialService:** `getMaterial` at `:149-153`, whose Javadoc (`:142-143`) says the instance "is shared across callers and must be treated as read-only". It is called at `:297` and `:341` under `@EvictAllMaterialCaches`, which is two after-invocation evicts.
  - **TerminalService:** `getTerminal` at `:67-70`, called by five mutators (`:82,98,114,131,147`).
- **Cross-bean callers today:** only the 12 controllers' GET-by-id endpoints, which map to DTOs (grep: no service-layer caller).
- **What a split would do.**
  - On a cache hit, a command bean would receive the shared detached instance. Readers would then see uncommitted edits, and `save()` would merge.
  - On a cache miss inside the write transaction, the write transaction's managed instance would be cached.
  - `@CacheEvict` runs only if no exception crosses the cache interceptor. Any failure after the first setter therefore leaves the mutated instance cached until the TTL expires.
- **Ordering does not change the conclusion.**
  - Commit-time failures depend on which advisor is outermost. Both `@EnableCaching` and `@EnableTransactionManagement` default to `LOWEST_PRECEDENCE` (spring-context/spring-tx 7.0.9 sources), so the actual order is **UNKNOWN** without a runtime `Advised.getAdvisors()` check.
  - `beforeInvocation=true` is worse: it evicts first, and the proxied getter then re-caches the instance that is about to be mutated.
  - A transaction-aware cache evicts only after commit, never after a rollback.
- The fix direction is to cache immutable snapshots or DTOs, or to have commands load through the repository.

## 9 — JAVA-06 (NARROWED)

**Code facts.**

- The five sites are the only OrgUnit-subtype pattern matches in backend main. The cast at `OrgUnitMembershipService.java:343` follows `:340`.
- `Hibernate.unproxy` is already used elsewhere: `StaffelMembershipResolver.java:123`, `JobOrderHandoverService.java:148`, `JobOrderItemHandoverService.java:295`, `OrgUnitStampingService.java:109`.
- `OrgUnit` is abstract with SINGLE_TABLE inheritance (`OrgUnit.java:51-59`). There is no `@ConcreteProxy` and no bytecode enhancement.
- 18 lazy associations target the hierarchy (`95-verify-3-assoc.py`), so their proxies are typed `OrgUnit`.
- OSIV is off (`application.yml:65`).

**Hibernate 7.4.5 behaviour (from the source).**

- A query reuses an existing proxy only if the proxy is an instance of the query's entity type (`EntityInitializerImpl.resolveEntityInstance1` and `isProxyInstance`).
- `findById` through the `OrgUnit` persister returns an existing `OrgUnit` proxy: `DefaultLoadEventListener.loadWithRegularProxy` → `narrowedProxy` → `StatefulPersistenceContext.narrowProxy`, which treats it as `alreadyNarrow`.

**Per site.**

| Site | Where the OrgUnit comes from | Can a proxy reach the tested kind? |
|---|---|---|
| `OrgHierarchyController.java:366` (`instanceof Bereich`) | `findAllActiveWithParent()` (`OrgUnitRepository.java:116-117`, typed `OrgUnit`, join-fetches `parent`), in a fresh controller transaction (`:64`, `:157-158`) | **No.** Only a grandparent can be proxied, which is the OL (V164 trigger: SQ/SK→BEREICH→OL). A Bereich is always join-fetched or is its own root row. |
| `LeitungViewService.java:199` (`instanceof Organisationsleitung`) | OLs come only from `SELECT o FROM Organisationsleitung o` (`OrgUnitRepository.java:106-107`, subclass-typed), so an existing `OrgUnit` proxy is not reused | **No.** |
| `OrgChartReadService.java:101` | Same query (`:75`). Line `:74` loads Bereiche and *does* create an OL proxy, but the subclass-typed query bypasses it | **No**, but fragile if that query is ever retyped to `OrgUnit`. |
| `OrgUnitMembershipService.java:340` (`removeOlMember`) | `orgUnitRepository.findById(olId)`. Earlier in the same transaction, `isProfitBereich` loads PROFIT Bereiche with lazy `parent` = OL (`OrgUnitBankResponsibilityService.java:228-231`), and `findByOrgUnitIdAndUserId(olId, userId)` loads the member's OL chart seat with lazy `orgUnit` = OL (`OrgChartService.java:448-451`) | **Yes, data-dependent.** It does not happen when the CARTEL account is FK-linked to the OL (optional per V168), because the `@EntityGraph` query `BankAccountRepository.java:103-105` then loads the OL concretely first. |
| `OrgUnitMembershipService.java:462` | Called at `:370`, `:409` and `:443` as the first DB access of transactions opened by `OrgHierarchyController`, with an empty persistence context | **No** on current call paths. |

**Consequence at `:340`.** When a proxy arrives, the Grand Admiral pointer is not cleared when that member leaves the OL.

- The impact is display-only: no authorization reads `grandAdmiralUserId`. Its readers are `LeitungViewService:199`, `OrgChartReadService:102` and `OrgUnitMembershipService:375/410`.
- A later `setGrandAdmiral(sameUser)` returns early at `:376` without writing an audit event.
- No service or integration test covers `removeOlMember`; only `OrgHierarchyControllerSecurityTest` touches it.
- This is from reading the source only; I did not execute it (no Gradle).

## 10 — DOM-09 (CONFIRMED)

- `grep -rn FetchType.EAGER backend/src/main/java` finds exactly one hit: `MissionParticipant.java:86`, a `@ManyToMany(fetch = EAGER)` `Set<OrgUnit> orgUnits` with `@BatchSize(50)` (`:86-94`).
- All 148 real `@ManyToOne` and `@OneToOne` annotations declare `LAZY` on the annotation line. There are 150 grep hits, of which 2 are Javadoc mentions.
- Every other `@OneToMany`, `@ManyToMany` and `@ElementCollection` is LAZY, either by default or explicitly (`Mission.java:284` relies on the default).
- All 115 entities live under `model`, and there is no `@Fetch(JOIN)` or `@LazyToOne` override.
- `ArchitectureTest.toOneAssociationsAreDeclaredLazy` (`:283-295`, condition `:769-784`) covers only `@ManyToOne` and `@OneToOne`. No rule covers the fetch type of collections.
