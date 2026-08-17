#!/usr/bin/env bash
#
# Provision the Keycloak pieces the Android app needs: the DPoP client policy and the
# `basetool-android` public client.
#
# Run it against the TEST realm first — the DPoP behaviour has to be verified there before the
# production realm is touched (docs/ANDROID_API_EXPOSURE_PLAN.md, work package E1).
#
# Read-only by default: without --apply the script only reports what it would change.
#
# Usage:
#   KC_SERVER=https://keycloak.example/ KC_REALM=iri \
#   KC_ADMIN_USER=admin KC_ADMIN_PASSWORD=... \
#   scripts/keycloak/setup-android-client.sh [--apply] [--redirect-uri URI]
#
# Inside the Keycloak container:
#   KC_ADM=/opt/keycloak/bin/kcadm.sh ... scripts/keycloak/setup-android-client.sh
#
# The password is read from the environment and never echoed. The script writes nothing to disk.

set -euo pipefail

KC_ADM="${KC_ADM:-kcadm.sh}"
KC_SERVER="${KC_SERVER:-}"
KC_REALM="${KC_REALM:-}"
KC_ADMIN_USER="${KC_ADMIN_USER:-}"
KC_ADMIN_PASSWORD="${KC_ADMIN_PASSWORD:-}"

CLIENT_ID="basetool-android"
PROFILE_NAME="krt-mobile-dpop"
POLICY_NAME="krt-mobile-dpop-policy"

# Production uses the verified App Link; the custom scheme exists only for the test realm, because
# any installed app can claim a custom scheme (PKCE stops code theft, not the confusion surface).
REDIRECT_URI="${REDIRECT_URI:-de.kartell.basetool:/oauth2redirect}"

APPLY=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --apply) APPLY=1; shift ;;
    --redirect-uri) REDIRECT_URI="$2"; shift 2 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

for required in KC_SERVER KC_REALM KC_ADMIN_USER KC_ADMIN_PASSWORD; do
  if [[ -z "${!required}" ]]; then
    echo "missing environment variable: $required" >&2
    exit 2
  fi
done

command -v jq >/dev/null || { echo "jq is required" >&2; exit 2; }

say() { printf '\n=== %s\n' "$1"; }
note() { printf '    %s\n' "$1"; }

$KC_ADM config credentials \
  --server "$KC_SERVER" --realm master \
  --user "$KC_ADMIN_USER" --password "$KC_ADMIN_PASSWORD" >/dev/null

# ---------------------------------------------------------------------------------------------
# 1. Report the current state before changing anything.
# ---------------------------------------------------------------------------------------------
say "current realm state ($KC_REALM)"
realm_json="$($KC_ADM get "realms/$KC_REALM")"
note "sslRequired          = $(jq -r '.sslRequired' <<<"$realm_json")"
note "revokeRefreshToken   = $(jq -r '.revokeRefreshToken' <<<"$realm_json")"
note "accessTokenLifespan  = $(jq -r '.accessTokenLifespan' <<<"$realm_json")"

profiles_json="$($KC_ADM get client-policies/profiles -r "$KC_REALM")"
policies_json="$($KC_ADM get client-policies/policies -r "$KC_REALM")"
profile_count="$(jq '[.profiles[]? | select(.global != true)] | length' <<<"$profiles_json")"
policy_count="$(jq '.policies | length' <<<"$policies_json")"
note "client profiles      = $profile_count (non-global)"
note "client policies      = $policy_count"

# The realm export of 2026-08-17 showed none, while docs/INGEST_KEYCLOAK_SETUP.md describes an
# `extractor-dpop` policy. Whichever is true, this script MERGES: a blind PUT of the profile or
# policy list would silently delete whatever else is there and break the extractor's token flow.
if [[ "$policy_count" != "0" ]]; then
  note "existing policies    = $(jq -r '[.policies[].name] | join(", ")' <<<"$policies_json")"
fi

existing_client="$($KC_ADM get clients -r "$KC_REALM" -q "clientId=$CLIENT_ID" --fields id,clientId)"
client_uuid="$(jq -r '.[0].id // empty' <<<"$existing_client")"
note "client $CLIENT_ID = ${client_uuid:-<absent>}"

# ---------------------------------------------------------------------------------------------
# 2. Build the payloads.
# ---------------------------------------------------------------------------------------------
# The refresh-only behaviour lives in the executor, never on the client: the per-client
# "Require DPoP bound tokens" switch would bind the ACCESS token too, and Spring Security's bearer
# filter rejects an access token carrying `cnf.jkt` outright.
profile_payload="$(jq -n --arg name "$PROFILE_NAME" '
  {
    name: $name,
    description: "Binds the refresh token of the mobile client to its DPoP key; access tokens stay plain Bearer.",
    executors: [
      {
        executor: "dpop-bind-enforcer",
        configuration: {
          "auto-configure": false,
          "enforce-authorization-code-binding-to-dpop": false,
          "allow-only-refresh-token-binding": true
        }
      }
    ]
  }')"

policy_payload="$(jq -n --arg name "$POLICY_NAME" --arg profile "$PROFILE_NAME" --arg client "$CLIENT_ID" '
  {
    name: $name,
    description: "Applies the mobile DPoP profile to the Android client only.",
    enabled: true,
    conditions: [
      { condition: "client-updater-source-roles", configuration: {} } | del(.condition) + { condition: "clients", configuration: { clients: [$client] } }
    ],
    profiles: [$profile]
  }')"

# The audience mapper sits on the client itself rather than on the shared `extractor-ingest` scope:
# that scope is documented as the extractor/frontend vehicle, and coupling a third client to it
# risks the exact scope mix-up docs/keycloak/README.md warns about. The emitted claim is identical.
client_payload="$(jq -n --arg clientId "$CLIENT_ID" --arg redirect "$REDIRECT_URI" '
  {
    clientId: $clientId,
    name: "Basetool Android",
    description: "Native Android companion app (public client, RFC 8252).",
    enabled: true,
    publicClient: true,
    protocol: "openid-connect",
    standardFlowEnabled: true,
    directAccessGrantsEnabled: false,
    serviceAccountsEnabled: false,
    implicitFlowEnabled: false,
    fullScopeAllowed: false,
    redirectUris: [$redirect],
    webOrigins: [],
    attributes: {
      "pkce.code.challenge.method": "S256",
      "oauth2.device.authorization.grant.enabled": "false",
      "dpop.bound.access.tokens": "false",
      "access.token.lifespan": "300"
    },
    protocolMappers: [
      {
        name: "aud-basetool-backend",
        protocol: "openid-connect",
        protocolMapper: "oidc-audience-mapper",
        config: {
          "included.client.audience": "basetool-backend",
          "access.token.claim": "true",
          "id.token.claim": "false",
          "introspection.token.claim": "true"
        }
      }
    ]
  }')"

if [[ "$APPLY" -eq 0 ]]; then
  say "dry run — nothing was changed"
  note "would ensure client profile : $PROFILE_NAME"
  note "would ensure client policy  : $POLICY_NAME (scoped to $CLIENT_ID)"
  note "would ${client_uuid:+update}${client_uuid:-create} client : $CLIENT_ID"
  note "redirect URI                : $REDIRECT_URI"
  note "re-run with --apply to write"
  exit 0
fi

# ---------------------------------------------------------------------------------------------
# 3. Apply, merging into whatever already exists.
# ---------------------------------------------------------------------------------------------
say "applying"

merged_profiles="$(jq --argjson new "$profile_payload" '
  .profiles = ([ (.profiles // [])[] | select(.name != $new.name) ] + [$new])' <<<"$profiles_json")"
printf '%s' "$merged_profiles" | $KC_ADM update client-policies/profiles -r "$KC_REALM" -f - >/dev/null
note "profile $PROFILE_NAME ensured"

merged_policies="$(jq --argjson new "$policy_payload" '
  .policies = ([ (.policies // [])[] | select(.name != $new.name) ] + [$new])' <<<"$policies_json")"
printf '%s' "$merged_policies" | $KC_ADM update client-policies/policies -r "$KC_REALM" -f - >/dev/null
note "policy $POLICY_NAME ensured"

if [[ -n "$client_uuid" ]]; then
  printf '%s' "$client_payload" | $KC_ADM update "clients/$client_uuid" -r "$KC_REALM" -f - >/dev/null
  note "client $CLIENT_ID updated"
else
  printf '%s' "$client_payload" | $KC_ADM create clients -r "$KC_REALM" -f - >/dev/null
  note "client $CLIENT_ID created"
fi

say "verify next (work package E1)"
note "1. run the app's login against this realm"
note "2. decode the ACCESS token: 'cnf' must be ABSENT and token_type must be 'Bearer'"
note "3. decode the REFRESH token: it must carry the DPoP thumbprint"
note "4. replay a refresh with a different key: it must fail"
note "If 'cnf' appears on the access token, allow-only-refresh-token-binding did not take effect —"
note "do not proceed to production; see docs/ANDROID_API_EXPOSURE_PLAN.md work package E1."
