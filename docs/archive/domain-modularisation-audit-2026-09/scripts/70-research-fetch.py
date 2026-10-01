"""Fetch a web page with a browser user agent and save it as plain text next to this script.

Usage: python 70-research-fetch.py <url> <outname> [grep-regex ...]
Prints the character count and, for each regex, up to 12 matches with 160 chars of context.
"""
import html
import pathlib
import re
import sys
import urllib.request

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/126.0 Safari/537.36")

def fetch(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "en"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return resp.read().decode("utf-8", errors="replace")

def to_text(raw: str) -> str:
    raw = re.sub(r"<script\b.*?</script\s*>", " ", raw, flags=re.S | re.I)
    raw = re.sub(r"<style\b.*?</style\s*>", " ", raw, flags=re.S | re.I)
    raw = re.sub(r"<[^>]+>", " ", raw)
    raw = html.unescape(raw)
    return re.sub(r"\s+", " ", raw)

def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    url, outname = sys.argv[1], sys.argv[2]
    here = pathlib.Path(__file__).parent
    text = to_text(fetch(url))
    (here / f"70-research-{outname}.txt").write_text(text, encoding="utf-8")
    print(f"{outname}: {len(text)} chars from {url}")
    for pattern in sys.argv[3:]:
        print(f"--- /{pattern}/")
        for m in list(re.finditer(pattern, text, flags=re.I))[:12]:
            s, e = max(0, m.start() - 160), min(len(text), m.end() + 160)
            print("..." + text[s:e] + "...")

if __name__ == "__main__":
    main()
