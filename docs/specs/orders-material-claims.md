> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-07-10.
> **Owner area:** ORDERS · **Related ADRs:** ADR-0092

# Material claims (Eintragungen) on Spezialkommando orders

## Context & goal

A **material claim** ("Eintragung") records that a profit squadron signs up to deliver a partial
quantity of one material bucket on a **public Spezialkommando (SK) job order** (Job-Order rework

# 340, Phase 4 / #344). A claim is keyed on the aggregated bucket

`(job_order, material, qualityRequirement)` so the same flow serves both order kinds (a `MATERIAL`
order buckets its material lines by `minQuality`, an `ITEM` order sums its per-item material
requirements per quality). Claims are **signal-only** — they record intent and never move inventory.

The claim domain shipped code-only; this spec captures its load-bearing invariants and, in
particular, makes the **no-overclaim** rule a concurrency-safe requirement after an audit found it
was not enforced across squadrons under concurrent first claims.

The invariants live in `MaterialClaimService`; the row is `material_claim` (V131) with a unique index
`uq_material_claim_bucket_org_unit` on `(job_order_id, material_id, quality_requirement,
claiming_org_unit_id)`.

## Requirements

### REQ-ORDERS-024 — A bucket's claims never overclaim, concurrently or otherwise

For every material bucket of an SK order, the sum of all squadrons' claim amounts MUST never exceed
the bucket's required amount. A claim upsert that would push the bucket's total over the requirement
is rejected (HTTP 400). This holds regardless of which squadron claims, whether the claim is a first
sign-up or an amount edit, and **regardless of concurrency**.

Because the sum is a **cross-row aggregate across squadrons**, the unique index (which keys per
`(bucket, claiming squadron)`) cannot protect it: two **different** squadrons lodging their first
claim on the same bucket at the same instant would each read a zero already-claimed sum under
Postgres `READ COMMITTED` (the other's uncommitted INSERT is invisible), both pass the guard, and
both commit — the two rows never collide, so the claim upsert's own `REQUIRES_NEW` retry (which only
catches a same-`(bucket, squadron)` unique / `@Version` violation) never fires.

The invariant is therefore serialized with a **`PESSIMISTIC_WRITE` row lock on the order** — the
claims' aggregate root — taken **before** the already-claimed sum is read
(`JobOrderRepository.lockForClaimUpsert`, ADR-0092). Claimants of one order serialize on that row,
so the loser of a first-claim race reads the winner's **committed** claim and its guard rejects the
overclaim; unrelated orders and unrelated buckets of other orders are never blocked. The existing
`REQUIRES_NEW` retry is retained as a defense-in-depth backstop for the same-row race the order lock
does not serialize (an upsert whose row is deleted by a concurrent withdrawal between find and save).

The claim domain's other invariants are unchanged and remain enforced in `MaterialClaimService`:
claims are allowed **only on non-terminal SK orders**; a squadron holds **at most one claim per
bucket** (a repeat post updates its row rather than duplicating, backed by the unique index); only a
**profit-eligible squadron** may claim; and the claim permission matrix (own-squadron
logistician/officer, or a logistician/lead of the responsible SK, or an admin) gates every mutation.

**Acceptance**

- [ ] Two different squadrons racing their first claim of 70 on a 100 bucket end with exactly one
  committed claim (70) and the other caller rejected with an overclaim HTTP 400; the committed
  row-sum never exceeds 100.
- [ ] Two logisticians of the **same** squadron racing the first claim on one bucket both succeed
  (last-writer-wins) and collapse to exactly one row — no 500, no overclaim.
- [ ] A single claim that alone exceeds the bucket's required amount is rejected (HTTP 400).
- [ ] A claim upsert on one bucket does not block a concurrent claim on an unrelated order.

**Enforced by:** `MaterialClaimConcurrencyTest`
(`firstClaimRace_differentSquadrons_neverOverclaims`, `firstClaimRace_sameSquadron_*`),
`MaterialClaimServiceTest` (overclaim rejection + `UpsertClaimConcurrencyTests`) · **Code:**
`MaterialClaimService.upsertClaim` / `upsertClaimWithinTransaction`,
`JobOrderRepository.lockForClaimUpsert` · **Issues:** #344 · **ADR:** ADR-0092

## Out of scope

- Moving inventory — claims are signal-only; delivery is the handover flow.
- Reworking the same-`(bucket, squadron)` last-writer-wins retry — it is retained unchanged as a
  backstop (ADR-0092); this change adds the cross-squadron aggregate guard on top of it.
- A per-bucket lock finer than the per-order lock — claiming is a low-frequency human action, and
  the brief per-order serialization never 409s a user (see ADR-0092, alternatives).

## Open questions

None.
