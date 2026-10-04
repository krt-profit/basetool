#!/usr/bin/env python3
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
"""Fail when the deploy account's podman allowlist and the podman calls it serves disagree.

The sudoers rule in ``ansible/roles/basetool_host/tasks/22-deploy-user.yml`` admits only the podman
sub-commands listed in ``basetool_host_deploy_podman_subcommands`` (role defaults, OPS-SEC-08). The
sub-commands actually used are derived from the code the deploy account runs: the ``ExecStart``
script of every unit in ``basetool_host_deploy_account_units``, every ``scripts/lib/*.sh`` they
source, and the role's own bridge proof.

* A used sub-command missing from the allowlist would be refused on the host by ``sudo -n``.
* An allowlisted sub-command nothing uses widens the account for no reason.

Usage::

    python3 scripts/check-deploy-podman-allowlist.py            # check the repository
    python3 scripts/check-deploy-podman-allowlist.py --selftest # prove both rules can fail
"""

from __future__ import annotations

import pathlib
import re
import sys

import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULTS = "ansible/roles/basetool_host/defaults/main.yml"
DEPLOY_USER_TASKS = "ansible/roles/basetool_host/tasks/22-deploy-user.yml"
LIB_GLOB = "scripts/lib/*.sh"

TWO_WORD = {"container", "image", "network", "system", "volume"}
RT_CLI_CALL = re.compile(r"\$\{?RT_CLI\}?\s+([a-z][a-z-]*)(?:\s+([a-z][a-z-]*))?")
SUDO_PODMAN_CALL = re.compile(
    r"sudo\s+-n\s+-u\s+(?:\{\{[^}]*\}\}|\S+)\s+podman\s+([a-z][a-z-]*)(?:\s+([a-z][a-z-]*))?"
)
EXEC_START = re.compile(r"^ExecStart=\S*/scripts/(\S+)", re.MULTILINE)


def subcommands(text: str) -> set[str]:
    """Return the podman sub-commands a shell or task text runs through the deploy bridge."""
    found: set[str] = set()
    for pattern in (RT_CLI_CALL, SUDO_PODMAN_CALL):
        for first, second in pattern.findall(text):
            found.add(f"{first} {second}" if first in TWO_WORD and second else first)
    return found


def compare(allowed: list[str], used: set[str]) -> list[str]:
    """Return one problem line per sub-command the two sides disagree on."""
    problems = [
        f"podman {sub} is run by the deploy account but missing from basetool_host_deploy_podman_subcommands"
        for sub in sorted(used - set(allowed))
    ]
    problems += [
        f"podman {sub} is allowlisted for the deploy account but nothing it runs uses it"
        for sub in sorted(set(allowed) - used)
    ]
    if len(allowed) != len(set(allowed)):
        problems.append("basetool_host_deploy_podman_subcommands lists a sub-command twice")
    return problems


def used_in_repository(defaults: dict) -> set[str]:
    """Collect the sub-commands of every script the deploy-account units run, their libs and the role."""
    texts = [(ROOT / DEPLOY_USER_TASKS).read_text(encoding="utf-8")]
    texts += [p.read_text(encoding="utf-8") for p in sorted(ROOT.glob(LIB_GLOB))]
    for unit in defaults["basetool_host_deploy_account_units"]:
        unit_text = (ROOT / "scripts" / unit).read_text(encoding="utf-8")
        for script in EXEC_START.findall(unit_text):
            texts.append((ROOT / "scripts" / script).read_text(encoding="utf-8"))
    used: set[str] = set()
    for text in texts:
        used |= subcommands(text)
    return used


def check() -> int:
    """Check the repository and return the process exit code."""
    defaults = yaml.safe_load((ROOT / DEFAULTS).read_text(encoding="utf-8"))
    allowed = defaults.get("basetool_host_deploy_podman_subcommands") or []
    problems = compare(allowed, used_in_repository(defaults))
    for problem in problems:
        print(f"::error file={DEFAULTS}::{problem}")
    if not problems:
        print(f"deploy podman allowlist OK: {len(allowed)} sub-commands, each one used")
    return 1 if problems else 0


def selftest() -> int:
    """Prove the extraction and both comparison rules on synthetic input."""
    script = (
        'cid="$(${RT_CLI} create "${ref}")"\n'
        "${RT_CLI} image prune --force\n"
        "${RT_CLI} system df || true\n"
        'sudo -n -u "${u}" podman ps --format x\n'
        "sudo -n -u {{ basetool_host_service_user }}\n    podman info --format x\n"
        "$RT_CLI exec db sh\n"
    )
    cases = [
        ("extraction", subcommands(script) == {"create", "image prune", "system df", "ps", "exec", "info"}),
        ("agreement passes", compare(["ps", "exec"], {"ps", "exec"}) == []),
        ("a used sub-command missing from the allowlist fails", len(compare(["ps"], {"ps", "exec"})) == 1),
        ("an unused allowlist entry fails", len(compare(["ps", "run"], {"ps"})) == 1),
        ("a duplicate entry fails", len(compare(["ps", "ps"], {"ps"})) == 1),
    ]
    failed = [name for name, ok in cases if not ok]
    for name in failed:
        print(f"selftest FAILED: {name}", file=sys.stderr)
    if not failed:
        print(f"selftest OK ({len(cases)} cases)")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(selftest() if "--selftest" in sys.argv[1:] else check())
