"""Search the September audit findings for keywords and print id, area, title and where (UTF-8 safe)."""
import json
import re
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")
DATA = Path(__file__).with_name("sept_audit_findings.json")
findings = json.loads(DATA.read_text(encoding="utf-8"))
if len(sys.argv) > 1 and sys.argv[1] == "--keys":
    print(sorted(findings[0].keys()))
    sys.exit(0)
for kw in sys.argv[1:]:
    rx = re.compile(kw, re.IGNORECASE)
    print(f"=== {kw}")
    for f in findings:
        blob = " ".join(str(v) for v in f.values())
        if rx.search(blob):
            print(f"  {f.get('id')}\t{f.get('prio')}\t{f.get('area')}\t{str(f.get('title'))[:120]}\t| where: {str(f.get('where'))[:120]}")
