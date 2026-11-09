# ADR-0356 — Job orders use Profit hierarchy responsibility and participation

- **Status:** Accepted — planned, not implemented
- **Date:** 2026-11-09
- **Deciders:** Repository owner (@greluc)
- **Related:** [REQ-ORDERS-049–056](../specs/orders-core.md) · REQ-ORDERS-023/-024 · REQ-ORG-003/-004/-014/-016 · amendments to ADR-0091 · issues #3193, #3197, #3200, #3203

## Context

The implemented MATERIAL/ITEM model limits responsibility to independently flagged Staffeln/SKs,
uses SK-only material claims, and gives requesters a reduced view with a post-handover edit freeze.
The owner-approved aUEC planning deliberately changes the shared Auftragssystem rather than
adding a separate organisation/access model for the third kind. The exactly-one Profit Bereich
rule is an approved domain invariant, not a claim about the current implementation.

## Decision

Use the actual active hierarchy under the unique Department.PROFIT Bereich for JobOrder
responsibility and Profit access; retire independent isProfitEligible settings for Staffeln/SKs.
Allow the Bereich itself to be directly responsible. Current responsible-unit writers may transfer
responsibility immediately within the original Profit boundary, retaining requester and history,
without target approval or automatic stock/money movement.

Represent each supporting Staffel once per order, with optional kind-specific promises and
preserved history. A material promise can create that participation; do not add another enrollment.
Becoming responsible ends active self-support without erasing past contributions. Invalid support
ends immediately; an invalid responsible unit leaves only authorized reading and valid transfer
until repaired, in every lifecycle status.

Use one complete order read model for participating-unit members, leadership reach, personal
Bearbeiter, actual Profit readers and the additional linked aUEC holders/payees. Preserve independent
blueprint, mission, inventory and Bank privacy boundaries. Authorized unit leadership/contextual Logisticians and specifically
authorized personal involvement own writes; reading or an assignee entry does not grant arbitrary
money management. Either requesting or responsible writer may create. Retain safe post-partial
MATERIAL/ITEM requester demand edits and their manual early completion; aUEC alone uses strict
monetary completion.

The canonical shared rules live in orders-core.md, not in the planning map. This decision amends
ADR-0091's reduced requester/frozen-edit model. It does not authorize implementation or invent
missing legacy provenance.

## Consequences

One organisational and participation model applies to all exclusive order kinds. Existing picker,
queue, search, access, version and migration contracts must honor the actual hierarchy and shared
read model. History cannot be inferred from a current flag, owner or membership. Bank and personal
blueprints retain their independent gates. Legacy implementation/tests remain baseline evidence;
new runtime acceptance stays unchecked until implementation.

## Alternatives considered

- Keep hierarchy plus an independent eligibility flag: rejected by the owner; the hierarchy is authoritative.
- Treat Profit Bereich as intake-only: rejected; it may be directly responsible.
- Require a target to accept reassignment: rejected; authorized transfer is immediate.
- Add separate helper and claim registrations: rejected; one Staffel participation carries both.
- Give every reader/assignee arbitrary financial rights: rejected; personal and unit write scopes remain explicit.

## Hierarchy write coordination

Order and linked-stock transactions acquire the shared PostgreSQL transaction lock before any
Inventory lot/row lock. Hierarchy changes acquire its exclusive counterpart and synchronously end
ineligible promises. A private transaction-synchronization marker avoids reacquiring the same lock
inside nested commands and permits one hierarchy snapshot while the shared lock keeps it stable.
Independent transactions, including `REQUIRES_NEW`, have independent markers; there is no request or
cross-transaction eligibility cache. An exclusive hierarchy change invalidates the shared snapshot
and reads its changed hierarchy afresh. Every affected order and current actor grant is still checked.

Before application readiness, initial reconciliation takes the exclusive lock and keyset-pages
orders with active promises. The same bounded claim-ending implementation preserves audit/history
and groups one coordination cause per order across every claim page. The application cannot become
ready after a failed reconciliation; repeated starts do not revive or re-end historical promises.
