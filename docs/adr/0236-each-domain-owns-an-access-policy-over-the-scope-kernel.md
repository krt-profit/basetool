# ADR-0236 — Each domain owns an access policy over the scope kernel

- **Status:** Accepted — implementation pending (plan Phases 2 to 4). Amends
  [ADR-0065](0065-ownerscope-and-orgunitbank-service-split.md).
- **Date:** 2026-09-29
- **Deciders:** @greluc (the plan decided on 2026-09-29)
- **Related:** [domain modularisation plan](../DOMAIN_MODULARISATION_PLAN.md) §5.4, §7.5 ·
  spec [`org-unit-tenancy.md`](../specs/org-unit-tenancy.md) (`REQ-ORG-*`), REQ-DATA-010 ·
  [ADR-0231](0231-the-backend-becomes-a-modular-monolith-one-package-per-domain.md) ·
  [ADR-0233](0233-module-boundaries-are-enforced-by-archunit-and-spring-modulith-in-test-scope.md)

## Context

`OwnerScopeService` and its slice `AccessGateService` (ADR-0065) decide access for nine domains (audit of 2026-09-29):
`OwnerScopeService` is used by 34 classes in 14 domains, `AccessGateService` reads nine
repositories of seven domains, and `@PreAuthorize` reaches `ownerScopeService` by bean name in 66 SpEL references.
The per-aggregate JPQL scope fragments live as package-private constants in `ScopeSpecifications`,
spliced into seven repositories, where javac folds them so no dependency tool sees them. The rule
"a list query and its per-row gate widen together" is kept by review across two classes in two
places. After a package move, neither the hub nor the spliced fragments can stay where they are.

## Decision

1. **One access policy per scoped aggregate, owned by its module**, each an explicitly named bean:
   `missionAccessPolicy`, `jobOrderAccessPolicy`, `inventoryAccessPolicy`, `refineryAccessPolicy`,
   `operationAccessPolicy`, `shipAccessPolicy`, and the promotion and blueprint-overview gates. A
   policy is built on the scope kernel (`RequestScopeResolver`, `ScopePredicate`) and the module's
   own repository.
2. **A policy owns both the per-row gate and the aggregate's JPQL scope fragment**, so a list query
   and its per-row gate change in one class.
3. **The `scope` module keeps the kernel**: `RequestScopeResolver`, `ScopePredicate`,
   `OrgUnitStampingService`, `ScopeSpecifications` for what is not aggregate-specific, the
   authorities converter — and `OwnerScopeService` and `AccessGateService` only until the policies
   have taken their methods over.
4. **Migration without a big bang**, the ADR-0065 pattern: name the `ownerScopeService` bean
   explicitly; add each policy as a delegate; re-point the SpEL of one domain per pull request; move
   the method bodies verbatim; delete the facade methods last.
5. **A differential verdict test per domain** evaluates the old and the new gate over one fixture
   matrix — admin pinned and unpinned; members of zero, one and two units; the Bereich cascade; the
   owner escape; an ownerless personal row; the operation participant escape; the job-order SK-queue
   and requester escapes; SK lead — and requires identical verdicts before the facade method goes.
6. **Controllers keep a coarse role gate that stands on its own**; the module API enforces scope and
   ownership, so non-HTTP callers (the exchange, listeners) meet the same business gates.
7. **A nested path keeps the gate on the child**: an operation moved under a parent path is still
   authorized on the child resource, and a child of another parent answers 404 after the scope
   check.

## Consequences

- The scope rules of a domain are read and tested in its module; the hub shrinks to the kernel.
- The SpEL bean names change domain by domain; plan guard G-04 (SpEL bean-reference test) must be
  green before the first re-point, because a missing bean fails at request time as HTTP 400.
- The bank seam is untouched: `OrgUnitBankAccessService` stays the only bridge (ADR-0020, ADR-0028).

## Alternatives considered

- **Keep the central hub and move it to `scope`** — every domain feature would keep editing one
  class that reads every domain's repositories, which is the coupling the cut removes.
- **One big-bang move of all gates** — a lost escape or a changed verdict leaks or hides rows
  silently; the differential test needs a small step to be readable.
- **Policies without the JPQL fragment** — the list query and the per-row gate would still drift
  apart in two classes.
