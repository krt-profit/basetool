"""Check a Markdown file's tables: every row of a table must have as many unescaped pipes as its header.

Usage: python 70-research-mdcheck.py <file.md>
Pipes inside backtick code spans are ignored.
"""
import re
import sys

def count_pipes(line: str) -> int:
    stripped = re.sub(r"`[^`]*`", "", line)
    return len(re.findall(r"(?<!\\)\|", stripped))

def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    lines = open(sys.argv[1], encoding="utf-8").read().split("\n")
    header = None
    problems = 0
    for number, line in enumerate(lines, 1):
        if line.startswith("|"):
            n = count_pipes(line)
            if header is None:
                header = (number, n)
            elif n != header[1]:
                problems += 1
                print(f"line {number}: {n} pipes, header at line {header[0]} has {header[1]}")
        else:
            header = None
    headings = [l for l in lines if l.startswith("## ") or l.startswith("### ")]
    print(f"{len(lines)} lines, {len(headings)} headings, {problems} table-row problems")

if __name__ == "__main__":
    main()
