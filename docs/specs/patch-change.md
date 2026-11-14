> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-11-14.
> **Owner area:** PATCH
> **Status:** The record, the options, the trigger with the refinery effect, the admin page with
> its history, the audit area, the `patch` change source and the no-mixing rule are implemented
> (REQ-PATCH-001…009), the wipe by group and exception with its dependents, preview and audit
> (REQ-PATCH-010…018), the home-location phase with its gate, lock and in-place relocation
> (REQ-PATCH-020…029), the profile setting „Meine Home Location“ as form default
> (REQ-PATCH-030…033), the currency wipe (REQ-PATCH-040…046) and the exchange resource with the
> home-location choice through a client and the patch change named in stale pushes
> (REQ-PATCH-050…054), also on org-stock entries (REQ-PATCH-057).
> **Related ADRs:** [ADR-0380](../adr/0380-the-patchchange-module-records-patch-changes-and-runs-their-effects.md),
> [ADR-0381](../adr/0381-the-live-time-decides-what-a-patch-change-touches.md),
> [ADR-0382](../adr/0382-patch-effects-are-excepted-from-the-invalid-responsibility-gate.md),
> [ADR-0383](../adr/0383-a-patch-change-relocation-moves-stock-in-place.md),
> [ADR-0384](../adr/0384-the-currency-wipe-resets-the-bank-as-of-the-live-time.md),
> [ADR-0385](../adr/0385-session-gates-are-one-spi-the-domains-implement.md)

# Patch change

## Context & goal

A Star Citizen patch can wipe what players held, end their refinery jobs, reset their money and
deliver everything they kept to a new home location. The Basetool follows the game when an admin
records the patch change once, for every org unit. The **live time** of the patch, when it went
live in the game, decides what each effect touches: only what existed before it.

The decisions behind this spec are on the wayfinder map #3246: the ground rules #3247 (with its
live-time amendment), the dependents #3249, the ship origin #3250, the wipe selection #3251 (with
its live-time amendment), the refinery effect #3254, the exchange side #3255, the admin page #3256,
audit and monitoring #3257, the currency wipe #3258 and the spec and ADR cut #3327.

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
and the option columns of `patch_change`). The trigger runs the effect of every option: the
refinery effect (REQ-PATCH-006), the wipe (REQ-PATCH-012, REQ-PATCH-015), the home-location phase
(REQ-PATCH-020…029) and the currency wipe (REQ-PATCH-040).

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

### REQ-PATCH-004 — No mixing: a row a relocation still moves never merges

While a member's relocation is pending in the open home-location phase (REQ-PATCH-024), no write
adds quantity to that member's stock rows created before the phase's live time, and no such row is
folded into another. The write-time merge of
[REQ-INV-026](inventory-lager.md#req-inv-026--write-time-stock-merge-for-piece-auto-and-scu-per-action-opt-in)
skips such rows as survivor and as candidate, and the merge probe of the Einbuchen form does not
count them; the write then lands its own row. A pre-patch row therefore holds only pre-patch stock,
which the relocation selects by its creation time (ADR-0381). A row without a creation time, the
just-written one included, counts as created before the live time.

The wipe runs at the trigger and needs no window: once the member relocates, or the phase ends or
is superseded, the member's rows merge again. Before the first phase every row takes part as
before, dated or not.

One write is excepted: a provision correction that adds quantity (the truthful upward correction
of [REQ-ORDERS-*](orders-material-claims.md)) restores a historic fact on the row the provision
came from, so it adds the quantity in place to a row created before the live time even while the
holder's relocation is pending (owner decision D3, ADR-0381).

**Acceptance**

- [x] While the holder's relocation is pending, a merge whose just-written row was created before
  the live time, or has no creation time, leaves every row as it is.
- [x] The merge group and the merge probe leave out the holder's rows created before the live time
  or without a creation time; a holder without a pending relocation, or any holder while no phase
  is open, merges every row.
- [x] An upward provision correction adds its quantity to a pre-patch row of a holder whose
  relocation is pending.

**Enforced by:** `InventoryMergeCutoffTest`, `PatchChangeMergeCutoffTest`,
`InventoryMergeGroupDataTest`, `MaterialProvisionCorrectionDuringRelocationIntegrationTest` ·
**Code:** `inventory.api.StockMergeCutoff#cutoffFor`, `PatchChangeMergeCutoff`,
`InventoryMergeService`, `InventoryItemRepository#findMergeGroupForUpdate`, `#countMergeCandidates`,
`InventoryQuantityCorrections#addKnownQuantity`

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
member that started before the live time; an order without a start counts from its creation, and
one with neither time counts as started before the live time. An order that ran in the game before
the patch is lost even when it was entered later; an order started after the live time stays.

Each order is cancelled as the web cancels it: status `CANCELED`, its game-data counter advanced
(REQ-REFINERY-029), its ready notice cleared (`readyCleared`), the web as its last change
(REQ-REFINERY-027). It keeps the patch change as its cancel reason
(`refinery_order.canceled_by_patch_change`), which only a `CANCELED` order holds; a later status
change drops it. Nothing is deleted. Mission finances and stock do not change: a lost order's
costs keep counting, and an order that was not stored has no stock. The web shows only the status.
`COMPLETED` and `CANCELED` orders stay untouched. The refinery records one summary event in its own
trail (REQ-PATCH-009), and the open refinery pages refresh in place.

**Acceptance**

- [x] Only running orders started (or, without a start, created) before the live time, or with
  neither time, are cancelled, each with the patch change as its reason and an advanced game-data
  counter.

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
cancelled, the wipe's areas as REQ-PATCH-017 lists them and the currency wipe's two events
(REQ-PATCH-046). Single orders are not audited one by one. No free text and no personal data go
into any details. The area has its own filter and DE/EN labels in the admin viewer.

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
  (`supply_donation.withdrawn_by_patch_change`, V423), which only a withdrawn donation holds. The
  donation lists name the patch change by its current label beneath the status
  ([REQ-SUP-010](supply.md#req-sup-010--decline-and-withdraw)).
- **Materialbörse offers** standing on a wiped row end with the usual „stock gone“ notice to the
  members who registered interest.
- **Minimum stock levels** turn low by themselves; they are read-time sums.

**Acceptance**

- [x] A wiped row's earmark, active offer and pledge go: the earmark and the offer are deleted, the
  pledge is withdrawn with the patch change as its reason; the job order stays.
- [x] „Meine Spenden" and „Eingehende Spenden" name the patch change beneath a wiped pledge's
  status, and only there.

**Enforced by:** `PatchChangeWipeIntegrationTest`, `SupplyMapperTest`,
`SupplyWithdrawalReasonRenderTest` · **Code:** `StockWipeEffect`,
`materialexchange.api.MaterialExchangePatchWipe`, `supply.api.SupplyPatchWipe`,
`supply.api.SupplyPatchLabels`, `joborder.api.JobOrderPatchWipe`, `PatchChangeLabelDirectory`

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
- [x] The Lager and order rooms are asserted by a test.

**Enforced by:** `SupplyPatchWipeServiceTest`, `MaterialExchangePatchWipeServiceTest`,
`HangarPatchWipeServiceTest`, `PatchChangeWipeIntegrationTest` (the `inventory`, `order` and
`orders` rooms of a full wipe), `LiveSyncTopicRegistryParityTest` (every room exists on both sides)
· **Code:**
`InventoryPatchWipeService`, `MaterialExchangePatchWipeService`, `SupplyPatchWipeService`,
`HangarPatchWipeService`, `JobOrderStockWriteEligibility`

### REQ-PATCH-020 — The home-location gate holds a pending member at the next request

While a home-location phase is open (REQ-PATCH-024), a member who must still choose
(REQ-PATCH-021) meets a **hard gate** like the terms acceptance (REQ-SEC-028): every page of the
web redirects to `/home-location` until the choice is made. It takes effect on the member's next
request, in sessions already open as well:

- it is the second session gate on both sides (ADR-0385), after the consent gate: the frontend's
  `HomeLocationGate` asks the backend (`GET /api/v1/patch-changes/me/home-location/status`) through
  `SessionGateFilter` and caches only a verdict that lets the member through, for 60 seconds; the
  home-location page and the choice clear it;
- a backend refusal with `403 HOME_LOCATION_REQUIRED` that reaches the frontend, a page section's
  read included, discards the cached verdict at once and hands the browser to the page: a page load
  is redirected, an AJAX call gets the header; so a member a new phase asks is held by the first
  refused request, not after the cache expires. The refusal carries `sessionGate: true` like every
  session gate's, by which the frontend tells it (REQ-SEC-028);
- an AJAX call gets `403` with the header `X-Home-Location-Required` naming the page, which
  `krtFetch` follows; an `EventSource` gets the one-shot event `home-location-gate`; a WebSocket
  handshake is marked (`SessionGateHandoff`) and closed with the terminal code `4003`;
- the backend's `HomeLocationGate`, run by `SessionGateAccessFilter`, refuses every other `/api/**`
  call of the member's web session with `403 HOME_LOCATION_REQUIRED`, leaving open the gate's own
  endpoints, the caller's `/api/v1/users/me` and the endpoints the consent gate leaves open;
- when a trigger opens a phase, the asked members' notification streams close after the commit, so
  their open pages reconnect through the gate and are handed off to the page at once.

An exchange client's relayed call is **not** gated: reads and new rows stay allowed, and the
relocation lock refuses its writes on locked rows and ships instead (REQ-PATCH-023).

**Acceptance**

- [x] A pending member's page load redirects to `/home-location`, an AJAX call gets the header, a
  stream gets the event, a WebSocket handshake is marked; the gate pages stay reachable.
- [x] The backend refuses a pending member's web calls with `403 HOME_LOCATION_REQUIRED` and lets
  the exempt endpoints and an exchange client's relayed call through.
- [x] A backend refusal of the gate discards the frontend's cached verdict and hands the browser to
  the page, also when a page section's read meets it.

**Enforced by:** `HomeLocationGateTest` (frontend and backend), `SessionGateFilterTest`,
`SessionGateAccessFilterTest`, `GlobalExceptionHandlerTest`, `SectionSessionGateHandoffMvcTest`,
`SessionGateClientParityTest`, `HomeLocationPageControllerMvcTest` · **Code:** `HomeLocationGate`
(frontend and backend), `SessionGateFilter`, `SessionGates`, `SessionGateHandoff`,
`SessionGateAccessFilter`, `SessionGateRefusalException`, `SectionFallback`,
`GlobalExceptionHandler`, `NotificationPageController`, `NotificationStreamService#reconnect`,
`krt-fetch.js`, `notifications.js`

### REQ-PATCH-021 — Who is asked and what is offered

A phase asks **every active member whose account was created before its live time** (or has no
creation time), members with nothing to relocate included; an account created later is not asked
(#3252 with its live-time amendment). The trigger's preview and record count them
(`MEMBERS_ASKED`).

Only the **admin-curated home locations** are offered: locations with the home-location flag, not
hidden and not reference-only, as the hangar's home-location picker lists them, each with the star
system of its city or station. The admin's form lists them under the home-location switch, links to
their curation and warns while the switch is on and the list is empty.

The member's page (variant A of the prototype) shows a greeting banner with the patch label, the
curated locations as selectable cards and the table „Was umzieht“: per current location the stock
rows from before the live time, the share held for each org unit's pool, and the ships; the row of
the chosen location reads „bleibt“. Rows and ships created later are not counted. Once the member
has chosen, the page shows the relocation's summary in place, „Umgezogen nach X.“ with what moved,
and a link „Weiter zum Basetool“; it never navigates by itself (REQ-FE-001).

**Acceptance**

- [x] An account younger than the live time is not asked and cannot choose.
- [x] The preview counts only rows and ships from before the live time, per location, with the pool
  shares and the ships without a location.
- [x] A location that is not a curated home location is refused with
  `error.patch_change.home_location_not_offered`.
- [x] After the choice the page shows the summary and the link into the tool and stays put.

**Enforced by:** `HomeLocationRelocationIntegrationTest`, `HomeLocationPageControllerMvcTest`,
`AdminPatchChangePageControllerMvcTest`, `testHomeLocationJs` · **Code:** `HomeLocationPhases`,
`HomeLocationOffer`, `HomeLocationPrompts`, `HomeLocationPromptEffect`, `HomeLocationPageReader`,
`templates/patchchange/home-location.html`, `static/js/patchchange/home-location.js`

### REQ-PATCH-022 — The choice is final, once per member and phase

The choice is stored per member per phase (`patch_change_relocation`, keyed by patch change and
member) together with who chose — the member (`SELF`), an admin on the member's behalf (`ADMIN`) or
the member's exchange client (`CLIENT`, REQ-PATCH-052) — and what moved. It is **final**: a second
choice in the same phase is refused with `409 HOME_LOCATION_ALREADY_CHOSEN`
(`error.patch_change.already_relocated`), a choice without an open phase, or by a member the phase
does not ask, with `409 HOME_LOCATION_NOT_ASKED` (`error.patch_change.phase_not_open`,
`error.patch_change.not_asked`), a location not on offer with `400 HOME_LOCATION_NOT_OFFERED`
(`error.patch_change.home_location_not_offered`); the web and the exchange share the codes
(`HomeLocationChoiceRefusedException`). There is no undo; later corrections use the ordinary
rebooks.

An admin chooses on a member's behalf from the member's admin tab „Home Location“
(`/admin/members/{id}/home-location`), which shows the member's state and the same preview; it runs
the same relocation. The choice is part of the member's Art. 15 export (`homeLocationChoices`).

Endpoints: `GET`/`POST /api/v1/patch-changes/me/home-location` (any member) and
`GET`/`POST /api/v1/patch-changes/admin/members/{userId}/home-location` (`ROLE_ADMIN`).

**Acceptance**

- [x] A second choice is refused and moves nothing again; the record names who chose.

**Enforced by:** `HomeLocationRelocationIntegrationTest`,
`AdminMemberHomeLocationPageControllerMvcTest`, `AdminMemberHomeLocationRelayControllerMvcTest` ·
**Code:** `MemberRelocations`, `PatchChangeRelocation`, `PatchChangeRelocationRepository#claim`,
`HomeLocationController`, `AdminPatchChangeController`, `PatchChangeExportSections`

### REQ-PATCH-023 — The relocation lock

Until a member relocates in the open phase, the member's **stock rows (own and pool) and ships
created before its live time** (or without a creation time) are locked: they change only for the
member and for admins on the web. Anything created later, including stock others book to the
member while the lock is open, stays editable as usual; **new stock may still be booked** to the
member.

- On the web a write by anyone else is refused with `409 RELOCATION_PENDING`.
- On the exchange every write to a locked row or ship is refused with `409 RELOCATION_PENDING`,
  from the member's own client and from other members' clients on pool rows the member holds;
  reads and new rows stay allowed. The whole change set is refused and nothing is written.
- A patch change's own writes (its trigger, a relocation) and work without a caller are never
  refused.

The Lager holds every write to an existing row against the lock: its rows' entity listener notes
each row Hibernate updates or deletes in a writing transaction, and before the commit, once the
last changes are flushed, a changed row from before the live time is checked
(`inventory.api.StockRelocationLock`, implemented by this module). A row a write only reads is never
refused; a bulk statement, which flushes no entity, is not seen. The hangar checks a ship edit, a
unit change and a delete (`hangar.api.ShipRelocationLock`); an officer's fitted reset leaves the
ships the lock keeps from the caller as they are and counts only the ships it cleared.

Others see the lock where they meet another member's stock and ships: a locked row, and a stack
or ship of a pending holder, carries the chip „Umzug ausstehend" with the explanation — in the
global Lager (the unit's stock and its pools, stack and rows), the material and item drill-downs,
the unit hangar overview's owner rows and the admin member hangar. A locked row's actions (book
out, rebook, pool change, stolen mark, note, earmarks) are disabled with the reason for a caller
the lock refuses; the row DTO says both (`relocationPending`, `relocationLocked`), so the page
never decides who may write. An admin keeps every action. The trigger, the end of a phase and a
relocation tell the open Lager and hangar pages through their live-sync rooms, so the markers
follow without a reload.

**Acceptance**

- [x] Another member's web write to a locked row and an exchange client's write to a locked row or
  ship are refused with `RELOCATION_PENDING`; the holder and an admin may write; a row created after
  the live time stays editable; after the relocation the lock is gone.
- [x] An officer's fitted reset leaves the locked ships of a pending owner fitted.
- [x] Another member's locked row shows „Umzug ausstehend" with its actions disabled and
  explained; an admin sees the chip and keeps the actions; the unit hangar overview and the admin
  member hangar mark a pending owner's locked ships.

**Enforced by:** `HomeLocationRelocationIntegrationTest`, `InventoryRelocationMarksTest`,
`HangarRelocationGuardTest`, `HangarServiceTest`, `ShipRepositoryPersonalHangarTest`,
`InventoryRelocationMarkerRenderTest`, `HangarPagePatternRenderTest`,
`MemberHangarPageControllerTest` · **Code:** `RelocationLocks`, `InventoryRelocationGuard`,
`InventoryRelocationListener`, `InventoryRelocationMarks`, `HangarRelocationGuard`,
`ShipRelocationScope`, `RelocationPendingException`, `PatchChangeProblemCode`

### REQ-PATCH-024 — The home-location phase: open, ended early, superseded

A patch change with the home-location option opens its phase at the trigger. Only the latest patch
change with the option on can hold an open phase:

- a **later patch change with the option on supersedes** the open phase and carries the lock over:
  every member who has not chosen is asked again for the newest patch, so their stock moves once;
  a member who chose for the earlier phase is asked again too, because the game delivered again;
- a later patch change **without** the option leaves the open phase open;
- an **admin may end** the open phase early („Abfrage beenden“ in the history,
  `POST /api/v1/patch-changes/admin/{id}/phase-end`); the gate and the lock end for every member
  who has not chosen, and nothing more relocates in it. Ending writes only the phase's columns,
  under the history's lock, so it never collides with a label correction.

The history shows the phase as a chip: „offen · x / y umgezogen“, „abgelöst“ or „beendet“.

**Acceptance**

- [x] Ending the open phase frees its pending members, keeps the label's version and is refused for
  a phase that is not the open one.
- [x] A later patch change with the option on supersedes the open phase and asks again.

**Enforced by:** `HomeLocationRelocationIntegrationTest`, `AdminPatchChangePageControllerMvcTest`,
`AdminPatchChangeRelayControllerMvcTest` · **Code:** `HomeLocationPhases`, `HomeLocationPhaseEnd`,
`PatchChangeRepository#endPhase`, `PatchChangeHistory`

### REQ-PATCH-025 — The relocation moves in place

After the choice, the member's stock rows, own and pool rows for any org unit, stolen ones
included, and the member's ships **created before the phase's live time** (or without a creation
time) move to the chosen home location; rows and ships created later stay where they are, and rows
and ships already at the target stay and are not counted (#3253 with its live-time amendment).

- The location changes **in place**, as an exception to the append-only rule (REQ-INV-001) for the
  patch-change relocation only (ADR-0383). Every row keeps its identity, so job-order and mission
  earmarks, donation pledges and Materialbörse offers stay attached; quantity, quality, the stolen
  marker, `personal` and the pool stay unchanged.
- Nothing merges with identical rows at the target; they display grouped anyway, and the existing
  merge action stays available. No material promise is credited again (ADR-0360).
- Every moved row and ship gets a new `version`, so a stale web form or exchange client cannot
  write the old location back.
- The exchange stock feed shows a relocated lot as a tombstone for the old key plus an item for the
  new key, which also includes stock already at the target; every entry names the change source
  `patch` with the phase's patch change (REQ-PATCH-007).

**Acceptance**

- [x] Rows and ships from before the live time move to the target with a new version; later ones
  and those already there stay; the feed entries carry the `patch` source.

**Enforced by:** `HomeLocationRelocationIntegrationTest` · **Code:**
`inventory.api.StockRelocation`, `InventoryStockRelocation`, `hangar.api.ShipRelocation`,
`HangarShipRelocation`

### REQ-PATCH-026 — One all-or-nothing transaction per member, exactly once

A relocation is one transaction per member, opened inside `PatchChangeWrites.runFor` of the phase's
patch change:

1. take the patch-change history's lock in shared mode, so relocations run side by side but never
   beside a trigger or a phase end, and re-check that the phase is still the open one;
2. claim the member's relocation (`INSERT … ON CONFLICT DO NOTHING`): a concurrent second choice
   waits for the first and then finds the key taken;
3. take the shared hierarchy lock (REQ-ORDERS-055), then the advisory locks of every affected lot,
   old and new keys, in ascending key order (ADR-0229), then the rows; a lot that appeared while
   waiting refuses with `409` (`error.inventory.concurrent_scope_changed`);
4. move the rows, then lock and move the ships;
5. record what moved, the audit event (REQ-PATCH-027) and the live update (REQ-PATCH-029).

Like the wipe, the relocation is an explicit **exception to the invalid-responsibility gate** of
REQ-ORDERS-055: it validates no linked job order, because it is a game fact and no order write.

**Acceptance**

- [x] A relocation runs once per member and phase and commits whole or not at all.

**Enforced by:** `HomeLocationRelocationIntegrationTest` · **Code:** `MemberRelocations`,
`InventoryStockRelocation`, `PatchChangeRepository#lockHistoryShared`

### REQ-PATCH-027 — Audit of the phase

The patch-change trail (REQ-PATCH-009) records:

| Event | When |
| --- | --- |
| `PATCH_CHANGE_RELOCATED` | per member; the subject and the target are the member, the details are the patch change and its label, the location, the moved row and ship counts and who chose (`SELF`, `ADMIN`, `CLIENT`), for `CLIENT` also the registry id of the client (`client`) |
| `PATCH_CHANGE_PHASE_ENDED` | an admin ended the phase early; the subject is the patch change with its label, the details the number of members still pending |

Single rows and ships are not audited one by one, and no free text or personal data goes into the
details.

**Acceptance**

- [x] A relocation records `PATCH_CHANGE_RELOCATED`, an early end `PATCH_CHANGE_PHASE_ENDED`.

**Enforced by:** `HomeLocationRelocationIntegrationTest`, `AdminAuditLogPageControllerTest` ·
**Code:** `MemberRelocations`, `HomeLocationPhaseEnd`, `AuditEventType`

### REQ-PATCH-028 — The gauge of pending relocations

The gauge `basetool_patch_change_relocations_pending` counts the members still to relocate in the
open phase, `0` while none is open. The module samples it through its `BusinessGaugeSource`, and the
business dashboard shows it in a panel. There is no alert rule (#3257).

**Acceptance**

- [x] The gauge reads the pending members of the open phase and `0` without one.

**Enforced by:** `HomeLocationRelocationIntegrationTest`, the dashboard JSON · **Code:**
`PatchChangeBusinessGauges`, `PatchChangeMetricNames`

### REQ-PATCH-029 — Live update of the phase

- After a relocation, the Lager's rooms (`inventory`, sections `stock` and `stock-levels`), the
  Materialbörse (`board`), the member's hangar (`hangar`, `ships`) and the hangar rooms of the units
  the moved ships belong to refresh in place; the admin's patch-change page and the member's admin
  tab refresh through the `patch-change` room (section `history`), which also announces an early
  end.
- After a trigger with the option on, the asked members' open pages are handed off to the gate
  (REQ-PATCH-020).
- The member's page navigates into the tool after the choice, as the consent page does.

**Acceptance**

- [x] A relocation and a phase end announce their rooms after the commit.

**Enforced by:** `HomeLocationRelocationIntegrationTest`, `LiveSyncSectionMapParityTest` · **Code:**
`InventoryStockRelocation`, `HangarShipRelocation`, `PatchChangeLiveSync`,
`HomeLocationPromptEffect`

### REQ-PATCH-030 — The profile setting „Meine Home Location“

Every member has a profile setting **„Meine Home Location“** (#3252, the profile surface of #3256).
It is editable at any time from the **curated home locations** (REQ-PATCH-021: the home-location
flag, not hidden, not reference-only) and is the default location of three forms (REQ-PATCH-033).
It **only sets that default and moves nothing**; new members set it there.

- It is stored per member in `member_home_location` (owned by the `identity` module, `V427`), with
  its **own optimistic-lock version** that starts at `1`, so it never collides with an edit of the
  member's other settings and version `0` always means "no choice". A location deleted later leaves
  the setting empty.
- The profile shows it as section „6 · Meine Home Location“ of the settings card: a select of the
  curated locations by name, the hint that it only prefills the forms and moves nothing, and the
  shared save bar, which saves it in place (`POST /profile/home-location`, REQ-FE-001). A stored
  location the profile no longer offers shows as no choice. There is no way back to "no choice".
- Endpoints: `GET`/`PUT /api/v1/users/me/home-location` (any member, only their own row). A
  location that is not curated is refused with `400` (`error.profile.home_location_not_offered`), a
  stale version with `409`; choosing the stored location again changes nothing.
- The setting is part of the member's Art. 15 / Art. 20 export (`homeLocationPreference`), goes
  with the account (FK `ON DELETE CASCADE`) and stays with the source account in an account merge.

**Acceptance**

- [x] A member reads and sets their own home location from the curated ones; another location and
  a stale version are refused.
- [x] The profile section lists the curated locations by name with the stored one selected and
  saves in place.

**Enforced by:** `MemberHomeLocationIntegrationTest`, `MyHomeLocationControllerIntegrationTest`,
`ProfileHomeLocationMvcTest`, `HomeLocationPreferenceReaderTest`, `IdentityBackendClientTest`,
`PersonalDataExportCatalogueTest`, `DataExportEndStateTest`, `GdprParticipantCoverageTest` ·
**Code:** `MemberHomeLocation`, `MemberHomeLocationRepository`, `MemberHomeLocationService`,
`MyHomeLocationController`, `IdentityExportSections`, `IdentityUserReassignment`,
`ProfileHomeLocationController`, `ProfileHomeLocationProxyController`,
`HomeLocationPreferenceReader`, `templates/identity/profile.html`, `static/js/identity/profile.js`

### REQ-PATCH-031 — The latest home-location choice overwrites the setting

When a relocation stores a member's choice (REQ-PATCH-022), whoever chose (`SELF`, `ADMIN`,
`CLIENT`), the member's setting „Meine Home Location“ becomes the chosen location **in the
relocation's transaction**: a relocation that fails leaves the setting as it was. The
relocation calls `identity.api.HomeLocationPreference#followRelocation`, which writes whatever the
setting's version is and gives it a new version, so a profile page still holding the old version
gets `409` on its next save. A choice equal to the stored setting changes nothing.

**Acceptance**

- [x] After a relocation the setting is the chosen location with a new version; a failed choice
  leaves it unchanged.

**Enforced by:** `HomeLocationRelocationIntegrationTest`, `MemberHomeLocationIntegrationTest` ·
**Code:** `MemberRelocations`, `identity.api.HomeLocationPreference`,
`MemberHomeLocationService#followRelocation`, `MemberHomeLocationRepository#follow`

### REQ-PATCH-032 — Audit of the setting

Every change of the setting records `HOME_LOCATION_PREFERENCE_SET` in the patch-change trail
(REQ-PATCH-009, #3257): the subject and the target are the member, the details are the curated
location's name and the `source`, `PROFILE` for the member's own change or `RELOCATION` for a
relocation's (REQ-PATCH-031). An unchanged choice records nothing. No free text and no personal data
go into the details. The admin viewer lists the event under „Patchwechsel“ with its DE/EN label;
`basetool_audit_events_total{domain}` counts it with the area, and no further metric is needed
(REQ-OBS-011).

**Acceptance**

- [x] A member's change and a relocation's change each record one event with their source.

**Enforced by:** `MemberHomeLocationIntegrationTest`, `HomeLocationRelocationIntegrationTest`,
`AdminAuditLogPageControllerTest` · **Code:** `MemberHomeLocationService`, `AuditEventType`,
`AdminAuditLogPageController`

### REQ-PATCH-033 — The home location prefills the location of three forms

The member's setting (REQ-PATCH-030) is the default location of:

- the **stock booking** („Einbuchen“, `/inventory/input`): a fresh form starts at it, from every
  entry point; a form re-shown after an error keeps what the member entered, and the merge probe
  reads the prefilled location like a chosen one
  ([REQ-INV-026](inventory-lager.md#req-inv-026--write-time-stock-merge-for-piece-auto-and-scu-per-action-opt-in));
- the **refinery store** (the „Einlagern“ dialog of a refinery order): every item starts at it,
  else at the order's refinery as before;
- **adding a ship** (the member's own hangar, „Schiff hinzufügen“): the dialog opens at it.

The default is only a starting value; the member may pick any location the form offers. A form
whose location list does not hold it starts empty, and a failed read of the setting leaves the
form as without one. The admin's ship dialog in a member's hangar and the other location fields
keep no default.

**Acceptance**

- [x] The three forms start at the member's home location and fall back as before without one.

**Enforced by:** `InventoryInputFormPatternRenderTest`, `RefineryOrderStorageCalculationTest`,
`HangarPageControllerMvcTest`, `HomeLocationPreferenceReaderTest` · **Code:**
`HomeLocationPreferenceReader#formDefault`, `InventoryInputPageController`,
`RefineryOrderPageController#addOrderAndForms`, `HangarPageController`, `static/js/hangar/hangar.js`

### REQ-PATCH-040 — The currency wipe follows the game in the Bank and the aUEC orders

The option **currency wipe** is off by default and offered for both kinds (REQ-PATCH-002). When it
is on, the trigger runs it inside the all-or-nothing step (REQ-PATCH-005), with the live time as
its criterion (ADR-0381): first in the aUEC orders (REQ-PATCH-043…045), then in the Kartellbank
(REQ-PATCH-041, -042). The orders run first because they take their order guards before any Bank
request, as a linked Bank decision does, and their cancelled attempts release their bindings
before the shortages are measured. Every order takes part whatever its status and its
responsibility: the wipe is an explicit exception to the invalid-responsibility gate of
[REQ-ORDERS-055](orders-core.md#req-orders-055--invalid-responsibility-permits-reading-and-valid-transfer-only),
because a patch change is a game fact. Nobody is notified.

**Acceptance**

- [x] With the option off nothing in the Bank or the orders changes and no count is reported.
- [x] The orders run before the Bank; a refusal of either rolls the whole patch change back.

**Enforced by:** `CurrencyWipeEffectTest`, `PatchChangeCurrencyWipeIntegrationTest` · **Code:**
`CurrencyWipeEffect`, `joborder.api.JobOrderCurrencyWipe`, `bank.api.BankCurrencyWipe`,
`JobOrderEditor#lockMoneyOrdersForPatchChange`

### REQ-PATCH-041 — The Bank reset offsets the balances as of the live time

One `WIPE_RESET` transaction offsets the balances **as of the live time** on both ledgers
(ADR-0384): a negative account leg per account and a negative holder leg per holder whose balance
from the postings before the live time is not zero. Every booking after the live time stays.

When an offset would take an account or a holder below zero, because more was booked out of it
than into it since the live time, the offset **stops at zero**: the account or holder ends at
zero, and a holder that is already below zero keeps its balance. The no-overdraft rule
(REQ-BANK-006) therefore holds, and the patch change always goes through. A negative holder
balance of the live time is offset exactly. The preview and the record count the accounts whose
offset stopped at zero; the Bank's summary event counts the stopped accounts and holders.

The standalone reset button of [REQ-BANK-013](bank.md#req-bank-013--admin-wipe-reset) keeps its
behaviour, and both share one booking. The staff grid, the org-unit Bank view and every account
detail the reset booked on refresh in place.

**Acceptance**

- [x] After the reset the accounts and holders hold exactly what was booked after the live time.
- [x] An account whose later debits exceed its later credits ends at zero, and the clamp is
  counted.

**Enforced by:** `BankWipeOffsetsTest`, `BankCurrencyWipeIntegrationTest`,
`CurrencyWipeEffectTest`, `BankLedgerServiceTest` · **Code:** `BankCurrencyWipeService`,
`BankWipeOffsets`, `BankWipeResetBooking`, `BankPostingRepository#accountBalancesBefore`,
`BankHolderPostingRepository#holderAmountsBefore`, `#holderAmountsOf`

### REQ-PATCH-042 — Pending Bank requests from before the live time are cancelled

Every pending Bank booking request submitted before the live time that no order payment attempt
owns ends as `CANCELLED`, with the patch change as its reason
(`bank_booking_request.cancelled_by_patch_change`). The cancellation clears the staff's open
request notices like any other, and a purpose-bound request tells its owner (REQ-BANK-063). A
request submitted after the live time stays pending; a request of an order attempt follows
REQ-PATCH-043.

Wherever the web shows the request's status it names the patch change by its current label
beneath the status („Patchwechsel 4.4“): „Meine Anträge“
([REQ-BANK-022](bank.md#req-bank-022--confirm-before-post-booking-requests-create--cancel)),
„Anträge an unsere Konten“, the staff queue (REQ-BANK-023), the request's own page as its reason
(REQ-BANK-068) and the dashboard widget „Meine Bankanträge“. The bank reads the labels through
the port `bank.api.BankPatchLabels`, once per page and not at all when no request of the page
needs one. A request its requester withdrew shows the status alone. The exchange names it as
`cancelledByPatch: {id, label}` on the member's own requests
([REQ-BXCH-003](bank-exchange.md#req-bxch-003--the-members-own-requests-and-their-change-feed)).

**Acceptance**

- [x] Only requests submitted before the live time are cancelled, each with the patch change as
  its reason.
- [x] Every web view of a cancelled request names the patch change beneath its status, and only
  for a request the wipe cancelled; the exchange carries `cancelledByPatch` for exactly those.

**Enforced by:** `BankCurrencyWipeIntegrationTest`, `BankRequestPatchLabelsTest`,
`BankBookingRequestServiceTest`, `ExchangeBankRequestServiceTest`, frontend
`BankRequestCancelledByPatchRenderTest`, `DashboardTradeWidgetsMvcTest` · **Code:**
`BankCurrencyWipeRequests`, `BankBookingRequest#cancelByPatchChange`, `V425`,
`bank.api.BankPatchLabels`, `BankRequestPatchLabels`, `PatchChangeLabelDirectory`,
`ExchangeBankRequestService`, `templates/bank/fragments/bank-request-reason.html`

### REQ-PATCH-043 — Waiting order Bank attempts from before the live time are cancelled

Every order Bank attempt waiting since before the live time ends as `CANCELLED`, with the patch
change as its reason (`job_order_linked_money_attempt.cancelled_by_patch_change`), and its
pending Bank request with it (`BankLinkedPayments#cancelForCurrencyWipe`). The order decides by
the attempt's submission time and cancels the request itself, so the Bank is not called back.
Under [REQ-ORDERS-075](orders-auec-bank.md#req-orders-075---failure-cancellation-and-reversal-preserve-actual-money-facts)
this creates no booking and releases the attempt's binding; an attempt with a reported actual
payment keeps its money unavailable until a correction or a replacement. The attempt history
shows the patch label.

**Acceptance**

- [x] An attempt waiting since before the live time and its Bank request are cancelled with the
  patch change as their reason; a later attempt stays waiting.

**Enforced by:** `JobOrderLinkedBankIntegrationTest` · **Code:** `JobOrderCurrencyWipeService`,
`JobOrderLinkedMoneyAttempt#cancelForCurrencyWipe`, `AuecBankComposition`, `V424`

### REQ-PATCH-044 — Holdings that existed at the live time become real shortages

Per holder and per Bank account position of every aUEC order, the part of the booked holding that
existed at the live time and is still held becomes a **real shortage**
([REQ-ORDERS-061](orders-auec.md#req-orders-061--settlement-reuses-movements-and-explicit-purpose-changes),
-062): the lesser of the balance from the facts recorded before the live time, at their current
effective amounts, and the current balance less what stays bound once the attempts of
REQ-PATCH-043 are cancelled. Money added later and money a later attempt still binds stay.

Each shortage is its own `SHORTAGE` operation with its own operation id, recorded by no actor and
naming the patch change (`job_order_money_operation.patch_change_id`). Past provision and
commitment credit stay true. Target, net handover and status do not change; residual money from
before the live time on a completed or cancelled order goes to zero. The order's
participant-readable history shows the shortage with the patch change's current label
(REQ-ORDERS-064, through `joborder.api.JobOrderPatchLabels`).

**Acceptance**

- [x] A holding of the live time is booked as a shortage, a later provision stays, a holding moved
  away after the live time is booked only for what its holder still holds, and the status stays.
- [x] The history shows the shortage with the patch label and without an actor.

**Enforced by:** `PatchChangeCurrencyWipeIntegrationTest`, `OrdersDetailPatternRenderTest` ·
**Code:** `JobOrderCurrencyWipePlanner`, `JobOrderCurrencyWipeService`,
`JobOrderMoneyLedger.Ledger#before`, `JobOrderMoneyOperation#currencyWipeShortage`,
`JobOrderMoneyQueries`, `PatchChangeLabelDirectory`, `V424`

### REQ-PATCH-045 — Open unit commitments from before the live time end

Every open unit commitment opened before the live time ends with the reason `CURRENCY_WIPE`
([REQ-ORDERS-059](orders-auec.md#req-orders-059--unit-commitments-are-planning-with-one-time-provision-attribution)).
Credited parts stay as history; a unit that still contributes commits anew. A commitment opened
after the live time stays.

**Acceptance**

- [x] A commitment from before the live time ends with the reason `CURRENCY_WIPE` and its
  outstanding amount at zero.

**Enforced by:** `PatchChangeCurrencyWipeIntegrationTest` · **Code:**
`JobOrderMoneyCommitments#endForCurrencyWipe`, `MoneyCommitmentChangeDto.Reason`

### REQ-PATCH-046 — Preview, audit and live update of the currency wipe

The preview and the record count seven kinds: the Bank reset total (`BANK_RESET_TOTAL`, aUEC),
the accounts whose reset stopped at zero (`BANK_ACCOUNTS_CLAMPED`), the Bank requests cancelled,
the aUEC holdings booked as shortage and their total (`AUEC_SHORTAGE_TOTAL`, aUEC), the order Bank
attempts cancelled and the commitments ended. The preview counts exactly what the trigger then
does.

The trigger records one summary event in each affected trail: the job orders'
`JOB_ORDER_MONEY_CURRENCY_WIPED` (subject the patch change with its label; details `orders`,
`shortages`, `shortageTotal`, `attemptsCancelled`, `commitmentsEnded`, `patchChange`) and the
Bank's own `WIPE_RESET_EXECUTED` (details `patchChange`, `liveAt`, `accounts`, `stashes`,
`totalZeroed`, `clampedAccounts`, `clampedStashes`, `cancelledRequests`; counts only, no amount
per account). Single shortages, attempts, requests and commitments are not audited one by one. Open order pages refresh their money and participation sections and the order
queue; the Bank's staff queue and grid, the org-unit Bank view and the touched account details
refresh in place.

**Acceptance**

- [x] Preview and record carry the same seven counts.
- [x] A trigger records one `JOB_ORDER_MONEY_CURRENCY_WIPED` and one `WIPE_RESET_EXECUTED` naming
  the patch change.

**Enforced by:** `PatchChangeCurrencyWipeIntegrationTest`, `BankCurrencyWipeIntegrationTest`,
`AdminAuditLogPageControllerTest`, `LiveSyncTopicRegistryParityTest` · **Code:** `PatchCount`,
`CurrencyWipeEffect`, `JobOrderCurrencyWipeService`, `BankCurrencyWipeService`,
`JobOrderMoneyEffects#currencyWiped`, `LiveSyncTopicClass.BANK_ACCOUNT`

### REQ-PATCH-050 — An exchange client reads the latest patch change and its options

Under the capability `exchange.patch.read`, `GET /exchange/v1/me/patch-change` answers the latest
patch change by live time (`patchChange`, absent before the first): its `id`, the same the feeds
name in every entry it caused (REQ-PATCH-007), its current label, its kind, its live time and its
options as triggered — **every** wipe group in declaration order with `selected` and the keys of
its catalogue types (`source` and `value` of `WipeGroupTypes`, REQ-PATCH-010), every wipe
exception as triggered with the entry named as the stock feed names it, its target and its mode
(REQ-PATCH-011), and the refinery, currency-wipe and home-location options. An exception whose
catalogue entry was deleted since stays in the list with its stored key as `bt` and no `name`. The
list is
the central list of what a patch change wiped, so a client can apply the same groups to its own
items (#3021, #3251). The resource is its own page of the exchange documentation
([`patch-change.md`](../exchange/resources/patch-change.md)) and is additive in `v1`.

**Acceptance**

- [x] Before the first patch change the read carries no patch change; after it, its id, label,
  kind, live time, all eight wipe groups with their types and `selected`, the exceptions and the
  further options.
- [x] An exception whose catalogue entry was deleted stays in the read with its stored key.
- [x] Without `exchange.patch.read` the read answers `403 SCOPE_MISSING`.

**Enforced by:** `ExchangePatchChangeControllerTest`, `ExchangePatchChangeRouteTest`,
`ExchangeContractTest` (the schema and its fixtures) · **Code:** `patchchange.api.PatchChangeExchangeReader`,
`PatchChangeExchangeViews`, `ExchangePatchChangeService`, `ExchangePatchChangeController` (both
modules), `patch-change.schema.json`

### REQ-PATCH-051 — The read carries the member's home-location prompt

Beside the patch change the read carries the member's prompt (`homeLocation`) as the member's own
page reads it (REQ-PATCH-021, `HomeLocationPrompts`): the state (`NOT_ASKED`, `PENDING`,
`RELOCATED`, `PHASE_ENDED`) in the latest patch change that asked, which need not be the latest
patch change (REQ-PATCH-024), that patch change's id, label and live time, the chosen home location
and the time of the choice, the curated home locations on offer with the id a choice names, and,
while the member is pending, the preview per current location: rows from before the live time, the
share held for each org unit's pool, and the ships. Every location carries its name, its UEX place
and its starmap GUID as the stock feed names them (REQ-XCH-018), a home location also its star
system. Only the member's own prompt is read; an admin role widens nothing.

**Acceptance**

- [x] A pending member's read carries the phase, the offered home locations and the preview with
  the pool share; after the choice it reads `RELOCATED` with the chosen location and its time.

**Enforced by:** `ExchangePatchChangeControllerTest` · **Code:** `PatchChangeExchangeViews`,
`ExchangeLocationLinks`, `ExchangePatchChangeDto`

### REQ-PATCH-052 — An exchange client makes the member's choice with the web's rules

Under the capability `exchange.patch.write`, which the registry grants only together with
`exchange.patch.read` (`error.exchange.client.patchWriteNeedsRead`),
`POST /exchange/v1/me/patch-change/home-location` with `{"location": "<id>"}` makes the member's
choice with the rules of REQ-PATCH-021 and REQ-PATCH-022 and runs the same relocation in the same
request (REQ-PATCH-025, REQ-PATCH-026), recorded as chosen by `CLIENT`, and the member's setting
„Meine Home Location“ follows the choice as for any other (REQ-PATCH-031); the audit event
`PATCH_CHANGE_RELOCATED` names the client in `client` (REQ-PATCH-027). A second choice answers
`409 HOME_LOCATION_ALREADY_CHOSEN`, a choice without an open phase asking the member
`409 HOME_LOCATION_NOT_ASKED`, a location not on offer `400 HOME_LOCATION_NOT_OFFERED`; nothing
moves then. The request needs an `Idempotency-Key` and counts one write against the daily quota
(REQ-XCH-020); the answer names the chosen location and the moved row and ship counts. The
gateway passes the three codes on with fixed details (`ExchangeRelay.DETAILS`).

**Acceptance**

- [x] A client's choice relocates the member once, records `CLIENT` and the client in the audit
  event; a second choice and a choice without an open phase answer `409`, a location not on offer
  `400`.
- [x] Without `exchange.patch.write` the choice answers `403 SCOPE_MISSING`; the registry refuses
  the write without the read.
- [x] The gateway counts the choice as one write and refuses a body outside its schema before the
  relay.

**Enforced by:** `ExchangePatchChangeControllerTest`, `AdminExchangeRegistryControllerTest`,
`ExchangePatchChangeRouteTest`, `HomeLocationRelocationIntegrationTest`,
`ExchangeRoutesContractTest` · **Code:** `patchchange.api.PatchChangeExchangeWriter`,
`MemberRelocations`, `HomeLocationChoiceRefusedException`, `PatchChangeProblemCode`,
`ExchangeRegistryService#requireGrantable`, `ExchangeRoutes`, `V428`

### REQ-PATCH-053 — A push against what a patch change removed names the patch change

A stock op against a lot whose quantity is zero and whose latest feed entry came from a patch
change is refused per op as `REMOVED_ELSEWHERE` whatever its `expectedQuantity`, unless it expects
and keeps the empty lot or sends `override`; a ship `upsert` naming a ship a patch change removed
is refused the same way (REQ-XCH-014). The op result names the patch change in
`results[].patchChange` `{id, label}`, so a client drops its pending change and pulls instead of
asking the member (#3255 amendment). A lot that kept rows from after the live time is no removed
lot: a stale op on it stays `VERSION_CONFLICT`, which names the patch change as well
(REQ-PATCH-054). The change is additive in `v1` (`change-result.schema.json`).

**Acceptance**

- [x] After a relocation, a push against the old lot with its old quantity is `REMOVED_ELSEWHERE`
  with the patch change's id and label.
- [x] An upsert of a ship a patch change removed is `REMOVED_ELSEWHERE` with the patch change's
  id.

**Enforced by:** `ExchangePatchChangeControllerTest` · **Code:** `ExchangePatchRemovals`,
`ExchangeStockWriteService#removedByPatch`, `ExchangeShipWriteService`,
`ExchangeChangeSetAdapter#removedByPatch`, `ExchangeChangeResultDto.OpResult`

### REQ-PATCH-054 — A stale push after a patch change names it in the version conflict

A stock op that expected another quantity than the lot holds, a share or unshare the lot no longer
allows for the same reason, and a ship `upsert` or `remove` that sends an older version are refused
per op as `VERSION_CONFLICT` (REQ-XCH-014). When the lot's or the ship's latest feed entry came
from a patch change, typically a relocation that moved part of a lot whose rows from after the live
time stayed, or moved a ship, the op result also names the patch change in
`results[].patchChange` `{id, label}`, so a client can tell the member why its view is stale
(owner decision D1). A conflict after any other change carries no `patchChange`. The property is
the optional one of REQ-PATCH-053 (`change-result.schema.json`); nothing else on the wire changes.

**Acceptance**

- [x] After a relocation that leaves rows from after the live time in a lot, a push with the lot's
  old quantity is `VERSION_CONFLICT` with the patch change's id and label.
- [x] After a relocation moved a ship, an upsert with the ship's old version is `VERSION_CONFLICT`
  with the patch change's id.

**Enforced by:** `ExchangePatchChangeControllerTest` · **Code:** `ExchangePatchRemovals`,
`ExchangeStockWriteService`, `ExchangeShipWriteService#staleVersion`,
`ExchangeChangeSetAdapter#versionConflict`

### REQ-PATCH-057 — An org-stock push against an entry a patch change moved or wiped names it

A patch change touches shared pool rows too. The wipe deletes the rows from before the live time of
the wiped types, personal and shared alike (REQ-PATCH-012). The relocation moves the relocating
member's rows from before the live time, pool rows included, in place and raises their version
(REQ-PATCH-025). An org-stock op names one entry by id and version (REQ-XCH-045), so the refusal is
judged by that entry's own last change and not by its lot, which sums many holders:

- every org-stock feed entry a shared row's own write makes, or the write of its earmarks, names the
  row in `exchange_change.org_entry_id` (`V429`). A lot-level re-announce (a location or unit
  rename, a member joining the pool, a holder's rename or name withholding, an earmarked mission's
  rename) names no row. The column holds only the row id and is purged with the feed (REQ-XCH-013);
- an op the Lager refuses `VERSION_CONFLICT` names the patch change in `results[].patchChange`
  `{id, label}` when the latest feed entry naming that row in the op's pool came from a patch
  change, as REQ-PATCH-054 does for a lot;
- an op the Lager refuses `NOT_PERMITTED` because the entry is gone is refused `REMOVED_ELSEWHERE`
  with the `patchChange` when the latest feed entry naming that row in the op's pool came from a
  patch change, as REQ-PATCH-053 does for a lot. Every other gone entry, and every other
  `NOT_PERMITTED`, stays as it is;
- a patch change's write to another row of the same lot, or a lot-level re-announce from a patch
  change, names the patch change for no entry.

The property is the optional one of REQ-PATCH-053 (`change-result.schema.json`); nothing else on the
wire changes.

**Acceptance**

- [x] After a relocation moved a pool entry, an op with its old version is `VERSION_CONFLICT` with
  the patch change's id and label.
- [x] An op on a pool entry the patch change deleted is `REMOVED_ELSEWHERE` with the patch change's
  id and label.
- [x] After a patch change only re-announced a lot, an op on an entry of it deleted elsewhere stays
  `NOT_PERMITTED` and a stale op on another entry stays `VERSION_CONFLICT`, both without
  `patchChange`.
- [x] The org-stock feed names the row for a row's own write and no row for a lot-level re-announce;
  `exchange_record_org_change` has one signature.

**Enforced by:** `ExchangeOrgStockPatchChangeTest`, `V429ExchangeOrgEntryMigrationTest` · **Code:**
`OrgStockRefusals`, `OrgStockExecutor`, `OrgStockPlan.PoolWrite#entry`,
`ExchangePatchRemovals#latestPatchChangeOfOrgEntry`, `ExchangeChangeRepository#findLatestForOrgEntry`,
`V429`
