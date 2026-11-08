# ADR-0343 — A personal blueprint is keyed by its produced item's GUID

- **Status:** Accepted
- **Date:** 2026-11-05
- **Deciders:** @greluc
- **Related:** the P4K catalogue spec (REQ-P4K-029, REQ-INV-070) · REQ-INV-006 · REQ-INV-019 · the exchange spec · the audit
  spec · the observability spec · ADR-0335 · ADR-0336 · #2893 · map #2884 ·
  [p4kx catalogue contract](https://github.com/p4kx-org/p4kx/blob/main/docs/basetool-catalog.md)

## Context

A member owns a product — what a blueprint produces — not a single recipe record: Game.log entries
carry only the display name and cannot tell recipes with the same output apart. The personal
blueprint is therefore keyed by the product, today the normalised `output_name` (`product_key`),
and the material exchange, the default blueprints and the exchange change feed carry that name key.

The P4K catalogue gives every blueprint `producedItemGuid`, the DataForge GUID of the item it
produces. Items and ships are seeded match-only from the catalogue, so a produced item need not
have a matched `game_item` row.

## Decision

1. **The unit stays the product; its identity becomes the produced item's DataForge GUID.** The
   name is used only to resolve to that key.
2. **The GUID comes from the P4K import.** The import stores `producedItemGuid` on the `blueprint`
   row, so the key does not depend on a matched `game_item` row. Only the DataForge GUID counts,
   never a Wiki UUID.
3. **The Game.log chain ends at a GUID.** The chain stays (scmdb `tag` → normalised name → alias →
   pack-tag strip → fuzzy). A name that resolves to several GUIDs becomes `SUGGESTED` with the
   candidates; the member's pick is learned as an alias. No match stays `UNMATCHED` /
   `SUGGESTED`.
4. **Existing rows are backfilled** through `product_key` → `blueprint.output_name` →
   produced-item GUID. A row that resolves to no GUID or to several keeps its name key as a
   fallback, goes on the review list of the change report (ADR-0336), and is upgraded
   automatically by a later P4K run that resolves it.
5. **A fallback row merges into the GUID row.** When a member holds a fallback row and the same
   product arrives by GUID, the two become one GUID row: earliest `acquired_at`, note kept,
   fallback row removed. The merge is audited.
6. **Every dependent reference moves in the same step:** `blueprint_external_alias`,
   `default_blueprint`, the material exchange's `item_product_key`, the exchange change feed
   `entity_key` and journal, and the audit details. One key in the system, no translation layer.
7. **The external exchange gains `guid` on blueprint references.** `bt` is deprecated, kept for
   90 days from the release that adds `guid`, announced in the exchange docs, watched with a usage
   metric, and then removed.
8. **The key change is its own migration step**, directly after the import runs and the change
   report's review list exist (ADR-0335, ADR-0336), independent of the blueprint precedence.

## Consequences

- Two recipes with the same output stay one personal blueprint, and a renamed product keeps its
  owners.
- The blueprint name-matching requirements (REQ-INV-006, REQ-INV-019) resolve to a GUID key; the
  exchange spec and `docs/exchange/` gain the `guid` field and the `bt` deprecation; the
  observability spec gains the `bt` usage metric; the audit spec gains the merge event.
- A `BACKFILL_AMBIGUOUS` sync-report event (a row's Wiki UUID differs from its P4K GUID) does not
  touch this key, which reads only the produced-item GUID.
