# Re-evaluation of the September audit — Backend, Ingest, Keycloak, Build (76 findings)

Agent `80-prev-sept-a`, 2026-09-29. Read-only. Code authority: worktree `claude/basetool-refactor-modularization-68184f` = `origin/main` `95e945326` (checked with `git rev-parse HEAD origin/main`). Input: `sept_audit_findings.json`, areas Backend (37), Build (22), Ingest (12), Keycloak (5). Machine-readable twin: `80-prev-sept-a.json`.

Path shorthand used below and in the JSON: `be:` = `backend/src/main/java/de/greluc/krt/profit/basetool/backend/`, `be-test:` = the backend test root, `in:` = `ingest/src/main/java/de/greluc/krt/profit/basetool/ingest/`, `in-test:` = the ingest test root, `fe:` = `frontend/src/main/java/de/greluc/krt/profit/basetool/frontend/`, `kc:` = `keycloak-spi/src/main/java/de/greluc/krt/profit/basetool/keycloak/spi/`. Other paths are repository-relative. PR numbers were resolved to merge commits with `git log --oneline --grep="(#NNNN)" origin/main`.

## 1. Summary

### Ten conclusions

1. **66 of 76 are done and hold on `main`**; 7 are partial, 1 regressed through later code, 1 open, 1 superseded as recorded (§2; merge commits in §6). The vault's Done table names 73 of the 76: it omits BE-SIMP-04/-05 (done in #1994/#1996) and BLD-PERF-03 (open), and it records APPSEC-02, BE-MOD-02, ING-MOD-02 and THEME-SIMP-01 as done although each left a part undone (PSA-05).
2. **The only untouched item is BLD-PERF-03** (test contexts): no commit mentions it; 191 backend `@SpringBootTest` classes carry `@ActiveProfiles("test")` and 40 do not although Gradle sets the profile for all (`build.gradle.kts:186`), and a static approximation finds about 49 distinct backend context keys (`80-prev-sept-a-contexts.py`). Unify the profile now; design module-scoped test slices with the modularisation.
3. **Production's Keycloak admin client and the internal JWKS fetch run without timeouts**: the pinned factory `be:config/KeycloakTrustSupport.java:74-75` replaces the 5 s/30 s client of `be:config/RestClientConfig.java:92-97` (BE-MOD-02 residual, P1; PSA-02).
4. **New code regressed two September fixes by following the easy default**: a new trim-to-null copy (`be:service/exchange/ExchangeRegistryService.java:413-416`) and reference-only callers of the role-graphed `UserRepository.findById` (`be:service/exchange/ExchangeStockWriteService.java:798`, `ExchangeAccountCheckService.java:68`, `JobOrderItemProductionService.java:371`) — the expensive variant still carries the default name (`be:repository/UserRepository.java:302-305`).
5. **The error kernel blocks package-per-domain as it stands**: the sealed `AppException` permits domain exceptions and `AppExceptionKind` holds domain codes (`be:exception/AppException.java:37-50`, `AppExceptionKind.java:37-171`); javac 25 refuses a sealed class in the unnamed module that permits a class in another package (`compiler.err.class.in.unnamed.module.cant.extend.sealed.in.diff.package`; same-package control compiles). APPSEC-02 added one entry to each (PSA-01).
6. **Some September fixes coupled domains more tightly**: APPSEC-02 added the edge refinery → `MissionParticipantRepository` (0 references before #1985), BE-PERF-01 added hangar → `UserMapper` and made other domains prime an identity mapper's memo, BE-PERF-15 added a second memo of the caller's memberships beside `RequestScopeResolver`'s, and APPSEC-01 is the third copy of an inventory rule in a foreign domain.
7. **The `support` package grew as a domain-helper hub** (63 classes; `CachedEntityGraphs`, three retention records, 18 of 27 properties records), and the ArchUnit leaf rule's own message tells authors to put shared logic there (`be-test ArchitectureTest.java:596-598, 609-611, 624-626`) (PSA-04).
8. **The build conventions help a Gradle split, with two traps**: JaCoCo floors and test heap are keyed on `project.name`, so code moved out of `backend` silently falls from 0.82/0.65 to 0.50/0.40 (`build.gradle.kts:184, 228-241`), and the one Dockerfile enumerates subprojects and copies only `${MODULE}/src/main` (`docker/app/Dockerfile:12-27`) (BLD-SIMP-06, IMG-SIMP-14).
9. **Two guards depend on the current layout**: the peer-redaction rule is selected by package `.backend.controller` and hard-coded DTO names with a floor of 10 (`be-test ArchitectureTest.java:1129-1173`) and must be re-keyed in the same commit as any mission move; the `Entities.require` ratchet scans only `backend/src/main/java` and must be widened for a Gradle split. The lazy-association and export-budget sweeps are annotation- and type-based and survive either target.
10. **Ingest's findings were reshaped by the exchange epic and their invariants survived** (token invalidation in `in:exchange/ExchangeRelay.java:430-437`, surface pinned to 16 exchange routes in `IngestEndpointSurfaceTest`), but one credential-hygiene leftover remains: ingest's and the frontend's `MonitoringScrapeProperties` are `@Data` with the password in `toString`, unlike the backend record (ING-MOD-02 → P1; PSA-03).

### Counts

| Status | Count |
| --- | ---: |
| DONE | 66 |
| PARTIAL | 7 |
| OPEN | 1 |
| SUPERSEDED | 1 |
| REGRESSED | 1 |
| NOT-VERIFIABLE | 0 |
| **Total** | **76** |

| Verdict | Count |
| --- | ---: |
| CONFIRMED | 58 |
| ADJUSTED | 15 |
| SUPERSEDED-BY-MODULARISATION | 1 |
| DROPPED | 1 |
| REPRIORITISED | 1 |
| **Total** | **76** |

NOT-VERIFIABLE is never the primary status: three items are done in code but have a production half only a host or Prometheus read can confirm (APPSEC-04 ACL state, ING-SEC-04 leaves in use, ING-SEC-03 gauge value); each says so in its evidence, and the vault records the rollouts.

| Area | Findings | Not DONE |
| --- | ---: | ---: |
| Backend | 37 | 5 |
| Build | 22 | 2 |
| Ingest | 12 | 2 |
| Keycloak | 5 | 1 |

## 2. All 76 findings

Domain effect: *helps* = eases the domain-modular target, *hinders* = adds or keeps cross-domain coupling, *neutral*, *interacts* = changes shape with the target. Full text per finding in the JSON.

| ID | Area | Old | Title (DE) | Status | Verdict | New | PRs | Domain |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| APPSEC-01 | Backend | P0 | Produktions-Einbuchung schreibt in fremde Lager und Privatbestände | DONE | ADJUSTED | P2 (inventory module step) | #1989 | hinders |
| APPSEC-02 | Backend | P0 | Raffinerie-Order an fremde Mission verändert deren Auszahlungstopf | PARTIAL | ADJUSTED | P2 (re-home, mission module step); picker P3 | #1985 | hinders |
| BE-SIMP-02 | Backend | P0 | 17 Legacy-Missionsendpunkte: Sunset 2026-10-20, Frontend nutzt noch 12 | DONE | CONFIRMED | — | #1994, #1996 | helps |
| BE-SIMP-03 | Backend | P0 | Owner-Wechsel ohne Optimistic Lock — letzter Schreiber gewinnt | DONE | CONFIRMED | — | #1994 | neutral |
| SEC-16 | Build | P1 | OWASP-Suppressions laufen nie ab | DONE | ADJUSTED | P3 | #2019, #2074 | neutral |
| SEC-17 | Build | P1 | Test-Profil liegt in den Produktions-Jars | DONE | CONFIRMED | — | #2019 | helps |
| APPSEC-08 | Backend | P1 | Audience-Prüfung in prod fällt bei leerer Variable still weg | DONE | CONFIRMED | — | #1989 | neutral |
| APPSEC-09 | Backend | P1 | Sicherheitskommentare behaupten, das Backend habe keinen Management-Port | DONE | CONFIRMED | — | #1989, #2074 | neutral |
| BE-MOD-03 | Backend | P1 | `P0D` als Aufbewahrungsdauer löscht den ganzen Audit-Trail | DONE | CONFIRMED | — | #1989 | neutral |
| ING-SEC-01 | Ingest | P1 | Ingest-Konfiguration empfiehlt die Audience, die den Vorfall vom 2026-08-21 verursachte | DONE | CONFIRMED | — | #1990, #2074 | neutral |
| ING-SEC-02 | Ingest | P1 | Backend-401/403 wird als Login-Fehler an den Extractor gemeldet; Gateway-Token wird nie verworfen | DONE | CONFIRMED | — | #1990, #2270 | neutral |
| ING-PERF-01 | Ingest | P1 | Ingest-WebClients nutzen den globalen Netty-Pool ohne Eviction | SUPERSEDED | CONFIRMED | — | #2008, #1990 | neutral |
| ING-SEC-05 | Ingest | P1 | Nichts erzwingt, dass alle Ingest-Endpunkte unter /v1 liegen | DONE | CONFIRMED | — | #1990, #2270 | neutral |
| ING-SIMP-01 | Ingest | P1 | Filterreihenfolge: Gleichstand bei +15, Body-Puffer vor dem Rate-Limit | DONE | CONFIRMED | — | #1990 | neutral |
| ING-SIMP-03 | Ingest | P1 | Problem-Body doppelt gebaut, MDC-Schlüssel hart codiert | DONE | CONFIRMED | — | #1990 | neutral |
| ING-PERF-02 | Ingest | P1 | Handoff-Trim mit überflüssigem Redis-Roundtrip; `consume()` ist toter Code | DONE | CONFIRMED | — | #1990 | neutral |
| KC-CI-01 | Keycloak | P1 | Login-Gate-Modul ohne SpotBugs/FindSecBugs und ohne Coverage | DONE | CONFIRMED | — | #1990 | neutral |
| KC-PERF-01 | Keycloak | P1 | Erster Discord-Login fragt denselben Guild-Endpunkt dreimal ab | DONE | CONFIRMED | — | #1990 | neutral |
| THEME-SEC-01 | Keycloak | P1 | Login-Form blockiert Passwortmanager und hakt „Angemeldet bleiben“ immer vor | DONE | CONFIRMED | — | #1990 | neutral |
| BLD-PERF-01 | Build | P1 | Image-Builds laufen `:X:build` mit Ausschlussliste statt `:X:bootJar` | DONE | CONFIRMED | — | #2029 | neutral |
| BLD-PERF-02 | Build | P1 | Backend baut ungenutzte Distributionen und ein Sources-Jar | DONE | CONFIRMED | — | #2019 | neutral |
| BLD-PERF-07 | Build | P1 | `:frontend:test` kommt nie aus dem Build-Cache | DONE | CONFIRMED | — | #2019 | neutral |
| BLD-CI-09 | Build | P1 | Veraltete openapi.json fällt in keinem PR auf | DONE | CONFIRMED | — | #2019 | helps |
| BLD-PERF-10 | Build | P1 | Mockito-Agent wird beim Konfigurieren aufgelöst und als absoluter Pfad in den Cache-Key geschrieben | DONE | CONFIRMED | — | #2019 | neutral |
| TST-18 | Build | P1 | Redis-Tests laufen gegen Redis 7, Produktion gegen Redis 8 | DONE | ADJUSTED | P3 | #2019 | neutral |
| TST-19 | Build | P1 | Testcontainers-JDBC ohne `TC_DAEMON` | DONE | CONFIRMED | — | #2019 | neutral |
| TS-SIMP-01 | Build | P1 | test-support nutzt die JUnit-Pin „nur für keycloak-spi“ | DONE | CONFIRMED | — | #1990 | neutral |
| BE-PERF-02 | Backend | P1 | Sieben gepagte Abfragen holen Collections per EntityGraph — Paging im Speicher | DONE | CONFIRMED | — | #2004 | neutral |
| BE-PERF-03 | Backend | P1 | Full-Replace einer Mission lädt Teilnehmer × Einheiten kartesisch | DONE | CONFIRMED | — | #2004 | neutral |
| BE-PERF-10 | Backend | P1 | Fremdschlüssel ohne Index und ein unindizierter Integritätscheck | DONE | CONFIRMED | — | #2004 | helps |
| BE-SIMP-06 | Backend | P1 | Fünf öffentliche Methoden ohne Aufrufer, eine davon in zwei Specs zitiert | DONE | CONFIRMED | — | #2011 | neutral |
| BE-MOD-02 | Backend | P1 | Keycloak-Admin-Aufrufe unbeobachtet, pro Aufruf ein neuer Client | PARTIAL | ADJUSTED | P1 | #2008, #2038 | neutral |
| BE-MOD-05 | Backend | P1 | MapStruct-Mapper injizieren per Feld | DONE | CONFIRMED | — | #2011 | helps |
| BE-SIMP-07 | Backend | P1 | Vier Controller prüfen `"ROLE_ADMIN"` an der Rollenhierarchie vorbei | DONE | CONFIRMED | — | #2011 | neutral |
| BE-SIMP-08 | Backend | P1 | `trimToNull` achtmal nachgebaut | REGRESSED | CONFIRMED | P3 | #2011 | neutral |
| BE-MOD-06 | Backend | P1 | Kleine Idiom-Reste | DONE | CONFIRMED | — | #2011 | neutral |
| BE-SIMP-10 | Backend | P1 | ~970 inline voll qualifizierte Klassennamen (Backend 551, Frontend 420) | PARTIAL | ADJUSTED | P3 | #2011 | neutral |
| DOC-20 | Build | P1 | Veraltete Build-Kommentare und die `versions.properties`-Frage | DONE | CONFIRMED | — | #2019, #2074 | neutral |
| DOC-21 | Build | P1 | Projekt-CLAUDE.md nennt die stillgelegte pm.me-Adresse | DONE | CONFIRMED | — | 674e55bd7 | neutral |
| BE-PERF-01 | Backend | P2 | UserMapper: rund drei Queries pro gemapptem Nutzer | DONE | ADJUSTED | P2 (identity module step) | #2004 | hinders |
| BE-PERF-04 | Backend | P2 | JDBC-Batching ist aus | DONE | CONFIRMED | — | #2009 | neutral |
| BE-PERF-09 | Backend | P2 | UEX-Syncs halten eine Transaktion über den HTTP-Abruf und prüfen jede Zeile einzeln | DONE | CONFIRMED | — | #2009 | neutral |
| BE-PERF-12 | Backend | P2 | `UserRepository.findById` lädt immer Rollen und Rechte | PARTIAL | ADJUSTED | P2 | #2004 | hinders |
| BE-PERF-15 | Backend | P2 | Kleine N+1-Schleifen auf seltenen Pfaden | DONE | ADJUSTED | P3 | #2004 | hinders |
| BE-SIMP-01 | Backend | P2 | REQ-API-004 schreibt `Entities.require` vor — 247 Stellen schreiben es von Hand | DONE | CONFIRMED | — | #2011, #2015 | neutral |
| BE-SIMP-04 | Backend | P2 | Teilnehmer-Auflösung viermal geschrieben, mit zwei verschiedenen Queries | DONE | CONFIRMED | — | #1994 | helps |
| BE-SIMP-05 | Backend | P2 | 14 Inline-Redaktionsblöcke trotz `redactForPeer` | DONE | SUPERSEDED-BY-MODULARISATION | P2 (mission module step) | #1994, #1996 | neutral |
| BE-SIMP-09 | Backend | P2 | Zwei Request-Memo-Idiome, eines mit drei unchecked-Casts | DONE | ADJUSTED | P2 (org-unit module step) | #2011 | neutral |
| BE-SIMP-11 | Backend | P2 | Zwei Redis-Fanouts teilen ein Gerüst | DONE | CONFIRMED | — | #2011 | neutral |
| BE-MOD-01 | Backend | P2 | WebClient im Backend nur blockierend genutzt — WebFlux entfernen | DONE | CONFIRMED | — | #2008 | neutral |
| BE-MOD-04 | Backend | P2 | 18 von 20 Properties-Klassen sind veränderbare JavaBeans | DONE | CONFIRMED | — | #2011 | neutral |
| BE-MOD-05b | Backend | P2 | MapStruct ignoriert unbemappte Ziele still | DONE | CONFIRMED | — | #2015, #2011 | helps |
| BLD-PERF-03 | Build | P2 | Spring-Testkontext-Cache zersplittert — 38 Kontexte bei Cache-Größe 32 | OPEN | ADJUSTED | P1 (profile unification) / P2 (module-scoped test slices) | - | interacts |
| BLD-PERF-04 | Build | P2 | Configuration Cache aus, weil refreshVersions in jedem Build läuft | PARTIAL | CONFIRMED | P3 | #2019 | helps |
| BLD-SIMP-06 | Build | P2 | Rund 250 doppelte Zeilen über die Modul-Builds | DONE | ADJUSTED | P2 (before any Gradle split) | #2019 | helps |
| BLD-PERF-08 | Build | P2 | `minifyStaticCss` überschreibt die Ausgabe von `processResources` | DONE | CONFIRMED | — | #2019 | neutral |
| IMG-PERF-12 | Build | P2 | CDS-Training mit Lazy-Init lädt kaum Anwendungsklassen | DONE | CONFIRMED | — | #2029, #2050 | neutral |
| IMG-CI-13 | Build | P2 | Nichts prüft, ob das CDS-Archiv entsteht und akzeptiert wird | DONE | CONFIRMED | — | #2029, #2050 | neutral |
| ING-SIMP-02 | Ingest | P2 | Bucket-Fabrik doppelt; pro-IP- und pro-Subjekt-Limit teilen ein Budget | DONE | CONFIRMED | — | #1990, #2270 | neutral |
| ING-MOD-02 | Ingest | P2 | Veränderbare Properties-Beans und `@Value`-Feldinjektion | PARTIAL | REPRIORITISED | P1 | #1990 | neutral |
| ING-SEC-03 | Ingest | P2 | Ob die Gates in prod greifen, lässt sich nur mit Host-Zugriff klären | DONE | CONFIRMED | — | #1990, #2270 | neutral |
| KC-SIMP-01 | Keycloak | P2 | Vier eigene HttpClients, drei davon identisch | DONE | CONFIRMED | — | #1990 | neutral |
| THEME-SIMP-01 | Keycloak | P2 | Inline-`onsubmit` und überflüssige TTF-Fonts | PARTIAL | DROPPED | — | #1990 | neutral |
| APPSEC-06 | Backend | P2 | Jede `IllegalStateException` wird zu 400 mit Rohmeldung | DONE | CONFIRMED | — | #1989 | neutral |
| APPSEC-04 | Backend | P3 | Backend, Frontend und Ingest teilen einen allmächtigen Redis-Nutzer | DONE | CONFIRMED | — | #2023 | neutral |
| APPSEC-10 | Backend | P3 | Teure Export- und Report-GETs nur mit dem pro-IP-Budget | DONE | CONFIRMED | — | #1989 | neutral |
| ING-SEC-04 | Ingest | P3 | Jeder Ingest-Container hält den Schlüssel, der auch Backend und Keycloak ausweist | DONE | ADJUSTED | P3 | #2034, #2036 | neutral |
| ING-MOD-01 | Ingest | P3 | WebFlux aus dem internetseitigen Gateway entfernen | DONE | CONFIRMED | — | #2008 | neutral |
| XMOD-SIMP-01 | Build | P3 | LogSafe/PiiMasker dreifach, zusammengehalten von einem Spiegeltest | DONE | CONFIRMED | — | #2016 | helps |
| IMG-MOD-11 | Build | P3 | Dynamisches AppCDS durch den AOT-Cache von Java 25 ersetzen | DONE | CONFIRMED | — | #2029, #2050 | neutral |
| SEC-15 | Build | P3 | Keine Gradle-Dependency-Verification für Artefakte, die in signierte Images fließen | DONE | CONFIRMED | — | #2025, #2040 | neutral |
| BE-PERF-08 | Backend | P3 | Authorities-Cache: TTL über 5 min bringt nichts, weil `iat` im Schlüssel steckt | DONE | CONFIRMED | — | #2017 | neutral |
| BE-PERF-11 | Backend | P3 | 37 `@ManyToOne` noch EAGER | DONE | ADJUSTED | P2 (catalogue module step) | #2030 | helps |
| BE-PERF-13 | Backend | P3 | Live-Sync schreibt synchron an jeden Abonnenten auf dem Request-Thread | DONE | CONFIRMED | — | #2024 | neutral |
| BE-PERF-14 | Backend | P3 | gzip auf dem internen Frontend→Backend-Hop messen | DONE | CONFIRMED | — | #2027 | neutral |
| IMG-SIMP-14 | Build | P3 | Drei fast identische App-Dockerfiles | DONE | ADJUSTED | P2 (only for option B/C) | #2029 | hinders |

## 3. Non-trivial re-evaluations

Every finding whose status is not DONE, or whose verdict is not CONFIRMED, in full. Order: security and correctness first, then modularisation relevance.

### BE-MOD-02 — Keycloak-Admin-Aufrufe unbeobachtet, pro Aufruf ein neuer Client

- **Status:** PARTIAL · **Verdict:** ADJUSTED · **Priority:** P1 → P1
- **Evidence:** #2008: be:service/KeycloakService.java:100,112-127 builds one adminClient from the observed prototype builder (be:config/RestClientConfig.java:73-97: 5 s connect, 30 s read, HTTP/1.1). But where the keycloak-trust bundle exists (prod) KeycloakService.java:122-125 swaps in be:config/KeycloakTrustSupport.java:74-75, HttpClient.newBuilder().sslContext(...).build() + new JdkClientHttpRequestFactory(httpClient) with no connect or read timeout. The same factory backs the internal JWKS fetch (be:config/SecurityConfig.java:177-181), on in production since 2026-09-25 (#2038, vault Backend.md:398-411). The vault notes the missing read timeout (Backend.md:362-363).
- **Re-evaluation:** The observation half is done. The pinned client silently drops the builder's timeouts, so a hung Keycloak blocks the sync job and every JWKS refresh on the authentication path until the JDK/OS defaults (UNKNOWN exact value; settle with the java.net.http.HttpClient Javadoc). Fix: KeycloakTrustSupport takes the same connect/read timeouts and HTTP/1.1 as RestClientConfig (the ingest copy, in:config/KeycloakTrustSupport.java:73-74, has the same shape but is not used for JWKS in prod).
- **Domain separation:** neutral (platform)
- **Security:** Availability on the authentication path; keep the pinned trust and hostname verification unchanged; add a test that asserts the pinned factory's timeouts.

### ING-MOD-02 — Veränderbare Properties-Beans und `@Value`-Feldinjektion

- **Status:** PARTIAL · **Verdict:** REPRIORITISED · **Priority:** P2 → P1
- **Evidence:** #1990: 7 of 8 ingest @ConfigurationProperties are records (IngestProperties, ServiceAccountProperties masking its secret, RateLimitProperties, LoggingProperties, three Exchange*Properties). in:config/MonitoringScrapeProperties.java:32-50 is still a Lombok @Data class holding the scrape password, so its generated toString prints it; the backend twin is a record that redacts it (be:config/MonitoringScrapeProperties.java:53-59); the frontend twin is @Data too. @Value field injection remains in in:logging/StartupBannerListener.java:61-71 and in:metrics/TracingEnabledMetric.java:52. expectedAudiences is a bean-method parameter (in:config/SecurityConfig.java:126), as asked.
- **Re-evaluation:** The leftover is small and carries a credential, which makes it a P1 by the audit's own definition (small, low risk, clear value). No current log call prints the bean (grep), so the exposure is latent. It is also an instance of the hand-mirrored platform-class drift the July audit warned about (PSA-03).
- **Domain separation:** neutral
- **Security:** Record with a redacting toString in ingest and frontend; no behaviour change.

### BLD-PERF-03 — Spring-Testkontext-Cache zersplittert — 38 Kontexte bei Cache-Größe 32

- **Status:** OPEN · **Verdict:** ADJUSTED · **Priority:** P2 → P1 (profile unification) / P2 (module-scoped test slices)
- **Evidence:** No commit or vault row (git log --grep BLD-PERF-03: none). Today: backend 231 @SpringBootTest classes, 191 with @ActiveProfiles("test") and 40 without, although every Gradle Test task sets spring.profiles.active=test (build.gradle.kts:186); frontend 161 classes, 42 with / 119 without; no shared meta-annotation, no spring.test.context.cache.maxSize (grep). Static approximation (80-prev-sept-a-contexts.py): about 49 distinct backend context keys, 35 of them with @MockitoBean sets; the September audit counted 38 contexts against a cache of 32.
- **Re-evaluation:** Do the mechanical half now: one profile convention per module (add the annotation to the 40 backend classes that lack it, or drop it from the 191; the key must be uniform) and a shared mock set for the controller-security tests; measure before/after with the context-cache DEBUG log. Design the meta-annotations with the modularisation: module-scoped bootstraps (e.g. a Modulith-style module test) create one context per module by design, so size the cache to the module count instead of chasing a single shared context.
- **Domain separation:** interacts (module-scoped tests reshape the context landscape)
- **Security:** Security tests must keep the real filter chain and the real @PreAuthorize beans; a @MockitoBean on a guarded bean strips its annotations.

### APPSEC-02 — Raffinerie-Order an fremde Mission verändert deren Auszahlungstopf

- **Status:** PARTIAL · **Verdict:** ADJUSTED · **Priority:** P0 → P2 (re-home, mission module step); picker P3
- **Evidence:** #1985 (160e0dba4): be:service/RefineryOrderService.java:349-359 resolveMissionForOwner throws MissionParticipantRequiredException; REQ-SEC-042 extended; tests RefineryOrderServiceLifecycleTest, RefineryOrderTest. Remaining: the mission picker still lists every recent mission, fe:controller/RefineryOrderPageController.java:814 fetches /api/v1/missions?size=1000 unfiltered (deferred in #1985's 'Not in this PR').
- **Re-evaluation:** Server-side rule complete; the security gap is closed. The implementation added a new cross-domain repository edge, RefineryOrderService -> MissionParticipantRepository (0 references before #1985; 4 non-mission classes now use that repository: InventoryCheckoutService, JobTypeService, RefineryOrderService, UserDeletionService). It also grew the central error kernel: MissionParticipantRequiredException joined the sealed AppException permits (be:exception/AppException.java:37-50) and MISSION_PARTICIPANT_REQUIRED the shared AppExceptionKind enum (be:exception/AppExceptionKind.java:121). A sealed class in the unnamed module cannot permit a class in another package (javac 25: compiler.err.class.in.unnamed.module.cant.extend.sealed.in.diff.package, scratchpad 80-prev-sept-a-sealed), so this shape blocks package-per-domain (see PSA-01). Target: a mission query port (isParticipant(missionId, userId); participatingMissions(userId, since)) that also serves the still-open picker filter.
- **Domain separation:** hinders (new refinery -> mission repository edge; central error enum grew)
- **Security:** The server check stays authoritative (create, changed mission, on-behalf variants; unlink free). The picker filter is UX only and must not replace it.

### APPSEC-01 — Produktions-Einbuchung schreibt in fremde Lager und Privatbestände

- **Status:** DONE · **Verdict:** ADJUSTED · **Priority:** P0 → P2 (inventory module step)
- **Evidence:** #1989 (3340c1211). be:service/JobOrderItemProductionService.java:139 calls assertMayBookInFor, :321-334 (foreign owner needs OwnerScopeService.canManageUserInventory before any lookup; personal=true for someone else refused). Guards be-test:.../JobOrderItemProductionServiceTest, JobOrderProductionBookInSecurityTest (MockMvc 403).
- **Re-evaluation:** The rule is right and stays. It is an inventory rule (who may write into whose ledger) that now lives as a copy in three domains: be:service/InventoryItemService.java:446-453, be:service/JobOrderItemProductionService.java:327-334 and be:service/RefineryOrderService.java:592; the job-order service also writes InventoryItem rows through repository.InventoryItemRepository itself (jdeps-backend.txt). #1989 created no new edge (JobOrderItemProductionService already referenced OwnerScopeService 3 times before, git show 3340c1211^1), but it added a third copy of the rule. Target: the inventory module exposes one booking command that performs the on-behalf and personal checks itself; job order, refinery and exchange call it and drop their copies.
- **Domain separation:** hinders (foreign-domain rule duplicated in 3 services; cross-domain repository writes pre-existing)
- **Security:** Moving the check into an inventory API must keep it before any lookup (no existence oracle, REQ-INV-032) and keep JobOrderProductionBookInSecurityTest; add a rule that only the inventory module writes InventoryItemRepository. be:service/exchange/ExchangeStockWriteService.java:797-808 also writes personal rows directly (self-only per REQ-XCH-009) and should use the same API.

### BE-SIMP-05 — 14 Inline-Redaktionsblöcke trotz `redactForPeer`

- **Status:** DONE · **Verdict:** SUPERSEDED-BY-MODULARISATION · **Priority:** P2 → P2 (mission module step)
- **Evidence:** #1994 + #1996: six private redactForPeer overloads at be:controller/MissionController.java:1446-1512 with 21 call sites; no inline isLogisticianOrAbove block in a handler; be-test ArchitectureTest.java:1129-1162 peerReadableMissionEndpointsMustRedactPii (floor >= 10 at :1144), selection keyed on package '.backend.controller' (:1132, :1149) and hard-coded DTO names (:1170-1174).
- **Re-evaluation:** The helper is done, but redaction still happens per handler in the web layer, guarded by a structural rule. A package move can silently de-select handlers as long as ten remain selected. In the target the mission module's read API returns viewer-specific views (redaction applied inside the module, still through MissionPeerRedactor's explicit full-field constructor), so no web adapter can forget it, and the rule becomes a module-internal check.
- **Domain separation:** neutral now; regression risk during the move
- **Security:** REQ-SEC-007: re-key the ArchUnit selection (package and DTO names) in the same commit as any mission package move; keep MissionDataLeakTest; keep the explicit constructor (July audit).

### BE-SIMP-09 — Zwei Request-Memo-Idiome, eines mit drei unchecked-Casts

- **Status:** DONE · **Verdict:** ADJUSTED · **Priority:** P2 → P2 (org-unit module step)
- **Evidence:** #2011: be:support/RequestMemo.java typed Key<T>; keys are private to their owners (be:mapper/UserMapper.java:70-78, be:service/OrgRoleManagementSecurityService.java:60-61, be:service/OrgUnitCascadeService.java:64-66, be:service/RequestScopeResolver.java:72-102); RequestMemoTest.
- **Re-evaluation:** The mechanism is right and created no central key registry. Ownership is not: the caller's org-unit memberships are memoised three times under different keys (RequestScopeResolver.java:94-95/282-286, OrgRoleManagementSecurityService.java:60-61/277-282, UserMapper.java:70-71), each calling membershipRepository.findAllByIdUserId, so one request can read them up to three times. RequestScopeResolver also memoises a job-order decision (canViewJobOrders, :101-102, 312-331) that reaches SpEL as @ownerScopeService.canViewJobOrders(). Target: the org-unit module owns one memoised caller-memberships query; the job-order module owns canViewJobOrders.
- **Domain separation:** neutral (mechanism); hinders (ownership)
- **Security:** These memos feed scope predicates and authorization; consolidation must stay request-scoped, keep the admin-pin semantics, and keep OwnerScopeServiceTest and the RequestScopeResolver tests green.

### BE-PERF-01 — UserMapper: rund drei Queries pro gemapptem Nutzer

- **Status:** DONE · **Verdict:** ADJUSTED · **Priority:** P2 → P2 (identity module step)
- **Evidence:** #2004: be:mapper/UserMapper.java:70-78 request memos, :155 primeStaffelMemberships; called from be:controller/HangarController.java:125, MemberEvaluationController.java:183, UserController.java:144,308, be:mapper/JobOrderMapper.java:115,134, MissionMapper.java:99; be-test:mapper/UserMappingNoNPlusOneTest.
- **Re-evaluation:** Performance goal met. The mechanism spreads identity internals: 16 classes depend on UserMapper, #2004 added the edge HangarController -> UserMapper (0 references before), and controllers of other domains must remember to prime an identity mapper's request memo before mapping. The identity mapper also resolves Staffel memberships (org-unit knowledge, support/StaffelMembershipResolver). Target: domains embed a small member reference (id, display name) resolved through one identity batch query that memoises internally, so no caller primes anything.
- **Domain separation:** hinders (identity mapper as a cross-domain hub; new hangar edge)
- **Security:** UserDto carries PII; a thinner member reference reduces what other domains can leak. UserDtoRedaction and MissionPeerRedactor semantics must be preserved.

### BE-PERF-12 — `UserRepository.findById` lädt immer Rollen und Rechte

- **Status:** PARTIAL · **Verdict:** ADJUSTED · **Priority:** P2 → P2
- **Evidence:** #2004 added UserRepository.findPlainById (22 call sites) but the default findById stays graphed (be:repository/UserRepository.java:302-305, roles + roles.permissions). Reference-only callers still use it: be:service/JobOrderItemProductionService.java:371 and, added after the fix by the exchange epic, be:service/exchange/ExchangeAccountCheckService.java:68 (reads rsiHandle) and be:service/exchange/ExchangeStockWriteService.java:798 (FK target). 44 findById call sites in total.
- **Re-evaluation:** The expensive variant carries the default name, so new code regresses by default. Invert it: a plain findById and an explicitly named graphed lookup for authentication and /users/me. The identity module step completes it: other domains get a member-reference port, never UserRepository (47 non-repository classes use it today, grep).
- **Domain separation:** hinders (47 cross-domain users of UserRepository)
- **Security:** Authentication must keep loading roles and permissions inside its transaction (FirstLoginAuthoritiesIntegrationTest).

### BE-PERF-11 — 37 `@ManyToOne` noch EAGER

- **Status:** DONE · **Verdict:** ADJUSTED · **Priority:** P3 → P2 (catalogue module step)
- **Evidence:** #2030 (c3f522f0c): all 148 @ManyToOne/@OneToOne declarations in be:model are LAZY (grep); be-test ArchitectureTest.java:284 toOneAssociationsAreDeclaredLazy (annotation-keyed); LazyToOneReadPathsTest; be:support/CachedEntityGraphs.java.
- **Re-evaluation:** Lazy by default is the prerequisite for cutting cross-domain entity graphs, and the rule survives package moves. But CachedEntityGraphs is a catalogue helper (Material, Location, ShipType, JobType) in the shared support package; it exists because catalogue services cache JPA entities that other domains then hold as @ManyToOne targets. In the target the catalogue module caches immutable read models and other domains reference catalogue rows by id; CachedEntityGraphs then disappears.
- **Domain separation:** helps (lazy) / hinders (catalogue hub in support)
- **Security:** LazyInitializationException has hit production before (open-in-view=false); keep LazyToOneReadPathsTest in every move.

### BLD-SIMP-06 — Rund 250 doppelte Zeilen über die Modul-Builds

- **Status:** DONE · **Verdict:** ADJUSTED · **Priority:** P2 → P2 (before any Gradle split)
- **Evidence:** #2019: build.gradle.kts:122-442 subprojects { plugins.withId(...) } for toolchain, Lombok/JetBrains, test JVM, JaCoCo, PIT, Checkstyle, Spotless, CycloneDX, SpotBugs, licensee; settings.gradle.kts:8 FAIL_ON_PROJECT_REPOS.
- **Re-evaluation:** Conventions keyed on plugin id are inherited by a new domain subproject, which helps option B/C. But per-module values are keyed on project.name: test heap (build.gradle.kts:184) and JaCoCo floors (228-241: backend 0.82/0.65, anything unnamed 0.50/0.40). Code moved from backend into a new subproject would silently fall to the default floors. Before a split, move the logic into convention plugins in an included build (the Gradle-recommended structure: UNKNOWN here without web access, settle with docs.gradle.org 'Sharing build logic between subprojects') and make the floor an explicit per-project value whose absence fails the build.
- **Domain separation:** helps, with one trap (silent floor drop)
- **Security:** A silently lowered coverage floor weakens a quality gate; make a missing value an error.

### IMG-SIMP-14 — Drei fast identische App-Dockerfiles

- **Status:** DONE · **Verdict:** ADJUSTED · **Priority:** P3 → P2 (only for option B/C)
- **Evidence:** #2029: docker/app/Dockerfile (ARG MODULE; tail stages runtime-backend/-frontend/-ingest :127-143); the per-module Dockerfiles are gone.
- **Re-evaluation:** The build stage enumerates every subproject's build file (Dockerfile:12-17), copies only ${MODULE}/src/main and logging-support/src/main (:26-27), and admits only backend|frontend|ingest (:19-22). Under option B/C each new backend subproject needs COPY lines, or a repo-lint check that derives the list from settings.gradle.kts, in the style of check_sbom_coverage.py's SHIPPED_INSIDE map.
- **Domain separation:** hinders option B/C (build coupling); neutral for option A
- **Security:** Keep .dockerignore excluding **/src/test and secrets; do not replace the enumerated COPY with a whole-tree copy without re-checking .dockerignore (keystores, .env).

### BE-PERF-15 — Kleine N+1-Schleifen auf seltenen Pfaden

- **Status:** DONE · **Verdict:** ADJUSTED · **Priority:** P2 → P3
- **Evidence:** #2004: be:service/HangarImportService.java:129 grouped per-type count; be:service/OrgRoleManagementSecurityService.java:60-61,277-282 caller-membership memo; OrgUnitBankResponsibilityService single statement.
- **Re-evaluation:** The N+1s are gone, but the fix added a second memo of a fact RequestScopeResolver already memoised: OrgRoleManagementSecurityService.CALLER_MEMBERSHIPS (0 references before #2004) next to RequestScopeResolver.CACHE_KEY_CALLER_MEMBERSHIPS (present before #2004). Fold into one org-unit-owned query (see BE-SIMP-09).
- **Domain separation:** hinders slightly (duplicate memo of an org-unit fact)
- **Security:** The memo feeds appointment authorization; consolidation must stay request-scoped.

### BE-SIMP-08 — `trimToNull` achtmal nachgebaut

- **Status:** REGRESSED · **Verdict:** CONFIRMED · **Priority:** P1 → P3
- **Evidence:** #2011 left one implementation (be:support/StringNormalization.java:82); a new copy blankToNull appeared in be:service/exchange/ExchangeRegistryService.java:413-416 (a2e82ff34, 2026-09-27).
- **Re-evaluation:** Trivial; fold the copy into StringNormalization. A dedicated gate is not worth it. StringNormalization belongs to the shared kernel of the target.
- **Domain separation:** neutral
- **Security:** None.

### BE-SIMP-10 — ~970 inline voll qualifizierte Klassennamen (Backend 551, Frontend 420)

- **Status:** PARTIAL · **Verdict:** ADJUSTED · **Priority:** P1 → P3
- **Evidence:** Script 80-prev-sept-a-fqn.py: backend main 19 inline FQNs left in 10 files, mostly deliberate jakarta vs JetBrains @NotNull clashes (be:controller/UserController.java:728-815); frontend main 482 in 42 files (InventoryPageController 82, JobOrderWriteController 71, InventoryWriteController 62); tests 1214 (backend) + 1051 (frontend), excluded by #2011.
- **Re-evaluation:** Backend half done (#2011, 659 names). The frontend half never ran. Run OpenRewrite ShortenFullyQualifiedTypeReferences on each frontend package right before it moves into its domain package, as a separate commit, so the move diff stays reviewable.
- **Domain separation:** neutral
- **Security:** None.

### BLD-PERF-04 — Configuration Cache aus, weil refreshVersions in jedem Build läuft

- **Status:** PARTIAL · **Verdict:** CONFIRMED · **Priority:** P2 → P3
- **Evidence:** #2019: refreshVersions applied only with -PrefreshVersions (settings.gradle.kts:1-5); CI runs --configuration-cache (3 workflow invocations, grep); configureondemand and evaluationDependsOn gone. gradle.properties has no org.gradle.configuration-cache default ('left for after a soak', #2019).
- **Re-evaluation:** CI has run strictly with the cache since 2026-09-23; switch the default on for developer builds. More subprojects under option B/C raise configuration time, which the cache offsets.
- **Domain separation:** helps option B/C
- **Security:** None.

### SEC-16 — OWASP-Suppressions laufen nie ab

- **Status:** DONE · **Verdict:** ADJUSTED · **Priority:** P1 → P3
- **Evidence:** #2019 (b49547283): config/owasp/dependency-check-suppressions.xml, 9 of 9 <suppress> carry until="2026-12-22Z" (grep); reasons in <notes> elements. The header comment that described the renewal was removed by the ADR-0214 sweep (#2074, e929a3a81): 0 '<!--' in the file, and no doc names the renewal procedure (grep docs/, CONTRIBUTING.md).
- **Re-evaluation:** Expiry is right. All nine expire on one day, so the weekly scan turns red at once on 2026-12-22 by design. Under ADR-0214 the renewal procedure must live in a document (CONTRIBUTING, dependency-check section) instead of the deleted header; the vault still claims the header describes it (Security.md:880-881).
- **Domain separation:** neutral
- **Security:** Renewal must re-verify each false positive, never bump the date blindly.

### TST-18 — Redis-Tests laufen gegen Redis 7, Produktion gegen Redis 8

- **Status:** DONE · **Verdict:** ADJUSTED · **Priority:** P1 → P3
- **Evidence:** #2019: test-support/src/main/java/.../containers/TestImages.java:39-40 equals docker-compose.yml:243 (redis:8-alpine@sha256:3811...); 12 test classes use TestImages.REDIS; TestImagesTest asserts parity with compose and the Quadlet unit.
- **Re-evaluation:** The same drift class exists for PostgreSQL: the Testcontainers JDBC URL uses the floating tag postgres:18-alpine (backend/src/test/resources/application-test.yml:7) while compose pins a digest (docker-compose.yml:8,55). Extending TestImages to Postgres needs either a tc: URL that accepts a digest (UNKNOWN, settle with the Testcontainers JDBC-URL documentation) or a PostgreSQLContainer bean.
- **Domain separation:** neutral
- **Security:** Tests exercise the same image digest that production runs.

### ING-SEC-04 — Jeder Ingest-Container hält den Schlüssel, der auch Backend und Keycloak ausweist

- **Status:** DONE · **Verdict:** ADJUSTED · **Priority:** P3 → P3
- **Evidence:** #2034/#2036 (ADR-0211): per-service leaves; relay hostname check via app.ingest.verify-backend-hostname (in:config/IngestProperties.java:62, ingest/src/main/resources/application.yml:50 default false), switched on by the deployment default INTERNAL_TLS_VERIFY_HOSTNAME:-true (docker-compose.yml:315,368; quadlet/env.d/ingest.env.tmpl:24). Production state (all four steps, 2026-09-25) per vault; NOT-VERIFIABLE here.
- **Re-evaluation:** Done as designed. Fail-safe hardening: the jar default is off and only the deployment turns it on, so a jar started outside compose/Quadlet under a non-dev profile skips hostname verification. Flip the jar default to true and let dev/test opt out (they trust all anyway); verify on the E2E stack, whose committed TLS material must carry the backend name.
- **Domain separation:** neutral
- **Security:** Strengthens the default; no change for the running deployments.

### THEME-SIMP-01 — Inline-`onsubmit` und überflüssige TTF-Fonts

- **Status:** PARTIAL · **Verdict:** DROPPED · **Priority:** P2 → —
- **Evidence:** No onsubmit left in keycloak-theme and 0 .ttf files (grep, #1990). Not done: 'fonts once from a shared theme' - byte-identical Lato woff2 x3 in both keycloak-theme/krt-theme/account/resources/fonts and .../login/resources/fonts (167,912 bytes together, cmp identical) with @font-face in both CSS files.
- **Re-evaluation:** The remaining half saves about 84 KB inside one provider image and has no runtime effect. Sharing resources across Keycloak theme types depends on theme import mechanics that would need a test-stack check (UNKNOWN for custom common resources in Keycloak 26.7). Not worth the risk.
- **Domain separation:** neutral
- **Security:** The CSP benefit (no inline handlers) is already realised.

### ING-PERF-01 — Ingest-WebClients nutzen den globalen Netty-Pool ohne Eviction

- **Status:** SUPERSEDED · **Verdict:** CONFIRMED · **Priority:** P1 → —
- **Evidence:** By ING-MOD-01 (#2008, cc3c5d090; skipped on owner decision in #1990): ingest has no Reactor Netty client; in:config/RestClientConfig.java:142-144 JDK HttpClient HTTP/1.1; in-test:config/RelayIdleConnectionBoundTest pins the JDK keep-alive (30 s) below Tomcat's 60 s.
- **Re-evaluation:** The problem class disappeared with the reactive stack; the decision to skip it was right.
- **Domain separation:** neutral
- **Security:** None.

### Done and confirmed, with a note the modularisation needs

- **BE-SIMP-04** (helps (one mission-internal component)): Already the seam the target wants. At the mission module step it moves into mission internals; its identity dependency (UserService.findMatchesByExactName returning List<User> entities) should narrow to an id-returning identity query.
- **BE-SIMP-11** (neutral): Correct extraction (transport only, no template). Package-private placement works only while both fan-outs share the service package; when notification (a domain) and live sync (a platform concern) separate, it becomes a public class in shared infrastructure. A pure re-home at that step.
- **BE-SIMP-01** (neutral): Complete. The ratchet scans only backend/src/main/java; under a Gradle split (option B/C) every new backend subproject must join the scan or its code escapes it.
- **BE-PERF-04** (neutral (a constraint the id-reference migration must respect)): The reason order_inserts stays off (Hibernate orders inserts only by associations it knows; org_unit_membership references its org unit through a plain id) grows stronger once cross-domain references become ids. Nothing pins it off except the spec; a one-line configuration assertion would guard it before the id-reference migration.
- **IMG-PERF-12** (neutral (constraint)): Constraint for the modularisation: any new bean that needs a live database or Redis at refresh (for example an event-publication registry that queries at startup; UNKNOWN for Spring Modulith's defaults) fails the image build with '[AOT] FAILED'. That is a guard, but plan stubs before introducing such a framework.
- **BE-MOD-05** (helps (explicit edges)): Constructor injection turns cross-domain mapper 'uses' into explicit constructor edges that jdeps, ArchUnit and a module verifier can see.
- **BE-MOD-05b** (helps): When domain DTOs are split into module API records, every unmapped field becomes a compile error instead of a silent null.
- **XMOD-SIMP-01** (helps (precedent)): Complete, and a precedent that a scope-closed shared Gradle module is acceptable, useful for domain API modules under option B/C. The mirror pattern persists for platform classes (PSA-03): KeycloakTrustSupport x2 (same code), ManagementPortSecurityConfig, MonitoringScrapeProperties, TracingEnabledMetric, CorrelationIdFilter, StartupBannerListener x3 each, all diverged. ADR-0205 closes logging-support to log hygiene, so a platform module needs its own ADR.
- **BLD-CI-09** (helps (contract stability)): A reviewed, byte-stable contract file is the API-stability gate the modular target needs; any future per-module API document should join the same gate.
- **SEC-17** (helps (inherited by new subprojects)): The gate is applied by plugin id in the root build, so every future domain subproject (option B/C) inherits it.
- **BE-PERF-10** (helps (layout-independent gate)): A schema-level invariant independent of the code layout. If cross-domain JPA associations become plain UUID columns but keep their database FK constraints, the gate still applies.
- **BE-MOD-03** (neutral (adds to the support hub, see PSA-04)): Correct fix. Placement note: the three records are domain-specific (audit, notification, identity) yet sit in the shared support package, where 18 of the 27 backend @ConfigurationProperties records live (9 in config). They move with their modules; a pure move, no behaviour change.
- **BE-MOD-04** (neutral (placement, see PSA-04)): Complete and held through the exchange epic (16 -> 27 records). Placement: 18 of 27 sit in support and move with their modules. Seven @Value fields remain (e.g. be:config/SecurityConfig.java:117,124; be:service/UserRegistrationService.java:84), outside this finding's scope.
- **BE-SIMP-02** (helps (smaller mission API surface to carve out)): Correct and complete. MissionController is still the largest backend controller (1516 lines, 47 handler mappings, wc/grep); splitting it by sub-resource belongs to the mission module step, not to this finding. The deprecation guard reads openapi.json, so it survives package moves.
- **BE-SIMP-06** (neutral): Complete. The getAllMissions tripwire becomes unnecessary once module boundaries forbid foreign access to the mission repository; revisit in the mission module step.
- **APPSEC-06** (neutral): The error-mapping kernel is the right home. Related hub for the target: the sealed AppException enumerates domain exceptions and AppExceptionKind has 11 constants including domain ones (OVER_ALLOCATION, PRODUCTION_ALLOCATION, OWNER_ORG_UNIT_REQUIRED, MISSION_PARTICIPANT_REQUIRED), see PSA-01.
- **ING-SEC-05** (neutral): Pinning the routed surface is the right invariant for an internet-facing gateway and survived the exchange rework. The non-JWT case is still fail-closed, only no longer counted.
- **SEC-15** (neutral): Any framework added for the modularisation (Spring Modulith, jMolecules, an OpenRewrite plugin) means regenerating the metadata with an empty GRADLE_USER_HOME, a documented procedure.

## 4. New observations made while verifying (not among the 76)

### PSA-01 — The error kernel enumerates domain errors, and Java forbids a sealed class to permit subclasses in other packages

- **Evidence:** `be:exception/AppException.java:37-50` permits 13 subclasses, among them `BankConflictException`, `ExchangeProblemException`, `MissionParticipantRequiredException`, `OverAllocationException`, `OwnerOrgUnitRequiredException`, `ProductionAllocationException`; `be:exception/AppExceptionKind.java:37-171` has 11 constants including the domain ones; #1985 added one of each. Experiment: `scratchpad/80-prev-sept-a-sealed` (kernel/AppException permits mission.MissionProblem) → javac 25 (Zulu 25) compile error at `kernel/AppException.java:3:83`, diagnostic key `compiler.err.class.in.unnamed.module.cant.extend.sealed.in.diff.package` (`-XDrawDiagnostics`); the same two classes in one package compile (exit 0), so the package boundary alone causes it. Problem codes are a wire contract without a schema: the frontend mirrors them by hand (`fe:service/BackendServiceException.java:85`), the gateway translates them (`in:exchange/ExchangeRelay.java:122, 415`), and `openapi.json` lists none (grep count 0).
- **Impact on domain separation / modern features:** Blocks target A as it stands: domain exceptions cannot move into domain packages while the kernel stays sealed over them, and each new domain error edits two shared files.
- **Proposed change:** Keep a sealed kernel of generic kinds plus one `non-sealed abstract` domain-problem base in the kernel that domain packages extend; replace the enum by a `ProblemKind` interface (code, status, title key) implemented by per-domain enums; a registry test collects every implementation and asserts unique codes against a committed list.
- **Pros:** Domains own their errors; the kernel stops growing; codes become an explicit, tested contract.
- **Cons:** Loses the one-enum overview; adds a registry test.
- **Risks, regressions, security and guard:** Security: `GlobalExceptionHandler`/`ErrorDisclosurePolicy` must keep deciding what is echoed (APPSEC-06: no raw messages). Regression: renaming a code silently breaks the frontend toast keys and the exchange translation. Guards: the registry test, `GlobalExceptionHandlerTest`, the frontend `BackendServiceException` tests, the exchange relay tests.
- **Effort:** M · **Prerequisites:** A new ADR on the error model; REQ-API-004 wording; before the first domain package move.

### PSA-02 — The pinned Keycloak client has no timeouts on the production authentication path

- **Evidence:** `be:config/KeycloakTrustSupport.java:74-75` builds `HttpClient.newBuilder().sslContext(...).build()` and `new JdkClientHttpRequestFactory(httpClient)` without connect or read timeout; it replaces the builder's factory in `be:service/KeycloakService.java:122-125` and backs the internal JWKS decoder in `be:config/SecurityConfig.java:177-181`. The intended values are `be:config/RestClientConfig.java:92-97` (5 s, 30 s, HTTP/1.1). The internal JWKS is on in production since 2026-09-25 (vault Backend.md:405-411).
- **Impact on domain separation / modern features:** None on domain separation; availability of authentication (JWKS refresh) and of the user sync.
- **Proposed change:** Give `trustedRequestFactory` the same connect/read timeouts and HTTP version (backend; the ingest copy `in:config/KeycloakTrustSupport.java:73-74` for consistency), or build the pinned factory through `RestClientConfig`'s factory with an SSL-context parameter.
- **Pros:** Bounded failure instead of a hang; one client shape (ADR-0204).
- **Cons:** A read timeout that is too short could fail a large admin listing; use the 30 s the other clients use (the sync already skips a failed run).
- **Risks, regressions, security and guard:** Security: trust pinning and hostname verification unchanged. During a Keycloak outage requests fail fast (401/503) instead of hanging. Guards: a new test asserting the pinned factory's timeouts, `SecurityConfigInternalJwksDecoderTest`, `KeycloakServiceTest`.
- **Effort:** S · **Prerequisites:** None.

### PSA-03 — Hand-mirrored platform classes have already diverged across the three apps

- **Evidence:** Same-named classes in backend, frontend and ingest whose bodies all differ (md5 of the non-import lines): `ManagementPortSecurityConfig` (73/63/60 lines), `MonitoringScrapeProperties` (67/61/60; the backend record redacts the password, the frontend and ingest `@Data` classes print it in `toString`), `TracingEnabledMetric`, `CorrelationIdFilter` (235/210/82), `StartupBannerListener`; `KeycloakTrustSupport` in backend and ingest differs only in Javadoc (diff).
- **Impact on domain separation / modern features:** Neutral for domain separation; it is the cross-app drift risk the July audit named for security contracts.
- **Proposed change:** Short term: ING-MOD-02 (record + redacting `toString` in ingest and frontend) and a parity test for the security-relevant ones. Medium term: evaluate a second scope-closed module for platform concerns (management-port chain, scrape credentials, trust support, tracing gauge) under its own ADR, since ADR-0205 closes `logging-support` to log hygiene.
- **Pros:** One implementation of security-relevant infrastructure.
- **Cons:** A shared module ties the three apps' release cadence together; some classes are app-specific by role (the correlation filters differ for good reasons).
- **Risks, regressions, security and guard:** Security: a shared management-port chain must keep each app's actuator exposure; guard `ManagementPortIsolationTest` in every app.
- **Effort:** M · **Prerequisites:** New ADR (platform module) or a documented decision to keep mirrors with parity tests.

### PSA-04 — September fixes grew `support` into a domain-helper hub

- **Evidence:** `be:support` holds 63 classes (ls), among them domain helpers (`MissionPeerRedactor`, `MissionSectionVersions`, `MissionViewerAccess`, `InventoryAllocations`, `InventoryAuditLabels`, `JobOrderAuditLabel`, `JobOrderInventoryOwnerRedactor`, `StockViewerAccess`, `StaffelMembershipResolver`) and 18 of the 27 `@ConfigurationProperties` records. September added `RequestMemo` (#2011, generic), `CachedEntityGraphs` (#2030, catalogue-specific) and the three retention records (#1989). The leaf rule permits support → model/repository only and its messages tell authors to put shared logic in support (`be-test ArchitectureTest.java:577-599, 609-611, 624-626`).
- **Impact on domain separation / modern features:** Hinders: support is acyclic by rule but collects domain logic, so it becomes a de-facto shared domain module.
- **Proposed change:** At the first module step split support into a small shared kernel (RequestMemo, StringNormalization, OptimisticLock, LikePatterns, ProblemResponseFactory, Roles/Permissions) and per-domain internal packages; change the ArchUnit messages to point at the owning module.
- **Pros:** Clear ownership; smaller kernel.
- **Cons:** Many moves at once; the acyclicity guarantee must be carried over.
- **Risks, regressions, security and guard:** Security: the redaction and viewer-access helpers (MissionPeerRedactor, JobOrderInventoryOwnerRedactor, UserDtoRedaction, MissionViewerAccess, StockViewerAccess) carry REQ-SEC-007 and owner redaction; their ArchUnit rules must be re-keyed in the same commit. Guards: `backendPackagesShouldBeFreeOfDependencyCycles`, the redaction rules, `MissionDataLeakTest`.
- **Effort:** M · **Prerequisites:** Part of the module steps; ADR-0047 wording on the support leaf.

### PSA-05 — Vault drift found while verifying (read-only here; for the vault owner)

- **Evidence:** 1) `80 Plans/Improvement Audit 2026-09.md`'s Done table has no row for BE-SIMP-04 and BE-SIMP-05 (both done: #1994 introduced ParticipantTargetResolver, #1996 finished the redaction helper) nor for BLD-PERF-03 (open, and not marked open either). 2) `30 Roles and Permissions/Security.md:880-881` says the suppressions file's header explains renewal — the header is gone since #2074 and no document describes it (SEC-16). 3) `80 Plans/Improvement Audit 2026-09.md:118` marks APPSEC-02 done — the picker restriction was deferred by #1985 and is open (`fe:controller/RefineryOrderPageController.java:814`). 4) same note :120 lists THEME-SIMP-01 as done — the shared-font part is not. 5) same note :136: BE-SIMP-10's frontend half (482 names) is open and unstated. 6) `10 Systems/Backend.md:368-369` 'All 16 @ConfigurationProperties are records' — 27 today (still all records); :373-374 '`trimToNull` exists once' — a second copy since a2e82ff34; :344-347 the findPlainById caller list — three reference-only callers use the graphed `findById`. 7) `10 Systems/Ingest.md:713-714` 'the configuration properties are records' — `MonitoringScrapeProperties` is still `@Data`.
- **Impact on domain separation / modern features:** Stale notes read as authoritative (vault CLAUDE rule: correct and date).
- **Proposed change:** Correct each note, dated, in the same session as the implementing change.
- **Pros:** Vault matches `main` again.
- **Cons:** None.
- **Risks, regressions, security and guard:** None; no secret or personal data involved.
- **Effort:** S · **Prerequisites:** None.

## 5. Re-evaluation against the current framework — cross-cutting remarks

- **ADR-0223 (final features only):** every Java construct the September fixes introduced is final — records (BE-MOD-04, ING-MOD-02), `getFirst`/`getLast` and pattern-matching `switch` (BE-MOD-06), virtual threads (BE-PERF-13), the JDK 25 AOT cache (IMG-MOD-11, ADR-0209:40-41 names JEP 483/514/515). No `--enable-preview` anywhere (ADR-0223 decision 1). The sealed error hierarchy is final Java too, but its package rule constrains target A (PSA-01).
- **ADR-0214 (no comments):** three findings were about comments (APPSEC-09, ING-SEC-01, DOC-20); their object no longer exists. One durable fact was lost in the sweep — the OWASP suppression renewal procedure (SEC-16) — and needs a document.
- **Security posture:** no re-evaluation weakens a control. Every recommendation above either keeps the existing guard (listed per finding) or adds one (timeouts, redacting `toString`, registry test for problem codes, explicit coverage floors). The two items that move security checks (APPSEC-01 into an inventory API, BE-SIMP-05 into the mission read API) keep the check before any lookup and keep their tests.
- **Target options:** option A (packages) is blocked only by PSA-01 and must re-key the package-based guards (BE-SIMP-05, PSA-04); option B/C additionally needs BLD-SIMP-06's floor fix, IMG-SIMP-14's Dockerfile list, the `Entities.require` ratchet's source roots (BE-SIMP-01) and `verification-metadata.xml` regeneration for any new plugin (SEC-15).

## 6. Data appendix

### Merge commits of the implementing PRs (`git log --oneline --grep="(#NNNN)" origin/main`)

| PR | Commit | IDs in scope |
| --- | --- | --- |
| #1985 | 160e0dba4 | APPSEC-02 |
| #1989 | 3340c1211 | APPSEC-01, -06, -08, -09, -10, BE-MOD-03 |
| #1990 | 26d1c1223 | ING-SEC-01/-02/-03/-05, ING-SIMP-01/-02/-03, ING-PERF-02, ING-MOD-02, KC-CI-01, KC-PERF-01, KC-SIMP-01, THEME-SEC-01, THEME-SIMP-01, TS-SIMP-01 |
| #1994 | df111eb1a | BE-SIMP-02 (migration), BE-SIMP-03, BE-SIMP-04, BE-SIMP-05 (part) |
| #1996 | 5ab7ff01e | BE-SIMP-02 (deletion), BE-SIMP-05 (rest) |
| #2004 | ccc886d28 | BE-PERF-01, -02, -03, -10, -12, -15 |
| #2008 | cc3c5d090 | BE-MOD-01, BE-MOD-02, ING-MOD-01, ING-PERF-01 (moot) |
| #2009 | d90dc295c | BE-PERF-04, BE-PERF-09 |
| #2011 | fc85cfb7f | BE-SIMP-01, -06, -07, -08, -09, -10 (backend), -11, BE-MOD-04, -05, -05b, -06 |
| #2015 | ba54a2c31 | BE-SIMP-01, BE-MOD-05b (follow-ups) |
| #2016 | e2567f18b | XMOD-SIMP-01 |
| #2017 | 28e6113d5 | BE-PERF-08 |
| #2019 | b49547283 | BLD-PERF-02, -04, -07, -08, -10, BLD-SIMP-06, BLD-CI-09, SEC-16, SEC-17, TST-18, TST-19, DOC-20 |
| #2023 | 8893acfcb | APPSEC-04 |
| #2024 | df3fe12df | BE-PERF-13 |
| #2025 | 135f4c9bb | SEC-15 (+ #2040 6644d6421) |
| #2027 | 4af1a2065 | BE-PERF-14 |
| #2029 | 6f419c55e | BLD-PERF-01, IMG-SIMP-14, IMG-MOD-11, IMG-PERF-12, IMG-CI-13 (+ #2050 1842ddbdf) |
| #2030 | c3f522f0c | BE-PERF-11 |
| #2034/#2036 | 85c773061 / 35350483b | ING-SEC-04 |
| — | 674e55bd7 | DOC-21 |
| #2270 | 7866985ed | later: removed the legacy /v1 ingest routes; reshaped ING-SEC-02/-03/-05, ING-SIMP-02, ING-MOD-02 |
| — | a2e82ff34 | later: exchange registry; introduced the BE-SIMP-08 regression |

### Commands and scripts

- Selection: `python 80-prev-sept-a-extract.py` (filters `sept_audit_findings.json` to the four areas → 76; writes `80-prev-sept-a-selected.json`).
- Inline FQNs (BE-SIMP-10): `python 80-prev-sept-a-fqn.py backend/src/main/java frontend/src/main/java ingest/src/main/java keycloak-spi/src/main/java backend/src/test/java frontend/src/test/java ingest/src/test/java` → 19 / 482 / 3 / 0 / 1214 / 1051 / 23 (comments, strings, imports excluded).
- Test contexts (BLD-PERF-03): `python 80-prev-sept-a-contexts.py backend/src/test/java frontend/src/test/java ingest/src/test/java` → backend 231 classes / ~49 keys (40 used once, 35 with mock beans); frontend 161 / ~26; ingest 21 / ~13. Static approximation: no meta-annotations or context customizers evaluated. Profile split: `grep -rl @SpringBootTest <root> | xargs grep -L @ActiveProfiles | wc -l` → backend 40, frontend 119, ingest 21.
- Sealed-class rule (PSA-01): in `80-prev-sept-a-sealed/` with javac 25 (Zulu 25): `javac -XDrawDiagnostics -d out3 kernel/AppException.java mission/MissionProblem.java` → `compiler.err.class.in.unnamed.module.cant.extend.sealed.in.diff.package`, exit 1; control `javac -d out2 same/AppException.java same/MissionProblem.java` → exit 0.
- Properties records: loop over `grep -rl @ConfigurationProperties backend/src/main/java` checking `public record` → 27 records, 0 classes; ingest 7 records + `MonitoringScrapeProperties` (`@Data`).
- Lazy associations: `grep -rn` for `@ManyToOne` or `@OneToOne` under `backend/src/main/java/.../backend/model`, then `grep -c LAZY` → 150 hits, 148 with `FetchType.LAZY`; the other two are Javadoc text (`OrgUnitMembershipId.java:55`, `TermsAcceptance.java:58`).
- New cross-domain edges: `git show <merge>^1:<file> | grep -c <Type>` before vs `grep -c` today (RefineryOrderService/MissionParticipantRepository 0 → present; HangarController/UserMapper 0 → 2; OrgRoleManagementSecurityService caller memo 0 → 6 before #2011).
- jdeps edges: `jdeps-backend.txt` (coordinator), e.g. `grep "service.RefineryOrderService -> " jdeps-backend.txt`.
- No Gradle run (briefing rule); no host or Prometheus access. Output assembled by `python 80-prev-sept-a-build.py` from `80-prev-sept-a-data.py`.
