# ADR-0024 — Opt-in global blueprint sharing overrides org-unit scoping (read-only)

- **Status:** Accepted
- **Date:** 2026-06-19
- **Deciders:** @greluc
- **Related:** spec `REQ-INV-018` (new) · amends `REQ-INV-012` (availability overview) and `REQ-ORDERS-015` / `REQ-ORDERS-016` (item-order coverage) · honours `REQ-SEC-006` (per-`sub` isolation) and `REQ-SEC-007` (name-only exposure) · migration `V163`

## Context

A user's owned `personal_blueprint` rows surface in two leadership/planning views, and both
are **org-unit-scoped by membership**:

- the **blueprint-availability overview** (#364, `PersonalBlueprintOverviewService`) counts
  the members of the caller's oversight org units;
- the **item-order blueprint-coverage** view (`REQ-ORDERS-015`,
  `JobOrderItemBlueprintOwnersService`) counts the members of the order's responsible org unit.

This is a deliberate isolation property: `PersonalBlueprint` is a per-`sub` aggregate with no
org-unit column, and the services bridge it to org units strictly through membership so that one
member's blueprints never leak into another unit's planning. But it also produces a real gap:
a member of Staffel A who owns exactly the blueprint an SK order needs is **invisible** to the SK
planning that order, purely because of the org-unit boundary — even though that member would
happily build the item. The org wants users to be able to volunteer their collection across that
boundary.

## Decision

Add a single per-user opt-in flag `app_user.share_blueprints_globally`
(`BOOLEAN NOT NULL DEFAULT FALSE`, `User.shareBlueprintsGlobally`). While it is `true`, the user
is counted in **both** views for **every** org unit, regardless of their own membership.

- The two aggregations **union** the opted-in users' `owner_sub`s into the org-unit member set
  they already resolve, before the existing `personalBlueprintRepository.findAllByOwnerSubIn(…)`
  lookup. `owner_sub` equals `User.id` as text, so the new
  `UserRepository.findIdsBySharingBlueprintsGlobally()` returns ids that the services render via
  `toString()`. Owner counting is over a set of distinct subs, so a user who is both a member and
  a global sharer is counted once.
- The flag is **self-service**, modelled exactly on the existing `defaultPayoutPreference`
  precedent: its own lightweight `GET` / `PUT /api/v1/users/me/blueprint-sharing` (JWT-scoped,
  optimistic-locked), saved **in place** on the profile page next to the payout preference.
- The widening is **opt-in** (default off), **read-only** (it grants no edit rights on the
  owner's blueprints), and exposes the owner by **display name only** — `REQ-SEC-006` /
  `REQ-SEC-007` are upheld.
- The **viewer-access gates are unchanged**: `OwnerScopeService.canAccessBlueprintOverview`
  (admin / officer / SK-lead) and `canSeeJobOrderBlueprintOwners` (responsible-org-unit member /
  admin) still decide *who may open* each view. The opt-in changes only *whose* blueprints are
  counted *inside* a view the caller could already open.

## Consequences

- An opted-in user's blueprints appear in the availability overview of every leadership viewer
  and in the coverage of every responsible org unit — which is the point ("make them available to
  everyone"). Users who do not opt in see no change.
- The two services gain one extra `User` query each per request (the global-sharer id set),
  unioned into the existing member set. At org scale this is negligible and adds no N+1.
- The flag is a per-user, all-or-nothing switch — not per-blueprint. A user who wants to share
  only some blueprints cannot; if that need arises it is a future, finer-grained decision.
- A `null`-responsible legacy order would now *consider* global sharers in the aggregation, but
  the `canSeeJobOrderBlueprintOwners` gate already rejects such orders, so the view is unreachable.

## Alternatives considered

- **Per-blueprint visibility flag** (`personal_blueprint.is_globally_visible`) — rejected: the
  request is an all-or-nothing profile choice, and a per-row flag multiplies the UI/storage cost
  for a need nobody expressed. A per-user switch is simpler and matches the ask.
- **Leadership-forced visibility** (officers opt *other* members in) — rejected: it inverts the
  consent model. Cross-unit exposure must be the owner's own choice, not imposed on them.
- **Widen the viewer gate instead of the data** (let any leader see every unit's owners) —
  rejected: that weakens *who may open* the views for everyone, the opposite of a narrow,
  user-consented carve-out; it would expose non-consenting members.
