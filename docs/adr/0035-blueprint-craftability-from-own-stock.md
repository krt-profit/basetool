# ADR-0035 — Blueprint craftability computed from the user's own stock

- **Status:** Accepted — ITEM-ingredient scope extended by [ADR-0046](0046-blueprint-craftability-bridges-piece-item-ingredients.md)
- **Date:** 2026-06-21
- **Deciders:** @greluc
- **Related:** spec [REQ-INV-019](../specs/personal-inventory-blueprints.md) · issue [#781](https://github.com/krt-profit/basetool/issues/781) · ingredient scope extended by [ADR-0046](0046-blueprint-craftability-bridges-piece-item-ingredients.md)

## Context

A user owns blueprints (`personal_blueprint`) and holds material in "My Inventory"
(`InventoryItem`, the `/inventory/my` set, with a real `Material` FK + per-stack quality + SCU
amount), but the two are shown separately. To know whether a blueprint is craftable today the user
has to open each recipe and cross-check it against their stock by hand. #781 asks the blueprint view
to answer, per blueprint: *can I craft it now, how often, with what output stats given my material's
quality, and what is missing* — optionally folding in the yield of refinery orders that have not yet
completed.

Several forces shape the computation, and none are pinned by existing requirements:

- The recipe model gives ingredient quality → stat **multipliers** (`BlueprintRequirementModifier`,
  optionally stepped via segments), but **no absolute base stat** — so "the output stat" can only be
  reasoned about as a multiplier relative to neutral (×1.0).
- Stock of one material is spread across locations and quality tiers; a recipe needs a fixed SCU per
  craft. The user decided (issue thread): pool across locations; consume the best quality first and,
  on shortfall, add the next-best, reporting the SCU-weighted average of the consumed portions.
- The user further decided that stock which would **worsen** the output must be excluded, alongside
  any ingredient `min_quality`.
- "My Inventory" already carries the `Material` FK, so matching is exact — unlike the free-text
  personal-inventory feature, which would force fuzzy name matching.

## Decision

We will compute craftability **server-side, strictly owner-scoped (JWT `sub`), read-only over
existing data** — a new `GET /api/v1/personal-blueprints/craftability?includeRefinery=` returning,
per owned blueprint, the craftable count, per-material availability, effective quality, and missing
SCU. No migration.

- **Stock = the caller's "My Inventory"** (`InventoryItem where user == me`), pooled per material
  across all locations; refinery yield (own `OPEN` + `IN_PROGRESS` orders) is folded in only when
  requested, behind a default-off UI toggle. Both inventory-only and refinery-included figures are
  returned so the toggle switches client-side.
- **Only RESOURCE ingredients** are evaluated (v1); ITEM ingredients are surfaced as "not
  evaluated". *(Extended by [ADR-0046](0046-blueprint-craftability-bridges-piece-item-ingredients.md):
  non-craftable, name-resolvable ITEM ingredients — the hand-mined gems the wiki counts in pieces —
  are now bridged to their PIECE material and evaluated too; only craftable sub-assemblies and
  unresolved items stay "not evaluated".)*
- **Craftable count** `N = floor( min over materials of ( qualifying available SCU / required SCU ) )`,
  required SCU aggregated per material per craft.
- **Effective quality** = SCU-weighted average of the best-quality qualifying stock consumed first,
  over one craft's requirement; it drives the projected stats and becomes the slot slider's default.
- **Quality floor** per material = max of the ingredient's `min_quality` and a **no-degradation
  floor**: the lowest quality at which no slot modifier worsens its stat. Because only multipliers
  exist, "worsening" is defined as a multiplier below neutral (×1.0) for a `higher`-is-better stat,
  above neutral for a `lower`-is-better stat. A modifier that worsens across its entire band imposes
  no floor (treated as inherently penalised) so a recipe never silently becomes uncraftable.
- **Stat projection reuses the existing modifier math verbatim** — the frontend
  `computeModifierValue` is mirrored on the server (`BlueprintModifierMath`) so server and slider
  agree exactly.
- **Quantity unit follows the material, not the field name.** A RESOURCE ingredient may resolve to a
  `PIECE`-quantity material. The calculation runs in the material's own unit on both sides (stock and
  refinery yield are already piece counts for a PIECE material), the per-craft requirement is rounded
  to a whole piece exactly as `JobOrderItemService.roundForQuantityType` does, and the per-material
  breakdown carries the material's `quantityType` so the UI labels amounts "SCU" / "Stück" and formats
  pieces as whole numbers. The `*Scu`-named DTO fields are historical; they carry whichever unit the
  material uses.

## Consequences

- The blueprint view answers the operational "what can I build now?" question in one read; the
  master-detail page already in place is extended, not replaced.
- Server and client share one curve definition; a future change to the interpolation must be made in
  both `computeModifierValue` and `BlueprintModifierMath` (a unit test pins the server copy).
- Defining "worsening" on the multiplier (neutral = ×1.0) is the only interpretation the data
  supports; if the recipe model later gains absolute base stats, the rule can be revisited.
- The endpoint resolves a representative recipe per owned product in one master scan and pools stock
  with two grouped reads, so cost is bounded even for a user who owns the whole catalogue; it is not
  paginated (one user's owned set is small).
- v1 ignores ITEM ingredients and treats committed/assigned stock as available (matching what
  `/inventory/my` shows); both are documented carve-outs, not silent gaps.

## Alternatives considered

- **Free-text personal inventory as the stock source** — rejected: it has no `Material` FK, forcing
  fuzzy name matching; "My Inventory" matches exactly with no migration.
- **Compute on the frontend from the recipe + a stock dump** — rejected: it would ship every stock
  row to the client and duplicate the floor/consumption logic; the server already holds both sides.
- **Per-location craftability** — rejected for v1 in favour of pooling across locations (the common
  "do I have the mats somewhere?" question); per-location is materially more complex in compute and
  UI.
- **Honour `includeRefinery` with a refetch per toggle** — rejected in favour of returning both
  figure sets once so the toggle is instant; the extra refinery read is a few rows for one user.
