#!/usr/bin/env python3
"""Profit Basetool - render the per-service environment files from their templates.

Renders ``quadlet/env.d/<service>.env.tmpl`` against the host ``.env`` into the files the Quadlet
units name in ``EnvironmentFile=``, one per service. Compose interpolation semantics, nesting
included::

    ${NAME}              unset -> empty
    ${NAME:-default}     default when unset or empty
    ${NAME-default}      default only when unset
    ${NAME:?message}     refuse when unset or empty
    ${NAME?message}      refuse only when unset

A refusal names every missing variable and writes nothing. Stale ``*.env`` files are removed.

Usage
-----
::

    render-env-d.py --env /var/iri/code/.env --templates quadlet/env.d --out /var/iri/code/env.d
    render-env-d.py ... --check     # report drift, write nothing

Exit codes: ``0`` clean, ``1`` a refusal or (under ``--check``) drift, ``2`` bad invocation.
"""

from __future__ import annotations

import argparse
import contextlib
import os
import sys
from typing import Sequence


class Refusal(Exception):
    """Raised when a template cannot be rendered correctly; the message names template and variable."""


def parse_env_file(path: str) -> dict[str, str]:
    """Read a ``.env`` into a mapping, the way compose reads one.

    Skips blanks and ``#`` comments, tolerates ``export``, strips one layer of matching quotes, and
    takes values literally.

    Args:
        path: absolute path to the ``.env``.

    Returns:
        The mapping. A key assigned more than once keeps its **last** value, matching compose.

    Raises:
        Refusal: if the file cannot be read at all.
    """
    try:
        with open(path, encoding="utf-8") as handle:
            lines = handle.readlines()
    except OSError as exc:
        raise Refusal(f"cannot read {path}: {exc}") from exc

    env: dict[str, str] = {}
    for raw in lines:
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[len("export "):].lstrip()
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        if not key:
            continue
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
            value = value[1:-1]
        env[key] = value
    return env


def _matching_brace(text: str, open_index: int) -> int:
    """Return the index of the ``}`` closing the ``{`` at ``open_index``.

    Counts nesting, so ``${A:-${B}}`` resolves to the outer brace.

    Args:
        text: the string being scanned.
        open_index: index of the opening ``{``.

    Returns:
        Index of the matching ``}``.

    Raises:
        Refusal: when the braces do not balance, which means a malformed template.
    """
    depth = 0
    for i in range(open_index, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return i
    raise Refusal(f"unbalanced ${{ ... }} in {text!r}")


def _split_operator(inner: str) -> tuple[str, str | None, str]:
    """Split ``NAME:-default`` into its three parts, ignoring operators inside a nested ``${}``.

    Args:
        inner: the text between ``${`` and its matching ``}``.

    Returns:
        ``(name, operator, rest)`` where operator is one of ``:-``, ``-``, ``:?``, ``?`` or None.
    """
    depth = 0
    for i, char in enumerate(inner):
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
        elif depth == 0 and char in (":", "-", "?"):
            if char == ":" and i + 1 < len(inner) and inner[i + 1] in ("-", "?"):
                return inner[:i], inner[i:i + 2], inner[i + 2:]
            if char in ("-", "?"):
                return inner[:i], char, inner[i + 1:]
    return inner, None, ""


def expand(text: str, env: dict[str, str], where: str, missing: list[str]) -> str:
    """Interpolate every ``${...}`` in ``text`` against ``env``.

    Args:
        text: the template text, one line or whole file.
        env: the host ``.env`` mapping.
        where: template name, used in refusal messages.
        missing: accumulator that absent required variables are appended to.

    Returns:
        The interpolated text.

    Raises:
        Refusal: on malformed syntax.
    """
    out: list[str] = []
    i = 0
    while i < len(text):
        if text[i] == "$" and i + 1 < len(text) and text[i + 1] == "{":
            close = _matching_brace(text, i + 1)
            inner = text[i + 2:close]
            name, op, rest = _split_operator(inner)
            value = env.get(name)
            if op in (":-", ":?"):
                unset = value is None or value == ""
            else:
                unset = value is None

            if op in (":-", "-"):
                out.append(expand(rest, env, where, missing) if unset else (value or ""))
            elif op in (":?", "?"):
                if unset:
                    note = expand(rest, env, where, missing).strip() or "must be set"
                    missing.append(f"{name} ({where}): {note}")
                    out.append("")
                else:
                    out.append(value or "")
            else:
                out.append(value or "")
            i = close + 1
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def render_one(tmpl_path: str, env: dict[str, str], missing: list[str]) -> str:
    """Render a single template file.

    Comment and blank lines pass through untouched.

    Args:
        tmpl_path: path to the ``.env.tmpl``.
        env: the host ``.env`` mapping.
        missing: accumulator for required-but-absent variables.

    Returns:
        The rendered file content.
    """
    name = os.path.basename(tmpl_path)
    with open(tmpl_path, encoding="utf-8") as handle:
        lines = handle.readlines()

    rendered: list[str] = []
    for line in lines:
        if line.lstrip().startswith("#") or not line.strip():
            rendered.append(line)
        else:
            rendered.append(expand(line, env, name, missing))
    return "".join(rendered)


def _stale(out_dir: str, produced: dict[str, str]) -> list[str]:
    """Rendered environment files in ``out_dir`` that no template produces any more.

    Only ``*.env`` files directly in ``out_dir`` are considered.

    Args:
        out_dir: the directory the rendered files live in.
        produced: service name -> rendered content, for this run.

    Returns:
        The stale file NAMES, sorted, or an empty list when the directory does not exist.
    """
    if not os.path.isdir(out_dir):
        return []
    expected = {f"{service}.env" for service in produced}
    return sorted(
        name for name in os.listdir(out_dir)
        if name.endswith(".env")
        and name not in expected
        and os.path.isfile(os.path.join(out_dir, name))
    )


def _write_env_file(path: str, content: str, mode: int) -> None:
    """Write one rendered ``<service>.env`` atomically via a sibling file and ``os.replace``.

    Replacing needs write permission on the directory only, not on the old file; the sibling
    inherits the directory's setgid group.

    Args:
        path: the final ``<service>.env`` path.
        content: the rendered text.
        mode: the permission bits, already parsed from ``--mode``.

    Raises:
        OSError: if the sibling cannot be written or renamed; the partial file is removed first.
    """
    directory = os.path.dirname(os.path.abspath(path)) or "."
    tmp = os.path.join(directory, f".{os.path.basename(path)}.{os.getpid()}.tmp")
    try:
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, mode)
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(content)
        os.chmod(tmp, mode)
        os.replace(tmp, path)
    except OSError:
        with contextlib.suppress(OSError):
            os.unlink(tmp)
        raise


def main(argv: Sequence[str] | None = None) -> int:
    """Render every template, or report drift under ``--check``.

    Args:
        argv: command-line arguments, defaulting to ``sys.argv[1:]``.

    Returns:
        ``0`` clean, ``1`` a refusal or drift, ``2`` a bad invocation.
    """
    parser = argparse.ArgumentParser(prog="render-env-d.py")
    parser.add_argument("--env", required=True, help="the host .env")
    parser.add_argument("--templates", required=True, help="directory holding <service>.env.tmpl")
    parser.add_argument("--out", required=True, help="directory to write <service>.env into")
    parser.add_argument("--check", action="store_true",
                        help="report what would change and write nothing")
    parser.add_argument("--mode", default="0640", help="mode for written files (default 0640)")
    args = parser.parse_args(argv)

    try:
        env = parse_env_file(args.env)
    except Refusal as exc:
        print(f"render-env-d: {exc}", file=sys.stderr)
        return 1

    templates = sorted(f for f in os.listdir(args.templates) if f.endswith(".env.tmpl"))
    if not templates:
        print(f"render-env-d: no *.env.tmpl under {args.templates}", file=sys.stderr)
        return 1

    missing: list[str] = []
    produced: dict[str, str] = {}
    try:
        for tmpl in templates:
            service = tmpl[: -len(".env.tmpl")]
            produced[service] = render_one(os.path.join(args.templates, tmpl), env, missing)
    except Refusal as exc:
        print(f"render-env-d: {exc}", file=sys.stderr)
        return 1

    if missing:
        print("render-env-d: REFUSING to write - required variables are unset in "
              f"{args.env}:", file=sys.stderr)
        for entry in sorted(set(missing)):
            print(f"  {entry}", file=sys.stderr)
        print("\nNothing was written. A container started against a half-rendered environment "
              "comes up on its image defaults, which looks like a working service.", file=sys.stderr)
        return 1

    stale = _stale(args.out, produced)

    if args.check:
        drift = []
        for service, content in produced.items():
            path = os.path.join(args.out, f"{service}.env")
            try:
                with open(path, encoding="utf-8") as handle:
                    if handle.read() != content:
                        drift.append(service)
            except OSError:
                drift.append(f"{service} (absent)")
        if drift or stale:
            if drift:
                print("render-env-d: DRIFT in " + ", ".join(sorted(drift)), file=sys.stderr)
            if stale:
                print("render-env-d: STALE, no template produces these: "
                      + ", ".join(sorted(stale)), file=sys.stderr)
            return 1
        print(f"render-env-d: {len(produced)} file(s) match the templates and the .env")
        return 0

    os.makedirs(args.out, exist_ok=True)
    mode = int(args.mode, 8)
    for service, content in produced.items():
        path = os.path.join(args.out, f"{service}.env")
        _write_env_file(path, content, mode)

    for name in sorted(stale):
        os.unlink(os.path.join(args.out, name))
        print(f"render-env-d: removed {name} - no template produces it any more")

    values = sum(1 for c in produced.values() for line in c.splitlines()
                 if line and not line.lstrip().startswith("#"))
    print(f"render-env-d: wrote {len(produced)} file(s), {values} assignment(s), into {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
