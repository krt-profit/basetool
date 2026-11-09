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
into the realm's default-role composite. Since that composite gives KRT Member to every account, an
account the Basetool lists as departed loses its own mapping of the default role and of KRT Member,
as a departure did before the switch; nothing else is removed. Dry run unless ``--apply``.

The input is a read-only export of the backend database, one ``<user id>\\t<role code>`` line per
assignment and one ``<user id>\\tDEPARTED`` line per active account without KRT Member, written by
``scripts/role-assignments-export.sql`` (``docs/deployment.md``, *Roles in the Basetool*)::

    python3 scripts/restore-keycloak-realm-roles.py --assignments roles.tsv
    python3 scripts/restore-keycloak-realm-roles.py --assignments roles.tsv --apply
    python3 scripts/restore-keycloak-realm-roles.py --selftest

The kcadm session needs the ``realm-management`` roles ``view-users`` and ``manage-users`` (to read
and write user role mappings) and ``manage-realm`` (the default-role composite). Exit codes: 0 when
nothing is left to do, 2 when a dry run planned writes, 1 on a problem, an empty export or a
skipped account.
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
DEFAULT_ROLE = "default-roles-basetool"
DEPARTED = "DEPARTED"
REQUIRED_IDENTITY = ("a kcadm session with the realm-management roles view-users, manage-users "
                     "and manage-realm")
# kcadm.sh prints this for an HTTP 404; every other failure is a refusal or an outage.
NOT_FOUND = re.compile(r"Resource not found|HTTP error - 404")


class MissingRoles(Exception):
    """The realm lacks a realm role the assignments need; the previous provisioner creates them."""


class ReadRefused(Exception):
    """Keycloak refused a role-mapping read for another reason than a missing account."""


UUID_PATTERN = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")


def parse_export(text: str) -> tuple[dict[str, set[str]], set[str]]:
    """Read ``<user id>\\t<role code>`` and ``<user id>\\tDEPARTED`` lines.

    :param text: the export, blank lines allowed
    :return: the role codes each user holds, and the departed accounts
    :raises SystemExit: on a malformed line, an unknown code or a departed account holding KRT Member
    """
    held: dict[str, set[str]] = {}
    departed: set[str] = set()
    for number, line in enumerate(text.splitlines(), 1):
        if not line.strip():
            continue
        parts = line.strip().split("\t")
        if (len(parts) != 2 or not UUID_PATTERN.fullmatch(parts[0])
                or parts[1] not in {*ROLE_NAMES, DEPARTED}):
            raise SystemExit(f"FATAL: line {number} is not '<user id>\\t<code>' with one of "
                             f"{sorted({*ROLE_NAMES, DEPARTED})}")
        if parts[1] == DEPARTED:
            departed.add(parts[0])
        else:
            held.setdefault(parts[0], set()).add(parts[1])
    contradicted = sorted(u for u in departed if DEFAULT_ROLE_MEMBER in held.get(u, set()))
    if contradicted:
        raise SystemExit(f"FATAL: departed account(s) {contradicted} also hold KRT_MEMBER; "
                         f"export again")
    return held, departed


def mapped_roles(kc, user_id: str) -> set[str] | None:
    """Read the realm roles mapped directly to one account.

    :param kc: the kcadm wrapper of the realm
    :param user_id: the Keycloak user id
    :return: the mapped role names, or ``None`` when the realm has no such account
    :raises ReadRefused: when the read fails for any other reason
    """
    try:
        return {r.get("name") for r in (kc.get(f"users/{user_id}/role-mappings/realm") or [])}
    except kcadm.KcadmError as error:
        if NOT_FOUND.search(str(error)):
            return None
        raise ReadRefused(f"reading the role mappings of user {user_id} failed; the rollback "
                          f"needs {REQUIRED_IDENTITY}:\n{error}") from error


def plan(kc, held: dict[str, set[str]],
         departed: set[str]) -> tuple[list[tuple[str, callable]], list[str]]:
    """Plan the writes: the default-role composite, each account's missing role mappings and the
    removal of the default role and KRT Member from departed accounts.

    :param kc: the kcadm wrapper of the realm
    :param held: the role codes each user holds in ``user_roles``
    :param departed: the active accounts the Basetool no longer counts as members
    :return: the planned writes as (description, action) and the accounts skipped
    :raises MissingRoles: when the realm lacks one of the four realm roles
    :raises ReadRefused: when a role-mapping read fails for another reason than a missing account
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
        mapped = mapped_roles(kc, user_id)
        if mapped is None:
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
    for user_id in sorted(departed):
        mapped = mapped_roles(kc, user_id)
        if mapped is None:
            skipped.append(user_id)
            continue
        strip = [roles[name] for name in (default_name, member["name"])
                 if name in mapped and name in roles]
        if strip:
            names = ", ".join(r["name"] for r in strip)
            changes.append((f"- user {user_id}: {names} (departed in the Basetool)",
                            lambda u=user_id, s=strip: kc.delete(
                                f"users/{u}/role-mappings/realm",
                                f"realm roles unmapped: {', '.join(r['name'] for r in s)}",
                                [{"id": r["id"], "name": r["name"]} for r in s])))
    problems = [f"user {u} is not in the realm and is skipped" for u in skipped]
    return changes, problems


def run(kc, held: dict[str, set[str]], departed: set[str], apply: bool) -> int:
    """Plan, print and with ``apply`` write the rollback against one realm.

    :param kc: the kcadm wrapper of the realm
    :param held: the role codes each user holds in ``user_roles``
    :param departed: the active accounts the Basetool no longer counts as members
    :param apply: whether to write the planned changes
    :return: 0 when nothing is left to do, 2 when a dry run planned writes, 1 on a problem
    """
    if not held:
        print("PROBLEM: the export holds no role assignment; export again before rolling back",
              file=sys.stderr)
        return 1
    try:
        changes, problems = plan(kc, held, departed)
    except (MissingRoles, ReadRefused) as refused:
        print(f"PROBLEM: {refused}", file=sys.stderr)
        return 1
    mode = "APPLY" if apply else "DRY RUN — nothing is written"
    print(f"Keycloak realm '{kc.realm}' <- {len(held)} account(s) from user_roles, "
          f"{len(departed)} departed [{mode}]")
    for text, _ in changes:
        print(f"  {text}")
    for line in problems:
        print(f"  PROBLEM: {line}")
    if changes and not apply:
        print(f"{len(changes)} write(s) planned. Review them, then re-run with --apply.")
        return 1 if problems else 2
    for _, action in changes:
        action()
    if not changes:
        print("Nothing to write.")
    return 1 if problems else 0


class FakeKc:
    """An in-memory realm for the self-test."""

    def __init__(self, roles: list[str], composite: list[str], users: dict[str, list[str]],
                 forbidden: set[str] | None = None):
        self.realm = "basetool"
        self.roles = [{"id": f"r-{n}", "name": n} for n in roles]
        self.composite = list(composite)
        self.users = {u: list(r) for u, r in users.items()}
        self.forbidden = forbidden or set()

    def get(self, path: str):
        if path == "roles":
            return self.roles
        if path.endswith("/composites/realm"):
            return [r for r in self.roles if r["name"] in self.composite]
        user = path.split("/")[1]
        if user in self.forbidden:
            raise kcadm.KcadmError(f"kcadm.sh get {path}\n  exit 1: HTTP error - 403, Forbidden")
        if user not in self.users:
            raise kcadm.KcadmError(f"kcadm.sh get {path}\n  exit 1: Resource not found for url: "
                                   f"http://localhost:8080/admin/realms/basetool/{path}")
        return [r for r in self.roles if r["name"] in self.users[user]]

    def get_realm(self) -> dict:
        return {"defaultRole": {"name": DEFAULT_ROLE}}

    def write(self, verb: str, path: str, payload: list, what: str) -> None:
        names = [r["name"] for r in payload]
        if path.endswith("/composites"):
            self.composite += names
        else:
            self.users[path.split("/")[1]] += names

    def delete(self, path: str, what: str, payload: list) -> None:
        names = {r["name"] for r in payload}
        user = path.split("/")[1]
        self.users[user] = [n for n in self.users[user] if n not in names]


def selftest() -> int:
    """Prove the plan restores members, strips departed accounts and fails loudly on skips.

    :return: the process exit code
    """
    failures: list[str] = []

    def expect(label: str, condition: bool) -> None:
        print(f"  {'ok  ' if condition else 'FAIL'} {label}")
        if not condition:
            failures.append(label)

    def realm(users: dict[str, list[str]], forbidden: set[str] | None = None) -> FakeKc:
        return FakeKc(list(ROLE_NAMES.values()) + ["offline_access", DEFAULT_ROLE],
                      ["offline_access"], users, forbidden or set())

    a, b, gone, left = (f"00000000-0000-4000-8000-00000000000{n}" for n in (1, 2, 3, 4))
    export = (f"{a}\tADMIN\n{a}\tKRT_MEMBER\n\n{b}\tKRT_MEMBER\n{gone}\tADMIN\n"
              f"{left}\t{DEPARTED}\n")
    held, departed = parse_export(export)
    expect("the export is read per user", held == {a: {"ADMIN", "KRT_MEMBER"}, b: {"KRT_MEMBER"},
                                                     gone: {"ADMIN"}})
    expect("the departed accounts are read apart", departed == {left})
    kc = realm({a: ["KRT Member", DEFAULT_ROLE], b: [DEFAULT_ROLE],
                left: [DEFAULT_ROLE, "KRT Member", "offline_access"]})
    changes, problems = plan(kc, held, departed)
    for _, action in changes:
        action()
    expect("the default role gets KRT Member back", "KRT Member" in kc.composite)
    expect("an account gets only the roles it lacks",
           sorted(kc.users[a]) == sorted(["Admin", "KRT Member", DEFAULT_ROLE]))
    expect("a member gets KRT Member", sorted(kc.users[b]) == sorted([DEFAULT_ROLE, "KRT Member"]))
    expect("a departed account loses the default role and KRT Member",
           kc.users[left] == ["offline_access"])
    expect("an account missing from the realm is reported", any(gone in p for p in problems))
    again, _ = plan(kc, held, departed)
    expect("a second run plans nothing", again == [])
    try:
        plan(FakeKc(["offline_access"], [], {}), held, departed)
        expect("a realm without the roles is refused", False)
    except MissingRoles:
        expect("a realm without the roles is refused", True)
    try:
        plan(realm({a: [], b: []}, forbidden={b}), held, set())
        expect("a refused role-mapping read stops the plan", False)
    except ReadRefused as refused:
        expect("a refused role-mapping read stops the plan", REQUIRED_IDENTITY in str(refused))
    kc = realm({a: [], b: []})
    expect("an apply that skipped an account exits 1", run(kc, held, set(), apply=True) == 1)
    expect("and still writes the accounts it could read", kc.users[b] == ["KRT Member"])
    expect("an apply without a skip exits 0",
           run(realm({a: [], b: []}), {a: {"ADMIN"}}, set(), apply=True) == 0)
    expect("an empty export exits 1 and writes nothing",
           run(realm({a: []}), {}, set(), apply=True) == 1)
    for label, text in (("an unknown role code is refused", f"{a}\tOFFICER\n"),
                        ("a departed account that holds KRT Member is refused",
                         f"{a}\tKRT_MEMBER\n{a}\t{DEPARTED}\n")):
        try:
            parse_export(text)
            expect(label, False)
        except SystemExit:
            expect(label, True)
    print("selftest: FAILED" if failures else "selftest: passed")
    return 1 if failures else 0


def main(argv: list[str] | None = None) -> int:
    """Plan, and with ``--apply`` write, the realm-role mappings of every account.

    :param argv: the command-line arguments, ``sys.argv[1:]`` when ``None``
    :return: 0 when nothing is left to do, 2 when a dry run planned changes, 1 on a problem, an
        empty export or a skipped account
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
                        help=f"the whole kcadm invocation, e.g. the rootless-Podman string of "
                             f"docs/keycloak/README.md; needs {REQUIRED_IDENTITY}")
    parser.add_argument("--selftest", action="store_true", help="prove the planning logic")
    args = parser.parse_args(argv)
    if args.selftest:
        return selftest()
    if args.assignments is None:
        parser.error("--assignments is required")
    held, departed = parse_export(args.assignments.read_text(encoding="utf-8"))
    prefix = (shlex.split(args.kcadm_command) if args.kcadm_command
              else ["docker", "exec", "-i", args.container, "/opt/keycloak/bin/kcadm.sh"])
    return run(kcadm.Kcadm(prefix, args.realm, dry_run=False), held, departed, args.apply)


if __name__ == "__main__":
    sys.exit(main())
