#!/bin/sh
set -eu
if [ -z "${ACME_HOSTS:-}" ]; then
  echo "acme: ACME_HOSTS is empty — no certificates are managed on this host"
  while true; do sleep 86400; done
fi
: "${ACME_EMAIL:?ACME_EMAIL must be set in .env for the acme container}"
PRIMARY="${ACME_HOSTS%% *}"
DOMAINS=""
for h in ${ACME_HOSTS}; do DOMAINS="${DOMAINS} -d $h"; done
while true; do
  echo "Y" | /lego migrate --path /data ||
    echo "acme: migrate failed, will retry on the next pass" >&2
  # shellcheck disable=SC2086
  /lego run --accept-tos --email "${ACME_EMAIL}" --path /data                --http --http.webroot /webroot --renew-days 30 ${DOMAINS} ||             echo "acme: run/renew failed, will retry on the next pass" >&2
  if [ -f "/data/certificates/${PRIMARY}.crt" ]; then
    publish() {
      cp "$1" "$2.new"
      chmod "$3" "$2.new"
      chown 101:101 "$2.new"
      mv -f "$2.new" "$2"
    }
    chown 0:0 /certs
    for h in ${ACME_HOSTS}; do
      mkdir -p "/certs/$h"
      chown 0:0 "/certs/$h"
      publish "/data/certificates/${PRIMARY}.crt" "/certs/$h/fullchain.pem" 0644
      publish "/data/certificates/${PRIMARY}.key" "/certs/$h/privkey.pem"   0600
    done
  fi
  sleep 43200
done
