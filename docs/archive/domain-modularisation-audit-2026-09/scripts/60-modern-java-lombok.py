"""Lombok usage on classes, split by what the class is (entity, form, config, other)."""

import collections
import importlib.util
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("mjlex", os.path.join(HERE, "60-modern-java-lexer.py"))
L = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(L)

TYPE_RE = re.compile(r"(?<![\w$.@])(class|interface|enum)\s+([A-Z_$][\w$]*)")
LOMBOK = ["Data", "Value", "Getter", "Setter", "Builder", "SuperBuilder", "ToString", "EqualsAndHashCode",
          "AllArgsConstructor", "NoArgsConstructor", "RequiredArgsConstructor", "With", "Slf4j", "JBossLog"]
rows = collections.Counter()
data_other = []
for m, s, p, rel in L.iter_java():
    src = L.read(p)
    code, _st, _cm = L.lex(src)
    imports = set(re.findall(r"(?m)^\s*import\s+([\w.]+)\s*;", code))
    lombok_star = "lombok" in {i.rsplit(".", 1)[0] for i in imports}
    for mt in TYPE_RE.finditer(code):
        if mt.group(1) != "class":
            continue
        name = mt.group(2)
        _start, mods, annots = L.scan_modifiers_back(code, mt.start())
        names = {a[0].split(".")[-1] for a in annots}
        if names & {"Entity", "Embeddable", "MappedSuperclass"}:
            cat = "entity"
        elif name.endswith("Form"):
            cat = "form"
        elif names & {"ConfigurationProperties"}:
            cat = "config-props"
        elif names & {"Component", "Service", "Repository", "Controller", "RestController", "Configuration", "ControllerAdvice", "RestControllerAdvice"}:
            cat = "bean"
        else:
            cat = "other"
        for a in LOMBOK:
            if a in names:
                if a == "Value" and "lombok.Value" not in imports:
                    continue
                rows[(f"{m}/{s}", cat, a)] += 1
        if "Data" in names and cat == "other":
            data_other.append(f"{rel}:{code.count(chr(10), 0, mt.start()) + 1} {name}")

for k in sorted(rows):
    if k[2] in ("Data", "Value", "Getter", "Setter", "Builder", "ToString", "EqualsAndHashCode", "With", "AllArgsConstructor", "NoArgsConstructor"):
        print(rows[k], k)
print("\n@Data on 'other' classes:")
for d in data_other:
    print("  ", d)
