# SC Wiki and UEX catalogue sync audit

Doc type: research snapshot; findings and implementation candidates, not a binding specification.

- Snapshot date: 2026-11-06, approximately 15:55-16:03 UTC.
- Local baseline: `507e14659` in `D:/Coding/krt/basetool`.
- SC Wiki source inspected: `StarCitizenWiki/API`, `develop` commit `fab953d20b6d6c485c2430066e0a03a7db6fbbb6`.
- Method: read-only local source inspection, read-only public HTTP GETs, upstream source and first-party developer documentation. No production-host access or application changes.
- Confidence labels: **verified** means observed in source or an actual response; **inference** describes an expected consequence that was not demonstrated against a running Basetool instance.

## Public API compatibility snapshot

The configured unversioned `/api/...` routes remain current. The API owner's [developer quickstart](https://api.star-citizen.wiki/developers) uses these routes, pagination, relationship includes and a game-version query parameter. The upstream [route definitions](https://github.com/StarCitizenWiki/API/blob/fab953d20b6d6c485c2430066e0a03a7db6fbbb6/routes/api.php) retain all endpoints configured in [ScWikiProperties.java](D:/Coding/krt/basetool/backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/internal/ScWikiProperties.java:87). No migration to a new URL prefix is needed on the evidence collected.

All following public probes returned HTTP 200. Counts are response metadata snapshots, not a claim that every row was enumerated or decoded by Jackson.

| Endpoint and relevant filter | `meta.total` | Observed result |
| --- | ---: | --- |
| [/api/items](https://api.star-citizen.wiki/api/items?page%5Bsize%5D=1) | 12,617 | Default player-relevant pool |
| [/api/items?filter[include_irrelevant]=true](https://api.star-citizen.wiki/api/items?page%5Bsize%5D=1&filter%5Binclude_irrelevant%5D=true) | 21,239 | Explicitly includes internal/test/placeholder items |
| [/api/vehicles](https://api.star-citizen.wiki/api/vehicles?page%5Bsize%5D=1) | 310 | Current vehicle list |
| [/api/blueprints](https://api.star-citizen.wiki/api/blueprints?page%5Bsize%5D=1) | 1,617 | Flat ingredients and dismantle returns present |
| [/api/commodities](https://api.star-citizen.wiki/api/commodities?page%5Bsize%5D=1) | 206 | Expanded mining and cargo metadata |
| [/api/manufacturers](https://api.star-citizen.wiki/api/manufacturers?page%5Bsize%5D=1) | 155 | `uuid`, `name`, `code`, `link` |
| [/api/vehicle-items](https://api.star-citizen.wiki/api/vehicle-items?page%5Bsize%5D=1) | 3,418 | Includes paints as well as components |
| [/api/vehicle-weapons](https://api.star-citizen.wiki/api/vehicle-weapons?page%5Bsize%5D=1) | 174 | Ship guns |
| [/api/weapons](https://api.star-citizen.wiki/api/weapons?page%5Bsize%5D=1) | 426 | Personal weapons |
| [/api/weapon-attachments](https://api.star-citizen.wiki/api/weapon-attachments?page%5Bsize%5D=1) | 106 | FPS attachments |
| [/api/armor?filter[classification]=FPS.Armor](https://api.star-citizen.wiki/api/armor?page%5Bsize%5D=1&filter%5Bclassification%5D=FPS.Armor) | 2,459 | Same count was observed without the filter |
| [/api/clothes?filter[classification]=FPS.Clothing](https://api.star-citizen.wiki/api/clothes?page%5Bsize%5D=1&filter%5Bclassification%5D=FPS.Clothing) | 1,868 | Clothing |
| [/api/food?filter[classification]=FPS.Consumable.Food](https://api.star-citizen.wiki/api/food?page%5Bsize%5D=1&filter%5Bclassification%5D=FPS.Consumable.Food) | 221 | Includes drinks |

The [default-version endpoint](https://api.star-citizen.wiki/api/game-versions/default) returned `4.10.2-LIVE.12881860`. Its response was served from an upstream cache; this records what the API returned rather than independently determining the current game release.

A 200-item first page was approximately 1.23 MB; a 50-vehicle first page approximately 3.19 MB. Both are below the client's 16 MiB body cap. This does not establish an upper bound for later pages. The upstream [vehicle controller](https://github.com/StarCitizenWiki/API/blob/fab953d20b6d6c485c2430066e0a03a7db6fbbb6/app/Http/Controllers/Api/Game/VehicleController.php#L255) caps vehicle pagination at 50; the existing default of 50 is appropriate. Sampled pages had locale maps under `description`, and current bound field names were still present. Real Java/Jackson deserialization was not executed during this audit.

## Required correction: vehicle inventory units

**Verified defect.** SC Wiki's `vehicle_inventory` is micro-SCU, while Basetool stores its value unchanged in `ship_type.vehicle_inventory_scu`.

- Upstream [VehicleResource.php:97](https://github.com/StarCitizenWiki/API/blob/fab953d20b6d6c485c2430066e0a03a7db6fbbb6/app/Http/Resources/Game/Vehicle/VehicleResource.php#L97) documents micro-SCU, and [line 887](https://github.com/StarCitizenWiki/API/blob/fab953d20b6d6c485c2430066e0a03a7db6fbbb6/app/Http/Resources/Game/Vehicle/VehicleResource.php#L887) multiplies the internal SCU quantity by one million.
- Live [Avenger Stalker](https://api.star-citizen.wiki/api/vehicles/avenger-stalker) returned `vehicle_inventory=710000` and `inventory_containers[0].scu=0.71`.
- Live [Carrack](https://api.star-citizen.wiki/api/vehicles/carrack) returned `vehicle_inventory=11260000`; its returned container SCU values sum to 11.26.
- [ScWikiVehicleDto.java:38](D:/Coding/krt/basetool/backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/internal/scwiki/ScWikiVehicleDto.java:38) incorrectly describes this field as SCU.
- [ScWikiVehicleSyncService.java:168](D:/Coding/krt/basetool/backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/internal/ScWikiVehicleSyncService.java:168) copies the number unchanged when the local field is null; [ShipType.java:151](D:/Coding/krt/basetool/backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/api/ShipType.java:151) explicitly declares the column in SCU.

**Consequence, inferred from the mapping:** a Wiki-populated inventory value is one million times too large. Convert units at the inbound boundary and correct the documentation. Existing stored rows need a targeted repair with provenance checks: changing only the mapper will not repair them because the current writer fills this column only when it is null. Do not divide every local value without first establishing its source.

## Required correction: game-version consistency

**Verified code defect.** [ScWikiClient.java:619](D:/Coding/krt/basetool/backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/internal/ScWikiClient.java:619) adds the configured `version` to paginated requests, but [fetchOne at line 496](D:/Coding/krt/basetool/backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/internal/ScWikiClient.java:496) constructs only `endpoint/{id}`. The game-version pin is therefore omitted from item details, blueprint details and other single-resource requests.

The upstream [developer documentation](https://api.star-citizen.wiki/developers) recommends pinning game data. [BlueprintController.php](https://github.com/StarCitizenWiki/API/blob/fab953d20b6d6c485c2430066e0a03a7db6fbbb6/app/Http/Controllers/Api/Game/BlueprintController.php) and [ItemController.php](https://github.com/StarCitizenWiki/API/blob/fab953d20b6d6c485c2430066e0a03a7db6fbbb6/app/Http/Controllers/Api/Game/ItemController.php) scope details to the requested version, otherwise the default.

**Consequence, inferred:** a configured historical-version list can be combined with current-default detail data, or a detail can be missing because it no longer exists in the default patch. Even with no configured pin, a default-version switch between list and details can produce mixed versions. Resolve one version per sync run and apply it to every request; persist and validate the returned version before applying data. No cross-version corruption was induced against Basetool during this investigation.

## Current filtering and tombstone meaning

**Verified current semantics, not an instruction to import development assets.** Current upstream [ItemController.php:378](https://github.com/StarCitizenWiki/API/blob/fab953d20b6d6c485c2430066e0a03a7db6fbbb6/app/Http/Controllers/Api/Game/ItemController.php#L378) excludes items not marked player-relevant by default. Setting `filter[include_irrelevant]=true` broadens the observed pool from 12,617 to 21,239. The equivalent default restriction exists in [VehicleController.php:676](https://github.com/StarCitizenWiki/API/blob/fab953d20b6d6c485c2430066e0a03a7db6fbbb6/app/Http/Controllers/Api/Game/VehicleController.php#L676).

[ScWikiItemSyncService.java:287](D:/Coding/krt/basetool/backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/internal/ScWikiItemSyncService.java:287) performs its residual item census without this filter. Its completeness checks can establish completeness of the **default relevant pool**, not completeness of all assets that still exist upstream. A relevance reclassification can therefore remove a row from the census without deleting the resource. Keep this distinction explicit in tombstone semantics and diagnostics. Whether all-assets mode is appropriate depends on the intended catalogue scope; enabling it indiscriminately would also admit test and placeholder data.

The historical full-pool behavior of bare `/api/armor` was not reproduced: both filtered and unfiltered probes returned 2,459 armor records. The existing classification filter remains compatible.

## Mappings that work, but depend on deprecated fields

### Item dimensions

**Verified semantic ambiguity.** [ScWikiDimensionDto.java:37](D:/Coding/krt/basetool/backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/internal/scwiki/ScWikiDimensionDto.java:37) binds only the top-level `width`, `height`, `length`; [ScWikiItemSyncService.java:692](D:/Coding/krt/basetool/backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/internal/ScWikiItemSyncService.java:692) copies them into the stored axes. The upstream [ItemDimensionResource.php](https://github.com/StarCitizenWiki/API/blob/fab953d20b6d6c485c2430066e0a03a7db6fbbb6/app/Http/Resources/Game/Item/ItemDimensionResource.php#L19) deprecates those axes and distinguishes physical `dimensions`, cargo-grid `cargo_dimension`, and inventory-display `ui_dimension`.

The live [A03 Akuma Sniper Rifle](https://api.star-citizen.wiki/api/items/4577c7a3-76ab-4e37-91de-68862dacb964) list payload showed top-level dimensions `(0.75, 0.75, 1.5)`, physical dimensions `(0.072, 0.238, 1.184)`, and cargo-grid dimensions `(0.06, 0.34, 1)`, all in width/height/length order. These represent different contracts. Decide what the local columns mean, bind the matching explicit block, and retain separately typed cargo occupancy/volume when needed. Do not silently replace one meaning with another.

### Tractor stats

The four flat tractor fields used by [ScWikiItemStatExtractor.java:86](D:/Coding/krt/basetool/backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/internal/ScWikiItemStatExtractor.java:86) still work in the live [Durus Tractor Beam](https://api.star-citizen.wiki/api/items/83b3857b-d7d5-40bd-b79c-058026f29ec6). The upstream [TractorBeamResource.php](https://github.com/StarCitizenWiki/API/blob/fab953d20b6d6c485c2430066e0a03a7db6fbbb6/app/Http/Resources/Game/ItemSpecification/TractorBeamResource.php#L124) marks the aliases deprecated. Prefer `force.max`, `range.max`, `range.full_strength_distance`, and `force.max_volume`; the last is documented in micro-SCU. This is forward-compatibility work, not a currently reproduced missing-stat failure.

The remaining sampled extractor paths were present and numeric: component health; shield maximum health; power and coolant segment generation; quantum standard-jump speed and fuel requirement; radar aim-assist distances; mining laser maximum power; personal/vehicle damage; personal weapon RPM; salvage speed/radius/efficiency; armor physical mitigation, temperature and radiation dissipation. Representative public sources: [Shield](https://api.star-citizen.wiki/api/items/51bf9a6e-0ad7-49d2-8d41-d1d5e69669e6), [Agni quantum drive](https://api.star-citizen.wiki/api/items/069689b2-0abf-47e6-a584-e20819764711), [Arbor mining laser](https://api.star-citizen.wiki/api/items/5b5d11f8-a2b0-4e6e-80c3-2437cc0accae), [Abrade salvage module](https://api.star-citizen.wiki/api/items/98e0e5ac-3a76-4f3f-be2f-7fabf65a8826), [A23 armor](https://api.star-citizen.wiki/api/items/9a26bc89-a35b-4317-adfd-ec4a285d7e72). This was a representative sample, not exhaustive validation of every item or modifier.

## Additional usable Wiki data

### Existing ship columns can be filled more completely

[ScWikiVehicleDto.java:42](D:/Coding/krt/basetool/backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/internal/scwiki/ScWikiVehicleDto.java:42) currently binds seven fields. Current [VehicleResource.php](https://github.com/StarCitizenWiki/API/blob/fab953d20b6d6c485c2430066e0a03a7db6fbbb6/app/Http/Resources/Game/Vehicle/VehicleResource.php) and the live [Carrack](https://api.star-citizen.wiki/api/vehicles/carrack) provide ore capacity, medical tier, hull/shield health, cargo-grid limits, inventories, weapon/suit storage, equipment ports, seating and many additional capabilities. Basetool already has `ore_capacity`, `max_medical_tier`, `health`, and `shield_hp` fields in [ShipType.java:155](D:/Coding/krt/basetool/backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/api/ShipType.java:155). These are useful narrow additions after defining source precedence and units. `max_medical_tier` is an upstream string such as `T2`, whereas the local field is an integer; parse it deliberately.

### Mining, cargo and taxonomy

[ScWikiCommodityDto.java:41](D:/Coding/krt/basetool/backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/internal/scwiki/ScWikiCommodityDto.java:41) currently retains eight commodity fields. The public [commodity response](https://api.star-citizen.wiki/api/commodities?page%5Bsize%5D=200) also supplies typed instability/resistance/volatility, volatility decay, supported box sizes, acquisition methods, systems/locations, and separate ship/ground/FPS/harvestable flags. `commodity_groups` adds taxonomy even where `kind` is null: live [Agricium](https://api.star-citizen.wiki/api/commodities/agricium) returned `commodity_groups=[Metal]` and null `kind`. Useful candidates are mining preparation, cargo suitability and catalogue filtering. Do not infer that every field is populated on every commodity.

### Item identity, compatibility and variants

Current [ItemResource.php](https://github.com/StarCitizenWiki/API/blob/fab953d20b6d6c485c2430066e0a03a7db6fbbb6/app/Http/Resources/Game/Item/ItemResource.php) exposes lootability, blueprint links, images, tags and required tags, equipment ports, and optional related items, variants and installed vehicles. Those relationships offer stronger compatibility and variant evidence than display-name normalization. `include=related_items,variants,vehicles` must be requested intentionally; empty default arrays do not prove no relationship exists. Physical dimensions and stored volume are also more useful than deriving volume from UI bounding boxes.

### Blueprint resource identities and unlocks

The list/detail distinction is still compatible with the current two-step sync. The live [Omnisky III blueprint detail](https://api.star-citizen.wiki/api/blueprints/280f47b7-8434-410c-b854-380768fdccec) adds requirement groups, summary properties, dismantle timing/efficiency, unlocking missions, aspects and raw tiers. Its list row already contains the flat ingredients and dismantle returns. Thus no present list-field omission was found that would by itself invalidate the fallback in [ScWikiBlueprintSyncService.java:156](D:/Coding/krt/basetool/backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/internal/ScWikiBlueprintSyncService.java:156).

A resource child additionally carries `ore_uuid`: the observed Agricium child has resource UUID `dc6fbcbb-5990-4ed5-82ee-93152dab7845` and ore UUID `fc1ec740-3047-48d8-81f0-396f4c9a90ef`. [ScWikiBlueprintRequirementChildDto.java:47](D:/Coding/krt/basetool/backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/internal/scwiki/ScWikiBlueprintRequirementChildDto.java:47) drops the latter. Preserve the distinction as typed source evidence; do not substitute ore identity for the recipe's resource identity. Unlocking-mission relationships could explain where users obtain recipes, while raw tiers may become relevant if multi-tier recipes need explicit support. These are product candidates, not established requirements.

### Wiki's UEX enrichment is derived evidence

Live item and vehicle records now include `uex_prices` with UEX terminal IDs/codes, Wiki starmap-location UUIDs, timestamps, game version and UEX links. [ItemResource.php:607](https://github.com/StarCitizenWiki/API/blob/fab953d20b6d6c485c2430066e0a03a7db6fbbb6/app/Http/Resources/Game/Item/ItemResource.php#L607) expands this enrichment. It may help location reconciliation, but must not count as an independent confirmation of UEX prices or identities: it originates from UEX. Prefer the application's direct UEX price ingestion for freshness and retain provenance when using Wiki links.

## Limits and validation needed for implementation

- No production data was inspected, and no statement here establishes how many stored rows are affected.
- No local Gradle tests were run because this phase changes only a research note. An implementation should add meaningful contract/unit tests for units, one-version-per-run requests, explicit dimensions and collection completeness, using reviewed public fixtures rather than live network tests.
- Public API requests did not return `ETag` in the inspected responses. Existing ETag-dependent behavior therefore remains a source-code risk rather than a reproduced live-server behavior; response headers exposing `ETag` through CORS do not prove one is emitted.
- API compatibility is a dated snapshot. JSON-name-only contract checks cannot catch a million-fold unit error or distinguish physical and UI dimensions; fixtures need semantic assertions.
- Any implementation must update the owning requirements, monitoring and relevant companion documents together. This note does not override P4K/UEX/SC Wiki source-ownership rules.

## Live UEX evidence and cross-source identity

The following are public API observations made on 2026-11-06. They describe the public feeds, not the contents of the production database.

| Public request | Observed result | Implication |
|---|---|---|
| [UEX vehicles](https://api.uexcorp.uk/2.0/vehicles) | 283 rows; 113 without a usable UUID; two duplicated UUID groups | Neither UUID presence nor uniqueness may be assumed. |
| [UEX armor arms](https://api.uexcorp.uk/2.0/items?id_category=1) | 485 rows; 119 without a usable UUID; four duplicated UUID groups | The existing shared-UUID sibling rule needs to cover first-seen items. This sample does not measure all item categories. |
| [UEX commodities](https://api.uexcorp.uk/2.0/commodities) | 205 rows; all UUIDs absent; 199 carry `id_item` | The documented commodity UUID currently supplies no joins; an item bridge is only a candidate until its semantics/cardinality are verified. |
| [Wiki commodities](https://api.star-citizen.wiki/api/commodities?page%5Bsize%5D=200), followed by page 2 | 206 rows; 166 of the 205 UEX names have an exact Wiki name match; all 206 Wiki rows carry `commodity_groups`, only 72 carry `kind` | The remaining 39 UEX names are not evidence of missing local links: aliases and qualified names still need reconciliation. Prefer typed commodity groups to the sparse `kind` for enrichment. |

### P1: UEX vehicle UUID collisions can overwrite another vehicle's identity

[UexVehicleService] lines 143–148 resolves UUID before UEX id; lines 170–172 replaces the selected row's UEX id. Only new rows receive a name (162–165), while all selected rows receive the incoming vehicle attributes (180, 199 onward). A second variant with a copied UUID therefore selects the first variant, retaining its name but replacing its UEX id and attributes. If both local rows already exist, selecting the wrong UUID owner can instead violate UEX-id uniqueness.

These are actual conflicting upstream claims, verified against both APIs:

| Vehicle | UEX id | UUID claimed by UEX | UUID supplied by Wiki |
|---|---|---|---|
| Prowler | 149 | `087b2253-2fc7-4fae-9f9d-97f64b7c43db` | same |
| Prowler Utility | 262 | `087b2253-2fc7-4fae-9f9d-97f64b7c43db` | `528fdddc-c97f-4ae8-a00e-11a59fc3662d` |
| Guardian QI | 239 | `e98521b6-ea2f-4c10-9050-abc29d89d0e8` | same |
| Guardian MX | 257 | `e98521b6-ea2f-4c10-9050-abc29d89d0e8` | `aaa0cdcd-907c-4533-b3fc-181ede06076d` |

Wiki checks: [Prowler variants](https://api.star-citizen.wiki/api/vehicles?filter%5Bname%5D=Prowler&page%5Bsize%5D=10), [Guardian variants](https://api.star-citizen.wiki/api/vehicles?filter%5Bname%5D=Guardian&page%5Bsize%5D=10). Sample Wiki build: `4.10.2-LIVE.12881860`. No production impact was measured.

Validate UUID claims across the feed before matching. Preserve distinct UEX ids; refuse conflicting identity claims and use verified class-name aliases rather than silently choosing a variant. Extend REQ-DATA-026's conflict contract together with the fix. Existing `UexVehicleServiceUuidMatchTest` does not exercise these collision pairs.

### P1: a first-seen UEX item can steal another item's row

[UexItemSyncService] lines 404–415 resolves an unknown UEX id through its UUID even when the UUID owner already has another UEX id. Lines 340–343 then overwrite name, manufacturer and UEX id. The existing shared-UUID guard (315–328) helps only when the incoming sibling already has a separate local row.

The live armor sample contains `FBL-8a Arms Arctic Digital` (261), `Righteous` (6002), and `Justified` (6007) with UUID `091fc83c-aa96-44af-a850-45c381af311b`. Preserve each source record and classify the shared UUID as a conflict. Do not confuse this with a verified UEX-id reassignment under REQ-DATA-028. `UexItemSyncServiceTest` lines 215–258 only covers a preexisting sibling.

### P1: Wiki fallbacks can accept a conflicting strong identity

[ScWikiCommoditySyncService] builds a canonical index over initially unlinked materials (269–279), but never consumes it after a match. Later Wiki rows can reuse a material already linked earlier in the same run (240–244); alias and exact-name paths (221–236) also lack a different-UUID guard. Line 321 replaces `scwikiUuid` unconditionally. A triggering commodity pair was not established in this API sample; the unsafe path is established by code inspection.

[ScWikiVehicleSyncService] lines 111–134 also accepts name/alias matches with a different existing `externalUuid`, leaves that UUID unchanged and writes the incoming Wiki attributes. A subsequent sweep matches the stored UUID and can mark this just-updated row deleted if its old UUID was absent from the census ([ShipTypeRepository], 153–155). The manufacturer sync already rejects different non-null Wiki UUIDs ([ScWikiManufacturerSyncService], 115–127). Apply that invariant consistently, and remove a canonical candidate only after its link commits.

### P2: UUID-less item reconciliation depends on source arrival order

UEX has no UUID-less name/slug reconciliation in [UexItemSyncService] lines 404–418. Wiki consults its UEX name/slug index only if no Wiki-UUID row exists ([ScWikiItemSyncService], 557–572). Wiki-first followed by a UUID-less UEX record therefore leaves two rows even when the name is unique. Later Wiki runs continue taking the existing UUID row and never inspect the orphan UEX candidate.

Add a reconciliation pass over both existing sides. Require compatible kind/manufacturer and uniqueness in both directions; a unique UEX-side name alone does not disambiguate several Wiki variants. Report ambiguous candidates for curation. Any merge of existing rows must move all dependent references and versions transactionally, following the reference-preserving pattern of V310. ADR-0307 already rejects token-overlap matching for vehicles because it merges genuine variants.

## Destructive cleanup and operational robustness

### P1: partial UEX responses can trigger destructive cleanup

[UexClient] lines 417–428 deliberately returns nonempty `FetchResult.partial(rows)` for a non-`ok` envelope. Several callers discard the completeness flag:

- [UexVehicleService], 87–117: can mark all vehicles outside a partial feed as UEX-deleted.
- [UexItemPriceSyncService], 80–116, and [UexCommodityService], 111–163: can clear valid prices outside a partial matrix.
- [UexCommodityService], 85–105: passes a possibly partial commodity list into occurrence replacement. The replacement only touches the supplied commodities, so it does not wipe all omitted commodities' locations, but it violates the complete-fetch contract of [UexCommodityOccurrenceSync], 76–81.

Require a complete, identity-validated census before any deletion or replacement that interprets absence. A partial fetch may update known rows, but must not imply upstream deletion. Add nonempty-partial regression cases for each destructive caller.

### P1: local write failures are incorrectly interpreted as upstream absence

[UexItemSyncService] records `seenUexItemIds` only after successful writes (238–255). Its sweep checks failed fetches but not failed writes (159–173). If existing ids 100 and 200 both arrive, 100 fails locally and 200 succeeds, the census becomes `{200}` and 100 can be marked UEX-deleted despite still being present upstream.

`UexItemSyncServiceTest` lines 445–470 explicitly expects the failed id to be missing from the sweep's protection set. It models a new row, so it misses the harm to a previously stored row.

Both price writers use successful write ids as their absence census too ([UexItemPriceSyncService], 94–116; [UexCommodityService], 136–163). A known, still-listed price whose update fails can subsequently be cleared. `UexItemPriceSyncServiceTest` lines 234–273 exercises a new failing price, not an existing one.

Keep fetched source identities separate from applied results. Protect every valid fetched identity regardless of its local write result. If identities cannot be resolved or completeness is uncertain, skip cleanup. Track `fetched`, `valid`, `applied`, `skipped`, and `failed` separately. Regression tests need previously persisted rows and prices with a deliberately failing update.

### P2: three Wiki syncs hold transactions open across HTTP

[ScWikiCommoditySyncService] 98–112, [ScWikiVehicleSyncService] 69–86 and [ScWikiManufacturerSyncService] 68–82 hold a transaction across pagination and the catalogue write. Their per-row catches do not recover a transaction marked rollback-only by a repository/constraint error. They can ultimately roll back otherwise valid rows.

Reuse [SyncChunkWriter] 80–112: fetch without a transaction, then short isolated writes with row replay. Items and blueprints already have isolated writers. Verify one valid row, one real constraint failure, and a later valid row within an existing sanctioned Spring test context.

### P2: a recent success timestamp does not prove a complete applied catalogue

[SyncReportService] 70–71 only generates a run UUID; [ExternalSyncReportRepository] 83–101 derives summaries and `finishedAt` from event rows. A no-event run is absent and the last anomaly timestamp is not the actual finish. Some runs emit summaries, so the limitation applies specifically to paths with no persisted event.

[ScWikiScheduler] 107–120 catches step exceptions, while [TaskMetrics] 111–116 records umbrella success when the method returns. Existing alerts already mitigate this: `ExternalFetchErrors`, `ScWikiStepFailing`, `CatalogueSyncZeroItems`, `ExternalSyncStale`, and `ScWikiOrphanSweepStandingDown` in [business alerts], 60–129.

Persist each source/aggregate attempt with start/end, build/version when available, `SUCCESS/PARTIAL/FAILED/UNCHANGED`, completeness, counters, matching conflicts and sweep outcome. Expose last attempt separately from last complete apply. UEX vehicle `game_version` means announced/updated version, not a versioned snapshot token; do not compare it as though it were a Wiki build pin ([UEX vehicle documentation](https://uexcorp.space/api/documentation/id/get_vehicles/)).

### P3, latent: ETag skipping needs an applied-snapshot contract

[ScWikiClient] 284–287 skips the whole collection after page-1 304. Lines 570–574 cache a validator before database application succeeds; fetch incompleteness clears it, persistence failure does not. A page validator also need not cover later pages.

No sampled live response supplied an ETag, so this was not reproduced as a current outage. Before depending on this optimization, confirm collection-wide upstream semantics and acknowledge a validator only after successful apply, or retain a replayable payload independently.

## Improvement sequence and existing decisions

1. Correct the micro-SCU conversion and specify the unit contract. Repair only rows demonstrably populated from the wrongly interpreted Wiki field; do not divide every existing inventory value heuristically. Existing values are fill-only, so a normal rerun alone will not repair them.
2. Add complete-census gates to every destructive writer and separate fetched identities from applied writes. Repair the test that currently endorses deleting the failed item's protection.
3. Add duplicate UUID validation, strong-identity conflict rejection and order-independent reconciliation. Keep curated vehicle aliases and the manufacturer's many-company alias model.
4. Move the remaining Wiki writers to isolated transactions, centralize the fetch context (version and requested scope) and persist complete run outcomes.
5. Replace deprecated stat/dimension mappings and add selected enrichment fields after identity and unit handling are safe.

The long-term attribute precedence is already decided: [ADR-0337] is **Accepted (implementation pending)** and [P4K catalogue spec] REQ-P4K-017…019 says P4K leads item attributes, Wiki fills gaps, and UEX retains category/prices. Current code still fills P4K gaps ([P4kImportService], 369–379), overwrites attributes from Wiki ([ScWikiItemSyncService], 672 onward), and writes name/manufacturer from UEX ([UexItemSyncService], 340–342). This is known pending implementation, not evidence of a new API regression. Integrate the fixes with that work; do not introduce per-attribute admin override columns contrary to REQ-P4K-017.

Keep the protections already present: Wiki duplicate/missing-row pagination checks; residual census containment checks; empty-result sweep refusal; bounded stale-price SQL; per-row UEX isolation; manufacturer aliases and conflict rejection. A distributed lock is only needed if the backend runs multiple replicas; [SyncCoordinator] currently serializes UEX/Wiki within one process.

## Verification and acceptance cases for implementation

The statements in this snapshot describe the initial research phase. Implementation and its
regressions are governed by REQ-DATA-032…038 and ADR-0349; the final verification record is in
the implementing pull request. The historical source line references below are not a current
implementation map.

This task performed public read-only HTTP probes and source/test inspection. It did not run the application, Gradle tests, a production sync, database repair, or production inspection. Only this research note was added. No original historical payload baseline was available to date each mismatch; distinguish an observed current mismatch from proof of a recent upstream change.

Required regression cases:

- Current Wiki inventory payload `710000` becomes `0.71` SCU, including a controlled repair path for already stored values.
- Prowler/Utility and Guardian QI/MX keep distinct identities, with one existing row, two existing rows, and both feed orders.
- A first-seen item sharing another item's UUID never steals its row; existing id reassignment remains supported.
- Conflicting Wiki UUIDs via exact name, canonical name or alias never rebind a material/vehicle.
- Wiki-first and UEX-first UUID-less item arrival converge; incompatible variants/manufacturers/kinds remain distinct.
- Nonempty partial UEX feeds never tombstone vehicles or clear prices.
- A failed update of an existing item or price keeps its old deletion status/value; valid neighboring rows still commit.
- List and detail requests use the same game version and scope; changing the relevance filter is not interpreted as upstream deletion.
- Current nested tractor fields work without deprecated aliases; true/cargo/UI dimensions retain explicit semantics.
- Unchanged/no-event, partial and failed attempts appear in run history with accurate start/end and sweep status.

After implementing the corresponding fixes, use Gradle only. A focused starting set is:

```powershell
.\gradlew.bat :backend:test --tests '*UexItemSyncServiceTest' --tests '*UexItemPriceSyncServiceTest' --tests '*UexCommodityServiceTest' --tests '*UexVehicleServiceUuidMatchTest' --tests '*ScWikiClientTest' --tests '*ScWikiVehicleSyncServiceTest' --tests '*ScWikiManufacturerSyncServiceTest' --tests '*ScWikiCommoditySyncServiceTest' --tests '*ScWikiItemStatExtractorTest' --tests '*SyncChunkWriterTest' --tests '*SyncRunSummaryIntegrationTest'
```

These existing tests are a starting set, not proof of coverage for the missing cases. Add regression cases at the actual transaction/matching seam, then run required module lint and the full push gate when preparing a push.

## Local source references for the merge and robustness review

[UexVehicleService]: ../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/internal/UexVehicleService.java
[UexItemSyncService]: ../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/internal/UexItemSyncService.java
[ScWikiCommoditySyncService]: ../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/internal/ScWikiCommoditySyncService.java
[ScWikiVehicleSyncService]: ../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/internal/ScWikiVehicleSyncService.java
[ShipTypeRepository]: ../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/api/ShipTypeRepository.java
[ScWikiManufacturerSyncService]: ../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/internal/ScWikiManufacturerSyncService.java
[ScWikiItemSyncService]: ../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/internal/ScWikiItemSyncService.java
[UexClient]: ../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/internal/UexClient.java
[UexItemPriceSyncService]: ../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/internal/UexItemPriceSyncService.java
[UexCommodityService]: ../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/internal/UexCommodityService.java
[UexCommodityOccurrenceSync]: ../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/internal/UexCommodityOccurrenceSync.java
[SyncChunkWriter]: ../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/internal/SyncChunkWriter.java
[SyncReportService]: ../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/internal/SyncReportService.java
[ExternalSyncReportRepository]: ../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/internal/ExternalSyncReportRepository.java
[ScWikiScheduler]: ../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/internal/ScWikiScheduler.java
[TaskMetrics]: ../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/platform/api/TaskMetrics.java
[ScWikiClient]: ../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/internal/ScWikiClient.java
[P4kImportService]: ../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/internal/P4kImportService.java
[SyncCoordinator]: ../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/internal/SyncCoordinator.java
[business alerts]: ../../monitoring/prometheus/alerts/business.yml
[ADR-0337]: ../adr/0337-the-p4k-catalogue-leads-a-game-items-attributes-and-the-sc-wiki-fills-gaps.md
[P4K catalogue spec]: ../specs/p4k-catalogue.md
