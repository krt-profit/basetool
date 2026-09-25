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
