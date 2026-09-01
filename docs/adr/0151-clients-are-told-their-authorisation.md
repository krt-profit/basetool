# ADR-0151 — Clients are told their authorisation instead of deriving it

> **Status:** Accepted · **Date:** 2026-09-01 · **Deciders:** @greluc
> **Related:** ADR-0011 (the app knows its permissions and refuses in place), ADR-0047 (the leaf
> interface that keeps mappers out of the service layer), `REQ-SEC-047`, `REQ-SEC-048`,
> `docs/specs/security-and-access.md`, `ROLES_AND_PERMISSIONS.md`

## Context

An admin signed in to the Android app could not assign a Lager entry, could not rebook another
member's row, could not edit a job order, could not confirm an Operation payout, and was offered no
org unit to pin — only „Alle Org-Einheiten". The server permitted every one of those actions.

The cause is one mistake made in four places, and it is not a rounding error in a rule. The clients
were gating on `UserDto.isLogistician` and `UserDto.isMissionManager`. Those fields answer **"is
there a Staffel membership row with this flag set"** — `UserMapper.resolveLogistician` reads the
membership rows and nothing else:

```java
return loadStaffelMemberships(user).stream().anyMatch(OrgUnitMembership::isLogistician);
```

The mapper's own Javadoc, two methods further down, says what follows from that: the projection is
empty "when the user has no Staffel membership (**admins** / guests)". An admin holds no Staffel
membership *by design*. So the field is `false` for the one role that may do everything.

The server, meanwhile, authorises through the role hierarchy —

```
ADMIN   > LOGISTICIAN        ADMIN   > MISSION_MANAGER
OFFICER > LOGISTICIAN        OFFICER > MISSION_MANAGER
```

— via `hasRole(...)` and `AuthHelperService.isLogisticianOrAbove()`. Membership and authorisation
are two different questions that agree for a plain Logistician and disagree for both roles above
them.

The web frontend was not affected because it had spelled the hierarchy out by hand
(`InventoryPageController.hasLogisticianOrAbove()` enumerates `LOGISTICIAN || OFFICER || ADMIN`) and
had branched on `isAdmin()` for the org-unit switcher. That is the same rule written a second time
in a second language — it happened to be right, and nothing kept it right.

Two parts of the system already did this correctly and show what the answer looks like.
`MissionDto.canEdit` carries the server's own decision per row, and its Javadoc states the principle
outright: deriving it in the client "would reproduce the role hierarchy in the client and get it
wrong for exactly the people most entitled to act". `GET /me/capabilities` resolves the bank flags
`hasReachableRole`, with a comment explaining that a direct check "would hide the staff bank from
the people who run it" — the identical trap, seen once and then not generalised.

## Decision

**A client is told its authorisation. It never derives it from a role, a membership flag, or a
role name.**

Two shapes, chosen by what the question is about:

1. **Standing** — `GET /api/v1/me/capabilities` gains `isLogisticianOrAbove`,
   `isMissionManagerOrAbove` and `isAdmin`, resolved through the hierarchy. These gate whole
   surfaces: whether a menu entry exists, whether the org switcher offers a catalogue or a
   membership list.
2. **A particular row** — `InventoryItemDto` and `JobOrderDto` gain `canEdit`, joining
   `MissionDto`. Computed by `AccessGateService`, the same bean the endpoint's `@PreAuthorize`
   reaches through `OwnerScopeService`, so the flag and the gate that would refuse the write cannot
   drift apart.

A row flag carries the endpoint's **whole** rule. `JobOrderController` gates writes on
`hasRole('LOGISTICIAN') and canEditJobOrder(#id)`; `mayEditJobOrder` answers with both halves,
because the scope half alone would offer editing to a plain member whose own Staffel owns the order.

The mappers reach the gate through `StockViewerAccess`, a leaf interface in `support` implemented in
`service` — the ADR-0047 pattern, for the ADR-0047 reason: a `mapper → service` edge closes a
package cycle, and ArchUnit forbids a mapper from touching `SecurityContextHolder`.

`GET /api/v1/me/org-units` answers which org units the caller may pin, admin branch included, so
neither client carries that fork.

**This supersedes nothing and narrows ADR-0011.** ADR-0011 said the app knows its permissions and
refuses in place; that stays true. What changes is where "knows" comes from: the server, not a
client-side reading of role data.

## Consequences

**A client that gets this wrong now fails visibly rather than silently.** The old failure mode was a
missing button — no error, no log line, and no way for the member to tell an intentional restriction
from a defect. It took a user report to surface.

**The membership fields keep their meaning.** `isLogistician` / `isMissionManager` remain correct
answers to "does this member hold this grant in a Staffel", which is what the admin area's
membership editor needs. They are simply not authorisation, and REQ-SEC-047 says so where a reader
will find it.

**Two clients get lighter.** The web frontend drops its hand-written hierarchy check and its
org-unit branch; the Android client drops the derivation it should never have had.

**Payload cost.** Two DTOs grow by one boolean each. `canEdit` is computed per row through
`findById`, which for a row already in the persistence context is served from Hibernate's L1 cache
rather than a second query — the same shape `MissionDto.canEdit` has carried since ADR-0047 without
trouble.

**What was considered and rejected:** inferring admin in the client from `permissions` containing
`ROLE_MANAGE`. It works today by accident of seeding, is an inference rather than a statement, and
breaks silently the first time another role is given that permission — the same class of mistake
one level down.
