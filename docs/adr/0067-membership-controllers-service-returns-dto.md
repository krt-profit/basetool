# ADR-0067 — Move the org-unit-membership entity→DTO mapping into the service; retire the mapper-forced controller `@Transactional`

- **Status:** Proposed
- **Date:** 2026-07-03
- **Deciders:** Repository owner (@greluc)
- **Related:** issue #923 (L4, epic #905) · the ArchUnit rules `controllerMethodsShouldNotReturnJpaEntities` (kept), `controllersUsingTheLazyMembershipMapperMustBeTransactional` (deleted) and `controllersMustNotInjectTheLazyMembershipMapper` (added as its inverted replacement) · REQ-FE-003 (optimistic-lock version echo)

## Context

Four `@RestController`s carried a class-level `@Transactional` purely as an Open-Session-In-View replacement: they mapped a service-returned JPA entity to its response DTO *in the controller*, and `OrgUnitMembershipMapper.toDto` reads `user.effectiveName` through the **lazy** `user` association — so without an open session the write committed but the response 500'd (the `/organisation/leitung` "assign Kommandoleiter" regression). The `controllersUsingTheLazyMembershipMapperMustBeTransactional` ArchUnit rule pinned that: any controller injecting `OrgUnitMembershipMapper` must be `@Transactional`.

Issue #923 (L4) wants the mapping moved into the service so the mapper injection **and** the forced `@Transactional` disappear from the controller, and the rule retired.

Two facts shaped the implementation:

1. **`OrgUnitMembershipDto` is a lossy projection** — it carries `isLead` (a boolean derived from `role == SK_LEAD`), **not** the full `role`. The `OrgUnitMembershipService` unit tests assert on the assigned rank (`assignSquadronRank` sets `KOMMANDOLEITER`, etc.), which the DTO cannot express. Changing the service methods' return type to the DTO would have destroyed that coverage.
2. **`UserController` also depends on the session for `userMapper`.** `UserMapper.toDto` is repository-coupled (`@Autowired OrgUnitMembershipRepository`, `resolveSquadron`/`resolveLogistician` queries) and reads the lazy `user.getRoles()` collection — so `UserController`'s class `@Transactional` serves `userMapper` too, not just `orgUnitMembershipMapper` (a coupling the ArchUnit rule never detected).

## Decision

**Add controller-facing DTO-projection methods to `OrgUnitMembershipService` and keep the entity-returning methods authoritative.** Because of fact (1), the entity methods keep their signatures, business logic, audit calls and full-fidelity unit tests (the only in-place change is the `save` → `saveAndFlush` conversion described below); each grows a thin `…Dto` sibling that maps the just-persisted row **inside the service transaction**:

- `listMemberDtos` / `addMemberDto` / `patchFlagsDto` / `patchSquadronMemberFlagsDto` / `toggleLeadDto` / `assignSquadronRankDto` / `removeSquadronRankDto` — each `orgUnitMembershipMapper.toDto(<entityMethod>(...))`.
- `findAllMembershipDtosForUser` for the `UserController` membership endpoints, delegating to a **private** `toDtos(List<OrgUnitMembership>)`. The list projection is deliberately not public: mapping caller-supplied entities is only safe while the session that loaded them is still open — a contract only same-transaction callers inside the service can guarantee — so external callers get the loading + mapping as one transactional unit instead.

Each **write** wrapper carries its own `@Transactional` so the self-invoked write runs read-write (not trapped in the class-level `readOnly` default), and the underlying entity methods persist with `saveAndFlush` (not `save`) so the mapping sees the bumped `@Version` (REQ-FE-003 — `patchFlags`, `patchSquadronMemberFlags` and `toggleLead` were converted from plain `save` in the same change; `assignSquadronRank`/`removeSquadronRank` already flushed). The read wrappers inherit the `readOnly` default. The `UserService.addMember` caller that mutates the returned managed row in place is unaffected — it still calls the entity method.

**The three small membership controllers drop the mapper field and the class `@Transactional`** (their only lazy mapping was `orgUnitMembershipMapper`). **`UserController` drops the `orgUnitMembershipMapper` field** and both membership endpoints become session-independent: `GET /{id}/memberships/detail` and the `PATCH /{id}/memberships` response both project via `findAllMembershipDtosForUser` (the PATCH applies the delta first, then re-reads inside the membership service's own transaction), so neither depends on the controller-spanning session. `UserController` **keeps its class `@Transactional`** — now standing for `userMapper` alone, documented in its class Javadoc. Fully retiring it (projecting `UserDto` in the service) is a larger, separate change and is explicitly out of scope; because the membership endpoints no longer lean on the annotation, that follow-up only has to move the `UserDto` projection.

**Replace `controllersUsingTheLazyMembershipMapperMustBeTransactional` with the inverted pin `controllersMustNotInjectTheLazyMembershipMapper`** — the old rule ("a controller that injects the mapper must be `@Transactional`") became vacuous once no `@RestController` injected `OrgUnitMembershipMapper` anymore, but the mapper is still an injectable Spring bean and open-in-view is off, so an unguarded reintroduction would compile, pass mock-based tests and 500 in production after the write committed. The new rule pins the invariant this ADR establishes instead: membership DTO projection lives in the service, so **no** controller may depend on the mapper at all. The sister rule `controllerMethodsShouldNotReturnJpaEntities` (the general "controllers return DTOs" guard) stays and is reinforced.

## Consequences

- **No API change** — every endpoint returns the same DTO shape and path; `openapi.json` is unchanged. Only *where* the `toDto` runs moved (service transaction vs controller transaction), and the flag-patch / lead-toggle responses now carry the **bumped** `@Version` (the pre-existing plain-`save` mapping returned the stale pre-flush version, so a client editing the same row twice in a row hit a spurious 409).
- **The `/organisation/leitung` 500 is closed at its root** — the membership mapping can no longer run after a committed transaction — and the invariant stays pinned: `controllersMustNotInjectTheLazyMembershipMapper` fails the build if a future controller reintroduces controller-side mapping.
- **Tests follow the code**: the `OrgUnitMembershipService` business-logic unit tests still assert on the entity (incl. the full rank); the new `…Dto` wrappers are covered by dedicated unit tests that run the **real** MapStruct mapper and pin the flushed-version contract; the `SpecialCommandMembershipController` unit test and the `DelegatedAppointmentControllerSecurityTest` gate matrix mock the `…Dto` service methods; the full backend suite, Checkstyle/SpotBugs/Spotless and ArchUnit pass.
- **`UserController` keeps a class `@Transactional`** for `userMapper` — a deliberate, documented residue. Retiring it (moving the repository-coupled `UserDto` projection into `UserService`) is a follow-up, and since both membership endpoints project through the membership service's own transactions, that follow-up no longer risks the membership-delta response.

