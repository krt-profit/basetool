"""Read-only scanner for the frontend module's Java sources (audit 30-frontend-java).

Produces a JSON data file with a per-class inventory, per-controller facts and a classification
of every BackendApiClient call site. Nothing in the repository is modified.
"""

import json
import os
import re
import sys
from collections import Counter, defaultdict

REPO = r"$REPO"
ROOT = os.path.join(REPO, "frontend", "src", "main", "java", "de", "greluc", "krt", "profit",
                    "basetool", "frontend")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "30-frontend-java-scan.json")

def strip_comments(src):
    """Remove // and /* */ comments while keeping string and char literals intact."""
    out = []
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        if c == '"':
            if src.startswith('"""', i):
                j = src.find('"""', i + 3)
                j = n if j < 0 else j + 3
                out.append(src[i:j])
                i = j
                continue
            j = i + 1
            while j < n and src[j] != '"':
                if src[j] == "\\":
                    j += 1
                j += 1
            out.append(src[i:j + 1])
            i = j + 1
            continue
        if c == "'":
            j = i + 1
            while j < n and src[j] != "'":
                if src[j] == "\\":
                    j += 1
                j += 1
            out.append(src[i:j + 1])
            i = j + 1
            continue
        if src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j < 0 else j
            i = j
            continue
        if src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("\n" * src.count("\n", i, j))
            i = j
            continue
        out.append(c)
        i += 1
    return "".join(out)

def matching_paren(src, open_idx):
    """Return the index of the ')' matching the '(' at open_idx, honouring literals."""
    depth = 0
    i = open_idx
    n = len(src)
    while i < n:
        c = src[i]
        if c == '"':
            if src.startswith('"""', i):
                j = src.find('"""', i + 3)
                i = n if j < 0 else j + 3
                continue
            j = i + 1
            while j < n and src[j] != '"':
                if src[j] == "\\":
                    j += 1
                j += 1
            i = j + 1
            continue
        if c == "'":
            j = i + 1
            while j < n and src[j] != "'":
                if src[j] == "\\":
                    j += 1
                j += 1
            i = j + 1
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1

def split_top_level(args):
    """Split an argument list at top-level commas (parens/brackets/braces aware)."""
    parts = []
    depth = 0
    cur = []
    i = 0
    n = len(args)
    while i < n:
        c = args[i]
        if c == '"':
            j = i + 1
            while j < n and args[j] != '"':
                if args[j] == "\\":
                    j += 1
                j += 1
            cur.append(args[i:j + 1])
            i = j + 1
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
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

def has_top_level_plus(expr):
    depth = 0
    i = 0
    n = len(expr)
    while i < n:
        c = expr[i]
        if c == '"':
            j = i + 1
            while j < n and expr[j] != '"':
                if expr[j] == "\\":
                    j += 1
                j += 1
            i = j + 1
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == "+" and depth == 0:
            return True
        i += 1
    return False

STRING_LIT = re.compile(r'^"(?:[^"\\]|\\.)*"$')
UPPER_IDENT = re.compile(r"^[A-Z][A-Z0-9_]*(\.[A-Z][A-Z0-9_]*)?$")
LOWER_IDENT = re.compile(r"^[a-z][A-Za-z0-9_]*$")

def classify_expr(expr, nargs, method):
    e = expr.strip()
    if method == "getCached":
        return "cached-catalog"
    if STRING_LIT.match(e):
        if "{" in e:
            return "template-literal" + ("+vars" if nargs > 2 else "")
        return "literal"
    if UPPER_IDENT.match(e) or re.match(r"^[A-Z][A-Za-z0-9]*\.[A-Z][A-Z0-9_]*$", e):
        return "constant"
    if "UriComponentsBuilder" in e:
        return "uri-builder"
    if ".formatted(" in e or "String.format(" in e:
        return "format"
    if has_top_level_plus(e):
        return "concat"
    if LOWER_IDENT.match(e):
        return "variable"
    if re.match(r"^[A-Za-z_][A-Za-z0-9_.]*\(", e):
        return "helper-call"
    return "other"

def resolve_variable(src, name, pos):
    """Find the last assignment to `name` before pos and classify it."""
    pat = re.compile(r"(?:\bString\s+|\bvar\s+|[^.\w])" + re.escape(name) + r"\s*=\s*")
    last = None
    for m in pat.finditer(src, 0, pos):
        last = m
    if not last:
        return "variable-unresolved"
    start = last.end()
    depth = 0
    i = start
    n = len(src)
    while i < n:
        c = src[i]
        if c == '"':
            j = i + 1
            while j < n and src[j] != '"':
                if src[j] == "\\":
                    j += 1
                j += 1
            i = j + 1
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == ";" and depth == 0:
            break
        i += 1
    rhs = src[start:i].strip()
    if "UriComponentsBuilder" in rhs:
        return "variable->uri-builder"
    if ".formatted(" in rhs or "String.format(" in rhs:
        return "variable->format"
    if has_top_level_plus(rhs):
        return "variable->concat"
    if STRING_LIT.match(rhs):
        return "variable->literal"
    if "?" in rhs and ":" in rhs:
        return "variable->conditional"
    if re.match(r"^[A-Za-z_][A-Za-z0-9_.]*\(", rhs):
        return "variable->helper-call"
    return "variable->other"

TYPE_DECL = re.compile(
    r"^(?:public\s+|protected\s+|private\s+|abstract\s+|final\s+|sealed\s+|non-sealed\s+|static\s+)*"
    r"(class|record|enum|interface|@interface)\s+([A-Za-z0-9_]+)", re.M)
MAPPING = re.compile(r"@(Get|Post|Put|Patch|Delete|Request)Mapping\b")
API_LIT = re.compile(r'"(/api/v1/[^"]*)"')

def api_segment(path):
    rest = path[len("/api/v1/"):]
    seg = rest.split("/")[0].split("?")[0]
    if seg in ("admin", "me") and "/" in rest:
        seg2 = rest.split("/")[1].split("?")[0]
        if seg2 and not seg2.startswith("{"):
            return seg + "/" + seg2
    return seg

def main():
    classes = []
    calls = []
    for dirpath, _, files in os.walk(ROOT):
        for f in sorted(files):
            if not f.endswith(".java"):
                continue
            path = os.path.join(dirpath, f)
            rel = os.path.relpath(path, ROOT).replace(os.sep, "/")
            pkg = os.path.dirname(rel).replace("/", ".") or "(root)"
            with open(path, encoding="utf-8") as fh:
                raw = fh.read()
            src = strip_comments(raw)
            lines_total = raw.count("\n") + 1
            code_lines = sum(1 for ln in src.splitlines() if ln.strip())
            m = TYPE_DECL.search(src)
            kind = m.group(1) if m else "?"
            name = f[:-5]
            ann = {
                "Controller": bool(re.search(r"^@Controller\b", src, re.M)),
                "RestController": bool(re.search(r"^@RestController\b", src, re.M)),
                "ControllerAdvice": bool(re.search(r"^@(Rest)?ControllerAdvice\b", src, re.M)),
                "Service": bool(re.search(r"^@Service\b", src, re.M)),
                "Component": bool(re.search(r"^@Component\b", src, re.M)),
                "Configuration": bool(re.search(r"^@Configuration\b", src, re.M)),
                "ConfigurationProperties": bool(re.search(r"^@ConfigurationProperties\b", src, re.M)),
                "UsesLayoutModel": bool(re.search(r"^@UsesLayoutModel\b", src, re.M)),
                "ClassPreAuthorize": bool(re.search(r"^@PreAuthorize\(", src, re.M)),
            }
            cls_mapping = re.search(r'^@RequestMapping\(\s*(?:value\s*=\s*|path\s*=\s*)?\{?\s*"([^"]*)"', src, re.M)
            handler_count = 0
            handler_pre = 0
            response_body = 0
            for hm in re.finditer(r"^\s+@(Get|Post|Put|Patch|Delete|Request)Mapping\b", src, re.M):
                handler_count += 1
            method_pre = len(re.findall(r"^\s+@PreAuthorize\(", src, re.M))
            response_body = len(re.findall(r"^\s+@ResponseBody\b", src, re.M))
            api_lits = API_LIT.findall(src)
            segs = Counter(api_segment(p) for p in api_lits)
            direct_webclient = bool(re.search(r"\bprivate final WebClient\s+\w+;", src))
            relay_uses = len(re.findall(r"BackendErrorResponses\.relay\(", src))
            propagate_uses = len(re.findall(r"\bpropagateBackendError\(", src))
            bse_catches = len(re.findall(r"catch\s*\(\s*(?:final\s+)?BackendServiceException\b", src))
            conflict_409 = len(re.findall(r"\b409\b|HttpStatus\.CONFLICT", src))
            injects_client = bool(re.search(r"BackendApiClient backendApiClient;", src))
            imports = re.findall(r"^import\s+(de\.greluc\.krt\.profit\.basetool\.frontend\.[\w.]+);", src, re.M)
            for cm in re.finditer(r"\bbackendApiClient\s*\.\s*([a-zA-Z]+)\s*\(", src):
                method = cm.group(1)
                open_idx = cm.end() - 1
                close_idx = matching_paren(src, open_idx)
                if close_idx < 0:
                    continue
                args = split_top_level(src[open_idx + 1:close_idx])
                first = args[0] if args else ""
                cls = classify_expr(first, len(args), method) if args else "no-args"
                resolved = None
                if cls == "variable":
                    resolved = resolve_variable(src, first.strip(), cm.start())
                line = src.count("\n", 0, cm.start()) + 1
                calls.append({
                    "file": rel,
                    "line": line,
                    "method": method,
                    "nargs": len(args),
                    "first": first[:160],
                    "class": cls,
                    "resolved": resolved,
                })
            classes.append({
                "file": rel,
                "package": pkg,
                "name": name,
                "kind": kind,
                "lines": lines_total,
                "code_lines": code_lines,
                "annotations": ann,
                "request_mapping": cls_mapping.group(1) if cls_mapping else None,
                "handlers": handler_count,
                "method_preauthorize": method_pre,
                "response_body": response_body,
                "api_literals": len(api_lits),
                "api_segments": dict(segs),
                "direct_webclient": direct_webclient,
                "relay_uses": relay_uses,
                "propagate_uses": propagate_uses,
                "bse_catches": bse_catches,
                "mentions_409": conflict_409,
                "injects_backend_api_client": injects_client,
                "frontend_imports": imports,
            })
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump({"classes": classes, "calls": calls}, fh, indent=1)
    print("classes", len(classes), "calls", len(calls))
    print("call classes", Counter(c["class"] for c in calls).most_common())
    print("resolved", Counter(c["resolved"] for c in calls if c["resolved"]).most_common())
    print("methods", Counter(c["method"] for c in calls).most_common())

if __name__ == "__main__":
    sys.exit(main())
