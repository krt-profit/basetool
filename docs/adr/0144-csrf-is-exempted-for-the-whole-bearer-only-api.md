# ADR-0144 — CSRF is exempted for the whole bearer-only API, not per endpoint

> **Status:** Accepted · **Date:** 2026-08-25 · **Deciders:** @greluc
> **Related:** `REQ-SEC-039`, `REQ-SEC-037`, `REQ-SEC-022`, ADR-0012 (the frontend's CSRF model),
> `krt-profit/basetool-android` (the client this broke)

## Context

The backend's filter chain is `SessionCreationPolicy.STATELESS` and authenticates with exactly one
mechanism: a bearer JWT via `oauth2ResourceServer`. There is no form login, no HTTP basic, no
session cookie, and no other ambient credential.

Cookie-based CSRF was nevertheless enabled on it, with an `ignoringRequestMatchers` list naming five
paths: `/api/v1/missions/**`, `/api/v1/operations/**`, `/api/v1/orders`, `/api/v1/orders/items`,
`/api/v1/finance-entries`, plus `/internal/**`. Its comment described the intent as *"only for the
JWT-bearer-token API endpoints under `/api/v1/**`"* — which is not what the list said.

Every write outside that list therefore answered **403 `MissingCsrfToken`** to any caller without a
CSRF cookie. Every bearer client is such a caller. The web frontend was unaffected because it is a
server-side Thymeleaf app with its own CSRF model (ADR-0012) and never posts to the backend from a
browser; the native app was affected completely. In production this broke:

- `POST /api/v1/inventory/{id}/book-out` — booking stock out of the Lager
- `POST` / `DELETE /api/v1/orders/{id}/assignees/{userId}` — taking an Auftrag
- `PUT /api/v1/orders/{id}/status` — moving it
- `PUT /api/v1/org-units/bank/accounts/{id}/balance-target`

while the two families that happened to be on the list worked, which is what made it look like a
per-feature problem rather than one rule.

Two things hid it for the length of a release:

- **No test can see the production branch.** The `test` profile disables CSRF outright so MockMvc
  can post without first fetching a token, so every `@SpringBootTest` in the repository exercises
  the branch that has no CSRF at all.
- **The one signal that did fire was misread as noise.** `edge-deny-probe` asserts `401` for an
  anonymous write and got `403` on exactly those four paths; the CSRF filter runs ahead of
  authorization and answered first. The run had been red for two days.

## Decision

**Exempt the whole bearer-only surface — `/api/v1/**` and `/internal/**` — and state why on the
constant rather than per entry.**

CSRF defends against a browser attaching a credential by itself. On a stateless chain whose only
credential is a bearer token there is nothing to attach, so the check cannot prevent an attack on
this surface; it can only refuse a legitimate client. Keeping it enabled with a per-endpoint
allow-list bought no protection and cost one broken feature per endpoint nobody remembered to add.

`/internal/**` keeps its entry for the same reason it always had one: machine-to-machine, no cookie,
its own constant-time shared-secret header (REQ-SEC-022).

CSRF stays **enabled** for anything outside those two patterns. Nothing browser-facing lives on this
backend today — it serves no HTML, enforced by ArchUnit — but the exemption is scoped so that adding
something browser-facing later does not arrive pre-exempted.

Rejected:

- **Add the four missing paths.** The shape that produced the defect. The next endpoint outside the
  list breaks the same way, in production, with a 403 that names CSRF and misdirects the reader.
- **`csrf.disable()` on the chain.** Behaviourally near-identical today and honest about the model,
  but it removes the scoping above and trips `java/spring-disabled-csrf-protection` for a reviewer
  who then has to reconstruct this reasoning from a suppression comment.
- **Teach the app to fetch and echo an XSRF cookie.** A cookie jar and a token round trip added to
  every write, to satisfy a control that protects nothing when the credential is a bearer token.

## Consequences

- The native app's writes work against production. That is the fix; there is no client change.
- The security model is now stated in one place with its precondition attached: *stateless,
  bearer-only*. If either ever stops being true — a session cookie, a form login, a browser-facing
  endpoint on this backend — this exemption has to be revisited, and `REQ-SEC-039` says so.
- `SecurityConfigCsrfExemptionTest` pins the exemption list without a Spring context, which is the
  part of the blind spot that can be closed cheaply. The rest of it stands: the `test` profile still
  disables CSRF, so no integration test observes the production branch, and the nightly probe
  remains the only end-to-end check of it.
- `edge-deny-probe` should return to `401` on those four paths on the next run after deploy. It is
  currently red for two further reasons that this ADR does not address (`materials/{id}/terminals`
  and `hangar/import/fleetview`).

