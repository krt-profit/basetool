/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.greluc.krt.profit.basetool.ingest.observability;

/** The ingest {@code basetool_*} metric names, tag keys and bounded tag values (REQ-OBS-011). */
public final class MetricNames {

  /** Counter {@code basetool_ingest_handoff_total} — successful handoffs, tag {@code kind}. */
  public static final String INGEST_HANDOFF = "basetool.ingest.handoff";

  /**
   * Counter {@code basetool_ingest_handoff_errors_total} — an unreachable handoff staging or an
   * unexpected failure, tag {@code reason}.
   */
  public static final String INGEST_HANDOFF_ERRORS = "basetool.ingest.handoff.errors";

  /**
   * Counter {@code basetool_ingest_payload_rejected_total} (untagged), bumped by {@link
   * de.greluc.krt.profit.basetool.ingest.edge.PayloadSizeLimitFilter} on each 413 (REQ-INGEST-005).
   */
  public static final String INGEST_PAYLOAD_REJECTED = "basetool.ingest.payload.rejected";

  /**
   * Counter {@code basetool_ratelimit_rejections_total} — tag {@code bucket} ({@link #BUCKET_IP}).
   * Shares its name with the backend rate-limit counter; the {@code application} common tag
   * distinguishes the module.
   */
  public static final String RATELIMIT_REJECTIONS = "basetool.ratelimit.rejections";

  /**
   * Counter {@code basetool_ratelimit_requests_total} with tag {@code bucket} ({@link #BUCKET_IP}),
   * bumped on every bucket evaluation, consumed or rejected.
   */
  public static final String RATELIMIT_REQUESTS = "basetool.ratelimit.requests";

  /** Tag key: the handoff draft kind ({@code HandoffKind#name()}). */
  public static final String TAG_KIND = "kind";

  /** Tag key: the bounded relay-failure reason. */
  public static final String TAG_REASON = "reason";

  /** Tag key: which rate limiter rejected the request. */
  public static final String TAG_BUCKET = "bucket";

  /** Rate-limit bucket value for the pre-auth per-IP servlet filter. */
  public static final String BUCKET_IP = "ip";

  /**
   * Failure reason: the Redis handoff staging was unreachable, so the draft could not be parked
   * (REQ-INGEST-003); answered with a retryable 503.
   */
  public static final String REASON_STAGING_UNAVAILABLE = "staging_unavailable";

  /** Failure reason: any other unexpected failure. */
  public static final String REASON_INTERNAL = "internal";

  /**
   * Counter {@code basetool_http_error_total} with tag {@code code}, emitted by {@link
   * de.greluc.krt.profit.basetool.ingest.auth.IdentityProviderUnavailableFilter} for an
   * identity-provider 503 (REQ-SEC-024).
   */
  public static final String HTTP_ERROR = "basetool.http.error";

  /** Tag key: the stable RFC-7807 error code. */
  public static final String TAG_CODE = "code";

  /**
   * Error code for a retryable 503 when the identity provider or the Redis handoff staging is
   * unreachable.
   */
  public static final String CODE_SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";

  /**
   * Error code: the caller presented no token, or one the resource server rejected — 401. Mirrors
   * the backend's code so a client can branch identically against both modules (REQ-API-004).
   */
  public static final String CODE_UNAUTHENTICATED = "UNAUTHENTICATED";

  /** Error code: the caller is authenticated but not allowed to use the endpoint — 403. */
  public static final String CODE_ACCESS_DENIED = "ACCESS_DENIED";

  /**
   * Counter {@code basetool_bot_blocked_total} with tag {@code rule} ({@link #BOT_RULE_METHOD} /
   * {@link #BOT_RULE_PATH_PREFIX} / {@link #BOT_RULE_FILE_EXTENSION} / {@link
   * #BOT_RULE_QUERY_STRING}), bumped by {@link
   * de.greluc.krt.profit.basetool.ingest.edge.BotProtectionFilter} on each rejection
   * (REQ-INGEST-009).
   */
  public static final String BOT_BLOCKED = "basetool.bot.blocked";

  /** Tag key: the bot-protection reject rule on {@link #BOT_BLOCKED}. */
  public static final String TAG_RULE = "rule";

  /** Bot-block rule: a disallowed HTTP method (answered 405). */
  public static final String BOT_RULE_METHOD = "method";

  /** Bot-block rule: a known bot/scanner path prefix (answered 404). */
  public static final String BOT_RULE_PATH_PREFIX = "path_prefix";

  /** Bot-block rule: a never-served file extension (answered 404). */
  public static final String BOT_RULE_FILE_EXTENSION = "file_extension";

  /**
   * Bot-block rule: a syntactically invalid query string (answered with a bare 400). A chunk whose
   * parameter name is empty but which carries a value (e.g. {@code /?=phpinfo()}) makes Tomcat's
   * parameter parser throw on the first {@code getParameter*()} call, which happens on every
   * request; rejecting it at the edge is what keeps that failure out of the error dispatch.
   */
  public static final String BOT_RULE_QUERY_STRING = "query_string";

  /**
   * Counter {@code basetool_ingest_exchange_refused_total{reason,client_id}}: every exchange
   * request the gateway refused, by its problem code in snake case and the registry client it came
   * from (REQ-XCH-028).
   */
  public static final String EXCHANGE_REFUSED = "basetool.ingest.exchange.refused";

  /**
   * Counter {@code basetool_ingest_exchange_relay_total{outcome,client_id}}: every admitted
   * exchange request the gateway relayed — {@code ok}, {@code refused} by the backend with a
   * registry code, or {@code failed} (answered {@code 502}) — by registry client (REQ-XCH-028).
   */
  public static final String EXCHANGE_RELAY = "basetool.ingest.exchange.relay";

  /**
   * Counter {@code basetool_ingest_exchange_mass_changes_staged_total{client_id}}: change sets the
   * backend's mass-change guard held back and the gateway staged for the member's confirmation, by
   * registry client (REQ-XCH-021).
   */
  public static final String EXCHANGE_MASS_CHANGES_STAGED =
      "basetool.ingest.exchange.mass.changes.staged";

  /**
   * {@link #TAG_CLIENT_ID} value on the exchange counters before a token names a client, and on the
   * zero registrations.
   */
  public static final String EXCHANGE_CLIENT_NONE = "none";

  /** {@link #TAG_CLIENT_ID} value on the exchange counters for a client the registry lacks. */
  public static final String EXCHANGE_CLIENT_UNREGISTERED = "unregistered";

  /** {@link #TAG_CLIENT_ID} value on the exchange counters while the registry cannot be read. */
  public static final String EXCHANGE_CLIENT_UNKNOWN = "unknown";

  /**
   * Counter {@code basetool_ingest_exchange_idempotent_replays_total}: exchange writes answered
   * from the idempotency cache instead of running again (REQ-XCH-020).
   */
  public static final String EXCHANGE_IDEMPOTENT_REPLAYS =
      "basetool.ingest.exchange.idempotent.replays";

  /**
   * Gauge {@code basetool_exchange_registry_mirror_age_seconds}: the seconds since the gateway last
   * read the registry mirror successfully, {@code NaN} before the first read (REQ-XCH-028).
   */
  public static final String EXCHANGE_REGISTRY_MIRROR_AGE = "basetool.exchange.registry.mirror.age";

  /**
   * Gauge {@code basetool_ingest_exchange_budget_used_ratio}: the share of the exchange's total
   * Redis byte budget in use when the gateway last measured it (REQ-XCH-023).
   */
  public static final String EXCHANGE_BUDGET_USED_RATIO =
      "basetool.ingest.exchange.budget.used.ratio";

  /**
   * Gauge {@code basetool_ingest_exchange_client_budget_used_ratio{client_id}}: the share of one
   * client's Redis byte budget in use when the gateway last measured it, labelled with the registry
   * client id of an admitted request (REQ-XCH-023, REQ-OBS-011).
   */
  public static final String EXCHANGE_CLIENT_BUDGET_USED_RATIO =
      "basetool.ingest.exchange.client.budget.used.ratio";

  /**
   * Counter {@code basetool_ingest_dpop_replay_refused_total{path_scope,reason}}: DPoP proofs the
   * {@code jti} replay cache refused — {@link #DPOP_REPLAY_REPLAYED}, {@link
   * #DPOP_REPLAY_MEMBER_CAP} or {@link #DPOP_REPLAY_FULL} — registered at zero (REQ-XCH-006).
   */
  public static final String DPOP_REPLAY_REFUSED = "basetool.ingest.dpop.replay.refused";

  /**
   * {@link #TAG_REASON} value on {@link #DPOP_REPLAY_REFUSED}: a proof whose {@code jti} was used.
   */
  public static final String DPOP_REPLAY_REPLAYED = "replayed";

  /**
   * {@link #TAG_REASON} value on {@link #DPOP_REPLAY_REFUSED}: the member holds too many proofs.
   */
  public static final String DPOP_REPLAY_MEMBER_CAP = "member_cap";

  /** {@link #TAG_REASON} value on {@link #DPOP_REPLAY_REFUSED}: the whole cache is full. */
  public static final String DPOP_REPLAY_FULL = "full";

  /**
   * Tag key on {@link #INGEST_AUTH_FAILURES}: the surface the request targeted — {@link
   * #PATH_SCOPE_EXCHANGE} or {@link #PATH_SCOPE_OTHER}.
   */
  public static final String TAG_PATH_SCOPE = "path_scope";

  /** {@link #TAG_PATH_SCOPE} value: the exchange under {@code /exchange}. */
  public static final String PATH_SCOPE_EXCHANGE = "exchange";

  /** {@link #TAG_PATH_SCOPE} value: any other path. */
  public static final String PATH_SCOPE_OTHER = "other";

  /**
   * Tag key: the calling client's Keycloak client id on the exchange counters, bounded by the
   * registry.
   */
  public static final String TAG_CLIENT_ID = "client_id";

  /** Tag: the outcome of an operation; bounded per meter (REQ-OBS-011). */
  public static final String TAG_OUTCOME = "outcome";

  /**
   * Counter {@code basetool_ingest_auth_failures_total} with tag {@code reason}: the RFC 6750 error
   * code ({@link #AUTH_INVALID_TOKEN} / {@link #AUTH_INVALID_REQUEST} / {@link
   * #AUTH_INSUFFICIENT_SCOPE}), {@link #AUTH_NO_CREDENTIALS} or {@link #AUTH_OTHER}.
   *
   * <p>The tag is the code, never the error description, which may quote the token (REQ-OBS-004).
   */
  public static final String INGEST_AUTH_FAILURES = "basetool.ingest.auth.failures";

  /**
   * Bearer error: a presented token was rejected (bad signature, wrong issuer, expired or failed
   * audience check).
   */
  public static final String AUTH_INVALID_TOKEN = "invalid_token";

  /** Bearer error: the request itself was malformed (e.g. two authentication schemes). */
  public static final String AUTH_INVALID_REQUEST = "invalid_request";

  /** Bearer error: the token is valid but lacks a required scope. */
  public static final String AUTH_INSUFFICIENT_SCOPE = "insufficient_scope";

  /**
   * {@link #INGEST_AUTH_FAILURES} reason: a DPoP proof was invalid, replayed or for another key.
   */
  public static final String AUTH_INVALID_DPOP_PROOF = "invalid_dpop_proof";

  /**
   * {@link #INGEST_AUTH_FAILURES} reason: an exchange proof lacked the server nonce — the normal
   * first round trip of a client, not an attack.
   */
  public static final String AUTH_USE_DPOP_NONCE = "use_dpop_nonce";

  /**
   * {@link #INGEST_AUTH_FAILURES} reason: an exchange proof's member already held its cap of live
   * proofs, answered {@code 429 DPOP_PROOF_LIMIT}.
   */
  public static final String AUTH_DPOP_PROOF_LIMIT = "dpop_proof_limit";

  /**
   * {@link #INGEST_AUTH_FAILURES} reason: the exchange's DPoP replay store held its total cap of
   * live proofs, answered {@code 503 SERVICE_UNAVAILABLE}.
   */
  public static final String AUTH_DPOP_STORE_FULL = "dpop_store_full";

  /** No credential was presented at all, so there is no RFC 6750 code to report. */
  public static final String AUTH_NO_CREDENTIALS = "no_credentials";

  /**
   * Bearer error: any other failure without an RFC 6750 code that is not the no-credential case.
   */
  public static final String AUTH_OTHER = "other";

  /**
   * Counter {@code basetool_ingest_service_account_token_total} with tag {@code outcome} ({@link
   * #SA_TOKEN_MINTED} / {@link #SA_TOKEN_CACHED} / {@link #SA_TOKEN_FAILED} / {@link
   * #SA_TOKEN_BACKOFF}) for the gateway's own backend token (ADR-0129).
   */
  public static final String INGEST_SERVICE_ACCOUNT_TOKEN = "basetool.ingest.service.account.token";

  /** {@link #INGEST_SERVICE_ACCOUNT_TOKEN} outcome: a fresh token was obtained from Keycloak. */
  public static final String SA_TOKEN_MINTED = "minted";

  /** {@link #INGEST_SERVICE_ACCOUNT_TOKEN} outcome: the cached token was still good. */
  public static final String SA_TOKEN_CACHED = "cached";

  /** {@link #INGEST_SERVICE_ACCOUNT_TOKEN} outcome: the grant failed; the relay is refused. */
  public static final String SA_TOKEN_FAILED = "failed";

  /**
   * {@link #INGEST_SERVICE_ACCOUNT_TOKEN} outcome: refused without calling Keycloak because a grant
   * failed within the last few seconds (the provider's failure backoff). Kept apart from {@link
   * #SA_TOKEN_FAILED} so that series still counts real grant attempts against Keycloak, while this
   * one shows how many relays the backoff turned away.
   */
  public static final String SA_TOKEN_BACKOFF = "backoff";

  /**
   * Gauge {@code basetool_tracing_enabled}: {@code 1} while this module emits spans, {@code 0}
   * while tracing is off. Always registered, so absence means the module is not scraped.
   */
  public static final String TRACING_ENABLED = "basetool.tracing.enabled";

  /**
   * Gauge {@code basetool_ingest_gate_enforcing} with tag {@link #TAG_GATE} ({@code audience}):
   * {@code 1} while the audience check refuses tokens, {@code 0} otherwise (REQ-INGEST-011).
   * Published by {@link de.greluc.krt.profit.basetool.ingest.assembly.IngestGatePostureMetric}.
   */
  public static final String INGEST_GATE_ENFORCING = "basetool.ingest.gate.enforcing";

  /** Tag key: which ingest gate {@link #INGEST_GATE_ENFORCING} describes. */
  public static final String TAG_GATE = "gate";

  private MetricNames() {}
}
