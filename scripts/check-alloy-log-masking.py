#!/usr/bin/env python3
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
#
"""Validate the log-masking stages in ``monitoring/alloy/config.alloy``.

WHY THIS GATE EXISTS
--------------------
Alloy's ``stage.replace`` does **not** do what its name and its syntax both suggest. It does not
substitute the whole match, and it expands **no** back-references. It walks *every capture group*
of every match and overwrites each one with the literal ``replace`` value. ``${1}`` is not a
reference to group 1 -- it is emitted as the four characters ``${1}``.

So the natural way to write a mask -- keep the field name in group 1 so it survives the scrub::

    expression = "(username=)(\\\\S+)"
    replace    = "${1}***"

destroys the field name *and* prints the template text. Both groups are overwritten, and the line
reaches Loki as::

    userId="...", ${1}***${1}*** error="invalid_user_credentials"

with nothing left to say which field had been scrubbed. Eight of the fourteen stages in that file
were written that way, and had been since the day each was added. Nothing caught it: the stages are
syntactically valid, ``alloy fmt`` is happy, the shipper is healthy, the stream is not silent, and
the masked value really is gone -- the only symptom is the *replacement text*, which no alert and no
dashboard reads. It was found on **2026-09-20** by exporting production logs and reading them by
hand, which is not a control anybody should have to rely on twice.

That is what this gate asserts, on two rules:

1. **No ``${`` in ``replace``.** There is no back-reference expansion, so any ``${...}`` is a
   literal that will be written into Loki.
2. **At most one capturing group in ``expression``.** Every group is overwritten, so a mask must
   put *only the value* in a group and keep the field name outside it -- as a literal prefix, or as
   ``(?:...)`` where an alternation needs grouping. ``(?:...)`` and flag groups like ``(?i)`` do not
   capture and do not count; named groups (``(?P<n>...)``) do.

An expression with **no** group at all is accepted: Alloy then replaces the entire match, which is
the same thing as one group wrapped around the whole pattern (the JWT and e-mail stages are written
that way). It is the *second* group that is always a defect.

This is a text check on purpose. Alloy will not tell us -- the broken and the correct form are both
valid configuration, which is precisely why this survived in production for as long as the stages
have existed. Running the stages for real would need a Loki sink and a fixture log stream; the
defect is visible in the file, so it is caught in the file.

Spec: REQ-OBS-007 (per-stream log-privacy rules). Vault: ``10 Systems/Observability.md``.

Usage:
    python scripts/check-alloy-log-masking.py [--config PATH]

Exits 0 when every stage is well-formed, 1 listing every offending stage, and 2 when the file
cannot be read or holds no ``stage.replace`` at all (a gate that checks nothing must not pass).
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

DEFAULT_CONFIG = pathlib.Path("monitoring/alloy/config.alloy")

#: Matches the ``stage.replace`` block header, up to and including its opening brace.
BLOCK_HEADER = re.compile(r"\bstage\.replace\b\s*\{")

#: Matches one ``name = "value"`` attribute line, honouring backslash escapes inside the value.
ATTRIBUTE = re.compile(r'^[ \t]*(\w+)[ \t]*=[ \t]*"((?:[^"\\]|\\.)*)"', re.M)


def skip_string(text: str, i: int) -> int:
    """Return the index just past the string literal that starts at ``text[i]``.

    Alloy strings come in two forms and a brace inside either one is not a brace: ``"..."`` honours
    backslash escapes, while a backtick-quoted raw string ends at its next backtick. Brace matching
    that does not know this walks straight out of the block on a pattern like ``\\S{2,}``.
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

    Counts braces while skipping string literals and ``//`` comments, so a brace that appears in a
    regex quantifier or in the file's very talkative comments cannot close the block early.
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
    """Turn an Alloy double-quoted string literal into the regex the engine actually compiles.

    The file writes RE2 patterns inside Alloy strings, so ``\\\\S`` on disk is the two characters
    ``\\S`` in the pattern. Counting groups on the raw literal would misread ``\\\\(`` -- an escaped,
    non-capturing parenthesis -- as a group.
    """
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

    Deliberately hand-written rather than delegated to ``re.compile(...).groups``: several of these
    patterns are RE2 rather than Python (and ``re`` would reject or silently reinterpret some), and
    the count has to be right about the three things that look like groups and are not -- an escaped
    ``\\(``, a parenthesis inside a character class (``[(]``), and the non-capturing and flag forms
    ``(?:...)`` / ``(?i)`` / ``(?i:...)``. Named groups ``(?P<n>...)`` and ``(?<n>...)`` *do*
    capture, and RE2 overwrites them exactly like a numbered one.
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
    """Yield ``(line_no, attributes)`` for every ``stage.replace`` block in ``text``.

    Finds nested blocks too: more than half of these stages live inside a ``stage.match`` that gates
    them to one ``app`` label, and a scanner that only looked at top level would check the Keycloak
    file mask while missing every container mask -- including the ``keycloak-stdout`` copy of the
    very same patterns.
    """
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
