# ADR-0373 — The active org-unit context has one pin rule and a stored choice

- **Status:** Accepted
- **Date:** 2026-11-11
- **Deciders:** @greluc
- **Amends:** [ADR-0300](0300-officer-reach-is-derived-per-org-unit-and-the-keycloak-officer-role-is-retired.md)
  — decision 2: the active context drives role powers and writes, not only visibility; decision 4:
  without `KRT Member` no membership confers anything, the explicit flags included ·
  [ADR-0026](0026-cascading-scope-without-admin.md) and
  [ADR-0027](0027-bereich-ol-aggregate-ownership.md) — the cascade needs `KRT Member`, the direct
  memberships that stamp are the active ones of a `KRT Member`, and a mission or operation created
  without a pick is stamped on the honoured pin ·
  [ADR-0022](0022-bank-booking-request-notifications-account-grant-selector.md) — `ACCOUNT_GRANT`
  resolves only grantees who hold `Bank Employee` or a role above it ·
  [ADR-0352](0352-one-explicit-bank-access-policy.md) — decision 4: the policy receives the
  honoured pin, not the header as sent
- **Narrows:** [ADR-0004](0004-ownerless-leadership-missions.md) and
  [ADR-0005](0005-ownerless-leadership-operations.md) — a mission or operation is ownerless only
  when its creator has neither a stampable unit nor a pin, an admin manages one only without a pin,
  and linking a mission to an ownerless operation needs the right to edit it ·
  [ADR-0006](0006-operation-participant-visibility.md) — the participant escape opens no mission
  link
- **Related:** REQ-ORG-002, REQ-ORG-004, REQ-ORG-008, REQ-ORG-017, REQ-ORG-023, REQ-ORG-024,
  REQ-ORG-031, REQ-ORG-032, REQ-OBS-011, REQ-SEC-005, REQ-SEC-015, REQ-SEC-065, REQ-ROLE-004,
  REQ-ROLE-007, REQ-BANK-034, REQ-INV-027, REQ-SUP-003, REQ-UI-026, REQ-FE-001; ADR-0048, ADR-0298,
  ADR-0329

## Context

A member of the Staffel Hel and the Spezialkommando Argon reported that the switcher in the
navigation always showed Hel, jumped back to Hel when she changed it, and that the Lager still
listed Argon's material. The backend gave every unpinned non-admin the union of their units
(REQ-ORG-002), while the web showed the backend's "home Staffel" (`currentSquadronId()`, the
name-sorted primary Staffel, SK pins ignored) as the selected unit. „Alle meine Org-Einheiten" could
therefore never appear selected, choosing it re-rendered Hel, and choosing Hel posted nothing.

An audit of the whole visibility system found the same split elsewhere. The pin was honoured in
different ways by different methods: a stale or foreign pin meant "no pin" for the list scope but
"nothing" for the grants, the officer reach and `narrowToPin`. Role gates (`hasRoleInOrgUnit`,
`canManageMissionsOf`, `canActAsOfficerIn`) ignored the pin, while the lists honoured it. A Bereich
pin meant the Bereich's own rows on the lists but the Bereich and everything below on the Leitung
page. The pin lived in the session only, so it was lost at every login and other tabs kept showing
the previous unit while their in-place refreshes read the new one.

## Decision

The owner decided (2026-11-11):

1. **Union with a truthful label (D1).** An unpinned member with several units sees the union of
   their units, and every surface says so: the switcher, the title and the context line read „Alle
   meine Org-Einheiten". A chosen unit narrows every scoped surface to that unit.
2. **The choice is stored per member (D2).** Every switch is stored in `member_active_org_unit`
   (scope module, own version) and restored into the session at its first authenticated request. A
   stored unit the member may no longer select reads as no choice. A change is counted, not
   audited: like the navigation preferences (ADR-0298) it is the member's own presentation choice,
   and a trail of who looked at which unit when would serve no oversight.
3. **Everything follows the selected unit (D3).** Reads, writes, role powers (Logistician, Mission
   Manager, officer, supply and purchasing managers, oversight, bank view grants) and creation
   without an explicit owner pick act only in the selected unit; „Alle meine" covers all own units.
4. **A Bereich or OL pin means exactly that unit (D4)**, on every surface. Leaders use „Alle meine"
   for the whole subtree.
5. **Personal views stay unit-independent (D5):** „Mein Lager", „Mein Hangar" and the own refinery
   orders list every own row, marked with its unit (REQ-ORG-011).
6. **Other tabs follow a switch (D6).** Every page announces its context on a `BroadcastChannel`;
   a tab showing another context reloads, and asks first through a KRT dialog when it holds unsaved
   input.

The owner settled the follow-up questions of the audit the same day:

7. **No membership right without `KRT Member` (D7).** A member without the role reaches no unit:
   no reach, no pin, no switcher entry, no seat or plain-membership right and no explicit
   Logistiker or Einsatzleiter flag (ADR-0300 decision 4, extended to the flags), no bank
   membership audience and no membership-derived notification. Bank roles stay a separate axis.
8. **Personal rows land in an own unit (D8).** Creation without a pick stamps a mission or an
   operation on the pinned unit, which its creator may always edit; a personal row (ship, Lager
   row, refinery order) only when the pin is one of its owner's direct memberships.
9. **Cross-unit by design (D9).** The member directory is organisation-wide; the notification inbox
   is personal; only the org-unit bank follows the pin — its view grants and its account holders'
   settings and approvals — while the KRT, cartel bank and Sonderkonto accounts, the transfer
   targets, the bank-staff side and connected applications do not; an operation's finances keep
   the refinery orders of every linked mission; a mission keeps showing its operation to everyone
   who may see the mission; the admins' administration tools stay organisation-wide. Linking a
   mission to an operation needs the right to edit that operation.
10. **A deactivated unit confers nothing (D10).** Deactivating a unit keeps its memberships for a
    reactivation, and until then they confer nothing: no reach, no role from a flag or seat, no
    leadership cascade into or out of the unit, no notice as its member or seat holder, and no new
    or moved row. Admins without a pin still see its data and owners their personal rows. The
    supply and purchasing oversight of the governing leadership, admins included, stays
    organisation-wide and does not follow the pin.

From these follows one pin rule, implemented once in `RequestScopeResolver`:

- A pin is **honoured** when it names a unit the caller may select: for an admin an existing active
  org unit, for everyone else an active unit of their reach (memberships plus the leadership
  cascade). Every other pin counts as no pin, on every surface.
- The pinned context is exactly the pinned unit; no pin is the caller's whole reach.
- Every surface intersects its own reach with the context. Out of context is nothing, never a fall
  back to the union.
- The effective context (`ActiveOrgUnitContext`: one unit, all own units, all units, none) is what
  `/me/layout` reports and what the web displays; the web no longer derives a context of its own.

The surfaces apply it as follows:

- Admins act within their own scope too: every unit unpinned, the pinned unit pinned. That covers
  managing a mission, its owner change and its finance entries, a unit's supply, and the balance
  target, view grants and approval limits of an org-unit or Bereich account; the Bankleitung's
  approval-limit right and the admins' administration tools do not read the pin — the supply
  unit-settings page lists and changes every unit, and only the manage flag it returns follows the
  pin. A mission's owner and co-managers keep their rights under any pin.
- The tabs of „Bedarf & Spenden" and „Ankauf" stay independent of the pin, as
  [ADR-0329](0329-the-supply-and-purchasing-tabs-are-capability-flags-of-the-layout-read.md)
  decision 2 requires: a tab flag that reads false deletes the member's stored favourite, phone
  slot and start page of that tab, so the flags of „Eingehende Spenden", „Statistik" and „Bericht"
  count the units a caller manages in any context. The manage flags, lists and writes behind the
  tabs follow the pin like every other role power.
- A target without an owning unit passes an officer, Logistician or Mission Manager gate only for
  an admin without a pin, like an ownerless personal row.
- A new row is stamped on its owner's or creator's **stampable units**: the direct memberships on
  active units, none without the stored role `KRT Member`. Without a pick, a mission or operation
  takes the honoured pin, else the creator's only stampable unit; a personal row takes the pin only
  when it is one of its owner's stampable units, else the owner's only one. An admin's row for a
  member uses the member's units, never the admin's pin.
- One check, `AuthHelperService#holdsKrtMember()`, decides whether a caller's memberships confer
  anything: the role, or the exchange marker of an acting member who holds it. One read,
  `OrgUnitMembershipRepository#findAllOnActiveOrgUnitsByIdUserId`, gives every access decision the
  memberships on active units; the leadership cascade and the notice recipients read active units
  only, while the administration's member and roster views keep the stored memberships.
- Two switches of one member never conflict: a write without a version that loses a concurrent
  write is retried in a fresh transaction, and the latest switch wins.
- A Lager earmark names its mission only to a reader who may see the mission, „Unbekannter
  Einsatz" otherwise; the earmark writes and undos look the earmark up without the reader's rights.

The named cross-unit escapes of REQ-ORG-003 are unchanged; D9 adds the features it names, which
REQ-ORG-032 lists.

## Consequences

The switcher, the title, the owner pickers and the dashboard agree with the data on every page. A
member who wants one unit's data picks it once and finds it again after the next login. A
multi-unit member's owner pickers show „Bitte wählen" in the union, as the backend requires a
choice there.

The stored choice is personal data: it is exported (Art. 15/20), deleted with the account and stays
with the source account in a merge. A switch writes no audit row; a counter records how often
members switch.

An offboarded member who keeps a bank role or a flagged membership no longer reads or writes any
unit's data, and an SK lead can no longer bring them back by ticking a flag; their new personal
rows get no unit, as do those of a member whose only unit was deactivated. A deactivated unit's
members lose its data, its rights and its notices until it is reactivated, and its SK lead can no
longer manage its roster. Admins pinned to a unit see and act on that unit only, outside their
administration tools, and manage no mission without a unit.

A stale pin no longer hides Logistician or officer surfaces, and a hand-crafted pin outside the
caller's reach widens nothing. Role holders acting in a pinned unit can no longer write in another
unit without switching; leaders see their subtree only under „Alle meine".

A tab with unsaved input that is told to stay keeps the previous labels until it is reloaded.

## Alternatives considered

- **Always exactly one active unit, defaulting to the home Staffel.** Rejected: every list would
  change its default, and leaders would lose their overview by default.
- **Home Staffel by default, „Alle meine" on request.** Rejected: a second session state and a
  special rule for leaders without a Staffel, for no gain over a truthful union.
- **A per-tab context for in-place refreshes.** Rejected: full-page form posts still carry the
  session context, so a tab would mix two contexts.
- **Storing the choice on `app_user` or in the navigation preferences.** Rejected: a column on
  `app_user` bumps the account's version on every switch, and the navigation module ranks below the
  scope module that validates the choice.
