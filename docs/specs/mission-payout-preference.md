> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-06-06.
> **Owner area:** MISSION · **Related ADRs:** none

# Mission payout preference

## Context & goal

When a member signs up to a mission they carry a per-participant **payout preference** — `PAYOUT`
(cash out their share) or `DONATE` (give it to the squadron). Historically every new participant was
initialised to `PAYOUT`, so a member who always donates had to flip the choice by hand in every
single mission. This spec governs the **personal default** a user sets once in their own profile,
which pre-fills that per-participant choice at sign-up. Tracked by issue #469.

It is the mission-side companion to the per-participant payout field introduced with mission
finances (migration `V42`); the per-mission editing flow itself is unchanged.

## Requirements

### REQ-MISSION-002 — Profile default payout preference pre-fills mission sign-up

A user may store a personal **default payout preference** (`PAYOUT` / `DONATE`) on their own
profile. When that user signs up to a mission (a registered-user `MissionParticipant` is created),
the new participant's `payoutPreference` is **seeded from the user's default**. The default is a
**pre-fill only**:

- A user who has expressed **no explicit choice** (default is `null`) signs up as `PAYOUT` — the
  behaviour for everyone who never touches the setting is unchanged.
- The per-mission preference stays **independently editable** through the existing payout overview
  and continues to win over the profile default; that editing flow is unchanged.
- Changing the profile default is **forward-only** — it never rewrites `payoutPreference` on
  existing participations.
- **External participants** have no profile to read a default from, so their row is recorded as
  `PAYOUT`.
- A user edits **only their own** default (the value is read for the signing-up user and written for
  the JWT subject), under the user row's optimistic-lock `version` — a stale write surfaces as a
  409, not a silent overwrite.

**Acceptance**

- [ ] A signed-in user can set their default payout preference (`PAYOUT` / `DONATE`) in their profile and it persists across sessions.
- [ ] A newly created participant for that user is initialised with the user's default; a user with no explicit choice (`null`) is initialised `PAYOUT`.
- [ ] Changing the profile default does not alter existing participations; the per-mission preference remains editable and overrides the default.
- [ ] An external participant's row is unaffected (remains `PAYOUT`).
- [ ] A concurrent profile edit surfaces as a 409 (optimistic lock), not a silent overwrite.

**Enforced by:** `MissionServiceTest` (sign-up pre-fill), `UserServiceSyncTest`
(`UpdateUserDefaultPayoutPreferenceTests`), `UserControllerTest` (`/me/payout-preference` GET/PUT),
`UserPayoutPreferenceValidationTest` (`@Valid` → 400 on a missing preference/version),
`ProfileControllerTest`. **Code:** `User.defaultPayoutPreference` (migration `V142`),
`UserService#updateUserDefaultPayoutPreference`, `UserController` `/api/v1/users/me/payout-preference`,
`MissionService#addParticipant`, `frontend/.../ProfileController` + `templates/profile.html`.
**Issues:** #469.

## Out of scope

- The per-participant payout mechanics and mission-finance amounts — see
  [`whole-number-amounts.md`](whole-number-amounts.md) (`REQ-MISSION-001`).
- A squadron- or org-unit-wide payout default — deliberately rejected; donate-vs-payout is a
  personal choice, not an org policy.

## Open questions

None.
