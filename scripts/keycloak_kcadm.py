#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only

"""The kcadm wrapper the Keycloak realm scripts share."""

from __future__ import annotations

import json
import shlex
import subprocess


class KcadmError(RuntimeError):
    """A kcadm invocation failed; carries the command and Keycloak's own message."""


class Kcadm:
    """Thin wrapper around `kcadm.sh`, invoked through the configured command prefix.

    The default prefix runs the CLI inside the Keycloak container with the operator's existing
    `kcadm.sh config credentials`.
    """

    def __init__(self, prefix: list[str], realm: str, dry_run: bool):
        self.prefix = prefix
        self.realm = realm
        self.dry_run = dry_run

    def _run(self, args: list[str], stdin: str | None = None) -> str:
        command = self.prefix + args
        if stdin is None:
            completed = subprocess.run(
                command, stdin=subprocess.DEVNULL, capture_output=True, text=True, check=False
            )
        else:
            completed = subprocess.run(
                command, input=stdin, capture_output=True, text=True, check=False
            )
        if completed.returncode != 0:
            raise KcadmError(
                f"{shlex.join(command)}\n"
                f"  exit {completed.returncode}: {(completed.stderr or completed.stdout).strip()}"
            )
        return completed.stdout

    def get(self, path: str, query: dict[str, str] | None = None):
        """Read `path` under the realm and parse the JSON body; reads run even under --dry-run."""
        args = ["get", path, "-r", self.realm]
        for key, value in (query or {}).items():
            args += ["-q", f"{key}={value}"]
        raw = self._run(args).strip()
        return json.loads(raw) if raw else None

    def get_realm(self) -> dict:
        """Read the realm representation itself, which lives above the realm-scoped paths."""
        raw = self._run(["get", f"realms/{self.realm}"]).strip()
        return json.loads(raw) if raw else {}

    def write(self, verb: str, path: str, payload: dict | list, what: str) -> None:
        """Create or update `path` with `payload`; under --dry-run only prints what it would send."""
        body = json.dumps(payload, indent=2, sort_keys=True)
        if self.dry_run:
            print(f"  [dry-run] {verb} {path} — {what}")
            print("".join(f"      {line}\n" for line in body.splitlines()), end="")
            return
        self._run([verb, path, "-r", self.realm, "-f", "-"], stdin=body)
        print(f"  {verb} {path} — {what}")

    def delete(self, path: str, what: str, payload: dict | list | None = None) -> None:
        """Delete `path`, optionally with a body — scope mappings are removed by naming them."""
        if self.dry_run:
            print(f"  [dry-run] delete {path} — {what}")
            return
        if payload is None:
            self._run(["delete", path, "-r", self.realm])
        else:
            self._run(["delete", path, "-r", self.realm, "-f", "-"],
                      stdin=json.dumps(payload, indent=2, sort_keys=True))
        print(f"  delete {path} — {what}")


def read_list(kc: Kcadm, path: str, key: str) -> list[dict]:
    """Read the client-policy list under `key`; Keycloak's read-only `global*` list is not in it."""
    body = kc.get(path) or {}
    return list(body.get(key) or [])
