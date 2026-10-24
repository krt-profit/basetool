#!/usr/bin/env python3
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only

"""Renames the production Keycloak realm from its old name to the current one.

Three steps, each skipped when it is already done, so a run that stopped halfway is simply run
again: the realm itself, then its default role by id (Keycloak renames the realm but not
`default-roles-<realm>`), then the display name and description of the frontend client. A final read
verifies the result. Like scripts/provision-keycloak-realm.py it drives the operator's existing
`kcadm.sh` session, never handles a credential and never prints a client representation. Dry run
unless --apply.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import shlex
import sys
from dataclasses import dataclass, field
from pathlib import Path


def _load_kcadm():
    """Import scripts/keycloak_kcadm.py by path, so the script runs from any working directory."""
    path = Path(__file__).resolve().with_name("keycloak_kcadm.py")
    spec = importlib.util.spec_from_file_location("keycloak_kcadm", path)
    if spec is None or spec.loader is None:
        raise SystemExit(f"FATAL: cannot load the kcadm wrapper from {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


kcadm = _load_kcadm()
KcadmError = kcadm.KcadmError

OLD_REALM = "iri"
NEW_REALM = "basetool"
OLD_DEFAULT_ROLE = f"default-roles-{OLD_REALM}"
NEW_DEFAULT_ROLE = f"default-roles-{NEW_REALM}"
FRONTEND_CLIENT_ID = "basetool-frontend"
OLD_BRAND = "IRIDIUM Basetool"
NEW_BRAND = "Profit Basetool"

SESSION_MARKERS = ("http 401", "status: 401", "unauthorized", "invalid token", "login again", "no server specified")
PROVISIONER_CLIENT = "basetool-provisioner"

EXIT_OK = 0
EXIT_FAILED = 1
EXIT_PENDING = 2


class RenameKcadm(kcadm.Kcadm):
    """The shared kcadm wrapper plus the realm-level calls the rename needs."""

    def update_realm(self, name: str, payload: dict, what: str) -> None:
        """Partial update of a realm representation, which lives above the `-r` paths."""
        self._run(["update", f"realms/{name}", "-f", "-"],
                  stdin=json.dumps(payload, indent=2, sort_keys=True))
        print(f"  update realms/{name} — {what}")


@dataclass
class Plan:
    """What the realm needs, read from the realm as it is now."""

    realm_present: dict[str, bool] = field(default_factory=dict)
    working_realm: str = ""
    role_id: str = ""
    role_rename: bool = False
    client_uuid: str = ""
    client_changes: dict[str, str] = field(default_factory=dict)
    problems: list[str] = field(default_factory=list)

    @property
    def realm_rename(self) -> bool:
        """True while the realm still carries the old name."""
        return self.realm_present.get(OLD_REALM, False) and not self.realm_present.get(NEW_REALM, False)

    @property
    def changes(self) -> list[str]:
        """The planned writes, one line each, in execution order."""
        lines = []
        if self.realm_rename:
            lines.append(f"~ rename realm '{OLD_REALM}' -> '{NEW_REALM}'")
        if self.role_rename:
            lines.append(f"~ rename default role '{OLD_DEFAULT_ROLE}' -> '{NEW_DEFAULT_ROLE}' (id {self.role_id})")
        for key, value in sorted(self.client_changes.items()):
            lines.append(f"~ client '{FRONTEND_CLIENT_ID}' {key} -> '{value}'")
        return lines


def is_absent(error: Exception) -> bool:
    """True when kcadm reports that the resource does not exist."""
    text = str(error).lower()
    return "not found" in text or "could not find" in text


def is_session_error(error: Exception) -> bool:
    """True when the failure is the kcadm session itself: expired, or issued by a realm that is gone."""
    text = str(error).lower()
    return any(marker in text for marker in SESSION_MARKERS)


def print_relogin(realm: str) -> None:
    """Tell the operator exactly how to sign kcadm in again (docs/keycloak/README.md, step 2)."""
    print("\nPENDING: the kcadm session is no longer valid (a session issued by a realm that was renamed "
          "stops working at the rename). Sign in again, then re-run this script; it continues with what "
          "is still open:", file=sys.stderr)
    print(f"  sudo -u basetool podman exec -it keycloak /opt/keycloak/bin/kcadm.sh config credentials "
          f"--server https://localhost:18443/auth --realm {realm} --client {PROVISIONER_CLIENT} "
          f"--config \"$KCCFG\"", file=sys.stderr)


def probe_realm(kc: RenameKcadm, name: str) -> dict | None:
    """The realm representation, or None when no realm of that name exists."""
    kc.realm = name
    try:
        return kc.get_realm()
    except KcadmError as error:
        if "not found" in str(error).lower():
            return None
        raise


def rebrand(value: str | None) -> str | None:
    """The value with the old brand replaced; None when it does not change."""
    if value and OLD_BRAND in value:
        return value.replace(OLD_BRAND, NEW_BRAND)
    return None


def build_plan(kc: RenameKcadm) -> Plan:
    """Read the realm and decide which of the three steps are still open."""
    plan = Plan()
    representations = {name: probe_realm(kc, name) for name in (OLD_REALM, NEW_REALM)}
    plan.realm_present = {name: rep is not None for name, rep in representations.items()}
    if all(plan.realm_present.values()):
        plan.problems.append(f"both realm '{OLD_REALM}' and realm '{NEW_REALM}' exist: renaming would "
                             f"collide, nothing is touched")
        return plan
    if not any(plan.realm_present.values()):
        plan.problems.append(f"neither realm '{OLD_REALM}' nor realm '{NEW_REALM}' exists")
        return plan
    plan.working_realm = OLD_REALM if plan.realm_present[OLD_REALM] else NEW_REALM
    kc.realm = plan.working_realm
    realm = representations[plan.working_realm] or {}

    default_role = realm.get("defaultRole") or {}
    plan.role_id = default_role.get("id") or ""
    role_name = default_role.get("name")
    if not plan.role_id:
        plan.problems.append("the realm names no default role")
    elif role_name == OLD_DEFAULT_ROLE:
        try:
            taken = kc.get(f"roles/{NEW_DEFAULT_ROLE}")
        except KcadmError as error:
            if not is_absent(error):
                raise
            taken = None
        if taken:
            plan.problems.append(f"a role named '{NEW_DEFAULT_ROLE}' exists already (id "
                                 f"{taken.get('id')}): decide by hand")
        plan.role_rename = True
    elif role_name != NEW_DEFAULT_ROLE:
        plan.problems.append(f"the default role is named '{role_name}', expected "
                             f"'{OLD_DEFAULT_ROLE}' or '{NEW_DEFAULT_ROLE}'")

    clients = kc.get("clients", {"clientId": FRONTEND_CLIENT_ID}) or []
    if not clients:
        plan.problems.append(f"client '{FRONTEND_CLIENT_ID}' does not exist")
    else:
        client = clients[0]
        plan.client_uuid = client.get("id") or ""
        for key in ("name", "description"):
            changed = rebrand(client.get(key))
            if changed is not None:
                plan.client_changes[key] = changed
    return plan


def print_plan(plan: Plan) -> None:
    """Print the realm state and the planned writes, never a representation."""
    state = ", ".join(f"'{name}' {'exists' if present else 'does not exist'}"
                      for name, present in plan.realm_present.items())
    print(f"realms: {state}")
    for line in plan.changes:
        print(f"  {line}")
    for problem in plan.problems:
        print(f"  ! {problem}")


def apply_plan(kc: RenameKcadm, plan: Plan) -> None:
    """Perform the open steps in order; after the rename every call uses the new realm name."""
    if plan.realm_rename:
        kc.update_realm(OLD_REALM, {"realm": NEW_REALM}, f"realm renamed to '{NEW_REALM}'")
        kc.realm = NEW_REALM
        print("  (the realm is renamed; every call from here on names the new realm)")
    kc.realm = NEW_REALM
    if plan.role_rename:
        current = kc.get(f"roles-by-id/{plan.role_id}") or {}
        body = {"name": NEW_DEFAULT_ROLE}
        if current.get("description") is not None:
            body["description"] = current["description"]
        kc.write("update", f"roles-by-id/{plan.role_id}", body,
                 f"default role renamed to '{NEW_DEFAULT_ROLE}'")
    if plan.client_changes:
        kc.write("update", f"clients/{plan.client_uuid}", dict(plan.client_changes),
                 f"client '{FRONTEND_CLIENT_ID}' relabelled")


def verify(kc: RenameKcadm, role_id: str) -> list[str]:
    """Read everything back; returns what is still wrong, empty when the rename is complete."""
    wrong = []
    if probe_realm(kc, OLD_REALM) is not None:
        wrong.append(f"realm '{OLD_REALM}' still exists")
    realm = probe_realm(kc, NEW_REALM)
    if realm is None:
        return wrong + [f"realm '{NEW_REALM}' does not exist"]
    default_role = realm.get("defaultRole") or {}
    if default_role.get("name") != NEW_DEFAULT_ROLE:
        wrong.append(f"the realm's default role is named '{default_role.get('name')}', not '{NEW_DEFAULT_ROLE}'")
    if role_id and default_role.get("id") != role_id:
        wrong.append("the realm's default role is no longer the role that was renamed")
    if role_id:
        role = kc.get(f"roles-by-id/{role_id}") or {}
        if role.get("name") != NEW_DEFAULT_ROLE:
            wrong.append(f"role {role_id} is named '{role.get('name')}', not '{NEW_DEFAULT_ROLE}'")
    clients = kc.get("clients", {"clientId": FRONTEND_CLIENT_ID}) or []
    if not clients:
        wrong.append(f"client '{FRONTEND_CLIENT_ID}' does not exist")
    for key in ("name", "description"):
        if clients and rebrand(clients[0].get(key)) is not None:
            wrong.append(f"client '{FRONTEND_CLIENT_ID}' {key} still carries '{OLD_BRAND}'")
    return wrong


def run(kc: RenameKcadm, apply: bool) -> int:
    """Plan, optionally apply and verify; returns the process exit code."""
    plan = build_plan(kc)
    print_plan(plan)
    if plan.problems:
        print("\nNot proceeding: fix the problems above first.", file=sys.stderr)
        return EXIT_FAILED
    if not plan.changes:
        print("\nNothing to do: the realm is renamed already.")
        wrong = verify(kc, plan.role_id)
        for line in wrong:
            print(f"  ! {line}", file=sys.stderr)
        return EXIT_FAILED if wrong else EXIT_OK
    if not apply:
        print(f"\n{len(plan.changes)} change(s) planned. Review them, then re-run with --apply.")
        return EXIT_PENDING
    print("\n[apply]")
    try:
        apply_plan(kc, plan)
    except KcadmError as error:
        if is_session_error(error):
            print_relogin(kc.realm)
            return EXIT_PENDING
        print(f"\nFAILED: {error}", file=sys.stderr)
        if plan.realm_rename and kc.realm == NEW_REALM:
            print(f"The realm is named '{NEW_REALM}' already; fix the cause and re-run, the script "
                  f"continues with what is still open.", file=sys.stderr)
        return EXIT_FAILED
    print("\n[verify] reading the realm back")
    wrong = verify(kc, plan.role_id)
    if wrong:
        for line in wrong:
            print(f"  ! {line}", file=sys.stderr)
        print("\nFAILED: the realm is not in shape after applying.", file=sys.stderr)
        return EXIT_FAILED
    print(f"  realm '{NEW_REALM}' exists, realm '{OLD_REALM}' is gone")
    print(f"  the default role is '{NEW_DEFAULT_ROLE}' and is still the realm's default role")
    print(f"  client '{FRONTEND_CLIENT_ID}' carries no '{OLD_BRAND}' label")
    print("\nApplied. A second run reports nothing to do.")
    return EXIT_OK


def main() -> int:
    parser = argparse.ArgumentParser(
        description=f"Rename the Keycloak realm '{OLD_REALM}' to '{NEW_REALM}', its default role "
                    "and the frontend client's labels. Dry run unless --apply.")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true",
                      help="read only and report what would change (the default)")
    mode.add_argument("--apply", action="store_true", help="write the planned changes")
    parser.add_argument("--container", default="keycloak",
                        help="Keycloak container for the default `docker exec` prefix")
    parser.add_argument("--kcadm-command",
                        help="the whole kcadm invocation, e.g. the rootless-Podman string of "
                             "docs/keycloak/README.md (also used by the tests)")
    args = parser.parse_args()
    prefix = (shlex.split(args.kcadm_command) if args.kcadm_command
              else ["docker", "exec", "-i", args.container, "/opt/keycloak/bin/kcadm.sh"])
    kc = RenameKcadm(prefix, OLD_REALM, dry_run=False)
    mode_name = "APPLY" if args.apply else "CHECK — nothing is written"
    print(f"Keycloak realm '{OLD_REALM}' -> '{NEW_REALM}' [{mode_name}]\n")
    try:
        return run(kc, args.apply)
    except KcadmError as error:
        if is_session_error(error):
            print_relogin(kc.realm)
            return EXIT_PENDING
        print(f"\nFAILED: {error}", file=sys.stderr)
        print("Fix the cause and re-run; the script is idempotent.", file=sys.stderr)
        return EXIT_FAILED


if __name__ == "__main__":
    sys.exit(main())
