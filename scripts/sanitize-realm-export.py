#!/usr/bin/env python3
"""Sanitize a Keycloak realm export so it can be committed as a reference.

The production realm export carries operator secrets — SMTP credentials, identity-provider
secrets, service-account users. `docs/keycloak/README.md` documents what has to be stripped
before such an export may enter the repository; this script performs exactly that list, so the
step is repeatable and reviewable instead of a hand edit nobody can re-run.

Usage:
    python scripts/sanitize-realm-export.py RAW_EXPORT.json OUT.json

The script never prints a secret. It reports counts and key names only, and it refuses to write
the output if a guard pattern survives sanitization — a silent leak is the one failure mode that
would matter here, so the exit code is the contract, not the log.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any

# Placeholder the repository already uses for values that are set at deploy time.
DEPLOY_PLACEHOLDER = "__SET_AT_DEPLOY__"

# Public hostnames of the deployment. Not secret, but the reference is kept host-neutral so it
# cannot be mistaken for an importable dump.
REAL_HOSTS = ("profit-base.online", "iri-base.org")
NEUTRAL_HOST = "basetool.example.invalid"

# Top-level sections that are dropped wholesale.
DROP_SECTIONS = (
    "users",           # only the backend service account, still a user record
    "components",      # realm signing keys
    "keys",
    "authenticationFlows",
    "authenticatorConfig",
    "requiredActions",
    "scopeMappings",
    "clientScopeMappings",
    "groups",
    "federatedUsers",
)

# Keycloak's built-in clients carry no project information.
BUILTIN_CLIENTS = frozenset(
    {"account", "account-console", "admin-cli", "broker", "realm-management", "security-admin-console"}
)

# Keys whose value is replaced with the deploy placeholder wherever they appear.
SECRET_KEYS = frozenset({"secret", "clientSecret", "password", "privateKey", "publicKey", "certificate"})

# Keys carrying identity-provider credentials.
IDP_SECRET_KEYS = frozenset({"clientId", "clientSecret"})

# Anything matching these in the *output* means sanitization missed something.
GUARD_PATTERNS = (
    (re.compile(r"[A-Za-z0-9._%+-]+@(?!example\.invalid)[A-Za-z0-9.-]+\.[A-Za-z]{2,}"), "e-mail address"),
    (re.compile("|".join(re.escape(h) for h in REAL_HOSTS)), "real hostname"),
    (re.compile(r'"(?:secret|clientSecret|password|privateKey)"\s*:\s*"(?!__SET_AT_DEPLOY__|\*+")[^"]+'), "secret value"),
)


def scrub(node: Any, stats: dict[str, int]) -> Any:
    """Recursively replace secret values and neutralize public hostnames.

    Structure is preserved on purpose: the reference documents *shape* — which mappers exist, which
    scopes are default — so dropping a whole client because one of its fields is secret would
    destroy the very information the file exists for.

    :param node: the current JSON node.
    :param stats: counters, mutated in place, reported to the operator afterwards.
    :return: the sanitized node.
    """
    if isinstance(node, dict):
        out: dict[str, Any] = {}
        for key, value in node.items():
            if key in SECRET_KEYS and isinstance(value, str):
                out[key] = DEPLOY_PLACEHOLDER
                stats["secrets"] = stats.get("secrets", 0) + 1
            elif key == "id" or key.endswith("Id") and key not in IDP_SECRET_KEYS and _looks_like_uuid(value):
                stats["ids"] = stats.get("ids", 0) + 1
            else:
                out[key] = scrub(value, stats)
        return out
    if isinstance(node, list):
        return [scrub(item, stats) for item in node]
    if isinstance(node, str):
        cleaned = node
        for host in REAL_HOSTS:
            if host in cleaned:
                cleaned = cleaned.replace(host, NEUTRAL_HOST)
                stats["hosts"] = stats.get("hosts", 0) + 1
        return cleaned
    return node


def _looks_like_uuid(value: Any) -> bool:
    """Whether a value is a Keycloak-generated identifier rather than a meaningful name.

    :param value: the candidate value.
    :return: ``True`` for UUID-shaped strings.
    """
    return isinstance(value, str) and bool(
        re.fullmatch(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", value)
    )


def sanitize(raw: dict[str, Any]) -> tuple[dict[str, Any], dict[str, int]]:
    """Apply the documented sanitization list to a realm export.

    :param raw: the parsed raw export.
    :return: the sanitized realm and the operator-facing statistics.
    """
    stats: dict[str, int] = {}

    for section in DROP_SECTIONS:
        if section in raw:
            stats[f"dropped:{section}"] = len(raw[section]) if isinstance(raw[section], list) else 1
            raw.pop(section)

    clients = raw.get("clients")
    if isinstance(clients, list):
        kept = [c for c in clients if c.get("clientId") not in BUILTIN_CLIENTS]
        stats["dropped:builtinClients"] = len(clients) - len(kept)
        raw["clients"] = kept

    smtp = raw.get("smtpServer")
    if isinstance(smtp, dict) and smtp:
        raw["smtpServer"] = {
            "host": "smtp.example.invalid",
            "from": "noreply@example.invalid",
            "user": "__SMTP_USER__",
            "password": DEPLOY_PLACEHOLDER,
        }
        stats["smtp"] = 1

    sanitized = scrub(raw, stats)
    sanitized["_comment"] = (
        "Sanitized reference of the production realm — NOT importable. "
        "Generated by scripts/sanitize-realm-export.py; see docs/keycloak/README.md."
    )
    return sanitized, stats


def guard(text: str) -> list[str]:
    """Scan the serialized output for anything that must never be committed.

    :param text: the serialized sanitized realm.
    :return: human-readable descriptions of the findings, empty when clean.
    """
    findings: list[str] = []
    for pattern, label in GUARD_PATTERNS:
        hits = pattern.findall(text)
        if hits:
            # Deliberately reports the count and the label, never the matched text.
            findings.append(f"{len(hits)} possible {label}(s) survived sanitization")
    return findings


def main() -> int:
    """Entry point.

    :return: process exit code; non-zero means nothing was written.
    """
    if len(sys.argv) != 3:
        print(__doc__)
        return 2

    source, target = Path(sys.argv[1]), Path(sys.argv[2])
    raw = json.loads(source.read_text(encoding="utf-8"))
    sanitized, stats = sanitize(raw)
    text = json.dumps(sanitized, indent=2, ensure_ascii=False, sort_keys=False) + "\n"

    findings = guard(text)
    if findings:
        print("REFUSED — output not written:", file=sys.stderr)
        for finding in findings:
            print(f"  - {finding}", file=sys.stderr)
        print("Extend the sanitization list, then re-run.", file=sys.stderr)
        return 1

    target.write_text(text, encoding="utf-8")
    print(f"wrote {target}")
    for key in sorted(stats):
        print(f"  {key}: {stats[key]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
