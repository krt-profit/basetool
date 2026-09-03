# ADR-0154 — Self-enrolment carries the sign-up sheet's answers, rather than borrowing the add-anybody endpoint

> **Status:** Accepted · **Date:** 2026-09-02 · **Deciders:** @greluc
> **Related:** `REQ-API-009` (the external contract set), `REQ-MISSION-002` (the profile-default
> payout chain), [ADR-0135](0135-public-api-vhost-not-a-gateway.md),
> [ADR-0136](0136-external-contract-set-for-shipped-clients.md),
> `MissionController#joinMission`, `JoinMissionRequest`,
> [`API_VHOST_ROLLOUT_RUNBOOK.md`](../API_VHOST_ROLLOUT_RUNBOOK.md)

## Context

Signing up for an Einsatz from the Android app failed with the app's generic „Konnte nicht
gespeichert werden." — **every time, only for signing up**. Signing *off* from the app worked, and
signing up from the web worked. Reported 2026-09-02.

`POST /api/v1/missions/{id}/join` exists and does exactly self-enrolment, but it took **no body**,
and the app's sign-up sheet collects two answers: the desired Funktion and the payout preference. So
the app posted to `POST …/participants/add` instead — the endpoint that can also add *other* people.
Its own KDoc records the reasoning and the check: *"Both endpoints are guarded by `canSeeMission`,
so this is the same permission through a door that fits — verified against the running stack rather
than inferred."*

The permission reasoning was right. The door was not.

**The API vhost is a default-deny allow-list** (ADR-0135), and for participants it exposes exactly
three paths:

|                             Allow-listed                              |      Used by the app for      |
|-----------------------------------------------------------------------|-------------------------------|
| `…/join`                                                              | *(nothing — it took no body)* |
| `…/participants/{uuid}/slim`                                          | signing off ✔                 |
| `…/participants/{uuid}/(check-in\|check-out\|payout-preference)/slim` | check-in / check-out ✔        |

`…/participants/add` is on **neither** the allow-list nor REQ-API-009's frozen contract set. The
sign-up was refused at the edge and never reached the backend, which is why it failed identically
every time while everything else the app does kept working.

> The verification that endorsed the choice ran against the **test stack**, and the test stack has
> no API vhost. The one gate that would have caught this does not exist locally, so the endorsement
> was true and still insufficient.

## Decision

**`POST /api/v1/missions/{id}/join` accepts an optional body carrying the sign-up sheet's answers,
and the app uses it.**

1. **The body and every field in it are optional** (`JoinMissionRequest`: `desiredJobTypeId`,
   `payoutPreference`). A bodyless `POST` behaves exactly as before. REQ-API-009 freezes this
   operation, and a new *required* request field is precisely the break that freeze forbids;
   optional additions are explicitly free.
2. **An absent field means "no answer", never "clear it".** A null `payoutPreference` keeps
   REQ-MISSION-002's default chain — the member's profile default, falling back to `PAYOUT`.
3. **The body cannot name anybody.** `join` derives the member from the JWT, so unlike
   `AddParticipantPublicRequest` it carries no `userId`, `guestName`, `orgUnitIds` or `comment`.
   That is what lets the endpoint skip the self-vs-manager check the add-anybody path needs, and it
   is the substance of the decision rather than a trimming of the payload.
4. **No production edge change.** `…/join` is already allow-listed and already frozen, so the fix
   ships through the existing surface.

## Alternatives rejected

**Allow-list `…/participants/add`.** It would work, and it is the smaller diff. It also puts an
endpoint that accepts `userId`, `guestName` and `orgUnitIds` on the public edge, with the backend's
self-vs-manager check as the only thing standing between a shipped client and adding other people —
a guard that exists and is tested, but one more thing that has to keep being right on a surface that
does not need to offer the capability at all. It additionally requires an allow-list change in the
NPM admin database on the production host, which no PR can review (ADR-0135).

**Two calls from the app: `join`, then `…/payout-preference/slim`.** Needs no server change at all,
since both paths are already exposed. Rejected because it makes one member action two round-trips
with a window in between where the sign-up exists and the preference does not — and because the
**desired Funktion has no allow-listed write path**, so that answer would simply be lost.

**Give `join` a required body.** Rejected: it breaks every shipped build that posts nothing, which
is exactly what REQ-API-009 protects.

## Consequences

**The narrow endpoint is now the complete one.** There is no longer a reason for a client to reach
for the add-anybody path to enrol itself, which is the property worth keeping: the capability a
surface offers should match what its callers need.

**The allow-list gap stays invisible locally.** This ADR does not fix that. A call to a path the
edge does not expose still passes every local test and every CI run, and surfaces only on a real
device against production. The durable guard is REQ-API-009's rule that the frozen set and the
allow-list move in the same change — which means **a client consuming a new operation is a contract
change, not an implementation detail**, and reviewing it as one is what would have caught this.

**The app's error copy did not help.** The refusal arrived without an RFC 7807 body, so
`fieldMessage()` was null and the sheet fell back to its own sentence. That is correct behaviour for
a 4xx with no problem body, but it means an edge refusal and a server-side validation failure read
identically to a member. Distinguishing them is left open.
