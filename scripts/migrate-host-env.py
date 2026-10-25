#!/usr/bin/env python3
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only

"""Rewrites the host-local files of one host from the old layout to the current one.

Used once by scripts/migrate-host-layout.sh. In an environment file it renames the keys, maps the
path values to the current host layout and renames the Keycloak realm in URLs and realm-named keys.
In a realm export it renames the realm, its default role and the realm URLs. Everything else,
comments and blank lines included, stays byte for byte. It never prints a value: reports carry file
names, counts and key names only.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import stat
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

EXIT_PROBLEM = 3

OLD_PREFIX = "IRI_"
NEW_PREFIX = "BASETOOL_"
VERSION_KEY_OLD = "IRI_BASETOOL_VERSION"
VERSION_KEY_NEW = "BASETOOL_VERSION"
RETIRED_SOURCE_KEY = "IRI_MONITORING_DIR"
RETIRED_DEFAULT = "/var/iri/monitoring"
RETIRED_KEYS = {
    "BASETOOL_MONITORING_DIR": "split into BASETOOL_MONITORING_DATA_DIR and BASETOOL_MONITORING_CONFIG_DIR",
}

OLD_REALM = "iri"
NEW_REALM = "basetool"

OLD_BRAND = "IRIDIUM Basetool"
NEW_BRAND = "Profit Basetool"

_FRONT = r"(?<![\w.])"
_BACK = r"(?![\w-])"

PATH_RULES: list[tuple[re.Pattern[str], str]] = [
    (re.compile(_FRONT + r"/var/iri/code/\.env\.backup-"), "/etc/basetool/basetool.env.backup-"),
    (re.compile(_FRONT + r"/var/iri/code/\.env" + _BACK), "/etc/basetool/basetool.env"),
    (re.compile(_FRONT + r"/var/iri/code/env\.d" + _BACK), "/etc/basetool/env.d"),
    (re.compile(_FRONT + r"/var/iri/code/realm-export\.json" + _BACK),
     "/etc/basetool/realm-export.json"),
    (re.compile(_FRONT + r"/var/iri/code" + _BACK), "/opt/basetool"),
    (re.compile(_FRONT + r"/var/iri/secrets" + _BACK), "/etc/basetool/secrets"),
    (re.compile(_FRONT + r"/var/iri/monitoring/secrets" + _BACK),
     "/etc/basetool/monitoring/secrets"),
    (re.compile(_FRONT + r"/var/iri/monitoring/certs" + _BACK), "/etc/basetool/monitoring/certs"),
    (re.compile(_FRONT + r"/var/iri/monitoring/data" + _BACK), "/var/lib/basetool/monitoring"),
    (re.compile(_FRONT + r"/var/iri/monitoring/textfile" + _BACK), "/var/lib/basetool/textfile"),
    (re.compile(_FRONT + r"/var/iri/(db-backend|db-keycloak|redis|backup)" + _BACK),
     r"/var/lib/basetool/\1"),
    (re.compile(_FRONT + r"/var/iri/(backend|frontend|ingest|keycloak)/log" + _BACK),
     r"/var/log/basetool/\1"),
    (re.compile(_FRONT + r"/var/lib/iri/restic-cache" + _BACK), "/var/cache/basetool/restic"),
    (re.compile(_FRONT + r"/var/lib/iri" + _BACK), "/var/lib/basetool/deploy"),
    (re.compile(_FRONT + r"/etc/iri/ssg-tailoring\.xml" + _BACK), "/etc/basetool/ssg-tailoring.xml"),
    (re.compile(_FRONT + r"/etc/iri" + _BACK), "/etc/basetool/deploy"),
    (re.compile(_FRONT + r"/var/log/iri-"), "/var/log/basetool/"),
    (re.compile(_FRONT + r"/hostlog/iri-"), "/hostlog/basetool/"),
    (re.compile(_FRONT + r"/var/lock/iri-([\w.-]+?)\.lock" + _BACK), r"/run/lock/basetool-\1.lock"),
    (re.compile(_FRONT + r"/tmp/iri-"), "/tmp/basetool-"),
    (re.compile(_FRONT + r"/home/iri" + _BACK), "/home/basetool"),
    (re.compile(_FRONT + r"/etc/systemd/system/iri-"), "/etc/systemd/system/basetool-"),
    (re.compile(_FRONT + r"/etc/logrotate\.d/iri-"), "/etc/logrotate.d/basetool-"),
    (re.compile(_FRONT + r"/etc/tmpfiles\.d/iri-locks\.conf" + _BACK),
     "/etc/tmpfiles.d/basetool-locks.conf"),
]

REALM_URL_RULE = (re.compile(r"/realms/" + OLD_REALM + r"(?![\w-])"), "/realms/" + NEW_REALM)
ROLE_RULE = (re.compile(r"(?<![\w-])default-roles-" + OLD_REALM + r"(?![\w-])"),
             "default-roles-" + NEW_REALM)
ADMIN_URL_RULE = (re.compile(r"/admin/" + OLD_REALM + r"(?=[/\"'\s]|$)"), "/admin/" + NEW_REALM)
REALM_FIELD_RULE = (re.compile(r'("realm"\s*:\s*)"' + OLD_REALM + '"'), r'\1"' + NEW_REALM + '"')
UNIT_NAME_RULE = (re.compile(r"(?<![\w.-])iri-([A-Za-z0-9][\w.-]*?)\.(service|timer|target|path|socket)(?![\w-])"),
                  r"basetool-\1.\2")
UNIT_VERSION_RULE = (re.compile(r"(?<![A-Za-z0-9_])" + re.escape(VERSION_KEY_OLD) + r"(?![A-Za-z0-9_])"),
                     VERSION_KEY_NEW)
UNIT_ENV_RULE = (re.compile(r"(?<![A-Za-z0-9_])IRI_(?=[A-Z0-9_])"), NEW_PREFIX)
BRAND_RULE = (re.compile(re.escape(OLD_BRAND)), NEW_BRAND)
RESIDUAL_REALM = re.compile(r"(?<![A-Za-z])" + OLD_REALM + r"(?![A-Za-z])", re.IGNORECASE)

LEFTOVER = re.compile(
    _FRONT + r"/(?:var/iri|var/lib/iri|etc/iri|home/iri|var/log/iri|var/lock/iri|tmp/iri"
    r"|hostlog/iri|etc/systemd/system/iri|etc/logrotate\.d/iri|etc/tmpfiles\.d/iri)(?![a-z])")

ASSIGNMENT = re.compile(
    r"^(?P<lead>[ \t]*)(?P<export>export[ \t]+)?(?P<key>[A-Za-z_][A-Za-z0-9_]*)"
    r"(?P<eq>[ \t]*=[ \t]*)(?P<value>.*?)(?P<eol>\r?\n?)$", re.DOTALL)

REFERENCE_VERSION = re.compile(r"(\$\{?)" + re.escape(VERSION_KEY_OLD) + r"(?![A-Za-z0-9_])")
REFERENCE = re.compile(r"(\$\{?)" + re.escape(OLD_PREFIX))

REALM_KEY = re.compile(r"(?:^|_)REALM$")

XATTR_PREFIXES = ("system.posix_acl", "user.")


class OwnershipError(RuntimeError):
    """The owner of a file could not be kept, so the file must not be replaced."""


@dataclass
class Report:
    """What one rewrite did, expressed as counts and key names only."""

    keys_renamed: int = 0
    values_path_mapped: int = 0
    values_realm_mapped: int = 0
    unmapped_keys: list[str] = field(default_factory=list)
    collisions: list[str] = field(default_factory=list)
    retired_keys: list[str] = field(default_factory=list)
    realm_names: int = 0
    role_names: int = 0
    realm_urls: int = 0
    dropped: int = 0
    unit_names: int = 0
    env_names: int = 0
    unmapped_lines: list[int] = field(default_factory=list)
    brand_labels: int = 0
    residual: int = 0
    invalid_json: bool = False

    @property
    def problems(self) -> bool:
        """True when the file must not be written."""
        return bool(self.unmapped_keys or self.collisions or self.retired_keys or self.invalid_json
                    or self.unmapped_lines)


def rename_key(key: str) -> str:
    """The current name of an environment key; keys without the old prefix are returned as is."""
    if key == VERSION_KEY_OLD:
        return VERSION_KEY_NEW
    if key.startswith(OLD_PREFIX):
        return NEW_PREFIX + key[len(OLD_PREFIX):]
    return key


def map_paths(value: str) -> tuple[str, bool]:
    """Maps every old host path in `value` to the current layout; returns the value and whether it changed."""
    original = value
    for pattern, replacement in PATH_RULES:
        value = pattern.sub(replacement, value)
    return value, value != original


def map_realm(value: str) -> tuple[str, bool]:
    """Maps the realm in URLs and the default role name; returns the value and whether it changed."""
    original = value
    for pattern, replacement in (REALM_URL_RULE, ROLE_RULE):
        value = pattern.sub(replacement, value)
    return value, value != original


def map_references(value: str) -> tuple[str, bool]:
    """Renames `${IRI_X}` style references inside a value."""
    original = value
    value = REFERENCE_VERSION.sub(r"\g<1>" + VERSION_KEY_NEW, value)
    value = REFERENCE.sub(r"\g<1>" + NEW_PREFIX, value)
    return value, value != original


def _unquoted(value: str) -> str:
    """The value without one surrounding pair of quotes and without surrounding blanks."""
    stripped = value.strip()
    if len(stripped) >= 2 and stripped[0] == stripped[-1] and stripped[0] in "\"'":
        return stripped[1:-1]
    return stripped


def _split_lines(text: str) -> list[str]:
    """Splits on line feeds only, keeping them, so a stray form feed never splits a value."""
    return re.findall(r"[^\n]*\n|[^\n]+", text)


def rewrite_env_text(text: str) -> tuple[str, Report]:
    """Rewrites the text of an environment file; comments and blank lines are left untouched."""
    report = Report()
    parsed: list[tuple[str, re.Match[str] | None]] = []
    existing_keys: set[str] = set()
    for line in _split_lines(text):
        match = None if line.lstrip().startswith("#") else ASSIGNMENT.match(line)
        parsed.append((line, match))
        if match:
            existing_keys.add(match.group("key"))

    seen_new: set[str] = set()
    out: list[str] = []
    for line, match in parsed:
        if not match:
            out.append(line)
            continue
        key = match.group("key")
        new_key = rename_key(key)
        if key == RETIRED_SOURCE_KEY:
            if _unquoted(match.group("value")) == RETIRED_DEFAULT:
                report.dropped += 1
                continue
            report.retired_keys.append(new_key)
        if new_key != key:
            report.keys_renamed += 1
            if new_key in existing_keys or new_key in seen_new:
                report.collisions.append(new_key)
            seen_new.add(new_key)
        new_value, _ = map_references(match.group("value"))
        new_value, mapped = map_paths(new_value)
        new_value, realm_mapped = map_realm(new_value)
        if REALM_KEY.search(new_key) and _unquoted(new_value) == OLD_REALM:
            new_value = new_value.replace(OLD_REALM, NEW_REALM, 1)
            realm_mapped = True
        if mapped:
            report.values_path_mapped += 1
        if realm_mapped:
            report.values_realm_mapped += 1
        if LEFTOVER.search(new_value):
            report.unmapped_keys.append(new_key)
        out.append(f"{match.group('lead')}{match.group('export') or ''}{new_key}"
                   f"{match.group('eq')}{new_value}{match.group('eol')}")
    return "".join(out), report


def rewrite_realm_export_text(text: str) -> tuple[str, Report]:
    """Renames the realm, the default role, the realm URLs and the brand in a realm export; ids stay."""
    report = Report()
    try:
        json.loads(text)
    except ValueError:
        report.invalid_json = True
        return text, report
    text, report.realm_names = REALM_FIELD_RULE[0].subn(REALM_FIELD_RULE[1], text)
    text, report.role_names = ROLE_RULE[0].subn(ROLE_RULE[1], text)
    text, urls = REALM_URL_RULE[0].subn(REALM_URL_RULE[1], text)
    text, admin_urls = ADMIN_URL_RULE[0].subn(ADMIN_URL_RULE[1], text)
    report.realm_urls = urls + admin_urls
    text, report.brand_labels = BRAND_RULE[0].subn(BRAND_RULE[1], text)
    report.residual = len(RESIDUAL_REALM.findall(text))
    try:
        json.loads(text)
    except ValueError:
        report.invalid_json = True
    return text, report


def rewrite_unit_text(text: str) -> tuple[str, Report]:
    """Rewrites a systemd drop-in: host paths, unit names and IRI_ names; comment lines stay."""
    report = Report()
    out: list[str] = []
    for number, line in enumerate(_split_lines(text), start=1):
        if line.lstrip().startswith(("#", ";")):
            out.append(line)
            continue
        new, mapped = map_paths(line)
        new, realm_mapped = map_realm(new)
        new, units = UNIT_NAME_RULE[0].subn(UNIT_NAME_RULE[1], new)
        new, versions = UNIT_VERSION_RULE[0].subn(UNIT_VERSION_RULE[1], new)
        new, names = UNIT_ENV_RULE[0].subn(UNIT_ENV_RULE[1], new)
        report.values_path_mapped += int(mapped)
        report.values_realm_mapped += int(realm_mapped)
        report.unit_names += units
        report.env_names += versions + names
        if LEFTOVER.search(new) or "BASETOOL_MONITORING_DIR" in new:
            report.unmapped_lines.append(number)
        out.append(new)
    return "".join(out), report


def _copy_metadata(source: Path, target: str, info: os.stat_result) -> None:
    """Gives `target` the access control of `source`: ACL and user attributes, mode and owner."""
    if hasattr(os, "listxattr"):
        for name in os.listxattr(source):
            if name.startswith(XATTR_PREFIXES):
                os.setxattr(target, name, os.getxattr(source, name))
    os.chmod(target, stat.S_IMODE(info.st_mode))
    if hasattr(os, "chown"):
        try:
            os.chown(target, info.st_uid, info.st_gid)
        except PermissionError as error:
            if (info.st_uid, info.st_gid) != (os.geteuid(), os.getegid()):
                raise OwnershipError(f"cannot keep the owner of {source.name}") from error


def _write_atomically(path: Path, data: bytes) -> None:
    """Replaces `path` with `data` through a temporary file that carries the original's mode, owner and ACL."""
    info = path.stat()
    handle, tmp_name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".migrate", dir=path.parent)
    try:
        with os.fdopen(handle, "wb") as tmp:
            tmp.write(data)
            tmp.flush()
            os.fsync(tmp.fileno())
        _copy_metadata(path, tmp_name, info)
        os.replace(tmp_name, path)
    except BaseException:
        if os.path.exists(tmp_name):
            os.unlink(tmp_name)
        raise


def process(path: Path, kind: str, check: bool) -> int:
    """Rewrites one file in place (or only reports under `check`); returns the exit code."""
    if not path.is_file():
        print(f"{path}: not a file", file=sys.stderr)
        return 1
    text = path.read_bytes().decode("utf-8", errors="surrogateescape")
    if kind == "env":
        new_text, report = rewrite_env_text(text)
        summary = (f"keys renamed {report.keys_renamed}, values path-mapped "
                   f"{report.values_path_mapped}, values realm-mapped {report.values_realm_mapped}, lines dropped {report.dropped}")
    elif kind == "unit":
        new_text, report = rewrite_unit_text(text)
        summary = (f"paths mapped {report.values_path_mapped}, unit names {report.unit_names}, "
                   f"environment names {report.env_names}")
    else:
        new_text, report = rewrite_realm_export_text(text)
        summary = (f"realm names {report.realm_names}, default roles {report.role_names}, realm "
                   f"URLs {report.realm_urls}, brand labels "
                   f"{report.brand_labels}, old-name occurrences left {report.residual}")
    changed = new_text != text
    state = "would change" if (changed and check) else ("changed" if changed else "unchanged")
    print(f"{path}: {state}; {summary}")
    for key in report.unmapped_keys:
        print(f"  unmapped old path in the value of {key}: decide by hand")
    for key in report.collisions:
        print(f"  collision: {key} is defined twice after the rename")
    for number in report.unmapped_lines:
        print(f"  unmapped old name or path on line {number}: decide by hand")
    for key in report.retired_keys:
        print(f"  {key} no longer exists: {RETIRED_KEYS[key]}")
    if report.invalid_json:
        print("  not valid JSON before or after the rewrite")
    if report.problems:
        print(f"{path}: nothing written", file=sys.stderr)
        return EXIT_PROBLEM
    if changed and not check:
        try:
            _write_atomically(path, new_text.encode("utf-8", errors="surrogateescape"))
        except OwnershipError as error:
            print(f"{path}: {error}; nothing written", file=sys.stderr)
            return EXIT_PROBLEM
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("kind", choices=("env", "realm-export", "unit"),
                        help="env: a KEY=value file; realm-export: a Keycloak realm export")
    parser.add_argument("files", nargs="+", type=Path)
    parser.add_argument("--check", action="store_true", help="report only, write nothing")
    args = parser.parse_args(argv)
    worst = 0
    for path in args.files:
        worst = max(worst, process(path, args.kind, args.check))
    return worst


if __name__ == "__main__":
    sys.exit(main())
