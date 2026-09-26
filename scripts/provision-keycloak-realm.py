#!/usr/bin/env python3
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only

from __future__ import annotations

import argparse
import importlib.util
import json
import os
import re
import shlex
import sys
from dataclasses import dataclass, field
from pathlib import Path
from collections.abc import Callable


def _load_mobile_provisioner():
    """Import scripts/provision-keycloak-mobile-client.py (a hyphenated, unimportable name)."""
    path = Path(__file__).resolve().with_name("provision-keycloak-mobile-client.py")
    spec = importlib.util.spec_from_file_location("provision_keycloak_mobile_client", path)
    if spec is None or spec.loader is None:
        raise SystemExit(f"FATAL: cannot load the mobile provisioner from {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


mobile = _load_mobile_provisioner()
KcadmError = mobile.KcadmError

BUILTIN_CLIENTS = frozenset({
    "account", "account-console", "admin-cli", "broker", "realm-management",
    "security-admin-console",
})

AUDIENCE_SCOPES = ("extractor-ingest", "extractor-ingest-only")

REALM_SETTINGS: dict[str, bool | int | str] = {
    "revokeRefreshToken": False,
    "refreshTokenMaxReuse": 5,
    "accessTokenLifespan": 300,
    "ssoSessionIdleTimeout": 2592000,
    "ssoSessionMaxLifespan": 15552000,
    "offlineSessionMaxLifespanEnabled": True,
    "offlineSessionMaxLifespan": 7776000,
    "clientSessionIdleTimeout": 0,
    "clientSessionMaxLifespan": 0,
    "clientOfflineSessionIdleTimeout": 0,
    "clientOfflineSessionMaxLifespan": 0,
    "oauth2DeviceCodeLifespan": 600,
    "oauth2DevicePollingInterval": 5,
    "loginTheme": "krt-theme",
}

EXTERNAL_CLIENTS_FILE = Path(__file__).resolve().parent / "keycloak" / "external-clients.json"

EXCHANGE_OFFLINE_SESSION_IDLE_SECONDS = 2592000
EXCHANGE_OFFLINE_SESSION_MAX_SECONDS = 7776000

EXCHANGE_SCOPES: list[tuple[str, str]] = [
    ("exchange.connect", "xchConsentConnect"),
    ("exchange.blueprints.read", "xchConsentBlueprintsRead"),
    ("exchange.blueprints.write", "xchConsentBlueprintsWrite"),
    ("exchange.stock.read", "xchConsentStockRead"),
    ("exchange.stock.write", "xchConsentStockWrite"),
    ("exchange.hangar.read", "xchConsentHangarRead"),
    ("exchange.hangar.write", "xchConsentHangarWrite"),
    ("exchange.demand.read", "xchConsentDemandRead"),
    ("exchange.drafts.blueprints", "xchConsentDraftsBlueprints"),
    ("exchange.drafts.refinery", "xchConsentDraftsRefinery"),
]
EXCHANGE_SCOPE_NAMES = [name for name, _ in EXCHANGE_SCOPES]

EXTRACTOR_EXCHANGE_SCOPES = ["exchange.connect", "exchange.blueprints.read",
                             "exchange.blueprints.write", "exchange.drafts.blueprints",
                             "exchange.drafts.refinery"]

AUDIENCE_MAPPER_CONFIG = {
    "access.token.claim": "true",
    "id.token.claim": "false",
    "introspection.token.claim": "true",
    "lightweight.claim": "false",
}

_SCOPE_ATTRIBUTES_COMMON = {
    "consent.screen.text": "",
    "display.on.consent.screen": "true",
    "gui.order": "",
    "include.in.openid.provider.metadata": "true",
}


@dataclass
class ScopeSpec:
    """One client scope the Basetool owns, with its attributes and mappers."""

    name: str
    attributes: dict[str, str]
    mappers: list[dict]


SCOPES = [
    ScopeSpec(
        name="extractor-ingest",
        attributes={**_SCOPE_ATTRIBUTES_COMMON, "include.in.token.scope": "false"},
        mappers=[{
            "name": "aud-basetool-backend",
            "protocolMapper": "oidc-audience-mapper",
            "config": {**AUDIENCE_MAPPER_CONFIG, "included.custom.audience": "basetool-backend"},
        }],
    ),
    ScopeSpec(
        name="extractor-ingest-only",
        attributes={**_SCOPE_ATTRIBUTES_COMMON, "include.in.token.scope": "true"},
        mappers=[{
            "name": "aud-basetool-ingest",
            "protocolMapper": "oidc-audience-mapper",
            "config": {**AUDIENCE_MAPPER_CONFIG, "included.custom.audience": "basetool-ingest"},
        }],
    ),
    *[ScopeSpec(
        name=name,
        attributes={**_SCOPE_ATTRIBUTES_COMMON, "include.in.token.scope": "true",
                    "consent.screen.text": "${" + key + "}", "gui.order": str(order)},
        mappers=[{
            "name": "aud-basetool-ingest",
            "protocolMapper": "oidc-audience-mapper",
            "config": {**AUDIENCE_MAPPER_CONFIG, "included.custom.audience": "basetool-ingest"},
        }],
    ) for order, (name, key) in enumerate(EXCHANGE_SCOPES, 1)],
]


@dataclass
class ClientSpec:
    """The production shape of one Basetool client.

    `fields` are converged on every run; `create_only` is written only on creation. List fields
    are unions: missing entries are added, extra ones reported, and `withheld_*` entries removed
    wherever found. `value_env` names the environment variable that supplies a confidential
    client's credential; only that name is ever printed.
    """

    client_id: str
    kind: str
    fields: dict
    attributes: dict[str, str]
    redirect_uris: list[str]
    web_origins: list[str]
    default_scopes: list[str]
    optional_scopes: list[str]
    create_only: dict = field(default_factory=dict)
    withheld_scopes: list[str] = field(default_factory=list)
    withheld_redirect_uris: list[str] = field(default_factory=list)
    withheld_web_origins: list[str] = field(default_factory=list)
    withheld_reason: str = ""
    mappers: list[dict] = field(default_factory=list)
    client_roles: list[dict] = field(default_factory=list)
    realm_role_scope: list[str] | None = None
    service_account_roles: dict[str, list[str]] | None = None
    env_vars_to_fill: list[str] = field(default_factory=list)
    env_doc_hint: str = ""
    frozen_by_dpop_policy: bool = False
    unmanaged_fields: set[str] = field(default_factory=set)
    value_env: str | None = None


def _flags(*, public: bool, standard: bool, service_accounts: bool, full_scope: bool,
           frontchannel_logout: bool, consent: bool = False) -> dict:
    """The scalar client fields every Basetool client pins; the flows that differ are arguments."""
    fields = {
        "enabled": True,
        "protocol": "openid-connect",
        "publicClient": public,
        "bearerOnly": False,
        "standardFlowEnabled": standard,
        "implicitFlowEnabled": False,
        "directAccessGrantsEnabled": False,
        "serviceAccountsEnabled": service_accounts,
        "consentRequired": consent,
        "fullScopeAllowed": full_scope,
        "frontchannelLogout": frontchannel_logout,
    }
    if not public:
        fields["clientAuthenticatorType"] = "client-secret"
    return fields


def _user_attribute_mapper(name: str, json_type: str, *, introspection: bool,
                           lightweight: bool) -> dict:
    """An oidc-usermodel-attribute-mapper emitting user attribute `name` as claim `name`."""
    config = {
        "access.token.claim": "true",
        "claim.name": name,
        "id.token.claim": "true",
        "jsonType.label": json_type,
        "user.attribute": name,
        "userinfo.token.claim": "true",
    }
    if introspection:
        config["introspection.token.claim"] = "true"
    if lightweight:
        config["lightweight.claim"] = "false"
    return {"name": name, "protocolMapper": "oidc-usermodel-attribute-mapper", "config": config}


_STANDARD_DEFAULT = ["acr", "basic", "email", "profile", "roles", "web-origins"]
_STANDARD_OPTIONAL = ["address", "microprofile-jwt", "offline_access", "organization", "phone"]

_CONFIDENTIAL_ATTRIBUTES = {
    "backchannel.logout.revoke.offline.tokens": "false",
    "backchannel.logout.session.required": "true",
    "dpop.bound.access.tokens": "false",
    "oauth2.device.authorization.grant.enabled": "false",
    "oidc.ciba.grant.enabled": "false",
    "standard.token.exchange.enabled": "false",
}


def validate_origin(value: str, flag: str) -> str:
    """Accept a bare `scheme://host[:port]` origin (no path, no trailing slash), or exit."""
    if not re.fullmatch(r"https?://[a-z0-9.-]+(:[0-9]{1,5})?", value):
        raise SystemExit(
            f"{flag} must be a bare origin such as https://basetool.example — lower-case "
            f"scheme and host, optional port, no path and no trailing slash (got {value!r})")
    return value


def client_specs(realm: str, public_origin: str, grafana_origin: str | None,
                 frontend_client: str | None = None,
                 external_clients: list[dict] | None = None) -> list[ClientSpec]:
    """Every Basetool client in its production shape, with the environment's origins filled in.

    The approved third-party clients follow the first-party ones and the Android client comes
    last. `frontend_client` is `public`, `confidential` or None; None leaves an existing frontend
    client's type unmanaged (ADR-0001).
    """
    frontend_confidential = frontend_client == "confidential"
    if frontend_client is None:
        frontend_kind = "authorization code + PKCE S256 (the web login; client type left as it is)"
    elif frontend_confidential:
        frontend_kind = "confidential, authorization code + client secret + PKCE S256 (the web login)"
    else:
        frontend_kind = "public, authorization code + PKCE S256 (the web login)"
    specs = [
        ClientSpec(
            client_id="basetool-frontend",
            kind=frontend_kind,
            fields={
                **_flags(public=not frontend_confidential, standard=True,
                         service_accounts=False, full_scope=True, frontchannel_logout=False),
                "baseUrl": f"{public_origin}/",
            },
            unmanaged_fields=(set() if frontend_client
                              else {"publicClient", "clientAuthenticatorType"}),
            value_env="KEYCLOAK_FRONTEND_CLIENT_SECRET" if frontend_confidential else None,
            env_vars_to_fill=(["KEYCLOAK_FRONTEND_CLIENT_SECRET"] if frontend_confidential
                              else []),
            env_doc_hint=" (the frontend's confidential login, ADR-0001)",
            create_only={"name": "IRIDIUM Basetool Frontend",
                         "description": "Frontend Application for IRIDIUM Basetool"},
            attributes={
                "backchannel.logout.revoke.offline.tokens": "false",
                "backchannel.logout.session.required": "false",
                "display.on.consent.screen": "false",
                "dpop.bound.access.tokens": "false",
                "exclude.session.state.from.auth.response": "false",
                "logout.confirmation.enabled": "false",
                "oauth2.device.authorization.grant.enabled": "false",
                "oauth2.jwt.authorization.grant.enabled": "false",
                "oidc.ciba.grant.enabled": "false",
                "pkce.code.challenge.method": "S256",
                "post.logout.redirect.uris": f"{public_origin}/*##{public_origin}",
                "standard.token.exchange.enabled": "false",
                "saml.assertion.signature": "false",
                "saml.authnstatement": "false",
                "saml.client.signature": "false",
                "saml.encrypt": "false",
                "saml.force.post.binding": "false",
                "saml.multivalued.roles": "false",
                "saml.onetimeuse.condition": "false",
                "saml.server.signature": "false",
                "saml_force_name_id_format": "false",
            },
            redirect_uris=[f"{public_origin}/*", f"{public_origin}/login/oauth2/code/keycloak"],
            web_origins=[public_origin],
            withheld_redirect_uris=["http://frontend:18081/*"],
            withheld_web_origins=["http://frontend:18081"],
            withheld_reason="compose-internal origin retired 2026-09-22",
            default_scopes=["email", "extractor-ingest", "profile", "roles", "web-origins"],
            optional_scopes=["address", "microprofile-jwt", "offline_access", "phone"],
            mappers=[
                _user_attribute_mapper("description", "String", introspection=False,
                                       lightweight=False),
                _user_attribute_mapper("discord_guild_nickname", "String", introspection=True,
                                       lightweight=True),
                {
                    "name": "discord_user_id",
                    "protocolMapper": "discord-federated-identity-mapper",
                    "config": {
                        "access.token.claim": "true",
                        "claim.name": "discord_user_id",
                        "id.token.claim": "true",
                        "idp.alias": "discord",
                        "lightweight.claim": "false",
                        "userinfo.token.claim": "true",
                    },
                },
                _user_attribute_mapper("rank", "int", introspection=False, lightweight=False),
                {
                    "name": "sub",
                    "protocolMapper": "oidc-sub-mapper",
                    "config": {
                        "access.token.claim": "true",
                        "introspection.token.claim": "true",
                        "lightweight.claim": "true",
                    },
                },
            ],
        ),
        ClientSpec(
            client_id="backend-service",
            kind="confidential, service account (the backend's Admin API identity)",
            fields=_flags(public=False, standard=False, service_accounts=True, full_scope=True,
                          frontchannel_logout=True),
            attributes={
                **_CONFIDENTIAL_ATTRIBUTES,
                "display.on.consent.screen": "false",
                "frontchannel.logout.session.required": "true",
                "login_theme": "krt-theme",
                "logout.confirmation.enabled": "false",
                "post.logout.redirect.uris": "+",
            },
            redirect_uris=[],
            web_origins=[],
            default_scopes=[*_STANDARD_DEFAULT, "service_account"],
            optional_scopes=list(_STANDARD_OPTIONAL),
            service_account_roles={
                "<realm>": [f"default-roles-{realm}"],
                "realm-management": ["manage-users", "view-realm", "view-users"],
            },
            env_vars_to_fill=["KEYCLOAK_ADMIN_CLIENT_SECRET"],
            env_doc_hint=" (the backend's user sync authenticates with it)",
        ),
        ClientSpec(
            client_id="basetool-ingest-gateway",
            kind="confidential, service account (the gateway's own identity, ADR-0129)",
            fields=_flags(public=False, standard=False, service_accounts=True, full_scope=True,
                          frontchannel_logout=True),
            create_only={"name": "Basetool Ingest Gateway"},
            attributes={**_CONFIDENTIAL_ATTRIBUTES,
                        "oauth2.jwt.authorization.grant.enabled": "false"},
            redirect_uris=[],
            web_origins=[],
            default_scopes=[*_STANDARD_DEFAULT, "extractor-ingest", "extractor-ingest-only",
                            "service_account"],
            optional_scopes=list(_STANDARD_OPTIONAL),
            service_account_roles={"<realm>": [f"default-roles-{realm}"]},
            env_vars_to_fill=["IRI_INGEST_SERVICE_ACCOUNT_CLIENT_SECRET"],
            env_doc_hint=(" together with the other four values of docs/INGEST_KEYCLOAK_SETUP.md "
                        "step 9b (IRI_INGEST_PUBLIC_BASE_URL, "
                        "IRI_INGEST_SERVICE_ACCOUNT_TOKEN_URI, "
                        "IRI_INGEST_SERVICE_ACCOUNT_CLIENT_ID=basetool-ingest-gateway, and "
                        "IRI_INGEST_GATEWAY_CLIENT_IDS=basetool-ingest-gateway for the backend) - "
                        "the gateway refuses to send until all five are set"),
        ),
        ClientSpec(
            client_id="basetool-sc-extractor",
            kind="public, device grant (the desktop SC Extractor)",
            fields=_flags(public=True, standard=False, service_accounts=False, full_scope=False,
                          frontchannel_logout=True),
            create_only={"name": "Basetool SC Extractor"},
            attributes={
                "access.token.header.type.rfc9068": "false",
                "acr.loa.map": "{}",
                "backchannel.logout.revoke.offline.tokens": "false",
                "backchannel.logout.session.required": "true",
                "client.introspection.response.allow.jwt.claim.enabled": "false",
                "client.use.lightweight.access.token.enabled": "false",
                "display.on.consent.screen": "false",
                "dpop.bound.access.tokens": "false",
                "frontchannel.logout.session.required": "true",
                "id.token.as.detached.signature": "false",
                "logout.confirmation.enabled": "false",
                "oauth2.device.authorization.grant.enabled": "true",
                "oauth2.jwt.authorization.grant.enabled": "false",
                "oidc.ciba.grant.enabled": "false",
                "request.object.required": "not required",
                "require.pushed.authorization.requests": "false",
                "standard.token.exchange.enabled": "false",
                "token.response.type.bearer.lower-case": "false",
                "use.refresh.tokens": "true",
            },
            redirect_uris=[],
            web_origins=[],
            default_scopes=[*_STANDARD_DEFAULT, "extractor-ingest", "extractor-ingest-only"],
            optional_scopes=[*_STANDARD_OPTIONAL, *EXTRACTOR_EXCHANGE_SCOPES],
            withheld_redirect_uris=["http://127.0.0.1/*", "http://localhost/*"],
            withheld_reason="unused authorization-code flow retired 2026-09-22",
        ),
    ]
    if grafana_origin:
        specs.append(ClientSpec(
            client_id="grafana",
            kind="confidential, authorization code (Grafana's OAuth login)",
            fields=_flags(public=False, standard=True, service_accounts=False, full_scope=True,
                          frontchannel_logout=True),
            attributes={
                **_CONFIDENTIAL_ATTRIBUTES,
                "display.on.consent.screen": "false",
                "frontchannel.logout.session.required": "true",
                "logout.confirmation.enabled": "false",
                "oauth2.jwt.authorization.grant.enabled": "false",
            },
            redirect_uris=[f"{grafana_origin}/login/generic_oauth"],
            web_origins=["+"],
            default_scopes=list(_STANDARD_DEFAULT),
            optional_scopes=list(_STANDARD_OPTIONAL),
            mappers=[{
                "name": "realm roles",
                "protocolMapper": "oidc-usermodel-realm-role-mapper",
                "config": {
                    "access.token.claim": "true",
                    "claim.name": "realm_access.roles",
                    "id.token.claim": "true",
                    "introspection.token.claim": "true",
                    "jsonType.label": "String",
                    "lightweight.claim": "false",
                    "multivalued": "true",
                    "userinfo.token.claim": "true",
                },
            }],
            env_vars_to_fill=["GRAFANA_OAUTH_CLIENT_SECRET"],
            env_doc_hint=" (Grafana's generic_oauth login reads it)",
        ))
    specs.extend(external_client_spec(entry) for entry in (external_clients or []))
    specs.append(android_spec(public_origin))
    return specs


def load_external_clients(path: Path) -> list[dict]:
    """Read and check the approved third-party clients, one `{clientId, name, description}` each."""
    try:
        entries = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise SystemExit(f"cannot read the third-party client list {path}: {error}") from error
    if not isinstance(entries, list):
        raise SystemExit(f"{path}: expected a JSON list of clients")
    reserved = BUILTIN_CLIENTS | {"basetool-frontend", "backend-service", "basetool-ingest-gateway",
                                  "basetool-sc-extractor", "grafana", mobile.CLIENT_ID}
    seen: set[str] = set()
    for entry in entries:
        client_id = entry.get("clientId") if isinstance(entry, dict) else None
        if not isinstance(client_id, str) or not re.fullmatch(r"[a-z0-9][a-z0-9-]{1,62}",
                                                               client_id):
            raise SystemExit(f"{path}: every client needs a clientId of lower-case letters, "
                             f"digits and hyphens (got {client_id!r})")
        if client_id in reserved or client_id in seen:
            raise SystemExit(f"{path}: clientId '{client_id}' is reserved or listed twice")
        if not isinstance(entry.get("name"), str) or not entry["name"].strip():
            raise SystemExit(f"{path}: client '{client_id}' needs a name for the consent page")
        seen.add(client_id)
    return entries


def external_client_spec(entry: dict) -> ClientSpec:
    """The template every approved third-party client follows (REQ-XCH-005, ADR-0217)."""
    return ClientSpec(
        client_id=entry["clientId"],
        kind="public, device grant, consent, DPoP-bound tokens (an approved third-party client)",
        fields={
            **_flags(public=True, standard=False, service_accounts=False, full_scope=False,
                     frontchannel_logout=True, consent=True),
            "name": entry["name"],
            "description": entry.get("description", ""),
        },
        attributes={
            "access.token.header.type.rfc9068": "false",
            "backchannel.logout.revoke.offline.tokens": "false",
            "backchannel.logout.session.required": "true",
            "client.offline.session.idle.timeout": str(EXCHANGE_OFFLINE_SESSION_IDLE_SECONDS),
            "client.offline.session.max.lifespan": str(EXCHANGE_OFFLINE_SESSION_MAX_SECONDS),
            "client.use.lightweight.access.token.enabled": "false",
            "display.on.consent.screen": "false",
            "dpop.bound.access.tokens": "true",
            "frontchannel.logout.session.required": "true",
            "login_theme": "krt-theme",
            "oauth2.device.authorization.grant.enabled": "true",
            "oauth2.jwt.authorization.grant.enabled": "false",
            "oidc.ciba.grant.enabled": "false",
            "standard.token.exchange.enabled": "false",
            "use.refresh.tokens": "true",
        },
        redirect_uris=[],
        web_origins=[],
        default_scopes=["basic"],
        optional_scopes=[*EXCHANGE_SCOPE_NAMES, "offline_access"],
        withheld_scopes=["acr", "address", "email", "extractor-ingest", "extractor-ingest-only",
                         "microprofile-jwt", "organization", "phone", "profile", "roles",
                         "web-origins"],
        withheld_reason="never offered to a third-party client, REQ-XCH-005",
    )


def android_spec(public_origin: str) -> ClientSpec:
    """`basetool-android`, built from the mobile provisioner's own definition.

    Session bounds are capped at this script's realm SSO settings.
    """
    idle = min(mobile.SESSION_IDLE_SECONDS, int(REALM_SETTINGS["ssoSessionIdleTimeout"]))
    maximum = min(mobile.SESSION_MAX_SECONDS, int(REALM_SETTINGS["ssoSessionMaxLifespan"]))
    rep = mobile.client_representation([f"{public_origin}/app/callback"], idle, maximum)
    scalar = {key: value for key, value in rep.items()
              if key not in {"clientId", "name", "description", "redirectUris", "webOrigins",
                             "attributes"}}
    scalar["bearerOnly"] = False
    return ClientSpec(
        client_id=mobile.CLIENT_ID,
        kind="public, authorization code + PKCE S256, DPoP-bound refresh token (ADR-0131)",
        fields=scalar,
        create_only={"name": rep["name"], "description": rep["description"]},
        attributes={**rep["attributes"],
                    "backchannel.logout.revoke.offline.tokens": "false",
                    "backchannel.logout.session.required": "true"},
        redirect_uris=rep["redirectUris"],
        web_origins=rep["webOrigins"],
        default_scopes=list(_STANDARD_DEFAULT),
        optional_scopes=[s for s in _STANDARD_OPTIONAL if s != "offline_access"],
        withheld_scopes=["offline_access", "extractor-ingest", "extractor-ingest-only"],
        withheld_reason="ADR-0131 / ingest scopes retired 2026-09-22",
        mappers=[{
            "name": mobile.AUDIENCE_MAPPER,
            "protocolMapper": "oidc-audience-mapper",
            "config": {"included.client.audience": mobile.BACKEND_AUDIENCE,
                       "access.token.claim": "true", "id.token.claim": "false",
                       "introspection.token.claim": "true"},
        }],
        client_roles=[{"name": mobile.MARKER_ROLE,
                       "description": "Marker role: scopes the refresh-only DPoP client policy."}],
        realm_role_scope=list(mobile.MEMBER_REALM_ROLES),
        frozen_by_dpop_policy=True,
    )


class RealmKcadm(mobile.Kcadm):
    """The mobile provisioner's kcadm wrapper plus realm updates and link PUTs."""

    def update_realm(self, payload: dict, what: str) -> None:
        """Partial update of the realm representation, which lives above the `-r` paths."""
        self._run(["update", f"realms/{self.realm}", "-f", "-"],
                  stdin=json.dumps(payload, indent=2, sort_keys=True))
        print(f"  update realms/{self.realm} — {what}")

    def link(self, path: str, what: str) -> None:
        """PUT a link resource (a client-scope assignment).

        These paths answer no GET, so `-n` skips kcadm's merge; the body is an empty object.
        """
        self._run(["update", path, "-r", self.realm, "-n", "-f", "-"], stdin="{}")
        print(f"  update {path} — {what}")


@dataclass
class Change:
    """One planned write: the line the operator reads, and the closure that performs it."""

    text: str
    action: Callable[[], None]
    frozen: bool = False
    service_account: bool = False


def _normalise(value):
    """Normalise a value to a string: booleans lower-case, ``None`` empty."""
    if isinstance(value, bool):
        return str(value).lower()
    return "" if value is None else str(value)


def _equal(desired, live) -> bool:
    """Compare one managed value; an empty desired string also matches an absent value."""
    if desired == "" and live in (None, ""):
        return True
    return _normalise(desired) == _normalise(live)


def _matches(desired, live) -> bool:
    """True when `live` carries every key of `desired` with an equal value, recursively; extra keys are ignored."""
    if isinstance(desired, dict):
        return isinstance(live, dict) and all(_matches(v, live.get(k)) for k, v in desired.items())
    if isinstance(desired, list):
        return (isinstance(live, list) and len(live) == len(desired)
                and all(_matches(d, lv) for d, lv in zip(desired, live, strict=True)))
    return _equal(desired, live)


def _redact(payload: dict) -> dict:
    """A client representation without `secret` and `registrationAccessToken`; Keycloak keeps the stored secret."""
    return {key: value for key, value in payload.items()
            if key not in {"secret", "registrationAccessToken"}}


class Planner:
    """Reads the live realm, compares it with the production shape, and lists what to write.

    Nothing is written while planning; each closure resolves ids when it runs.
    """

    def __init__(self, kc: RealmKcadm, realm: str, specs: list[ClientSpec]):
        self.kc = kc
        self.realm = realm
        self.specs = specs
        self.sections: list[tuple[str, list[Change]]] = []
        self.reports: list[str] = []
        self.problems: list[str] = []
        self.manual: list[str] = []
        self.followup_notes: list[str] = []
        self._scope_cache: dict[str, dict] | None = None


    def find_client(self, client_id: str) -> dict | None:
        found = self.kc.get("clients", {"clientId": client_id}) or []
        exact = [c for c in found if c.get("clientId") == client_id]
        return exact[0] if exact else None

    def client_uuid(self, client_id: str) -> str:
        client = self.find_client(client_id)
        if client is None:
            raise KcadmError(f"client '{client_id}' does not exist (it should have been created "
                             f"earlier in this run)")
        return client["id"]

    def scopes_by_name(self, refresh: bool = False) -> dict[str, dict]:
        """Every client scope of the realm by EXACT name; `-q name=` is not a filter there."""
        if self._scope_cache is None or refresh:
            self._scope_cache = {s.get("name"): s for s in (self.kc.get("client-scopes") or [])}
        return self._scope_cache

    def scope_id(self, name: str) -> str:
        scope = self.scopes_by_name().get(name) or self.scopes_by_name(refresh=True).get(name)
        if scope is None:
            raise KcadmError(f"client scope '{name}' does not exist in realm '{self.realm}'")
        return scope["id"]


    def section(self, title: str) -> list[Change]:
        changes: list[Change] = []
        self.sections.append((title, changes))
        return changes

    def plan(self) -> None:
        """Build the whole plan in write order."""
        self.plan_realm_settings()
        self.plan_realm_roles()
        self.plan_scopes()
        for spec in (s for s in self.specs if not s.frozen_by_dpop_policy):
            self.plan_client(spec)
        frozen_index = len(self.sections)
        frozen_changes: list[Change] = []
        for spec in (s for s in self.specs if s.frozen_by_dpop_policy):
            frozen_changes += self.plan_client(spec, frozen=True)
        self.plan_client_policies(frozen_changes, frozen_index)
        self.plan_reports()

    def plan_realm_settings(self) -> None:
        changes = self.section("realm token and session settings")
        live = self.kc.get_realm()
        diff = {key: value for key, value in REALM_SETTINGS.items()
                if not _equal(value, live.get(key))}
        for key, value in diff.items():
            changes.append(Change(f"~ {key}: {_normalise(live.get(key)) or '<absent>'} -> "
                                  f"{_normalise(value)}", lambda: None))
        if diff:
            changes.append(Change("  (one partial update of the realm)",
                                  lambda d=dict(diff): self.kc.update_realm(d, "token settings")))

    def plan_realm_roles(self) -> None:
        """The application's realm roles, created when missing — the Android scope names them."""
        changes = self.section("application realm roles")
        present = {role.get("name") for role in (self.kc.get("roles") or [])}
        for name in mobile.MEMBER_REALM_ROLES:
            if name not in present:
                changes.append(Change(
                    f"+ create realm role '{name}'",
                    lambda n=name: self.kc.write("create", "roles", {"name": n},
                                                 f"realm role '{n}' created")))

    def plan_scopes(self) -> None:
        changes = self.section("client scopes and their audience mappers")
        live_scopes = self.scopes_by_name(refresh=True)
        for spec in SCOPES:
            live = live_scopes.get(spec.name)
            if live is None:
                changes.append(Change(
                    f"+ create client scope '{spec.name}' "
                    f"(include.in.token.scope={spec.attributes['include.in.token.scope']})",
                    lambda s=spec: self._create_scope(s)))
                for mapper in spec.mappers:
                    changes.append(Change(
                        f"+ {spec.name}: mapper '{mapper['name']}' -> "
                        f"{self._mapper_summary(mapper)}",
                        lambda s=spec, m=mapper: self._create_mapper(
                            f"client-scopes/{self.scope_id(s.name)}", m)))
                continue
            attributes = live.get("attributes") or {}
            diff = {k: v for k, v in spec.attributes.items() if not _equal(v, attributes.get(k))}
            if diff or live.get("protocol") != "openid-connect":
                for key, value in diff.items():
                    changes.append(Change(
                        f"~ {spec.name}: attribute {key}: "
                        f"{attributes.get(key, '<absent>')} -> {value!r}", lambda: None))
                changes.append(Change(
                    f"  (update of client scope '{spec.name}')",
                    lambda s=spec, lv=live: self._update_scope(s, lv)))
            base = f"client-scopes/{live['id']}"
            live_mappers = self.kc.get(f"{base}/protocol-mappers/models") or []
            changes += self._mapper_changes(spec.name, base, spec.mappers, live_mappers)

    def _create_scope(self, spec: ScopeSpec) -> None:
        self.kc.write("create", "client-scopes",
                      {"name": spec.name, "protocol": "openid-connect",
                       "attributes": dict(spec.attributes)},
                      f"client scope '{spec.name}' created")
        self.scopes_by_name(refresh=True)

    def _update_scope(self, spec: ScopeSpec, live: dict) -> None:
        payload = dict(live)
        payload["protocol"] = "openid-connect"
        payload["attributes"] = {**(live.get("attributes") or {}), **spec.attributes}
        payload.pop("protocolMappers", None)
        self.kc.write("update", f"client-scopes/{live['id']}", payload,
                      f"client scope '{spec.name}' updated")

    @staticmethod
    def _mapper_summary(mapper: dict) -> str:
        config = mapper["config"]
        target = (config.get("included.custom.audience") or config.get("included.client.audience")
                  or config.get("claim.name") or "")
        return f"{mapper['protocolMapper']} {target}".strip()

    def _create_mapper(self, base: str, mapper: dict) -> None:
        self.kc.write("create", f"{base}/protocol-mappers/models",
                      {"name": mapper["name"], "protocol": "openid-connect",
                       "protocolMapper": mapper["protocolMapper"],
                       "config": dict(mapper["config"])},
                      f"mapper '{mapper['name']}' created")

    def _mapper_changes(self, owner: str, base: str, desired: list[dict],
                        live_mappers: list[dict], frozen: bool = False) -> list[Change]:
        """Create missing mappers, correct drifted ones, report those production does not have."""
        changes: list[Change] = []
        by_name = {m.get("name"): m for m in live_mappers}
        for mapper in desired:
            live = by_name.get(mapper["name"])
            if live is None:
                changes.append(Change(
                    f"+ {owner}: mapper '{mapper['name']}' -> {self._mapper_summary(mapper)}",
                    lambda m=mapper: self._create_mapper(base, m), frozen))
                continue
            config = live.get("config") or {}
            drift = {k: v for k, v in mapper["config"].items() if not _equal(v, config.get(k))}
            if drift or live.get("protocolMapper") != mapper["protocolMapper"]:
                detail = ", ".join(f"{k}: {config.get(k, '<absent>')} -> {v}"
                                   for k, v in drift.items())
                if live.get("protocolMapper") != mapper["protocolMapper"]:
                    detail = (f"type {live.get('protocolMapper')} -> {mapper['protocolMapper']}"
                              + (f", {detail}" if detail else ""))
                changes.append(Change(
                    f"~ {owner}: mapper '{mapper['name']}': {detail}",
                    lambda m=mapper, lv=live: self.kc.write(
                        "update", f"{base}/protocol-mappers/models/{lv['id']}",
                        {**lv, "protocolMapper": m["protocolMapper"],
                         "config": {**(lv.get("config") or {}), **m["config"]}},
                        f"mapper '{m['name']}' corrected"), frozen))
        wanted = {m["name"] for m in desired}
        for name in sorted(n for n in by_name if n not in wanted):
            self.reports.append(f"{owner}: mapper '{name}' is not in the production shape")
        return changes

    def plan_client(self, spec: ClientSpec, frozen: bool = False) -> list[Change]:
        """Plan one client. Returns its changes, so the caller can see whether it needs a write."""
        changes = self.section(f"client '{spec.client_id}' — {spec.kind}")
        live = self.find_client(spec.client_id)
        if live is None:
            changes += self._plan_new_client(spec, frozen)
            return changes

        uuid = live["id"]
        field_diff = {k: v for k, v in spec.fields.items()
                      if k not in spec.unmanaged_fields and not _equal(v, live.get(k))}
        if spec.value_env and "publicClient" in field_diff and _normalise(
                live.get("publicClient")) == "true":
            if not os.environ.get(spec.value_env):
                self.problems.append(
                    f"{spec.client_id}: switching it to confidential needs ${spec.value_env} in "
                    f"this process's environment -- the value the frontend has in the host .env "
                    f"-- or Keycloak and the frontend would hold different secrets. Nothing about "
                    f"this client is written.")
            else:
                self.followup_notes.append(
                    f"'{spec.client_id}' becomes confidential with the secret from "
                    f"${spec.value_env} (not printed). The frontend must already send that value "
                    f"(docs/OAUTH2_CONFIDENTIAL_CLIENT_MIGRATION.md).")
        attributes = live.get("attributes") or {}
        attribute_diff = {k: v for k, v in spec.attributes.items()
                          if not _equal(v, attributes.get(k))}
        missing_redirects = [u for u in spec.redirect_uris if u not in (live.get("redirectUris")
                                                                        or [])]
        missing_origins = [o for o in spec.web_origins if o not in (live.get("webOrigins") or [])]
        retired_redirects = [u for u in spec.withheld_redirect_uris
                             if u in (live.get("redirectUris") or [])]
        retired_origins = [o for o in spec.withheld_web_origins
                           if o in (live.get("webOrigins") or [])]
        for key, value in field_diff.items():
            changes.append(Change(f"~ {key}: {_normalise(live.get(key)) or '<absent>'} -> "
                                  f"{_normalise(value)}", lambda: None, frozen))
        if spec.value_env and "publicClient" in field_diff:
            changes.append(Change(f"~ secret: set from ${spec.value_env} (the value is not "
                                  f"printed)", lambda: None, frozen))
        for key, value in attribute_diff.items():
            changes.append(Change(f"~ attribute {key}: {attributes.get(key, '<absent>')} -> "
                                  f"{value!r}", lambda: None, frozen))
        for uri in missing_redirects:
            changes.append(Change(f"+ redirect URI {uri}", lambda: None, frozen))
        for origin in missing_origins:
            changes.append(Change(f"+ web origin {origin}", lambda: None, frozen))
        for uri in retired_redirects:
            changes.append(Change(f"- redirect URI {uri} withheld ({spec.withheld_reason})",
                                  lambda: None, frozen))
        for origin in retired_origins:
            changes.append(Change(f"- web origin {origin} withheld ({spec.withheld_reason})",
                                  lambda: None, frozen))
        if (field_diff or attribute_diff or missing_redirects or missing_origins
                or retired_redirects or retired_origins):
            changes.append(Change(
                "  (one update of the client representation)",
                lambda lv=live: self._update_client(spec, lv), frozen))
        for uri in sorted(set(live.get("redirectUris") or []) - set(spec.redirect_uris)
                          - set(spec.withheld_redirect_uris)):
            self.reports.append(f"{spec.client_id}: redirect URI '{uri}' is not in the "
                                f"production shape")
        for origin in sorted(set(live.get("webOrigins") or []) - set(spec.web_origins)
                             - set(spec.withheld_web_origins)):
            self.reports.append(f"{spec.client_id}: web origin '{origin}' is not in the "
                                f"production shape")

        changes += self._plan_scope_assignments(spec, uuid, frozen)
        changes += self._mapper_changes(
            spec.client_id, f"clients/{uuid}", spec.mappers,
            self.kc.get(f"clients/{uuid}/protocol-mappers/models") or [], frozen=frozen)
        changes += self._plan_client_roles(spec, uuid, frozen)
        changes += self._plan_role_scope(spec, uuid, frozen)
        changes += self._plan_service_account_roles(spec, uuid)
        return changes

    def _plan_new_client(self, spec: ClientSpec, frozen: bool) -> list[Change]:
        changes = [Change(f"+ create client '{spec.client_id}'",
                          lambda: self._create_client(spec), frozen)]
        for uri in spec.redirect_uris:
            changes.append(Change(f"  redirect URI {uri}", lambda: None, frozen))
        for origin in spec.web_origins:
            changes.append(Change(f"  web origin {origin}", lambda: None, frozen))
        changes.append(Change(
            f"= default scopes [{', '.join(spec.default_scopes)}], optional scopes "
            f"[{', '.join(spec.optional_scopes)}] — exactly; the realm defaults Keycloak attaches "
            f"on creation are this run's own side effect and are replaced",
            lambda: self._converge_new_client_scopes(spec), frozen))
        for mapper in spec.mappers:
            changes.append(Change(
                f"+ mapper '{mapper['name']}' -> {self._mapper_summary(mapper)}",
                lambda m=mapper: self._create_mapper(
                    f"clients/{self.client_uuid(spec.client_id)}", m), frozen))
        for role in spec.client_roles:
            changes.append(Change(
                f"+ client role '{role['name']}'",
                lambda r=role: self.kc.write(
                    "create", f"clients/{self.client_uuid(spec.client_id)}/roles", dict(r),
                    f"client role '{r['name']}' created"), frozen))
        if spec.realm_role_scope is not None:
            changes.append(Change(
                f"= realm-role scope exactly [{', '.join(spec.realm_role_scope)}] "
                f"(fullScopeAllowed off, REQ-SEC-035)",
                lambda: self._converge_role_scope(spec, self.client_uuid(spec.client_id)),
                frozen))
        if spec.service_account_roles:
            changes.append(Change(
                f"+ service-account roles {self._role_summary(spec.service_account_roles)}",
                lambda: self._grant_service_account_roles(spec, self.client_uuid(spec.client_id),
                                                          spec.service_account_roles),
                service_account=True))
        if spec.env_vars_to_fill:
            self.followup_notes.append(self._confidential_client_note(spec))
        return changes

    @staticmethod
    def _role_summary(roles: dict[str, list[str]]) -> str:
        return ", ".join(f"{container}:{name}" for container, names in sorted(roles.items())
                         for name in names)

    def _confidential_client_note(self, spec: ClientSpec) -> str:
        return (f"'{spec.client_id}' is created confidential, so Keycloak generates its client "
                f"secret. It is NOT printed here: read it in the Admin Console -> Clients -> "
                f"{spec.client_id} -> Credentials, and put it into "
                f"{', '.join(spec.env_vars_to_fill)} in the host .env{spec.env_doc_hint}, then "
                f"re-render env.d and restart the consumer.")

    def _create_client(self, spec: ClientSpec) -> None:
        payload = {"clientId": spec.client_id, **spec.create_only, **spec.fields,
                   "attributes": dict(spec.attributes),
                   "redirectUris": list(spec.redirect_uris),
                   "webOrigins": list(spec.web_origins),
                   "defaultClientScopes": list(spec.default_scopes),
                   "optionalClientScopes": list(spec.optional_scopes)}
        if spec.value_env and os.environ.get(spec.value_env):
            payload["secret"] = os.environ[spec.value_env]
        self.kc.write("create", "clients", payload, f"client '{spec.client_id}' created")
        if spec.env_vars_to_fill and not (spec.value_env and os.environ.get(spec.value_env)):
            print(f"  NOTE: {self._confidential_client_note(spec)}")

    def _update_client(self, spec: ClientSpec, planned_live: dict) -> None:
        live = self.find_client(spec.client_id) or planned_live
        switching_to_confidential = (spec.value_env is not None
                                     and _normalise(live.get("publicClient")) == "true"
                                     and not spec.fields.get("publicClient", True))
        payload = dict(live)
        payload.update({k: v for k, v in spec.fields.items() if k not in spec.unmanaged_fields})
        payload["attributes"] = {**(live.get("attributes") or {}), **spec.attributes}
        payload["redirectUris"] = [
            u for u in list(live.get("redirectUris") or []) + [
                u for u in spec.redirect_uris if u not in (live.get("redirectUris") or [])]
            if u not in spec.withheld_redirect_uris]
        payload["webOrigins"] = [
            o for o in list(live.get("webOrigins") or []) + [
                o for o in spec.web_origins if o not in (live.get("webOrigins") or [])]
            if o not in spec.withheld_web_origins]
        payload = _redact(payload)
        if switching_to_confidential:
            payload["secret"] = os.environ[spec.value_env]
        self.kc.write("update", f"clients/{live['id']}", payload,
                      f"client '{spec.client_id}' updated")

    def _assigned_scopes(self, uuid: str) -> tuple[dict[str, str], dict[str, str]]:
        """name -> id of the client's default and optional scopes."""
        default = {s.get("name"): s.get("id")
                   for s in (self.kc.get(f"clients/{uuid}/default-client-scopes") or [])}
        optional = {s.get("name"): s.get("id")
                    for s in (self.kc.get(f"clients/{uuid}/optional-client-scopes") or [])}
        return default, optional

    def _link_scope(self, uuid: str, kind: str, name: str) -> None:
        self.kc.link(f"clients/{uuid}/{kind}-client-scopes/{self.scope_id(name)}",
                     f"'{name}' assigned as {kind}")

    def _unlink_scope(self, uuid: str, kind: str, name: str, scope_id: str, why: str) -> None:
        self.kc.delete(f"clients/{uuid}/{kind}-client-scopes/{scope_id}", f"'{name}' {why}")

    def _converge_new_client_scopes(self, spec: ClientSpec) -> None:
        uuid = self.client_uuid(spec.client_id)
        default, optional = self._assigned_scopes(uuid)
        for name, scope_id in default.items():
            if name not in spec.default_scopes:
                self._unlink_scope(uuid, "default", name, scope_id, "removed (creation default)")
        for name, scope_id in optional.items():
            if name not in spec.optional_scopes:
                self._unlink_scope(uuid, "optional", name, scope_id, "removed (creation default)")
        for name in spec.default_scopes:
            if name not in default:
                self._link_scope(uuid, "default", name)
        for name in spec.optional_scopes:
            if name not in optional:
                self._link_scope(uuid, "optional", name)

    def _plan_scope_assignments(self, spec: ClientSpec, uuid: str, frozen: bool) -> list[Change]:
        changes: list[Change] = []
        default, optional = self._assigned_scopes(uuid)
        existing = self.scopes_by_name()
        planned = {s.name for s in SCOPES}
        for kind, wanted, have, other in (("default", spec.default_scopes, default, optional),
                                          ("optional", spec.optional_scopes, optional, default)):
            for name in wanted:
                if name in have:
                    continue
                if name not in existing and name not in planned:
                    self.problems.append(
                        f"{spec.client_id}: scope '{name}' does not exist in this realm, so it "
                        f"cannot be assigned as {kind}. It is a Keycloak built-in; a realm "
                        f"without it predates the Keycloak version production runs.")
                    continue
                if name in other:
                    changes.append(Change(
                        f"~ scope '{name}': {'optional' if kind == 'default' else 'default'} "
                        f"-> {kind}",
                        lambda n=name, k=kind, sid=other[name]: (
                            self._unlink_scope(uuid, "optional" if k == "default" else "default",
                                               n, sid, "moved"),
                            self._link_scope(uuid, k, n)), frozen))
                else:
                    changes.append(Change(f"+ {kind} scope '{name}'",
                                          lambda n=name, k=kind: self._link_scope(uuid, k, n),
                                          frozen))
        for name in spec.withheld_scopes:
            for kind, have in (("default", default), ("optional", optional)):
                if name in have:
                    changes.append(Change(
                        f"- {kind} scope '{name}' withheld ({spec.withheld_reason})",
                        lambda n=name, k=kind, sid=have[name]: self._unlink_scope(
                            uuid, k, n, sid, "withheld"), frozen))
        wanted_all = set(spec.default_scopes) | set(spec.optional_scopes) | set(
            spec.withheld_scopes)
        for kind, have in (("default", default), ("optional", optional)):
            for name in sorted(n for n in have if n not in wanted_all):
                self.reports.append(f"{spec.client_id}: {kind} scope '{name}' is not in the "
                                    f"production shape")
        return changes

    def _plan_client_roles(self, spec: ClientSpec, uuid: str, frozen: bool) -> list[Change]:
        if not spec.client_roles:
            return []
        present = {r.get("name") for r in (self.kc.get(f"clients/{uuid}/roles") or [])}
        return [Change(f"+ client role '{role['name']}'",
                       lambda r=role: self.kc.write("create", f"clients/{uuid}/roles", dict(r),
                                                    f"client role '{r['name']}' created"), frozen)
                for role in spec.client_roles if role["name"] not in present]

    def _plan_role_scope(self, spec: ClientSpec, uuid: str, frozen: bool) -> list[Change]:
        if spec.realm_role_scope is None:
            return []
        assigned = {r.get("name") for r in
                    (self.kc.get(f"clients/{uuid}/scope-mappings/realm") or [])}
        missing = [n for n in spec.realm_role_scope if n not in assigned]
        surplus = sorted(assigned - set(spec.realm_role_scope))
        if not missing and not surplus:
            return []
        text = "= realm-role scope:"
        if missing:
            text += f" grant {', '.join(missing)}"
        if surplus:
            text += (f"{';' if missing else ''} take back {', '.join(surplus)} (REQ-SEC-035: a "
                     f"role added by hand does not survive a provisioning run)")
        return [Change(text, lambda: self._converge_role_scope(spec, uuid), frozen)]

    def _converge_role_scope(self, spec: ClientSpec, uuid: str) -> None:
        assigned = self.kc.get(f"clients/{uuid}/scope-mappings/realm") or []
        names = {r.get("name") for r in assigned}
        realm_roles = {r.get("name"): r for r in (self.kc.get("roles") or [])}
        missing = [n for n in spec.realm_role_scope if n not in names]
        unknown = [n for n in missing if n not in realm_roles]
        if unknown:
            raise KcadmError(f"the realm has no role(s) {unknown}; with fullScopeAllowed off the "
                             f"token would carry nothing for them")
        if missing:
            self.kc.write("create", f"clients/{uuid}/scope-mappings/realm",
                          [{"id": realm_roles[n]["id"], "name": n} for n in missing],
                          f"realm roles granted to the client scope: {', '.join(missing)}")
        surplus = [r for r in assigned if r.get("name") not in spec.realm_role_scope]
        if surplus:
            self.kc.delete(f"clients/{uuid}/scope-mappings/realm",
                           "realm roles taken back: " + ", ".join(
                               sorted(r.get("name") or "?" for r in surplus)), surplus)

    def _service_account_state(self, uuid: str) -> tuple[str, dict[str, set[str]]]:
        """The service-account user's id and its realm role names, keyed `<realm>`."""
        user = self.kc.get(f"clients/{uuid}/service-account-user") or {}
        user_id = user["id"]
        held: dict[str, set[str]] = {
            "<realm>": {r.get("name") for r in
                        (self.kc.get(f"users/{user_id}/role-mappings/realm") or [])}}
        return user_id, held

    def _held_client_roles(self, user_id: str, container: str) -> set[str]:
        container_uuid = self.client_uuid(container)
        return {r.get("name") for r in (self.kc.get(
            f"users/{user_id}/role-mappings/clients/{container_uuid}") or [])}

    def _manual_grant(self, spec: ClientSpec, missing: dict[str, list[str]], reason: str) -> None:
        lines = [f"{spec.client_id}: service-account roles could not be {reason} — this needs an "
                 f"identity holding manage-users (the provisioning client deliberately does not). "
                 f"Grant by hand: Admin Console -> Clients -> {spec.client_id} -> Service account "
                 f"roles -> Assign role:"]
        for container, names in sorted(missing.items()):
            for name in names:
                source = ("realm role" if container == "<realm>"
                          else f"client '{container}' role")
                lines.append(f"      {source} '{name}'")
        self.manual.append("\n".join(lines))

    def _plan_service_account_roles(self, spec: ClientSpec, uuid: str) -> list[Change]:
        if not spec.service_account_roles:
            return []
        try:
            user_id, held = self._service_account_state(uuid)
            for container in spec.service_account_roles:
                if container != "<realm>":
                    held[container] = self._held_client_roles(user_id, container)
        except (KcadmError, KeyError):
            self._manual_grant(spec, spec.service_account_roles, "read")
            return []
        missing = {c: [n for n in names if n not in held.get(c, set())]
                   for c, names in spec.service_account_roles.items()}
        missing = {c: names for c, names in missing.items() if names}
        if not missing:
            return []
        return [Change(f"+ service-account roles {self._role_summary(missing)}",
                       lambda: self._grant_service_account_roles(spec, uuid, missing),
                       service_account=True)]

    def _grant_service_account_roles(self, spec: ClientSpec, uuid: str,
                                     roles: dict[str, list[str]]) -> None:
        try:
            user_id, held = self._service_account_state(uuid)
            for container, names in sorted(roles.items()):
                if container == "<realm>":
                    available = {r.get("name"): r for r in (self.kc.get("roles") or [])}
                    path = f"users/{user_id}/role-mappings/realm"
                    have = held["<realm>"]
                else:
                    container_uuid = self.client_uuid(container)
                    available = {r.get("name"): r for r in
                                 (self.kc.get(f"clients/{container_uuid}/roles") or [])}
                    path = f"users/{user_id}/role-mappings/clients/{container_uuid}"
                    have = self._held_client_roles(user_id, container)
                grant = [available[n] for n in names if n not in have and n in available]
                absent = [n for n in names if n not in available]
                if absent:
                    raise KcadmError(f"container '{container}' has no role(s) {absent}")
                if grant:
                    self.kc.write("create", path,
                                  [{"id": r["id"], "name": r["name"]} for r in grant],
                                  f"service-account roles granted: "
                                  f"{', '.join(r['name'] for r in grant)}")
        except (KcadmError, KeyError) as error:
            print(f"  could not grant: {str(error).splitlines()[0]}")
            self._manual_grant(spec, roles, "granted")

    def plan_client_policies(self, frozen_changes: list[Change], frozen_index: int) -> None:
        """Profile and policy, with the detach the frozen client's writes need placed first.

        :param frozen_changes: everything planned for the client the policy freezes
        :param frozen_index: the section index where that client's writes begin — the detach
            goes there, so it runs before the first of them
        """
        profiles = mobile.read_list(self.kc, "client-policies/profiles", "profiles")
        policies = mobile.read_list(self.kc, "client-policies/policies", "policies")
        ours_profile = next((p for p in profiles if p.get("name") == mobile.PROFILE_NAME), None)
        ours_policy = next((p for p in policies if p.get("name") == mobile.POLICY_NAME), None)
        profile_differs = not _matches(mobile.dpop_profile(), ours_profile)
        policy_differs = not _matches(mobile.dpop_policy(), ours_policy)
        writes_frozen = any(c.frozen for c in frozen_changes)
        detach = ours_policy is not None and (writes_frozen or profile_differs)

        if detach:
            title = "detach the DPoP policy — Keycloak refuses edits to the client while attached"
            self.sections.insert(frozen_index, (title, [Change(
                f"- detach '{mobile.POLICY_NAME}' ({len(policies) - 1} other policy(ies) "
                f"carried forward)", self._detach_policy)]))

        changes = self.section("DPoP client profile and policy (ADR-0131)")
        if profile_differs:
            changes.append(Change(
                f"{'+' if ours_profile is None else '~'} profile '{mobile.PROFILE_NAME}' "
                f"(merged by name into {len(profiles)} existing)",
                lambda: self._merge("client-policies/profiles", "profiles", mobile.dpop_profile())))
        if policy_differs or detach:
            changes.append(Change(
                f"{'+' if ours_policy is None or detach else '~'} attach policy "
                f"'{mobile.POLICY_NAME}' (merged by name; every other policy carried forward)",
                lambda: self._merge("client-policies/policies", "policies", mobile.dpop_policy())))
        foreign = [p.get("name") for p in policies if p.get("name") != mobile.POLICY_NAME]
        if foreign:
            self.reports.append(f"client policies not in the production shape (kept): "
                                f"{', '.join(sorted(str(n) for n in foreign))}")

    def _merge(self, path: str, key: str, desired: dict) -> None:
        current = mobile.read_list(self.kc, path, key)
        self.kc.write("update", path, {key: mobile.merge_by_name(current, desired)},
                      f"'{desired['name']}' merged into {len(current)} existing")

    def _detach_policy(self) -> None:
        policies = mobile.read_list(self.kc, "client-policies/policies", "policies")
        remaining = [p for p in policies if p.get("name") != mobile.POLICY_NAME]
        if len(remaining) != len(policies):
            self.kc.write("update", "client-policies/policies", {"policies": remaining},
                          "detached so the client can be edited")

    def plan_reports(self) -> None:
        managed = {s.client_id for s in self.specs}
        for client in self.kc.get("clients") or []:
            client_id = client.get("clientId")
            if client_id in BUILTIN_CLIENTS or client_id in managed:
                continue
            hint = " (pass --grafana-origin to manage it)" if client_id == "grafana" else ""
            self.reports.append(f"client '{client_id}' is not in the production shape{hint}")
        realm_defaults = {s.get("name") for s in
                          (self.kc.get("default-default-client-scopes") or [])}
        for name in AUDIENCE_SCOPES:
            if name in realm_defaults:
                self.reports.append(
                    f"'{name}' is a realm DEFAULT client scope — every client created from now "
                    f"on inherits its audience. Production removed it (hardening step 9a); remove "
                    f"it here by hand: Client scopes -> {name} -> Assigned type: None")


    def change_count(self) -> int:
        """The differences the plan lists — detail and bookkeeping lines are not counted."""
        return sum(1 for _, section in self.sections for c in section
                   if not c.text.startswith("  "))

    def has_changes(self) -> bool:
        return any(section for _, section in self.sections)

    def print_plan(self) -> None:
        total = len(self.sections)
        for number, (title, section) in enumerate(self.sections, 1):
            print(f"[{number}/{total}] {title}")
            if not section:
                print("  in shape")
            for change in section:
                if not change.text.startswith("  ("):
                    print(f"  {change.text}")

    def print_tail(self) -> None:
        if self.reports:
            print("\n[only on this realm — reported, never deleted]")
            for line in self.reports:
                print(f"  - {line}")
        if self.followup_notes:
            print("\n[secrets]")
            for line in self.followup_notes:
                print(f"  - {line}")
        if self.manual:
            print("\n[manual]")
            for line in self.manual:
                print(f"  - {line}")
        if self.problems:
            print("\n[problems]")
            for line in self.problems:
                print(f"  PROBLEM: {line}")

    def execute(self) -> None:
        for title, section in self.sections:
            if section:
                print(f"-- {title}")
            for change in section:
                change.action()


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Bring a Keycloak realm to the production shape of the Basetool's clients, "
                    "scopes, policies and token settings. Dry run unless --apply.")
    parser.add_argument("--realm", default="iri", help="target realm (default: iri)")
    parser.add_argument("--public-origin", required=True,
                        help="the environment's web origin, e.g. https://basetool.example — "
                             "feeds the frontend's and the app's redirect URIs and web origins")
    parser.add_argument("--grafana-origin",
                        help="Grafana's origin; when given, the `grafana` OAuth client is "
                             "managed too, otherwise it is left alone")
    parser.add_argument("--frontend-client", choices=("public", "confidential"),
                        help="converge basetool-frontend's client type (ADR-0001); without it the "
                             "type of an existing client is left as it is. `confidential` needs "
                             "KEYCLOAK_FRONTEND_CLIENT_SECRET in the environment when it switches "
                             "a public client")
    parser.add_argument("--external-clients", type=Path, default=EXTERNAL_CLIENTS_FILE,
                        help="the approved third-party clients to create from the template "
                             "(default: scripts/keycloak/external-clients.json)")
    parser.add_argument("--apply", action="store_true",
                        help="write the planned changes (default: dry run, writes nothing)")
    parser.add_argument("--container", default="keycloak",
                        help="Keycloak container for the default `docker exec` prefix")
    parser.add_argument("--kcadm-command",
                        help="the whole kcadm invocation, e.g. the rootless-Podman string of "
                             "docs/keycloak/README.md (also used by the tests)")
    args = parser.parse_args()

    public_origin = validate_origin(args.public_origin, "--public-origin")
    grafana_origin = (validate_origin(args.grafana_origin, "--grafana-origin")
                      if args.grafana_origin else None)
    prefix = (shlex.split(args.kcadm_command) if args.kcadm_command
              else ["docker", "exec", "-i", args.container, "/opt/keycloak/bin/kcadm.sh"])
    kc = RealmKcadm(prefix, args.realm, dry_run=False)
    specs = client_specs(args.realm, public_origin, grafana_origin, args.frontend_client,
                         load_external_clients(args.external_clients))

    mode = "APPLY" if args.apply else "DRY RUN — nothing is written"
    print(f"Keycloak realm '{args.realm}' -> production shape, public origin {public_origin} "
          f"[{mode}]\n")
    try:
        planner = Planner(kc, args.realm, specs)
        planner.plan()
        planner.print_plan()
        if not args.apply:
            planner.print_tail()
            if planner.problems:
                return 1
            if planner.has_changes():
                print(f"\n{planner.change_count()} change(s) planned. Review them, then re-run "
                      f"with --apply.")
                return 2
            print("\nThe realm is in the production shape. Nothing to do.")
            return 3 if planner.manual else 0
        if planner.problems:
            planner.print_tail()
            print("\nNot applying: fix the problems above first.", file=sys.stderr)
            return 1
        if not planner.has_changes():
            planner.print_tail()
            print("\nNo changes: the realm is already in the production shape.")
            return 3 if planner.manual else 0

        print("\n[apply]")
        planner.execute()
        print("\n[verify] re-planning against the realm as it is now")
        check = Planner(kc, args.realm, specs)
        check.plan()
        check.manual = []
        check.print_tail()
        remaining = [(title, change) for title, section in check.sections for change in section
                     if not (change.service_account and planner.manual)]
        if remaining or check.problems:
            for title, change in remaining:
                print(f"  STILL PLANNED ({title}): {change.text.strip()}")
            print("\nFAILED: the realm is not in shape after applying.", file=sys.stderr)
            return 1
        if planner.manual:
            print("\n[manual]")
            for line in planner.manual:
                print(f"  - {line}")
            print("\nApplied, except the service-account roles above.")
            return 3
        print("\nApplied. A second run reports no changes.")
        return 0
    except KcadmError as error:
        print(f"\nFAILED: {error}", file=sys.stderr)
        print("If this stopped inside the Android client's section, the DPoP policy may be "
              "detached: the client is then unbound, not half-bound. Fix the cause and re-run; "
              "the script is idempotent and re-attaches it.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
