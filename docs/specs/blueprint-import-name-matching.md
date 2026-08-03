> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-06-21.
> **Owner area:** INV · **Related ADRs:** [ADR-0008](../adr/0008-refinery-extract-json-contract.md)
> (its additive-v1 evolution rule is mirrored by REQ-INV-014),
> [ADR-0033](../adr/0033-scmdb-net-export-and-structural-tag-matching.md) (scmdb.net export +
> structural tag match) · **Plan:** `SC_WIKI_SYNC_PLAN.md` (historical)

# Blueprint import — product name matching

## Context & goal

The personal-inventory blueprint feature ([#327](https://github.com/greluc/basetool/issues/327))
lets a user own crafting blueprints and import them from the game. The in-game
`"Received Blueprint: <name>"` notification — captured by the SCMDB log-watcher / Basetool
Blueprint Extractor — is matched, by name, against the blueprint **product master**.
`BlueprintProductService` builds that master from `blueprint.output_name`, which
`ScWikiBlueprintSyncService` syncs verbatim from the SC Wiki `/api/blueprints` feed. So the
import only finds a product if the in-game name it sends equals a stored `output_name` — after
both are normalized. This spec governs that name-matching contract, the correction layer that
keeps it working when CIG mislabels a name at the source, and the tolerance rules for the
uploaded export file's envelope.

## Requirements

### REQ-INV-006 — Import matches on the normalized `output_name`

The blueprint import and product search match the in-game/log product name against the master
list of `blueprint.output_name`s, comparing both sides through the single
`BlueprintNameNormalizer` (trim, collapse internal whitespace, fold Unicode quote/apostrophe
glyphs to ASCII, lowercase). Normalization is conservative — it must not strip punctuation
wholesale, which could collide genuinely distinct products. The master product list is built
from `output_name` (not the resolved `output_item`/`game_item` name), so a blueprint whose
`output_name` is wrong cannot be matched under its true name.

**Acceptance**

- [ ] Search and import normalize names through `BlueprintNameNormalizer` before comparing.
- [ ] The product master is grouped by the normalized `output_name`.
- [ ] Casing / surrounding & internal whitespace / quote-glyph differences do not defeat a match.

**Enforced by:** `BlueprintNameNormalizerTest`, `BlueprintProductServiceTest` · **Code:**
`BlueprintNameNormalizer`, `BlueprintProductService`, `ScWikiBlueprintSyncService` · **Issues:**
[#327](https://github.com/greluc/basetool/issues/327)

### REQ-INV-007 — Curated, guarded, self-healing correction of CIG-mislabeled `output_name`s

Some blueprints carry a wrong English `output_name` because CIG's game data (`Data.p4k`) has a
mislabeled localization string; the SC Wiki mirrors it faithfully and the basetool copies it, so
the correct in-game name exists under no entry and the import can only fuzzy-suggest a wrong
piece. The sync applies a curated correction layer — a small, immutable, developer-maintained
code map (`BlueprintOutputNameOverrides`) keyed on the structurally-correct `scwiki_key`, each
entry `(expectedWrongName, correctedName)` — immediately before persisting `output_name`. The
correction is:

- **Guarded:** it fires only when the incoming `output_name`, normalized via
  `BlueprintNameNormalizer`, equals the equally-normalized `expectedWrongName` for that key.
  Otherwise the upstream value passes through unchanged.
- **Self-healing:** once CIG (or the Wiki) fixes or changes the string the guard stops matching
  and upstream wins automatically — the override never overwrites a name it does not recognise,
  so it cannot silently go stale.
- **Observable when obsolete:** when a registered override's `scwiki_key` is present in the feed
  this run but its `expectedWrongName` no longer matches (so the override did not fire), the sync
  emits a `SyncEventType.BLUEPRINT_NAME_OVERRIDE_OBSOLETE` event so an operator removes the entry.

The correction is applied identically on the P4K seed path (`P4kImportService.maybeSeedBlueprint`,
which also writes `output_name`) for consistency. It touches **only** `output_name` — never the
resolved `output_item`/`game_item` name. The seeded set are the two confirmed CIG bugs:

|                  `scwiki_key`                   | wrong `output_name` | corrected (in-game) name |
|-------------------------------------------------|---------------------|--------------------------|
| `BP_CRAFT_qrt_specialist_heavy_arms_01_01_13`   | `Antium Helmet Jet` | `Antium Arms Maroon`     |
| `BP_CRAFT_qrt_specialist_heavy_helmet_01_01_12` | `Antium Core Jet`   | `Antium Helmet Jet`      |

A DB-backed, admin-managed table is an explicit non-goal: this is a small, developer-curated set
of known upstream bugs that need the correct name in code review, not a runtime-editable list.

**Acceptance**

- [ ] The correction fires (stores `correctedName`) when the registered wrong name is present.
- [ ] It passes the upstream value through when CIG fixed/changed the name (different/correct
  incoming name) and for any unregistered `scwiki_key`.
- [ ] The guard is normalization-insensitive (differing case/spacing still corrects).
- [ ] A `BLUEPRINT_NAME_OVERRIDE_OBSOLETE` event is emitted when a registered key is seen but its
  wrong name is gone.
- [ ] The two seed entries above are present.

**Enforced by:** `BlueprintOutputNameOverridesTest`, `ScWikiBlueprintSyncServiceTest`,
`P4kImportServiceTest` · **Code:** `BlueprintOutputNameOverrides`,
`ScWikiBlueprintSyncService.upsertBlueprintWithinTransaction`,
`P4kImportService.maybeSeedBlueprint` · **Issues:**
[#327](https://github.com/greluc/basetool/issues/327)

### REQ-INV-014 — Tolerant export-envelope parsing (additive v1 evolution)

The import accepts **four** upload shapes: a bare JSON array of entries, the SCMDB log-watcher
document, the Basetool Blueprint Extractor `BlueprintExport` document (`schemaVersion` 1), and the
[scmdb.net](https://scmdb.net) profile / tracking export (`version` 3). In every document form only
the top-level `blueprints` array is consumed; every other envelope field is tolerated and ignored
(`@JsonIgnoreProperties(ignoreUnknown = true)` on `BlueprintExportFileDto`). The extractor evolves
its export contract additively within schema version 1 — the same rule ADR-0008 fixes for the
refinery extract (precedent: `capturedAt` on `sourceImages`, 2026-06-11) — so new nullable envelope
fields appear without a version bump and must never break the import.

One such extractor field is `additionalSourceFolders` (`List<String>`, nullable, default `null`):
the extra game-channel folders the extractor scanned beside its primary `sourceFolder`
(currently the `HOTFIX` sibling of `LIVE`). Because the extractor encodes defaults, the key is
always present in its exports — as JSON `null` when only the primary folder was scanned. The
field is mirrored on `BlueprintExportFileDto` for contract explicitness but is provenance only;
the import does not consume it.

The **scmdb.net export** is a manually-curated checklist rather than a `Game.log` capture, so its
`blueprints` entries differ from the watcher / extractor shape and the parser adapts to them at the
entry level (`BlueprintExportEntryDto`):

- the product name is under **`name`**, read as an alias of the watcher/extractor `productName` key
  (Jackson `@JsonAlias({"name"})`);
- a **`completed`** flag is present; an entry with `completed == false` is a not-yet-unlocked
  placeholder and is **skipped** before resolution. A `null` flag (watcher / extractor exports list
  only acquired blueprints) and `true` both count as owned;
- a structural **`tag`** (the DataForge blueprint key) drives the tag match (REQ-INV-019), and the
  parse de-duplication itself keys on this `tag` when present (else on the trimmed product name), so
  two distinct blueprints scmdb.net displays under one name never collapse into one (REQ-INV-019);
- there is **no acquisition timestamp** (`suggestedAcquiredAt` stays `null`); the sibling top-level
  `missions` array, `profile`, `url`, and `favorite` are all ignored.

**Acceptance**

- [ ] Bare-array, SCMDB-document, Extractor-document, and scmdb.net-document uploads all parse.
- [ ] An Extractor export with `additionalSourceFolders` populated, explicitly `null`, or absent
  (older extractor version) imports identically.
- [ ] An scmdb.net entry naming the product under `name` resolves the same product as `productName`.
- [ ] An scmdb.net entry with `completed == false` is dropped; `true` / absent is kept.
- [ ] The scmdb.net `missions` array, `profile`, `url`, and `favorite` never affect the import.
- [ ] Unknown envelope fields never fail the parse.

**Enforced by:** `BlueprintImportServiceTest` (`preview_acceptsBareArrayForm`,
`preview_acceptsFullScmdbWatcherDocumentShape`, `preview_acceptsBpExtractorReceivedAtFormat`,
`preview_acceptsBpExtractorWithAdditionalSourceFolders`,
`preview_acceptsBpExtractorWithNullAdditionalSourceFolders`, `preview_acceptsScmdbNetNameAlias`,
`preview_acceptsFullScmdbNetProfileExportIgnoringMissions`, `preview_skipsNotCompletedScmdbNetEntries`,
`preview_acceptsBareArrayOfScmdbNetEntries`) · **Code:** `BlueprintExportFileDto`,
`BlueprintExportEntryDto`, `BlueprintImportService#parse` · **Issues:**
[#327](https://github.com/greluc/basetool/issues/327)

### REQ-INV-015 — Variant family key (cosmetic-variant grouping)

Two features that count blueprint ownership across members — the item-order coverage view
(`REQ-ORDERS-015`) and the org-unit availability overview (`REQ-INV-012`) — group a base item and
its **cosmetic variants** into one craftable *family*, so owning any family member counts. The
grouping is **name-based** (the catalog's `game_item.is_base_variant` / `class_name` are nullable,
ship dark by default, and unreachable from a `PersonalBlueprint`, which is keyed by `product_key`
string only), via a single `BlueprintVariantFamilyResolver.familyKey(name)` both sides call:

- A cosmetic variant is the base name with a **quoted nickname** spliced in (`Fresnel Energy LMG` →
  `Fresnel "Molten" Energy LMG`; `Novian Crossbow` → `Novian "Wildshot" Crossbow`). The family key is
  the `BlueprintNameNormalizer`-normalized name with every ASCII double-quoted span removed and
  whitespace re-collapsed — so all family members reduce to the same key. The rule is **conservative**:
  it keeps the full unquoted residue (incl. the weapon-type word like `Rifle`/`SMG`/`Crossbow`), which
  prevents cross-family collisions (`Sawtooth "Sirocco" Combat Knife` ≠ a `Karna` rifle) and leaves
  genuinely distinct unquoted products distinct (ship sub-models `Aurora MR` vs `Aurora LN`).
- **Magazines are never variants.** A name detected as an ammo container — a `(NNN cap)` capacity
  parenthetical (a digit run + the whole word `cap`), or the standalone noun `magazine` / `battery` /
  `ammo box` — gets an **atomic** family key that can only equal an identical magazine. It folds into
  no weapon family, and two capacities of the "same" magazine stay distinct.
- A small, curated, guarded/self-healing **alias map** (`BlueprintVariantAliasOverrides`, mirroring
  `BlueprintOutputNameOverrides`, `REQ-INV-007`) canonicalizes the residual same-line cases the
  structural rule cannot merge — base-name spelling drift (`Pulse "Blacklist" Pistol` → `pulse pistol`
  folded onto `pulse laser pistol`) and confirmed unquoted sub-models (`Salvo Esteban Frag Pistol`,
  `Model II Arclight`). Each entry is a deliberate game-domain judgement; a non-matching key is a no-op.

**Acceptance**

- [ ] `familyKey(base) == familyKey(variant)` for every quoted-nickname cosmetic variant, in both
  directions, surviving the normalizer (case, spacing, curly quotes, apostrophes inside the nickname).
- [ ] A magazine's family key is atomic and never equals its weapon's; the `cap` test never matches a
  substring (`capacitor`/`capstone`) nor a non-capacity parenthetical (`(Modified)`).
- [ ] Genuinely distinct unquoted products (ship sub-models, cross-family names) do not merge.
- [ ] A registered alias canonicalizes; an unregistered key passes through unchanged.

**Enforced by:** `BlueprintVariantFamilyResolverTest`, `BlueprintVariantAliasOverridesTest` ·
**Code:** `BlueprintVariantFamilyResolver`, `BlueprintVariantAliasOverrides` · **Issues:** —

### REQ-INV-019 — Structural tag match for the scmdb.net export

The scmdb.net export uniquely carries, per blueprint, the **DataForge blueprint key** under `tag`
(e.g. `BP_CRAFT_behr_rifle_ballistic_02_civilian`). That key is the same identifier the basetool
stores as `blueprint.scwiki_key` and already matches against in the P4K import
(`findFirstByScwikiKeyOrderByScwikiUuidAsc`). The blueprint import therefore resolves an entry's
`tag` **first**, before the name chain (REQ-INV-006): `BlueprintProductService.scwikiKeyToProductKeyIndex()`
builds a `scwiki_key → product_key` index over all active recipes, and a tag that maps to a known
product short-circuits to a `MATCHED` resolution carrying that product. On any miss the entry falls
through to the existing exact → alias → fuzzy name chain unchanged, so the tag step is **strictly
additive** and never regresses an import. The index is built lazily — only when at least one parsed
entry carries a `tag` — so the watcher / extractor / bare-array imports do not pay for it.

The match is deliberately robust and conservative:

- **Case-insensitive.** The Wiki keeps the DataForge key in CamelCase (`BP_CRAFT_AMRS_LaserCannon_S1`)
  while scmdb.net lower-cases it; the index and the lookup both fold to lower case (`Locale.ROOT`).
- **Unambiguous-only.** `scwiki_key` is not UNIQUE; if one key maps to two recipes with **diverging**
  output names (hence diverging product keys) it is **excluded** from the index, so the tag match
  never silently picks an arbitrary product — that entry falls back to the name chain.
- **De-duplication keys on the tag.** Because a tag identifies a blueprint structurally, the parse
  de-duplicates by `tag` when present (else by name). Two **distinct** blueprints scmdb.net displays
  under one name — e.g. a genuine piece and a CIG-mislabeled one both shown as `Antium Core Jet`
  (REQ-INV-007) — therefore stay separate and each resolves via its own tag, instead of collapsing
  by name and importing only one of the two owned products.
- **Immune to the name-mislabel problem.** Because the tag identifies the blueprint structurally, an
  entry still resolves correctly even when the blueprint's `output_name` is one of the CIG-mislabeled
  names REQ-INV-007 has to correct, or a cosmetic-variant spelling the name match would only
  fuzzy-suggest.
- **Self-healing for future name imports.** A tag-resolved entry whose `name` does not normalize to
  the resolved `product_key` still learns a `blueprint_external_alias` on apply (the existing
  `learnAliasIfManual` path), so the next name-only import of that name auto-resolves.

The tag match reuses the `MATCHED` status (it *is* an automatic match); no new status enum value and
no API/`openapi.json` change are introduced.

**Acceptance**

- [ ] An scmdb.net entry whose `tag` is in the index resolves to that product even when its `name`
  would not match any product.
- [ ] The tag match is case-insensitive (CamelCase Wiki key vs lower-cased scmdb.net tag).
- [ ] A `tag` absent from the index (unsynced or ambiguous blueprint) falls back to the name chain
  without error.
- [ ] A `scwiki_key` shared by two recipes with different output names is excluded from the index.
- [ ] Two scmdb.net entries sharing a display name but carrying different tags stay separate and each
  resolves via its own tag (no name-collapse).
- [ ] Watcher / extractor exports (no `tag`) are unaffected — the step is a no-op for them.

**Enforced by:** `BlueprintImportServiceTest` (`preview_matchesByTagWhenNameWouldNotMatch`,
`preview_tagMatchIsCaseInsensitive`, `preview_tagMissFallsBackToNameChain`,
`preview_keepsDistinctTagsUnderSameName`, plus `preview_acceptsBpExtractorReceivedAtFormat` for the
tag-less no-op), `BlueprintProductServiceTest`
(`scwikiKeyToProductKeyIndex_mapsLowercasedKeyToProductKey`,
`scwikiKeyToProductKeyIndex_excludesAmbiguousKeys`) · **Code:**
`BlueprintProductService#scwikiKeyToProductKeyIndex`, `BlueprintImportService#resolveViaTag`,
`BlueprintImportService#parse` · **ADR:**
[ADR-0033](../adr/0033-scmdb-net-export-and-structural-tag-matching.md)

### REQ-INV-020 — Case-insensitive blueprint-alias uniqueness

At most one `blueprint_external_alias` row may exist per `(source_system, LOWER(external_name))`. The
uniqueness rule must match the resolution lookup (`findBySourceSystemAndExternalNameIgnoreCase`),
which folds case because external systems drift casing across patch versions — and because the
structural tag match (REQ-INV-019) now routes many name-mismatching display names through the
alias-learning path, where two differently-cased names for the same product would otherwise each
insert a row.

*Why:* the original V127 constraint was case-sensitive while the lookup was not (the same defect
V146 fixed for `material_external_alias`, REQ-REFINERY-010). Two rows differing only in case could
legally coexist, making the `Optional`-returning lookup throw
`IncorrectResultSizeDataAccessException` → HTTP 500 on the next import touching that name. V176
de-duplicated existing case-variant rows (oldest row per group survives) and replaced the constraint
with the functional unique index `uq_blueprint_external_alias_source_lower_name`.

**Acceptance**

- [ ] The DB rejects a second alias whose `(source_system, external_name)` differs from an existing
  row only in case (V176 unique index on `(source_system, LOWER(external_name))`).
- [ ] `BlueprintImportService` learns an alias at most once per `(source, LOWER(external_name))`: a
  stored case-variant suppresses a new insert pre-emptively, and two case-only variants in one apply
  request collapse to a single learned alias (no duplicate, no DB error).
- [ ] The case-insensitive resolution lookup can never observe two candidate rows.

**Enforced by:** `BlueprintImportServiceTest` (`apply_doesNotDuplicateAliasWhenOneAlreadyExists`,
`apply_doesNotDuplicateAliasWhenCaseVariantAlreadyExists`,
`apply_suppressesCaseVariantDuplicateWithinSameRequest`) · **Code:**
`BlueprintImportService#learnAliasIfManual`, `BlueprintExternalAliasRepository`,
[`V176__make_blueprint_alias_uniqueness_case_insensitive.sql`](../../backend/src/main/resources/db/migration/V176__make_blueprint_alias_uniqueness_case_insensitive.sql)
· **Precedent:** REQ-REFINERY-010 / V146 (material aliases)

### REQ-INV-021 — Localised client names: seeded aliases for the German capacity suffix

The SC Extractor reads blueprint notifications from **localised** Star Citizen clients, so
`productName` can arrive spelled the way a non-English client renders it. The German community
translation keeps the base item name in English and translates only the parenthetical capacity unit:

```
S71 Rifle Magazine (30 cap)      ← English client
S71 Rifle Magazine (30 Schuss)   ← German client
```

`blueprint_external_alias` rows mapping the German spelling onto the English catalogue product are
**seeded** for every active craftable whose `output_name` carries a `(N cap)` suffix.

*Why seed rather than match at runtime:* `productName` is written byte-verbatim from the game log on
purpose — it **is** the key this chain resolves on — so the extractor must not fold the suffix
itself; that would invent a name the game never wrote and would diverge from what the user sees
in-game. Equally, `BlueprintNameNormalizer` must stay conservative (REQ-INV-006): folding a unit word
inside parentheses would be a per-language word list bolted onto a class whose whole value is that it
only touches whitespace, quote glyphs and case. The alias table is the documented place for a curated
cross-reference, and using it needs no change to the fixed resolution chain.

*Derivation, not a hand-written list:* the German spelling is produced from the local catalogue by
rewriting the suffix, and `product_key` is computed exactly as `BlueprintNameNormalizer` does. The
seed therefore cannot drift from a hand-copied name and covers every `(N cap)` craftable present at
migration time, not merely those observed in a sample corpus.

*`source_system` stays `SCMDB`*, deliberately not a new localisation-specific value:
`BlueprintImportService` hardcodes `SOURCE = BlueprintExternalAliasSource.SCMDB` for its lookup, so
any other value would produce rows the import never queries. The name genuinely is an SCMDB-format
log-export name — the German rendering of one.

**This is a convenience, not a correctness fix.** Without it nothing breaks: the extractor export
carries no structural `tag` (REQ-INV-019 is a no-op for it), the exact match misses, and the fuzzy
matcher resolves these names well above threshold with the correct product at rank 1. The seed
removes a one-time manual pass that would otherwise land on exactly the users localisation support
just enabled, and that reads like the tool failing to recognise ordinary ammunition.

**Known limit, accepted:** the seed is one-shot. Ammo added by a later SC-Wiki sync gets no alias and
falls back to the fuzzy matcher — the same outcome as before the seed, for one item instead of
thirteen. A rerun of the statement is safe and idempotent.

A genuinely different translated name (not just the suffix) is **out of scope**: it scores below
threshold, the matcher correctly declines to suggest, and the user resolves it once — which the
alias-learning path then persists for everyone.

**Acceptance**

- [ ] A German `(N Schuss)` name resolves to the English catalogue product via the alias step, with
  `product_key` identical to `BlueprintNameNormalizer`'s output for the English name.
- [ ] Both the `(N cap)` and `(N Cap)` spellings the English catalogue itself uses are covered.
- [ ] Several catalogue variants of one product yield exactly one alias row.
- [ ] Products without a capacity suffix and soft-deleted rows are skipped.
- [ ] The seed is idempotent and never overwrites a user- or admin-curated alias for the same
  spelling.
- [ ] A German spelling the seed did not cover is still offered by the fuzzy matcher at rank 1.

**Enforced by:** `V228GermanAmmoAliasSeedMigrationTest` (executes the shipped migration file against
fixtures — derivation, case variants, one-row-per-product, skip rules, idempotency, curated-alias
precedence), `BlueprintFuzzyMatcherTest#topSuggestions_stillCatchesAGermanCapacitySuffixTheV228SeedDidNotCover`
· **Code:**
[`V228__seed_german_ammo_capacity_blueprint_aliases.sql`](../../backend/src/main/resources/db/migration/V228__seed_german_ammo_capacity_blueprint_aliases.sql),
`BlueprintImportService#resolveViaAlias` (unchanged) · **Issue:** [#1485](https://github.com/krt-profit/basetool/issues/1485)

## Out of scope

- Non-suffix localisation differences (a genuinely different translated item name) — below the fuzzy
  threshold by design, resolved once by the user and then persisted by the alias-learning path
  (REQ-INV-021).
- The resolved `output_item` / `game_item` name (the recipe-detail view may still show a wrong
  item name) — a separate concern resolved by `external_uuid`, not by this name-correction layer.
- The fuzzy-suggestion ranking of the import matcher itself (it consumes the corrected master).
- The broader SC Wiki / UEX / P4K catalog-sync mechanics, documented in the historical
  `SC_WIKI_SYNC_PLAN.md` and the sync services' Javadoc.

## Open questions

None.
