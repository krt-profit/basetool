# ADR-0051 — Deny a colliding Discord first-login via a fail-open backend account-existence precheck

- **Status:** Accepted — amended 2026-11-13 (in production the SPI trusts the CA-only internal
  truststore that the generated keycloak unit mounts, replacing the hand-built store and its
  drop-in) — see the amendment below
- **Date:** 2026-06-29
- **Deciders:** @greluc
- **Related:** spec REQ-SEC-022 · REQ-DATA-006 · REQ-SEC-016/017 · REQ-SEC-070 · REQ-OBS-014 · ADR-0030 · ADR-0036 · ADR-0211 · runbook `docs/keycloak/DISCORD_KEYCLOAK_SETUP.md`

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

## Amendment — 2026-11-13: the SPI trusts the internal truststore the keycloak unit mounts

**Context.** The decision left the truststore to the operator: a dedicated PKCS#12 store, named by
`KRT_BACKEND_TRUSTSTORE_PATH` / `KRT_BACKEND_TRUSTSTORE_PASSWORD` in the host `basetool.env` and
mounted by hand. On production that was a hand-built `/etc/basetool/secrets/backend-truststore.p12`,
mounted by a hand-installed Quadlet drop-in `keycloak.container.d/50-backend-truststore.conf`. The
drop-in disappeared — its whole directory with it — and nothing noticed: Keycloak logged `Failed to
load the backend truststore` (`NoSuchFileException: /run/secrets/backend-truststore.p12`) at every
start and the precheck failed open, the state the internal-TLS rollout had already found and fixed
once on 2026-09-25. A file outside the release and outside the backup is not guaranteed to stay, and
a fail-open check causes no outage by which its loss would be noticed. Since ADR-0211 the backend
serves a leaf of the internal CA, and backend, frontend and ingest already pin a CA-only truststore
for exactly that leaf.

**Decision** (@greluc, 2026-11-13):

1. In production the SPI trusts the backend through the **CA-only internal truststore**
   (`scripts/mint-internal-tls.sh`, alias `ca`; REQ-SEC-070), not through a store of its own.
2. The prod `keycloak` service in `docker-compose.yml` mounts it read-only at
   `/run/secrets/internal-truststore.p12` from `BASETOOL_INTERNAL_TRUSTSTORE_HOST_PATH`, which the
   generated Quadlet unit bakes to `/etc/basetool/secrets/tls/truststore.p12`. The same service
   fixes `KRT_BACKEND_TRUSTSTORE_PATH` to that path and `KRT_BACKEND_TRUSTSTORE_PASSWORD` to the
   expression the three apps use for the store (`INTERNAL_TLS_TRUSTSTORE_PASSWORD`, else
   `SERVER_SSL_KEY_STORE_PASSWORD`). The host `basetool.env` no longer chooses either, and the
   variable and the mount target live in one file. `scripts/generate-quadlet.test.sh` asserts the
   generated mount and both values. `keycloak-dev` keeps the precheck unconfigured.
3. The hand-built store, its drop-in and the two `basetool.env` lines are obsolete; removing them is
   a one-time, owner-approved host cleanup (`docs/deployment.md` → *One-time cleanup*).
4. The Loki alert **`KeycloakBackendTruststoreUnreadable`** (warning) fires on the SPI's `Failed to
   load the backend truststore` line for 30 minutes after a Keycloak start that could not read the
   store, and **`KeycloakAccountExistenceProbeFailing`** (warning) fires for an hour on the
   per-login `Account-existence probe could not reach the backend` line, which a store that loads
   but does not trust the backend writes (production logged it with `PKIX path building failed` on
   2026-11-12). `scripts/check-loki-rule-signatures.py` holds both to verbatim production lines
   (REQ-OBS-014).

**Consequences.**

- The precheck's trust follows every internal-TLS re-mint with no step of its own. The deploy's
  mount pre-flight already requires the file, because the apps mount it, so no new host file becomes
  mandatory; the file is in the backup with `/etc/basetool/secrets/tls`.
- The SPI trusts any leaf of the internal CA rather than one pinned certificate. The JDK HTTP
  client checks the name it dials (`backend`), which only the backend's leaf carries (ADR-0211).
- Deploying the change restarts keycloak, and with it the application (`Requires=`). A rollback to a
  release from before it runs the precheck without a truststore once the cleanup has run: it fails
  open until the release is replaced.
- A store that loads but does not trust the backend still shows only as the per-login
  `Account-existence probe could not reach the backend` `WARN`; the alert covers the unreadable
  store, the failure that happened twice.

**Considered and not taken.**

- **Keep a separate store, with its drop-in installed by the Ansible role.** It would survive a
  host rebuild, but it is still a second store holding the same CA, with a password of its own and
  a re-mint step of its own.
- **Pin the backend's leaf instead of the CA.** Every re-mint replaces the leaf, and no other
  internal client pins a leaf; the CA is the anchor ADR-0211 decided on.
