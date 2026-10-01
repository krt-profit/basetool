"""Dump per-controller facts from 30-frontend-java-scan.json for manual domain assignment."""

import io
import json
import os
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
HERE = os.path.dirname(os.path.abspath(__file__))
data = json.load(open(os.path.join(HERE, "30-frontend-java-scan.json"), encoding="utf-8"))
for c in sorted(data["classes"], key=lambda x: x["name"]):
    if c["package"] != "controller":
        continue
    a = c["annotations"]
    stereo = "REST" if a["RestController"] else ("VIEW" if a["Controller"] else "-")
    print(f'{c["name"]:45s} {stereo:4s} L{c["lines"]:5d} h{c["handlers"]:3d} rb{c["response_body"]:3d} '
          f'cpa={int(a["ClassPreAuthorize"])} mpa={c["method_preauthorize"]:2d} wc={int(c["direct_webclient"])} '
          f'map={c["request_mapping"]} segs={c["api_segments"]}')
