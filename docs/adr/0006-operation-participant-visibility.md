# ADR-0006 — Operation visibility for mission participants

- **Status:** Accepted — **narrowed by [ADR-0150](0150-the-participant-escape-does-not-open-the-operation-ledger.md)** (2026-08-30): the participant escape opens the operation, not its ledger
- **Date:** 2026-06-09
- **Deciders:** Repository owner (@greluc)
- **Related:** [ADR-0005](0005-ownerless-leadership-operations.md) · spec [`org-unit-tenancy.md`](../specs/org-unit-tenancy.md) `REQ-ORG-003/009` · issue #500

## Context

An `Operation` is the umbrella that groups missions and computes a per-participant payout. Until now
an operation was visible only by owning-OrgUnit scope ([ADR-0005](0005-ownerless-leadership-operations.md)
added the ownerless-leadership and members-or-above escape). That leaves a gap: a user who *flew in*
an operation's missions but is **not** a member of the operation's owning Staffel cannot see the
operation at all — and therefore cannot see their own payout. Cross-Staffel and joint operations make
this common: participants are routinely drawn from several Staffeln (and from guests). The owning
Staffel is an attribution detail, not a fence the participants should sit behind.

## Decision

Operations gain a **participant-visibility escape**: any *authenticated* user who participated in one
of the operation's linked missions may **view** the operation, regardless of its owning OrgUnit (or
lack of one). Anonymous callers are excluded — viewing requires a resolved `currentUserId`.

- A participant is a `mission_participant` row with `user_id = currentUserId` on a mission whose
  `operation_id` is the operation. Guest-name participants (no `user`) do not match — but a logged-in
  guest-role account that was added as a real participant does (only anonymous is excluded, per the
  requirement).
- Implemented as a `viewerUserId` EXISTS branch on all three scoped operation queries
  (`findAllScoped`, `findAllReferenceScoped`, `searchOperations`) and a matching
  `OwnerScopeService.canSeeOperation` check backed by
  `OperationRepository.existsParticipantUserInOperation`. List, picker and per-row detail gate move in
  lockstep, the invariant the codebase enforces everywhere to avoid list/detail divergence.
- The escape is **view-only**. `canEditOperation` is unchanged: editing stays role + owning-scope, so
  a participant gains read access (and payout visibility) but not the ability to mutate the operation.

## Consequences

- Participants — including those from other Staffeln and logged-in guests — can find an operation they
  flew in and see their payout. This is the behaviour squadrons expect for joint operations.
- Operation visibility is no longer purely strict-staffel: it has a participation-based cross-Staffel
  read escape. It is narrow (only your own participation widens it) and never widens *edit*.
- One extra query parameter (`viewerUserId`) is threaded through the three scoped queries and the gate
  in lockstep — the same lockstep discipline ADR-0005 applied to `viewerIsMemberOrAbove`.
- The EXISTS subquery joins `mission_participant → mission` on indexed columns
  (`mission_participant.user_id`, `mission.operation_id`); the operation table is small and the gate's
  point-existence check is a single indexed lookup, so the cost is negligible.
- No frontend change: the operation list/detail/picker already render whatever the backend returns.

## Alternatives considered

- **Owning-Staffel (+ members-or-above for ownerless) only — no participant escape.** Rejected: it is
  the status quo that leaves cross-Staffel participants unable to see their own payout, which is the
  whole point of the operation view.
- **Make every operation visible to all organisation members.** Rejected: operations are internal
  economy/payout records; broad org-wide visibility would leak one Staffel's finances to every member
  who never took part. Participation is the right, minimal key.
- **Resolve participation in Java by loading each operation's missions and participants.** Rejected:
  it reintroduces the N+1 the payout path already fights and is far heavier than an indexed EXISTS;
  the DB answers "did this user participate?" in one lookup.
- **Grant participants edit access too.** Rejected: participation is not authority over the operation;
  settling/deleting stays with mission managers and admins (role + scope).
