# ADR-0159 — The Basetool has no anonymous or guest surface; every account is a member

- **Status:** Accepted
- **Date:** 2026-09-06
- **Deciders:** @greluc
- **Requirement:** [REQ-SEC-052, REQ-SEC-053](../specs/security-and-access.md)
- **Supersedes:** [ADR-0034](0034-anonymous-outsider-mission-visibility.md) (the anonymous outsider view)
- **Related:**
  [ADR-0138](0138-terms-wording-is-a-backend-resource.md) and
  [ADR-0149](0149-the-job-order-request-form-requires-a-login.md) (both narrowed the surface before this;
  neither is amended, both keep their reasoning),
  [ADR-0104](0104-no-silent-caps-on-complete-list-surfaces.md) (the page-size refusal
  whose anonymous ceiling goes with the callers it bounded),
  REQ-SEC-007 (the member-peer redaction that survives), REQ-SEC-017 (the pending gate this pattern
  is modelled on), REQ-SEC-018 and REQ-SEC-021 (superseded), REQ-SEC-032 (the amplification lever),
  REQ-API-010 (the forced-update read that stays public)
- **Plan:** [`docs/MEMBERS_ONLY_PLAN.md`](../MEMBERS_ONLY_PLAN.md) — the inventory, the thirteen
  owner decisions of 2026-09-05, and the work packages this ADR records the decision for

## Context

The Basetool grew a public surface one argument at a time, and each argument was reasonable on its
own. A mission board that a prospective member could look at. A catalogue of game reference data
that carries no member data. An order form for somebody outside the organisation to ask for
material. A capability token so the guest who signed up could edit their own row without an account.

Read together rather than one at a time, that surface was:

- the whole game-data catalogue on **every verb** — ten families, `permitAll`, writes included at
  the URL layer;
- five mission `GET` rules, the mission `POST`, and twelve participant write patterns;
- `GET /api/v1/org-units/active`;
- on the frontend, `/missions`, `/missions/**`, `/operations/**`, `/orders/**` and `/catalog/**`,
  rendering the roster, the party lead, the Ablauf and the Ziele to anyone who asked.

It also had a shape nobody had decided on. A token whose realm roles mapped to no application role
was assigned the seeded `GUEST` role, whose authority set is empty — so "we could not resolve this
account's roles" and "this person is a guest" were the same state, and that state was granted the
anonymous read surface. The roster sync could produce it by accident: it reads *directly-assigned*
realm roles, and the realm grants `KRT Member` through the `default-roles-iri` composite, so a
member who holds membership only that way came back with nothing.

Three defects found while closing it are the argument for closing it, better than any of the above:

- `MissionController.joinMission` returned the **whole Einsatz, roster included**, to the member who
  had just joined — and that caller is by definition an ordinary member, the one most likely to be
  below Logistician. The ArchUnit rule that should have caught it selected only gates *without*
  `isAuthenticated()`, and this gate has always had one.
- Three UEX price reads (`prices-overview`, `{id}/prices`, `profit-calculation`) answered anonymously
  — the first two with `200`, the third with a `500` from dispatching and then crashing. Found by
  measuring the status before writing it down (runbook phase W, 2026-09-06).
- `GET /` minted a session for **every** anonymous visitor twice over: once through the
  `HttpSession` parameter Spring resolves with `getSession(true)`, once through `SafeCsrfAdvice`
  forcing the deferred CSRF token, which the default repository saves into a fresh session before
  any template runs.

None of these was found by reading the matrix. Each was found by asking what a specific caller
actually receives.

## Decision

**The Basetool has no anonymous surface beyond the landing page, the legal pages, the assets and
four enumerated infrastructure paths, and no role below member.** The exact lists are REQ-SEC-052
and REQ-SEC-053; this ADR records why, and what was rejected.

Two API paths keep answering without a token, both `GET`-scoped:

- `GET /api/v1/app/version-policy` — a version gate that only answers after a successful login is
  silent in exactly the case it exists for. An app too old to log in must still be able to learn
  that it is too old.
- `GET /api/v1/terms/document` — a document everyone must be able to read before agreeing to
  anything cannot require having agreed, and the same text is already published on `/terms`.

`/internal/**` stays `permitAll` at the URL layer and is not part of that list: it is
machine-to-machine behind a constant-time shared-secret header (REQ-SEC-022), and Keycloak sits
outside the resource server's trust boundary, so it carries no JWT to gate on.

An authenticated token that maps to no application role is **refused** with `403 NO_ROLE`, not
admitted with an empty authority set. An empty set passes every `isAuthenticated()` gate and fails
only the ones that name a role, which makes admission a per-endpoint accident rather than a
decision.

Guest participant rows stay as **external participants**: any member who can see the Einsatz may
record a named person without an account, and editing or removing such a row is the mission
leadership's, because the row carries no creator to bind a self-edit to.

## Alternatives rejected

- **Keep the guest self-sign-up behind a login-less capability token.** The token worked, and its
  design was sound — but it needed a `canSeeMission` re-check beside it, because the capability
  otherwise outlived the surface that granted it: a guest who signed up while a mission was public
  kept `PUT` / `DELETE` / check-in after it was flipped to internal or reached `COMPLETED`, and
  back-dating a settled operation moved real money away from every other participant. A credential
  that needs a second gate to be safe is a credential nobody is holding correctly. Rejected on the
  brief.
- **Keep the read-only catalogues.** They carry no member data, and that is true. It is also not
  the property that matters: the vhost admits paths one at a time, so "anonymous but unreachable"
  was doing the actual work, and the moment a path is admitted for the app the backend's stance
  becomes the whole gate. Rejected on "no anonymous access except the landing page".
- **Auto-promote a role-less token to member.** It would have made the roster-sync defect invisible
  instead of loud. Rejected: fail closed.
- **Keep `GUEST` as a dormant role.** A role nobody may hold is a role somebody will be given.
  Rejected; `V239` deletes it, its assignments and `guest_edit_token_hash` together.
- **Drop the two anonymous reads as well.** Both were put to the owner as questions (D2, D3) and
  both were kept, for the reasons above. Neither publishes anything: three integers and a public
  release URL, and a text already on a public page.

## Consequences

- **The requirement is a list, and something refuses to grow it.** `AnonymousSurfaceSweepTest`
  enumerates every `RequestMappingInfo` the dispatcher knows and issues each one without a token,
  with a `PENDING` bearer, and with a role-less bearer — plus `HEAD` for every `GET`, because a
  `GET`-scoped rule and `String.equals` verb matching are how REQ-SEC-032 leaked once. A path added
  next month is covered on the day it is added. `permitAllIsDeclaredOnlyOnTheThreePublicEndpoints`
  refuses a fourth `permitAll()`; `OpenApiAnonymousOperationsTest` refuses a third `security: []`.
- **The roster sync had to be fixed first.** It now reads the realm's default-role composite, and a
  run in which the realm matches *none* of the app's roles aborts rather than writing a role-strip
  for every account. A single account resolving to no role is still written through — removing
  someone's roles in Keycloak still removes their access.
- **No edge paste is needed.** The vhost allow-list keeps admitting the same paths; what changed is
  that the backend now refuses the callers behind them. The runbook says so explicitly, so nobody
  pastes.
- **Rollback after promotion is a forward fix.** `V239` drops a column and deletes rows, so a
  reverted image validates its schema against a missing column and fails to boot. The migration logs
  the affected user ids at INFO before deleting — identifiers, not identities — so the assignments
  can be restored.
- **Members lose nothing.** The mission list, detail, join, check-in, payout preference, crew board,
  Ablauf, Ziele, Funk and the finance ledger are unchanged; only the caller set shrinks. The
  `isInternal = false` escape keeps cross-Staffel visibility, and its wording changes from
  „öffentlich" to „organisationsweit" because that is what it always meant.
