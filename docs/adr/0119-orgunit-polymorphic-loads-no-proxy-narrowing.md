# ADR-0119 — Load OrgUnit hierarchy rows polymorphically; never narrow a base-typed proxy

- **Status:** Accepted
- **Date:** 2026-07-23
- **Deciders:** @greluc
- **Related:** REQ-DATA-013 (`docs/specs/data-persistence.md`) ·
  `org-unit-tenancy.md` (REQ-ORG-\*) · production WARN `HHH000179` (2026-07-23 log review)

## Context

`OrgUnit` is a single-table inheritance root (`@DiscriminatorColumn kind`) with four concrete
subtypes (`Squadron`, `SpecialCommand`, `Bereich`, `Organisationsleitung`). Fourteen aggregates
(`Mission.owningOrgUnit`, `JobOrder.responsibleOrgUnit`, `InventoryItem.owningOrgUnit`, …) hold
LAZY `@ManyToOne` associations **typed to the base class**, so hydrating any of them registers an
uninitialized base-typed `OrgUnit` proxy in the persistence context. Several services then loaded
the *same* row **subclass-typed** in the same transaction — `SquadronRepository.findById` /
`findAllById`, `SpecialCommandRepository.findById` — which forces Hibernate to *narrow* the proxy
and emit `HHH000179 "Narrowing proxy to class …Squadron/SpecialCommand — this operation breaks
=="`. Production logs showed the warning on three reconstructed flows: the mission-detail read
(participant `UserDto` squadron resolution vs. the mission's owning-unit proxy), the create-time
owner stamping (the pinned unit equals the source aggregate's owning unit on a same-unit
transfer/booking), and the job-order handover (the executing Staffel deliberately *is* the order's
responsible unit). Beyond the log noise, the narrowed second instance breaks `==` identity within
the session — a latent trap for identity-based collections and dirty tracking.

Alternatives considered: **(B)** `@ConcreteProxy` on `OrgUnit` (Hibernate 6.6+) removes narrowing
globally but adds a discriminator-resolution SELECT to every to-one reference that today never
initializes — a hot-path cost on list/gate reads; rejected until profiled. **(C)** widening entity
graphs to eager-fetch the org unit everywhere is inherently incomplete (any future load path
reintroduces the WARN) and widens queries that do not need the data. **(D)** retyping the four
`Squadron`-typed associations to `OrgUnit` addresses only one flow and moves a type-system
invariant into validation code.

## Decision

At every site where a base-typed proxy of the same id can pre-exist, load org units **only through
the polymorphic `OrgUnitRepository`** (`findById`, or one `findAllById` batch) — which reuses the
persistence-context instance instead of minting a narrowed twin — and dispatch on `getKind()`;
where the concrete subtype is required, obtain it via `Hibernate.unproxy(ou, OrgUnit.class)` +
pattern matching (a base proxy is never `instanceof` a subclass, so unproxy is mandatory before any
type test). The in-Java kind check replaces the subclass repository's SQL discriminator filter
1:1. Converted sites: `OrgUnitStampingService` (the Squadron→SK→base probe chain collapses to one
load), `RequestScopeResolver.currentSquadron` (the R2.d swap the code already announced),
`StaffelMembershipResolver`, `OrgUnitMembershipQueryService` (`listOptionsForUser`,
`findAllMembershipsForUser` — also de-N+1-ing the sort comparator), `JobOrderHandoverService` and
`JobOrderItemHandoverService`. `JobOrderOrgUnitResolver` already followed the pattern and is the
canonical precedent. Non-loading subclass queries (`existsById`, `findAllByActiveTrue`,
counts/aggregates) stay as they are. The rule is recorded as REQ-DATA-013.

## Consequences

- The `HHH000179` warnings disappear at their cause; `==` identity holds because the tracked
  instance is reused, not narrowed.
- The stamping probe drops from up to 3 queries to 1; the membership sort comparator loses its
  per-row N+1.
- `Hibernate.unproxy` initializes the proxy — one SELECT in the rare path where the entity was
  never read; every converted flow reads name/kind anyway, so no measurable cost.
- Future org-unit load sites must follow REQ-DATA-013; a subclass-typed `findById` on a
  proxy-prone path is a review defect.
- `@ConcreteProxy` stays available as defence-in-depth if a new narrowing source appears, but only
  after load-shape profiling of the hot list/gate paths.

