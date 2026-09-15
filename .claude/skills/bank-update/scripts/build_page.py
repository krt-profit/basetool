#!/usr/bin/env python3
"""Wrap a finished post fragment in the standing bank-update page.

The page is the deliverable: a browser page whose "Formatiert kopieren" button puts
forum-safe markup on the clipboard, so the report goes into the forum's CKEditor by
paste and nobody ever handles HTML source. Only the fragment changes from month to
month; the chrome comes from ``assets/report-page.html`` so every month looks the
same.

Usage:
    python .claude/skills/bank-update/scripts/build_page.py \
        --fragment post.html --title "Bank Update August 2026" \
        --subline "Stand 01.09.2026 · Buchungen vom 01.08. bis 31.08.2026" \
        --out bank-update-2026-08.html
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

TEMPLATE = Path(__file__).resolve().parent.parent / "assets" / "report-page.html"

# A fragment that still carries these has not been finished; publishing one would put
# a visible placeholder in front of the whole squadron.
LEFTOVERS = ("{{", "LINK-BITTE-EINSETZEN", "BITTE-EINSETZEN", "TODO", "XXX")


def main() -> None:
    """Substitute title, subline and post into the template and write the page."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fragment", required=True, help="file holding the post's HTML fragment")
    parser.add_argument("--title", required=True, help='e.g. "Bank Update August 2026"')
    parser.add_argument("--subline", required=True, help="the grey line under the title")
    parser.add_argument("--out", required=True, help="where to write the finished page")
    args = parser.parse_args()

    if not TEMPLATE.exists():
        sys.exit(f"Template missing: {TEMPLATE}")
    fragment_path = Path(args.fragment)
    if not fragment_path.exists():
        sys.exit(f"Fragment missing: {fragment_path}")

    fragment = fragment_path.read_text(encoding="utf-8").strip()
    if not fragment:
        sys.exit("The fragment is empty.")

    found = [marker for marker in LEFTOVERS if marker in fragment]
    if found:
        sys.exit(f"The fragment still carries placeholders: {', '.join(found)}")
    if "<h2" not in fragment:
        sys.exit("The fragment has no <h2> title — it should open with the post's headline.")

    page = TEMPLATE.read_text(encoding="utf-8")
    for token, value in (("{{TITLE}}", args.title), ("{{SUBLINE}}", args.subline)):
        if token not in page:
            sys.exit(f"Template lost its {token} placeholder.")
        page = page.replace(token, value)
    page = page.replace("{{POST}}", fragment)

    if "{{" in page:
        sys.exit("A placeholder survived substitution — check the template.")

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(page, encoding="utf-8", newline="\n")
    print(f"Written: {out}  ({len(page):,} bytes)")


if __name__ == "__main__":
    main()
