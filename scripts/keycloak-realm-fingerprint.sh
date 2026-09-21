#!/usr/bin/env bash
# =============================================================================
# Profit Basetool — Keycloak realm fingerprint
#
# Run it on the old host before the dump and on the new host after the restore,
# then `diff` the two. Identical output means the realms came across whole.
#
# WHY A COUNT IS NOT ENOUGH, and why this exists.
#
# `restore-drill.sh` asserts that the restored Keycloak database has at least
# twenty tables in `public`. A restore that produced the schema and NO ROWS has a
# hundred tables and passes that check. The drill therefore proves the dump is
# structurally restorable; it proves nothing about the realm's content.
#
# This prints identities, not only totals: every client id, every role name,
# every flow and execution, every mapper, every key provider. A missing client is
# then a missing LINE in a diff rather than a number that moved by one -- and a
# number that moved by one is exactly what nobody notices.
#
# WHAT IT DELIBERATELY DOES NOT PRINT.
#
# No secret value, ever: client secrets, credential hashes, key material and IdP
# secrets appear as `present`/`absent` and as counts. A dump restore reproduces
# the bytes or fails loudly; a secret that is present on both sides and different
# is not a failure mode `pg_restore` has. Printing them to compare them would
# create the leak this check is supposed to protect against.
#
# It also does not print user names or e-mail addresses (REQ-SEC / PII): users are
# counted, and their *linkage* is fingerprinted by counting federated identities
# and role mappings per realm.
#
# WHAT LIVES OUTSIDE THE DATABASE, and is therefore NOT covered here. Section E
# names it, because a fingerprint that matched perfectly while the SPI JAR was
# missing would be the most misleading possible result:
#
#   * the SPI provider JAR      — a realm flow referencing `discord-guild-role-gate`
#                                 breaks without it, and 50 users reach this
#                                 deployment through Discord
#   * the theme directory       — the realm names it by string; absent, Keycloak
#                                 silently falls back to its own theme
#   * the keystore              — TLS, not realm content
#   * KRT_DISCORD_SPI_SHARED_SECRET and the KRT_BACKEND_* variables — the SPI's
#                                 own configuration, which lives in the host .env
#
# Usage:
#   scripts/keycloak-realm-fingerprint.sh                      # container db-keycloak
#   scripts/keycloak-realm-fingerprint.sh --container kc-old   # a different one
#   scripts/keycloak-realm-fingerprint.sh --runtime podman     # rootless host
#
#   old:  scripts/keycloak-realm-fingerprint.sh > /tmp/kc.before
#   new:  scripts/keycloak-realm-fingerprint.sh > /tmp/kc.after
#         diff -u /tmp/kc.before /tmp/kc.after && echo "realms identical"
#
# Exit codes: 0 fingerprint produced, 1 the database could not be read, 2 bad
# invocation. It does not decide whether a difference is acceptable -- `diff`
# does, and a human reads it.
# =============================================================================
set -uo pipefail

RUNTIME="${IRI_RUNTIME:-docker}"
CONTAINER="${IRI_KC_DB_CONTAINER:-db-keycloak}"
PORT="${IRI_KC_DB_PORT:-15433}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --runtime)   RUNTIME="$2"; shift 2 ;;
    --container) CONTAINER="$2"; shift 2 ;;
    --port)      PORT="$2"; shift 2 ;;
    -h|--help)   sed -n '2,58p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

command -v "$RUNTIME" >/dev/null 2>&1 || { echo "FATAL: ${RUNTIME} is not on PATH" >&2; exit 2; }

# Every query runs in a READ ONLY session. This script is run against production
# and must not be capable of changing anything, whatever is passed to it.
#
# STDERR IS NOT DISCARDED, and that is the whole point of this comment.
#
# The first version sent it to /dev/null, so a query naming a column that does not
# exist returned NOTHING and the section came out empty. Two sections did exactly
# that against production -- clients and protocol mappers, the two that matter
# most for "do the clients still work" -- because Keycloak 26.7 spells those
# columns `standard_flow_enabled` and `protocol_mapper_name`. The output looked
# like a realm with no clients, and a diff of two such fingerprints matches
# perfectly.
#
# A failed query now prints its error into the fingerprint as a `psql-error|`
# line. That makes it impossible to diff two fingerprints and see agreement where
# there was only silence.
q() {
  local out err rc
  err=$(mktemp)
  out=$("$RUNTIME" exec "$CONTAINER" sh -c \
    "PGPASSWORD=\$POSTGRES_PASSWORD psql -h 127.0.0.1 -p ${PORT} -U \$POSTGRES_USER -d \$POSTGRES_DB \
     -tA -F'|' -q -v ON_ERROR_STOP=1 \
     -c \"SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY;\" -c \"$1\"" 2>"$err")
  rc=$?
  if [[ $rc -ne 0 || -s "$err" ]]; then
    sed 's/^/psql-error|/' "$err"
  fi
  rm -f "$err"
  printf '%s\n' "$out" | grep -v '^$' || true
}

if ! q "SELECT 1;" | grep -q 1; then
  echo "FATAL: cannot read the Keycloak database in container ${CONTAINER}" >&2
  exit 1
fi

echo "# Keycloak realm fingerprint"
echo "# Compare two of these with diff. Any line that differs is a difference in the realm."
echo

echo "== A. schema version =="
echo "# A restore into a NEWER Keycloak migrates the schema on first start, which is one-way."
echo "# During a cutover both hosts must run the same image digest; the version bump is its own"
echo "# operator-gated change afterwards."
q "SELECT 'schema|' || version FROM migration_model ORDER BY update_time DESC LIMIT 1;"
echo

echo "== B. realms =="
q "SELECT 'realm|' || name || '|enabled=' || enabled || '|ssl=' || ssl_required
        || '|login_theme=' || coalesce(login_theme,'-')
        || '|account_theme=' || coalesce(account_theme,'-')
        || '|email_theme=' || coalesce(email_theme,'-')
        || '|registration=' || registration_allowed
        || '|reset_password=' || reset_password_allowed
   FROM realm ORDER BY name;"
echo

echo "== C. clients — the thing that must keep working =="
echo "# has_secret is presence only. A dump restore reproduces the byte or fails; printing"
echo "# secrets to compare them would create the leak this check exists to avoid."
# EVERY concatenated column is coalesced and cast, and that is not defensive
# habit. In PostgreSQL `'x' || NULL` is NULL, so ONE nullable column makes the
# WHOLE row vanish -- and this query produced zero rows on the first run against
# production while the realm has nineteen clients. A fingerprint whose most
# important section is silently empty is worse than no fingerprint, because the
# diff of two empty sections matches.
q "SELECT 'client|' || coalesce(r.name,'?') || '|' || coalesce(c.client_id,'?')
        || '|enabled=' || coalesce(c.enabled::text,'?')
        || '|public=' || coalesce(c.public_client::text,'?')
        || '|service_accounts=' || coalesce(c.service_accounts_enabled::text,'?')
        || '|standard_flow=' || coalesce(c.standard_flow_enabled::text,'?')
        || '|bearer_only=' || coalesce(c.bearer_only::text,'?')
        || '|protocol=' || coalesce(c.protocol,'openid-connect')
        || '|has_secret=' || coalesce((c.secret IS NOT NULL AND c.secret <> '')::text,'?')
   FROM client c JOIN realm r ON c.realm_id = r.id ORDER BY 1;"
echo
echo "-- redirect URIs and web origins, which decide whether a login can complete --"
q "SELECT 'redirect|' || r.name || '|' || c.client_id || '|' || u.value
   FROM redirect_uris u JOIN client c ON u.client_id = c.id JOIN realm r ON c.realm_id = r.id
   ORDER BY r.name, c.client_id, u.value;"
q "SELECT 'weborigin|' || r.name || '|' || c.client_id || '|' || w.value
   FROM web_origins w JOIN client c ON w.client_id = c.id JOIN realm r ON c.realm_id = r.id
   ORDER BY r.name, c.client_id, w.value;"
echo

echo "== D. roles, scopes and mappers =="
q "SELECT 'role|' || r.name || '|' || coalesce(c.client_id,'<realm>') || '|' || k.name
   FROM keycloak_role k JOIN realm r ON k.realm_id = r.id
   LEFT JOIN client c ON k.client = c.id ORDER BY 1;"
q "SELECT 'scope|' || r.name || '|' || s.name || '|' || coalesce(s.protocol,'-')
   FROM client_scope s JOIN realm r ON s.realm_id = r.id ORDER BY 1;"
q "SELECT 'mapper|' || coalesce(r1.name, r2.name, '?') || '|'
        || coalesce(c.client_id, s.name, '?') || '|' || coalesce(m.name,'?')
        || '|' || coalesce(m.protocol_mapper_name,'?') || '|' || coalesce(m.protocol,'?')
   FROM protocol_mapper m
   LEFT JOIN client c ON m.client_id = c.id
   LEFT JOIN realm r1 ON c.realm_id = r1.id
   LEFT JOIN client_scope s ON m.client_scope_id = s.id
   LEFT JOIN realm r2 ON s.realm_id = r2.id
   ORDER BY 1;"
echo

echo "== E. authentication flows — where a CUSTOM authenticator would be referenced =="
echo "# An execution naming a provider the SPI JAR supplies breaks if the JAR is not staged"
echo "# BEFORE Keycloak starts. That file is not in this database and not in this fingerprint."
q "SELECT 'flow|' || r.name || '|' || f.alias || '|builtin=' || f.built_in || '|toplevel=' || f.top_level
   FROM authentication_flow f JOIN realm r ON f.realm_id = r.id ORDER BY 1;"
q "SELECT 'execution|' || r.name || '|' || coalesce(f.alias,'?') || '|'
        || coalesce(e.authenticator,'<flow>') || '|' || e.requirement || '|' || e.priority
   FROM authentication_execution e JOIN realm r ON e.realm_id = r.id
   LEFT JOIN authentication_flow f ON e.flow_id = f.id ORDER BY 1;"
echo

echo "== F. identity providers =="
q "SELECT 'idp|' || r.name || '|' || i.provider_alias || '|' || i.provider_id
        || '|enabled=' || i.enabled || '|trust_email=' || i.trust_email
   FROM identity_provider i JOIN realm r ON i.realm_id = r.id ORDER BY 1;"
q "SELECT 'idpmapper|' || r.name || '|' || m.idp_alias || '|' || m.name || '|' || m.idp_mapper_name
   FROM identity_provider_mapper m JOIN realm r ON m.realm_id = r.id ORDER BY 1;"
echo

echo "== G. key providers — if these do not survive, every issued token dies =="
echo "# The key MATERIAL lives in component_config and comes across with the dump. Only the"
echo "# providers are named here; their secrets are counted, never printed."
q "SELECT 'keyprovider|' || r.name || '|' || c.provider_id || '|' || c.name
   FROM component c JOIN realm r ON c.realm_id = r.id
   WHERE c.provider_type LIKE '%KeyProvider%' ORDER BY 1;"
q "SELECT 'component|' || r.name || '|' || c.provider_type || '|' || c.provider_id
   FROM component c JOIN realm r ON c.realm_id = r.id ORDER BY 1;"
echo

echo "== H. required actions =="
q "SELECT 'requiredaction|' || r.name || '|' || a.provider_id
        || '|enabled=' || a.enabled || '|default=' || a.default_action
   FROM required_action_provider a JOIN realm r ON a.realm_id = r.id ORDER BY 1;"
echo

echo "== I. counts — users and credentials, never their identities =="
q "SELECT 'count|' || r.name || '|users|' || count(*) FROM user_entity u
   JOIN realm r ON u.realm_id = r.id GROUP BY r.name ORDER BY 1;"
q "SELECT 'count|' || r.name || '|credentials|' || count(*) FROM credential cr
   JOIN user_entity u ON cr.user_id = u.id JOIN realm r ON u.realm_id = r.id GROUP BY r.name ORDER BY 1;"
q "SELECT 'count|' || r.name || '|federated_identities|' || count(*) FROM federated_identity f
   JOIN user_entity u ON f.user_id = u.id JOIN realm r ON u.realm_id = r.id GROUP BY r.name ORDER BY 1;"
q "SELECT 'count|' || r.name || '|user_role_mappings|' || count(*) FROM user_role_mapping m
   JOIN user_entity u ON m.user_id = u.id JOIN realm r ON u.realm_id = r.id GROUP BY r.name ORDER BY 1;"
q "SELECT 'count|' || r.name || '|user_attributes|' || count(*) FROM user_attribute a
   JOIN user_entity u ON a.user_id = u.id JOIN realm r ON u.realm_id = r.id GROUP BY r.name ORDER BY 1;"
q "SELECT 'count|' || r.name || '|groups|' || count(*) FROM keycloak_group g
   JOIN realm r ON g.realm_id = r.id GROUP BY r.name ORDER BY 1;"
q "SELECT 'count|total|component_config|' || count(*) FROM component_config;"
q "SELECT 'count|total|realm_attribute|' || count(*) FROM realm_attribute;"
q "SELECT 'count|total|client_attributes|' || count(*) FROM client_attributes;"
q "SELECT 'count|total|composite_role|' || count(*) FROM composite_role;"
q "SELECT 'count|total|client_scope_client|' || count(*) FROM client_scope_client;"
q "SELECT 'count|total|scope_mapping|' || count(*) FROM scope_mapping;"
q "SELECT 'count|total|authenticator_config|' || count(*) FROM authenticator_config;"
echo

echo "== J. self-check =="
echo "# A section that is silently empty defeats the whole point: two empty sections diff clean."
echo "# The first run of this script against production emitted ZERO client lines, because one"
echo "# nullable column made every concatenated row NULL. It looked like a realm with no clients."
# Counted from the database directly rather than from this script's own output, so a query that
# returns nothing is caught by comparing against the table it was supposed to read.
expect() { # $1 label, $2 count-query
  local have; have=$(q "$2")
  if [[ "${have:-0}" =~ ^[0-9]+$ ]] && [[ "${have}" -gt 0 ]]; then
    echo "selfcheck|${1}|rows_in_db=${have}|ok"
  else
    echo "selfcheck|${1}|rows_in_db=${have:-?}|*** EMPTY OR UNREADABLE — this section proves nothing ***"
  fi
}
expect clients          "SELECT count(*) FROM client;"
expect roles            "SELECT count(*) FROM keycloak_role;"
expect client_scopes    "SELECT count(*) FROM client_scope;"
expect protocol_mappers "SELECT count(*) FROM protocol_mapper;"
expect flows            "SELECT count(*) FROM authentication_flow;"
expect executions       "SELECT count(*) FROM authentication_execution;"
expect components       "SELECT count(*) FROM component;"
expect users            "SELECT count(*) FROM user_entity;"
echo
echo "# Compare each rows_in_db above with the number of matching lines in this file:"
echo "#   grep -c '^client|'  fingerprint     should equal selfcheck|clients"
echo "#   grep -c '^mapper|'  fingerprint     should equal selfcheck|protocol_mappers"
echo "# A section whose line count is below its rows_in_db lost rows to a NULL and must be fixed"
echo "# before the fingerprint is trusted for a migration."
echo

echo "# end of fingerprint"
