#!/usr/bin/env python3
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
"""Fail when the build, a runtime, or any Java source opts into a non-final Java feature (ADR-0223).

ADR-0223 decision 1 allows only final language and library features: no ``--enable-preview`` and no
``jdk.incubator.*`` module in any build, test, image or launch configuration, and no
``import module`` declaration in any source set (Checkstyle covers ``main`` only). This check reads
every tracked text file except Markdown, the audit archive and itself, and reports each line that
names ``--enable-preview`` or ``jdk.incubator``, plus each ``import module`` line in a ``.java``
file. A selection floor fails the check when it scans fewer files than the repository holds today.

Usage::

    python3 scripts/check-final-java-only.py             # check the repository
    python3 scripts/check-final-java-only.py --selftest  # prove the check can fail
"""

from __future__ import annotations

import re
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SELF = Path(__file__).resolve()

MIN_FILES = 5000
MIN_JAVA_FILES = 3800

FLAGS = re.compile(r"--enable-preview|jdk\.incubator")
MODULE_IMPORT = re.compile(r"^[ \t]*import[ \t]+module[ \t]+[\w.]+[ \t]*;", re.M)
SKIPPED_SUFFIXES = (".md", ".png", ".jpg", ".jpeg", ".gif", ".ico", ".svg", ".woff", ".woff2",
                    ".ttf", ".jar", ".p12", ".pdf", ".zip")
SKIPPED_PREFIXES = ("docs/archive/",)


def tracked_files(root: Path) -> list[str]:
    """Lists the files git tracks under a root."""
    out = subprocess.run(["git", "-C", str(root), "ls-files"], capture_output=True, text=True,
                         check=True).stdout
    return [line for line in out.splitlines() if line]


def violations(root: Path, files: list[str]) -> tuple[list[str], int, int]:
    """Returns the findings and the counts of scanned files and Java files."""
    found = []
    scanned = 0
    java = 0
    for rel in files:
        if rel.endswith(SKIPPED_SUFFIXES) or rel.startswith(SKIPPED_PREFIXES):
            continue
        path = root / rel
        if path.resolve() == SELF or not path.is_file():
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        scanned += 1
        for number, line in enumerate(text.splitlines(), start=1):
            if FLAGS.search(line):
                found.append(f"{rel}:{number}: {line.strip()}")
        if rel.endswith(".java"):
            java += 1
            for match in MODULE_IMPORT.finditer(text):
                number = text.count("\n", 0, match.start()) + 1
                found.append(f"{rel}:{number}: {match.group(0).strip()}")
    return found, scanned, java


def check() -> int:
    """Checks the repository."""
    found, scanned, java = violations(REPO, tracked_files(REPO))
    if scanned < MIN_FILES or java < MIN_JAVA_FILES:
        print(f"check-final-java-only: scanned {scanned} files and {java} Java files, fewer than"
              f" the floors {MIN_FILES} and {MIN_JAVA_FILES}; the scan lost its selection")
        return 1
    if found:
        print("check-final-java-only: a non-final Java feature is opted into (ADR-0223):")
        for line in found:
            print("  " + line)
        return 1
    print(f"check-final-java-only: {scanned} files ({java} Java) use final Java features only")
    return 0


def selftest() -> int:
    """Plants every kind of violation in a scratch repository and requires each to be reported."""
    planted = {
        "build.gradle.kts": 'tasks.withType<JavaCompile> { options.compilerArgs.add("--enable-preview") }\n',
        "Dockerfile": 'ENV JAVA_TOOL_OPTIONS="--add-modules jdk.incubator.vector"\n',
        "src/test/java/a/A.java": "package a;\n\nimport module java.base;\n\nclass A {}\n",
        "src/main/java/a/Clean.java": "package a;\n\nimport java.util.List;\n\nclass Clean {}\n",
        "README.md": "never pass --enable-preview\n",
    }
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        subprocess.run(["git", "init", "-q", str(root)], check=True)
        for rel, text in planted.items():
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        subprocess.run(["git", "-C", str(root), "add", "."], check=True)
        found, _, _ = violations(root, tracked_files(root))
    expected = ["build.gradle.kts:1", "Dockerfile:1", "src/test/java/a/A.java:3"]
    missing = [e for e in expected if not any(f.startswith(e + ":") for f in found)]
    unexpected = [f for f in found if not any(f.startswith(e + ":") for e in expected)]
    if missing or unexpected:
        print(f"check-final-java-only selftest FAILED: missing {missing}, unexpected {unexpected}")
        return 1
    print("check-final-java-only selftest: every planted violation reported, the clean files pass")
    return 0


if __name__ == "__main__":
    sys.exit(selftest() if "--selftest" in sys.argv[1:] else check())
