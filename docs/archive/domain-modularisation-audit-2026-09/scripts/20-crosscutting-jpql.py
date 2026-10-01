"""Cross-domain references inside repository @Query strings (JPQL entity names and native table names)."""
import collections
import importlib.util as U
import json
import os
import re

spec = U.spec_from_file_location("common", os.path.join(os.path.dirname(os.path.abspath(__file__)), "20-crosscutting-common.py"))
common = U.module_from_spec(spec)
spec.loader.exec_module(common)

def unescape(s):
    return s.encode("utf-8").decode("unicode_escape", errors="ignore")

def main():
    ann = common.load_annotations()["backend"]
    entities = {}
    for fq, rec in ann.items():
        for a in rec["class"]:
            if a["type"] == "jakarta.persistence.Entity":
                m = re.search(r'name="([^"]+)"', a["text"])
                entities[m.group(1) if m else common.simple_name(fq)] = fq
    fkres = json.load(open(os.path.join(common.SCRATCH, "20-crosscutting-fk.json"), encoding="utf-8"))
    queries = 0
    native = 0
    cross = collections.Counter()
    samples = collections.defaultdict(list)
    per_repo = collections.Counter()
    for fq, rec in ann.items():
        if ".repository." not in fq:
            continue
        rdom = common.domain_of(fq)
        for member, anns in rec["members"].items():
            for a in anns:
                if a["type"] != "org.springframework.data.jpa.repository.Query":
                    continue
                queries += 1
                vm = re.search(r'value="((?:[^"\\]|\\.)*)"', a["text"])
                q = unescape(vm.group(1)) if vm else ""
                is_native = "nativeQuery=true" in a["text"]
                if is_native:
                    native += 1
                    continue
                names = set(re.findall(r"\b(?:FROM|JOIN|TYPE\([^)]*\)\s*=|UPDATE|DELETE\s+FROM|MEMBER\s+OF)\s+([A-Z]\w+)", q, re.I))
                names |= set(re.findall(r"=\s*([A-Z][a-zA-Z]+)\b", q)) & set(entities)
                doms = {common.domain_of(entities[n]) for n in names if n in entities}
                others = {d for d in doms if d != rdom}
                if others:
                    per_repo[fq.rsplit(".", 1)[-1]] += 1
                    for d in others:
                        cross[(rdom, d)] += 1
                        if len(samples[(rdom, d)]) < 3:
                            samples[(rdom, d)].append(fq.rsplit(".", 1)[-1] + "#" + re.search(r"\s([\w$]+)\(", member).group(1))
    res = {"queries": queries, "native": native, "jpql_cross_domain_refs": sum(cross.values()),
           "by_edge": {"%s -> %s" % k: {"count": v, "samples": samples[k]} for k, v in cross.most_common()},
           "repos_with_cross_domain_jpql": dict(per_repo.most_common())}
    with open(os.path.join(common.SCRATCH, "20-crosscutting-jpql.json"), "w", encoding="utf-8") as fh:
        json.dump(res, fh, indent=1)
    print("@Query:", queries, "native:", native, "cross-domain entity refs:", res["jpql_cross_domain_refs"])
    for k, v in res["by_edge"].items():
        print("  %-36s %3d %s" % (k, v["count"], v["samples"]))
    print(res["repos_with_cross_domain_jpql"])

if __name__ == "__main__":
    main()
