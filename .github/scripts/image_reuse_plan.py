#!/usr/bin/env python3
"""Decide whether a main-branch push may re-tag the previous build instead of rebuilding.

REQ-OPS-021 / ADR-0137 build every app image once per COMMIT, and the release-tag run already
re-tags what the main run of the same commit produced. A main push whose diff touches nothing that
goes into an image still rebuilt all three images on six runners -- measured over thirty main
commits on 2026-09-22, nineteen of them changed no image input (audit item CI-07). This script is
the git half of the second reuse path: it answers "did anything that goes into an image change
between the previous main tip and this one?". The registry half -- the predecessor's images exist,
carry both architectures, carry this workflow's main-branch signature and are recent enough --
stays in the workflow's `plan` job, next to the tag path's identical gates.

WHAT COUNTS AS AN IMAGE INPUT (the precise definition; REQ-OPS-021 quotes it):

1. every build-context path a ``COPY`` in ``docker/app/Dockerfile`` reads (``${MODULE}`` expanded
   to backend, frontend and ingest; ``COPY --from=<stage>`` is internal and skipped). Parsed from
   the Dockerfile rather than listed here, so a COPY added there is an input here without anyone
   remembering to say so. Today that is the Gradle wrapper and ``gradle/`` (catalog, wrapper,
   ``verification-metadata.xml``), the root build scripts and ``gradle.properties``, all six module
   build scripts, the three modules' and ``logging-support``'s ``src/main`` and
   ``frontend/oss-bundled-components.json``;
2. the Dockerfile itself -- which pins both base-image digests, so a base bump is a Dockerfile
   change -- and the root ``.dockerignore``, which decides what those COPYs see;
3. what shapes the build without being copied: ``release-images.yml`` (build args, labels,
   platforms), ``.github/actions/setup-buildx/`` (the BuildKit that runs the build) and
   ``.github/scripts/app_version.py`` (the version string the frontend bakes).

A change to anything else -- docs, tests, the monitoring tree, compose files, other workflows --
leaves every image byte-for-byte what the previous build produced, apart from what the
predecessor's own build time and commit stamp into it (its labels and the frontend's version
chip, which then name the commit that built the image, not the one that re-tagged it).

A RELEASE COMMIT IS ALWAYS BUILT, whatever it changes: it is the commit whose image the frontend
footer must name as the release (app_version.py), and a release deserves images built on its own
day. It is recognised by the newest dated CHANGELOG section differing from the base's -- the
section ``release-prepare.yml`` writes -- which, unlike "is the tag there yet?", does not depend on
how fast ``release-publish.yml`` runs.

Everything unclear is "build": an unknown base, a base that is not an ancestor, an unreadable
Dockerfile, a git error. The default is the behaviour this path optimises, never a guess.

Usage:
    image_reuse_plan.py --base <sha> --head <sha>     # one decision, JSON on stdout
    image_reuse_plan.py --dry-run 30 [--ref origin/main]
    image_reuse_plan.py --selftest

Exit codes: 0 -> a decision was printed (eligible or not), or the selftest passed.
            1 -> the selftest failed.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from app_version import newest_changelog_version  # noqa: E402  (sibling module, path set above)

APP_DOCKERFILE = "docker/app/Dockerfile"
MODULES = ("backend", "frontend", "ingest")

# Inputs that no COPY names but that still decide what the image is (definition point 2 and 3).
STATIC_INPUTS = (
    APP_DOCKERFILE,
    ".dockerignore",
    ".github/workflows/release-images.yml",
    ".github/actions/setup-buildx",
    ".github/scripts/app_version.py",
)

ZERO_SHA = "0" * 40


def copy_sources(dockerfile: str) -> list[str]:
    """Return every build-context path the Dockerfile's ``COPY`` instructions read.

    Line continuations are joined first; ``COPY --from=...`` is skipped (it copies between stages,
    not from the context); other ``--flag`` options are dropped; the last operand is the
    destination. ``${MODULE}`` / ``$MODULE`` are expanded for every module.

    :param dockerfile: the Dockerfile's full text.
    :return: the source paths, without trailing slashes, de-duplicated, in first-seen order.
    """
    joined = re.sub(r"\\\r?\n", " ", dockerfile)
    sources: list[str] = []
    for line in joined.splitlines():
        stripped = line.strip()
        if not re.match(r"(?i)^COPY\s", stripped):
            continue
        tokens = stripped.split()[1:]
        if any(t.startswith("--from=") for t in tokens):
            continue
        operands = [t for t in tokens if not t.startswith("--")]
        for src in operands[:-1]:
            variants = [src]
            if "MODULE" in src:
                variants = [re.sub(r"\$\{MODULE\}|\$MODULE\b", m, src) for m in MODULES]
            for v in variants:
                v = v.rstrip("/")
                if v.startswith("./"):
                    v = v[2:]
                if v and v not in sources:
                    sources.append(v)
    return sources


def image_inputs(dockerfile: str) -> list[str]:
    """Return the full image-input set: the COPY sources plus the static inputs.

    :param dockerfile: the text of ``docker/app/Dockerfile`` at the commit being planned.
    :return: path prefixes; a changed file is an input when it equals one or lies below one.
    """
    inputs = list(STATIC_INPUTS)
    for src in copy_sources(dockerfile):
        if src not in inputs:
            inputs.append(src)
    return inputs


def touched_inputs(changed: list[str], inputs: list[str]) -> list[str]:
    """Select the changed files that are image inputs.

    :param changed: repository-relative paths changed between base and head.
    :param inputs: the prefixes from :func:`image_inputs`.
    :return: the changed paths that equal an input or lie below one.
    """
    hits = []
    for path in changed:
        for prefix in inputs:
            if path == prefix or path.startswith(prefix + "/"):
                hits.append(path)
                break
    return hits


def is_release_commit(changelog_head: str, changelog_base: str) -> bool:
    """Whether the range introduces a new dated CHANGELOG section, i.e. contains a release commit.

    :param changelog_head: CHANGELOG.md at the head commit.
    :param changelog_base: CHANGELOG.md at the base commit.
    :return: ``True`` when the newest dated version differs between the two.
    """
    return newest_changelog_version(changelog_head) != newest_changelog_version(changelog_base)


def decide(base: str, head: str, dockerfile: str | None, changed: list[str] | None,
           changelog_head: str, changelog_base: str, base_is_ancestor: bool) -> dict:
    """Return the git-side decision for one push.

    :param base: the previous main tip (``github.event.before``).
    :param head: the pushed commit.
    :param dockerfile: ``docker/app/Dockerfile`` at head, or ``None`` when it could not be read.
    :param changed: the files changed between base and head, or ``None`` on a git error.
    :param changelog_head: CHANGELOG.md at head.
    :param changelog_base: CHANGELOG.md at base.
    :param base_is_ancestor: whether base is an ancestor of head.
    :return: ``{"eligible": bool, "reason": str, "inputs_changed": [...]}``.
    """
    def result(eligible: bool, reason: str, hits: list[str] | None = None) -> dict:
        return {"eligible": eligible, "reason": reason, "inputs_changed": hits or []}

    if not re.fullmatch(r"[0-9a-f]{40}", base or "") or base == ZERO_SHA:
        return result(False, f"no previous main tip to compare against (base={base!r})")
    if not base_is_ancestor:
        return result(False, f"base {base[:12]} is not an ancestor of {head[:12]} (force push?)")
    if dockerfile is None:
        return result(False, f"{APP_DOCKERFILE} unreadable at {head[:12]}")
    if changed is None:
        return result(False, "git diff failed")
    if is_release_commit(changelog_head, changelog_base):
        return result(False, "the range contains a release commit (new dated CHANGELOG section)")
    hits = touched_inputs(changed, image_inputs(dockerfile))
    if hits:
        shown = ", ".join(hits[:5]) + (f" and {len(hits) - 5} more" if len(hits) > 5 else "")
        return result(False, f"{len(hits)} image input(s) changed: {shown}", hits)
    return result(True, f"none of {len(changed)} changed file(s) is an image input")


def git(*args: str) -> subprocess.CompletedProcess:
    """Run git in the repository and capture its output.

    :param args: the git arguments.
    :return: the completed process; callers check ``returncode``.
    """
    return subprocess.run(["git", *args], cwd=REPO, capture_output=True, text=True)


def show(rev: str, path: str) -> str | None:
    """Return ``path`` at ``rev``, or ``None`` when it does not exist there.

    :param rev: a commit.
    :param path: a repository-relative path.
    :return: the file content or ``None``.
    """
    proc = git("show", f"{rev}:{path}")
    return proc.stdout if proc.returncode == 0 else None


def decide_from_git(base: str, head: str, dockerfile_override: str | None = None) -> dict:
    """Gather the git facts for one push and decide.

    :param base: the previous main tip.
    :param head: the pushed commit.
    :param dockerfile_override: a Dockerfile text to use instead of the one at ``head`` -- only the
        dry run passes it, to replay commits older than ``docker/app/Dockerfile``.
    :return: the decision from :func:`decide`, plus ``base`` and ``head``.
    """
    valid = re.fullmatch(r"[0-9a-f]{40}", base or "") and base != ZERO_SHA
    ancestor = bool(valid) and git("merge-base", "--is-ancestor", base, head).returncode == 0
    changed = None
    if valid and ancestor:
        diff = git("diff", "--name-only", "--no-renames", base, head)
        changed = [p for p in diff.stdout.splitlines() if p] if diff.returncode == 0 else None
    decision = decide(
        base,
        head,
        dockerfile_override if dockerfile_override is not None else show(head, APP_DOCKERFILE),
        changed,
        show(head, "CHANGELOG.md") or "",
        (show(base, "CHANGELOG.md") or "") if valid else "",
        ancestor,
    )
    decision.update({"base": base, "head": head})
    return decision


def dry_run(count: int, ref: str, legacy: list[str]) -> int:
    """Replay the decision over the last ``count`` first-parent commits of ``ref``.

    Each commit is planned against its first parent, as a one-commit push would be. A chain of
    reuses is sound because each step compares against a predecessor whose image is, by
    induction, what that predecessor would have built.

    :param count: how many commits to replay.
    :param ref: the branch to replay, e.g. ``origin/main``.
    :param legacy: extra input paths for commits older than docker/app/Dockerfile (the three
        per-module Dockerfiles and what they copied).
    :return: 0.
    """
    revs = git("rev-list", "--first-parent", f"--max-count={count}", ref).stdout.split()
    current = (REPO / APP_DOCKERFILE).read_text(encoding="utf-8")
    reuse = 0
    for head in reversed(revs):
        parent = git("rev-parse", f"{head}^1").stdout.strip()
        subject = git("log", "-1", "--format=%s", head).stdout.strip()
        decision = decide_from_git(parent, head, current)
        if decision["eligible"] and legacy:
            changed = git("diff", "--name-only", "--no-renames", parent, head).stdout.split()
            hits = touched_inputs(changed, legacy)
            if hits:
                decision = {"eligible": False, "reason": f"legacy input(s) changed: {', '.join(hits[:3])}"}
        reuse += decision["eligible"]
        verdict = "RE-TAG" if decision["eligible"] else "build "
        print(f"{verdict} {head[:9]} {subject[:60]:<60} | {decision['reason'][:90]}")
    print(f"\n{reuse} of {len(revs)} commits would re-tag, {len(revs) - reuse} would build.")
    return 0


def selftest() -> int:
    """Assert the input parser and every decision branch on synthetic data.

    :return: 0 when every case holds, 1 otherwise.
    """
    failures = 0

    def check(name: str, cond: bool) -> None:
        nonlocal failures
        print(f"  [{'ok ' if cond else 'FAIL'}] {name}")
        failures += not cond

    dockerfile = (
        "FROM x AS build\n"
        "COPY --chmod=755 gradlew .\n"
        "COPY gradle/ gradle/\n"
        "COPY ${MODULE}/src/main/ ${MODULE}/src/main/\n"
        "COPY logging-support/src/main/ \\\n    logging-support/src/main/\n"
        "FROM y AS runtime\n"
        "COPY --from=build --chown=1:1 /app/extracted/application/ ./\n"
    )
    src = copy_sources(dockerfile)
    check("COPY flags are dropped, the wrapper is an input", "gradlew" in src)
    check("a directory source loses its trailing slash", "gradle" in src)
    check("${MODULE} expands to all three modules",
          all(f"{m}/src/main" in src for m in MODULES))
    check("a continued COPY line is joined", "logging-support/src/main" in src)
    check("COPY --from reads no context path", not any("extracted" in s for s in src))

    inputs = image_inputs(dockerfile)
    check("the Dockerfile and .dockerignore are always inputs",
          APP_DOCKERFILE in inputs and ".dockerignore" in inputs)
    check("a source file below an input matches",
          touched_inputs(["frontend/src/main/java/A.java"], inputs) == ["frontend/src/main/java/A.java"])
    check("a test source is not an input", touched_inputs(["frontend/src/test/java/ATest.java"], inputs) == [])
    check("a prefix match needs a path boundary", touched_inputs(["gradle.properties.bak", "gradlew.bat"], inputs) == [])
    check("the verification metadata is an input (inside gradle/)",
          touched_inputs(["gradle/verification-metadata.xml"], inputs) != [])
    check("the release workflow is an input",
          touched_inputs([".github/workflows/release-images.yml"], inputs) != [])
    check("docs are not an input", touched_inputs(["docs/specs/x.md", "README.md"], inputs) == [])

    base, head = "a" * 40, "b" * 40
    cut = "## [v1.8.5](https://x/v1.8.5) - 2026-09-20\n"
    old = "## [v1.8.4](https://x/v1.8.4) - 2026-09-15\n"
    ok = decide(base, head, dockerfile, ["docs/a.md"], old, old, True)
    check("docs-only range is eligible", ok["eligible"])
    check("an input change is not eligible",
          not decide(base, head, dockerfile, ["backend/src/main/A.java"], old, old, True)["eligible"])
    check("a release commit is never eligible, even docs-only",
          not decide(base, head, dockerfile, ["CHANGELOG.md"], cut + old, old, True)["eligible"])
    check("the all-zero base of a new branch is not eligible",
          not decide(ZERO_SHA, head, dockerfile, [], old, old, True)["eligible"])
    check("a non-ancestor base is not eligible",
          not decide(base, head, dockerfile, [], old, old, False)["eligible"])
    check("an unreadable Dockerfile is not eligible",
          not decide(base, head, None, [], old, old, True)["eligible"])
    check("a failed diff is not eligible",
          not decide(base, head, dockerfile, None, old, old, True)["eligible"])

    real = show("HEAD", APP_DOCKERFILE) or (REPO / APP_DOCKERFILE).read_text(encoding="utf-8")
    real_inputs = image_inputs(real)
    # Anti-vacuity: the parser must still see the real Dockerfile's COPYs. A parser that found none
    # would make every docs-and-code change look like "no input changed" -- the one wrong answer.
    for must in ("gradle", "backend/src/main", "frontend/src/main", "ingest/src/main",
                 "logging-support/src/main", "frontend/oss-bundled-components.json"):
        check(f"the real Dockerfile yields input {must}", must in real_inputs)

    print("selftest: FAILED" if failures else "selftest: passed")
    return 1 if failures else 0


def main() -> int:
    """Parse the command line and run the requested mode.

    :return: the process exit code.
    """
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base")
    parser.add_argument("--head")
    parser.add_argument("--dry-run", type=int, metavar="N")
    parser.add_argument("--ref", default="origin/main")
    parser.add_argument("--legacy-input", action="append", default=[])
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args()
    if args.selftest:
        return selftest()
    if args.dry_run:
        return dry_run(args.dry_run, args.ref, args.legacy_input)
    if not args.head:
        parser.error("--head is required")
    print(json.dumps(decide_from_git(args.base or "", args.head)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
