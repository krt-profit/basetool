> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: YYYY-MM-DD.
> **Owner area:** <AUTH | ORG | ORDERS | …> · **Related ADRs:** <ADR-NNNN, or none>

# <Spec title>

## Context & goal

One paragraph: which part of the system this governs and the user problem it serves. Link
the GitHub epic / issues that track the work, if any.

## Requirements

Each requirement gets a stable id and a one-line statement of what must hold. Keep them
atomic and testable.

### REQ-<AREA>-001 — <short title>

<What must be true. Prose, not implementation. State the *why* if it is non-obvious.>

**Acceptance**

- [ ] <checkable criterion — maps to a test>
- [ ] <checkable criterion>

**Enforced by:** `<TestClass>` · **Code:** `<Service / Controller>` · **Issues:** #NNN

### REQ-<AREA>-002 — <short title>

…

### REQ-<AREA>-003 — <short title> *(superseded)*

> [!warning] Superseded YYYY-MM-DD by REQ-<AREA>-NNN / ADR-NNNN — kept for the reasoning

A requirement that no longer applies keeps its heading and id (ids are never reused — take the next
free one from [`INDEX.md`](INDEX.md#2-requirement-ids-traceability-anchors)); the callout says what
replaced it.

## Out of scope

What this spec deliberately does NOT cover, and where that lives instead.

## Open questions

Unresolved points. Promote each to an ADR once it is decided.
