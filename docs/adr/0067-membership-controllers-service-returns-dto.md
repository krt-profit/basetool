# ADR-0065 — Move the org-unit-membership entity→DTO mapping into the service; retire the mapper-forced controller `@Transactional`

- **Status:** Proposed
- **Date:** 2026-07-02
- **Deciders:** Repository owner (@greluc)
- **Related:** issue #923 (L4, epic #905) · the ArchUnit rules `controllerMethodsShouldNotReturnJpaEntities` (kept) and `controllersUsingTheLazyMembershipMapperMustBeTransactional` (deleted) · REQ-FE-003 (optimistic-lock version echo)

## Context

Four `@RestController`s carried a class-level `@Transactional` purely as an Open-Session-In-View replacement: they mapped a service-returned JPA entity to its response DTO *in the controller*, and `OrgUnitMembershipMapper.toDto` reads `user.effectiveName` through the **lazy** `user` association — so without an open session the write committed but the response 500'd (the `/organisation/leitung` "assign Kommandoleiter" regression). The `controllersUsingTheLazyMembershipMapperMustBeTransactional` ArchUnit rule pinned that: any controller injecting `OrgUnitMembershipMapper` must be `@Transactional`.

Issue #923 (L4) wants the mapping moved into the service so the mapper injection **and** the forced `@Transactional` disappear from the controller, and the rule retired.

Two facts shaped the implementation:

1. **`OrgUnitMembershipDto` is a lossy projection** — it carries `isLead` (a boolean derived from `role == SK_LEAD`), **not** the full `role`. The `OrgUnitMembershipService` unit tests assert on the assigned rank (`assignSquadronRank` sets `KOMMANDOLEITER`, etc.), which the DTO cannot express. Changing the service methods' return type to the DTO would have destroyed that coverage.
2. **`UserController` also depends on the session for `userMapper`.** `UserMapper.toDto` is repository-coupled (`@Autowired OrgUnitMembershipRepository`, `resolveSquadron`/`resolveLogistician` queries) and reads the lazy `user.getRoles()` collection — so `UserController`'s class `@Transactional` serves `userMapper` too, not just `orgUnitMembershipMapper` (a coupling the ArchUnit rule never detected).

## Decision

**Add controller-facing DTO-projection methods to `OrgUnitMembershipService` and keep the entity-returning methods authoritative.** Because of fact (1), the entity methods (with their full-fidelity unit tests and their audit/`saveAndFlush` write logic) stay byte-for-byte unchanged; each grows a thin `…Dto` sibling that maps the just-persisted row **inside the service transaction**:

- `listMemberDtos` / `addMemberDto` / `patchFlagsDto` / `patchSquadronMemberFlagsDto` / `toggleLeadDto` / `assignSquadronRankDto` / `removeSquadronRankDto` — each `orgUnitMembershipMapper.toDto(<entityMethod>(...))`.
- `findAllMembershipDtosForUser` + a public `toDtos(List<OrgUnitMembership>)` for the `UserController` membership-delta endpoints (whose entities come from `UserService.applyMembershipDelta`, left unchanged).

Each **write** wrapper carries its own `@Transactional` so the self-invoked write runs read-write (not trapped in the class-level `readOnly` default) and the mapping sees the bumped `@Version` (REQ-FE-003); the read wrappers inherit the `readOnly` default. The `UserService.addMember` caller that mutates the returned managed row in place is unaffected — it still calls the entity method.

**The three small membership controllers drop the mapper field and the class `@Transactional`** (their only lazy mapping was `orgUnitMembershipMapper`). **`UserController` drops the `orgUnitMembershipMapper` field** (its 2 sites now call the service projections) **but keeps its class `@Transactional`** — now standing for `userMapper` alone, documented in its class Javadoc. Fully retiring the `userMapper`-forced `@Transactional` (projecting `UserDto` in the service) is a larger, separate change and is explicitly out of scope.

**Delete `controllersUsingTheLazyMembershipMapperMustBeTransactional`** — no `@RestController` injects `OrgUnitMembershipMapper` anymore, so the rule is vacuous. The sister rule `controllerMethodsShouldNotReturnJpaEntities` (the general "controllers return DTOs" guard) stays and is reinforced.

## Consequences

- **No API change** — every endpoint returns the same DTO shape and path; `openapi.json` is unchanged. The DTO the client receives is byte-identical; only *where* the `toDto` runs moved (service transaction vs controller transaction).
- **The `/organisation/leitung` 500 is closed at its root** — the membership mapping can no longer run after a committed transaction, so the guard is no longer needed to prevent it.
- **Tests follow the code**: the `OrgUnitMembershipService` business-logic unit tests are untouched (they still assert on the entity, incl. the full rank); the `SpecialCommandMembershipController` unit test and the `DelegatedAppointmentControllerSecurityTest` gate matrix now mock the `…Dto` service methods; the full backend suite, Checkstyle/SpotBugs/Spotless and ArchUnit pass.
- **`UserController` keeps a class `@Transactional`** for `userMapper` — a deliberate, documented residue. Retiring it (moving the repository-coupled `UserDto` projection into `UserService`) is a follow-up.

