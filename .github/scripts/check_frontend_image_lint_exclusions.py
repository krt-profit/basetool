#!/usr/bin/env python3
"""Every Node-based frontend task must be excluded from the frontend image build.

``frontend/Dockerfile`` runs ``./gradlew :frontend:build`` with each lint and format task excluded
**by name**. The image has no reason to lint — and cannot: those tasks need ``npmInstall`` and the
Node toolchain the runtime stage does not carry. So a task added to ``check`` is silently added to
the image build too, and fails it.

That is not a theoretical risk. ``lintCssInline`` was added on 2026-09-13 and not named here, and
the failure mode is as indirect as it gets: the image build failed, so the e2e stack's
``docker compose up --wait`` never saw a healthy service, so **all 94 e2e test classes** reported
``initializationError`` — with the real cause appearing only in ``compose-up.log`` inside the
uploaded artifact. Nothing in the build output said "lint".

The rule this enforces: **every ``NpxTask`` registered in ``frontend/build.gradle.kts`` appears as
``-x <name>`` in ``frontend/Dockerfile``.** Those are exactly the tasks that shell out to Node.

Run it with ``python3 .github/scripts/check_frontend_image_lint_exclusions.py``, and its self-test
with ``--selftest``.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
BUILD = REPO / "frontend" / "build.gradle.kts"
DOCKERFILE = REPO / "frontend" / "Dockerfile"

# `tasks.register<NpxTask>("lintCss") {` — the Node-backed tasks, whatever they are called.
NPX_TASK = re.compile(r"tasks\.register<NpxTask>\(\s*\"([A-Za-z0-9_]+)\"")
# `-x lintCss \` in the image's gradle invocation.
EXCLUDED = re.compile(r"-x\s+([A-Za-z0-9_]+)")
# `tasks.named("check").configure { dependsOn( ... ) }` — what `build` actually runs.
CHECK_BLOCK = re.compile(
    r"tasks\.named\(\s*\"check\"\s*\)\s*\.configure\s*\{(.*?)\n\}", re.DOTALL
)


def node_tasks(text: str) -> set[str]:
    """Node-backed tasks that `check` depends on — the only ones the image build can reach.

    An NpxTask nothing depends on (an `*Apply` formatter, say) never runs during `:frontend:build`,
    so requiring it to be excluded would be a false positive. `prettierApply` is exactly that case.
    """
    npx = set(NPX_TASK.findall(text))
    wired = set()
    for block in CHECK_BLOCK.findall(text):
        wired.update(re.findall(r"[A-Za-z0-9_]+", block))
    return npx & wired


def excluded_tasks(text: str) -> set[str]:
    """Exclusions the build actually passes — COMMENT LINES DO NOT COUNT.

    The prose above the RUN restates the same list for a reader, so scanning the whole file let a
    comment satisfy the gate: deleting the real `-x lintCssInline` line and leaving the sentence
    above it intact passed the check. A gate a comment can satisfy is not one.
    """
    lines = [line for line in text.splitlines() if not line.lstrip().startswith("#")]
    return set(EXCLUDED.findall("\n".join(lines)))


def compare(tasks: set[str], excluded: set[str]) -> list[str]:
    """Every Node task must be excluded. Extra exclusions are fine — they cover non-Npx tasks."""
    return [
        f"`{name}` is an NpxTask in frontend/build.gradle.kts but frontend/Dockerfile does not "
        f"pass `-x {name}`, so it runs during the image build and fails it"
        for name in sorted(tasks - excluded)
    ]


def selftest() -> int:
    cases = [
        ({"lintCss"}, {"lintCss"}, 0),
        ({"lintCss", "lintCssInline"}, {"lintCss"}, 1),
        ({"lintCss"}, {"lintCss", "test", "checkstyleMain"}, 0),
        (set(), {"test"}, 0),
        ({"a", "b"}, set(), 2),
    ]
    for tasks, excluded, expected in cases:
        found = compare(tasks, excluded)
        if len(found) != expected:
            print(f"SELFTEST FAIL: {tasks} vs {excluded}: expected {expected}, got {found}")
            return 1

    # And the real files must parse to something, or the regexes have rotted against a refactor.
    parsed = node_tasks(BUILD.read_text(encoding="utf-8"))
    if not parsed:
        print("SELFTEST FAIL: no NpxTask is wired into `check` in frontend/build.gradle.kts")
        return 1
    if not excluded_tasks(DOCKERFILE.read_text(encoding="utf-8")):
        print("SELFTEST FAIL: no `-x <task>` found in frontend/Dockerfile")
        return 1
    print(f"selftest: ok ({len(parsed)} Node tasks parsed)")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()

    tasks = node_tasks(BUILD.read_text(encoding="utf-8"))
    excluded = excluded_tasks(DOCKERFILE.read_text(encoding="utf-8"))
    problems = compare(tasks, excluded)

    if problems:
        print("FAIL: the frontend image build would run a Node-based task:")
        for problem in problems:
            print(f"  - {problem}")
        return 1

    print(f"ok: all {len(tasks)} Node-based frontend tasks are excluded from the image build")
    return 0


if __name__ == "__main__":
    sys.exit(main())
