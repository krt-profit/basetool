# Star Citizen Wiki + UEX-Items Sync Plan

Doc type: **historical plan** — the design intent, written 2026-05-27 *before* any of it existed.
Its own status line below still reads "plan draft awaiting user approval. Nothing is implemented
yet." Everything it describes has since shipped (R1-R6), and the implementation moved past the plan
in the places listed here.

**This is not the living description of the SC Wiki sync.** For what the code does today, read
`10 Systems/SC Wiki.md` in the Basetool Knowledge Base and the ADRs named below. Where this
document and the code disagree, **the code is right**. Do not edit it to track new changes —
record them in the knowledge base and, where they are decisions, in an ADR.

Restored to `docs/` on 2026-09-02. It had been deleted from the repository root on 2026-06-02 in
`b8b87c987` ("delte old docs", #358), together with its three companion documents — while **48
Javadoc citations in `backend/src` still pointed at it by section number** ("§8.7", "§6.3.5", "§3.4
quirk #1"). Restoring it makes those citations resolve again; this header is where the drift is
recorded, so it is stated once here rather than 48 times in the code.

> [!warning] Known deviations — where the shipped code no longer matches this plan
> Verified against `main` on 2026-09-02. In several of these the code's own Javadoc already says it
> deviates; the rest were found by comparing the two.
>
> - **§3.3 — the item payload does NOT carry `dimension{x,y,z}`.** This is a live-probe section and
>   the shape recorded there is wrong: the Wiki serves `{width, height, length, volume,
>   true_dimension, cargo_dimension, ui_dimension, …}`. `ScWikiDimensionDto` was built to this line,
>   so `game_item.dimension_x/y/z` **never held a value from R4 until 2026-08-28** — silently,
>   because an unbound component simply decodes to `null`. Corrected by **ADR-0148**, which also
>   added `ExternalCatalogueMappingTest` to pin every inbound mapping against a captured live
>   payload.
> - **§3.4 quirk 5 / §5.3 — "just paginate until `meta.current_page == meta.last_page`" is not
>   sufficient.** The census that decides whether a page walk may drive a tombstone sweep now counts
>   **distinct row identities**, tolerates a surplus over `meta.total` (`/api/items` serves 12 331
>   distinct rows against a declared 12 283), and treats a repeated row or a mid-walk growth in the
>   announced page count as a failure. See **ADR-0147**.
> - **§8.7 — the orphan sweep has TWO gates, not one.** This section describes only the empty-`seen`
>   gate. `ScWikiOrphanSweep` additionally requires the fetch to have been a **complete census**,
>   because a walk that died on page 2 of 6 still returns enough UUIDs to pass the emptiness gate
>   while every un-fetched row would be tombstoned. Added by **ADR-0147**. Thirteen call sites cite
>   this section; read it together with that ADR.
> - **§5.3 — the ETag cache is page-1-only, deliberately.** The plan says "pages are independently
>
>   > ETag-cached". `ScWikiClient` caches the page-1 request URI alone, because a mixed 200/304 walk
>   > produces a merged list with unknown gaps — which must never feed a tombstone sweep.
>
> - **§5 / §8.6 — `/api/vehicles` is walked at `page[size]=50`, not 200.** The plan's sizing note
>   ("the full items pool at `page[size]=200` is ~1.3 MB", "the 16 MB buffer is enough") does not
>   hold for vehicles: measured 2026-08-28, one page at 200 is **10.4 MB**, and crossing the codec
>   ceiling is a silent stop, not a partial read. `krt.scwiki.vehicles-page-size` exists for this.
> - **§8.1.1 — a canonical multi-match is SKIPPED, not turned into a `WIKI_ONLY` row.** The plan's
>   pseudocode creates one; `ScWikiCommoditySyncService.resolve` skips it and logs
>   `MULTI_MATCH_AMBIGUOUS`, and its Javadoc records why.
> - **§8.4 — Mode B also runs a residual `/api/items` catch-all pass.** The plan frames Mode B as
>   "iterate the 7 kind endpoints"; without the §6.3.1 catch-all the backfill would not reach the
>   full pool the §11 R5 target describes.
> - **§6.4 — the manufacturer chain's third step is `abbreviation == code`, not `industry+name`.**
>   The Wiki manufacturer payload exposes no industry, so the analogue that exists was used.
> - **§11 R3 — the scheduler was never run weekly.** It ships at the 24 h default
>   (`krt.scwiki.scheduler-delay = 86400000`), staggered 1 h behind the UEX scheduler.
> - **§11 R1-R6 — every per-sync feature flag is `true` in production.** The flags still default to
>   `false` in `ScWikiProperties`, and the Javadoc still says "ships dark", but
>   `application-prod.yml` (and `application-dev.yml`) enable all five plus `sync-all-items`.
>   Reading only the defaults gives the opposite of the truth.

Companion document to the existing UEX integration (see `backend/.../integration/UexClient.java` and `UexProperties.java`). The goal is twofold:

1. **Add the community-maintained Star Citizen Wiki API** at `https://api.star-citizen.wiki` as a second external-data source alongside UEX. Merge its overlapping commodity catalog, and extend the schema to cover entirely new aggregates (blueprints, game items, ship/vehicle components, weapons, armor, clothing, food, weapon attachments).
2. **Extend the existing UEX integration to also sync `/items` and improve `/vehicles`.** UEX's `/items` endpoint (armor, clothing, hand weapons, vehicle weapons, vehicle components, paints, attachments) shares the **same in-game UUID space** as the SC Wiki — both expose `uuid: "28c76343-8da9-..."` for the same Venture Helmet. The same is true for `/vehicles` (UEX `100i` `uuid: 6135a874-...` matches Wiki's `100i` `uuid: 6135a874-...`). This means UEX and Wiki items can live in **one** `game_item` row, keyed by the shared `external_uuid`, with UEX-specific columns and Wiki-specific columns filled in by their respective sync services. Same for vehicles on the existing `ship_type` entity.

> **Status (2026-05-27):** plan draft awaiting user approval. Nothing is implemented yet. Live API probes captured at `/tmp/sc-sync-research/*.json` for the diff numbers quoted below.

---

## 1. Motivation

UEX is today the source of truth for **trade-good prices and terminal locations**. It is the canonical commodity catalog driving the refinery, job-order, and trading flows. But UEX also exposes two entity classes we currently do *not* sync: `/items` (armor, weapons, components, paints, ~thousands of rows) and richer fields on `/vehicles`. The current `UexVehicleService` only writes `name + manufacturer + scu + synthesized description` into `ship_type`; everything else (mass, dimensions, cargo grids, crew, all 47 `is_*` capability flags) is dropped.

The SC Wiki API adds three further things UEX does not have:

1. **Crafting blueprints** (1559 recipes as of 4.8.0-LIVE) — output item, ingredients (resource or item), quantities, craft time, dismantle returns, unlocking missions. Required to power any future blueprint / manufacturing / progression UI.
2. **Richer per-item data** — `classification` ("FPS.Armor.Helmet", "Ship.Weapon.Gun"), `class_name`, `mass`, `dimension`, `entity_tags`, `port_tags`, `required_tags`, multi-language descriptions (EN, DE, ZH, FR, ...), variants list, related items, full stats blocks for weapons.
3. **A richer commodity model with hierarchy** — every commodity carries `raw_versions` / `refined_versions` cross-refs, harvestable / mineable / salvage flags, and per-system / per-location availability. The wiki commodity catalog **overlaps but does not equal** UEX's (165/205 names match exactly; the rest split between naming variants, wiki-only harvestables, and UEX-only chemicals/event items).

**UEX and Wiki share the in-game UUID space for items and vehicles — when UEX actually has a UUID for the row.** The hypothesis was verified on 130 UEX items (across 17 categories spanning all 21 sections) and 172 UEX vehicles: every single row where both systems carried a UUID had **identical** UUIDs — 0 mismatches in 241 paired tests (see §3.6 for the full sample matrix). The caveat is that **~30% of UEX item rows and ~31% of UEX vehicle rows have an empty `uuid` field** — `Avionics/FlightBlade` is 100% empty, `Decorations` 88%, `Utility/DockingCollars` 57%, `Liveries` 42%, `Armor` ~33%, while `Systems/Coolers` is 1.4% and `Footwear` 8%. For those, we fall back to slug match (§8.3 resolution chain).

So the merge model is:

|        Caller has        | Match strength |                                    Resolution                                     |
|--------------------------|----------------|-----------------------------------------------------------------------------------|
| UEX `uuid` non-empty     | strong         | direct `byExternalUuid(uuid)` → Wiki UUID identical                               |
| UEX `uuid` empty         | medium         | `bySlug(slug)` with slug normalization (strip `--`→`-`, trailing dash, lowercase) |
| Neither side has the row | n/a            | row stays `UEX_ONLY` (UEX-side write only) or `WIKI_ONLY`                         |

Roughly: ~68% direct UUID resolve, ~15–20% via slug-fallback, ~12–15% remain `UEX_ONLY` (genuine Wiki gaps — specific Aurora Mk I sub-variants, capital ships like Idris-M/Polaris, certain decorations). Commodities are different again: UEX's `/commodities` uses integer ids only, Wiki's `/commodities` uses UUIDs, and the two catalogs intersect by name — see §4.

The Wiki API additionally **already exposes UEX cross-references**: every wiki item carries a `uex_prices` block with `terminal_id`, `terminal_code`, `terminal_name`, and a direct `uex_link` back to uexcorp.space. That confirms the two systems are designed to interoperate.

---

## 2. Scope

### 2.1 In scope

**Wiki side — new integration:**
- New integration package `integration/scwiki` mirroring the existing UEX package shape.
- A scheduled job (`ScWikiScheduler`) on its own `@Async` executor.
- Sync of: **wiki commodities, wiki blueprints, wiki items (all kinds), wiki vehicles, wiki manufacturers**.
- **Merging wiki commodities into the existing `material` table** (same row when matched by name/alias, separate row when not).

**UEX side — extension of existing integration:**
- New `UexItemSyncService` that walks all `/items?id_category=<n>` for the 98 categories and upserts into `game_item` keyed by `external_uuid`.
- New optional `UexItemPriceSyncService` for `/items_prices_all` — retail prices for armor, clothing, vehicle components, etc. (Behind a feature flag in this plan; defaulted off — see §11 phase R7.)
- New `UexCategoryRefService` writing the 98-row category reference table.
- **Harden `UexVehicleService` to match by `external_uuid` first**, name only as fallback. Extend `ship_type` with the rich vehicle fields UEX exposes (and that Wiki adds — mass, dimensions, fuel capacities, all the capability `is_*` flags, urls, paths).

**Joint concerns:**
- A single `game_item` table keyed by `external_uuid` (UNIQUE). UEX columns + Wiki columns coexist. Each side updates only its own columns, plus the canonical `external_uuid` / `name` / `manufacturer` set.
- A single `ship_type` row per vehicle, keyed by `external_uuid`. Same merge model.
- **Orphan handling** — entities removed upstream get a `<source>_deleted_at` timestamp on next sync (not a hard `DELETE`; FK safety + history). Each source tracks its own deletion state.
- **Alias table** for the *commodity*-only name-variant mapping between wiki and UEX (item/vehicle merge uses UUID match, no alias needed).

### 2.2 Out of scope (this plan)

- **Missions, factions, starmap locations, jurisdictions, planets, moons, cities** — UEX already covers these and our schema (`Faction`, `City`, `Planet`, `Moon`, etc.) is keyed to UEX. Pulling them from the wiki on top would create a second source of truth for the same data without a clear benefit. Defer until a concrete UI need emerges.
- **Comm-Link / RSI Archive / Galactapedia content** — out of the manufacturing domain.
- **Image / asset downloads** — both APIs return CDN URLs for thumbnails; storing the URL is enough.
- **A blueprint / crafting UI** — the data layer ships in this plan; a UI on top is a separate piece of work, gated on whichever org-unit-scoped flow needs it.
- **Auto-fetching every one of the ~12 700 Wiki items in one shot** in the *Wiki sync*. Wiki sync runs in **closure mode** first — only items referenced by an ingested blueprint or already present in `game_item` (because UEX put them there). A full Wiki backfill is a feature-flagged later phase.
- **UEX vehicle-loaner / purchase-prices / rental-prices catalogs** — defer until there is a UI surface that uses them.
- **UEX `/items_attributes`** — the per-item stats endpoint. It requires a per-item walk (~thousands of round-trips); without a concrete UI need it would burn budget for no gain. Defer.

---

## 3. API findings — live probe

All numbers below come from raw `curl` against the public endpoints on **2026-05-27** against game version **4.8.0-LIVE.11875683**. Raw responses are in `/tmp/sc-sync-research/`.

### 3.1 Base properties

|   Property   |                                                                       Value                                                                        |
|--------------|----------------------------------------------------------------------------------------------------------------------------------------------------|
| Base URL     | `https://api.star-citizen.wiki`                                                                                                                    |
| OpenAPI YAML | `GET /api/openapi` (667 KB, OpenAPI 3.0.0, info.version 3.0.0)                                                                                     |
| Auth         | Public for game-data endpoints; Sanctum bearer for image-search. **No key required for our scope.**                                                |
| Format       | JSON                                                                                                                                               |
| Versioning   | `?version=<gameVersion>` per request; omitted = current default. `GET /api/game-versions` lists available codes.                                   |
| Pagination   | `page[number]`, `page[size]` (max 200). Response: `data[]`, `links{}`, `meta{current_page,last_page,per_page,total}`.                              |
| Sorting      | `sort=field` / `sort=-field`.                                                                                                                      |
| Filtering    | `filter[<field>]=<value>`. Per-endpoint catalog at `/{resource}/filters`.                                                                          |
| Rate limit   | Search 60 req/min/IP, image-search 10 req/min. Plain list endpoints not advertised — observed > 60 req/min works. We pace at 5 req/sec to be safe. |
| Caching      | Standard HTTP `ETag` + `If-None-Match`. We reuse the existing `UexClient` ETag pattern verbatim.                                                   |

### 3.2 Endpoints in scope

|           Endpoint            |                                                          Total (4.8.0) |                                                   Notes                                                   |
|-------------------------------|-----------------------------------------------------------------------:|-----------------------------------------------------------------------------------------------------------|
| `GET /api/commodities`        |                                                                **205** | The mining/trade-good catalog *plus* a handful of in-game items that have cargo metadata (helmets, ammo). |
| `GET /api/blueprints`         |                                                               **1559** | Recipes. Sub-resource detail under `/api/blueprints/{uuid}`.                                              |
| `GET /api/items`              |                                                             **12 706** | Full game-item pool, *no* default classification filter.                                                  |
| `GET /api/vehicle-items`      |                                                              **3 211** | Ship/vehicle components. Filter still recommended — by default includes paints under `Ship.Paints`.       |
| `GET /api/vehicle-weapons`    | **~1 100** *(not probed in full; estimate from `filter[size]` facets)* | Mounted ship weapons.                                                                                     |
| `GET /api/weapons`            |                                                               **~600** | Hand-held FPS weapons.                                                                                    |
| `GET /api/weapon-attachments` |                                                               **~700** | Scopes, magazines, barrel mods, etc.                                                                      |
| `GET /api/armor`              |                                                 **12 706** **(quirk)** | Endpoint returns the **full /items pool** when no `filter[classification]` is set. See §3.4.              |
| `GET /api/clothes`            |                                                    **(probe pending)** | Same quirk as `/armor` likely.                                                                            |
| `GET /api/food`               |                                                    **(probe pending)** | Same quirk likely.                                                                                        |
| `GET /api/manufacturers`      |                                                    **(probe pending)** | Companies / in-universe vendors.                                                                          |
| `GET /api/game-versions`      |                                                                  small | Reference table for `version` query param.                                                                |

### 3.3 Schema highlights

**Blueprint** (`/api/blueprints/{uuid}`) — clean and stable:

```json
{
  "uuid": "280f47b7-...",
  "key": "BP_CRAFT_AMRS_LaserCannon_S1",
  "output_item_uuid": "26838ca7-...",
  "output_name": "Omnisky III Cannon",
  "output_class": "amrs_lasercannon_s1",
  "category_uuid": "61e576d2-...",
  "craft_time_seconds": 540,
  "is_available_by_default": false,
  "game_version": "4.8.0-LIVE.11875683",
  "ingredient_count": 3,
  "unlocking_missions_count": 1,
  "ingredients": [
    { "name": "Agricium", "kind": "resource",
      "resource_type_uuid": "dc6fbcbb-...", "item_uuid": null,
      "quantity_scu": 0.36, "quantity": null },
    { "name": "Hadanite", "kind": "item",
      "resource_type_uuid": null, "item_uuid": "125dd723-...",
      "quantity_scu": null, "quantity": 7 }
  ],
  "dismantle_returns": [
    { "name": "Agricium", "resource_type_uuid": "dc6fbcbb-...",
      "quantity_scu": 0.18 }
  ],
  "output": { "uuid": "26838ca7-...", "name": "Omnisky III Cannon",
              "type": "WeaponGun", "sub_type": "Gun", "grade": "1" }
}
```

Key insight: **ingredients carry a `kind` discriminator**:
- `kind: "resource"` → references a **commodity** by `resource_type_uuid`, quantity in SCU.
- `kind: "item"` → references a **game item** by `item_uuid`, quantity in whole units.

Same commodity can appear in both pools (Hadanite is a commodity *and* an item).

**Commodity** (`/api/commodities/{uuid}?include=blueprints,items`):

```json
{
  "uuid": "dc6fbcbb-...",
  "key": "Agricium",
  "name": "Agricium",
  "slug": "agricium",
  "kind": "",
  "density_g_per_cc": 1,
  "box_sizes_scu": [1,2,4,8,...],
  "raw_versions": [...],
  "is_mineable": false,
  "has_harvestables": false,
  "uex_prices": { "purchase": [ ... terminal-level price rows ... ] },
  "blueprints": [ ... 196 blueprints referencing this commodity ... ],
  "items": [ ... ]
}
```

Note `uex_prices.purchase[].uex_link = "https://uexcorp.space/items/info?name=..."` — the wiki already cross-references UEX.

**Game item** (`/api/items/{uuid}`):

```
uuid, slug, name, class_name, classification ("Ship.Weapon.Gun", "Char.Armor", "Char.Clothing.Backpack", "FoodAndDrink", "Vehicle.Cooler", etc.),
type, type_label, sub_type, sub_type_label, size, grade, mass, dimension{x,y,z},
manufacturer{uuid,name,code}, description{en_EN,de_DE,zh_CN,...},
description_data, tags[], entity_tags[], required_tags[],
images[], shops[], variants[], is_base_variant, is_craftable,
uex_prices{ purchase[], rent[], sell[] }, updated_at, version
```

Multiple language descriptions are available — we capture EN and DE.

### 3.4 Observed quirks

1. **`/api/armor` (and likely `/clothes`, `/food`) returns the full `/items` pool** when called without a `filter[classification]`. Workaround: drive the sync from `/api/items?filter[classification]=Char.Armor.*` patterns OR call the resource endpoints with the right classification filter. We will use `/api/items?filter[type]=Char_Armor` (and analogues) — needs probing per resource. **Open question — see §13.**
2. **Wiki commodity catalog includes non-trade-good entries** — paints, ammo, environmental indicators ("Heat", "Oxygen", "Power:"), placeholders ("<= PLACEHOLDER =>"), entries with raw HTML in the name (`CO<font size="%d">2</font>`), entries with underscore-only asset names (`Vlk_Fang`). The sync layer must **filter** these. Heuristic:
   - skip if `name` contains `<` or `>` or starts with `<=`
   - skip if `name` contains an underscore
   - skip if `key` matches `Char_*`, `Char\.Armor.*`, `cold`, `hot`, `EVAFuel`, `Electricity`, `Oxygen`, `LifeSupport`, `ShipAmmo*` (these are environment / item entities polluting the commodity pool)
   - log the skip at INFO level so we can audit which entries got dropped
3. **Ingredient resource UUID ≠ commodity UUID in some cases.** The Agricium blueprint sample has ingredient `resource_type_uuid = dc6fbcbb-...` (which equals the commodity uuid) *but* an embedded `link` pointing to `/api/commodities/fc1ec740-...` (a different uuid). The wiki API has internal aliases for the same logical resource. **Always trust `resource_type_uuid` over `link.uuid`** — that's what's stable across blueprint syncs.
4. **`output.uuid` on a blueprint summary entry can be empty**; the top-level `output_item_uuid` is authoritative.
5. **Pagination quirk** — when `page[size]=200&page[number]=1`, the meta block's `last_page` says `2` but `total=205`, so page 2 has 5 entries. Standard. Just paginate until `meta.current_page == meta.last_page`.
6. **Game version** — every game-data row carries the game version it was last seen in. Cross-game-version diffing is out of scope: we always use the current default. If we ever need to honor a pinned version, add a `krt.scwiki.game-version` property and pass it through.
7. **Wiki vehicle lookup at `/api/items/{uuid}` 302-redirects to `/api/vehicles/{uuid}`** when the uuid points to a vehicle. Treat 302 from `/api/items/{uuid}` as "this is a vehicle, not an item" — the existing redirect target is the right place to read full vehicle data.
8. **Wiki items can have variant entries with `slug-2`, `slug-3` suffixes** sharing the same display name (e.g., `venture-helmet-white` uuid `2a1c2646-...` and `venture-helmet-white-2` uuid `28c76343-...`). They are distinct in-game `class_name` values for color/quality variants. UEX picks one canonical variant per visible name. Always trust the UUID as identity, never the slug.

### 3.5 UEX items API — findings

Live probe of `https://api.uexcorp.space/2.0/*`:

|              Endpoint              |        Result        |                                                                                                            Notes                                                                                                            |
|------------------------------------|----------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `GET /commodities`                 | 201 rows             | Existing UEX commodity catalog. Keyed by integer `id`. **No `uuid`.** This is what currently drives `material`.                                                                                                             |
| `GET /items`                       | 400 without params   | Requires `id_category`, `id_company`, or `uuid` as filter.                                                                                                                                                                  |
| `GET /items?id_category=3`         | 493 rows             | Helmets. Each row has integer `id` AND `uuid` (= the in-game UUID, identical to Wiki's).                                                                                                                                    |
| `GET /items_prices`                | 400 without params   | Requires `id_item` / `id_terminal` / `id_category` / `uuid`.                                                                                                                                                                |
| `GET /items_prices_all`            | (large, est. ~1 MB+) | All item retail prices in one shot, similar shape to `/commodities_prices_all`.                                                                                                                                             |
| `GET /items_attributes`            | 92 bytes empty       | Requires params; per-item stats endpoint. **Defer.**                                                                                                                                                                        |
| `GET /categories`                  | 98 rows              | Reference data. Each row: `id`, `type` ('item' or 'vehicle'), `section` ("Armor"/"Clothing"/"Vehicle Weapons"/"Personal Weapons"/"Utility"/"Systems"/"Avionics"/...), `name` (subcategory), `is_game_related`, `is_mining`. |
| `GET /companies`                   | 311 rows             | Manufacturers. `id`, `name`, `nickname`, `industry`, `is_item_manufacturer`, `is_vehicle_manufacturer`.                                                                                                                     |
| `GET /vehicles`                    | 279 rows             | Each has `uuid` matching Wiki. Rich payload: 47 `is_*` capability flags, dimensions, mass, fuel, urls, container_sizes, pad_type, crew, scu.                                                                                |
| `GET /vehicles_prices`             | (~225 KB)            | Pledge-store retail prices (`price`, `price_warbond`, `price_concierge`, currency, on_sale flags).                                                                                                                          |
| `GET /vehicles_loaners`            | (defer)              | Loaner cross-references.                                                                                                                                                                                                    |
| `GET /vehicles_rentals_prices_all` | (defer)              | In-game rental prices. Out of plan scope.                                                                                                                                                                                   |

**UEX category structure (relevant sections):**

|     Section      | Categories |                                Examples                                 |
|------------------|------------|-------------------------------------------------------------------------|
| Armor            | 6          | `3` Helmets, `5` Torso, `4` Legs, `1` Arms, `2` Backpacks, `7` Full Set |
| Clothing         | 10         | `8` Footwear, `9` Gloves, `10` Hats, `11` Jackets, `12` Jumpsuits, ...  |
| Vehicle Weapons  | 8          | various sizes / mount types                                             |
| Personal Weapons | 2          | rifles vs sidearms                                                      |
| Systems          | 7          | power plants, shields, coolers, ...                                     |
| Utility          | 11         | scanners, quantum drives, ...                                           |
| Avionics         | 2          | radar, jammer                                                           |
| Liveries         | 1          | paints                                                                  |
| (others)         | ~20        |                                                                         |

The Java sync iterates the 98 categories, hitting `/items?id_category=<n>` for each, then aggregates. Pacing: 5 req/s → ~20 s for the 98 calls.

**UEX item schema (helmet example):**

```
id              integer       — UEX internal id (stable across runs)
id_parent       integer       — variant grouping; 0 = no parent
id_category     integer       — FK to category
id_company      integer       — FK to company / manufacturer
id_vehicle      integer       — FK to vehicle for vehicle-bound items (paints/components)
name            string        — display name
slug            string        — kebab-case url slug
uuid            UUID          — in-game RSI asset UUID (matches Wiki)
size            string        — '1','2',... or ''
color           string        — primary color of the variant
color2          string        — secondary color
quality         integer       — 0..n quality tier
url_store       string        — RSI pledge store URL
section         string        — denormalized from category
category        string        — denormalized name
company_name    string        — denormalized
vehicle_name    string        — denormalized (when id_vehicle != 0)
screenshot      string        — URL
is_exclusive_pledge / _subscriber / _concierge — flags
is_commodity    integer       — 1 if also appears in /commodities
is_harvestable  integer
game_version    string
date_added / date_modified — unix timestamps
notification    string        — UEX freeform note
```

**Critical cross-ref**: `items[].uuid` is identical to the wiki's `uuid` for the same in-game asset — but only when UEX has one. Probe of UEX uuid `28c76343-8da9-495a-9339-3d5de02e6c3c` (Venture Helmet White) resolved on the wiki side as `GET /api/items/28c76343-8da9-495a-9339-3d5de02e6c3c` → returns a wiki row with `slug=venture-helmet-white-2`. Same UUID, same item, different `slug` (Wiki appends `-2` to disambiguate from another helmet of the same display name with a different `class_name`).

### 3.6 Cross-ref verification — 100+ item sample

To validate the UUID-identity claim at scale, ran a structured probe on **2026-05-27**:

**Sample design.** 17 UEX categories chosen to span every section that has items (Armor 3 categories, Clothing 3, Personal Weapons 1, Vehicle Weapons 1, Systems 1, Utility 1, Avionics 1, Liveries 1, Undersuits 1, Decorations 1, Technology 1, Propulsion 1, Flair 1). Pulled the first 10 items per category and stripped `uuid=""` rows separately. Tested each non-empty UUID against `GET https://api.star-citizen.wiki/api/items/{uuid}` over a 1-minute window. Names compared case-insensitive.

**Result — items with non-empty UEX uuid (130 tested):**

|                                                Outcome                                                 | Count |    Pct |
|--------------------------------------------------------------------------------------------------------|------:|-------:|
| Wiki returns 200, **UUID identical**, name EXACT                                                       |   124 |  95.4% |
| Wiki returns 200, **UUID identical**, name DIFFER (variant naming, e.g. "Aurora Mk I X" vs "Aurora X") |     3 |   2.3% |
| Wiki returns 404 (truly missing on wiki)                                                               |     3 |   2.3% |
| Wiki returns UUID **different** from UEX                                                               | **0** | **0%** |

**Result — items with empty UEX uuid (68 tested via slug fallback):**

|                     Outcome                     | Count |   Pct |
|-------------------------------------------------|------:|------:|
| Wiki returns 200 for the UEX slug, name EXACT   |    29 | 42.6% |
| Wiki returns 200 for the UEX slug, name DIFFER  |     2 |  2.9% |
| Wiki returns 404 — slug mismatch or genuine gap |    37 | 54.4% |

**Per-category UEX empty-UUID rate** (matters for sync planning):

|        Section / Category        |     Total | Empty UUID |        Pct |
|----------------------------------|----------:|-----------:|-----------:|
| Avionics / Flight Blade          |        64 |         64 | **100.0%** |
| Flair / Surface (Action Figures) |         2 |          2 | **100.0%** |
| Decorations                      |        34 |         30 |  **88.2%** |
| Utility / Docking Collars        |         7 |          4 |  **57.1%** |
| Liveries                         |       706 |        300 |  **42.5%** |
| Personal Weapons / Attachments   |       141 |         52 |  **36.9%** |
| Armor / Torso                    |       348 |        125 |  **35.9%** |
| Armor / Arms                     |       342 |        113 |  **33.0%** |
| Armor / Helmets                  |       493 |        161 |  **32.7%** |
| Undersuits                       |       177 |         41 |  **23.2%** |
| Clothing / Shirts                |       263 |         52 |  **19.8%** |
| Clothing / Jackets               |       391 |         65 |  **16.6%** |
| Clothing / Footwear              |       273 |         21 |   **7.7%** |
| Vehicle Weapons / Guns           |       146 |         10 |   **6.8%** |
| Technology / Mobiglas            |        20 |          1 |   **5.0%** |
| Systems / Coolers                |        70 |          1 |   **1.4%** |
| Propulsion / Jump Modules        |         3 |          0 |   **0.0%** |
| **Across all 17 categories**     | **3 480** |  **1 042** |  **29.9%** |

**Vehicles (172 of 279 probed, 107 truncated by an irrelevant Unicode artifact in the test rig):**

|                                                                                  Outcome                                                                                  | Count |    Pct |
|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------:|-------:|
| UEX uuid present, Wiki returns 200, UUID identical, name EXACT                                                                                                            |   104 |  60.5% |
| UEX uuid present, Wiki returns 200, UUID identical, name DIFFER (e.g. UEX `M50` vs Wiki `M50 Interceptor`, UEX `Caterpillar Pirate Edition` vs Wiki `Caterpillar Pirate`) |     7 |   4.1% |
| UEX uuid present, Wiki returns 404 (Aurora Mk I CL/ES/LN/LX/MR, Idris-M, Polaris)                                                                                         |     7 |   4.1% |
| UEX uuid empty (e.g. A2/C2 Hercules, Apollo Medivac, Ares Inferno, Ballista Dunestalker)                                                                                  |    54 |  31.4% |
| **UUID mismatch (UEX uuid != Wiki uuid)**                                                                                                                                 | **0** | **0%** |

**Takeaways encoded into the plan:**

1. **The UUID-identity invariant holds — every paired test agreed.** This justifies `external_uuid` as the strong join key. (§6.3.1 entity.)
2. **~30% of UEX rows ship with `uuid=""`** — heavy in Avionics, Decorations, Liveries, Armor. The plan must not assume UUID coverage. (§8.3 adds slug fallback.)
3. **~46% of empty-UUID items resolve via direct slug match**; with normalization (strip `--`→`-`, trailing dash, lowercase) we expect ~65-70%. The remaining ~25-30% are genuine `UEX_ONLY`. (§8.3.2 normalization rules.)
4. **Wiki has a small set of genuine gaps** even for items WITH UUIDs — the 3% "Wiki 404" rate is real. Furniture, certain Aurora Mk I sub-variants, capital ships (Idris-M, Polaris) — Wiki simply doesn't track them. These rows stay `source_systems = UEX_ONLY` forever. (§14 risk row.)
5. **Name divergence between UEX and Wiki is common but harmless** — both sides keep their own name in their own column; the conflict-resolution rule (§6.3.5) picks the more specific form for the canonical `name`.

Raw probe files in `/tmp/sc-sync-research/`: `xref-sample-uuid.csv`, `xref-results-v2.tsv`, `xref-sample-noUuid-clean.csv`, `xref-results-noUuid-v2.tsv`, `vehicle-results.tsv`.

---

## 4. Material merge analysis — concrete diff

Live diff between `https://api.uexcorp.space/2.0/commodities` (201 entries) and `https://api.star-citizen.wiki/api/commodities` (205 entries), normalized via `lowercase → strip non-alphanumeric`:

|                         Category                         |   Count |
|----------------------------------------------------------|--------:|
| Exact-name matches (normalized)                          | **165** |
| Fuzzy matches (strip `raw|ore|refined|pure|r` qualifier) |   **4** |
| Remaining wiki-only entries                              |  **33** |
| Remaining UEX-only entries                               |  **32** |

### 4.1 Fuzzy-pair seed (auto-applied at first sync) — verified

All 4 confirmed via Wiki detail fetch on 2026-05-27 (artifacts in `/tmp/sc-sync-research/detail-wiki/`). Each Wiki entry has `is_mineable=True` and `kind=mineable`; UEX counterparts all carry `is_raw=1 + is_refinable=1`. Both sides describe the same raw form of the same in-game material.

|     Wiki name     | Wiki density |      UEX name       | UEX code |       UEX kind        |
|-------------------|-------------:|---------------------|----------|-----------------------|
| Raw Silicon       |         2.34 | Silicon (Raw)       | SILI     | Raw Materials         |
| Stileron (Ore)    |         4.75 | Stileron (Raw)      | STIL     | Man-made              |
| Raw Ouratite      |         1.10 | Ouratite (Raw)      | OURAR    | `Minteral` (UEX typo) |
| Hephaestanite (R) |         3.20 | Hephaestanite (Raw) | HEPH     | Mineral               |

### 4.2 Manual alias seed (must be encoded in V108 seed insert) — verified

Cases the fuzzy rule can't match but are clearly the same referent. **Construction-*** and **Combat Supplies** entries from earlier draft were *removed* after verification — see §4.2.1.

|      Wiki name       |  UEX name   |                                                                  Rationale                                                                  |
|----------------------|-------------|---------------------------------------------------------------------------------------------------------------------------------------------|
| Lastaprene           | Lastaphrene | UEX has extra `h` (canonical RSI spelling appears to be `Lastaprene`, matching Wiki). Both density=1, no flags — single Man-made commodity. |
| Lunes (Spiral Fruit) | Lunes       | parenthetical taxonomic suffix on Wiki side; bare name on UEX side. Both reference the same fruit.                                          |

#### 4.2.1 Aliases REMOVED after verification

These were in the earlier draft of the seed; deep verification proved they are unsafe to auto-seed:

|      Wiki name       |      Proposed UEX target      |     Verdict      |                                                                                                                                                                                                          Reason                                                                                                                                                                                                           |
|----------------------|-------------------------------|------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Combat Supplies      | "Military Supplies"           | **NO UEX MATCH** | UEX has only `Agricultural Supplies (AGRS)` and `Medical Supplies (MEDS)` — *no* "Combat Supplies" / "Military Supplies". Wiki key `MilitarySupplies` is misleading; the displayed name has no UEX twin. → Treat as **genuine Wiki-only**.                                                                                                                                                                                |
| Construction Pieces  | Construction Material Pebbles | **AMBIGUOUS**    | Wiki `key=ConstructionMaterialScrap` vs UEX `code=CMATP` (Pebbles). Three different naming schemes for the three size variants: Wiki asset class (`Scrap/Powder/Chunks`), Wiki display (`Pieces/Rubble/Salvage`), UEX display (`Pebbles/Rubble/Salvage`). UEX `weight_scu=0` on all three (no density to disambiguate). Without in-game asset verification, auto-pairing risks collapsing two physically distinct grades. |
| Construction Rubble  | Construction Material Rubble  | **AMBIGUOUS**    | Same — Wiki key `ConstructionMaterialPowder`.                                                                                                                                                                                                                                                                                                                                                                             |
| Construction Salvage | Construction Material Salvage | **AMBIGUOUS**    | Same — Wiki key `ConstructionMaterialChunks`.                                                                                                                                                                                                                                                                                                                                                                             |

> **Domain question for the user:** the Construction-* triplet needs in-game verification before manual aliasing. The wiki "raw_versions" cross-ref on Wiki "Construction Materials" lists all three Wiki variants (Pieces/Rubble/Salvage) as raw forms of the same refined commodity, and UEX "Construction Materials (CMAT, is_refined=1)" similarly considers CMATP/CMATR/CMATS as its raw inputs — but which Wiki variant maps to which UEX variant requires playing the game and comparing asset names. **Hold these out of the V108 seed entirely** and let an admin add them via the `material_external_alias` admin UI after testing.

### 4.3 Wiki-only commodities — categorize via stricter heuristic (verified)

The original draft used a `kind==""` + flag-based heuristic to drop "junk" entries. **Verification proved this filter is unreliable** — it would drop real commodities like `Blue Bilva`, `Biological Samples`, `Molina Mold`, `Oza`, `Uncut SLAM`, `Virus Cultures`, `Desert Quasi Grazer Egg`. All have `kind=""`, `density=1`, all flags `False`, `methods=0` — the same shape as real junk. The wiki simply doesn't classify them consistently.

**Revised — only the following name patterns are dropped HARD at sync (`SKIP_JUNK`):**

|                Pattern                |                                                            Examples                                                            |
|---------------------------------------|--------------------------------------------------------------------------------------------------------------------------------|
| name contains `<` or `>`              | `<= PLACEHOLDER =>`, `CO<font size="%d">2</font>`                                                                              |
| name starts with `<=`                 | `<= PLACEHOLDER =>`                                                                                                            |
| name contains `_` (raw asset name)    | `Vlk_Fang`                                                                                                                     |
| name ends with `:` (incomplete)       | `Power:`                                                                                                                       |
| name in hardcoded ammo/atmosphere set | `Heat`, `Oxygen`, `Cooler`, `Life Support`, `Mixed Mining`, `EVA Fuel`, `Ship Ammunition - Size 8`, `Ship Ammunition - Size 9` |

The hardcoded ammo/atmosphere set is a small **maintained list** — *not* derived from a generic rule. Admin can add to it via a small `commodity_blacklist` table (or just edit the constant if we keep it in-code; small enough that DB-driven config isn't worth it).

**All other Wiki-only entries are imported into `material` with `source_systems=WIKI_ONLY` and `is_visible=false`.** The new `is_visible` column (Phase R3 migration adds it, default `true` for existing rows, `false` only for fresh Wiki-only writes) lets the admin review and toggle. Real harvestables get flipped to visible; ambiguous "items in commodity pool" like `Ace Interceptor Helmet`, `MedGel`, `HLX99 Hyperprocessors`, `mobyGlass Personal Computers`, `RS1 Odysey Spacesuits` stay invisible until reviewed.

**Confirmed real Wiki-only commodities (visible after admin approval):**

|          Name           | Has methods/systems |                                              Notes                                              |
|-------------------------|---------------------|-------------------------------------------------------------------------------------------------|
| Bluemoon Fungus         | 1/2                 | harvestable, kind=harvestable                                                                   |
| Decari Pod              | 1/1                 | harvestable                                                                                     |
| Degnous Root            | 1/1                 | harvestable                                                                                     |
| Fotia Seedpod           | 1/1                 | harvestable                                                                                     |
| Golden Medmon           | 1/1                 | harvestable                                                                                     |
| Heart of the Woods      | 1/1                 | harvestable                                                                                     |
| Pingala Seeds           | 1/1                 | harvestable                                                                                     |
| Pitambu                 | 1/1                 | harvestable                                                                                     |
| Prota                   | 1/2                 | harvestable                                                                                     |
| Revenant Pod            | 1/1                 | harvestable                                                                                     |
| Sunset Berries          | 1/1                 | harvestable                                                                                     |
| Biological Samples      | 0/0                 | no flags but rich description (~17 chars name + 17 description)                                 |
| Blue Bilva              | 0/0                 | no flags; description 501 chars                                                                 |
| Desert Quasi Grazer Egg | 0/0                 | no flags; description 339 chars                                                                 |
| Molina Mold             | 0/0                 | no flags; description 183 chars                                                                 |
| Oza                     | 0/0                 | no flags; description 591 chars                                                                 |
| Uncut SLAM              | 0/0                 | drug-raw; UEX has "SLAM" refined but not the uncut form                                         |
| Virus Cultures          | 0/0                 | exists in lore + game                                                                           |
| Lindinium (Ore)         | check               | Wiki has both bare + (Ore), UEX has only bare. The (Ore) form is wiki-only.                     |
| Savrilium (Ore)         | check               | Same as Lindinium.                                                                              |
| Combat Supplies         | n/a                 | No UEX twin. Real or junk? Description suggests real (military equipment crate); admin decides. |

**Confirmed Wiki entries that are *items*, not commodities** (auto-marked `is_visible=false` AND emit `LOOKS_LIKE_ITEM` event — admin should likely **not** flip these visible):

`Ace Interceptor Helmet`, `MedGel`, `HLX99 Hyperprocessors`, `mobyGlass Personal Computers`, `RS1 Odysey Spacesuits`.

### 4.4 UEX-only commodities — verified by Wiki search

20 of the 32 UEX-only entries were probed by `GET /api/commodities?filter[query]=<name>` against the Wiki:

|                       Result                        | Count |                                                                                              Examples                                                                                              |
|-----------------------------------------------------|------:|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Wiki search empty (true UEX-only)                   |    18 | Anti-Hydrogen, Apoxygenite, Arsenic, Atacamite, Boron, Coal, Crude Oil, Fireworks, Jahlium, Krypton, Magnesium, Phosphorus, Selenium, Tellurium, Xenon, Wuotan Seed, Stone Bug Shell, Cobalt (Raw) |
| Wiki search returns `<= PLACEHOLDER =>` (false hit) |     1 | CryoPod — wiki indexes the term but as a placeholder. **Genuine UEX-only.**                                                                                                                        |
| Wiki has the related raw form                       |     1 | Jaclium — UEX bare + UEX (Ore). Wiki has only `Jaclium (Ore)`. UEX bare `Jaclium` (no flags) stays UEX-only.                                                                                       |

So **31 / 32 UEX-only entries** stay UEX-only (the 32nd, `Jaclium`, is also genuine UEX-only — Wiki just lacks the bare/refined form). All these `material` rows carry `source_systems = UEX_ONLY` indefinitely. The Wiki sync **does not** mark them as deleted — wiki not knowing about them ≠ wiki saying they were deleted.

### 4.5 Catalog granularity differences

A small set of materials don't fit clean "exact-match or fuzzy" because the two catalogs disagree on whether to model raw vs refined separately:

|   Base    |                UEX side                 |                  Wiki side                  |                                                    Effect on merge                                                     |
|-----------|-----------------------------------------|---------------------------------------------|------------------------------------------------------------------------------------------------------------------------|
| Cobalt    | `Cobalt` + `Cobalt (Raw)` (both rows)   | `Cobalt` (single row, `is_mineable=True`)   | UEX `Cobalt` ↔ Wiki `Cobalt` (exact match). UEX `Cobalt (Raw)` stays **UEX-only** — Wiki conflates raw + refined here. |
| Lindinium | `Lindinium` (single row)                | `Lindinium` + `Lindinium (Ore)` (both rows) | Wiki `Lindinium` ↔ UEX `Lindinium` (exact match). Wiki `Lindinium (Ore)` stays **Wiki-only**.                          |
| Savrilium | `Savrilium` (single row)                | `Savrilium` + `Savrilium (Ore)` (both rows) | Same as Lindinium — Wiki `Savrilium (Ore)` stays **Wiki-only**.                                                        |
| Jaclium   | `Jaclium` + `Jaclium (Ore)` (both rows) | `Jaclium (Ore)` (single row)                | UEX `Jaclium (Ore)` ↔ Wiki `Jaclium (Ore)` (exact match). UEX `Jaclium` (bare, no flags) stays **UEX-only**.           |

These are not aliases. They're cases where one catalog is more granular than the other, and we accept the extra row on the more-granular side.

### 4.6 Conflict resolution policy

For a single material row that exists in both systems:

|                    Field                     |                                 Winner                                 |                                                      Reason                                                       |
|----------------------------------------------|------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------|
| `name`                                       | UEX (`Material.name` stays UEX-canonical)                              | UEX names are the ones users see in trade/refinery UI; do not rewrite them. Wiki name becomes a searchable alias. |
| `code`                                       | UEX                                                                    | 4-letter trading code, UEX-canonical.                                                                             |
| `slug`                                       | first writer                                                           | both systems set independently.                                                                                   |
| `kind`                                       | UEX                                                                    | UEX has cleaner taxonomy (Metal/Mineral/Gas/...) vs wiki's `null`-heavy field.                                    |
| `description`                                | UEX if present, else wiki                                              | wiki has richer multi-language descriptions; surface them via a new `descriptions` JSON column? Defer — see §13.  |
| `weight_scu` / `density_g_per_cc`            | UEX `weightScu` stays; wiki `density_g_per_cc` populates a new column. | distinct semantics — `weightScu` is cargo density (UEX), `density_g_per_cc` is physical density.                  |
| `is_*` flags                                 | UEX                                                                    | UEX flags are the established source.                                                                             |
| `scwiki_uuid` / `scwiki_key` / `scwiki_slug` | wiki                                                                   | new columns, wiki-only writes.                                                                                    |
| `price_buy` / `price_sell`                   | UEX                                                                    | wiki doesn't track these on the commodity entity (only per-item).                                                 |

---

## 5. Architecture

Mirror the UEX integration's package layout verbatim. Same patterns → easy review, low cognitive load.

### 5.1 New / extended package layout

```
backend/src/main/java/de/greluc/krt/iri/basetool/backend/
│
│  ─── NEW SC Wiki side ───
│
├── integration/scwiki/                          ← NEW
│   ├── ScWikiClient.java                        — WebClient + ETag (clone of UexClient)
│   ├── ScWikiScheduler.java                     — @Scheduled orchestrator
│   ├── ScWikiCommoditySyncService.java          — /api/commodities → material
│   ├── ScWikiBlueprintSyncService.java          — /api/blueprints → blueprint + blueprint_ingredient
│   ├── ScWikiItemSyncService.java               — /api/items + per-classification → game_item (Wiki cols)
│   ├── ScWikiVehicleSyncService.java            — /api/vehicles → ship_type (Wiki cols)
│   ├── ScWikiManufacturerSyncService.java       — /api/manufacturers → manufacturer (Wiki cols)
│   ├── ScWikiAliasService.java                  — applies commodity-name alias seed + admin entries
│   └── ScWikiSyncReportService.java             — collects unmatched / skipped → admin notification
├── dto/scwiki/                                  ← NEW
│   ├── ScWikiResponseDto.java                   — {data: T[], links, meta} envelope
│   ├── ScWikiCommodityDto.java
│   ├── ScWikiBlueprintDto.java
│   ├── ScWikiBlueprintIngredientDto.java
│   ├── ScWikiItemDto.java
│   ├── ScWikiVehicleDto.java                    — rich vehicle payload (cargo_grids, sizes, etc.)
│   ├── ScWikiManufacturerDto.java
│   └── ScWikiMetaDto.java                       — {current_page, last_page, per_page, total}
├── config/
│   └── ScWikiProperties.java                    — @ConfigurationProperties("krt.scwiki")
│
│  ─── EXTENDED UEX side ───
│
├── service/                                     ← existing UEX services live here
│   ├── UexItemSyncService.java                  ← NEW — walks /categories, syncs items via /items?id_category=<n>
│   ├── UexCategoryRefService.java               ← NEW — populates uex_category ref table
│   ├── UexItemPriceSyncService.java             ← NEW (feature-flagged) — /items_prices_all → game_item_price
│   ├── UexVehicleService.java                   ← EXTEND — match by uuid first; populate richer ship_type cols
│   └── UexScheduler.java                        ← EXTEND — calls the new services in topological order
├── dto/uex/                                     ← existing
│   ├── UexItemDto.java                          ← NEW
│   ├── UexItemPriceDto.java                     ← NEW
│   └── UexCategoryDto.java                      ← NEW
├── model/
│   ├── UexCategory.java                         ← NEW — small reference table (98 rows)
│   ├── scwiki/                                  ← NEW package — see §6.3
│   │   ├── GameItem.java                        — joint UEX+Wiki entity (external_uuid UNIQUE)
│   │   ├── GameItemKind.java                    — enum
│   │   ├── Blueprint.java
│   │   ├── BlueprintIngredient.java
│   │   ├── BlueprintIngredientKind.java
│   │   └── BlueprintDismantleReturn.java
│   ├── ShipType.java                            ← EXTEND with external_uuid + uex/wiki columns
│   ├── Manufacturer.java                        ← EXTEND with external_uuid + uex_company_id
│   └── Material.java                            ← EXTEND with scwiki_* columns (commodities only)
└── config/
    └── UexProperties.java                       ← EXTEND with itemsEndpoint, itemsPricesEndpoint, categoriesEndpoint
```

### 5.2 ScWikiProperties

```java
@Data @Validated @Configuration
@EnableScheduling @EnableAsync                  // already enabled by UexProperties; re-enabling is fine
@ConfigurationProperties(prefix = "krt.scwiki")
public class ScWikiProperties {
  @NotBlank private String apiUrl = "https://api.star-citizen.wiki";
  @NotBlank private String commoditiesEndpoint     = "/api/commodities";
  @NotBlank private String blueprintsEndpoint     = "/api/blueprints";
  @NotBlank private String itemsEndpoint           = "/api/items";
  @NotBlank private String vehicleItemsEndpoint    = "/api/vehicle-items";
  @NotBlank private String vehicleWeaponsEndpoint  = "/api/vehicle-weapons";
  @NotBlank private String weaponsEndpoint         = "/api/weapons";
  @NotBlank private String weaponAttachmentsEndpoint = "/api/weapon-attachments";
  @NotBlank private String armorEndpoint           = "/api/armor";
  @NotBlank private String clothesEndpoint         = "/api/clothes";
  @NotBlank private String foodEndpoint            = "/api/food";
  @NotBlank private String manufacturersEndpoint   = "/api/manufacturers";
  @NotNull  private Boolean schedulerEnabled = true;
  @NotBlank private String  schedulerDelay  = "3600000";  // 1 h, same default as UEX
  @Min(50) @Max(200) private Integer pageSize = 200;
  @Min(1) @Max(20) private Integer requestsPerSecond = 5;  // rate-limit pacing
  // Optional pin for reproducibility; null = follow upstream default.
  private String gameVersion;
  // Phase R4 backfill toggle (see §11).
  @NotNull private Boolean syncAllItems = false;
}
```

### 5.3 Reusing `UexClient` patterns

`ScWikiClient` is a near-copy of `UexClient` with three behavioral differences:

1. **Pagination loop** — `fetchAllPages(endpoint, type)` walks `?page[number]=1..last_page`, accumulating into one list. Pages are independently `ETag`-cached.
2. **Rate-limit pacing** — between page fetches, sleep `1000 / requestsPerSecond`. Plain `Thread.sleep` in the async executor is fine; we're already off the request thread.
3. **`include=` parameter support** — appends `?include=blueprints,items` etc. when caller requests it.

The 16 MB max-in-memory buffer is enough: largest probed response is the OpenAPI YAML at 667 KB; the full items pool at `page[size]=200` is ~1.3 MB.

### 5.4 Scheduling

**Both schedulers run independently** on their own `@Async` executor with their own fixed-delay cycle. The `UexScheduler` keeps its hourly cadence (existing); `ScWikiScheduler` defaults to every 24 h (Wiki data changes only on game patches).

```
UexScheduler                                    ← EXISTING — calls extended in order:
  @Async("uexExecutor")
  @Scheduled(fixedDelayString = "${krt.uex.scheduler-delay:3600000}")
  run():
    // existing topology
    syncFactions(); syncJurisdictions(); syncStarSystems();
    syncPlanets(); syncMoons(); syncOrbits(); syncCities();
    syncOutposts(); syncSpaceStations(); syncPois(); syncTerminals();
    // existing trade-good chain
    syncCompanies();                            // → manufacturer (extends with uex_company_id)
    syncVehicles();                             // → ship_type (HARDENED to uuid-match)
    syncCommoditiesAndPrices();
    syncRefiningMethodsAndYields();
    // NEW item chain (additive, fail-soft)
    syncCategoriesRef();                        // → uex_category (98 rows)
    syncItems();                                // 98 category calls → game_item (UEX cols)
    if (itemPriceSyncEnabled) syncItemPrices(); // → game_item_price (feature-flagged)

ScWikiScheduler                                 ← NEW separate executor
  @Async("scWikiExecutor")
  @Scheduled(fixedDelayString = "${krt.scwiki.scheduler-delay:86400000}")  // 24h default
  run():
    syncManufacturers()                         // dimension; cheap; populates manufacturer.scwiki_*
    syncCommodities()                           // single page; populates material.scwiki_*
    syncBlueprints()                            // 8 pages; populates blueprint + ingredient
    if (syncAllItems) syncItemsFull()           // ~64 pages; expensive
    else              syncItemsFromBlueprints() // closure-only — picks up items referenced by blueprints
                                                //   AND items already in game_item from UEX side
    syncVehicles()                              // /api/vehicles → ship_type.scwiki_* fill-in
    syncReport.flushUnmatched()                 // log + persist for admin review
```

Per-service exceptions are caught and logged, **not propagated** — same fail-one-succeed-others contract as today.

Add a new `AsyncConfig.SCWIKI_EXECUTOR` thread pool (size 2, queue 0) so wiki sync never starves UEX sync. The MDC-propagating decorator already in place (`AsyncConfig.MdcPropagatingTaskDecorator`, landed 2026-05-25 per CHANGELOG) covers both pools.

The two schedulers race-free for the joint tables (`game_item`, `ship_type`, `manufacturer`): each side writes only its own `<source>_*` columns, with optimistic-lock `@Version` arbitrating the canonical fields (`name`, `manufacturer_id`, `external_uuid`). On a 409 the loser retries the upsert once; on second failure it logs `SYNC_RACE_CONFLICT` and continues with the next row.

---

## 6. Data model

### 6.1 `material` table — new columns

Add to existing entity ([Material.java](backend/src/main/java/de/greluc/krt/iri/basetool/backend/model/Material.java:26)):

```java
@Column(name = "scwiki_uuid", unique = true)
private UUID scwikiUuid;                          // wiki commodity UUID

@Column(name = "scwiki_key")
private String scwikiKey;                         // wiki internal key, e.g. "Agricium"

@Column(name = "scwiki_slug")
private String scwikiSlug;                        // wiki url slug

@Column(name = "scwiki_synced_at")
private Instant scwikiSyncedAt;                   // last successful wiki touch

@Column(name = "scwiki_deleted_at")
private Instant scwikiDeletedAt;                  // set when wiki stops returning this row

@Column(name = "density_g_per_cc")
private Double densityGramPerCc;                     // physical density (wiki-only)

@Column(name = "instability")
private Double instability;                       // wiki-only refining hint

@Column(name = "resistance")
private Double resistance;                        // wiki-only refining hint

/**
 * Visibility flag for the trading / refinery UIs. Defaults to {@code true} for the existing
 * UEX-sourced catalog (back-compat). Wiki-only commodity rows are inserted with
 * {@code false} so they don't pollute trading flows until an admin has reviewed them
 * (see §4.3). Existing UEX rows keep their current behavior (visible by default).
 */
@Column(name = "is_visible", nullable = false)
private Boolean isVisible = true;

@Enumerated(EnumType.STRING)
@Column(name = "source_systems", nullable = false)
private MaterialSourceSystem sourceSystems = MaterialSourceSystem.UEX_ONLY;
```

New enum:

```java
public enum MaterialSourceSystem {
  UEX_ONLY,        // only seen on UEX
  WIKI_ONLY,       // only seen on SC Wiki — typically uncovered harvestables
  BOTH,            // merged
  MANUAL           // created by admin (existing isManualEntry overlap — see migration)
}
```

The existing `isManualEntry: Boolean` field collapses into `sourceSystems = MANUAL` over a soak window. Both can coexist for one release.

### 6.2 `material_external_alias` — NEW table

Curated cross-references that the auto-matcher can't safely derive.

```sql
CREATE TABLE material_external_alias (
  id              UUID PRIMARY KEY,
  version         BIGINT NOT NULL DEFAULT 0,
  created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  material_id     UUID NOT NULL REFERENCES material(id),
  source_system   VARCHAR(32) NOT NULL,        -- 'UEX' | 'SCWIKI'
  external_name   VARCHAR(255) NOT NULL,        -- the foreign name to match
  external_key    VARCHAR(255),                  -- optional: wiki `key` field
  external_uuid   UUID,                          -- optional: wiki UUID
  external_code   VARCHAR(32),                   -- optional: UEX code (4-letter)
  note            TEXT,                          -- why this alias exists
  created_by      VARCHAR(255),                  -- 'system' for seed, JWT sub for admin-created
  UNIQUE (source_system, external_name)
);
CREATE INDEX idx_material_external_alias_material ON material_external_alias(material_id);
```

The seed insert (V108) writes the §4.2 manual alias table verbatim. The §4.1 fuzzy-derivation rule is *not* persisted — it runs at sync time so the canonical form changes propagate as the rule evolves.

### 6.3 New entities for the item / blueprint domain

```
backend/src/main/java/de/greluc/krt/iri/basetool/backend/model/scwiki/
├── GameItem.java                — joint UEX+Wiki item row (paint, weapon, armor, component, …)
├── GameItemKind.java            — enum: GENERIC, VEHICLE_ITEM, VEHICLE_WEAPON, WEAPON,
│                                  WEAPON_ATTACHMENT, ARMOR, CLOTHING, FOOD
├── GameItemPrice.java           — UEX retail prices per terminal (feature-flagged, phase R7)
├── Blueprint.java               — recipe
├── BlueprintIngredient.java     — per-line ingredient
├── BlueprintIngredientKind.java — enum: RESOURCE | ITEM
└── BlueprintDismantleReturn.java
```

#### 6.3.1 `game_item` — joint UEX+Wiki entity, keyed by `external_uuid`

```java
@Entity
public class GameItem extends AbstractEntity<UUID> {
  @Id @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** In-game RSI asset UUID. Identical between UEX `/items[].uuid` and Wiki `/api/items/{uuid}`.
   *  This is the cross-source join key. */
  @Column(name = "external_uuid", nullable = false, unique = true)
  private UUID externalUuid;

  @Column(nullable = false)
  private String name;

  // ---- canonical fields (either source can set; conflict resolution per §6.3.3) ----
  @ManyToOne
  @JoinColumn(name = "manufacturer_id")
  private Manufacturer manufacturer;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private GameItemKind kind = GameItemKind.GENERIC;

  // ---- Wiki-sourced columns (only ScWiki sync writes) ----
  @Column(name = "scwiki_slug")
  private String scwikiSlug;                          // "venture-helmet-white-2"

  @Column(name = "class_name")
  private String className;                            // "rsi_explorer_armor_light_helmet_01_01_10"

  @Column(name = "classification")
  private String classification;                       // "FPS.Armor.Helmet"
  @Column(name = "classification_label")
  private String classificationLabel;

  @Column(name = "wiki_type")        private String wikiType;
  @Column(name = "wiki_type_label")  private String wikiTypeLabel;
  @Column(name = "wiki_sub_type")    private String wikiSubType;
  @Column(name = "wiki_sub_type_label") private String wikiSubTypeLabel;

  @Column(name = "size_class")       private Integer sizeClass;     // weapon/component size
  @Column(name = "grade")            private String grade;
  @Column(name = "rarity")           private String rarity;
  @Column(name = "mass")             private Double mass;
  @Column(name = "dimension_x")      private Double dimensionX;
  @Column(name = "dimension_y")      private Double dimensionY;
  @Column(name = "dimension_z")      private Double dimensionZ;

  @Column(name = "description_en", columnDefinition = "TEXT")
  private String descriptionEn;
  @Column(name = "description_de", columnDefinition = "TEXT")
  private String descriptionDe;

  @Column(name = "is_base_variant") private Boolean isBaseVariant;
  @Column(name = "is_craftable")    private Boolean isCraftable;

  @Column(name = "scwiki_synced_at")  private Instant scwikiSyncedAt;
  @Column(name = "scwiki_deleted_at") private Instant scwikiDeletedAt;
  @Column(name = "scwiki_game_version_seen") private String scwikiGameVersionSeen;

  // ---- UEX-sourced columns (only UEX sync writes) ----
  @Column(name = "uex_item_id", unique = true)         // UEX's integer id; UNIQUE so we can lookup
  private Integer uexItemId;

  @Column(name = "uex_slug")
  private String uexSlug;

  @ManyToOne
  @JoinColumn(name = "uex_category_id")                 // FK to uex_category ref table
  private UexCategory uexCategory;

  @Column(name = "uex_company_id")                      // raw UEX company id (denormalized;
  private Integer uexCompanyId;                         //   manufacturer FK above is canonical)

  @Column(name = "uex_vehicle_id")                      // raw UEX vehicle id for vehicle-bound
  private Integer uexVehicleId;                         //   items; ship_type FK below resolves it
  @ManyToOne
  @JoinColumn(name = "linked_ship_type_id")
  private ShipType linkedShipType;

  @Column(name = "uex_color")        private String uexColor;
  @Column(name = "uex_color2")       private String uexColor2;
  @Column(name = "uex_quality")      private Integer uexQuality;
  @Column(name = "uex_url_store")    private String uexUrlStore;
  @Column(name = "uex_screenshot")   private String uexScreenshot;

  @Column(name = "is_exclusive_pledge")     private Boolean isExclusivePledge;
  @Column(name = "is_exclusive_subscriber") private Boolean isExclusiveSubscriber;
  @Column(name = "is_exclusive_concierge")  private Boolean isExclusiveConcierge;
  @Column(name = "uex_is_commodity")        private Boolean uexIsCommodity;
  @Column(name = "uex_is_harvestable")      private Boolean uexIsHarvestable;

  @Column(name = "uex_notification", columnDefinition = "TEXT")
  private String uexNotification;

  @Column(name = "uex_synced_at")    private Instant uexSyncedAt;
  @Column(name = "uex_deleted_at")   private Instant uexDeletedAt;
  @Column(name = "uex_game_version_seen") private String uexGameVersionSeen;

  // ---- joint provenance ----
  @Enumerated(EnumType.STRING)
  @Column(name = "source_systems", nullable = false)
  private GameItemSourceSystem sourceSystems = GameItemSourceSystem.UEX_ONLY;
}
```

`GameItemKind` derivation at sync time:

|                                                   Source endpoint                                                   |       `kind`        |
|---------------------------------------------------------------------------------------------------------------------|---------------------|
| UEX `/items?id_category=<n>` where category.section = `"Armor"`                                                     | `ARMOR`             |
| UEX `/items?id_category=<n>` where category.section = `"Clothing"` or `"Undersuits"`                                | `CLOTHING`          |
| UEX `/items?id_category=<n>` where category.section = `"Personal Weapons"`                                          | `WEAPON`            |
| UEX `/items?id_category=<n>` where category.section = `"Vehicle Weapons"`                                           | `VEHICLE_WEAPON`    |
| UEX `/items?id_category=<n>` where category.section = `"Liveries"` or `"Flair"`                                     | `GENERIC`           |
| UEX `/items?id_category=<n>` where category.section in (Systems, Utility, Avionics, Propulsion, Module, Technology) | `VEHICLE_ITEM`      |
| Wiki `/api/vehicle-items` (any)                                                                                     | `VEHICLE_ITEM`      |
| Wiki `/api/vehicle-weapons`                                                                                         | `VEHICLE_WEAPON`    |
| Wiki `/api/weapons`                                                                                                 | `WEAPON`            |
| Wiki `/api/weapon-attachments`                                                                                      | `WEAPON_ATTACHMENT` |
| Wiki `/api/armor` (with explicit classification filter)                                                             | `ARMOR`             |
| Wiki `/api/clothes` (with explicit classification filter)                                                           | `CLOTHING`          |
| Wiki `/api/food` (with explicit classification filter)                                                              | `FOOD`              |
| Wiki `/api/items` everything else                                                                                   | `GENERIC`           |

The two sides can disagree (UEX classifies via 98-entry category list, Wiki via `classification` path). Tie-breaker rule: **the more specific wins**. `WEAPON_ATTACHMENT` > `WEAPON`, `VEHICLE_WEAPON` > `VEHICLE_ITEM`, anything > `GENERIC`. The same logic applies whether UEX or Wiki was the last writer.

A single `external_uuid` is unique across the whole table — if the same uuid is hit from multiple resource endpoints (paints come up under both `/vehicle-items` and `/items` in the wiki, and under multiple UEX categories), each sync run updates the row in place, only writing to its own `<source>_*` column block plus canonical fields. New `GameItemSourceSystem` enum mirrors `MaterialSourceSystem`:

```java
public enum GameItemSourceSystem { UEX_ONLY, WIKI_ONLY, BOTH }
```

#### 6.3.2 `blueprint`

```java
@Entity
public class Blueprint extends AbstractEntity<UUID> {
  @Id @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "scwiki_uuid", nullable = false, unique = true)
  private UUID scwikiUuid;

  @Column(name = "scwiki_key")
  private String scwikiKey;                            // e.g. BP_CRAFT_AMRS_LaserCannon_S1

  @ManyToOne
  @JoinColumn(name = "output_item_id")
  private GameItem outputItem;

  @Column(name = "output_name")
  private String outputName;

  @Column(name = "category_uuid")
  private UUID categoryUuid;

  @Column(name = "craft_time_seconds")
  private Integer craftTimeSeconds;

  @Column(name = "is_available_by_default", nullable = false)
  private Boolean isAvailableByDefault = false;

  @Column(name = "ingredient_count")
  private Integer ingredientCount;

  @Column(name = "unlocking_missions_count")
  private Integer unlockingMissionsCount;

  @Column(name = "game_version_seen")
  private String gameVersionSeen;

  @Column(name = "scwiki_synced_at")
  private Instant scwikiSyncedAt;

  @Column(name = "scwiki_deleted_at")
  private Instant scwikiDeletedAt;

  @OneToMany(mappedBy = "blueprint", cascade = CascadeType.ALL, orphanRemoval = true,
             fetch = FetchType.LAZY)
  @OrderBy("orderIndex ASC")
  private List<BlueprintIngredient> ingredients = new ArrayList<>();

  @OneToMany(mappedBy = "blueprint", cascade = CascadeType.ALL, orphanRemoval = true,
             fetch = FetchType.LAZY)
  private List<BlueprintDismantleReturn> dismantleReturns = new ArrayList<>();
}
```

#### 6.3.3 `blueprint_ingredient`

```java
@Entity
public class BlueprintIngredient extends AbstractEntity<UUID> {
  @Id @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "blueprint_id", nullable = false)
  private Blueprint blueprint;

  @Column(name = "order_index", nullable = false)
  private Integer orderIndex;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false)
  private BlueprintIngredientKind kind;        // RESOURCE | ITEM

  // exactly one of the two FKs is non-null, enforced by a CHECK
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "material_id")
  private Material material;                   // when kind = RESOURCE

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "game_item_id")
  private GameItem gameItem;                   // when kind = ITEM

  // raw wiki refs for traceability / re-sync without re-resolving
  @Column(name = "wiki_resource_uuid")
  private UUID wikiResourceUuid;
  @Column(name = "wiki_item_uuid")
  private UUID wikiItemUuid;
  @Column(name = "wiki_name_snapshot")
  private String wikiNameSnapshot;

  @Column(name = "quantity_scu")
  private Double quantityScu;                  // when kind = RESOURCE

  @Column(name = "quantity_units")
  private Integer quantityUnits;               // when kind = ITEM
}
```

The DB enforces:

```
CHECK ((kind = 'RESOURCE' AND material_id IS NOT NULL AND game_item_id IS NULL)
    OR (kind = 'ITEM'     AND game_item_id IS NOT NULL AND material_id IS NULL))
CHECK ((kind = 'RESOURCE' AND quantity_scu  IS NOT NULL AND quantity_units IS NULL)
    OR (kind = 'ITEM'     AND quantity_units IS NOT NULL AND quantity_scu  IS NULL))
```

`wiki_resource_uuid` / `wiki_item_uuid` / `wiki_name_snapshot` are kept *even when* `material_id` / `game_item_id` resolves successfully. That gives us forensic data when a wiki uuid changes — we can re-resolve without losing the original payload.

#### 6.3.4 `blueprint_dismantle_return`

Same shape as `blueprint_ingredient` but RESOURCE-only (the wiki schema only returns commodity dismantle yields).

#### 6.3.5 Joint-write conflict resolution (`name`, `manufacturer`, `kind`) on `game_item`

When both sources have written, the **last writer wins** for the canonical fields, with one exception: `manufacturer` is sticky — if UEX has set it and Wiki sends a *different* manufacturer for the same `external_uuid`, log a `MANUFACTURER_MISMATCH` event in the sync report and **keep UEX's value**. This rarely happens (both pull from RSI), but when it does the UEX value is the better default (UEX is the trading-data source).

For `name`, the Wiki form is preferred because it carries proper casing and uses spaces, whereas UEX has occasional inconsistencies (e.g., `"100i"` vs `"Origin 100i"` — UEX has both `name` and `name_full`). Sync logic: if `wiki.name != null and wiki.name.length > existing.name.length and existing.name not in wiki.variants`, prefer wiki name. Otherwise keep current.

For `kind`, the more-specific rule above.

The CHECK on `source_systems = 'WIKI_ONLY' OR 'BOTH'` implies `scwiki_synced_at IS NOT NULL` is enforced at the application layer, not DB-CHECK level (Postgres CHECK can encode it, but it adds noise on each write). ArchUnit + service unit tests pin the invariant.

### 6.4 `manufacturer` — new columns

```java
// existing: id, name (UNIQUE), abbreviation (UNIQUE), nickname, wiki, description, hidden

// ---- joint cross-ref columns ----
@Column(name = "uex_company_id", unique = true)     // UEX integer id
private Integer uexCompanyId;

@Column(name = "scwiki_uuid", unique = true)        // Wiki UUID for manufacturer
private UUID scwikiUuid;

@Column(name = "scwiki_code")                       // e.g. "RSI", "AEGS"
private String scwikiCode;

@Column(name = "industry")                          // UEX adds "Fashion", "Aerospace", …
private String industry;

@Column(name = "is_item_manufacturer")
private Boolean isItemManufacturer;
@Column(name = "is_vehicle_manufacturer")
private Boolean isVehicleManufacturer;

@Column(name = "uex_synced_at")    private Instant uexSyncedAt;
@Column(name = "scwiki_synced_at") private Instant scwikiSyncedAt;
@Column(name = "uex_deleted_at")   private Instant uexDeletedAt;
@Column(name = "scwiki_deleted_at") private Instant scwikiDeletedAt;
```

Match chain on the UEX sync (`UexCompanyDto`): `uex_company_id` → `name (case-insensitive)`. Match chain on the Wiki sync: `scwiki_uuid` → `name (case-insensitive)` → `industry+name`. Both sides only update their own `*_synced_at` and the canonical `name`/`abbreviation`/`industry` block.

### 6.5 `ship_type` — extensions for UUID match + rich vehicle data

The current entity only has `id`, `name (UNIQUE)`, `manufacturer`, `description (synthesized text)`, `scu`, `hidden`. UEX dumps the synthesized description because every other field is dropped today. New columns:

```java
// ---- joint cross-ref columns ----
@Column(name = "external_uuid", unique = true)      // RSI in-game UUID; shared between UEX + Wiki
private UUID externalUuid;

@Column(name = "uex_vehicle_id", unique = true)     // UEX integer id (1, 2, ...)
private Integer uexVehicleId;

@Column(name = "uex_slug")    private String uexSlug;       // "100i"
@Column(name = "scwiki_slug") private String scwikiSlug;     // "orig-100i"

// ---- canonical specs (either source can set; last writer wins by field) ----
@Column(name = "name_full") private String nameFull;        // "Origin 100i"
@Column(name = "game_name") private String gameName;        // Wiki's game_name
@Column(name = "class_name") private String className;       // "ORIG_100i"

@Column(name = "crew_min") private Integer crewMin;
@Column(name = "crew_max") private Integer crewMax;
@Column(name = "mass")     private Double mass;             // hull mass
@Column(name = "mass_total") private Double massTotal;      // hull + loadout
@Column(name = "width")    private Double width;
@Column(name = "height")   private Double height;
@Column(name = "length_m") private Double lengthM;          // `length` is SQL-reserved
@Column(name = "pad_type") private String padType;          // "XS", "S", "M", "L", "XL"

@Column(name = "fuel_quantum")  private Double fuelQuantum;
@Column(name = "fuel_hydrogen") private Double fuelHydrogen;

@Column(name = "vehicle_inventory_scu") private Double vehicleInventoryScu;  // Wiki's vehicle_inventory
@Column(name = "ore_capacity")          private Double oreCapacity;
@Column(name = "container_sizes")       private String containerSizes;       // "1,2"
@Column(name = "max_medical_tier")      private Integer maxMedicalTier;
@Column(name = "health")                private Integer health;
@Column(name = "shield_hp")             private Integer shieldHp;

// ---- 47 UEX is_* capability flags as boolean columns ----
@Column(name = "is_addon")        private Boolean isAddon;
@Column(name = "is_boarding")     private Boolean isBoarding;
@Column(name = "is_bomber")       private Boolean isBomber;
@Column(name = "is_cargo")        private Boolean isCargo;
@Column(name = "is_carrier")      private Boolean isCarrier;
@Column(name = "is_civilian")     private Boolean isCivilian;
@Column(name = "is_concept")      private Boolean isConcept;
@Column(name = "is_construction") private Boolean isConstruction;
@Column(name = "is_datarunner")   private Boolean isDatarunner;
@Column(name = "is_docking")      private Boolean isDocking;
@Column(name = "is_emp")          private Boolean isEmp;
@Column(name = "is_exploration")  private Boolean isExploration;
@Column(name = "is_ground_vehicle") private Boolean isGroundVehicle;
@Column(name = "is_hangar")       private Boolean isHangar;
@Column(name = "is_industrial")   private Boolean isIndustrial;
@Column(name = "is_interdiction") private Boolean isInterdiction;
@Column(name = "is_loading_dock") private Boolean isLoadingDock;
@Column(name = "is_medical")      private Boolean isMedical;
@Column(name = "is_military")     private Boolean isMilitary;
@Column(name = "is_mining")       private Boolean isMining;
@Column(name = "is_passenger")    private Boolean isPassenger;
@Column(name = "is_qed")          private Boolean isQed;
@Column(name = "is_quantum_capable") private Boolean isQuantumCapable;
@Column(name = "is_racing")       private Boolean isRacing;
@Column(name = "is_refinery")     private Boolean isRefinery;
@Column(name = "is_refuel")       private Boolean isRefuel;
@Column(name = "is_repair")       private Boolean isRepair;
@Column(name = "is_research")     private Boolean isResearch;
@Column(name = "is_salvage")      private Boolean isSalvage;
@Column(name = "is_scanning")     private Boolean isScanning;
@Column(name = "is_science")      private Boolean isScience;
@Column(name = "is_showdown_winner") private Boolean isShowdownWinner;
@Column(name = "is_spaceship")    private Boolean isSpaceship;
@Column(name = "is_starter")      private Boolean isStarter;
@Column(name = "is_stealth")      private Boolean isStealth;
@Column(name = "is_tractor_beam") private Boolean isTractorBeam;

// ---- urls ----
@Column(name = "url_store")    private String urlStore;
@Column(name = "url_brochure") private String urlBrochure;
@Column(name = "url_hotsite")  private String urlHotsite;
@Column(name = "url_photo")    private String urlPhoto;
@Column(name = "url_video")    private String urlVideo;
@Column(name = "url_wiki")     private String urlWiki;     // existing UexVehicleDto carries this

// ---- multi-language description ----
@Column(name = "description_en", columnDefinition = "TEXT") private String descriptionEn;
@Column(name = "description_de", columnDefinition = "TEXT") private String descriptionDe;

// ---- provenance ----
@Column(name = "uex_synced_at")    private Instant uexSyncedAt;
@Column(name = "scwiki_synced_at") private Instant scwikiSyncedAt;
@Column(name = "uex_deleted_at")   private Instant uexDeletedAt;
@Column(name = "scwiki_deleted_at") private Instant scwikiDeletedAt;

@Enumerated(EnumType.STRING)
@Column(name = "source_systems", nullable = false)
private GameItemSourceSystem sourceSystems = GameItemSourceSystem.UEX_ONLY;
```

The existing `description` (synthesized text) column gets DEPRECATED but not dropped — backwards-compat for current UI. New views surface `descriptionEn` / `descriptionDe` directly.

Match chain (both sync sides):

```
1. byExternalUuid(dto.uuid)                      // strongest signal
2. byUexVehicleId(dto.id) for UEX sync only      // when wiki UUID race lost
3. byNameIgnoreCase(dto.name)                    // legacy match; backfills external_uuid on hit
4. null → caller creates new row
```

### 6.6 `uex_category` — NEW reference table

```sql
CREATE TABLE uex_category (
  id                   INTEGER PRIMARY KEY,        -- UEX's integer id (1..98+)
  version              BIGINT NOT NULL DEFAULT 0,
  type                 VARCHAR(16) NOT NULL,        -- 'item' | 'vehicle'
  section              VARCHAR(64) NOT NULL,        -- "Armor", "Clothing", "Vehicle Weapons", …
  name                 VARCHAR(128) NOT NULL,        -- subcategory name ("Helmets", "Torso", …)
  is_game_related      BOOLEAN NOT NULL,
  is_mining            BOOLEAN NOT NULL,
  uex_synced_at        TIMESTAMPTZ,
  uex_deleted_at       TIMESTAMPTZ
);
CREATE INDEX idx_uex_category_section ON uex_category(section);
```

PK is UEX's id (integer) to keep it predictable for JOINs. The 98 rows seed via the regular sync (no Flyway seed); the table starts empty and fills on first run.

### 6.7 `game_item_price` — NEW table (feature-flagged, phase R7)

```sql
CREATE TABLE game_item_price (
  id              UUID PRIMARY KEY,
  version         BIGINT NOT NULL DEFAULT 0,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  game_item_id    UUID NOT NULL REFERENCES game_item(id),
  terminal_id     UUID NOT NULL REFERENCES terminal(id),
  price_buy       DOUBLE PRECISION,
  price_sell      DOUBLE PRECISION,
  price_rent      DOUBLE PRECISION,
  status_buy      INTEGER,
  status_sell     INTEGER,
  date_modified   BIGINT,                            -- UEX unix timestamp
  game_version    VARCHAR(64),
  uex_synced_at   TIMESTAMPTZ,
  UNIQUE (game_item_id, terminal_id)
);
CREATE INDEX idx_game_item_price_terminal ON game_item_price(terminal_id);
```

Same `clearStalePrices` orphan handling as `material_price`. Build off the existing `terminal` table (synced by `UexUniverseSyncService`).

---

## 7. Flyway migrations (V106 onwards)

|  #   |                           File                           |                                                                                                                                                                                                                                                 Purpose                                                                                                                                                                                                                                                  |           Reversibility           |
|------|----------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------|
| V106 | `add_scwiki_columns_to_material.sql`                     | Add `scwiki_uuid` (UNIQUE), `scwiki_key`, `scwiki_slug`, `scwiki_synced_at`, `scwiki_deleted_at`, `density_g_per_cc`, `instability`, `resistance`, `is_visible` (NOT NULL DEFAULT TRUE), `source_systems` to `material`. Backfill `source_systems = 'UEX_ONLY'`, `is_visible = TRUE` for existing rows (UEX-sourced catalog is visible by default).                                                                                                                                                      | Safe additive.                    |
| V107 | `add_cross_ref_columns_to_manufacturer.sql`              | Add `uex_company_id` (UNIQUE), `scwiki_uuid` (UNIQUE), `scwiki_code`, `industry`, `is_item_manufacturer`, `is_vehicle_manufacturer`, `uex_synced_at`, `scwiki_synced_at`, `*_deleted_at` to `manufacturer`.                                                                                                                                                                                                                                                                                              | Safe additive.                    |
| V108 | `create_material_external_alias.sql`                     | Create alias table + indexes + seed insert (§4.2 entries with `created_by = 'system'`).                                                                                                                                                                                                                                                                                                                                                                                                                  | Safe additive.                    |
| V109 | `create_uex_category.sql`                                | Create `uex_category` reference table.                                                                                                                                                                                                                                                                                                                                                                                                                                                                   | Safe additive.                    |
| V110 | `create_game_item.sql`                                   | Create `game_item` with `external_uuid` UNIQUE + `uex_item_id` UNIQUE + indexes on `kind`, `classification`, `manufacturer_id`, `uex_category_id`, `linked_ship_type_id`.                                                                                                                                                                                                                                                                                                                                | Safe additive.                    |
| V111 | `extend_ship_type_with_cross_ref_and_vehicle_fields.sql` | Add `external_uuid` (UNIQUE), `uex_vehicle_id` (UNIQUE), `uex_slug`, `scwiki_slug`, `name_full`, `game_name`, `class_name`, all 47 `is_*` flags, `mass`, dimensions, fuel, urls, descriptions, `*_synced_at`, `source_systems` to `ship_type`. Keep existing `description` column (synthesized) for back-compat.                                                                                                                                                                                         | Safe additive.                    |
| V112 | `backfill_ship_type_external_uuid_from_uex.sql`          | One-shot: cross-reference each `ship_type` row by `LOWER(name)` against the freshly synced UEX vehicle catalog and set `external_uuid` + `uex_vehicle_id` where unique. Logs `BACKFILL_AMBIGUOUS` events for duplicate names. **Runs after V111 + after the first UEX sync** — gated by a Flyway condition that the `uex_category` table has rows. (Alternative: ship as a Spring service called once on app start under a `krt.bootstrap.backfill-shiptype-uuid=true` flag, dropped after one release.) | Idempotent (no-op on second run). |
| V113 | `create_blueprint_tables.sql`                            | Create `blueprint`, `blueprint_ingredient`, `blueprint_dismantle_return` with CHECK constraints.                                                                                                                                                                                                                                                                                                                                                                                                         | Safe additive.                    |
| V114 | `create_game_item_price.sql`                             | Create `game_item_price` (feature-flagged sync writes to it).                                                                                                                                                                                                                                                                                                                                                                                                                                            | Safe additive.                    |
| V115 | `update_material_source_systems_for_isManualEntry.sql`   | One-shot: `UPDATE material SET source_systems = 'MANUAL' WHERE is_manual_entry = TRUE;`.                                                                                                                                                                                                                                                                                                                                                                                                                 | Idempotent.                       |
| V116 | *(reserved)*                                             | future cleanup — drop `material.is_manual_entry` and `ship_type.description` after one release soak. **Not in this plan's scope.**                                                                                                                                                                                                                                                                                                                                                                       | Destructive.                      |

All migrations follow `backend/src/main/resources/db/migration/README.md` conventions: two-phase for any destructive change; performance-aware (CREATE INDEX CONCURRENTLY is not Flyway-friendly, so we accept short locks during table creation).

---

## 8. Sync algorithms

### 8.1 Commodity sync (`ScWikiCommoditySyncService`)

```
fetched = scWikiClient.fetchAllPages(/api/commodities)
seenScwikiUuids = new HashSet<>()
for each wikiDto in fetched:
  if isJunk(wikiDto):                          // §3.4 #2 heuristic
    syncReport.logSkip("commodity-junk", wikiDto)
    continue
  seenScwikiUuids.add(wikiDto.uuid)
  material = findMaterialFor(wikiDto)          // §8.1.1
  if (material == null):
    material = new Material(name=wikiDto.name, sourceSystems=WIKI_ONLY)
  applyWikiFields(material, wikiDto)           // §4.5 conflict rules
  material.scwikiSyncedAt = now()
  if (material.sourceSystems == UEX_ONLY)
    material.sourceSystems = BOTH
  materialRepository.save(material)

// orphan handling — mark wiki-deleted, do NOT hard-delete
materialRepository.markScwikiDeleted(
    excludeIds=seenScwikiUuids,
    deletedAt=now())
```

#### 8.1.1 `findMaterialFor(dto)` resolution chain

```
1. byScwikiUuid(dto.uuid)          // strongest signal — set on previous sync
2. byAlias(SCWIKI, dto.name) → alias.material  // manual / seeded aliases
3. byName(dto.name)                // exact-name match → most UEX rows
4. byCanonicalName(canon(dto.name)) // strip qualifier words (raw|ore|refined|pure|r)
                                    //   AND canonical name not already matched in this run
                                    //   AND target material lacks scwikiUuid
5. byUexCommodityName matching      // a few well-known parenthetical variant flips
6. null  → caller creates new row
```

Step 4 also rejects multi-target ambiguity: if `canon(dto.name)` hits 2+ UEX rows, we **log a `MULTI_MATCH` warning and pick none**, deferring to the admin alias UI.

### 8.2 Blueprint sync (`ScWikiBlueprintSyncService`)

```
fetched = scWikiClient.fetchAllPages(/api/blueprints)
for each dto in fetched:
  bp = blueprintRepo.findByScwikiUuid(dto.uuid).orElseGet(Blueprint::new)
  bp.scwikiUuid = dto.uuid
  bp.scwikiKey = dto.key
  bp.outputName = dto.outputName
  bp.craftTimeSeconds = dto.craftTimeSeconds
  bp.isAvailableByDefault = dto.isAvailableByDefault
  bp.outputItem = resolveGameItem(dto.outputItemUuid, dto.outputName)  // §8.3
  // ingredients
  for each (i, ing) in dto.ingredients:
    line = bp.ingredients.size() > i ? bp.ingredients.get(i) : new BlueprintIngredient()
    line.blueprint = bp
    line.orderIndex = i
    line.kind = BlueprintIngredientKind.valueOf(ing.kind.toUpperCase())
    line.wikiResourceUuid = ing.resourceTypeUuid
    line.wikiItemUuid = ing.itemUuid
    line.wikiNameSnapshot = ing.name
    line.quantityScu = ing.quantityScu
    line.quantityUnits = ing.quantity
    if (line.kind == RESOURCE):
      line.material = resolveMaterialForResource(ing.resourceTypeUuid, ing.name)
      line.gameItem = null
    else:
      line.gameItem = resolveGameItem(ing.itemUuid, ing.name)
      line.material = null
  // drop trailing lines if upstream count shrank
  while bp.ingredients.size() > dto.ingredients.size():
    bp.ingredients.removeLast()
  // same for dismantle_returns
  bp.scwikiSyncedAt = now()
  blueprintRepo.save(bp)

blueprintRepo.markScwikiDeleted(excludeIds=seenScwikiUuids, deletedAt=now())
```

**Key choice:** the `BlueprintIngredient` list is owned by the blueprint with `orphanRemoval=true`. Concurrency-wise we follow the `…WithinTransaction` pattern from CLAUDE.md to avoid optimistic-lock races when the same blueprint is also being touched by a parallel sync. Since only one sync runs at a time (single-threaded executor), races are unlikely in practice, but we keep the pattern for robustness.

**Materialresolution:** ingredient names like "Agricium" come from `dto.ingredients[].name`. We resolve via:

```
materialRepository.findByScwikiUuid(ing.resourceTypeUuid)
  .or(() -> alias(SCWIKI, ing.name))
  .or(() -> materialRepository.findByName(ing.name))
  .or(() -> {
      syncReport.logUnresolved("blueprint-ingredient", dto.uuid, ing);
      return null;                              // ingredient line stored anyway via wiki_* snapshot
  });
```

We never lose data — an unresolved ingredient still persists its `wiki_resource_uuid` / `wiki_name_snapshot` so a later sync (after admin adds the alias) re-resolves transparently.

### 8.3 UEX item sync (`UexItemSyncService`) — runs first

UEX's `/items` requires `id_category` as a filter, so we iterate the 98-row `uex_category` reference table (which `UexCategoryRefService` populates as the first step of every run). For each category:

```
categories = uexCategoryRefService.syncAndReturn();    // 98 rows, hits /categories
seenExternalUuids = new HashSet<>()
seenUexItemIds    = new HashSet<>()
for each cat in categories where cat.is_game_related == 1:
  itemsForCategory = uexClient.fetchItems(cat.id)      // /items?id_category=<cat.id>
  for each dto in itemsForCategory:
    seenExternalUuids.add(dto.uuid)
    seenUexItemIds.add(dto.id)
    item = resolveGameItem(dto)                        // §8.3.1
    if (item == null):
      item = new GameItem();
      item.externalUuid = dto.uuid;
      item.sourceSystems = UEX_ONLY;
    applyUexFields(item, dto, cat)                     // writes uex_* cols only +
                                                       //   canonical name/manufacturer/kind
    item.uexSyncedAt = now();
    if (item.sourceSystems == WIKI_ONLY) item.sourceSystems = BOTH;
    gameItemRepo.save(item);

// orphan handling — wipe uex_deleted_at on rows seen, mark unseen
gameItemRepo.markUexDeletedExcept(seenUexItemIds, now());
```

#### 8.3.1 `resolveGameItem(uexDto)` resolution chain

```
1. byUexItemId(dto.id)                            // strongest signal — set on previous UEX sync
2. dto.uuid != null && byExternalUuid(dto.uuid)   // wiki sync may have written first
3. dto.uuid == null && bySlugMatch(dto.slug)      // ~46% direct + ~20% after normalization
4. byNameAndKind(dto.name, kindFromCategory(dto)) // legacy fallback; rare
5. null  → caller creates new row, source = UEX_ONLY
```

Step 2 is the joint key. If Wiki has already written a row with `external_uuid = dto.uuid`, this picks it up and merges in the UEX columns. After the run, `sourceSystems` flips from `WIKI_ONLY` to `BOTH`.

Step 3 covers the ~30% of UEX items where `dto.uuid == ""`. The Wiki HAS most of these — we just need to find them by `slug`. §3.6 measured ~46% resolve on a raw slug; normalization (next sub-section) pushes that to ~65-70%. If `bySlugMatch` succeeds, we **write the Wiki UUID into `game_item.external_uuid`** — UEX's empty UUID is replaced by Wiki's, and the next sync run can fast-path via step 2.

#### 8.3.2 Slug normalization rules

UEX slugs are sometimes malformed in ways the Wiki normalizes away. Probe-derived rules, applied in `bySlugMatch`:

```java
String normalizeSlug(String s) {
  if (s == null) return null;
  return s
    .toLowerCase(Locale.ROOT)
    .replaceAll("--+",       "-")        // wolf---laser-weapons-kit → wolf-laser-weapons-kit
    .replaceAll("^-+|-+$",   "")          // -venture-arms- → venture-arms
    .replaceAll("[^a-z0-9-]","-")         // strip stray punctuation (en-dashes, smart quotes)
    .replaceAll("--+",       "-");        // collapse runs again after the regex above
}
```

`bySlugMatch(uexSlug)` tries in order:

```
a. exact match: gameItemRepo.findByScwikiSlug(uexSlug)
b. normalized: gameItemRepo.findByScwikiSlug(normalizeSlug(uexSlug))
c. wiki probe: GET /api/items/{normalizeSlug(uexSlug)} → if 200, write the row, return it
```

Step c is a live API hit — only safe under the per-sync rate budget. Bounded at most by the count of empty-UUID UEX items in a sync (a few hundred), pacing 0.3 s each = ~2 min. Acceptable for an hourly schedule.

Slug-fallback also writes `external_uuid` from the Wiki response into the local row so the next sync run resolves via UUID (step 2) — fast path.

#### 8.3.3 Items genuinely missing from Wiki — `UEX_ONLY` forever

The 3% Wiki-404 rate for UEX items WITH UUIDs (§3.6) and the unrecoverable ~25-30% of empty-UUID items together produce a **stable ~12-15% of UEX rows with `source_systems = UEX_ONLY` and `scwiki_synced_at = NULL` forever**. Known examples:

- *Furniture / decorations* — wiki tracks ships and people, not couches: "High End Couch", "Low End Couch", "Salvaged Skull Couch", many other Decorations / Flair items.
- *Aurora Mk I sub-variants* — UEX tracks CL/ES/LN/LX/MR; Wiki currently only has the base "Aurora Mk I" and the post-Mk II variants. **Confirmed UEX-only.**
- *Idris-M, Polaris* — capital ships present in UEX but absent from the wiki's `/vehicles` (the wiki's RSI archive may have them under a different resource — out of scope here).
- *Action figures* — flair items the wiki community hasn't catalogued.
- *Certain liveries* — `Eclipse Ambush`, `Gladius Frostbite`, `Hammerhead Polar` — UEX-only.

This is **expected and acceptable.** Admin sync-report flags every such row with `CREATED_UEX_ONLY` at first sight, and the admin can manually clear the flag once they've confirmed it's not a wiki bug worth reporting upstream.

### 8.4 Wiki item sync (`ScWikiItemSyncService`)

Two modes selected by `krt.scwiki.sync-all-items`:

**Mode A — closure-only (default):**
- Pull every item uuid referenced as a blueprint `output_item_uuid` or `ingredient.itemUuid`.
- **Also** pull every uuid already present in `game_item` (i.e., everything UEX put there) — that way we always fill in Wiki columns for items the UEX side already knows about. This is the typical case: UEX → 5000 items → Wiki → fills in `classification`/`mass`/`descriptions` on each.
- Hit `GET /api/items/{uuid}` for each (no list-walk).
- Throttled to `requestsPerSecond` (5 = ~17 min for 5000 items, run once per Wiki cycle).
- A 404 on `/api/items/{uuid}` (with no 302 to /vehicles) means "wiki doesn't know this item" — log `WIKI_MISSING` event; the row stays UEX_ONLY.

**Mode B — full backfill (`syncAllItems=true`):**
- Iterate through 7 endpoints with the right `filter[classification]` per kind (see §13 open question on the exact filter values).
- Single sync run will take ~10–15 minutes against the 5 req/s pacer at page-size 200.
- Orphan handling: each kind tracks its own seen-uuid set; cross-kind we only soft-delete when **all** kinds have run successfully.

The Wiki sync calls `resolveGameItem(wikiDto)` with the same chain but kicking off from `byExternalUuid(dto.uuid)` first.

**Manufacturer resolution:**

```
manufacturerRepo.findByScwikiUuid(dto.manufacturer.uuid)
  .or(() -> manufacturerRepo.findByNameIgnoreCase(dto.manufacturer.name))
  .or(() -> {
      // create stub; ScWikiManufacturerSyncService backfills missing fields next cycle
      var m = new Manufacturer();
      m.setName(dto.manufacturer.name);
      m.setAbbreviation(dto.manufacturer.code != null ? dto.manufacturer.code : "UNKN");
      m.setScwikiUuid(dto.manufacturer.uuid);
      return manufacturerRepo.save(m);
  });
```

### 8.5 UEX vehicle sync (`UexVehicleService` — extended)

Today the service matches `ShipType.findByNameIgnoreCase(dto.name())`. Replace with a UUID-first chain and populate the rich fields:

```
for each dto in uexClient.getVehicles():
  st = shipTypeRepo.findByExternalUuid(dto.uuid)
    .or(() -> shipTypeRepo.findByUexVehicleId(dto.id))
    .or(() -> shipTypeRepo.findByNameIgnoreCase(dto.name))
    .orElseGet(() -> {
        var s = new ShipType();
        s.setName(dto.name);
        s.setExternalUuid(dto.uuid);                // critical: write the cross-ref key
        s.setSourceSystems(GameItemSourceSystem.UEX_ONLY);
        return s;
    });
  applyUexVehicleFields(st, dto);                   // 47 is_* flags + dims + fuel + urls
  st.setUexSyncedAt(now());
  st.setUexVehicleId(dto.id);
  if (st.getSourceSystems() == WIKI_ONLY) st.setSourceSystems(BOTH);
  shipTypeRepo.save(st);

shipTypeRepo.markUexDeletedExcept(seenUexVehicleIds, now());
```

The synthesized `description` column keeps being written by `updateShipType()` for back-compat — but new code reads `descriptionEn`/`descriptionDe` instead.

### 8.6 Wiki vehicle sync (`ScWikiVehicleSyncService`)

Iterates `/api/vehicles?page[size]=200`, paginating until last_page. Per vehicle:

```
for each dto in wikiClient.fetchAllVehicles():
  st = shipTypeRepo.findByExternalUuid(dto.uuid)
    .or(() -> shipTypeRepo.findByNameIgnoreCase(dto.name))
    .orElseGet(() -> {
        var s = new ShipType();
        s.setName(dto.name);
        s.setExternalUuid(dto.uuid);
        s.setSourceSystems(GameItemSourceSystem.WIKI_ONLY);
        return s;
    });
  applyWikiVehicleFields(st, dto);                  // cargo_grids→vehicleInventoryScu,
                                                    //   sizes, mass, descriptions(EN/DE),
                                                    //   game_name, class_name, port_tags
  st.setScwikiSyncedAt(now());
  st.setScwikiSlug(dto.slug);
  if (st.getSourceSystems() == UEX_ONLY) st.setSourceSystems(BOTH);
  shipTypeRepo.save(st);

shipTypeRepo.markScwikiDeletedExcept(seenExternalUuids, now());
```

Same conflict-resolution rules as `game_item` (§6.3.3) — `name` prefers Wiki, `manufacturer` is sticky on UEX, `is_*` flags only UEX writes (Wiki doesn't expose them).

### 8.7 Orphan handling

Every sync service tracks `seenScwikiUuids` (Wiki side) or `seenUexItemIds` / `seenUexVehicleIds` / `seenExternalUuids` (UEX side) for its aggregate. After all pages are processed successfully, the service issues:

```java
@Modifying
@Query("UPDATE Material m SET m.scwikiDeletedAt = :now " +
       "WHERE m.scwikiUuid IS NOT NULL AND m.scwikiUuid NOT IN :seenIds " +
       "AND m.scwikiDeletedAt IS NULL")
int markScwikiDeleted(@Param("seenIds") Collection<UUID> seenIds, @Param("now") Instant now);
```

If `seenIds.isEmpty()` (full-fetch failure / empty response from upstream), **skip the markDeleted call** — same gate as `MaterialPriceRepository.clearStalePrices`. Prevents wiping the catalog when the wiki has an outage.

A subsequent sync run that sees the row again clears `scwikiDeletedAt` and refreshes the data. Surface this status in the admin UI as "missing from wiki since YYYY-MM-DD".

### 8.8 Sync report (`SyncReportService` — single table, two sources)

Persist sync findings to one `external_sync_report` table covering both UEX and Wiki events:

```sql
CREATE TABLE external_sync_report (
  id              UUID PRIMARY KEY,
  run_id          UUID NOT NULL,                 -- groups events of one sync cycle
  ran_at          TIMESTAMPTZ NOT NULL,
  source_system   VARCHAR(16) NOT NULL,           -- 'UEX' | 'SCWIKI'
  event_type      VARCHAR(64) NOT NULL,           -- see below
  aggregate       VARCHAR(64) NOT NULL,            -- 'commodity', 'blueprint', 'game_item', 'ship_type'
  external_uuid   UUID,                            -- when applicable
  external_id     INTEGER,                         -- UEX integer id when applicable
  external_name   VARCHAR(255),
  detail          TEXT
);
CREATE INDEX idx_external_sync_report_run ON external_sync_report(run_id);
CREATE INDEX idx_external_sync_report_source ON external_sync_report(source_system, ran_at DESC);
```

Event-type catalogue (one row each, used by both sides):

|       Event type        |                                                                Emitted by                                                                |
|-------------------------|------------------------------------------------------------------------------------------------------------------------------------------|
| `SKIP_JUNK`             | Wiki commodity sync (hard junk filter — see §8.9)                                                                                        |
| `CREATED_WIKI_ONLY`     | Wiki commodity sync (no UEX match; inserted with `is_visible=false`)                                                                     |
| `CREATED_UEX_ONLY`      | UEX item sync (no Wiki cross-ref)                                                                                                        |
| `LOOKS_LIKE_ITEM`       | Wiki commodity sync — name matches the §4.3 "items in commodity pool" list (Ace Helmet, MedGel, etc.). Inserted with `is_visible=false`. |
| `LINKED_VIA_ALIAS`      | Wiki commodity sync (used alias table)                                                                                                   |
| `LINKED_VIA_UUID`       | Wiki item sync (joined an existing UEX row)                                                                                              |
| `MULTI_MATCH_AMBIGUOUS` | Wiki commodity sync (canonical form hit > 1 UEX row)                                                                                     |
| `UNRESOLVED_INGREDIENT` | Wiki blueprint sync (ingredient resource/item not found)                                                                                 |
| `MANUFACTURER_MISMATCH` | Both — UEX and Wiki disagree on manufacturer for same UUID                                                                               |
| `SYNC_RACE_CONFLICT`    | Both — optimistic-lock retry exhausted                                                                                                   |
| `WIKI_MISSING`          | Wiki item sync — UUID present in UEX, absent on wiki                                                                                     |
| `BACKFILL_AMBIGUOUS`    | V112 backfill — `ship_type.name` matched > 1 UEX vehicle                                                                                 |

Retention: keep the last 30 runs. Admin pages:
- `/admin/sync-reports/scwiki` — Wiki events only
- `/admin/sync-reports/uex` — UEX events only
- `/admin/sync-reports` — combined view

Per-row "Add alias" link drops the admin on a pre-filled alias form (commodity events only — item/vehicle events are UUID-keyed and don't need aliases).

### 8.9 Junk filter heuristic — concrete

The verification in §4.3 found that the original flag-based heuristic (`kind==""` etc.) was unreliable — it dropped real harvestables like `Uncut SLAM`, `Blue Bilva`, `Molina Mold`, `Oza` that have no flags but are real game commodities. The revised filter is **purely name-pattern based** for hard drops; everything else is imported with `is_visible=false` for admin review.

```java
boolean isCommodityHardJunk(ScWikiCommodityDto dto) {
  if (dto.name() == null || dto.name().isBlank()) return true;
  String n = dto.name();
  if (n.contains("<") || n.contains(">"))   return true;     // HTML / placeholder
  if (n.startsWith("<="))                    return true;
  if (n.contains("_"))                       return true;     // raw asset name (Vlk_Fang)
  if (n.endsWith(":"))                       return true;     // "Power:"
  if (n.startsWith("Ship Ammunition"))       return true;     // "Ship Ammunition - Size 8/9"
  if (HARDCODED_ATMOSPHERE_SET.contains(n))  return true;     // Heat / Oxygen / Cooler / etc.
  return false;
}

// Maintained constant. Add via PR review, not at runtime.
// Verification (§4.3) confirmed these are atmospheric / environment-system entries,
// not tradeable commodities. They produced empty descriptions or single-word stubs.
private static final Set<String> HARDCODED_ATMOSPHERE_SET = Set.of(
    "Cooler", "Heat", "Oxygen", "Life Support", "EVA Fuel", "Mixed Mining");
```

**Note: `Uncut SLAM`, `Virus Cultures`, `Ace Interceptor Helmet`, `MedGel`, `HLX99 Hyperprocessors`, `mobyGlass Personal Computers`, `RS1 Odysey Spacesuits` are NO LONGER hard-junked.** Per §4.3 verification, the first two are likely real commodities (drug + virus); the last five are most likely items, not commodities, but the filter can't tell automatically. **All seven are imported with `is_visible=false` and emit a `LOOKS_LIKE_ITEM` event** so the admin can review.

Audit-log every skip so the curated list can grow as new junk variants appear. The same admin UI surface offers an "Unsuppress" toggle if we ever decide one of these *is* a real commodity.

---

## 9. Alias / fuzzy-match strategy

Three resolution layers, in order of precedence:

1. **Direct external ID** (`scwiki_uuid` on `material`).
2. **Alias table** (`material_external_alias`) — both seed and admin-curated.
3. **Algorithmic match**:
   a. exact-name (case-insensitive, ASCII fold)
   b. canonical-form (strip qualifier words `raw|ore|refined|pure|r`, parenthetical suffix, leading/trailing whitespace)
   c. multi-target rejection — if more than one row matches, log `MULTI_MATCH_AMBIGUOUS` and do not auto-link.

The seed insert (V108) pre-populates the §4.2 manual aliases. New aliases are added via:

- The admin sync-report page when a `MULTI_MATCH_AMBIGUOUS` or `UNRESOLVED_INGREDIENT` event surfaces.
- A standalone admin form `/admin/material-aliases` (CRUD on `material_external_alias`).

Once an alias is added, the **next sync run** picks it up — no immediate re-run is forced. Admin can click "Re-run sync now" on the report page if they want immediate effect.

---

## 10. Frontend / admin UI

Minimum viable surface for this plan:

- **`/admin/sync-reports/scwiki`** — paginated list of sync runs with event counts; click into a run to see all events; per-event "Add alias" / "Suppress" actions.
- **`/admin/material-aliases`** — CRUD on `material_external_alias`. Filter by source system + material.
- **Material edit page** — extend the existing admin material editor to expose `scwiki_uuid`, `scwiki_key`, `scwiki_slug`, `source_systems`, and a read-only "Wiki link" pointing at `https://api.star-citizen.wiki/commodities/<slug>`.

Blueprint / game-item listing pages are deferred (§2.2). The data lands in the schema; consuming UIs are separate features driven by user need.

i18n keys go under `admin.scwiki.*` (`messages_en.properties` + `messages_de.properties`). German umlauts encoded as `\uXXXX` per CLAUDE.md.

---

## 11. Rollout phases

The same release pattern as the SK rollout (small slices, each independently mergeable + reversible). The UEX side ships **before** the Wiki side for each joint table — that way the canonical `external_uuid` is already populated when Wiki sync runs, and Wiki sync is a pure column-fill.

### R1 — Foundation (one PR, additive only)

- `ScWikiProperties`, `ScWikiClient` (with one-page-only mode), shared `SyncReportService`.
- New `UexProperties` keys for `itemsEndpoint`, `itemsPricesEndpoint`, `categoriesEndpoint`.
- Migration **V106** (material columns) + **V107** (manufacturer columns) + **V108** (alias + seed) + **V109** (uex_category).
- `material_external_alias` admin CRUD.
- Skeleton `ScWikiScheduler` with `schedulerEnabled = false` default — does nothing until R3 hooks in services.
- ArchUnit rule: any class in `integration/scwiki` must inject `ScWikiClient` (mirror of the existing UEX rule).
- Tests: WireMock-based unit tests for `ScWikiClient` pagination + ETag.

### R2 — UEX item catalogue + UEX manufacturer/vehicle hardening

- Migration **V110** (game_item) + **V111** (ship_type extensions) + **V112** (backfill `external_uuid` on existing ship_type rows).
- `UexCategoryRefService` populates `uex_category`.
- `UexItemSyncService` walks the 98 categories, writes `game_item` (UEX_ONLY).
- `UexCompanyDto`-driven `UexManufacturerService` extended to write `uex_company_id` + `industry` + `is_item_manufacturer` / `is_vehicle_manufacturer` columns.
- `UexVehicleService` hardened to UUID-match (§8.5); fills all 47 `is_*` flags + dims + fuel + urls on `ship_type`.
- **No Wiki side touched yet** — but Wiki side is *unblocked* because `external_uuid` is now populated.
- Tests: WireMock fixture of one helmet category response; verify `game_item` row carries the right `kind`, `uex_*` cols, and `manufacturer` FK; ArchUnit pins that `UexItemSyncService` writes only `uex_*` and canonical cols (never `scwiki_*`).

### R3 — Wiki commodity merge

- `ScWikiCommoditySyncService` with junk filter, fuzzy + alias resolution.
- Schedule it weekly first (`scheduler-delay = 604800000`) on a feature flag (`krt.scwiki.commodity-sync-enabled`).
- Backfill in test: assert the §4.1 fuzzy seed produces 4 matches and the §4.2 manual seed produces 5 matches (Construction Pieces excluded until user confirms domain mapping).
- Sync-report page lists `MULTI_MATCH_AMBIGUOUS` and `CREATED_WIKI_ONLY` events.
- Admin review window: 1 week of weekly sync, escalate frequency to 24 h once report is clean.

### R4 — Wiki items merge (closure mode) + Wiki vehicles

- Migration **V113** (blueprint tables).
- `ScWikiItemSyncService` in **closure mode** — fills Wiki columns on every row UEX already put in `game_item`, plus the items referenced by blueprints. Mostly Wiki-fills, very few new rows expected.
- `ScWikiVehicleSyncService` writes Wiki columns on every `ship_type` row UEX already put there, picking up `descriptionEn` / `descriptionDe` / `game_name` / `cargo_grids` / dimensions.
- `ScWikiBlueprintSyncService` writes the recipe graph; ingredient resolution uses the shared `external_uuid` on `game_item`.
- Tests: WireMock fixture of one blueprint with both `RESOURCE` and `ITEM` ingredients; verify the FKs land correctly and CHECK constraints fire when violated. Verify that a row with both `uex_synced_at` and `scwiki_synced_at` ends up with `source_systems = BOTH`.

### R5 — Full Wiki item backfill (feature-flagged)

- Enable `syncAllItems = true` on a single environment first.
- Run once, measure runtime + DB growth, then promote.
- Per-classification probing to discover the right `filter[type]` / `filter[classification]` values for `/armor`, `/clothes`, `/food` (see §13).
- Expected result: ~12 700 Wiki items, of which ~5000 already exist (UEX seeded them), ~7000 new rows with `source_systems = WIKI_ONLY` (paints, variant skins UEX doesn't track).

### R6 — Manufacturer Wiki reconciliation

- `ScWikiManufacturerSyncService` populates `scwiki_uuid` / `scwiki_code` on rows where UEX has set `uex_company_id`.
- Same pattern, much smaller scale (~50 manufacturers).
- No new entities, no new tables — just the V107 columns from R1 finally getting Wiki-side writes.

### R7 — UEX item prices (feature-flagged) + game_item_price

- Migration **V114** (game_item_price table).
- `UexItemPriceSyncService` walks `/items_prices_all`, joins by `id_item → game_item.uex_item_id → game_item.id` and `id_terminal → terminal.id`.
- Same orphan handling as `material_price.clearStalePrices` (gated on non-empty seen-id set).
- Default off (`krt.uex.item-price-sync-enabled=false`). Enable once a UI surface needs the data.

### R8 — Soak / observability + V115 cleanup

- Hourly UEX schedule, 24h Wiki schedule, all services on.
- Monitor: log-volume baseline for the new service, DB growth on `game_item` + `blueprint_ingredient`, sync-report event mix.
- After two weeks clean: ship **V115** (`is_manual_entry → source_systems = 'MANUAL'`) and start the soak window for **V116** drop of `material.is_manual_entry` + `ship_type.description` synthesized column.

### R9 — V125 destructive cleanup *(separate track — see [`SC_WIKI_SYNC_DESTRUCTIVE_ROADMAP.md`](SC_WIKI_SYNC_DESTRUCTIVE_ROADMAP.md))*

- Drop `material.is_manual_entry` and `ship_type.description`.
- Out of scope of this plan; tracked in `SC_WIKI_SYNC_DESTRUCTIVE_ROADMAP.md`, which stages the reader migrations (`is_manual_entry` → `source_systems = 'MANUAL'`; `ship_type.description` → `description_en` / `description_de`, resolving §13 #9 — both are still UI-consumed) ahead of the irreversible drop, with a soak between, gated on a clean R8 soak.
- V-number drift: V115 / V116 went to R7 (`game_item_price`) / R8 (`is_manual_entry` backfill), and V117–V124 were claimed by features merged to `main` while this PR was open, so the destructive drop is **V125** (the draft §7 table called it V116).

---

## 12. Tests

Per CLAUDE.md "every new feature ships with tests". Concrete test plan:

|    Layer    |                                Test                                |                                                                                                                                                                    What it pins                                                                                                                                                                    |
|-------------|--------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Unit        | `ScWikiClientTest` (WireMock)                                      | pagination loop, ETag short-circuit, 304 handling, rate-limit pacing, empty-response idempotence                                                                                                                                                                                                                                                   |
| Unit        | `ScWikiCommoditySyncServiceTest` (Mockito)                         | junk filter, scwiki_uuid match, alias match, name match, canonical-form match, multi-match rejection, orphan marking gated on non-empty seen set                                                                                                                                                                                                   |
| Unit        | `ScWikiBlueprintSyncServiceTest`                                   | ingredient kind discriminator, RESOURCE FK to material, ITEM FK to game_item, CHECK violation propagates, shrinking ingredient count drops trailing lines                                                                                                                                                                                          |
| Unit        | `ScWikiAliasServiceTest`                                           | seed insert produces expected matches, admin-added alias picked up at next run                                                                                                                                                                                                                                                                     |
| Unit        | `ScWikiItemSyncServiceClosureTest`                                 | closure-mode pulls only items already in `game_item` + blueprint refs, never enumerates full /items list                                                                                                                                                                                                                                           |
| Unit        | `UexItemSyncServiceTest` (WireMock)                                | walks 98 categories, kind derivation per section, `external_uuid` set, `source_systems` flips UEX_ONLY→BOTH when row already has `scwiki_synced_at`                                                                                                                                                                                                |
| Unit        | `UexCategoryRefServiceTest`                                        | reference table populates with all 98 rows, idempotent on re-run                                                                                                                                                                                                                                                                                   |
| Unit        | `UexVehicleServiceUuidMatchTest`                                   | UUID-first chain: uuid → uex_vehicle_id → name; legacy name-only rows get backfilled with `external_uuid`                                                                                                                                                                                                                                          |
| Unit        | `ScWikiVehicleSyncServiceTest`                                     | wiki row enters joint `ship_type` row via `external_uuid` match; UEX `is_*` flags untouched; descriptions filled                                                                                                                                                                                                                                   |
| Unit        | `GameItemConflictResolutionTest`                                   | name prefers Wiki (longer, properly-cased); manufacturer is sticky on UEX; kind tie-breaker (more-specific wins)                                                                                                                                                                                                                                   |
| Integration | `UexItemSyncIntegrationTest` (`@SpringBootTest` with WireMock)     | full UEX item run against a fixture of 3 categories; end-state DB matches expected snapshot                                                                                                                                                                                                                                                        |
| Integration | `ScWikiSchedulerIntegrationTest` (`@SpringBootTest` with WireMock) | full happy-path cycle: commodities → blueprints → items closure → vehicles, end-state DB matches expected snapshot, including UEX_ONLY→BOTH flips                                                                                                                                                                                                  |
| ArchUnit    | extend `ArchitectureTest`                                          | classes in `integration.scwiki` must inject `ScWikiClient`; new `BlueprintIngredient` is not a JPA entity exposed by a controller (DTO-only); `GameItem` table not directly returned by `@RestController`; `UexItemSyncService` writes only `uex_*` cols + canonical (`name`, `manufacturer`, `kind`) — verified via field-write set in unit tests |
| Migration   | `V106Test` ...                                                     | each migration ports a frozen DB snapshot and verifies the schema delta                                                                                                                                                                                                                                                                            |

All tests run via `./gradlew :backend:test` per CLAUDE.md hard rule. No mocked DB — every integration test uses a real Postgres via the existing TestContainers config.

---

## 13. Open questions

These need user input before R3 / R4 work begins. R1 + R2 can ship without resolving them.

1. **Construction-material grade mapping** (§4.2). The wiki and UEX disagree on size names (`Scrap/Chunks/Powder` vs `Pebbles/Rubble/Salvage`). Best guess in §4.2; need confirmation from someone who's mined these in-game. If wrong, we collapse two physically distinct grades into one row. **Risk: medium.** Mitigation: hold the Construction-* alias entries out of the V108 seed until confirmed; admin can add them later.
2. **UEX `Lastaphrene` typo** (§4.2). The wiki name `Lastaprene` is plausibly the correct in-game spelling. Confirm with user, then either alias-in or open a ticket with the UEX team.
3. **`filter[classification]` values per `/armor`, `/clothes`, `/food`** (§3.4 #1). The OpenAPI lists the filter but not its enum values. The `/api/items/filters` endpoint exposes them at runtime — we'll probe it from a one-shot test during R4, but we want a quick "this is the expected list" sanity check from the user once we have it.
   - **RESOLVED (R5), live probe of `GET /api/items/filters` on game 4.8.0:** `filter[classification]` does **prefix matching** and is honoured by the resource endpoints too. Only `/api/armor` actually returns the full ~12 700-row pool with no filter — `/clothes` (1 826), `/food` (221), `/weapons` (391), `/weapon-attachments` (104), `/vehicle-items` (3 211, incl. 947 paints), `/vehicle-weapons` (168) all return their kind natively. The clean classification prefixes are `FPS.Armor` (2 318), `FPS.Clothing` (1 826), `FPS.Consumable.Food` (221), `FPS.WeaponAttachment`, `FPS.Weapon`, `Ship.Weapon`. R5 ships these as configurable `krt.scwiki.*-filter` properties (only `armor-filter=FPS.Armor` is mandatory; the rest default to the prefix or blank) plus a `backfill-kind-sanity-cap` guard against a future regression of the quirk.
4. **`description` JSON / multi-language storage** (§4.5 row 5). Wiki returns rich multi-language `description{en_EN,de_DE,zh_CN,...}`. We've planned `descriptionEn` + `descriptionDe` columns on `GameItem` and `ShipType`. For `Material.description`, should the wiki write into a new `description_de` column (parallel to existing `description`), or into a JSON `descriptions_by_locale` column? **Recommendation:** add `description_en`, `description_de` columns alongside the existing `description` (UEX-fed). Cheaper to query and matches the existing flat-column style.
5. **Schedule frequency for the wiki sync.** UEX runs hourly. Wiki data changes much more slowly (every game patch — every 2-6 weeks). Recommendation: every 24 h once stable; hourly during R3/R4 for fast iteration. User to confirm.
6. **Game-version handling.** Always follow upstream default, or pin to a specific code (`4.8.0-LIVE.11875683`)? Pinning means we miss new commodities until we bump the property; following means a patch can introduce unexpected entries. **Recommendation:** follow default; admin sync-report flags new entries for review.
7. **Should `GameItem` participate in OrgUnit-scoped queries?** It's a static catalog (like `ShipType`, `Material`); no, it should not. Confirm.
8. **`UexItemPriceSyncService` cost.** UEX `/items_prices_all` is large (estimated > 1 MB, possibly several MB once the catalog grows). The existing 16 MB WebClient buffer is sized for it but we should re-check after first probe. Also: is there a use case for surfacing item retail prices to users today? If not, R7 stays gated.
9. **`ship_type.description` deprecation timeline.** The current `description` is a synthesized multi-line text built from `nameFull / scu / crew / urlWiki|urlStore`. After R2 ships rich columns, downstream UI must migrate to `descriptionEn`/`descriptionDe`/explicit fields. Is there a hangar / fleet UI page that depends on the synthesized format today? If yes, we keep it longer than one release; if no, we can ship V116 in the soak window after R8.
10. **Vehicle `external_uuid` backfill on existing rows** (V112). The current `ship_type` table has rows like "Aegis Avenger Titan" matched by name to UEX vehicle id 5. We need to map them to the UUID UEX returns now. Best done by re-running `UexVehicleService` once with the new code (it will UUID-stamp every row it can match by name). If a row's name doesn't match any UEX vehicle (e.g., an admin-created entry), it stays UUID-less and `source_systems = MANUAL`. Confirm there are no such admin-only rows that would lose their tracking.
11. **UEX item ↔ vehicle linkage**. UEX items carry `id_vehicle` for paints/components specific to a vehicle (e.g., "100i Auspicious Red Dog Livery" has `id_vehicle = 1`). We've planned a `linked_ship_type_id` FK on `game_item`. Resolution chain: `shipTypeRepo.findByUexVehicleId(dto.id_vehicle)`. What's the expected use case? The data is there but no UI currently consumes it. Defer the FK if not needed — but the migration is cheap and we'd rather not need a V117 for it.

---

## 14. Risks

|                                                   Risk                                                    |    Likelihood    | Impact |                                                                                                                                    Mitigation                                                                                                                                    |
|-----------------------------------------------------------------------------------------------------------|:----------------:|:------:|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Wiki API breaking-change between OpenAPI v3 → v4                                                          |        M         |   M    | URL pinned to `/api/openapi` (versioned via `openapi.info.version`); ETag-cached responses fall back to empty list on 4xx; per-service exception swallow keeps UEX sync alive.                                                                                                   |
| UEX `/items` endpoint schema drift                                                                        |        L         |   M    | DTO has `@JsonIgnoreProperties(ignoreUnknown = true)` (standard for UEX DTOs); existing UEX commodity sync has survived several UEX field renames the same way.                                                                                                                  |
| False-positive name match collapses two distinct materials                                                |        L         |   H    | Step 4 of resolution chain rejects multi-match; admin gets `MULTI_MATCH_AMBIGUOUS` event with both candidates. Hold Construction-* aliases until confirmed.                                                                                                                      |
| UUID **not** identical between UEX and Wiki for a given asset                                             |        L         |   H    | The 241-test cross-ref probe (§3.6) found 0 UUID conflicts. The resolution chain still defends via `byName` fallback and a new `UUID_DRIFT` event so a future divergence is logged.                                                                                              |
| ~30% of UEX items / vehicles ship with `uuid=""`                                                          | **H** (measured) |   M    | **Slug-fallback** (§8.3.2) with normalization rules recovers ~65-70% of these. The remaining ~12-15% rows stay `UEX_ONLY` — admin sync-report makes them visible (`CREATED_UEX_ONLY` event); admin can mark them as "known UEX-only" to silence future reports.                  |
| Slug-fallback live wiki probes blow the sync budget                                                       |        M         |   L    | Slug probes pace at 0.3 s/req. Bounded by empty-UUID UEX row count (~300-1000); worst case 5 min per sync run. If volumes grow, switch step c of `bySlugMatch` to "queue for next-cycle resolution" instead of inline.                                                           |
| Junk filter drops a real new commodity                                                                    |        L         |   M    | Every skip is logged + persisted to `external_sync_report`; admin reviews. Suppression is editable post-hoc.                                                                                                                                                                     |
| `GameItem` table growth (~12 700 Wiki + ~5000 UEX = ~13 000 distinct rows × any future variant explosion) |        M         |   L    | Single table with indexes on `kind`, `classification`, `manufacturer_id`, `uex_category_id`, `external_uuid`. Postgres handles 100 K rows trivially.                                                                                                                             |
| Wiki rate-limit hit during full backfill                                                                  |        M         |   L    | `requestsPerSecond` config, default 5; exponential backoff on 429 (mirror UEX backoff).                                                                                                                                                                                          |
| UEX rate-limit hit during /items walk                                                                     |        M         |   L    | UEX has no advertised rate limit. We pace at 5 req/s to be safe — 98 calls in ~20 s.                                                                                                                                                                                             |
| Soft-delete (`scwiki_deleted_at` / `uex_deleted_at`) bloats catalog                                       |        L         |   L    | Admin UI lets you hard-delete a row that's been **both**-sources-deleted for >30 days. Rows with only one side deleted stay (other side still owns them).                                                                                                                        |
| Concurrent sync hits optimistic-lock 409 on a joint row                                                   |        M         |   M    | Separate `@Async` executors with queue size 0 prevent within-source concurrency. Cross-source UEX-vs-Wiki on same row: handled by retry-once-then-log path; in practice rare because UEX runs hourly and Wiki runs every 24 h, rarely overlapping on a single row's millisecond. |
| Wiki UUID changes for a row                                                                               |        L         |   M    | Resolution chain step 2 (alias by name) catches; we also keep `wiki_name_snapshot` on `BlueprintIngredient` for forensic re-resolution. The joint `external_uuid` UNIQUE constraint catches a stale write before it can split a row.                                             |
| V112 backfill matches a `ship_type.name` to the wrong UEX vehicle                                         |        L         |   M    | `BACKFILL_AMBIGUOUS` event is emitted when name matches > 1 UEX vehicle; ambiguous rows stay UUID-less. Admin reviews and links manually via the admin UI.                                                                                                                       |
| UEX adds a new category mid-soak that we don't recognize                                                  |        L         |   L    | `UexCategoryRefService` auto-syncs the 98 → N rows; `UexItemSyncService` iterates whatever is in `uex_category` so it picks up new categories automatically. Kind derivation (§6.3.1) has a default `GENERIC` for unknown sections.                                              |

---

## 15. References

- `SPEZIALKOMMANDO_PLAN.md` — pattern reference for phased rollout + migration numbering.
- `R8_DESTRUCTIVE_ROADMAP.md` — pattern for two-phase destructive migration (relevant for the V116 future drop of `is_manual_entry` + `ship_type.description`).
- `backend/src/main/resources/db/migration/README.md` — migration conventions.
- `backend/src/main/java/de/greluc/krt/iri/basetool/backend/integration/UexClient.java` — pattern source.
- `backend/src/main/java/de/greluc/krt/iri/basetool/backend/service/UexVehicleService.java:65-95` — the name-match upsert that R2 hardens to UUID-match.
- `CLAUDE.md` §Concurrency — `…WithinTransaction` pattern.
- OpenAPI specs:
  - SC Wiki: `https://api.star-citizen.wiki/api/openapi`
  - UEX: `https://uexcorp.space/api/documentation/`
- Live probe artifacts (local-only, not committed; in `/tmp/sc-sync-research/`):
  - Wiki: `openapi.yaml`, `wiki-commodities-{1,2}.json`, `wiki-blueprints-list.json`, `wiki-commodity-agricium.json`, `wiki-item-detail.json`, `wiki-vehicle-100i.json`, `wiki-vehicles-sample.json`
  - UEX: `uex-commodities.json`, `uex-commodities-prices-all.json`, `uex-categories.json`, `uex-companies.json`, `uex-items-helmets.json`, `uex-vehicles.json`, `uex-vehicles-prices.json`
  - 17 UEX categories: `uex-by-cat/cat-{1,3,5,8,11,14,17,19,20,24,25,32,73,75,82,86,107}.json`
- Cross-ref verification (§3.6) artifacts:
  - `xref-sample-uuid.csv` + `xref-results-v2.tsv` — 130 items with non-empty UEX uuid → 0 UUID mismatches
  - `xref-sample-noUuid-clean.csv` + `xref-results-noUuid-v2.tsv` — 68 items with empty UEX uuid → 46% direct slug resolve
  - `vehicles-sample-clean.csv` + `vehicle-results.tsv` — 172 vehicles → 0 UUID mismatches, 31% empty UEX uuid
- Commodity merge verification (§4.1 / §4.2 / §4.3 / §4.4 / §4.5) artifacts:
  - `commodity-pairs.csv` — 165 exact-name pairs with UEX vs Wiki field-by-field comparison; 0 real data conflicts (only semantic `is_refined` vs `is_mineable` differences)
  - `detail-wiki/raw-silicon.json`, `stileron-ore.json`, `raw-ouratite.json`, `hephaestanite-r-2.json` — §4.1 fuzzy verification (all 4 confirmed raw mineable materials)
  - `detail-wiki/lastaprene.json`, `lunes-spiral-fruit.json` — §4.2 manual alias verification (both confirmed)
  - `detail-wiki/construction-pieces.json` / `-rubble.json` / `-salvage.json` / `construction-materials.json` — §4.2.1 ambiguous-construction verification (held out of seed)
  - `detail-wiki/wo-junk-*.json` (15 files) + `wo-real-*.json` (16 files) — §4.3 wiki-only classification (flag-based heuristic proved unreliable; revised to name-pattern-only hard junk)
  - `detail-wiki/uo-*.json` (20 files) — §4.4 UEX-only verification via wiki search (18/20 confirmed empty; 1 false hit on PLACEHOLDER; 1 partial match on Jaclium that resolves as catalog-granularity-difference)

