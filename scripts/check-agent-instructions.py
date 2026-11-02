#!/usr/bin/env python3
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
"""Fail when the agent instructions outgrow the reading limit or a skill has no matching stub.

REQ-OPS-055, ADR-0327.

* **Size.** An agent that reads ``AGENTS.md`` concatenates the file of every directory from the
  repository root down to its working directory, and stops at ``project_doc_max_bytes`` (32 KiB by
  default). For every tracked ``AGENTS.md``, the files on its path from the root must stay within
  ``LIMIT_BYTES``.
* **Skill stubs.** Every skill ``.claude/skills/<name>/`` has a stub
  ``.agents/skills/<name>/SKILL.md`` whose frontmatter ``name`` and ``description`` equal the
  original's and whose body names the original's path; no stub exists without its skill. A skill
  whose ``SKILL.md`` is absent (an uninitialised submodule) is compared by ``name`` only.

Usage::

    python3 scripts/check-agent-instructions.py            # check the repository
    python3 scripts/check-agent-instructions.py --selftest # prove every rule can fail
"""

from __future__ import annotations

import pathlib
import subprocess
import sys
import tempfile

import yaml

LIMIT_BYTES = 32 * 1024
INSTRUCTIONS = "AGENTS.md"
SKILLS_DIR = pathlib.PurePosixPath(".claude/skills")
STUBS_DIR = pathlib.PurePosixPath(".agents/skills")
SKILL_FILE = "SKILL.md"
COMPARED_KEYS = ("name", "description")


def tracked_instructions(root: pathlib.Path) -> list[pathlib.PurePosixPath]:
    """Repository-relative paths of every ``AGENTS.md``, from git when the root is a work tree."""
    try:
        out = subprocess.run(
            ["git", "-C", str(root), "ls-files", "-z", "--", f"*{INSTRUCTIONS}", INSTRUCTIONS],
            check=True,
            capture_output=True,
            text=True,
        ).stdout
        names = {name for name in out.split("\0") if name}
    except (OSError, subprocess.CalledProcessError):
        names = set()
    if not names:
        names = {p.relative_to(root).as_posix() for p in root.rglob(INSTRUCTIONS)}
    return sorted(
        pathlib.PurePosixPath(name) for name in names if pathlib.PurePosixPath(name).name == INSTRUCTIONS
    )


def check_sizes(root: pathlib.Path) -> list[str]:
    """The size rule: each chain of ``AGENTS.md`` files from the root stays within the limit."""
    problems: list[str] = []
    files = tracked_instructions(root)
    present = set(files)
    for leaf in files:
        chain = [parent / INSTRUCTIONS for parent in reversed(leaf.parents)]
        chain = [path for path in chain if path in present]
        total = sum((root / path).stat().st_size for path in chain)
        if total > LIMIT_BYTES:
            listed = " + ".join(str(path) for path in chain)
            problems.append(
                f"{leaf}: {listed} = {total} bytes, above the {LIMIT_BYTES}-byte limit an agent reads; "
                "move long sections into docs/agents/ and link them"
            )
    return problems


def frontmatter(text: str) -> dict | None:
    """The YAML frontmatter of a ``SKILL.md``, or ``None`` when it has none."""
    lines = text.splitlines()
    if not lines or lines[0].strip() != "---":
        return None
    try:
        end = next(i for i in range(1, len(lines)) if lines[i].strip() == "---")
    except StopIteration:
        return None
    data = yaml.safe_load("\n".join(lines[1:end])) or {}
    return data if isinstance(data, dict) else None


def submodule_skills(root: pathlib.Path) -> set[str]:
    """Skill names registered as submodules in ``.gitmodules``, which a checkout may leave out."""
    gitmodules = root / ".gitmodules"
    if not gitmodules.is_file():
        return set()
    names: set[str] = set()
    for line in gitmodules.read_text(encoding="utf-8").splitlines():
        key, _, value = line.partition("=")
        path = pathlib.PurePosixPath(value.strip())
        if key.strip() == "path" and path.parent == SKILLS_DIR:
            names.add(path.name)
    return names


def check_skills(root: pathlib.Path) -> list[str]:
    """The stub rule for every skill and every stub."""
    problems: list[str] = []
    skills_dir = root / SKILLS_DIR
    stubs_dir = root / STUBS_DIR
    found = {p.name for p in skills_dir.iterdir() if p.is_dir()} if skills_dir.is_dir() else set()
    skills = sorted(found | submodule_skills(root))
    stubs =sorted(p.name for p in stubs_dir.iterdir() if p.is_dir()) if stubs_dir.is_dir() else []

    for name in skills:
        original_path = SKILLS_DIR / name / SKILL_FILE
        stub_path = STUBS_DIR / name / SKILL_FILE
        if not (root / stub_path).is_file():
            problems.append(f"{original_path}: no stub at {stub_path}")
            continue
        stub_text = (root / stub_path).read_text(encoding="utf-8")
        stub = frontmatter(stub_text)
        if stub is None:
            problems.append(f"{stub_path}: no YAML frontmatter")
            continue
        if str(original_path) not in stub_text:
            problems.append(f"{stub_path}: the body does not name {original_path}")
        if not (root / original_path).is_file():
            if stub.get("name") != name:
                problems.append(f"{stub_path}: name is {stub.get('name')!r}, expected {name!r}")
            print(f"::notice title=Agent skills::{original_path} is absent; compared {stub_path} by name only")
            continue
        original = frontmatter((root / original_path).read_text(encoding="utf-8"))
        if original is None:
            problems.append(f"{original_path}: no YAML frontmatter")
            continue
        for key in COMPARED_KEYS:
            if stub.get(key) != original.get(key):
                problems.append(f"{stub_path}: {key} differs from {original_path}")

    for name in stubs:
        if name not in skills:
            problems.append(f"{STUBS_DIR / name}: no skill {SKILLS_DIR / name} for this stub")
    return problems


def check(root: pathlib.Path) -> list[str]:
    """Every rule against one repository root."""
    return check_sizes(root) + check_skills(root)


def write(root: pathlib.Path, rel: str, text: str) -> None:
    """Write one fixture file with LF line endings."""
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")


def skill(name: str, description: str) -> str:
    """A fixture skill file."""
    return f"---\nname: {name}\ndescription: {description}\n---\n\nBody.\n"


def stub(name: str, description: str) -> str:
    """A fixture stub that names its skill."""
    return f"---\nname: {name}\ndescription: {description}\n---\n\nRead `.claude/skills/{name}/SKILL.md`.\n"


def fixture(root: pathlib.Path) -> None:
    """A passing repository: root and module instructions, one skill, one absent submodule skill."""
    write(root, "AGENTS.md", "root\n")
    write(root, "backend/AGENTS.md", "backend\n")
    write(root, ".claude/skills/lint/SKILL.md", skill("lint", "Run the gate."))
    write(root, ".agents/skills/lint/SKILL.md", stub("lint", "Run the gate."))
    (root / ".claude/skills/design").mkdir(parents=True)
    write(root, ".agents/skills/design/SKILL.md", stub("design", "Brand."))


def selftest() -> int:
    """Run every case against a fresh fixture and fail unless each yields its expected count."""
    cases = {
        "a clean fixture passes": (lambda r: None, 0),
        "root plus module above the limit": (
            lambda r: write(r, "backend/AGENTS.md", "x" * (LIMIT_BYTES - 4)),
            1,
        ),
        "root alone above the limit": (lambda r: write(r, "AGENTS.md", "x" * (LIMIT_BYTES + 1)), 2),
        "a skill without stub": (lambda r: write(r, ".claude/skills/new/SKILL.md", skill("new", "N.")), 1),
        "a stub with another description": (
            lambda r: write(r, ".agents/skills/lint/SKILL.md", stub("lint", "Something else.")),
            1,
        ),
        "a stub with another name": (
            lambda r: write(r, ".agents/skills/lint/SKILL.md", stub("lynt", "Run the gate.")),
            2,
        ),
        "a stub that does not name its skill": (
            lambda r: write(r, ".agents/skills/lint/SKILL.md", skill("lint", "Run the gate.")),
            1,
        ),
        "a stub without skill": (lambda r: write(r, ".agents/skills/gone/SKILL.md", stub("gone", "G.")), 1),
        "a submodule skill missing from the checkout without stub": (
            lambda r: write(r, ".gitmodules", '[submodule "b"]\n\tpath = .claude/skills/brand\n'),
            1,
        ),
        "an absent submodule skill with a wrong stub name": (
            lambda r: write(r, ".agents/skills/design/SKILL.md", stub("dezign", "Brand.").replace(
                ".claude/skills/dezign/", ".claude/skills/design/")),
            1,
        ),
    }
    failed = 0
    for label, (mutate, expected) in cases.items():
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            fixture(root)
            mutate(root)
            problems = check(root)
            if len(problems) != expected:
                failed += 1
                print(f"FAIL {label}: expected {expected} problem(s), got {problems}")
            else:
                print(f"ok   {label}")
    return 1 if failed else 0


def main(argv: list[str]) -> int:
    """Check the repository, or run the self-test with ``--selftest``."""
    if argv[1:] == ["--selftest"]:
        return selftest()
    if argv[1:]:
        print(__doc__)
        return 2
    root = pathlib.Path(__file__).resolve().parent.parent
    problems = check(root)
    for problem in problems:
        print(f"::error title=Agent instructions::{problem}")
    if not problems:
        print("Agent instructions within the size limit; every skill has a matching stub.")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
