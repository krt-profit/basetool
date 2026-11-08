# scmdb `cig_data_issues` as the correction seed

Research for [#3076](https://github.com/krt-profit/basetool/issues/3076) (map #3072), answering
whether scmdb's per-build list of known CIG data errors can seed the field-level corrections that
[#2899](https://github.com/krt-profit/basetool/issues/2899) proposes.

Read on 2026-11-08 from scmdb.net. Every claim below names the file it was read from; anything that
could not be read from a primary source is listed under [Open](#open).

## Short answer

- **Format:** one JSON object per build with four sections, each a map from a key to an entry that
  carries only the *expected* value(s) and an English `note`. The **raw value is not in the file**:
  it is the value in the build's own data, which scmdb's parser echoes as `knownIssue.actual`.
- **Keying:** `blueprintRecords` by blueprint-record GUID, `blueprintPools` by pool GUID,
  `entities` by entity-class GUID, `contracts` by contract `debugName` (not a GUID).
- **Counts (4.10.2-live.12913470):** 24 entries — 5 blueprint records, 2 pools, 14 entities,
  3 contracts. 19 fall into domains the P4K map imports (blueprints, items, manufacturers); the 2
  pools and 3 contracts do not.
- **Licence verdict: not cleared for import.** scmdb states it is licensed under **CC BY-NC-ND 4.0**
  and its imprint requires written consent for duplication and processing. Turning the entries
  into Basetool corrections and passing them to VerseKit through the Exchange API is an adaptation
  that is shared, which NoDerivatives does not permit. Use it as a pointer list that Basetool admins
  re-verify against the P4K and write up in their own words, or get the author's written consent.
- **Mapping:** 19 entries become about 13 field-level corrections (the 7 maxOx entries are one
  manufacturer-name correction). Two shapes in the file do not fit #2899's "one record, one field"
  model and need a decision: a quality-modifier value sits on a sub-record of the blueprint
  (slot + property + range end), and a pool's duplicate reference is a list-membership defect.

## Sources

| Source | What it is |
| --- | --- |
| `https://scmdb.net/data/versions.json` | The builds scmdb currently serves |
| `https://scmdb.net/data/cig_data_issues-<build>.json` | The data-issue file, one per served build |
| `https://scmdb.net/data/crafting_blueprints-<build>.json` | scmdb's blueprint output, used to check keys and raw values |
| `https://scmdb.net/data/merged-<build>.json` | scmdb's contract and pool output, used to check pool keys |
| `https://scmdb.net/assets/index-u78iMDyJ.js` | The site's JavaScript bundle: how the file is loaded and applied, the About, Imprint and licence texts |
| `https://scmdb.net/robots.txt` | Crawler policy |
| [#2899](https://github.com/krt-profit/basetool/issues/2899) | The correction proposal |
| [`docs/specs/p4k-catalogue.md`](../docs/specs/p4k-catalogue.md) | Which P4K kinds the Basetool imports |

No public source repository or written documentation of the file format was found; the format
below is read from the file's own `_comment` fields and from the bundle code that consumes it.

## Builds served

`versions.json` lists two builds: `4.10.2-live.12913470` and `4.10.3-ptu.13132336`. Both
`cig_data_issues` files are byte-identical (SHA-256 `889a59f8…6cef41`, 23,706 bytes). A request for
an earlier build (`4.10.1-live.12660092`) returns the single-page app's HTML, not JSON: **scmdb does
not keep older builds' files online**, so a history per build is not available from it.

`robots.txt` disallows `/data/` for all crawlers, stating the files are "machine-consumed via
fetch() from the SPA". An automated periodic fetch by the Basetool would run against that policy.

## Format

The file is one object: a top-level `_comment` and the four sections. Each section has its own
`_comment` (German at the top level and for pools, English for entities and contracts) and then
entries. The top-level comment states the design: the parser stays faithful to CIG's source and
only adds an annotation, so the frontend can say what was meant.

### `blueprintRecords` — keyed by blueprint-record GUID

| Field | Meaning |
| --- | --- |
| `kind` | Optional. `label` = right item, wrong name (`expected.name` only); `entity` = the blueprint points at a different item (`expected.entityClass` + `name`). Absent = neither; the comment says not to infer it from the fields present. |
| `slot`, `propertyKey` | Present on a quality-modifier error: the recipe slot name and the gameplay-property key. |
| `expected` | Only the fields that differ, with their intended values (`modifierAtStart`, `name`, …). |
| `note` | English free text: evidence, observed effect, whether it was checked in-game. |

The comment adds: when `expected` equals the actual value, the parser silently drops the
annotation and warns during the run. The bundle does the same client-side (function `Dx`: every
non-null `expected` field equals the current value → the issue is hidden).

### `blueprintPools` — keyed by pool GUID

Each entry holds `duplicateReferences`: a list of `{blueprintRecord, expected, note}`, where
`blueprintRecord` is the GUID listed twice and `expected` is the presumed intended replacement
(`blueprintRecord`, `entityClass`, `name`) or `null` when none is plausible.

### `entities` — keyed by entity-class GUID

`expected` holds the intended attribute values (seen: `attachType` + `cgItemType`, `manufacturer`)
and `note`. The comment says `expected` may be `null` when the game's data does not define the
intended value, and that the parser adds `knownIssue` beside the raw attribute.

### `contracts` — keyed by contract `debugName`

`field` names the output field, `expected` the intended value(s) (seen: `factionGuid` +
`factionName`), `note`. The raw value is kept and echoed as `knownIssue.actual`.

### What #2899 asks for, against what the file has

| #2899 field | In the file? |
| --- | --- |
| Record by GUID | Yes for three sections; contracts are keyed by `debugName` |
| Field | Implicit in `expected`'s keys; explicit `field` only for contracts; a modifier also needs `slot` + `propertyKey` |
| Raw value | **No.** It must be read from the P4K data of the same build |
| Corrected value | `expected`, sometimes `null` (no correction possible) |
| Reason in one sentence | No — `note` is a multi-sentence paragraph (and under ND may not be shortened and shared, see below) |
| Evidence link or image | No |
| Created by / when / from which build | No author or date; the build is only the file name |

## Entries in 4.10.2-live.12913470

| Section | Entries | Kind of error | Imported by the P4K map? |
| --- | --- | --- | --- |
| `blueprintRecords` | 3 | Quality modifier: `armor_damagemitigation` `modifierAtStart` 9.0 where 0.9 is expected, slot "Segment Paneling", the three Aztalan Legs variants | Yes — blueprint modifiers (REQ-P4K-027) |
| `blueprintRecords` | 2 | `kind: label`: the produced item's localised name is another piece's (Antium arms named as a helmet, Antium helmet named as a torso) | Yes — but the Basetool names a blueprint through its produced item (REQ-P4K-027), so the field is the item's name |
| `blueprintPools` | 2 | A blueprint listed twice in a reward pool; one with a presumed replacement, one with `expected: null` | **No** — `blueprintPools` are not imported (REQ-P4K-027, Out of scope) |
| `entities` | 7 | Fuel nozzles whose AttachDef type is `DockingCollar`; expected `FuelNozzle` for `attachType` and `cgItemType`. The note says the game data defines no `FuelNozzle` type | Yes — the item's type (REQ-P4K-017) |
| `entities` | 7 | Seven maxOx guns (code `MXOX`) resolving to manufacturer "Aegis Dynamics"; expected `maxOx`. The cause is one manufacturer record whose name key points at Aegis's | Yes — manufacturer name (REQ-P4K-020) |
| `contracts` | 3 | A Red Wind contract tier's reputation faction points at another faction | **No** — contracts are not imported (Out of scope) |

**Total 24; 19 in imported domains, 5 outside.**

Checked against scmdb's own output of the same build:

- all 5 `blueprintRecords` GUIDs are `guid` values in `crafting_blueprints` (1,618 blueprints), and
  the raw value is there as described (`modifierAtStart` 9.0, `modifierAtEnd` 1.1 for the first);
- all 14 `entities` GUIDs are blueprints' `productEntityClass`, i.e. items a blueprint produces;
- both pool GUIDs are keys of `blueprintPools` in `merged` (12 and 11 blueprints);
- `crafting_blueprints` carries no `knownIssue`; `merged` carries 7 occurrences (contracts and
  pools), so the annotation is not uniformly present in scmdb's outputs and is no substitute for the
  raw value from the P4K.

## Licence and terms

What scmdb publishes (About and Imprint pages, read from the bundle):

1. "SCMDB is licensed under a Creative Commons Attribution-NonCommercial-NoDerivatives 4.0
   International License" (link to `creativecommons.org/licenses/by-nc-nd/4.0/`).
2. Imprint, "Copyright": content created by the site operator is subject to German copyright law;
   duplication, processing, distribution and exploitation beyond copyright law need the author's
   written consent. The imprint names the operator and a contact address (not repeated here).
3. Imprint, "Third-Party Licenses": the German translation data comes from the Star Citizen Wiki
   (Deutsch) under CC BY-SA 4.0, described as "the same license used by this project". This
   contradicts item 1; the About page's explicit SCMDB licence is the specific statement.
4. The site is an unofficial fan site; content not authored by the host belongs to its owners
   (CIG's data stays CIG's).

Verdict for the Basetool:

- **Copying the file or its notes into the Basetool and exposing them** (UI, Exchange API to
  VerseKit) is distribution. Turning entries into correction records with a shortened reason is an
  adaptation, and CC BY-NC-ND does not allow sharing adapted material. **Not permitted as is.**
- **NonCommercial:** the Basetool is a squadron tool; whether its use counts as non-commercial was
  not assessed (open). ND already blocks the import path regardless.
- **What remains possible without consent:** reading the file as a list of *where to look*. The
  underlying facts (a GUID, a wrong value in CIG's data, its plausible intended value) are CIG's data
  and can be re-checked by a Basetool admin against the P4K catalogue; a correction written from
  that check, with the admin's own one-sentence reason, is the Basetool's own record. Whether that is
  clean enough is a legal judgement this note does not make (open).
- **Clean route:** ask the operator, through the contact in scmdb's imprint, for written permission
  to import the entries as a starting set, with the attribution they want (e.g. "Seed: scmdb.net,
  cig_data_issues-<build>").

## Mapping to #2899's field-level corrections

| Entries | Record (GUID kind in the Basetool) | Field | Raw (from the P4K) | Corrected | Fits one record + one field? |
| --- | --- | --- | --- | --- | --- |
| 3 modifier errors | blueprint (`p4k_uuid`) → modifier of slot + `property_key` | range start of the band | 9.0 | 0.9 | Only with a sub-record path: the modifier is a `blueprint_requirement_modifier` row of a group, not a column of the blueprint |
| 2 label errors | produced item (`game_item`, entity GUID) | `name` | the wrong string | the expected name | Yes, on the item, not the blueprint. The name is a game text (REQ-P4K-033) |
| 7 fuel nozzles | item (entity GUID) | type (`p4k_type`) | `DockingCollar` | `FuelNozzle` | Yes, but the target value is not a type the game defines, so it may be an unknown value for the Basetool's type handling (REQ-P4K-019 counts unknown values) |
| 7 maxOx guns | manufacturer (`p4k_uuid` of the MXOX record) | `name` | Aegis Dynamics | maxOx | Yes, as **one** correction on the manufacturer; seven item-level corrections would be the wrong record |
| 2 pool duplicates | pool | list membership | — | a replacement or `null` | No; pools are not imported |
| 3 contracts | contract `debugName` | `factionGuid` | — | Red Wind Linehaul | No; contracts are not imported and are not GUID-keyed |

So the 19 in-domain entries yield **13 corrections**: 3 modifier values, 2 item names, 7 item types
and 1 manufacturer name.

The "CIG fixed it" path of #2899 matches scmdb's own rule: an annotation whose `expected` equals the
current value is dropped. scmdb does not model the second case (raw value changed to something
else); #2899's "raw value changed, review the correction" has no counterpart there.

## Open

- **GUID identity with the p4kx catalogue:** scmdb's blueprint `guid` and `productEntityClass` are
  DataForge record GUIDs extracted with unp4k; that they equal the Basetool's `blueprint.p4k_uuid`
  and `game_item.p4k_uuid` from the p4kx catalogue is likely but was not checked against an imported
  catalogue.
- **Which modifier field `modifierAtStart` is in the Basetool's `ranges`:** read from scmdb's
  output, not from the p4kx contract.
- **NonCommercial and the facts-versus-expression question** under German law: not assessed.
- **That VerseKit 4.12 applies the file** (#2899): not verified from a VerseKit source.
- **Whether scmdb would grant permission**, and with which attribution: unasked.
- **History per build:** scmdb serves only the current LIVE and PTU files; earlier issues and fixes
  cannot be read from it.
