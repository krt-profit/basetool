# ADR-0286 — The mission controller calls the mission section services directly

- **Status:** Accepted
- **Date:** 2026-10-18
- **Deciders:** @greluc
- **Related:** supersedes [ADR-0061](0061-mission-service-split-into-delegating-facade.md) ·
  [ADR-0266](0266-the-org-unit-bank-seam-and-the-live-sync-relay-split-by-responsibility.md) ·
  [ADR-0062](0062-joborder-service-split-into-facade-and-collaborators.md) ·
  [ADR-0267](0267-a-size-ratchet-caps-java-files-and-methods-at-their-current-length.md) ·
  `REQ-OPS-041` · issue #2736

## Context

ADR-0061 split the mission god-class into `MissionTimelineService`, `MissionParticipantService` and
`MissionStructureService` and kept `MissionService` as a delegating facade, so that
`MissionController` stayed untouched. After the split, `MissionService` still held 28 public
methods whose body was a single call into one of the three section services, each repeating its
`@Transactional` and its full Javadoc. The facade had exactly one caller, the controller, and kept
`MissionService` at its Checkstyle size cap. The section services had no unit test classes of their
own: their behaviour was tested through the facade, built with a long positional argument list.

ADR-0266 rejected the same facade shape for the org-unit bank seam: a facade keeps every public
signature and its Javadoc, so the class barely shrinks and every change still touches it.

## Decision

- **`MissionController` injects `MissionTimelineService`, `MissionParticipantService` and
  `MissionStructureService`** and calls them for the steps and goals, the roster (sign-up,
  attributes, check-in and check-out, payout preference, removal, party lead, co-managers) and the
  units and crews.
- **`MissionService` keeps only the behaviour it implements**: the mission core, schedule, flags
  and owning org unit, search and visibility, frequencies, owner management, `addSubMission`,
  `detachFromOperation` and the policy constant `MAX_PARTICIPANTS_PER_MISSION`. It still calls
  `MissionTimelineService` for the goals and steps a create request seeds.
- **Nothing else moves.** Endpoints, request and response shapes, `@PreAuthorize` expressions and
  error mapping are unchanged. Every section-service method carries its own `@Transactional`, and
  the controller is class-`@Transactional`, so the transaction boundary, section-version checks
  and audit timing are the same as through the facade.
- **Each section service has its own unit tests** against mocks of its own collaborators
  (`MissionParticipantServiceTest`, `MissionStructureServiceTest`, and the timeline's
  `MissionStepServiceTest` and `MissionObjectiveServiceTest`); the `MissionService` tests keep only
  its own behaviour.
- The scope-guard pin `staffelScopedServicesMustWireOwnerScopeOrAuthHelper` keeps listing
  `MissionService` only. The section services neither list missions nor stamp an owning org unit;
  they act on one mission by id behind the controller's `MissionSecurityService` gates, so a scope
  guard field would be unused.

## Consequences

- `MissionService` shrinks from 1,301 to about 860 lines and leaves the size ratchet.
- A change to a section's behaviour touches its section service, its test and, when the endpoint
  changes, the controller — no facade method in between.
- The controller tests that mock the services in a Spring context mock the participant and
  structure services in one shared mock set, so the number of test contexts stays the same.
- [ADR-0062](0062-joborder-service-split-into-facade-and-collaborators.md) gave `JobOrderService`
  the same facade shape. It is left as it is and needs its own decision.

## Alternatives considered

- **Keep the delegating facade (ADR-0061)** — rejected for the reason ADR-0266 gives: the facade
  keeps every signature and its Javadoc, so the class does not shrink and every change still
  touches it.
- **One edit service per aggregate in front of the section services** — left to its own issue; it
  is a new building block, not a removal.
