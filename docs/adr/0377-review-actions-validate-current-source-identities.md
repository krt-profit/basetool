# ADR-0377 — Review actions validate current source identities

**Status:** Accepted
**Date:** 2026-11-12

## Context

The P4K import can produce thousands of findings in one run. The change report preserves the
original source label and class. Candidate rows and their UEX and SC Wiki relationships can change
between the import and the administrator's review.

## Decision

Keep durable cases keyed by kind, GUID and reason. The workspace derives groups from the source
context and loads current catalogue evidence on demand. Each catalogue kind supplies its own
proposals and mutation rules. A confirmed proposal must still match the current case, action,
target and relevant versions inside the same coordinator lock used by imports and external syncs.
The existing domain commands remain responsible for retaining source identities and references.

Grouping does not establish identity. A batch contains the versions of the selected cases and
has one shared decision. Identity changes remain individual and explicitly confirmed.

## Consequences

Administrators can reuse verified source relationships without losing the import evidence.
Peers see committed review updates. A stale proposal or batch is rejected as a unit. Candidate
discovery remains read-only and evaluates the current catalogue.

**Requirements:** [REQ-P4K-049](../specs/p4k-catalogue.md), [REQ-AUDIT-001](../specs/audit.md).
