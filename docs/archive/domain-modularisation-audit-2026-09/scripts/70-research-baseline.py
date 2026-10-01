"""Print the Baseline status of selected web features from the web-features npm package.

Usage: python 70-research-baseline.py <path-to-data.json>
"""
import json
import sys

FEATURES = [
    "array-by-copy",
    "array-findlast",
    "array-group",
    "promise-withresolvers",
    "set-methods",
    "iterator-methods",
    "nesting",
    "container-queries",
    "container-style-queries",
    "has",
    "scope",
    "color-mix",
    "view-transitions",
    "cross-document-view-transitions",
    "trusted-types",
    "dialog",
    "popover",
    "array-fromasync",
    "promise-try",
    "regexp-escape",
    "float16array",
    "anchor-positioning",
    "invoker-commands",
    "dialog-closedby",
]

def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    data = json.load(open(sys.argv[1], encoding="utf-8"))
    features = data["features"]
    print("| id | name | baseline | low date | high date | support |")
    print("| --- | --- | --- | --- | --- | --- |")
    for fid in FEATURES:
        f = features.get(fid)
        if f is None:
            print(f"| {fid} | (not in dataset) | | | | |")
            continue
        if f.get("kind") == "moved":
            print(f"| {fid} | moved -> {f.get('redirect_target')} | | | | |")
            continue
        st = f.get("status", {})
        support = ", ".join(f"{k} {v}" for k, v in sorted(st.get("support", {}).items()))
        print(
            f"| {fid} | {f.get('name')} | {st.get('baseline')} | {st.get('baseline_low_date', '')} "
            f"| {st.get('baseline_high_date', '')} | {support} |"
        )

if __name__ == "__main__":
    main()
