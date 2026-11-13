> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-11-13.
> **Owner area:** PATCH
> **Status:** The record, the options, the trigger with the refinery effect, the admin page with
> its history, the audit area, the `patch` change source and the no-mixing rule are implemented
> (REQ-PATCH-001…009), and so is the wipe by group and exception with its dependents, preview and
> audit (REQ-PATCH-010…018). The home-location phase with the relocation, the form default, the
> currency wipe and the exchange resource follow in their own slices of epic #3328 and add their
> requirements here.
> **Related ADRs:** [ADR-0380](../adr/0380-the-patchchange-module-records-patch-changes-and-runs-their-effects.md),
> [ADR-0381](../adr/0381-the-live-time-decides-what-a-patch-change-touches.md),
> [ADR-0382](../adr/0382-patch-effects-are-excepted-from-the-invalid-responsibility-gate.md)

# Patch change

## Context & goal

A Star Citizen patch can wipe what players held, end their refinery jobs, reset their money and
deliver everything they kept to a new home location. The Basetool follows the game when an admin
records the patch change once, for every org unit. The **live time** of the patch, when it went
live in the game, decides what each effect touches: only what existed before it.

The decisions behind this spec are on the wayfinder map #3246: the ground rules #3247 (with its
live-time amendment), the dependents #3249, the ship origin #3250, the wipe selection #3251 (with
its live-time amendment), the refinery effect #3254, the exchange side #3255, the admin page #3256,
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
(#3251, REQ-PATCH-010); exceptions keep or wipe single entries beside them (REQ-PATCH-011). The
record stores every option as triggered (`patch_change_wipe_group`, `patch_change_wipe_exception`
and the option columns of `patch_change`). The trigger runs the effect of an option only once its
effect exists: today the refinery effect (REQ-PATCH-006) and the wipe (REQ-PATCH-012,
REQ-PATCH-015); the currency wipe and the home-location phase are stored and take effect with their
slices.

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
which the wipe and the relocation select by its creation time (ADR-0381). A candidate row without a
creation time counts as created before the live time. Before the first patch change every row
takes part as before, dated or not.

**Acceptance**

- [x] A merge whose just-written row was created before the cutoff leaves every row as it is.
- [x] The merge group and the merge probe leave out rows created before the cutoff or without a
  creation time; without a patch change they leave out none.

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
  once triggered, the wipe groups with one switch each, their catalogue types and the rows and
  members each would wipe, the exceptions as removable chips with a picker for an item or a
  material and its mode (REQ-PATCH-011, REQ-PATCH-016), and the further effects as switches;
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

**Enforced by:** `PatchChangeTriggerIntegrationTest`, `AdminPatchChangePageControllerMvcTest`,
`AdminPatchChangeRelayControllerMvcTest`, `PatchChangeBackendClientTest`,
`LiveSyncSectionMapParityTest`, `LiveSyncTopicTest`, `NavigationMenuTest` · **Code:**
`PatchChangeHistory`, `PatchChangeLabelCorrection`, `PatchChangeLiveSync`, the frontend's
`patchchange` domain (`AdminPatchChangePageController`, `AdminPatchChangeRelayController`,
`PatchChangeBackendClient`, `static/js/patchchange/`), `LiveSyncTopicClass.PATCH_CHANGE`

### REQ-PATCH-009 — The audit area „Patchwechsel“

The audit domain `PATCH_CHANGE` keeps the patch-change trail (REQ-AUDIT-001):

| Event | When |
| --- | --- |
| `PATCH_CHANGE_TRIGGERED` | at the trigger; the subject is the patch change with its label, the details are the kind, the live time, the options and the counts |
| `PATCH_CHANGE_LABEL_CORRECTED` | a label correction, with the old and the new label |
| `PATCH_CHANGE_AUDIT_EXPORTED`, `PATCH_CHANGE_AUDIT_PURGED` | the export and the retention purge of the trail (REQ-AUDIT-003, -004) |

Each affected area records **one** summary event per trigger in its own trail, with counts and the
patch change id: the refinery `REFINERY_ORDERS_CANCELED_BY_PATCH` with the number of orders
cancelled, and the wipe's areas as REQ-PATCH-017 lists them. Single orders are not audited one by
one. No free text and no personal data go into
any details. The area has its own filter and DE/EN labels in the admin viewer.

The existing `basetool_audit_events_total{domain}` counts the area by itself; `AuditDomainSilenceAnomaly`
exempts `PATCH_CHANGE`, because patch changes are rare (REQ-OBS-011).

**Acceptance**

- [x] A trigger records `PATCH_CHANGE_TRIGGERED` and the refinery's summary event; a correction
  records `PATCH_CHANGE_LABEL_CORRECTED`.

**Enforced by:** `PatchChangeTriggerIntegrationTest`, `AuditReportServiceTest`,
`AdminAuditLogPageControllerTest`, the alert's promtool test · **Code:**
`AuditDomain.PATCH_CHANGE`, `AuditDomains`, `AdminAuditLogPageController`, `PatchChangeTrigger`,
`PatchChangeLabelCorrection`, `RefineryPatchCancellationService`

### REQ-PATCH-010 — The wipe groups are defined over catalogue types

Every stock row falls into exactly one wipe group, decided in code from its catalogue entry
(`WipeGroupTypes`); the groups need no maintenance:

| Group | Catalogue types |
| --- | --- |
| Consumables | P4K types `FPS_Consumable`, `Food`, `Drink`, `Bottle`; sub-types `WeaponPersonal:Grenade`, `WeaponAttachment:Magazine`, `WeaponAttachment:Missile`, `Misc:Consumable`; untyped: wiki classification `FPS.Consumable…`, `FPS.WeaponAttachment.Magazine…`, kind `FOOD` |
| FPS equipment | P4K types `WeaponPersonal`, `WeaponAttachment`, `Suit`, every type starting with `Char_Armor_`, `Char_Clothing_`, `Char_Accessory_`; untyped: `FPS.Armor…`, `FPS.Clothing…`, `FPS.Weapon…`, kinds `WEAPON`, `WEAPON_ATTACHMENT`, `ARMOR`, `CLOTHING` |
| Ship components and vehicle weapons | the P4K type of every `ShipComponentType` and `GroundVehicleMissileLauncher`; untyped: `Ship.…`, kinds `VEHICLE_ITEM`, `VEHICLE_WEAPON` |
| Materials: raw / refined / no-refine | the material's type `RAW` / `REFINED` / `NO_REFINE`, in every quality |
| Other items | every item no other group claims, a new type included |
| In-game ships | ships with the origin `IN_GAME` (REQ-PATCH-015) |

An item with a P4K type (`GameItem#wikiType`, the SC Wiki filling a gap) is matched by its type and
sub-type, then its type, then the longest matching type prefix; the wiki classification and the
kind decide only for an item without a type. The admin form shows the catalogue types of each
group; the form defaults carry them (`PatchChangeFormDto.groups`).

**Acceptance**

- [x] Each group claims the types of the table; an item with a known type no group names is an
  other item, whatever its kind.

**Enforced by:** `WipeGroupTypesTest` · **Code:** `WipeGroup`, `WipeGroupType`, `WipeGroupTypes`

### REQ-PATCH-011 — Exceptions keep or wipe single game items and materials

Beside the groups the admin may add **exceptions**: a single game item, or a material in every
quality, whose rows are **kept** although their group is selected, or **wiped** although it is not.
A row of an exception's entry follows the exception, never its group. Each entry has at most one
exception, and a patch change carries at most 100; an exception naming an unknown entry is refused
with `error.patch_change.wipe_exception_unknown`, a second exception of one entry with
`error.patch_change.wipe_exception_duplicate`. The record stores the exceptions with the options
(`patch_change_wipe_exception`, V422); the history names each entry by its current catalogue name.

**Acceptance**

- [x] A keeping exception saves its entry's rows in a selected group; a wiping exception wipes its
  entry's rows outside the selected groups.
- [x] An unknown entry and a second exception of one entry are refused.

**Enforced by:** `WipeSelectionTest`, `PatchChangeOptionsTest`, `PatchChangeWipeIntegrationTest` ·
**Code:** `WipeException`, `WipeSelection`, `WipeExceptionCatalogue`, `PatchChange`, V422

### REQ-PATCH-012 — The stock wipe reaches every member's rows created before the live time

When a stock group or a wiping exception is selected, the trigger deletes every stock row the
options select that was **created before the live time** (a row without a creation time counts as
created before it): own, pool and personal rows (`personal = true`) of every member in every org
unit, stolen ones included. Rows created after the live time stay. Personal inventory
(`personal_inventory_item`), blueprints and the Bank are never touched; money is the currency
wipe.

The rows are selected, locked and selected again on their locked facts, then deleted by their ids
(ADR-0362). The Lager's admin action „Globales Lager leeren“ (REQ-INV-041) stays the scoped
clean-up of pool stock; the patch wipe is a separate path for everyone.

**Acceptance**

- [x] Only the selected rows created before the live time or undated go, personal rows included;
  rows created after it and rows of unselected groups stay.

**Enforced by:** `PatchChangeWipeIntegrationTest` · **Code:** `StockWipeEffect`,
`inventory.api.StockPatchWipe`, `InventoryPatchWipeService`

### REQ-PATCH-013 — What depends on a wiped row goes with it

- **Job orders:** the earmarks on a wiped row go with it. For material promises the wipe is a
  genuine stock loss (ADR-0360): credited contribution stays, uncovered demand rises and no ended
  promise revives. The open order pages refresh through the stock write.
- **Donation pledges** earmarked on a wiped row are withdrawn as their donor would withdraw them,
  with the usual notice; the donation keeps the patch change as its reason
  (`supply_donation.withdrawn_by_patch_change`, V423), which only a withdrawn donation holds.
- **Materialbörse offers** standing on a wiped row end with the usual „stock gone“ notice to the
  members who registered interest.
- **Minimum stock levels** turn low by themselves; they are read-time sums.

**Acceptance**

- [x] A wiped row's earmark, active offer and pledge go: the earmark and the offer are deleted, the
  pledge is withdrawn with the patch change as its reason; the job order stays.

**Enforced by:** `PatchChangeWipeIntegrationTest` · **Code:** `StockWipeEffect`,
`materialexchange.api.MaterialExchangePatchWipe`, `supply.api.SupplyPatchWipe`,
`joborder.api.JobOrderPatchWipe`

### REQ-PATCH-014 — The wipe is excepted from the invalid-responsibility gate

The stock wipe is a game fact, not an order write (ADR-0382): an earmarked job order with invalid
responsibility (REQ-ORDERS-055) does not refuse it. The wipe still takes every lock an ordinary
stock write takes, the shared hierarchy lock first, then the material scopes, the lots and the rows,
and the orders must exist. Every other stock write keeps the ordinary check.

**Acceptance**

- [x] A row earmarked for an order with invalid responsibility is wiped; the order keeps its
  history.

**Enforced by:** `PatchChangeWipeIntegrationTest` · **Code:**
`InventoryOrderWrites#duringGameFactLoss`, `OrderLinkedStockWriteObserver#beforeGameFactStockLoss`,
`JobOrderStockWriteEligibility`

### REQ-PATCH-015 — In-game ships go through the ordinary deletion path

When the in-game ships are selected, the trigger deletes every member's ship whose chosen origin is
`IN_GAME` and not locked (REQ-HANGAR-017) and that was created before the live time (undated
counts as before). A pledged ship, a ship of unknown origin and a ship whose origin an import or a
pledge id locked stay. Each ship goes through the ordinary deletion path (`HangarShipRemoval`), so
`ShipDeletionObserver` releases a mission unit that carried it with its notice; the exchange ship
feed records the removal under the `patch` source.

**Acceptance**

- [x] Only unlocked in-game ships created before the live time go; their mission units let go of
  them.

**Enforced by:** `PatchChangeWipeIntegrationTest`, `HangarServiceTest` · **Code:**
`ShipWipeEffect`, `hangar.api.ShipPatchWipe`, `HangarPatchWipeService`, `HangarShipRemoval`

### REQ-PATCH-016 — The preview counts each group and exception

The preview counts, from what the live time selects:

- the tiles: stock rows wiped, in-game ships wiped, members with wiped stock rows, job orders with
  wiped earmarks, offers ended and pledges withdrawn (each only while its option wipes);
- per wipe group, selected or not, the rows (ships for the in-game ships) and the members who hold
  them, shown in the group table;
- per exception, the rows of its entry, shown on its chip.

One survey per transaction and plan serves the tiles and the breakdown, so they agree
(`WipeSurvey`). There is no list of removed rows for members and no undo; the preview and the typed
label are the safeguard.

**Acceptance**

- [x] The preview counts every group and every exception without writing.

**Enforced by:** `PatchChangeWipeIntegrationTest`, `AdminPatchChangePageControllerMvcTest`,
`AdminPatchChangeRelayControllerMvcTest` · **Code:** `WipeSurvey`, `WipePreview`,
`WipePreviewDto`, `static/js/patchchange/patch-change-wipe.js`

### REQ-PATCH-017 — One summary event per affected area

Each area the wipe touches records **one** summary event per trigger in its own trail; the subject
is the patch change with its label, the details carry counts and `patchChange=<id>`:

| Area | Event | Details |
| --- | --- | --- |
| Inventory | `INVENTORY_WIPED_BY_PATCH` | rows, members, job orders, rows per wipe group (`WIPE_EXCEPTIONS` for rows a wiping exception selects) |
| Hangar | `HANGAR_SHIPS_WIPED_BY_PATCH` | ships, owners, mission units released |
| Supply | `DONATIONS_WITHDRAWN_BY_PATCH` | pledges withdrawn |
| Materialbörse | `MARKET_OFFERS_ENDED_BY_PATCH` | offers ended |
| Job orders | `JOB_ORDERS_STOCK_WIPED_BY_PATCH` | orders whose earmarks went |

Single rows, ships, offers and pledges are not audited one by one. No free text and no personal
data go into any details.

**Acceptance**

- [x] A wipe records each area's summary event once and no per-offer or per-ship event.

**Enforced by:** `PatchChangeWipeIntegrationTest`, `SupplyPatchWipeServiceTest`,
`MaterialExchangePatchWipeServiceTest`, `HangarPatchWipeServiceTest`,
`AdminAuditLogPageControllerTest` · **Code:**
`InventoryPatchWipeService`, `HangarPatchWipeService`, `SupplyPatchWipeService`,
`MaterialExchangePatchWipeService`, `JobOrderPatchWipeService`

### REQ-PATCH-018 — Open pages of the affected areas refresh in place

After the commit the backend publishes the rooms the wipe changed: `inventory` (`stock`,
`stock-levels`), `materialboard` (`board`), `supply-needs` (`needs`, `mine`, `stats`) and
`supply-donations:{unit}` of every receiving unit, `hangar:{member}` of every owner and the unit
hangar rooms, and through the stock write `order:{id}` and `orders`. Nothing is published for a
rolled-back trigger.

**Acceptance**

- [x] The supply, Materialbörse and member hangar rooms are published after the commit, and none
  when nothing changed.
- [ ] The Lager and order rooms are asserted by a test.

**Enforced by:** `SupplyPatchWipeServiceTest`, `MaterialExchangePatchWipeServiceTest`,
`HangarPatchWipeServiceTest`, `LiveSyncTopicRegistryParityTest` (every room exists on both sides)
· **Code:**
`InventoryPatchWipeService`, `MaterialExchangePatchWipeService`, `SupplyPatchWipeService`,
`HangarPatchWipeService`, `JobOrderStockWriteEligibility`
