# ADR-0152 — The audit row records which client a mutation came through

- **Status:** Accepted
- **Date:** 2026-09-02
- **Deciders:** @greluc
- **Requirement:** [REQ-AUDIT-005](../specs/audit.md)
- **Related:** [ADR-0037](0037-shared-multi-domain-activity-audit-log.md) (the shared audit table),
  [ADR-0129](0129-ingest-gateway-is-a-trusted-subsystem-not-a-token-relay.md) (`azp` as a trust
  handle), [ADR-0141](0141-a-clients-role-claim-is-authoritative-only-if-its-scope-is-complete.md)
  (the same claim deciding whether a role claim is authoritative), REQ-OBS-018 (the per-client
  request counter), REQ-SEC-035 (what the mobile client's scope carries)
- **Source:** private security advisory GHSA-2vq5-8p8w-5r64

## Context

`audit_event` records who acted, on what, and when. It did not record **through which client**.

That was not an oversight so much as a fact that had not yet become interesting. While exactly one
client could perform a given action, "which client did this" had one possible answer for every row,
and a column holding it would have restated the row's own domain.

Two things ended that. A second first-party client exists — the Android app — and the realm roles on
its client scope decide which mutations it can reach. Whenever those two sets overlap, the same
mutation can arrive from two places and the trail cannot separate them. The advisory that prompted
this names the case that makes it matter rather than merely bother: an access token replayed inside
its lifetime carries its member's authority, and the rows it writes are indistinguishable from the
same person working in the browser. "Which client did this" is then the first question of the review
and the log's first non-answer.

The seam already existed on the other side of the house. `basetool_api_client_requests_total`
carries a bounded `client_id` label (REQ-OBS-018) derived from the token's `azp`. But a counter
answers "how much traffic from whom", not "who did *this*"; it cannot be joined to a row.

## Decision

**Every audit row records the originating client, as a bounded label, written in the same
transaction as the row itself.**

- The value is the token's `azp` — a claim Keycloak signs and a client cannot set, the same handle
  `IngestGatewayProperties` already uses for the far more dangerous on-behalf-of decision
  (ADR-0129), so recording it introduces no new trust.
- It is **bounded, not verbatim**: mapped through the known-client allowlist to a configured client
  id, else `other`, else `none` for a caller with no token or a token carrying no `azp`.
- The mapping is **one implementation** (`support.ClientAttribution`) shared with the metric label,
  not a second copy of the same four lines.
- The unified admin viewer gains a per-tab **client filter and column**, and the JSON export carries
  the field. Both are absent on the Bank tab (see Consequences).
- Rows written before V237 keep `NULL`. There is no backfill.

## Alternatives considered

**Write the raw `azp`.** Rejected. The claim's *content* is trustworthy; its *range* is not — a
client id exists in the realm the moment somebody registers one. The audit trail is the one table
whose value rests on carrying no unreviewed strings (the "no user free text, no PII in details" rule
is the same instinct), and a metric label derived from a token claim without a bound is the kind of
thing that stays correct until the day it does not (REQ-OBS-006).

**Reuse the metric instead of adding a column.** Rejected: `basetool_api_client_requests_total` is a
counter over a scrape interval. It can show that the app made requests around the time of an
incident; it can never say that *this* role grant came from it. The two are complements — the metric
finds the window, the column attributes the act — which is precisely why they must share one
mapping.

**Record it in the details payload.** Rejected. `details` is free-form text rendered verbatim; a
value there cannot be filtered on, cannot be indexed, and would be indistinguishable from the
payload's own vocabulary.

**Backfill the existing rows.** Impossible and unnecessary in the same breath: the claim was never
stored, and the rows predate the ambiguity — while they were written, the authority they record
could come from one client only. Stated in the migration so a later reader does not mistake the
nulls for data loss.

**Derive the client at read time from the actor's session.** Rejected: sessions are not retained for
the audit log's lifetime, an actor may hold several, and the answer would change depending on when
the question was asked. Evidence is written once, at the moment of the act.

**Extend `bank_audit_event` in the same change.** Deliberately not done here — see below.

## Consequences

- `none` is a **recorded answer, not an absence**: a scheduled job writing its own audit row lands
  there, and so would a Keycloak mapper regression that stripped `azp` from every token at once. The
  row's actor separates the two — a system write also carries the `system` actor handle — and
  `ApiUnknownClient` already alerts on the second case from the metric side.
- **`NULL` means "before the column existed"** and nothing else. New rows always carry a value.
- **The Bank tab is not covered.** The bank keeps its own `bank_audit_event` table (ADR-0037), which
  has no such column, so the viewer hides the filter and the column on that tab rather than showing
  a control that appears to apply and does not. The gap is real: `Bank Employee` and `Bank
  Management` sit on the mobile client's scope, so bank mutations are two-client-ambiguous by the
  same argument. Closing it is a sibling change against a second table, deliberately not folded into
  this one.
- The viewer's filter list is a **hand-maintained mirror** of the backend's allowlist, held in the
  frontend controller the way the event-type lists already are — the frontend module holds no
  backend beans. A deployment that renames its Keycloak clients through
  `app.monitoring.api-clients.known-client-ids` must update the mirror and the three message
  bundles, or the trail will record a client the viewer cannot filter for. Nothing fails when it
  drifts: the column falls back to the raw id, and the stale option simply selects nothing.
- The label mapping now has a **single owner**, so the metric and the trail cannot drift into
  disagreeing about what `other` means — an operator moving from a Grafana panel to the audit filter
  is following one rule, not two that look alike.
- The write costs one extra read of the current authentication per audited mutation, taken from the
  same `AuthHelperService` call path the actor already comes from, so "who" and "through what"
  can never describe different requests.

