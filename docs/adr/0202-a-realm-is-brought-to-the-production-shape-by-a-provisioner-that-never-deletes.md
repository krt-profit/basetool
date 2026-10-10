# ADR-0202 — A realm is brought to the production shape by a provisioner that never deletes

- **Status:** Accepted
- **Date:** 2026-09-22
- **Deciders:** @greluc (requested 2026-09-22: "the testing host must mirror production")
- **Related:** [ADR-0131](0131-mobile-auth-refresh-only-dpop-binding.md) (the DPoP policy and its
  write order) · [ADR-0129](0129-ingest-gateway-is-a-trusted-subsystem-not-a-token-relay.md) (the
  gateway's own client) ·
  [`docs/specs/deployment-delivery.md`](../specs/deployment-delivery.md) (`REQ-OPS-022`,
  `REQ-OPS-033`) · [`docs/specs/security-and-access.md`](../specs/security-and-access.md)
  (`REQ-SEC-030`, `REQ-SEC-035`) ·
  [`INGEST_KEYCLOAK_SETUP.md`](../INGEST_KEYCLOAK_SETUP.md) (the procedure)

## Context

The production realm `iri` was built by hand over four months: the ingest runbook's steps 1–9, the
mobile provisioner, the WP-K2 hardening. Delivery keeps images, units and the provider JAR in
lock-step across production and testing (`REQ-OPS-022`), but the realm lives in each host's
`db-keycloak` and no artifact carries it. Nothing rebuilt the production shape anywhere else.

A read-only configuration snapshot of both realms on 2026-09-22 showed what that cost. Testing had
no audience mapper at all; no `basetool-sc-extractor`, `basetool-ingest-gateway` or
`basetool-android` client; neither ingest scope; no DPoP policy or profile; none of the frontend's
claim mappers (`discord_user_id`, `rank`, …); and `backend-service`'s service account held none of
its `realm-management` roles. So the backend's audience gate — fail-closed at startup since
APPSEC-08 — could not be enabled there, and "try it on testing first" did not test the shape
production runs.

Only one piece of this was already code: `scripts/provision-keycloak-mobile-client.py`, which
provisions the Android client and its policy.

## Decision

**The Basetool-owned part of a realm is code, `scripts/provision-keycloak-realm.py`, and it
converges a realm to production's shape additively.**

1. **The desired state is production's**, read with `scripts/keycloak-config-snapshot.sql` (committed,
   read-only, secret-free) and written into the script. Something production carries that looks
   unintended is reproduced and marked `PROD-AS-IS`: a provisioner that quietly improved on
   production would make the two realms disagree in exactly the places nobody looks. Changing one is
   a production decision first, then a one-line change in the script.
2. **Environment-specific values are arguments** — `--public-origin`, `--grafana-origin` — so no
   production hostname is written into another realm.
3. **Diff-based and dry-run by default.** The script plans every write by comparing the live realm
   with the shape and prints the plan; `--apply` executes it, re-plans, and fails unless the second
   plan is empty. A realm in shape receives no write at all.
4. **It never deletes what only the target realm has.** An extra client, mapper, redirect URI, web
   origin or scope assignment is reported and left alone. Three deletions remain, each an existing
   decision rather than a new one: the Android client's realm-role scope is converged in both
   directions (`REQ-SEC-035`), `offline_access` is withheld from that client (ADR-0131), and a client
   the same run created gets exactly its production scope lists (Keycloak attaches the realm
   defaults on creation; they are the run's own side effect).
5. **The Android client has one definition.** The realm provisioner imports the mobile
   provisioner's client representation, DPoP profile and policy, kcadm wrapper and by-name merge. It
   keeps ADR-0131's write order: when that client or the profile has to change, the policy is
   detached first and re-attached last, and both realm-global lists are merged by name.
6. **No secret passes through it.** An update omits the `secret` field (Keycloak keeps the stored
   one); output shows diffs, never representations; a confidential client it creates is reported
   with the Admin Console path to its generated secret and the `.env` variables that need it.
7. **Service-account roles need `manage-users`**, which the short-lived provisioning identity
   deliberately lacks (Keycloak also refuses to let an identity grant an admin role it does not hold,
   `RolePermissions.checkAdminRoles`). Without it, the script applies everything else, prints the
   roles to assign in the Admin Console, and exits `3`.
8. **Out of scope:** Keycloak's built-in clients and scopes (their differences between realms are
   version artefacts), the realm-wide hardening (Require SSL, events, OTP, default roles), the
   Discord identity provider, and the realm's default client scopes — an ingest scope that is a
   realm default is reported, not removed.

## Consequences

- Testing can mirror production with one reviewed dry run and one apply, and the snapshot SQL tells
  whether it still does.
- **Two realms agree only while someone runs it.** Nothing runs it on a timer and nothing compares
  realms automatically; a hand edit drifts until the next snapshot diff (arc42 §11).
- **Production's oddities travel** until production decides them. Three were decided the same day
  — see *Amendment 1*; what remains marked `PROD-AS-IS` (inert SAML attributes on the frontend,
  both ingest scopes on the gateway) is reproduced as it is.
- The mobile provisioner stays, as the focused tool for that one client; a change to it now also
  runs the realm provisioner's tests (`keycloak-provisioner.yml`).

**Rejected:** a full realm import (`--import-realm` or a partial import of a sanitized export — it
overwrites or skips wholesale, cannot take origins as arguments, and the committed reference is
sanitized and not importable by design); converging in both directions everywhere (deletes what a
tester added on purpose, and an empty or partial read would then delete real configuration);
Terraform's Keycloak provider (a second toolchain and state file for one realm, and it deletes on
drift by default); replaying the runbook steps by hand (how the gap arose); and encoding the
*intended* rather than the production state (the two realms would disagree by construction).

## Amendment 1 — 2026-09-22: three production entries retired, and they converge both ways

- **Deciders:** @greluc (owner decision on the three `PROD-AS-IS` items this ADR reported)

The first version reproduced three entries production carried and nobody had chosen. The owner
decided all three on the day it merged:

| Client | Retired | Why it is safe |
| --- | --- | --- |
| `basetool-sc-extractor` | the authorization-code flow (`standardFlowEnabled: false`) and its redirect URIs `http://127.0.0.1/*`, `http://localhost/*` — the hardening runbook's thirteenth finding | the extractor authenticates with the device grant only: `DeviceGrantClient` sends `device_code` and `refresh_token` grants and has no authorization-code client (read on `basetool-sc-extractor` `main`, `c6de57ff4`) |
| `basetool-android` | the default scopes `extractor-ingest` and `extractor-ingest-only` | the app requests only `openid profile email roles` (`AuthorizationRequest.DEFAULT_SCOPES`, its one call site in `LoginViewModel`), sends no `scope` on refresh, and never calls ingest (read on `basetool-android` `main`, `7bb7cbe1a`). Its `aud=basetool-backend` comes from its own `backend-audience` mapper. The two scopes only made an app token pass the gateway's audience and capability checks, leaving the `azp` allowlist as the one gate in the way |
| `basetool-frontend` | redirect URI `http://frontend:18081/*`, web origin `http://frontend:18081` | the frontend listens HTTPS-only on 18081 (the blackbox probe targets `https://frontend:18081` and never logs in), and its `redirect_uri` is `{baseUrl}/…` built from the forwarded public origin — no real login can present an `http://frontend:18081` URI. The e2e realm is a separate file and keeps its own entries |

**These entries converge in both directions**, like REQ-SEC-035's Android role scope: the
provisioner removes each of them wherever it finds one — production included, on its next apply —
and never reports them as "only on this realm". The mechanism is per-spec and named
(`withheld_scopes`, `withheld_redirect_uris`, `withheld_web_origins`, each with its reason), so a
withheld entry is always an owner decision recorded next to it, never a general permission to delete.
Decision 4 above holds for everything else. Requirements: `REQ-OPS-033`, `REQ-INGEST-002`,
`REQ-INGEST-011`.

Removing the Android client's scopes is a sub-resource write on the client the DPoP policy freezes,
so the provisioner detaches the policy before it and re-attaches it afterwards (decision 5); the
self-test pins that order and that the plan against the old production shape removes exactly these
entries.

## Amendment 2 — 2026-09-23: the frontend's client type is an explicit choice

ADR-0001 makes `basetool-frontend` confidential through an owner rollout. Encoding either type as
the target shape would have let an ordinary run flip production — to confidential before the
frontend holds a secret, or back to public after the rollout. So the type is converged only when
`--frontend-client public|confidential` says which; without it `publicClient` and
`clientAuthenticatorType` of an existing client are left as they are (a new client is created
public). `confidential` sets the secret from `$KEYCLOAK_FRONTEND_CLIENT_SECRET` in the same update
that flips the client — over kcadm's stdin, never printed, refused when the variable is unset — the
one secret this script ever sends, and only on the switch; a later run never rewrites it.
`public` is the rollback and sends none. Pinned by cases 9–12 of
`scripts/provision-keycloak-realm.test.sh`.

## Amendment 3 — 2026-09-25: the frontend gets a `baseUrl`

- **Deciders:** proposed by Claude with the investigation of a production `cookie_not_found` event;
  takes effect as the owner's decision when @greluc merges it and applies the provisioner

Production's `basetool-frontend` has an empty `baseUrl` (and `rootUrl`). Keycloak's `error.ftl`
renders its *"« Zurück zur Applikation"* link only when `client.baseUrl` has content, so every
Keycloak error page for this client ended without a way back — above all `cookie_not_found`, whose
own text (*'Klicken Sie auf "Zurück zur Anwendung" um einen neuen Anmeldevorgang zu starten.'*,
quoted verbatim) tells the member to click exactly that link. Verified against the Keycloak 26.7.4 sources
(`SessionCodeChecks.initialVerifyAuthSession`, `theme/base/login/error.ftl`) on 2026-09-25.

The provisioner now converges `baseUrl` to `<--public-origin>/`. It is an **added** field, not a
retirement, and it is environment-specific like the redirect URIs, so no production hostname is
written into another realm. `baseUrl` affects only where Keycloak *links* to the client (error and
info pages, the account console's application list) — it is not a redirect-URI allowance and widens
nothing. Production gets it on the owner's next `--apply`. Requirement: `REQ-SEC-071`; pinned by
cases 2 and 5 of `scripts/provision-keycloak-realm.test.sh`.

## Amendment 4 — 2026-09-28: the exchange clients carry no mapper but their own

- **Deciders:** @greluc (owner decision in chat, 2026-09-28, on finding G5-L4 of the final security
  review of #2092)

Decision 4 left a client-level protocol mapper that only the target realm has in place and reported
it under *only on this realm*. For `basetool-sc-extractor` and the third-party clients of
`scripts/keycloak/external-clients.json` that is the one piece of their shape the provisioner could
not enforce: a hand-added `oidc-audience-mapper` stamping `basetool-backend` would survive an apply,
and since reports are not planned changes the verify pass still ended *"Applied. A second run
reports no changes."* A phished device code would then yield a token the backend API accepts —
finding H1 of review 1 again. Their documented shape is "no protocol mapper"
(`REQ-XCH-005`).

**For these clients the mapper list converges in both directions.** The spec flag `exact_mappers`
makes every live client-level mapper that is not in the client's own `mappers` list — for these
clients that is every one — a planned `- <client>: mapper '<name>' (<type> <target>) removed`
change: the dry run lists it and exits `2`, the apply deletes it, and the verify pass fails with
`STILL PLANNED` if one is left. Mappers of client scopes are unaffected; the withheld scopes already
keep every identity- or audience-carrying scope off these clients. Decision 4 holds for every other
client, whose extra mappers are still reported and left alone. Requirements: `REQ-OPS-033`,
`REQ-XCH-005`; pinned by case 17 of `scripts/provision-keycloak-realm.test.sh`.

## Amendment 5 — 2026-09-28: every member may hold an offline session

- **Deciders:** @greluc (owner decision in chat, 2026-09-28, on the production go-live defect of
  SC Extractor 2.10.0)

SC Extractor 2.10.0 could not sign in on production: its device-token poll was answered `400
not_allowed` — „Offline tokens not allowed for the user or client". Keycloak issues an offline token
only when the member holds the `offline_access` realm role within the client's scope.
`basetool-sc-extractor` has `fullScopeAllowed` off and requests `offline_access` (#2179,
`REQ-XCH-005`), and production's `default-roles-iri` held `KRT Member`, `uma_authorization` and the
`account` roles but not `offline_access`: hardening step 10 (`KEYCLOAK_HARDENING_RUNBOOK.md`, done by
2026-09-09) had removed it so that no account could mint an offline token. The `offline_access`
client scope already mapped the role; the sanitized reference did not show it because the
sanitizer dropped `scopeMappings` (it keeps them since the same day). The sandbox and E2E realms carried both, so no test saw the gap, and
the go-live's provisioner apply (S15) left it, because decision 8 put the default roles out of scope.

**Decision: grant the role on the server, as the sandbox does, and let the provisioner converge
it.** The owner added the composite on production by hand on 2026-09-28. The provisioner now plans
two additive writes in a section of its own after the application realm roles: `offline_access` as a
composite of the realm's default role, and the `offline_access` realm role mapped on the
`offline_access` client scope. It never removes either, resolves the role by name when the write
runs, and treats a realm without the role, the default role or the scope as a problem rather than a
write. This narrows decision 8 by exactly these two built-in objects and reverses hardening step 10.

**What it opens.** Every member can be issued an offline token again, by any client that offers the
`offline_access` scope and requests it:

- `basetool-sc-extractor` and every approved third-party client request it, and their offline
  sessions are pinned at 30 days idle and 90 days in total (ADR-0217 amendments);
- `basetool-frontend` offers it but requests `openid, profile, email, roles`; `grafana` offers it but
  requests `openid email profile`; `backend-service` and `basetool-ingest-gateway` authenticate as
  service accounts and request it nowhere — all four have it withheld since the narrowing below;
- `basetool-android` is not offered it (decision 4, ADR-0131) and omits it from its request.

Tokens of a client with full scope now also list `offline_access` in `realm_access.roles`, beside
`uma_authorization` and `default-roles-iri`. The frontend maps it to an authority at login and its
sync with the backend drops it again, as it drops every realm role without a catalog entry
(`REQ-SEC-013`); nothing is gated on it.

**Considered and not taken** (the owner chose the sandbox's shape): a role or group of its own that only the extractor's members hold (every member uses
the extractor, so it would be the default role under another name); `offline_access` as a composite
of `KRT Member` (the same reach, hidden in an application role the roster sync mirrors); giving up
`offline_access` for the exchange clients (reverses the owner decision of 2026-09-26 that a web
logout must not disconnect them). Requirements: `REQ-OPS-033`, `REQ-XCH-005`; pinned by case 18 of
`scripts/provision-keycloak-realm.test.sh`.

**Narrowed the same day (owner decision, 2026-09-28): only the exchange clients are offered the
scope.** Offering alone is not issuing, but a client that is not offered `offline_access` cannot be
issued an offline token whatever it requests, and that no longer rests on what each client happens
to ask for. `basetool-frontend`, `backend-service`, `basetool-ingest-gateway` and `grafana` (when
`--grafana-origin` manages it) therefore have `offline_access` withheld: it is gone from their
optional scopes and converges away wherever found, like the Android client's (decision 4). The
dry-run line is `- optional scope 'offline_access' withheld (offline sessions are for the exchange
clients only, ADR-0202 amendment 5)`. The extractor and the third-party template keep it.
**Nor is it a realm default client scope** (owner decision, the same day): a client created by hand
would otherwise inherit it. The provisioner removes it from the realm's default and optional client
scopes wherever it is listed — `- realm optional client scope 'offline_access' removed (a client
created by hand no longer inherits it; the exchange clients name it themselves)`, or `default` —
in the `offline_access` section; the exchange clients' specs name it, so they keep it. This is a
second built-in object decision 8 no longer leaves alone. Pinned by cases 19 and 20; production gets it with a separate
owner-approved dry run and apply (`INGEST_KEYCLOAK_SETUP.md` → *Withholding `offline_access` from
the first-party clients*).


## Amendment 6 — 2026-10-10: the realm-wide hardening steps 2, 11 and 12 are a script, and the session windows are one file

Owner decision D-26 (2026-10-04) put hardening steps 2, 11 and 12 in scope. They are realm-wide, so
the provisioner (decision 1: the Basetool's own objects) still does not carry them, but a sibling
script does: `scripts/harden-keycloak-realm.py`, with the same conventions (dry run by default, exit
`2` when something is to do, `--apply` re-reads and verifies, nothing deleted). Its additions to the
realm are the browser-flow copy `browser-admin-otp`, the top-level flow `post-broker-admin-otp`, the
required action Configure OTP, and the bindings; REQ-SEC-082 states the shape.

- **One file for the two session windows** both scripts touch: `scripts/keycloak/session-windows.json`.
  The provisioner reads its `active` profile for `ssoSessionIdleTimeout` and `ssoSessionMaxLifespan`
  (it used to hold the literals), so adopting new numbers is one reviewed change and the provisioner
  can never put the old ones back. The file travels with the provisioner to the host, next to
  `keycloak/external-clients.json`.
- **A found trap, not a decision:** the runbook told the owner to bind the browser-flow copy as the
  Discord provider's post login flow. A post login flow runs after the provider; the browser copy
  would ask a brokered user for a username and password. The script builds a small flow for it.
