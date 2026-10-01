"""Inventory every top-level type under backend/src/main/java: FQCN, layer, kind, stereotype, LOC, summary.

Writes 10-backend-domains-inventory.json next to this script.
"""
import json
import os
import re
import sys

REPO = r"$REPO"
SRC = os.path.join(REPO, "backend", "src", "main", "java")
BASE_PKG = "de.greluc.krt.profit.basetool.backend"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "10-backend-domains-inventory.json")

KIND_RE = re.compile(r"^\s*(?:public\s+|protected\s+|private\s+)?(?:abstract\s+|final\s+|sealed\s+|non-sealed\s+|static\s+|strictfp\s+)*(class|interface|enum|record|@interface)\s+(\w+)", re.M)
STEREOTYPES = ["Entity", "MappedSuperclass", "Embeddable", "RestController", "Controller", "ControllerAdvice",
               "RestControllerAdvice", "Service", "Component", "Repository", "Configuration", "Mapper",
               "ConfigurationProperties", "Converter"]

def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    return text

def first_javadoc(text, name):
    m = re.search(r"/\*\*(.*?)\*/\s*(?:@[\w.]+(?:\([^)]*\))?\s*)*(?:public\s+|final\s+|abstract\s+|sealed\s+|non-sealed\s+)*(?:class|interface|enum|record|@interface)\s+" + re.escape(name) + r"\b", text, re.S)
    if not m:
        return ""
    body = m.group(1)
    lines = [re.sub(r"^\s*\*\s?", "", ln) for ln in body.splitlines()]
    joined = " ".join(ln.strip() for ln in lines if ln.strip() and not ln.strip().startswith("@"))
    joined = re.sub(r"\{@\w+\s+([^}]*)\}", r"\1", joined)
    joined = re.sub(r"<[^>]+>", "", joined)
    sent = re.split(r"(?<=[.!?])\s", joined, maxsplit=1)[0]
    return sent[:220]

def main():
    rows = []
    for root, _dirs, files in os.walk(SRC):
        for fn in files:
            if not fn.endswith(".java"):
                continue
            path = os.path.join(root, fn)
            rel = os.path.relpath(path, SRC).replace(os.sep, "/")
            pkg = rel.rsplit("/", 1)[0].replace("/", ".")
            name = fn[:-5]
            with open(path, encoding="utf-8") as fh:
                text = fh.read()
            lines = text.splitlines()
            loc = len(lines)
            code = strip_comments(text)
            ncloc = sum(1 for ln in code.splitlines() if ln.strip())
            km = None
            for m in KIND_RE.finditer(code):
                if m.group(2) == name:
                    km = m
                    break
            kind = km.group(1) if km else "?"
            header = code[: km.start()] if km else code
            stereo = [s for s in STEREOTYPES if re.search(r"@" + s + r"\b", header)]
            sub = pkg[len(BASE_PKG) + 1:] if pkg.startswith(BASE_PKG + ".") else ""
            layer = sub.split(".")[0] if sub else "(root)"
            if sub.startswith("model.dto"):
                layer = "dto"
            elif sub.startswith("model.projection"):
                layer = "projection"
            elif sub == "model.scwiki":
                layer = "model"
            elif sub.startswith("dto"):
                layer = "dto-external"
            public_methods = len(re.findall(r"^\s{2,4}public\s+(?!class|interface|enum|record|static\s+final)[\w<>\[\],.? ]+\s+\w+\s*\(", code, re.M))
            rows.append({
                "fqcn": pkg + "." + name,
                "simple": name,
                "package": pkg,
                "subpackage": sub,
                "layer": layer,
                "kind": kind,
                "stereotypes": stereo,
                "loc": loc,
                "ncloc": ncloc,
                "public_methods": public_methods,
                "summary": first_javadoc(text, name),
                "path": "backend/src/main/java/" + rel,
            })
    rows.sort(key=lambda r: r["fqcn"])
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(rows, fh, indent=1)
    print(len(rows), "types written to", OUT)

if __name__ == "__main__":
    sys.exit(main())
