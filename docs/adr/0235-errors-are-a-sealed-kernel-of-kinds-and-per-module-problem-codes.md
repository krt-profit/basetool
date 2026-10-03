# ADR-0235 — Errors are a sealed kernel of kinds plus per-module problem codes

- **Status:** Accepted — implementation pending (plan Phase 1, error-code registry in Phase 0.7)
- **Date:** 2026-10-01
- **Deciders:** @greluc (owner decision D-09)
- **Related:** [domain modularisation plan](../DOMAIN_MODULARISATION_PLAN.md) §5.5, §8.1 ·
  [REST API cut](../modularisation/rest-api-cut.md) (*Contract machinery*, error-code registry) ·
  spec REQ-API-004, REQ-API-007 ·
  [ADR-0231](0231-the-backend-becomes-a-modular-monolith-one-package-per-domain.md) ·
  [ADR-0234](0234-the-api-is-re-cut-by-hard-cut-with-a-forced-app-update.md)

## Context

The backend's `AppException` is a `sealed` class that permits thirteen subclasses, domain
exceptions among them (`BankConflictException`, `ExchangeProblemException`,
`MissionParticipantRequiredException`, `OverAllocationException`, `ProductionAllocationException`,
…), and `AppExceptionKind` holds domain codes. In a class-path application a sealed class can permit
subclasses only in its own package (verified with javac 25), so this shape cannot follow the
domains into their modules (ADR-0231). Separately, the RFC 7807 body carries `code`,
`correlationId` and `fieldErrors`, but the committed `openapi.json` documents none of them, so the
app and the frontend learn the codes from the source.

## Decision

1. **A sealed kernel of generic kinds** in `kernel`: not found, conflict, validation, access and
   unavailable. Their permitted subclasses live in the kernel package.
2. **One non-sealed abstract `DomainProblem` base** in the kernel, permitted by the sealed root;
   every module's exceptions extend it from the module's own package.
3. **Codes are a `ProblemCode` interface implemented by one enum per module.** A code carries its
   HTTP status and its message key; the code string is the contract.
4. **A registry test** collects every `ProblemCode` implementation, asserts that the codes are
   unique, and compares them with a committed list, so a new, renamed or removed code is a reviewed
   change. It also asserts that every code the exception handler, the filters and the exceptions
   produce is registered.
5. **The codes are documented in OpenAPI.** The document's problem schema gains `code`,
   `correlationId` and `fieldErrors`; `code` stays a string with the known values listed, never a
   required enum, because a required enum would freeze the code list against every addition.
6. **`GlobalExceptionHandler` and `ErrorDisclosurePolicy` stay the single place** that decides what
   a response discloses.
7. **Sealed hierarchies stay inside one package and never cover JPA entities** (a Hibernate proxy
   cannot subclass a sealed class).

## Consequences

- A module owns its failure vocabulary; adding a domain error touches only its module and the
  registry list.
- The app can generate its constants from the document, and a code change is visible in review.
- Exhaustive switches over the generic kinds keep working; switches over a module's own codes are
  exhaustive within that module (ADR-0238).
- Existing codes keep their strings; the migration moves classes, not wire values.

## Alternatives considered

- **Keep every exception type in the kernel package and seal it** — no domain ownership and no code
  documentation; the kernel would grow with every domain error.
- **An unsealed root** — loses exhaustiveness over the generic kinds, which the handler relies on.
- **A required `code` enum in OpenAPI** — every new code would fail the frozen-contract enum guard.
