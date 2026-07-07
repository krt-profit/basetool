# ADR-0034 — Anonymous outsider view of public missions is operational by design, minus payout and free-text comment

- **Status:** Accepted
- **Date:** 2026-06-21
- **Deciders:** @greluc
- **Related:** spec REQ-SEC-021 · security audit finding L3 · MULTI_SQUADRON_PLAN §7 (historical plan; cited only in code Javadoc, no file in-repo) · issue (none)

## Context

The public mission sign-up surface is reachable without an account. `config/SecurityConfig.java` marks `GET /api/v1/missions/search`, `GET /api/v1/missions/next` and `GET /api/v1/missions/{id}` as `permitAll()` (and falls through to the same for `GET /api/v1/missions/**`). The `{id}` read is method-gated by `@ownerScopeService.canSeeMission(#id)`. The visibility decision splits across two methods:

- `OwnerScopeService.canSeeMissionRow` is the per-row escape — an org-owned mission is visible if the caller may see the owning org unit **or** the mission is non-internal (the cross-staffel public escape), so **any non-internal mission is visible to anyone**; an ownerless non-internal mission is likewise public.
- `OwnerScopeService.canSeeMission` adds audit hardening M-2: it walks the parent chain through `canSeeMissionRow` and, for **anonymous** callers only (`!authHelper.isAuthenticated()`), denies `COMPLETED`/`CANCELLED` missions.

For an **outsider** — an anonymous caller or an authenticated but role-less `GUEST` (`AuthHelperService.isMemberOrAbove()` is false) — `MissionController.getMissionById` then enforces two further gates **before redaction**: it throws `AccessDeniedException` if the mission is internal, and if its status is `COMPLETED`/`CANCELLED` (this is what blocks a role-less `GUEST` from past missions, since `canSeeMission`'s M-2 check covers only anonymous callers). Surviving outsider reads are redacted by `MissionController.cleanupOutsiderMissionForGuest` → `cleanupMissionForGuest` → `cleanupParticipantForGuest` → `cleanupUserForGuest`.

That chain strips the mission **description**, every participant's **email + real name + roles/permissions/joinDate/squadron** (`cleanupUserForGuest`), the **owner**, **managers**, and the `canEdit` / `canManageManagers` flags. (The mission **economy** — inventory entries and refinery orders — was removed from `MissionDto` entirely in #1138 and is served member-gated at its own endpoints, so there is nothing economy-related left on this DTO for the chain to clear.) The PII redaction is **statically enforced**: the ArchUnit rule `anonymousReadableMissionEndpointsMustRedactGuestPii` (audit finding C-1) in `ArchitectureTest.java` fails the build if any guest-reachable mission endpoint returning a PII-carrying DTO (`MissionDto` / `MissionParticipantDto` / `MissionFinanceEntryDto`, or a generic wrapper of those) skips a `cleanup…ForGuest` helper.

What the outsider view **keeps** is the operational shape of the op: the full participant **roster** with each member's public callsign tuple (`username` / `displayName` / `effectiveName` / `rank`), each participant's **org-unit affiliation**, the **desired/planned job type**, the free-text **comment**, **check-in/out times**, the **payout preference**, the assigned **units** (ship/unit assignments), the mission **frequencies**, the owning **organisation** (`owningSquadron`), the **schedule/status**, and the **party lead**. These retentions are documented as a product decision: `cleanupMissionForGuest` cites MULTI_SQUADRON_PLAN §7 ("Squadron shorthand is not sensitive") on the owning-squadron forward-through, `cleanupParticipantForGuest` documents the kept participant fields as "public per the squadron policy", and `cleanupOutsiderMissionForGuest` documents the kept set as an "explicit product decision".

Audit finding **L3** flags the residual exposure: with PII already removed, an unaffiliated observer can still scrape `/search` + `/{id}` with no account to assemble a real-time order-of-battle for every public operation — who is participating, their callsigns and ranks, ship/unit assignments, mission frequencies, and payout intent. This is operational-intelligence exposure, not a PII leak; the verifier rated it **LOW** and "intentional-by-design". This ADR exists to ratify the intentional part on the record and to trim the two fields whose public-coordination value does not justify their sensitivity.

## Decision

We **ratify the operational coordination fields** of the anonymous outsider mission view as intentional, and **trim two fields** from it.

The fields a public sign-up / op-board genuinely needs stay public on a non-internal mission: the participant roster callsign tuple, org-unit affiliation, desired/planned job type, assigned ship/unit, mission frequencies, owning organisation, schedule/status, and party lead. These are the information a prospective sign-up reads to decide whether and how to join — removing them would defeat the public-sign-up purpose the `permitAll()` endpoints exist to serve.

We **remove from the anonymous outsider view** the two fields with low public-coordination value and higher sensitivity:

- **`payoutPreference`** — a participant's financial intent, irrelevant to an outside observer deciding whether to join.
- the free-text **`comment`** — uncontrolled text that can carry incidental PII or private coordination notes the author never meant to publish.

Both fields **stay on the authenticated member-peer view** (`cleanupMissionForGuest`); only the strict outsider paths null them, via a small `stripOutsiderParticipantFields` helper applied in `cleanupOutsiderMissionForGuest` (the pass every outsider full-mission response routes through — `getMissionById` and the participant write endpoints) and in the `addParticipantSlim` outsider branch (`jwt == null || !isMemberOrAbove()`). The shared `cleanupParticipantForGuest` is deliberately left unchanged so the member-peer view keeps both fields. (`searchMissions` returns `MissionListDto`, which carries no roster, so it was never an exposure for these per-participant fields.) The C-1 ArchUnit rule continues to pass — the redaction chain is unchanged in shape, only stricter.

The public Thymeleaf `mission-detail.html` already null-tolerates both fields through safe-navigation (`${p?.payoutPreference?.name()}` on the payout `<select>`, `${!#strings.isEmpty(p?.comment)}` on the comment note-mark), so nulling them does not throw; we only confirm the visible result is acceptable — for an outsider the payout `<select>` renders with no option pre-selected and the comment note-mark does not appear.

**Accepted and implemented (2026-06-21).** **REQ-SEC-021** has been added to `docs/specs/security-and-access.md` recording that the anonymous outsider mission view is an operational-coordination surface that exposes the public roster, unit assignments, frequencies and organisation of non-internal missions while withholding PII, financial intent and free-text comments.

## Consequences

- The exposure is bounded and documented rather than implicit. The public order-of-battle visibility is acknowledged as an accepted cost of running an open sign-up board, not an oversight; finding L3's "intentional-by-design" rating is now backed by a decision record and a binding REQ-SEC (REQ-SEC-021).
- Two of the higher-sensitivity, lower-value fields leave the anonymous surface, shrinking the scrape value without touching the sign-up workflow. Financial intent and uncontrolled free text are no longer published to unauthenticated callers.
- The trim is a one-method, two-field null-out at an existing chokepoint — no new endpoint, no migration, no change to `canSeeMission` / `canSeeMissionRow` and no change to the C-1 ArchUnit guard.
- The member-peer and authenticated views are unaffected, so members keep seeing payout and comment; the public view's payout `<select>` renders unselected and the comment note-mark is hidden for outsiders.
- The residual operational-intelligence exposure (roster, ranks, units, frequencies, organisation) remains accepted. If that posture ever needs to tighten, the same chokepoint is where it happens — this ADR establishes that `cleanupOutsiderMissionForGuest` is the single lever for the anonymous view's field set.

## Alternatives considered

- **Ratify everything as-is (no trim)** — rejected as the weaker posture. It is the lowest-effort option and the C-1 PII guard already holds, but it keeps publishing two fields (financial intent, uncontrolled free text) that a public op-board does not need and that carry real incidental-PII and sensitivity risk. Since the trim is a two-field null-out at one method with zero workflow cost, accepting the avoidable exposure is not justified.
- **Trim aggressively — also drop the roster, ranks, units and frequencies from the outsider view** — rejected. These are exactly the operational fields a prospective participant reads to decide whether and how to sign up; hiding them would gut the purpose of the `permitAll()` sign-up endpoints and push the whole flow behind authentication, a far larger product change than finding L3's LOW rating warrants. The order-of-battle visibility is the deliberate price of an open sign-up board (MULTI_SQUADRON_PLAN §7); if that price is ever judged too high, it is a separate, larger decision — gating `/{id}` behind authentication — not this one.
- **Gate the read endpoints behind authentication** — rejected here as out of scope. It would eliminate the anonymous order-of-battle entirely but breaks the unauthenticated public sign-up that the surface is built for. It is the fallback if the accepted residual exposure is later deemed unacceptable, and would itself warrant its own ADR.

