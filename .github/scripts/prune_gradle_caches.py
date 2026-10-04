#!/usr/bin/env python3
"""Select the superseded Gradle caches on ``main`` that the cache janitor deletes (PSB-11).

The input is the JSON array ``gh cache list --ref refs/heads/main --key gradle- --json
id,key,sizeInBytes,createdAt,lastAccessedAt`` prints. A ``gradle-home-`` entry belongs to the
family its key names without the trailing commit SHA, and only the newest entry of each family is
kept. Every other ``gradle-`` entry is an extracted bundle a home entry points to; it is kept when
it was created or last restored together with a kept home entry, or when it was touched within the
grace period that protects a run still in flight. Everything else is printed as a tab-separated
``id size key`` line for deletion.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import UTC, datetime, timedelta

HOME_PREFIX = "gradle-home-"
SHA_SUFFIX = re.compile(r"-[0-9a-f]{40}$")
LINK_WINDOW = timedelta(seconds=120)
GRACE = timedelta(hours=2)


def _ts(value: str) -> datetime:
    """Parse an ISO-8601 timestamp as ``gh`` prints it, with or without fractional seconds."""
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def family(key: str) -> str:
    """Return the family of a ``gradle-home-`` key: the key without its trailing commit SHA."""
    return SHA_SUFFIX.sub("", key)


def plan(caches: list[dict], now: datetime) -> tuple[list[dict], list[dict]]:
    """Split the caches into the entries to keep and the entries to delete.

    :param caches: the entries of ``gh cache list`` for ``refs/heads/main``, keys starting ``gradle-``
    :param now: the reference time for the grace period
    :returns: ``(keep, delete)``, each in input order
    """
    homes = [c for c in caches if c["key"].startswith(HOME_PREFIX)]
    newest: dict[str, dict] = {}
    for home in homes:
        current = newest.get(family(home["key"]))
        if current is None or _ts(home["createdAt"]) > _ts(current["createdAt"]):
            newest[family(home["key"])] = home
    kept_homes = list(newest.values())
    anchors = [_ts(h["createdAt"]) for h in kept_homes] + [_ts(h["lastAccessedAt"]) for h in kept_homes]

    def linked(entry: dict) -> bool:
        times = (_ts(entry["createdAt"]), _ts(entry["lastAccessedAt"]))
        return any(abs(t - a) <= LINK_WINDOW for t in times for a in anchors)

    def recent(entry: dict) -> bool:
        return max(_ts(entry["createdAt"]), _ts(entry["lastAccessedAt"])) >= now - GRACE

    keep: list[dict] = []
    delete: list[dict] = []
    kept_ids = {h["id"] for h in kept_homes}
    for entry in caches:
        if entry["key"].startswith(HOME_PREFIX):
            (keep if entry["id"] in kept_ids or recent(entry) else delete).append(entry)
        else:
            (keep if linked(entry) or recent(entry) else delete).append(entry)
    return keep, delete


def _entry(i: int, key: str, created: str, accessed: str) -> dict:
    """Build one synthetic cache entry for the self-test."""
    return {"id": i, "key": key, "sizeInBytes": 1 << 20, "createdAt": created, "lastAccessedAt": accessed}


def selftest() -> int:
    """Check the selection on a synthetic listing and return the process exit code."""
    sha_a = "a" * 40
    sha_b = "b" * 40
    home = "gradle-home-v2|Linux-X64|build[0123]-"
    caches = [
        _entry(1, home + sha_a, "2026-10-03T10:00:00Z", "2026-10-03T11:00:00Z"),
        _entry(2, home + sha_b, "2026-10-03T12:00:00Z", "2026-10-03T12:00:00Z"),
        _entry(3, "gradle-dependencies-v2-old", "2026-10-03T09:59:58Z", "2026-10-03T11:00:01Z"),
        _entry(4, "gradle-dependencies-v2-new", "2026-10-03T11:59:58.5Z", "2026-10-03T11:59:58.5Z"),
        _entry(5, "gradle-wrapper-zips-v2-x", "2026-09-01T00:00:00Z", "2026-10-03T12:00:01Z"),
        _entry(6, "gradle-dependencies-v2-inflight", "2026-10-03T13:30:00Z", "2026-10-03T13:30:00Z"),
    ]
    keep, delete = plan(caches, _ts("2026-10-03T14:00:00Z"))
    got = sorted(c["id"] for c in delete)
    if got != [1, 3]:
        print(f"selftest FAILED: expected to delete [1, 3], got {got}", file=sys.stderr)
        return 1
    if family(home + sha_a) != home[:-1]:
        print("selftest FAILED: family() does not strip the commit SHA", file=sys.stderr)
        return 1
    if sorted(c["id"] for c in keep) != [2, 4, 5, 6]:
        print("selftest FAILED: keep set is not the complement of the delete set", file=sys.stderr)
        return 1
    print("selftest OK")
    return 0


def main() -> int:
    """Read the listing from a file or stdin and print the entries to delete."""
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("listing", nargs="?", help="JSON listing; stdin when omitted")
    parser.add_argument("--selftest", action="store_true", help="run the built-in self-test")
    args = parser.parse_args()
    if args.selftest:
        return selftest()
    raw = open(args.listing, encoding="utf-8").read() if args.listing else sys.stdin.read()
    keep, delete = plan(json.loads(raw), datetime.now(UTC))
    for entry in keep:
        print(f"keep   {entry['key']}", file=sys.stderr)
    for entry in delete:
        print(f"{entry['id']}\t{entry['sizeInBytes']}\t{entry['key']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
