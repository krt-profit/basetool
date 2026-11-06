# ADR-0349 — External catalogue identity, presence and application are separate contracts

- **Status:** Accepted
- **Date:** 2026-11-06
- **Deciders:** @greluc
- **Related:** REQ-DATA-032…038, ADR-0147, ADR-0195, ADR-0307, ADR-0337…0347

## Context

UEX can assign one game UUID to distinct source records. A Wiki player-relevant list can omit an
asset that still exists. A local write can fail after its source record was successfully fetched.
Treating any of these as the same identity or absence signal corrupts joins and cleanup decisions.
Page-level validators and event-only reports cannot prove that a complete snapshot was applied.

## Decision

Keep source identities, source presence and committed application outcomes separate. Pin one Wiki
build per run; pace and retry all request shapes through one bounded transport. Replay complete
payloads instead of treating a page validator as a collection validator. Before cleanup, verify
both collection completeness and identity validity. A complete all-asset census may protect known
records omitted by the player-relevant scope, without seeding internal assets.

Only unique compatible source claims may converge. Reject conflicting strong identities before
writing attributes. Equivalent persisted rows converge in one transaction through reference-owner
SPIs. Lock identities and stock lots deterministically, preserve user row ids and amounts, audit
reference changes, and publish their existing live fragments after commit. Catalogue row locks
fence concurrent FK inserts; reconciliation uses nonblocking advisory stock locks with bounded
fresh-transaction retry so a normal booking cannot deadlock against that fence. Preserve both complete
catalogue rows and their price/stat evidence in a permanent redirect snapshot before retiring an
obsolete duplicate. Keep old local ids resolvable. Duplicate stock-floor settings retain the greater
minimum and target; duplicate prices keep the newest observation, and duplicate stat properties
keep the Wiki survivor's value. The archived source values remain available for verification.

Keep Wiki enrichment typed and attributed, with explicit units and loading state. Physical, cargo
and display geometry remain distinct. UEX evidence imported through the Wiki is derived evidence,
not independent corroboration or price authority. These changes preserve the decided per-area
P4K precedence and its separate implementation issues.

Persist each attempted source sweep and slice independently of anomaly events, with actual times,
complete/partial/failed/unchanged outcomes, committed counts and cleanup decisions. Existing-row
repairs follow current source evidence; no blanket migration infers units from a sync timestamp.

**Amendment (P4K identity guard, 2026-11-07):** a duplicate merge never joins two rows with
different P4K GUIDs; both stay untouched and go to the P4K review list as `GUID_CONFLICT`
(REQ-P4K-007). A GUID only the retired row holds moves onto the survivor, and a P4K-matched
survivor keeps its name and manufacturer.

## Consequences

A source error or local failure cannot masquerade as upstream removal. Source order and variant
collisions have explicit tests. The runtime keeps more evidence and performs an additional
existence census when known records disappear from the player-relevant scope. Redirect snapshots
consume lasting storage; retaining that evidence is preferable to discarding source values.

## Alternatives considered

- **Trust every upstream UUID or the first name match:** copied UUIDs and variants make it unsafe.
- **Use successful writes as the census:** a constraint failure would delete valid existing data.
- **Delete a duplicate without redirects and evidence:** breaks stored local ids and loses values.
- **Use a single screen-wide import transaction:** one bad row rolls back unrelated valid rows.
- **Skip a collection after page-one 304:** the validator does not establish collection-wide or
  applied-snapshot identity.
