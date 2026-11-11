#!/usr/bin/env python3
# Copyright (C) 2026 Lucas Greuloch
# SPDX-License-Identifier: GPL-3.0-only
"""Hash the operational host companions from one source/target installation inventory."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parent.parent


def normalized(data: bytes, ordering: bool, service_uid: int | None = None) -> bytes:
    """Normalize only the generated systemd ordering file's uid and non-semantic comments."""
    if not ordering:
        return data
    lines = [line.strip() for line in data.decode("utf-8").splitlines()
             if line.strip() and not line.lstrip().startswith(("#", ";", "{{ ansible_managed"))]
    text = "\n".join(lines) + "\n"
    text = text.replace("{{ basetool_host_service_uid }}", "SERVICE_UID")
    return re.sub(r"user@([0-9]+)",
                  lambda match: "user@SERVICE_UID" if service_uid is None
                  or match[1] == str(service_uid) else match[0], text).encode("utf-8")


def digest(data: bytes, ordering: bool = False, service_uid: int | None = None) -> str:
    """Hash byte-exact static files or the normalized generated ordering contract."""
    return hashlib.sha256(normalized(data, ordering, service_uid)).hexdigest()


def generate(revision: str | None = None) -> list[str]:
    """Return manifest entries for every inventory target, using committed bytes under --rev."""
    def source(path: str) -> bytes:
        if revision:
            return subprocess.run(["git", "show", f"{revision}:{path}"], check=True,
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30).stdout
        return (ROOT / path).read_bytes()

    try:
        entries = json.loads(source("scripts/host-companions.json"))
    except subprocess.CalledProcessError:
        return []
    result = []
    seen = set()
    for entry in entries:
        target = entry["target"]
        if not target.startswith("/etc/") or ".." in target or target in seen:
            raise ValueError("Invalid or duplicate host companion target")
        seen.add(target)
        result.append(f"{digest(source(entry['source']), entry['kind'] == 'ordering')}  host{target}")
    return result


def main() -> None:
    """Generate release expectations or inspect one installed companion without writing it."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rev")
    parser.add_argument("--inspect", type=Path)
    parser.add_argument("--service-uid", type=int)
    args = parser.parse_args()
    if args.inspect:
        ordering = args.inspect.name == "20-service-user.conf"
        if ordering and args.service_uid is None:
            parser.error("--inspect of an ordering drop-in requires --service-uid")
        print(digest(args.inspect.read_bytes(), ordering, args.service_uid))
    else:
        lines = generate(args.rev)
        print("\n".join(lines), end="\n" if lines else "")


if __name__ == "__main__":
    main()
