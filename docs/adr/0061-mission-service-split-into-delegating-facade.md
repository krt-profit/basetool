# ADR-0061 — Split `MissionService` into focused services behind a delegating orchestration facade

- **Status:** Proposed
- **Date:** 2026-07-02
- **Deciders:** Repository owner (@greluc)
- **Related:** issue #920 (L1, epic #905) · ADR-0057 (`objectivesVersion`) · ADR-0050 (`owningOrgUnitVersion`) · spec [`org-unit-tenancy.md`](../specs/org-unit-tenancy.md) `REQ-ORG-018` · the CLAUDE.md optimistic-lock rules

## Context

`MissionService` had grown to 2766 LOC with 21 `private final` dependencies and seven distinct
responsibilities behind one `@Transactional` bean: search/visibility, core/schedule/flags section
updates, the participant lifecycle, the units/crews structure, the Ablauf steps + objectives
timeline, frequencies, and owner management. Issue #920 prescribed a two-step split; **step 1**
(#947, ADR carried inline in CLAUDE.md) collapsed the per-section version helpers into the parameterized
`assertSectionVersion` / `bumpSectionVersion` pair. **This ADR covers step 2: the class split.**

The mission code is concurrency-hot. It carries the canonical fine-grained optimistic-lock design
(REQ-ORG-018): manual `coreVersion` / `scheduleVersion` / `flagsVersion` / `partyLeadVersion` /
`stepsVersion` / `objectivesVersion` / `owningOrgUnitVersion` counters (plain business `Long`s, **not**
JPA `@Version`), the "participant edits never `save(mission)`" invariant, and per-participant/per-unit
`@Version` locks. Any regression here is a silent 409 or a cross-tenant leak, so the split had to be
**behaviour-preserving to the byte**.

## Decision

**Extract three focused `@Service`s and keep `MissionService` as a delegating orchestration facade.**

- **`MissionTimelineService`** — Ablauf steps + objectives (Ziele).
- **`MissionParticipantService`** — participant lifecycle (sign-up incl. guest + capability token,
  attribute edit, check-in/out, payout preference, removal), party-lead, co-manager add/remove, and
  the org-unit resolve helpers.
- **`MissionStructureService`** — units + crews and their helpers.

Each moved method body is **verbatim**. `MissionService` keeps **every** public method; each moved
method's body becomes a one-line delegation to the matching sub-service method, and each sub-service
injects only the repositories/services it actually needs (so `MissionService` sheds ~11 dependencies
and ~1200 LOC, landing at ~1570 LOC covering core/schedule/flags + search + frequencies + owner +
`addSubMission`).

**Delegation, not controller-repoint.** The sole caller, `MissionController`, is left untouched; its
public entry points and the per-operation `@Transactional` boundaries are byte-identical. This is the
lowest-regression option and matches the issue's "keep `MissionService` as the … orchestration
facade" wording. A delegating facade method annotated `@Transactional` calling one `@Transactional`
sub-service method is a single joined transaction (propagation `REQUIRED`), so the tx boundary,
`@Version` writeback, and audit timing are unchanged. There is **no cross-cluster call** among the
moved methods (verified: the only internal references were within-cluster), so the `…WithinTransaction`
double-`@Version`-bump hazard does not arise.

**Shared section-version guard.** The step-1 `MissionSection` enum + `assertSectionVersion` /
`bumpSectionVersion` helpers are promoted from a private nested type into a static
`support.MissionSectionVersions` utility (mirroring `support.OptimisticLock`), static-imported by
`MissionService` (core/schedule/flags/owning-org-unit), `MissionTimelineService` (steps/objectives)
and `MissionParticipantService` (party-lead). The deliberate `null → 0L` semantics are preserved. The
step-1 call sites in `MissionService` stay byte-identical under the static import.

**Coupling.** The bean-injection direction is one-way (`MissionService` → the three sub-services); the
sub-services do **not** inject `MissionService`, so there is no Spring cycle. The only sub-facade code
reference is `MissionParticipantService` reading the public `MissionService.MAX_PARTICIPANTS_PER_MISSION`
policy constant — a benign compile-time static read, left on the facade as a mission-level policy.

## Consequences

- **No behaviour change.** `openapi.json` is unchanged (no controller/DTO touched); audit events,
  lock/version semantics, and the participant "no `save(mission)`" invariant are preserved verbatim.
  The full backend suite — including the mission integration, concurrency, ArchUnit and access-control
  tests — passes unchanged in substance.
- **Tests follow the code.** The step/objective and crew/participant-only unit tests retarget their
  `@InjectMocks` onto the new service. The **mixed** unit tests (section-patch, lifecycle, and the
  broad `MissionServiceTest`) keep `@InjectMocks MissionService` and wire a real, mock-fed sub-service
  into it via `ReflectionTestUtils.setField` in `@BeforeEach` — because Mockito does **not** inject one
  `@InjectMocks` target into another. This runs the real delegated logic against the same mock pool, so
  their existing assertions hold without moving test methods.
- **Each responsibility is now independently testable** with a small dependency set, and `MissionService`
  reads as an orchestration facade rather than a god-class.
- **Future slices** (frequencies, owner) can extract the same way if the facade grows again; the pattern
  and the shared guard are in place.
