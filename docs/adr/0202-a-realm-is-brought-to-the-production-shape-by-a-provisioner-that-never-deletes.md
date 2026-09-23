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
