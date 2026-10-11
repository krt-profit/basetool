> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-10-10.
> **Owner area:** HANGAR · **Related ADRs:** [ADR-0245](../adr/0245-group-recipients-time-based-notices-and-muting-extend-the-notification-engine.md)

# Hangar and blueprint notifications

## Context & goal

Issue #2414 tells a member when somebody else touched their ships or blueprints, and tells a mission's
leadership when a deleted ship drops out of a plan. Each notice is published after the commit, never
for the actor, carries only ship type names, mission and unit names, counts and the acting member's
display name (never a ship's own free-text name), and has a seeded, admin-editable rule. Each can be
muted (REQ-NOTIF-027).

## Requirements

### REQ-HANGAR-005 — A ship assigned to a unit tells its owner

`MissionStructureService#addUnitToMission` and `#updateMissionUnit` call
`MissionNotificationPublisher#shipAssigned` when a unit gets a ship (new, or a different one) whose
owner is not the acting member; the default rule (`EVENT_RECIPIENT`, actor excluded) names the ship type,
the mission, the unit and the planned start. Taking the ship off the unit, or swapping it, publishes
`HANGAR_SHIP_UNASSIGNED_FROM_UNIT`, which clears the former owner's notice for that unit (per-recipient
supersede, REQ-NOTIF-025); keeping the same ship announces nothing. The notice belongs to the unit
(`MISSION_UNIT`), so it links to the owner's hangar.

### REQ-HANGAR-006 — A deleted ship tells the mission about its gap

Deleting a ship (`HangarService#deleteShip`, `#deleteAllShipsForUser`) runs the mission module's
`MissionUnitShipRelease`, which publishes `HANGAR_SHIP_DELETED_FROM_MISSION` for every unit the ship was
assigned to, unless the unit's mission is `COMPLETED` or `CANCELLED`. The default
rule notifies the mission leadership (`MISSION_LEADERSHIP`) and the unit's responsible member
(`EVENT_RECIPIENT`), names the ship **type** (never the ship's own name), the mission and the unit, and
excludes the actor.

### REQ-HANGAR-007 — A bulk reset of the fitted marks tells each owner

`HangarService#resetAllFittedStatus` counts the fitted ships per owner in the caller's scope before it
clears them and publishes one `HANGAR_FITTED_RESET_FOR_OWNER` per owner with their count. The default
rule notifies the owner and excludes the actor, so an officer resetting their own ships hears nothing.

### REQ-HANGAR-008 — An admin changing a member's hangar or blueprints tells the member

`HangarService#addShipByAdmin`, `#updateShipByAdmin` and `#deleteShipByAdmin` (the admin endpoints under
`/hangar/users/{userId}/ships`) publish `HANGAR_CHANGED_BY_ADMIN`; `PersonalBlueprintService#addForUser`,
`#addBatchForUser`, `#updateForUser`, `#deleteForUser` and the admin import apply
(`#announceImportByAdmin`) publish `BLUEPRINT_CHANGED_BY_ADMIN`; the global purge
`#deleteAllForAllUsers` counts the removable blueprints per owner first and publishes
`BLUEPRINT_PURGED_BY_ADMIN` to each member who lost some, with a hint to re-import or re-sync. The coded
`changeCode` is `ADDED`, `UPDATED`, `DELETED` or `IMPORTED`. An admin changing their own data hears
nothing.

**Acceptance**

- [x] Each event reaches the member its seeded rule names and never the actor.
- [x] Unassigning or swapping a ship clears the former owner's notice; keeping it announces nothing.
- [x] A ship of a finished mission, a ship in no unit and an admin's own data announce nothing.
- [x] One notice per member per reset or purge, with that member's count.
- [x] The default rules exist, are enabled, and use the intended selector kinds.

**Enforced by:** `HangarNoticeIntegrationTest`, `MissionStructureShipNoticeTest`, `MissionUnitShipReleaseTest`,
`HangarServiceTest`,
`PersonalBlueprintServiceTest`, `SeededNotificationRulesIntegrationTest`,
`NotificationPageControllerTest` (`targetOf_…`) · **Code:** `mission/api/events/MissionNotices`,
`admin/api/events/HangarNotices`, `mission/internal/MissionNotificationPublisher`,
`mission/internal/MissionStructureService`, `mission/internal/MissionUnitShipRelease`, `service/HangarService`, `service/PersonalBlueprintService`,
`repository/ShipRepository#countFittedByOwnerScoped`,
`repository/PersonalBlueprintRepository#countRemovableByOwner`,
`V280__seed_hangar_and_blueprint_notification_rules.sql` · **Issues:** #2414
