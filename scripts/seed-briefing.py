#!/usr/bin/env python3
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only

"""Seed the briefing test data into the testing system through its API, or remove the seed accounts.

`seed` creates the Keycloak accounts (five presenter accounts, a numbered participant pool and two
service accounts), signs in as each of them through the testing-only `basetool-seed` client and
builds the org structure and the feature data the briefing walks through, all through the
Basetool's own API so audit, permissions and versioning apply. It writes the participant handout
and the presenter list as CSV into `--out`. A second run reuses the accounts and their passwords
and skips the data once the test org structure exists.

`purge` deletes every Keycloak account the seed created (marked by its e-mail domain). The
Basetool data itself goes with the wipe of `scripts/testing-reset.sh` (ADR-0279, REQ-OPS-050).

The client secret comes from `$SEED_CLIENT_SECRET`, never from the command line. Every URL that
names a production host is refused.
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import secrets
import ssl
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

PRODUCTION_HOST_MARKERS = ("profit-base.online", "krt-profit.de")
SEED_EMAIL_DOMAIN = "briefing.invalid"
SECRET_ENV = "SEED_CLIENT_SECRET"
STATE_FILE = "seed-state.json"
HANDOUT_FILE = "teilnehmer.csv"
PRESENTER_FILE = "praesentation.csv"
EXTRACT_FILE = "extractor-beispiel.json"

ROLE_MEMBER = "KRT Member"
ROLE_OFFICER = "Officer"
ROLE_ADMIN = "Admin"
ROLE_BANK_MANAGEMENT = "Bank Management"

STAFFELN = (("Test-Staffel Alpha", "TSA"), ("Test-Staffel Bravo", "TSB"),
            ("Test-Staffel Charlie", "TSC"))
SPECIAL_COMMAND = ("Test-Spezialkommando", "TSK")
BEREICHE = (("Test-Bereich Logistik", "TBL"), ("Test-Bereich Einsatz", "TBE"))
ORGANISATIONSLEITUNG = ("Test-Organisationsleitung", "TOL")

TRACK_MEMBER = "Mitglied"
TRACK_LOGISTICS = "Logistiker"
TRACK_MISSION = "Einsatz-Manager"
TRACK_OFFICER = "Offizier"
TRACK_LEAD = "Leitung"
PARTICIPANT_TRACKS = (TRACK_MEMBER, TRACK_LOGISTICS, TRACK_MEMBER, TRACK_MISSION, TRACK_MEMBER,
                      TRACK_OFFICER)


class SeedError(RuntimeError):
    """A request the seed cannot continue without failed."""


@dataclass
class Account:
    """One seeded Keycloak account and its place in the test org structure."""

    username: str
    first_name: str
    last_name: str
    realm_roles: list[str]
    kind: str
    staffel: str | None = None
    track: str = TRACK_MEMBER
    rank: str | None = None
    logistician: bool = False
    mission_manager: bool = False
    special_command: bool = False
    special_command_lead: bool = False
    password: str = ""
    keycloak_id: str = ""
    user_id: str = ""


def refuse_production(url: str, flag: str) -> None:
    """Exit when `url` names a production host."""
    host = urllib.parse.urlsplit(url).hostname or ""
    if any(host == marker or host.endswith("." + marker) for marker in PRODUCTION_HOST_MARKERS):
        raise SystemExit(f"{flag} {url} names a production host; the seed runs against the "
                         f"testing system only (ADR-0279)")


def require_loopback(url: str) -> None:
    """Exit unless `url` points at the loopback, the only place `--api-insecure` is allowed."""
    host = urllib.parse.urlsplit(url).hostname or ""
    if host not in ("127.0.0.1", "localhost", "::1"):
        raise SystemExit(f"--api-insecure is only allowed for a loopback --api-url (an SSH tunnel), "
                         f"not for {url}")


def plan_accounts(participants: int) -> list[Account]:
    """The full account list: service accounts, presenter accounts and the participant pool.

    The first three participants are the unit leads of Bravo and Charlie and the lead of the
    special command, who belongs to no Staffel (REQ-ORG-017); the rest cycle through the member,
    Logistiker, Einsatz-Manager and officer tracks and through the three Staffeln.
    """
    if participants < 3:
        raise SystemExit("--participants must be at least 3 (the lead track needs three accounts)")
    alpha, bravo, charlie = (short for _, short in STAFFELN)
    accounts = [
        Account("seed-admin", "Seed", "Admin", [ROLE_MEMBER, ROLE_ADMIN], "service"),
        Account("seed-bank", "Seed", "Bank", [ROLE_MEMBER, ROLE_BANK_MANAGEMENT], "service"),
        Account("demo-mitglied", "Demo", "Mitglied", [ROLE_MEMBER], "presenter", alpha),
        Account("demo-logistiker", "Demo", "Logistiker", [ROLE_MEMBER], "presenter", alpha,
                TRACK_LOGISTICS, logistician=True),
        Account("demo-offizier", "Demo", "Offizier", [ROLE_MEMBER, ROLE_OFFICER], "presenter",
                alpha, TRACK_OFFICER, rank="KOMMANDOLEITER", mission_manager=True),
        Account("demo-staffelleitung", "Demo", "Staffelleitung", [ROLE_MEMBER, ROLE_OFFICER],
                "presenter", alpha, TRACK_LEAD, rank="STAFFELLEITER"),
        Account("demo-bereichsleitung", "Demo", "Bereichsleitung", [ROLE_MEMBER, ROLE_OFFICER],
                "presenter", None, TRACK_LEAD),
    ]
    leads = [(bravo, "STAFFELLEITER", False), (charlie, "STAFFELLEITER", False),
             (None, None, True)]
    staffeln = (alpha, bravo, charlie)
    for index in range(1, participants + 1):
        username = f"teilnehmer-{index:02d}"
        if index <= len(leads):
            staffel, rank, sk_lead = leads[index - 1]
            roles = [ROLE_MEMBER, ROLE_OFFICER] if rank else [ROLE_MEMBER]
            accounts.append(Account(username, "Teilnehmer", f"{index:02d}", roles, "participant",
                                    staffel, TRACK_LEAD, rank=rank, special_command=sk_lead,
                                    special_command_lead=sk_lead))
            continue
        track = PARTICIPANT_TRACKS[(index - len(leads) - 1) % len(PARTICIPANT_TRACKS)]
        officer = track == TRACK_OFFICER
        accounts.append(Account(
            username, "Teilnehmer", f"{index:02d}",
            [ROLE_MEMBER, ROLE_OFFICER] if officer else [ROLE_MEMBER], "participant",
            staffeln[(index - len(leads) - 1) % len(staffeln)], track,
            rank="ENSIGN" if officer else None,
            logistician=track == TRACK_LOGISTICS,
            mission_manager=track in (TRACK_MISSION, TRACK_OFFICER),
            special_command=index % 5 == 0))
    return accounts


def new_password() -> str:
    """A random password that satisfies a default Keycloak password policy."""
    return f"Kt-{secrets.token_urlsafe(12)}-{secrets.randbelow(90) + 10}"


def resolve_out_dir(raw: str) -> Path:
    """The output directory, refused when it lies inside this repository (it holds passwords)."""
    out = Path(raw).resolve()
    repo = Path(__file__).resolve().parent.parent
    if out == repo or repo in out.parents:
        raise SystemExit(f"--out {out} lies inside the repository; the handout holds passwords, "
                         f"so write it somewhere outside")
    out.mkdir(parents=True, exist_ok=True)
    return out


def load_state(out: Path) -> dict[str, str]:
    """The passwords of an earlier run, keyed by username."""
    path = out / STATE_FILE
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def write_state(out: Path, accounts: list[Account]) -> None:
    """Persist every account's password so a second run can sign in again."""
    data = {a.username: a.password for a in accounts if a.password}
    (out / STATE_FILE).write_text(json.dumps(data, indent=2, sort_keys=True), encoding="utf-8",
                                  newline="\n")


def write_handouts(out: Path, accounts: list[Account], staffel_names: dict[str, str]) -> None:
    """Write the participant handout and the presenter list as semicolon CSV for a spreadsheet."""
    columns = ["Benutzername", "Passwort", "Staffel", "Track"]
    for name, kind in ((HANDOUT_FILE, "participant"), (PRESENTER_FILE, "presenter")):
        with (out / name).open("w", encoding="utf-8-sig", newline="") as handle:
            writer = csv.writer(handle, delimiter=";")
            writer.writerow(columns)
            for account in accounts:
                if account.kind != kind:
                    continue
                unit = staffel_names.get(account.staffel or "", "-")
                if not account.staffel and account.special_command:
                    unit = SPECIAL_COMMAND[0]
                writer.writerow([account.username, account.password, unit, account.track])


def extractor_sample(location: str, method: str, materials: list[str]) -> dict:
    """A desktop-extractor refinery export the Raffinerie demo imports through the pre-fill."""
    goods = [{"rowIndex": i, "rawMaterialName": name, "quality": quality, "inputQuantity": amount,
              "outputQuantity": amount // 2, "refine": True, "confidence": confidence,
              "sourceImage": "briefing_frame_1.png"}
             for i, (name, quality, amount, confidence)
             in enumerate(zip(materials, (712, 455), (240, 160), (0.97, 0.64)))]
    return {
        "schemaVersion": 1, "tool": "basetool-sc-extractor", "toolVersion": "0.0.0-briefing",
        "model": "briefing-sample", "generatedAt": utc(0), "clientLanguage": "de",
        "orders": [{
            "panelType": "SETUP", "quoted": True, "layoutConfidence": 0.95,
            "rawLocationName": location, "rawMethodName": method,
            "rawInManifestTotal": 400, "rawToRefineTotal": 400, "expenses": 2150.0,
            "durationMinutes": 75, "totalYieldScu": None,
            "sourceImages": [{"name": "briefing_frame_1.png", "width": 3840, "height": 2160,
                              "cropMode": "manual", "capturedAt": utc(0)}],
            "goods": goods,
        }],
    }


def utc(minutes: int) -> str:
    """An ISO-8601 UTC timestamp `minutes` from now."""
    moment = datetime.now(timezone.utc) + timedelta(minutes=minutes)
    return moment.replace(microsecond=0).strftime("%Y-%m-%dT%H:%M:%SZ")


class Http:
    """A small JSON client over urllib with one TLS context."""

    def __init__(self, ca_file: str | None, insecure: bool = False):
        if insecure:
            self.context = ssl.create_default_context()
            self.context.check_hostname = False
            self.context.verify_mode = ssl.CERT_NONE
        else:
            self.context = ssl.create_default_context(cafile=ca_file) if ca_file else None

    def request(self, method: str, url: str, *, token: str | None = None,
                body: Any = None, form: dict | None = None) -> tuple[int, Any]:
        """Send one request; return the status and the parsed JSON body (or None)."""
        headers = {"Accept": "application/json"}
        data = None
        if form is not None:
            data = urllib.parse.urlencode(form).encode()
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        elif body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        if token:
            headers["Authorization"] = f"Bearer {token}"
        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, context=self.context, timeout=60) as response:
                return response.status, _parse(response.read())
        except urllib.error.HTTPError as error:
            return error.code, _parse(error.read())


def _parse(raw: bytes) -> Any:
    if not raw:
        return None
    try:
        return json.loads(raw)
    except ValueError:
        return raw.decode("utf-8", "replace")[:500]


class Keycloak:
    """Account management and sign-in through the seed client."""

    def __init__(self, http: Http, base: str, realm: str, client_id: str, secret: str):
        self.http = http
        self.base = base.rstrip("/")
        self.realm = realm
        self.client_id = client_id
        self.secret = secret
        self._service: tuple[str, float] | None = None
        self._tokens: dict[str, tuple[str, float]] = {}

    def _grant(self, form: dict) -> tuple[str, float]:
        url = f"{self.base}/realms/{self.realm}/protocol/openid-connect/token"
        status, data = self.http.request("POST", url, form={
            **form, "client_id": self.client_id, "client_secret": self.secret})
        if status != 200:
            raise SeedError(f"token request for {form.get('username', 'the service account')} "
                            f"failed: HTTP {status} {data}")
        return data["access_token"], time.monotonic() + float(data.get("expires_in", 60)) - 20

    def service_token(self) -> str:
        """A service-account token for the Admin API, renewed shortly before it expires."""
        if not self._service or self._service[1] < time.monotonic():
            self._service = self._grant({"grant_type": "client_credentials"})
        return self._service[0]

    def user_token(self, account: Account) -> str:
        """A password-grant token for `account`, renewed shortly before it expires."""
        cached = self._tokens.get(account.username)
        if not cached or cached[1] < time.monotonic():
            cached = self._grant({"grant_type": "password", "username": account.username,
                                  "password": account.password, "scope": "openid"})
            self._tokens[account.username] = cached
        return cached[0]

    def forget_tokens(self) -> None:
        """Drop every cached user token, so the next call signs in with a new session."""
        self._tokens.clear()

    def admin(self, method: str, path: str, body: Any = None,
              ok: tuple[int, ...] = (200, 201, 204)) -> Any:
        """Call the realm's Admin API as the seed client's service account."""
        url = f"{self.base}/admin/realms/{self.realm}{path}"
        status, data = self.http.request(method, url, token=self.service_token(), body=body)
        if status not in ok:
            raise SeedError(f"Keycloak {method} {path} failed: HTTP {status} {data}")
        return data

    def find_user(self, username: str) -> dict | None:
        """The Keycloak user with exactly this username, or None."""
        query = urllib.parse.urlencode({"username": username, "exact": "true"})
        users = self.admin("GET", f"/users?{query}") or []
        return users[0] if users else None

    def ensure_user(self, account: Account) -> None:
        """Create the account, or adopt the existing seed account, and set its password and roles."""
        existing = self.find_user(account.username)
        if existing is None:
            self.admin("POST", "/users", {
                "username": account.username, "enabled": True, "emailVerified": True,
                "firstName": account.first_name, "lastName": account.last_name,
                "email": f"{account.username}@{SEED_EMAIL_DOMAIN}",
                "requiredActions": [],
            })
            existing = self.find_user(account.username)
            if existing is None:
                raise SeedError(f"Keycloak did not return the new user {account.username}")
        elif not is_seed_user(existing):
            raise SeedError(f"Keycloak user {account.username} exists but was not created by the "
                            f"seed; refusing to take it over")
        account.keycloak_id = existing["id"]
        self.admin("PUT", f"/users/{account.keycloak_id}/reset-password",
                   {"type": "password", "value": account.password, "temporary": False})
        roles = [self.admin("GET", f"/roles/{urllib.parse.quote(name)}")
                 for name in account.realm_roles]
        self.admin("POST", f"/users/{account.keycloak_id}/role-mappings/realm",
                   [{"id": r["id"], "name": r["name"]} for r in roles])

    def seed_users(self) -> list[dict]:
        """Every Keycloak user whose e-mail address lies in the seed's domain."""
        query = urllib.parse.urlencode({"email": f"@{SEED_EMAIL_DOMAIN}", "max": "1000"})
        return [u for u in self.admin("GET", f"/users?{query}") or [] if is_seed_user(u)]


def is_seed_user(user: dict) -> bool:
    """Whether a Keycloak user representation belongs to the seed (by its e-mail domain)."""
    return str(user.get("email") or "").lower().endswith("@" + SEED_EMAIL_DOMAIN)


class Api:
    """The Basetool API, called as one of the seeded accounts."""

    def __init__(self, http: Http, base: str, keycloak: Keycloak):
        self.http = http
        self.base = base.rstrip("/")
        self.keycloak = keycloak

    def call(self, account: Account, method: str, path: str, body: Any = None,
             ok: tuple[int, ...] = (200, 201, 204)) -> Any:
        """Send one request as `account`; raise SeedError on any status outside `ok`."""
        status, data = self.http.request(method, f"{self.base}/api/v1{path}",
                                         token=self.keycloak.user_token(account), body=body)
        if status not in ok:
            detail = data.get("detail") if isinstance(data, dict) else data
            raise SeedError(f"{account.username}: {method} {path} -> HTTP {status} {detail}")
        return data

    def get(self, account: Account, path: str) -> Any:
        """GET `path` as `account`."""
        return self.call(account, "GET", path)

    def post(self, account: Account, path: str, body: Any = None) -> Any:
        """POST `body` to `path` as `account`."""
        return self.call(account, "POST", path, body)

    def put(self, account: Account, path: str, body: Any = None) -> Any:
        """PUT `body` to `path` as `account`."""
        return self.call(account, "PUT", path, body)

    def patch(self, account: Account, path: str, body: Any = None) -> Any:
        """PATCH `body` to `path` as `account`."""
        return self.call(account, "PATCH", path, body)


def content(page: Any) -> list:
    """The rows of a list or a PageResponse."""
    if isinstance(page, dict):
        return page.get("content") or []
    return page or []


@dataclass
class Seeder:
    """Builds the org structure and the feature data, one domain after the other."""

    api: Api
    accounts: list[Account]
    out: Path
    staffeln: dict[str, str] = field(default_factory=dict)
    staffel_names: dict[str, str] = field(default_factory=dict)
    special_command: str = ""
    bereiche: list[str] = field(default_factory=list)
    failures: list[str] = field(default_factory=list)
    catalog: dict[str, Any] = field(default_factory=dict)
    missions: dict[str, dict] = field(default_factory=dict)
    orders: dict[str, dict] = field(default_factory=dict)
    stock: dict[str, list[dict]] = field(default_factory=dict)

    def by_name(self, username: str) -> Account:
        """The account with this username."""
        return next(a for a in self.accounts if a.username == username)

    def members_of(self, short: str) -> list[Account]:
        """Every presenter and participant account of the Staffel with this shorthand."""
        return [a for a in self.accounts if a.staffel == short and a.kind != "service"]

    @property
    def admin(self) -> Account:
        """The seed's admin account."""
        return self.by_name("seed-admin")

    def step(self, title: str, action) -> None:
        """Run one domain; record its failure and carry on with the next."""
        print(f"[{title}]")
        try:
            action()
        except SeedError as error:
            print(f"  FAILED: {error}", file=sys.stderr)
            self.failures.append(f"{title}: {error}")

    def activate(self) -> None:
        """Sign every account in once, approve the pending ones and accept the terms."""
        for account in self.accounts:
            me = self.api.call(account, "GET", "/users/me", ok=(200, 403))
            if isinstance(me, dict) and me.get("id"):
                account.user_id = me["id"]
        self.api.post(self.admin, "/terms/acceptance")
        pending = {r["username"]: r for r in content(
            self.api.get(self.admin, "/users/admin/registrations?status=PENDING"))}
        for account in self.accounts:
            registration = pending.get(account.username)
            if registration:
                self.api.post(self.admin,
                              f"/users/admin/registrations/{registration['id']}/approve",
                              {"version": registration.get("version")})
                print(f"  approved {account.username}")
        self.api.keycloak.forget_tokens()
        for account in self.accounts:
            self.api.post(account, "/terms/acceptance")
            if not account.user_id:
                account.user_id = self.api.get(account, "/users/me")["id"]

    def domains(self) -> dict:
        """The data domains in seeding order, keyed by the name `--only` accepts."""
        return {
            "memberships": self.memberships, "settings": self.settings, "hangar": self.hangar,
            "blueprints": self.blueprints, "lager": self.lager, "auftraege": self.job_orders,
            "einsaetze": self.missions_and_operation, "raffinerie": self.refinery,
            "boerse": self.material_exchange, "spenden": self.supply, "bank": self.bank,
            "anwendung": self.connected_app,
        }

    def data_present(self) -> bool:
        """Whether the test org structure already exists, so the data phase is skipped."""
        rows = content(self.api.get(self.admin, "/squadrons?size=200&includeInactive=true"))
        return any(r.get("shorthand") == STAFFELN[0][1] for r in rows)

    def org_structure(self) -> None:
        """Organisationsleitung, Bereiche, Staffeln and the special command, linked as a tree."""
        admin = self.admin
        ol = next((o for o in content(self.api.get(admin, "/org-units/organisationsleitung"))),
                  None)
        if ol is None:
            ol = self.api.post(admin, "/org-units/organisationsleitung",
                               {"name": ORGANISATIONSLEITUNG[0], "shorthand": ORGANISATIONSLEITUNG[1],
                                "description": "Testdaten für die Einweisung", "active": True})
        for name, short in BEREICHE:
            bereich = self.api.post(admin, "/org-units/bereiche", {
                "name": name, "shorthand": short, "department": "PROFIT", "active": True,
                "description": "Testdaten für die Einweisung", "parentOrgUnitId": ol["id"]})
            self.bereiche.append(bereich["id"])
        for index, (name, short) in enumerate(STAFFELN):
            squadron = self.api.post(admin, "/squadrons", {
                "name": name, "shorthand": short, "active": True, "isProfitEligible": True,
                "isPromotionEnabled": False, "description": "Testdaten für die Einweisung"})
            self.staffeln[short] = squadron["id"]
            self.staffel_names[short] = name
            self.api.patch(admin, f"/org-units/{squadron['id']}/parent", {
                "parentOrgUnitId": self.bereiche[min(index, 1)], "version": squadron["version"]})
        command = self.api.post(admin, "/special-commands", {
            "name": SPECIAL_COMMAND[0], "shorthand": SPECIAL_COMMAND[1], "active": True,
            "isProfitEligible": True, "description": "Testdaten für die Einweisung"})
        self.special_command = command["id"]
        self.api.patch(admin, f"/org-units/{command['id']}/parent",
                       {"parentOrgUnitId": self.bereiche[0], "version": command["version"]})
        print(f"  {len(self.staffeln)} Staffeln, 1 Spezialkommando, {len(self.bereiche)} Bereiche")

    def load_org_structure(self) -> None:
        """Read back the ids of an existing test org structure."""
        rows = content(self.api.get(self.admin, "/squadrons?size=200&includeInactive=true"))
        for name, short in STAFFELN:
            match = next((r for r in rows if r.get("shorthand") == short), None)
            if match:
                self.staffeln[short] = match["id"]
                self.staffel_names[short] = name
        commands = content(self.api.get(self.admin, "/special-commands?size=200"))
        match = next((c for c in commands if c.get("shorthand") == SPECIAL_COMMAND[1]), None)
        self.special_command = match["id"] if match else ""

    def memberships(self) -> None:
        """Staffel and special-command memberships, role flags, ranks and the leads."""
        admin = self.admin
        for squadron in self.staffeln.values():
            self.api.patch(admin, f"/squadrons/{squadron}/profit-eligible", {"eligible": True})
        if self.special_command:
            self.api.patch(admin, f"/special-commands/{self.special_command}/profit-eligible",
                           {"eligible": True})
        groups = {short: self.api.post(admin, f"/squadrons/{squadron}/kommando-groups",
                                       {"name": "Kommandogruppe Rot"})["id"]
                  for short, squadron in self.staffeln.items()}
        for account in self.accounts:
            in_command = account.special_command and bool(self.special_command)
            if not account.staffel and not in_command:
                continue
            delta: dict[str, Any] = {}
            if account.staffel:
                delta["staffeln"] = [{"squadronId": self.staffeln[account.staffel],
                                      "isLogistician": account.logistician,
                                      "isMissionManager": account.mission_manager}]
            if in_command:
                delta["specialCommands"] = [{"action": "ADD", "orgUnitId": self.special_command,
                                             "isLogistician": account.logistician,
                                             "isMissionManager": account.mission_manager}]
            result = self.api.patch(admin, f"/org-units/members/{account.user_id}/memberships",
                                    delta)
            memberships = {m["orgUnitId"]: m for m in (result or {}).get("memberships", [])}
            squadron_id = self.staffeln.get(account.staffel or "", "")
            if account.rank and squadron_id in memberships:
                rank: dict[str, Any] = {"role": account.rank,
                                        "version": memberships[squadron_id]["version"]}
                if account.rank in ("KOMMANDOLEITER", "STELLV_KOMMANDOLEITER", "ENSIGN"):
                    rank["kommandoGroupId"] = groups[account.staffel]
                self.api.put(admin, f"/squadrons/{squadron_id}/ranks/{account.user_id}", rank)
            if account.special_command_lead and self.special_command in memberships:
                self.api.patch(
                    admin, f"/special-commands/{self.special_command}/members/"
                           f"{account.user_id}/lead",
                    {"isLead": True, "version": memberships[self.special_command]["version"]})
        leader = self.by_name("demo-bereichsleitung")
        for bereich in self.bereiche:
            self.api.post(admin, f"/org-units/bereiche/{bereich}/members",
                          {"role": "LEITER", "userId": leader.user_id})
        self.api.keycloak.forget_tokens()
        print(f"  {sum(1 for a in self.accounts if a.staffel or a.special_command)} memberships, "
              f"every test unit profit-eligible")

    def settings(self) -> None:
        """Switch Bedarf & Spenden on, keep Ankauf off and open every test unit for donations."""
        admin = self.admin
        self.api.put(admin, "/supply/admin/settings", {"enabled": True})
        units = {u.get("orgUnitId"): u for u in content(self.api.get(admin, "/supply/admin/units"))}
        for org_unit in [*self.staffeln.values(), self.special_command]:
            unit = units.get(org_unit, {})
            self.api.put(admin, f"/supply/admin/units/{org_unit}", {
                "mayRaiseNeeds": True, "acceptsDonations": True, "version": unit.get("version")})
        status, purchasing = self.api.http.request(
            "GET", f"{self.api.base}/api/v1/purchasing/admin/settings",
            token=self.api.keycloak.user_token(admin))
        if status == 200 and isinstance(purchasing, dict) and purchasing.get("enabled"):
            print("  WARNING: Ankauf (purchasing.enabled) is on; switch it off in the admin area",
                  file=sys.stderr)
        print("  supply.enabled on, every test unit may raise needs and accept donations")

    def load_catalog(self) -> None:
        """Look up the catalogue rows the data needs, creating a minimal set where it is empty."""
        admin = self.admin
        materials = [m for m in self.all_pages(admin, "/materials")
                     if m.get("isVisible", True) and m.get("quantityType") == "SCU"]
        raw = [m for m in materials if m.get("type") == "RAW" or m.get("isManualRawMaterial")]
        refined = [m for m in materials if m not in raw]
        names = {m.get("name") for m in materials}
        for name, kind in (("Test-Erz Quantanium", "RAW"), ("Test-Erz Laranite", "RAW"),
                           ("Test-Agricium", "REFINED"), ("Test-Titan", "REFINED"),
                           ("Test-Gold", "REFINED")):
            pool = raw if kind == "RAW" else refined
            if len(pool) >= (2 if kind == "RAW" else 3) or name in names:
                continue
            body = {"name": name, "type": kind, "quantityType": "SCU"}
            if kind == "RAW":
                body["isManualRawMaterial"] = True
            pool.append(self.api.post(admin, "/materials", body))
        locations = content(self.api.get(admin, "/locations/lookup"))
        if not locations:
            locations = [self.api.post(admin, "/locations", {"name": "Test-Station Port Olisar",
                                                             "hidden": False})]
        refineries = content(self.api.get(admin, "/locations/refineries"))
        methods = content(self.api.get(admin, "/refining-methods"))
        if not methods:
            methods = [self.api.post(admin, "/refining-methods", {"name": "Test-Verfahren"})]
        ship_types = content(self.api.get(admin, "/ship-types?size=50"))
        frequency_types = content(self.api.get(admin, "/frequency-types"))
        for index, name in enumerate(("Kommando", "Staffel")):
            if len(frequency_types) < 2:
                frequency_types.append(self.api.post(admin, "/frequency-types", {
                    "name": name, "active": True, "sortIndex": 90 + index}))
        job_types = [j for j in content(self.api.get(admin, "/job-types"))
                     if j.get("archetype") == "CREW"]
        if not job_types:
            job_types = [self.api.post(admin, "/job-types",
                                       {"name": name, "archetype": "CREW", "active": True})
                         for name in ("Pilot", "Geschützbediener")]
        self.catalog = {"raw": raw, "refined": refined, "locations": locations,
                        "refineries": refineries, "methods": methods,
                        "ship_types": ship_types, "frequency_types": frequency_types,
                        "job_types": job_types}
        print(f"  {len(raw)} raw / {len(refined)} refined materials, {len(locations)} locations, "
              f"{len(ship_types)} ship types")

    def all_pages(self, account: Account, path: str) -> list:
        """Every row of a paged list endpoint, read in pages of 200."""
        rows: list = []
        page = 0
        while True:
            separator = "&" if "?" in path else "?"
            data = self.api.get(account, f"{path}{separator}page={page}&size=200")
            rows.extend(content(data))
            if not isinstance(data, dict) or page + 1 >= int(data.get("totalPages") or 1):
                return rows
            page += 1

    def material(self, index: int) -> dict:
        """A refined material, cycling through the catalogue."""
        pool = self.catalog["refined"] or self.catalog["raw"]
        return pool[index % len(pool)]

    def location(self, index: int) -> dict:
        """A location, cycling through the catalogue."""
        return self.catalog["locations"][index % len(self.catalog["locations"])]

    def hangar(self) -> None:
        """Ships in personal hangars and in each Staffel's fleet."""
        types = self.catalog["ship_types"]
        if not types:
            raise SeedError("the ship-type catalogue is empty; run the UEX sync first")
        count = 0
        for index, account in enumerate(a for a in self.accounts if a.staffel):
            for offset in range(2 if account.kind == "presenter" else 1):
                ship_type = types[(index * 3 + offset) % len(types)]
                self.api.post(account, "/hangar/ships", {
                    "shipTypeId": ship_type["id"], "insurance": "LTI" if offset == 0 else "6",
                    "fitted": offset == 0, "name": f"{account.first_name} {account.last_name} "
                                                   f"{offset + 1}",
                    "owningOrgUnitId": self.staffeln[account.staffel],
                    "locationId": self.location(index)["id"]})
                count += 1
        print(f"  {count} ships")

    def blueprints(self) -> None:
        """Unlocked blueprints for the presenters and a starter set for every participant."""
        products = content(self.api.get(self.admin, "/blueprints/products/search?q=&limit=40"))
        keys = [p.get("productKey") for p in products if p.get("productKey")]
        if not keys:
            print("  no blueprint catalogue; skipped")
            return
        for account in self.accounts:
            if account.kind == "service":
                continue
            take = keys[:12] if account.kind == "presenter" else keys[:4]
            self.api.post(account, "/personal-blueprints/batch", {"productKeys": take})
        print(f"  {len(keys[:12])} blueprints per presenter, {len(keys[:4])} per participant")

    def lager(self) -> None:
        """Stock per Staffel pool and personal rows at several qualities, one marked stolen."""
        logistician = self.by_name("demo-logistiker")
        for index, (short, squadron) in enumerate(self.staffeln.items()):
            owner = next((a for a in self.members_of(short) if a.logistician), self.admin)
            holder = {} if owner is not self.admin else {"userId": self.members_of(short)[0].user_id}
            rows = []
            for offset, quality in enumerate((320, 680, 910)):
                rows.append(self.api.post(owner, "/inventory", {
                    "materialId": self.material(index + offset)["id"], "quality": quality,
                    "amount": 40 + 25 * offset, "locationId": self.location(offset)["id"],
                    "owningOrgUnitId": squadron, "mergeStock": False, "stolen": False, **holder}))
            self.stock[short] = rows
        personal = []
        for index, account in enumerate(a for a in self.accounts
                                        if a.staffel and a.kind != "service"):
            personal.append(self.api.post(account, "/inventory", {
                "materialId": self.material(index)["id"], "quality": 500 + (index * 37) % 450,
                "amount": 12 + index % 9, "locationId": self.location(index)["id"],
                "owningOrgUnitId": self.staffeln[account.staffel], "personal": True,
                "mergeStock": False, "stolen": False}))
        self.stock["personal"] = personal
        status, _ = self.api.http.request(
            "POST", f"{self.api.base}/api/v1/inventory",
            token=self.api.keycloak.user_token(logistician),
            body={"materialId": self.material(4)["id"], "quality": 600, "amount": 8,
                  "locationId": self.location(1)["id"],
                  "owningOrgUnitId": self.staffeln[STAFFELN[0][1]], "stolen": True})
        stolen = "one stolen row" if status in (200, 201) else (
            f"no stolen row (HTTP {status}: stolen marking is off on this system)")
        print(f"  {sum(len(r) for r in self.stock.values())} rows, {stolen}")

    def job_orders(self) -> None:
        """Open, in-progress and done job orders with priorities, Bearbeiter and SK claims."""
        member = self.by_name("demo-mitglied")
        logistician = self.by_name("demo-logistiker")
        alpha = self.staffeln[STAFFELN[0][1]]
        specs = [
            ("open-alpha", alpha, [(0, 120, 650), (1, 60, None)], "OPEN", 1),
            ("progress-alpha", alpha, [(2, 80, None)], "IN_PROGRESS", 2),
            ("done-alpha", alpha, [(3, 30, None)], "COMPLETED", 3),
            ("open-sk", self.special_command, [(1, 200, 650), (4, 90, None)], "OPEN", 1),
            ("progress-bravo", self.staffeln[STAFFELN[1][1]], [(0, 45, None)], "IN_PROGRESS", 1),
        ]
        for key, responsible, materials, status, priority in specs:
            if not responsible:
                continue
            order = self.api.post(member, "/orders", {
                "responsibleOrgUnitId": responsible, "requestingOrgUnitId": alpha,
                "comment": "Testauftrag für die Einweisung",
                "materials": [{"materialId": self.material(m)["id"], "amount": amount,
                               **({"minQuality": q} if q else {})}
                              for m, amount, q in materials]})
            self.orders[key] = order
            if responsible == alpha:
                self.api.call(logistician, "PUT", f"/orders/{order['id']}/priority?priority={priority}")
                self.api.post(logistician, f"/orders/{order['id']}/assignees/{logistician.user_id}")
            if status != "OPEN":
                current = self.api.get(self.admin, f"/orders/{order['id']}")
                self.api.put(self.admin, f"/orders/{order['id']}/status",
                             {"status": status, "version": current["version"]})
        sk_order = self.orders.get("open-sk")
        if sk_order:
            for short in (STAFFELN[0][1], STAFFELN[1][1]):
                claimer = next((a for a in self.members_of(short) if a.logistician), logistician)
                self.api.post(claimer, f"/orders/{sk_order['id']}/claims", {
                    "amount": 50, "claimingOrgUnitId": self.staffeln[short],
                    "materialId": self.material(1)["id"], "qualityRequirement": "GOOD"})
        open_order = self.orders.get("open-alpha")
        rows = self.stock.get(STAFFELN[0][1], [])
        allocated = 0
        if open_order and len(rows) > 1:
            row = rows[1]
            self.api.post(logistician, f"/inventory/{row['id']}/allocation", {
                "field": "JOB_ORDER", "targetId": open_order["id"], "amount": 20,
                "version": row.get("version")})
            allocated = 1
        print(f"  {len(self.orders)} job orders, {allocated} Lager row assigned to an order")

    def missions_and_operation(self) -> None:
        """One mission per lifecycle state, internal and public, plus an operation with payouts."""
        officer = self.by_name("demo-offizier")
        alpha = self.staffeln[STAFFELN[0][1]]
        operation = self.api.post(officer, "/operations", {
            "name": "Operation Morgenrot", "status": "ACTIVE", "owningOrgUnitId": alpha,
            "description": "Testoperation mit abgeschlossenen Einsätzen"})
        specs = [
            ("Übung Alpha", False, "PLANNED", 60 * 24, None),
            ("Bergbau Bravo", True, "PLANNED", 60 * 48, None),
            ("Patrouille Charlie", False, "ACTIVE", -30, None),
            ("Konvoi Delta", True, "COMPLETED", -60 * 72, operation["id"]),
            ("Konvoi Echo", False, "COMPLETED", -60 * 48, operation["id"]),
        ]
        frequency_types = self.catalog["frequency_types"]
        job_types = self.catalog["job_types"]
        ship_types = self.catalog["ship_types"]
        crew_pool = [a for a in self.members_of(STAFFELN[0][1]) if a.username != officer.username]
        for name, internal, status, start, operation_id in specs:
            mission = self.api.post(officer, "/missions", {
                "name": name, "isInternal": internal, "owningOrgUnitId": alpha,
                "description": "Testeinsatz für die Einweisung.",
                "meetingPoint": self.location(0).get("name", "Treffpunkt"),
                "meetingTime": utc(start - 15), "plannedStartTime": utc(start),
                "plannedEndTime": utc(start + 120),
                **({"operationId": operation_id} if operation_id else {}),
                "objectives": [{"kind": "PRIMARY", "title": "Ziel erreichen und sichern"},
                               {"kind": "SECONDARY", "title": "Fracht einsammeln"},
                               {"kind": "NON_GOAL", "title": "Kein Kampf mit Unbeteiligten"}],
                "steps": [{"title": "Treffen am Treffpunkt"}, {"title": "Abflug in Formation"},
                          {"title": "Ziel anfliegen"}, {"title": "Rückflug und Abrechnung"}]})
            mid = mission["id"]
            for offset, frequency_type in enumerate(frequency_types[:2]):
                self.api.post(officer, f"/missions/{mid}/frequencies", {
                    "frequencyTypeId": frequency_type["id"], "value": 121.5 + offset})
            unit = None
            if ship_types:
                units = self.api.post(officer, f"/missions/{mid}/units", {
                    "name": "Einheit Rot", "shipTypeId": ship_types[0]["id"],
                    "responsibleUserId": officer.user_id, "frequency": 125.0})
                unit = units[0] if units else None
            for account in crew_pool[:4]:
                self.api.post(account, f"/missions/{mid}/join",
                              {"payoutPreference": "PAYOUT" if account.track != TRACK_LEAD
                               else "DONATE"})
            self.api.post(officer, f"/missions/{mid}/participants",
                          {"guestName": "Gastpilot Nova", "payoutPreference": "PAYOUT"})
            detail = self.api.get(officer, f"/missions/{mid}")
            participants = detail.get("participants") or []
            if unit and participants:
                registered = next((p for p in participants if p.get("user")), participants[0])
                self.api.post(officer, f"/missions/{mid}/units/{unit['id']}/crew", {
                    "participantId": registered["id"],
                    "jobTypeIds": [job_types[0]["id"]] if job_types else []})
            if status != "PLANNED":
                steps = detail.get("steps") or []
                version = detail.get("stepsVersion")
                for step in steps[:2]:
                    result = self.api.patch(officer, f"/missions/{mid}/steps/{step['id']}/done",
                                            {"done": True, "stepsVersion": version})
                    version = (version or 0) + 1 if not isinstance(result, dict) else \
                        result.get("stepsVersion", (version or 0) + 1)
                for target in ("ACTIVE", "COMPLETED")[: 1 if status == "ACTIVE" else 2]:
                    core = self.api.get(officer, f"/missions/{mid}")
                    self.api.patch(officer, f"/missions/{mid}/core", {
                        "name": core["name"], "description": core.get("description"),
                        "meetingPoint": core.get("meetingPoint"),
                        "calendarLink": core.get("calendarLink"),
                        "operationId": (core.get("operation") or {}).get("id"),
                        "status": target, "version": core["coreVersion"]})
            if status == "COMPLETED":
                self.record_times(officer, mid)
                for index, participant in enumerate(participants):
                    self.api.post(officer, f"/missions/{mid}/finance-entries", {
                        "missionId": mid, "participantId": participant["id"], "type": "INCOME",
                        "amount": 150000 + 25000 * index, "note": "Frachtverkauf"})
            self.missions[name] = mission
        rows = self.stock.get(STAFFELN[0][1], [])
        drill = self.missions.get("Übung Alpha")
        if drill and len(rows) > 2:
            self.api.post(self.by_name("demo-logistiker"), f"/inventory/{rows[2]['id']}/allocation", {
                "field": "MISSION", "targetId": drill["id"], "amount": 15,
                "version": rows[2].get("version")})
        summary = self.api.get(officer, f"/operations/{operation['id']}/payouts") or {}
        payouts = summary.get("payouts") or []
        if payouts:
            key = payouts[0].get("participantKey") or payouts[0].get("participantId")
            self.api.put(officer, f"/operations/{operation['id']}/payouts/paid-out",
                         {"participantKey": key, "paidOut": True})
        print(f"  {len(self.missions)} missions, 1 operation, {len(payouts)} payout rows "
              f"(1 marked paid)")

    def record_times(self, officer: Account, mission_id: str) -> None:
        """Stamp a finished mission's actual times and every participant's attendance on them,
        which the operation's payout split is computed from."""
        detail = self.api.get(officer, f"/missions/{mission_id}")
        start, end = detail["plannedStartTime"], detail["plannedEndTime"]
        self.api.patch(officer, f"/missions/{mission_id}/schedule", {
            "meetingTime": detail.get("meetingTime"), "plannedStartTime": start,
            "plannedEndTime": end, "actualStartTime": start, "actualEndTime": end,
            "version": detail["scheduleVersion"]})
        for participant in detail.get("participants") or []:
            self.api.put(officer, f"/missions/{mission_id}/participants/{participant['id']}", {
                "startTime": start, "endTime": end,
                "guestName": participant.get("guestName"), "comment": participant.get("comment"),
                "payoutPreference": participant.get("payoutPreference"),
                "orgUnitIds": [u["id"] for u in participant.get("orgUnits") or []],
                "desiredMissionJobTypeId": (participant.get("desiredMissionJobType") or {}).get("id"),
                "plannedMissionJobTypeId": (participant.get("plannedMissionJobType") or {}).get("id"),
                "version": participant["version"]})

    def refinery(self) -> None:
        """Refinery orders running, ready for pickup and stored, plus the extractor sample file."""
        logistician = self.by_name("demo-logistiker")
        method = self.catalog["methods"][0]
        raw = self.catalog["raw"]
        if not self.catalog["refineries"]:
            raise SeedError("the catalogue has no refinery location; run the UEX sync first")
        location = self.catalog["refineries"][0]
        alpha = self.staffeln[STAFFELN[0][1]]
        specs = [("running", 0, 600), ("ready", -240, 60), ("stored", -480, 60)]
        for key, started, duration in specs:
            order = self.api.post(logistician, "/refinery-orders", {
                "location": {"id": location["id"], "hidden": False, "homeLocation": False},
                "refiningMethod": {"id": method["id"]}, "owningOrgUnitId": alpha,
                "startedAt": utc(started), "durationMinutes": duration, "expenses": 1800.0,
                "goods": [{"inputMaterial": {"id": raw[i % len(raw)]["id"]},
                           "inputQuantity": 120 + 40 * i, "outputQuantity": 60 + 20 * i,
                           "quality": 640 + 90 * i} for i in range(2)]})
            if key == "stored":
                self.api.post(logistician, f"/refinery-orders/{order['id']}/store", {
                    "items": [{"materialId": raw[0]["id"], "locationId": location["id"],
                               "quality": 640, "amount": 60}]})
        sample = extractor_sample(location.get("name", "Refinery"), method.get("name", "Method"),
                                  [m.get("name", "Material") for m in raw[:2]])
        (self.out / EXTRACT_FILE).write_text(json.dumps(sample, indent=2, ensure_ascii=False),
                                             encoding="utf-8", newline="\n")
        print(f"  3 refinery orders, sample extract written to {self.out / EXTRACT_FILE}")

    def material_exchange(self) -> None:
        """Offers and requests from several accounts, one request with minimum quality and a
        delivery signal."""
        personal = self.stock.get("personal", [])
        sellers = [a for a in self.accounts if a.kind != "service" and a.staffel]
        offers = 0
        for account, row in zip(sellers, personal):
            if offers >= 4:
                break
            offer = self.api.post(account, "/material-exchange/offers", {
                "inventoryItemId": row["id"], "offeredAmount": max(1, row.get("amount", 2) // 2),
                "remark": "Abholung nach Absprache"})
            offers += 1
            if offers == 1:
                self.api.post(self.by_name("demo-logistiker"),
                              f"/material-exchange/offers/{offer['id']}/interest")
        member = self.by_name("demo-mitglied")
        wanted = self.api.post(member, "/material-exchange/requests", {
            "materialId": self.material(2)["id"], "requestedAmount": 32, "minQuality": 700,
            "remark": "Für einen Warenauftrag"})
        self.api.post(self.by_name("demo-logistiker"),
                      f"/material-exchange/requests/{wanted['id']}/interest")
        self.api.post(self.by_name("demo-offizier"), "/material-exchange/requests", {
            "materialId": self.material(0)["id"], "requestedAmount": 12,
            "remark": "Kleine Menge reicht"})
        print(f"  {offers} offers, 2 requests (one with minimum quality and a delivery signal)")

    def supply(self) -> None:
        """Open needs per Staffel, pledged and received donations."""
        lead = self.by_name("demo-staffelleitung")
        logistician = self.by_name("demo-logistiker")
        needs = []
        for index, (short, squadron) in enumerate(self.staffeln.items()):
            raiser = lead if short == STAFFELN[0][1] else next(
                (a for a in self.members_of(short) if a.track == TRACK_LEAD), lead)
            needs.append(self.api.post(raiser, "/supply/needs", {
                "orgUnitId": squadron, "materialId": self.material(index)["id"],
                "amount": 100 + 50 * index, "priority": ("HIGH", "NORMAL", "LOW")[index % 3]}))
        donors = [a for a in self.members_of(STAFFELN[0][1]) if a.track == TRACK_MEMBER]
        received = 0
        for index, donor in enumerate(donors[:4]):
            donation = self.api.post(donor, "/supply/donations", {
                "needId": needs[0]["id"], "materialId": needs[0]["material"]["id"],
                "amount": 10 + 5 * index, "quality": 600})
            if index < 2:
                self.api.post(logistician, f"/supply/donations/{donation['id']}/receive", {
                    "locationId": self.location(0)["id"], "receivedAmount": 10 + 5 * index,
                    "version": donation["version"]})
                received += 1
        self.api.post(logistician, "/supply/donations/recorded", {
            "receivingOrgUnitId": self.staffeln[STAFFELN[0][1]],
            "materialId": needs[0]["material"]["id"], "amount": 25, "quality": 700,
            "locationId": self.location(0)["id"], "donorUserId": self.by_name("demo-mitglied").user_id})
        print(f"  {len(needs)} needs, {min(4, len(donors))} pledges, {received + 1} received")

    def bank(self) -> None:
        """An account per test Staffel with balance, target and history, and two open requests."""
        bank = self.by_name("seed-bank")
        member = self.by_name("demo-mitglied")
        lead = self.by_name("demo-staffelleitung")
        holders = {h.get("userId"): h["id"] for h in content(self.api.get(bank, "/bank/holders"))}
        for account in (member, lead):
            if account.user_id not in holders:
                holders[account.user_id] = self.api.post(bank, "/bank/holders",
                                                         {"userId": account.user_id})["id"]
        alpha_account = None
        for index, (short, squadron) in enumerate(self.staffeln.items()):
            created = self.api.post(bank, "/bank/accounts", {
                "name": f"Konto {self.staffel_names[short]}", "type": "ORG_UNIT",
                "orgUnitId": squadron})
            self.api.post(bank, "/bank/grants", {
                "userId": bank.user_id, "accountId": created["id"], "canDeposit": True,
                "canWithdraw": True, "canTransfer": True})
            for amount in (1_200_000, 350_000, 800_000)[: 3 - index % 2]:
                self.api.post(bank, "/bank/deposits", {
                    "accountId": created["id"], "holderId": holders[lead.user_id],
                    "amount": amount * (index + 1), "note": "Einzahlung Testdaten"})
            current = self.api.get(bank, f"/bank/accounts/{created['id']}")
            account_row = current.get("account", current)
            self.api.put(bank, f"/bank/accounts/{created['id']}/balance-target", {
                "target": 5_000_000 * (index + 1), "version": account_row["version"]})
            holder = next((a for a in self.members_of(short) if a.rank == "STAFFELLEITER"), None)
            if holder:
                self.api.put(holder, f"/bank/org-units/accounts/{created['id']}/visibility/"
                                     f"all-members/true")
            if short == STAFFELN[0][1]:
                alpha_account = created
        if alpha_account:
            for kind, amount in (("DEPOSIT", 250_000), ("WITHDRAWAL", 120_000)):
                self.api.post(member, "/bank/org-units/requests", {
                    "sourceAccountId": alpha_account["id"], "type": kind, "amount": amount,
                    "justification": "Testantrag für die Einweisung",
                    **({"counterpartyUserId": member.user_id} if kind == "WITHDRAWAL" else {})})
        print(f"  {len(self.staffeln)} org-unit accounts, 1 open deposit and 1 open withdrawal "
              f"request on Alpha")

    def connected_app(self) -> None:
        """Register the test connected-application client the profile demo shows."""
        clients = content(self.api.get(self.admin, "/connected-apps/admin/clients"))
        if any(c.get("clientId") == "briefing-test-app" for c in clients):
            print("  test client already registered")
            return
        created = self.api.post(self.admin, "/connected-apps/admin/clients", {
            "clientId": "briefing-test-app", "displayName": "Briefing-Testanwendung",
            "capabilities": ["exchange.connect", "exchange.blueprints.read",
                             "exchange.blueprints.write", "exchange.stock.read"]})
        if created.get("status") != "ACTIVE":
            self.api.post(self.admin, f"/connected-apps/admin/clients/{created['id']}/activate")
        print("  test client registered (consent and journaled writes need one device-grant "
              "connection, see the runbook)")


def run_seed(args: argparse.Namespace) -> int:
    """Create or adopt the accounts, then build the data unless it already exists."""
    secret = os.environ.get(SECRET_ENV, "")
    if not secret:
        raise SystemExit(f"${SECRET_ENV} is empty; put the {args.client_id} client secret there")
    out = resolve_out_dir(args.out)
    http = Http(args.ca_file)
    keycloak = Keycloak(http, args.keycloak_url, args.realm, args.client_id, secret)
    api = Api(Http(args.ca_file, insecure=args.api_insecure), args.api_url, keycloak)
    accounts = plan_accounts(args.participants)
    known = load_state(out)
    for account in accounts:
        account.password = known.get(account.username) or new_password()
    print(f"[accounts] {len(accounts)} accounts in realm '{args.realm}'")
    for account in accounts:
        keycloak.ensure_user(account)
    write_state(out, accounts)
    seeder = Seeder(api, accounts, out)
    seeder.step("activation", seeder.activate)
    if seeder.failures:
        print("\nFAILED: the accounts could not be activated", file=sys.stderr)
        return 1
    domains = seeder.domains()
    only = [d.strip() for d in (args.only or "").split(",") if d.strip()]
    unknown = [d for d in only if d not in domains]
    if unknown:
        raise SystemExit(f"--only: unknown domain(s) {', '.join(unknown)}; choose from "
                         f"{', '.join(domains)}")
    present = seeder.data_present()
    if present and not only:
        print("[data] the test org structure exists; data phase skipped (use --only to repeat "
              "a domain)")
        seeder.step("org structure", seeder.load_org_structure)
    else:
        if present:
            seeder.step("org structure", seeder.load_org_structure)
        else:
            seeder.step("org structure", seeder.org_structure)
        if seeder.failures:
            print("\nFAILED: the org structure is incomplete; nothing else was seeded",
                  file=sys.stderr)
            return 1
        seeder.step("catalogue", seeder.load_catalog)
        if not seeder.catalog:
            print("\nFAILED: the catalogue lookup failed; nothing else was seeded", file=sys.stderr)
            return 1
        for name, action in domains.items():
            if not only or name in only:
                seeder.step(name, action)
    write_handouts(out, accounts, seeder.staffel_names)
    print(f"\nHandout: {out / HANDOUT_FILE}\nPresenters: {out / PRESENTER_FILE}")
    if seeder.failures:
        print(f"\n{len(seeder.failures)} domain(s) failed:", file=sys.stderr)
        for failure in seeder.failures:
            print(f"  - {failure}", file=sys.stderr)
        return 1
    return 0


def run_purge(args: argparse.Namespace) -> int:
    """Delete every Keycloak account in the seed's e-mail domain."""
    secret = os.environ.get(SECRET_ENV, "")
    if not secret:
        raise SystemExit(f"${SECRET_ENV} is empty; put the {args.client_id} client secret there")
    keycloak = Keycloak(Http(args.ca_file), args.keycloak_url, args.realm, args.client_id, secret)
    users = keycloak.seed_users()
    for user in users:
        keycloak.admin("DELETE", f"/users/{user['id']}")
        print(f"  deleted {user['username']}")
    print(f"{len(users)} seed account(s) deleted")
    return 0


def selftest() -> int:
    """Check the offline parts: account plan, production guard, handout and extractor sample."""
    failures: list[str] = []

    def check(condition: bool, label: str) -> None:
        print(f"  {'ok  ' if condition else 'FAIL'} {label}")
        if not condition:
            failures.append(label)

    accounts = plan_accounts(20)
    names = [a.username for a in accounts]
    check(len(names) == len(set(names)), "usernames are unique")
    check(sum(a.kind == "participant" for a in accounts) == 20, "20 participants planned")
    check({a.username for a in accounts if a.kind == "presenter"} == {
        "demo-mitglied", "demo-logistiker", "demo-offizier", "demo-staffelleitung",
        "demo-bereichsleitung"}, "the presenter accounts match the run of show")
    check(next(a for a in accounts if a.username == "demo-staffelleitung").rank == "STAFFELLEITER",
          "demo-staffelleitung leads Alpha")
    tracks = {a.track for a in accounts if a.kind == "participant"}
    check(tracks == {TRACK_MEMBER, TRACK_LOGISTICS, TRACK_MISSION, TRACK_OFFICER, TRACK_LEAD},
          "every hands-on track has participants")
    check({a.staffel for a in accounts if a.kind == "participant" and a.staffel}
          == {s for _, s in STAFFELN}, "participants are spread over all three Staffeln")
    check(sum(a.special_command_lead for a in accounts) == 1, "exactly one SK lead")
    check(all(a.staffel is None for a in accounts if a.special_command_lead),
          "the SK lead belongs to no Staffel")
    check(all(ROLE_OFFICER in a.realm_roles for a in accounts if a.rank),
          "every ranked account holds the Officer role")
    check(not any(ROLE_ADMIN in a.realm_roles for a in accounts if a.kind != "service"),
          "no presenter or participant is an admin")
    for url in ("https://profit-base.online", "https://auth.profit-base.online/auth",
                "https://krt-profit.de"):
        try:
            refuse_production(url, "--api-url")
            check(False, f"refuses {url}")
        except SystemExit:
            check(True, f"refuses {url}")
    try:
        refuse_production("https://testing.example.org", "--api-url")
        check(True, "accepts a testing host")
    except SystemExit:
        check(False, "accepts a testing host")
    try:
        require_loopback("https://127.0.0.1:11299")
        check(True, "--api-insecure accepts a loopback tunnel")
    except SystemExit:
        check(False, "--api-insecure accepts a loopback tunnel")
    try:
        require_loopback("https://testing.example.org")
        check(False, "--api-insecure refuses a remote URL")
    except SystemExit:
        check(True, "--api-insecure refuses a remote URL")
    try:
        plan_accounts(2)
        check(False, "refuses fewer than three participants")
    except SystemExit:
        check(True, "refuses fewer than three participants")
    try:
        resolve_out_dir(str(Path(__file__).resolve().parent / "seed-out"))
        check(False, "refuses an output directory inside the repository")
    except SystemExit:
        check(True, "refuses an output directory inside the repository")
    password = new_password()
    check(len(password) >= 16 and any(c.isdigit() for c in password), "passwords are long")
    sample = extractor_sample("Hub", "Method", ["A", "B"])
    check(len(sample["orders"][0]["goods"]) == 2 and sample["schemaVersion"] == 1,
          "the extractor sample has two goods rows")
    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp)
        for account in accounts:
            account.password = "pw-" + account.username
        write_state(out, accounts)
        check(load_state(out)["teilnehmer-01"] == "pw-teilnehmer-01", "state round-trips")
        write_handouts(out, accounts, {"TSA": "Test-Staffel Alpha"})
        rows = (out / HANDOUT_FILE).read_text(encoding="utf-8-sig").splitlines()
        check(len(rows) == 21 and rows[0].startswith("Benutzername;Passwort"),
              "the handout lists every participant")
        check("seed-admin" not in (out / HANDOUT_FILE).read_text(encoding="utf-8-sig")
              + (out / PRESENTER_FILE).read_text(encoding="utf-8-sig"),
              "service accounts stay out of the handouts")
    print(f"\n{'FAILED' if failures else 'OK'}: {len(failures)} failure(s)")
    return 1 if failures else 0


def main() -> int:
    """Parse the command line and run `seed`, `purge` or the self-test."""
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--selftest", action="store_true", help="run the offline self-test")
    sub = parser.add_subparsers(dest="command")
    for name, help_text in (("seed", "create the accounts and the briefing data"),
                            ("purge", "delete every seed account from Keycloak")):
        command = sub.add_parser(name, help=help_text)
        command.add_argument("--keycloak-url", required=True,
                             help="Keycloak base URL including the context path, e.g. "
                                  "https://testing.example/auth")
        command.add_argument("--realm", default="iri", help="realm (default: iri)")
        command.add_argument("--client-id", default="basetool-seed",
                             help="seed client id (default: basetool-seed); its secret comes "
                                  f"from ${SECRET_ENV}")
        command.add_argument("--ca-file", help="CA bundle for a self-signed TLS endpoint")
        if name == "seed":
            command.add_argument("--api-url", required=True,
                                 help="backend base URL without /api/v1, e.g. https://testing.example")
            command.add_argument("--participants", type=int, default=20,
                                 help="size of the participant pool (default: 20)")
            command.add_argument("--out", required=True,
                                 help="directory outside the repository for the handout, the "
                                      "presenter list, the extractor sample and the saved passwords")
            command.add_argument("--api-insecure", action="store_true",
                                 help="skip TLS verification for --api-url only; allowed only for "
                                      "a loopback URL, i.e. an SSH tunnel to the testing backend")
            command.add_argument("--only",
                                 help="comma-separated data domains to run again on an existing "
                                      "org structure, e.g. --only raffinerie,bank")
    args = parser.parse_args()
    if args.selftest:
        return selftest()
    if not args.command:
        parser.error("choose seed or purge, or pass --selftest")
    refuse_production(args.keycloak_url, "--keycloak-url")
    if args.command == "seed":
        refuse_production(args.api_url, "--api-url")
        if args.api_insecure:
            require_loopback(args.api_url)
        return run_seed(args)
    return run_purge(args)


if __name__ == "__main__":
    sys.exit(main())
