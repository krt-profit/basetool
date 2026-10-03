> **Doc type:** Appendix of the living plan [Domain modularisation plan](../DOMAIN_MODULARISATION_PLAN.md)
> — the target cut decided on 2026-09-29 (full cut, hard cut with forced update); a snapshot
> against `origin/main` `95e945326`.

# REST API cut

This appendix is the detail behind decisions D-03, D-04 and D-05 and behind §5.10 and §7.9 of the
plan: where each operation of the backend's `/api/v1` moves, what the move costs the Android app,
the edge, the frontend and the backend's path-keyed controls, which security property it puts at
risk and which guard of plan §6.1 (G-01 … G-25) catches that, and how a wave reaches production
with a forced app update.

**Conventions.**

- An *operation* is one verb and path of the committed `backend/src/main/resources/api/openapi.json`;
  a *mapping* is one handler method in the source. *App-frozen* means listed in the frozen contract
  set of `ExternalContractTest` (REQ-API-009). *Web* counts are lower bounds: 516 of the frontend's
  624 `BackendApiClient` call sites resolve to a literal path.
- API paths drop the `/api/v1` prefix where the context is clear: `/users/me` is `/api/v1/users/me`.
- In Java paths, `…` stands for `java/de/greluc/krt/profit/basetool/<module>`:
  `backend/src/main/…/config/SecurityConfig.java` is the backend's security configuration,
  `ingest/src/main/…/web/ExchangeController.java` the gateway's relay. After its first mention a
  file is cited by its name. Line numbers are exact for `95e945326`; class and symbol names outlive
  them.
- App facts come from the `basetool-android` repository at tag `v0.3.1`, one release behind the
  newest app; its files are cited by name.
- The cut below was measured: every move was applied to today's mappings, edge include, no-store
  list, rate-limit rules and app contract set, and the deltas were counted. Rows marked
  *not measured* belong to the same analysis but were not part of that run.

## Principles

The cut follows eight principles. P7 is the owner's decision D-04; the others come from the audit.

- **P1 — One resource root per domain** under `/api/v1/`. A domain keeps several roots only for
  genuinely different resources: org units (`/org-units`, `/squadrons`, `/special-commands`) and
  blueprints (`/blueprints` is the catalogue, `/personal-blueprints` what a member owns).
- **P2 — Domain first, audience second.** Admin sub-trees live at `/api/v1/<root>/admin/**`, as the
  bank's already do. One `SecurityConfig` matcher `/api/v1/*/admin/**` → `ADMIN` replaces
  `/api/v1/admin/**` and `/api/v1/bank/admin/**` once the last operation has left the old prefix;
  `*` is exactly one segment in a `PathPattern`, which a test proves. The matcher sits before every
  domain rule (see *Security deltas and guards*), and every admin controller keeps its class-level
  `hasRole('ADMIN')`. Nothing moves under `/terms/` or `/me/` while a prefix rule or a prefix
  exemption keyed on them exists.
- **P3 — Member-scoped resources live in their owning domain** (`/api/v1/<root>/me/…`). Identity
  keeps `/api/v1/me` (capabilities and the layout composite) and `/api/v1/users`.
- **P4 — A cross-domain read model is a filter on the owner's collection**
  (`/inventory/allocations?missionId=`); a sub-resource that owns a link entity stays with the
  link's owner (an order's allocations under `/orders/{id}/allocations`).
- **P5 — No shape suffixes** such as `/slim`. One collection `GET` with typed filters, `q` for free
  text and `page`, `size` and `sort`, plus `/lookup` for pickers; paged for anything that grows,
  plain arrays only for closed vocabularies.
- **P6 — Tier T0 never moves** (next section).
- **P7 — Hard cut.** No parallel old and new paths, no deprecation aliases, no sunset windows (D-04).
  A wave that changes app operations is one app release, one deploy and one raise of the minimum
  app version, and every removed or changed app operation is a line of the declared-break ledger
  before the wave merges. A wave that changes only web operations ships with the ordinary atomic
  deploy of frontend and backend.
- **P8 — No generic CRUD base controller** for the catalogue: the July 2026 audit rejected such
  templates because they hide the `auditService.record` call, and the rejection still holds.

## Contract tiers

Every operation carries one tier, marked on the handler and emitted into the document as
`x-contract-tier`.

| Tier | What it holds | When it may break |
| --- | --- | --- |
| **T0** | The version gate, the SPI endpoint, the 14 exchange relay operations and, by recommendation, the three stream operations (below) | Never; a T0 break fails every build |
| **T1** — the app contract | Every operation a released app build calls, minus T0. Today the frozen set stands for it (234 verb+path pairs, the T0 members the app uses included); it must be derived from the app's own call list instead, because the frozen set misses operations the app calls (*Findings*) | Only in a declared hard-cut wave: a ledger line, a new app release published first, the floor raised once the re-cut release is verified healthy (or with the release itself, once the floor is release-bound — D-11) |
| **T2** — web only | Everything else | Freely, with the atomic deploy of frontend and backend; guarded by the frontend contract tests and the call-existence test G-14 |

**T0, member by member.**

| Operation | Why it is frozen |
| --- | --- |
| `GET /api/v1/app/version-policy` | It is the wall. The app reads it ahead of its lock, login and session; a moved or renamed gate leaves exactly the builds that are too old reading "no floor" (REQ-API-010). Details in *The forced update* |
| `POST /internal/discord/account-existence` | The Keycloak SPI calls it during first-broker login and fails open, so a 404 would silently switch the duplicate-account check off (`backend/src/main/…/controller/DiscordAccountExistenceController.java:44-58`). Outside `/api/**`, no JWT, gated by a shared-secret header, hidden from the document |
| `/api/v1/exchange/**` — 14 operations on 13 paths | D-05. Every external read and write is relayed here. The gateway validates each answer against the published schema, passes a refusal only for a registered `code` with its fixed status and turns everything else into `502 BACKEND_RELAY_FAILED` (`ingest/src/main/…/web/ExchangeController.java:948-955`) |
| `GET /api/v1/notifications/stream`, `GET /api/v1/live-sync/stream`, `POST /api/v1/live-sync/changed` — by recommendation | All three are app-frozen parts of the app's push channels. The two streams are read by path in `SubjectRateLimitingFilter`, `StreamAwareShallowEtagHeaderFilter`, `NotificationStreamObservationPredicate`, `RequestLoggingFilter`, the edge include and an alert text (`monitoring/prometheus/alerts/business.yml:895`); moving them buys no domain separation |

**What frozen means for the exchange surface.** The 14 method and path pairs and the `cursor` and
`limit` parameters; request and response field names, types, optionality and enum values, with
tolerant reading of unknown fields; the RFC 7807 body with its `code`; every relayed code and its
status, including the codes that backend-wide components emit (`GlobalExceptionHandler`,
`TermsAcceptanceAccessFilter`, `PendingApprovalAccessFilter`, `ActingMemberFilter`); the five relay
headers and the `azp` allow-list that decides who may send them; `ActingMemberFilter`'s 13 exact
path patterns (`backend/src/main/…/config/ActingMemberFilter.java:98-112`), never a prefix; the
Redis registry mirror, revocation and handoff formats; the three landing URLs in the answers; and
the global JSON input handling the exchange DTOs inherit (`NormalizedStringDeserializer`: trim, NFC,
length cap, blank to `null`). The package, module and class that serve the operations may change,
and so may DTO class names as long as the JSON stays identical. The surface is published as its own
internal OpenAPI document, so per-domain diffs, the frontend's generated test types and
`DtoOpenApiContractTest` stop seeing it (ADR-0216 amendment). Two draft routes reuse web-import
DTOs today: the refinery route takes `RefineryExtractDto` and answers `RefineryImportDraftDto`, the
blueprint route answers `BlueprintImportPreviewDto`. The refinery route gets its own request DTO
before the refinery wave, or that wave changes frozen external behaviour; the two answers are staged
for the frontend, never reach the external client, and change only together with the frontend's
handoff reader.

**How the tiers are enforced.** The frozen contract set is exactly what the app calls — T1 plus the
T0 members the app uses — and the generated edge include admits exactly the frozen set plus the two
anonymous reads (`GET /api/v1/app/version-policy`, `GET /api/v1/terms/document`). T0 is compared
with a record that never changes. A breaking-change gate — the ingest's `SchemaCompatibility`
helper generalised to the backend document — compares every pull request with the previous
release: T0 breaks fail always, T1 breaks fail unless the ledger declares them, T2 breaks are
reported. No external diff tool is added (plan §10). The tier annotation must not become an exposure
switch: the edge include is generated from it but stays committed and reviewed, because opening a
family to the app and freezing it remain one decision (ADR-0136).

## Today's surface

- **99 controllers, 574 mappings, 572 documented operations.** All controllers sit in
  `backend/src/main/…/controller`, eight of them in `controller.exchange`. The committed document
  has 440 paths, 489 schemas and 96 tags (2,038,990 bytes); the two undocumented mappings are
  `/error` and the SPI endpoint. 244 documented operations are `GET`s; 188 mappings take a request
  body.
- **22 domains, 11 of them spread over several first segments**: catalogue 18, orgunit 5,
  identity 4, admin-system 4, blueprint 3, exchange 3, and bank, mission, materialexchange,
  notification and personalinventory 2 each. `/api/v1` has 52 first segments.
- **The admin prefix is an audience, not a domain.** `/api/v1/admin/**` holds 51 operations of five
  domains — identity 18, exchange 14, blueprint 11, catalogue 4, personalinventory 4 — behind one URL
  rule (`SecurityConfig.java:433-434`). The bank does it the other way round:
  `/api/v1/bank/admin/**` (`:435-436`, 5 operations).
- **`/api/v1/users/**` carries the data of six domains** (identity, orgunit, bank, mission,
  blueprint, dashboard); `UserController` alone has 27 mappings.
- **29 operations sit in the controller or prefix of a domain they do not belong to**, not counting
  three intended exchange-to-blueprint relays: identity → orgunit 8, identity → bank 2, identity →
  mission 2, identity → blueprint 2, identity → dashboard 1, joborder → blueprint 4, joborder →
  inventory 3, joborder and inventory → catalogue 2, mission → hangar 1, refinery → mission and
  catalogue 2, materialexchange → inventory 1.
- **Three capabilities exist twice**: the balance target (`PATCH /bank/accounts/{id}/balance-target`
  and `PUT /org-units/bank/accounts/{id}/balance-target`), the game-item catalogue
  (`GET /inventory/item-catalog` with `q`, `GET /orders/item-catalog` with `search`) and the user
  attributes (`PUT /users/{id}/attributes` and `PUT /admin/users/{id}/attributes`, the latter
  without a caller).
- **The public API vhost admits 259 documented operations**, against 234 app-frozen verb+path pairs. Since 2026-10-01 the vhost also admits, and the frozen set holds, `POST /api/v1/operations` (B-02: the app's Operation create answered 404 at the edge), a method-scoped admission that keeps `GET` on the collection refused.

| Domain | Controllers | Operations | Writes | App-frozen | First segments |
| --- | ---: | ---: | ---: | ---: | --- |
| catalogue | 19 | 90 | 45 | 14 | 18 (listed below) |
| bank | 9 | 65 | 40 | 49 | `bank`, `org-units` |
| identity | 14 | 59 | 24 | 19 | `admin`, `me`, `terms`, `users` |
| mission | 2 | 53 | 43 | 38 | `finance-entries`, `missions` |
| orgunit | 8 | 40 | 30 | 1 | `kommando-groups`, `org-hierarchy`, `org-units`, `special-commands`, `squadrons` |
| joborder | 4 | 39 | 23 | 26 | `orders` |
| exchange | 11 | 35 | 21 | 0 | `admin`, `connected-apps`, `exchange` |
| promotion | 6 | 34 | 14 | 2 | `promotion` |
| inventory | 1 | 27 | 15 | 21 | `inventory` |
| blueprint | 6 | 25 | 16 | 12 | `admin`, `blueprints`, `personal-blueprints` |
| materialexchange | 2 | 21 | 13 | 16 | `material-exchange`, `material-requests` |
| hangar | 1 | 15 | 11 | 7 | `hangar` |
| refinery | 2 | 14 | 8 | 5 | `refinery-orders` |
| notification | 2 | 13 | 7 | 7 | `notification-rules`, `notifications` |
| operation | 1 | 12 | 4 | 7 | `operations` |
| personalinventory | 2 | 9 | 6 | 5 | `admin`, `personal-inventory` |
| admin-system | 4 | 7 | 2 | 2 | `app`, `settings`, `system`, and `/api/v2/system` |
| orgchart | 1 | 5 | 4 | 0 | `org-chart` |
| dashboard | 1 | 4 | 2 | 1 | `announcement` |
| audit | 1 | 4 | 1 | 0 | `audit` |
| livesync | 1 | 2 | 1 | 2 | `live-sync` |
| leadership | 1 | 1 | 0 | 0 | `leitung` |
| **Total** | **99** | **574** | **330** | **234** | 52 under `/api/v1` |

*Operations* counts mappings, `/error` and the SPI endpoint included; *Writes* counts every mapping
that is not a `GET`, the verb-less `/error` included; *App-frozen* counts verb+path pairs of the
frozen set. Each controller belongs to one domain; `leadership` is the Leitung view, which the
target folds into the org chart. The catalogue's first segments are `admin`, `cities`,
`frequency-types`, `job-types`, `locations`, `manufacturers`, `material-categories`,
`material-external-aliases`, `materials`, `outposts`, `pois`, `refining-methods`, `ship-types`,
`space-stations`, `star-systems`, `sync-reports`, `terminals` and `uex`.

## Target cut per domain

Each subsection gives the moves as a table, then the consequences, pros and cons, the risks with
the guard that catches each, and the effort. Counts in parentheses belong to another domain's table
and are not added twice. *Probe* counts are occurrences of the family prefix in the nightly edge
probe `.github/workflows/edge-deny-probe.yml`, *E2E* counts occurrences in `frontend/src/e2e`, and
*allow-list lines* are lines of `docker/edge/include/api-allowlist.conf`, which the generated include
of G-08 replaces.

### Identity

Identity keeps `/users`, `/me` and `/terms` for identity and hands the data of five other domains
and its admin sub-trees to their owners.

| Current | Target | Operations | App-frozen |
| --- | --- | ---: | ---: |
| `GET`, `PUT /users/me/payout-preference` | `/missions/me/payout-preference` | 2 | 2 |
| `GET`, `PUT /users/me/blueprint-sharing` | `/blueprints/me/sharing` | 2 | 2 |
| `PUT /users/me/read-announcement/{id}` | `PUT /announcements/{id}/read` | 1 | 1 |
| `GET /users/search-bank`, `GET /users/search-bank/references` | `/bank/members/search`, `/bank/members/search/references` | 2 | 1 |
| `GET /users/me/memberships`, `/users/me/pickable-org-units`, `/users/me/org-unit-ids` | `/org-units/me/memberships`, `/org-units/me/pickable`, `/org-units/me/ids` | 3 | 1 |
| `GET`, `PATCH /users/{id}/memberships`, `GET /users/{id}/memberships/detail` | `/org-units/members/{id}/memberships`, `…/detail` | 3 | 1 |
| `GET /me/active-org-unit`, `GET /me/org-units` | `/org-units/me/active`, `/org-units/me/switchable` | 2 | 2 |
| `/admin/registrations/**`, `/admin/deletion-requests/**`, `/admin/person-search`, `/admin/users/{id}/export`, `…/export/pdf` | `/users/admin/…` | 12 | 0 |
| `/admin/roles/**` | `/roles/**` (`ADMIN`) | 3 | 0 |
| `GET /admin/terms`, `GET /admin/terms/pending-count` | `/terms/admin`, `/terms/admin/pending-count` — only once G-07 and G-08 are in place (risks below) | 2 | 0 |
| `PUT /admin/users/{id}/attributes` | deleted: a duplicate of `PUT /users/{id}/attributes` without a caller in the frontend, E2E or the app | 1 | 0 |
| `/me/capabilities`, `/me/layout`, `/users/me`, `/users/me/registration-status`, `…/rsi-handle`, `…/description`, `…/export`, `…/deletion-request`, `/terms/*` | stay | — | — |
| **Moved or deleted** | | **33** | **10** |

**Consequences.**

- **App:** 10 app-frozen operations. v0.3.1 names them in `MemberPreferencesRepository`,
  `MissionRepository`, `AnnouncementRepository`, `BankStaffRepository`, `IdentityRepository`,
  `OrgUnitRepository`, `OrgUnitViewModel` and the membership pickers of `InventoryRepository` and
  `JobOrderRepository`.
- **Edge:** allow-list lines 3, 6, 95, 110, 120, 121 and 123. Line 3 is the `^/api/v1/me/` prefix
  rule, which the generated include replaces with exact rules. Probe: `users` 11, `me` 2.
- **E2E and web:** `users` 19 E2E occurrences; in the frontend `users` 64 literals in 19 classes,
  `me` 2, `admin` 28 in 11 classes.
- **Backend:** no move loses a role requirement — every moved operation's own annotation already
  equals the URL rule it leaves (for example `GET /users/search-bank` carries
  `hasAnyRole('ADMIN', 'OFFICER', 'KRT_MEMBER', 'BANK_EMPLOYEE')`, and `PATCH …/memberships` and
  `…/detail` carry `hasRole('ADMIN')`, which today also comes from the `/api/v1/users/**` catch-all
  at `SecurityConfig.java:405-406`). The `/users` URL rules (`:378-406`) either follow the paths to
  `/bank/members/**` and `/org-units/members/**` or go; the authorization matrix shows the change.
  `NoStoreApiScopes` gains `/missions/me/**`, `/blueprints/me/**`, `/org-units/me/**`,
  `/org-units/members/**` and `/announcements/*/read` in the same change, or 10 paths and 13
  operations fall to a storable cache directive. `PendingApprovalAccessFilter` keeps
  `/users/me/registration-status`, which does not move.
- **Storage (decided as D-17 of the plan):** `defaultPayoutPreference` and `shareBlueprintsGlobally` are
  columns of the one `User` row and share its `@Version`. Moving only the paths keeps a
  cross-domain lock and writes through identity's module API. Moving the columns into mission- and
  blueprint-owned tables with their own versions is the real separation and the finer lock the
  project's locking rule asks for, at the cost of a Flyway migration with backfill; if these writes
  are audited today (to be checked), their audit events follow the new owner (REQ-AUDIT-001). The
  plan recommends the column move, in the owning domain's wave; it changes no path, so it may follow
  the path move.

**Pros** identity becomes identity, each domain owns its member settings, and `/users` stops mixing
six domains. **Cons** 10 app operations in one wave; two storage options with different cost.
**Risks → guards** a storable cache directive on member data → G-07; a lost URL rule on a moved
path → G-02; `/terms/admin` would be admitted on the public vhost by the prefix rule
`^/api/v1/terms/` (allow-list line 2) and exempted from the terms gate by the prefix exemption
`/api/v1/terms/**` (`backend/src/main/…/config/TermsAcceptanceAccessFilter.java:83-88`) → G-08 and
G-07 first; `/roles/**` leaves every admin URL rule, so its class-level `hasRole('ADMIN')` becomes
its only gate → G-02 shows it. **Effort** M with the path-only option, L with the column move.

### Org units

| Current | Target | Operations | App-frozen |
| --- | --- | ---: | ---: |
| `GET /org-hierarchy/org-units`, `PATCH /org-hierarchy/org-units/{id}/parent` | `/org-units`, `/org-units/{id}/parent` | 2 | 0 |
| `/org-hierarchy/bereiche/**`, `/org-hierarchy/organisationsleitung/**` | `/org-units/bereiche/**`, `/org-units/organisationsleitung/**` | 10 | 0 |
| `GET /leitung/view` | `GET /org-chart/leadership` (the org chart module) | 1 | 0 |
| `PUT`, `DELETE /kommando-groups/{groupId}` | optional: `/squadrons/{squadronId}/kommando-groups/{groupId}`, beside today's `GET` and `POST` there; the gate changes from `#groupId` to the squadron path (not measured) | 2 | 0 |
| `/org-units/bank/**` | leaves for the bank | (29) | (24) |
| memberships and org-unit context under `/users` and `/me` | arrive at `/org-units/me/**` and `/org-units/members/**` (identity) | (8) | (4) |
| **Moved, measured** | | **13** | **0** |

**Consequences.** **App:** none for the hierarchy fold. **Edge:** none. **E2E and web:**
`org-hierarchy` 7, `squadrons` 6 and `special-commands` 8 E2E occurrences; in the frontend
`org-hierarchy` 10 literals in 2 classes and `leitung` 1. **Backend:** no `NoStoreApiScopes` change —
`/org-units/**` is not a no-store family and the moved hierarchy reads were not either; the
`OrgRoleManagementSecurityService` gates are keyed on ids and stay.

**Pros** tenancy administration in one family; the Leitung view gets an English name in its own
module. **Cons** the kommando-group variant needs the squadron id on the client. **Risks → guards**
an id-keyed gate that changes meaning under the path-scoped variant → G-02, G-04. **Effort** S.

### Bank

| Current | Target | Operations | App-frozen |
| --- | --- | ---: | ---: |
| `/org-units/bank/**` (the member and org-unit view) | `/bank/org-units/**` | 29 | 24 |
| `PATCH /bank/accounts/{id}/balance-target` | `PUT` with the request shape of its org-unit twin; the two audiences stay two paths (not measured) | 1 | 0 |
| `GET /users/search-bank`, `…/references` | `/bank/members/search`, `…/references` (identity) | (2) | (1) |
| **Moved, measured** | | **29** | **24** |

**Consequences.**

- **App:** 24 app-frozen operations in `BankRepository` and `BankStaffRepository` — the largest
  single app change of the cut.
- **Edge:** allow-list lines 16–19, 48–50, 89–94 and 115 (14 lines). Probe: `bank` 32,
  `org-units` 13.
- **E2E and web:** `bank` 43 and `org-units` 16 E2E occurrences; in the frontend `bank` 48 literals
  in 10 classes and `org-units` 36 in 6.
- **Backend:** `NoStoreApiScopes` already lists `/api/v1/bank/**`, so the moved reads stay
  `no-store` and the `/api/v1/org-units/bank/**` entry goes
  (`backend/src/main/…/filter/NoStoreApiScopes.java:42-43`). No URL rule covers
  `/org-units/bank/**` today, so none is lost; its 29 mappings carry only
  `isAuthenticated()` in their annotations and are decided imperatively by the one seam class
  `OrgUnitBankAccessService`. The seam rules `bankClassesMustNotConsultOrgUnitScope` and
  `orgUnitAwareBankSeamIsContainedToOneClass` (ADR-0020, ADR-0028;
  `backend/src/test/…/ArchitectureTest.java:1794`, `:1853`) are keyed on names; the controller move
  must not touch the seam class, and G-01 re-keys both rules first.

**Pros** one family for the edge, the cache policy, the tag and the monitoring of the ledger.
**Cons** 24 app operations. **Risks → guards** the two audiences carry different redaction
(REQ-BANK-054 hides the employee's `staffNote` from members), so they keep separate controllers and
DTOs and only the prefix moves → G-02 identical apart from paths, the bank's redaction tests; a
seam rule passing vacuously → G-01. **Effort** M.

### Mission

| Current | Target | Operations | App-frozen |
| --- | --- | ---: | ---: |
| `POST /finance-entries`, `PUT`, `DELETE /finance-entries/{entryId}` | `/missions/{missionId}/finance-entries`, `…/{entryId}` | 3 | 3 |
| 28 operations ending in `/slim`, all in `MissionController` | the same paths without `/slim` | 28 | 24 |
| `GET /missions/search` | `GET /missions` with typed filters and `q` | 1 | 1 |
| `POST /missions/{id}/participants/add` | deleted: the twin of `POST /missions/{id}/participants/slim`; its web caller (`frontend/src/main/…/controller/MissionWriteController.java:199`) and the E2E seeder move to the survivor | 1 | 0 |
| `GET /inventory/mission/{missionId}`, `GET /refinery-orders/mission/{missionId}` | `GET /inventory/allocations?missionId=`, `GET /refinery-orders?missionId=` — filters on the owners' collections | 2 | 0 |
| `PUT /missions/{id}`, the legacy full replace (`backend/src/main/…/controller/MissionController.java:314`) | deleted: no caller in the frontend, E2E or the app, and the one mission write that force-increments the row `@Version` (not measured) | 1 | 0 |
| `GET /missions/{id}/unit-ship-options` | stays, a mission use case served through the hangar module API | — | — |
| **Moved or deleted, measured** | | **35** | **28** |

**Consequences.**

- **App:** 28 app-frozen operations in `MissionRepository` and `MissionTimelineRepository`. v0.3.1
  also sends `PUT /missions/{id}/participants/{participantId}/slim`, which the edge admits but the
  frozen set does not list.
- **Edge:** allow-list lines 7, 11–14, 116 and 143–153. Probe: `missions` 30, `finance-entries` 1.
- **E2E and web:** `missions` 13 E2E occurrences; in the frontend `missions` 67 literals in
  7 classes and `finance-entries` 6.
- **Backend:** the rate-limit rule `finance-entry-create` (`POST /api/v1/finance-entries`, 200 per
  minute, `backend/src/main/resources/application.yml:213-219`) is re-keyed to the nested path, or
  the tighter budget silently stops applying; `participant-mutations` (`:220-227`) still matches
  without `/slim`. `NoStoreApiScopes` already covers `/missions/*/finance-entries/**`; the
  `/finance-entries/**` entry goes. The finance gate `@missionSecurityService.canCreateFinanceEntry`
  reads the mission id from the body today
  (`backend/src/main/…/controller/MissionFinanceEntryController.java:152`) and can read it from the
  path — a stronger declarative gate.

**Pros** the ledger is visibly a mission sub-resource; 28 path suffixes and two dead writes
disappear, among them the one coarse-lock write path. **Cons** the largest app change after the
bank. **Risks → guards** `peerReadableMissionEndpointsMustRedactPii` (floor ≥ 10) must still select
the renamed methods → G-01; the folded `GET /missions` must keep the list's scope rules with and
without filters (REQ-MISSION-008) → the differential verdict test of plan §5.4; an entry edit
reached through another mission's path → the gate stays on the entry (*Security deltas*, item 7);
the frozen query parameter `query` of `/missions/search` changes → a ledger line (G-23).
**Effort** M. **Prerequisites** REQ-MISSION-020 and the ADR-0170 amendment name the `by-id/slim`
path.

### Job orders

| Current | Target | Operations | App-frozen |
| --- | --- | ---: | ---: |
| `GET /orders/item-catalog` (`search`, `GameItemReferenceDto`), `GET /inventory/item-catalog` (`q`, `InventoryGameItemReferenceDto`) | `GET /game-items`: one catalogue, one DTO, `q` | 2 | 1 |
| `GET /orders/item-catalog/{gameItemId}/blueprints` | `GET /game-items/{gameItemId}/blueprints` | 1 | 1 |
| `GET /orders/item-catalog/blueprints/{id}/derivation` | `GET /blueprints/{id}/derivation` | 1 | 0 |
| `GET /orders/{id}/inventory/orphaned`, `DELETE /orders/{jobOrderId}/inventory/{inventoryItemId}/unlink` | `GET /orders/{id}/allocations/orphaned`, `DELETE /orders/{id}/allocations/{inventoryItemId}` | 2 | 1 |
| claims, material collection, item stock, handovers, production | stay under `/orders/{id}/…` | — | — |
| **Moved** | | **6** | **3** |

`GET /orders/{id}/item-blueprint-owners` and `PATCH /orders/{id}/blueprint-variant-counting`, which
the foreign-domain count assigns to blueprints, are not moved by the measured cut.

**Consequences.** **App:** 3 app-frozen operations in `JobOrderRepository` and
`JobOrderWorkRepository`. **Edge:** allow-list lines 41, 42 and 118; probe `orders` 21, of which
lines 110–111 name the item catalogue. **E2E and web:** `orders` 42 E2E occurrences; in the
frontend `orders` 57 literals in 10 classes. **Backend:** `GET /inventory/item-catalog` is one of the
15 operations whose only role gate is a URL rule —
`hasAnyRole(ADMIN, OFFICER, LOGISTICIAN, KRT_MEMBER)` on `/api/v1/inventory/**`
(`SecurityConfig.java:427-428`); moved to `/game-items` it would drop that requirement. It also
leaves the `/inventory/**` no-store family.

**Pros** the order screen reads catalogue data from the catalogue; one game-item catalogue instead of
two. **Cons** two DTOs merge, and the field union is still to be decided. **Risks → guards** the lost
role requirement → G-03 lifts it into the annotation before the move; the cache class of the new
catalogue read is decided explicitly (D-18). **Effort** S.

### Catalogue

The catalogue keeps its 18 first segments, gains the game-item catalogue (job orders) and the
refinery yields, and takes its admin trees out of `/admin`.

| Current | Target | Operations | App-frozen |
| --- | --- | ---: | ---: |
| the game-item catalogues of job orders and inventory | `/game-items` (job orders) | (3) | (2) |
| `GET /refinery-orders/locations/{locationId}/yields` | `GET /locations/{locationId}/refinery-yields` | 1 | 0 |
| `/admin/import/p4k/**` | `/catalog/admin/import/p4k/**` | 4 | 0 |
| `GET`, `DELETE /sync-reports` | `/catalog/admin/sync-reports` (not measured) | 2 | 0 |
| **Moved, measured** | | **5** | **0** |

**Option, advised against for now:** one root `/api/v1/catalog/**` for all 18 families would give
one read-only edge family, one cache policy and one tag, but costs 14 app-frozen operations
(`/materials/search`, `/locations/search`, `/ship-types`, `/job-types`, `/terminals`,
`/refining-methods`, …) and about 100 frontend literals for naming alone.

**Consequences.** **App:** none. **Edge:** none. **Backend:** the yields leave the
`/refinery-orders/**` no-store family for the `no-cache, must-revalidate` default — catalogue data,
acceptable, but decided explicitly (D-18). The P4K and sync-report trees come under the
`/api/v1/*/admin/**` matcher and keep their `hasRole('ADMIN')` gates. When the catalogue is cut, its
eight dual-use DTOs (`FrequencyType`, `JobType`, `Location`, `MaterialCategory`, `Material`,
`RefiningMethod`, `StarSystem`, `Terminal`) get request records, and its nine write bodies without
`@Valid` gain it (G-06).

**Pros** one game-item catalogue; the catalogue's administration in its own tree. **Cons** about 13
new request records for catalogue writes. **Risks → guards** a generic CRUD base creeping in with
the request records (P8) → review and the audit contract per command; a read field turning writable
→ G-06. **Effort** S without the common root.

### Blueprints and personal inventory

| Current | Target | Operations | App-frozen |
| --- | --- | ---: | ---: |
| `/admin/personal-blueprints/**` | `/personal-blueprints/admin/**` | 8 | 0 |
| `/admin/default-blueprints/**` | `/blueprints/admin/defaults/**` | 3 | 0 |
| `/admin/personal-inventory/**` | `/personal-inventory/admin/**` | 4 | 0 |
| `GET`, `PUT /users/me/blueprint-sharing` | `/blueprints/me/sharing` (identity) | (2) | (2) |
| `GET /orders/item-catalog/blueprints/{id}/derivation` | `/blueprints/{id}/derivation` (job orders) | (1) | (0) |
| `/personal-blueprints`, `/blueprints`, `/personal-inventory` | stay: member-owned rows and the catalogue are different resources | — | — |
| **Moved** | | **15** | **0** |

**Consequences.** **App:** none for the admin moves. **Web:** part of the `admin` family (28
literals in 11 classes) and of `personal-blueprints` (14 in 3). **Backend:** eight admin paths move
up to `no-store` (personal inventory 2, personal blueprints 6); `/blueprints/**` is not a no-store
family, so the default-blueprint admin keeps the revalidate default. The new sub-trees come under
the `/api/v1/*/admin/**` matcher, and all three controllers keep their class-level
`hasRole('ADMIN')`. `BlueprintImportPreviewDto` is also the staged answer of the exchange's
blueprint draft route; it changes only together with the frontend that reads the staged handoff.

**Pros** each admin tree sits with its domain. **Cons** a behaviour change in caching, in the safe
direction. **Risks → guards** the admin matcher must precede `/api/v1/personal-inventory/**`
(`SecurityConfig.java:429-430`, `authenticated()`), or that rule decides first for the new admin
sub-tree; the class gate still holds → G-02 shows the matching rule. **Effort** S.

### Materialbörse

| Current | Target | Operations | App-frozen |
| --- | --- | ---: | ---: |
| `/material-requests/**` | `/material-exchange/requests/**` | 9 | 7 |
| `/material-exchange/offers/**` | stays | — | — |
| `POST /material-exchange/items/{inventoryItemId}/deactivate` | optional: `POST /material-exchange/offers/deactivate?inventoryItemId=` (not measured) | 1 | 0 |
| **Moved, measured** | | **9** | **7** |

**Consequences.** **App:** 7 app-frozen operations in `MaterialBoardRepository`. **Edge:** allow-list
lines 64–65, 162 and 164; probe `material-requests` 2, `material-exchange` 3. **Web:**
`material-requests` 9 literals in 1 class, `material-exchange` 12 in 2. **Backend:** caching is
unchanged — both roots are deliberately outside the no-store list as an org-wide shared board
(REQ-SEC-031).

**Pros** one family and one tag for the board. **Cons** an app change for naming alone. **Risks →
guards** none beyond the wave checklist. **Effort** S.

### Hangar

| Current | Target | Operations | App-frozen |
| --- | --- | ---: | ---: |
| `/hangar/users/{userId}/ships/**`, four operations with `hasRole('ADMIN')` | `/hangar/admin/users/{userId}/ships/**` | 4 | 0 |
| `POST /hangar/import/fleetview`, deprecated | deleted; the new app uses `POST /hangar/import/ships` | 1 | 1 |
| **Moved or deleted** | | **5** | **1** |

**Consequences.** **App:** `HangarRepository` drops the fleetview import. The operation's announced
sunset date no longer matters: under the hard cut it leaves in this wave through a ledger line.
**Edge:** allow-list line 170 goes; probe lines 247 (the fleetview import) and 249 (the 404 probe of
`/hangar/users/…`) change. **Web:** `hangar` 15 literals in 4 classes. **Backend:** the
`authenticated()` rule for the fleetview path (`SecurityConfig.java:417-418`) goes; `NoStoreApiScopes`
keeps covering `/hangar/**`.

**Pros** the admin tree follows P2; the only deprecated app operation disappears. **Cons** none
material. **Risks → guards** the domain rule `/api/v1/hangar/**` (`:419-423`, `HANGAR_READ`,
`HANGAR_WRITE` or `ADMIN`) comes before today's admin rule and would decide first for
`/hangar/admin/**` unless the new matcher precedes it; the method gates are `hasRole('ADMIN')`, so
nothing widens → G-02. The two hangar operations whose only role gate is a URL rule
(`GET /hangar/squadron-overview`, `POST /hangar/ships/home-location`) do not move, and G-03 lifts
them anyway. **Effort** S.

### Refinery

The refinery keeps its root and loses the second way to act for another member.

| Current | Target | Operations | App-frozen |
| --- | --- | ---: | ---: |
| `POST /refinery-orders` binds the full read DTO `RefineryOrderDto`; an `owner` in the body is honoured behind `canManageUserRefineryOrders` | a request record without `id`, `owner`, `profit` and `owningSquadron`; `status` restricted to `OPEN` or `IN_PROGRESS` (not measured) | 1 | 1 |
| `PUT /refinery-orders/{id}` binds `RefineryOrderDto`; a logistician may reassign the owner through the body | a request record without owner and without `status`; the status moves only through `/store` (not measured) | 1 | 0 |
| `/refinery-orders/users/{userId}/**` | stays: its per-target gate reads `#userId` from the path, the stronger form | — | — |
| `GET /refinery-orders/mission/{missionId}`, `GET /refinery-orders/locations/{locationId}/yields` | mission and catalogue | (2) | (0) |

**Consequences.** **App:** `POST /refinery-orders` keeps its frozen request fields (`goods`,
`location`); the app sends `status = IN_PROGRESS` on create, which stays valid, and no `owner`. It
echoes the current `status` on edit (`RefineryRepository`), which the wave's app release stops.
v0.3.1 also sends `PUT` and `DELETE /refinery-orders/{id}`, which the edge admits but the frozen set
does not list. **Web:** `refinery-orders` 16 literals in 5 classes; 27 E2E occurrences.
**Backend:** the imperative owner override and logistician reassignment in
`backend/src/main/…/controller/RefineryOrderController.java:171-220` go, so each case has one
declarative path. The `status` restriction is a Phase −1 fix (plan §7.1) and may land before the
wave.

**Pros** one gate per case, readable in the annotation; no read field is writable any more.
**Cons** the web's "create for another member" moves to the per-target path. **Risks → guards** the
exchange's refinery draft route binds `RefineryExtractDto`, the request DTO of the web import
`POST /refinery-orders/import-extract`, so a change to it would alter frozen external behaviour →
the draft route gets its own request DTO first (G-18); a new request record re-adding a
server-managed field → G-06. Security effect: strictly narrowing. **Effort** S.

### Notification, dashboard, settings, system

| Current | Target | Operations | App-frozen |
| --- | --- | ---: | ---: |
| `/notification-rules/**`, five operations with `hasRole('ADMIN')` | `/notifications/admin/rules/**` | 5 | 0 |
| `GET`, `PUT`, `DELETE /announcement`, `GET /announcement/admin` | optional, naming only: `/announcements/current`, `/announcements/current/admin` | 4 | 1 |
| the app's `GET /settings/{key}` read of `job_order.age_yellow_days` and `job_order.age_red_days` | `GET /orders/settings` (`ageYellowDays`, `ageRedDays`), likewise `GET /refinery-orders/settings` (`roundingMode`); the fee rate already has `/bank/transfer-fee-rate`; the generic `/settings/{key}` stays as the admin store behind them (not measured) | 1 | 1 |
| `GET /api/v1/system/ping`, `GET /api/v2/system/ping` | deleted: a demonstration pair without a caller, and the only `/api/v2` operation | 2 | 0 |
| **Moved or deleted, measured** | | **11** | **1** |

**Consequences.** **App:** the announcement read (`AnnouncementRepository`) keeps its `204` semantics
under the new path (REQ-API-009) and changes allow-list line 32; the typed settings replace the
app's frozen settings read (`JobOrderRepository`) and the two exact edge rules on lines 172–173.
**Backend:** the notification rules move up to `no-store`, because `/notifications/**` is a no-store
family. With the pings and the fleetview import gone, no operation is deprecated and
`@ApiDeprecation` and `DeprecationInterceptor` have no user left.

**Pros** typed, validated, per-domain settings; one announcement resource. **Cons** two app reads
change. **Risks → guards** none beyond the wave checklist. **Effort** S.

## What does not move

- **The T0 tier:** `GET /api/v1/app/version-policy`, `POST /internal/discord/account-existence`,
  `/api/v1/exchange/**` with `ActingMemberFilter`'s exact patterns, the two streams and
  `POST /api/v1/live-sync/changed`.
- **`/api/v1/terms/*`**: `GET /terms/document` is one of the four `permitAll` methods, and
  `GET /terms/status` is the target of the blackbox probe
  (`monitoring/prometheus/prometheus.yml:136`, `:338`, `:384`).
- **`/api/v1/audit/**`**: the cross-cutting audit viewer behind its own `ADMIN` URL rule
  (`SecurityConfig.java:437-438`).
- **`/api/v1/connected-apps/**`**: the member's web-only control of external clients.
  `theExchangeStaysOffTheApiVhost` probes it, and its mass-change confirm posts the frozen
  change-set JSON, so its bodies stay as they are.
- **Identity's own rows** listed in the identity table, and `GET /missions/{id}/unit-ship-options`.
- **Roots the cut does not touch**: `/inventory` apart from the two moves above, `/operations`,
  `/promotion`, `/org-chart`, `/orders`, `/missions`, `/bank`, `/hangar`, `/refinery-orders`,
  `/material-exchange`, `/notifications`, `/personal-inventory`, `/personal-blueprints`,
  `/blueprints` and the catalogue's 18 segments. `/operations/search`, `/users/search` and
  `/materials/search` are twins of their collection `GET` like `/missions/search` and fold the same
  way under P5 when their domain is next cut; they are not in the measured set, and the materials
  one is app-frozen.
- **The exchange administration** (decided 2026-10-01, D-12 of the plan). 14 web-only operations
  under `/api/v1/admin/exchange-clients/**`, `/api/v1/admin/exchange-undo-runs/**` and
  `/api/v1/admin/exchange-settings` are not part of the measured cut. Their P2 home cannot be
  `/api/v1/exchange/admin/**`, which is the frozen relay prefix; they move to
  `/api/v1/connected-apps/admin/**` in wave 1, and until then `/api/v1/admin/**` stays as a URL
  rule beside `/api/v1/*/admin/**`.

## Security deltas and guards

### Measured blast radius

The measured cut — every move above not marked *not measured*, 43 move rules — touches **161
operations, 74 of them app-frozen**. *Revalidate* is the storable default
`no-cache, must-revalidate`; `no-store` is `private, no-store` (REQ-SEC-031).

| Move | Operations | App-frozen | Allow-list lines | Security delta |
| --- | ---: | ---: | --- | --- |
| identity → mission: payout preference | 2 | 2 | 120 | `no-store` → revalidate |
| identity → blueprint: sharing | 2 | 2 | 121 | `no-store` → revalidate |
| identity → dashboard: read announcement | 1 | 1 | 123 | `no-store` → revalidate |
| identity → bank: member search | 2 | 1 | 110 | stays `no-store` (bank family) |
| identity → orgunit: memberships and org-unit context | 8 | 4 | 3, 6, 95 | 7 paths `no-store` → revalidate; line 3 is a prefix rule |
| identity admin → `/users/admin`, `/roles` | 15 | 0 | — | 12 paths revalidate → `no-store` |
| identity admin terms → `/terms/admin` | 2 | 0 | — | **newly admitted on the public vhost** by line 2 |
| duplicate attributes write deleted | 1 | 0 | — | — |
| bank: `/org-units/bank` → `/bank/org-units` | 29 | 24 | 16–19, 48–50, 89–94, 115 | stays `no-store` |
| orgunit: hierarchy fold | 12 | 0 | — | — |
| mission: finance ledger nested | 3 | 3 | 13, 14 | rate rule `finance-entry-create` lost unless re-keyed |
| mission: `/slim` dropped | 28 | 24 | 11, 12, 116, 143–153 | `participant-mutations` still matches |
| mission: search folded, legacy add deleted | 2 | 1 | 7 | — |
| inventory and refinery mission views → filters | 2 | 0 | — | — |
| refinery yields → locations | 1 | 0 | — | `no-store` → revalidate |
| item catalogues → `/game-items`, with blueprints and derivation | 4 | 2 | 41, 42 | inventory item catalogue `no-store` → revalidate; URL-only role gate lost |
| job-order allocations named as such | 2 | 1 | 118 | — |
| `/material-requests` → `/material-exchange/requests` | 9 | 7 | 64, 65, 162, 164 | — |
| hangar admin sub-tree, fleetview import deleted | 5 | 1 | 170 | — |
| notification rules → `/notifications/admin/rules` | 5 | 0 | — | revalidate → `no-store` |
| `/announcement` → `/announcements/current` | 4 | 1 | 32 | — |
| `/leitung/view` → `/org-chart/leadership` | 1 | 0 | — | — |
| ping v1 and v2 deleted | 2 | 0 | — | — |
| P4K, personal-inventory and blueprint admin sub-trees | 19 | 0 | — | 8 paths revalidate → `no-store` |
| **Total** | **161** | **74** | | |

### The deltas that matter

Items 1–5 come from the measurement; items 6 and 7 follow from reading `SecurityConfig` and the
gates of the moved operations.

1. **Cache downgrade: 12 paths, 15 operations.** They would fall from `private, no-store` to
   revalidate unless `NoStoreApiScopes` (14 families, `NoStoreApiScopes.java:42-55`) gains their
   new roots in the same change:

   | Today | Target | Operations |
   | --- | --- | ---: |
   | `/users/me/payout-preference` | `/missions/me/payout-preference` | 2 |
   | `/users/me/blueprint-sharing` | `/blueprints/me/sharing` | 2 |
   | `/users/me/read-announcement/{id}` | `/announcements/{id}/read` | 1 |
   | `/users/me/memberships` | `/org-units/me/memberships` | 1 |
   | `/users/me/pickable-org-units` | `/org-units/me/pickable` | 1 |
   | `/users/me/org-unit-ids` | `/org-units/me/ids` | 1 |
   | `/users/{id}/memberships` | `/org-units/members/{id}/memberships` | 2 |
   | `/users/{id}/memberships/detail` | `/org-units/members/{id}/memberships/detail` | 1 |
   | `/me/active-org-unit` | `/org-units/me/active` | 1 |
   | `/me/org-units` | `/org-units/me/switchable` | 1 |
   | `/refinery-orders/locations/{id}/yields` | `/locations/{id}/refinery-yields` | 1 |
   | `/inventory/item-catalog` | `/game-items` | 1 |

   The first ten are member data and must stay `no-store`; the last two are catalogue data, decided
   explicitly (D-18). Guard: G-07, a runtime test that every `GET` of a no-store family answers
   `private, no-store`.
2. **Cache upgrade: 22 paths** move up to `no-store` — 12 identity admin paths, 8 blueprint and
   personal-inventory admin paths, 2 notification-rule paths. The safe direction, but a behaviour
   change the wave names.
3. **Public exposure: 1 move.** `/admin/terms` → `/terms/admin` would be admitted on the public
   vhost by the prefix rule `^/api/v1/terms/` (allow-list line 2), and the terms filter's prefix
   exemption would exempt it from the terms gate. It must not ship before G-08 (a generated,
   verb-aware include without prefix rules) and G-07 (exemptions pinned to exact sets).
4. **Lost rate limit: 1 rule.** `finance-entry-create` (`POST /api/v1/finance-entries`) stops
   applying when the ledger is nested, unless the rule is re-keyed in the same change. Guard: G-07,
   every rate-limit rule matches at least one documented operation.
5. **URL-rule-only gates: 2 of 15 in the moved set.** `/inventory/item-catalog` → `/game-items`
   would lose its only role requirement; `/inventory/mission/{id}` →
   `/inventory/allocations?missionId=` keeps it, because it stays under `/api/v1/inventory/**`.
   The role hierarchy (`SecurityConfig.java:208-218`) does not make bank roles or `MISSION_MANAGER`
   imply `KRT_MEMBER`, so an account holding only `BANK_EMPLOYEE` is stopped by that URL rule
   alone. Guard: G-03 lifts all 15 into their annotations before anything moves.
6. **Admin fence order.** `SecurityConfig` decides by the first matching rule, and the domain rules
   `/api/v1/hangar/**` (`:419`) and `/api/v1/personal-inventory/**` (`:429`, `authenticated()`)
   come before today's `/api/v1/admin/**` rule (`:433`). A `/api/v1/*/admin/**` matcher placed
   where the admin rule sits now would never decide for `/hangar/admin/**` or
   `/personal-inventory/admin/**`. Nothing widens — all 51 operations under `/api/v1/admin/**`
   carry `hasRole('ADMIN')` in their own annotations — but the URL fence would be void there; the
   matcher therefore goes before the domain rules. Today it would already cover six operations,
   `/announcement/admin` and the five `/bank/admin/**`, all `hasRole('ADMIN')` as well. Guard:
   G-02, whose matrix names the matching URL rule per operation.
7. **Nested paths carry two identifiers.** `/missions/{missionId}/finance-entries/{entryId}` and
   the optional `/squadrons/{squadronId}/kommando-groups/{groupId}` name a parent the handler did
   not read before. Today the finance-entry edit and delete are gated on the entry
   (`canEditFinanceEntry(#entryId, …)` in the service); a gate re-pointed at the path's parent
   alone would let a manager of one mission edit another mission's entry through a mismatched
   path. The gate keeps evaluating the child, and the handler answers 404 for a child of another
   parent — after the scope check, so no existence oracle appears (plan §6, red lines). Guard:
   G-02 shows a gate that switches from the child to the parent; one test per nested write.

### Invariants for every wave

Every wave's pull request states, and its reviewer confirms, that these invariants hold.

| Invariant | How a re-cut breaks it | Guard |
| --- | --- | --- |
| Every moved mapping keeps its gate | package- and name-keyed ArchUnit rules go partial; URL-only gates are lost | G-01 (rules re-keyed on `@RestController`, with selection floors), G-02 (authorization matrix), G-03 (URL-only gates lifted) |
| No new anonymous path | a moved `permitAll` method leaves the loop's package; `SecurityConfig`'s `permitAll` lines are path-keyed (`:364-377`) | G-01 (`permitAllIsDeclaredOnlyOnTheFourPublicEndpoints` re-keyed, floor 4), `OpenApiAnonymousOperationsTest`, `ApiVhostAnonymousSurfaceTest`, the probe's 200 rows |
| No path newly reachable on the public vhost | the prefix rules `^/api/v1/terms/` and `^/api/v1/me/`; admission by path, not operation | G-08 (generated verb-aware include; admitted equals frozen plus the two anonymous reads) |
| Rate limits still apply | path rules in `application.yml:197-227`; the stream paths and `EXPORT_SEGMENTS` names in `SubjectRateLimitingFilter` | G-07 (every rule and export segment matches at least one operation) |
| Sensitive reads stay `no-store` | the `NoStoreApiScopes` path list | G-07 (runtime test per no-store family) |
| Pending, terms and acting-member exemptions stay exact | exact paths in three filters, and one prefix (`/api/v1/terms/**`) | G-07 (exemptions pinned to exact sets), G-18 (`ActingMemberFilter` parity), the T0 paths kept |
| CSRF exemption unchanged (ADR-0144) | keyed on `/api/v1/**` and `/internal/**` (`SecurityConfig.java:111`) | G-07 (every non-`GET` mapping inside the exemption; no mapping outside `/api/**`, `/internal/**` and `/error`); every target stays under `/api/v1` |
| Redaction still selected | renamed mission methods leave `peerReadableMissionEndpointsMustRedactPii`'s selection | G-01 (re-keyed selection), the rule's floor of 10 |
| Admin fence holds | `/api/v1/admin/**` no longer covers moved admin trees; a domain rule decides first | the `/api/v1/*/admin/**` matcher ahead of the domain rules, class-level `hasRole('ADMIN')` kept, G-02 |
| Tenancy gate moves with the list query | a split controller escapes a name-listed scope rule | G-05 (`@TenantScoped`), the differential verdict test (plan §5.4) |
| No read field becomes writable | a dual-use DTO on a new path | G-06 |
| The exchange is unchanged | relay paths are strings in two modules | G-18 (wire-contract test, parity tests, golden answers), `theExchangeStaysOffTheApiVhost` |
| The app contract breaks only as declared | an undeclared removal or field change | G-23 (declared-break ledger, app call list) |
| Frontend calls and live-sync probes point at real operations | a call or probe names a retired path; the probe authorizer fails open on 400, 405 and 5xx for non-presence topics | G-14 |
| Frontend and backend deploy together | a mixed release answers 404 for every call of the re-cut domain | G-21 |

## The forced update

### What the wall needs

The app needs exactly one server operation before it can show the update wall, and it is T0:

| Element | Evidence |
| --- | --- |
| `GET /api/v1/app/version-policy` answers `200` with `minimumVersionCode` (int), `latestVersionCode` (int) and `releasesUrl` (https string) | `backend/src/main/…/controller/AppVersionPolicyController.java:44-75`, `AndroidClientProperties`; app `AppVersionRepository.kt:117` (the path), `:89` (a null floor is no floor), `:97` (a non-https URL falls back, `:125`), `:39` (`allows`) |
| No authentication, no required header, not refused by the pending or terms gates | `@PreAuthorize("permitAll()")` with empty `@SecurityRequirements`; `SecurityConfig.java:376-377`; `backend/src/main/…/config/PendingApprovalAccessFilter.java:85-86`; `TermsAcceptanceAccessFilter.java:88`; `ExternalContractTest.theContractRequiresNoHeader` |
| The `/api/v1` prefix for this one path, whatever else moves | the app constant `AppVersionRepository.kt:117` |
| Exact admission on the public vhost | allow-list line 53; probe `edge-deny-probe.yml:90`, `:92` |
| The ArchUnit `permitAll` allow-list entry | `ArchitectureTest.java:336` |
| Tests | `AppVersionPolicyControllerTest`, `ApiVhostAnonymousSurfaceTest`, `OpenApiAnonymousOperationsTest`, the `ExternalContractTest` entry |

Nothing else server-side comes first: `UpdateGate` is the app's outermost gate, ahead of the app
lock and the session (`UpdateGate.kt:133`), so login, terms and capabilities are not prerequisites.
While the verdict is pending the app composes its content — an unknown verdict runs the app — so its
first calls race the wall, which is harmless for showing it.

### How the floor is configured and applied

> [!note] Implemented 2026-10-03 — the floor is release-bound (REQ-API-020)
> The bullets below describe the floor as it was until then. Now the floor, the newest build and the
> release page are literals under `app.android.version-policy.release.*` in the backend's
> `application.yml` (17 / 17), baked into the image, so a promotion applies them and every rollback
> restores the previous release's values. The host keeps only an emergency override under new names
> (`APP_ANDROID_*_OVERRIDE`, empty by default, logged at `WARN`, alerted after a day); the old
> `APP_ANDROID_MINIMUM_VERSION_CODE` / `…_LATEST_VERSION_CODE` / `…_RELEASES_URL` reach no container
> and bind to nothing. Raising the floor is a change in the wave's pull request, not S8 — procedure in
> [`deployment.md` → *The Android app floor*](../deployment.md#the-android-app-floor).

- `app.android.minimum-version-code` comes from `APP_ANDROID_MINIMUM_VERSION_CODE` (default `0`,
  meaning no floor), with `…LATEST_VERSION_CODE` and `…RELEASES_URL` beside it
  (`backend/src/main/resources/application.yml:172-175`). `docker-compose.yml:213-215` and
  `quadlet/env.d/backend.env.tmpl:11-13` pass them; the live value exists only in the host `.env`,
  not in the promoted configuration bundle.
- `AndroidClientProperties` is a `@ConfigurationProperties` record read at start, so a new floor
  needs a backend restart, which through `Requires=` restarts frontend and ingest too — about one
  minute of web and app outage (`docs/EXCHANGE_GO_LIVE_RUNBOOK.md`, step S8).
- `scripts/deploy.sh` renders `env.d` from the host `.env` only inside `install_quadlet_units`
  (`:268-278`), which runs when the configuration bundle's digest changed, on `--reapply` or on a
  host without units (`:962-985`, `:1238-1303`), and on a rollback (`:352`); an unchanged tick exits
  before any render (`:1062-1068`). Even a rendering deploy restarts the backend only when its unit
  file or its digest pin changed (`:285-289`; `scripts/container-runtime.sh:279-288`). Whether every
  release carries a new configuration digest is not verified. A floor written into `.env` before a
  promotion is therefore probably, not certainly, applied by the release's recreate — and a
  health-gate rollback re-renders `env.d` from the same `.env`, so the old backend would come back
  with the raised floor and no app version would work.
- Raising the floor is runbook step S8 (`docs/EXCHANGE_GO_LIVE_RUNBOOK.md:533-559`): set both codes
  in `.env`, run `render-env-d.py`, restart `backend.service`, start `ingest` and `frontend` again
  (both `Requires=backend.service`). It is a production write and needs the owner's per-action
  approval (root `CLAUDE.md`).

**What the app does (v0.3.1).** It reads the policy once per process (a `started` flag,
`UpdateGate.kt:100`, triggered by `LaunchedEffect(Unit)` at `:156`) and fails open on any failure
(`:118`: a failed read leaves the verdict unknown, and unknown runs the app). A floor of `0` allows
every build. Below the floor it shows a non-dismissible wall with a link to `releasesUrl` and an
exit; cached data survives.

### Release sequence per wave

> [!note] Implemented 2026-10-03 — one step instead of three (REQ-API-020)
> With the release-bound floor the wave's pull request raises the committed floor and newest build to
> N+1 itself. The sequence is: publish app N+1, then promote; the promotion applies the new API, the
> floor and the retired paths' `APP_UPDATE_REQUIRED` together, so old apps meet the wall at their next
> policy read instead of after a separate S8 (steps 1 and 3 below merge, and the second outage
> minute goes). The table is the sequence while the floor still lived in `.env`.

The wave's pull request — backend, frontend, ledger lines, regenerated edge include and probe table —
is merged and released first; its images wait for promotion. As long as the floor lives only in the
host `.env`, the plan's order (§5.10) is:

| Step | Server | Old app (≤ N) | New app (N+1) |
| --- | --- | --- | --- |
| 0 — publish app N+1 | old API, floor N | works | early installers call new paths and get 404 until step 1 |
| 1 — promotion tick: backend, frontend and ingest recreated (about a minute, the edge answers 503 from its maintenance page), then the edge reconciled with the new include in the same deploy (`reconcile_edge`, `deploy.sh:1400`; a brief outage of all vhosts) | new API, new include, floor N | runs against the new API without a wall: retired paths answer 404 — from the backend until the edge is reconciled, then from the edge | works once the edge is reconciled |
| 2 — the release is verified healthy | | | |
| 3 — S8, owner-approved: floor and latest = N+1, `env.d` rendered, backend restarted (about a minute of web and app outage) | new API, floor N+1 | cold start: wall; running: 404 until the next cold start | works |
| 4 — steady state | | wall at every cold start until updated | works |

Publishing the app after the deploy is worse: old apps would be walled with nothing to install, so
step 0 comes shortly before the promotion, not days before it. Raising the floor in the same deploy
would close the window between steps 1 and 3 and save one outage minute, but it is unsafe while the
floor lives in `.env`: a health-gate rollback would restore the old backend with the raised floor
(*Rollback caveats*). The window is the price of that safety; a release-bound floor removes both
(D-11).

### Rollback caveats

> [!note] Implemented 2026-10-03 (REQ-API-020)
> A rollback onto a release that carries the release-bound floor restores that release's floor with
> its API, so the second caveat below no longer arises and there is no floor to revert first. It
> still holds for a rollback onto an older release, which reads `APP_ANDROID_MINIMUM_VERSION_CODE`
> from `.env` — the reason that line stays untouched there. The first caveat — members on N+1 broken
> by a rollback — is inherent to a hard cut and remains.

- A health-gate rollback before step 3 restores the previous release with floor N: old apps work
  again, and members who already installed N+1 are broken until the wave is re-deployed — a hard
  cut in the other direction.
- After step 3, a rollback re-renders `env.d` from the raised `.env` (`deploy.sh:352`): the old
  backend would run with floor N+1, which walls old apps while the new app finds its paths gone, so
  no app version works. The rollback runbook therefore reverts the floor first (part of D-11), and
  a defect found after step 3 is better fixed forward.
- Any deploy that renders `env.d` between an `.env` edit and its intended moment applies the floor
  early — the reason S8 edits and applies in one supervised step.

### Closing the gaps — decided as D-11

Three gaps are known: the app reads the policy once per process and fails open, so apps already
running, and apps started during a restart, run against the new API without a wall until their next
cold start; old apps meet the new API without a wall between the deploy and S8; and a rollback after
S8 keeps the raised floor. The plan's primary recommendation is a **release-bound floor**: the
minimum version as a reviewed default in the release's own configuration, so it deploys and rolls
back together with the API it protects, with the host `.env` kept only as an emergency override. It
changes REQ-API-010's "configuration, not a deploy" wording. The further options:

| Option | What it does | Security note |
| --- | --- | --- |
| (a) app | re-reads the policy on foreground resume and after an unexpected 404 or `NOT_FOUND` on a known path; must ship in a release before the first T1 wave to help it | none |
| (b) edge | serves a static policy file, rendered from the same `.env` value at `reconcile_edge`, while the backend answers 502, 503 or 504, so the wall works during the restart | a second producer of an anonymous answer: it carries no more than the three values and stays exact-path |
| (c) server | retired paths answer one stable problem `APP_UPDATE_REQUIRED`, which later app versions map to the wall; helps from the second T1 wave on | answers only paths that were admitted before; the generated include then admits the ledger's retired paths as well, and G-08's equality counts them |
| (d) operations | cut at low usage and announce the wave | none |

The plan's recommendation: the release-bound floor, plus (a) before the first cut, (c) and (d); (b)
only if (a) proves insufficient; and, while the floor still lives in `.env`, the floor revert in the
rollback runbook.

> [!note] Status 2026-10-03
> **Release-bound floor and (c): implemented** (REQ-API-020). The retired-operation list is
> `backend/src/main/resources/api/retired-operations.txt`, empty today; a match answers `410` with
> `APP_UPDATE_REQUIRED` ahead of authentication, and every entry must be a declared break of the
> ledger. (c) reaches an app on the API vhost only once the generated include admits the ledger's
> retired paths (G-08); until then the edge answers those paths `404`. **(a)** is the app's
> basetool-android#209. **(d)** is an operating rule. **(b)** stays open as decided.

### Contract machinery

All of it is Phase 0.7 of the plan; the first T1 wave depends on every item, and *Wave order* names
what wave 1 already needs.

- **Requirements first.** A new ADR records the hard cut with forced update and supersedes the
  retirement clause of ADR-0136 (decision bullet 4); REQ-API-001 (breaking changes to `/api/v2`,
  retirement through `@ApiDeprecation` with a sunset), REQ-API-009 (retirement through `/api/v2`
  rather than deletion) and REQ-API-010 are amended to match. The root `CLAUDE.md` requires the
  amendment before the code diverges.
- **Declared-break ledger (G-23).** `backend/src/test/resources/api/declared-breaks.txt`: one line
  per removed or changed frozen operation or field, with the app `versionCode` that absorbs it.
  `theContractTypesMatchThePreviousRelease` accepts exactly the declared breaks and fails on
  anything else. Entries name an operation and a field, never a wildcard.
- **App call list (G-23).** Each app release publishes a machine-readable list of the calls it makes
  — verb, path, query parameters and the response fields it reads — generated from its `core:data`
  repositories and generated models (an app-side requirement, REQ-APP-API-005 in the app
  repository). The backend repository commits it per app release; `ExternalContractTest` asserts
  that the frozen set covers it, and the list of app N+1 shows that it calls nothing the ledger
  declares broken. The list comes before the generated include: v0.3.1 already calls at least ten
  admitted operations the frozen set does not list (*Findings*), and an include generated from
  today's frozen set would refuse them.
- **Generated edge include (G-08).** One nginx `map` on `"$request_method:$uri"` with an anchored
  regex per T1 operation (UUID placeholders as today), no prefix rules and no read-only family; the
  probe table generated from the same source (200 or 401 for admitted operations, 404 for a sample
  of refused ones, 405 where a sibling verb is admitted); a test that admitted equals frozen plus
  the two anonymous reads, in both directions. The generated file stays committed and reviewed
  (ADR-0135 amendment); the per-request cost of about 250 regexes is measured with `nginx -t` and a
  load probe.
- **Mandatory baseline.** The previous-release baseline is fetched with `continue-on-error: true`
  (`.github/workflows/ci.yml:34-60`) and the test `assumeTrue`s it
  (`backend/src/test/…/api/ExternalContractTest.java:2238`), so a failed fetch is a green check
  that checked nothing. On `main` a missing baseline fails; the frozen-set floor,
  `hasSizeGreaterThanOrEqualTo(5)` for 235 entries (`:2511`), ratchets to the current count.
- **Per-domain OpenAPI tags.** One ASCII tag per domain and `x-domain` on every operation, set by an
  `OperationCustomizer`, with springdoc's `autoTagClasses` off. One committed document stays — the
  app vendors it and the frontend generates its test types from it — and per-domain views are
  filtered from it at build time rather than built as springdoc groups, which duplicate shared
  schemas and drop an operation no group matches; the union of the views equals the document.
- **Unique schema names.** A test that every `components.schemas` name belongs to exactly one
  exposed Java type or an explicit `@Schema(name = …)`; `Op`, `Provenance` and `Skipped` get explicit
  names. `use-fqn` is not switched on: it would rename all 489 schemas and every generated app model.
- **Error-code registry.** Per-module `ProblemCode` enums (plan §5.5, D-09); the document's
  `ProblemDetail` gains `code`, `correlationId` and `fieldErrors`; a test that every code the
  handler, the filters and the exceptions produce is registered; the app generates its constants
  from it. `code` stays a string with a documented value list, not a required enum, or
  `theContractRequiredEnumsAreFrozen` would fail on every added code.
- **Generator assertions.** `OpenApiGeneratorTest` asserts the security scheme, the two anonymous
  operations, a per-domain operation-count floor and schema-name uniqueness before it writes the
  document.
- **The exchange fence.** `/api/v1/exchange/**` moves into its own internal OpenAPI document with its
  own staleness check (ADR-0216 amendment); G-18 adds the backend wire-contract test against the 28
  published schemas and 101 fixtures and the parity tests for every shared identifier.

## Wave order

Each T1 wave is one app release, one deploy and one floor raise; the web-only wave rides the ordinary
atomic deploy. The order follows plan §7.9 — risk and app impact — and each wave ships together with
the backend and frontend change of its domain.

| Wave | Content | Operations (app-frozen) | App release and floor |
| --- | --- | --- | --- |
| 1 — web only | admin sub-trees: identity (12 to `/users/admin`, 3 to `/roles`, 2 to `/terms/admin`, the duplicate attributes write deleted), catalogue (P4K 4; sync reports 2, not measured), personal blueprints 8, default blueprints 3, personal inventory 4, hangar 4; notification rules 5; the two pings | 48 (0) | none |
| 2 — identity and org units | the member-scoped moves out of `/users` and `/me` (15); the hierarchy fold (12); the Leitung view (1); kommando groups (optional, not measured) | 28 (10) | one release, one floor raise |
| 3 — mission | finance ledger nested (3), `/slim` dropped (28), search folded (1), legacy add deleted (1), inventory and refinery mission views as filters (2); legacy `PUT /missions/{id}` deleted (not measured) | 35 (28) | one release, one floor raise |
| 4 — bank | `/org-units/bank/**` → `/bank/org-units/**` (29); the balance-target verb (not measured) | 29 (24) | one release, one floor raise |
| 5 — job orders and game items | `/game-items` (3), the derivation (1), allocations (2), refinery yields under locations (1) | 7 (3) | one release, one floor raise |
| 6 — the small rest | Materialbörse (9), fleetview import deleted (1), announcement (4, optional), typed settings and the refinery request records (not measured) | 14 (9) | one release, one floor raise |
| **Total** | | **161 (74)** | |

Wave 1 carries all 22 cache upgrades and introduces the `/api/v1/*/admin/**` matcher; wave 2 carries
ten of the twelve cache downgrades and its storage decision is D-17 (the columns move with their owner), although the column move may follow
in the owning domain's wave; wave 3 re-keys `finance-entry-create`; wave 4 needs the bank seam rules
re-keyed; wave 5 needs the item catalogue's URL-only gate lifted. Every T1 wave costs every member
one forced update, so waves 5 and 6 can share one app release when both are ready.

**Before wave 1** — every item green on today's code and each re-keyed rule proven able to fail
once; two items may follow until wave 2, the first T1 wave, as their rows say:

| Guard | Why it must come first |
| --- | --- |
| G-01 re-keyed ArchUnit rules with selection floors | a moved controller would silently leave the gate, redaction and `permitAll` rules |
| G-02 authorization matrix, G-03 URL-only gates lifted | every moved line must show old and new path with unchanged gates; 15 operations keep their role only in a path rule |
| G-04 SpEL bean references | a split controller may rename or re-home a gate bean, which fails at request time as HTTP 400 |
| G-05 tenancy marker, G-06 mass-assignment rule | split controllers and new request records |
| G-07 path-keyed controls self-checking | no-store, rate limits, CSRF and the filter exemptions are keyed on paths |
| G-08 generated edge include, built after the frozen set is corrected from the app's call list | the prefix rules and path-only admission; wave 1 moves `/admin/terms` |
| G-13, G-14 frontend route/gate snapshot and call-existence test | every wave moves frontend call sites; G-17 too when a wave brings the domain's typed client |
| G-18 exchange freeze, including the refinery draft route's own request DTO | the relay surface must not move with its neighbours |
| G-21 no mixed release | frontend and backend of one wave must deploy together |
| G-23 ledger and app call list | the call list before G-08; the ledger before wave 2, the first T1 wave |
| One tag per domain, unique schema names, generator assertions, error-code registry, mandatory baseline | a per-domain review surface, a correct document and a previous-release comparison that cannot skip |
| An app release with the policy re-read of D-11 | at least one release before wave 2, so running apps notice the cut |

## Findings about today's API

**Authorization lives in five places.** URL rules (`SecurityConfig.java:362-440`: 31 `/api` path
patterns on 25 lines, first match wins), controller `@PreAuthorize` (413 annotations, 53 of them
class-level; 214 of 574 mappings rely on the class-level one), service `@PreAuthorize` (16 promotion
methods, and 2 in `MissionFinanceEntryService` that are the finance-entry edit's and delete's only
gate beyond `isAuthenticated()`), imperative service checks (`OrgUnitBankAccessService.requireCan*`)
and imperative controller checks (`RefineryOrderController.java:171-220`). 196 mappings carry only
`isAuthenticated()` in their annotations; for 15 the URL rule is the only role gate. Who may call an
operation cannot be read next to it. → G-02, G-03, plan §5.4.

**The gates that should catch a bad re-cut are keyed on names.** Nine controller rules select
`resideInAPackage("..backend.controller..")` (`ArchitectureTest.java:222`, `:272`, `:393`, `:430`,
`:445`, `:681`, `:741`, `:1149`, `:1256`); ArchUnit 1.5.1 fails an empty selection by default, so they
fail only when no controller is left, and a partial move silently drops the moved ones. The
`permitAll` loop skips classes outside that package (`:345-382`),
`staffelScopedWriteEndpointsMustGateOnOwnerScopeService` selects seven controller simple names
(`:1036`), and only the peer-redaction rule has a floor. → G-01.

**The public vhost admits by path, not by operation.** The include has 172 admission rules (93 exact,
79 regex) keyed on `$uri` alone, among them two prefix rules without an end anchor (lines 2–3), plus
a read-only family of 16 prefixes that answers 405 to writes (line 177) and per-path resets. 259
documented operations pass it against 234 frozen pairs; 11 of the 25 extra ones are refused by the
read-only rule and 14 stay reachable, `GET /me/layout` among them although REQ-API-012 says it is
not on the vhost. Every one is gated in the backend; nothing asserts admitted ⊆ frozen. → G-08.

**Read and write DTOs are not separated.** 13 DTOs are request body and response type at once; the
ArchUnit list `RESPONSE_ONLY_DTOS` protects one type, `MissionDto` (`ArchitectureTest.java:131`).
`POST /refinery-orders` binds `RefineryOrderDto`; its mapper ignores only owner, org unit and
timestamps (`backend/src/main/…/mapper/RefineryOrderMapper.java:118-121`) and the service resets id,
version, owner and org unit (`backend/src/main/…/service/RefineryOrderService.java:279-286`) but not
`status`, so by code reading a member can create an order directly as `COMPLETED`, which the store
then refuses (`:562-564`) — own data only, to be confirmed by a MockMvc test. 23 DTO types are
nested in 9 controllers, frozen contract types among them. → G-06; ADR-0060 becomes mandatory at
each domain's cut.

**Validation has gaps.** 13 `@RequestBody` parameters lack `@Valid` — 12 admin-only writes (9
catalogue, 2 role catalogue, 1 registration approval) and the shared-secret SPI endpoint; 14 body
types carry no Jakarta constraint; 7 `PUT` or `PATCH` bodies have no version component, one of them
by design (`OperationPayoutStatusUpdateDto`, the find-or-create retry). No test enforces REQ-API-002
or REQ-API-003; `NormalizedStringDeserializer` bounds free text globally. → G-06.

**The error contract is not in the contract.**
`backend/src/main/…/config/OpenApiProblemDetailsConfig.java:55-108` attaches six problem responses
to every operation and defines `ProblemDetail` without `code`, `correlationId` or `fieldErrors`, the
fields REQ-API-004 calls the contract; 429 is documented on one operation although both rate
limiters answer it on all of `/api/**`. Codes are string literals — about 50 in the exception
package plus four in filters — with no registry, unlike the exchange's. The app once listened for
`TERMS_ACCEPTANCE_REQUIRED` while the server sends `TERMS_NOT_ACCEPTED`. → the error-code registry,
D-09.

**Pagination, search and naming diverge from REQ-API-005.** One mapping takes a `Pageable`
(`AdminTermsController`), 67 take `page` and `size` through `PaginationUtil`, 75 return a
`PageResponse`, 71 `GET`s return an unpaged list. Free text is `q` (11), `query` (7), `search` (7),
`name` or `filter`; missions, operations, users and materials expose both a collection `GET` and a
`/search` twin. The 28 `/slim` suffixes no longer distinguish anything since their full-DTO twins
were deleted; reorder is `PUT` twice and `POST` once; boolean switches come in three shapes. → P5;
REQ-API-005 amended to the `PaginationUtil` reality.

**The OpenAPI document is monolithic and partly wrong.** No `GroupedOpenApi`; 96 tags, 55 of them
generated from class names; `@Operation` on 400 of 574 mappings and `@ApiResponses` on 231, although
REQ-API-007 asks for both everywhere. `backend/src/main/…/config/OpenApiCachingConfig.java:58-75`
promises `304` and an `ETag` on all 244 `GET`s, while 114 of them belong to no-store families that
never send one. Three schema names collide (`Op`, `Provenance`, `Skipped`): the committed `Op` is
the stock shape, so the blueprint and ship change sets are documented wrongly.
`OpenApiGeneratorTest` asserts only `200`; the assertions REQ-API-007 describes belonged to the
removed ingest generator. → one tag per domain, unique schema names, generator assertions.

**Contract pinning is hand-maintained.** `ExternalContractTest` (2,888 lines) freezes 235 entries —
234 pairs, one listed twice — down to response fields one level deep, required request fields,
query parameters, required enums, and types and nullability against
`backend/src/test/resources/api/frozen-contract-types.txt` (1,819 lines) and the previous release.
Its floor is 5, its release baseline may be skipped, and the app vendors a hand copy of
`openapi.json` (400 paths, copied 2026-09-22; 440 today) that nothing checks. → mandatory baseline,
ratcheted floor, G-23.

**The frozen set misses operations the app calls.** It freezes by verb and path, and v0.3.1 calls
`POST /operations`, which no edge rule admits (a 404 at the edge), and at least ten operations the
edge admits but the set does not list: `GET /materials/matrix`, `POST /orders`,
`POST /bank/accounts`, `POST /bank/holders`, `PATCH /bank/holders/{id}`, `PUT` and
`DELETE /refinery-orders/{id}`, `DELETE /hangar/ships`, `DELETE /personal-blueprints` and
`PUT /missions/{id}/participants/{participantId}/slim` (read from the app's `core:data`
repositories). Ten of the fourteen reachable extras above are therefore app calls, not
over-admissions. → G-23 before G-08.

> [!note] Corrected 2026-10-03 — the app's own call list found one more
> The app's published call list (243 operations, REQ-API-016) names an eleventh unfrozen call,
> `PUT /orders/{id}/requested` (the requester's order edit, since app v0.2.0), which no edge rule
> admitted either — a `404` at the edge like `POST /operations` was. All eleven are frozen now and the
> edit is admitted, method-scoped; the frozen set holds 246 operations — 235 pairs before (with
> `POST /operations`) plus the eleven, and its duplicate entry is removed. Three frozen operations are no longer called by the
> app: `GET /personal-inventory/{id}`, `GET /refinery-orders/my-orders`, `GET /users/me/memberships`.

**Versioning and deprecation.** All documented operations but one are `/api/v1`; the `/api/v2` one
is a demonstration ping whose v1 twin is deprecated. Two operations are deprecated (that ping and
`POST /hangar/import/fleetview`); `DeprecationInterceptor` sends `Deprecation: true` and a
`rel="alternate"` link. Spring Framework 7.0.9 offers first-class API versioning, but path versioning
is load-bearing here (edge include, no-store and rate-limit lists, logs), and under the hard cut there
are never two versions to negotiate — it is not adopted (plan §10).

**The controllers are part of the domain implementation.** 283 of 574 mappings run in a
controller-level `@Transactional` (175 through the class, 82 read-only and 26 read-write methods),
because entity-to-DTO mapping happens in the controller with `open-in-view: false`; 27 controllers
call entity mappers, and 58 mappings in 11 controllers take the `Jwt` and call
`UserService.getUserIdFromJwt`, while 24 use `@CurrentUserId`. → plan §5.2: the module API owns
transaction and mapping, `web` holds thin controllers and separate REST DTOs for T1 operations.

**Path strings are a contract with at least 14 readers.** The frontend (624 `BackendApiClient` call
sites in 83 classes, plus the controllers that bypass it, plan §4.3), its E2E seeder, about 35 app
files, the ingest relay, the Keycloak SPI, the edge include and nightly probe, the blackbox target,
`SecurityConfig`, three security filters, `NoStoreApiScopes` and the ETag filter, the rate-limit
rules and `SubjectRateLimitingFilter`, four more backend components, the contract tests, and
dashboards that group by the `uri` label, whose series history breaks at a cut. The edge's own rate
limit is per client address, not per path, and survives a re-cut. → G-07, G-08, per-domain path
classes in the frontend.

**The exchange relay seam is frozen but not pinned at build time.** The ingest builds its relay
targets by concatenation (`ExchangeController.java:134` and seven call sites); the backend serves them
in eight exchange controllers, each behind `@exchangeGate`; `ActingMemberFilter` lists 13 exact paths.
No test connects the three lists, and no backend test validates exchange JSON against the published
schemas — drift surfaces in production as a 502. → G-18.
