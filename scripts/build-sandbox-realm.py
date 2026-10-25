#!/usr/bin/env python3
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
"""Build the Keycloak realms of the local exchange sandbox and the E2E stack (REQ-XCH-029, ADR-0225).

Both realms start from the shared test realm base (``scripts/keycloak/test-realm-base.json``: the
roles, the two first-party clients and the E2E accounts) and add what the production provisioner
(``scripts/provision-keycloak-realm.py``) defines: the token and session settings, the login theme,
the Basetool client scopes, the ingest gateway's service account and the approved third-party
client template. The sandbox realm replaces the accounts with the sandbox members and instantiates
the template as ``sandbox-client`` and ``sandbox-suspended-client``; the E2E realm keeps the E2E
accounts and instantiates it as ``e2e-exchange-client``. Every secret and password in them is a
throwaway committed on purpose.

Usage::

    python3 scripts/build-sandbox-realm.py             # write both realms
    python3 scripts/build-sandbox-realm.py --check     # fail when a committed realm is stale
    python3 scripts/build-sandbox-realm.py --selftest  # prove the invariants the realms must keep
"""

from __future__ import annotations

import argparse
import copy
import importlib.util
import json
import re
import sys
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from types import ModuleType

REPO = Path(__file__).resolve().parent.parent
PROVISIONER = REPO / "scripts" / "provision-keycloak-realm.py"
BASE_REALM = REPO / "scripts" / "keycloak" / "test-realm-base.json"
BUILTIN_SCOPES = REPO / "scripts" / "keycloak" / "builtin-client-scopes.json"

REALM = "basetool"
THROWAWAY = "do-not-use-in-prod"
GATEWAY_CLIENT = "basetool-ingest-gateway"
LOAD_MEMBERS = 16

LOCALES = {"internationalizationEnabled": True, "supportedLocales": ["de", "en"],
           "defaultLocale": "en"}

PRODUCTION_MARKERS = ("profit-base.online", "krt-profit.de")

UUID_PATTERN = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"


@dataclass(frozen=True)
class Target:
    """One realm this script writes: where it goes and what sets it apart from the other.

    ``users`` replaces the base accounts when set; ``None`` keeps them. ``offline_users`` names the
    accounts a device grant signs in, which must hold the default roles to get an offline token.
    ``password`` gives the one password each account may have.
    """

    name: str
    output: Path
    display_name: str | None
    secrets: dict[str, str]
    service_account_ids: dict[str, str]
    third_party: list[dict]
    users: list[dict] | None
    offline_users: tuple[str, ...]
    fixed_ids: bool
    password: Callable[[str], str]


SANDBOX = Target(
    name="sandbox",
    output=REPO / "docker" / "sandbox" / "keycloak" / "realm-basetool.json",
    display_name="Basetool (SANDBOX REALM - throwaway, do not use in prod)",
    secrets={
        "basetool-frontend": f"sandbox-frontend-client-secret-{THROWAWAY}",
        "backend-service": f"sandbox-backend-service-secret-{THROWAWAY}",
        GATEWAY_CLIENT: f"sandbox-ingest-gateway-secret-{THROWAWAY}",
    },
    service_account_ids={
        "backend-service": "5a4d0000-0000-4000-8000-0000000000a1",
        GATEWAY_CLIENT: "5a4d0000-0000-4000-8000-0000000000a2",
    },
    third_party=[
        {
            "clientId": "sandbox-client",
            "name": "Sandbox Client",
            "description": "The third-party test client of the local exchange sandbox (REQ-XCH-029)",
            "firefoxExtension": True,
        },
        {
            "clientId": "sandbox-suspended-client",
            "name": "Suspended Example Client",
            "description": "A sandbox client the registry lists as suspended, to show the refusal",
        },
    ],
    users=[
        {"id": "5a4d0000-0000-4000-8000-000000000001", "username": "sandbox-member",
         "roles": ["KRT Member"]},
        {"id": "5a4d0000-0000-4000-8000-000000000002", "username": "sandbox-member-2",
         "roles": ["KRT Member"]},
        {"id": "5a4d0000-0000-4000-8000-000000000003", "username": "sandbox-admin",
         "roles": ["Admin", "Officer", "KRT Member"]},
        *({"id": f"5a4d0000-0000-4000-8000-0000000002{n:02d}", "username": f"sandbox-load-{n:02d}",
           "roles": ["KRT Member"]} for n in range(1, LOAD_MEMBERS + 1)),
    ],
    offline_users=("sandbox-member", "sandbox-member-2", "sandbox-admin",
                   *(f"sandbox-load-{n:02d}" for n in range(1, LOAD_MEMBERS + 1))),
    fixed_ids=True,
    password=lambda username: f"{username}-pw-{THROWAWAY}",
)

E2E = Target(
    name="e2e",
    output=REPO / "frontend" / "src" / "e2e" / "resources" / "realm-export.e2e.json",
    display_name=None,
    secrets={
        "basetool-frontend": f"e2e-frontend-client-secret-{THROWAWAY}",
        "backend-service": f"e2e-client-secret-{THROWAWAY}",
        GATEWAY_CLIENT: f"e2e-ingest-gateway-secret-{THROWAWAY}",
    },
    service_account_ids={
        "backend-service": "0e2e0000-0000-4000-8000-0000000000a1",
        GATEWAY_CLIENT: "0e2e0000-0000-4000-8000-0000000000a2",
    },
    third_party=[
        {
            "clientId": "e2e-exchange-client",
            "name": "E2E Exchange Client",
            "description": "The third-party test client of the E2E exchange round trip (REQ-XCH-032)",
        },
    ],
    users=None,
    offline_users=("test-exchange", "test-exchange-2", "test-exchange-3", "test-exchange-departed"),
    fixed_ids=False,
    password=lambda username: f"{username}-pw",
)

TARGETS = (SANDBOX, E2E)


def load_provisioner() -> ModuleType:
    """Import the production provisioner, whose hyphenated file name is not importable."""
    name = "provision_keycloak_realm"
    if name in sys.modules:
        return sys.modules[name]
    spec = importlib.util.spec_from_file_location(name, PROVISIONER)
    if spec is None or spec.loader is None:
        raise SystemExit(f"FATAL: cannot load the provisioner from {PROVISIONER}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def scope_representation(scope) -> dict:
    """Convert a provisioner ``ScopeSpec`` to the realm-import representation of a client scope."""
    return {
        "name": scope.name,
        "protocol": "openid-connect",
        "attributes": dict(scope.attributes),
        "protocolMappers": [mapper_representation(mapper) for mapper in scope.mappers],
    }


def mapper_representation(mapper: dict) -> dict:
    """Complete a provisioner mapper with the fields the realm import expects."""
    return {
        "name": mapper["name"],
        "protocol": "openid-connect",
        "protocolMapper": mapper["protocolMapper"],
        "consentRequired": False,
        "config": dict(mapper["config"]),
    }


def client_representation(spec, secret: str | None) -> dict:
    """Convert a provisioner ``ClientSpec`` to the realm-import representation of a client.

    :param spec: the client's production shape
    :param secret: the throwaway secret of a confidential client, ``None`` for a public one
    :return: the client as Keycloak imports it
    """
    client = {"clientId": spec.client_id, **spec.create_only, **spec.fields}
    client.update({
        "attributes": dict(spec.attributes),
        "redirectUris": list(spec.redirect_uris),
        "webOrigins": list(spec.web_origins),
        "defaultClientScopes": list(spec.default_scopes),
        "optionalClientScopes": list(spec.optional_scopes),
    })
    if spec.mappers:
        client["protocolMappers"] = [mapper_representation(m) for m in spec.mappers]
    if secret is not None:
        client["secret"] = secret
    return client


def spec_by_id(provisioner: ModuleType, client_id: str):
    """Return the provisioner's production shape of one first-party client."""
    specs = provisioner.client_specs(REALM, "http://localhost:18081", None)
    return next(spec for spec in specs if spec.client_id == client_id)


def user_representation(target: Target, user: dict) -> dict:
    """Build one synthetic account of a target that replaces the base accounts."""
    name = user["username"]
    return {
        "id": user["id"],
        "username": name,
        "enabled": True,
        "emailVerified": True,
        "email": f"{name}@example.invalid",
        "credentials": [{"type": "password", "value": target.password(name),
                         "temporary": False}],
        "realmRoles": [f"default-roles-{REALM}", *user["roles"]],
    }


def service_account_user(target: Target, client_id: str,
                         client_roles: dict[str, list[str]]) -> dict:
    """Build the service-account user of a confidential client."""
    user = {
        "id": target.service_account_ids[client_id],
        "username": f"service-account-{client_id}",
        "enabled": True,
        "serviceAccountClientId": client_id,
        "realmRoles": [f"default-roles-{REALM}"],
    }
    if client_roles:
        user["clientRoles"] = {name: sorted(roles) for name, roles in client_roles.items()}
    return user


def build_realm(provisioner: ModuleType, base: dict, builtin_scopes: list[dict],
                target: Target) -> dict:
    """Assemble one target's realm from the base, the built-in scopes and the provisioner.

    :param provisioner: the loaded production provisioner module
    :param base: the shared test realm base
    :param builtin_scopes: Keycloak's built-in OpenID Connect client scopes
    :param target: the realm to build
    :return: the realm representation, ready to serialise
    """
    realm = copy.deepcopy(base)
    base_users = realm.pop("users", [])
    if target.display_name is not None:
        realm["displayName"] = target.display_name
    realm.update(provisioner.REALM_SETTINGS)
    realm.update(LOCALES)

    builtin_names = {scope["name"] for scope in builtin_scopes}
    own = [scope_representation(scope) for scope in provisioner.SCOPES]
    clash = builtin_names & {scope["name"] for scope in own}
    if clash:
        raise SystemExit(f"FATAL: provisioner scopes shadow built-in scopes: {sorted(clash)}")
    realm["clientScopes"] = copy.deepcopy(builtin_scopes) + own
    realm["defaultDefaultClientScopes"] = list(provisioner._STANDARD_DEFAULT)
    realm["defaultOptionalClientScopes"] = list(provisioner._OPTIONAL_WITHOUT_OFFLINE)
    offline = provisioner.OFFLINE_ACCESS
    realm["scopeMappings"] = [{"clientScope": offline, "roles": [offline]}]
    default_role = next(r for r in realm["roles"]["realm"] if r["name"] == f"default-roles-{REALM}")
    held = default_role.setdefault("composites", {}).setdefault("realm", [])
    if offline not in held:
        held.append(offline)

    clients = []
    for client in base["clients"]:
        client = copy.deepcopy(client)
        client["secret"] = target.secrets[client["clientId"]]
        if target is SANDBOX:
            client["name"] = client["name"].replace("(E2E)", "(Sandbox)")
        if client["clientId"] == "backend-service":
            spec = spec_by_id(provisioner, "backend-service")
            client["defaultClientScopes"] = list(spec.default_scopes)
            client["optionalClientScopes"] = list(spec.optional_scopes)
        clients.append(client)
    gateway = spec_by_id(provisioner, GATEWAY_CLIENT)
    clients.append(client_representation(gateway, target.secrets[GATEWAY_CLIENT]))
    for entry in target.third_party:
        clients.append(client_representation(provisioner.external_client_spec(entry), None))
    realm["clients"] = clients

    if target.users is None:
        accounts = copy.deepcopy(base_users)
    else:
        accounts = [user_representation(target, user) for user in target.users]
    backend_roles = spec_by_id(provisioner, "backend-service").service_account_roles or {}
    realm["users"] = accounts + [
        service_account_user(target, "backend-service",
                             {k: v for k, v in backend_roles.items() if k != "<realm>"}),
        service_account_user(target, GATEWAY_CLIENT, {}),
    ]
    return realm


def render(realm: dict) -> str:
    """Serialise the realm deterministically."""
    return json.dumps(realm, indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def inputs() -> tuple[ModuleType, dict, list[dict]]:
    """Read the provisioner, the base realm and the built-in scopes of this checkout."""
    provisioner = load_provisioner()
    base = json.loads(BASE_REALM.read_text(encoding="utf-8"))
    builtin = json.loads(BUILTIN_SCOPES.read_text(encoding="utf-8"))
    return provisioner, base, builtin


def problems(realm: dict, provisioner: ModuleType, target: Target) -> list[str]:
    """List every way a realm breaks its target's invariants.

    :param realm: the realm representation
    :param provisioner: the loaded production provisioner module
    :param target: the realm's target
    :return: one line per broken invariant, empty when the realm is sound
    """
    found: list[str] = []
    text = render(realm)
    for marker in PRODUCTION_MARKERS:
        if marker in text:
            found.append(f"the realm names the production host {marker}")
    clients = {c["clientId"]: c for c in realm.get("clients", [])}
    scopes = {s["name"] for s in realm.get("clientScopes", [])}
    for client_id, client in clients.items():
        secret = client.get("secret")
        if not client.get("publicClient") and (not secret or THROWAWAY not in secret):
            found.append(f"client {client_id} has no throwaway secret")
        for key in ("defaultClientScopes", "optionalClientScopes"):
            for name in client.get(key, []):
                if name not in scopes:
                    found.append(f"client {client_id} names the undefined scope {name}")
    if GATEWAY_CLIENT not in clients:
        found.append(f"{GATEWAY_CLIENT} is missing")
    for entry in target.third_party:
        client_id = entry["clientId"]
        client = clients.get(client_id)
        if client is None:
            found.append(f"{client_id} is missing")
            continue
        attributes = client.get("attributes", {})
        if not (client.get("publicClient") and client.get("consentRequired")):
            found.append(f"{client_id} must be public and require consent")
        if client.get("redirectUris") or client.get("standardFlowEnabled"):
            found.append(f"{client_id} must have no redirect URI and no code flow")
        if attributes.get("dpop.bound.access.tokens") != "true":
            found.append(f"{client_id} tokens must be DPoP-bound")
        if attributes.get("oauth2.device.authorization.grant.enabled") != "true":
            found.append(f"{client_id} must use the device grant")
        missing = set(provisioner.EXCHANGE_SCOPE_NAMES) - set(client.get("optionalClientScopes", []))
        if missing:
            found.append(f"{client_id} lacks exchange scopes {sorted(missing)}")
    for scope in provisioner.SCOPES:
        if scope.name not in scopes:
            found.append(f"the provisioner scope {scope.name} is missing")
    offline = provisioner.OFFLINE_ACCESS
    default_role = next((r for r in realm.get("roles", {}).get("realm", [])
                         if r.get("name") == f"default-roles-{REALM}"), {})
    if offline not in default_role.get("composites", {}).get("realm", []):
        found.append(f"default-roles-{REALM} lacks {offline}, so no member gets an offline token")
    if not any(m.get("clientScope") == offline and offline in m.get("roles", [])
               for m in realm.get("scopeMappings", [])):
        found.append(f"the {offline} client scope maps no {offline} role, so a client without "
                     f"full scope gets no offline token")
    for key in ("defaultDefaultClientScopes", "defaultOptionalClientScopes"):
        if offline in realm.get(key, []):
            found.append(f"{key} lists {offline}, so every client inherits it")
    for key, value in provisioner.REALM_SETTINGS.items():
        if realm.get(key) != value:
            found.append(f"realm setting {key} differs from the provisioner")
    usernames = set()
    for user in realm.get("users", []):
        name = user["username"]
        usernames.add(name)
        if target.fixed_ids and not re.fullmatch(UUID_PATTERN, user.get("id", "")):
            found.append(f"user {name} has no fixed id")
        for credential in user.get("credentials", []):
            if credential.get("value") != target.password(name):
                found.append(f"user {name} has a password that is not its throwaway")
        needs_default = name in target.offline_users or "serviceAccountClientId" in user
        if needs_default and f"default-roles-{REALM}" not in user.get("realmRoles", []):
            found.append(f"user {name} lacks the default roles, so no offline token")
    for name in target.offline_users:
        if name not in usernames:
            found.append(f"user {name} is missing")
    return found


def selftest() -> int:
    """Prove the checks catch a broken realm and the builds are deterministic.

    :return: the process exit code
    """
    failures: list[str] = []

    def expect(label: str, condition: bool) -> None:
        print(f"  {'ok  ' if condition else 'FAIL'} {label}")
        if not condition:
            failures.append(label)

    provisioner, base, builtin = inputs()
    for target in TARGETS:
        realm = build_realm(provisioner, base, builtin, target)

        def caught(broken: dict, current: Target = target) -> bool:
            return problems(broken, provisioner, current) != []

        prefix = f"{target.name}:"
        expect(f"{prefix} the generated realm is sound", not caught(realm))
        expect(f"{prefix} two builds are byte-identical",
               render(realm) == render(build_realm(provisioner, base, builtin, target)))

        broken = copy.deepcopy(realm)
        client_id = target.third_party[0]["clientId"]
        client = next(c for c in broken["clients"] if c["clientId"] == client_id)
        client["attributes"]["dpop.bound.access.tokens"] = "false"
        expect(f"{prefix} a third-party client without DPoP is caught", caught(broken))

        broken = copy.deepcopy(realm)
        broken["clients"][0]["secret"] = "a-real-looking-secret"
        expect(f"{prefix} a secret that is not a throwaway is caught", caught(broken))

        broken = copy.deepcopy(realm)
        broken["users"][0]["credentials"][0]["value"] = "hunter2"
        expect(f"{prefix} a password that is not a throwaway is caught", caught(broken))

        broken = copy.deepcopy(realm)
        broken["clientScopes"] = [s for s in broken["clientScopes"]
                                  if s["name"] != "exchange.connect"]
        expect(f"{prefix} a missing exchange scope is caught", caught(broken))

        broken = copy.deepcopy(realm)
        broken["displayName"] = "https://profit-base.online"
        expect(f"{prefix} a production host name is caught", caught(broken))

        broken = copy.deepcopy(realm)
        broken["accessTokenLifespan"] = 3600
        expect(f"{prefix} a realm setting that differs from production is caught", caught(broken))

        broken = copy.deepcopy(realm)
        offline = next(u for u in broken["users"] if u["username"] == target.offline_users[0])
        offline["realmRoles"] = ["KRT Member"]
        expect(f"{prefix} a device-grant account without the default roles is caught",
               caught(broken))

        broken = copy.deepcopy(realm)
        broken["users"] = [u for u in broken["users"]
                           if u["username"] != target.offline_users[0]]
        expect(f"{prefix} a missing device-grant account is caught", caught(broken))

        broken = copy.deepcopy(realm)
        default_role = next(r for r in broken["roles"]["realm"]
                            if r["name"] == f"default-roles-{REALM}")
        default_role["composites"]["realm"].remove(provisioner.OFFLINE_ACCESS)
        expect(f"{prefix} a default role without offline_access is caught", caught(broken))

        broken = copy.deepcopy(realm)
        broken["scopeMappings"] = []
        expect(f"{prefix} an offline_access scope without its role mapping is caught",
               caught(broken))

        broken = copy.deepcopy(realm)
        broken["defaultOptionalClientScopes"].append(provisioner.OFFLINE_ACCESS)
        expect(f"{prefix} offline_access as a realm default client scope is caught",
               caught(broken))

    sandbox = build_realm(provisioner, base, builtin, SANDBOX)
    expect("sandbox: the E2E accounts are dropped",
           not any(u["username"].startswith("test-") for u in sandbox["users"]))
    e2e = build_realm(provisioner, base, builtin, E2E)
    kept = {u["username"]: u for u in e2e["users"]}
    expect("e2e: every base account is kept with its roles",
           all(b["username"] in kept and kept[b["username"]]["realmRoles"] == b["realmRoles"]
               for b in base["users"]))

    print("selftest: FAILED" if failures else "selftest: passed")
    return 1 if failures else 0


def main(argv: list[str] | None = None) -> int:
    """Write, check or self-test the realms.

    :param argv: the command-line arguments, ``sys.argv[1:]`` when ``None``
    :return: the process exit code
    """
    parser = argparse.ArgumentParser(prog="build-sandbox-realm.py")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true",
                      help="fail when a committed realm differs from a fresh build")
    mode.add_argument("--selftest", action="store_true", help="prove the realms' invariants")
    args = parser.parse_args(argv)
    if args.selftest:
        return selftest()
    provisioner, base, builtin = inputs()
    status = 0
    for target in TARGETS:
        text = render(build_realm(provisioner, base, builtin, target))
        relative = target.output.relative_to(REPO).as_posix()
        found = problems(json.loads(text), provisioner, target)
        if found:
            for line in found:
                print(f"FAIL {relative}: {line}")
            status = 1
            continue
        if args.check:
            current = target.output.read_text(encoding="utf-8") if target.output.exists() else ""
            if current != text:
                print(f"FAIL {relative} is stale; run python3 scripts/build-sandbox-realm.py "
                      f"and commit the result")
                status = 1
            else:
                print(f"OK: {relative} matches a fresh build")
            continue
        target.output.parent.mkdir(parents=True, exist_ok=True)
        with target.output.open("w", encoding="utf-8", newline="\n") as handle:
            handle.write(text)
        print(f"wrote {relative}")
    return status


if __name__ == "__main__":
    sys.exit(main())
