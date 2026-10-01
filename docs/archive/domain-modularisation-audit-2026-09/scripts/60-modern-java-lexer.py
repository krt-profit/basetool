"""Java lexer and structural helpers shared by the 60-modern-java scanners.

lex() blanks comments and the contents of string, text-block and char literals while keeping
every newline and every offset, so regexes over the returned code never match inside a literal or
a comment and line numbers stay exact.
"""

import bisect
import os
import re

ROOT = r"$REPO"
MODULES = ["backend", "frontend", "ingest", "keycloak-spi", "logging-support", "test-support"]
SETS = ["main", "test", "e2e"]

def lex(src):
    """Return (code, strings, comments) for one Java source text."""
    n = len(src)
    out = []
    strings = []
    comments = []
    i = 0
    line = 1
    while i < n:
        c = src[i]
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            j = src.find("\n", i)
            if j == -1:
                j = n
            text = src[i:j]
            kind = "mddoc" if text.startswith("///") and not text.startswith("////") else "line"
            comments.append((line, kind, text, i))
            out.append(" " * (j - i))
            i = j
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "*":
            j = src.find("*/", i + 2)
            if j == -1:
                j = n - 2
            text = src[i:j + 2]
            kind = "javadoc" if text.startswith("/**") and text != "/**/" else "block"
            comments.append((line, kind, text, i))
            out.append("".join("\n" if ch == "\n" else " " for ch in text))
            line += text.count("\n")
            i = j + 2
            continue
        if c == '"':
            if src.startswith('"""', i):
                j = i + 3
                k = n
                while True:
                    k = src.find('"""', j)
                    if k == -1:
                        k = n - 3
                        break
                    b = k - 1
                    nb = 0
                    while b >= 0 and src[b] == "\\":
                        nb += 1
                        b -= 1
                    if nb % 2 == 1:
                        j = k + 1
                        continue
                    break
                text = src[i:k + 3]
                inner = text[3:-3]
                strings.append((line, "textblock", inner, i))
                out.append('"""' + "".join("\n" if ch == "\n" else " " for ch in inner) + '"""')
                line += text.count("\n")
                i = k + 3
                continue
            j = i + 1
            while j < n and src[j] != '"':
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == "\n":
                    break
                j += 1
            text = src[i:j + 1]
            strings.append((line, "string", text[1:-1], i))
            out.append('"' + " " * (len(text) - 2) + '"')
            i = j + 1
            continue
        if c == "'":
            j = i + 1
            while j < n and src[j] != "'":
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == "\n":
                    break
                j += 1
            text = src[i:j + 1]
            out.append("'" + " " * (len(text) - 2) + "'")
            i = j + 1
            continue
        if c == "\n":
            line += 1
        out.append(c)
        i += 1
    return "".join(out), strings, comments

class LineIndex:
    """Maps an offset in a text to its 1-based line number."""

    def __init__(self, text):
        self.starts = [0] + [m.end() for m in re.finditer(r"\n", text)]

    def line(self, off):
        return bisect.bisect_right(self.starts, off)

PAIRS = {"(": ")", "[": "]", "{": "}"}

def match_close(code, i):
    """Return the index of the bracket closing the one at code[i], or len(code)."""
    open_c = code[i]
    close_c = PAIRS[open_c]
    depth = 0
    n = len(code)
    j = i
    while j < n:
        ch = code[j]
        if ch == open_c:
            depth += 1
        elif ch == close_c:
            depth -= 1
            if depth == 0:
                return j
        j += 1
    return n

def match_open_back(code, j):
    """Return the index of the bracket opening the one closing at code[j]."""
    close_c = code[j]
    open_c = {")": "(", "]": "[", "}": "{"}[close_c]
    depth = 0
    i = j
    while i >= 0:
        ch = code[i]
        if ch == close_c:
            depth += 1
        elif ch == open_c:
            depth -= 1
            if depth == 0:
                return i
        i -= 1
    return -1

IDENT_CH = re.compile(r"[\w$]")

def skip_ws(code, i):
    n = len(code)
    while i < n and code[i].isspace():
        i += 1
    return i

def skip_ws_back(code, i):
    while i >= 0 and code[i].isspace():
        i -= 1
    return i

def prev_token(code, i):
    """Return the significant token ending before offset i."""
    j = skip_ws_back(code, i - 1)
    if j < 0:
        return ""
    if IDENT_CH.match(code[j]):
        k = j
        while k >= 0 and IDENT_CH.match(code[k]):
            k -= 1
        return code[k + 1:j + 1]
    two = code[max(0, j - 1):j + 1]
    if two in ("->", "&&", "||", "==", "!=", "+=", "-=", "::", "<=", ">="):
        return two
    return code[j]

def split_top(text, sep=","):
    """Split text on sep at bracket depth zero (parens, brackets, braces, angle brackets)."""
    parts = []
    depth = 0
    angle = 0
    cur = []
    for ch in text:
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif ch == "<":
            angle += 1
        elif ch == ">":
            angle = max(0, angle - 1)
        if ch == sep and depth == 0 and angle == 0:
            parts.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
    parts.append("".join(cur))
    return parts

ANNOT_RE = re.compile(r"@\s*[\w$.]+")

def strip_annotations(text):
    """Remove annotations (with balanced argument lists) from a declaration fragment."""
    out = []
    i = 0
    n = len(text)
    while i < n:
        if text[i] == "@" and not text.startswith("@interface", i):
            m = ANNOT_RE.match(text, i)
            if m:
                j = skip_ws(text, m.end())
                if j < n and text[j] == "(":
                    j = match_close(text, j) + 1
                    i = j
                else:
                    i = m.end()
                out.append(" ")
                continue
        out.append(text[i])
        i += 1
    return "".join(out)

MODIFIERS = {
    "public", "private", "protected", "static", "final", "abstract", "sealed", "non-sealed",
    "strictfp", "default", "synchronized", "native", "transient", "volatile",
}

def scan_modifiers_back(code, idx):
    """Walk back from a declaration keyword over modifiers and annotations.

    Returns (start, modifiers, annotations) where annotations is a list of (name, args).
    """
    mods = set()
    annots = []
    i = idx
    while True:
        j = skip_ws_back(code, i - 1)
        if j < 0:
            break
        if code[j] == ")":
            o = match_open_back(code, j)
            if o < 0:
                break
            k = skip_ws_back(code, o - 1)
            e = k
            while k >= 0 and (IDENT_CH.match(code[k]) or code[k] == "."):
                k -= 1
            if k >= 0 and code[k] == "@" and e > k:
                annots.append((code[k + 1:e + 1], code[o + 1:j]))
                i = k
                continue
            break
        if IDENT_CH.match(code[j]):
            k = j
            while k >= 0 and (IDENT_CH.match(code[k]) or code[k] in ".-"):
                k -= 1
            word = code[k + 1:j + 1]
            if k >= 0 and code[k] == "@":
                annots.append((word, None))
                i = k
                continue
            if word in MODIFIERS:
                mods.add(word)
                i = k + 1
                continue
            break
        break
    return i, mods, annots

def iter_java():
    """Yield (module, source_set, absolute_path, repo_relative_path) for every Java source."""
    for m in MODULES:
        for s in SETS:
            base = os.path.join(ROOT, m, "src", s, "java")
            if not os.path.isdir(base):
                continue
            for dirpath, _dirs, files in os.walk(base):
                for f in files:
                    if f.endswith(".java"):
                        p = os.path.join(dirpath, f)
                        rel = os.path.relpath(p, ROOT).replace("\\", "/")
                        yield m, s, p, rel

def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()
