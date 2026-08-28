# ADR-0149 — The job-order request form requires a login

> **Status:** Accepted · **Date:** 2026-08-28 · **Deciders:** @greluc
> **Related:** `REQ-SEC-037` (the API vhost's anonymous surface), `REQ-ORG-014` (the intake
> Spezialkommando), `REQ-ORDERS-*`, `ROLES_AND_PERMISSIONS.md` §1.1, ADR-0144 (CSRF on the
> bearer-only API), `krt-profit/basetool-android` `REQ-APP-ORDERS-013` (the app form this unblocks)

## Context

`POST /api/v1/orders` and `POST /api/v1/orders/items` were `permitAll`. That was deliberate: the web
app's `/orders/create` is reachable without a login, so an outsider can ask the organisation for
material without an account. An order raised that way carries no `created_by` and is stamped onto
the configured intake Spezialkommando (`job_order.intake_special_command_id`, V128) so it lands in a
defined queue rather than nowhere.

The Android app needs the same create. It reaches the backend through the public **API vhost**,
whose allow-list is default-deny and whose `$krt_readonly_family` guard refuses every non-GET verb
under `/api/v1/orders` — precisely so the anonymous create does not become reachable there. A CI
probe asserts it (`edge-deny-probe.yml`: `probe POST /api/v1/orders 405`), and the runbook says why
in as many words: *"the one where only the VERB separates two surfaces"*.

So the app's form could not ship without one of three things happening:

1. **Admit the verb on the vhost.** The endpoint is already public at the web origin, so this adds
   no authentication hole — but it does add a second public hostname that accepts anonymous writes,
   and it deletes the property the probe exists to defend.
2. **Guard the verb at the edge**, letting it through only with an `Authorization` header. Keeps
   the web form public and the vhost non-anonymous, at the cost of an auth rule living in nginx
   rather than in the application.
3. **Require a login for the create.** The endpoint stops being anonymous everywhere, the vhost
   question becomes ordinary, and the public request form goes away.

A fourth data point settles the direction rather than merely permitting it. The app's own plan
already dropped guest mode (`krt-profit/basetool-android` `ANDROID_APP_PLAN.md`, decision Q8, owner,
2026-08-18): *"Every user signs in; the app has no anonymous surface"*, with the consequence spelled
out as *"no guest signup or anonymous order create"*. The client that motivated this change was
never going to carry the anonymous create; what was left was a backend that still offered it.

## Decision

**Option 3.** `POST /api/v1/orders` and `POST /api/v1/orders/items` require an authenticated caller.
The anonymous job-order request form is removed from the product.

The deciding argument is that the property being protected is worth more than the feature being
protected. Every other write in this system is attributable to a person; the order create was the
one that was not, and the intake-SK stamping existed to give those unattributable rows somewhere to
land. Options 1 and 2 both keep an anonymous write alive and spend design budget on containing it —
one by widening its reach, the other by moving an authorisation decision out of the application and
into an nginx `if`. Neither makes the anonymous write better; they make it further away.

The cost is real and is not being minimised: an outsider who wants material from the organisation
now needs an account first. That is a product regression for a surface that, by construction, had no
way of telling who was on the other end.

## Consequences

- **The public request form is gone.** `/orders/create` on the web requires a login, as does the
  whole `/orders` surface it sits in.
- **The intake Spezialkommando setting loses its only consumer.** `job_order.intake_special_command_id`
  was read exactly once — the guest fallback in `JobOrderOrgUnitResolver`. The setting, its admin
  control and its seeded row go with the feature.
- **`JobOrderOrgUnitResolver` loses its guest branch.** `resolveResponsibleOrgUnit` no longer has an
  unauthenticated path, so the profit-eligibility check is the only rule left and applies to
  everyone.
- **The per-IP `order-create` rate-limit budget stops being the interesting one.** It stays — an
  authenticated caller still arrives from an IP — but the subject bucket is now what bounds abuse.
- **The API vhost admits the verb ordinarily.** With the endpoint authenticated, `POST
  /api/v1/orders` is the same kind of thing as `PUT /orders/{id}/status`, and the deny probe changes
  from asserting `405` to asserting `401`, which is the stronger statement.
- **A job order still has no `created_by`.** Requiring a login does not add one; requester access
  (`REQ-ORDERS-023`, ADR-0091) continues to work off the requesting org unit, not off an author.
  Adding authorship is a separate change and is not implied by this one.

## Alternatives considered

**Keep the form public and admit the verb (option 1).** Rejected: it trades a defended property for
convenience, and the probe would have to be weakened to say `400` where it says `405` — a CI
assertion that no longer asserts anything about anonymity.

**Edge auth on the vhost (option 2).** Rejected on the same ground as the general rule that
authorisation belongs in the application: an nginx `if ($http_authorization = "")` is invisible to
every test the backend runs, and a future vhost edit could drop it without a single Java test going
red.

**A service credential for the frontend's anonymous relay.** Rejected: it would make guest orders
attributable to the frontend rather than to a person, which is attribution in name only, and it
gives the frontend a machine identity it does not otherwise need.
