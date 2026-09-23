#!/usr/bin/env python3
"""Decide, per app image, whether a main-branch push must rebuild it or may re-tag the previous one.

REQ-OPS-021 / ADR-0137 build every app image once per COMMIT, and the release-tag run already
re-tags what the main run of the same commit produced. A main push whose diff touches nothing that
goes into an image still rebuilt all three images on six runners (audit item CI-07). ADR-0210 first
re-tagged all three when NO image input changed; since its per-module amendment (owner decision
2026-09-23) each image is decided on its own: a frontend-only change rebuilds the frontend and
re-tags the backend and ingest images of the previous main tip.

This script is the git half of that decision: for each module it answers "did anything that goes
into THIS image change between the previous main tip and this one?". The registry half -- the
predecessor's image exists, carries both architectures, carries this workflow's main-branch
signature and is at most seven days old -- stays in the workflow's ``plan`` job, per module; a module
that fails it is rebuilt.

WHAT COUNTS AS AN IMAGE INPUT (the precise definition; REQ-OPS-021 quotes it). Everything is derived
from ``docker/app/Dockerfile``'s ``COPY`` lines (``COPY --from=<stage>`` is internal and skipped), so
a COPY added there is an input here without anyone remembering to say so:

* A module's OWN inputs rebuild only that module:
  - every COPY source written with ``${MODULE}`` -- today ``<module>/src/main``;
  - the paths in ``MODULE_OWN``, each with the reason it is read by one module's build only --
    today ``frontend/oss-bundled-components.json`` (the Dockerfile copies it for every module, but
    only the frontend's ``generateOssLicenses`` reads it).
* Every other COPY source is SHARED and rebuilds all three: the Gradle wrapper and ``gradle/``
  (catalog, wrapper, ``verification-metadata.xml``), the root build scripts and
  ``gradle.properties``, ``logging-support/src/main`` (all three ship it), and ALL SIX module build
  scripts. The build scripts are shared on purpose, not by caution alone: Gradle configures every
  project for every build, and the frontend jar embeds the Licensee reports of the backend, ingest
  and keycloak-spi runtime classpaths (the "Open-Source-Lizenzen" page, REQ-UI-021), so a
  dependency change in ``backend/build.gradle.kts`` changes the frontend image.
* Also shared, because they shape every build without being copied: the Dockerfile itself (it pins
  both base-image digests), the root ``.dockerignore``, ``release-images.yml`` (build args, labels,
  platforms), ``.github/actions/setup-buildx/`` (the BuildKit that runs the build) and
  ``.github/scripts/app_version.py`` (the version string the frontend bakes).

A change to anything else -- docs, tests, the monitoring tree, compose files, other workflows --
leaves an image byte-for-byte what its previous build produced, apart from what that build's time
and commit stamp into it: its labels and, for the frontend, the version chip, which name the commit
that BUILT the image, not the one that re-tagged it. After a partial reuse the three images of one
``:sha-<short>`` can therefore name three different building commits; ADR-0210 lists what reads that.

A RELEASE COMMIT REBUILDS ALL THREE, whatever it changes: it is the commit whose images the tag run
re-tags as the release and whose frontend footer must name the release (app_version.py), and a
release deserves images built from one commit on its own day. It is recognised by the newest dated
CHANGELOG section differing from the base's -- the section ``release-prepare.yml`` writes -- which,
unlike "is the tag there yet?", does not depend on how fast ``release-publish.yml`` runs.

Everything unclear rebuilds all three: an unknown base, a base that is not an ancestor, an
unreadable Dockerfile, a git error. The default is the behaviour this path optimises, never a guess.

Usage:
    image_reuse_plan.py --base <sha> --head <sha>     # one decision, JSON on stdout
    image_reuse_plan.py --dry-run 30 [--ref origin/main]
    image_reuse_plan.py --selftest

Output of a decision: ``{"rebuild": [...], "reuse": [...], "reason": "...", "modules": {m: {"rebuild":
bool, "reason": "...", "inputs_changed": [...]}}, "base": ..., "head": ...}``. ``rebuild`` and
``reuse`` partition backend, frontend and ingest.

Exit codes: 0 -> a decision was printed, or the selftest passed.
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

# Inputs that no COPY names but that still decide what every image is.
STATIC_SHARED = (
    APP_DOCKERFILE,
    ".dockerignore",
    ".github/workflows/release-images.yml",
    ".github/actions/setup-buildx",
    ".github/scripts/app_version.py",
)

# COPY sources written without ${MODULE} that nevertheless only one module's build reads. Each entry
# needs a reason; everything not listed here and not written with ${MODULE} is shared. The selftest
# fails when an entry is no longer a COPY source, so a stale entry cannot linger.
MODULE_OWN = {
    # Read only by the frontend's generateOssLicenses (frontend/build.gradle.kts); the Dockerfile
    # copies it for every module to avoid a per-module glob.
    "frontend/oss-bundled-components.json": "frontend",
}

# Only for --dry-run over history older than docker/app/Dockerfile (ADR-0209, 2026-09-23): what the
# three per-module Dockerfiles copied on top of today's inputs.
LEGACY_OWN = {m: [f"{m}/Dockerfile"] for m in MODULES}
LEGACY_SHARED = ["test-support/src/main", "versions.properties"]

ZERO_SHA = "0" * 40


def copy_sources(dockerfile: str) -> list[str]:
    """Return every build-context COPY source, ``${MODULE}`` left unexpanded.

    Line continuations are joined first; ``COPY --from=...`` is skipped (it copies between stages,
    not from the context); other ``--flag`` options are dropped; the last operand is the
    destination.

    :param dockerfile: the Dockerfile's full text.
    :return: the source paths without trailing slashes, de-duplicated, in first-seen order.
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
            src = src.rstrip("/")
            if src.startswith("./"):
                src = src[2:]
            if src and src not in sources:
                sources.append(src)
    return sources


def input_sets(dockerfile: str) -> tuple[dict[str, list[str]], list[str]]:
    """Split the image inputs into each module's own inputs and the shared ones.

    :param dockerfile: the text of ``docker/app/Dockerfile`` at the commit being planned.
    :return: ``(own, shared)`` -- ``own`` maps each module to its path prefixes, ``shared`` lists
        the prefixes that feed all three.
    """
    own: dict[str, list[str]] = {m: [] for m in MODULES}
    shared = list(STATIC_SHARED)
    for src in copy_sources(dockerfile):
        if re.search(r"\$\{MODULE\}|\$MODULE\b", src):
            for m in MODULES:
                own[m].append(re.sub(r"\$\{MODULE\}|\$MODULE\b", m, src))
        elif src in MODULE_OWN:
            own[MODULE_OWN[src]].append(src)
        elif src not in shared:
            shared.append(src)
    return own, shared


def touched_inputs(changed: list[str], inputs: list[str]) -> list[str]:
    """Select the changed files that are inputs.

    :param changed: repository-relative paths changed between base and head.
    :param inputs: path prefixes; a file matches when it equals one or lies below one.
    :return: the changed paths that match.
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


def _shown(hits: list[str]) -> str:
    """Abbreviate a list of paths for a reason string.

    :param hits: the paths.
    :return: at most three paths, with a count of the rest.
    """
    return ", ".join(hits[:3]) + (f" and {len(hits) - 3} more" if len(hits) > 3 else "")


def decide(base: str, head: str, dockerfile: str | None, changed: list[str] | None,
           changelog_head: str, changelog_base: str, base_is_ancestor: bool,
           extra_own: dict[str, list[str]] | None = None, extra_shared: list[str] | None = None) -> dict:
    """Return the git-side decision for one push, per module.

    :param base: the previous main tip (``github.event.before``).
    :param head: the pushed commit.
    :param dockerfile: ``docker/app/Dockerfile`` at head, or ``None`` when it could not be read.
    :param changed: the files changed between base and head, or ``None`` on a git error.
    :param changelog_head: CHANGELOG.md at head.
    :param changelog_base: CHANGELOG.md at base.
    :param base_is_ancestor: whether base is an ancestor of head.
    :param extra_own: additional per-module inputs (the dry run's legacy Dockerfiles).
    :param extra_shared: additional shared inputs (the dry run's legacy copies).
    :return: the decision described in the module docstring.
    """
    def everything(reason: str) -> dict:
        return {"rebuild": list(MODULES), "reuse": [], "reason": reason,
                "modules": {m: {"rebuild": True, "reason": reason, "inputs_changed": []} for m in MODULES}}

    if not re.fullmatch(r"[0-9a-f]{40}", base or "") or base == ZERO_SHA:
        return everything(f"no previous main tip to compare against (base={base!r})")
    if not base_is_ancestor:
        return everything(f"base {base[:12]} is not an ancestor of {head[:12]} (force push?)")
    if dockerfile is None:
        return everything(f"{APP_DOCKERFILE} unreadable at {head[:12]}")
    if changed is None:
        return everything("git diff failed")
    if is_release_commit(changelog_head, changelog_base):
        return everything("the range contains a release commit (new dated CHANGELOG section)")

    own, shared = input_sets(dockerfile)
    for m, extra in (extra_own or {}).items():
        own[m] = own[m] + extra
    shared = shared + list(extra_shared or [])

    shared_hits = touched_inputs(changed, shared)
    if shared_hits:
        result = everything(f"{len(shared_hits)} shared input(s) changed: {_shown(shared_hits)}")
        for m in MODULES:
            result["modules"][m]["inputs_changed"] = shared_hits
        return result

    modules = {}
    for m in MODULES:
        hits = touched_inputs(changed, own[m])
        if hits:
            modules[m] = {"rebuild": True, "reason": f"{len(hits)} own input(s) changed: {_shown(hits)}",
                          "inputs_changed": hits}
        else:
            modules[m] = {"rebuild": False, "reason": "no own or shared input changed", "inputs_changed": []}
    rebuild = [m for m in MODULES if modules[m]["rebuild"]]
    reuse = [m for m in MODULES if not modules[m]["rebuild"]]
    reason = (f"rebuild {', '.join(rebuild) or 'nothing'}, re-tag {', '.join(reuse) or 'nothing'} "
              f"({len(changed)} changed file(s), no shared input among them)")
    return {"rebuild": rebuild, "reuse": reuse, "reason": reason, "modules": modules}


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


def decide_from_git(base: str, head: str, dockerfile_override: str | None = None,
                    legacy: bool = False) -> dict:
    """Gather the git facts for one push and decide.

    :param base: the previous main tip.
    :param head: the pushed commit.
    :param dockerfile_override: a Dockerfile text to use instead of the one at ``head`` -- only the
        dry run passes it, to replay commits older than ``docker/app/Dockerfile``.
    :param legacy: add the pre-2026-09-23 per-module Dockerfile inputs (dry run only).
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
        LEGACY_OWN if legacy else None,
        LEGACY_SHARED if legacy else None,
    )
    decision.update({"base": base, "head": head})
    return decision


def dry_run(count: int, ref: str) -> int:
    """Replay the decision over the last ``count`` first-parent commits of ``ref``.

    Each commit is planned against its first parent, as a one-commit push would be, with today's
    Dockerfile and the legacy per-module Dockerfiles counted as inputs, so history from before
    ``docker/app/Dockerfile`` is judged as it would have had to be.

    :param count: how many commits to replay.
    :param ref: the branch to replay, e.g. ``origin/main``.
    :return: 0.
    """
    revs = git("rev-list", "--first-parent", f"--max-count={count}", ref).stdout.split()
    current = (REPO / APP_DOCKERFILE).read_text(encoding="utf-8")
    jobs = {m: 0 for m in MODULES}
    whole = 0
    for head in reversed(revs):
        parent = git("rev-parse", f"{head}^1").stdout.strip()
        subject = git("log", "-1", "--format=%s", head).stdout.strip()
        decision = decide_from_git(parent, head, current, legacy=True)
        for m in decision["reuse"]:
            jobs[m] += 1
        whole += len(decision["reuse"]) == len(MODULES)
        marks = "".join(m[0].upper() if m in decision["reuse"] else "." for m in MODULES)
        print(f"re-tag[{marks}] {head[:9]} {subject[:55]:<55} | {decision['reason'][:80]}")
    saved = sum(jobs.values())
    print(f"\n{len(revs)} commits, {len(revs) * len(MODULES)} module builds "
          f"({len(revs) * len(MODULES) * 2} build jobs, two platforms each).")
    print(f"per-module reuse: {saved} module builds re-tagged ({saved * 2} build jobs) -- "
          + ", ".join(f"{m} {n}" for m, n in jobs.items()))
    print(f"all-three reuse (for comparison): {whole} commits ({whole * len(MODULES) * 2} build jobs).")
    return 0


def selftest() -> int:
    """Assert the input split and every decision branch on synthetic data.

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
        "COPY backend/build.gradle.kts backend/\n"
        "COPY frontend/build.gradle.kts frontend/\n"
        "COPY ${MODULE}/src/main/ ${MODULE}/src/main/\n"
        "COPY logging-support/src/main/ \\\n    logging-support/src/main/\n"
        "COPY frontend/oss-bundled-components.json frontend/oss-bundled-components.json\n"
        "FROM y AS runtime\n"
        "COPY --from=build --chown=1:1 /app/extracted/application/ ./\n"
    )
    own, shared = input_sets(dockerfile)
    check("COPY flags are dropped, the wrapper is shared", "gradlew" in shared)
    check("a continued COPY line is joined, logging-support is shared", "logging-support/src/main" in shared)
    check("${MODULE} sources become each module's own input",
          all(own[m] == [f"{m}/src/main"] + (["frontend/oss-bundled-components.json"] if m == "frontend" else [])
              for m in MODULES))
    check("module build scripts are shared (Licensee reports, project configuration)",
          "backend/build.gradle.kts" in shared and "frontend/build.gradle.kts" in shared)
    check("COPY --from reads no context path", not any("extracted" in s for s in shared))
    check("the Dockerfile, .dockerignore and the workflow are shared",
          all(p in shared for p in (APP_DOCKERFILE, ".dockerignore", ".github/workflows/release-images.yml")))
    check("a prefix match needs a path boundary", touched_inputs(["gradle.properties.bak", "gradlew.bat"], shared) == [])

    base, head = "a" * 40, "b" * 40
    cut = "## [v1.8.5](https://x/v1.8.5) - 2026-09-20\n"
    old = "## [v1.8.4](https://x/v1.8.4) - 2026-09-15\n"

    def d(changed, head_log=old, **kw):
        return decide(base, head, kw.get("df", dockerfile), changed, head_log, old, kw.get("anc", True))

    r = d(["docs/a.md", "frontend/src/test/java/ATest.java"])
    check("docs and tests only: all three re-tag", r["reuse"] == list(MODULES) and r["rebuild"] == [])
    r = d(["frontend/src/main/resources/static/css/app.css"])
    check("a frontend source rebuilds the frontend only",
          r["rebuild"] == ["frontend"] and r["reuse"] == ["backend", "ingest"])
    r = d(["frontend/oss-bundled-components.json"])
    check("the OSS component list rebuilds the frontend only", r["rebuild"] == ["frontend"])
    r = d(["backend/src/main/A.java", "ingest/src/main/B.java"])
    check("backend and ingest sources rebuild those two", r["rebuild"] == ["backend", "ingest"] and r["reuse"] == ["frontend"])
    for path in ("logging-support/src/main/L.java", "gradle/verification-metadata.xml", "gradle/libs.versions.toml",
                 "backend/build.gradle.kts", APP_DOCKERFILE, ".dockerignore",
                 ".github/actions/setup-buildx/Dockerfile", ".github/scripts/app_version.py"):
        r = d([path, "frontend/src/main/C.java"])
        check(f"shared input {path} rebuilds all three", r["rebuild"] == list(MODULES))
    check("a release commit rebuilds all three, even docs-only",
          d(["CHANGELOG.md"], head_log=cut + old)["rebuild"] == list(MODULES))
    check("the all-zero base of a new branch rebuilds all three",
          decide(ZERO_SHA, head, dockerfile, [], old, old, True)["rebuild"] == list(MODULES))
    check("a non-ancestor base rebuilds all three", d([], anc=False)["rebuild"] == list(MODULES))
    check("an unreadable Dockerfile rebuilds all three", d([], df=None)["rebuild"] == list(MODULES))
    check("a failed diff rebuilds all three",
          decide(base, head, dockerfile, None, old, old, True)["rebuild"] == list(MODULES))
    r = d(["ingest/src/main/X.java"])
    check("rebuild and reuse partition the three modules",
          sorted(r["rebuild"] + r["reuse"]) == sorted(MODULES) and not set(r["rebuild"]) & set(r["reuse"]))
    r = decide(base, head, dockerfile, ["backend/Dockerfile"], old, old, True, LEGACY_OWN, LEGACY_SHARED)
    check("dry run: a legacy per-module Dockerfile rebuilds its module", r["rebuild"] == ["backend"])

    real = show("HEAD", APP_DOCKERFILE) or (REPO / APP_DOCKERFILE).read_text(encoding="utf-8")
    real_own, real_shared = input_sets(real)
    # Anti-vacuity: the split must still see the real Dockerfile's COPYs. A parser that found none
    # would make every change look input-free -- the one wrong answer.
    for m in MODULES:
        check(f"the real Dockerfile yields {m}/src/main as {m}'s own input", f"{m}/src/main" in real_own[m])
    for must in ("gradle", "logging-support/src/main", "build.gradle.kts", "backend/build.gradle.kts"):
        check(f"the real Dockerfile yields shared input {must}", must in real_shared)
    real_sources = copy_sources(real)
    for path in MODULE_OWN:
        check(f"MODULE_OWN entry {path} is still a COPY source", path in real_sources)

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
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args()
    if args.selftest:
        return selftest()
    if args.dry_run:
        return dry_run(args.dry_run, args.ref)
    if not args.head:
        parser.error("--head is required")
    print(json.dumps(decide_from_git(args.base or "", args.head)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
