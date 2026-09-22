# ADR-0046 — Blueprint craftability bridges PIECE-material ITEM ingredients

- **Status:** Accepted
- **Date:** 2026-06-27
- **Deciders:** @greluc
- **Related:** spec [REQ-INV-048](../specs/personal-inventory-blueprints.md) · supersedes the ingredient-scope decision of [ADR-0035](0035-blueprint-craftability-from-own-stock.md) · issue [#781](https://github.com/krt-profit/basetool/issues/781)

## Context

[ADR-0035](0035-blueprint-craftability-from-own-stock.md) decided that the Personal Inventory
blueprint craftability calculation evaluates **only RESOURCE** (commodity) ingredients, and surfaces
**ITEM** ingredients as "not evaluated" — an explicit v1 carve-out.

That carve-out leaves a real gap. The SC Wiki blueprint payload classifies hand-mined gems —
Hadanite, Beradom and the like, which the player counts in whole **pieces** ("Stück") — as
`kind: "item"`, so they persist as **ITEM** ingredients resolving to a `game_item`, not as RESOURCE
lines. They render in the recipe's ingredient list with a piece count (e.g. "Beradom · 58x"), but the
craftability view never counts them: a recipe whose only missing input is such a gem still reports
its RESOURCE materials and silently omits the gem, even though the user holds it in "My Inventory" as
a PIECE `material`. For many real recipes (e.g. the A03 Sniper Rifle's Hadanite, the 7MA 'Lorica's
Beradom) this makes the craftability breakdown incomplete.

The rest of the app already solved the same mismatch. The **job-order** path
(`JobOrderItemService.bridgedMaterial`, issue #304) bridges a **non-craftable** ITEM ingredient that
exists in the shared `material` catalogue by name to that `material`, treating it as a (PIECE)
procurement requirement on the very row Lager and Refinery use — while a **craftable** ITEM (the
output of another blueprint) stays a genuine sub-assembly handled as its own line. Craftability and
job orders read the same recipe graph and the same `material` rows, so they should agree on which
gems a recipe needs.

## Decision

We will **evaluate non-craftable, name-resolvable ITEM ingredients in the craftability calculation**,
bridging each to its PIECE `material` exactly as the job-order path does — superseding ADR-0035's
"RESOURCE only" ingredient-scope decision (ADR-0035 otherwise stands).

- An ITEM ingredient whose resolved `game_item` is **not** the output of any active blueprint (i.e.
  not itself craftable) and whose name matches a `material` (case-insensitive, on the resolved game
  item's canonical name — the same match `JobOrderItemService.resolveItemMaterial` makes) is
  **bridged** to that `material` and evaluated as a requirement, with the ingredient's whole-unit
  count as its per-craft quantity (rounded to a whole piece, as everywhere else).
- A **craftable** ITEM (a genuine sub-assembly) and an unresolved or name-unmatched ITEM carry no
  material and stay flagged **"not evaluated"**; a recipe with no evaluable requirement at all is
  still reported as not assessable.
- The bridge is resolved **once for the caller's whole owned set**: one query for the craftable
  game-item ids among the recipes' ITEM lines, one batched load of the bridgeable game items'
  canonical names, and one case-insensitive material-by-name query — so the extra cost is a fixed
  handful of queries regardless of how many blueprints are owned, honouring ADR-0035's bounded-cost
  claim. The batched query folds the candidate names caller-side (`Locale.ROOT`) and the column
  DB-side (`LOWER()`), which is byte-identical to the job-order bridge's all-DB fold for the ASCII
  commodity / gem names this matches; a hypothetical non-ASCII material name could fold differently
  — an accepted, documented limitation (the gems this targets are ASCII), called out on
  `MaterialRepository.findByNameInIgnoreCase`.
- It remains **read-only and strictly owner-scoped**; no schema change, no new endpoint. Bridged PIECE
  materials flow through the existing per-material breakdown, quality floor, effective-quality and
  refinery-fold-in logic unchanged — the breakdown already labels each row by its material's
  `quantityType` ("SCU" / "Stück"), so a bridged gem renders in whole pieces with no UI change.

## Consequences

- The craftability breakdown is complete: a recipe's hand-mined gems are counted, can be the limiting
  material, and drive their slot's quality slider default like any other material — matching what the
  job-order requirement for the same item shows.
- Craftability and job orders now share one definition of "which ITEM ingredients are raw materials",
  so the two never diverge for the same recipe. The bridge logic is mirrored (not shared) between
  `BlueprintCraftabilityService` and `JobOrderItemService`: the former needs the batched form to keep
  cost bounded across many owned blueprints, the latter the per-ingredient form for a single order;
  both carry cross-references, and a future change to "what counts as a procurement component" must be
  made in both.
- `hasItemIngredients` now means "the recipe still needs an ITEM left **un**evaluated" (a craftable
  sub-assembly or an unresolved item), not "the recipe has any ITEM at all"; the UI's "not evaluated"
  hint therefore fires only when something genuinely cannot be assessed.
- The "is this game item craftable?" exclusion costs one extra batched query per craftability request;
  it is what keeps a craftable sub-assembly from being mistaken for a raw material.

## Alternatives considered

- **Leave ITEM ingredients unevaluated (ADR-0035 status quo)** — rejected: it omits real, in-stock
  materials from the craftability answer and disagrees with the job-order requirement for the same
  recipe, which is exactly the confusion the feature set out to remove.
- **Reuse `JobOrderItemService.bridgedMaterial` per ingredient** — rejected: it issues two queries per
  ITEM line, so across a user who owns hundreds of blueprints it would turn one bounded request into
  hundreds of lookups, breaking ADR-0035's bounded-cost guarantee. The batched resolution keeps the
  cost fixed.
- **Match the bridge on the persisted wiki name snapshot instead of the resolved game item's name** —
  rejected: it would save one game-item load but could resolve a different `material` than the
  job-order path (which prefers the canonical game-item name), reintroducing the very divergence this
  decision removes.
- **Reclassify these gems as RESOURCE materials at sync time** — rejected: it would fight the wiki's
  own `kind` classification, ripple into every other consumer of the recipe graph, and still need a
  bridge for items the reclassification missed; bridging at read time is local and reversible.
