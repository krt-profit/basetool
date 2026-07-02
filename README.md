# Profit Basetool

The Profit Basetool is the squadron-management web app for the
"[DAS KARTELL](https://das-kartell.org/dashboard/)" organization in *Star Citizen*. It provides a central
platform for mission planning, hangar and inventory tracking, refinery and
material logistics, terminal data, and member administration — backed by
single-sign-on via Keycloak and a clear role and permission model.

---

## 1. Overview

### What the application provides

- **Mission planning** — plan, brief and review squadron missions with role-aware access (`MISSION_MANAGER`, `OFFICER`, `ADMIN`), including a sortable *Ablauf* (procedure-step checklist) whose shared done-state and current phase are tracked live during a running mission, and per-mission radio frequencies (the shared *Frequenztypen* plus custom, mission-specific channels). Non-internal missions are browsable (and joinable) without an account. The owning org unit a mission is assigned to can be changed after creation from the Verwaltung tab (or set to *ownerless*), re-scoping its visibility (ADR-0050). A mission detail page updates live for every viewer over the presence WebSocket, so a peer's change (join, crew move, finance entry, schedule/status/party-lead/frequency/Ablauf/owning-org-unit edit) propagates without a manual reload.
- **Operations & payouts** — group missions under an *Operation*, track per-participant finances and payouts, and confirm pay-outs with an asymmetric mark/clear gate (`MISSION_MANAGER` sets, `OFFICER`/`ADMIN` clears). Members can set a default payout preference (pay out / donate) in their profile that pre-selects on every mission signup. Leadership members without an org unit can create org-wide *ownerless* operations, and every participant of a linked mission can see the operation and their payout regardless of squadron.
- **Public request surface** — unauthenticated visitors can submit material/item job orders (auto-routed to the intake Spezialkommando) and sign up for non-internal missions as a named guest — including changing their payout preference — without logging in. See [ROLES_AND_PERMISSIONS.md](ROLES_AND_PERMISSIONS.md) §1.
- **Hangar & inventory** — track ships and personal inventories per member, including UEX City and Space Station locations. The unit hangar overview (`/hangar/squadron`, titled *Org-Einheitsübersicht*) aggregates the scoped fleet one row per ship type and — with no org unit pinned — spans every org unit the caller can see: a member's own Staffeln and SKs, a Bereichsleitung's subordinate units, and for the Organisationsleitung every ship in the system including the ownerless ships of members in no unit (ADR-0048); both the unit overview and the personal hangar (*Meine Schiffe*) paginate, sort and filter server-side.
- **Refinery & materials** — manage refinery job orders, material handovers and the materials matrix (`/materials/overview`) with planet-aware grouping. A new refinery order can be pre-filled from a screenshot-extract JSON produced by the desktop extractor (`POST /api/v1/refinery-orders/import-extract` matches materials, location and method against the master data and returns an unsaved draft with review hints).
- **Kartellbank** — an organization bank on a double-entry, append-only ledger: accounts, a holder registry and per-account grants, gated by two dedicated Keycloak roles (`Bank Employee` / `Bank Management`). Holder balances are bank-wide and decoupled from accounts — they may go negative (a custodian fronts his own aUEC) and are reconciled by holder-to-holder Umbuchungen (ADR-0039); the in-game transfer fee (default 0.5%, shared with operation payouts) is added on top and borne by the debited account on payouts and holder-changing transfers, so the full requested amount arrives and a booking that cannot cover the amount plus its fee is refused (ADR-0052, superseding ADR-0041). Bank employees can reach Bankverwaltung to create Sonderkonten and are auto-registered as holders (ADR-0040). On the org-unit bank page (`/org-unit-bank`) every account has a derived responsible holder who configures balance visibility and an optional balance target; anyone who may view a request-capable account gets a read-only drill-in split into two tabs — booking history + Halter-redacted statement, and the responsible holder's target/visibility/limit settings shown only to that holder (REQ-BANK-034..038). Any authenticated user may request a **deposit** against **any** active account with no approval limit (REQ-BANK-042); withdrawal/transfer requests stay gated by view eligibility and by per-account, per-tier approval limits with two-step owner approval — a request without an applicable limit (none set for the user, their role or all members) always needs the responsible holder's approval, a configured limit only above its ceiling (REQ-BANK-039/040/041). A bank employee confirms every request before money moves, and the account's responsible holder is notified when a request on their account is created or decided (REQ-BANK-026). A deposit may optionally fan a percentage of its gross evenly across all active squadron accounts (REQ-BANK-043). Deposits and withdrawals optionally record the **counterparty** — the depositor (Einzahler) / payout recipient (Empfänger) and the org unit they belong to, chosen from the tool users and their memberships — shown in the booking history, the Kontoauszug *Gegenseite* column and the admin audit log, and redacted on member views like the holder (REQ-BANK-044). A withdrawal or transfer additionally carries an optional **Begründung** (justification), required for outflows from the cartel-wide accounts — the KRT account, the bank's own account and Sonderkonten — and optional for Staffel/SK/Bereich accounts, shown like the note in the booking history and both PDFs (REQ-BANK-045). The bank dashboard (`/bank`) and the org-unit account list (`/org-unit-bank`) each carry a client-side account-name filter that hides non-matching accounts live as you type, no reload (REQ-BANK-046). The **KRT account** routes withdrawals/transfers through an **amount-tiered 3-stage approval ladder** — a bank employee up to a first threshold, then the Bereichsleiter Profit, then the Organisationsleitung — with the two thresholds set **only by the Bankleitung** in a dedicated „KRT-Freigaben" Verwaltung tab and the non-staff approvals band-routed in the org-unit bank (REQ-BANK-047). A Bereichskonto can additionally open its balance/limit to the **whole area cascade** ("Mitglieder des Bereichs"), and the "Alle Mitglieder" limit tier now applies only to actual members of the account's own org unit (REQ-BANK-048). Account statements ship as PDF. The bank itself stays org-unit-blind (epics #556 / #666).
- **Terminals** — administer trade terminals, including UEX raw state (loading dock, auto-load) and the last UEX sync timestamp (`/admin/terminals`).
- **User administration** — manage members and roles; the `LOGISTICIAN` / `MISSION_MANAGER` capability flags are set per Staffel on the member-edit page (each of a member's up to two Staffeln carries its own flags), no longer as flat member-list toggles. Graded leadership ranks (Staffelleiter / Kommandoleiter / stellv. / Ensign, plus the Bereichs- and OL ranks) are a single membership rank enum appointed via the delegated *Leitung* page rather than admin-only (REQ-ROLE-001 / -004, ADR-0042).
- **In-app notifications** — a data-driven notification system: an admin-managed rule engine (`/admin/notification-rules`) raises per-user notifications (e.g. bank booking requests, new Discord registrations) into a personal inbox (`/notifications`) delivered via polling plus a live SSE push, with automatic 90-day cleanup (epic #622).
- **Activity audit logs** — an immutable, append-only activity trail across nine areas (Bank, Lager, Aufträge, Raffinerie, Mein Inventar, Missionen, Operationen, Rollen & Mitglieder, Beförderung), read on one ADMIN-only page (`/admin/audit-log`) with a per-area tab switcher and event-type/period/actor filters. Each log exports as PDF or JSON for a chosen period and supports an explicit, itself-logged retention purge of entries older than a cutoff (REQ-AUDIT-001/004, ADR-0037/0038, epics #795/#800).
- **Discord login** — members can additionally sign in with Discord. Login is gated, fail-closed, on "DAS KARTELL" guild membership and the in-guild `KRT-Mitglied` role; a brand-new Discord sign-up lands in a pending state with no access until an admin approves it under `/admin/discord-registrations`, where the member's das-kartell server nickname is shown to anchor the decision (epic #720). The admin member-management page also shows, per account, whether it is Discord-linked (an admin-only indicator, never the raw Discord id), recognised from the Keycloak federated-identity link so it lights up however and whenever the account was linked — registered via Discord or an existing credential account linked later — and on any login method ([ADR-0036](docs/adr/0036-discord-link-recognised-from-federated-identity.md)). To avoid duplicate accounts, a brand-new Discord first-login whose Discord username, server nickname, or e-mail already matches an existing account is denied with a localized hint to link Discord to that existing account instead of registering anew; the check is fail-open and HTTPS-only (REQ-SEC-022, [ADR-0051](docs/adr/0051-discord-first-login-account-existence-precheck.md)).
- **Personal inventory** — every authenticated member maintains their own item list at `/personal-inventory`; admins manage other members' inventories at `/admin/personal-inventory`. Backend endpoints under `/api/v1/personal-inventory` (user) and `/api/v1/admin/personal-inventory` (admin) are paginated, validated, and protected by optimistic locking.
- **Personal blueprints** — the personal-inventory area splits into *Items* and *Blueprints* sub-pages. On `/personal-inventory/blueprints` a member records the crafting blueprints they have unlocked in-game, added via a multi-select type-ahead over the SC Wiki product catalogue or by importing a JSON export from the **SCMDB log-watcher**, the **[Basetool Blueprint Extractor](https://github.com/krt-profit/basetool-bp-extractor)**, or the **[scmdb.net](https://scmdb.net) profile/tracking export** (the watcher/extractor exports carry a `blueprints` array of identically-named entries and the importer reads the acquisition time from either `ts` or `receivedAt`; the scmdb.net export only imports unlocked blueprints and additionally carries a structural blueprint key (`tag`) that resolves a product even when its displayed name differs). The import previews each blueprint name, resolving it by normalized-exact match, a curated alias, or dependency-free fuzzy suggestions; the user resolves the rest manually and each manual pick is learned as an alias for future imports. Admins manage any member's blueprints at `/admin/personal-blueprints`. Backend endpoints under `/api/v1/personal-blueprints` (user) and `/api/v1/admin/personal-blueprints` (admin), plus the slim product search at `/api/v1/blueprints/products/search`. The page also annotates each owned blueprint with its **craftability from the member's own "Mein Lager" stock** (`GET /api/v1/personal-blueprints/craftability`): how often it can be crafted right now, the limiting material and per-material shortfall, and the output stats the stock's quality would deliver; a default-off toggle folds in the yield of the member's open/in-progress refinery orders. The calculation is strictly owner-scoped and counts both RESOURCE ingredients and the hand-mined PIECE-material ITEM ingredients (e.g. Hadanite, Beradom), with craftable sub-assemblies left "not evaluated".
- **Blueprint availability overview** — officers, admins and Spezialkommando leads can see which blueprints are available among the members of their org unit at `/blueprint-overview`, with a lazy per-blueprint drill-down to the owning members (display name only). Officers see their squadron, SK leads their SK, admins all org units (or a pinned one); the sidebar entry is hidden from everyone else. Backend endpoints `GET /api/v1/personal-blueprints/overview` (+ `/owners`) gate on the same check as the menu (`GET /api/v1/me/capabilities`). Item-order detail pages additionally show a *blueprint coverage* section — which members of the order's responsible squadron/SK own the blueprints for the requested items — visible only to members of that responsible unit (and admins). A per-order toggle (default on, editable by logisticians with edit scope) controls whether coverage counts cosmetic variants of an ordered item or matches the exact blueprint only; the choice is persisted on the order and applies for every viewer.
- **Org chart & structure** — an interactive, keyboard-accessible organization chart (`/org-chart`) of the full hierarchy: Organisationsleitung (OL) → Bereiche (areas) → Staffeln and Spezialkommandos; readable by every authenticated member. Admins maintain the structure itself (create areas / OL, wire up parents) under `/admin/org-structure`. Account-linked leadership seats are no longer set in the chart: leadership ranks are appointed down a delegated ladder on the *Leitung* page (Organisation → Leitung), and the chart only mirrors those account-linked seats read-only (REQ-ROLE-004 / -006, ADR-0042); free-text holders without an account stay editable in the chart. Area and OL leaders get officer-equivalent reach over their subordinate units, without any admin rights (epic #692).
- **i18n** — every user-visible string is fully translated (German default, English).
- **Custom Keycloak theme** — login and account console in the DAS KARTELL (KRT) corporate design.

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

- **Backend** — REST API only (`/api/v1/...`), Spring Boot 4 on Java 25, JPA / Flyway / PostgreSQL.
- **Frontend** — Thymeleaf-rendered UI calling the backend via a centrally-configured WebClient (Resilience4j wrapped). No direct database or Keycloak Admin API access.
- **Keycloak** — OAuth2 / OIDC identity provider, custom KRT theme, with the `keycloak-spi` provider JAR (Discord social login + the guild / `KRT-Mitglied`-role login gate).
- **Redis** — Spring Session store; sessions survive frontend restarts.
- **Ingest** — internet-facing one-click gateway for the desktop extractor (refinery / blueprint JSON → basetool); owns no database, terminates a token-authenticated `POST` and relays it to the backend over the internal network so the backend stays internet-unreachable ([ADR-0018](docs/adr/0018-desktop-ingest-gateway-device-grant.md)).

The tenant unit is the **OrgUnit** — a `SQUADRON` (Staffel), a `SPECIAL_COMMAND` (SK), a `BEREICH` (area) or an `ORGANISATIONSLEITUNG` (OL); the latter two are the org-hierarchy layers stacked above the Staffeln/SKs (epic #692). A user belongs to up to two Staffeln and to any number of SKs; the staffel-scoped aggregates (Mission, Operation, Ship, InventoryItem, RefineryOrder) carry an `owning_org_unit_id` FK that resolves to either kind (nullable for deliberate *ownerless* rows: personal aggregates of members without an org unit, and leadership missions/operations). **Job Orders are scoped differently** (see the Job-Order rework, parent issue #340): they carry a `responsible_org_unit_id` (the *processing* unit — a profit-eligible Squadron or SK, governs visibility) and a `requesting_org_unit_id` (the customer), and are conditionally scoped — an SK-responsible order is public to all squadrons (a shared queue that squadrons sign up for partial material *claims* against), a squadron-responsible order is private to that squadron + admins. The promotion subsystem is permanently restricted to Squadron-owned topics by DB CHECK + trigger + ArchUnit rule, and is itself per-squadron: every read is filtered to the caller's active squadron, so a member or officer sees only their own squadron's system, an admin sees the pinned squadron's (all-squadrons mode shows a "pick a squadron" prompt rather than a cross-staffel merge), and a non-admin without any squadron sees no promotion system at all (menu hidden, list reads empty, direct page access 403). See [`docs/specs/org-unit-tenancy.md`](docs/specs/org-unit-tenancy.md) for the full per-aggregate scope model.

---

## 2. Project Documentation

The README focuses on getting the project up and running. The following
documents cover everything else:

| Document                                                                                               | Purpose                                                                                                                                                                                                                                           |
|:-------------------------------------------------------------------------------------------------------|:--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| [CHANGELOG.md](CHANGELOG.md)                                                                           | Release notes and every user-visible change.                                                                                                                                                                                                      |
| [CONTRIBUTING.md](CONTRIBUTING.md)                                                                     | How to report bugs, suggest features and submit pull requests, plus the coding style guide.                                                                                                                                                       |
| [CLA.md](CLA.md)                                                                                       | Individual Contributor License Agreement every contributor signs before their first pull request; the public roster of signatures lives in [docs/cla-signatures.md](docs/cla-signatures.md).                                                      |
| [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)                                                               | Community standards (Contributor Covenant 3.0).                                                                                                                                                                                                   |
| [.github/SECURITY.md](.github/SECURITY.md)                                                             | Security policy — how to report a vulnerability via GitHub Private Vulnerability Reporting, supported versions, scope, safe harbor, release verification (Cosign, SLSA, SBOM).                                                                    |
| [LICENSE.md](LICENSE.md)                                                                               | GNU General Public License v3.0.                                                                                                                                                                                                                  |
| [ROLES_AND_PERMISSIONS.md](ROLES_AND_PERMISSIONS.md)                                                   | Full role and permission matrix (`ADMIN`, `OFFICER`, `LOGISTICIAN`, `MISSION_MANAGER`, `KRT_MEMBER`, `GUEST`, plus the per-SK `Lead` role) and the anonymous / unauthenticated public request surface.                                            |
| [docs/specs/INDEX.md](docs/specs/INDEX.md)                                                             | Registry of the canonical, binding requirement specs (`REQ-<AREA>-NNN`) — security & access, org-unit tenancy, data persistence, API conventions, observability, UI design system and the per-feature specs.                                      |
| [docs/adr/README.md](docs/adr/README.md)                                                               | Architecture Decision Records — every architecturally significant decision, recorded before or with the change that implements it.                                                                                                                |
| [.claude/skills/das-kartell-design/README.md](.claude/skills/das-kartell-design/README.md)             | "DAS KARTELL" design system / Corporate Design Manual — the source of truth for brand colors, typography, the department palette and UI components. A git submodule of [`krt-profit/design-system`](https://github.com/krt-profit/design-system). |
| [docs/deployment.md](docs/deployment.md)                                                               | Production deployment runbook — host bootstrap, normal releases, manual rollback, PAT rotation, troubleshooting.                                                                                                                                  |
| [backend/src/main/resources/db/migration/README.md](backend/src/main/resources/db/migration/README.md) | Flyway migration conventions — destructive-ops two-phase rule, data-migration patterns, performance / locking, pre-merge checklist.                                                                                                               |
| [docs/e2e-test/README.md](docs/e2e-test/README.md)                                                     | End-to-end test use cases — one document per functional flow (actor, preconditions, steps, expected result) linking the Playwright test classes, plus the [role/scope reference](docs/e2e-test/rollen-und-scope.md).                              |
| [CLAUDE.md](CLAUDE.md)                                                                                 | Project-specific guidance for the Claude Code AI assistant — build / run / test commands, architectural invariants, conventions.                                                                                                                  |
| [.github/PULL_REQUEST_TEMPLATE.md](.github/PULL_REQUEST_TEMPLATE.md)                                   | The pull-request template that ships with every PR.                                                                                                                                                                                               |
| [Profit Basetool Wiki](https://github.com/krt-profit/basetool/wiki)                                    | German end-user handbook — one page per feature area (Einsätze, Operationen, Kartellbank, Hangar, Beförderung, Organisation, Leitung, …) for members, officers, bank staff and admins.                                                            |
| [docs/keycloak/DISCORD_KEYCLOAK_SETUP.md](docs/keycloak/DISCORD_KEYCLOAK_SETUP.md)                     | Keycloak setup for the optional Discord social login and the per-guild nickname capture (`DISCORD_GUILD_ID` + identity-provider mappers).                                                                                                         |
| [docs/INGEST_KEYCLOAK_SETUP.md](docs/INGEST_KEYCLOAK_SETUP.md)                                         | Keycloak audience setup for the ingest gateway's device-grant token validation (`IRI_INGEST_EXPECTED_AUDIENCES`).                                                                                                                                 |

---

## 3. Deployment

> [!IMPORTANT]
> **Never ship the placeholder credentials shown below into production.**
> Generate strong values (`openssl rand -base64 32`) for every secret and
> rotate them before the first deployment. The Keycloak bootstrap admin in
> particular is the realm-master account; an `admin` / `admin` setup makes
> the entire identity provider trivially compromisable.

### 3.1 Environment file

Both deployment paths read a `.env` file at the repository root. Copy
`.env.example` and replace every `CHANGE_ME`:

```env
# Backend Database configuration
POSTGRES_DB=krt_basetool
POSTGRES_USER=CHANGE_ME
POSTGRES_PASSWORD=CHANGE_ME

# Keycloak Database configuration
KC_POSTGRES_DB=keycloak
KC_POSTGRES_USER=CHANGE_ME
KC_POSTGRES_PASSWORD=CHANGE_ME

# Keycloak Initial Admin User
KC_BOOTSTRAP_ADMIN_USERNAME=CHANGE_ME
KC_BOOTSTRAP_ADMIN_PASSWORD=CHANGE_ME

# Keycloak admin-API client secret (used by backend to sync users).
# Get / rotate this in the Keycloak admin console:
#   Realm "iri" -> Clients -> backend-service -> Credentials -> Regenerate.
KEYCLOAK_ADMIN_CLIENT_SECRET=CHANGE_ME

# PKCS12 keystore password for backend + frontend Spring SSL.
SERVER_SSL_KEY_STORE_PASSWORD=CHANGE_ME

# Absolute host path of the production keystore.p12. Bind-mounted read-only
# into backend + frontend at /run/secrets/keystore.p12. The keystore is
# NEVER baked into the GHCR image (see CLAUDE.md and .dockerignore).
IRI_KEYSTORE_HOST_PATH=/var/iri/secrets/keystore.p12

REDIS_PASSWORD=CHANGE_ME

# Host IP for outbound binding (set to the deployment host's IP).
HOST_IP=CHANGE_ME
```

Compose uses `${VAR:?...}` references throughout — if any required variable
is missing, the stack refuses to start.

#### Transactional e-mail (account approval/rejection notices)

The account approval/rejection e-mail (REQ-NOTIF-013/-014,
[ADR-0064](docs/adr/0064-transactional-email-delivery-channel.md)) **ships enabled**, but sends
nothing until you point the backend at an SMTP host — with no host it is a safe no-op, so the app is
fully functional without SMTP. The curated Docker Compose env already forwards the variables below;
set them in `.env` to start sending. Example for **Gmail** (needs 2-Step Verification + a 16-character
[App Password](https://support.google.com/accounts/answer/185833) — a normal account password will
not work):

```env
SPRING_MAIL_HOST=smtp.gmail.com
SPRING_MAIL_PORT=587
SPRING_MAIL_USERNAME=you@gmail.com
SPRING_MAIL_PASSWORD=<16-char Google App Password>   # NOT your normal password
APP_MAIL_FROM_ADDRESS=you@gmail.com                  # Gmail rewrites From to the auth'd user
APP_MAIL_FROM_NAME=Profit Basetool
```

STARTTLS + SMTP auth are already set in `application.yml`. Restart the backend after editing `.env`
(`docker compose up -d backend`). With `SPRING_MAIL_HOST` unset (or `APP_MAIL_ENABLED=false`) no mail
is sent and the approval flow is otherwise unchanged.

#### Ingest gateway (epic #639 — desktop one-click send)

The `ingest` service is the only **new** internet-reachable component: it lets the
desktop extractor push its JSON straight into the basetool (design: [ADR-0018](docs/adr/0018-desktop-ingest-gateway-device-grant.md),
[`docs/specs/desktop-ingest.md`](docs/specs/desktop-ingest.md)). It serves HTTPS on
port `11262` (NPM terminates the public TLS and re-encrypts to it), owns no database, and relays to
the backend over the internal network — **the backend stays internet-unreachable**.

To expose it, add a new **NPM proxy host** (alongside the existing frontend/keycloak hosts):

- Domain: `ingest.<your-domain>` (e.g. `ingest.profit-base.online`), with a Let's Encrypt cert.
- Forward: scheme `https`, host `ingest`, port `11262`. Enable the host's SSL options so NPM does
  **not** verify the upstream certificate — the gateway presents the shared self-signed cert.
- Set `client_max_body_size 2m` for this host to match the gateway's payload cap; a real
  extract is a few KB.

Optional `.env` keys (defaults shown, see `.env.example`): `IRI_FRONTEND_PUBLIC_URL` (the public
frontend URL the gateway returns to the extractor) and `IRI_INGEST_EXPECTED_AUDIENCES` (leave
empty until the Keycloak audience runbook [`docs/INGEST_KEYCLOAK_SETUP.md`](docs/INGEST_KEYCLOAK_SETUP.md)
is applied). Verify from outside afterwards that `https://ingest.<domain>/v1/...` requires a token
and that the backend's `/api/v1/**` remains unreachable.

### 3.2 Production deployment (GHCR pull + systemd timer)

Production hosts do **not** build images locally. The
[release-images](.github/workflows/release-images.yml) GitHub Actions
workflow builds, scans (Trivy), signs (Cosign keyless / Sigstore) and
pushes the backend, frontend and ingest images — plus the `basetool-config`
host-configuration bundle (the compose file + maintenance page + Keycloak
theme) — to GHCR on every push to `main` and every `v*.*.*` tag. The
[promote](.github/workflows/promote.yml) workflow re-tags an existing
digest as `:stable` (app images **and** config, in lock-step) when an
operator decides it should go live. The production host polls `:stable`
every five minutes via `iri-deploy.timer` and applies any new digest with
health-check-gated rollback — so an infra-image bump in the compose file
(e.g. redis) ships automatically once promoted, with no manual file copy.
A postgres/Keycloak image change is the one operator-gated exception. See
[ADR-0049](docs/adr/0049-config-as-promotable-oci-artifact.md) and
[REQ-OPS-*](docs/specs/deployment-delivery.md).

The end-to-end runbook lives in [**docs/deployment.md**](docs/deployment.md).
Summary of the release loop:

1. **Build + push**: `git tag -a v1.4.3 -m "..." && git push origin v1.4.3`
   → fires `release-images.yml` → images appear in GHCR as `:1.4.3`,
   `:1.4`, `:1`, `:latest`. Nothing is deployed yet.
2. **Promote**: `gh workflow run promote.yml -f version=1.4.3` → flips
   `:stable` to the same digest. Still nothing is deployed; the server
   polls the change within five minutes.
3. **Pull + apply**: `iri-deploy.timer` fires → `scripts/deploy.sh`
   resolves the new `:stable` digests (app images + config bundle), pins
   the images in a compose override, swaps in the promoted compose file
   if its digest moved, runs `docker compose pull && docker compose up -d
   --wait` with a 180 s health-check, and auto-rolls-back both the digest
   pin and the config tree if the new stack fails to become healthy.
   When nothing new was promoted, the tick still verifies that the
   running containers match the pinned digests and are healthy, and
   re-applies on drift — a stack started off a stale local `:stable`
   tag, crash-looping or left half-down self-heals within one tick
   (REQ-OPS-013).

During the brief window in step 3 between "old container gone" and "new
container healthy", `nginx-proxy-manager` intercepts the upstream `502` and
serves a branded `503 Service Unavailable` maintenance page from
[`docker/maintenance/`](docker/maintenance/) — the HTML variant auto-refreshes
every 30 s, the `application/problem+json` variant lets AJAX calls render their
existing "backend unreachable" toast. Mechanics live in
[docs/deployment.md → Maintenance page](docs/deployment.md#maintenance-page).

### 3.3 Running locally with Docker Compose (pulling from GHCR)

Same images, same orchestration as production, but on your own machine:

1. **Authenticate to GHCR** (one-time): create a fine-grained PAT with
   `Packages: Read` on `krt-profit/basetool`, then

   ```bash
   echo "$GHCR_TOKEN" | docker login ghcr.io --username your-gh-handle --password-stdin
   ```
2. **Provide a `keystore.p12`** at the path your `.env`'s
   `IRI_KEYSTORE_HOST_PATH` points to (default: repo root `./keystore.p12`).
   See [§4.4 Running the Local Test Stack](#44-running-the-local-test-stack)
   for the `keytool` recipe.
3. **Start**:

   ```bash
   docker compose --profile prod up -d         # pulls :stable
   # or pin a specific version:
   IRI_BASETOOL_VERSION=1.4.3 docker compose --profile prod up -d
   ```

Default host ports: backend `11261`, frontend `18081`, Keycloak `18080`,
backend DB `15432`, Keycloak DB `15433`, Redis `6379`, NPM admin `10081`.

---

## 3.4 Multi-squadron rollout

The deployed shape on `MULTI_SQUADRON` (Flyway `V80`–`V83`) turns the
single-tenant Basetool into a multi-squadron app while keeping a single
production database and a single Keycloak realm. The living spec is
[`docs/specs/org-unit-tenancy.md`](docs/specs/org-unit-tenancy.md)
(`REQ-ORG-*`); the full audit trail is in
[`CHANGELOG.md`](CHANGELOG.md) under the `Multi-Squadron-Umbau` heading.

What changed at the data layer:

* Every `app_user` row gained a `squadron_id` (FK to `squadron`). The
  IRIDIUM squadron is seeded at the canonical UUID
  `00000000-0000-0000-0000-000000000001` and was the default for legacy
  rows. (Since superseded: `V104` dropped `app_user.squadron_id` again —
  memberships live exclusively in `org_unit_membership`.)
* The five staffel-scoped aggregate roots —
  `mission` / `operation` / `ship` / `inventory_item` /
  `refinery_order` — gain an `owning_squadron_id` column.
* `job_order` started cross-squadron with `creating_squadron_id` +
  `requesting_squadron_id`. **This was later reworked** (parent issue #340):
  `creating` was dropped in favour of a `responsible_org_unit_id` (the
  *processing* unit, which governs visibility), `requesting` became
  `requesting_org_unit_id`, the order became conditionally scoped
  (SK-responsible = public, squadron-responsible = private), and squadrons can
  now sign up for partial material **claims** (`material_claim`) on the public
  SK queue. See `CLAUDE.md`.

What changed at the authorization layer:

* [`OwnerScopeService`](backend/src/main/java/de/greluc/krt/profit/basetool/backend/service/OwnerScopeService.java)
  (originally `SquadronScopeService`, later renamed and broadened to cover
  Staffeln **and** Spezialkommandos) centralises `currentOrgUnitId()` /
  `canSee…` / `canEdit…` for every staffel-scoped aggregate. Read paths
  consult it in service / repository filters (via a three-parameter
  `ScopePredicate` tuple), write paths stamp the owning OrgUnit at create
  time, and controllers use it via `@PreAuthorize("@ownerScopeService.canEdit…")`
  on detail-view endpoints.
* [`MeController`](backend/src/main/java/de/greluc/krt/profit/basetool/backend/controller/MeController.java)
  exposes `GET /api/v1/me/active-org-unit` for every authenticated caller to
  **read** the resolved context. **Switching** is a frontend concern:
  [`MeFrontendController`](frontend/src/main/java/de/greluc/krt/profit/basetool/frontend/controller/MeFrontendController.java)
  `POST /active-org-unit` updates the Redis-backed session pin and relays it
  to the backend on every outbound call via the `X-Active-Org-Unit-Id` header.
* MDC field `orgUnitId` (sentinels `all` / `none` / `anonymous`) is attached
  by [`CorrelationIdFilter`](backend/src/main/java/de/greluc/krt/profit/basetool/backend/logging/CorrelationIdFilter.java)
  so log lines and access-log JSON show which OrgUnit context a request ran
  under.
* ArchUnit rule
  `staffelScopedServicesMustWireOwnerScopeOrAuthHelper` in
  [`ArchitectureTest`](backend/src/test/java/de/greluc/krt/profit/basetool/backend/ArchitectureTest.java)
  fails the build if a staffel-scoped service stops injecting one of
  the auth services.

What changed for **Officers** (Phase 4 lockdown):

The admin area used to be `hasAnyRole('ADMIN','OFFICER')`. It is now
strict `hasRole('ADMIN')` everywhere — Stammdaten / Member-Management /
Announcements / UEX / System-Settings / Promotion-System. Officers
keep every squadron-internal capability: mission management, hangar
write (incl. `resetAllFittedStatus`), refinery, logistician via role
hierarchy, and the Job Order workflow (now conditionally scoped —
SK-responsible orders are a shared queue, squadron-responsible orders
are private; see the Job-Order rework, #340). The full matrix lives in
[`ROLES_AND_PERMISSIONS.md`](ROLES_AND_PERMISSIONS.md).

Mission visibility for guests / cross-squadron callers stays generous:
non-internal missions stay visible across squadrons (think public
operations boards), but everything else respects the strict squadron
filter.

**Status today.** The multi-squadron rollout is long complete and was
**extended into a multi-OrgUnit model**: a shared `org_unit` table with a `kind`
discriminator now backs every owner reference. The Spezialkommando work added the
`SPECIAL_COMMAND` kind, and the area / leadership hierarchy (epic #692) added the
`BEREICH` and `ORGANISATIONSLEITUNG` kinds stacked above the Staffeln/SKs; the
active-OrgUnit switcher shipped in the frontend, and the active unit now shows in
the application title (no separate context badge). The schema has advanced well
past the original Phase-7 chain (currently `V193`). The destructive cleanup is done — the
legacy `owning_squadron_id` mirror columns (`V103`), the per-user
`app_user.squadron_id` / `is_logistician` / `is_mission_manager` columns
(`V104`, now sourced from `org_unit_membership`), the legacy `squadron` table
(`V105`), `job_order.creating_*` (`V129`), and the legacy material /
ship-type columns (`V125`) are all dropped; a rollback to single-tenant is no
longer supported. Strict not-null owner stamping has since been *relaxed*
for deliberate ownerless rows: personal aggregates (`V132`), leadership
missions (`V144`) and leadership operations (`V145`). See
[`docs/specs/org-unit-tenancy.md`](docs/specs/org-unit-tenancy.md)
(`REQ-ORG-*`) for the current per-aggregate scope model.

### 3.5 Backups & disaster recovery

The production host runs an automated, client-side-encrypted, **off-site** backup. Every
night at 04:15 `scripts/backup.sh` (systemd `iri-backup.timer`) captures both PostgreSQL
databases (`krt_basetool` + the Keycloak realm), the nginx-proxy-manager state, and the host
secrets needed to stand the stack back up (`.env`, `keystore.p12`, `realm-export.json`,
`keycloak/providers`), then pushes them with **restic** to a **Nextcloud** target over an
rclone WebDAV remote (GFS retention: 7 daily / 4 weekly / 6 monthly, with a `restic check`).
For a consistent snapshot the writer services (`frontend`, `backend`, `ingest`) are stopped
**only for the dump** — seconds, inside the 04:00–05:00 window, NPM serving the maintenance
page meanwhile — while the slow upload runs after the stack is back up. A weekly restore
drill (`iri-restore-drill.timer`) restores the latest snapshot into a throwaway PostgreSQL
and verifies it, so recoverability is proven, not assumed.

Delivery is outbound-only (consistent with the pull-only host posture, `REQ-OPS-001`). Redis
(only sessions) and the WireGuard `wg0.conf` key are intentionally **not** in this backup —
`wg0.conf` is the operator's out-of-band responsibility. The backup credentials (restic
repository password + Nextcloud app password) live host-only in `/etc/iri/backup.env`, never
in git or the config bundle. Setup, the secured-Nextcloud-target guide, and the full restore
procedure are in [`docs/backup.md`](docs/backup.md); the binding requirements are in
[`docs/specs/backup-recovery.md`](docs/specs/backup-recovery.md) (`REQ-OPS-008..012`,
ADR-0056).

---

## 4. Development & Testing

### 4.1 Prerequisites

* [Java 25](https://adoptium.net/) — required for local Gradle builds.
* [Docker](https://www.docker.com/) and Docker Compose — for the dependency
  stack and the full dev / test stacks.
* Access to a Keycloak server — the Docker Compose stack ships one.

The project uses **Gradle 9 with the Kotlin DSL**. Always use the wrapper
(`./gradlew`); never the IDE test runner. Dependencies are managed by
[refreshVersions](https://jmfayard.github.io/refreshVersions/) — edit
`versions.properties`, not `build.gradle.kts`. Run
`./gradlew refreshVersions` to discover updates.

### 4.2 Local development setup (running the apps from Gradle)

Recommended for active development — the apps run on the host JVM with
fast restarts; only the dependencies live in containers.

1. **Start dependencies** (Postgres × 2, Keycloak, Redis):

   ```bash
   docker compose --profile dev up -d db-backend-dev db-keycloak-dev keycloak-dev redis-dev
   ```

   This exposes ports `15432` (backend DB), `15433` (Keycloak DB), `18080`
   (Keycloak) and `6379` (Redis) on the host.

2. **Run the backend** (uses the `dev` profile by default, HTTPS):

   ```bash
   ./gradlew :backend:bootRun
   ```

   Backend at `https://localhost:11261`. The OpenAPI document is served at
   `https://localhost:11261/v3/api-docs` in the `dev`/`test` profiles (disabled
   in `prod`); there is no Swagger UI.

3. **Run the frontend** (uses the `dev` profile by default, HTTP):

   ```bash
   ./gradlew :frontend:bootRun
   ```

   Frontend at `http://localhost:18081`.

*If you need to override `KEYCLOAK_ISSUER_URI`, set it as an environment
variable before running the commands.*

**Database details** (defaults from `.env`):
* Backend DB `krt_basetool` (user from `POSTGRES_USER`), port `15432`.
* Keycloak DB `keycloak` (user from `KC_POSTGRES_USER`), port `15433`.

### 4.3 Running the full dev stack with Docker Compose

Two flavours, depending on whether you need a local rebuild of the
application image.

**Pulling from GHCR** (default — fast, matches prod):

```bash
docker compose --profile dev up -d              # pulls :stable, exposes host ports
```

**Building locally from this checkout** (when iterating on the Dockerfile):

```bash
docker compose -f docker-compose.yml -f docker-compose.build.yml \
    --profile dev up -d --build
```

The `docker-compose.build.yml` override re-introduces `build:` directives
and tags the result as `:local` so it does not collide with GHCR-pulled
images.

Access in either flavour:
* **Frontend**: `http://localhost:18081`
* **Backend API**: `http://localhost:11261`
* **OpenAPI document** (dev/test only): `http://localhost:11261/v3/api-docs`
* **Keycloak**: `http://localhost:18080`

### 4.4 Running the local test stack

For quick UI verification of an in-progress change in a worktree, or for
any scenario where you want to spin up the full stack **without exposing
the production `.env`, the production `keystore.p12`, or the shared
`realm-export.json`** to a transient workspace. This setup uses an isolated
set of throwaway credentials so a forgotten `docker volume prune`, a stray
screenshot, or a CI artifact upload cannot leak real secrets. The rule is
enforced by [CLAUDE.md](CLAUDE.md) → *Testing*; the repo's `.gitignore`
already excludes `.env.*`, `keystore.p12` and `realm-export.json` so the
test artifacts never land in commits.

The procedure assumes you are working in the repository root (or in a
worktree; the commands are identical).

1. **Create `.env.test`** with throwaway credentials. The strings below are
   placeholders — pick anything that is *not* a value you use anywhere else.

   ```env
   # Backend DB (Postgres)
   POSTGRES_DB=krt_basetool_test
   POSTGRES_USER=basetool_test
   POSTGRES_PASSWORD=basetool-test-pw-do-not-use-in-prod

   # Keycloak DB (Postgres)
   KC_POSTGRES_DB=keycloak_test
   KC_POSTGRES_USER=keycloak_test
   KC_POSTGRES_PASSWORD=keycloak-test-pw-do-not-use-in-prod

   # Keycloak bootstrap admin
   KC_BOOTSTRAP_ADMIN_USERNAME=admin
   KC_BOOTSTRAP_ADMIN_PASSWORD=admin-test-pw-do-not-use-in-prod

   # Keycloak admin-API client secret (must match realm-export.json — see below)
   KEYCLOAK_ADMIN_CLIENT_SECRET=test-client-secret-do-not-use-in-prod

   # Redis
   REDIS_PASSWORD=redis-test-pw-do-not-use-in-prod

   # PKCS12 keystore password (must match the test keystore — see below)
   SERVER_SSL_KEY_STORE_PASSWORD=keystore-test-pw-do-not-use-in-prod

   # Host path of the test keystore (bind-mounted into the containers).
   # Default in docker-compose.yml is `./keystore.p12` at the repo root,
   # which lines up with step 2 below.
   IRI_KEYSTORE_HOST_PATH=./keystore.p12
   ```
2. **Generate a test keystore** with the password from step 1. Place it at
   the repo root so the default `IRI_KEYSTORE_HOST_PATH=./keystore.p12`
   from step 1 resolves. Do *not* copy a `keystore.p12` from any other
   checkout — the password would not match and `keytool` errors are hard
   to debug.

   ```bash
   keytool -genkeypair -alias basetool -storetype PKCS12 \
     -keystore ./keystore.p12 \
     -storepass "keystore-test-pw-do-not-use-in-prod" \
     -keypass  "keystore-test-pw-do-not-use-in-prod" \
     -keyalg RSA -keysize 2048 -validity 365 \
     -dname "CN=localhost, OU=Test, O=KRT Basetool Test, L=Test, ST=Test, C=DE" \
     -ext "san=dns:localhost,ip:127.0.0.1,dns:backend,dns:frontend,dns:ingest,dns:keycloak"
   ```

   > The `dns:keycloak` SAN entry lets the backend's user-sync admin client validate the
   > internal `https://keycloak:18443` connector with hostname verification on (see
   > [Keycloak behind NPM over HTTPS](docs/deployment.md#keycloak-behind-npm-over-https)).
   > A keystore generated without it still serves every other path (frontend/ingest disable
   > hostname verification), but the backend→Keycloak sync would fail the TLS handshake.

3. **Provide a test `realm-export.json`** at the repo root containing a
   Keycloak realm named `iri` with a `basetool-frontend` public client, a
   `backend-service` confidential client whose `secret` matches
   `KEYCLOAK_ADMIN_CLIENT_SECRET` from `.env.test`, and at least one test
   user. The minimal recipe: copy the production export, replace the
   `backend-service` secret, clear the SMTP block, drop the password
   policy, and replace the `users` array with a single test admin:

   ```python
   # build-test-realm.py — run once, never commit the output
   import json
   r = json.load(open('realm-export.json', encoding='utf-8'))   # production source (separate checkout)
   r['smtpServer'] = {}
   r.pop('passwordPolicy', None)
   for c in r['clients']:
       if c['clientId'] == 'backend-service':
           c['secret'] = 'test-client-secret-do-not-use-in-prod'
           c.setdefault('attributes', {}).pop('client.secret.creation.time', None)
       if c['clientId'] == 'basetool-frontend':
           c['redirectUris'] = ['http://frontend:18081/*', 'http://localhost:18081/*']
           c['webOrigins']   = ['http://frontend:18081', 'http://localhost:18081']
   r['users'] = [{
       'username': 'test-admin',
       'enabled': True, 'emailVerified': True,
       'email': 'test-admin@example.test',
       'credentials': [{'type': 'password', 'value': 'test-admin-pw', 'temporary': False}],
       'realmRoles': ['Admin', 'Officer', 'KRT Member', 'default-roles-iri',
                      'offline_access', 'uma_authorization'],
   }]
   json.dump(r, open('realm-export.json', 'w', encoding='utf-8'), indent=2, ensure_ascii=False)
   ```
4. **Use the `docker-compose.test.yml` override.** This file lives in the
   repo root and overrides three things in the base `docker-compose.yml`:
   * the hardcoded production `KEYCLOAK_ISSUER_URI` in the backend / frontend templates,
   * `KC_HOSTNAME=host.docker.internal` on Keycloak so the OIDC issuer URL resolves identically from the host browser (Docker Desktop magic) and from inside containers (`extra_hosts: host-gateway`),
   * the matching `KEYCLOAK_ADMIN_URL`, `KEYCLOAK_REALM` and `KEYCLOAK_ADMIN_CLIENT_ID` on the backend.
5. **One-time cleanup** if a previous Postgres init left stale credentials
   in the bind-mount data dirs (you will see
   `FATAL: password authentication failed` in the logs):

   ```bash
   docker compose --env-file .env.test \
     -f docker-compose.yml -f docker-compose.test.yml --profile dev down
   MSYS_NO_PATHCONV=1 docker run --rm -v /var/iri/db-backend:/data alpine \
     sh -c "rm -rf /data/* /data/.[!.]*"
   MSYS_NO_PATHCONV=1 docker run --rm -v /var/iri/db-keycloak:/data alpine \
     sh -c "rm -rf /data/* /data/.[!.]*"
   ```

   The `MSYS_NO_PATHCONV=1` prefix is only needed on Git Bash for Windows;
   it prevents the shell from translating the `/var/iri/...` Linux path
   into a Windows path inside the Docker CLI.

6. **Start the stack**:

   ```bash
   docker compose --env-file .env.test \
     -f docker-compose.yml -f docker-compose.test.yml --profile dev up -d
   ```
7. **Access** (same ports as the regular dev stack):
   * **Frontend**: `http://localhost:18081`
   * **Backend API**: `https://localhost:11261` (self-signed cert from the test keystore)
   * **OpenAPI document** (dev/test only): `https://localhost:11261/v3/api-docs`
   * **Keycloak**: `http://localhost:18080` — log in with the bootstrap admin from `.env.test`
   * **Realm `iri` test user**: username `test-admin`, password `test-admin-pw`
8. **Tear down** after the verification — leaving a test stack running
   consumes 2+ GB of RAM and the named anonymous volumes will collide
   with the next spin-up:

   ```bash
   docker compose --env-file .env.test \
     -f docker-compose.yml -f docker-compose.test.yml --profile dev down --volumes
   ```

### 4.5 Running tests

Tests force `spring.profiles.active=test`; both `Test` and `BootRun` set
`--enable-native-access=ALL-UNNAMED` and a Mockito agent JVM arg.

```bash
./gradlew test                                              # all tests
./gradlew :backend:test                                     # backend tests only
./gradlew :frontend:test                                    # frontend tests (also produces a JaCoCo report)
./gradlew :backend:test --tests "FullyQualifiedClassName"   # single test class
./gradlew :backend:test --tests "ClassName.methodName"      # single test method
```

ArchUnit rules in [`backend/.../ArchitectureTest.java`](backend/src/test/java/de/greluc/krt/profit/basetool/backend/ArchitectureTest.java)
and [`frontend/.../ArchitectureTest.java`](frontend/src/test/java/de/greluc/krt/profit/basetool/frontend/ArchitectureTest.java)
enforce architectural invariants (no `SecurityContextHolder` outside the
auth-helper service, every `@RestController` carries at least one
`@PreAuthorize`, controllers never return JPA entities, the frontend
never depends on Spring Data JPA). Adding a violation fails `./gradlew test`.

### 4.6 Linting, static analysis and SBOM

```bash
./gradlew check                                              # full sweep: Checkstyle + SpotBugs + tests
./gradlew :backend:checkstyleMain :backend:spotbugsMain      # backend lint only
./gradlew :frontend:checkstyleMain :frontend:spotbugsMain    # frontend lint only
./gradlew spotlessApply                                      # auto-format Java sources (run before every push)
./gradlew :backend:cyclonedxBom                              # SBOM on demand into backend/docs/
./gradlew :frontend:cyclonedxBom                             # SBOM on demand into frontend/docs/
```

The two `cyclonedxBom` commands generate the SBOM **on demand** — it is not part
of `build` or `check`. The committed `<module>/docs/*-bom.*` files are a release
artifact, refreshed automatically by the release workflow; run these only to
inspect the SBOM locally.

Reports land under `<module>/build/reports/{checkstyle,spotbugs}/main.{html,xml}`.
Checkstyle runs with `maxWarnings = 0` and Spotless is wired into `check`
via `isEnforceCheck = true` — any unformatted Java file or new Checkstyle
warning fails CI immediately.

### 4.7 End-to-end (E2E) tests

Playwright-Java drives the real frontend through a browser. The suite lives in
the `frontend` module's `e2e` source set and is **not** wired into `check` — it
needs Docker and a downloaded browser, so it runs only on explicit tasks:

```bash
./gradlew :frontend:e2eTest                              # full destructive flows (@Tag("e2e"))
./gradlew :frontend:smokeTest                            # non-destructive page-load checks (@Tag("smoke"))
./gradlew :frontend:e2eTest --tests "*MissionCreate*"    # a single flow
./gradlew :frontend:e2eTest -Pe2e.browser=firefox        # engine: chromium (default), firefox, webkit
```

By default, `E2eStackExtension` builds the app images and brings up an
**ephemeral stack** (Postgres ×2 + Keycloak + Redis + backend + frontend) via
`docker compose`, seeds the minimal data each flow needs, runs the tests, and
tears the stack down (`down --volumes`) afterwards — all with throwaway
credentials generated on the fly (never the production `.env` / keystore /
realm). The first run downloads the Playwright browsers — Chromium, Firefox and
WebKit (the `playwrightInstall` task); Docker plus a recent `docker compose` v2
must be available.

Point the suite at an already-running deployment (e.g. staging) instead by
setting `E2E_BASE_URL`; the extension then skips Docker entirely and only logs
in and exercises pages:

```bash
E2E_BASE_URL=https://staging.example E2E_USERNAME=<user> E2E_PASSWORD=<pw> \
    ./gradlew :frontend:smokeTest
```

**Cross-browser.** `-Pe2e.browser` selects the engine (`chromium` default,
`firefox`, `webkit`); CI runs all three as a matrix. Against the ephemeral stack
the Keycloak issuer host `host.docker.internal` must resolve to the loopback, and
each engine gets there differently: Chromium via a resolver arg and Firefox via a
profile pref (both automatic), but **WebKit** has no launch-level override and
relies on the OS hosts file. CI adds the entry; on a workstation add `127.0.0.1
host.docker.internal` to your hosts file to run WebKit against the local stack (or
just run WebKit against staging via `E2E_BASE_URL`). The suite fails fast with
this hint if WebKit is selected without the mapping.

CI runs these as a Chromium/Firefox/WebKit matrix:
[`e2e.yml`](.github/workflows/e2e.yml) runs the destructive flows on an `e2e` PR
label, a nightly schedule, or manual dispatch;
[`e2e-smoke.yml`](.github/workflows/e2e-smoke.yml) runs the smoke checks against
staging on a schedule or manual dispatch (never on PRs) once the repo defines an
`E2E_BASE_URL` variable. The per-flow use cases (actor, preconditions, steps,
expected result) are documented under [`docs/e2e-test/`](docs/e2e-test/README.md).

---

## 5. Technical Details

### 5.1 Tech stack

* **Language** — Java 25
* **Framework** — Spring Boot 4.1.0
* **Build tool** — Gradle 9 with the Kotlin DSL, dependencies via refreshVersions
* **Database** — PostgreSQL 18, schema owned by Flyway (Hibernate `ddl-auto=validate` everywhere)
* **Session store** — Redis (`spring-session-data-redis`)
* **Security** — Spring Security with OAuth2 / OIDC (Keycloak 26.6)
* **Frontend** — Thymeleaf, Spring Security OAuth2 Client, WebClient wrapped with Resilience4j (Timeout, Retry, CircuitBreaker, Bulkhead)
* **API documentation** — SpringDoc / OpenAPI; the committed `backend/src/main/resources/api/openapi.json` is the single documentation artifact. No Swagger UI is bundled, and `/v3/api-docs` is disabled in the `prod` profile.
* **DTO mapping** — MapStruct (`@Mapper(componentModel = "spring")`)
* **Containerization** — Docker and Docker Compose; images published to GHCR, signed with Cosign keyless and shipped with SLSA provenance + SPDX SBOM attestations

### 5.2 Project structure

The project is split into three Spring Boot modules (backend, frontend,
ingest) plus a Keycloak provider library and a few smaller top-level
directories:

* **`backend`** — REST API only. Layered: `controller` → `service` → `repository` → `model` (JPA entities), with `dto` records, MapStruct `mapper`s, `config` (security, caching, OpenAPI, rate limiting, WebClient), `integration` (UEX external API), `task` (scheduled jobs), `filter`/`interceptor` (correlation ID, deprecation headers), `annotation` (`@ApiDeprecation`).
* **`frontend`** — Thymeleaf server-rendered UI that calls the backend via WebClient. No business logic of its own; `service.BackendApiClient` is the single seam. Persistent state across frontend restarts lives in Redis (Spring Session).
* **`ingest`** — internet-facing one-click ingest gateway (desktop extractor → basetool). Spring Boot, owns no database, terminates a token-authenticated `POST` and relays it to the backend over the internal network so the backend stays internet-unreachable (see §3.1, [ADR-0018](docs/adr/0018-desktop-ingest-gateway-device-grant.md)).
* **`keycloak-spi`** — Keycloak provider library (not a Spring Boot app): the Discord social identity provider and the guild / `KRT-Mitglied`-role login gate. Built as a plain JAR and staged into Keycloak's `providers/` directory (epic #720, [ADR-0030](docs/adr/0030-discord-federation-first-login-membership-gate.md)).
* **`keycloak-theme/krt-theme`** — Custom Keycloak login and account UI theme matching the DAS KARTELL (KRT) corporate design. See [§5.7 Keycloak theme](#57-keycloak-theme).
* **`design`** — Brand font sources. The design system itself (colors, typography, components, the Corporate Design Manual) lives in the [`krt-profit/design-system`](https://github.com/krt-profit/design-system) git submodule mounted at [`.claude/skills/das-kartell-design/`](.claude/skills/das-kartell-design/README.md).
* **`scripts`** — Server-side operations layer: the GHCR-pull production deploy script (`deploy.sh`, invoked by the `iri-deploy` systemd timer), the weekly `docker-cleanup.sh` and `vpn-restart.sh` maintenance scripts, the `check-flyway-migrations.sh` guard, and their systemd / cron / logrotate units.
* **`docs`** — Long-form documentation: the binding requirement specs under [`docs/specs/`](docs/specs/INDEX.md), the ADRs under [`docs/adr/`](docs/adr/README.md), the [deployment runbook](docs/deployment.md) and the [E2E use cases](docs/e2e-test/README.md).
* **`config`** — Static-analysis configuration consumed by the Gradle build: Checkstyle (`config/checkstyle/google_checks.xml`), SpotBugs (`config/spotbugs/exclude.xml`) and the OWASP dependency-check suppressions.
* **`docker`** — The `docker/maintenance/` branded `503 Service Unavailable` page that nginx-proxy-manager serves during the brief deploy switchover (see §3.2).

The frontend never talks to PostgreSQL or the Keycloak Admin API directly.
The backend never serves HTML.

The frontend hand-mirrors the backend's DTOs as its own records (no shared module, no code
generation) — deliberately, to keep the module split clean. `FrontendDtoContractTest` is the drift
gate for that duplication: it diffs every `frontend/model/dto` record against the committed
`backend/src/main/resources/api/openapi.json` and fails the build on a shape divergence or on an
`enum`&rarr;`String` demotion that is not explicitly opted into via `@BackendEnumAsString` (the
frontend renders some backend enums as raw names for i18n keys — a choice the annotation records).

### 5.3 Configuration (environment variables)

Both modules read configuration from environment variables. The most
commonly tuned values:

| Variable                                     | Description                                                                                                                                                                                                                                                                                 | Default                                                         |
|:---------------------------------------------|:--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|:----------------------------------------------------------------|
| `KEYCLOAK_ISSUER_URI`                        | The URL of the Keycloak realm.                                                                                                                                                                                                                                                              | `https://keycloak.profit-base.online/realms/iri`                |
| `BACKEND_URL`                                | (Frontend only) The URL of the backend API. Override to `http://localhost:11261` when running the frontend from Gradle on the host.                                                                                                                                                         | `https://backend:11261`                                         |
| `APP_LOGGING_CORRELATION_ID_HEADER`          | HTTP header used for inbound / outbound request correlation (MDC-backed).                                                                                                                                                                                                                   | `X-Correlation-Id`                                              |
| `APP_LOGGING_SLOW_REQUEST_THRESHOLD_MS`      | Threshold (ms) above which a request is logged at `WARN` instead of `INFO`.                                                                                                                                                                                                                 | `2000`                                                          |
| `APP_LOGGING_STRUCTURED_ENABLED`             | Enables the JSON (Logstash) log appender. Automatically `true` in the `prod` profile.                                                                                                                                                                                                       | `false` (dev / test), `true` (prod)                             |
| `APP_LOGGING_SLOW_BACKEND_CALL_THRESHOLD_MS` | (Frontend only) Threshold (ms) above which an outbound backend call is logged at `WARN`.                                                                                                                                                                                                    | `1500`                                                          |
| `IRI_BASETOOL_VERSION`                       | Image tag pulled by the production compose stack.                                                                                                                                                                                                                                           | `stable`                                                        |
| `IRI_IMAGE_NAMESPACE`                        | GHCR namespace for the image lookup.                                                                                                                                                                                                                                                        | `krt-profit`                                                    |
| `IRI_KEYSTORE_HOST_PATH`                     | Absolute host path of the production `keystore.p12`, bind-mounted read-only into backend + frontend at `/run/secrets/keystore.p12`.                                                                                                                                                         | `./keystore.p12` (prod `.env`: `/var/iri/secrets/keystore.p12`) |
| `REDIS_PASSWORD`                             | Password for the Redis session store.                                                                                                                                                                                                                                                       | *(required, no default)*                                        |
| `HOST_IP`                                    | Deployment host IP that the compose stack binds outbound services to.                                                                                                                                                                                                                       | *(required, no default)*                                        |
| `DISCORD_GUILD_ID`                           | (Optional) DAS KARTELL Discord server (guild) id; enables the per-guild nickname capture in the admin Discord-registration queue. Needs the mappers from [docs/keycloak/DISCORD_KEYCLOAK_SETUP.md](docs/keycloak/DISCORD_KEYCLOAK_SETUP.md).                                                | *(unset → no nickname capture)*                                 |
| `KRT_DISCORD_SPI_SHARED_SECRET`              | (Optional) Shared secret for the Discord first-login account-existence precheck (REQ-SEC-022). Set the **same** value on the backend (it guards `/internal/discord/account-existence`) and the Keycloak SPI (it presents it). Blank → precheck disabled (fail-open).                        | *(unset → precheck disabled)*                                   |
| `KRT_BACKEND_PRECHECK_URL`                   | (Optional, Keycloak only) HTTPS URL of the backend account-existence endpoint, e.g. `https://backend:11261/internal/discord/account-existence`. Must be `https://`; blank/non-HTTPS → precheck skipped (fail-open).                                                                         | *(unset → precheck disabled)*                                   |
| `KRT_BACKEND_TRUSTSTORE_PATH`                | (Optional, Keycloak only) Path inside the Keycloak container to a PKCS#12 truststore trusting the backend's certificate, so the precheck's HTTPS call validates it. Unconfigured/unreadable → the call fails the handshake and the precheck fails open (cert validation is never disabled). | *(unset → default JVM trust)*                                   |
| `KRT_BACKEND_TRUSTSTORE_PASSWORD`            | (Optional, Keycloak only) Password for `KRT_BACKEND_TRUSTSTORE_PATH`.                                                                                                                                                                                                                       | *(unset)*                                                       |

Relevant `application-*.yml` settings live in `@ConfigurationProperties`
classes with `@Validated` (Keycloak URIs, backend URLs, limits). Constraints
are enforced via `@NotBlank`, `@URL`, `@Min` / `@Max`; misconfiguration is
caught at startup. See the `*Properties` classes under `config/`.

### 5.4 Session persistence (Redis)

Spring Sessions are persisted in Redis (`spring-session-data-redis`).
After a frontend container restart, all active user sessions remain
intact — no visible re-login flow, no 302 redirects. Redis runs as a
dedicated Docker service (`redis` / `redis-dev`) on the
`net-redis-frontend` network and is reachable from the frontend container
under the hostname `redis`. The password is configured via the
`REDIS_PASSWORD` environment variable (see `.env`).

The `SsoReAuthenticationEntryPoint` remains as a fallback for genuinely
expired sessions (session timeout). It recognises known bot / scanner
paths (`/wp-admin/`, `/robots.txt`, `/feed/`, …) and answers them
directly with HTTP 404, without triggering an OAuth2 flow. For legitimate
app paths with an expired session, it performs a silent Keycloak SSO
redirect (`prompt=none`).

### 5.5 Request correlation and structured logging

Both modules emit one access-log line per request (HTTP method, path,
status, duration) and enrich **every** log line with these MDC fields:

- `correlationId` — taken from the inbound `X-Correlation-Id` request header (configurable via `APP_LOGGING_CORRELATION_ID_HEADER`) or generated as a UUID if absent. The effective id is echoed back in the response header of the same name so clients, proxies and load balancers can trace requests end-to-end. The frontend's `WebClientLoggingFilter` also propagates the same id to every outbound backend call, so backend and frontend log lines of the same user interaction share one id.
- `userId` — the JWT / OIDC `sub` claim of the authenticated user, or `anonymous` for unauthenticated traffic. Names, emails and tokens are never logged.
- `orgUnitId` — (backend) the effective OrgUnit context the request ran under (sentinels `all` / `none` / `anonymous`).

In addition to access logs, the frontend module logs Resilience4j events
(circuit-breaker state transitions, retry attempts, bulkhead rejections,
time-limiter timeouts) via a dedicated `ResilienceEventLogger` so that
degraded-backend symptoms such as `SERVICE_UNAVAILABLE` / `BACKEND_TIMEOUT`
always carry a matching log entry explaining why.

In the `prod` profile both applications additionally write a structured
JSON log (`logs/backend.json` / `logs/frontend.json`) via `LogstashEncoder`,
making the logs ready for ELK / Loki / CloudWatch ingestion. Error events
are rolled into dedicated `*-error.log` files for fast incident triage.

### 5.6 API conventions

**Error format (RFC 7807 Problem Details).** All API errors are returned
using RFC 7807 Problem Details with content type `application/problem+json`.

* Fields: `type` (URI), `title` (short summary), `status` (HTTP status), `detail` (human-readable explanation), `instance` (request URI).
* Validation errors additionally include an `errors` object with field → message pairs.

```json
{
  "type": "https://profit-base.online/problems/constraint-violation",
  "title": "Validation failed",
  "status": 400,
  "detail": "One or more fields have invalid values.",
  "instance": "/api/v1/job-types",
  "errors": {
    "name": "must not be blank"
  }
}
```

The OpenAPI document (`openapi.json`) documents standard 4xx / 5xx responses with this schema.

**API versioning and deprecation.** The REST API uses semantic versioning
via URI paths (`/api/v1/...`). Endpoints slated for removal are marked as
deprecated in the OpenAPI specification and return the following HTTP
headers:

- `Deprecation: true`
- `Sunset: <Date>` (e.g. `Thu, 31 Dec 2026 00:00:00 GMT`)
- `Link: <replacement-url>; rel="alternate"` (providing the migration path)

**Pagination and sorting.** All list endpoints take Spring's `Pageable`
and return a `PageResponse` wrapper (total elements, pages, current page).
Allowed sort fields are whitelisted in the service layer — user input is
never passed directly to `Sort`.

**Time handling.** All times are stored and processed as `Instant` or
`OffsetDateTime` in UTC; conversion to the user's local timezone happens
in the display layer only.

### 5.7 Keycloak theme

The custom theme lives under `keycloak-theme/krt-theme/` and contains two
FreeMarker theme families:

| Folder     | Used for                                                      | Parent                                             | Locales              |
|:-----------|:--------------------------------------------------------------|:---------------------------------------------------|:---------------------|
| `login/`   | The Keycloak login flow (`login.ftl`, OTP, password reset, …) | `keycloak` (Keycloak's classic theme)              | `de` (default), `en` |
| `account/` | The user-facing self-service Account Console                  | `keycloak.v3` (Keycloak's modern v3 account theme) | `de` (default), `en` |

Both flavours pull in the `Lato` font faces from
`<flavour>/resources/fonts/` and a single CSS file
(`login/resources/css/krt-login-v3.css` and
`account/resources/css/krt-account-v3.css`) that overrides the parent
theme's colours and typography to match the corporate design described
in the design system
([`.claude/skills/das-kartell-design/README.md`](.claude/skills/das-kartell-design/README.md),
binding rules in [`docs/specs/ui-design-system.md`](docs/specs/ui-design-system.md)).

**Wiring.** `docker-compose.yml` bind-mounts the theme directory directly
into the Keycloak container, so any edit takes effect on the next
container restart with no rebuild:

```yaml
keycloak:
  volumes:
    - ./keycloak-theme/krt-theme:/opt/keycloak/themes/krt-theme
```

The realm export (`realm-export.json`, also bind-mounted into
`/opt/keycloak/data/import/`) sets the per-realm `loginTheme` and
`accountTheme` to `krt-theme`, so the customisation activates as soon as
Keycloak boots.

**Making theme changes.**

1. Edit the relevant FreeMarker template (`login/login.ftl`, …) or CSS
   file under `keycloak-theme/krt-theme/<login|account>/`.
2. Restart only the Keycloak container so it re-reads the theme:

   ```bash
   docker compose --profile prod restart keycloak     # or `keycloak-dev` for the dev profile
   ```

   Keycloak caches theme resources by default; the restart bumps the cache.

3. Hard-reload the login / account page (browser cache clear or
   `Ctrl+Shift+R`) — the CSS file is served with a long cache header, so
   without a hard reload you may keep seeing the previous version.

No Java build / Docker rebuild is needed for theme-only edits; only the
volume mount has to be in place and the container has to bounce.

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
