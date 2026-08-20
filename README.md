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

- **Mission planning** — plan, brief and review squadron missions with role-aware access, a live procedure-step checklist and per-mission radio frequencies. Non-internal missions are browsable and joinable without an account.
- **Operations & payouts** — group missions under an *Operation*, track per-participant finances and confirm payouts behind an asymmetric mark/clear gate.
- **Public request surface** — unauthenticated visitors can submit material/item job orders and sign up for non-internal missions as a named guest.
- **Job orders & cross-order material demand** — a prioritised queue of material and item orders, plus a *Materialbedarf* overview that folds every open / in-progress order into what each responsible unit still has to gather per material, showing booked stock and signed-up claims side by side.
- **Hangar & inventory** — track ships and personal inventories per member, with a server-paginated org-unit fleet overview and personal hangar.
- **Lager (warehouse)** — org-scoped, append-only stock tracking: book stock in/out, transfer it (Umbuchen) — individually or for a whole marked selection at once — earmark slices to job orders and missions, and track both materials (with quality) and craftable game items.
- **Refinery & materials** — refinery job orders, material handovers and a planet-aware materials matrix; new orders can be pre-filled from a desktop-extractor screenshot JSON.
- **Materialbörse** — a central, org-wide material-exchange trade board where members both **offer** owned stock (Angebote) and post **requests** (Gesuche) for materials and craftable items; requests carry an optional minimum quality and a desired quantity, and other members signal "Ich kann liefern". Handover and location stay off-tool and private.
- **Kartellbank** — an organization bank on a double-entry, append-only ledger with accounts, a holder registry, per-account grants, tiered approval ladders and PDF statements; gated by dedicated Keycloak bank roles.
- **User & role administration** — manage members, per-Staffel capability flags and graded leadership ranks via a delegated *Leitung* page.
- **In-app notifications** — a rule-driven notification engine delivering per-user notifications to a personal inbox via polling plus a live SSE push.
- **Live multi-user sync** — every surface several people share updates in place for all viewers when a peer changes it, over one multiplexed `/ws/sync` WebSocket fanned across replicas by Redis pub/sub.
- **Activity audit logs** — an immutable, append-only activity trail across the audited areas, on one admin-only page with per-area filters and PDF/JSON export.
- **Discord login** — optional social login gated (fail-closed) on guild membership and an in-guild role, with an admin approval queue for new sign-ups (approve, reject, or link a sign-up onto an existing account).
- **Personal inventory & blueprints** — members maintain their own item list and unlocked crafting blueprints (importable from external extractors), with craftability and org-unit availability overviews.
- **Org chart & structure** — an interactive hierarchy view (OL → Bereiche → Staffeln/SKs) plus admin structure maintenance.
- **i18n & Keycloak theme** — every user-visible string is translated (German default, English); a custom Keycloak theme carries the DAS KARTELL corporate design.

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
- **Ingest** — internet-facing one-click gateway for the desktop extractor; owns no database and relays token-authenticated `POST`s to the backend over the internal network so the backend stays internet-unreachable. **Restricted interface:** only client software explicitly approved by the basetool developer (@greluc) may use it — enforced technically at the gateway (see [`docs/specs/desktop-ingest.md`](docs/specs/desktop-ingest.md)) and binding on users through section 4 of the Terms of Use (`REQ-SEC-027`, [`docs/specs/security-and-access.md`](docs/specs/security-and-access.md)).

The tenant unit is the **OrgUnit** — a Staffel (`SQUADRON`), Spezialkommando (`SPECIAL_COMMAND`), Bereich (`BEREICH`) or Organisationsleitung (`ORGANISATIONSLEITUNG`), the latter two stacked above the Staffeln/SKs. Staffel-scoped aggregates (Mission, Operation, Ship, InventoryItem, RefineryOrder) carry an `owning_org_unit_id` (nullable for deliberate *ownerless* rows). Job Orders are scoped separately via `responsible_org_unit_id` (the processing unit, governs visibility) and `requesting_org_unit_id` (the customer). See [`docs/specs/org-unit-tenancy.md`](docs/specs/org-unit-tenancy.md) for the full per-aggregate scope model.

---

## Documentation

The README is the overview; everything else lives in dedicated, versioned docs:

| Document                                                                                         | Purpose                                                                                                                                                                       |
|:-------------------------------------------------------------------------------------------------|:------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| [CHANGELOG.md](CHANGELOG.md)                                                                     | Release notes and every user-visible change.                                                                                                                                  |
| [CONTRIBUTING.md](CONTRIBUTING.md) / [CLA.md](CLA.md) / [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) | Contribution workflow and style guide, Contributor License Agreement, community standards.                                                                                    |
| [.github/SECURITY.md](.github/SECURITY.md)                                                       | Security policy, supported versions, release verification (Cosign, SLSA, SBOM).                                                                                               |
| [ROLES_AND_PERMISSIONS.md](ROLES_AND_PERMISSIONS.md)                                             | The full role and permission matrix plus the public request surface.                                                                                                          |
| [docs/specs/INDEX.md](docs/specs/INDEX.md)                                                       | Registry of the binding requirement specs (`REQ-<AREA>-NNN`) — security, tenancy, persistence, API, observability, UI and the per-feature specs.                              |
| [docs/adr/README.md](docs/adr/README.md)                                                         | Architecture Decision Records.                                                                                                                                                |
| [docs/deployment.md](docs/deployment.md)                                                         | Production deployment runbook — host bootstrap, releases, rollback, troubleshooting.                                                                                          |
| [backend/.../db/migration/README.md](backend/src/main/resources/db/migration/README.md)          | Flyway migration conventions.                                                                                                                                                 |
| [docs/e2e-test/README.md](docs/e2e-test/README.md)                                               | End-to-end test use cases, one per functional flow.                                                                                                                           |
| [CLAUDE.md](CLAUDE.md)                                                                           | Guidance for the Claude Code AI assistant — build/run/test, architectural invariants, conventions.                                                                            |
| [.claude/skills/das-kartell-design/README.md](.claude/skills/das-kartell-design/README.md)       | The DAS KARTELL design system (source of truth for colors, typography, components); a submodule of [`krt-profit/design-system`](https://github.com/krt-profit/design-system). |
| [Profit Basetool Wiki](https://github.com/krt-profit/basetool/wiki)                              | German end-user handbook, one page per feature area.                                                                                                                          |

---

## Development & testing

### Prerequisites

- [Java 25](https://adoptium.net/) — required for local Gradle builds.
- [Docker](https://www.docker.com/) and Docker Compose — for the dependency stack and the dev/test stacks.
- Access to a Keycloak server — the Docker Compose stack ships one.

The project uses **Gradle 9 with the Kotlin DSL**. Always use the wrapper (`./gradlew`); never the IDE test runner. Dependency versions live in the **version catalog** at `gradle/libs.versions.toml` — edit that, not `build.gradle.kts`. [refreshVersions](https://jmfayard.github.io/refreshVersions/) runs in catalog mode: `./gradlew refreshVersions` annotates the catalog in place with `## ⬆ = "…"` comments for each available update rather than changing any version itself. (`versions.properties` is a vestigial refreshVersions file and holds no versions; nothing reads it.)

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

Tests force `spring.profiles.active=test`. Both `Test` and `BootRun` set `--enable-native-access=ALL-UNNAMED` and a Mockito agent JVM arg.

```bash
./gradlew test                                              # all tests
./gradlew :backend:test                                     # backend only
./gradlew :frontend:test                                    # frontend only (produces a JaCoCo report)
./gradlew :backend:test --tests "FullyQualifiedClassName"   # single test class
./gradlew :backend:test --tests "ClassName.methodName"      # single test method
```

ArchUnit rules in `backend`/`frontend` `ArchitectureTest.java` enforce architectural invariants (no `SecurityContextHolder` outside the auth-helper service, every `@RestController` carries at least one `@PreAuthorize`, controllers never return JPA entities, the frontend never depends on Spring Data JPA). A violation fails `./gradlew test`.

### Linting, static analysis and SBOM

```bash
./gradlew check                                             # full sweep: Checkstyle + SpotBugs + tests + Spotless
./gradlew :backend:checkstyleMain :backend:spotbugsMain     # backend lint only
./gradlew spotlessApply                                     # auto-format sources — run before every push
./gradlew :backend:cyclonedxBom :frontend:cyclonedxBom      # SBOM on demand into <module>/docs/
```

Checkstyle runs with `maxWarnings = 0` and Spotless is wired into `check` — any unformatted file or new Checkstyle warning fails CI. The frontend additionally runs strict asset linters (`:frontend:lintCss`, `:frontend:lintJs`, `:frontend:lintHtml`) that are not covered by Spotless/Prettier; run `spotlessApply` **and** those before pushing changes under `src/main/resources/static/**` or `templates/**`.

The frontend's browser scripts are also statically type-checked by `:frontend:typecheckJs` (strict, in `check`). TypeScript runs there as a **checker only** — `tsc --noEmit`, no compilation, no bundle, no renamed files; the sources stay JavaScript and opt in per file with a leading `// @ts-check`. Backend DTO types are generated from `backend/src/main/resources/api/openapi.json` by `:frontend:generateApiTypes` on every build and are never committed, so the frontend's view of a DTO cannot drift from the published contract. See [ADR-0125](docs/adr/0125-typed-javascript-via-checkjs-not-typescript.md), REQ-FE-018, and [`docs/TYPESCRIPT_MIGRATION_PLAN.md`](docs/TYPESCRIPT_MIGRATION_PLAN.md) for the (currently unscheduled) full-TypeScript path.

### End-to-end (E2E) tests

Playwright-Java drives the real frontend through a browser. The suite lives in the `frontend` module's `e2e` source set and is **not** wired into `check` (it needs Docker and a downloaded browser).

```bash
./gradlew :frontend:e2eTest                              # full destructive flows (@Tag("e2e"))
./gradlew :frontend:smokeTest                            # non-destructive page-load checks (@Tag("smoke"))
./gradlew :frontend:e2eTest -Pe2e.browser=firefox        # engine: chromium (default), firefox, webkit
```

By default the suite builds the app images, brings up an ephemeral stack with throwaway credentials, seeds the minimal data, runs and tears down. Set `E2E_BASE_URL` to point at an already-running deployment instead. CI runs a Chromium/Firefox/WebKit matrix — see [`e2e.yml`](.github/workflows/e2e.yml) and the per-flow use cases under [`docs/e2e-test/`](docs/e2e-test/README.md).

---

## Deployment

> [!IMPORTANT]
> **Never ship placeholder credentials into production.** Generate strong values
> (`openssl rand -base64 32`) for every secret and rotate them before the first
> deployment — the Keycloak bootstrap admin in particular is the realm-master account.

Both deployment paths read a `.env` file at the repository root; copy `.env.example` and replace every `CHANGE_ME`. Compose uses `${VAR:?...}` references, so a missing required variable refuses to start the stack. The essential keys:

```env
POSTGRES_USER / POSTGRES_PASSWORD            # backend DB
KC_POSTGRES_USER / KC_POSTGRES_PASSWORD      # Keycloak DB
KC_BOOTSTRAP_ADMIN_USERNAME / _PASSWORD      # Keycloak realm-master admin
KEYCLOAK_ADMIN_CLIENT_SECRET                 # backend → Keycloak admin API
SERVER_SSL_KEY_STORE_PASSWORD                # PKCS12 keystore password
IRI_KEYSTORE_HOST_PATH                       # host path of keystore.p12 (bind-mounted read-only)
REDIS_PASSWORD                               # Redis session store
HOST_IP                                      # deployment host IP for outbound binding
```

Optional channels ship as safe no-ops until configured: transactional e-mail (SMTP — account approval/rejection + new-registration notices), the Discord social login, and the internet-facing **ingest** gateway (needs its own NPM proxy host). Their env keys are documented in `.env.example`, [docs/keycloak/DISCORD_KEYCLOAK_SETUP.md](docs/keycloak/DISCORD_KEYCLOAK_SETUP.md) and [docs/INGEST_KEYCLOAK_SETUP.md](docs/INGEST_KEYCLOAK_SETUP.md).

**Production release loop.** Hosts do not build images. A GitHub Actions workflow builds, scans, signs (Cosign) and pushes the backend/frontend/ingest images plus a `basetool-config` bundle to GHCR; a `promote` workflow re-tags a digest as `:stable`; the host polls `:stable` every five minutes and applies new digests with health-check-gated rollback. During the switchover, nginx-proxy-manager serves a branded maintenance page. The full runbook — including manual rollback, backups & disaster recovery, and the admin-only monitoring stack — is in [**docs/deployment.md**](docs/deployment.md), with binding requirements under [`docs/specs/`](docs/specs/INDEX.md).

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

**The TLS material is not something you generate.** [`docker/test-tls/`](docker/test-tls/README.md) carries a committed keystore that every test stack, every CI run and the Android dev build share, so there is nothing to create and no CA to install on an emulator. It is bound by a hardcoded path rather than through `IRI_KEYSTORE_HOST_PATH`, which still selects the *production* keystore — a test stack must not be one typo away from mounting it. Why publishing it is safe, and the one price it costs: [ADR-0139](docs/adr/0139-shared-committed-tls-material-for-the-test-stack.md).

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
- **Session store** — Redis (`spring-session-data-redis`)
- **Security** — Spring Security with OAuth2 / OIDC (Keycloak 26.7)
- **Frontend** — Thymeleaf + Spring Security OAuth2 Client, WebClient wrapped with Resilience4j (Timeout, Retry, CircuitBreaker, Bulkhead)
- **API docs** — SpringDoc / OpenAPI; each REST-serving module ships one committed document — `backend/src/main/resources/api/openapi.json` and `ingest/src/main/resources/api/openapi.json` — as its single documentation artifact
- **DTO mapping** — MapStruct
- **Containerization** — Docker / Docker Compose; images published to GHCR, Cosign-signed with SLSA provenance + SBOM attestations

### Project structure

- **`backend`** — REST API only. Layered `controller` → `service` → `repository` → `model`, with `dto` records, MapStruct `mapper`s, `config`, `integration` (UEX), `task` (scheduled jobs), `filter`/`interceptor`.
- **`frontend`** — Thymeleaf UI. `service.BackendApiClient` is the single seam to the backend; Redis holds persistent session state.
- **`ingest`** — internet-facing one-click gateway (desktop extractor → basetool); owns no database, relays to the backend internally. Its published OpenAPI document exists so the official extractor can be built against a stable contract — the interface itself is **restricted to approved clients** (`REQ-INGEST-011`) and is not an open integration API; unapproved callers are refused `403 CLIENT_NOT_ALLOWED`.
- **`keycloak-spi`** — Keycloak provider JAR: the Discord identity provider and the guild/role login gate.
- **`keycloak-theme/krt-theme`** — custom Keycloak login + account UI theme.
- **`scripts`** — server-side operations layer (deploy, cleanup, migration guard) plus their systemd/logrotate units.
- **`docs` / `config` / `docker` / `design`** — specs & ADRs, static-analysis config, the maintenance page, and brand font sources.

The frontend hand-mirrors the backend's DTOs as its own records (no shared module); `FrontendDtoContractTest` is the drift gate, diffing them against `openapi.json`.

### Configuration (common env vars)

| Variable                                | Description                                                                                                      | Default                                          |
|:----------------------------------------|:-----------------------------------------------------------------------------------------------------------------|:-------------------------------------------------|
| `KEYCLOAK_ISSUER_URI`                   | URL of the Keycloak realm.                                                                                       | `https://keycloak.profit-base.online/realms/iri` |
| `BACKEND_URL`                           | (Frontend) backend API URL; override to `http://localhost:11261` when running from Gradle.                       | `https://backend:11261`                          |
| `IRI_BASETOOL_VERSION`                  | Image tag pulled by the compose stack (`testing` on a non-production host).                                      | `stable`                                         |
| `IRI_KEYCLOAK_HOSTNAME`                 | Public Keycloak hostname (`KC_HOSTNAME`). Only for a non-production domain; set with the next var or not at all. | `keycloak.profit-base.online`                    |
| `IRI_KEYCLOAK_ISSUER_URI`               | Issuer the three apps validate against. Must match `IRI_KEYCLOAK_HOSTNAME` (REQ-OPS-022).                        | `https://keycloak.profit-base.online/realms/iri` |
| `IRI_KEYSTORE_HOST_PATH`                | Host path of `keystore.p12`, bind-mounted read-only into backend + frontend.                                     | `./keystore.p12`                                 |
| `REDIS_PASSWORD`                        | Password for the Redis session store.                                                                            | *(required)*                                     |
| `HOST_IP`                               | Deployment host IP the compose stack binds outbound services to.                                                 | *(required)*                                     |
| `APP_LOGGING_STRUCTURED_ENABLED`        | Enables the JSON (Logstash) log appender.                                                                        | `false` (dev/test), `true` (prod)                |
| `APP_LOGGING_SLOW_REQUEST_THRESHOLD_MS` | Requests slower than this are logged at WARN (`Slow request …`) by all three modules.                            | `2000`                                           |

The complete set — Keycloak JWKS, Discord login/precheck, SMTP, and the monitoring scrape/tracing gates — lives in `.env.example` and the specs under [`docs/specs/`](docs/specs/INDEX.md). Type-safe settings live in `@ConfigurationProperties` classes with `@Validated`, so misconfiguration is caught at startup.

### API conventions

- **Errors** — RFC 7807 Problem Details (`application/problem+json`) with `type`/`title`/`status`/`detail`/`instance`; validation errors add a field→message `errors` object.
- **Versioning** — semantic versioning via URI paths (`/api/v1/...`); deprecated endpoints emit `Deprecation`/`Sunset`/`Link` headers.
- **Pagination & sorting** — list endpoints take Spring's `Pageable` and return a `PageResponse`; sort fields are whitelisted in the service layer (never passed straight to `Sort`).
- **Time** — stored and processed as UTC `Instant`/`OffsetDateTime`; timezone conversion happens in the display layer only.

### Request correlation & logging

All three modules emit one access-log line per request — escalated to WARN past `APP_LOGGING_SLOW_REQUEST_THRESHOLD_MS` — and enrich every log line with MDC fields `correlationId` (echoed in the response header and propagated to outbound backend calls) and `userId` (the OIDC/JWT `sub`, or `anonymous`); backend and frontend additionally carry `orgUnitId` (the active org-unit pin, or `none`), which the ingest gateway has no use for. Names, emails and tokens are never logged, and a PII-masking layout scrubs JWTs, e-mail-shaped strings and token keywords as a safety net. Every client-supplied string a logger receives — search terms, filter values, relayed keys — additionally passes through the module's `LogSafe`, which strips control characters and caps the length so a pasted newline cannot forge a second log line. Scheduled jobs get a per-run correlation id so a nightly sweep reads as one unit, and each module logs a startup banner naming its effective runtime configuration (secrets sanitised). In `prod` all modules additionally write structured JSON logs via `LogstashEncoder`, ready for ELK/Loki/CloudWatch.

**Levels carry meaning.** A failure is logged exactly once, at the level its status warrants: anything a client or an attacker can trigger at will sits at DEBUG (an open circuit breaker, a 401, a rate-limit rejection, a type-ahead keystroke, an SSE broken pipe), an operator-actionable fault at WARN, and a once-per-run summary at INFO. Logback's own faults are not exempt — a `statusListener` reports a self-disabled appender on `System.err`, which is shipped even when the file appender is dead ([`observability.md`](docs/specs/observability.md) REQ-OBS-017). Browser-side JavaScript errors, previously invisible server-side, are reported by a small beacon to `POST /internal/client-error` (authenticated, DEBUG-only, a bounded `{message, source, line, column, kind}` payload — never a stack trace) and counted as `basetool_client_error_total{kind}`.

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
