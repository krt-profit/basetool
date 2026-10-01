"""Inventory every backend REST mapping from the controller sources.

For each handler method: file:line, class, HTTP verb, full path, class- and method-level
@PreAuthorize, request body type and whether it is validated, return type, query parameters,
identity parameters, @ApiDeprecation / @Deprecated, @Transactional, @Operation presence.
Joins the result with the committed openapi.json by (verb, normalised path).
"""
import csv
import json
import os
import re
import sys
from collections import Counter, defaultdict

REPO = r"$REPO"
SRC = os.path.join(REPO, "backend", "src", "main", "java")
PKG = os.path.join(SRC, "de", "greluc", "krt", "profit", "basetool", "backend")
OUT = r"$SCRATCHPAD"

MAPPINGS = {
    "GetMapping": "GET",
    "PostMapping": "POST",
    "PutMapping": "PUT",
    "DeleteMapping": "DELETE",
    "PatchMapping": "PATCH",
    "RequestMapping": None,
}

def strip_comments(text):
    """Replace comments with spaces, keeping newlines and string literals intact."""
    out = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if text.startswith('"""', i):
            j = text.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append(text[i:j])
            i = j
            continue
        if c == '"':
            j = i + 1
            while j < n and text[j] != '"':
                if text[j] == "\\":
                    j += 1
                j += 1
            out.append(text[i:j + 1])
            i = j + 1
            continue
        if c == "'":
            j = i + 1
            while j < n and text[j] != "'":
                if text[j] == "\\":
                    j += 1
                j += 1
            out.append(text[i:j + 1])
            i = j + 1
            continue
        if text.startswith("//", i):
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
            continue
        if text.startswith("/*", i):
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            seg = text[i:j]
            out.append(re.sub(r"[^\n]", " ", seg))
            i = j
            continue
        out.append(c)
        i += 1
    return "".join(out)

def balanced(text, start, open_c="(", close_c=")"):
    """Return index after the matching close paren for text[start] == open_c."""
    depth = 0
    i = start
    n = len(text)
    in_str = False
    while i < n:
        c = text[i]
        if in_str:
            if c == "\\":
                i += 2
                continue
            if c == '"':
                in_str = False
        else:
            if c == '"':
                in_str = True
            elif c == open_c:
                depth += 1
            elif c == close_c:
                depth -= 1
                if depth == 0:
                    return i + 1
        i += 1
    return n

def parse_annotations(buf):
    """Extract annotations (name, args) from a declaration buffer; return (annotations, rest)."""
    anns = []
    rest = []
    i = 0
    n = len(buf)
    while i < n:
        c = buf[i]
        if c == "@" and not buf.startswith("@interface", i):
            m = re.match(r"@([\w.]+)", buf[i:])
            name = m.group(1).split(".")[-1]
            j = i + len(m.group(0))
            k = j
            while k < n and buf[k] in " \t\r\n":
                k += 1
            args = ""
            if k < n and buf[k] == "(":
                e = balanced(buf, k)
                args = buf[k + 1:e - 1]
                j = e
            anns.append((name, args, i))
            i = j
            rest.append(" ")
            continue
        rest.append(c)
        i += 1
    return anns, "".join(rest)

CONSTANTS = {}

def load_constants():
    pat = re.compile(r"static\s+final\s+String\s+(\w+)\s*=\s*((?:\"(?:[^\"\\]|\\.)*\"\s*\+?\s*|[A-Z_][\w.]*\s*\+?\s*)+);")
    for root, _, files in os.walk(PKG):
        for f in files:
            if not f.endswith(".java"):
                continue
            cls = f[:-5]
            with open(os.path.join(root, f), encoding="utf-8") as fh:
                t = strip_comments(fh.read())
            for m in pat.finditer(t):
                CONSTANTS[cls + "." + m.group(1)] = m.group(2)
                CONSTANTS.setdefault("#" + cls + "#" + m.group(1), m.group(2))

def resolve_expr(expr, cls=None, depth=0):
    """Resolve a Java string concatenation of literals and constants into one string."""
    if depth > 6:
        return expr
    parts = re.findall(r"\"(?:[^\"\\]|\\.)*\"|[A-Za-z_][\w.]*", expr)
    out = []
    for p in parts:
        if p.startswith('"'):
            out.append(bytes(p[1:-1], "utf-8").decode("unicode_escape"))
        else:
            key = p
            if key in CONSTANTS:
                out.append(resolve_expr(CONSTANTS[key], key.split(".")[0], depth + 1))
            elif cls and ("#" + cls + "#" + p) in CONSTANTS:
                out.append(resolve_expr(CONSTANTS["#" + cls + "#" + p], cls, depth + 1))
            else:
                cands = [k for k in CONSTANTS if not k.startswith("#") and k.endswith("." + p)]
                if len(cands) == 1:
                    out.append(resolve_expr(CONSTANTS[cands[0]], cands[0].split(".")[0], depth + 1))
                else:
                    out.append("<" + p + ">")
    return "".join(out)

def mapping_info(name, args, cls):
    verbs = []
    if name == "RequestMapping":
        for v in re.findall(r"RequestMethod\.(\w+)", args):
            verbs.append(v)
    else:
        verbs = [MAPPINGS[name]]
    paths = []
    a = args.strip()
    if not a:
        paths = [""]
    else:
        m = re.search(r"(?:value|path)\s*=\s*(\{[^}]*\}|\"(?:[^\"\\]|\\.)*\"(?:\s*\+\s*[\w.\"]+)*|[A-Za-z_][\w.]*(?:\s*\+\s*[\w.\"]+)*)", a)
        if m:
            raw = m.group(1)
        elif "=" not in a.split(",")[0]:
            raw = a
            if a.startswith("{"):
                raw = a[:balanced(a, 0, "{", "}")]
            else:
                raw = a.split(",")[0] if not a.startswith('"') else a
        else:
            raw = ""
        if raw.startswith("{"):
            items = re.findall(r"\"(?:[^\"\\]|\\.)*\"(?:\s*\+\s*[\w.\"]+)*|[A-Za-z_][\w.]*(?:\s*\+\s*[\w.\"]+)*", raw[1:-1])
            paths = [resolve_expr(x, cls) for x in items] or [""]
        elif raw:
            m2 = re.match(r"\s*((?:\"(?:[^\"\\]|\\.)*\"|[A-Za-z_][\w.]*)(?:\s*\+\s*(?:\"(?:[^\"\\]|\\.)*\"|[A-Za-z_][\w.]*))*)", raw)
            paths = [resolve_expr(m2.group(1), cls)] if m2 else [""]
        else:
            paths = [""]
    produces = re.findall(r"produces\s*=\s*([^,)]+)", args)
    consumes = re.findall(r"consumes\s*=\s*([^,)]+)", args)
    return verbs, paths, produces, consumes

def split_params(ptext):
    params = []
    depth = 0
    cur = []
    in_str = False
    for c in ptext:
        if in_str:
            cur.append(c)
            if c == '"':
                in_str = False
            continue
        if c == '"':
            in_str = True
        if c in "(<{":
            depth += 1
        elif c in ")>}":
            depth -= 1
        if c == "," and depth == 0:
            params.append("".join(cur).strip())
            cur = []
        else:
            cur.append(c)
    if "".join(cur).strip():
        params.append("".join(cur).strip())
    return params

def norm_path(p):
    p = re.sub(r"\{(\w+):[^}]*\}", r"{\1}", p)
    p = re.sub(r"//+", "/", p)
    if len(p) > 1 and p.endswith("/"):
        p = p[:-1]
    return p

def join_paths(base, sub):
    if not base:
        return norm_path(sub or "/")
    if not sub:
        return norm_path(base)
    return norm_path(base.rstrip("/") + "/" + sub.lstrip("/"))

def parse_file(path):
    with open(path, encoding="utf-8") as fh:
        raw = fh.read()
    text = strip_comments(raw)
    line_of = lambda idx: text.count("\n", 0, idx) + 1
    cm = re.search(r"\b(class|record|interface)\s+(\w+)", text)
    if not cm:
        return None
    cls = cm.group(2)
    header_start = text.rfind(";", 0, cm.start()) + 1
    cls_anns, _ = parse_annotations(text[header_start:cm.start()])
    body_start = text.find("{", cm.end())
    body_end = balanced(text, body_start, "{", "}")
    info = {
        "class": cls,
        "file": os.path.relpath(path, REPO).replace("\\", "/"),
        "class_line": line_of(cm.start()),
        "class_anns": [(a, b) for a, b, _ in cls_anns],
        "base_paths": [""],
        "class_preauth": "",
        "class_tag": "",
        "class_tx": False,
        "class_validated": False,
        "methods": [],
    }
    for a, b, _ in cls_anns:
        if a == "RequestMapping":
            _, paths, _, _ = mapping_info(a, b, cls)
            info["base_paths"] = paths
        elif a == "PreAuthorize":
            info["class_preauth"] = resolve_expr(b, cls)
        elif a == "Tag":
            m = re.search(r"name\s*=\s*(\"(?:[^\"\\]|\\.)*\")", b)
            info["class_tag"] = resolve_expr(m.group(1), cls) if m else b
        elif a == "Transactional":
            info["class_tx"] = True
        elif a == "Validated":
            info["class_validated"] = True
    i = body_start + 1
    depth = 1
    decl_start = i
    n = body_end - 1
    while i < n:
        c = text[i]
        if c == '"':
            if text.startswith('"""', i):
                j = text.find('"""', i + 3)
                i = j + 3
                continue
            j = i + 1
            while j < n and text[j] != '"':
                if text[j] == "\\":
                    j += 1
                j += 1
            i = j + 1
            continue
        if c == "(":
            i = balanced(text, i)
            continue
        if c == ";" and depth == 1:
            decl_start = i + 1
        elif c == "{":
            if depth == 1:
                buf = text[decl_start:i]
                anns, rest = parse_annotations(buf)
                mm = re.search(r"([\w<>\[\],.?\s]+?)\s+(\w+)\s*\(", rest)
                if mm and re.search(r"\b(class|record|interface|enum)\b", rest) is None:
                    name = mm.group(2)
                    ret = " ".join(mm.group(1).split())
                    ret = re.sub(r"^(public|protected|private|static|final|synchronized|default|abstract|\s)+", "", ret).strip()
                    open_idx = decl_start + buf.find(name + "(", 0) + len(name) if (name + "(") in buf else None
                    pstart = buf.find("(", buf.find(" " + name) if (" " + name) in buf else 0)
                    pend = balanced(buf, pstart)
                    ptext = buf[pstart + 1:pend - 1]
                    params = split_params(ptext)
                    mline = line_of(decl_start + (anns[0][2] if anns else pstart))
                    info["methods"].append({
                        "name": name,
                        "line": line_of(decl_start + pstart),
                        "ann_line": mline,
                        "ret": ret,
                        "anns": [(a, b) for a, b, _ in anns],
                        "params": params,
                    })
                decl_start = i + 1
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 1:
                decl_start = i + 1
        i += 1
    return info

def analyse_param(p, cls):
    anns, rest = parse_annotations(p)
    names = [a for a, _, _ in anns]
    rest = " ".join(rest.split())
    toks = rest.rsplit(" ", 1)
    ptype = toks[0].replace("final ", "").strip() if len(toks) == 2 else rest
    pname = toks[-1]
    d = {"anns": names, "type": ptype, "name": pname, "raw_anns": anns}
    for a, b, _ in anns:
        if a == "RequestParam":
            m = re.search(r"(?:name|value)\s*=\s*(\"[^\"]*\")", b)
            if m:
                d["qname"] = resolve_expr(m.group(1), cls)
            elif b.strip().startswith('"'):
                d["qname"] = resolve_expr(b.split(",")[0], cls)
            else:
                d["qname"] = pname
            d["q_required"] = "required = false" not in b.replace("required=false", "required = false") and "defaultValue" not in b
        if a == "Validated":
            d["validated_groups"] = b
    return d

def main():
    load_constants()
    files = []
    for root, _, fs in os.walk(os.path.join(PKG, "controller")):
        for f in fs:
            if f.endswith(".java"):
                files.append(os.path.join(root, f))
    rows = []
    classes = []
    for f in sorted(files):
        info = parse_file(f)
        if not info:
            continue
        ann_names = [a for a, _ in info["class_anns"]]
        if "RestController" not in ann_names and "Controller" not in ann_names:
            continue
        classes.append(info)
        for m in info["methods"]:
            maps = [(a, b) for a, b in m["anns"] if a in MAPPINGS]
            if not maps:
                continue
            a, b = maps[0]
            verbs, paths, produces, consumes = mapping_info(a, b, info["class"])
            if not verbs:
                verbs = ["ANY"]
            preauth = ""
            deprecated_ann = ""
            java_deprecated = False
            tx = ""
            has_operation = False
            op_deprecated = False
            has_api_responses = False
            response_status = ""
            secured_other = []
            for an, ab in m["anns"]:
                if an == "PreAuthorize":
                    preauth = resolve_expr(ab, info["class"])
                elif an == "ApiDeprecation":
                    deprecated_ann = " ".join(ab.split())
                elif an == "Deprecated":
                    java_deprecated = True
                elif an == "Transactional":
                    tx = "ro" if "readOnly" in ab and "true" in ab else "rw"
                elif an == "Operation":
                    has_operation = True
                    if re.search(r"deprecated\s*=\s*true", ab):
                        op_deprecated = True
                elif an == "ApiResponses" or an == "ApiResponse":
                    has_api_responses = True
                elif an == "ResponseStatus":
                    response_status = " ".join(ab.split())
                elif an in ("Secured", "RolesAllowed", "PostAuthorize", "PreFilter", "PostFilter"):
                    secured_other.append(an)
            body_type = ""
            body_valid = ""
            body_groups = ""
            query = []
            identity = []
            pageable = False
            path_vars = []
            multipart = False
            for p in m["params"]:
                d = analyse_param(p, info["class"])
                if "RequestBody" in d["anns"]:
                    body_type = d["type"]
                    body_valid = "Valid" if "Valid" in d["anns"] else ("Validated" if "Validated" in d["anns"] else "")
                    body_groups = d.get("validated_groups", "")
                if "RequestParam" in d["anns"]:
                    query.append(d.get("qname", d["name"]) + ":" + d["type"])
                    if "MultipartFile" in d["type"]:
                        multipart = True
                if "RequestPart" in d["anns"]:
                    multipart = True
                if "PathVariable" in d["anns"]:
                    path_vars.append(d["name"])
                if "AuthenticationPrincipal" in d["anns"] or "CurrentUserId" in d["anns"] or d["type"] in ("JwtAuthenticationToken", "Authentication", "Principal", "Jwt"):
                    identity.append(("@CurrentUserId" if "CurrentUserId" in d["anns"] else "") + d["type"])
                if d["type"] == "Pageable":
                    pageable = True
            for v in verbs:
                for bp in info["base_paths"]:
                    for sp in paths:
                        full = join_paths(bp, sp)
                        rows.append({
                            "file": info["file"],
                            "line": m["ann_line"],
                            "class": info["class"],
                            "method": m["name"],
                            "verb": v,
                            "path": full,
                            "class_preauth": info["class_preauth"],
                            "method_preauth": preauth,
                            "effective_preauth": preauth or info["class_preauth"],
                            "other_security_anns": "|".join(secured_other),
                            "body_type": body_type,
                            "body_valid": body_valid,
                            "body_groups": " ".join(body_groups.split()),
                            "return_type": m["ret"],
                            "query": "|".join(query),
                            "path_vars": "|".join(path_vars),
                            "identity": "|".join(identity),
                            "pageable_param": pageable,
                            "multipart": multipart,
                            "api_deprecation": deprecated_ann,
                            "java_deprecated": java_deprecated,
                            "op_deprecated": op_deprecated,
                            "tx": tx or ("class" if info["class_tx"] else ""),
                            "has_operation": has_operation,
                            "has_api_responses": has_api_responses,
                            "response_status": response_status,
                            "class_tag": info["class_tag"],
                            "produces": "|".join(produces),
                            "consumes": "|".join(consumes),
                        })
    with open(os.path.join(OUT, "90-rest-api-mappings.csv"), "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)
    print("controller classes", len(classes))
    print("mappings (verb x path)", len(rows))
    print("verbs", Counter(r["verb"] for r in rows))
    unresolved = [r for r in rows if "<" in r["path"]]
    print("unresolved paths", len(unresolved), [(r["class"], r["path"]) for r in unresolved[:10]])
    oas_path = os.path.join(REPO, "backend", "src", "main", "resources", "api", "openapi.json")
    with open(oas_path, encoding="utf-8") as fh:
        oas = json.load(fh)
    oas_ops = set()
    for p, item in oas["paths"].items():
        for v in ("get", "put", "post", "delete", "patch"):
            if v in item:
                oas_ops.add((v.upper(), p))
    src_ops = set((r["verb"], r["path"]) for r in rows)
    print("in source not in openapi", len(src_ops - oas_ops), sorted(src_ops - oas_ops)[:40])
    print("in openapi not in source", len(oas_ops - src_ops), sorted(oas_ops - src_ops)[:40])
    with open(os.path.join(OUT, "90-rest-api-classes.json"), "w", encoding="utf-8") as fh:
        json.dump([{k: v for k, v in c.items() if k != "methods"} for c in classes], fh, indent=1)

if __name__ == "__main__":
    sys.exit(main())
