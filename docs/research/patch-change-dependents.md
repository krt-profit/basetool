# Patch change: what depends on a stock row, a ship and a refinery order

> **Doc type:** Research note — a snapshot of findings, not a spec and not a decision. Collected
> 2026-11-10 against `origin/main` at `fd7ffdc5b`, plus the open draft PR #3214 in section (h).
> Nothing here is binding.
>
> **Question:** issue #3249 of the planning map #3246. When a patch change deletes or relocates a
> member's stock rows, ships and refinery orders server-side, what else is affected, how do
> today's paths handle it, and which guarantees would a server-side bulk path skip?

Reference prefixes:

- `B/` = `backend/src/main/java/de/greluc/krt/profit/basetool/backend/`
- `MIG/V<n>` = the migration `backend/src/main/resources/db/migration/V<n>__*.sql`
- `FE/` = `frontend/src/main/resources/static/js/`
- `PR3214:` = the branch `origin/codex/spec-auec-joborders:`, combined with the prefixes above
  (section (h) only)
- Spec and ADR paths are repository-relative (`docs/specs/...`, `docs/adr/...`).

## Summary

- **No server write escapes the Exchange feeds.** Stock, ship and refinery-order changes reach
  `exchange_change` through AFTER ... FOR EACH ROW triggers (`MIG/V252:126-146`, `MIG/V304:1-57`,
  `MIG/V367:51-67`). This includes JPQL and native bulk statements, by design
  (`docs/adr/0224-the-change-feed-is-a-trigger-written-key-log.md:17-21`). Outside a web request
  or an Exchange client call, a change is attributed `system` (`B/exchange/internal/ChangeSource.java:75-100`).
- **Only one path deletes stock by scope today.** That is the admin global-stock wipe
  (REQ-INV-041, `InventoryCheckoutService#deleteAllGlobalInventory`). It removes only
  `personal = false` rows in the caller's request scope, in one JPQL DELETE. It takes no lock and
  has no donation guard. Allocations and offers go with the rows through `ON DELETE CASCADE`.
  No path deletes other members' `personal = true` rows in bulk, except account erasure.
- **Stock never moves in place.** Every location move inserts a new row and lowers or deletes the
  source (REQ-INV-001). The bulk rebook mode `LOCATION`:
  - copies the note and every job-order and mission earmark in full;
  - refuses a row with a donation pledge (one such row aborts the whole run);
  - drops the row's offers after a "stock gone" notice;
  - optionally merges into an identical row at the target (REQ-INV-026), summing amounts and
    unioning allocations;
  - works only on the caller's own rows, and stamps one org unit on every row it moves.
- **Ships have two dependents.** `mission_unit.ship_id` is set to null on delete, and
  `exchange_ship_link` cascades. The only `ShipDeletionObserver` notifies, audits and bumps the
  mission unit's version, but the bulk erasure delete bypasses it. The bulk home-location update
  does not bump `Ship.@Version`. Nothing in the data model tells a pledged ship from one bought
  in-game.
- **"Ending" a refinery order today means status `CANCELED`.** That is a soft cancel. A hard delete
  exists only through the Exchange. The mission finances count every linked order whatever its
  status, so a cancel does not change mission figures. A path that ends orders must advance the
  `gameDataVersion` counter and publish `readyCleared`, or stale writes pass and ready notices stay.
- **Stock live sync is published by the browser.** A server-side bulk path must publish its own
  rooms (ADR-0274, Amendment 1). Gauges sampled from the database see a bulk change. Write-time
  counters and notices only fire on the paths that emit them.
- **Under PR #3214**, every stock write takes a shared hierarchy lock. One job order with invalid
  responsibility refuses the whole global wipe with HTTP 400.

## (a) Deleting stock rows

### The append-only rule and the REQs that delete

- REQ-INV-001 is about folding rows, not about deleting them. A write "does not fold a new or
  edited `InventoryItem` into a different existing row unless the scoped stock merge of
  REQ-INV-026 applies" (`docs/specs/inventory-lager.md:50-52`). Its acceptance criterion: an
  in-place edit never deletes the row in favour of a matching one (`:78-79`).
- These REQs allow a hard delete of a row:
  - a depleted source (REQ-INV-007, `:315-317`, `:347`);
  - a depleting book-out (REQ-INV-025, `:372-374`, `:392-394`);
  - merge victims (REQ-INV-026, `:433-437`);
  - the bulk rebook source (REQ-INV-036, `:954-956`);
  - the bulk checkout (REQ-INV-044, `:853-857`);
  - account deletion (REQ-DATA-008, `docs/specs/data-persistence.md:487`).
- **REQ-INV-041** is the only scope-wide delete (`docs/specs/inventory-lager.md:1321-1358`). It
  covers every non-personal row in the current org-unit scope, "in one statement". Personal rows
  are never touched (`:1325-1328`). Allocations and offers go by `ON DELETE CASCADE`
  (`:1329-1331`). One `INVENTORY_WIPED` event is recorded (`:1337-1338`).
- **No REQ covers bulk-deleting other members' `personal = true` rows.**

### Existing delete paths

- **Global wipe, `B/inventory/internal/InventoryCheckoutService.java:839-866`.** The endpoint is
  `DELETE /api/v1/inventory/all`, `hasRole('ADMIN')`, and the steps run in this order:
  1. Read the scope from `requestScopeResolver.currentScopePredicate()` (`:841`). This needs a
     request scope.
  2. `stockChangeObserver.beforeWipe(...)` (`:847-848`).
  3. `deleteAllNonPersonal`, a JPQL `DELETE FROM InventoryItem i WHERE i.personal = false AND`
     the scope triple (`B/inventory/internal/InventoryItemRepository.java:994-999`;
     `B/inventory/api/InventoryScope.java:41-48`). It is `@Modifying` without
     `clearAutomatically`.
  4. One `INVENTORY_WIPED` event with no subject and no target, carrying the details `scope`
     (`adminAll` or `active=<id>`) and `removed` (`InventoryCheckoutService.java:857-864`).
  - It takes **no advisory lot lock, no row lock, no version check and no `requireNoDonation`**.
  - It sends no owner notification and publishes no backend live sync.
- **Book-out, TRANSFER, personal rebook, stolen split and pool change** all reach
  `LotRows.lower` (`B/inventory/internal/LotRows.java:131-149`). When the row is depleted, it
  calls `requireNoDonation` (a 422 if a pledge exists), then `observer.beforeDelete`, then an
  entity delete. Otherwise it sets the lower amount, runs the R5 `fits` check, `saveAndFlush`, and
  `observer.lower`.
- **Bulk checkout** (`InventoryCheckoutService.java:868-911`):
  - locks each row `PESSIMISTIC_WRITE` (`InventoryItemRepository.java:855-860`) and checks the
    owner;
  - calls `beforeDelete(BULK_CHECKOUT)`, then `deleteAllById` (`:901-902`);
  - records one `INVENTORY_BULK_CHECKED_OUT` event (`:905-910`);
  - has **no donation guard**.
- **Job-order handover and consumption** (`B/inventory/internal/InventoryStockCommands.java:280-304`,
  `:306-356`): `requireNoDonation`, then `beforeDelete(HANDOVER)`, then the delete.
- **Merge fold** (`InventoryCheckoutService.java:733-801`): `deleteAll(victims)` with no observer
  call. Rows that back an offer are never folded (`:744-746`, `:725-726`).
- **Account deletion** (`B/privacy/api/UserDeletionService.java:184-241`) runs three participants:
  - `MaterialExchangeOfferErasure` first, reason `user-deletion`
    (`B/materialexchange/internal/MaterialExchangeOfferErasure.java:42-68`);
  - then the bulk `deleteByUserId`, whose Javadoc says it "must stay a bulk delete"
    (`InventoryItemRepository.java:1001-1013`; `B/inventory/internal/InventoryUserErasure.java:40-65`);
  - `SupplyUserErasure`, which withdraws open pledges (`B/supply/internal/SupplyUserErasure.java:31-66`).
- **Advisory lot locks** (ADR-0229, `InventoryStockCommands.java:68-111`) are taken only by the
  Exchange, supply, purchasing, undo and catalogue-reassignment paths. ADR-0229 states that the
  stock web paths do not take it (`docs/adr/0229-exchange-stock-writes-take-an-advisory-lock-per-lot.md:66-68`).

### Dependents of a stock row

| Dependent | Link | On delete | Consequence |
|---|---|---|---|
| Job-order earmark | `inventory_item_job_order_allocation` (`MIG/V217:12`) | CASCADE | Earmarked figures fall, open need rises, the per-slice `delivered` flag is lost (`B/joborder/internal/JobOrderStockReadService.java:63-139`, `B/joborder/internal/JobOrderQueryService.java:358-404`) |
| Mission earmark | `inventory_item_mission_allocation` (`MIG/V217:33`) | CASCADE | The rows leave the mission's linked stock (`InventoryItemRepository.java:200-226`); income is booked only on SELL (`InventoryCheckoutService.java:437-487`) |
| Donation pledge | `inventory_item_donation_allocation` (`MIG/V284:3`) and `supply_donation.source_inventory_item_id`, a plain id (`MIG/V285:63`) | CASCADE / dangling | The donation stays PLEDGED. On receive the source is missing and the stock is booked fresh into the pool (`B/supply/internal/SupplyDonationService.java:347-367`) |
| Material exchange offer | `material_exchange_offer.inventory_item_id` (`MIG/V210:18`); interests cascade from the offer (`MIG/V210:47`) | CASCADE | The offer row is deleted, not set to a status |
| Job-order handover item | none; the column was dropped (`MIG/V64:1-13`) | — | A snapshot keeps material, quality, amount, location name and stolen (`B/joborder/api/JobOrderHandoverItem.java:59-81`) |
| Purchasing delivery | `booked_row_id`, a plain id (`B/purchasing/internal/PurchasingDealDelivery.java:102-105`; `MIG/V288:218-227`) | dangling | Written once, never read in main code |
| Refinery store | no back-reference (`InventoryStockCommands.java:598-642`; `B/refinery/internal/RefineryOrderStoreBooking.java:90-121`) | — | none |
| Minimum stock level | no row reference (`MIG/V314:1-22`) | — | Computed at read time (see below) |

Earmarks live only in the three allocation tables; the scalar columns were dropped
(`MIG/V218:11-13`). The entity side is `B/inventory/internal/InventoryItem.java:152-180`.

### Minimum stock levels

- Figures are sums at read time. `sumPoolStock` groups by unit, subject, quality, personal and
  stolen over every location (`B/inventory/internal/StockLevelRepository.java:191-225`). Free stock
  is 0 for personal or stolen rows (`B/inventory/internal/StockLevelAllocation.java:72-79`), and
  `low` means allocated < minimum (`:126-127`). Deleting pool rows therefore turns levels low;
  deleting personal rows changes nothing.
- Low-stock notices come from the timed `StockLevelNoticeProducer`
  (`B/inventory/internal/StockLevelNoticeProducer.java:38-73`), not from the write. It runs every
  `app.notifications.timed.interval`, by default PT1M
  (`B/notification/internal/NotificationTimedTask.java:52-56`). It shares a budget of 100 events
  per tick (`B/notification/internal/NotificationTimedProperties.java:38-46`), so a large wipe
  drains over several ticks. It sends one notice per level and re-arms once allocated ≥ target
  (`StockLevelNoticeProducer.java:59-71`).

### Material exchange offers (`StockChangeObserver`)

- `StockChangeObserver` has exactly one implementation, `MaterialExchangeOfferRatchet`
  (`B/inventory/api/StockChangeObserver.java:37-70`;
  `B/materialexchange/internal/MaterialExchangeOfferRatchet.java:55`).
- `lower` clamps active offers to the new amount and records `MARKET_OFFER_REDUCED`; a reduced
  offer sends no notice (`MaterialExchangeOfferRatchet.java:73-97`, `:206-220`).
- `beforeDelete`, `beforeWipe` and `removeOffersOnRowsOf` handle each ACTIVE offer the same way
  (`:155-196`):
  - publish `MATERIAL_EXCHANGE_OFFER_UNAVAILABLE` (`STOCK_GONE`) to the interested members, after
    commit;
  - clear the owner's interest notices;
  - record `MARKET_OFFER_REMOVED` with the reason; `wipe` for the wipe
    (`B/inventory/api/StockChangeReason.java:33-49`).
- **`beforeWipe` only sees active offers on `personal = false` rows**
  (`B/materialexchange/internal/MaterialExchangeOfferRepository.java:260-275`). Personal rows can
  back offers too (`InventoryItemRepository.java:1030-1036`). Their offers, and every
  non-ACTIVE offer, cascade away with no audit and no notice. The offer read and the delete are
  separate statements with no lock in between.

## (b) Relocating stock rows

### Insert, not update

- Outside the merge, TRANSFER, personal rebooking and bulk rebooking each insert their own row,
  and an in-place edit changes only the row it targets (`docs/specs/inventory-lager.md:53-56`). A
  partial SCU TRANSFER "decrements the source and inserts a new row at the target" (`:80-81`).
- Every path that changes a location writes a new row:
  - TRANSFER (`InventoryCheckoutService.java:389-410`);
  - bulk `LOCATION` (`:1259-1278`);
  - `transferToPool` (`InventoryStockCommands.java:401-428`).
- The only in-place `setLocation` is the Exchange undo
  (`B/inventory/internal/InventoryPoolRestorer.java:382-398`).
- The schema does not make the location immutable. The class-level `@Setter` covers every field
  (`InventoryItem.java:70`), and `location` is a plain `@JoinColumn(nullable = false)`
  (`:113-116`).

### TRANSFER (one row, part or all)

Path: `InventoryCheckoutService.java:206-275`, then `:351-435`.

- Loads the row without a lock and checks the version only if the client sends one (`:213-216`).
- Requires an offered target location (`:370-376`) and resolves the target pool from the target
  user (`:383-387`).
- `LotRows.split` builds the new row without a note and without earmarks
  (`B/inventory/internal/LotRows.java:74-116`). The reduced earmarks are then copied onto it
  (`applyTransferInherit`, `InventoryCheckoutService.java:403`, `:499-522`); with no plan, a full
  move inherits every earmark (`B/inventory/internal/AllocationReductions.java:136-180`).
- Donation slices never move. Offers stay with the source, so they are ratcheted down or cascade
  away.
- Records `INVENTORY_ITEM_TRANSFERRED`, without the old location (`:411-420`). Notices are sent
  only when the owner changes (`:421-431`).

### Bulk rebook, mode `LOCATION`

- **Mode and request.** `B/inventory/internal/BulkRebookMode.java:32-37`. The request has no
  version field (`B/inventory/internal/BulkRebookRequest.java:28-53`).
- **Who may call it.** `POST /api/v1/inventory/bulk-rebook` needs `INVENTORY_ACCESS`
  (`B/inventory/web/InventoryItemController.java:873-878`). Every row must belong to the caller
  (`InventoryCheckoutService.java:980-1000`). Even an admin cannot move another member's stock
  this way (`docs/specs/inventory-lager.md:975-977`).
- **Locks.** Rows are locked `PESSIMISTIC_WRITE` in sorted id order (`:982-988`;
  `InventoryItemRepository.java:871-874`). No lot lock is taken and no per-row version is checked
  (`docs/specs/inventory-lager.md:977-980`).
- **One org unit for the whole run.** A single `targetOwningOrgUnit` is resolved from the target
  user or the owner (`InventoryCheckoutService.java:1039-1041`) and stamped on every moved row,
  personal or shared (`:1067`, `:1261`). With no explicit pick and several memberships, the
  resolver needs a matching active pin, or it throws `OwnerOrgUnitRequiredException`
  (`B/scope/api/OrgUnitStampingService.java:306-320`). Pool rows of several units therefore cannot
  keep their own units in one run.
- **Skip rule.** A row is skipped only if it is already at the same user and location (`:1050-1053`).
- **Per row, `rebookWholeRow`** (`:1249-1281`):
  1. `requireNoDonation`: a pledged row is a 422 that aborts the whole transaction (`:1256`;
     `B/inventory/internal/InventoryDonationGuards.java:43-47`).
  2. Insert the new row with the full amount, personal and stolen flags, and the copied note
     (`:1259-1269`).
  3. Copy every job-order and mission slice in full, `delivered` included (`:1271-1275`).
  4. Save; `beforeDelete([source], REBOOK)`; delete the source (`:1276-1278`).
  5. Merge if requested (`:1280`).
- **Offers.** The source's offers get `MARKET_OFFER_REMOVED` (reason `rebook`) and a `STOCK_GONE`
  notice, then cascade away. They do not follow the stock
  (`MaterialExchangeOfferRatchet.java:106-114`; `docs/specs/materialboerse.md:186-192`).
- **Audit and notices.** One `INVENTORY_BULK_REBOOKED` event with mode and counts, only when
  something moved (`:956-965`). Owner-change notices go out through
  `publishTransferNotifications` (`:1072`).

### Write-time merge (REQ-INV-026) at the target

- **When it runs.** PIECE stock always merges; SCU stock merges only on opt-in. It runs on every
  row the bulk rebook moves (`docs/specs/inventory-lager.md:410-419`).
- **Merge key.** Owner · material or game item · location · quality · `personal` · owning org unit
  (null matches null) · `stolen` (`:431-437`; `InventoryItemRepository.java:901-913`). Earmarks and
  the note are not part of it.
- **Exclusion.** A row that backs an offer is never folded (`docs/specs/inventory-lager.md:448-452`).
  So rows at the target that back an offer stay separate.
- **Effect** (`InventoryCheckoutService.java:733-801`):
  - amounts are summed;
  - distinct notes are concatenated and cut to 1000 characters;
  - allocations are unioned: the same target is summed, `delivered` is OR-ed, donations are added
    (`B/inventory/internal/InventoryAllocations.java:281-306`);
  - there is no cap, because the amounts were summed too (`docs/specs/inventory-lager.md:595-598`);
  - the group is locked `FOR UPDATE`; the event is `INVENTORY_ITEM_MERGED`.

### Location-dependent things downstream

- **Earmarks.** No earmark target carries a location. `JobOrder` has none; `Mission` has a
  free-text meeting point only (`B/mission/api/Mission.java:104-107`). The earmark records carry
  ids and labels (`B/inventory/api/JobOrderEarmark.java:32`; `B/inventory/api/DonationEarmark.java:33`).
- **Minimum stock levels** have no location column (`MIG/V314:1-17`). They do depend on the pool,
  and a bulk `LOCATION` run can re-stamp the pool.
- **Location deletion.** There is no inventory `LocationUsage`, only the ship and refinery ones
  (`B/catalogue/internal/LocationService.java:284-304`). Stock is protected only by the FK
  `inventory_item.location_id` (`MIG/V5:8`).

### Other bulk paths that rewrite many rows

| Path | How it writes | `@Version` | Locks |
|---|---|---|---|
| Org-unit change (`B/inventory/internal/InventoryOrgUnitChangeService.java:94-145`) | entity update | bumped | pessimistic, sorted |
| Org restamp (`B/inventory/internal/InventoryOrgUnitReconciler.java:109-133`) | entity update | bumped | none |
| Stolen mark (`B/inventory/internal/InventoryStolenMarkService.java:152-160`) | entity update | bumped | pessimistic, sorted |
| Account merge (`B/platform/api/OwnedRowsMover.java:108-117`) | native `UPDATE ... SET user_id` | **not bumped** | none |
| Catalogue reassignment (`B/inventory/internal/InventoryItemReassignmentRepository.java:85-95`) | native update with `version = version + 1` | bumped explicitly | non-waiting lot locks (`B/inventory/internal/InventoryItemReassignment.java:56-69`) |

## (c) Ships

### The entity

- `B/hangar/api/Ship.java:56-163`. `@Version` is inherited (`B/kernel/AbstractEntity.java:43-51`).
- `owningOrgUnitVersion` (ADR-0258) is a separate counter, excluded from the optimistic lock
  (`Ship.java:115-117`). Only `changeOwningOrgUnitIfVersionMatches` writes it
  (`B/hangar/api/ShipRepository.java:157-165`). A location change touches neither it nor any
  other section counter; Ship has none.
- **Pledged vs in-game is not answered by the data model.**
  - The pledge fields (`Ship.java:123-163`, `MIG/V309:1-16`) are written only by the hangar
    import, and only the Hangar Extension CSV parser supplies them
    (`B/hangar/internal/HangarImportService.java:276-313`;
    `B/hangar/internal/HangarExtensionCsvParser.java:136`).
  - Web and Exchange creates never set them (`B/hangar/internal/HangarService.java:186-205`;
    `B/exchange/internal/ExchangeShipWriteService.java:325-336`).

### Dependents

Only two tables reference `ship(id)`:

- `mission_unit.ship_id` is `ON DELETE SET NULL` (`MIG/V51:4-10`;
  `B/mission/api/MissionUnit.java:73-77`);
- `exchange_ship_link.ship_id` is `ON DELETE CASCADE` (`MIG/V254:7`).

The cascade happens in the database only. The entity holds a plain UUID
(`B/exchange/internal/ExchangeShipLink.java:69-71`). No job-order, refinery, insurance or loadout
table references a ship.

### Delete paths

- **`HangarService#deleteShip`** (`HangarService.java:650-667`):
  1. `findById`, with no lock and no version check.
  2. `ShipDeletionObserver.beforeShipDelete`.
  3. Flush and delete.
  4. `HANGAR_SHIP_DELETED`.
  5. `unitLiveSync.shipsChanged`.
  - `deleteAllShipsForUser` (`:675-697`) does the same per ship and records one `HANGAR_EMPTIED`.
- **The only observer is `MissionUnitShipRelease`**
  (`B/mission/internal/MissionUnitShipRelease.java:56-73`). For each unit holding the ship it:
  - sends `HangarNotices.shipDeleted` to leadership and the responsible member, unless the mission
    is COMPLETED or CANCELLED (`:82-96`);
  - sets the ship to null, which bumps the `MissionUnit` `@Version`;
  - records `MISSION_UNIT_UPDATED`.
  - It bumps no Mission section counter, because no unit section exists
    (`B/mission/internal/MissionSectionVersions.java:47-166`), and publishes no live sync.
- **Account erasure** (`B/hangar/internal/HangarUserErasure.java:62-70`) uses the bulk
  `DELETE FROM Ship s WHERE s.owner.id = :ownerId` (`ShipRepository.java:273-275`) without the
  observer. The FK's SET NULL releases the mission units silently: no notice, no
  `MISSION_UNIT_UPDATED`, no `MissionUnit` version bump.
- **Exchange remove.** `ExchangeShipWriteService.java:460-486` locks the ship
  (`ShipRepository.java:90-92`) and requires a matching version (`:481`). `:553-564` deletes
  through the full `deleteShip` path and journals `SHIP_REMOVE`.

### Location change paths

- **`HangarService#setHomeLocationForMyShips`** (`HangarService.java:837-858`) requires an offered
  home location. It then runs `ShipRepository#setLocationForOwner`
  (`ShipRepository.java:259-261`):
  - a JPQL `UPDATE Ship s SET s.location = :location WHERE s.owner.id = :ownerId`, with
    `@Modifying(clearAutomatically = true)` and no `flushAutomatically`;
  - its Javadoc says it "does not bump `@Version`" (`:252`), and `updated_at` is not written
    either;
  - it takes no row lock and counts ships already at that location.
  - Afterwards it records one `HANGAR_HOME_LOCATION_SET` with the ship count, and publishes
    `HANGAR_UNIT` / `HANGAR_UNITS` (`HangarService.java:849-855`;
    `B/hangar/internal/HangarUnitLiveSync.java:67-72`).
  - It publishes no `HANGAR_OWN` and sends no notice.
- **Single-ship edit** (`HangarService.java:336-393`):
  - `OptimisticLock.checkOptionalClient` (`:340`) skips the check when the client sends no
    version (`B/kernel/OptimisticLock.java:70-80`);
  - the save bumps `@Version`;
  - it records `HANGAR_SHIP_UPDATED`.
- **Version checks.** The Exchange upsert rejects a version mismatch with `VERSION_CONFLICT`:
  `if (op.version() == null || !op.version().equals(ship.getVersion()))`
  (`ExchangeShipWriteService.java:354-356`). The Exchange remove checks the same (`:481`), and the
  contract requires the current version (`docs/exchange/resources/ships.md:12`).
  - Because the bulk update leaves the version unchanged, a client or an open edit form holding
    the old version passes both checks. It could write the old location back without a 409. This
    is an inference from the code and is not tested.

### Exchange ship feed

- The trigger is `trg_ship_exchange_change`, AFTER INSERT/UPDATE/DELETE FOR EACH ROW
  (`MIG/V252:130-146`). Both the JPA delete and the JPQL bulk update reach it.
- A moved ship arrives as an item with its new location and its unchanged `version`
  (`B/exchange/internal/ExchangeShipFeedService.java:135-161`, `:218`). A deleted ship arrives as a
  tombstone.
- An upsert that names a gone ship is answered `REMOVED_ELSEWHERE`, unless this installation
  removed it or sends `override` (`ExchangeShipWriteService.java:418-428`).
- `ShipLocationUsage` blocks deleting a location that ships still stand at
  (`B/hangar/internal/ShipLocationUsage.java:31-55`). A relocation changes which locations count
  as used.

## (d) Refinery orders

### Status and entity

- `RefineryOrderStatus` has four values: `OPEN`, `IN_PROGRESS`, `COMPLETED`, `CANCELED`
  (`B/refinery/api/RefineryOrderStatus.java:23-32`). There is no state machine.
  - A web PUT may set any status except on a stored order
    (`B/refinery/internal/RefineryOrderService.java:374-376`;
    `B/refinery/internal/RefineryOrderEdits.java:55-60`).
  - Exchange writes may set only `OPEN` or `IN_PROGRESS`
    (`B/exchange/internal/dto/ExchangeRefineryOrderChangeSet.java:103`).
- **Section counters.** `gameDataVersion` covers status, location, timer, goods and the store
  markers; `financesVersion` covers the money fields
  (`B/refinery/internal/RefineryOrder.java:159-174`; `MIG/V368`). Both are raised in application
  code only (`B/refinery/internal/RefineryOrderSection.java:86-98`).
- **Provenance** allows only `WEB` or `CLIENT`; there is no system value
  (`B/refinery/internal/RefineryChangeSource.java:23-28`; `MIG/V369:12-14`).

### Cancel vs delete

- **Web "delete" is a soft cancel** (`RefineryOrderService.java:462-487`):
  1. Locks the row.
  2. Sets `CANCELED`.
  3. `GAME_DATA.advance`.
  4. Records the web provenance.
  5. Publishes `RefineryNotices.readyCleared`.
  6. `announceByOther` when the actor is not the owner.
  7. Records `REFINERY_ORDER_CANCELED` with `previousStatus`.
- **Hard delete exists only through the Exchange** (`B/refinery/internal/RefineryExchangeWrites.java:257-275`).
  It refuses stored orders, records `REFINERY_ORDER_DELETED`, deletes, and publishes
  `readyCleared`. The goods FK has no ON DELETE (`MIG/V1:496`), so a SQL delete must remove the
  goods first.
- **Ready notices.** The poller selects only `OPEN` / `IN_PROGRESS` orders that are not stored and
  not yet notified (`B/refinery/internal/RefineryOrderRepository.java:380-390`). An ended order is
  never announced afterwards. Notices already delivered are removed only through `readyCleared`
  (`B/refinery/api/events/RefineryNotices.java:96-104`;
  `B/notification/internal/NotificationCreationService.java:228-242`).
- **Live sync.** The refinery module publishes no backend live sync; the browser publishes
  (`FE/refinery/refinery-orders-details.js:48`). The Exchange path publishes the `refinery` and
  `refinery-order:{id}` rooms (`B/exchange/internal/ExchangeLiveSync.java:113-128`).
- **Metrics.** `basetool.refinery.order.open.count` (by status) and `...open.oldest.age` are DB
  gauges (`B/refinery/internal/RefineryBusinessGauges.java:62-69`), so they see any write. The
  alert `RefineryOrderStale` fires on the oldest OPEN order
  (`monitoring/prometheus/alerts/business.yml:612-619`).

### Mission finances

- `RefineryMissionFinance.contributionTo` sums `oreSales − expenses − otherExpenses` over
  `findByMissionId` (`B/refinery/internal/RefineryMissionFinance.java:45-63`). That query has **no
  status filter** (`RefineryOrderRepository.java:170-171`). The same holds for the operation
  finance reads (`RefineryOrderRepository.java:208-228`; `B/refinery/internal/RefineryOrderFinanceReads.java:43-67`).
- So a `CANCELED` order counts exactly like an open or completed one. Only a hard delete, or
  unlinking the mission, removes it from the figures (`docs/specs/external-exchange.md:3592-3593`).
- No refinery write touches a Mission section counter.
- On mission deletion, `MissionRefineryOrderRelease` sets `missionId = null` per entity, with no
  audit (`B/refinery/internal/MissionRefineryOrderRelease.java:42-50`).

### Feed (V367)

- `trg_refinery_order_exchange_change` and `trg_refinery_good_exchange_change` are AFTER ... FOR
  EACH ROW, recorded once per order and transaction (`MIG/V367:32-67`, `:74-94`). A bulk UPDATE
  reaches them.
- A cancel arrives as an item with `status: CANCELED`, not as a tombstone
  (`docs/exchange/resources/refinery-orders.md:50-57`). The snapshot lists only `OPEN` and
  `IN_PROGRESS` orders.
- `REMOVED_ELSEWHERE` is not implemented for refinery orders, only for blueprints, ships and stock.
- `exchange_refinery_order_link.refinery_order_id` has no FK by design, so a tombstone keeps the
  client key (`MIG/V370:1-13`).
- The Exchange compares the section counters, never the row `@Version`
  (`B/exchange/internal/ExchangeRefineryOrderRun.java:808-817`).

### Existing bulk refinery paths

None of these changes status or deletes in bulk:

- `unlinkMissions` (no caller);
- `updateOwner` (account erasure; it moves no counter);
- `markReadyNotified` (`RefineryOrderRepository.java:355-404`). It is `@Modifying` without
  `clearAutomatically`.

## (e) Exchange feeds and client-side effects

- **The feed is a key log.** Triggers write `exchange_change` rows with user, resource, key,
  source and time; no operation is stored (`MIG/V252:1-13`). The reader serves each key's current
  state: an item if rows exist, otherwise a tombstone
  (`B/exchange/internal/ExchangeStockFeedService.java:130-151`).
- **Stock trigger** (`MIG/V304:24-54`):
  - DELETE records the old lot key;
  - an UPDATE of user, material, item, location, quality or stolen records the old and the new key;
  - INSERT records the new key;
  - shared rows also record `ORG_STOCK` under the unit lot key (`MIG/V296:47-53`).
  - The allocation tables have their own triggers (`MIG/V296:120-140`). They look up the parent
    row (`:55-64`).
- **A relocated lot yields two keys.** The old key becomes a tombstone if no rows are left; the
  new key becomes an item whose quantity includes stock already at the target, since a lot is all
  of a member's rows there (`ExchangeStockFeedService.java:55-58`). A test pins this ordering
  (`backend/src/test/java/de/greluc/krt/profit/basetool/backend/exchange/internal/ExchangeChangeFeedTriggerIntegrationTest.java:287-300`).
  The client docs describe the key only as opaque (`docs/exchange/resources/stock.md:15`). They do
  not say that a move to another location means a tombstone plus a new key.
- **`removedBy` and `change_source`.**
  - `ChangeSourceTransactionManager` sets `basetool.change_source` at the start of every
    non-read-only transaction (`B/exchange/internal/ChangeSourceTransactionManager.java:53-70`).
  - `ChangeSource.current()` gives `client|<id>|<key>` for a relayed client and `web` for an azp in
    `app.exchange.change-source.web-client-ids` (default `basetool-frontend`,
    `B/exchange/internal/ChangeSourceProperties.java:39-40`); otherwise `system`
    (`ChangeSource.java:75-100`).
  - There is no admin channel: an admin action in a web request is `web`
    (`docs/adr/0224-the-change-feed-is-a-trigger-written-key-log.md:61-62`).
  - `@Async` work does not carry the SecurityContext
    (`B/platform/api/AsyncConfig.java:242-264`), so it is `system` unless it runs as someone. The
    bulk undo runs as the admin (`B/exchange/internal/ExchangeBulkUndoRunner.java:68-76`).
  - A tombstone's `removedBy` and `removedAt` come from the key's latest change
    (`B/exchange/internal/ExchangeFeedReader.java:244-273`, `:323-326`).
- **`REMOVED_ELSEWHERE`** is a per-op reason, not a problem code
  (`docs/exchange/errors.md:77-86`).
  - For stock it applies when the current quantity is 0, no `override` is sent, and the latest
    change of the lot is not this client's own (`B/exchange/internal/ExchangeStockWriteService.java:413-416`,
    `:562-573`). It lapses with the 90-day purge (`:566-568`).
  - The order of checks matters: `VERSION_CONFLICT` comes first (`:407-408`). A stale client that
    expects its old quantity on the old lot gets `VERSION_CONFLICT`; one that expects 0 and adds
    stock gets `REMOVED_ELSEWHERE`.
  - Clients are told to remove such entries locally, not to add them back, and to send `override`
    only with the member's consent (`docs/exchange/sync-guide.md:71-80`).
- **Horizon and burst size.**
  - Retention is P90D (`B/exchange/internal/ExchangeChangeRetentionProperties.java:35-37`). A cursor
    behind the horizon gets `410 CURSOR_EXPIRED` (`ExchangeFeedReader.java:144-150`), and the
    client must re-snapshot and reconcile (`docs/exchange/sync-guide.md:82-97`).
  - A page holds up to 1000 keys, 500 by default (`ExchangeFeedReader.java:48-69`). Nothing caps or
    throttles a burst of server-side tombstones.
  - Readers see only transactions below `pg_snapshot_xmin`
    (`B/exchange/internal/ExchangeChangeRepository.java:40-43`, `:98`). One long writing
    transaction holds back every member's feed until it ends (ADR-0224 `:31-36`).
- **Mass-change guard.**
  - The limits are code constants: a 24 h window, 25 removals at most, and from 5 removals a share
    rule of `total × 5 > currentCount + inWindow`
    (`B/exchange/internal/ExchangeMassChangeGuard.java:43-49`, `:102-108`).
  - It runs only on client change sets (`B/exchange/internal/ExchangeChangeSetRunner.java:64-74`).
    Server-side changes never enter its window.
  - A server-side wipe does lower `currentCount` for the client's later batches
    (`ExchangeStockWriteService.java:194`, `:855-857`).
- **Undo.**
  - `exchange_journal` holds client writes only. `ExchangeJournalService.record` is called by the
    Exchange writers alone (`B/exchange/internal/ExchangeJournalService.java:64-90`). A server-side
    bulk change cannot be undone through it.
  - A server change on a key makes the client's own undo skip as `CHANGED_AFTERWARDS`
    (`B/exchange/internal/ExchangeEntryRestorer.java:56-74`). The journal is keyed to the old,
    location-bearing lot key.
  - For refinery orders the check is the section counters
    (`B/exchange/internal/ExchangeRefineryOrderJournal.java:113-118`). A server path that does not
    advance them is not detected.
- **Contract docs.** `docs/exchange/` says nothing about patch changes, wipes or bulk relocations.
  The `system` channel is described as "default-grant changes, administrative clean-ups and account
  deletion" (`docs/exchange/resources/blueprints.md:54-56`).

## (f) Live sync, notifications and metrics

- **Who publishes.** ADR-0274 Amendment 1: writes that "arrive over the exchange API ... or from an
  admin bulk change" must be published by the backend
  (`docs/adr/0274-the-backends-app-live-sync-bridge-ends-and-the-pwa-keeps-ws-sync.md:24-30`).
  `LiveSyncPublisher.publishAfterCommit` coalesces frames per transaction
  (`B/livesync/api/LiveSyncPublisher.java:77-107`).
- **Rooms the backend can publish into** (`B/livesync/api/LiveSyncTopicClass.java:43-110`):
  - `inventory` (`stock`, `stock-levels`), one global room with no per-unit variant;
  - `order:{id}`;
  - `supply-needs` and `supply-donations:{unit}`;
  - `hangar:{member}`, `hangar-unit:{unit}` and `hangar-units`;
  - `materialboard`;
  - `refinery` and `refinery-order:{id}`.
  - Mission rooms exist only in the frontend registry. Adding one to the backend falls under the
    parity test (`LiveSyncTopicRegistryParityTest`).
- **What today's paths publish:**
  - The global wipe publishes nothing from the backend. The browser runs `inventoryAll.notify('stock')`
    and the board broadcast (`FE/inventory/inventory-admin.js:736-744`), with no `order:{id}`
    frames (`docs/specs/frontend-ajax-mutations.md:1333-1336`).
  - Bulk rebook publishes from the browser (`FE/inventory/inventory-my.js:552-558`).
  - The ship paths publish only unit rooms (`HangarUnitLiveSync`).
  - The backend's own inventory publishers are `ExchangeLiveSync.stockChanged` and
    `InventoryItemReassignment` (`B/exchange/internal/ExchangeLiveSync.java:92-102`;
    `InventoryItemReassignment.java:87-90`).
- **Notifications a bulk path would trigger or skip:**
  - offer `STOCK_GONE`, only via the observer;
  - transfer notices, only on an owner change;
  - the mission-unit lost-ship notice, only via `ShipDeletionObserver`;
  - refinery `readyCleared`, only when the path publishes it;
  - low-stock notices, timed and delayed (see (a)).
  - The wipe sends no notice to the stock owner.
- **Metrics:**
  - DB-sampled gauges see any bulk change:
    - `basetool.inventory.minimum.levels.below` (`B/inventory/internal/InventoryBusinessGauges.java:30-53`);
    - active offers (`B/materialexchange/internal/MaterialExchangeBusinessGauges.java:56-62`);
    - refinery gauges;
    - pledged donations, which a wipe leaves unchanged.
  - Write-time counters move only on their paths:
    - the audit counter per event (`B/audit/api/AuditService.java:95-99`);
    - `basetool.exchange.removals`, client writes only (`ExchangeJournalService.java:73-75`);
    - live-sync publishes;
    - timed notices.
  - The `hangar` and `mission` packages have no metrics.

## (g) Guarantees a server-side bulk path skips today

1. **No `@Version` bump on the ship bulk update.** `ShipRepository#setLocationForOwner` leaves
   `version` and `updated_at` alone (`ShipRepository.java:252-261`). The Exchange version checks
   (`ExchangeShipWriteService.java:354-356`, `:481`) and the web edit check
   (`HangarService.java:340`) then accept a stale version.
2. **The ship bulk update is silent on the member's side.** It publishes no `HANGAR_OWN` frame,
   writes no per-ship audit and sends no notice (`HangarService.java:847-855`).
3. **The bulk ship delete bypasses the observer.** `ShipRepository#deleteByOwnerId`
   (`ShipRepository.java:273-275`) skips `ShipDeletionObserver`. There is no mission-unit notice,
   no `MISSION_UNIT_UPDATED` and no `MissionUnit` version bump; only the FK's SET NULL runs.
4. **The global stock wipe skips most guards.** `deleteAllGlobalInventory`
   (`InventoryCheckoutService.java:839-866`) takes:
   - no advisory lot lock, which ADR-0229 requires of Exchange writes;
   - no row lock and no version check;
   - no `requireNoDonation`, so pledges dangle and the donation stays PLEDGED.
   - It also sends no owner notice and publishes no backend live sync.
   - It needs a request scope (`:841`).
5. **`beforeWipe` covers only ACTIVE offers on non-personal rows**
   (`MaterialExchangeOfferRepository.java:260-275`). Other offers cascade with no audit and no
   notice. Their read and the delete run unlocked.
6. **Bulk checkout has no donation guard** (`InventoryCheckoutService.java:868-911`). Single-row
   deletes do have it (`LotRows.java:131-142`).
7. **Bulk statements leave the persistence context stale.** These `@Modifying` methods have no
   `clearAutomatically`:
   - `deleteAllNonPersonal` (`InventoryItemRepository.java:994`);
   - the refinery `updateOwner` and `markReadyNotified` (`RefineryOrderRepository.java:367-404`).
8. **A refinery bulk update would skip two things.** It would not advance `gameDataVersion`, so
   stale web or client echoes and Exchange undo pass. It would not publish `readyCleared`, so
   delivered ready notices stay (inference from `RefineryOrderSection.java:86-98` and
   `ExchangeRefineryOrderJournal.java:113-118`).
9. **Mission finances keep counting cancelled orders** (`RefineryMissionFinance.java:45-63`). Ending
   orders by status does not remove them from mission figures.
10. **Bulk paths are attributed `system`.** Anything outside a web request is `system`, including
    `@Async` work (`ChangeSource.java:89-99`; `AsyncConfig.java:242-264`). Tombstones then show
    `removedBy.channel: "system"`.
11. **Server-side bulk changes are outside the mass-change guard and the undo journal.** They cannot
    be undone through the Exchange undo, and they make client undos skip as `CHANGED_AFTERWARDS`
    (`ExchangeEntryRestorer.java:56-74`).
12. **One huge transaction stalls every member's feed** until it commits (ADR-0224 `:31-36`).
13. **Precedent: the account-merge native update bumps no version** (`OwnedRowsMover.java:108-117`).
    The Javadocs of both erasure deletes require them to stay bulk
    (`InventoryItemRepository.java:1005-1006`; `ShipRepository.java:267-268`).
14. **Bulk rebook `LOCATION` cannot move all of a member's holdings.** It is owner-scoped, stamps
    one org unit on every row, and aborts on any donation pledge
    (`InventoryCheckoutService.java:980-1000`, `:1039-1041`, `:1256`).

## (h) Under PR #3214

The branch `origin/codex/spec-auec-joborders` is an open draft and not merged. Every reference in
this section is on that branch.

- **Stock-write prerequisite.** `OrderLinkedStockWriteObserver.beforeOrderLinkedStockWrite(orderIds)`
  "stabilizes and validates all affected order responsibilities"
  (`PR3214:B/inventory/api/OrderLinkedStockWriteObserver.java:31-36`). `InventoryOrderWrites`
  offers four methods (`PR3214:B/inventory/internal/InventoryOrderWrites.java:42-89`):
  - `lockEligibility()`, which passes an empty list;
  - `assertRow`;
  - `assertRows`;
  - `assertWipe`.
- **The wipe on the branch** (`PR3214:B/inventory/internal/InventoryCheckoutService.java:854-869`)
  runs in this order:
  1. `lockEligibility()`.
  2. Read the scope.
  3. `assertWipe(...)` over the job orders allocated to non-personal rows in scope
     (`PR3214:B/inventory/internal/InventoryOrderAllocationQueries.java:55-63`).
  4. `beforeWipe`.
  5. `deleteAllNonPersonal`.
  - No order claim or capacity reaction runs on the wipe in branch code.
- **The observer in `joborder`** (`PR3214:B/joborder/internal/JobOrderStockWriteEligibility.java:48-63`):
  1. Takes `profitHierarchy.lockForOrderWrite()`, a transaction-scoped
     `pg_advisory_xact_lock_shared` (`PR3214:B/orgunit/api/ProfitHierarchyService.java:63-74`, `:127-134`).
  2. Throws `NotFoundException` (404) for an unknown order id.
  3. Throws `BadRequestException("orders.responsibility.invalid")` if any affected order is
     invalid.
  - **Invalid** means one of these:
    - no responsible unit;
    - a unit outside the eligible Profit set;
    - a changed original Profit root (`PR3214:B/joborder/internal/JobOrderResponsibilityGuard.java:54-60`).
  - The check ignores order status (`PR3214:docs/specs/orders-core.md:191-192`).
  - The exception maps to **HTTP 400, code `BAD_REQUEST`**
    (`PR3214:B/kernel/AppExceptionKind.java:36-43`;
    `PR3214:B/platform/api/ApplicationProblemHandlers.java:91-98`). There is no dedicated problem
    code.
- **One invalid order refuses the whole bulk write.** The check is all-or-nothing and runs before
  the delete inside one transaction, so nothing is deleted. A one-row bulk checkout test shows
  the 400 (`PR3214:backend/src/test/java/de/greluc/krt/profit/basetool/backend/inventory/web/InventoryJobOrderScopeIntegrationTest.java:276-292`).
  No wipe-specific test exists.
- **REQ-ORDERS-055.** "All Inventory write transactions acquire the shared hierarchy lock before
  lot/row locks" (`PR3214:docs/specs/orders-core.md:180-182`). The spec makes no exception for a
  wipe, a relocation or an admin bulk path. The lock is held until the bulk transaction commits.
  Some writes take only the lock without an assert:
  - `InventoryItemReassignment`;
  - `InventoryOrgUnitReconciler`;
  - most of `InventoryPoolEntryCommands`;
  - `StockLevelService`.
  - Whether they assert through delegated calls was not traced.
- **Material promises** (`PR3214:docs/specs/orders-material-claims.md`, ADR-0360; both planned, not
  implemented, `:1`, `:129`):
  - **Relocation.** It "cannot be credited again through relocation, release/relinking, row
    merge/removal" (`:42-43`). The acceptance row for relocation reads "No repeat credit"
    (`:110`).
  - **True loss.** "Correctly credited past provision remains a contribution when stock is
    genuinely consumed, released or lost" (`:66-68`). A true stock loss is "not a correction"
    (`:74-75`). The acceptance row reads "Genuine provision 3 later lost ... Gap 3, no revived
    obligation" (`:115`). ADR-0360 `:30` agrees.
  - **Capacity guard (REQ-ORDERS-024).** It sits at order-plus-material scope, and "every relevant
    demand, stock-allocation, promise, attribution and correction mutation participates"
    (`:84-89`).
  - **No concrete lock is named** (`:90-91`). Branch code still takes the whole-order
    `lockForClaimUpsert` (`PR3214:B/joborder/internal/MaterialClaimService.java:343`).
- **aUEC holdings** (`PR3214:docs/specs/orders-auec.md`, `orders-auec-bank.md`, ADR-0357, ADR-0359):
  - **Storage.** They are a conceptual model with no table or entity on the branch. A booked
    holding is a manually reported, purpose-bound amount per player and order (`orders-auec.md:31-32`).
    It is neither the in-game wallet nor a Bank balance (`:39`), and it has no automatic expiry
    (`:58-61`).
  - **Wipes.** None of these documents mentions a currency wipe, `WIPE_RESET` or
    `resetAllBalances`. REQ-BANK-013 and `resetAllBalances` are unchanged on the branch
    (`PR3214:docs/specs/bank.md:472-483`; `PR3214:B/bank/internal/BankLedgerService.java:597-599`).
  - **Nearest rules.**
    - A real loss reduces holdings and keeps the past provision (`orders-auec.md:195-196`, `:209`).
    - Pending Bank-coupled effects keep the money locally unavailable until final confirmation
      (`orders-auec-bank.md:141-150`).
    - Only the valid active attempt can become effective (REQ-ORDERS-065, `orders-auec.md:286-290`).
  - **Not answered:** what a wipe or `WIPE_RESET` does to pending operations or Bank attempts.
- **Ship, refinery and Exchange paths.**
  - The PR changes no main code under `hangar` or `refinery`. Under `exchange` it changes only a
    Javadoc line in `ExchangeOrgDemandDto`; the rest are tests. The only migration is `V376`.
  - Two paths are affected indirectly through `StockCommands`, which now locks eligibility and
    asserts orders (`PR3214:B/inventory/internal/InventoryStockCommands.java:96-255`, `:649-667`):
    - the refinery store (`bookInFromRefinery`);
    - Exchange stock writes.

## Where the code does not answer

- How to tell a pledged ship from one bought in-game. No source or acquisition field exists.
- Whether the allocation-table triggers (`MIG/V296:120-140`) still find the parent row during an
  `ON DELETE CASCADE`, and so record an `ORG_STOCK` entry. The row trigger on `inventory_item`
  records the org lot on DELETE itself (`MIG/V304:36-47`).
- Whether Hibernate auto-flushes pending changes before `setLocationForOwner`, which has no
  `flushAutomatically`.
- Whether a cancelled refinery order is meant to keep counting in mission finances.
- How a frontend ship form reacts to a version conflict, beyond forwarding the version.
- **Stale ADR text.** ADR-0224 `:28-30` still says stock is recorded only for personal rows.
  `MIG/V304` superseded that.
