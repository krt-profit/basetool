"""Check that every Markdown table in the report has a consistent column count per table."""
import re
import sys
from pathlib import Path

path = Path(__file__).with_name("82-prev-july-other.md")
lines = path.read_text(encoding="utf-8").splitlines()
problems = 0
table = []
start = 0

def cells(line):
    """Count cells in a Markdown table row, ignoring pipes inside backticks."""
    stripped = re.sub(r"`[^`]*`", "X", line.strip())
    return stripped.strip("|").count("|") + 1

for i, line in enumerate(lines + [""], 1):
    if line.startswith("|"):
        if not table:
            start = i
        table.append((i, cells(line)))
    else:
        if table:
            counts = {c for _, c in table}
            if len(counts) != 1:
                problems += 1
                print(f"table at line {start}: column counts {sorted(counts)}")
                for ln, c in table:
                    if c != table[0][1]:
                        print(f"   line {ln}: {c} cells (header {table[0][1]})")
            table = []
print(f"tables checked, problems={problems}, lines={len(lines)}")
sys.exit(0)
