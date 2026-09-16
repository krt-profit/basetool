# ADR-0051 — Deny a colliding Discord first-login via a fail-open backend account-existence precheck

- **Status:** Accepted
- **Date:** 2026-06-29
- **Deciders:** @greluc
- **Related:** spec REQ-SEC-022 · REQ-DATA-006 · REQ-SEC-016/017 · ADR-0030 · ADR-0036 · runbook `docs/keycloak/DISCORD_KEYCLOAK_SETUP.md`

## Context

A member who already has a Basetool account (a Keycloak credential account) and signs in with
Discord for the first time silently creates a **second**, brand-new `PENDING` registration — a
duplicate account. We want the first-login to instead be **rejected** with a hint to **link** Discord
to the existing account (Account Console → Linked accounts → Discord, ADR-0036).

Several forces constrain the design:

- **"Reject the login"** is the explicit requirement: no Keycloak session, a clear localized error
  page. The existing first-broker-login membership gate (REQ-SEC-016, `DiscordGuildRoleGateAuthenticator`)
  already does exactly this for non-members, before any user/`app_user` row is created.
- **The match must cover the in-app display name**, not just the Keycloak username/e-mail. The display
  name lives **only in the backend database** — Keycloak does not have it. So the decision cannot be
  made inside Keycloak alone.
- **REQ-DATA-006** forbids matching a Discord login onto a pre-existing row to *link or inherit*. A
  match here must therefore only ever **reject**, never link.
- **Availability:** the collision check is a UX / data-integrity guard, not a security boundary. It
  must not lock out a legitimate new member when the backend or Discord is briefly unavailable.
- **Transport:** all service-to-service traffic is **HTTPS only**; the backend serves a self-signed
  certificate, so any caller must trust it explicitly (certificate validation is never disabled).

## Decision

We will run the collision check as a **second stage inside the existing first-broker-login gate**
(`DiscordGuildRoleGateAuthenticator`), after the fail-closed membership gate admits the user and
before the Keycloak user is created. The gate asks the backend over a new **internal HTTPS endpoint**
`POST /internal/discord/account-existence` — outside `/api/**`, guarded by a constant-time
**shared-secret** header (`X-KRT-SPI-Secret`), returning only `{ "exists": <bool> }` — whether the
incoming Discord username / server nickname matches an existing account's username or display name,
or the Discord e-mail matches an existing e-mail. On a confident match the gate denies the flow with
the localized `discordAccountAlreadyExists` page.

The check is **fail-open**: it is skipped when unconfigured, when the configured URL is not `https://`,
or during an account-linking flow (an already-authenticated session, ADR-0036); and any backend
error / timeout / TLS failure / unparseable answer is treated as "unknown" → allow. The SPI trusts
the backend's self-signed certificate via a configured PKCS#12 truststore; it never disables
certificate validation, so a trust failure simply fails open rather than connecting insecurely.

## Consequences

- **Easier:** a true login denial with a localized "link instead" page; no duplicate `PENDING`
  account and no junk Keycloak user; the matching covers username + e-mail + in-app display name in
  one backend call; REQ-DATA-006's no-inheritance guarantee is preserved and strengthened (a colliding
  Discord identity is blocked outright instead of becoming a parallel account).
- **Harder / costs accepted:** a **new SPI→backend trust channel** (shared secret + truststore + two
  env vars on Keycloak, one optional property on the backend) — the largest new surface. It is
  mitigated by being entirely fail-open: a deployment that does not configure it behaves exactly as
  before. The check runs only at first-broker-login (REQ-SEC-016's scope), so it adds no per-request
  cost and no continuous enforcement.
- **Follow-up:** operator wiring (truststore generation, env vars, docker-compose) is documented in
  the runbook; the German `basetool.wiki` Discord/registration page gains a sentence. No new
  `AuditEventType`: the denial is a Keycloak-level event with no backend state mutation, and Discord
  registration is not an audited area — consistent with the membership gate, which is likewise not
  Basetool-audited.

## Alternatives considered

- **Backend-side check in `UserService.syncUser`** — rejected. By the time `syncUser` runs the
  Keycloak session is already issued; throwing propagates through `CustomJwtGrantedAuthoritiesConverter`
  on *every* request (no clean page), and it leaves a junk Keycloak account. It cannot deliver a true
  "login rejected" experience, and surfacing a new "must link" limbo state would have to be threaded
  through the converter, the registration-status endpoint, the frontend filter and a new page.
- **A separate, dedicated authenticator** for the collision check (sibling to the membership gate) —
  rejected for now. It is the same "should this Discord first-login be admitted?" decision at the same
  flow position, so folding it into the existing gate avoids a second factory, a second
  `META-INF/services` registration, and — most importantly — a second REQUIRED flow step the operator
  must add and could misconfigure. The two stages keep distinct failure postures (membership =
  fail-closed; existence = fail-open), documented in the gate.
- **Matching only against the Keycloak user store from inside the SPI** (username + e-mail, no backend
  call) — rejected because it cannot see the backend-only in-app display name the owner required.
- **Keycloak's built-in "Detect Existing Broker User" / auto-link** — rejected: it links automatically
  (which REQ-DATA-006 forbids) and knows nothing about the Discord per-guild server nickname.
- **Plain HTTP or a trust-all `SSLContext`** for the backend call — rejected outright: HTTPS only, and
  certificate validation is never disabled; an untrusted cert fails open instead.
