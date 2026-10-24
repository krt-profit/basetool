#!/usr/bin/env python3
"""Sanitize a Keycloak realm export so it can be committed as a reference.

Applies the stripping list documented in `docs/keycloak/README.md`. Prints counts and key names
only, never a secret.

Usage:
    python scripts/sanitize-realm-export.py RAW_EXPORT.json OUT.json
    python scripts/sanitize-realm-export.py --selftest

Exit codes: 0 written (or the self-test passed), 1 a guard pattern survived (nothing written) or
the self-test failed, 2 bad invocation.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any

DEPLOY_PLACEHOLDER = "__SET_AT_DEPLOY__"

REAL_HOSTS = ("profit-base.online",)
NEUTRAL_HOST = "basetool.example.invalid"

DROP_SECTIONS = (
    "users",
    "components",
    "keys",
    "authenticationFlows",
    "authenticatorConfig",
    "requiredActions",
    "clientScopeMappings",
    "groups",
    "federatedUsers",
)

BUILTIN_CLIENTS = frozenset(
    {"account", "account-console", "admin-cli", "broker", "realm-management", "security-admin-console"}
)

SECRET_KEYS = frozenset({"secret", "clientSecret", "password", "privateKey", "publicKey", "certificate"})

IDP_SECRET_KEYS = frozenset({"clientId", "clientSecret"})

GUARD_PATTERNS = (
    (re.compile(r"[A-Za-z0-9._%+-]+@(?!example\.invalid)[A-Za-z0-9.-]+\.[A-Za-z]{2,}"), "e-mail address"),
    (re.compile("|".join(re.escape(h) for h in REAL_HOSTS)), "real hostname"),
    (re.compile(r'"(?:secret|clientSecret|password|privateKey)"\s*:\s*"(?!__SET_AT_DEPLOY__|\*+")[^"]+'), "secret value"),
)


def scrub(node: Any, stats: dict[str, int]) -> Any:
    """Recursively replace secret values, drop UUID ids and neutralize public hostnames, keeping the structure.

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

    mappings = raw.get("scopeMappings")
    if isinstance(mappings, list):
        kept_mappings = [m for m in mappings if m.get("client") not in BUILTIN_CLIENTS]
        if len(kept_mappings) != len(mappings):
            stats["dropped:builtinClientScopeMappings"] = len(mappings) - len(kept_mappings)
        raw["scopeMappings"] = kept_mappings

    client_roles = (raw.get("roles") or {}).get("client")
    if isinstance(client_roles, dict):
        removed = [name for name in client_roles if name in BUILTIN_CLIENTS]
        for name in removed:
            client_roles.pop(name)
        if removed:
            stats["dropped:builtinClientRoles"] = len(removed)

    for provider in raw.get("identityProviders") or []:
        config = provider.get("config")
        if isinstance(config, dict):
            for key in ("clientId", "clientSecret"):
                if key in config:
                    config[key] = DEPLOY_PLACEHOLDER
                    stats["idpCredentials"] = stats.get("idpCredentials", 0) + 1

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
            findings.append(f"{len(hits)} possible {label}(s) survived sanitization")
    return findings


def selftest() -> int:
    """Sanitize a synthetic export and check what is kept and what is dropped.

    :return: process exit code, 0 when every check holds.
    """
    raw = {
        "realm": "basetool",
        "users": [{"username": "someone", "email": "someone@mail.test"}],
        "clientScopeMappings": {"account": [{"client": "account-console", "roles": ["manage-account"]}]},
        "scopeMappings": [
            {"clientScope": "offline_access", "roles": ["offline_access"]},
            {"client": "basetool-sc-extractor", "roles": ["KRT Member"]},
            {"client": "admin-cli", "roles": ["uma_authorization"]},
        ],
        "clients": [
            {"clientId": "account", "secret": "**********"},
            {"clientId": "backend-service", "secret": "a-real-looking-secret",
             "redirectUris": ["https://profit-base.online/*"]},
        ],
    }
    sanitized, _ = sanitize(raw)
    text = json.dumps(sanitized, indent=2, ensure_ascii=False)
    checks = {
        "scopeMappings are kept": sanitized.get("scopeMappings") == [
            {"clientScope": "offline_access", "roles": ["offline_access"]},
            {"client": "basetool-sc-extractor", "roles": ["KRT Member"]}],
        "a built-in client's scope mapping is dropped": "admin-cli" not in text,
        "clientScopeMappings are dropped": "clientScopeMappings" not in sanitized,
        "users are dropped": "users" not in sanitized,
        "a secret is replaced": sanitized["clients"][0].get("secret") == DEPLOY_PLACEHOLDER,
        "the real hostname is neutralized": "profit-base.online" not in text,
        "the guard passes the result": guard(text) == [],
    }
    for label, passed in checks.items():
        print(f"  {'ok  ' if passed else 'FAIL'} {label}")
    failed = [label for label, passed in checks.items() if not passed]
    print("selftest: FAILED" if failed else "selftest: passed")
    return 1 if failed else 0


def main() -> int:
    """Sanitize the export given on the command line and write it unless a guard pattern survives.

    :return: process exit code; non-zero means nothing was written.
    """
    if sys.argv[1:] == ["--selftest"]:
        return selftest()
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
