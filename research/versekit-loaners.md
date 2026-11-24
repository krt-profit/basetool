# What VerseKit does with ships not in the game and loaners

Research for [What VerseKit does with ships not in the game and loaners today](https://github.com/krt-profit/basetool/issues/3669),
part of the map [Wayfinder: loaners of pledged ships that are not in the game](https://github.com/krt-profit/basetool/issues/3666)
behind [Hangar: show the loaners of pledged ships that are not in the game (loaner matrix)](https://github.com/krt-profit/basetool/issues/3665).
Status: findings as of 2026-11-24. Facts only; nothing here is decided.

## Sources

Primary sources only. Each claim below names its file and lines.

- **VerseKit source**, public, GPL-3.0: <https://github.com/Xharig/VerseKit>, `main` at commit
  [`cf4841e9129939d1ffdc026232e057807b97c715`](https://github.com/Xharig/VerseKit/tree/cf4841e9129939d1ffdc026232e057807b97c715)
  (2026-11-23, "VerseKit 5.6.0", workspace version
  [`5.6.0`](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/Cargo.toml#L6)).
  Read as data; nothing was built or run.
  - `basetool/x.rs` is short for `crates/versekit-core/src/basetool/x.rs`, `core/x.rs` for
    `crates/versekit-core/src/x.rs`, `ui/x.rs` for `crates/versekit-ui/src/x.rs`, `en.json` for
    `crates/versekit-core/lang/en.json`. Line numbers are at that commit.
- **VerseKit documentation and data** at the same commit: `PRIVACY.en.md`,
  `tools/basetool-vertrag/` (the vendored exchange contract), `tools/pruefdaten/hangar-schiffe-beispiel.json`
  (a sample of the Hangar-Exporter's output), `daten/beiboote.json` and `daten/flottenanalyse-v5.json`
  (bundled data).
- **VerseKit issues**, all ten: none mentions loaners or concept ships. One bears on ship-type keys:
  [Basetool catalogue interface: plan for VerseKit and a few questions](https://github.com/Xharig/VerseKit/issues/13).
- **Basetool**, `origin/main` at `78a52dd8fb7ba0412c796604a723c4c58f986bd7`:
  `docs/exchange/resources/ships.md`, `docs/exchange/resources/catalog.md`,
  `docs/exchange/sync-guide.md`; for one point the docs leave open, the backend classes
  `ExchangeResolveService`, `ExchangeShipTypeResolution` and `OfferedShipTypes`.

## The short answer

- **VerseKit never reads the RSI site for the hangar.** The ships come from the Hangar-Exporter
  browser add-on, which posts a ship list to VerseKit on `127.0.0.1`, or from export files of other
  tools. A ship that cannot be flown yet arrives like any other pledged ship: nothing VerseKit reads
  or stores marks it. **Loaners appear nowhere**: no field, text or line of code in the repository
  names them.
- **The ship sync resolves every hangar entry first.** An entry whose `{name, scRecord}` does not
  resolve as a `SHIP_TYPE` is neither linked nor created; the Basetool page only counts it ("The
  Basetool does not know n ship types. They stay here only."). A resolved entry goes out as `link`
  or `upsert` with type, insurance, own name, `fitted` and unit, never with `origin` or purchase
  data. A change-set `UNMATCHED` drops the cached type so the next pass resolves again; the same
  refused op stays back for 24 hours. `AMBIGUOUS` gets no handling of its own, and resolve
  candidates are never read or offered.
- **No mirror.** Server ships of a type the hangar lacks are taken in ("from the Basetool"); a
  second server ship of a type the hangar already holds is left alone; nothing is removed on the
  server or created again there without the member's click. A ship RSI stops listing leaves
  VerseKit's hangar on the next pledge import and then becomes a question to the member.
- **Production status only at display time.** The hangar row says "Concept, not in the game yet
  (per UEX)" from UEX's `is_concept`; the bundled Fleetmanifest data carries a per-ship `status`
  that VerseKit parses but never reads. Neither is stored on the hangar entry or sent.
- **Ship types:** `POST /catalog/resolve` with name and the pledge or RSI code, cached per hangar
  entry. VerseKit keeps several ship catalogues of its own (UEX, erkul, game values, included
  vessels, Fleetmanifest); none is keyed to Basetool ids.

## 1. Reading the RSI hangar

### Where the hangar comes from

- No code fetches RSI's pledge pages. The RSI addresses in the code are the org and citizen pages,
  the status page, the Issue Council and image links
  ([`core/handle_query/page.rs` L30](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/handle_query/page.rs#L30),
  [`core/serverstatus.rs` L46](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/serverstatus.rs#L46),
  [`core/issue_council.rs` L39–41](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/issue_council.rs#L39-L41)).
- **Direct delivery:** the Hangar-Exporter (a browser add-on by Drachennarr, separate repository
  [Drachennarr/Hangar-Exporter-Releases](https://github.com/Drachennarr/Hangar-Exporter-Releases),
  [`core/credits.rs` L63–65](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/credits.rs#L63-L65))
  reads the hangar on the RSI site and posts a `hangar-schiffe` v1 list to
  `POST http://127.0.0.1:<port>/api/v1/hangar/uebergabe` with a pairing token
  ([`core/hangar_inbox.rs` L18–38](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/hangar_inbox.rs#L18-L38),
  [`PRIVACY.en.md` L362–385](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/PRIVACY.en.md#L362-L385)).
- **Files:** the same list as a file, the JSON and CSV of the Hangar Extension (AlyxOne), the JSON
  and CSV of the Hangar XPLORer, and ship lists of Fleetyards, Fleetview and StarJump; the format is
  told by the content
  ([`core/hangar_formats.rs` L18–28](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/hangar_formats.rs#L18-L28)).

### What a ship carries after reading

- From `hangar-schiffe` v1 VerseKit takes `name`, `matrix` (only when `matrixZuordnung` is `sicher`
  or `naeherung`), `code` else `rsiKennung`, the manufacturer, `insurance`/`insuranceMonths`,
  `warbond`, `includedWith`, `purchaseDate`, `pledgePriceUsd` and `includedShips`
  ([`core/hangar.rs` L1791–1838](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/hangar.rs#L1791-L1838),
  [L1917–1956](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/hangar.rs#L1917-L1956)).
  `pledgeId` is never taken over
  ([`core/hangar.rs` L29](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/hangar.rs#L29)).
- The Hangar Extension CSV is read per pledge; only the content types `Ship`, `Included Ship` and
  `Insurance` are used, every other row is skipped
  ([`core/hangar_formats.rs` L550–560](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/hangar_formats.rs#L550-L560)).
  The XPLORer JSON skips every `entity_type` other than `ship`
  ([L222–227](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/hangar_formats.rs#L222-L227)).
- A read ship becomes a `hangar.json` entry with `name`, `hersteller`, `kurz`, `hkurz`, `lti`,
  `warbond`, `versicherung`, `paket`, `gekauft`, `preis` and an origin `herkunft`: `pledge`,
  `ingame`, `basetool`, `liste` or `katalog`
  ([`core/hangar.rs` L49–53](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/hangar.rs#L49-L53),
  [L67](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/hangar.rs#L67)).
  Read entries without an origin count as `pledge`
  ([L1986–1990](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/hangar.rs#L1986-L1990)).

### Ships that cannot be flown yet

- Nothing in the read formats or the stored entry says whether a ship is in the game. Such a ship
  arrives as an ordinary entry of origin `pledge`.
- The Hangar-Exporter sample has a pledge whose ship the exporter matched to no ship-matrix entry:
  `matrix: null`, `matrixZuordnung: "keine"`, `rsiKennung: null`, counted as `ohneMatrix`
  ([`tools/pruefdaten/hangar-schiffe-beispiel.json` L12](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/tools/pruefdaten/hangar-schiffe-beispiel.json#L12),
  [L236–238](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/tools/pruefdaten/hangar-schiffe-beispiel.json#L236-L238)).
  VerseKit then takes the pledge's `name`
  ([`core/hangar.rs` L1791–1815](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/hangar.rs#L1791-L1815)).
  The sample does not say why a pledge has no match.

### Loaners

- A case-insensitive search of the whole tree for `loan` and `leih` finds only the word "loanword"
  in a test list: no field, no text key, no code, no data file names loaners.
- None of the formats VerseKit reads has a loaner field it takes, and the CSV content types it
  handles are the three above. VerseKit's parsers therefore do not reveal how RSI's hangar page
  presents loaners.

### Included vessels are a separate thing

- `daten/beiboote.json` is a bundled catalogue of the vehicles a ship brings along (C8 Pisces and
  Ursa with the Carrack, and so on), each with an RSI page as evidence
  ([`daten/beiboote.json` L2](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/daten/beiboote.json#L2)).
  VerseKit adds those vehicles as entries of origin `katalog`
  ([`core/hangar.rs` L1376–1448](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/hangar.rs#L1376-L1448)),
  shown as "per catalogue, with the {ship}"
  ([`ui/hangar_page.rs` L793–796](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-ui/src/hangar_page.rs#L793-L796),
  [`en.json` L3110](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/lang/en.json#L3110)).
  The ship sync leaves `katalog` entries out
  ([`basetool/ship_sync.rs` L170–181](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/ship_sync.rs#L170-L181)).
- Vehicles an export lists under `includedShips` become entries of origin `pledge` with
  `beilage: true` and do take part in the sync
  ([`core/hangar.rs` L1847–1900](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/hangar.rs#L1847-L1900)).

## 2. What the ship sync sends, and `UNMATCHED` / `AMBIGUOUS`

### Which entries take part

- Every hangar entry with a name except origin `katalog`
  ([`basetool/ship_sync.rs` L170–204](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/ship_sync.rs#L170-L204)).
  VerseKit keeps one entry per ship type; exactly one server ship per type is linked
  ([L25–28](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/ship_sync.rs#L25-L28)).
- The `externalId` is `vk-` plus the first 24 hex digits of SHA-1 over maker and name, kept in the
  entry once given
  ([L75–102](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/ship_sync.rs#L75-L102)).

### Resolving before writing

- Each entry's reference is `{name}` plus `scRecord` = the entry's `kurz` when known
  ([L182–185](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/ship_sync.rs#L182-L185)),
  sent as `POST /catalog/resolve` with `kind: SHIP_TYPE` in chunks of 500
  ([`basetool/ship_area.rs` L276–288](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/ship_area.rs#L276-L288),
  [`basetool/feed.rs` L124–169](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/feed.rs#L124-L169)).
- `resolved` keeps the `bt`. Every other status, `ambiguous` and `unmatched` alike, is kept as
  `{status, at}` and asked again after 24 hours; the `candidates` of `ambiguous` are not read
  ([`basetool/feed.rs` L35–36](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/feed.rs#L35-L36),
  [L139–146](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/feed.rs#L139-L146),
  [L156–165](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/feed.rs#L156-L165)).
- An entry without a resolved `bt` is neither linked nor created
  ([`basetool/ship_sync.rs` L539](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/ship_sync.rs#L539)).
  It is counted as `unresolved`
  ([`basetool/ship_area.rs` L469](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/ship_area.rs#L469)),
  and the Basetool page shows the count only: "The Basetool does not know {n} ship types. They stay
  here only."
  ([`basetool/overview.rs` L253–261](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/overview.rs#L253-L261),
  [`en.json` L5535](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/lang/en.json#L5535)).
  No list of names, no choice among candidates.

### What goes out

- **Link first:** an unlinked entry is linked to an unlinked server ship of the same `bt`; only
  when there is none is it created
  ([`basetool/ship_sync.rs` L526–565](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/ship_sync.rs#L526-L565),
  [L606–631](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/ship_sync.rs#L606-L631)).
- **Create:** `upsert` without `shipId` with `externalId`, `shipType` `{name, scRecord?, bt}`,
  `insurance` (`LTI` or `MONTHS` 0 to 120), the own name when the ship maps to a game name, `fitted`
  when ever set, `orgUnit` when chosen and known, `override: true` after the member agreed
  ([L619–659](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/ship_sync.rs#L619-L659),
  [L140–147](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/ship_sync.rs#L140-L147)).
- **Update:** `upsert` with `shipId` and `version` echoes the server's own `shipType` `{bt, name}`,
  name and location and sends only the changed fields of its own
  ([L660–708](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/ship_sync.rs#L660-L708)).
- **Never** purchase data and **never `origin`**: no code in `basetool/` writes an `origin` field
  ([`basetool/ship_sync.rs` L149–167](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/ship_sync.rs#L149-L167),
  [`PRIVACY.en.md` L274–281](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/PRIVACY.en.md#L274-L281)).
  The vendored contract is pinned at Basetool `20928c7` (2026-10-18) and its ship schema has no
  `origin`
  ([`tools/basetool-vertrag/README.md` L10](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/tools/basetool-vertrag/README.md#L10),
  [`v1/schemas/ship.schema.json`](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/tools/basetool-vertrag/v1/schemas/ship.schema.json)).

### A ship that is not in the game

What goes out depends on what `catalog/resolve` answers for it:

1. **`unmatched` or `ambiguous`:** nothing goes out. The entry stays local, is counted as
   unresolved and is asked again after 24 hours (above).
2. **`resolved` to a ship type the Basetool holds but does not offer:** VerseKit sends the create
   `upsert` with that `bt`. The Basetool answers `unmatched` / `UNMATCHED` for an upsert that would
   create a ship of a type "the game files do not list, or one it hid or retired"
   ([`ships.md` L114](https://github.com/krt-profit/basetool/blob/78a52dd8fb7ba0412c796604a723c4c58f986bd7/docs/exchange/resources/ships.md#L114)).
   This case exists on the Basetool side: `catalog/resolve` matches over all ship types
   ([`ExchangeResolveService.java` L533–563](https://github.com/krt-profit/basetool/blob/78a52dd8fb7ba0412c796604a723c4c58f986bd7/backend/src/main/java/de/greluc/krt/profit/basetool/backend/exchange/internal/ExchangeResolveService.java#L533-L563)),
   while the change set accepts only an offered type, or a ship's current one
   ([`ExchangeShipTypeResolution.java` L61–83, L127–143](https://github.com/krt-profit/basetool/blob/78a52dd8fb7ba0412c796604a723c4c58f986bd7/backend/src/main/java/de/greluc/krt/profit/basetool/backend/exchange/internal/ExchangeShipTypeResolution.java#L61-L143),
   [`OfferedShipTypes.java` L45–47](https://github.com/krt-profit/basetool/blob/78a52dd8fb7ba0412c796604a723c4c58f986bd7/backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/api/OfferedShipTypes.java#L45-L47)).
3. **A server ship of that type already exists, unlinked:** VerseKit sends `link`, which carries no
   ship type, and later updates echo the server's own type, which the Basetool accepts as the ship's
   current type (sources in 2.).

### `UNMATCHED` and `AMBIGUOUS` in the change-set result

- Every result other than `unchanged` is a failure `(externalId, result, reason)`
  ([`basetool/area_support.rs` L341–360](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/area_support.rs#L341-L360)).
- **`UNMATCHED`** (result `unmatched` or reason `UNMATCHED`): the cached `bt` of that entry is
  dropped, so the next pass resolves it anew
  ([`basetool/ship_area.rs` L304–314](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/ship_area.rs#L304-L314)).
- **`AMBIGUOUS`** has no branch of its own.
- Every refusal goes into the sync state `ships.rejected` and into "The Basetool declined {n}
  changes ({reasons})" with at most three reasons
  ([`basetool/overview.rs` L132–144](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/overview.rs#L132-L144),
  [`en.json` L5530](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/lang/en.json#L5530)).
- An op answered without being applied does not go out again for 24 hours while the plan holds it
  unchanged, and its old refusal is reported; a sync button sends it at once
  ([`basetool/pacing.rs` L18–30](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/pacing.rs#L18-L30),
  [L106–141](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/pacing.rs#L106-L141)).
  For case 2 above that means: refused, resolved again, the same `upsert` planned and held back,
  sent once more after 24 hours or on the button.

## 3. Mirroring the server feed

VerseKit pulls `GET /me/ships` as snapshot plus feed with tombstones and keeps the result as
`ships.server`
([`basetool/area_support.rs` L143–195](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/area_support.rs#L143-L195)).
It does not mirror it:

| Case | What VerseKit does | Where |
| --- | --- | --- |
| Server ship not linked by this installation, of a type no hangar entry resolves to | Takes it into `hangar.json` as a new entry of origin `basetool` ("from the Basetool"), named by the server's ship-type name, with the server's insurance; one per type | [`ship_sync.rs` L567–579](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/ship_sync.rs#L567-L579), [`ship_area.rs` L133–155](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/ship_area.rs#L133-L155) |
| Server ship not linked, of a type an unlinked hangar entry resolves to | `link` | [`ship_sync.rs` L543–560](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/ship_sync.rs#L543-L560) |
| Server ship not linked, of a type a linked hangar entry already holds | Left alone: not linked, not taken in, never removed | [`ship_sync.rs` L25–28, L540–542](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/ship_sync.rs#L25-L28) |
| Linked server ship whose hangar entry is gone here | Nothing removed. Card "Hangar: no longer here (n)": "Remove them in the Basetool too" sends `remove` on the next pass; "Keep them in the Basetool" stops the question | [`ship_sync.rs` L481–491](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/ship_sync.rs#L481-L491), [`decisions.rs` L105–119](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/decisions.rs#L105-L119), [`ui/basetool_page.rs` L1258–1260](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-ui/src/basetool_page.rs#L1258-L1260) |
| Linked ship gone on the server, entry of origin `basetool` | Entry dropped from the hangar | [`ship_sync.rs` L493–496](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/ship_sync.rs#L493-L496), [`ship_area.rs` L202–212](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/ship_area.rs#L202-L212) |
| Linked ship gone on the server, other origin, or `REMOVED_ELSEWHERE` | Kept here, not created again. Card "Hangar: removed in the Basetool (n)": "Put them back into the Basetool anyway" sends `upsert` with `override: true` | [`ship_sync.rs` L497–501, L561–564](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/ship_sync.rs#L497-L501), [`ship_area.rs` L345–347](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/ship_area.rs#L345-L347), [`overview.rs` L556–569](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/overview.rs#L556-L569) |

Card texts: [`en.json` L5560, L5563, L5572–5576](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/lang/en.json#L5560-L5576).

**A ship RSI does not list.**

- Only an import can tell. A pledge export is taken as the whole pledge hangar: entries of origin
  `pledge` it no longer names are removed; `ingame`, `liste`, `basetool` and `katalog` entries stay
  ([`core/hangar.rs` L1978–1981, L2048–2073](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/hangar.rs#L1978-L2073)).
  Before a delivery removes a ship, VerseKit writes `hangar.bak.json`
  ([`core/hangar_inbox.rs` L297–305](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/hangar_inbox.rs#L297-L305)).
- A removed entry that was linked becomes the "no longer here" card above, so the server ship stays
  until the member chooses.
- A ship the Basetool has and RSI never listed comes in as origin `basetool` and no pledge import
  removes it. Once an export names it, it takes that import's origin plus the mark `aus_basetool`
  ([`core/hangar.rs` L936–945, L998–999](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/hangar.rs#L936-L999)),
  and from then on an export that leaves it out removes it.
- With the hangar area on, the import page says: "With the Basetool sync on, the Basetool comes
  first. Ships from there are not added twice. The import only adds what they lack, like insurance."
  ([`ui/hangar_page.rs` L1318–1321](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-ui/src/hangar_page.rs#L1318-L1321),
  [`en.json` L6126](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/lang/en.json#L6126)).

## 4. Loaner information and production status

**Loaners:** none, anywhere (section 1).

**Production status**, in three places, none of them on the hangar entry and none sent:

- **UEX `is_concept`.** VerseKit fetches `https://api.uexcorp.uk/2.0/vehicles` at most daily into
  `schiffe.json` and keeps `konzept: 1` for a ship UEX flags
  ([`core/ship_market.rs` L35–45](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/ship_market.rs#L35-L45),
  [L271–281](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/ship_market.rs#L271-L281),
  [L296–298](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/ship_market.rs#L296-L298),
  [`core/ships.rs` L49–57, L139–142](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/ships.rs#L49-L142)).
  - The hangar row shows "Concept, not in the game yet (per UEX)" in place of the slot count, only
    when erkul knows no slots for the ship
    ([`ui/hangar_page.rs` L849–880](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-ui/src/hangar_page.rs#L849-L880),
    [`en.json` L3121](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/lang/en.json#L3121)).
  - The salvage page says "Concept per UEX, so no loadout."
    ([`ui/salvage_page.rs` L696–697](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-ui/src/salvage_page.rs#L696-L697),
    [`en.json` L2694](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/lang/en.json#L2694)).
- **Fleetmanifest `status`.** The bundled fleet-analysis data, from MaluhTV/SC_Fleetmanifest at
  commit `dfa4617` (2026-10-20), gives each ship `flight-ready` (213), `in-production` (10) or
  `concept` (20)
  ([`daten/flottenanalyse-v5.json` L1–3](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/daten/flottenanalyse-v5.json#L1-L3),
  for example [L47](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/daten/flottenanalyse-v5.json#L47)
  and [L99](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/daten/flottenanalyse-v5.json#L99)).
  VerseKit parses it into `Ship::status`
  ([`core/fleet_analysis.rs` L117–130, L281](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/fleet_analysis.rs#L117-L130)),
  but no code reads that field. The analysis uses the per-loop role `planned` instead
  ([L49–66](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/fleet_analysis.rs#L49-L66)).
- **Not stored, not sent.** The hangar entry has no such key (section 1), `LocalShip` has no such
  field
  ([`basetool/ship_sync.rs` L149–167](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/ship_sync.rs#L149-L167)),
  and the Exchange ship has no field for a production status or a loaner either
  ([`ships.md` L9–21](https://github.com/krt-profit/basetool/blob/78a52dd8fb7ba0412c796604a723c4c58f986bd7/docs/exchange/resources/ships.md#L9-L21)).

## 5. Ship types and catalogues

### Resolving

- The reference carries `name` and, when known, `scRecord` = `kurz`. `kurz` is the pledge's `code`
  else `rsiKennung` for the Hangar-Exporter
  ([`core/hangar.rs` L1821](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/hangar.rs#L1821)),
  `code` for the Hangar Extension JSON and `ship_code` for the XPLORer JSON
  ([`core/hangar_formats.rs` L210, L240](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/hangar_formats.rs#L210-L240)),
  and empty for the CSVs and the ship lists
  ([L273, L474, L574](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/hangar_formats.rs#L273)).
- No `guid`, `scGuid`, `uexId` or `locKey` is sent.
- The answer is cached per `externalId` in the sync state `ships.resolved` (section 2).
- **Merged types:** when a server ship keeps its `shipId` but carries another `bt`, VerseKit asks
  `catalog/resolve` for the old `bt`. If that resolves to the new one, or is `unmatched`, the cache
  is rewritten to the new `bt`
  ([`basetool/ship_sync.rs` L431–454](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/ship_sync.rs#L431-L454),
  [`basetool/ship_area.rs` L254–285](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/ship_area.rs#L254-L285),
  [`basetool/feed.rs` L207–216](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/feed.rs#L207-L216)).

### Catalogues of its own

| Catalogue | File | Source | Holds | Where |
| --- | --- | --- | --- | --- |
| UEX vehicles | `schiffe.json`, at most daily | `api.uexcorp.uk/2.0/vehicles` plus buy and rent prices | name with maker, maker, SCU, concept, add-on | [`ship_market.rs` L18–45](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/ship_market.rs#L18-L45), [`ships.rs` L18–21](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/ships.rs#L18-L21) |
| erkul ship slots | `erkul-schiffe.json` | erkul.games | slots and factory parts | [`erkul_slots.rs` L18–26](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/erkul_slots.rs#L18-L26) |
| Game ship values | `spiel-schiffe.json` | game data | SCM speed, size, cargo | [`erkul_slots.rs` L43](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/erkul_slots.rs#L43), [`ui/hangar_page.rs` L891–899](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-ui/src/hangar_page.rs#L891-L899) |
| Included vessels | `daten/beiboote.json`, bundled | RSI pages, one piece of evidence per entry | carrier ship → vehicles | [`hangar.rs` L1376–1448](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/hangar.rs#L1376-L1448) |
| Fleetmanifest | `daten/flottenanalyse-v5.json`, bundled | MaluhTV/SC_Fleetmanifest | loops, roles, status, motherships, CCU | [`fleet_analysis.rs` L18–34](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/fleet_analysis.rs#L18-L34) |

- The UEX names also fill in the maker of a ship from a list that has none
  ([`core/hangar.rs` L1733–1740](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/hangar.rs#L1733-L1740)).
- None of these catalogues is keyed to Basetool ids. The only Basetool ship-type id VerseKit keeps
  is the resolved `bt` per hangar entry in the sync state.
- **Stated plan, not code:** in
  [Basetool catalogue interface: plan for VerseKit and a few questions](https://github.com/Xharig/VerseKit/issues/13)
  (open), the VerseKit maintainer answered on 2026-11-08 that the blueprint and hangar syncs would
  move to GUIDs step by step, sending the GUID in addition first. At `cf4841e` the ship reference
  carries no GUID.

## Against the Exchange docs

- **Link before create**
  ([`sync-guide.md` L45–50](https://github.com/krt-profit/basetool/blob/78a52dd8fb7ba0412c796604a723c4c58f986bd7/docs/exchange/sync-guide.md#L45-L50)):
  VerseKit links first and sends `link` ops before creates
  ([`basetool/ship_sync.rs` L606–607](https://github.com/Xharig/VerseKit/blob/cf4841e9129939d1ffdc026232e057807b97c715/crates/versekit-core/src/basetool/ship_sync.rs#L606-L607)).
- **Removing and re-adding**
  ([`sync-guide.md` L64–84](https://github.com/krt-profit/basetool/blob/78a52dd8fb7ba0412c796604a723c4c58f986bd7/docs/exchange/sync-guide.md#L64-L84),
  [`ships.md` L129–132](https://github.com/krt-profit/basetool/blob/78a52dd8fb7ba0412c796604a723c4c58f986bd7/docs/exchange/resources/ships.md#L129-L132)):
  VerseKit takes in what it lacks, removes on the server only after the member's click, and
  re-adds only with `override: true` after consent. It takes in one ship per type; a further ship of
  a type it already holds is neither taken in nor removed.
- **`externalId`**
  ([`ships.md` L48–50](https://github.com/krt-profit/basetool/blob/78a52dd8fb7ba0412c796604a723c4c58f986bd7/docs/exchange/resources/ships.md#L48-L50)):
  derived from maker and name, never from a pledge id.
- **`origin`**
  ([`ships.md` L21, L84–95](https://github.com/krt-profit/basetool/blob/78a52dd8fb7ba0412c796604a723c4c58f986bd7/docs/exchange/resources/ships.md#L84-L95)):
  VerseKit knows `pledge` and `ingame` per entry but sends no `origin`, so a ship it creates is
  `UNKNOWN`. Its contract copy predates the field.
- **`unmatched` and `ambiguous`**
  ([`catalog.md` L57–61](https://github.com/krt-profit/basetool/blob/78a52dd8fb7ba0412c796604a723c4c58f986bd7/docs/exchange/resources/catalog.md#L57-L61)):
  the docs say to show `unmatched` to the member and let the member pick for `ambiguous`. VerseKit
  shows a count and offers no pick.
- **A type the game files do not list**
  ([`ships.md` L113–115](https://github.com/krt-profit/basetool/blob/78a52dd8fb7ba0412c796604a723c4c58f986bd7/docs/exchange/resources/ships.md#L113-L115)):
  the change set refuses it with `UNMATCHED`. `catalog.md` does not say whether `catalog/resolve`
  answers `resolved` for such a type
  ([`catalog.md` L29–34](https://github.com/krt-profit/basetool/blob/78a52dd8fb7ba0412c796604a723c4c58f986bd7/docs/exchange/resources/catalog.md#L29-L34));
  the backend does (section 2, case 2).

## What the code cannot tell

- Whether the Hangar-Exporter, the Hangar Extension or the XPLORer see loaners or a production
  status on the RSI site. Their code is not in this repository; VerseKit's code shows only which
  of their fields it reads.
- Which ship types the Basetool's catalogue holds without offering them, and so whether a given
  concept ship takes case 1 or case 2 of section 2.
