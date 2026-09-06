> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-09-06.
> **Owner area:** MISSION · **Related ADRs:** ADR-0159

# Home-page upcoming-missions overview

## Context & goal

The home page (`/`) shows the **upcoming missions of the next seven days** as a tile grid, nearest
planned start first (REQ-MISSION-012). Each tile carries the same fields as the legacy single "next
mission" card — name, status, schedule, optional calendar link, the **owning org unit**
(Staffel/SK/Bereich/OL, or "ownerless", read from the `owningSquadron` field) and a Markdown
description preview clamped to three lines. It is populated from `GET /api/v1/missions/search` and
uses the **broad mission-list scope** (the viewer's own org units plus every unit's
organisation-wide missions), deliberately wider than the own-unit `/next` lookup described below.

**The grid needs a login (REQ-SEC-052, ADR-0159).** It used to render for anyone who asked — the
largest anonymous read surface the tool had, carrying unit, status, meeting point and times for
every mission of the coming week. An unauthenticated visitor now gets the landing page, which
carries no mission data at all. Everything below therefore describes what a **member** sees; the
former "guest" tier had no other rule of its own than "public missions, no description", and it is
gone with its audience.

The single-mission `GET /api/v1/missions/next` endpoint (REQ-MISSION-003, REQ-MISSION-008) is
**retained** for API consumers — the Android app is one — but it is no longer what the home page
renders, and it too requires a login.

The `/next` lookup must surface only missions that are still **operationally relevant**. A mission
that has already been `COMPLETED` or `CANCELLED` but happens to carry a future planned-start time
(e.g. a cancelled future plan, or a closed mission whose schedule was never corrected) is not
something the squadron is heading towards, and returning it as "next mission" is misleading.

The `/next` lookup must also be **org-unit relevant**. A member should get the next mission of their
_own_ org unit (or, for a Bereich/Organisationsleitung leader, their subordinate units), not the
organisation-wide next mission that may belong to a squadron they have nothing to do with. A viewer
who belongs to no org unit (a brand-new account, an admin in "all squadrons" mode) keeps the
organisation-wide next mission as before. (The home-page grid above deliberately does **not**
apply this narrowing — it uses the broad mission-list scope per REQ-MISSION-012.)

## Requirements

### REQ-MISSION-003 — Next-mission banner only considers PLANNED or ACTIVE missions

The home-page next-mission lookup considers a mission **eligible** only when its `status` is
`PLANNED` or `ACTIVE`. Among the eligible missions, the one with the soonest `plannedStartTime`
strictly after "now" is the next mission.

- A `COMPLETED` or `CANCELLED` mission is **never** the next mission, even if its `plannedStartTime`
  is in the future and earlier than every eligible mission's.
- Every caller is authenticated, so `allowInternal = true` throughout and internal missions in
  scope are eligible. The `allowInternal = false` variant existed for the anonymous and role-less
  tier and has no caller left (ADR-0159); the query variant itself survives because the
  membershipless fallback below still uses the scope, not the flag.
- When no eligible mission is upcoming, the endpoint returns `204 No Content` and the page renders
  its empty state — unchanged.

**Acceptance**

- [ ] Given an upcoming `PLANNED` mission and an upcoming `ACTIVE` mission, the banner shows whichever has the earlier planned start.
- [ ] Given a `COMPLETED`/`CANCELLED` mission with an earlier future planned start and a later `PLANNED` mission, the banner shows the `PLANNED` mission — the terminal one is skipped, not merely sorted behind.
- [ ] A member sees an internal mission of their own unit in the banner.
- [ ] When no `PLANNED`/`ACTIVE` mission is upcoming, the endpoint returns 204.

**Enforced by:** `MissionServiceTest` (`getNextMission_*` — status passed to the finder),
`MissionRepositoryLookupOrderingTest`
(`findFirstByPlannedStartTimeAfterAndStatusIn_skipsTerminalStatusEvenWhenItSortsEarlier`).
**Code:** `MissionService#getNextMission`,
`MissionRepository#findFirstByPlannedStartTimeAfterAndStatusInOrderByPlannedStartTimeAsc` +
`#findFirstByPlannedStartTimeAfterAndIsInternalFalseAndStatusInOrderByPlannedStartTimeAsc`,
`MissionController` `/api/v1/missions/next`. (The home page no longer consumes `/next` — its
upcoming-missions grid is REQ-MISSION-012.)

### REQ-MISSION-008 — Next-mission banner is scoped to the viewer's org units

The next-mission lookup is restricted to missions **owned by the viewer's effective org units** when
the viewer has any:

- The effective scope is the same vector the scoped mission lists use
  (`OwnerScopeService#currentScopePredicate()`): a plain member's own Staffel/Spezialkommando
  membership(s); a Bereichsleitung/OL leader's **cascade-expanded** reach (their Bereich + its
  Staffeln/SKs, or — for the Organisationsleitung — every org unit), per
  [`org-unit-tenancy.md`](org-unit-tenancy.md) REQ-ORG-015; or, when an active org unit is pinned and
  within reach, exactly that one unit.
- Within scope, both **internal and public** missions of the viewer's own org units are eligible
  (REQ-MISSION-003's status filter still applies). Foreign missions are **excluded — including other
  units' public ones**; the cross-staffel public escape that widens the mission _lists_ deliberately
  does **not** apply to the banner, because the banner answers "what is _my_ unit heading towards".
- A viewer with **no** effective org-unit scope keeps the unchanged organisation-wide behaviour of
  REQ-MISSION-003: an admin in "all squadrons" mode (no active pin) and a member who belongs to no
  org unit both see the soonest eligible mission across the whole organisation.
- When no eligible mission exists in scope, the endpoint returns `204 No Content` and the page
  renders its empty state — unchanged.

**Acceptance**

- [ ] A member of Staffel A whose own next mission is later than Staffel B's public next mission sees Staffel A's mission, not Staffel B's.
- [ ] A Bereichsleitung sees the soonest mission across their Bereich's Staffeln/SKs; an OL member sees the soonest across every org unit.
- [ ] A member sees their own org unit's **internal** next mission (it is not hidden by the public-escape removal).
- [ ] A member with no org-unit membership still sees the organisation-wide next mission.
- [ ] A scoped viewer with no upcoming own-unit mission gets `204`, even if other units have upcoming missions.

**Enforced by:** `MissionServiceTest`
(`getNextMission_scopedMember_usesScopedQueryThenRefetches`,
`getNextMission_scopedPinned_passesActiveOrgUnitId`,
`getNextMission_scopedMember_noUpcoming_returnsEmptyWithoutRefetch`,
`getNextMission_allowInternal_refetchesByIdThroughGraph` (admin all-scope fallback),
`getNextMission_allowInternalFalse_usesTheIsInternalFalseVariantThenRefetches`),
`MissionRepositoryLookupOrderingTest`
(`findNextScopedMission_returnsOwnUnitNextSkippingForeignAndTerminal`,
`findNextScopedMission_allowInternalFalse_excludesOwnInternalMission`).
**Code:** `MissionService#getNextMission` + `#findNextScopedMissionHead`,
`MissionRepository#findNextScopedMission`,
`OwnerScopeService#currentScopePredicate` (scope vector + leadership cascade).

### REQ-MISSION-012 — Home-page upcoming-missions tile grid (next 7 days)

The home page (`/`) renders the missions whose `plannedStartTime` falls within the next seven days
(from "now" to "now + 7 days") as a **tile grid**, ordered by `plannedStartTime` ascending — the
nearest planned start first. This replaces the former single next-mission banner; the first tile is
the soonest upcoming mission.

- **Source & scope.** The grid is populated from `GET /api/v1/missions/search` with `start = now`,
  `end = now + 7d`, `status = PLANNED, ACTIVE`, `sort = plannedStartTime,asc`. It therefore uses the
  **broad mission-list scope** — the viewer's own org units' missions (internal and public) **plus**
  every unit's public missions via the cross-staffel public escape ([`org-unit-tenancy.md`](org-unit-tenancy.md)) —
  deliberately **wider** than the own-unit-only scope of the `/next` lookup (REQ-MISSION-008). This
  is an explicit, owner-approved product decision: the home overview answers "what is the
  organisation heading towards in the coming week", not only "my unit".
- **Own-unit highlight.** Because the scope is broad, a tile whose owning org unit is one the
  **authenticated** viewer is **directly assigned to** is flagged with a "Meine Einheit" chip
  (`home.upcoming.my_unit`, the square `.chip--primary`). "Directly assigned" spans **every org-unit
  kind** — Staffel, Spezialkommando, Bereich (when the viewer is a direct member) and
  Organisationsleitung (when a direct member) — **not** only Staffeln. The set comes from the
  kind-agnostic `GET /api/v1/users/me/org-unit-ids` self-endpoint (the caller's direct
  `org_unit_membership` rows mapped to their org-unit ids), unioned with the Staffel ids already on
  the `/me` `UserDto` as a fallback; the mission's `owningSquadron.id` is matched against that set.
  The **leadership cascade** of the `/next` lookup (REQ-MISSION-008) is intentionally **not** applied
  (a Bereichs-/OL-leader's subordinate units are not "their unit" here), and a member with no
  memberships never sees the chip. The chip is pinned to the **bottom-right** of the tile (right of the "Einsatz
  öffnen" CTA), **not** the top — so it never inserts a row above the tile body and every tile's body
  text starts at the same height whether or not it carries the chip.
- **Eligibility & redaction.** Only `PLANNED` / `ACTIVE` missions appear (terminal ones are excluded
  by the status filter). The outsider tier that hid the description from anonymous and role-less
  callers is gone with its audience (ADR-0159); what remains is REQ-SEC-007's peer redaction, which
  strips participant PII and never touched the tile fields
  ([`security-and-access.md`](security-and-access.md)).
- **Tile content.** Each tile carries the same fields as the legacy next-mission card — name, owning
  org unit (or "Keine"), status pill, meeting time, planned start, optional calendar link, and — for
  a Markdown description **preview clamped to three lines**; the full description is on the mission
  detail page. The "Einsatz öffnen" link opens the mission.
- **Empty state.** When no `PLANNED` / `ACTIVE` mission starts within the next seven days, the
  section renders its localized empty state (`home.upcoming.empty`); a mission whose planned start is
  more than seven days out is **not** shown, even if it is the soonest upcoming one.
- **Layout.** The announcement / information panel above the grid spans the full width and is only as
  tall as its content (minimal when collapsed); the grid is responsive (auto-fit tiles, single column
  on touch classes) per the design system ([`ui-design-system.md`](ui-design-system.md), REQ-UI-009).

**Acceptance**

- [ ] The home page lists every `PLANNED`/`ACTIVE` mission with a planned start in `[now, now+7d]` as a tile, nearest planned start first.
- [ ] A mission starting more than seven days out is not shown; when none qualify, the localized empty state renders.
- [ ] A member sees own-unit internal missions alongside the organisation-wide ones, each with the three-line description preview.
- [ ] An unauthenticated visitor gets the landing page and no tile at all (REQ-SEC-052).
- [ ] Each tile's description preview is truncated to three lines; the full text is visible on the mission detail page.
- [ ] The information panel spans the full width and collapses to its header height.
- [ ] An own-unit mission's tile shows the "Meine Einheit" chip; a foreign mission's tile does not.
- [ ] The own-unit match covers every direct membership kind — a Spezialkommando, a directly-assigned Bereich and a directly-assigned Organisationsleitung mission are flagged, not only Staffel missions — while a subordinate unit reached only via the leadership cascade is **not**.

**Enforced by:** `HomeControllerMvcTest`
(`home_ShouldShowOwningOrgUnitName_WhenUpcomingMissionIsOrgOwned`,
`home_ShouldShowOwnerlessLabel_WhenUpcomingMissionHasNoOrgUnit`,
`home_ShouldShowMyUnitChip_WhenUpcomingMissionIsOwnedByViewersStaffel`,
`home_ShouldShowMyUnitChip_WhenUpcomingMissionIsOwnedByViewersSpecialCommand`,
`home_ShouldNotShowMyUnitChip_WhenUpcomingMissionIsForeign`),
`OrgUnitMembershipServiceTest` (`findDirectMembershipOrgUnitIds_returnsEveryKindWithoutCascade`,
`findDirectMembershipOrgUnitIds_noMemberships_returnsEmpty`),
`UserControllerTest` (`getMyOrgUnitIds_derivesCallerFromJwt_andDelegatesToService`).
**Code:** `frontend/.../HomeController#home` (the next-7-days `/api/v1/missions/search` call + the
`/api/v1/users/me/org-unit-ids` own-unit lookup), `templates/index.html` (the `upcomingMissions`
tile grid + `.mission-tile__desc` clamp + the `myOrgUnitIds` chip gate), `static/css/styles.css`
(`.mission-tile*`); backend `UserController#getMyOrgUnitIds` +
`OrgUnitMembershipService#findDirectMembershipOrgUnitIds`. The mission grid reuses the existing
`MissionController` `/api/v1/missions/search` endpoint; the only new backend surface is the
kind-agnostic own-membership id lookup.

## Out of scope

- The peer field redaction applied to the returned mission DTO, and who may reach the page at all —
  owned by the security spec (see [`security-and-access.md`](security-and-access.md), REQ-SEC-007 /
  REQ-SEC-052).
- The broader mission list / search status filtering, which already accepts an explicit status set.
- The org-unit scope vector and the leadership cascade themselves — owned by
  [`org-unit-tenancy.md`](org-unit-tenancy.md) (`REQ-ORG-*`); this spec only consumes them.

## Open questions

None.
