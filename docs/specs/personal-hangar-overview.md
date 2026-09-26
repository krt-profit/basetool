> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-09-26.
> **Owner area:** HANGAR/UI · **Related ADRs:** none

# Personal hangar overview — pagination & server-side sort/filter

## Context & goal

The personal hangar (`GET /hangar`, "Meine Schiffe") lists the calling user's own ships. It
originally fetched the whole hangar in one unbounded `size=1000` response, sorted it client-side with
a rich multi-key comparator (manufacturer → ship type → insurance tier → insurance amount desc →
location → fitted → name), and filtered it in the DOM. As a fleet grows this is wasteful and
silently truncates beyond the fetch cap. This was the personal-hangar tail of the performance-audit
pagination work (the squadron overview is REQ-HANGAR-001, the order lists are REQ-ORDERS-020 /
REQ-REFINERY-019); it was deferred there because, unlike those, the hangar could not paginate without
backend work — the computed insurance-tier ordering is a bucket, not a column, so it cannot be
expressed as a plain `Sort`, and the text filter had to move server-side to span every page.

This spec pins the listing contract after the rework: one server-side page, the rich ordering and the
text filter applied by the backend across the user's whole fleet, and the shared pagination component
from `fragments/pagination.html`.

Who may see which ships (per-`sub` isolation — `/my-ships` derives the owner from the JWT, never the
URL) is unchanged; see [`security-and-access.md`](security-and-access.md) and `HangarService`.

## Requirements

### REQ-HANGAR-002 — Personal hangar paginates with server-side sort & search

The personal hangar MUST fetch one **server-side page** of the caller's ships from
`/api/v1/hangar/my-ships` (`page`/`size`/`search`) instead of the former unbounded `size=1000` pull,
and render the shared pagination component — the `.pagination` page-nav plus the square `.page-btn`
size picker from `fragments/pagination.html`. It adopts the shared page-size contract
(REQ-INV-013 / REQ-API-005): **page sizes {10, 50, 100} with a default of 50**; a client-supplied
`size` outside that set snaps back to the default before the backend call, and a negative `page`
clamps to 0.

The ordering and the filter MUST be applied by the **backend** so they span the user's whole fleet,
not just the current page — there is no client-side `SHIP_SORT` that would only reorder the rows
already in the DOM:

- The fixed order replays the former comparator verbatim: manufacturer name, ship-type name, the
  computed insurance tier (`LTI` < numeric < unset — a bucket, not a column), insurance amount
  descending within the numeric tier, location name, fitted-first, ship name, and the row id as a
  stable tiebreaker so pages never interleave. The amount key casts `insurance` to an integer, which
  is safe because `Ship.insurance` is `@Pattern`-constrained to `{0, 1–120, LTI}` and the `CASE`
  guards the cast against the three non-numeric cases.
- The optional `search` term filters case-insensitively on ship-type *or* manufacturer name (parity
  with the former client-side filter and the squadron overview, REQ-HANGAR-001); types without a
  manufacturer still match on their own name (LEFT-JOIN semantics). Blank input means "no filter".
- `/my-ships` is per-user data and MUST stay **uncached** (never routed through the shared
  `getCached`); only the reference catalogs (ship types, locations, manufacturers) are cached.

Writes (add / edit / delete / import / home-location) re-render the `hangarResults` fragment in place
(REQ-FE-001/005) carrying the active `page`/`size`/`search` from the address bar, so a write never
bounces the user back to page 0 or drops the filter. The home-location and delete-all affordances act
on **all** the caller's ships (not the current page), so their count reflects the page envelope's
`totalElements`.

**Acceptance**

- [ ] `GET /api/v1/hangar/my-ships` honours `page`/`size` and returns page metadata
  (`totalElements`/`totalPages`); the order matches the rich comparator across page boundaries.
- [ ] The optional `search` parameter filters case-insensitively on ship-type or manufacturer name;
  blank means "no filter". The filter spans the whole fleet, not the rows currently rendered.
- [ ] The frontend offers exactly the page sizes 10 / 50 / 100 (shared `pageSizePicker` fragment,
  default 50); any other client-supplied `size` snaps back to the default before the backend call;
  a negative `page` clamps to 0. The size picker hides while the total fits the smallest size.
- [ ] Changing the page size re-enters at page 0, and pagination/page-size links preserve an active
  search term — switching the size never silently drops the filter. The pagination controls live
  **inside** the `hangarResults` AJAX-swap fragment.
- [ ] The page renders distinct empty states for "no ships in hangar" vs. "no match for this search",
  and the filter stays clearable when a search yields nothing.
- [ ] An add / edit / delete / import / home-location write re-renders the table in place keeping the
  active page/size/search; the home-location modal's ship count reflects `totalElements`.

**Enforced by:** `HangarPaginationMvcTest`, `HangarPageControllerMvcTest`,
`ShipRepositoryPersonalHangarTest`, `HangarPaginationE2eTest` · **Code:**
`HangarController#getMyShips`, `HangarService#getMyShipsFiltered`,
`ShipRepository#findByOwnerIdFiltered`, `PaginationUtil#createUnsortedPageRequest`,
`HangarPageController#viewHangar`, `frontend/src/main/resources/templates/hangar.html`,
`frontend/src/main/resources/templates/fragments/pagination.html` · **Issues:** #773 (performance
audit item 10), #772 (orders/refinery pagination).

### REQ-HANGAR-004 — Every hangar mutation is audited

Every change to a hangar writes exactly one event to the **Hangar** audit area
(`AuditDomain.HANGAR`, REQ-AUDIT-001), whichever channel made it — the web, the app, an admin on
another member's hangar, the file or Fleetview import, the officer's fitted reset — and later the
exchange API (epic #2078). A run that changed nothing records nothing. The subject is the ship,
labelled by its ship type, never by its free-text name; details hold ids, counts and changed field
names, never values. Deleting a ship first detaches it from mission units and records
`MISSION_UNIT_UPDATED` in the Missionen area for each.

**Acceptance**

- [x] Create, edit (only with a changed field), delete, empty, import (only when ships were
  created), fitted reset (only when a flag was cleared) and home location each record one event.
- [x] A deleted ship's mission units are detached and each detachment is recorded.
- [x] The Hangar tab in the admin audit viewer lists, filters, exports and purges the area.

**Enforced by:** `HangarServiceTest`, `HangarImportServiceTest`, `AuditReportServiceTest`,
`AdminAuditLogPageControllerTest`, `AuditReportProxyControllerTest`, `AuditPdfTitleKeysTest` ·
**Code:** `HangarService`, `HangarImportService`, `AuditEventType.HANGAR_*` · **Issues:** #2098
(epic #2078).

## Out of scope

- The **squadron** hangar overview (`/hangar/squadron`) — REQ-HANGAR-001 — and the **admin per-user**
  hangar (`/api/v1/hangar/users/{userId}/ships`), which keeps its existing `{name, insurance, fitted,
  id}` sort whitelist.
- New user-facing sortable columns. The personal hangar keeps its fixed rich order; the `/my-ships`
  endpoint no longer accepts a `sort` parameter (the order is server-fixed).
- The add / edit / delete / import / home-location flows themselves — this spec only governs the list
  view's pagination, ordering and filter. The home-location count is the only count this spec pins.
- The shared pagination fragment's look — governed by the design system
  ([`ui-design-system.md`](ui-design-system.md)).

## Open questions

- With an active `search`, `totalElements` (and therefore the home-location modal's ship count)
  reflects the filtered match count, while the home-location/delete-all actions still operate on the
  caller's whole fleet. Accepted as a minor display nuance — these global affordances are normally
  used without a filter, and the delete-all confirmation shows no count.
