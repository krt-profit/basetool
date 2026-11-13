> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-11-13.
> **Owner area:** PATCH
> **Status:** The record, the options, the trigger with the refinery effect, the admin page with
> its history, the audit area, the `patch` change source and the no-mixing rule are implemented
> (REQ-PATCH-001…009). The wipe, the home-location phase with the relocation, the form default,
> the currency wipe and the exchange resource follow in their own slices of epic #3328 and add
> their requirements here.
> **Related ADRs:** [ADR-0380](../adr/0380-the-patchchange-module-records-patch-changes-and-runs-their-effects.md),
> [ADR-0381](../adr/0381-the-live-time-decides-what-a-patch-change-touches.md)

# Patch change

## Context & goal

A Star Citizen patch can wipe what players held, end their refinery jobs, reset their money and
deliver everything they kept to a new home location. The Basetool follows the game when an admin
records the patch change once, for every org unit. The **live time** of the patch, when it went
live in the game, decides what each effect touches: only what existed before it.

The decisions behind this spec are on the wayfinder map #3246: the ground rules #3247 (with its
live-time amendment), the refinery effect #3254, the exchange side #3255, the admin page #3256,
audit and monitoring #3257 and the spec and ADR cut #3327.

**Terms** (arc42 §12): a *patch change* is the admin's record of a game patch and its options; the
*patch kind* is Rebuild or Full wipe; the *live time* is when the patch went live, the cutoff of
every effect; the *home-location phase* is the period in which members are asked for their new
home location.

## Requirements

### REQ-PATCH-001 — One record per patch change, triggered by an admin for every org unit

A patch change is one record of the backend module `patchchange` (ADR-0380), in the table
`patch_change`:

- the **label**: a version of two or three numbers (`4.4`, `4.4.1`, each number at most nine
  digits), unique and higher than the label of every earlier patch change. Labels compare number
  by number; a missing third number counts as zero, so `4.4.0` is not higher than `4.4`. The form
  prefills it with the patch version of the latest applied P4K import that knows one (REQ-P4K-005);
- the **live time** (REQ-PATCH-003);
- the **kind** and the **options** (REQ-PATCH-002);
- the triggering **admin** and the **trigger time**, and what the effects touched (REQ-PATCH-005).

Only an admin triggers a patch change (`/api/v1/patch-changes/admin/**`, `ROLE_ADMIN`). It applies
to every org unit, also when the admin is pinned to one. Hotfixes change nothing and are not
recorded. The admin's id is a pseudonymous record of the act that outlives the account
(REQ-DATA-021).

**Acceptance**

- [x] A label of another shape is refused with `error.patch_change.label_invalid`, a label not
  higher than the previous one with `error.patch_change.label_not_higher`.
- [x] A member without `ROLE_ADMIN` cannot reach the endpoints.

**Enforced by:** `PatchLabelTest`, `PatchChangeRulesTest`, `PatchChangeTriggerIntegrationTest`,
`AuthorizationMatrixTest` · **Code:** `PatchLabel`, `PatchChangeRules`, `PatchChangeTrigger`,
`AdminPatchChangeController`

### REQ-PATCH-002 — The kind presets the options; every option stays adjustable

The patch kind presets the options, and the admin may change each before the trigger:

| Option | Rebuild | Full wipe |
| --- | --- | --- |
| Wipe groups | consumables | every group |
| End running refinery orders | on | on |
| Currency wipe (Bank reset, aUEC orders) | off | off, offered |
| Home-location prompt | on | on |

The wipe groups are fixed: consumables, FPS equipment, ship components and vehicle weapons, raw
materials, refined materials, materials that cannot be refined, other items and in-game ships
(#3251). The record stores every option as triggered (`patch_change_wipe_group` and the option
columns of `patch_change`). The trigger runs the effect of an option only once its effect exists:
today the refinery effect (REQ-PATCH-006); the wipe, the currency wipe and the home-location phase
are stored and take effect with their slices.

**Acceptance**

- [x] Each kind's preset matches the table; an option switched off leaves its area untouched and
  is stored as off.

**Enforced by:** `PatchChangeTriggerIntegrationTest` · **Code:** `PatchChangeOptions`,
`PatchKind`, `WipeGroup`

### REQ-PATCH-003 — The live time selects what every effect touches

The live time defaults to the moment of the trigger and may be backdated to any point after the
previous patch change's live time; it never lies in the future. Every effect touches only what was
created or started before it (ADR-0381); what came later is a post-patch fact and stays. The live
time is **fixed** once the patch change is triggered, because its effects ran against it; only the
label stays correctable (REQ-PATCH-008). The preview counts only what the live time selects.

**Acceptance**

- [x] A live time after now is refused with `error.patch_change.live_at_future`, one not after the
  previous patch change's with `error.patch_change.live_at_not_after_previous`.
- [x] A label correction leaves the live time unchanged.

**Enforced by:** `PatchChangeRulesTest`, `PatchChangeTriggerIntegrationTest` · **Code:**
`PatchChangeRules`, `PatchChange`

### REQ-PATCH-004 — No mixing: a row from before the live time never merges

From the latest patch change's live time on, no write adds quantity to a stock row created before
it, and no such row is folded into another. The write-time merge of
[REQ-INV-026](inventory-lager.md#req-inv-026--write-time-stock-merge-for-piece-auto-and-scu-per-action-opt-in)
skips such rows as survivor and as candidate, and the merge probe of the Einbuchen form does not
count them; the write then lands its own row. A pre-patch row therefore holds only pre-patch stock,
which the wipe and the relocation select by its creation time (ADR-0381). Before the first patch
change every row takes part as before.

**Acceptance**

- [x] A merge whose just-written row was created before the cutoff leaves every row as it is.
- [x] The merge group and the merge probe leave out rows created before the cutoff.

**Enforced by:** `InventoryMergeCutoffTest`, `InventoryMergeGroupDataTest` · **Code:**
`inventory.api.StockMergeCutoff`, `PatchChangeMergeCutoff`, `InventoryMergeService`,
`InventoryItemRepository#findMergeGroupForUpdate`, `#countMergeCandidates`

### REQ-PATCH-005 — The trigger is one all-or-nothing step with the record

The admin's page shows a preview of what the effects would touch now, one count per kind, and the
admin confirms by typing the label (the `.confirm-input` pattern with the label as token); the
backend checks the typed label again (`error.patch_change.confirmation_mismatch`).

The trigger records the patch change and runs every enabled effect in **one transaction**: all
commit or none does. An effect that refuses the patch change stops the rest and the admin sees its
reason. The trigger takes the history's lock before it reads the previous patch change, so two
triggers never judge labels and live times on a stale read. The record keeps what each effect
touched (`patch_change_effect_count`).

Every effect is a bean of the `patchchange` module that drives one other module through that
module's api package (ADR-0380); the trigger and the preview run all of them.

**Acceptance**

- [x] The record, the effects' writes and the audit events of a trigger commit together.
- [x] An effect that refuses stops the rest; a wrong confirmation records nothing.
- [x] The preview counts without writing.

**Enforced by:** `PatchChangeEffectsTest`, `PatchChangeTriggerIntegrationTest` · **Code:**
`PatchChangeTrigger`, `PatchChangeEffects`, `PatchEffect`

### REQ-PATCH-006 — The refinery effect ends the running orders started before the live time

When its option is on, the trigger cancels every `OPEN` or `IN_PROGRESS` refinery order of every
member that started before the live time; an order without a start counts from its creation. An
order that ran in the game before the patch is lost even when it was entered later; an order
started after the live time stays.

Each order is cancelled as the web cancels it: status `CANCELED`, its game-data counter advanced
(REQ-REFINERY-029), its ready notice cleared (`readyCleared`), the web as its last change
(REQ-REFINERY-027). It keeps the patch change as its cancel reason
(`refinery_order.canceled_by_patch_change`), which only a `CANCELED` order holds; a later status
change drops it. Nothing is deleted. Mission finances and stock do not change: a lost order's
costs keep counting, and an order that was not stored has no stock. The web shows only the status.
`COMPLETED` and `CANCELED` orders stay untouched. The refinery records one summary event in its own
trail (REQ-PATCH-009), and the open refinery pages refresh in place.

**Acceptance**

- [x] Only running orders started (or, without a start, created) before the live time are
  cancelled, each with the patch change as its reason and an advanced game-data counter.

**Enforced by:** `PatchChangeTriggerIntegrationTest` · **Code:** `RefineryCancellationEffect`,
`refinery.api.RefineryPatchCancellation`, `RefineryPatchCancellationService`

### REQ-PATCH-007 — The exchange feeds name the patch change behind a change

Every row a trigger writes reaches the exchange change feed with the change source **`patch`** and
the patch change's id (`exchange_change.source_patch_change`, REQ-XCH-013). Wherever a feed
attributes a change it names the patch change as `{id, label}` with its current label: a
tombstone's `removedBy` reads `{"channel": "patch", "patchChange": {"id": …, "label": "4.4"}}`,
and the refinery-order resource carries `canceledByPatch` on an order a patch change cancelled
(REQ-XCH-051). Changes from the `patch` source are no client writes: they count for no client's
mass-change guard, and a client's undo skips an entry a patch change touched afterwards as
`CHANGED_AFTERWARDS` (REQ-XCH-056). The change is additive in `v1`.

**Acceptance**

- [x] A trigger's feed entries carry the `patch` channel and the patch change's id.
- [x] A cancelled order in the refinery feed carries `canceledByPatch` with the current label.

**Enforced by:** `PatchChangeTriggerIntegrationTest` · **Code:** `PatchChangeWrites`,
`ChangeSource`, `ExchangeFeedReader`, `ExchangeRefineryOrderFeedService`, `V421`

### REQ-PATCH-008 — The admin page with its history

The page `/admin/settings/patch-change` is reached from a card on the danger-zone page (variant B
of the prototype on `prototype/patch-change`):

- **left, the form:** the patch kind as a segmented control that presets everything below, the
  label and the live time with the line that the live time selects what is affected and is fixed
  once triggered, the wipe groups with one switch each, and the further effects as switches;
- **right, a sticky preview:** one tile per count the enabled effects report, refreshed on every
  change, a danger alert while the currency wipe is on, and the confirmation: typing the label
  enables the single danger button „Patchwechsel <label> auslösen“;
- **below, the history:** label, kind, live time, triggered by and at, the effects' counts and the
  options, newest first, with „Bezeichnung korrigieren“ per row.

A label correction checks the version the admin last saw and keeps the label between its
neighbours' labels. Every write updates the page in place; the backend announces a trigger or a
correction in the `patch-change` room (section `history`), so every other admin's open page
refreshes its history and its form's previous values.

**Acceptance**

- [x] A correction with a stale version answers 409.
- [x] The page renders the form, the preview, the confirmation and the history.

**Enforced by:** `PatchChangeTriggerIntegrationTest`, the frontend page tests · **Code:**
`PatchChangeHistory`, `PatchChangeLabelCorrection`, `PatchChangeLiveSync`, the frontend's
`patchchange` domain

### REQ-PATCH-009 — The audit area „Patchwechsel“

The audit domain `PATCH_CHANGE` keeps the patch-change trail (REQ-AUDIT-001):

| Event | When |
| --- | --- |
| `PATCH_CHANGE_TRIGGERED` | at the trigger; the subject is the patch change with its label, the details are the kind, the live time, the options and the counts |
| `PATCH_CHANGE_LABEL_CORRECTED` | a label correction, with the old and the new label |
| `PATCH_CHANGE_AUDIT_EXPORTED`, `PATCH_CHANGE_AUDIT_PURGED` | the export and the retention purge of the trail (REQ-AUDIT-003, -004) |

Each affected area records **one** summary event per trigger in its own trail, with counts and the
patch change id: the refinery `REFINERY_ORDERS_CANCELED_BY_PATCH` with the number of orders
cancelled. Single orders are not audited one by one. No free text and no personal data go into
any details. The area has its own filter and DE/EN labels in the admin viewer.

The existing `basetool_audit_events_total{domain}` counts the area by itself; `AuditDomainSilenceAnomaly`
exempts `PATCH_CHANGE`, because patch changes are rare (REQ-OBS-011).

**Acceptance**

- [x] A trigger records `PATCH_CHANGE_TRIGGERED` and the refinery's summary event; a correction
  records `PATCH_CHANGE_LABEL_CORRECTED`.

**Enforced by:** `PatchChangeTriggerIntegrationTest`, `AuditReportServiceTest`, the alert's
promtool test · **Code:** `AuditDomain.PATCH_CHANGE`, `PatchChangeTrigger`,
`PatchChangeLabelCorrection`, `RefineryPatchCancellationService`
