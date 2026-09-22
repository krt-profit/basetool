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
- **Production's oddities travel.** The extractor's unused authorization-code flow (the hardening
  runbook's thirteenth finding), the two ingest scopes on the app and the gateway, and the
  compose-internal `http://frontend:18081` pair are reproduced in every realm it shapes until
  production decides them.
- The mobile provisioner stays, as the focused tool for that one client; a change to it now also
  runs the realm provisioner's tests (`keycloak-provisioner.yml`).

**Rejected:** a full realm import (`--import-realm` or a partial import of a sanitized export — it
overwrites or skips wholesale, cannot take origins as arguments, and the committed reference is
sanitized and not importable by design); converging in both directions everywhere (deletes what a
tester added on purpose, and an empty or partial read would then delete real configuration);
Terraform's Keycloak provider (a second toolchain and state file for one realm, and it deletes on
drift by default); replaying the runbook steps by hand (how the gap arose); and encoding the
*intended* rather than the production state (the two realms would disagree by construction).
