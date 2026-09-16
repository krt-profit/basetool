# ADR-0023 — Merge UEX duplicate companies onto one manufacturer via a company-id alias table

- **Status:** Accepted
- **Date:** 2026-06-18
- **Deciders:** @greluc
- **Related:** spec REQ-DATA-004 · REQ-DATA-005 · migration `V162` · supersedes the "each company keeps its own row" decision recorded in `V158` / REQ-DATA-004

## Context

UEX Corp's `/companies` feed ships several **distinct** records for the **same real-world
manufacturer**. They carry different integer ids and frequently different names, and the item-side
record and the vehicle-side record split a brand's catalogue across them. Observed in prod (v0.5.9):

|  Brand  |           item-side record           |                vehicle-side record                 |
|---------|--------------------------------------|----------------------------------------------------|
| Esperia | `87 "Esperia"` (43 items)            | `278 "Esperia Incorporation"` (7 ships + 15 items) |
| DMC     | `70 "Denim Manufacture Corporation"` | `287 "DMC"`                                        |
| Covalex | `62 "Covalex Shipping"`              | `293 "Covalex"`                                    |

`V158` (REQ-DATA-004) stopped the original `manufacturer.abbreviation` `UNIQUE`-constraint crash by
dropping the constraint and letting each company keep its **own** `manufacturer` row. That removed
the crash but **split the brand**: the item sync resolves the manufacturer by `id_company` and the
vehicle sync resolves it by `id_company` too, yet the two surfaces reference *different* company ids
for the same brand. No single `manufacturer` row keyed on one `uex_company_id` can satisfy both
lookups, so a brand's ships and items end up on separate rows and the manufacturer appears twice in
every picker.

The empirical check (the full `/companies` feed, 311 rows) found that **every** pair of companies
sharing a nickname-derived abbreviation is the same brand — there is no case of two genuinely
different manufacturers sharing a code. So the duplicates should collapse, not coexist.

## Decision

We will let a manufacturer **own several UEX company ids**. A new `manufacturer_uex_company` alias
table maps each UEX company id (canonical *and* duplicate) to exactly one `manufacturer` row, and
the item and vehicle syncs resolve the manufacturer **through that table**.

- The **canonical** company of a brand is the **lowest `uex_company_id`**. Because the manufacturer
  sync processes the feed in ascending-id order, the canonical company is persisted first and owns
  the row's display identity (`name` / `abbreviation` / `uex_company_id`).
- Every other company of the brand (matched by name or shared abbreviation) **merges** into the
  canonical row: it registers its id as an alias and only OR-s the `is_item_manufacturer` /
  `is_vehicle_manufacturer` flags. It never overwrites the canonical identity — which is what keeps
  the merge stable (ping-pong-free) across daily syncs.
- `V162` performs the one-time reconciliation of the existing split rows: repoint the `ship_type` /
  `game_item` FKs onto the canonical row, carry the SC Wiki / P4K cross-source links over, OR the
  flags, delete the loser rows and seed the alias table.

This **supersedes** the `V158`/REQ-DATA-004 stance that "two companies sharing an abbreviation each
keep their own row".

## Consequences

- A brand's ships and items reunite on one manufacturer row, and the manufacturer appears once in
  every picker.
- `UexVehicleService` now needs the vehicle's `id_company`, so `UexVehicleDto` gains an `idCompany`
  field; both syncs resolve through `ManufacturerUexCompanyRepository.findManufacturerByUexCompanyId`.
- A duplicate company is re-resolved via the slower name/abbreviation path on every sync (it never
  caches its own id on the row). At ~311 companies this is negligible.
- `manufacturer.uex_company_id` stays UNIQUE and now means "the **canonical** id of this brand"; the
  full id set lives in the alias table. Any future code that needs "the manufacturer for UEX company
  X" must go through the alias table, not `manufacturer.uex_company_id`.
- The `V162` dedup is destructive (deletes loser rows); it is up-only and exercised by
  `V162MigrationTest`.

## Alternatives considered

- **Keep two rows, dedupe only in the UI** — rejected: it does not reunite ships and items (they
  stay on different rows with different FKs) and leaves the picker showing the brand twice.
- **Pick one canonical row, drop the duplicate, no alias table** — rejected: a single `(name,
  uex_company_id)` cannot satisfy both the item lookup (id `87`) and the vehicle lookup (id `278` /
  name `"Esperia Incorporation"`), so whichever surface's id/name the canonical row does not carry
  loses its manufacturer link.
- **Drop `external_uuid`/`uex_company_id` uniqueness and resolve purely by name** — rejected:
  `uex_company_id` and the cross-source UUID keys are load-bearing identity (the §3.6 invariant, the
  SC Wiki / P4K join); names are the least stable signal.
