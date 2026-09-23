# Profit Basetool

The Profit Basetool is the squadron-management web app for the
"[DAS KARTELL](https://das-kartell.org/)" organization in *Star Citizen*.
It provides a central platform for mission planning, hangar and inventory tracking,
refinery and material logistics, an organization bank, terminal data and member
administration — backed by single-sign-on via Keycloak and a clear role and
permission model.

This README is the developer overview: what the app does, how it is put together, and
how to build, run and test it. The binding detail lives in the docs linked below —
requirement specs (`REQ-<AREA>-NNN`), ADRs, the role matrix and the deployment
runbook. Individual features, plans and decisions are **not** re-documented here.

---

## Overview

### What the application provides

- **Mission planning** — plan, brief and review squadron missions with role-aware access, a live procedure-step checklist and per-mission radio frequencies. Non-internal missions are visible to every member of the organisation, not only to the owning unit.
- **Operations & payouts** — group missions under an *Operation*, track per-participant finances and confirm payouts behind an asymmetric mark/clear gate.
- **Members only** — the landing page and the legal pages are the whole public surface (ADR-0159). Everything else needs a login, and every account holds at least member rights. A person without an account can still take part in an Einsatz: the mission leadership records them as an external participant.
- **Job orders & cross-order material demand** — a prioritised queue of material and item orders, plus a *Materialbedarf* overview that folds every open / in-progress order into what each responsible unit still has to gather per material, showing booked stock and signed-up claims side by side.
- **Hangar & inventory** — track ships and personal inventories per member, with a server-paginated org-unit fleet overview and personal hangar.
- **Lager (warehouse)** — org-scoped, append-only stock tracking: book stock in/out, transfer it (Umbuchen) — individually or for a whole marked selection at once — earmark slices to job orders and missions, and track both materials (with quality) and craftable game items.
- **Refinery & materials** — refinery job orders, material handovers and a planet-aware materials matrix; new orders can be pre-filled from a desktop-extractor screenshot JSON.
- **Materialbörse** — a central, org-wide material-exchange trade board where members both **offer** owned stock (Angebote) and post **requests** (Gesuche) for materials and craftable items; requests carry an optional minimum quality and a desired quantity, and other members signal "Ich kann liefern". Handover and location stay off-tool and private.
- **Kartellbank** — an organization bank on a double-entry, append-only ledger with accounts, a holder registry, per-account grants, tiered approval ladders and PDF statements; gated by dedicated Keycloak bank roles.
- **User & role administration** — manage members, per-Staffel capability flags and graded leadership ranks via a delegated *Leitung* page, from which an SK lead also manages their own Spezialkommando's members.
- **In-app notifications** — a rule-driven notification engine delivering per-user notifications to a personal inbox via polling plus a live SSE push.
- **Live multi-user sync** — every surface several people share updates in place for all viewers when a peer changes it, over one multiplexed `/ws/sync` WebSocket fanned across replicas by Redis pub/sub.
- **Activity audit logs** — an immutable, append-only activity trail across the audited areas, on one admin-only page with per-area filters and PDF/JSON export.
- **Discord login** — optional social login gated (fail-closed) on guild membership and an in-guild role, with an admin approval queue for new sign-ups (approve, reject, or link a sign-up onto an existing account).
- **Personal inventory & blueprints** — members maintain their own item list and unlocked crafting blueprints (importable from external extractors), with craftability and org-unit availability overviews.
- **Org chart & structure** — an interactive hierarchy view (OL → Bereiche → Staffeln/SKs) plus admin structure maintenance.
- **Data protection, in the application** — every member exports a complete copy of their own data as PDF or JSON and can request the erasure of their account; an admin decides the request, can export another account's for a requester who cannot sign in, and has a name search that finds every mention of a person across the free-text surfaces no foreign key points at. Retention is bounded rather than open-ended: refused registrations 90 days, notifications 90/180 days, both audit trails 24 months. The engineering records the GDPR expects a controller to hold are in [`docs/privacy/`](docs/privacy/README.md).
- **i18n & Keycloak theme** — every user-visible string is translated (German default, English); a custom Keycloak theme carries the DAS KARTELL corporate design.
- **Companion clients** — a native Android app ([`krt-profit/basetool-android`](https://github.com/krt-profit/basetool-android)) consumes the same REST API through the dedicated API vhost, with a server-side version policy (`GET /api/v1/app/version-policy`); the desktop SC extractor ([`krt-profit/basetool-sc-extractor`](https://github.com/krt-profit/basetool-sc-extractor)) sends refinery and blueprint data through the ingest gateway. Neither carries business logic of its own.

The full permission model is in [ROLES_AND_PERMISSIONS.md](ROLES_AND_PERMISSIONS.md); per-feature behaviour is specified under [`docs/specs/`](docs/specs/INDEX.md).

### High-level architecture

```
┌──────────────┐         ┌─────────────┐         ┌──────────────┐          ┌──────────────┐          ┌───────────────────┐
│   Browser    │ ──SSO──►│   Keycloak  │◄────────│   Backend    │◄──relay──│    Ingest    │◄──token──│ Desktop Extractor │
│              │         │  (OIDC IdP) │  JWT    │ (REST, JPA)  │ internal │(edge gateway)│   POST   │    (JSON push)    │
└──────┬───────┘         └─────────────┘         └──────┬───────┘          └──────────────┘  (HTTPS) └───────────────────┘
       │                                                 │
       │                ┌─────────────┐                  │
       └───HTML/CSS────►│  Frontend   │──WebClient──────►│
                        │ (Thymeleaf) │   bearer-token   │
                        └──────┬──────┘                  │
                               │                         │
                               ▼                         ▼
                         ┌─────────┐               ┌──────────┐
                         │  Redis  │               │ Postgres │
                         │(session)│               │  (data)  │
                         └─────────┘               └──────────┘
```

- **Backend** — REST API only (`/api/v1/...`), Spring Boot on Java 25, JPA / Flyway / PostgreSQL. Never serves HTML.
- **Frontend** — Thymeleaf-rendered UI calling the backend via a centrally-configured, Resilience4j-wrapped WebClient. No business logic of its own; no direct database or Keycloak Admin API access.
- **Keycloak** — OAuth2 / OIDC identity provider with a custom KRT theme and a `keycloak-spi` provider JAR (Discord login + the guild/role login gate).
- **Redis** — Spring Session store; sessions survive frontend restarts.
- **Ingest** — internet-facing one-click gateway for the desktop extractor; owns no database and relays token-authenticated `POST`s to the backend over the internal network, so the extractor never reaches the backend itself. (The backend's only internet path is the default-deny API vhost for the Android app, ADR-0135.) **Restricted interface:** only client software explicitly approved by the basetool developer (@greluc) may use it — enforced technically at the gateway (see [`docs/specs/desktop-ingest.md`](docs/specs/desktop-ingest.md)) and binding on users through section 4 of the Terms of Use (`REQ-SEC-027`, [`docs/specs/security-and-access.md`](docs/specs/security-and-access.md)).

The tenant unit is the **OrgUnit** — a Staffel (`SQUADRON`), Spezialkommando (`SPECIAL_COMMAND`), Bereich (`BEREICH`) or Organisationsleitung (`ORGANISATIONSLEITUNG`), the latter two stacked above the Staffeln/SKs. Staffel-scoped aggregates (Mission, Operation, Ship, InventoryItem, RefineryOrder) carry an `owning_org_unit_id` (nullable for deliberate *ownerless* rows). Job Orders are scoped separately via `responsible_org_unit_id` (the processing unit, governs visibility) and `requesting_org_unit_id` (the customer). See [`docs/specs/org-unit-tenancy.md`](docs/specs/org-unit-tenancy.md) for the full per-aggregate scope model.

---

## Documentation

The README is the overview; everything else lives in dedicated, versioned docs:

| Document                                                                                         | Purpose                                                                                                                                                                                                                                                         |
|:-------------------------------------------------------------------------------------------------|:----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| [CHANGELOG.md](CHANGELOG.md)                                                                     | Release notes and every user-visible change.                                                                                                                                                                                                                    |
| [CONTRIBUTING.md](CONTRIBUTING.md) / [CLA.md](CLA.md) / [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) | Contribution workflow and style guide, Contributor License Agreement, community standards.                                                                                                                                                                      |
| [.github/SECURITY.md](.github/SECURITY.md)                                                       | Security policy, supported versions, release verification (Cosign, SLSA provenance, GitHub attestations, SBOM).                                                                                                                                                 |
| [.github/CODEOWNERS](.github/CODEOWNERS)                                                         | Review routing — who is auto-requested on a PR, and which surfaces count as high-risk. Grants no permissions.                                                                                                                                                   |
| [ROLES_AND_PERMISSIONS.md](ROLES_AND_PERMISSIONS.md)                                             | The full role and permission matrix, and the public surface — which is now the landing page and the legal pages.                                                                                                                                                |
| [docs/specs/INDEX.md](docs/specs/INDEX.md)                                                       | Registry of the binding requirement specs (`REQ-<AREA>-NNN`) — security, tenancy, persistence, API, observability, UI and the per-feature specs.                                                                                                                |
| [docs/privacy/README.md](docs/privacy/README.md)                                                 | The data-protection records: processing activities (Art. 30), technical and organisational measures (Art. 32), processors (Art. 28), the data-subject-request procedure (Art. 12–21), the breach runbook (Art. 33) and the DPIA threshold assessment (Art. 35). |
| [docs/arc42/README.md](docs/arc42/README.md)                                                     | Architecture documentation along the arc42 template — context, building blocks, runtime, deployment (as of **after the Podman cutover**), cross-cutting concepts, quality and known debt. |
| [docs/adr/README.md](docs/adr/README.md)                                                         | Architecture Decision Records.                                                                                                                                                                                                                                  |
| [docs/archive/README.md](docs/archive/README.md)                                                 | Implemented plans and executed one-time runbooks, frozen as historical records (the Podman cutover, the multi-squadron and members-only rollouts, …). Not current procedure. |
| [docs/deployment.md](docs/deployment.md)                                                         | Production deployment runbook — host bootstrap, releases, rollback, troubleshooting.                                                                                                                                                                            |
| [backend/.../db/migration/README.md](backend/src/main/resources/db/migration/README.md)          | Flyway migration conventions.                                                                                                                                                                                                                                   |
| [docs/e2e-test/README.md](docs/e2e-test/README.md)                                               | End-to-end test use cases, one per functional flow.                                                                                                                                                                                                             |
| [CLAUDE.md](CLAUDE.md)                                                                           | Guidance for the Claude Code AI assistant — build/run/test, architectural invariants, conventions.                                                                                                                                                              |
| [.claude/skills/das-kartell-design/README.md](.claude/skills/das-kartell-design/README.md)       | The DAS KARTELL design system (source of truth for colors, typography, components); a submodule of [`krt-profit/design-system`](https://github.com/krt-profit/design-system).                                                                                   |
| [Profit Basetool Wiki](https://github.com/krt-profit/basetool/wiki)                              | German end-user handbook, one page per feature area.                                                                                                                                                                                                            |

---

## Development & testing

### Prerequisites

- [Java 25](https://adoptium.net/) — required for local Gradle builds.
- [Docker](https://www.docker.com/) and Docker Compose — for the dependency stack and the dev/test stacks.
- Access to a Keycloak server — the Docker Compose stack ships one.

The project uses **Gradle 9 with the Kotlin DSL**. Always use the wrapper (`./gradlew`); never the IDE test runner. Dependency versions live in the **version catalog** at `gradle/libs.versions.toml` — edit that, not `build.gradle.kts`. [refreshVersions](https://jmfayard.github.io/refreshVersions/) runs in catalog mode and only on request: `./gradlew refreshVersions -PrefreshVersions` annotates the catalog in place with `## ⬆ = "…"` comments for each available update rather than changing any version itself. (The empty `versions.properties` it recreates on each run is gitignored.) CI builds with the configuration cache (`--configuration-cache`); a local build can opt in the same way.

### Local development (apps from Gradle)

Recommended for active development — the apps run on the host JVM with fast restarts; only the dependencies live in containers.

```bash
# 1. Start dependencies (Postgres ×2, Keycloak, Redis)
docker compose --profile dev up -d db-backend-dev db-keycloak-dev keycloak-dev redis-dev

# 2. Run the backend (dev profile, HTTPS) — https://localhost:11261
./gradlew :backend:bootRun

# 3. Run the frontend (dev profile, HTTP) — http://localhost:18081
./gradlew :frontend:bootRun
```

Host ports: backend `11261`, frontend `18081`, ingest `11262`, Keycloak `18080`, backend DB `15432`, Keycloak DB `15433`, Redis `6379`. The OpenAPI documents are served at `https://localhost:11261/v3/api-docs` (backend) and `https://localhost:11262/v3/api-docs` (ingest gateway) in the `dev`/`test` profiles only (disabled in `prod`); there is no Swagger UI.

### Full stack via Docker Compose

```bash
docker compose --profile dev up -d              # pulls :stable from GHCR, exposes host ports
docker compose -f docker-compose.yml -f docker-compose.build.yml \
    --profile dev up -d --build                 # build locally from this checkout (tags :local)
```

For a fully isolated stack with **throwaway** credentials (never the production `.env`, `keystore.p12` or `realm-export.json`) — used for UI verification in a worktree — see [§ Running the local test stack](#running-the-local-test-stack) below.

### Tests

Tests force `spring.profiles.active=test`. Both `Test` and `BootRun` set `--enable-native-access=ALL-UNNAMED`; `Test` additionally attaches the Mockito agent.

```bash
./gradlew test                                              # all tests
./gradlew :backend:test                                     # backend only
./gradlew :frontend:test                                    # frontend only (produces a JaCoCo report)
./gradlew :backend:test --tests "FullyQualifiedClassName"   # single test class
./gradlew :backend:test --tests "ClassName.methodName"      # single test method
```

ArchUnit rules in each module's `ArchitectureTest.java` (`backend`, `frontend`, `ingest`) enforce architectural invariants (no `SecurityContextHolder` outside the auth-helper service, every `@RestController` carries at least one `@PreAuthorize`, controllers never return JPA entities, the frontend never depends on Spring Data JPA). A violation fails `./gradlew test`.

### Linting, static analysis and SBOM

```bash
./gradlew check                                             # full sweep: Checkstyle + SpotBugs + tests + Spotless
./gradlew :backend:checkstyleMain :backend:spotbugsMain     # backend lint only
./gradlew spotlessApply                                     # auto-format sources — run before every push
./gradlew :backend:cyclonedxBom :frontend:cyclonedxBom      # SBOM on demand into <module>/docs/
./gradlew :ingest:cyclonedxBom :keycloak-spi:cyclonedxBom   # the other two shipped modules (REQ-OPS-025)
./gradlew :backend:licensee                                 # third-party licence gate (runs in check, ADR-0197)
```

Checkstyle runs with `maxWarnings = 0` and Spotless is wired into `check` — any unformatted file or new Checkstyle warning fails CI. The frontend additionally runs strict asset gates in `check` that Spotless does not cover — Stylelint (`:frontend:lintCss`, `:frontend:lintCssInline`), ESLint (`:frontend:lintJs`, `:frontend:lintProbeJs`), HTMLHint (`:frontend:lintHtml`) and Prettier (`:frontend:prettierCheck`); run `spotlessApply` **and** those before pushing changes under `src/main/resources/static/**` or `templates/**`. The task list and the auto-fix recipe are in the [`lint-gate`](.claude/skills/lint-gate/SKILL.md) skill.

The frontend's browser scripts are also statically type-checked by `:frontend:typecheckJs` (strict, in `check`). TypeScript runs there as a **checker only** — `tsc --noEmit`, no compilation, no bundle, no renamed files; the sources stay JavaScript and opt in per file with a leading `// @ts-check`. Backend DTO types are generated from `backend/src/main/resources/api/openapi.json` by `:frontend:generateApiTypes` on every build and are never committed, so the frontend's view of a DTO cannot drift from the published contract. See [ADR-0125](docs/adr/0125-typed-javascript-via-checkjs-not-typescript.md), REQ-FE-018, and [`docs/TYPESCRIPT_MIGRATION_PLAN.md`](docs/TYPESCRIPT_MIGRATION_PLAN.md) for the (currently unscheduled) full-TypeScript path.

### End-to-end (E2E) tests

Playwright-Java drives the real frontend through a browser. The suite lives in the `frontend` module's `e2e` source set and is **not** wired into `check` (it needs Docker and a downloaded browser).

```bash
./gradlew :frontend:e2eTest                              # full destructive flows (@Tag("e2e"))
./gradlew :frontend:smokeTest                            # non-destructive page-load checks (@Tag("smoke"))
./gradlew :frontend:e2eTest -Pe2e.browser=firefox        # engine: chromium (default), firefox, webkit
```

By default the suite builds the app images, brings up an ephemeral stack with throwaway credentials, seeds the minimal data, runs and tears down. Set `E2E_BASE_URL` to point at an already-running deployment instead. CI runs a Chromium/Firefox/WebKit matrix — see [`e2e.yml`](.github/workflows/e2e.yml) and the per-flow use cases under [`docs/e2e-test/`](docs/e2e-test/README.md). There the images are built once per run and loaded into every matrix cell (`-Pe2e.prebuilt=true` boots them with `--no-build`), and `-Pe2e.browser` makes `playwrightInstall` fetch only that engine.

---

## Deployment

> [!IMPORTANT]
> **Never ship placeholder credentials into production.** Generate strong values
> (`openssl rand -base64 32`) for every secret and rotate them before the first
> deployment — the Keycloak bootstrap admin in particular is the realm-master account.

Every stack is configured from one `.env` file: copy `.env.example` and replace every `CHANGE_ME`. A local Compose stack reads it from the repository root, and its `${VAR:?...}` references refuse to start the stack when a required variable is missing. The production host keeps it at `/var/iri/code/.env`, and the deployer renders it into one closed allow-list env file per service (`scripts/render-env-d.py`, from the `quadlet/env.d/*.env.tmpl` templates), refusing the same missing variables. The essential keys:

```env
POSTGRES_USER / POSTGRES_PASSWORD            # backend DB
KC_POSTGRES_USER / KC_POSTGRES_PASSWORD      # Keycloak DB
KC_BOOTSTRAP_ADMIN_USERNAME / _PASSWORD      # Keycloak realm-master admin
KEYCLOAK_ADMIN_CLIENT_SECRET                 # backend → Keycloak admin API
KEYCLOAK_FRONTEND_CLIENT_SECRET              # optional: set = the frontend logs in as a confidential client (ADR-0001)
SERVER_SSL_KEY_STORE_PASSWORD                # PKCS12 keystore password
IRI_KEYSTORE_HOST_PATH                       # host path of keystore.p12 (bind-mounted read-only)
REDIS_PASSWORD                               # Redis session store
ACME_EMAIL                                   # Let's Encrypt contact address (edge certificates)
EDGE_HOST_FRONTEND                           # the four vhost names the edge serves — no defaults,
EDGE_HOST_INGEST                             # the edge refuses to start without them
EDGE_HOST_GRAFANA
EDGE_HOST_API
ACME_HOSTS                                   # hosts acme obtains a certificate for; EMPTY = none
```

Optional channels ship as safe no-ops until configured: transactional e-mail (SMTP — account approval/rejection + new-registration notices), the Discord social login, and the internet-facing **ingest** gateway (needs its own vhost in [`docker/edge/conf.d/`](docker/edge/conf.d)). Their env keys are documented in `.env.example`, [docs/keycloak/DISCORD_KEYCLOAK_SETUP.md](docs/keycloak/DISCORD_KEYCLOAK_SETUP.md) and [docs/INGEST_KEYCLOAK_SETUP.md](docs/INGEST_KEYCLOAK_SETUP.md).

**Production runtime.** Production runs on a single Rocky Linux 10 host as **rootless Podman + Quadlet** units: every container is a systemd user unit of an unprivileged service account, generated from the compose files by [`scripts/generate-quadlet.py`](scripts/generate-quadlet.py) into [`quadlet/`](quadlet/) and shipped in the config bundle. The host itself is bootstrapped by the Ansible role under [`ansible/`](ansible/README.md); the internet-facing edge is the nginx container under [`docker/edge/`](docker/edge/). The Compose files stay the source of those units and remain the way local, test and E2E stacks run.

**Production release loop.** Hosts do not build images. [`release-images.yml`](.github/workflows/release-images.yml) builds, scans, signs (Cosign) and pushes the backend/frontend/ingest images, the `keycloak-spi` provider bundle and a `basetool-config` bundle to GHCR; [`promote.yml`](.github/workflows/promote.yml) re-tags a verified digest as `:stable` (and [`promote-testing.yml`](.github/workflows/promote-testing.yml) as `:testing`); the host's `iri-deploy.timer` polls `:stable` every five minutes and `scripts/deploy.sh` applies new digests with health-check-gated rollback. While an upstream cycles, the edge serves a branded maintenance page. The full runbook — including manual rollback, backups & disaster recovery, and the admin-only monitoring stack — is in [**docs/deployment.md**](docs/deployment.md), with the deployment view in [arc42 §7](docs/arc42/README.md) and binding requirements under [`docs/specs/`](docs/specs/INDEX.md).

**The Keycloak realm is not delivered.** It lives in each host's database, so a new host's realm — or a testing realm that has fallen behind — is brought to production's shape (the Basetool's clients, audience scopes, DPoP policy, service-account roles, token settings) by [`scripts/provision-keycloak-realm.py`](scripts/provision-keycloak-realm.py): dry run by default, additive, never printing a secret. Procedure: [docs/INGEST_KEYCLOAK_SETUP.md → *New or out-of-date realm*](docs/INGEST_KEYCLOAK_SETUP.md#new-or-out-of-date-realm-run-the-provisioner).

The multi-squadron / multi-OrgUnit rollout is long complete; its current model is the living spec [`docs/specs/org-unit-tenancy.md`](docs/specs/org-unit-tenancy.md), and the historical audit trail is in [CHANGELOG.md](CHANGELOG.md).

### Running the local test stack

For UI verification in a worktree without exposing any production secret. Use an isolated `.env.test` and a stripped `realm-export.json`, driven through the `docker-compose.test.yml` override:

```bash
docker compose --env-file .env.test \
    -f docker-compose.yml -f docker-compose.test.yml --profile dev up -d
# ... verify at http://localhost:18081 ...
docker compose --env-file .env.test \
    -f docker-compose.yml -f docker-compose.test.yml --profile dev down --volumes
```

**The TLS material is not something you generate.** [`docker/test-tls/`](docker/test-tls/README.md) carries committed per-service keystores, a CA-only truststore and their CA certificate (the shape production has, REQ-SEC-070) that every test stack, every CI run and the Android dev build share, so there is nothing to create and no CA to install on an emulator. It is bound by a hardcoded path rather than through `IRI_KEYSTORE_HOST_PATH`, which still selects the *production* keystore — a test stack must not be one typo away from mounting it. Why publishing it is safe, and the one price it costs: [ADR-0139](docs/adr/0139-shared-committed-tls-material-for-the-test-stack.md).

The dev-profile Postgres and Redis services keep their data in **named volumes**, which Compose prefixes with the project name — so two worktrees do not share a database, and `down --volumes` genuinely resets the stack. (The prod services keep their `/var/iri/*` host binds, which are provisioned and backed up on the production host.) If a stack ever refuses to start with `password authentication failed`, the data directory was initialised by an earlier run with different credentials: `down --volumes` and start again, since `initdb` never re-runs on a non-empty directory.

`.gitignore` already excludes `.env.*`, `keystore.p12` and `realm-export.json`, so neither of the two artifacts below can be committed by accident. **Never substitute the production `.env`, `keystore.p12` or `realm-export.json`** — the reasoning is in the *Testing* section of [CLAUDE.md](CLAUDE.md).

**1. `.env.test`.** Every variable the base compose marks required, with throwaway values:

```bash
SERVER_SSL_KEY_STORE_PASSWORD=throwaway-test-pw
POSTGRES_DB=krt_basetool
POSTGRES_USER=basetool_test
POSTGRES_PASSWORD=throwaway-test-pw
KC_POSTGRES_DB=keycloak_test
KC_POSTGRES_USER=keycloak_test
KC_POSTGRES_PASSWORD=throwaway-test-pw
KC_BOOTSTRAP_ADMIN_USERNAME=test-admin
KC_BOOTSTRAP_ADMIN_PASSWORD=throwaway-test-pw
KEYCLOAK_ADMIN_CLIENT_SECRET=throwaway-test-secret
REDIS_PASSWORD=throwaway-test-pw
IRI_BASETOOL_VERSION=stable
```

`SERVER_SSL_KEY_STORE_PASSWORD` is still required — the base compose marks it mandatory, so interpolation fails without it — but its value is irrelevant to the test stack: `docker-compose.test.yml` pins the committed keystore's own password per service.

**2. `realm-export.json`.** Keycloak imports it on first boot (`start-dev --import-realm`). Derive it from an existing export by rewriting every secret and replacing the user list — never by copying one in unchanged:

```bash
python - <<'PY'
import json, pathlib
realm = json.loads(pathlib.Path("realm-export-source.json").read_text(encoding="utf-8"))
for client in realm.get("clients", []):
    if client.get("secret"):
        client["secret"] = "throwaway-test-secret"
realm["users"] = [{
    "username": "test-admin", "enabled": True, "emailVerified": True,
    "credentials": [{"type": "password", "value": "test-admin-pw", "temporary": False}],
}]
realm.pop("smtpServer", None)
pathlib.Path("realm-export.json").write_text(json.dumps(realm, indent=2), encoding="utf-8")
PY
```

If you only need the stack to *start* (health checks, a UI smoke test that does not log in), a minimal realm is enough — `{"realm": "iri", "enabled": true, "sslRequired": "none", "clients": [...], "users": [...]}` with the two clients `basetool-backend` and `backend-service`, the latter with `serviceAccountsEnabled: true` and its secret matching `KEYCLOAK_ADMIN_CLIENT_SECRET`.

`POSTGRES_DB` is not free-form: the backend's `application-dev.yml` names `krt_basetool`, and although the compose-supplied `SPRING_DATASOURCE_URL` environment variable overrides that, keeping the two aligned avoids a confusing "database does not exist" on first boot.

---

## Technical details

### Tech stack

- **Language / framework** — Java 25, Spring Boot 4.1
- **Build** — Gradle 9 (Kotlin DSL), versions in the `gradle/libs.versions.toml` catalog, updates surfaced by refreshVersions
- **Database** — PostgreSQL 18, schema owned by Flyway (Hibernate `ddl-auto=validate`)
- **Session store** — Redis 8 (`spring-session-data-redis`)
- **Security** — Spring Security with OAuth2 / OIDC (Keycloak 26.7)
- **Frontend** — Thymeleaf + Spring Security OAuth2 Client, WebClient wrapped with Resilience4j (Timeout, Retry, CircuitBreaker, Bulkhead)
- **Outbound HTTP in backend and ingest** — blocking `RestClient` on the JDK HTTP client; neither module carries WebFlux (ADR-0204)
- **API docs** — SpringDoc / OpenAPI; each REST-serving module ships one committed document — `backend/src/main/resources/api/openapi.json` and `ingest/src/main/resources/api/openapi.json` — as its single documentation artifact
- **DTO mapping** — MapStruct
- **Containerization** — Docker Compose for local, test and E2E stacks; rootless Podman + Quadlet (generated from the compose files) in production; images published to GHCR, Cosign-signed with SLSA provenance + SBOM attestations, and additionally attested to GitHub's attestation store so provenance survives a registry-side rewrite (`gh attestation verify`, REQ-OPS-023)

### Project structure

- **`backend`** — REST API only. Layered `controller` → `service` → `repository` → `model`, with `dto` records, MapStruct `mapper`s, `config`, `integration` (UEX), `task` (scheduled jobs), `filter`/`interceptor`.
- **`frontend`** — Thymeleaf UI. `service.BackendApiClient` is the single seam to the backend; Redis holds persistent session state.
- **`ingest`** — internet-facing one-click gateway (desktop extractor → basetool); owns no database, relays to the backend internally. Its published OpenAPI document exists so the official extractor can be built against a stable contract — the interface itself is **restricted to approved clients** (`REQ-INGEST-011`) and is not an open integration API; unapproved callers are refused `403 CLIENT_NOT_ALLOWED`.
- **`keycloak-spi`** — Keycloak provider JAR: the Discord identity provider and the guild/role login gate.
- **`keycloak-theme/krt-theme`** — custom Keycloak login + account UI theme.
- **`logging-support`** — the one `LogSafe` and PII-masking layout/encoder all three applications ship and log through (ADR-0205).
- **`test-support`** — test-only helper library shared by the backend and frontend anonymous-surface sweeps; never shipped.
- **`scripts`** — server-side operations layer (deploy, env rendering, Quadlet generation, backup and restore drill, cleanup, host metrics) plus their systemd/logrotate units, and the CI gate scripts (`check-*`) with their self-tests.
- **`quadlet`** — the generated Quadlet units (`systemd/`) and per-service env templates (`env.d/`); regenerate with `scripts/generate-quadlet.py`, never edit by hand.
- **`ansible`** — the host bootstrap role for the production and testing hosts ([`ansible/README.md`](ansible/README.md)).
- **`monitoring`** — Prometheus, Alertmanager, Loki, Tempo, Alloy and Grafana configuration ([`monitoring/README.md`](monitoring/README.md)).
- **`docs` / `config` / `docker` / `design`** — specs, ADRs & arc42, static-analysis config, the edge (nginx) and ACME configuration, the maintenance page, the one Dockerfile all three app images are built from (`docker/app/Dockerfile`), the `basetool-config` and `keycloak-spi` bundle Dockerfiles and the committed test TLS material, and brand font sources.

The frontend hand-mirrors the backend's DTOs as its own records (no shared module). Two gates watch for drift: `FrontendDtoContractTest` diffs them against `openapi.json`, and since ADR-0161 §8.2 `GeneratedDtoAgreementTest` compares them field by field against every model `openapi-generator` emits from the same document into the test source set. Nothing in `main` imports a generated type yet — replacing the mirrors is a separate epic.

### Configuration (common env vars)

| Variable                                                                                             | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                | Default                                      |
|:-----------------------------------------------------------------------------------------------------|:---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|:---------------------------------------------|
| `KEYCLOAK_ISSUER_URI`                                                                                | URL of the Keycloak realm.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 | `https://profit-base.online/auth/realms/iri` |
| `BACKEND_URL`                                                                                        | (Frontend) backend API URL; override to `https://localhost:11261` when running from Gradle (the backend always serves TLS).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 | `https://backend:11261`                      |
| `APP_HTTP_BACKEND_PROTOCOL`                                                                          | (Frontend) wire protocol to the backend: `H2` negotiates HTTP/2 by ALPN, `HTTP11` restores the pre-2026-09-10 behaviour. The SSE relay stays on HTTP/1.1 either way (ADR-0161 §8.1).                                                                                                                                                                                                                                                                                                                                                                                       | `H2`                                         |
| `APP_HTTP_MAX_CONCURRENT_STREAMS`                                                                    | (Frontend) HTTP/2 streams per backend connection before the pool opens another. 20 mirrors Tomcat's own per-connection execution limit; raising it caps backend concurrency rather than lifting it.                                                                                                                                                                                                                                                                                                                                                                        | `20`                                         |
| `APP_HTTP_CODEC`                                                                                     | (Frontend) what backend reads are asked for: `CBOR` (`application/cbor`, falling back to JSON) or `JSON`. Request bodies and RFC 7807 problems stay JSON either way (REQ-API-011).                                                                                                                                                                                                                                                                                                                                                                                         | `CBOR`                                       |
| `APP_SESSION_TYPE_ALLOW_LIST` | (Frontend) which classes a stored session value may name (REQ-SEC-067): `report` (default) reads everything as before and counts/logs a class outside the list; `enforce` refuses it, dropping that one attribute; `off` is the old permissive validator. Switch to `enforce` only after `basetool_session_type_refused_total` stayed at zero — see [`docs/deployment.md`](docs/deployment.md#session-type-allow-list-report-then-enforce). | `report` |
| `IRI_BASETOOL_VERSION`                                                                               | Image tag a Compose stack pulls. The deployer does not read it: it applies the digest behind `:stable` (production) or `:testing`, and the Quadlet units carry the `stable` default.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                | `stable`                                     |
| `IRI_KEYCLOAK_HOSTNAME`                                                                              | Public Keycloak **base URL** (`KC_HOSTNAME`) — a full URL **including the `/auth` path**, e.g. `https://basetool.example/auth`, not a bare hostname (ADR-0166: a full URL without the path makes Keycloak serve `/auth` while advertising root issuer links, and the apps then die on an issuer mismatch). Only for a non-production domain, and **the one value to set**: the issuer below and Grafana's three OIDC URLs are all derived from it (ADR-0167). The Prometheus probe targets are the exception — that file is static, so the CI gate compares them instead.  | `https://profit-base.online/auth`            |
| `IRI_KEYCLOAK_ISSUER_URI`                                                                            | Issuer the three apps validate against. **Normally leave unset** — it is derived as `${IRI_KEYCLOAK_HOSTNAME}/realms/iri`, and `scripts/check-keycloak-issuer.py` gates that (REQ-OPS-022). Set it only where the advertised issuer genuinely differs from `KC_HOSTNAME`; it then overrides, and opts that deployment out of the agreement check.                                                                                                                                                                                                                          | `https://profit-base.online/auth/realms/iri` |
| `IRI_KEYCLOAK_HOST_ALIAS`                                                                            | `name:ip` entry so the apps resolve the public issuer name to the local proxy where NAT does not hairpin. Since ADR-0166 that is the WEB host — identity has no hostname of its own. Compose only: a Quadlet host sets the alias as an `AddHost=` drop-in.                                                                                                                                                                                                                                                                                                                                                                                       | `localhost:127.0.0.1` (no-op)                |
| `IRI_AUTHORITIES_CACHE_TTL`                                                                          | How long the backend reuses a member's resolved roles and permissions before reading them from the database again (`REQ-SEC-056`, ADR-0174). Also the window in which a revoked role stays effective on an already-issued token, so lowering it tightens revocation and raising it sheds database load. ISO-8601 duration; must be positive and at most `PT15M`, or the backend refuses to start.                                                                                                                                                                          | `PT5M`                                       |
| `IRI_BACKEND_EXPECTED_AUDIENCES` | JWT `aud` the backend accepts (normally `basetool-backend`, REQ-SEC-024). **Required in production:** the `prod` profile refuses to start while it is blank (APPSEC-08); dev and test stacks leave it empty (check off). | — |
| `APP_RATE_LIMIT_SUBJECT_EXPORT_CAPACITY`, `APP_RATE_LIMIT_SUBJECT_EXPORT_REFILL_PERIOD` | Per-account budget for the export, statement, report and PDF endpoints (REQ-SEC-033 carve-out, APPSEC-10); over it the backend answers `429`. | `10`, `1m` |
| `IRI_EXTRA_JAVA_OPTS`                                                                                | Appended to `JAVA_TOOL_OPTIONS`; used to select a non-default trust store where the issuer is self-signed. Because it is appended last, it also overrides a base flag — e.g. `-XX:-UseCompactObjectHeaders` disables the object layout of ADR-0180, at the cost of the baked AOT cache (the JVM starts without it, and the Loki alert `JvmStartupCacheRejected` says so).                                                                                                                                                                                                                                                                               | *(empty)*                                    |
| `IRI_TRUSTSTORE_HOST_PATH`                                                                           | Host path mounted at `/run/secrets/truststore.p12`. Build it from the JVM anchors plus your cert.                                                                                                                                                                                                                                                                                                                                                                                                                                                                          | same file as the keystore                    |
| `IRI_KEYSTORE_HOST_PATH`                                                                             | Host path of `keystore.p12`, bind-mounted read-only into backend + frontend. The Quadlet units bake the canonical `/var/iri/secrets/keystore.p12` (as for every `*_HOST_PATH`), so this variable moves a Compose stack only.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | `./keystore.p12`                             |
| `IRI_BACKEND_KEYSTORE_HOST_PATH` / `IRI_FRONTEND_KEYSTORE_HOST_PATH` / `IRI_INGEST_KEYSTORE_HOST_PATH` / `IRI_KEYCLOAK_KEYSTORE_HOST_PATH` | Each service's own keystore, mounted at `/run/secrets/keystore.p12` (REQ-SEC-070). Baked into the Quadlet units like every `*_HOST_PATH`; the per-service rollout moves them to `/var/iri/secrets/tls/<service>.p12` ([`deployment.md` → *Internal TLS*](docs/deployment.md#internal-tls-per-service-certificates-from-a-private-ca)). | `IRI_KEYSTORE_HOST_PATH` |
| `IRI_INTERNAL_TRUSTSTORE_HOST_PATH` | Host path mounted at `/run/secrets/internal-truststore.p12`: the anchor the internal SSL bundles pin — the internal CA after the rollout. Not the REQ-OPS-022 JVM truststore above. | `IRI_KEYSTORE_HOST_PATH` |
| `INTERNAL_TLS_TRUSTSTORE` / `INTERNAL_TLS_TRUSTSTORE_PASSWORD` | Spring resource and password of that truststore, read by the `backend-trust` / `keycloak-trust` bundles of all three apps (prod profile). | `file:/run/secrets/internal-truststore.p12` / `SERVER_SSL_KEY_STORE_PASSWORD` |
| `INTERNAL_TLS_VERIFY_HOSTNAME` | `true` makes the frontend's backend client, its readiness probe and the ingest relay verify the backend's hostname on top of the pinned anchor (REQ-SEC-070). Host `.env`, rendered into `env.d`; `dev`/`test` ignore it. | `false` |
| `REDIS_PASSWORD` | Password of the Redis `default` user until the per-service rollout, and of the operator's `admin` user always; the ACL file holds its SHA-256 (REQ-SEC-068). | *(required)* |
| `REDIS_FRONTEND_USERNAME` / `_PASSWORD`, `REDIS_BACKEND_USERNAME` / `_PASSWORD`, `REDIS_INGEST_USERNAME` / `_PASSWORD` | Each service's own Redis ACL user (`basetool-frontend`, `-backend`, `-ingest`; REQ-SEC-068). A service uses its user only when its username is set; unset, it authenticates as `default` with `REDIS_PASSWORD`, as before. Rollout: [`docs/deployment.md` → *The Redis ACL*](docs/deployment.md#the-redis-acl). | *(unset)* |
| `REDIS_DEFAULT_USER` | `on` or `off`: whether the rendered ACL leaves the `default` user usable. `off` once all three services use their own users. Read by `render-redis-acl.py` only. | `on` |
| `ACME_EMAIL`                                                                                         | Contact address Let's Encrypt registers for the edge certificates. Held by the `acme` container only; the edge never sees it (ADR-0162).                                                                                                                                                                                                                                                                                                                                                                                                                                   | *(required)*                                 |
| `ACME_HOSTS`                                                                                         | Space-separated hosts `acme` obtains ONE multi-SAN certificate for; the first becomes the CN. **Empty is valid and meaningful** — an environment whose certificate is provided rather than issued (a test host behind a proxy, where HTTP-01 cannot reach anything) sets nothing, and the container idles instead of exiting.                                                                                                                                                                                                                                              | *(empty = no ACME)*                          |
| `IRI_UPSTREAM_CA_HOST_PATH`                                                                          | Host path of the public certificate the edge verifies its upstreams against — the same file Prometheus already validates the apps with.                                                                                                                                                                                                                                                                                                                                                                                                                                    | `/var/iri/monitoring/certs/basetool-ca.crt`  |
| `APP_SECURITY_PARTIAL_ROLE_SCOPE_CLIENT_IDS`                                                         | Keycloak clients whose realm-role claim is deliberately incomplete and must never rewrite an account's stored roles (REQ-SEC-036). Only needed if a deployment renames its realm clients.                                                                                                                                                                                                                                                                                                                                                                                  | `basetool-android`                           |
| `APP_ANDROID_MINIMUM_VERSION_CODE`                                                                   | Oldest Android `versionCode` still served; a build below it is walled off with „Update erforderlich". `0` means no floor — raise it only when a contract change breaks an old build (app owner runbook § 5).                                                                                                                                                                                                                                                                                                                                                               | `0`                                          |
| `APP_ANDROID_LATEST_VERSION_CODE`                                                                    | Newest published Android `versionCode`. Advisory only — it never gates. Kept apart from the floor so a routine release does not read as a forced one.                                                                                                                                                                                                                                                                                                                                                                                                                      | `0`                                          |
| `APP_LOGGING_STRUCTURED_ENABLED`                                                                     | Enables the JSON (Logstash) log appender. **Set by the Spring profile, not by the environment:** `application-prod.yml` turns it on and the base config leaves it off. Deliberately *not* in the compose allow-list — backend and `backend-dev` merge one `environment:` map, so a single forwarded default would give the dev stack the prod value. Override it by editing the profile config, not the host `.env`.                                                                                                                                                       | `false` (dev/test), `true` (prod)            |
| `APP_LOGGING_SLOW_REQUEST_THRESHOLD_MS`                                                              | Requests slower than this are logged at WARN (`Slow request …`) by all three modules.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      | `2000`                                       |
| `EDGE_HOST_FRONTEND`, `EDGE_HOST_INGEST`, `EDGE_HOST_GRAFANA`, `EDGE_HOST_API`                       | The host name each edge vhost answers to. Keycloak has none: since ADR-0166 it answers at `/auth` on `EDGE_HOST_FRONTEND`, so identity is same-origin with the app. nginx has no variables in `server_name`, and one promoted bundle serves every environment, so these are substituted into the vhost templates at container start. The certificate directory is derived from the same name (`/etc/nginx/certs/<host>/`). **No defaults:** an unset one would render `server_name ;`, and the edge refuses to start rather than serve a half-configured vhost (ADR-0162). | *(required)*                                 |
| `EDGE_GRAFANA_UPSTREAM_VERIFY` | `on` makes the edge verify Grafana's upstream certificate against Grafana's own `grafana.crt` (pinned) and the name `grafana` (REQ-OBS-008). `on` without the certificate, or any value but `on`/`off`, refuses to start. Runbook: [`deployment.md` → *The edge verifies Grafana*](docs/deployment.md#the-edge-verifies-grafana). | `off` |

The complete set — Keycloak JWKS, Discord login/precheck, SMTP, and the monitoring scrape/tracing gates — lives in `.env.example` and the specs under [`docs/specs/`](docs/specs/INDEX.md). Type-safe settings live in `@ConfigurationProperties` classes with `@Validated`, so misconfiguration is caught at startup.

### API conventions

- **Errors** — RFC 7807 Problem Details (`application/problem+json`) with `type`/`title`/`status`/`detail`/`instance`; validation errors add a field→message `errors` object.
- **Versioning** — semantic versioning via URI paths (`/api/v1/...`); deprecated endpoints emit `Deprecation`/`Sunset`/`Link` headers.
- **Pagination & sorting** — list endpoints take Spring's `Pageable` and return a `PageResponse`; sort fields are whitelisted in the service layer (never passed straight to `Sort`).
- **Time** — stored and processed as UTC `Instant`/`OffsetDateTime`; timezone conversion happens in the display layer only.

### Request correlation & logging

All three modules emit one access-log line per request — escalated to WARN past `APP_LOGGING_SLOW_REQUEST_THRESHOLD_MS` — and enrich every log line with MDC fields `correlationId` (echoed in the response header and propagated to outbound backend calls) and `userId` (the OIDC/JWT `sub`, or `anonymous`); backend and frontend additionally carry `orgUnitId` (the active org-unit pin, or `none`), which the ingest gateway has no use for. Names, emails and tokens are never logged, and a PII-masking layout scrubs JWTs, e-mail-shaped strings and token keywords as a safety net. Every client-supplied string a logger receives — search terms, filter values, relayed keys — additionally passes through `LogSafe` (one class in the shared `logging-support` module), which strips control characters and caps the length so a pasted newline cannot forge a second log line. Scheduled jobs get a per-run correlation id so a nightly sweep reads as one unit, and each module logs a startup banner naming its effective runtime configuration (secrets sanitised). In `prod` all modules additionally write structured JSON logs via `LogstashEncoder`, ready for ELK/Loki/CloudWatch.

**Levels carry meaning.** A failure is logged exactly once, at the level its status warrants: anything a client or an attacker can trigger at will sits at DEBUG (an open circuit breaker, a 401, a rate-limit rejection, a type-ahead keystroke, an SSE broken pipe), an operator-actionable fault at WARN, and a once-per-run summary at INFO. Logback's own faults are not exempt — a `statusListener` reports a self-disabled appender on `System.err`, which is shipped even when the file appender is dead ([`observability.md`](docs/specs/observability.md) REQ-OBS-017). Browser-side JavaScript errors and Content-Security-Policy violations, previously invisible server-side, are reported by a small beacon to `POST /internal/client-error` (authenticated, DEBUG-only, a bounded `{message, source, line, column, kind}` payload — never a stack trace; a CSP violation sends only the directive and the blocked origin) and counted as `basetool_client_error_total{kind}`.

Log levels are changeable **at runtime** via the Actuator `loggers` endpoint — `POST {"configuredLevel":"DEBUG"}` to `/actuator/loggers/<logger>` — so a DEBUG diagnosis costs no redeploy. The endpoint is never public (see [`observability.md`](docs/specs/observability.md) REQ-OBS-016) and changes are not persisted across a restart.

### Keycloak theme

The custom theme under `keycloak-theme/krt-theme/` has two FreeMarker families — `login/` (parent `keycloak`) and `account/` (parent `keycloak.v3`), both `de` (default) / `en`. `docker-compose.yml` bind-mounts the directory into the container, so theme-only edits need no build — just restart the Keycloak container and hard-reload the page.

---

## Star Citizen Fan Content

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/images/fankit/MadeByTheCommunity_White.png">
    <img alt="Star Citizen — Made by the Community" src="docs/images/fankit/MadeByTheCommunity_Black.png" width="150" height="150">
  </picture>
</p>

Profit Basetool is an unofficial, non-commercial fan project for the *Star Citizen*
community. It is **not affiliated with, endorsed, sponsored, or approved by** Cloud
Imperium Rights LLC, Cloud Imperium Rights Ltd., or Roberts Space Industries.

This project makes use of assets from the official
[Star Citizen Fankit](https://robertsspaceindustries.com/fankit). Those materials are
published for fan use and may only be used as explained by the terms of the **Fankit
Agreement**, the **Fan Style Guide**, and the
[Roberts Space Industries Terms of Service](https://robertsspaceindustries.com/tos) —
specifically the section on User Generated Content (UGC).

> **Star Citizen®, Roberts Space Industries® and Cloud Imperium® are registered
> trademarks of Cloud Imperium Rights LLC.**

All other Star Citizen content, artwork, names, logos and trademarks are the property of
their respective owners. © 2025 Cloud Imperium Rights LLC and Cloud Imperium Rights Ltd.

---

## License

Profit Basetool is released under the [GNU General Public License v3.0](LICENSE.md).

Every third-party library a shipped module carries must be GPL-3.0-compatible: each of `backend`, `frontend`, `ingest` and `keycloak-spi` runs [Licensee](https://github.com/cashapp/licensee) against its runtime classpath in `check`, with one allow-list in the root `build.gradle.kts` ([ADR-0197](docs/adr/0197-shipped-dependencies-pass-a-gpl-compatible-licence-gate-and-are-listed-on-a-public-page.md)). The running app lists every shipped component and its licence on the public page `/licenses` („Open-Source-Lizenzen“, linked in the footer), generated from the same reports on every build (REQ-UI-021). Non-Maven components — the Lato font, the image base layers — are kept in `frontend/oss-bundled-components.json`.
