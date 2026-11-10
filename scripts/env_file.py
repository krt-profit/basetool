# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
# SPDX-License-Identifier: GPL-3.0-only
"""Read the host's literal single-line environment-file grammar without interpolation."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

KEY = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")


class EnvFileError(ValueError):
    """The environment source cannot be read without revealing its values."""


def parse_env_file(path: str | Path) -> dict[str, str]:
    """Read literal assignments; last assignment wins and one matching quote layer is stripped."""
    try:
        lines = Path(path).read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as error:
        raise EnvFileError(f"cannot read environment file {path}") from error
    values: dict[str, str] = {}
    for raw in lines:
        line = re.sub(r"^export[ \t]+", "", raw.strip())
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key, value = key.strip(), value.strip()
        if not KEY.fullmatch(key):
            continue
        if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
            value = value[1:-1]
        values[key] = value
    return values


def main() -> int:
    """Print only the requested value, or a value-free diagnostic on a read failure."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--file", required=True)
    parser.add_argument("--key", required=True)
    args = parser.parse_args()
    if not KEY.fullmatch(args.key):
        return 2
    try:
        print(parse_env_file(args.file).get(args.key, ""), end="")
    except EnvFileError as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
