# ADR-0092 — Material-claim no-overclaim invariant serialized by a pessimistic order-row lock

- **Status:** Accepted
- **Date:** 2026-07-10
- **Deciders:** Repository owner (@greluc)
- **Related:** spec [`orders-material-claims.md`](../specs/orders-material-claims.md) `REQ-ORDERS-024` · `material_claim` table + `uq_material_claim_bucket_org_unit` (V131) · ADR-0080 (a sibling aggregate-concurrency decision, different mechanism) · issue #344 · the code-coverage concurrency audit

> **ADR-number note.** The next free ADR number on `main` at authoring time was 0092. The LiveSync
> epic plan (#1102) also intends to claim ADR-0092; whichever merges second renumbers. Reconcile the
> number (and this cross-reference) at merge.

## Context

A **material claim** ("Eintragung") lets a profit squadron sign up for a partial quantity of one
material bucket `(job_order, material, qualityRequirement)` on a public Spezialkommando order (#344).
`MaterialClaimService.upsertClaimWithinTransaction` enforces a **no-overclaim** invariant: the sum of
all squadrons' claims on a bucket must not exceed the bucket's required amount.

That guard reads the already-claimed sum with a plain, unlocked query
(`findByJobOrderIdAndMaterialIdAndQualityRequirement`) and compares `claimedByOthers + amount` to the
required amount. The sum is a **cross-row aggregate across squadrons**, and the coverage audit found
it is not concurrency-safe:

- Two **different** squadrons lodging their first claim on the same bucket at the same instant each
  read `claimedByOthers = 0` under Postgres `READ COMMITTED` — the other's uncommitted INSERT is
  invisible — so both pass the guard and both commit.
- The unique index `uq_material_claim_bucket_org_unit` keys per `(bucket, claiming squadron)`, so the
  two distinct-squadron rows **never collide**. The upsert's existing `REQUIRES_NEW` retry
  orchestrator only catches a same-`(bucket, squadron)` `DataIntegrityViolationException` /
  `ObjectOptimisticLockingFailureException`, so it never fires here.

Result: a bucket's total claims silently exceed the required amount — a quantity/financial-surface
defect. The same-`(bucket, squadron)` last-writer-wins retry is correct and unaffected; only the
cross-squadron aggregate is unguarded.

A pessimistic lock on the *existing* bucket rows would not fix it: a `SELECT … FOR UPDATE` over
present rows does not block a phantom INSERT of a new-squadron row. The lock must be on a **common
parent row** every claimant of the bucket contends on.

## Decision

Serialize claim upserts on a job order by taking a **`PESSIMISTIC_WRITE` row lock on the order** — the
claims' aggregate root — before the already-claimed sum is read.

- **Lock finder.** `JobOrderRepository.lockForClaimUpsert(id)` is a bare single-row
  `SELECT o FROM JobOrder o WHERE o.id = :id` under `LockModeType.PESSIMISTIC_WRITE` (no join fetch,
  so only the `job_order` row is locked). It mirrors the existing `lockAllJobOrders` reorder
  precedent and CLAUDE.md's "pessimistic locking for bulk reorders" rule.
- **Placement.** `upsertClaimWithinTransaction` acquires the lock **after** `loadOrder` (preserving
  its entity-graph fetch) and **before** computing `claimedByOthers`. Under `READ COMMITTED` the
  loser blocks on the lock until the winner commits, then its next statement reads the winner's
  committed claim and the guard rejects the overclaim (HTTP 400). The lock releases at the attempt's
  commit/rollback. Claimants of **different** orders — and unrelated buckets of other orders — never
  contend, so no user is blocked in practice.
- **Retry kept as a backstop.** The `NOT_SUPPORTED` orchestrator + `REQUIRES_NEW` retry
  (`MaterialClaimService.upsertClaim`, the CLAUDE.md "find-or-create races" canonical example) is
  **retained unchanged**. The order lock now also serializes the same-squadron first-insert race, so
  the retry is defense-in-depth: it still covers the one same-row race the order lock does not
  serialize — an upsert whose row is deleted by a concurrent `withdrawClaim` between the
  find-or-create and the save.

The change is behaviour-preserving at the API boundary (same endpoint, DTO, 400/409 statuses, audit
event `JOB_ORDER_CLAIM_UPSERTED`); it hardens *how* the existing no-overclaim guard holds under
concurrency. Requirement `REQ-ORDERS-024` records the invariant.

## Consequences

- The cross-squadron overclaim can no longer occur: of two racing first claims that together exceed
  the requirement, exactly one commits and the other gets a truthful HTTP 400.
- Claim writers of one order are briefly **serialized** by the row lock (one blocks the other for the
  duration of a single claim upsert) — acceptable latency for a low-frequency human action, and it
  never surfaces as a 409/500. Different orders are fully independent.
- A claim upsert now takes a write lock on the `job_order` row, so it serializes against a concurrent
  whole-order edit that also write-locks the row (e.g. a reassignment that withdraws claims). This is
  correct — a claim must not be inserted against a bucket being removed — and low-frequency.
- The retry orchestrator is now largely redundant for its original same-squadron case but is kept as
  a documented backstop; the deterministic `UpsertClaimConcurrencyTests` continue to pin its bounded
  behaviour.
- No schema change (no migration): the lock reuses the existing `job_order` primary-key row.

## Alternatives considered

- **A version/serialization counter column on `job_order` (or a bucket), bumped atomically by every
  claimant (Mission-section-counter style, ADR-0080).** Rejected: needs a Flyway migration and a new
  column whose only purpose is to serialize; a `PESSIMISTIC_WRITE` on the existing aggregate-root row
  expresses the same serialization with no schema change.
- **A `pg_advisory_xact_lock` hashed per `(order, material, quality)` bucket.** Finer-grained (only
  same-bucket claimants serialize) and migration-free, but introduces an advisory-lock pattern used
  nowhere else in the codebase, plus a small hash-collision risk of over-serializing unrelated
  buckets. Rejected as not worth the novelty: claiming is low-frequency, so per-order serialization is
  cheap enough.
- **Lock only the existing bucket claim rows (`SELECT … FOR UPDATE`).** Rejected — incorrect: it does
  not block a phantom INSERT of a new-squadron row, which is exactly the first-claim race.
- **Remove the `REQUIRES_NEW` retry and rely solely on the order lock.** Rejected: the retry still
  covers the upsert-vs-concurrent-withdraw same-row race the order lock does not serialize, and it is
  a CLAUDE.md-cited canonical example; keeping it as a backstop is cheaper than proving that race
  away.
- **Raise the transaction isolation to `SERIALIZABLE` for the claim upsert.** Rejected: a broad
  isolation change with serialization-failure retries for the whole method, versus a single targeted
  row lock that expresses exactly the intended serialization.

