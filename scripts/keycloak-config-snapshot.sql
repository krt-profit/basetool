-- Profit Basetool - squadron-management web app.
-- Copyright (C) 2026 Lucas Greuloch
--
-- SPDX-License-Identifier: GPL-3.0-only
--
-- Keycloak realm CONFIGURATION snapshot of the realm `iri`, one fact per line, secret-free, for
-- comparing two environments with `diff`. It is what production's shape was read from on
-- 2026-09-22, and what scripts/provision-keycloak-realm.py encodes; re-run it on both hosts after
-- provisioning and diff the two outputs.
--
-- WHAT IT IS NOT. scripts/keycloak-realm-fingerprint.sh answers "did a dump restore bring the
-- realm across whole" (identities, flows, key providers, counts). This one answers "are two realms
-- configured alike": client flags and attributes, redirect URIs, scope assignments, mapper configs,
-- scope role mappings, service-account roles, client policies and token settings.
--
-- NOTHING SENSITIVE IS PRINTED. A client secret appears only as `present`/`absent`; attributes
-- whose name suggests a credential, key, certificate or token value are excluded by name; no user
-- is printed (the service-account query joins users only to reach their role mappings).
--
-- READ-ONLY BY CONSTRUCTION. The first statement makes the session read-only, so nothing below can
-- write whatever is edited into it. Reading needs no approval under the production-host rule; the
-- recipe (vault `60 Runbooks/Production Access.md`, and docs/keycloak/README.md) feeds this file on
-- stdin so `$POSTGRES_USER` / `$POSTGRES_DB` expand INSIDE the container and no credential reaches
-- a command line:
--
--   ssh <host> 'cd / && sudo -n -u iri podman exec -i db-keycloak sh -c "psql -qAt -U \$POSTGRES_USER -d \$POSTGRES_DB -p 15433 -f -"' \
--       < scripts/keycloak-config-snapshot.sql > realm-<env>.txt
--
-- From PowerShell, strip the carriage returns first (the file is LF in the repository; a CRLF copy
-- makes psql read `\pset footer off\r`). Then:
--
--   diff realm-prod.txt realm-testing.txt
--
-- Lines that legitimately differ between two environments: the public origin in redirect URIs,
-- web origins and `post.logout.redirect.uris`; `realm_client` on every client and the `basic` /
-- `acr` / `service_account` scope mapper configs of Keycloak's built-ins (both are artefacts of the
-- Keycloak version a realm was created under, and built-ins are left alone); anything the
-- provisioner reports as "only on this realm". Every other line is drift.
--
-- A NULL anywhere in a concatenation makes the whole row vanish in PostgreSQL, and a section that
-- silently comes back empty diffs clean against another empty section. Every nullable column is
-- therefore coalesced. (The realm-role section returned nothing on 2026-09-22 because it joined the
-- wrong column; fixed here to `realm_id`, which keycloak-realm-fingerprint.sh uses too.)

SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY;
\pset footer off

SELECT 'client|' || c.client_id || '|enabled=' || c.enabled || '|public=' || c.public_client
    || '|bearer=' || c.bearer_only || '|std=' || c.standard_flow_enabled || '|implicit=' || c.implicit_flow_enabled
    || '|direct=' || c.direct_access_grants_enabled || '|svcacct=' || c.service_accounts_enabled
    || '|consent=' || c.consent_required || '|fullscope=' || c.full_scope_allowed
    || '|frontlogout=' || c.frontchannel_logout || '|protocol=' || coalesce(c.protocol, '-')
    || '|root=' || coalesce(c.root_url, '-') || '|base=' || coalesce(c.base_url, '-')
    || '|authtype=' || coalesce(c.client_authenticator_type, '-')
    || '|secret=' || CASE WHEN c.secret IS NULL OR c.secret = '' THEN 'absent' ELSE 'present' END
  FROM client c JOIN realm r ON r.id = c.realm_id WHERE r.name = 'iri' ORDER BY 1;

SELECT 'clientattr|' || c.client_id || '|' || a.name || '=' || coalesce(left(a.value, 300), '<null>')
  FROM client_attributes a JOIN client c ON c.id = a.client_id JOIN realm r ON r.id = c.realm_id
 WHERE r.name = 'iri' AND a.name !~* '(secret|key|cert|jwks|credential|password|token\.value)'
 ORDER BY 1;

SELECT 'redirect|' || c.client_id || '|' || u.value
  FROM redirect_uris u JOIN client c ON c.id = u.client_id JOIN realm r ON r.id = c.realm_id
 WHERE r.name = 'iri' ORDER BY 1;

SELECT 'weborigin|' || c.client_id || '|' || w.value
  FROM web_origins w JOIN client c ON c.id = w.client_id JOIN realm r ON r.id = c.realm_id
 WHERE r.name = 'iri' ORDER BY 1;

SELECT 'scope|' || s.name || '|' || coalesce(s.protocol, '-')
  FROM client_scope s JOIN realm r ON r.id = s.realm_id WHERE r.name = 'iri' ORDER BY 1;

SELECT 'scopeattr|' || s.name || '|' || a.name || '=' || coalesce(left(a.value, 200), '<null>')
  FROM client_scope_attributes a JOIN client_scope s ON s.id = a.scope_id JOIN realm r ON r.id = s.realm_id
 WHERE r.name = 'iri' ORDER BY 1;

-- The realm's DEFAULT client scopes: attached to every client created later (hardening step 9a).
SELECT 'realmdefaultscope|' || s.name || '|' || CASE WHEN d.default_scope THEN 'default' ELSE 'optional' END
  FROM default_client_scope d JOIN client_scope s ON s.id = d.scope_id JOIN realm r ON r.id = d.realm_id
 WHERE r.name = 'iri' ORDER BY 1;

SELECT 'scopeuse|' || c.client_id || '|' || s.name || '|' || CASE WHEN x.default_scope THEN 'default' ELSE 'optional' END
  FROM client_scope_client x JOIN client c ON c.id = x.client_id JOIN client_scope s ON s.id = x.scope_id
  JOIN realm r ON r.id = c.realm_id
 WHERE r.name = 'iri' ORDER BY 1;

SELECT 'mapper|' || coalesce(c.client_id, 'scope:' || s.name) || '|' || m.name || '|' || m.protocol_mapper_name
    || '|' || coalesce((SELECT string_agg(k.name || '=' || coalesce(left(k.value, 120), '<null>'), ',' ORDER BY k.name)
                          FROM protocol_mapper_config k WHERE k.protocol_mapper_id = m.id), '')
  FROM protocol_mapper m LEFT JOIN client c ON c.id = m.client_id LEFT JOIN client_scope s ON s.id = m.client_scope_id
  JOIN realm r ON r.id = coalesce(c.realm_id, s.realm_id)
 WHERE r.name = 'iri' ORDER BY 1;

SELECT 'clientrole|' || c.client_id || '|' || k.name
  FROM keycloak_role k JOIN client c ON c.id = k.client JOIN realm r ON r.id = c.realm_id
 WHERE r.name = 'iri' AND k.client_role ORDER BY 1;

SELECT 'realmrole|' || k.name
  FROM keycloak_role k JOIN realm r ON r.id = k.realm_id
 WHERE r.name = 'iri' AND NOT k.client_role ORDER BY 1;

SELECT 'clientscopemap|' || c.client_id || '|' || coalesce(rc.client_id, '<realm>') || ':' || k.name
  FROM scope_mapping sm JOIN client c ON c.id = sm.client_id JOIN keycloak_role k ON k.id = sm.role_id
  LEFT JOIN client rc ON rc.id = k.client JOIN realm r ON r.id = c.realm_id
 WHERE r.name = 'iri' ORDER BY 1;

SELECT 'svcacctrole|' || c.client_id || '|' || coalesce(rc.client_id, '<realm>') || ':' || k.name
  FROM user_entity u JOIN client c ON c.id = u.service_account_client_link
  JOIN user_role_mapping m ON m.user_id = u.id JOIN keycloak_role k ON k.id = m.role_id
  LEFT JOIN client rc ON rc.id = k.client JOIN realm r ON r.id = c.realm_id
 WHERE r.name = 'iri' ORDER BY 1;

SELECT 'realmattr|' || a.name || '=' || coalesce(left(a.value, 2000), '<null>')
  FROM realm_attribute a JOIN realm r ON r.id = a.realm_id
 WHERE r.name = 'iri' AND (a.name LIKE 'client-policies%' OR a.name ~* '(refresh|revoke|lifespan|dpop|pkce|offline|session)')
 ORDER BY 1;

SELECT 'realm|' || name || '|revokeRefresh=' || revoke_refresh_token || '|refreshMaxReuse=' || refresh_token_max_reuse
    || '|accessLifespan=' || access_token_lifespan || '|ssoIdle=' || sso_idle_timeout || '|ssoMax=' || sso_max_lifespan
  FROM realm WHERE name = 'iri';
