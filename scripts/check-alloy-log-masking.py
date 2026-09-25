#!/usr/bin/env python3
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
"""Validate the log-masking stages in ``monitoring/alloy/config.alloy``.

``stage.replace`` overwrites every capture group with the literal ``replace`` value and expands
no back-references, so each stage must satisfy (REQ-OBS-007):

1. no ``${`` in ``replace``;
2. at most one capturing group in ``expression`` (named groups count; ``(?:...)`` and flag groups
   do not). No group at all replaces the whole match and is accepted.

Usage:
    python scripts/check-alloy-log-masking.py [--config PATH]

Exits 0 when every stage is well-formed, 1 listing every offending stage, and 2 when the file
cannot be read or holds no ``stage.replace`` at all.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

DEFAULT_CONFIG = pathlib.Path("monitoring/alloy/config.alloy")

BLOCK_HEADER = re.compile(r"\bstage\.replace\b\s*\{")

ATTRIBUTE = re.compile(r'^[ \t]*(\w+)[ \t]*=[ \t]*"((?:[^"\\]|\\.)*)"', re.M)


def skip_string(text: str, i: int) -> int:
    """Return the index just past the string literal that starts at ``text[i]``.

    A ``"..."`` string honours backslash escapes; a backtick raw string ends at its next backtick.
    """
    quote = text[i]
    i += 1
    while i < len(text):
        if quote == '"' and text[i] == "\\":
            i += 2
            continue
        if text[i] == quote:
            return i + 1
        i += 1
    return i


def block_body(text: str, open_brace: int) -> tuple[str, int]:
    """Return the body of the block whose opening brace sits at ``open_brace``, and its end index.

    Braces inside string literals and ``//`` comments are ignored.
    """
    depth = 0
    i = open_brace
    while i < len(text):
        c = text[i]
        if c in '"`':
            i = skip_string(text, i)
            continue
        if c == "/" and text[i : i + 2] == "//":
            nl = text.find("\n", i)
            i = len(text) if nl == -1 else nl
            continue
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return text[open_brace + 1 : i], i + 1
        i += 1
    return text[open_brace + 1 :], len(text)


def unescape(literal: str) -> str:
    """Turn an Alloy double-quoted string literal into the regex the engine actually compiles."""
    out: list[str] = []
    i = 0
    simple = {"n": "\n", "t": "\t", "r": "\r", '"': '"', "\\": "\\"}
    while i < len(literal):
        c = literal[i]
        if c == "\\" and i + 1 < len(literal):
            nxt = literal[i + 1]
            out.append(simple.get(nxt, "\\" + nxt))
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def capturing_groups(pattern: str) -> int:
    """Count the capturing groups in an RE2 ``pattern``.

    Escaped parentheses, parentheses in a character class, and ``(?:...)`` / flag groups do not
    count; named groups ``(?P<n>...)`` and ``(?<n>...)`` do.
    """
    count = 0
    i = 0
    in_class = False
    while i < len(pattern):
        c = pattern[i]
        if c == "\\":
            i += 2
            continue
        if in_class:
            if c == "]":
                in_class = False
            i += 1
            continue
        if c == "[":
            in_class = True
        elif c == "(":
            rest = pattern[i + 1 :]
            if not rest.startswith("?"):
                count += 1
            elif rest.startswith("?P<") or rest.startswith("?<"):
                count += 1
        i += 1
    return count


def replace_stages(text: str):
    """Yield ``(line_no, attributes)`` for every ``stage.replace`` block in ``text``, nested ones included."""
    for match in BLOCK_HEADER.finditer(text):
        open_brace = match.end() - 1
        body, _ = block_body(text, open_brace)
        attrs = {name: value for name, value in ATTRIBUTE.findall(body)}
        yield text.count("\n", 0, match.start()) + 1, attrs


def main() -> int:
    """Check every masking stage and report each rule it breaks, with its line number."""
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--config",
        type=pathlib.Path,
        default=DEFAULT_CONFIG,
        help="path to the Alloy configuration (default: %(default)s)",
    )
    args = parser.parse_args()

    if not args.config.is_file():
        print("FATAL: %s does not exist" % args.config, file=sys.stderr)
        return 2

    text = args.config.read_text(encoding="utf-8")
    stages = list(replace_stages(text))
    if not stages:
        print(
            "FATAL: %s contains no stage.replace blocks -- either the masking was removed or this "
            "checker no longer finds it. Both are a problem; neither is a pass." % args.config,
            file=sys.stderr,
        )
        return 2

    problems: list[str] = []
    for line_no, attrs in stages:
        where = "%s:%d" % (args.config.as_posix(), line_no)
        expression = attrs.get("expression")
        if expression is None:
            problems.append("%s: stage.replace has no expression" % where)
            continue

        if "${" in attrs.get("replace", ""):
            problems.append(
                "%s: replace = \"%s\" contains '${' -- stage.replace expands no back-references, so "
                "those characters are written into Loki verbatim. Drop the reference and keep the "
                "field name outside the capture group." % (where, unescape(attrs["replace"]))
            )

        groups = capturing_groups(unescape(expression))
        if groups > 1:
            problems.append(
                "%s: expression = \"%s\" has %d capturing groups -- stage.replace overwrites EVERY one "
                "of them, so the field name in group 1 is destroyed along with the value. Wrap only "
                "the value; use (?:...) for the rest." % (where, unescape(expression), groups)
            )

    if problems:
        print(
            "Alloy log-masking check FAILED (%d problem(s) in %d stage.replace block(s)):\n"
            % (len(problems), len(stages)),
            file=sys.stderr,
        )
        for problem in problems:
            print("  - %s" % problem, file=sys.stderr)
        print(
            "\nREQ-OBS-007: a mask puts exactly the value in one capture group and nothing else.",
            file=sys.stderr,
        )
        return 1

    print(
        "Alloy log masking OK: %d stage.replace block(s), each with at most one capture group and "
        "no back-reference in its replacement." % len(stages)
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
