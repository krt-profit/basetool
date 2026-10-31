> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-10-20.
> **Owner area:** HANGAR/UI · **Related ADRs:** [ADR-0258](../adr/0258-a-ships-org-unit-has-its-own-optimistic-counter.md), [ADR-0295](../adr/0295-a-hangar-import-matches-ships-by-pledge-and-keeps-pledge-data-private.md)

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
from `kernel/fragments/pagination.html`.

Who may see which ships (per-`sub` isolation — `/my-ships` derives the owner from the JWT, never the
URL) is unchanged; see [`security-and-access.md`](security-and-access.md), `HangarService` and the
per-ship gates of `HangarAccessPolicy` (`canSeeShip` / `canEditShip`, REQ-ORG-011).

## Requirements

### REQ-HANGAR-002 — Personal hangar paginates with server-side sort & search

The personal hangar MUST fetch one **server-side page** of the caller's ships from
`/api/v1/hangar/my-ships` (`page`/`size`/`search`/`fitted`) instead of the former unbounded `size=1000` pull,
and render the shared pagination component — the `.pagination` page-nav plus the square `.page-btn`
size picker from `kernel/fragments/pagination.html`. It adopts the shared page-size contract
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
- The optional `fitted` flag (`true` / `false`) narrows the page and its count to fitted or unfitted
  ships; absent means both. The page offers it as the segment „Alle · Bereit · Nicht bereit" and shows
  „n von m einsatzbereit" for the current search, read with one extra single-entry page request — the
  fitted count when the segment stands on „Alle", the unsegmented total otherwise (for „Nicht bereit"
  the fitted count is that total minus the listed one) — never by counting rendered rows. A value
  other than `true` / `false` in the page's `fitted` query lists every ship instead of failing.
- `/my-ships` is per-user data and MUST stay **uncached** (never routed through the shared
  `getCached`); only the reference catalogs (ship types, locations, manufacturers) are cached.

Writes (add / edit / delete / import / home-location) re-render the `hangarResults` fragment in place
(REQ-FE-001/005) carrying the active `page`/`size`/`search` from the address bar, so a write never
bounces the user back to page 0 or drops the filter. The home-location and delete-all affordances act
on **all** the caller's ships (not the current page), so their count reflects the page envelope's
`totalElements`.

A connected application's ship writes (REQ-XCH-017) run through the Hangar's own create, update and
delete (`HangarService`), so the ships they create, change or remove appear in this list exactly
like web writes — same ordering, filter and counts. After the write commits, the member's open
hangar page is told through its live-sync room (`hangar:{member}`, REQ-XCH-013) and refreshes
without a reload.

**Acceptance**

- [ ] `GET /api/v1/hangar/my-ships` honours `page`/`size` and returns page metadata
  (`totalElements`/`totalPages`); the order matches the rich comparator across page boundaries.
- [ ] The optional `search` parameter filters case-insensitively on ship-type or manufacturer name;
  blank means "no filter". The filter spans the whole fleet, not the rows currently rendered.
- [ ] The optional `fitted` parameter narrows the page and `totalElements` to fitted or unfitted
  ships; the readiness counter and the „Meine Schiffe" tab count follow the search, not the fitted
  segment, and update in place after every swap.
- [ ] The frontend offers exactly the page sizes 10 / 50 / 100 (shared `pageSizePicker` fragment,
  default 50); any other client-supplied `size` snaps back to the default before the backend call;
  a negative `page` clamps to 0. The size picker hides while the total fits the smallest size.
- [ ] Changing the page size re-enters at page 0, and pagination/page-size links preserve an active
  search term and fitted segment — switching the size never silently drops the filter. The
  pagination controls live **inside** the `hangarResults` AJAX-swap fragment.
- [ ] The page renders distinct empty states for "no ships in hangar" vs. "no match for this search",
  and the filter stays clearable when a search yields nothing.
- [ ] An add / edit / delete / import / home-location write re-renders the table in place keeping the
  active page/size/search; the home-location modal's ship count reflects `totalElements`.

**Enforced by:** `HangarPaginationMvcTest`, `HangarPageControllerMvcTest`, `HangarPagePatternRenderTest`,
`ShipRepositoryPersonalHangarTest`, `HangarControllerTest`, `HangarPaginationE2eTest` · **Code:**
`HangarController#getMyShips`, `HangarService#getMyShipsFiltered`,
`ShipRepository#findByOwnerIdFiltered`, `PaginationUtil#createUnsortedPageRequest`,
`HangarPageController#viewHangar`, `frontend/src/main/resources/templates/hangar/hangar.html`,
`frontend/src/main/resources/templates/kernel/fragments/pagination.html` · **Issues:** #773 (performance
audit item 10), #772 (orders/refinery pagination).

*Amended 2026-10-03 (website overhaul phase 3):* `/my-ships` takes the optional `fitted` filter, the
page offers it as the segment „Alle · Bereit · Nicht bereit" beside the search with the readiness
counter „n von m einsatzbereit", and the list is the first tab „Meine Schiffe (n)" of the one hangar
page whose second tab is the unit overview (REQ-HANGAR-001). The import, „Home-Location für alle
setzen" and „Hangar leeren …" moved from the box under the table into the page head (import as a ghost
button, the other two in the ⋯ menu).

### REQ-HANGAR-004 — Every hangar mutation is audited

Every change to a hangar writes exactly one event to the **Hangar** audit area
(`AuditDomain.HANGAR`, REQ-AUDIT-001), whichever channel made it — the web, the app, an admin on
another member's hangar, the file or Fleetview import, the fitted reset of a caller with officer reach (limited to the units it reaches), or a connected
application through the exchange API (REQ-XCH-017), which writes through the same create, update and
delete and is recorded with its client. A run that changed nothing records nothing. The subject is the ship,
labelled by its ship type, never by its free-text name; details hold ids, counts and changed field
names, never values. Deleting a ship first detaches it from mission units and records
`MISSION_UNIT_UPDATED` in the Missionen area for each.

**Acceptance**

- [x] Create, edit (only with a changed field), delete, empty, import (only when ships were
  created or updated, REQ-HANGAR-015), fitted reset (only when a flag was cleared) and home
  location each record one event.
- [x] A deleted ship's mission units are detached and each detachment is recorded.
- [x] The Hangar tab in the admin audit viewer lists, filters, exports and purges the area.
- [x] A connected application's create, update and delete record the same events.
  *`ExchangeShipWriteControllerTest`.*

**Enforced by:** `HangarServiceTest`, `HangarImportServiceTest`, `AuditReportServiceTest`,
`AdminAuditLogPageControllerTest`, `AuditReportProxyControllerTest`, `AuditPdfTitleKeysTest`,
`ExchangeShipWriteControllerTest`, `HangarModuleContractTest`,
`HangarShipOrgUnitChangeIntegrationTest` ·
**Code:** `HangarService`, `HangarImportService`, `AuditEventType.HANGAR_*` · **Issues:** #2098
(epic #2078).

### REQ-HANGAR-009 — The owner changes a ship's org unit afterwards

The owner of a ship MUST be able to change its owning org unit after creation, from the row action
„Einheit ändern" in „Meine Schiffe". The choice is one of the owner's **direct** memberships, of
any of the four kinds, or „Keine Einheit"; neither the caller's leadership scope nor an
active-context pin widens it. The ship then appears in, or leaves, that unit's hangar overview
(REQ-HANGAR-001/003), and every row of „Meine Schiffe" shows its unit.

The change has its own endpoint, `PUT /api/v1/hangar/ships/{id}/org-unit` with `orgUnitId` (or
`null`) and the ship's `owningOrgUnitVersion`, and its own optimistic counter (ADR-0258): it never
bumps the ship's `version`, and an edit of name, type, insurance, location or fitted never bumps the
counter, so neither 409s nor overwrites the other. Leaving the unit unchanged writes nothing and
records nothing, even when the stored unit is one the owner has since left; the dialog then offers
that unit as the current choice instead of silently clearing it. A change records `HANGAR_SHIP_ORG_UNIT_CHANGED` (REQ-HANGAR-004) with `from` and
`to` as `kind:id` or `none`, no names. A connected application changes the unit under the same
rules through an `upsert` with `orgUnit` (REQ-XCH-017), audited with its client.

The table swaps in place and the owner's other open hangar pages refresh through the live-sync room
`hangar:{owner}` (REQ-FE-001/010). After the commit the backend announces the move to the unit-tab
rooms of the old and the new unit (`hangar-unit:{orgUnitId}`) and to the all-units room
(`hangar-units`), so every open unit overview adds or drops the ship in place (REQ-HANGAR-013,
ADR-0260). The create dialog keeps the „Zuordnen zu" picker; the edit dialog does not show it.

**Acceptance**

- [x] A ship without a unit is assigned to one of the owner's units from the row action and shows
  it in place; „Keine Einheit" removes it again.
- [x] Another member's ship → 403; a target outside the owner's direct memberships → 400; a stale
  or missing `owningOrgUnitVersion` → 409 / 400.
- [x] A field edit with the old ship `version` succeeds after a unit change and keeps the unit, and
  a unit change with the old counter succeeds after a field edit; an edit flushed from a stale
  entity never overwrites a concurrent unit change.
- [x] An unchanged unit writes nothing and records no audit event.
- [x] A change is audited with `from` / `to` as `kind:id` or `none`; the Hangar filter of the audit
  viewer lists it in DE and EN.
- [x] The edit dialog hides the unit picker; the create dialog keeps it.

**Enforced by:** `HangarShipOrgUnitChangeIntegrationTest`, `HangarWriteAjaxControllerTest`,
`HangarBackendClientTest`, `HangarPagePatternRenderTest` · **Code:**
`HangarController#changeMyShipOrgUnit`, `HangarService#changeShipOrgUnit`,
`ShipRepository#changeOwningOrgUnitIfVersionMatches`,
`OrgUnitStampingService#resolveOwnerMembershipTarget`,
`HangarShipOrgUnitProxyController`, `hangar/hangar.html`, `hangar/hangar.js` ·
**Issues:** #2596 (epic #2595, #2581).

### REQ-HANGAR-010 — The owner changes the org unit of several ships at once

The owner MUST be able to select several ships in „Meine Schiffe" and move them to one of their
direct memberships, or to „Keine Einheit", in one action (REQ-HANGAR-009 for the single ship). This
is the repair after a connected application created many ships without a unit.

- **Selection bar.** Each row has a checkbox, the list head a box that selects the shown page. While
  anything is selected, a sticky bar below the list shows the count, „Auswahl aufheben" and „Einheit
  ändern" (design system: the sticky form-actions bar, ghost / outline buttons, one CTA in the
  dialog). The selection holds only ships the list shows: a filter, page change or peer refresh drops
  the others.
- **Dialog.** The „Einheit ändern" dialog of REQ-HANGAR-009 names the number of selected ships and is
  preset to their unit when they all share one (a unit the owner has since left included), otherwise
  to „Keine Einheit".
- **Endpoint.** `POST /api/v1/hangar/ships/bulk-org-unit` with `shipIds` (1 – 1000) and `orgUnitId`
  (or `null`) answers the `changed` and `skipped` counts. The ships are locked pessimistically in
  ascending id order and carry no counters, like REQ-INV-036 / REQ-INV-052, so two bulk actions over
  overlapping selections queue instead of deadlocking. Every moved ship's `owningOrgUnitVersion`
  goes up (ADR-0258), so a single-ship dialog opened before the bulk action 409s.
- **Refusal as a whole.** An unknown ship → 404, another member's ship → 403, a target outside the
  owner's direct memberships → 400; nothing changes. Ships already in the target are skipped and
  counted; a selection already wholly in the target writes nothing, records nothing and validates no
  target.
- **Audit.** One `HANGAR_SHIPS_BULK_ORG_UNIT_CHANGED` summary (REQ-HANGAR-004) with `changed`,
  `skipped`, `from` (the distinct previous units, `kind:id` or `none`, comma-separated) and `to`; no
  names, no per-ship events.
- **Live update.** The list swaps in place, the bar hides, a toast names the counts, and the owner's
  other open hangar pages refresh through `hangar:{owner}` (REQ-FE-001/010). The open unit overviews
  of every unit a ship left or joined refresh in place, as in REQ-HANGAR-009 (REQ-HANGAR-013).

**Acceptance**

- [x] Selected ships move to a unit and back to „Keine Einheit" in one request each, in place.
- [x] A selection with a foreign or unknown ship is refused as a whole and changes nothing.
- [x] Ships already in the target are skipped; one summary event records the counts and from / to.
- [x] Two concurrent bulk actions over overlapping selections in opposite orders both finish.
- [x] A bulk change makes a stale single-ship change 409.

**Enforced by:** `HangarShipBulkOrgUnitChangeIntegrationTest`,
`HangarShipBulkOrgUnitConcurrencyIntegrationTest`, `HangarWriteAjaxControllerTest`,
`HangarBackendClientTest`, `HangarPagePatternRenderTest`, `HangarBulkUnitE2eTest` · **Code:**
`HangarController#bulkChangeMyShipsOrgUnit`, `HangarService#bulkChangeShipOrgUnit`,
`ShipRepository#lockAllByIdOrderById`, `HangarShipOrgUnitProxyController`, `hangar/hangar.html`,
`hangar/hangar.js` · **Issues:** #2599 (epic #2595, #2581).

### REQ-HANGAR-011 — An admin keeps a member's hangar on its own page

An admin MUST be able to list, add, edit and delete the ships of one member on
`/admin/members/{id}/hangar`, through the admin endpoints of REQ-HANGAR-008
(`/api/v1/hangar/admin/users/{userId}/ships`), so the member is told about each change.

- **Access.** The page and its writes are `ROLE_ADMIN` only (frontend class gate, backend
  `hasRole('ADMIN')`); anyone else gets 403. The member edit page links to it in its page head, and
  only for an admin.
- **List.** The member's ships, ordered by name and paginated (10 / 50 / 100), with the same columns as
  „Meine Schiffe"; the unit changes through its own action (REQ-HANGAR-012). A hint says that every
  change notifies the member.
- **Writes.** Add, edit and delete use the ship dialog of „Meine Schiffe" (design system: one CTA in
  the page head and one in the dialog, icon-only edit per row, quiet-danger delete behind a danger
  confirm). An added ship takes the unit picked from the member's direct memberships; with more than
  one, the pick is required and starts empty, never preset from the admin's own active unit. With
  one or none the ship is stamped as for the member's own add. An edit echoes the ship's `version` and
  never changes the unit; a stale version is a 409 with the conflict dialog.
- **Live update.** Every write swaps the list and the count in place (REQ-FE-001) and tells the
  member's open „Meine Schiffe" pages through `hangar:{memberId}`; the page listens on the same room
  and re-pulls its list when the member, another admin or a connected application changes the hangar
  (REQ-FE-015, ADR-0260).

**Acceptance**

- [x] An admin reaches the page from the member edit page; a non-admin gets 403 on the page and its
  writes.
- [x] Add, edit and delete go to the member's admin paths and update the list in place.
- [x] An edit carries the version and no unit; an add carries the picked unit and no version.

**Enforced by:** `MemberHangarPageControllerTest`, `HangarBackendClientTest`,
`MemberEditPageRenderTest`, `RouteGateSnapshotTest`, `HangarIntegrationTest`,
`HangarNoticeIntegrationTest` · **Code:** `MemberHangarPageController`, `HangarBackendClient`,
`hangar/member-hangar.html`, `hangar/member-hangar.js`, `identity/member-edit.html` · **Issues:**
#2597 (epic #2595, #2581).

### REQ-HANGAR-012 — An admin changes the org unit of a member's ships

An admin MUST be able to change the owning org unit of a member's ships on the admin member hangar
(REQ-HANGAR-011), singly from the row action and in bulk from a selection bar, with the rules of
REQ-HANGAR-009 and REQ-HANGAR-010 applied to the **member**.

- **Targets.** The member's direct memberships (all four kinds) or „Keine Einheit", never the
  admin's own: a unit only the admin belongs to is a 400. The dialog offers the member's units, and a
  unit the member has since left as the current choice.
- **Endpoints.** `PUT /api/v1/hangar/admin/users/{userId}/ships/{shipId}/org-unit` (`orgUnitId`,
  `owningOrgUnitVersion`; one conditional update on the ship's own counter, ADR-0258) and
  `POST /api/v1/hangar/admin/users/{userId}/ships/bulk-org-unit` (`shipIds` 1 – 1000, `orgUnitId`;
  pessimistic locks in ascending id order, `changed` / `skipped` counts). Both are
  `hasRole('ADMIN')`; anyone else gets 403. A ship of another member under the member's path is a
  403, an unknown ship in a selection a 404, a stale counter a 409. An unchanged unit writes,
  records and announces nothing.
- **Audit.** The events of REQ-HANGAR-009 / -010 with the admin as actor and the member as target
  user; the single event's subject is the ship. No names, no free text.
- **Notice.** The member hears one notice per action, after the commit: `HANGAR_CHANGED_BY_ADMIN`
  with `changeCode` `ORG_UNIT_CHANGED` and the ship type when one ship moved, and one summary
  `HANGAR_SHIPS_ORG_UNIT_CHANGED_BY_ADMIN` with the count when several did (seeded rule, V291,
  `EVENT_RECIPIENT`, actor excluded; [`hangar-notifications.md`](hangar-notifications.md)
  REQ-HANGAR-008). An admin acting on their own ships hears nothing.
- **UI.** The page gets the row action, the checkboxes, the sticky selection bar and the „Einheit
  ändern" dialog of „Meine Schiffe" (design system: icon-only row action, ghost / outline bar
  buttons, one CTA in the dialog, the conflict dialog on a 409). Every change swaps the list in place
  and clears the selection; a toast names the counts (REQ-FE-001). The member's open hangar pages
  and the affected unit overviews follow through live sync (REQ-HANGAR-011, REQ-HANGAR-013).

**Acceptance**

- [x] An admin moves one ship and several ships of a member; the admin page updates in place.
- [x] A target outside the member's direct memberships → 400, even when the admin belongs to it.
- [x] A non-admin calling the admin endpoints → 403.
- [x] The member receives one notice per single or bulk change; an admin acting on their own ships
  hears nothing.
- [x] Audit events record the admin as actor and the member's ship(s) as subject, no free text.

**Enforced by:** `HangarShipAdminOrgUnitChangeIntegrationTest`, `HangarNoticeIntegrationTest`,
`SeededNotificationRulesIntegrationTest`, `MemberHangarPageControllerTest`,
`HangarBackendClientTest`, `NotificationPageControllerTest`, `MemberHangarE2eTest` · **Code:**
`HangarController#changeUserShipOrgUnit`, `#bulkChangeUserShipsOrgUnit`,
`HangarService#changeShipOrgUnitByAdmin`, `#bulkChangeShipOrgUnitByAdmin`, `HangarNotices`,
`MemberHangarShipProxyController`, `hangar/member-hangar.html`, `hangar/member-hangar.js`,
`V291__seed_admin_ship_org_unit_notification_rule.sql` · **Issues:** #2601 (epic #2595, #2581).

### REQ-HANGAR-014 — An import never fails on the member's org units

„Schiffsliste importieren" offers no unit picker, so the import MUST NOT refuse a member who belongs
to several org units. The ships it creates go to the member's only direct membership, else to the
active org unit of the sidebar when it is one of the member's direct memberships, else to no unit;
the member assigns them afterwards with „Einheit ändern" (REQ-HANGAR-009 / -010). This is the rule
of a connected application's create (REQ-XCH-017) plus the pin, and an exception to REQ-ORG-017's
„pin, else choose", which needs a picker the import does not have.

**Acceptance**

- [x] A member of several units with „Alle Staffeln" active imports successfully; the ships carry no
  unit.
- [x] With an active org unit among the member's memberships, the ships carry that unit.
- [x] A member of exactly one unit, or of none, imports as before.

**Enforced by:** `OwnerScopeServiceTest` (`resolveOrgUnitForImport_*`),
`HangarImportIntegrationTest#importFleetview_memberOfSeveralUnitsWithoutPin_importsShipsWithoutUnit`,
`HangarImportServiceTest` · **Code:** `OrgUnitStampingService#resolveOrgUnitForImport`,
`HangarImportService#importShips`.
### REQ-HANGAR-015 — The Hangar Extension export imports status and pledge data

„Schiffsliste importieren" MUST also accept the two exports of the browser extension „Star Citizen:
Hangar Extension": the fleet JSON (`my-fleet-*.json`) and the hangar CSV (`my-hangar-*.csv`). The
format is detected from the payload, like the other formats (REQ-HANGAR-014 keeps the unit rule).

- **Ships only.** Only ship types go into the hangar. From the JSON every record is a ship; from the
  CSV only the rows whose `Content Type` is `Ship` or `Included Ship`. Skins, equipment,
  components, hangar decorations, credits, coupons, insurance rows and rows without a type are
  dropped silently; an `Insurance` row only lends its insurance to the ships of the same pledge. A
  ship row that matches no ship type is listed under „Nicht erkannt", as for every format. A CSV
  name is matched without its leading manufacturer word first („Mirai Fury LX" as „Fury LX"), so
  the prefix does not make a model ambiguous with its shorter siblings, then in full.
- **Production status.** The JSON's `status` is stored per ship as `FLIGHT_READY`,
  `IN_CONCEPT` or `IN_PRODUCTION` (case, spaces and hyphens ignored; any other text stores
  nothing). „Meine Schiffe" and the admin member hangar show it as a chip under the ship name:
  „Flugfähig" (success), „Konzept" (warning), „In Produktion" (info).
- **Pledge data.** From the CSV, `Pledge ID`, `Pledge Date` (`October 15, 2026` or ISO),
  `Pledge Cost` (amount and currency, `15,000.00 USD`), `Meltable` and `Giftable` (`yes` / `no`)
  are stored per ship when present and readable; an unreadable value stores nothing instead of
  failing the import. The cost is the price of the whole pledge, shared by every ship in it. The
  row shows „Pledge #id · date · cost · schmelzbar / nicht schmelzbar · verschenkbar / nicht
  verschenkbar".
- **Insurance.** `LTI` and „Lifetime Insurance" become `LTI`, `<n>MI` and „<n> Month Insurance"
  become `n` months (0 – 120); a ship without readable insurance gets the default `0`. Each
  created ship takes its own entry's insurance.
- **Matching instead of duplicating.** Per ship type each upload entry is matched to one ship
  already in the hangar — first a ship with the same pledge id, then a ship without a pledge id
  (preferring one with the same insurance); only entries left over create ships. A matched ship
  takes the entry's status and pledge data and keeps everything else (name, insurance, location,
  fitted, unit). So JSON then CSV, or the same file twice, never duplicates ships; ships missing
  from the upload are never deleted. The other formats follow the same matching and carry no
  status or pledge data. The importer's ships are locked in ascending id order for the run.
- **Locking.** Status and pledge columns are written only by the import and lie outside the ship's
  `version` (ADR-0295): an import never 409s a concurrent edit, and an edit never overwrites
  imported data.
- **Privacy.** Pledge data are the owner's purchase data. They leave the backend only on the
  owner's own hangar endpoints (`/my-ships`, the owner's add / edit / unit change) and on the
  admin member hangar; `/ships`, the mission views and every other `ShipDto` carry `pledge = null`.
  The production status is shown wherever the ship is.
- **Result.** The result dialog shows „Importiert", „Aktualisiert", „Bereits im Hangar" and
  „Nicht erkannt"; the list refreshes in place when ships were created or updated. The audit event
  `HANGAR_IMPORTED` (REQ-HANGAR-004) is recorded when ships were created or updated, with the
  counts `created`, `updated`, `alreadyPresent` and `unmatched`, never pledge values.

**Acceptance**

- [x] The JSON creates one ship per record with its status and insurance; the CSV creates one ship
  per `Ship` / `Included Ship` row with its pledge data, and nothing for the other rows.
- [x] A re-import, and the CSV after the JSON, creates no ship and fills the existing ones; a
  re-import that changes nothing records no event.
- [x] Pledge data appear on „Meine Schiffe" and the admin member hangar, never in `/ships` or a
  mission.
- [x] An import does not change a ship's `version`.

**Enforced by:** `HangarImportServiceTest` (`importShips_hangarExtension*`),
`HangarExtensionCsvParserTest`, `HangarImportIntegrationTest#importHangarExtension_jsonThenCsv_fillsTheSameShipsWithoutDuplicates`,
`ShipMapperTest`, `MissionPeerRedactorTest`, `HangarControllerTest`, `HangarPagePatternRenderTest` ·
**Code:** `FleetExportParser`, `HangarExtensionCsvParser`, `HangarImportService#importShips`,
`ShipRepository#lockAllOwnedByOrderById`, `ShipMapper#toOwnerDto`, `ShipProductionStatus`,
`V309__add_ship_pledge_and_production_status.sql`, `hangar/fragments/ship-meta.html`.

## Out of scope

- The **squadron** hangar overview (`/hangar/squadron`) — REQ-HANGAR-001 — and the **admin per-user**
  hangar endpoint (`/api/v1/hangar/admin/users/{userId}/ships`), which keeps its existing `{name,
  insurance, fitted, id}` sort whitelist (its page is REQ-HANGAR-011, its unit actions
  REQ-HANGAR-012).
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
