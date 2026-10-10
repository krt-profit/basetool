> **Doc type:** Living work plan of the REST API cut — the wave-by-wave execution plan behind
> [`rest-api-cut.md`](rest-api-cut.md) (the measured cut) and plan §7.9. Written 2026-10-10 against
> `origin/main` `110e16def`; the counts below were taken from the committed `openapi.json`, the app
> call lists and the code of that commit, not copied from the appendix. **Where this file and the
> appendix disagree, this file is the newer fact** (the appendix is a snapshot of `95e945326`).

# REST API cut: waves, mechanics, decisions

## 1 What changed since the appendix was measured

| Fact | Appendix (`95e945326`) | Today (`110e16def`) |
| --- | --- | --- |
| Documented operations / paths | 572 / 440 | **567 / 434** (`openapi.json`) |
| Tiers | — | **T0 4, T1 244, T2 319** (`x-contract-tier`) |
| Operations the cut touches | 161 (74 app-frozen) | **188 (77 app-called)**: 182 path moves and deletions plus 6 operations that change a shape (§ 5), among them the 2 optional kommando-group writes that this cut leaves out (Q-8); the 4 quality-tier admin operations of ADR-0241, the 14 exchange-administration operations (D-12) and the 2 sync-report operations are inside the 182 |
| Machinery | planned | **built**: G-01 … G-08 and G-23 guards, the generated edge map (REQ-API-021), the ledger `declared-breaks.txt` (empty), `retired-operations.txt` (empty, answers `410 APP_UPDATE_REQUIRED`), the release-bound floor (REQ-API-020; `application.yml` 17 / 17), the app call lists (`app-calls/18.txt` = app v0.5.0, `unreleased.txt` = 245 calls) |
| App | v0.3.1 | **v0.5.0 (versionCode 18)** published 2026-10-03, with the policy re-read on resume (basetool-android#209, merged); the next build is **19** |
| Backend layout | layer packages | controllers live in `<module>/web` for 13 modules (e.g. `mission/web/MissionController`, `bank/web/…`); the rest still in `controller` |
| Frontend | one `BackendApiClient` | **typed clients per domain** (`<domain>/client/*BackendClient`): the path strings of a domain are in one class, plus a few page controllers that still hold literals |

The machine-readable map is [`api-cut-map.txt`](../../backend/src/test/resources/api/api-cut-map.txt):
`<wave> <VERB> <old path> <new path|-> <tier> <app|->`, one line per operation, 182 lines for the
path moves and deletions (the shape changes are in § 5). `ApiCutMapTest` (built with wave 1)
holds the committed document to it: every line is in exactly one of its two states, and a wave that
has landed has no old path left.

## 2 Integration branch and CI

- **Branch** `claude/api-cut`, from `origin/main`. `origin/main` is merged into it at least daily
  (the CHANGELOG is the usual conflict: keep both entries). A wave is a pull request **into
  `claude/api-cut`**; nothing reaches `main` before the whole cut is ready (D-24).
- **CI does not run on those pull requests**: every workflow that matters (`ci`, `codeql`, `dco`,
  `repo-lint`, `flyway-migrations`, `dependency-check`, `e2e`, `keycloak-provisioner`, …) filters
  `pull_request` on `branches: [main]`. The branch protection of `main` still names its required checks.
  So: (1) one **draft pull request `claude/api-cut` → `main`** carries CI for every push to the branch
  (a draft runs the workflows); (2) before a wave pull request is merged into the branch, the same
  gates run locally (§ 9); (3) the `e2e` label sits on the draft PR from wave 1 on, because
  controllers, security configuration and frontend flows all change.
- **The draft PR is not merged until the cut is complete**: all six waves, the app release published,
  the floor literal raised, the owner's release decision.
- **Rebasing is not used**; a merge of `main` into the branch is signed and described like any commit.

## 3 What a moved operation costs: the checklist every wave follows

For each operation of the map, in the same pull request:

| # | Place | Change | Guard that fails if forgotten |
| --- | --- | --- | --- |
| 1 | Backend controller | the class or method mapping; the handler keeps its `@PreAuthorize`, its `@ContractTier` and its audit call | `AuthorizationMatrixTest` (golden), `ExternalContractTest`, `ArchitectureTest` rules |
| 2 | `authorization-matrix.txt` | regenerate; every changed line is a path change only, named in the PR | `AuthorizationMatrixTest` |
| 3 | `SecurityConfig` URL rules | the rule that matched the old path follows or goes; the `/api/v1/*/admin/**` matcher (wave 1) sits **before** the domain rules | the matrix names the matching rule per operation |
| 4 | `NoStoreApiScopes` | the new root joins the right family (`NO_STORE` for member and admin data); the old root leaves when empty | `NoStoreApiScopesTest`, the runtime test per family |
| 5 | Rate-limit rules, `SubjectRateLimitingFilter` | path rules re-keyed (`finance-entry-create` in wave 3); export segments are names, not paths | `RateLimitRuleCoverageTest` |
| 6 | CSRF exemption | unchanged (`/api/v1/**`); every target stays under `/api/v1` | `CsrfExemptionCoverageTest` |
| 7 | `openapi.json` | regenerated by `OpenApiGeneratorTest`; committed | CI diff check, `CommittedOpenApi` |
| 8 | Frozen set and `frozen-contract-types.txt` (T1 only) | the new path replaces the old one with the same field record | `ExternalContractTest` |
| 9 | `declared-breaks.txt` (T1 only) | one line `<VERB> <old path> - 19` per removed or changed frozen operation | `DeclaredBreaksTest`, G-23 |
| 10 | `retired-operations.txt` (T1 only) | `<VERB> <old path>`; the old path then answers `410 APP_UPDATE_REQUIRED` ahead of authentication | `RetiredOperationsTest`, `EdgeAdmissionTest` |
| 11 | Edge map and probe table | `./gradlew :backend:generateEdgeAdmission`; the refused samples follow; the new T1 paths are admitted, the retired old ones answered by the backend | `EdgeAdmissionTest` (both directions), `EdgeAdmissionNginxTest`, `EdgeProbeBackendStatusTest` |
| 12 | Frontend client | one `*BackendClient` per domain (a few page controllers hold literals: found by the call-existence test) | `BackendCallExistenceTest` (G-14), the client tests, `DtoOpenApiContractTest` |
| 13 | Frontend JS / templates | only when a page calls the backend path itself (none of wave 1) | route/gate snapshot (G-13) |
| 14 | E2E seeder and tests | literal paths in `BackendSeeder`, `ExchangeE2eSupport`, the tests | the E2E suite |
| 15 | Specs, ROLES_AND_PERMISSIONS, wiki, CHANGELOG | the paths the documents name | review |
| 16 | The app (T1 only) | repository classes that name the path; vendored `openapi.json`; the call list `19.txt` | `AppCallListTest` against the backend lists |

The ingest and the Keycloak SPI call only T0 paths (`/api/v1/exchange/**`, `/internal/discord/…`),
which never move; `theExchangeStaysOffTheApiVhost` and the exchange wire-contract tests are in the
gate list of every wave.

## 4 The waves

Every table below is generated from the map (`example` rows stand for a family; the operation
count is exact). Tier and app column count operations: T1 = in the frozen set, app = called by
app v0.5.0 (`app-calls/18.txt`). The order is the appendix order, risk and app impact first.

| Wave | Content | Operations | T1 | App-called | App release |
| --- | --- | ---: | ---: | ---: | --- |
| 1 | web-only: admin sub-trees, exchange administration (D-12), quality tiers (ADR-0241), sync reports, notification rules, hangar admin, three deletions | 68 | 0 | 0 | none |
| 2 | identity and org units: member-scoped moves out of `/users` and `/me`, the hierarchy fold, the Leitung view | 28 | 10 | 9 | 19 |
| 3 | mission: nested finance ledger, `/slim` dropped, search folded, two dead writes deleted, mission views as filters | 36 | 29 | 29 | 19 |
| 4 | bank: `/org-units/bank/**` to `/bank/org-units/**` | 29 | 24 | 24 | 19 |
| 5 | job orders and game items | 7 | 3 | 3 | 19 |
| 6 | the small rest: Materialbörse, announcement, fleetview import deleted | 14 | 9 | 9 | 19 |
| | **Path moves and deletions** | **182** | **75** | **74** | |

Reconciliation with the inventory (wave 1: 50 + 14 exchange + 4 quality tier = 68; waves 2 to 6:
30/9, 36/29, 30/24, 7/3, 17/12): the inventory's 188 operations are the 182 map lines plus six
operations that change a shape and not a path (§ 5): the two optional kommando-group writes (wave 2,
not in this cut), the balance-target write (wave 4) and the typed settings read and the two
refinery writes (wave 6). Its 77 app-called operations are the 74 above plus the balance-target
write, the settings read and the refinery create. Wave 6 differs by one app-called operation from
the inventory's 12 (map 9 plus settings and refinery create = 11); the wave 6 pull request settles
it against the call list.

Wave 1 needs no app release, but it does not reach `main` before the others either (D-24). The waves are
built and reviewed in this order on the integration branch so that each review stays small; the
forced-update path (§ 6) is proven on wave 2, the first wave with T1 operations.

### 4.1 Wave 1: web-only moves

| Example old path | Example new path | Ops | T1 | App-called |
| --- | --- | ---: | ---: | ---: |
| `/admin/default-blueprints` | `/blueprints/admin/defaults` | 3 | 0 | 0 |
| `/admin/deletion-requests` | `/users/admin/deletion-requests` | 3 | 0 | 0 |
| `/admin/exchange-clients` | `/connected-apps/admin/clients` | 10 | 0 | 0 |
| `/admin/exchange-settings` | `/connected-apps/admin/settings` | 2 | 0 | 0 |
| `/admin/exchange-undo-runs` | `/connected-apps/admin/undo-runs` | 2 | 0 | 0 |
| `/admin/import/p4k/jobs` | `/catalog/admin/import/p4k/jobs` | 4 | 0 | 0 |
| `/admin/person-search` | `/users/admin/person-search` | 1 | 0 | 0 |
| `/admin/personal-blueprints` | `/personal-blueprints/admin` | 8 | 0 | 0 |
| `/admin/personal-inventory/items/{id}` | `/personal-inventory/admin/items/{id}` | 4 | 0 | 0 |
| `/admin/quality-tiers` | `/catalog/admin/quality-tiers` | 4 | 0 | 0 |
| `/admin/registrations` | `/users/admin/registrations` | 6 | 0 | 0 |
| `/admin/roles` | `/roles` | 3 | 0 | 0 |
| `/admin/terms` | `/terms/admin` | 2 | 0 | 0 |
| `/admin/users/{id}/attributes` | `deleted` | 1 | 0 | 0 |
| `/admin/users/{userId}/export` | `/users/admin/{userId}/export` | 2 | 0 | 0 |
| `/hangar/users/{userId}/ships` | `/hangar/admin/users/{userId}/ships` | 4 | 0 | 0 |
| `/notification-rules` | `/notifications/admin/rules` | 5 | 0 | 0 |
| `/sync-reports` | `/catalog/admin/sync-reports` | 2 | 0 | 0 |
| `/system/ping` | `deleted` | 1 | 0 | 0 |
| `/api/v2/system/ping` | `deleted` | 1 | 0 | 0 |

**Content and decisions already taken.** The 14 exchange-administration operations
(`/admin/exchange-clients/**`, `-settings`, `-undo-runs/**`) move to
`/api/v1/connected-apps/admin/**` (D-12); the 14 relay operations of the exchange are T0 and stay.
The four quality-tier operations of ADR-0241 move to `/catalog/admin/quality-tiers`, the two sync
report operations to `/catalog/admin/sync-reports` (not measured by the appendix; they are
ADMIN-only web calls). `/roles` leaves the admin family: the whole domain is administration and
carries its own `hasRole('ADMIN')`; an explicit URL rule `/api/v1/roles/**` (ADMIN) is added so that
the rule does not rest on the class gate alone.

**Deleted.** `PUT /admin/users/{id}/attributes` (the duplicate of `PUT /users/{id}/attributes`;
its web caller moves to the survivor), `GET /api/v1/system/ping` and `GET /api/v2/system/ping`
(demonstration pair, no caller, the only `/api/v2` operation). The deprecation
interceptor and `@ApiDeprecation` lose their last user when the pings (wave 1) and the fleetview
import (wave 6) are gone; they are removed with wave 6, after a grep in both waves.

**Callers.** Backend: 17 controller classes (the `Admin*` family and `DiscordRegistrationAdminController`,
the exchange administration pair, `AdminPersonalInventoryController`, `HangarController`,
`SystemController`, `SyncReportController`, `NotificationRuleController`); frontend: 7 typed clients
(identity, catalogue, blueprint, exchange, notification, personal inventory, audit) and the admin page
controllers (about 30 files hold a wave-1 literal; `BackendCallExistenceTest` names every one that
is missed); ingest, Keycloak SPI and the app: none (all T2). E2E: `BackendSeeder`,
`ExchangeE2eSupport` and the admin specs.

**Security deltas (the reason this wave comes first).**

1. New first-match rule `/api/v1/*/admin/**` → `hasRole('ADMIN')`, placed **before** the user rules
   (`/api/v1/users/**` would otherwise decide `users/admin/**`, and its `GET /users/*` rule
   admits officers and members for any single-segment id), before `/api/v1/hangar/**`
   (`HANGAR_READ/WRITE`) and before `/api/v1/personal-inventory/**` (`authenticated()`).
   Every operation that matches the new pattern today is ADMIN-only (`bank/admin/**` ×5, and
   `announcement/admin`, whose URL rule is `anyRequest` and tightens), so no gate widens; the matrix
   shows the matching rule per operation. The old `/api/v1/admin/**` rule is deleted in the same
   change; `bank/admin/**` becomes redundant and goes too.
2. `NoStoreApiScopes`: the appendix's 22 cache upgrades (admin reads that become no-store because
   their new root sits under a no-store family) are made root by root, `/api/v1/admin/` leaves, and
   each new root is classified explicitly: an unclassified `/api` path fails the family test.
3. `/api/v1/terms/admin` and `/terms/admin/pending-count`: the terms gate and the pending-approval
   gate use exact path sets (REQ-SEC-080), so the moved paths are not exempted by prefix; the
   `terms/admin` pair is added to the exact sets only if the matrix test says the old ones were
   (verified in the implementation, not assumed).
4. Edge: all moved operations are T2. The generated map admits only T0/T1; nothing is added. The
   probe table rows for `admin/terms`, `hangar/users` and `bank/admin` follow the new paths (still
   refused on the public vhost); the new `…/admin/…` paths are added as refused samples.
5. Rate limits and CSRF: no rule is keyed on these paths; CSRF stays exempt for `/api/v1/**`.

**Ledger and retirement.** None (no T1 operation). `ApiCutMapTest` sees the wave flip.

### 4.2 Wave 2: identity and org units

| Example old path | Example new path | Ops | T1 | App-called |
| --- | --- | ---: | ---: | ---: |
| `/leitung/view` | `/org-chart/leadership` | 1 | 0 | 0 |
| `/me/active-org-unit` | `/org-units/me/active` | 1 | 1 | 1 |
| `/me/org-units` | `/org-units/me/switchable` | 1 | 1 | 1 |
| `/org-hierarchy/bereiche` | `/org-units/bereiche` | 10 | 0 | 0 |
| `/org-hierarchy/org-units` | `/org-units` | 1 | 0 | 0 |
| `/org-hierarchy/org-units/{id}/parent` | `/org-units/{id}/parent` | 1 | 0 | 0 |
| `/users/me/blueprint-sharing` | `/blueprints/me/sharing` | 2 | 2 | 2 |
| `/users/me/memberships` | `/org-units/me/memberships` | 1 | 1 | 0 |
| `/users/me/org-unit-ids` | `/org-units/me/ids` | 1 | 0 | 0 |
| `/users/me/payout-preference` | `/missions/me/payout-preference` | 2 | 2 | 2 |
| `/users/me/pickable-org-units` | `/org-units/me/pickable` | 1 | 0 | 0 |
| `/users/me/read-announcement/{announcementId}` | `/announcements/{id}/read` | 1 | 1 | 1 |
| `/users/search-bank` | `/bank/members/search` | 1 | 1 | 1 |
| `/users/search-bank/references` | `/bank/members/search/references` | 1 | 0 | 0 |
| `/users/{id}/memberships` | `/org-units/members/{id}/memberships` | 3 | 1 | 1 |

**App-called (9):** `OrgUnitRepository` 2 (`/me/active-org-unit`, `/me/org-units`),
`MemberPreferencesRepository` 4 (payout preference, blueprint sharing), `AnnouncementRepository` 1
(read marker), `BankStaffRepository` 1 (member search), `InventoryRepository` 1 (member
memberships). The other 18 operations are web-only.

**Backend.** Controllers re-homed by domain (identity's `/users/me/**` leaves; the org-unit
module gets `/org-units/me/**` and `/org-units/members/**`, the mission module the payout
preference, the blueprint module the sharing setting, the announcement module the read marker, the
bank module the member search). `/users/me/**` stays for identity. Wave 2 carries ten of the
appendix's twelve cache downgrades; each is decided per operation (D-18), and member-scoped data
(`/org-units/me/**`, `/org-units/members/**`) stays no-store.

**Rules that change.** The `users/search-bank` pair is a URL rule today
(`hasAnyRole(ADMIN, OFFICER, KRT_MEMBER, BANK_MANAGEMENT, BANK_EMPLOYEE)`): its gate is carried by
the annotation (G-03) before the path moves. `GET /users/*/memberships` has a URL rule too
(`ADMIN, OFFICER, KRT_MEMBER, BANK_EMPLOYEE`) and follows the same way.

**Storage (D-17).** The payout and sharing columns stay on the identity row in this cut; the move into
their owners' tables is a separate migration without a path change and may follow.

**Ledger and retirement.** Ten T1 lines `<VERB> <old> - 19`; the nine app-called ones are in
`retired-operations.txt`, so an old app gets `410 APP_UPDATE_REQUIRED`.

### 4.3 Wave 3: mission

| Example old path | Example new path | Ops | T1 | App-called |
| --- | --- | ---: | ---: | ---: |
| `/finance-entries` | `/missions/{missionId}/finance-entries` | 1 | 1 | 1 |
| `/finance-entries/{entryId}` | `/missions/{missionId}/finance-entries/{entryId}` | 2 | 2 | 2 |
| `/inventory/mission/{missionId}` | `/inventory/allocations` | 1 | 0 | 0 |
| `/missions/search` | `/missions` | 1 | 1 | 1 |
| `/missions/{id}` | `deleted` | 1 | 0 | 0 |
| `/missions/{id}/frequencies/custom/slim` | `/missions/{id}/frequencies/custom` | 28 | 25 | 25 |
| `/missions/{id}/participants/add` | `deleted` | 1 | 0 | 0 |
| `/refinery-orders/mission/{missionId}` | `/refinery-orders` | 1 | 0 | 0 |

**Not a pure path move.**

- `GET /missions/search` folds into `GET /missions` (the existing list): typed filters and `q`,
  the app's frozen parameter `query` changes name, so it is a ledger line with the field. The
  list's scope rules must hold with and without filters (REQ-MISSION-008): a differential verdict
  test of the fold (plan §5.4).
- `POST /finance-entries`, `PUT/DELETE /finance-entries/{entryId}` nest under
  `/missions/{missionId}/finance-entries`. The create gate reads the mission from the path
  instead of the body (`canCreateFinanceEntry`); the rate-limit rule `finance-entry-create` is
  re-keyed, or its tighter budget silently stops applying (proven by a planted test).
  `NoStoreApiScopes` drops `/finance-entries/**`.
- The 28 `/slim` operations drop the suffix; `participant-mutations` still matches. The legacy
  `POST /missions/{id}/participants/add` and `PUT /missions/{id}` are deleted (their web caller and
  the E2E seeder move to the survivors).
- `GET /inventory/mission/{id}` and `GET /refinery-orders/mission/{id}` become filters
  `?missionId=` on `/inventory/allocations` and `/refinery-orders` (web-only).

**App-called (29):** `MissionRepository` 20, `MissionTimelineRepository` 9. v0.3.1 also sends
`PUT /missions/{id}/participants/{participantId}/slim`; the call list of 19 must carry the new path.
**Guards:** G-01 re-keyed selection for `peerReadableMissionEndpointsMustRedactPii` (floor 10); the
mission web-client tests; the matrix lines change by path only.

### 4.4 Wave 4: bank

| Example old path | Example new path | Ops | T1 | App-called |
| --- | --- | ---: | ---: | ---: |
| `/org-units/bank/accounts/{id}` | `/bank/org-units/accounts/{id}` | 29 | 24 | 24 |

**App-called (24):** `BankRepository` 24 (the largest single app change), plus the balance-target
write (§ 5). The two audiences keep two controllers and two DTO families (REQ-BANK-054 redaction of
`staffNote`), only the prefix moves. `NoStoreApiScopes` already lists `/api/v1/bank/**`, so the
moved reads stay no-store; the `/api/v1/org-units/bank/` entry goes. The 29 mappings carry only
`isAuthenticated()` and are decided in `OrgUnitBankAccessService`; the seam ArchUnit rules
(`bankClassesMustNotConsultOrgUnitScope`, `orgUnitAwareBankSeamIsContainedToOneClass`) are keyed on
names and must still select the moved classes (G-01).

### 4.5 Wave 5: job orders and game items

| Example old path | Example new path | Ops | T1 | App-called |
| --- | --- | ---: | ---: | ---: |
| `/inventory/item-catalog` | `/game-items` | 1 | 0 | 0 |
| `/orders/item-catalog` | `/game-items` | 1 | 1 | 1 |
| `/orders/item-catalog/blueprints/{blueprintId}/derivation` | `/blueprints/{id}/derivation` | 1 | 0 | 0 |
| `/orders/item-catalog/{gameItemId}/blueprints` | `/game-items/{gameItemId}/blueprints` | 1 | 1 | 1 |
| `/orders/{id}/inventory/orphaned` | `/orders/{id}/allocations/orphaned` | 1 | 0 | 0 |
| `/orders/{jobOrderId}/inventory/{inventoryItemId}/unlink` | `/orders/{id}/allocations/{inventoryItemId}` | 1 | 1 | 1 |
| `/refinery-orders/locations/{locationId}/yields` | `/locations/{locationId}/refinery-yields` | 1 | 0 | 0 |

`GET /orders/item-catalog` and `GET /inventory/item-catalog` become one `GET /game-items` with one
DTO and `q`; the inventory twin's URL-only role gate (`/inventory/**`:
`ADMIN, OFFICER, LOGISTICIAN, KRT_MEMBER`) is lifted into the annotation before the move (G-03), or
the new read would drop the requirement. The cache class of `/game-items` and
`/locations/*/refinery-yields` is decided explicitly (D-18; proposal: revalidate, they are
catalogue data). **App-called (3):** `JobOrderRepository`, `MaterialCollectionRepository`,
`InventoryRepository`.

### 4.6 Wave 6: the small rest

| Example old path | Example new path | Ops | T1 | App-called |
| --- | --- | ---: | ---: | ---: |
| `/announcement` | `/announcements/current` | 3 | 1 | 1 |
| `/announcement/admin` | `/announcements/current/admin` | 1 | 0 | 0 |
| `/hangar/import/fleetview` | `deleted` | 1 | 1 | 1 |
| `/material-requests` | `/material-exchange/requests` | 9 | 7 | 7 |

`/material-requests/**` to `/material-exchange/requests/**` (both roots stay outside the no-store
families as an org-wide board, REQ-SEC-031); `/announcement` to `/announcements/current` (the read
keeps its `204` semantics, REQ-API-009); `POST /hangar/import/fleetview` is deleted (the app
already uses `POST /hangar/import/ships`; the operation's announced sunset no longer matters under
the hard cut) and with it the last deprecated operation: `@ApiDeprecation`, `DeprecationInterceptor`
and their tests go. **App-called (9):** `MaterialBoardRepository` 7, `AnnouncementRepository` 1,
`HangarRepository` 1.

## 5 Shape changes the map does not carry

Seven items change a request, a response or a gate instead of a path. Three of them touch the app
and are part of the same forced update; they are listed because the map is a list of paths.

| # | Wave | Item | App | Decision |
| --- | --- | --- | --- | --- |
| S-1 | 3 | `GET /missions` absorbs the search: filters and `q` replace the parameter `query` | `MissionRepository` | ledger line with the field |
| S-2 | 4 | `PATCH /bank/accounts/{id}/balance-target` becomes `PUT` with its org-unit twin's request shape | `BankStaffRepository` | owner (Q-5) |
| S-3 | 6 | the app's `GET /settings/{key}` for the two job-order age thresholds becomes `GET /orders/settings`; `GET /refinery-orders/settings` for the rounding mode | `JobOrderRepository` | owner (Q-5) |
| S-4 | 6 | `POST`/`PUT /refinery-orders` bind request records without `id`, `owner`, `profit`, `owningSquadron`; `status` limited on create; the owner override moves to the per-target path | `RefineryRepository` | owner (Q-5) |
| S-5 | 3 | finance gate reads the mission from the path | none | none |
| S-6 | 5 | one `GameItemReferenceDto` instead of two | `JobOrderRepository` | field union reviewed in the wave |
| S-7 | 2 | payout and sharing columns move into their owners | none | later, no path change (D-17) |

S-4 needs the exchange's refinery draft route to bind its own request DTO first (G-18), or the
frozen external behaviour changes with it.

## 6 The forced update (D-04, D-11, D-24)

1. **One release.** The six waves ship as one backend/frontend release and one app release,
   versionCode **19**. The release's own `application.yml` carries the floor: both
   `minimum-version-code` and `latest-version-code` become 19 (today 17 / 17 although 18 is
   published). The host `.env` stays an emergency override only.
2. **Ledger.** One line `<VERB> <old path> - 19` for each of the 75 T1 operations that move or are
   deleted (the 74 app-called ones and `GET /users/me/memberships`, T1 but not called by 0.5.0),
   plus lines with the field for the shape changes S-1 to S-4. `declared-breaks.txt` is filled
   wave by wave on the integration branch, so a wave is red until its lines are there (G-23).
3. **Retirement.** Each of those operations is also in `retired-operations.txt`; the retired
   filter answers `410 APP_UPDATE_REQUIRED` ahead of authentication, so v0.5.0 and older get the wall
   instead of a 404. T0 is never retired; the test already refuses it.
4. **Edge.** The generated include is regenerated per wave: new T1 paths admitted, old ones no
   longer admitted at the edge. The edge answers old paths with its own 404 once reconciled;
   until then the backend's 410 applies. (The release sequence: publish app 19, then promote;
   floor, retirements and new paths arrive in one deploy; a rollback to a release with the old
   floor restores the old API with it.)
5. **Order of events.** App 19 is built from the app's integration branch against the backend
   integration branch's `openapi.json`; its call list `19.txt` is generated by the app and copied
   in; `AppCallListTest` then proves every call of 19 exists and no call of 19 is in the ledger.
   Only then is the draft PR marked ready.
6. **Rehearsal.** Before the production promotion the whole sequence is rehearsed on the testing
   host (old app → 410 wall → new app works); that is an owner-executed step (§ 7, Q-9).

## 7 Decisions for the owner

| # | Question | Recommendation |
| --- | --- | --- |
| Q-1 | CI for `claude/api-cut`: the draft PR into `main` carries CI for the branch; wave PRs target the branch and are merged into it after the local gates are green and reported (proposal: I merge them, as with the modularisation PRs). May the draft PR stay open for weeks? | yes |
| Q-2 | Release name and floor: app 19 (version name proposal 0.6.0), floor and latest 19. Also: `latest-version-code` is 17 although 18 is published; raise it to 18 now on its own? | yes to 19; raise to 18 only if the in-app update prompt should already point to 0.5.0 |
| Q-3 | App branch: `claude/api-cut` in `basetool-android`, one PR per repository class (§ 8), nothing on its `main` until the backend is ready; who may merge there? | same rule as here |
| Q-4 | `POST /hangar/import/fleetview` is called by the app today (T1). The new app drops the fleetview import and keeps the ship import; confirm the feature goes. | confirm |
| Q-5 | Shape changes with an app touch: S-2 balance-target `PUT`, S-3 typed settings, S-4 refinery request records. In this cut (one forced update) or later (another one)? | in this cut |
| Q-6 | `GET /missions?q=` renames the app's `query` parameter; keep the old name to spare the app a change? | rename (principle P5) |
| Q-7 | Cache classes (D-18): member data stays no-store; `/game-items` and `refinery-yields` revalidate; admin catalogue reads revalidate. | as listed |
| Q-8 | Optional items outside the cut (web-only, no app): kommando-group writes under the squadron, `POST /material-exchange/items/{id}/deactivate` as a collection action. | defer to a web-only follow-up wave |
| Q-9 | Rehearsal on the testing host and the production promotion order (owner-executed steps; nothing runs against any host from this work). | rehearse first |

## 8 Work split for the app (basetool-android)

One app release, one branch, one pull request per repository class so that each is reviewable
against its backend wave. Counts are app-called operations.

| Order | Repository class | Wave | Calls | Notes |
| --- | --- | --- | ---: | --- |
| 1 | `OrgUnitRepository`, `MemberPreferencesRepository`, `AnnouncementRepository` (read marker), `BankStaffRepository` (member search), `InventoryRepository` (memberships) | 2 | 9 | small, identity-flavoured |
| 2 | `MissionRepository`, `MissionTimelineRepository` | 3 | 29 | `/slim` removal is mechanical; `query` → `q`; finance entries nested, the mission id goes into the path |
| 3 | `BankRepository` (+ balance-target in `BankStaffRepository`) | 4 | 24 (+1) | mechanical prefix |
| 4 | `JobOrderRepository`, `MaterialCollectionRepository`, `InventoryRepository` (game items), settings read | 5, 6 | 3 (+1) | DTO union for game items |
| 5 | `MaterialBoardRepository`, `AnnouncementRepository` (current), `HangarRepository` (drop fleetview) | 6 | 9 | |
| 6 | `RefineryRepository` | 6 | 1 | request record, `status` no longer echoed |
| 7 | vendored `openapi.json`, the generated call list `19.txt`, the version-policy test, release notes | all | | the list is produced by the app's own generator |

Everything the app does not call stays out of its diff. The coordinator's brief for the app side
should name the same order so that the backend wave and the app class are reviewed together.

## 9 Local gates (as CI does not run on the integration branch)

Before every merge into `claude/api-cut` and before every push to it:

`./gradlew spotlessApply` (own invocation), then `checkstyleMain checkstyleTest spotbugsMain`,
`:backend:test :frontend:test :ingest:test`, `:frontend:lintJs :frontend:typecheckJs
:frontend:lintHtml :frontend:lintCss :frontend:prettierCheck` where the frontend changed, the
edge-generation tasks (`:backend:generateEdgeAdmission`, `generateOpenApi` through
`OpenApiGeneratorTest` with rewrite) and a clean `git diff` afterwards. The E2E suite runs on the
draft PR (label `e2e`) and locally before the wave is called green when a flow changed. The
verification metadata is untouched by this track (no new dependency).

## 10 Wave 1 implementation steps (what the next pull request contains)

1. `ApiCutMapTest` and the map are already merged with this plan; wave 1 flips its lines.
2. Backend controllers: base paths per § 4.1; delete the three operations and their tests.
3. `SecurityConfig`: add `/api/v1/*/admin/**` and `/api/v1/roles/**` before the domain rules; delete
   `/api/v1/admin/**` and `/api/v1/bank/admin/**`.
4. `NoStoreApiScopes`: families and the family test.
5. Regenerate `openapi.json`, `authorization-matrix.txt`, the edge map and the probe table; review
   each diff line as path-only.
6. Frontend: re-point the 11 clients and the remaining literals; the typed-client and
   call-existence tests; E2E seeder and specs.
7. Tests keyed on the old paths in the backend (controller, security, audit, rate-limit, cache).
8. Docs: `docs/specs/*` paths, `ROLES_AND_PERMISSIONS.md`, arc42 §5/§8 where a root is named,
   the knowledge base, the German wiki, CHANGELOG (one line), plan §7.9 row.
