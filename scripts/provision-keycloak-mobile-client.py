#!/usr/bin/env python3
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
#
# Provisions the Keycloak client `basetool-android` and the DPoP client policy that gives it
# refresh-token-only sender-constraining. Idempotent: re-running converges on the same state.
#
# WHY THIS IS A SCRIPT AND NOT A RUNBOOK
# --------------------------------------
# The Admin Console clickpath for client policies is eleven steps deep and the two endpoints it
# writes are **realm-global lists that are replaced wholesale**. A hand-edit that forgets to carry
# an existing profile forward deletes it silently, and nothing in the console warns. The realm has
# no client policies today, so today that risk is zero and tomorrow it is not.
#
# WHAT THE ORDER OF OPERATIONS IS FOR
# -----------------------------------
# Experiment E1 (2026-08-17, docs/ANDROID_API_EXPOSURE_PLAN.md section 7) established that while
# the DPoP policy is attached to a client, Keycloak refuses **every** admin update to that client
# with `invalid_client_metadata: DPoP token is disabled` — down to a description change. So the
# order below is load-bearing, not stylistic:
#
#   1. detach our policy (if attached)   <- makes the client editable at all
#   2. write the client
#   3. write the marker client role      <- the policy's scoping handle
#   4. write the client profile
#   5. attach the policy
#   6. verify
#
# A failure between 1 and 5 leaves the client unbound rather than half-bound; the script says so
# and re-running finishes the job.
#
# WHAT THIS SCRIPT DELIBERATELY DOES NOT DO
# -----------------------------------------
# It does not install the realm-wide "S256 for public clients" policy of Phase 0's D1. That policy
# has realm-wide blast radius (every public client, including the desktop extractor) whereas
# everything here is scoped to one client by a marker role. Mixing them into one script would mean
# one mistake takes out unrelated logins. It also never touches production directly: it runs where
# the operator runs it, against the container the operator names.
#
# USAGE
# -----
#   # authenticate kcadm first, inside the container, so no password reaches this process:
#   docker exec -it keycloak /opt/keycloak/bin/kcadm.sh config credentials \
#       --server http://localhost:8080 --realm master --user <admin>
#
#   scripts/provision-keycloak-mobile-client.py --dry-run     # print every payload, write nothing
#   scripts/provision-keycloak-mobile-client.py               # apply
#   scripts/provision-keycloak-mobile-client.py --verify-only # assert the live state, change nothing

from __future__ import annotations

import argparse
import json
import shlex
import subprocess
import sys

CLIENT_ID = "basetool-android"
MARKER_ROLE = "dpop-refresh-only"
PROFILE_NAME = "krt-mobile-dpop"
POLICY_NAME = "krt-mobile-dpop-policy"
AUDIENCE_MAPPER = "backend-audience"
BACKEND_AUDIENCE = "basetool-backend"

# Verified App Link only in production — a custom scheme is claimable by any installed app.
# The dev/test realm additionally registers the loopback + custom-scheme fallbacks so the app can
# be run against a local stack without an assetlinks.json.
REDIRECT_URIS = {
    "prod": ["https://profit-base.online/app/callback"],
    "test": [
        "https://profit-base.online/app/callback",
        "de.kartell.basetool:/oauth2redirect",
        "http://127.0.0.1/*",
    ],
}

# Per-client session bounds, in seconds: idle 30 d, max 180 d — a phone that is used monthly must
# not be logged out, and the refresh token is sender-constrained, so a long window is affordable.
SESSION_IDLE_SECONDS = 30 * 24 * 3600
SESSION_MAX_SECONDS = 180 * 24 * 3600
ACCESS_TOKEN_LIFESPAN_SECONDS = 300

# The realm roles the app's token may carry.
#
# `fullScopeAllowed` below is false, so this list is the whole of it: a realm role that is not
# named here never reaches the app. That matters more than it looks, because the backend does not
# read the token's roles directly — `UserReconciliationService` REPLACES the local role set from
# `realm_access.roles` on every login, and falls back to `Guest` when the claim carries none. A
# client with `fullScopeAllowed: false` and no scope mappings therefore does not merely narrow the
# app's rights; it demotes every member who logs in through it to Guest, in the database, for the
# web app too. Measured on the test stack before this list existed: a fresh app login as an account
# holding Admin + Officer + KRT Member left it holding `Guest` alone.
#
# `Guest` itself is deliberately absent: it is what the backend assigns when the claim is empty, so
# listing it would buy nothing. `Admin` is absent for a stronger reason — see below.
MEMBER_REALM_ROLES = ["KRT Member", "Officer", "Bank Employee", "Bank Management"]

# The role the app must NOT carry, asserted rather than merely omitted.
#
# The admin area is web-only permanently (app decision, 2026-08-17), but ADMIN is not just a menu:
# `RequestScopeResolver.currentScopePredicate()` gives an admin WITHOUT an active-org-unit header
# `adminAllScope = true` — every org unit at once — and honours an admin's pin to any unit, not only
# to one they belong to. The app has no screen built for either. Carrying ADMIN into it would hand
# the app a scope rule nothing there is designed around, silently, the first time an administrator
# installs it.
FORBIDDEN_REALM_ROLE = "Admin"


class KcadmError(RuntimeError):
    """A kcadm invocation failed; carries the command and Keycloak's own message."""


class Kcadm:
    """Thin wrapper around `kcadm.sh`, invoked through whatever command prefix is configured.

    The default prefix runs the CLI *inside* the Keycloak container, which keeps the Admin API off
    the host network entirely and reuses the credentials the operator established with
    `kcadm.sh config credentials`. Tests substitute a stub prefix.
    """

    def __init__(self, prefix: list[str], realm: str, dry_run: bool):
        self.prefix = prefix
        self.realm = realm
        self.dry_run = dry_run

    def _run(self, args: list[str], stdin: str | None = None) -> str:
        command = self.prefix + args
        completed = subprocess.run(
            command, input=stdin, capture_output=True, text=True, check=False
        )
        if completed.returncode != 0:
            raise KcadmError(
                f"{shlex.join(command)}\n"
                f"  exit {completed.returncode}: {(completed.stderr or completed.stdout).strip()}"
            )
        return completed.stdout

    def get(self, path: str, query: dict[str, str] | None = None):
        """Read `path` under the realm and parse the JSON body; reads run even under --dry-run."""
        args = ["get", path, "-r", self.realm]
        for key, value in (query or {}).items():
            args += ["-q", f"{key}={value}"]
        raw = self._run(args).strip()
        return json.loads(raw) if raw else None

    def get_realm(self) -> dict:
        """Read the realm representation itself, which lives above the realm-scoped paths."""
        raw = self._run(["get", f"realms/{self.realm}"]).strip()
        return json.loads(raw) if raw else {}

    def write(self, verb: str, path: str, payload: dict | list, what: str) -> None:
        """Create or update `path` with `payload`; under --dry-run only prints what it would send."""
        body = json.dumps(payload, indent=2, sort_keys=True)
        if self.dry_run:
            print(f"  [dry-run] {verb} {path} — {what}")
            print("".join(f"      {line}\n" for line in body.splitlines()), end="")
            return
        self._run([verb, path, "-r", self.realm, "-f", "-"], stdin=body)
        print(f"  {verb} {path} — {what}")

    def delete(self, path: str, what: str, payload: dict | list | None = None) -> None:
        """Delete `path`, optionally with a body — scope mappings are removed by naming them."""
        if self.dry_run:
            print(f"  [dry-run] delete {path} — {what}")
            return
        if payload is None:
            self._run(["delete", path, "-r", self.realm])
        else:
            self._run(["delete", path, "-r", self.realm, "-f", "-"],
                      stdin=json.dumps(payload, indent=2, sort_keys=True))
        print(f"  delete {path} — {what}")


def session_bounds(kc: Kcadm) -> tuple[int, int]:
    """The per-client session bounds to write, clamped to what the realm permits.

    Keycloak refuses a client whose session idle/max **exceeds** the realm's SSO values
    ("Client session idle timeout cannot exceed realm SSO session idle timeout"), so the intended
    30 d / 180 d only writes cleanly against a realm configured that way — which production is.
    Rather than fail on a realm that is set tighter, clamp and say so out loud: a silently
    shortened session would surface much later as unexplained logouts.
    """
    realm = kc.get_realm()
    realm_idle = int(realm.get("ssoSessionIdleTimeout") or 0)
    realm_max = int(realm.get("ssoSessionMaxLifespan") or 0)

    idle = min(SESSION_IDLE_SECONDS, realm_idle) if realm_idle else SESSION_IDLE_SECONDS
    maximum = min(SESSION_MAX_SECONDS, realm_max) if realm_max else SESSION_MAX_SECONDS
    if idle != SESSION_IDLE_SECONDS or maximum != SESSION_MAX_SECONDS:
        print(f"  NOTE: realm SSO bounds are idle {realm_idle}s / max {realm_max}s, below the "
              f"intended {SESSION_IDLE_SECONDS}s / {SESSION_MAX_SECONDS}s — writing the realm's "
              f"values ({idle}s / {maximum}s) because Keycloak rejects anything larger.")
    return idle, maximum


def client_representation(redirect_uris: list[str], idle: int, maximum: int) -> dict:
    """The full desired state of the client, per the app repo's security concept section 3.

    `dpop.bound.access.tokens` is pinned to "false" on purpose and is the single most important
    value here: setting it true overrides the refresh-only profile and re-binds the access token,
    which Spring Security's bearer filter then rejects outright.
    """
    return {
        "clientId": CLIENT_ID,
        "name": "Basetool Android",
        "description": "Native Android companion app — public client per RFC 8252.",
        "protocol": "openid-connect",
        "enabled": True,
        "publicClient": True,
        "standardFlowEnabled": True,
        # One login path, through a Custom Tab. Direct grants would also mis-report the DPoP
        # binding (E1: ROPC binds the access token on the initial grant), so they stay off.
        "directAccessGrantsEnabled": False,
        "implicitFlowEnabled": False,
        "serviceAccountsEnabled": False,
        "consentRequired": False,
        "frontchannelLogout": False,
        # Least privilege: the app gets only the roles it is explicitly assigned.
        "fullScopeAllowed": False,
        "redirectUris": redirect_uris,
        # A native app sends no Origin, and CORS on the API stays closed.
        "webOrigins": [],
        "attributes": {
            "pkce.code.challenge.method": "S256",
            "dpop.bound.access.tokens": "false",
            "oauth2.device.authorization.grant.enabled": "false",
            "oauth2.token.exchange.grant.enabled": "false",
            "access.token.lifespan": str(ACCESS_TOKEN_LIFESPAN_SECONDS),
            "client.session.idle.timeout": str(idle),
            "client.session.max.lifespan": str(maximum),
            "post.logout.redirect.uris": "+",
        },
    }


def dpop_profile() -> dict:
    """The client profile carrying the refresh-token-only bind enforcer.

    Only `allow-only-refresh-token-binding` is on. The other two options are spelled out rather
    than omitted because their defaults are what a reader will assume, and E1 showed
    `enforce-authorization-code-binding-to-dpop` silently requires the per-client "require DPoP"
    switch — which would re-bind the access token.
    """
    return {
        "name": PROFILE_NAME,
        "description": (
            "Bind only the refresh token to the DPoP key; access tokens stay plain Bearer so the "
            "backend's resource server accepts them unchanged."
        ),
        "executors": [
            {
                "executor": "dpop-bind-enforcer",
                "configuration": {
                    "auto-configure": False,
                    "enforce-authorization-code-binding-to-dpop": False,
                    "allow-only-refresh-token-binding": True,
                },
            }
        ],
    }


def dpop_policy() -> dict:
    """The policy binding the profile to whichever client carries the marker role.

    Keycloak has no condition that names clients directly; a marker client role plus the
    `client-roles` condition is the documented way to scope a policy to one client.
    """
    return {
        "name": POLICY_NAME,
        "description": (
            "Applies the mobile DPoP profile to clients carrying the "
            f"'{MARKER_ROLE}' client role."
        ),
        "enabled": True,
        "conditions": [
            {"condition": "client-roles", "configuration": {"roles": [MARKER_ROLE]}}
        ],
        "profiles": [PROFILE_NAME],
    }


def merge_by_name(existing: list[dict], desired: dict) -> list[dict]:
    """Replace the entry named like `desired`, or append it, preserving every other entry.

    Both client-policy endpoints replace the whole realm-global list on write, so anything not
    carried forward here is deleted. Order is preserved so a diff of two consecutive runs is empty.
    """
    replaced = False
    merged = []
    for entry in existing:
        if entry.get("name") == desired["name"]:
            merged.append(desired)
            replaced = True
        else:
            merged.append(entry)
    if not replaced:
        merged.append(desired)
    return merged


def read_list(kc: Kcadm, path: str, key: str) -> list[dict]:
    """Read one of the client-policy lists, dropping Keycloak's read-only global entries.

    `globalProfiles` / `globalPolicies` come back on read and are rejected on write.
    """
    body = kc.get(path) or {}
    return list(body.get(key) or [])


def find_client(kc: Kcadm) -> dict | None:
    found = kc.get("clients", {"clientId": CLIENT_ID})
    return found[0] if found else None


def detach_policy(kc: Kcadm) -> bool:
    """Remove our policy from the realm list so the client becomes editable. Returns True if it was
    attached."""
    policies = read_list(kc, "client-policies/policies", "policies")
    remaining = [p for p in policies if p.get("name") != POLICY_NAME]
    if len(remaining) == len(policies):
        print("  policy not attached — nothing to detach")
        return False
    kc.write("update", "client-policies/policies", {"policies": remaining},
             "detached so the client can be edited")
    return True


def upsert_client(kc: Kcadm, redirect_uris: list[str]) -> str | None:
    """Create or update the client. Returns its internal id, or None under --dry-run before create."""
    idle, maximum = session_bounds(kc)
    desired = client_representation(redirect_uris, idle, maximum)
    existing = find_client(kc)
    if existing is None:
        kc.write("create", "clients", desired, "client created")
        created = find_client(kc)
        return created["id"] if created else None
    # Merge onto the live representation so unrelated fields Keycloak maintains survive.
    merged = dict(existing)
    merged.update(desired)
    merged["attributes"] = {**(existing.get("attributes") or {}), **desired["attributes"]}
    kc.write("update", f"clients/{existing['id']}", merged, "client updated")
    return existing["id"]


def upsert_marker_role(kc: Kcadm, client_uuid: str) -> None:
    roles = kc.get(f"clients/{client_uuid}/roles") or []
    if any(role.get("name") == MARKER_ROLE for role in roles):
        print(f"  marker role '{MARKER_ROLE}' already present")
        return
    kc.write("create", f"clients/{client_uuid}/roles",
             {"name": MARKER_ROLE,
              "description": "Marker role: scopes the refresh-only DPoP client policy."},
             "marker role created")


def upsert_audience_mapper(kc: Kcadm, client_uuid: str) -> None:
    """Ensure the `aud=basetool-backend` mapper exists — the backend's audience gate depends on it."""
    path = f"clients/{client_uuid}/protocol-mappers/models"
    mappers = kc.get(path) or []
    desired_config = {
        "included.client.audience": BACKEND_AUDIENCE,
        "access.token.claim": "true",
        "id.token.claim": "false",
        "introspection.token.claim": "true",
    }
    for mapper in mappers:
        if mapper.get("name") == AUDIENCE_MAPPER:
            if (mapper.get("config") or {}).get("included.client.audience") == BACKEND_AUDIENCE:
                print(f"  audience mapper '{AUDIENCE_MAPPER}' already correct")
                return
            updated = dict(mapper)
            updated["config"] = {**(mapper.get("config") or {}), **desired_config}
            kc.write("update", f"{path}/{mapper['id']}", updated, "audience mapper corrected")
            return
    kc.write("create", path, {
        "name": AUDIENCE_MAPPER,
        "protocol": "openid-connect",
        "protocolMapper": "oidc-audience-mapper",
        "config": desired_config,
    }, "audience mapper created")


def upsert_realm_role_scope(kc: Kcadm, client_uuid: str) -> None:
    """Grant exactly [MEMBER_REALM_ROLES] to the client's scope, and take back anything else.

    Converges in both directions on purpose. Granting is what makes the app usable at all; taking
    back is what keeps `FORBIDDEN_REALM_ROLE` from being added by hand in the Admin Console and
    surviving the next provisioning run, which is precisely how a scope grows without a decision.
    """
    assigned = kc.get(f"clients/{client_uuid}/scope-mappings/realm") or []
    assigned_names = {role.get("name") for role in assigned}

    missing = [name for name in MEMBER_REALM_ROLES if name not in assigned_names]
    if missing:
        available = {role.get("name"): role for role in (kc.get("roles") or [])}
        unknown = [name for name in missing if name not in available]
        if unknown:
            raise KcadmError(
                f"the realm has no role(s) {unknown}. The app's token would carry nothing for them "
                f"and every holder would be reconciled onto the Guest fallback. Check the realm's "
                f"role names before re-running."
            )
        kc.write("create", f"clients/{client_uuid}/scope-mappings/realm",
                 [{"id": available[name]["id"], "name": name} for name in missing],
                 f"realm roles granted to the client scope: {', '.join(missing)}")
    else:
        print(f"  realm-role scope already carries {len(MEMBER_REALM_ROLES)} member roles")

    surplus = [role for role in assigned if role.get("name") not in MEMBER_REALM_ROLES]
    if surplus:
        names = ", ".join(sorted(role.get("name") or "?" for role in surplus))
        kc.write("delete", f"clients/{client_uuid}/scope-mappings/realm", surplus,
                 f"realm roles taken back off the client scope: {names}")


def drop_offline_access(kc: Kcadm, client_uuid: str) -> None:
    """Remove the `offline_access` optional scope Keycloak assigns by default.

    An offline token outlives every session bound above; the concept withholds it until there is a
    reason to grant it.
    """
    scopes = kc.get(f"clients/{client_uuid}/optional-client-scopes") or []
    for scope in scopes:
        if scope.get("name") == "offline_access":
            kc.delete(f"clients/{client_uuid}/optional-client-scopes/{scope['id']}",
                      "offline_access withheld")
            return
    print("  offline_access already absent")


def verify(kc: Kcadm, profile: str = "prod") -> list[str]:
    """Assert the live state matches the intent. Returns a list of problems; empty means good.

    :param profile: which redirect-URI set the realm was provisioned with. The loopback
        wildcard the `test` profile installs is deliberate -- a native app cannot know its
        loopback port in advance -- so it is only a problem on `prod`. Without this the
        script flagged a URI it had just written itself, and `--verify-only` could never
        come back clean against a test stack.
    """
    problems: list[str] = []

    client = find_client(kc)
    if client is None:
        return [f"client '{CLIENT_ID}' does not exist"]

    attributes = client.get("attributes") or {}
    if attributes.get("dpop.bound.access.tokens") not in (None, "false"):
        problems.append(
            "dpop.bound.access.tokens is not false — the access token will be DPoP-bound and the "
            "backend will reject it"
        )
    if attributes.get("pkce.code.challenge.method") != "S256":
        problems.append("pkce.code.challenge.method is not S256")
    if client.get("directAccessGrantsEnabled"):
        problems.append("direct access grants are enabled")
    if client.get("publicClient") is not True:
        problems.append("client is not public")
    if profile == "prod" and any("*" in uri for uri in client.get("redirectUris") or []):
        problems.append("a redirect URI contains a wildcard")

    roles = kc.get(f"clients/{client['id']}/roles") or []
    if not any(role.get("name") == MARKER_ROLE for role in roles):
        problems.append(f"marker role '{MARKER_ROLE}' is missing — the policy matches nothing")

    scope = {role.get("name") for role in (kc.get(f"clients/{client['id']}/scope-mappings/realm") or [])}
    for name in MEMBER_REALM_ROLES:
        if name not in scope:
            problems.append(
                f"realm role '{name}' is not on the client scope — with fullScopeAllowed off the "
                f"token carries no roles, and every member who signs in through the app is "
                f"reconciled onto the Guest fallback in the database"
            )
    if FORBIDDEN_REALM_ROLE in scope:
        problems.append(
            f"realm role '{FORBIDDEN_REALM_ROLE}' IS on the client scope — an administrator using "
            f"the app would get admin scoping (all org units without a pin), which no screen there "
            f"is built for"
        )

    profiles = read_list(kc, "client-policies/profiles", "profiles")
    profile = next((p for p in profiles if p.get("name") == PROFILE_NAME), None)
    if profile is None:
        problems.append(f"client profile '{PROFILE_NAME}' is missing")
    else:
        executors = profile.get("executors") or []
        enforcer = next((e for e in executors if e.get("executor") == "dpop-bind-enforcer"), None)
        if enforcer is None:
            problems.append("the profile carries no dpop-bind-enforcer executor")
        elif (enforcer.get("configuration") or {}).get("allow-only-refresh-token-binding") is not True:
            problems.append("allow-only-refresh-token-binding is not on — both tokens would bind")

    policies = read_list(kc, "client-policies/policies", "policies")
    policy = next((p for p in policies if p.get("name") == POLICY_NAME), None)
    if policy is None:
        problems.append(f"policy '{POLICY_NAME}' is not attached — nothing is enforced")
    else:
        if not policy.get("enabled"):
            problems.append("the policy is present but disabled")
        conditions = policy.get("conditions") or []
        scoped = any(
            c.get("condition") == "client-roles"
            and MARKER_ROLE in ((c.get("configuration") or {}).get("roles") or [])
            for c in conditions
        )
        if not scoped:
            problems.append("the policy is not scoped by the marker role — it may hit other clients")
        if PROFILE_NAME not in (policy.get("profiles") or []):
            problems.append("the policy does not reference the profile")

    return problems


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Provision the basetool-android Keycloak client and its DPoP policy.")
    parser.add_argument("--realm", default="iri", help="target realm (default: iri)")
    parser.add_argument("--container", default="keycloak",
                        help="Keycloak container name (default: keycloak)")
    parser.add_argument("--profile", choices=sorted(REDIRECT_URIS), default="prod",
                        help="which redirect-URI set to register (default: prod)")
    parser.add_argument("--dry-run", action="store_true",
                        help="print every payload, write nothing")
    parser.add_argument("--verify-only", action="store_true",
                        help="assert the live state and exit without writing")
    parser.add_argument("--kcadm-command",
                        help="override the kcadm invocation (used by the tests)")
    args = parser.parse_args()

    prefix = (shlex.split(args.kcadm_command) if args.kcadm_command
              else ["docker", "exec", "-i", args.container, "/opt/keycloak/bin/kcadm.sh"])
    kc = Kcadm(prefix, args.realm, dry_run=args.dry_run)

    try:
        if args.verify_only:
            problems = verify(kc, args.profile)
        else:
            print(f"[1/5] detaching '{POLICY_NAME}' so the client is editable")
            was_attached = detach_policy(kc)

            print(f"[2/5] client '{CLIENT_ID}' ({args.profile} redirect URIs)")
            client_uuid = upsert_client(kc, REDIRECT_URIS[args.profile])
            if client_uuid is None:
                print("\n[dry-run] the client does not exist yet; the steps that need its id are "
                      "skipped. Re-run without --dry-run, or against an existing client, to see "
                      "them.")
                return 0

            print("[3/5] marker role, realm-role scope, audience mapper, offline_access")
            upsert_marker_role(kc, client_uuid)
            upsert_realm_role_scope(kc, client_uuid)
            upsert_audience_mapper(kc, client_uuid)
            drop_offline_access(kc, client_uuid)

            print(f"[4/5] client profile '{PROFILE_NAME}'")
            profiles = read_list(kc, "client-policies/profiles", "profiles")
            kc.write("update", "client-policies/profiles",
                     {"profiles": merge_by_name(profiles, dpop_profile())},
                     f"profile merged into {len(profiles)} existing")

            print(f"[5/5] attaching policy '{POLICY_NAME}'"
                  f"{' (was attached before)' if was_attached else ''}")
            policies = read_list(kc, "client-policies/policies", "policies")
            kc.write("update", "client-policies/policies",
                     {"policies": merge_by_name(policies, dpop_policy())},
                     f"policy merged into {len(policies)} existing")

            if args.dry_run:
                print("\n[dry-run] nothing was written.")
                return 0
            problems = verify(kc, args.profile)
    except KcadmError as error:
        print(f"\nFAILED: {error}", file=sys.stderr)
        print("The client may be left without its policy — it is then unbound, not half-bound. "
              "Fix the cause and re-run; the script is idempotent.", file=sys.stderr)
        return 1

    print("\n[verify]")
    if problems:
        for problem in problems:
            print(f"  PROBLEM: {problem}")
        return 1
    print("  the client and its refresh-only DPoP policy are in the intended state")
    return 0


if __name__ == "__main__":
    sys.exit(main())
