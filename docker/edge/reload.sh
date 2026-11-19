#!/bin/sh
set -u

# Runs inside the running edge. Exit 3: rendering failed; 4: nginx refused the rendered
# configuration and keeps serving the one it has; 5: the reload signal failed.
EDGE_DIR=/etc/nginx/edge

EDGE_RENDER_ONLY=1 sh "${EDGE_DIR}/render-and-run.sh" >/dev/null || exit 3
nginx -t -q -c "${EDGE_DIR}/nginx.conf" || exit 4
nginx -s reload -c "${EDGE_DIR}/nginx.conf" || exit 5
