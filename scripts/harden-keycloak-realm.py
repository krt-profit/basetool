#!/usr/bin/env python3
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only

"""Steps 2, 11 and 12 of docs/KEYCLOAK_HARDENING_RUNBOOK.md as an idempotent, dry-run-first script.

Step 2: report the realm's own SMTP sender and switch "Forgot password" on or off (the sender's
test stays a console action: Keycloak mails the admin who runs it).
Step 11: a browser-flow copy and a post-broker flow that demand an OTP of every holder of the
Admin realm role, and their binding to the realm and to the Discord identity provider.
Step 12: the realm's SSO session windows, from keycloak/session-windows.json.

Dry run by default (exit 2 when something is to do, 0 when the realm is in shape). --apply writes
a rollback file first, then writes, then re-reads and fails unless the realm is in shape. It never
deletes a flow; --rollback FILE puts the bound values back.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import os
import shlex
import stat
import sys
import tempfile
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import quote, unquote

SCRIPT_DIR = Path(__file__).resolve().parent
SESSION_WINDOWS_FILE = SCRIPT_DIR / "keycloak" / "session-windows.json"

BROWSER_FLOW_BASE = "browser"
BROWSER_FLOW = "browser-admin-otp"
POST_BROKER_FLOW = "post-broker-admin-otp"
BROWSER_BLOCK = "Admin OTP (browser)"
POST_BROKER_BLOCK = "Admin OTP (post-broker)"
BROWSER_ROLE_CONDITION = "is-admin-browser"
BROWSER_CREDENTIAL_CONDITION = "otp-not-used-browser"
POST_BROKER_ROLE_CONDITION = "is-admin-post-broker"
ADMIN_ROLE = "Admin"
IDP_ALIAS = "discord"
TOTP_ACTION = "CONFIGURE_TOTP"
ROLE_PROVIDER = "conditional-user-role"
CREDENTIAL_PROVIDER = "conditional-credential"
OTP_PROVIDER = "auth-otp-form"
WINDOW_FIELDS = ("ssoSessionIdleTimeout", "ssoSessionMaxLifespan",
                 "ssoSessionIdleTimeoutRememberMe", "ssoSessionMaxLifespanRememberMe")
SMTP_FIELDS = ("host", "port", "from", "fromDisplayName", "auth", "user", "starttls", "ssl")
KEEP_STORED_VALUE = "**********"


def _load_mobile_provisioner():
    """Import scripts/provision-keycloak-mobile-client.py, whose kcadm wrapper this script reuses."""
    path = SCRIPT_DIR / "provision-keycloak-mobile-client.py"
    spec = importlib.util.spec_from_file_location("provision_keycloak_mobile_client", path)
    if spec is None or spec.loader is None:
        raise SystemExit(f"FATAL: cannot load the kcadm wrapper from {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


mobile = _load_mobile_provisioner()
KcadmError = mobile.KcadmError


class HardenKcadm(mobile.Kcadm):
    """The shared kcadm wrapper plus the two write shapes the flow endpoints need."""

    def write(self, verb: str, path: str, payload: dict | list, what: str) -> None:
        """Create or update `path`; names what is written and never prints the payload.

        The shared wrapper prints payloads under --dry-run, which would show the SMTP password this
        script can be handed through the environment.
        """
        if self.dry_run:
            print(f"  [dry-run] {verb} {path} - {what}")
            return
        self._run([verb, path, "-r", self.realm, "-f", "-"],
                  stdin=json.dumps(payload, indent=2, sort_keys=True))
        print(f"  {verb} {path} - {what}")

    def post(self, path: str, payload: dict, what: str) -> None:
        """Create under `path`; under --dry-run only prints."""
        self.write("create", path, payload, what)

    def put(self, path: str, payload: dict, what: str) -> None:
        """Replace without merging: the executions endpoint answers no GET of its own."""
        if self.dry_run:
            print(f"  [dry-run] update {path} - {what}")
            return
        self._run(["update", path, "-r", self.realm, "-n", "-f", "-"],
                  stdin=json.dumps(payload, indent=2, sort_keys=True))
        print(f"  update {path} - {what}")


@dataclass
class Change:
    """One planned write: the line shown in the plan and the call that makes it."""

    label: str
    apply: Callable[[], None]


@dataclass
class Plan:
    """The ordered changes of one step and the information lines printed with them."""

    step: str
    notes: list[str] = field(default_factory=list)
    changes: list[Change] = field(default_factory=list)


@dataclass(frozen=True)
class Block:
    """One Admin OTP sub-flow: where it sits and the realm-unique aliases of its conditions.

    `credential_alias` is set for the browser flow only: its built-in second-factor step already asks
    every user who has an OTP device, so the block must run only while no OTP was presented in this
    login, which is exactly the admin who has no device yet and has to set one up.
    """

    parent: str
    name: str
    role_alias: str
    credential_alias: str | None


def browser_block(forms_alias: str) -> Block:
    """The block in the copied browser flow's forms sub-flow."""
    return Block(forms_alias, BROWSER_BLOCK, BROWSER_ROLE_CONDITION, BROWSER_CREDENTIAL_CONDITION)


def post_broker_block() -> Block:
    """The block in the top-level post-broker flow, where nothing has asked for an OTP yet."""
    return Block(POST_BROKER_FLOW, POST_BROKER_BLOCK, POST_BROKER_ROLE_CONDITION, None)


def desired_conditions(block: Block, role: str) -> list[tuple[str, str, str, dict[str, str]]]:
    """The block's conditions in order: (provider, label, config alias, config)."""
    conditions = [(ROLE_PROVIDER, "Condition - User Role", block.role_alias,
                   {"condUserRole": role, "negate": "false"})]
    if block.credential_alias:
        conditions.append((CREDENTIAL_PROVIDER, "Condition - credential", block.credential_alias,
                           {"credentials": "otp", "included": "false"}))
    return conditions


def on_off(flag: object) -> str:
    """A switch as the word an operator reads; the value itself is never interpolated."""
    return "on" if flag else "off"


def load_windows(profile: str) -> dict[str, int]:
    """The SSO session windows of one profile in keycloak/session-windows.json."""
    try:
        data = json.loads(SESSION_WINDOWS_FILE.read_text(encoding="utf-8"))
        return {key: int(value) for key, value in data[profile].items()}
    except (OSError, ValueError, KeyError) as error:
        raise SystemExit(f"FATAL: cannot read the session windows ({profile}) from "
                         f"{SESSION_WINDOWS_FILE}: {error}")


def flows_by_alias(kc: HardenKcadm) -> dict[str, dict]:
    """Every authentication flow of the realm, by alias."""
    return {flow["alias"]: flow for flow in (kc.get("authentication/flows") or [])}


def executions_of(kc: HardenKcadm, flow_alias: str) -> list[dict]:
    """The executions and sub-flows of one flow, in order."""
    return kc.get(f"authentication/flows/{quote(flow_alias, safe='')}/executions") or []


def find_block(executions: list[dict], name: str) -> dict | None:
    """The named sub-flow among a flow's executions."""
    for execution in executions:
        if execution.get("authenticationFlow") and execution.get("displayName") == name:
            return execution
    return None


def find_provider(executions: list[dict], provider: str) -> dict | None:
    """The execution of one provider among a sub-flow's executions."""
    for execution in executions:
        if execution.get("providerId") == provider:
            return execution
    return None


def config_of(kc: HardenKcadm, execution: dict) -> dict[str, str]:
    """The configuration of an execution, or an empty dict when it has none."""
    config_id = execution.get("authenticationConfig")
    if not config_id:
        return {}
    config = kc.get(f"authentication/config/{quote(config_id, safe='')}") or {}
    return config.get("config") or {}


def siblings(executions: list[dict]) -> list[dict]:
    """The top-level entries of a flow's executions, without the children of its sub-flows."""
    return [execution for execution in executions if execution.get("level", 0) == 0]


def sink_to_end(kc: HardenKcadm, block: Block) -> None:
    """Move the block behind everything else in its parent.

    Keycloak puts a new entry in front. Behind the built-in second-factor step, the block's
    credential condition sees the OTP that step already asked for and stays out of the way; in
    front of it, an admin who has a device is asked twice.
    """
    for _ in range(50):
        entries = siblings(executions_of(kc, block.parent))
        names = [entry.get("displayName") for entry in entries]
        if block.name not in names or names[-1] == block.name:
            return
        found = entries[names.index(block.name)]
        kc.post(f"authentication/executions/{quote(found['id'], safe='')}/lower-priority", {},
                f"'{block.name}' one place later in '{block.parent}'")
        if kc.dry_run:
            return


def block_is_last(executions: list[dict], block: Block) -> bool:
    """Whether the block is the last entry of its parent."""
    names = [entry.get("displayName") for entry in siblings(executions)]
    return bool(names) and names[-1] == block.name


def block_problems(kc: HardenKcadm, block: Block, role: str) -> list[str]:
    """What is missing from an Admin OTP block; empty when it is in shape."""
    try:
        parent_executions = executions_of(kc, block.parent)
    except KcadmError:
        return [f"flow '{block.parent}' does not exist"]
    found = find_block(parent_executions, block.name)
    if found is None:
        return [f"'{block.parent}' has no sub-flow '{block.name}'"]
    problems: list[str] = []
    if not block_is_last(parent_executions, block):
        problems.append(f"sub-flow '{block.name}' is not the last entry of '{block.parent}': an "
                        f"admin with an OTP device would be asked for the code twice")
    if found.get("requirement") != "CONDITIONAL":
        problems.append(f"sub-flow '{block.name}' in '{block.parent}' is "
                        f"{found.get('requirement')}, not CONDITIONAL")
    inner = executions_of(kc, block.name)
    for provider, label, _alias, wanted in desired_conditions(block, role):
        condition = find_provider(inner, provider)
        if condition is None:
            problems.append(f"'{block.name}' has no '{label}'")
            continue
        if condition.get("requirement") != "REQUIRED":
            problems.append(f"'{label}' is {condition.get('requirement')}, not REQUIRED")
        live = config_of(kc, condition)
        if any(str(live.get(key)) != value for key, value in wanted.items()):
            problems.append(f"'{label}' is not configured as intended")
    otp = find_provider(inner, OTP_PROVIDER)
    if otp is None:
        problems.append(f"'{block.name}' has no 'OTP Form'")
    elif otp.get("requirement") != "REQUIRED":
        problems.append(f"the OTP Form is {otp.get('requirement')}, not REQUIRED")
    return problems


def set_requirement(kc: HardenKcadm, parent_alias: str, execution: dict, requirement: str) -> None:
    """Set the requirement of one execution of a flow."""
    kc.put(f"authentication/flows/{quote(parent_alias, safe='')}/executions",
           {"id": execution["id"], "requirement": requirement},
           f"'{execution.get('displayName')}' -> {requirement}")


def ensure_condition(kc: HardenKcadm, block: Block, provider: str, label: str, alias: str,
                     wanted: dict[str, str]) -> None:
    """Create one condition of the block, set it REQUIRED and give it its configuration."""
    condition = find_provider(executions_of(kc, block.name), provider)
    if condition is None:
        kc.post(f"authentication/flows/{quote(block.name, safe='')}/executions/execution",
                {"provider": provider}, f"execution '{label}'")
        condition = find_provider(executions_of(kc, block.name), provider)
    if condition is None:
        return
    if condition.get("requirement") != "REQUIRED":
        set_requirement(kc, block.name, condition, "REQUIRED")
    live = config_of(kc, condition)
    if any(str(live.get(key)) != value for key, value in wanted.items()):
        config = {"alias": alias, "config": wanted}
        if condition.get("authenticationConfig"):
            config_id = condition["authenticationConfig"]
            kc.put(f"authentication/config/{quote(config_id, safe='')}",
                   {"id": config_id, **config}, f"configuration of '{label}'")
        else:
            kc.post(f"authentication/executions/{quote(condition['id'], safe='')}/config",
                    config, f"configuration of '{label}'")


def ensure_block(kc: HardenKcadm, block: Block, role: str) -> None:
    """Create or repair an Admin OTP sub-flow, one missing piece at a time."""
    found = find_block(executions_of(kc, block.parent), block.name)
    if found is None:
        kc.post(f"authentication/flows/{quote(block.parent, safe='')}/executions/flow",
                {"alias": block.name, "type": "basic-flow",
                 "description": f"OTP for holders of the realm role {role}"},
                f"sub-flow '{block.name}' in '{block.parent}'")
        found = find_block(executions_of(kc, block.parent), block.name)
    if found is not None and found.get("requirement") != "CONDITIONAL":
        set_requirement(kc, block.parent, found, "CONDITIONAL")
    sink_to_end(kc, block)

    for provider, label, alias, wanted in desired_conditions(block, role):
        ensure_condition(kc, block, provider, label, alias, wanted)

    otp = find_provider(executions_of(kc, block.name), OTP_PROVIDER)
    if otp is None:
        kc.post(f"authentication/flows/{quote(block.name, safe='')}/executions/execution",
                {"provider": OTP_PROVIDER}, "execution 'OTP Form'")
        otp = find_provider(executions_of(kc, block.name), OTP_PROVIDER)
    if otp is not None and otp.get("requirement") != "REQUIRED":
        set_requirement(kc, block.name, otp, "REQUIRED")


def ensure_browser_copy(kc: HardenKcadm) -> None:
    """Copy the built-in browser flow once; the copy is never edited in place later."""
    if BROWSER_FLOW not in flows_by_alias(kc):
        kc.post(f"authentication/flows/{BROWSER_FLOW_BASE}/copy", {"newName": BROWSER_FLOW},
                f"copy of the built-in flow '{BROWSER_FLOW_BASE}'")


def ensure_post_broker_flow(kc: HardenKcadm) -> None:
    """Create the small top-level flow a brokered login runs after the identity provider."""
    if POST_BROKER_FLOW not in flows_by_alias(kc):
        kc.post("authentication/flows",
                {"alias": POST_BROKER_FLOW, "providerId": "basic-flow", "topLevel": True,
                 "builtIn": False,
                 "description": f"OTP for holders of the realm role {ADMIN_ROLE} after a brokered "
                                f"login"},
                f"top-level flow '{POST_BROKER_FLOW}'")


def forms_flow(kc: HardenKcadm) -> str | None:
    """The alias of the copied browser flow's forms sub-flow, where the block belongs."""
    for execution in executions_of(kc, BROWSER_FLOW):
        if (execution.get("authenticationFlow")
                and str(execution.get("displayName", "")).endswith("forms")):
            return execution["displayName"]
    return None


def required_action(kc: HardenKcadm, alias: str) -> dict | None:
    """One required action of the realm, or None when it is not registered."""
    try:
        return kc.get(f"authentication/required-actions/{quote(alias, safe='')}")
    except KcadmError:
        return None


def ensure_required_action(kc: HardenKcadm) -> None:
    """Register and enable 'Configure OTP': without it an admin with no OTP device cannot log in."""
    action = required_action(kc, TOTP_ACTION)
    if action is None:
        kc.post("authentication/register-required-action",
                {"providerId": TOTP_ACTION, "name": "Configure OTP"},
                f"required action {TOTP_ACTION}")
        action = required_action(kc, TOTP_ACTION)
    if action is not None and not action.get("enabled"):
        kc.write("update", f"authentication/required-actions/{TOTP_ACTION}", {"enabled": True},
                 f"enable {TOTP_ACTION}")


def plan_forgot_password(kc: HardenKcadm, args: argparse.Namespace) -> Plan:
    """Step 2: report the realm's sender; change the switch and the sender fields when asked."""
    plan = Plan("2 - Forgot password and the realm's SMTP sender")
    realm = kc.get_realm()
    smtp = realm.get("smtpServer") or {}
    password_set = bool(smtp.get("password"))
    plan.notes.append(
        f"forgot-password link {on_off(realm.get('resetPasswordAllowed'))}; smtpServer host="
        f"{smtp.get('host') or '<none>'} port={smtp.get('port') or '<default>'} from="
        f"{smtp.get('from') or '<none>'} auth={smtp.get('auth')} password "
        f"{'set' if password_set else 'NOT set'}")
    if realm.get("resetPasswordAllowed") and not (smtp.get("host") and smtp.get("from")):
        plan.notes.append("WARNING: the link is offered but the realm has no sender, so a reset "
                          "mail can never leave")
    plan.notes.append("The sender's own test is the console's Realm settings > Email > Test "
                      "connection; it mails the admin who clicks it, so it cannot run here")

    wanted = {"on": True, "off": False}.get(args.reset_password)
    if wanted is not None and realm.get("resetPasswordAllowed") != wanted:
        plan.changes.append(Change(
            f"~ forgot-password link: {on_off(realm.get('resetPasswordAllowed'))} -> {on_off(wanted)}",
            lambda: kc.write("update", f"realms/{kc.realm}", {"resetPasswordAllowed": wanted},
                             "Forgot password")))

    desired = {key: value for key, value in {
        "host": args.smtp_host, "port": args.smtp_port, "from": args.smtp_from,
        "user": args.smtp_user, "starttls": args.smtp_starttls, "ssl": args.smtp_ssl,
    }.items() if value is not None}
    password = os.environ.get(args.smtp_password_env, "") if args.smtp_password_env else ""
    if args.smtp_password_env and not password:
        raise SystemExit(f"FATAL: ${args.smtp_password_env} is empty or unset")
    if desired or password:
        merged = {key: smtp[key] for key in SMTP_FIELDS if key in smtp}
        merged.update({key: str(value) for key, value in desired.items()})
        if desired.get("user") or password:
            merged["auth"] = "true"
        if password:
            merged["password"] = password
        elif smtp.get("password"):
            merged["password"] = KEEP_STORED_VALUE
        if any(str(smtp.get(key)) != str(value) for key, value in merged.items()
               if key != "password") or password:
            shown = dict(desired)
            plan.changes.append(Change(
                f"~ smtpServer: {shown}{' and a new password' if password else ''}",
                lambda m=merged: kc.write("update", f"realms/{kc.realm}", {"smtpServer": m},
                                          "the realm's SMTP sender")))
    return plan


def plan_session_windows(kc: HardenKcadm, args: argparse.Namespace) -> Plan:
    """Step 12: the four SSO windows of the chosen profile, and the client overrides above them."""
    windows = load_windows(args.windows)
    plan = Plan(f"12 - SSO session windows ('{args.windows}' of session-windows.json)")
    realm = kc.get_realm()
    diff = {key: value for key, value in windows.items() if realm.get(key) != value}
    for key, value in diff.items():
        plan.changes.append(Change(f"~ {key}: {realm.get(key)} -> {value}", lambda: None))
    if diff:
        plan.changes.append(Change(
            "  (one partial update of the realm)",
            lambda d=dict(diff): kc.write("update", f"realms/{kc.realm}", d, "session windows")))
    max_allowed = windows["ssoSessionMaxLifespan"]
    idle_allowed = windows["ssoSessionIdleTimeout"]
    if args.windows != "active":
        plan.notes.append(f"NOTE: scripts/provision-keycloak-realm.py reads the 'active' profile and "
                          f"would plan to put the old windows back; make '{args.windows}' the "
                          f"'active' profile in keycloak/session-windows.json in the same change")
    try:
        clients = kc.get("clients", {"max": "1000"}) or []
    except KcadmError:
        clients = []
        plan.notes.append("the clients could not be read (no view-clients/manage-clients): the "
                          "client session overrides above the new windows were not checked")
    for client in clients:
        attrs = client.get("attributes") or {}
        over = []
        for attr, limit in (("client.session.max.lifespan", max_allowed),
                            ("client.session.idle.timeout", idle_allowed)):
            if attrs.get(attr) and int(attrs[attr]) > limit:
                over.append(f"{attr}={attrs[attr]} > {limit}")
        if over:
            plan.notes.append(f"REVIEW: client '{client.get('clientId')}' overrides above the realm "
                              f"({', '.join(over)}); run scripts/provision-keycloak-realm.py, "
                              f"which clamps the app and exchange clients")
    return plan


def plan_admin_otp(kc: HardenKcadm, args: argparse.Namespace) -> Plan:
    """Step 11: the Admin OTP block in the browser copy and in the post-broker flow, and the bindings."""
    role = args.admin_role
    plan = Plan(f"11 - OTP for holders of the realm role '{role}'")
    realm = kc.get_realm()
    flows = flows_by_alias(kc)

    action = required_action(kc, TOTP_ACTION)
    if action is None or not action.get("enabled"):
        plan.changes.append(Change(
            f"+ required action {TOTP_ACTION}: "
            f"{'not registered' if action is None else 'disabled'} -> enabled",
            lambda: ensure_required_action(kc)))
        plan.notes.append("Without the required action an admin who has no OTP device yet is "
                          "refused with 'credential setup required' instead of being asked to "
                          "set one up")

    if BROWSER_FLOW not in flows:
        plan.changes.append(Change(f"+ copy flow '{BROWSER_FLOW_BASE}' as '{BROWSER_FLOW}'",
                                   lambda: ensure_browser_copy(kc)))
        browser_problems = [f"the block '{BROWSER_BLOCK}' does not exist yet"]
    else:
        forms_alias = forms_flow(kc)
        browser_problems = (block_problems(kc, browser_block(forms_alias), role)
                            if forms_alias else [f"'{BROWSER_FLOW}' has no forms sub-flow"])
    for problem in browser_problems:
        plan.changes.append(Change(f"+ browser flow: {problem}", lambda: None))
    if browser_problems:
        plan.changes.append(Change(
            "  (build or repair the Admin OTP block in the browser copy)",
            lambda: ensure_block(kc, browser_block(forms_flow(kc) or ""), role)))
    if realm.get("browserFlow") != BROWSER_FLOW:
        plan.changes.append(Change(
            f"~ realm browserFlow: {realm.get('browserFlow')} -> {BROWSER_FLOW}",
            lambda: kc.write("update", f"realms/{kc.realm}", {"browserFlow": BROWSER_FLOW},
                             "bind the browser flow")))

    idp_path = f"identity-provider/instances/{quote(args.idp_alias, safe='')}"
    try:
        idp = kc.get(idp_path)
    except KcadmError:
        idp = None
    if idp is None:
        plan.notes.append(f"identity provider '{args.idp_alias}' not found: the post-broker half "
                          f"is skipped (a realm without Discord has no brokered login)")
    else:
        if POST_BROKER_FLOW not in flows:
            plan.changes.append(Change(f"+ top-level flow '{POST_BROKER_FLOW}'",
                                       lambda: ensure_post_broker_flow(kc)))
        pb_problems = (block_problems(kc, post_broker_block(), role) if POST_BROKER_FLOW in flows
                       else [f"the block '{POST_BROKER_BLOCK}' does not exist yet"])
        for problem in pb_problems:
            plan.changes.append(Change(f"+ post-broker flow: {problem}", lambda: None))
        if pb_problems:
            plan.changes.append(Change(
                "  (build or repair the Admin OTP block in the post-broker flow)",
                lambda: ensure_block(kc, post_broker_block(), role)))
        if idp.get("postBrokerLoginFlowAlias") != POST_BROKER_FLOW:
            plan.changes.append(Change(
                f"~ identity provider '{args.idp_alias}' postBrokerLoginFlowAlias: "
                f"{idp.get('postBrokerLoginFlowAlias') or '<none>'} -> {POST_BROKER_FLOW}",
                lambda: kc.write("update", idp_path,
                                 {"postBrokerLoginFlowAlias": POST_BROKER_FLOW},
                                 "bind the post-login flow")))
    plan.notes.append("Keep a second admin session open in another browser while this runs; "
                      "after the bind an admin account cannot authenticate to kcadm any more")
    return plan


def verify(kc: HardenKcadm, steps: set[str], args: argparse.Namespace) -> list[str]:
    """Re-read the realm and list everything that is not as the selected steps leave it."""
    problems: list[str] = []
    realm = kc.get_realm()
    if "2" in steps:
        wanted = {"on": True, "off": False}.get(args.reset_password)
        if wanted is not None and realm.get("resetPasswordAllowed") != wanted:
            problems.append(f"the forgot-password link is {on_off(realm.get('resetPasswordAllowed'))}")
    if "12" in steps:
        for key, value in load_windows(args.windows).items():
            if realm.get(key) != value:
                problems.append(f"{key} is {realm.get(key)}, expected {value}")
    if "11" in steps:
        action = required_action(kc, TOTP_ACTION)
        if action is None or not action.get("enabled"):
            problems.append(f"required action {TOTP_ACTION} is missing or disabled")
        if realm.get("browserFlow") != BROWSER_FLOW:
            problems.append(f"browserFlow is {realm.get('browserFlow')}")
        forms_alias = forms_flow(kc)
        if forms_alias is None:
            problems.append(f"'{BROWSER_FLOW}' has no forms sub-flow")
        else:
            problems += [f"browser flow: {p}"
                         for p in block_problems(kc, browser_block(forms_alias), args.admin_role)]
        try:
            idp = kc.get(f"identity-provider/instances/{quote(args.idp_alias, safe='')}")
        except KcadmError:
            idp = None
        if idp is not None:
            if idp.get("postBrokerLoginFlowAlias") != POST_BROKER_FLOW:
                problems.append(f"postBrokerLoginFlowAlias is {idp.get('postBrokerLoginFlowAlias')}")
            problems += [f"post-broker flow: {p}"
                         for p in block_problems(kc, post_broker_block(), args.admin_role)]
    return problems


def snapshot(kc: HardenKcadm, args: argparse.Namespace) -> dict:
    """The values this script can change, as they are now: the rollback basis."""
    realm = kc.get_realm()
    try:
        idp = kc.get(f"identity-provider/instances/{quote(args.idp_alias, safe='')}")
    except KcadmError:
        idp = None
    return {
        "realm": {key: realm.get(key) for key in
                  ("resetPasswordAllowed", "browserFlow", *WINDOW_FIELDS)},
        "identityProvider": None if idp is None else {
            "alias": args.idp_alias, "postBrokerLoginFlowAlias": idp.get("postBrokerLoginFlowAlias")},
    }


def plan_rollback(kc: HardenKcadm, backup: dict, args: argparse.Namespace) -> list[Plan]:
    """Put every value of the rollback file back; flows the script created stay, unbound."""
    plan = Plan("rollback - values from the rollback file")
    realm = kc.get_realm()
    wanted = {key: value for key, value in backup["realm"].items()
              if key != "smtpServer" and value is not None}
    diff = {key: value for key, value in wanted.items() if realm.get(key) != value}
    for key, value in diff.items():
        plan.changes.append(Change(f"~ {key}: {realm.get(key)} -> {value}", lambda: None))
    if diff:
        plan.changes.append(Change("  (one partial update of the realm)", lambda d=dict(diff): kc.write(
            "update", f"realms/{kc.realm}", d, "restore the realm values")))
    idp_backup = backup.get("identityProvider")
    if idp_backup:
        live = kc.get(f"identity-provider/instances/{quote(idp_backup['alias'], safe='')}")
        if live is not None and live.get("postBrokerLoginFlowAlias") != idp_backup["postBrokerLoginFlowAlias"]:
            plan.changes.append(Change(
                f"~ identity provider '{idp_backup['alias']}' postBrokerLoginFlowAlias: "
                f"{live.get('postBrokerLoginFlowAlias') or '<none>'} -> "
                f"{idp_backup['postBrokerLoginFlowAlias'] or '<none>'}",
                lambda: kc.write("update", f"identity-provider/instances/{quote(idp_backup['alias'], safe='')}",
                                 {"postBrokerLoginFlowAlias": idp_backup["postBrokerLoginFlowAlias"] or ""},
                                 "restore the post-login flow")))
    plan.notes.append(f"The flows '{BROWSER_FLOW}' and '{POST_BROKER_FLOW}' are left in the realm, "
                      f"unbound; delete them in the console when they are no longer wanted")
    plan.notes.append("The SMTP sender is not restored (its password is masked in the file)")
    return [plan]


def run(kc: HardenKcadm, args: argparse.Namespace) -> int:
    """Plan, print, and with --apply write; returns the process exit code."""
    steps = set(args.step) if args.step else {"2", "11", "12"}
    if "all" in steps:
        steps = {"2", "11", "12"}
    if args.rollback:
        backup = json.loads(Path(args.rollback).read_text(encoding="utf-8"))
        plans = plan_rollback(kc, backup, args)
    else:
        builders = {"2": plan_forgot_password, "11": plan_admin_otp, "12": plan_session_windows}
        plans = [builders[step](kc, args) for step in ("2", "12", "11") if step in steps]

    total = 0
    for plan in plans:
        print(f"\n[step {plan.step}]")
        for note in plan.notes:
            print(f"  {note}")
        for change in plan.changes:
            print(f"  {change.label}")
        total += sum(1 for change in plan.changes if not change.label.startswith("  "))
    if total == 0:
        print("\nNo changes: the selected steps are in shape.")
        return 0
    if not args.apply:
        print(f"\n[dry-run] {total} change(s) planned; nothing was written. Re-run with --apply.")
        return 2

    if not args.rollback:
        if Path(args.backup_file).exists():
            print(f"\nKeeping the existing rollback file {args.backup_file}: it holds the values "
                  f"from before the first run.")
        else:
            Path(args.backup_file).write_text(
                json.dumps(snapshot(kc, args), indent=2, sort_keys=True), encoding="utf-8")
            os.chmod(args.backup_file, stat.S_IRUSR | stat.S_IWUSR)
            print(f"\nRollback file written: {args.backup_file}")
    kc.dry_run = False
    print("\n[apply]")
    for plan in plans:
        for change in plan.changes:
            change.apply()

    if args.rollback:
        print("\n[done] rollback applied.")
        return 0
    print("\n[verify]")
    problems = verify(kc, steps, args)
    if problems:
        for problem in problems:
            print(f"  PROBLEM: {problem}")
        return 1
    print("  the selected steps are in shape")
    return 0


class FakeKeycloak:
    """An in-memory realm that answers the kcadm shapes this script emits, for --selftest."""

    def __init__(self) -> None:
        self.realm = {"realm": "iri", "resetPasswordAllowed": True, "browserFlow": "browser",
                      "ssoSessionIdleTimeout": 2592000, "ssoSessionMaxLifespan": 15552000,
                      "ssoSessionIdleTimeoutRememberMe": 2592000,
                      "ssoSessionMaxLifespanRememberMe": 15552000,
                      "smtpServer": {"host": "smtp.test", "from": "noreply@test", "password": KEEP_STORED_VALUE}}
        self.flows: dict[str, list[dict]] = {"browser": []}
        self.configs: dict[str, dict] = {}
        self.required_actions: dict[str, dict] = {}
        self.idp = {"alias": "discord", "postBrokerLoginFlowAlias": ""}
        self.clients = [{"clientId": "basetool-android",
                         "attributes": {"client.session.max.lifespan": "15552000"}}]
        self.counter = 0
        self.writes: list[str] = []

    def next_id(self) -> str:
        """A fresh id."""
        self.counter += 1
        return f"id-{self.counter}"

    def execution_by_id(self, execution_id: str) -> dict:
        """Find an execution in any flow."""
        for executions in self.flows.values():
            for execution in executions:
                if execution["id"] == execution_id:
                    return execution
        raise KcadmError(f"no execution {execution_id}")


class FakeKcadm(HardenKcadm):
    """HardenKcadm over a FakeKeycloak; counts writes so dry runs can be shown to write nothing."""

    def __init__(self, fake: FakeKeycloak, dry_run: bool):
        super().__init__([], "iri", dry_run)
        self.fake = fake

    def get_realm(self) -> dict:
        return json.loads(json.dumps(self.fake.realm))

    def get(self, path: str, query: dict[str, str] | None = None):
        fake = self.fake
        path = unquote(path)
        if path == "authentication/flows":
            return [{"alias": alias, "id": alias} for alias in fake.flows]
        if path.startswith("authentication/flows/") and path.endswith("/executions"):
            alias = path[len("authentication/flows/"):-len("/executions")]
            if alias not in fake.flows:
                raise KcadmError(f"no flow {alias}")
            return json.loads(json.dumps(fake.flows[alias]))
        if path.startswith("authentication/config/"):
            return fake.configs[path.rsplit("/", 1)[1]]
        if path.startswith("authentication/required-actions/"):
            alias = path.rsplit("/", 1)[1]
            if alias not in fake.required_actions:
                raise KcadmError(f"no required action {alias}")
            return dict(fake.required_actions[alias])
        if path == "clients":
            return fake.clients
        if path.startswith("identity-provider/instances/"):
            return dict(fake.idp)
        raise KcadmError(f"stub: unexpected get {path}")

    def write(self, verb: str, path: str, payload, what: str) -> None:
        path = unquote(path)
        if self.dry_run:
            print(f"  [dry-run] {verb} {path} - {what}")
            return
        fake = self.fake
        fake.writes.append(f"{verb} {path}")
        if verb == "update" and path == "realms/iri":
            fake.realm.update(payload)
        elif verb == "update" and path.startswith("identity-provider/instances/"):
            fake.idp.update(payload)
        elif verb == "create" and path == "authentication/flows/browser/copy":
            alias = payload["newName"]
            fake.flows[alias] = [{"id": fake.next_id(), "displayName": f"{alias} forms",
                                  "authenticationFlow": True, "requirement": "ALTERNATIVE"}]
            fake.flows[f"{alias} forms"] = [{"id": fake.next_id(), "displayName": "Username Password Form",
                                             "providerId": "auth-username-password-form",
                                             "requirement": "REQUIRED", "level": 0}]
        elif verb == "create" and path == "authentication/register-required-action":
            fake.required_actions[payload["providerId"]] = {
                "alias": payload["providerId"], "enabled": True}
        elif verb == "update" and path.startswith("authentication/required-actions/"):
            fake.required_actions[path.rsplit("/", 1)[1]].update(payload)
        elif verb == "create" and path.endswith("/lower-priority"):
            execution_id = path.split("/")[2]
            for entries in fake.flows.values():
                ids = [entry["id"] for entry in entries]
                if execution_id in ids and ids.index(execution_id) < len(ids) - 1:
                    index = ids.index(execution_id)
                    entries[index], entries[index + 1] = entries[index + 1], entries[index]
                    break
        elif verb == "create" and path == "authentication/flows":
            fake.flows[payload["alias"]] = []
        elif verb == "create" and path.endswith("/executions/flow"):
            parent = path[len("authentication/flows/"):-len("/executions/flow")]
            fake.flows[parent].insert(0, {"id": fake.next_id(), "displayName": payload["alias"],
                                          "authenticationFlow": True, "requirement": "DISABLED",
                                          "level": 0})
            fake.flows[payload["alias"]] = []
        elif verb == "create" and path.endswith("/executions/execution"):
            parent = path[len("authentication/flows/"):-len("/executions/execution")]
            fake.flows[parent].append({"id": fake.next_id(), "providerId": payload["provider"],
                                       "displayName": payload["provider"], "requirement": "DISABLED",
                                       "level": 1})
        elif verb == "create" and path.endswith("/config"):
            execution = fake.execution_by_id(path.split("/")[2])
            config_id = fake.next_id()
            fake.configs[config_id] = {"id": config_id, **payload}
            execution["authenticationConfig"] = config_id
        else:
            raise KcadmError(f"stub: unexpected write {verb} {path}")
        print(f"  {verb} {path} - {what}")

    def put(self, path: str, payload: dict, what: str) -> None:
        path = unquote(path)
        if self.dry_run:
            print(f"  [dry-run] update {path} - {what}")
            return
        self.fake.writes.append(f"put {path}")
        if path.startswith("authentication/config/"):
            self.fake.configs[path.rsplit("/", 1)[1]].update(payload)
        else:
            self.fake.execution_by_id(payload["id"])["requirement"] = payload["requirement"]
        print(f"  update {path} - {what}")


def selftest() -> int:
    """Run the plan, apply, idempotence and rollback paths against the in-memory realm."""
    failures: list[str] = []

    def check(condition: bool, message: str) -> None:
        print(f"  {'ok  ' if condition else 'FAIL'} {message}")
        if not condition:
            failures.append(message)

    def arguments(*extra: str) -> argparse.Namespace:
        return build_parser().parse_args(["--backup-file", str(backup_path), *extra])

    backup_path = Path(tempfile.mkdtemp()) / "before.json"

    print("dry run writes nothing and plans every step")
    fake = FakeKeycloak()
    code = run(FakeKcadm(fake, dry_run=True), arguments("--windows", "proposal", "--reset-password", "off"))
    check(code == 2, "a dry run with work to do exits 2")
    check(fake.writes == [], "a dry run sends no write")
    check(not backup_path.exists(), "a dry run writes no rollback file")

    print("apply builds the flows, binds them and verifies")
    code = run(FakeKcadm(fake, dry_run=True), arguments("--apply", "--windows", "proposal", "--reset-password", "off"))
    check(code == 0, "apply exits 0 after the verify")
    check(fake.realm["browserFlow"] == BROWSER_FLOW, "the browser flow is bound")
    check(fake.realm["ssoSessionMaxLifespan"] == 7776000, "the session maximum is the proposal's")
    check(fake.realm["resetPasswordAllowed"] is False, "Forgot password is off")
    check(fake.idp["postBrokerLoginFlowAlias"] == POST_BROKER_FLOW, "the Discord provider runs the post-broker flow")
    condition = find_provider(fake.flows[BROWSER_BLOCK], ROLE_PROVIDER)
    check(condition is not None and fake.configs[condition["authenticationConfig"]]["config"]["condUserRole"] == "Admin",
          "the condition names the Admin realm role")
    credential = find_provider(fake.flows[BROWSER_BLOCK], CREDENTIAL_PROVIDER)
    check(credential is not None and fake.configs[credential["authenticationConfig"]]["config"] ==
          {"credentials": "otp", "included": "false"},
          "the browser block runs only while no OTP was presented in this login")
    check(find_provider(fake.flows[POST_BROKER_BLOCK], CREDENTIAL_PROVIDER) is None,
          "the post-broker block has no such condition: nothing asked for an OTP before it")
    check(find_provider(fake.flows[BROWSER_BLOCK], OTP_PROVIDER)["requirement"] == "REQUIRED", "the OTP form is required")
    check(backup_path.exists() and json.loads(backup_path.read_text())["realm"]["browserFlow"] == "browser",
          "the rollback file holds the values from before")
    check(fake.required_actions.get(TOTP_ACTION, {}).get("enabled") is True,
          "Configure OTP is registered and enabled")

    print("a second run finds nothing to do")
    writes_before = len(fake.writes)
    code = run(FakeKcadm(fake, dry_run=True), arguments("--windows", "proposal", "--reset-password", "off"))
    check(code == 0 and len(fake.writes) == writes_before, "the second plan is empty and writes nothing")

    print("a broken block is repaired, not duplicated")
    fake.execution_by_id(find_provider(fake.flows[BROWSER_BLOCK], OTP_PROVIDER)["id"])["requirement"] = "DISABLED"
    code = run(FakeKcadm(fake, dry_run=True), arguments("--apply", "--windows", "proposal", "--reset-password", "off"))
    check(code == 0 and len(fake.flows[BROWSER_BLOCK]) == 3, "the OTP form is required again and nothing was added twice")

    print("a block that moved in front of the second-factor step is moved back")
    forms = fake.flows[f"{BROWSER_FLOW} forms"]
    check(forms[-1]["displayName"] == BROWSER_BLOCK, "the block starts behind the other entries")
    forms.insert(0, forms.pop())
    code = run(FakeKcadm(fake, dry_run=True), arguments("--windows", "proposal", "--reset-password", "off"))
    check(code == 2, "a dry run reports the misplaced block")
    code = run(FakeKcadm(fake, dry_run=True), arguments("--apply", "--windows", "proposal", "--reset-password", "off"))
    check(code == 0 and forms[-1]["displayName"] == BROWSER_BLOCK, "apply moves it back behind them")

    print("rollback puts the bound values back and deletes nothing")
    code = run(FakeKcadm(fake, dry_run=True), arguments("--apply", "--rollback", str(backup_path)))
    check(code == 0 and fake.realm["browserFlow"] == "browser", "the built-in browser flow is bound again")
    check(fake.realm["ssoSessionMaxLifespan"] == 15552000, "the session maximum is back")
    check(fake.idp["postBrokerLoginFlowAlias"] == "", "the post-login flow is unbound")
    check(BROWSER_FLOW in fake.flows and POST_BROKER_FLOW in fake.flows, "the created flows are left in place")

    print("the active windows are the provisioner's")
    spec = importlib.util.spec_from_file_location(
        "provision_keycloak_realm", SCRIPT_DIR / "provision-keycloak-realm.py")
    provisioner = importlib.util.module_from_spec(spec)
    sys.modules["provision_keycloak_realm"] = provisioner
    spec.loader.exec_module(provisioner)
    active = load_windows("active")
    check(provisioner.REALM_SETTINGS["ssoSessionIdleTimeout"] == active["ssoSessionIdleTimeout"]
          and provisioner.REALM_SETTINGS["ssoSessionMaxLifespan"] == active["ssoSessionMaxLifespan"],
          "the provisioner reads the same file")

    print("a missing Discord provider skips the post-broker half")
    fake = FakeKeycloak()
    kc = FakeKcadm(fake, dry_run=True)
    kc.get = lambda path, query=None, _orig=kc.get: (_ for _ in ()).throw(KcadmError("404")) \
        if path.startswith("identity-provider/") else _orig(path, query)
    code = run(kc, arguments("--apply", "--step", "11"))
    check(code == 0 and POST_BROKER_FLOW not in fake.flows, "no post-broker flow is created without the provider")

    print(f"\n{'selftest FAILED: ' + str(len(failures)) + ' check(s)' if failures else 'selftest passed'}")
    return 1 if failures else 0


def build_parser() -> argparse.ArgumentParser:
    """The command line."""
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n")[0])
    parser.add_argument("--realm", default="iri")
    parser.add_argument("--container", default="keycloak")
    parser.add_argument("--kcadm-command", help="override the kcadm invocation (a quoted command line)")
    parser.add_argument("--step", action="append", choices=["2", "11", "12", "all"],
                        help="a step to run; repeatable; default: all three")
    parser.add_argument("--apply", action="store_true", help="write; without it nothing is written")
    parser.add_argument("--backup-file", default="keycloak-hardening.before.json",
                        help="where --apply writes the rollback file (mode 0600)")
    parser.add_argument("--rollback", metavar="FILE", help="put the values of a rollback file back")
    parser.add_argument("--windows", choices=["active", "proposal"], default="active",
                        help="which profile of keycloak/session-windows.json step 12 applies")
    parser.add_argument("--reset-password", choices=["leave", "on", "off"], default="leave")
    parser.add_argument("--smtp-host")
    parser.add_argument("--smtp-port", type=int)
    parser.add_argument("--smtp-from")
    parser.add_argument("--smtp-user")
    parser.add_argument("--smtp-starttls", choices=["true", "false"])
    parser.add_argument("--smtp-ssl", choices=["true", "false"])
    parser.add_argument("--smtp-password-env", metavar="VAR",
                        help="name of an environment variable holding the SMTP password; never printed")
    parser.add_argument("--admin-role", default=ADMIN_ROLE)
    parser.add_argument("--idp-alias", default=IDP_ALIAS)
    parser.add_argument("--selftest", action="store_true", help="run the in-memory self-test and exit")
    return parser


def main() -> int:
    """Entry point."""
    args = build_parser().parse_args()
    if args.selftest:
        return selftest()
    prefix = (shlex.split(args.kcadm_command) if args.kcadm_command
              else ["docker", "exec", "-i", args.container, "/opt/keycloak/bin/kcadm.sh"])
    kc = HardenKcadm(prefix, args.realm, dry_run=not args.apply)
    try:
        return run(kc, args)
    except KcadmError as error:
        print(f"\nFAILED: {error}", file=sys.stderr)
        print("Nothing was deleted. Fix the cause and re-run: the script is idempotent; the "
              "rollback file (if --apply got that far) holds the values from before.",
              file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
