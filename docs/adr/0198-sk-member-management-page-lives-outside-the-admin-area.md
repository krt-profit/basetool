# ADR-0198 — The SK member-management page lives outside the admin area, reached from „Leitung"

- **Status:** Accepted
- **Date:** 2026-09-22
- **Deciders:** @greluc
- **Related:** ADR-0042 (delegated appointment ladder, the Leitung page) ·
  [`docs/specs/org-unit-tenancy.md`](../specs/org-unit-tenancy.md) (`REQ-ORG-005`) ·
  [`docs/specs/role-model.md`](../specs/role-model.md) (`REQ-ROLE-004`) ·
  [`ROLES_AND_PERMISSIONS.md`](../../ROLES_AND_PERMISSIONS.md) §3.9

## Context

`REQ-ORG-005` and the role matrix give an **SK lead** (a Spezialkommando membership with
`role = SK_LEAD`) the right to manage their own SK's members: list them, add and remove them, and set
their Logistiker / Einsatzmanager flags. The backend has enforced exactly that since the SK was
introduced — `SpecialCommandSecurityService.canManageMembers` admits an admin or the SK's own lead on
every endpoint under `/api/v1/special-commands/{id}/members`.

The web never followed. The only page that lists an SK's members was `/admin/special-commands/{id}`,
served by `AdminSpecialCommandsPageController`, which is class-level `hasRole('ADMIN')`. The SK read
that heads the page, `GET /api/v1/special-commands/{id}`, was admin-only too. So the right existed in
the API and no screen could exercise it. A documentation audit of the German user wiki against the
app found the contradiction on 2026-09-22; the wiki had been papering over it with „der SK-Lead hat
das Recht ebenfalls, die SK-Detailseite liegt aber in der Administration".

The repository owner decided that the right stands and the web moves to meet it, rather than the
right being withdrawn.

## Decision

1. **The member page gets its own route outside `/admin/**`:** `/organisation/special-commands/{id}`,
   served by a new frontend controller, `SpecialCommandMembersPageController`. It carries the
   detail view, add / remove member and the flag writes, each with its in-place AJAX twin. Its
   coarse gate is `Roles.ADMIN_OR_OFFICER`, the same as the „Leitung" page — every SK lead carries
   the operational `OFFICER` grant. The fine gate is the backend's per-SK `canManageMembers`, which
   the page relays: a backend 403 renders the standard 403 page.
2. **`GET /api/v1/special-commands/{id}` is widened** from `hasRole('ADMIN')` to
   `canManageMembers(#id)`, so the page can show the SK it manages. The SK list, lifecycle writes
   (create / rename / deactivate / activate / profit eligibility) and `includeInactive` stay admin
   only.
3. **The lead seat stays where it was.** The lead column renders on the member page for admins only.
   The Bereichsleiter of the parent Bereich keeps setting it on „Leitung"
   (`canAppointSkLead`, REQ-ROLE-004). An SK lead never sees a control that changes their own seat.
4. **„Leitung" is the entry point.** Its Spezialkommandos section lists every SK the caller may
   appoint the lead of **or** manage the members of (`LeitungUnitDto.canManageRoster`, now filled
   from `canManageMembers` for SKs). The lead toggle renders only with `canAppointLead`; a
   „Mitglieder verwalten" link to the member page renders with `canManageRoster`.
5. **`/admin/special-commands/{id}` redirects** to the new route, so a bookmark or an old link keeps
   working. The admin list and the member-edit page link to the new route directly.

## Alternatives considered

- **Withdraw the SK lead's right** (make `canManageMembers` admin-only and amend `REQ-ORG-005`).
  Rejected by the owner: SK leads are expected to run their own roster, and the backend had
  supported it all along.
- **Open the existing `/admin/special-commands/{id}` handlers to officers** with method-level gates.
  Rejected: `REQ-ORG-005` says the admin area is admin-only, and a page under `/admin/**` that
  non-admins may open makes that sentence untrue while the path still claims it. It would also have
  left the SK lead on a page whose back link and sidebar context point into an area they cannot
  enter.
- **A sidebar entry for SK leads.** Rejected for now: the frontend cannot tell an SK lead from
  another officer without asking the backend, and „Leitung" already asks it — it is the page where a
  leader looks for the units they run.

## Consequences

- An SK lead manages their SK's roster on the web for the first time.
- The frontend gate on the member page is deliberately coarser than the real one. An officer who
  leads no SK passes it and is stopped by the backend with a 403, which is the pattern the „Leitung"
  write proxies already follow.
- Member-roster mutations were already audited (Rollen & Mitglieder) and already updated in place;
  neither changes. No metric, alert or probe is affected — no endpoint was added to the backend, one
  gate was widened.
