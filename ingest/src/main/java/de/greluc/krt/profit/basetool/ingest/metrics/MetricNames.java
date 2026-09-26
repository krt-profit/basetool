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

package de.greluc.krt.profit.basetool.ingest.metrics;

/** The ingest {@code basetool_*} metric names, tag keys and bounded tag values (REQ-OBS-011). */
public final class MetricNames {

  /** Counter {@code basetool_ingest_handoff_total} — successful handoffs, tag {@code kind}. */
  public static final String INGEST_HANDOFF = "basetool.ingest.handoff";

  /** Counter {@code basetool_ingest_handoff_errors_total} — failed relays, tag {@code reason}. */
  public static final String INGEST_HANDOFF_ERRORS = "basetool.ingest.handoff.errors";

  /**
   * Counter {@code basetool_ingest_payload_rejected_total} (untagged), bumped by {@link
   * de.greluc.krt.profit.basetool.ingest.filter.PayloadSizeLimitFilter} on each 413
   * (REQ-INGEST-005).
   */
  public static final String INGEST_PAYLOAD_REJECTED = "basetool.ingest.payload.rejected";

  /**
   * Counter {@code basetool_ratelimit_rejections_total} — tag {@code bucket} ({@code ip}/{@code
   * subject}). Shares its name with the backend rate-limit counter; the {@code application} common
   * tag distinguishes the module.
   */
  public static final String RATELIMIT_REJECTIONS = "basetool.ratelimit.rejections";

  /**
   * Counter {@code basetool_ratelimit_requests_total} with tag {@code bucket} ({@link #BUCKET_IP} /
   * {@link #BUCKET_SUBJECT}), bumped on every bucket evaluation, consumed or rejected.
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

  /** Rate-limit bucket value for the per-subject limiter. */
  public static final String BUCKET_SUBJECT = "subject";

  /** Failure reason: the backend rejected the forwarded payload (4xx relayed / 5xx). */
  public static final String REASON_BACKEND_REJECT = "backend_reject";

  /** Failure reason: the backend was unreachable or the circuit was open. */
  public static final String REASON_BACKEND_UNAVAILABLE = "backend_unavailable";

  /**
   * Failure reason: the backend answered {@code 401}/{@code 403} to the gateway's own
   * service-account identity (ADR-0129).
   */
  public static final String REASON_BACKEND_AUTH = "backend_auth";

  /**
   * Failure reason: the Redis handoff staging was unreachable, so the draft could not be parked
   * (REQ-INGEST-003); answered with a retryable 503.
   */
  public static final String REASON_STAGING_UNAVAILABLE = "staging_unavailable";

  /** Failure reason: any other unexpected relay failure. */
  public static final String REASON_INTERNAL = "internal";

  /**
   * Counter {@code basetool_http_error_total} with tag {@code code}, emitted by {@link
   * de.greluc.krt.profit.basetool.ingest.config.IdentityProviderUnavailableFilter} for an
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
   * de.greluc.krt.profit.basetool.ingest.filter.BotProtectionFilter} on each rejection
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
   * Counter {@code basetool_ingest_client_total} with tag {@code client_id}, bumped per accepted
   * ingest call (REQ-INGEST-011). The tag is the matched allowlist entry or {@link
   * #CLIENT_ID_OTHER}, never the raw {@code azp}.
   */
  public static final String INGEST_CLIENT = "basetool.ingest.client";

  /**
   * Counter {@code basetool_ingest_client_rejected_total} with tag {@code reason}, bumped whenever
   * the client-identity gate refuses a caller or would refuse it under audit-only (REQ-INGEST-011).
   */
  public static final String INGEST_CLIENT_REJECTED = "basetool.ingest.client.rejected";

  /** Tag key: the calling client's Keycloak client id, bounded by the configured allowlist. */
  public static final String TAG_CLIENT_ID = "client_id";

  /** Tag: the outcome of an operation; bounded per meter (REQ-OBS-011). */
  public static final String TAG_OUTCOME = "outcome";

  /**
   * {@code client_id} tag value for a caller whose {@code azp} is absent or not on the allowlist.
   */
  public static final String CLIENT_ID_OTHER = "other";

  /** Client-identity reject reason: the token's {@code azp} is not on the configured allowlist. */
  public static final String REASON_UNKNOWN_CLIENT = "unknown_client";

  /**
   * Client-identity reject reason: the token carries no {@code azp} claim at all while the
   * allowlist is configured. Separated from {@link #REASON_UNKNOWN_CLIENT} because it points at a
   * Keycloak mapper/realm change rather than at a foreign caller — same rejection, entirely
   * different fix.
   */
  public static final String REASON_MISSING_AZP = "missing_azp";

  /** Client-identity reject reason: the token lacks the configured ingest scope. */
  public static final String REASON_MISSING_SCOPE = "missing_scope";

  /**
   * Client-identity reject reason: the payload's {@code tool} provenance is not on the configured
   * allowlist. Distinct from the token-level reasons because it is the only one a caller can forge,
   * so a spike here alongside a clean {@code azp} reads as "someone is hand-building payloads with
   * a real extractor token" rather than as an infrastructure fault.
   */
  public static final String REASON_BAD_PROVENANCE = "bad_provenance";

  /**
   * Client-identity reject reason: an authenticated principal that is not a JWT reached the gate.
   */
  public static final String REASON_NON_JWT_PRINCIPAL = "non_jwt_principal";

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

  /**
   * {@link #INGEST_SERVICE_ACCOUNT_TOKEN} outcome: the grant failed; the ingest write is refused.
   */
  public static final String SA_TOKEN_FAILED = "failed";

  /**
   * {@link #INGEST_SERVICE_ACCOUNT_TOKEN} outcome: refused without calling Keycloak because a grant
   * failed within the last few seconds (the provider's failure backoff). Kept apart from {@link
   * #SA_TOKEN_FAILED} so that series still counts real grant attempts against Keycloak, while this
   * one shows how many uploads the backoff turned away.
   */
  public static final String SA_TOKEN_BACKOFF = "backoff";

  /**
   * Error code: the user is authenticated but the calling client software is not approved for
   * ingest, answered with 403 (REQ-INGEST-011). Distinct from {@link #CODE_ACCESS_DENIED}.
   */
  public static final String CODE_CLIENT_NOT_ALLOWED = "CLIENT_NOT_ALLOWED";

  /**
   * Gauge {@code basetool_tracing_enabled}: {@code 1} while this module emits spans, {@code 0}
   * while tracing is off. Always registered, so absence means the module is not scraped.
   */
  public static final String TRACING_ENABLED = "basetool.tracing.enabled";

  /**
   * Gauge {@code basetool_ingest_gate_enforcing} with tag {@link #TAG_GATE} ({@code azp} / {@code
   * scope} / {@code tool} / {@code audience}): {@code 1} while that gate refuses callers, {@code 0}
   * otherwise (REQ-INGEST-011). Published by {@link IngestGatePostureMetric}.
   */
  public static final String INGEST_GATE_ENFORCING = "basetool.ingest.gate.enforcing";

  /** Tag key: which ingest client gate {@link #INGEST_GATE_ENFORCING} describes. */
  public static final String TAG_GATE = "gate";

  private MetricNames() {}
}
