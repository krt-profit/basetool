# ADR-0241 — Quality tiers are a catalogue table, and linked stock counts toward one bucket

- **Status:** Accepted — owner decision 2026-10-03. Built by #2359 (catalogue and allocation),
  #2361 (the member's choice at handover and production, `REQ-ORDERS-038/039`) and V282 (the
  superseded columns dropped).
- **Date:** 2026-10-03
- **Deciders:** @greluc
- **Related:** spec [`orders-quality-tiers.md`](../specs/orders-quality-tiers.md) (`REQ-ORDERS-036`,
  `REQ-ORDERS-037`) · spec [`data-persistence.md`](../specs/data-persistence.md) (`REQ-DATA-023`) ·
  spec [`orders-material-demand.md`](../specs/orders-material-demand.md) (`REQ-ORDERS-034`) ·
  [ADR-0136](0136-external-contract-set-for-shipped-clients.md) (the frozen client contract)

## Context

A job order states a material requirement at a quality: „Keine" (any stock) or „Gut (650+)". The
two were the enum `QualityRequirement` (`GOOD`, `NONE`), and `GOOD` was hard-wired to the floor 650 in
five places in the backend, in the database (`CHECK IN ('GOOD','NONE')`), in templates and in
scripts.

Stock is linked to an order and a material, never to a quality. Every reader asked each of an
order's buckets separately how much linked stock met its floor. A row of quality 681 meets both
floors, so when one order needed the same material at both tiers, the row counted toward both.
Production order #80 needed Stileron at GOOD 0.1 and NONE 2.64 with 2.64 SCU linked at 681, and every
surface reported both buckets covered, while 0.1 SCU was still missing. The same double count reached
the cross-order Materialbedarf, the Lager pickers' need figure and the exchange org-demand feed.

The owner wants further quality tiers to be addable without a code change.

## Decision

1. **Quality tiers live in the table `quality_tier`** (code, floor, German and English label, sort
   order, active flag). Lines, item requirements and claims reference it by foreign key. The labels
   are data, not message keys — the owner-approved exception to the i18n rule, because a tier added
   at runtime has no bundle entry.
2. **A tier's floor is frozen once anything references it**, the base tier (floor 0) always exists
   and stays active, and a used tier is deactivated rather than deleted. Old orders therefore never
   change meaning.
3. **The wire keeps its shape.** The tier code is the value of the existing `qualityRequirement` /
   `quality` fields and a material line's `minQuality` names the tier by its unique floor, so the
   shipped Android app keeps working. Responses add a `qualityTier` object. Only the request enums
   widen from `GOOD`/`NONE` to any tier code.
4. **One allocator distributes linked stock** across an order's buckets for every reader: highest
   floor first, lowest qualifying grade first, surplus to the highest floor it satisfies, stock below
   every floor unattributed. With nested floors this greedy order is optimal, and with one bucket per
   material it equals the old per-bucket sum.
5. **Every quality is bounded to 0–1000** in the database, on input and on import.

## Consequences

- Adding a tier is an admin action at `/admin/quality-tiers`; no release is needed.
- Historic floors (30, 100, … 900) on terminal orders became inactive tiers instead of being folded
  into `GOOD`, so their history is unchanged.
- The superseded columns (`job_order_material.min_quality`, `quality_requirement` on item
  requirements and claims) are not written any more and are dropped one release later.
- The bucket a handover or a production run books against is still chosen by the server; letting the
  member choose, and checking quality at production, is the follow-up change.

## Rejected

- **Keep the enum and add constants per tier.** Every tier would need a release and an app update.
- **A free numeric floor per line, without a catalogue.** Every value would be its own bucket, the
  pickers would need free input, and nothing would carry a label.
- **Count stock per bucket and cap at the need.** Still double-counts below the need and cannot say
  which bucket a row serves.
