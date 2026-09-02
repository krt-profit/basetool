# ADR-0153 — The bank trail records the client through the same seam

- **Status:** Accepted
- **Date:** 2026-09-02
- **Deciders:** @greluc
- **Requirement:** [REQ-AUDIT-005](../specs/audit.md), [REQ-BANK-012](../specs/bank.md)
- **Related:** [ADR-0152](0152-the-audit-row-records-which-client-a-mutation-came-through.md)
  (the shared trail's column, and the gap this closes),
  [ADR-0037](0037-shared-multi-domain-activity-audit-log.md) (why the bank keeps its own table),
  [ADR-0129](0129-ingest-gateway-is-a-trusted-subsystem-not-a-token-relay.md) (`azp` as a trust
  handle), REQ-SEC-035 (what the mobile client's scope carries), REQ-OBS-018
- **Source:** private security advisory GHSA-2vq5-8p8w-5r64

## Context

ADR-0152 gave `audit_event` a bounded originating-client column and left `bank_audit_event` without
one, on the grounds that the bank is a second table and closing it was a sibling change. This is
that change.

The gap was not a smaller version of the one ADR-0152 closed. It was an older one.

ADR-0152 could say its own nulls were unambiguous: while those rows were written, the authority they
record could come from one client only, so the row's domain implied the answer. That reasoning never
applied here. `Bank Employee` and `Bank Management` have been on the mobile client's Keycloak scope
since it was provisioned — REQ-SEC-035's role list named them in its **first** revision, the one
that withheld `Admin`. A bank row has therefore been reachable from two clients for as long as the
mobile client has existed, and unlike the shared trail there was never a period during which "which
client did this" had one possible answer.

Whether a shipped app screen ever booked a deposit is beside the point, and it is worth being
explicit about why: the authority rides on the **token**, not on the UI. A token minted for the app
carries the bank roles whatever the app chooses to render, and the case the advisory is about — a
token replayed inside its lifetime — never involves the app's UI at all.

The bank is also the area where a misattributed action is most expensive. It is the one audited area
with its own reversal, approval-limit and wipe machinery, and the one whose rows an investigation
would reach for first.

## Decision

**`bank_audit_event` records the originating client, on the same terms and through the same code as
`audit_event`.**

- `V238` adds a nullable, bounded `client_id VARCHAR(60)` plus a `(client_id, occurred_at DESC)`
  index — the mirror of V237 on the mirror table.
- `BankAuditService.record(...)` stamps it in the same transaction as the row, from the same
  `AuthHelperService` authentication the actor is read from.
- The mapping is **the same `ClientAttribution` bean**, not a bank-flavoured copy. The blank-filter
  guard moves onto that bean too (`filterValue`), because both trails hit the same trap and a silent
  empty result is the worst failure an audit viewer has.
- The unified viewer's client filter and column now appear on **all ten** tabs, with one list.
- No backfill.

## Alternatives considered

**Leave it, and note the gap.** What ADR-0152 did, deliberately, to keep one change one change.
Rejected as a permanent state once the reasoning was written down: a documented blind spot in the
most consequential audited area is a decision to keep the blind spot.

**Merge `bank_audit_event` into `audit_event` while touching both.** Tempting — one table, one
column, one filter, and the tenth tab stops being special. Rejected: ADR-0037 chose the split for
reasons that have not changed (bank-specific reference columns, its own event-type enum, its own
export and purge paths), and a migration that moves years of financial audit rows to fix a reporting
seam risks the evidence it is meant to improve.

**Give the bank its own bounded vocabulary**, e.g. distinguishing bank-capable clients. Rejected:
two vocabularies that look alike is exactly the drift ADR-0152 centralised the mapping to prevent,
and an operator filtering two tabs for "the app" must not be asking two different questions.

**Backfill pre-V238 rows as `basetool-frontend`.** Rejected, and it is the one alternative that
would have been actively harmful. It is a *guess*, written into evidence, in the one area where the
guess is least safe — and it would erase the very ambiguity the column exists to expose.

## Consequences

- **The two tables' nulls now mean different things**, and both migrations say so in a
  `COMMENT ON COLUMN`. Pre-V237 `audit_event` nulls are "unambiguous anyway"; pre-V238
  `bank_audit_event` nulls are "not recorded", and must **never** be read as "the web frontend".
  This is the one place a reader can go wrong, so it is stated in the schema rather than only here.
- The viewer's tenth tab stops being an exception. The frontend's `clientFilterSupported` flag and
  the bank adapter's hardcoded `null` are gone rather than inverted — a per-tab capability flag with
  one value is a place for a future divergence to hide.
- `BankAuditEventDto` and its frontend mirror gain a field; the bank JSON export carries it. The
  bank PDF does not, matching the shared trail's PDF — both render the same shared five-column
  table, and widening it is a formatting decision, not an evidence one.
- The bank trail's own metric (`basetool_bank_audit_events_total{event_type}`) is untouched. It is a
  volume signal tagged by event type; client attribution across the whole API already lives on
  `basetool_api_client_requests_total{client_id}` (REQ-OBS-018), and a second per-client counter
  here would restate it.

