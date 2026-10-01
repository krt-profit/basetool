"""Look up web-features entries by id or by BCD compat key and print Baseline status and versions.

Usage: python 70-research-browserfloor.py <path-to-data.json>
Prints one table per group and, for group 1, the implied minimum version per browser (the maximum
of the per-feature minimums).
"""
import json
import sys

BROWSERS = ["chrome", "edge", "firefox", "safari", "safari_ios", "chrome_android", "firefox_android"]

GROUPS = {
    "1 - features the app already ships": [
        ("cascade-layers", None),
        ("has", None),
        ("media-query-range-syntax", None),
        ("dialog", None),
        ("focus-visible", None),
        ("inert", None),
        (None, "api.Element.replaceChildren"),
        (None, "api.AbortController"),
    ],
    "2 - JavaScript APIs": [
        ("array-at", None),
        ("object-hasown", None),
        ("structured-clone", None),
        ("array-by-copy", None),
        ("array-group", None),
        ("promise-withresolvers", None),
        ("set-methods", None),
        ("iterator-methods", None),
        (None, "api.AbortSignal.timeout_static"),
        (None, "api.AbortSignal.any_static"),
        (None, "javascript.builtins.RegExp.unicodeSets"),
    ],
    "5 - CSS and HTML": [
        ("nesting", None),
        (None, "css.selectors.nesting.type_selector"),
        ("container-queries", None),
        ("container-style-queries", None),
        ("scope", None),
        ("color-mix", None),
        ("starting-style", None),
        ("view-transitions", None),
        ("cross-document-view-transitions", None),
        ("popover", None),
        ("dialog-closedby", None),
        ("viewport-unit-variants", None),
        ("import-maps", None),
        ("trusted-types", None),
    ],
}

def find_by_compat(features, key):
    for fid, f in features.items():
        if key in (f.get("compat_features") or []):
            return fid, f
    return None, None

def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    data = json.load(open(sys.argv[1], encoding="utf-8"))
    features = data["features"]
    for title, items in GROUPS.items():
        print(f"\n### {title}\n")
        print("| query | web-features id | baseline | low | high | " + " | ".join(BROWSERS) + " |")
        print("| --- | --- | --- | --- | --- | " + " | ".join("---" for _ in BROWSERS) + " |")
        floor = {}
        for fid, compat in items:
            f = features.get(fid) if fid else None
            if compat:
                fid, f = find_by_compat(features, compat)
            q = fid if not compat else compat
            if f is None:
                print(f"| {q} | (not found) | | | | " + " | ".join("" for _ in BROWSERS) + " |")
                continue
            if f.get("kind") == "moved":
                print(f"| {q} | moved -> {f.get('redirect_target')} | | | | " + " | ".join("" for _ in BROWSERS) + " |")
                continue
            st = f.get("status", {})
            sup = st.get("support", {})
            if compat:
                sup = (st.get("by_compat_key", {}) or {}).get(compat, {}).get("support", sup)
            vals = [str(sup.get(b, "-")) for b in BROWSERS]
            print(f"| {q} | {fid} | {st.get('baseline')} | {st.get('baseline_low_date', '')} | "
                  f"{st.get('baseline_high_date', '')} | " + " | ".join(vals) + " |")
            if title.startswith("1"):
                for b in BROWSERS:
                    v = sup.get(b)
                    if v is None:
                        floor[b] = "unsupported"
                        continue
                    try:
                        num = float(str(v).lstrip("≤"))
                    except ValueError:
                        continue
                    if floor.get(b) != "unsupported":
                        floor[b] = max(floor.get(b, 0.0), num)
        if floor:
            print("\nImplied floor: " + ", ".join(f"{b} {floor[b]}" for b in BROWSERS if b in floor))

if __name__ == "__main__":
    main()
