#!/usr/bin/env python3
"""Complete a Dependabot compose bump: vet the branch, then re-resolve the bumped digests.

Two subcommands run before ``generate-quadlet.py`` on a Dependabot ``docker-compose`` PR (ADR-0215,
REQ-OPS-035):

``guard``
    Refuses unless every commit in ``merge-base..HEAD`` is a non-merge commit authored by
    Dependabot or by the ``basetool-release`` App, and every Dependabot commit touches only the
    top-level ``docker-compose*.yml`` files. Code executed afterwards is then ``main``'s.

``refresh-digests``
    Re-resolves each ``image: name:tag@sha256:…`` line the branch changed against ``--base`` to the
    digest the tag names now, and rewrites it in place when it moved. A resolution that fails leaves
    the line as Dependabot wrote it and prints a warning.

Exit codes: ``0`` done, ``1`` refused or self-test failed, ``2`` bad invocation.

Usage:
    dependabot_compose_followup.py guard --base <sha>
    dependabot_compose_followup.py refresh-digests --base <sha>
    dependabot_compose_followup.py --selftest
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from collections.abc import Callable
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]

DEPENDABOT_EMAIL = "49699333+dependabot[bot]@users.noreply.github.com"
APP_EMAIL = "332641749+basetool-release[bot]@users.noreply.github.com"

COMPOSE_PATH = re.compile(r"^docker-compose[^/]*\.yml$")

IMAGE_LINE = re.compile(
    r"^(?P<lead>\s*image:\s*\"?)"
    r"(?P<name>[^\s\"@#:]+(?::\d+/[^\s\"@#:]+)?)"
    r":(?P<tag>[^\s\"@#]+)"
    r"@(?P<digest>sha256:[0-9a-f]{64})"
    r"(?P<tail>.*)$"
)


def git(*args: str) -> str:
    """Run git in the repository root and return its standard output.

    Raises:
        subprocess.CalledProcessError: when git exits non-zero.
    """
    return subprocess.run(
        ["git", *args], cwd=REPO, check=True, capture_output=True, text=True, encoding="utf-8"
    ).stdout


def vet_commits(commits: list[tuple[str, str, int, list[str]]]) -> list[str]:
    """Return one problem per commit that makes the branch unsafe to complete automatically.

    Args:
        commits: ``(short sha, author email, parent count, changed paths)`` per commit in range.

    Returns:
        The problems found; an empty list means the branch is Dependabot's compose bump plus,
        at most, earlier runs of this follow-up.
    """
    problems: list[str] = []
    if not commits:
        problems.append("the branch holds no commits beyond its merge base")
    for sha, email, parents, paths in commits:
        author = email.lower()
        if parents > 1:
            problems.append(f"{sha} is a merge commit; recreate the PR with '@dependabot recreate'")
        elif author == DEPENDABOT_EMAIL:
            foreign = [p for p in paths if not COMPOSE_PATH.match(p)]
            if foreign:
                problems.append(f"{sha} (Dependabot) touches more than compose: {', '.join(foreign)}")
        elif author != APP_EMAIL:
            problems.append(f"{sha} is authored by {email}, neither Dependabot nor basetool-release")
    return problems


def guard(base: str) -> int:
    """Refuse a branch that carries anything but a Dependabot compose bump and earlier follow-ups."""
    merge_base = git("merge-base", base, "HEAD").strip()
    commits = []
    for line in git("log", "--format=%h%x09%ae%x09%P", f"{merge_base}..HEAD").splitlines():
        sha, email, parents = line.split("\t")
        paths = git("diff-tree", "--no-commit-id", "--name-only", "-r", sha).split()
        commits.append((sha, email, len(parents.split()), paths))
    problems = vet_commits(commits)
    for problem in problems:
        print(f"::error title=dependabot-compose-followup::{problem}")
    if not problems:
        print(f"guard: {len(commits)} commit(s) since {merge_base[:12]} are a compose bump")
    return 1 if problems else 0


def changed_images(diff: str) -> dict[str, str]:
    """Map each ``name:tag`` a unified diff adds on an ``image:`` line to the digest it pins."""
    found: dict[str, str] = {}
    for line in diff.splitlines():
        if not line.startswith("+") or line.startswith("+++"):
            continue
        match = IMAGE_LINE.match(line[1:])
        if match:
            found[f"{match['name']}:{match['tag']}"] = match["digest"]
    return found


def rewrite(text: str, ref: str, digest: str) -> str:
    """Return ``text`` with every ``image:`` line pinning ``ref`` moved to ``digest``."""
    lines = []
    for line in text.splitlines(keepends=True):
        match = IMAGE_LINE.match(line.rstrip("\r\n"))
        if match and f"{match['name']}:{match['tag']}" == ref:
            ending = line[len(line.rstrip("\r\n")):]
            line = f"{match['lead']}{ref}@{digest}{match['tail']}{ending}"
        lines.append(line)
    return "".join(lines)


def resolve(ref: str) -> str | None:
    """Return the index digest ``ref`` names in its registry now, or ``None`` when it cannot be read."""
    result = subprocess.run(
        ["docker", "buildx", "imagetools", "inspect", ref, "--format", "{{json .Manifest}}"],
        capture_output=True, text=True, encoding="utf-8", timeout=120, check=False,
    )
    if result.returncode != 0:
        return None
    try:
        digest = json.loads(result.stdout).get("digest", "")
    except json.JSONDecodeError:
        return None
    return digest if re.fullmatch(r"sha256:[0-9a-f]{64}", digest) else None


def refresh_digests(base: str, resolver: Callable[[str], str | None] = resolve) -> int:
    """Move every image the branch bumped to the digest its tag resolves to now."""
    merge_base = git("merge-base", base, "HEAD").strip()
    files = [p for p in git("diff", "--name-only", merge_base, "HEAD").split() if COMPOSE_PATH.match(p)]
    for name in files:
        path = REPO / name
        original = path.read_bytes().decode("utf-8")
        text = original
        for ref, pinned in changed_images(git("diff", merge_base, "HEAD", "--", name)).items():
            current = resolver(ref)
            if current is None:
                print(f"::warning title=dependabot-compose-followup::{ref}: could not resolve the "
                      f"tag; keeping Dependabot's {pinned[:19]}. Re-resolve it before merging.")
            elif current != pinned:
                print(f"{name}: {ref} moved from {pinned[:19]} to {current[:19]} since Dependabot pinned it")
                text = rewrite(text, ref, current)
            else:
                print(f"{name}: {ref} still resolves to {pinned[:19]}")
        if text != original:
            path.write_bytes(text.encode("utf-8"))
    return 0


def selftest() -> int:
    """Exercise the commit vetting, the diff parsing and the line rewrite on fixed inputs."""
    old = "sha256:" + "a" * 64
    new = "sha256:" + "b" * 64
    compose = (
        "services:\n"
        f"  redis:\n    image: redis:8-alpine@{old}\n"
        f"  exp:\n    image: \"quay.io/x/y:v1.2@{old}\"\n"
        f"  other:\n    image: redis:7-alpine@{old}\n"
    )
    diff = (
        "--- a/docker-compose.yml\n+++ b/docker-compose.yml\n"
        f"-    image: redis:8-alpine@{new}\n+    image: redis:8-alpine@{old}\n"
        f"+    image: \"quay.io/x/y:v1.2@{old}\"\n+    command: [\"image: nope\"]\n"
    )
    bump = ("abc1234", DEPENDABOT_EMAIL, 1, ["docker-compose.yml", "docker-compose.monitoring.yml"])
    cases: list[tuple[str, bool]] = [
        ("a Dependabot compose bump passes", vet_commits([bump]) == []),
        ("a follow-up commit by the App passes",
         vet_commits([bump, ("def5678", APP_EMAIL.upper(), 1, ["quadlet/systemd/redis.container"])]) == []),
        ("an empty range is refused", len(vet_commits([])) == 1),
        ("a Dependabot commit outside compose is refused",
         len(vet_commits([("abc1234", DEPENDABOT_EMAIL, 1, ["scripts/deploy.sh"])])) == 1),
        ("a nested compose path is refused",
         len(vet_commits([("abc1234", DEPENDABOT_EMAIL, 1, ["docker/docker-compose.yml"])])) == 1),
        ("a human commit is refused", len(vet_commits([bump, ("123abcd", "someone@example.org", 1, [])])) == 1),
        ("a merge commit is refused", len(vet_commits([bump, ("fedcba9", APP_EMAIL, 2, [])])) == 1),
        ("the diff yields both added pins and nothing else",
         changed_images(diff) == {"redis:8-alpine": old, "quay.io/x/y:v1.2": old}),
        ("a registry with a port is parsed",
         changed_images(f"+  image: reg:5000/a/b:1@{old}\n") == {"reg:5000/a/b:1": old}),
        ("the rewrite moves only the named tag",
         rewrite(compose, "redis:8-alpine", new)
         == compose.replace(f"redis:8-alpine@{old}", f"redis:8-alpine@{new}")),
        ("the rewrite keeps quotes and CRLF",
         rewrite(f"  image: \"quay.io/x/y:v1.2@{old}\"\r\n", "quay.io/x/y:v1.2", new)
         == f"  image: \"quay.io/x/y:v1.2@{new}\"\r\n"),
        ("an unpinned tag is not an image line", IMAGE_LINE.match("  image: redis:8-alpine") is None),
    ]
    failures = 0
    for name, passed in cases:
        print(f"  {'ok  ' if passed else 'FAIL'} {name}")
        failures += 0 if passed else 1
    print("selftest: FAILED" if failures else "selftest: passed")
    return 1 if failures else 0


def main() -> int:
    """Dispatch to the self-test or to one of the two subcommands."""
    if sys.argv[1:] == ["--selftest"]:
        return selftest()
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("command", choices=["guard", "refresh-digests"])
    parser.add_argument("--base", required=True, help="The PR's base commit.")
    args = parser.parse_args()
    if args.command == "guard":
        return guard(args.base)
    return refresh_digests(args.base)


if __name__ == "__main__":
    sys.exit(main())
