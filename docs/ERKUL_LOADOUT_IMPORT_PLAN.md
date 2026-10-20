# Erkul loadout import — research notes and plan

Doc type: **living plan** (draft, research done 2026-10-20, not approved by @greluc, nothing
implemented).

**Status: parked.** Continue once the new P4K extractor (`D:\Coding\krt\p4k-extractor`) delivers
its new output format. The work below depends on the extractor exporting the ship hardpoint tree and
the default loadouts (see [What the new P4K export must deliver](#what-the-new-p4k-export-must-deliver)).

**Goal:** a member can store the loadout (fitted components) of a ship in their hangar, and can fill
it by pasting an [erkul.games](https://erkul.games) share link (`https://erkul.games/s/<id>`)
instead of entering every slot by hand.

## 1. How an erkul share link works (reverse-engineered, undocumented)

Erkul ("#DPSCalculator") is an Angular SPA. There is **no public, documented API**. Everything
below was read from its shipped JavaScript (`main-*.js`, route chunk `ShareResolvePage`) on
2026-10-20, data version `4.10.2-LIVE.12913470`. It can change without notice.

### 1.1 Resolving a link

- The page route `s/:id` resolves the link client-side. The id matches `^[a-z0-9]{8}$`.
- It calls **`GET https://api.erkul.games/shares/{id}`**. No authentication, no cookie, no token.
  - `200`: body is `application/octet-stream`, the JSON document compressed with **raw DEFLATE**
    (`DecompressionStream("deflate-raw")`, i.e. `java.util.zip.Inflater(true)` in Java).
  - `404`: the share does not exist.
- The decoded document validates against this envelope: `{ id, createdAt, payload }`.
- After resolving, the SPA restores the loadout into its local state and navigates to
  `/calculator`. That is why the browser ends up on another page; the URL itself carries no data.

Creating a share (`POST https://api.erkul.games/shares`, deflated `{ payload, turnstileToken? }`)
carries a Cloudflare Turnstile token. **We only ever read shares; we never create them.**

Other base URLs in the bundle: `https://cdn.erkul.games` (game data), `https://legacy.erkul.games`.

### 1.2 Payload schema (zod schema in erkul's chunk, transcribed)

```text
payload = {
  schemaVersion: 1                     (literal; reject anything else)
  v:        string  /^(?:(LIVE|PTU)@[0-9a-f]{8}|.+-(LIVE|PTU)\.\d+)$/   e.g. "4.10.2-LIVE.12913470"
  branch:   "LIVE" | "PTU"
  ship:     string  (lower-case game class name, e.g. "drak_ironclad")
  shipMode: "SCM" | "NAV"
  gimbalMode?, beamSustainMode?: "aggressive"|"sustained", chargeFireMode?: "tap"|"hold"
  slotOverrides?: { [slotPath: string]: SlotOverride }
  crafting?:      { [slotPath: string]: int[0..1000][] (min 1) }     crafted-quality values
  overridesByMode?: { SCM?: PowerOverrides, NAV?: PowerOverrides }    power allocation, UI-only
}

SlotOverride = { kind: K, className: string(min 1) }
             | { kind: "empty" }
             | { kind: "fixed", size?: int }
K = weapon | mount | rack | missile | bomb | shield | powerplant | cooler | radar | paint
  | quantumdrive | jumpdrive | blade | utility | module
```

Key facts for an importer:

- **Only deviations from the ship's default loadout are stored.** A stock ship has no
  `slotOverrides` at all. A complete loadout = the ship's default loadout + these overrides.
- `slotPath` is the **hardpoint path** in the ship's item-port tree, `/`-separated for nested
  ports (turret → sub-turret → weapon port).
- `className` is the **game's entity class name, lower-cased** (the P4K DataCore record name).
- `kind: "empty"` = the slot was deliberately emptied. `kind: "fixed"` = a fixed mount, optional size.
- `overridesByMode`, `gimbalMode`, `beamSustainMode`, `chargeFireMode` are calculator settings,
  not equipment. Probably not worth storing.

### 1.3 Sample 1 — stock ship (`https://erkul.games/s/dhw4rdy7`)

```json
{"id":"dhw4rdy7","createdAt":"2026-10-20T05:47:14Z",
 "payload":{"schemaVersion":1,"v":"4.10.2-LIVE.12913470","branch":"LIVE",
  "ship":"drak_ironclad","shipMode":"SCM","gimbalMode":"lock",
  "beamSustainMode":"aggressive","chargeFireMode":"hold"}}
```

### 1.4 Sample 2 — every component type changed (`https://erkul.games/s/ikdocrki`)

```json
{"id":"ikdocrki","createdAt":"2026-10-20T05:52:29Z","payload":{"schemaVersion":1,
 "v":"4.10.2-LIVE.12913470","branch":"LIVE","ship":"drak_ironclad","shipMode":"SCM",
 "gimbalMode":"lock","beamSustainMode":"aggressive","chargeFireMode":"hold",
 "slotOverrides":{
  "hardpoint_manned_turret_bottom/turret_left/hardpoint_class_2":{"kind":"weapon","className":"espr_ballisticcannon_s5"},
  "hardpoint_manned_turret_bottom/turret_right/hardpoint_class_2":{"kind":"weapon","className":"espr_ballisticcannon_s5"},
  "hardpoint_manned_turret_right/turret_left/hardpoint_class_2":{"kind":"weapon","className":"behr_lasercannon_s3"},
  "hardpoint_manned_turret_right/turret_right/hardpoint_class_2":{"kind":"weapon","className":"behr_lasercannon_s3"},
  "hardpoint_remote_turret_left/duel_turret_left/hardpoint_class_2":{"kind":"weapon","className":"krig_lasercannon_s3"},
  "hardpoint_remote_turret_left/duel_turret_right/hardpoint_class_2":{"kind":"weapon","className":"krig_lasercannon_s3"},
  "hardpoint_remote_turret_interior/turret_left/hardpoint_class_2":{"kind":"weapon","className":"klwe_massdriver_s2"},
  "hardpoint_remote_turret_interior/turret_right/hardpoint_class_2":{"kind":"weapon","className":"klwe_massdriver_s2"},
  "hardpoint_shield_generator_a":{"kind":"shield","className":"shld_godi_s03_surestop_scitem"},
  "hardpoint_shield_generator_b":{"kind":"shield","className":"shld_basl_s03_parapet_scitem"},
  "hardpoint_shield_generator_c":{"kind":"shield","className":"shld_godi_s03_fr86_scitem"},
  "hardpoint_shield_generator_d":{"kind":"shield","className":"shld_behr_s03_7ca_scitem"},
  "hardpoint_quantum_drive":{"kind":"quantumdrive","className":"qdrv_rsi_s03_erebos_scitem"},
  "hardpoint_radar":{"kind":"radar","className":"radr_grnp_s03_v880"},
  "hardpoint_cooler_a":{"kind":"cooler","className":"cool_just_s03_iceflush_scitem"},
  "hardpoint_cooler_b":{"kind":"cooler","className":"cool_aegs_s03_mercury_scitem"},
  "hardpoint_cooler_c":{"kind":"cooler","className":"cool_just_s03_chillmax_scitem"},
  "hardpoint_power_plant_a":{"kind":"powerplant","className":"powr_aegs_s03_quadracellmx_scitem"},
  "hardpoint_power_plant_b":{"kind":"powerplant","className":"powr_amrs_s03_js500_scitem"}}}}
```

Both share ids may expire or be deleted on erkul's side. The JSON above is the durable record.

### 1.5 Erkul's own game data (for reference only)

The SPA loads binary files from `https://cdn.erkul.games/LIVE/…` (`ships.group.<hash>.bin`,
`ships/<ship>.<hash>.bin`, `weapons.<hash>.bin`, `shields…`, `coolers…`, `powerplants…`,
`quantumdrives…`, `radars…`, `missiles…`, `missileracks…`, `turrets…`, `mounts…`, `paints…`,
`prices.bin`, …). The per-ship file most likely holds the default loadout and the port tree. We
did **not** decode the `.bin` format. **Prefer our own P4K data over these files**: same source,
no dependency on an undocumented binary format.

## 2. Match against the Basetool catalogue (verified on production, 2026-10-20)

Read-only query (`SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY`, approved by @greluc)
against `game_item.class_name` / `ship_type.class_name` with `upper(...) = upper(...)`:

**15 of 15 component class names and the ship matched. No misses.**

| erkul `className` | `game_item.class_name` | name | kind | size | grade |
|---|---|---|---|---|---|
| espr_ballisticcannon_s5 | ESPR_BallisticCannon_S5 | Deadbolt V Cannon | VEHICLE_WEAPON | 5 | A |
| behr_lasercannon_s3 | BEHR_LaserCannon_S3 | M5A Cannon Defense Division | VEHICLE_WEAPON | 3 | A |
| krig_lasercannon_s3 | KRIG_LaserCannon_S3 | Quarreler Cannon | VEHICLE_WEAPON | 3 | A |
| klwe_massdriver_s2 | KLWE_MassDriver_S2 | Sledge II Mass Driver Cannon | VEHICLE_WEAPON | 2 | A |
| shld_godi_s03_surestop_scitem | SHLD_GODI_S03_SureStop_SCItem | SureStop | VEHICLE_ITEM | 3 | C |
| shld_basl_s03_parapet_scitem | SHLD_BASL_S03_Parapet_SCItem | Parapet | VEHICLE_ITEM | 3 | A |
| shld_godi_s03_fr86_scitem | SHLD_GODI_S03_FR86_SCItem | FR-86 | VEHICLE_ITEM | 3 | A |
| shld_behr_s03_7ca_scitem | SHLD_BEHR_S03_7CA_SCItem | 7CA 'Nargun' | VEHICLE_ITEM | 3 | A |
| qdrv_rsi_s03_erebos_scitem | QDRV_RSI_S03_Erebos_SCItem | Erebos | VEHICLE_ITEM | 3 | A |
| radr_grnp_s03_v880 | RADR_GRNP_S03_V880 | V880 | VEHICLE_ITEM | 3 | A |
| cool_just_s03_iceflush_scitem | COOL_JUST_S03_IceFlush_SCItem | Ice-Flush | VEHICLE_ITEM | 3 | B |
| cool_aegs_s03_mercury_scitem | COOL_AEGS_S03_Mercury_SCItem | Mercury | VEHICLE_ITEM | 3 | C |
| cool_just_s03_chillmax_scitem | COOL_JUST_S03_ChillMax_SCItem | Chill-Max | VEHICLE_ITEM | 3 | A |
| powr_aegs_s03_quadracellmx_scitem | POWR_AEGS_S03_QuadracellMX_SCItem | QuadraCell MX | VEHICLE_ITEM | 3 | A |
| powr_amrs_s03_js500_scitem | POWR_AMRS_S03_JS500_SCItem | JS-500 | VEHICLE_ITEM | 3 | A |
| drak_ironclad (ship) | DRAK_Ironclad (`ship_type`) | Ironclad | – | – | – |

Conclusions:

- Matching is case-insensitive on `class_name`. The functional indexes already exist
  (`idx_game_item_class_name_upper`, `idx_ship_type_class_name_upper`, migration V140) and the
  P4K import uses the same lookup (`findByClassNameIgnoreCase`).
- Not tested yet: `missile`, `rack`, `bomb`, `mount`, `paint`, `jumpdrive`, `blade`, `utility`,
  `module`, `empty`, `fixed`. Paints and racks may well be missing from `game_item`. Re-check with
  a share that changes those once the new catalogue is in.
- If the new extractor changes how `class_name` is stored, re-run the match (query in §6).

## 3. What the Basetool has today

Verified against the code on 2026-10-20 (`B` = `backend/src/main/java/de/greluc/krt/profit/basetool/backend`):

- **Owned ship:** `B/hangar/api/Ship.java` — `name`, `shipType` (ManyToOne `ShipType`), `insurance`,
  `location`, `fitted` (plain boolean, the only loadout-related field), `owner`, `owningOrgUnit` +
  `owningOrgUnitVersion` (fine-grained lock, ADR-0258, `@DynamicUpdate`).
  **No loadout, slot, hardpoint or component concept exists anywhere** (entities, migrations, specs).
- **Hangar services:** `B/hangar/internal/HangarService`, `HangarImportService` (Fleetyards JSON
  upload via `FleetExportParser` / `FleetyardsEntryDto`), controller `B/hangar/web/HangarController`.
  Specs: `docs/specs/personal-hangar-overview.md`, `hangar-squadron-overview.md`,
  `hangar-notifications.md` (REQ-HANGAR-001…014).
- **Ship catalogue:** `B/catalogue/api/ShipType.java` — `className` ("RSI engine class name"),
  `p4kUuid`, `uexVehicleId`, `uexSlug`, `scwikiSlug`, `gameName`, `nameKey`.
- **Component catalogue:** `B/catalogue/api/GameItem.java` (table `game_item`) — `className`,
  `kind` (`GameItemKind`: `VEHICLE_ITEM`, `VEHICLE_WEAPON`, …), `wikiType` / `wikiSubType`,
  `sizeClass`, `grade`, `itemClass` (`ShipComponentClass`), `p4kUuid`, `uexItemId`,
  `linkedShipType`. `B/catalogue/api/ShipComponentType.fromWikiType()` maps Shield / Cooler /
  PowerPlant / QuantumDrive / WeaponGun / MissileLauncher.
- **P4K import:** `B/catalogue/internal/P4kImportService.java`, admin upload of a "KRT P4K Reader"
  export via `B/catalogue/web/AdminP4kImportController` (async job, preview + apply). DTOs
  `B/catalogue/internal/p4k/P4kItemDto` (guid, className, type, subType, size, grade) and
  `P4kShipDto` (guid, className, `vehicleDefinition`, …). `className` is filled only where empty.
  **No hardpoint tree and no default loadout is imported today.**
- **Outbound HTTP to third parties:** only `B/catalogue/internal/UexClient` and `ScWikiClient`,
  both Spring `RestClient` from `RestClientConfig.EXTERNAL_REST_CLIENT_BUILDER`
  (`B/platform/api/RestClientConfig.java`), base URL from a `@ConfigurationProperties` class.
  An erkul client would follow the same pattern.
- **Erkul today:** only a static navigation link,
  `frontend/src/main/java/de/greluc/krt/profit/basetool/frontend/shell/model/ScLink.java`
  (`https://erkul.games/calculator`).

## 4. What the new P4K export must deliver

Needed for a complete loadout. Without it an import can only store the overridden slots.

1. **Per ship, the item-port (hardpoint) tree:** port name, the parent path (so `a/b/c` paths can be
   built the same way erkul does), min/max size, accepted item types/subtypes, editable or fixed.
2. **Per ship, the default loadout:** port path → entity class name (and/or GUID). Nested: a
   turret's default loadout includes its sub-ports.
3. **Per item:** class name and GUID as today, plus type/subtype/size/grade. Items that own ports
   themselves (turrets, mounts, missile racks) need their own port list.
4. Coverage for paints, missile racks, missiles, bombs, mounts, utility, modules, blades, jump
   drives, so every erkul `kind` can be matched.

**Check first when the extractor is ready:** do its port names and path separators produce exactly
erkul's `slotPath` strings for the Ironclad (compare against sample 2)? If not, a mapping rule is
needed and must be written down here.

## 5. Proposed design (draft, needs @greluc's decisions)

- **Storage:** a new hangar-owned entity, e.g. `ShipLoadoutSlot` (`ship_id`, `slot_path`,
  `game_item_id` nullable for "empty", `kind`), with its **own version counter on the ship**
  (e.g. `Ship.loadoutVersion`, conditional bump like `owningOrgUnitVersion`) so editing a loadout
  never 409s a concurrent rename/location edit. Store only overrides, or the full resolved loadout —
  decision open (full is easier to display; overrides survive a game patch better).
- **Import flow:** user pastes a share link → frontend posts it to the backend → backend validates
  the id (`^[a-z0-9]{8}$`, never a free URL — no SSRF), fetches `GET {erkul.api-url}/shares/{id}`
  with a timeout and a response-size cap, inflates (raw DEFLATE, with a decompressed-size cap),
  validates `schemaVersion == 1`, checks that `payload.ship` matches the hangar ship's
  `ShipType.className` (case-insensitive, else reject), resolves every `className` against
  `game_item`, and returns a **preview** (matched / unmatched slots). Apply stores the slots.
  Unknown class names are reported, never silently dropped.
- **Fallback without network:** also accept the decoded JSON pasted directly, in case erkul blocks
  server-side calls.
- **Data version:** store `payload.v` / `branch` with the loadout so a later patch can flag stale
  loadouts.

### Repo obligations when this is implemented (root `CLAUDE.md`)

- Spec: new `REQ-HANGAR-0NN` entries; ADR for the erkul dependency and the loadout model.
- Audit: Hangar is an audited area — new `AuditEventType`s for loadout import/edit/clear, viewer
  filter, DE/EN labels, REQ-AUDIT-001 list. No user free text in details.
- Monitoring: `basetool_*` metric for import outcomes (bounded labels: success / not_found /
  ship_mismatch / unmatched_items / upstream_error), tracing on the outbound call, alert if the
  upstream error rate is high.
- Frontend: DAS KARTELL design system, live update via `krtFetch`, i18n DE/EN (umlauts `\uXXXX`).
- Docs: CHANGELOG, README, ROLES_AND_PERMISSIONS (who may edit a ship's loadout), the German wiki
  hangar page, arc42 §3 (new external neighbour) and §5.
- Tests for every path; `verification-metadata.xml` only if a new dependency is added
  (`java.util.zip.Inflater` needs none).

## 6. Open questions / risks

- **Permission from erkul:** the API is undocumented. Ask the erkul operator before shipping
  (contact via the site / its Discord). Respect rate limits; cache resolved shares.
- **Format drift:** `schemaVersion` may change; reject unknown versions with a clear message.
- **Which ships carry a loadout:** every hangar ship, or only `fitted` ones? Does a loadout replace
  the `fitted` flag?
- **Visibility:** owner only, or also the squadron hangar overview?
- **Export the other way** (Basetool → erkul link) is out of scope: creating shares needs a
  Turnstile token.

### Re-running the catalogue match

The read-only query used in §2 (run inside the `db-backend` container with `psql -At -f -`;
production access follows the root `CLAUDE.md` gate — each run needs @greluc's explicit yes):

```sql
SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY;
WITH wanted(cn) AS (VALUES ('espr_ballisticcannon_s5'), ('shld_godi_s03_surestop_scitem'))
SELECT w.cn, g.class_name, g.name, g.kind, g.size_class, g.grade
FROM wanted w LEFT JOIN game_item g ON upper(g.class_name) = upper(w.cn)
ORDER BY w.cn;
SELECT class_name, name FROM ship_type WHERE upper(class_name) = upper('drak_ironclad');
```

### Decoding a share by hand

In the browser console of an `erkul.games` page (the only origin it was tested from; whether the
API sends CORS headers for other origins is unknown, a server-side client does not need them):

```js
const r = await fetch('https://api.erkul.games/shares/ikdocrki');
const b = new Uint8Array(await r.arrayBuffer());
await new Response(new Blob([b]).stream().pipeThrough(new DecompressionStream('deflate-raw'))).text();
```
