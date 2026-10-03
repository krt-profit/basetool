#!/usr/bin/env python3
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
"""Fail when a build script declares a task input on a path that does not exist (REQ-OPS-038).

A ``inputs.files(...)`` entry whose file was moved or deleted is dropped by Gradle without a word: the
task then stays UP-TO-DATE or FROM-CACHE when the real file changes, and a test that reads the path
may skip what it no longer finds. This check reads every tracked ``*.gradle.kts``, finds each
``inputs.file``, ``inputs.files`` and ``inputs.dir`` call, resolves the source paths it names (string
literals, ``file(...)``, ``fileTree(...)``, ``layout.projectDirectory.file|dir(...)``,
``rootProject.file|fileTree(...)``, and a ``val`` holding one of these) and fails on any that is
missing. ``layout.buildDirectory`` outputs and interpolated strings are not source paths and are
skipped. A selection floor fails the check when the parser stops finding today's references.

Usage::

    python3 scripts/check-gradle-input-paths.py             # check the repository
    python3 scripts/check-gradle-input-paths.py --selftest  # prove the check can fail
    python3 scripts/check-gradle-input-paths.py --list      # also list every path found
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]

MIN_REFERENCES = 56

INPUT_CALL = re.compile(r"\binputs\s*\.\s*(?:file|files|dir)\s*\(")

PATH_CALLS = (
    (re.compile(r"rootProject\s*\.\s*(?:layout\s*\.\s*projectDirectory\s*\.\s*)?(?:file|files|fileTree|dir)\s*\(\s*"), "root"),
    (re.compile(r"(?<![\w.])layout\s*\.\s*projectDirectory\s*\.\s*(?:file|dir)\s*\(\s*"), "module"),
    (re.compile(r"(?<![\w.])(?:project\s*\.\s*)?(?:file|files|fileTree)\s*\(\s*"), "module"),
)

STRING = re.compile(r'"((?:[^"\\]|\\.)*)"')
IDENT = re.compile(r"[A-Za-z_]\w*")
VAL_DECL = re.compile(r"^\s*val\s+([A-Za-z_]\w*)\s*(?::[^=\n]+)?=", re.M)


@dataclass(frozen=True)
class Reference:
    """One source path a build script declares as a task input."""

    script: str
    line: int
    path: str
    base: str


def balanced(text: str, start: int) -> int:
    """Return the index just past the parenthesis that closes the one opened before ``start``.

    :param text: the script text.
    :param start: the index just after an opening parenthesis.
    :return: the index after the matching closing parenthesis, or ``len(text)``.
    """
    depth = 1
    i = start
    while i < len(text) and depth:
        c = text[i]
        if c == '"':
            m = STRING.match(text, i)
            i = m.end() if m else i + 1
            continue
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        i += 1
    return i


def val_expressions(text: str) -> dict[str, str]:
    """Map every top-level-looking ``val name = expression`` of a script to its expression text.

    An expression ends at a line break outside any parenthesis whose next line does not continue it
    with a leading ``.``.

    :param text: the script text.
    :return: name to expression.
    """
    out: dict[str, str] = {}
    for m in VAL_DECL.finditer(text):
        i = m.end()
        depth = 0
        while i < len(text):
            c = text[i]
            if c == '"':
                s = STRING.match(text, i)
                i = s.end() if s else i + 1
                continue
            if c in "({":
                depth += 1
            elif c in ")}":
                depth -= 1
            elif c == "\n" and depth <= 0 and text[m.end():i].strip():
                rest = text[i + 1:].lstrip(" \t")
                if not rest.startswith("."):
                    break
            i += 1
        out.setdefault(m.group(1), text[m.end():i])
    return out


def top_level_args(args: str) -> list[str]:
    """Split a call's argument text on the commas outside nested parentheses and strings.

    :param args: the text between a call's parentheses.
    :return: each argument, stripped.
    """
    parts: list[str] = []
    depth = 0
    cur = []
    i = 0
    while i < len(args):
        c = args[i]
        if c == '"':
            s = STRING.match(args, i)
            end = s.end() if s else i + 1
            cur.append(args[i:end])
            i = end
            continue
        if c in "({":
            depth += 1
        elif c in ")}":
            depth -= 1
        if c == "," and depth == 0:
            parts.append("".join(cur).strip())
            cur = []
        else:
            cur.append(c)
        i += 1
    if "".join(cur).strip():
        parts.append("".join(cur).strip())
    return parts


def paths_in(expr: str, vals: dict[str, str], depth: int = 0) -> list[tuple[str, str]]:
    """Resolve the source paths an input expression names.

    :param expr: an argument or ``val`` expression.
    :param vals: the script's ``val`` expressions.
    :param depth: the ``val`` indirection depth, bounded to stop cycles.
    :return: ``(path, base)`` pairs, ``base`` being ``root`` or ``module``.
    """
    if depth > 4 or "buildDirectory" in expr:
        return []
    found: list[tuple[str, str]] = []
    claimed: set[int] = set()
    for pattern, base in PATH_CALLS:
        for m in pattern.finditer(expr):
            if m.start() in claimed:
                continue
            claimed.update(range(m.start(), m.end()))
            arg = expr[m.end():]
            lit = STRING.match(arg)
            if lit:
                if "$" not in lit.group(1):
                    found.append((lit.group(1), base))
                continue
            ident = IDENT.match(arg)
            if ident and ident.group(0) in vals:
                inner = vals[ident.group(0)].strip()
                inner_lit = STRING.fullmatch(inner)
                if inner_lit and "$" not in inner_lit.group(1):
                    found.append((inner_lit.group(1), base))
                else:
                    found.extend(paths_in(inner, vals, depth + 1))
    return found


def references(script: str, text: str) -> list[Reference]:
    """List every source path the ``inputs`` calls of one script declare.

    :param script: the script path relative to the repository root, ``/``-separated.
    :param text: the script text.
    :return: the references, in order of appearance.
    """
    vals = val_expressions(text)
    out: list[Reference] = []
    for m in INPUT_CALL.finditer(text):
        end = balanced(text, m.end())
        args = text[m.end():end - 1]
        line = text.count("\n", 0, m.start()) + 1
        for arg in top_level_args(args):
            lit = STRING.fullmatch(arg)
            if lit:
                if "$" not in lit.group(1):
                    out.append(Reference(script, line, lit.group(1), "module"))
                continue
            if IDENT.fullmatch(arg) and arg in vals:
                inner = vals[arg].strip()
                inner_lit = STRING.fullmatch(inner)
                if inner_lit:
                    if "$" not in inner_lit.group(1):
                        out.append(Reference(script, line, inner_lit.group(1), "module"))
                    continue
                pairs = paths_in(inner, vals, 1)
            else:
                pairs = paths_in(arg, vals)
            out.extend(Reference(script, line, p, b) for p, b in pairs)
    return out


def missing(refs: list[Reference], root: Path) -> list[str]:
    """Return a message for every reference whose path does not exist.

    :param refs: the references to check.
    :param root: the repository root the ``root`` base and every script path resolve against.
    :return: one message per missing path.
    """
    problems = []
    for ref in refs:
        base = root if ref.base == "root" else (root / ref.script).parent
        target = Path(os.path.normpath(base / ref.path))
        if not target.exists():
            problems.append(f"{ref.script}:{ref.line}: input path '{ref.path}' does not exist ({target})")
    return problems


def tracked_scripts(root: Path) -> list[str]:
    """List the tracked Gradle Kotlin scripts.

    :param root: the repository root.
    :return: ``/``-separated paths relative to the root.
    """
    result = subprocess.run(
        ["git", "ls-files", "*.gradle.kts"], cwd=root, capture_output=True, text=True, check=True
    )
    return sorted(p for p in result.stdout.splitlines() if p)


def check(root: Path, verbose: bool = False) -> int:
    """Check every tracked script of a repository.

    :param root: the repository root.
    :param verbose: whether to list every reference found.
    :return: 0 when every path exists and the floor holds, 1 otherwise.
    """
    refs: list[Reference] = []
    for script in tracked_scripts(root):
        refs.extend(references(script, (root / script).read_text(encoding="utf-8")))
    problems = missing(refs, root)
    print(f"check-gradle-input-paths: {len(refs)} input path(s) in build scripts")
    if verbose:
        for ref in refs:
            print(f"  {ref.script}:{ref.line} [{ref.base}] {ref.path}")
    if len(refs) < MIN_REFERENCES:
        problems.append(
            f"only {len(refs)} input paths found, fewer than the floor of {MIN_REFERENCES}: the parser"
            " no longer sees the build scripts' inputs"
        )
    for p in problems:
        print(f"::error title=gradle-input-paths::{p}")
    return 1 if problems else 0


def selftest() -> int:
    """Prove the parser finds each reference shape and that a missing path fails.

    :return: 0 when every case holds, 1 otherwise.
    """
    failures = 0

    def expect(name: str, cond: bool) -> None:
        nonlocal failures
        print(f"  [{'ok ' if cond else 'FAIL'}] {name}")
        failures += not cond

    script = (
        'val mirrorDir = "backend/dto"\n'
        'val probe =\n  layout.projectDirectory.file(\n    "src/probe.js"\n  )\n'
        'val generated = layout.buildDirectory.file("gen/x.js")\n'
        'val spec = rootProject.file("backend/openapi.json")\n'
        'tasks.named<Test>("test") {\n'
        '  inputs\n    .files(rootProject.fileTree(mirrorDir) { include("*.java") })\n'
        '    .withPropertyName("mirror")\n'
        '  inputs.file("package.json")\n'
        '  inputs.file(probe)\n'
        '  inputs.file(generated)\n'
        '  inputs.file(spec)\n'
        '  inputs.dir(layout.projectDirectory.dir("../docs/exchange"))\n'
        '  inputs.files(fileTree("src/css") { include("**/*.css") }, file("oss.json"))\n'
        '  inputs.file("docs/${project.name}.json")\n'
        '}\n'
    )
    refs = references("frontend/build.gradle.kts", script)
    got = {(r.path, r.base) for r in refs}
    expect("a val holding a string, used in rootProject.fileTree, resolves against the root",
           ("backend/dto", "root") in got)
    expect("a bare string argument resolves against the module", ("package.json", "module") in got)
    expect("a multi-line val holding layout.projectDirectory.file resolves", ("src/probe.js", "module") in got)
    expect("a val holding rootProject.file resolves against the root", ("backend/openapi.json", "root") in got)
    expect("inputs.dir with a relative parent resolves", ("../docs/exchange", "module") in got)
    expect("fileTree and file in one call both resolve", {("src/css", "module"), ("oss.json", "module")} <= got)
    expect("an include pattern is not taken for a path", not any("*" in p for p, _ in got))
    expect("a buildDirectory output is skipped", not any(p.startswith("gen/") for p, _ in got))
    expect("an interpolated string is skipped", not any("$" in p for p, _ in got))
    expect("exactly the seven source paths are found", len(got) == 7)

    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / "frontend" / "src" / "css").mkdir(parents=True)
        (root / "backend" / "dto").mkdir(parents=True)
        (root / "docs" / "exchange").mkdir(parents=True)
        for f in ("frontend/package.json", "frontend/src/probe.js", "backend/openapi.json", "frontend/oss.json"):
            (root / f).write_text("x", encoding="utf-8")
        expect("every planted path exists: no problem", missing(refs, root) == [])
        (root / "backend" / "openapi.json").unlink()
        problems = missing(refs, root)
        expect("a deleted input file is reported", len(problems) == 1 and "backend/openapi.json" in problems[0])
        (root / "docs" / "exchange").rmdir()
        expect("a deleted input directory is reported", len(missing(refs, root)) == 2)

    print("selftest: FAILED" if failures else "selftest: passed")
    return 1 if failures else 0


def main(argv: list[str]) -> int:
    """Run the check or its self-test.

    :param argv: the command-line arguments.
    :return: the exit code.
    """
    if "--selftest" in argv:
        return selftest()
    return check(REPO, "--list" in argv)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
