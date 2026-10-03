> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-10-03.
> **Owner area:** ORDERS/DATA · **Related ADRs:** [ADR-0241](../adr/0241-quality-tiers-are-a-catalogue-table.md)

# Quality tiers and quality-bucket allocation

## Context & goal

A material requirement of a job order is stated at a **quality tier**: "Keine" accepts any stock,
"Gut (650+)" only stock of quality 650 or more. Until 2026-10 the two tiers were a hard-wired enum
(`GOOD` / `NONE`), and every reader of order-linked stock asked each bucket on its own "how much
linked stock meets my floor?". A stock row of quality 681 meets both floors, so an order that needs
one material at both tiers counted the same row **twice** — once per bucket. The order detail, the
material-demand overview (REQ-ORDERS-034), the allocation pickers (REQ-INV-039) and the exchange
org-demand feed (REQ-XCH-018) all reported a bucket as covered that was not.

This spec makes the tiers a catalogue an administrator maintains, and defines the one distribution
of linked stock across an order's buckets that every reader uses.

## Requirements

### REQ-ORDERS-036 — Quality tiers are an administered catalogue

Quality tiers MUST live in the table `quality_tier` (`code`, `min_quality`, `label_de`,
`label_en`, `sort_order`, `active`), not in code. Material lines (`job_order_material`),
item-order material requirements (`job_order_item_material`) and material claims
(`material_claim`) reference a tier by foreign key.

- `code` is unique, upper case, `^[A-Z][A-Z0-9_]{0,31}$`, and is the wire value of the existing
  `qualityRequirement` / `quality` fields, so a shipped client that sends `GOOD` or `NONE` keeps
  working. `min_quality` is unique and lies in 0–1000 (REQ-DATA-023); a material line's
  `minQuality` on the wire names the tier with that floor.
- The labels are **data**, not message-bundle keys, so a new tier needs no release. This is the
  one owner-approved exception to the i18n rule; every fixed text around them stays in the
  bundles.
- The tier with floor 0 is the base tier: it MUST exist, stay active and cannot be deleted.
- A tier's floor MUST NOT change once a line, an item requirement or a claim references it — old
  orders would otherwise be re-evaluated silently. Code, labels, order and the active flag stay
  editable.
- An inactive tier disappears from every picker; existing references keep it, and an edit of an
  order may keep a tier the order already used. Deleting is allowed only while nothing references
  the tier; otherwise the admin deactivates it.
- The seed holds `NONE` (0) and `GOOD` (650). Floors that terminal orders stored before the
  catalogue existed (30, 100, 353, 500, 800, 850, 900 in production) became **inactive** tiers
  `Q<floor>` labelled "Mindestens <floor>", so no historic requirement changed meaning.
- A blueprint ingredient's default tier is the active tier with the highest floor its
  `minQuality` reaches.
- Administrators maintain the catalogue at `/admin/quality-tiers`
  (`/api/v1/admin/quality-tiers`, ADMIN only); every member reads it at `/api/v1/quality-tiers`.
  Every change is audited as `QUALITY_TIER_CREATED` / `_UPDATED` / `_DEACTIVATED` / `_DELETED`
  in the job-order domain (REQ-AUDIT-001).

**Acceptance**

- [x] `quality_tier` carries the seed with fixed ids; a second tier with an existing floor, a floor
  above 1000 and an inactive base tier are refused by the database.
- [x] Lines, item requirements and claims carry a non-null `quality_tier_id`; the claim's unique
  index is `(job_order_id, material_id, quality_tier_id, claiming_org_unit_id)`.
- [x] Resolution by code is case-insensitive; an unknown code or floor, or an inactive tier not
  already used, is a 400.
- [x] Changing the floor of a used tier is a 409; deleting a used tier is a 409 (`ENTITY_IN_USE`).
- [x] The order pages, the material-demand overview and its filter list tiers from the catalogue;
  no template or script compares against `GOOD` / `NONE`.

**Enforced by:** `QualityTierServiceTest`, `V261QualityTierMigrationTest`,
`MaterialClaimServiceTest`, `JobOrderServiceTest`, `JobOrderItemServiceTest`,
`ExternalContractTest` · **Code:** `QualityTier`, `QualityTierService`, `QualityTierController`,
`AdminQualityTierController`, V261–V264, `fragments/quality-tier.html`, `QualityTierCatalog`

### REQ-ORDERS-037 — Linked stock counts toward exactly one quality bucket

For every order and material, the linked stock MUST be distributed across that order's quality
buckets by `QualityBucketAllocator`, and every reader of order-linked stock MUST use that
distribution: the order detail (material lines and the aggregated item view), the
material-demand overview (REQ-ORDERS-034), the allocation pickers' need figure (REQ-INV-039) and
the exchange org-demand feed (REQ-XCH-018).

The distribution:

1. Buckets are served **from the highest floor down**; buckets with the same floor in list order.
2. Each bucket takes qualifying stock (quality ≥ its floor) **lowest quality first**, up to its
   outstanding need.
3. Stock left over after every need is met goes to the **highest-floor bucket it satisfies**.
4. Stock below every floor is attributed to no bucket. It is still listed under the material,
   marked "Unter Mindestqualität".

Because the floors are nested, this greedy order covers as much of the order's need as any
assignment can, and the sum over all buckets is the linked stock that meets at least one floor —
no unit is counted twice and none is lost. With a single bucket per material the result is
identical to the previous per-bucket sum.

`GET /api/v1/orders/{id}/materials/{matId}/attribution` reports, per linked row, the amount
counted toward each bucket (row id, tier id, amount — no owner or location), and the order detail
lists under each bucket only the rows it counts, with their counted share.

**Acceptance**

- [x] An order needing Stileron at GOOD 0.1 and NONE 2.64, with 2.64 linked at quality 681, shows
  GOOD booked 0.1 / open 0 and NONE booked 2.54 / open 0.1 — on the order detail, in the
  material-demand overview and in the picker need.
- [x] Surplus lands on the highest floor it satisfies; low-grade surplus on the base bucket.
- [x] A shortfall on a high bucket is never filled by taking stock a lower bucket needs and that
  the high bucket cannot use.
- [x] Over 500 random cases, attributed + unattributed equals the linked stock and the covered
  need equals the optimum.
- [x] The overview and the exchange feed still agree (`ExchangeDemandParityTest`).

**Enforced by:** `QualityBucketAllocatorTest`, `JobOrderMaterialDemandServiceTest`
(`linkedStockIsAllocatedAcrossQualityBucketsWithoutDoubleCounting`), `JobOrderReferenceNeedsTest`,
`ExchangeDemandParityTest`, `JobOrderMaterialStockRowQueryDataTest` · **Code:**
`QualityBucketAllocator`, `JobOrderStockProjectionService.OrderLinkedStockIndex#bookedFor`,
`JobOrderStockProjectionService#attributionFor`, `JobOrderMaterialDemandService`,
`JobOrderQueryService`, `ExchangeDemandService`, `static/js/orders-detail.js`
(`_loadStockAttribution`)

## Out of scope

- **Choosing the bucket at a handover or a production run.** Which stock a member hands over or
  consumes for which bucket is the member's choice, as it is in the game. That, the quality check
  of the production run and the handover's per-bucket booking are the follow-up change.
- **Dropping the superseded columns.** `job_order_material.min_quality` and the two
  `quality_requirement` columns are no longer written and are dropped one release later (two-phase
  drop, `db/migration/README.md`).
