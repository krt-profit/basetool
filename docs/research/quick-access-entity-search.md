# Entity search in the quick access — feasibility

> **Doc type:** Research note — a snapshot of findings, not a spec and not a decision. Collected
> 2026-10-20 for wayfinder ticket #2803 (map #2800). Nothing here is binding; a feature built on it
> gets its own `REQ-…` and an ADR.
>
> **Question:** which entities could the quick access (`Ctrl+K`) find by name, and what would each
> take — backend endpoints, tenancy and owner scope, redaction and PII rules, latency and paging?
> How could ADR-0282's `QuickAccessContributor` carry entity results, and what does ADR-0240's
> rejection of a backend-served index mean for it?

All sources are the code and docs of the repository at `origin/main` (`96ecabd60`). Paths are
relative to the repository root; `backend/…/` stands for
`backend/src/main/java/de/greluc/krt/profit/basetool/backend/`, `frontend/…/` for
`frontend/src/main/java/de/greluc/krt/profit/basetool/frontend/`.

## 1. How the quick access works today

- **Index in the browser.** `frontend/src/main/resources/static/js/kernel/krt-palette.js` builds its
  index from the links the server rendered into the drawer and the palette fragment, plus the
  on-demand entries. Matching is a folded substring test: rank 0 for a label hit, rank 1 for a
  breadcrumb or search-term hit (`rankOf`); at most `PAGE_MAX = 8` pages and the matching actions
  are shown (`groupsFor`).
- **On-demand entries (ADR-0282).** `frontend/…/shell/web/QuickAccessController.java` answers
  `GET /quick-access/entries` (authenticated, `X-Requested-With` marker, JSON
  `{href, label, crumb, icon, keywords}`) by asking every `QuickAccessContributor`
  (`frontend/…/kernel/layout/QuickAccessContributor.java`). The interface has one method,
  `entries()`, **with no query parameter**; it fails closed. The palette reads the endpoint **once
  per page**, on the first opening (`loadExtraPages`), and merges the entries into the index.
- **Recents.** `krt.recent` (max 4) holds paths and labels; an entry is offered only when its
  `href` is still in the current index (`groupsFor` filters `readRecent()` through `index.find`).
  An entity result that is not in the index would therefore never reappear in "Zuletzt besucht"
  unless that filter changes.
- **ADR-0240, decision 4 and alternatives:** the index is built from the rendered menu "so the
  server stays the only place that decides what a member may see, and no endpoint is added"; a
  backend-served index was rejected because it "would duplicate the permission rules the templates
  already apply … and could drift from what the menu shows". ADR-0282 already added the first
  endpoint, for pages whose visibility needs a backend read.

## 2. Cross-cutting constraints

- **Tenancy (REQ-ORG-*, `docs/specs/org-unit-tenancy.md`).** Scoped reads build a `ScopePredicate`
  from `OwnerScopeService.currentScopePredicate()` (`adminAllScope`, `activeOrgUnitId`,
  `memberOrgUnitIds`) and pass it into the repository query. Mission and operation search already
  do this (`mission/internal/MissionService.searchMissions`,
  `operation/internal/OperationService.searchOperations`). The active-org-unit pin travels from
  the frontend as `X-Active-Org-Unit-Id` through the Reactor context accessors, so a frontend
  search endpoint that calls the typed backend clients inherits the caller's scope with no extra
  work.
- **Guests and peers (REQ-SEC-007, `docs/specs/security-and-access.md`).** The role-less `GUEST`
  cohort is gone (ADR-0159, REQ-SEC-052/053); what remains is the member-peer redaction of mission
  detail fields (e-mail, real name, internal orders and items) for members below Logistician. A
  search result needs only id, name, status and date — none of the redacted fields — so a slim
  projection avoids the redaction question entirely. The mission list is gated on
  `isMemberOrAbove()`; bank-only accounts (authenticated non-members) get nothing there.
- **PII and logging (REQ-OBS-004, `docs/specs/observability.md`).** The access logs write the path
  only: the frontend `kernel/observability/RequestLoggingFilter` logs `request.getRequestURI()` plus
  a whitelisted `fragment` marker, the frontend `kernel/backend/WebClientLoggingFilter` logs
  `request.url().getPath()`, and the backend `platform/internal/RequestLoggingFilter` logs the URI
  without query. A search term in a **query parameter** therefore stays out of the logs; it must
  never move into the path, an MDC field or a metric label. Member names in results are handles,
  but "never log names" applies to any new log line the search adds.
- **Rate limits.** Backend reads are not charged to the per-subject bucket
  (`platform/api/SubjectRateLimitingFilter`: writes, streams and exports only); they fall under the
  per-IP bucket of `/api/**` (`backend/src/main/resources/application.yml`, `rate-limit`: 5000 per
  minute, keyed on the relayed client IP). A debounced type-ahead with one call per domain per
  keystroke fits easily.
- **Paging.** `PaginationUtil.createPageRequest` clamps `size`; every candidate search endpoint
  takes `page`/`size`, so a quick-access call asks for `size=5`. `BlueprintProductController.search`
  takes `limit` and caps it itself.
- **Monitoring (REQ-OBS-005…011).** A new endpoint needs a probe decision and a bounded metric
  (e.g. `basetool_quick_access_search_total{domain,outcome}`), never the term as a label.

## 3. Per-entity findings

"Target page" is where a result would navigate; without one, an entity hit has nowhere to go.

| Entity | Endpoint available? | Name filter? | Scope / tenancy | Redaction / PII | Target page | Effort |
|---|---|---|---|---|---|---|
| **Missions** | Yes — `GET /api/v1/missions` (`mission/web/MissionController.getAllMissions`), paged; `GET /missions/lookup` (unpaged reference list) | Yes, `q` — but it matches name **and** description, location and operation name (`MissionService.searchMissions` Javadoc) | `ScopePredicate` in the repository query; gated `isMemberOrAbove()` | List DTO is not peer-redacted detail; a slim `{id, name, status, plannedStartTime}` (`MissionReferenceDto`) needs no redaction | `/missions/{id}` (`MissionPageController`) | **S** (M if a name-only match is wanted) |
| **Operations** | Yes — `GET /api/v1/operations/search` (`operation/web/OperationController.searchOperations`), paged | Yes, `query` over name and description | `ScopePredicate` + member flag + caller id in the query | `OperationReferenceDto(id, name)` exists; no PII | `/operations/{id}` | **S** |
| **Job orders** | Partly — `GET /api/v1/orders` (paged, status/unit filters only) and `GET /orders/lookup` (all active orders, unpaged) | **No.** Orders have no name; they are known by `displayId` (`#123`), requesting org unit and material lines (`joborder/internal/JobOrderReferenceDto`) | `getAllJobOrders` is scoped; `findAllActiveReference` loads **all** active orders with materials and filters them in memory through `JobOrderAccessPolicy` — too heavy per keystroke | `handle` in the reference DTO is a contact handle and is redacted on the requester view: do not search or show it | `/orders/{id}` | **M–L** (new `q`/`displayId` filter on the scoped query) |
| **Ships / hangar** | Yes — `GET /api/v1/hangar/my-ships?search=` (own fleet) and `/hangar/squadron-overview?search=` (`HANGAR_ACCESS`) | Ship type / manufacturer only; the ship's own `name` (`hangar/api/Ship.name`) is **not** searched | Own ships keyed on JWT `sub`; overview adds owner details only for officers/admins | Overview owner breakdown is officer/admin data — use `my-ships` only | **None per ship**; at best the hangar page with its filter | **M** |
| **Materials** | Yes — `GET /api/v1/materials/search?search=` (picker search, paged) and `/materials/lookup` | Yes, case-insensitive name | Global catalogue, no tenancy; `isAuthenticated()` | None | `/materials/{id}` (`MaterialsPageController`) | **S** |
| **Members** | Yes — `GET /api/v1/users/search/references?query=` (`identity/web/UserController`), slim `UserReferenceDto(id, username, displayName, effectiveName, rank)` | Yes, username/display name | Squadron-scoped projection; roles Admin/Officer/KRT member | Names are PII under the logging rule; projection is already the peer field set | **None for members** — the only per-member page is `/members/{id}/edit`, Admin only (`MemberManagementController`) | **L** for members (needs a target page first); **S** for admins in admin mode |
| **Org units** | Yes — `GET /api/v1/org-units/active` and `/active-all-kinds` (unpaged, small, "carries no PII") | No server filter needed — the list is small enough to filter in the browser | Global catalogue of active units; `isAuthenticated()` | None | **Weak** — `/org-chart` for everyone (no deep link verified); `/organisation/special-commands/{id}` is Admin/Officer | **M** (needs a target, e.g. an org-chart anchor) |
| **Blueprints** | Yes — `GET /api/v1/personal-blueprints?q=` (caller's own) and `GET /api/v1/blueprints/products/search?q=&limit=` (type-ahead, capped, owned flag); `GET /api/v1/blueprints` is Admin only | Yes, product name | Own data keyed on `@CurrentUserId`; product catalogue global | None | Own blueprints page; per-item `/orders/item-blueprints/{gameItemId}` | **M** |

Effort scale: **S** = a contributor over an existing typed-client call and an existing page;
**M** = a new backend filter or a missing target page; **L** = a new page plus a privacy decision.

## 4. Implications for the Basetool

- **Feasible now for four entities:** missions, operations and materials have a scoped, paged name
  search and a detail page each; blueprints are close. Job orders, ships, members and org units
  each lack either a name filter or a page to land on, so they belong in a second step.
- **`QuickAccessContributor` cannot carry search as it is.** Its `entries()` has no query and is
  called once per page. Entity search needs a sibling seam — e.g. `QuickAccessSearchContributor`
  with `results(String query, int limit)` — behind a new endpoint such as
  `GET /quick-access/search?q=`, which runs the contributors in parallel (`ParallelPageLoader`,
  which already restores the scope, locale and correlation context), fails closed per domain
  through `SectionFallback`, and returns the same `{href, label, crumb, icon, keywords}` shape
  plus a group key. Each domain implements it in its own `web` package over its typed backend
  client, as ADR-0282 does for tabs; the kernel stays domain-free.
- **Client side:** debounce (~200 ms), a minimum of two characters, abort the previous read through
  `krtFetch.get(…, {signal})`, render entity hits as a separate group below "Seiten", and keep the
  page index instant — the network must never delay page matches.
- **ADR-0240's rejection does not forbid this, but the new ADR must say so explicitly.** The
  rejected thing was a backend-served index of *pages*, which would have duplicated the template's
  `sec:authorize` / `th:if` rules. For *entities* the opposite holds: the visibility rules live only
  in the backend (`OwnerScopeService`, access policies), so asking the backend is the single source
  and building an entity index in the browser would be the duplication. ADR-0282 already broke the
  "no endpoint is added" sentence; the navigation ADR this map produces should supersede ADR-0240
  decision 4 to read "pages from the rendered menu, entities from scoped backend searches".
- **Recents and privacy:** today an entity visit could not return as a recent, because recents are
  filtered through the page index. If entity recents are wanted, `krt.recent` would hold entity
  names in `localStorage`; that is a deliberate decision (REQ-UI-017 / ADR-0120 territory), not a
  side effect.
- **Live update / multi-user sync** does not apply to a transient result list; a renamed or
  deleted entity simply drops out of the next search.
- **Monitoring and specs:** a new `REQ-UI-*` (or an amended REQ-UI-026) for entity search, a
  bounded counter per domain and outcome, and no search term in any log, label or path.
