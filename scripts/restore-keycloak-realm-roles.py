#!/usr/bin/env python3
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
"""Write the Basetool's role assignments back into Keycloak realm roles (rollback of ADR-0355).

Used only when the release that holds roles in ``user_roles`` is rolled back to one that reads
them from the token. The previous release's provisioner recreates the four realm roles first; this
script then maps every account to the roles ``user_roles`` holds for it and puts KRT Member back
into the realm's default-role composite. It adds and never removes. Dry run unless ``--apply``.

The assignments come from a read-only export of the backend database, one ``<user id>\\t<role
code>`` line each, written by ``scripts/role-assignments-export.sql`` (``docs/deployment.md``,
*Roles in the Basetool*)::

    python3 scripts/restore-keycloak-realm-roles.py --assignments roles.tsv
    python3 scripts/restore-keycloak-realm-roles.py --assignments roles.tsv --apply
    python3 scripts/restore-keycloak-realm-roles.py --selftest
"""

from __future__ import annotations

import argparse
import importlib.util
import re
import shlex
import sys
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

ROLE_NAMES = {
    "ADMIN": "Admin",
    "KRT_MEMBER": "KRT Member",
    "BANK_EMPLOYEE": "Bank Employee",
    "BANK_MANAGEMENT": "Bank Management",
}
DEFAULT_ROLE_MEMBER = "KRT_MEMBER"


class MissingRoles(Exception):
    """The realm lacks a realm role the assignments need; the previous provisioner creates them."""


UUID_PATTERN = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")


def parse_assignments(text: str) -> dict[str, set[str]]:
    """Read ``<user id>\\t<role code>`` lines into role codes by user id.

    :param text: the export, blank lines allowed
    :return: the role codes each user holds
    :raises SystemExit: on a malformed line or an unknown role code
    """
    held: dict[str, set[str]] = {}
    for number, line in enumerate(text.splitlines(), 1):
        if not line.strip():
            continue
        parts = line.strip().split("\t")
        if len(parts) != 2 or not UUID_PATTERN.fullmatch(parts[0]) or parts[1] not in ROLE_NAMES:
            raise SystemExit(f"FATAL: line {number} is not '<user id>\\t<role code>' with one of "
                             f"{sorted(ROLE_NAMES)}")
        held.setdefault(parts[0], set()).add(parts[1])
    return held


def plan(kc, held: dict[str, set[str]]) -> tuple[list[tuple[str, callable]], list[str]]:
    """Plan the writes: the default-role composite and each account's missing role mappings.

    :param kc: the kcadm wrapper of the realm
    :param held: the role codes each user holds in ``user_roles``
    :return: the planned writes as (description, action) and the accounts skipped
    :raises MissingRoles: when the realm lacks one of the four realm roles
    """
    roles = {r.get("name"): r for r in (kc.get("roles") or [])}
    missing = sorted(name for name in ROLE_NAMES.values() if name not in roles)
    if missing:
        raise MissingRoles(f"the realm lacks the realm role(s) {missing}; run the previous "
                           f"release's provisioner first, which creates them")
    changes: list[tuple[str, callable]] = []
    default_name = ((kc.get_realm().get("defaultRole") or {}).get("name")
                    or f"default-roles-{kc.realm}")
    member = roles[ROLE_NAMES[DEFAULT_ROLE_MEMBER]]
    composites = {r.get("name") for r in (kc.get(f"roles/{default_name}/composites/realm") or [])}
    if member["name"] not in composites:
        changes.append((f"+ default role '{default_name}': composite '{member['name']}'",
                        lambda: kc.write("create", f"roles/{default_name}/composites",
                                         [{"id": member["id"], "name": member["name"]}],
                                         f"'{member['name']}' added to '{default_name}'")))
    skipped: list[str] = []
    for user_id in sorted(held):
        try:
            mapped = {r.get("name")
                      for r in (kc.get(f"users/{user_id}/role-mappings/realm") or [])}
        except kcadm.KcadmError:
            skipped.append(user_id)
            continue
        grant = [roles[ROLE_NAMES[code]] for code in sorted(held[user_id])
                 if ROLE_NAMES[code] not in mapped]
        if grant:
            names = ", ".join(r["name"] for r in grant)
            changes.append((f"+ user {user_id}: {names}",
                            lambda u=user_id, g=grant: kc.write(
                                "create", f"users/{u}/role-mappings/realm",
                                [{"id": r["id"], "name": r["name"]} for r in g],
                                f"realm roles mapped: {', '.join(r['name'] for r in g)}")))
    problems = [f"user {u} is not in the realm and is skipped" for u in skipped]
    return changes, problems


class FakeKc:
    """An in-memory realm for the self-test."""

    def __init__(self, roles: list[str], composite: list[str], users: dict[str, list[str]]):
        self.realm = "basetool"
        self.roles = [{"id": f"r-{n}", "name": n} for n in roles]
        self.composite = list(composite)
        self.users = {u: list(r) for u, r in users.items()}
        self.writes: list[str] = []

    def get(self, path: str):
        if path == "roles":
            return self.roles
        if path.endswith("/composites/realm"):
            return [r for r in self.roles if r["name"] in self.composite]
        user = path.split("/")[1]
        if user not in self.users:
            raise kcadm.KcadmError("404")
        return [r for r in self.roles if r["name"] in self.users[user]]

    def get_realm(self) -> dict:
        return {"defaultRole": {"name": "default-roles-basetool"}}

    def write(self, verb: str, path: str, payload: list, what: str) -> None:
        self.writes.append(path)
        names = [r["name"] for r in payload]
        if path.endswith("/composites"):
            self.composite += names
        else:
            self.users[path.split("/")[1]] += names


def selftest() -> int:
    """Prove the plan adds what is missing, keeps what is there and refuses a realm without roles.

    :return: the process exit code
    """
    failures: list[str] = []

    def expect(label: str, condition: bool) -> None:
        print(f"  {'ok  ' if condition else 'FAIL'} {label}")
        if not condition:
            failures.append(label)

    a, b, gone = (f"00000000-0000-4000-8000-00000000000{n}" for n in (1, 2, 3))
    held = parse_assignments(f"{a}\tADMIN\n{a}\tKRT_MEMBER\n\n{b}\tKRT_MEMBER\n{gone}\tADMIN\n")
    expect("the export is read per user", held == {a: {"ADMIN", "KRT_MEMBER"}, b: {"KRT_MEMBER"},
                                                     gone: {"ADMIN"}})
    kc = FakeKc(list(ROLE_NAMES.values()) + ["offline_access"], ["offline_access"],
                {a: ["KRT Member"], b: []})
    changes, problems = plan(kc, held)
    for _, action in changes:
        action()
    expect("the default role gets KRT Member back", "KRT Member" in kc.composite)
    expect("an account gets only the roles it lacks",
           sorted(kc.users[a]) == ["Admin", "KRT Member"])
    expect("a member gets KRT Member", kc.users[b] == ["KRT Member"])
    expect("an account missing from the realm is reported", any(gone in p for p in problems))
    again, _ = plan(kc, held)
    expect("a second run plans nothing", again == [])
    try:
        plan(FakeKc(["offline_access"], [], {}), held)
        expect("a realm without the roles is refused", False)
    except MissingRoles:
        expect("a realm without the roles is refused", True)
    try:
        parse_assignments(f"{a}\tOFFICER\n")
        expect("an unknown role code is refused", False)
    except SystemExit:
        expect("an unknown role code is refused", True)
    print("selftest: FAILED" if failures else "selftest: passed")
    return 1 if failures else 0


def main(argv: list[str] | None = None) -> int:
    """Plan, and with ``--apply`` write, the realm-role mappings of every account.

    :param argv: the command-line arguments, ``sys.argv[1:]`` when ``None``
    :return: 0 when nothing is left to do, 2 when a dry run planned changes, 1 on a problem
    """
    parser = argparse.ArgumentParser(prog="restore-keycloak-realm-roles.py")
    parser.add_argument("--realm", default="basetool", help="target realm (default: basetool)")
    parser.add_argument("--assignments", type=Path,
                        help="the user_roles export, one '<user id>\\t<role code>' line each")
    parser.add_argument("--apply", action="store_true",
                        help="write the planned mappings (default: dry run, writes nothing)")
    parser.add_argument("--container", default="keycloak",
                        help="Keycloak container for the default `docker exec` prefix")
    parser.add_argument("--kcadm-command",
                        help="the whole kcadm invocation, e.g. the rootless-Podman string of "
                             "docs/keycloak/README.md")
    parser.add_argument("--selftest", action="store_true", help="prove the planning logic")
    args = parser.parse_args(argv)
    if args.selftest:
        return selftest()
    if args.assignments is None:
        parser.error("--assignments is required")
    held = parse_assignments(args.assignments.read_text(encoding="utf-8"))
    prefix = (shlex.split(args.kcadm_command) if args.kcadm_command
              else ["docker", "exec", "-i", args.container, "/opt/keycloak/bin/kcadm.sh"])
    kc = kcadm.Kcadm(prefix, args.realm, dry_run=False)
    try:
        changes, problems = plan(kc, held)
    except MissingRoles as missing:
        print(f"PROBLEM: {missing}", file=sys.stderr)
        return 1
    mode = "APPLY" if args.apply else "DRY RUN — nothing is written"
    print(f"Keycloak realm '{args.realm}' <- {len(held)} account(s) from user_roles [{mode}]")
    for text, _ in changes:
        print(f"  {text}")
    for line in problems:
        print(f"  PROBLEM: {line}")
    if not changes:
        print("Nothing to write.")
        return 0
    if not args.apply:
        print(f"{len(changes)} write(s) planned. Review them, then re-run with --apply.")
        return 2
    for _, action in changes:
        action()
    return 0


if __name__ == "__main__":
    sys.exit(main())
