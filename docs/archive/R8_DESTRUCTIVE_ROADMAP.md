> **Archived 2026-09-22.** Doc type: Historical plan — frozen, kept as a record and no longer updated. All six steps shipped (V100–V105, 2026-05-23). It was deleted from the repository on 2026-05-29 (#273) and restored here on 2026-09-22 because the code still cites it.
>
> **Current truth:** [`org-unit-tenancy.md`](../specs/org-unit-tenancy.md), [`db/migration/README.md`](../../backend/src/main/resources/db/migration/README.md). Index of the archive: [`README.md`](README.md).

# R8 Destructive Cleanup — Roadmap

Companion document to `SPEZIALKOMMANDO_PLAN.md` §10 PR-6 / PR-7. Tracks the
steps still pending after the R8 follow-up sweep ships V100 (NOT NULL tighten +
legacy NULL-allowance) and the parallel `resolveOrgUnitForPickerOutput` method
that unblocks SK aggregate ownership.

> **Status (2026-05-23):** all six steps shipped. V100 + new resolver,
> caller migration, lifecycle-hook drop, V101/V102/V103 destructive schema
> drops, and the corresponding Java cleanup all landed. The legacy
> `squadron` table is gone; every reference to a Squadron now resolves via
> `org_unit` (kind='SQUADRON').
>
> Historic context kept for archaeology:

## What R8 already shipped

- **V100 migration**: `owning_org_unit_id` becomes NOT NULL on every staffel-
  scoped aggregate; `owning_squadron_id` becomes nullable. SK-owned aggregates
  are now representable at the DB layer.
- **`OwnerScopeService.resolveOrgUnitForPickerOutput`**: new method returning
  `OrgUnit` (Staffel or SK) instead of `Squadron`. Lifts the "SK ownership not
  supported" 400 that the legacy `resolveSquadronForPickerOutput` still throws.
  Both methods coexist — callers migrate at their own pace.
- **Lifecycle hook still in place**: every aggregate entity's `@PrePersist /
  @PreUpdate / @PostLoad` `syncOwnerFields()` keeps `owningSquadron` and
  `owningOrgUnit` in lockstep for Squadron-owned rows. SK-owned rows have a
  null legacy column — which V100 makes legal.

## Still pending — order of operations

### Step 1 — Caller migration (no migration, Java-only)

Migrate every caller of `resolveSquadronForPickerOutput` to use
`resolveOrgUnitForPickerOutput` and to write via `setOwningOrgUnit(...)`
instead of `setOwningSquadron(...)`. Eight call sites:

- `HangarService.addShip` (line 88)
- `InventoryItemService.createInventoryItem` (line 442)
- `InventoryItemService.bookOutInventoryItem` TRANSFER branch (line 688)
- `MissionService.createMission` (line 233)
- `OperationService.createOperation` (line 236)
- `RefineryOrderService.createRefineryOrder` (line 199)
- `RefineryOrderService.storeRefineryOrder` (line 595)

After the migration, `resolveSquadronForPickerOutput` becomes unreachable and
can be deleted. Mark it `@Deprecated` in the same PR that finishes the caller
migration; delete it in the PR after.

### Step 2 — Stop dual-write (Java-only, no migration)

Drop the `@PrePersist / @PreUpdate / @PostLoad syncOwnerFields()` hooks from
the six aggregate entities (Mission, Operation, Ship, InventoryItem,
RefineryOrder, JobOrder). After Step 1, no caller writes `owningSquadron`
directly, so the hook becomes inert — but removing it makes the eventual
schema drop trivial.

`JobOrder` has the same setup for the two creating/requesting fields; remove
both legacy fields.

### Step 3 — V101: drop legacy aggregate columns

```sql
ALTER TABLE mission        DROP COLUMN owning_squadron_id;
ALTER TABLE operation      DROP COLUMN owning_squadron_id;
ALTER TABLE ship           DROP COLUMN owning_squadron_id;
ALTER TABLE inventory_item DROP COLUMN owning_squadron_id;
ALTER TABLE refinery_order DROP COLUMN owning_squadron_id;
ALTER TABLE job_order      DROP COLUMN creating_squadron_id;
ALTER TABLE job_order      DROP COLUMN requesting_squadron_id;
```

Pre-merge gate: Step 2 must have removed every `@JoinColumn(name =
"owning_squadron_id")` from the Java side. Hibernate `ddl-auto=validate`
would otherwise crash on every boot. Take a full DB backup.

### Step 4 — Stop-write of `User.squadron` (Java-only)

`UserService.updateUserSquadron` currently writes both `user.setSquadron(...)`
and (via `OrgUnitMembershipService.syncStaffelMembership`) the membership row.
Migrate every reader of `user.getSquadron()` to consult
`OrgUnitMembershipRepository.findAllByIdUserIdAndKind(userId,
OrgUnitKind.SQUADRON)` instead. This is the largest blast radius:
`OwnerScopeService.resolveOrgUnitForPickerOutput`, every list filter that
falls through `User.squadron`, every test fixture that calls
`user.setSquadron(...)`.

After the migration, drop the `User.squadron` field + every reference to
`User.isLogistician` / `User.isMissionManager`. The JWT converter's
"memberships.isEmpty()" fallback comes out in the same PR.

### Step 5 — V102: drop `app_user` legacy columns

```sql
ALTER TABLE app_user DROP COLUMN squadron_id;
ALTER TABLE app_user DROP COLUMN is_logistician;
ALTER TABLE app_user DROP COLUMN is_mission_manager;
```

Pre-merge gate: Step 4 must be in prod. Take a full DB backup.

### Step 6 — V103: drop legacy `squadron` table + V98 trigger

Pre-condition: `promotion_topic.owning_squadron_id` must be migrated to
`promotion_topic.owning_org_unit_id` with a CHECK / FK that resolves against
the `org_unit` table (kind=SQUADRON). The V99 trigger already enforces the
SQUADRON-only contract at INSERT/UPDATE time; the column rename + FK retarget
is the missing piece.

```sql
DROP TRIGGER IF EXISTS trg_sync_org_unit_to_squadron ON org_unit;
DROP FUNCTION IF EXISTS sync_org_unit_to_squadron();
DROP TABLE squadron;
```

After this lands, the `SquadronRepository` deletion can ship — every reader
goes through `OrgUnitRepository` (which doesn't exist yet — needs to be
introduced once Hibernate single-table inheritance is the only path).

## Acceptance checklist per step

- [x] Step 1 PR: every staffel-scoped service test calls `.setOwningOrgUnit`,
      not `.setOwningSquadron`. `resolveSquadronForPickerOutput` deprecated.
- [x] Step 2 PR: `git grep "syncOwnerFields"` returns nothing. Every test that
      previously asserted on `entity.getOwningSquadron()` is updated.
- [x] Step 3 PR: V101 applied against a `.env.test` snapshot of prod schema.
      Backup confirmed.
- [x] Step 4 PR: `git grep "user.setSquadron\|user.getSquadron"` returns
      nothing in `main` source set. JWT converter's legacy branch removed.
- [x] Step 5 PR: V102 applied. Backup confirmed.
- [x] Step 6 PR: `promotion_topic` migrated. V103 applied. Backup confirmed.

## Why not all in one PR?

The plan §10 explicitly stages these as "two soak windows" between the dual-
write deploy and the destructive drops. Each step is reversible on its own;
the combined drop is not. A single mega-PR would block the soak benefit and
maximise blast radius.
