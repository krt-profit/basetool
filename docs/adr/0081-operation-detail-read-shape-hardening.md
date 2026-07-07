# ADR-0081 — Operation-detail read-shape: finance roll-up aggregate, lazy per-mission detail, slim payout toggle

- **Status:** Accepted
- **Date:** 2026-07-07
- **Deciders:** @greluc
- **Related:** ADR-0078 (mission-page fragment-gated reads + scale hardening) · REQ-DATA-012 ·
  REQ-DATA-003 · REQ-FE-010 · `OperationFinanceService` · `OperationService` · `OperationController` ·
  `OperationPageController` · `operation-detail.js`

## Context

ADR-0078 hardened the **mission** page for a 200-viewer / 20-editor live-update workload. The
post-#1104 audit (#1109, Wave 5) found the **operation** detail page roughly one hardening generation
behind — the operation aggregate is a roll-up over its child missions, and the render scanned the
whole ledger twice under a held connection:

- `OperationFinanceService.getOperationFinances` and `OperationService.getOperationPayouts` each ran
  unbounded `findAllByMissionIdIn` (finance entries) + `findByMissionIdIn` (refinery orders) across
  **all** child missions, and the render called both. The finance strip materialized every entry to
  compute per-mission totals it then rendered as bars; the payout math needs the ledger for the
  per-participant reimbursement.
- The per-participant paid-out toggle re-ran the **entire** payout computation (the double ledger
  load-all + all the money math) just to hand back the one boolean row it changed.
- The four operation-detail reads (operation, missions, finances, payouts) ran **serially**, and only
  the missions sub-table was fragment-gated.
- The mission-detail operation-picker (`/operations/lookup`) had no status / recency bound and ran on
  every mission render, growing unbounded with the operation count.
- Latent, same class: the job-order detail controller re-ran its uncached `/users?size=1000` load-all
  on every in-place section swap, an amplification waiting for order live-sync.

Both `getOperationFinances` and `getOperationPayouts` run under `@Transactional(readOnly = true)`, so
the connection is pinned across the scan + serialization — the assumption the prod Hikari-100 sizing
was justified on (ADR-0078).

## Decision

Apply the ADR-0078 shape to the operation aggregate, split the finance render into a cheap roll-up
plus lazy per-mission detail, and make the payout toggle O(1).

1. **Finance roll-up via a grouped SQL aggregate.** New `GET /api/v1/operations/{id}/finance-summary`
   returns the operation-wide total + one total line per mission (`OperationFinanceSummaryDto` /
   `OperationMissionFinanceDto`), computed from two grouped queries — `aggregateFinanceByMissionIds`
   (per-mission income / expense SUMs) and `aggregateProfitByMissionIds` (per-mission refinery
   `sales − expenses − other` SUM) — instead of materializing every row. The per-mission breakdown is
   capped at `MAX_FINANCE_SUMMARY_MISSIONS = 500` (`truncated` flag, surfaced in the UI). This mirrors
   the mission `finance-entries/summary` aggregate (ADR-0078).
2. **Per-mission entry detail loads lazily.** The finance tab renders each mission as a collapsed
   `<details>` (name + total from the roll-up); the per-entry / per-refinery breakdown loads on first
   expand via `GET /api/v1/operations/{id}/finances/{missionId}` and is injected in place. Authorized
   at the operation scope (`canSeeOperation`) and validated to belong to the operation, so an operation
   viewer can expand any of its missions without a separate mission-scope gate. The heavy full-detail
   `GET .../finances` endpoint survives for API consumers but is off the render path.
3. **Parallel + fragment-gated operation reads.** `operationDetails` loads operation + missions +
   finance-summary + payouts concurrently via `ParallelPageLoader`; the `fragment=missions` pager swap
   fetches neither finance-summary nor payouts (regression fence: a MockMvc `never()` guard).
4. **Slim payout toggle.** `PUT .../payouts/paid-out` returns only the participant's paid-out block
   (`OperationPayoutStatusDto`) — a toggle never changes an amount, so the client patches the single
   "Bezahlt" cell. `setPayoutStatusWithinTransaction` now validates the participant key against the
   bounded mission-participant graph (`computeParticipationBreakdown`, no ledger load-all) instead of
   re-running `getOperationPayouts`; the #1111 retry contract is unchanged.
5. **Bounded operation-picker + gating.** `OperationRepository.findAllReferenceScoped` gains the
   mission-style status / recency bound (PLANNED / ACTIVE always, COMPLETED / CANCELED only within the
   last 3 months by `createdAt`), and the mission-detail controller fetches it only on a full render.
6. **Job-order users read gated to the full render.** `JobOrderPageController.viewOrderDetail` fetches
   `/users?size=1000` only when `fragment == null` (the assignee picker lives in the full-page-only
   `assigneesSection`).

## Consequences

- An operation-detail render no longer scans the whole ledger under a held connection: the overview
  reads two grouped aggregates, the per-mission detail is lazy and per-mission-bounded, and payouts is
  the only remaining ledger read (one per full render, not per toggle).
- A paid-out toggle drops from "full graph + double ledger load-all + money math + DTO assembly" to a
  bounded participant-graph load + one row write — the per-click, connection-held cost the audit
  flagged.
- No new business metric or blackbox probe is needed: the change removes connection-hold weight rather
  than adding a surface. The new endpoints are authenticated API reads, covered by the existing
  frontend→backend read instrumentation.
- The finance tab now shows only per-mission roll-up totals eagerly; the per-entry detail is one click
  away (and unchanged on each mission's own finance page). Deliberate UX trade approved by @greluc.
- Deferred to a coordinated follow-up (with the #1102 tool-wide Redis pub/sub peer sync): operation
  peer live-sync (#1115) and the Verwaltung Organisation-panel broadcast (#1120) — both need a
  presence-stack change and are out of this read-shape PR's scope.

## Alternatives considered

- **Drop the operation-level per-entry detail entirely (roll-up only).** Rejected: it is a user-facing
  view; lazy-loading keeps it at one click without the eager scan.
- **Keep the eager per-entry breakdown, only add the aggregate for reuse.** Rejected: the eager
  `<details>` still forces the load-all on a full render, so the aggregate would not help the render.
- **Add a client `version` to the paid-out toggle to make it idempotent.** Rejected (ADR-0078 /

  # 1111): the field is a boolean; the REQUIRES_NEW retry, not a version echo, is what makes it

  last-writer-wins. The slim response carries no version.

