"""Lists findings whose evidence string names no PR number or commit."""
import importlib.util
import os
import re

BASE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("d", os.path.join(BASE, "80-prev-sept-a-data.py"))
d = importlib.util.module_from_spec(spec)
spec.loader.exec_module(d)
for k, v in d.E.items():
    if not re.search(r"#\d{4}|674e55bd7", v["evidence"]):
        print(k)
