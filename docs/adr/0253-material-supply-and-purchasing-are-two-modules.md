# ADR-0253 — Material supply and purchasing are two modules

- **Status:** Accepted
- **Date:** 2026-10-13
- **Deciders:** @greluc
- **Related:** specs [`supply.md`](../specs/supply.md) (REQ-SUP-*) · [`module-boundaries.md`](../specs/module-boundaries.md) (REQ-MOD-001 … 006) ·
  [ADR-0231](0231-the-backend-becomes-a-modular-monolith-one-package-per-domain.md) ·
  [ADR-0232](0232-modules-interact-through-commands-observers-and-after-commit-events.md) ·
  [ADR-0252](0252-stockcommands-gains-the-pool-and-donation-commands.md) ·
  [`SUPPLY_AND_PURCHASING_PLAN.md`](../SUPPLY_AND_PURCHASING_PLAN.md) (E-02, §5.1)

## Context

The Bereich Profit's concept „Handelsbeauftragte" has two halves with different risk. Recording what a
unit needs and what members donate moves no money and can run on its own. Buying material from external
sources moves money out of the Kartellbank and needs appointed people, partners, deliveries and a
settlement. The pilot's abort criterion — falling willingness to donate — can only be measured if the
first half exists before, and independently of, the second.

## Decision

Two modules, cut along that line:

- **`supply`** (rank 11) owns the needs of the raising units and the members' donations, the per-unit
  settings and the feature switch. It depends on `catalogue`, `identity`, `orgunit`, `scope`, `admin`,
  `inventory` and `joborder` through their `api` packages and on nothing above it.
- **`purchasing`** (rank 12) owns delegates, partners, deals and deliveries and builds on `supply.api`,
  `inventory.api` and `bank.api`. It implements `supply`'s `NeedPriceAudience`, so a trade delegate sees
  the purchase fields of a need without `supply` naming `purchasing`.

`supply` is rank 11 and not 10 because its job-order link is validated against `joborder.api` (rank 10);
at rank 11 the rank rule needs no `allow` row. `inventory` never names `supply`: a pledge reaches a Lager
row through the earmark of ADR-0252. The two modules own their tables (`table-ownership.txt`) and
publish only `api` packages (`@NamedInterface("api")`, REQ-MOD-006).

The feature switch `supply.enabled` and the governing Bereich live in the system settings, not in a
column, so the switch can be turned on and off without a migration and with an audit row.

## Consequences

- The donation half ships and is measured alone; `purchasing` can be developed, switched on or removed
  without touching a donation.
- A rank-11 `supply` may not call `bank`; the money half reaches the bank through `purchasing` only.
- `supply` is a new participant in the privacy SPIs (export, erasure, account merge, person search) and
  in the audit, notification and live-sync registries; all are registered with the module.

## Alternatives considered

- **One module `procurement`.** Rejected: the donation half could not be switched on without the money
  half's code and risk, and the module would span ranks 10 and 12.
- **`supply` at rank 10 with an `allow supply joborder` row.** Rejected: a special case in the map for no
  gain over rank 11.
