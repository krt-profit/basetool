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

/**
 * Single source of truth for the ingest {@code basetool_*} business-metric names, tag keys and
 * bounded tag values (REQ-OBS-011).
 *
 * <p>Meter names use Micrometer's dotted convention (rendered with underscores + a type suffix by
 * the Prometheus scrape). Every tag value comes from a bounded, enumerable set (REQ-OBS-006): the
 * handoff {@code kind} is the {@code HandoffKind} enum, and the failure {@code reason} / rate-limit
 * {@code bucket} are the fixed value constants below — never a subject, IP or URI.
 */
public final class MetricNames {

  /** Counter {@code basetool_ingest_handoff_total} — successful handoffs, tag {@code kind}. */
  public static final String INGEST_HANDOFF = "basetool.ingest.handoff";

  /** Counter {@code basetool_ingest_handoff_errors_total} — failed relays, tag {@code reason}. */
  public static final String INGEST_HANDOFF_ERRORS = "basetool.ingest.handoff.errors";

  /**
   * Counter {@code basetool_ingest_payload_rejected_total} (untagged). Bumped by {@link
   * de.greluc.krt.profit.basetool.ingest.filter.PayloadSizeLimitFilter} when a request body exceeds
   * the cap and is refused with 413. The DoS guard was otherwise silent (no log, no metric), unlike
   * its sibling bot / rate-limit reject filters, so a flood of oversized-body probes against the
   * only internet-facing surface was undetectable (REQ-OBS-011, REQ-INGEST-005).
   */
  public static final String INGEST_PAYLOAD_REJECTED = "basetool.ingest.payload.rejected";

  /**
   * Counter {@code basetool_ratelimit_rejections_total} — tag {@code bucket} ({@code ip}/{@code
   * subject}). Shares its name with the backend rate-limit counter; the {@code application} common
   * tag distinguishes the module.
   */
  public static final String RATELIMIT_REJECTIONS = "basetool.ratelimit.rejections";

  /**
   * Counter {@code basetool_ratelimit_requests_total} — tag {@code bucket} ({@link #BUCKET_IP} /
   * {@link #BUCKET_SUBJECT}). Bumped for every bucket evaluation (consumed or rejected), so
   * rejections/requests gives a per-bucket rejection ratio rather than 429-only detection (#1041
   * item 19).
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
   * Failure reason: the Redis handoff staging was unreachable, so the relayed draft could not be
   * parked for browser pickup (REQ-INGEST-003). Kept apart from {@link #REASON_INTERNAL} because it
   * is an availability event with an obvious operator action, not an application fault — the caller
   * is told to retry (503) rather than handed a 500.
   */
  public static final String REASON_STAGING_UNAVAILABLE = "staging_unavailable";

  /** Failure reason: any other unexpected relay failure. */
  public static final String REASON_INTERNAL = "internal";

  /**
   * Counter {@code basetool_http_error_total} — tag {@code code} (stable RFC-7807 code). Shares its
   * name with the backend's error counter; the {@code application} common tag distinguishes the
   * module. Emitted by {@link
   * de.greluc.krt.profit.basetool.ingest.config.IdentityProviderUnavailableFilter} for an
   * identity-provider-unavailable 503 (REQ-SEC-024, REQ-OBS-011).
   */
  public static final String HTTP_ERROR = "basetool.http.error";

  /** Tag key: the stable RFC-7807 error code. */
  public static final String TAG_CODE = "code";

  /**
   * Error code: a dependency the request needs was unreachable — retryable 503. Covers both the
   * identity provider (Keycloak JWKS, {@code IdentityProviderUnavailableFilter}) and the Redis
   * handoff staging ({@code GlobalExceptionHandler}); the client's reaction is the same in both
   * cases, and {@link #INGEST_HANDOFF_ERRORS} separates them by {@code reason} for the dashboard.
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
   * Counter {@code basetool_bot_blocked_total} — tag {@code rule} ({@link #BOT_RULE_METHOD} /
   * {@link #BOT_RULE_PATH_PREFIX} / {@link #BOT_RULE_FILE_EXTENSION} / {@link
   * #BOT_RULE_QUERY_STRING}). Bumped by {@link
   * de.greluc.krt.profit.basetool.ingest.filter.BotProtectionFilter} at its four reject branches
   * (REQ-INGEST-009), which are otherwise {@code log.debug}-only (prod-invisible). Shares its name
   * with the frontend bot counter; the {@code application} common tag distinguishes the module. The
   * counter also surfaces a self-inflicted false positive when a new legit route matches a blocked
   * prefix (REQ-OBS-011).
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
   * Counter {@code basetool_ingest_client_total} — tag {@code client_id}. Bumped once per accepted
   * ingest call with the calling client's {@code azp} (REQ-INGEST-011, REQ-OBS-011). Answers "which
   * software is actually driving the gateway", which no other signal carries: the handoff counter
   * is tagged by draft kind and the access log by path, so a second producer appearing alongside
   * the extractor was previously invisible.
   *
   * <p>The tag value is bounded by construction — it is the matched entry of the configured
   * allowlist, or the literal {@link #CLIENT_ID_OTHER} for anything else. The raw {@code azp} is
   * never used as a label: even though Keycloak only ever stamps a registered client id, deriving a
   * label from a token claim is the shape of an unbounded-cardinality bug and is exactly what
   * REQ-OBS-011 forbids.
   */
  public static final String INGEST_CLIENT = "basetool.ingest.client";

  /**
   * Counter {@code basetool_ingest_client_rejected_total} — tag {@code reason}. Bumped whenever the
   * client-identity gate refuses a caller, and — importantly — also when it <em>would have</em>
   * refused one while {@code app.ingest.client-identity.audit-only} is set. That is what makes the
   * audit-only rollout usable: the operator configures the gates, watches this counter stay at zero
   * for a scrape interval, and only then enforces (REQ-INGEST-011).
   *
   * <p>It is also the alerting hook: a non-zero rate means either a foreign client is probing the
   * ingress or a legitimate one drifted out of the allowlist. Both need a human, which is why
   * {@code IngestUnknownClient} fires on it rather than leaving it to a dashboard nobody watches.
   */
  public static final String INGEST_CLIENT_REJECTED = "basetool.ingest.client.rejected";

  /** Tag key: the calling client's Keycloak client id, bounded by the configured allowlist. */
  public static final String TAG_CLIENT_ID = "client_id";

  /** Tag: the outcome of an operation; bounded per meter (REQ-OBS-011). */
  public static final String TAG_OUTCOME = "outcome";

  /**
   * Bounded {@code client_id} tag value for a caller whose {@code azp} is not on the allowlist (or
   * absent). Keeps the label set finite while still separating "the known extractor" from
   * "something else"; the {@code reason} on {@link #INGEST_CLIENT_REJECTED} says which of the two
   * it was.
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
   * Counter {@code basetool_ingest_auth_failures_total} — tag {@code reason}, the RFC 6750 bearer
   * error code the resource server raised ({@link #AUTH_INVALID_TOKEN} / {@link
   * #AUTH_INVALID_REQUEST} / {@link #AUTH_INSUFFICIENT_SCOPE} / {@link #AUTH_OTHER}).
   *
   * <p>Exists because a {@code 401} was previously undiagnosable. It is logged at {@code DEBUG}
   * with nothing but the exception class — deliberately, since this is the only internet-facing
   * surface and an anonymous scanner would otherwise flood the log — so in production a legitimate
   * operator chasing a failing client had no signal at all. That cost real time on 2026-08-03: a
   * client reporting "you must sign in" could have meant a malformed header, a bad signature, a
   * wrong issuer, an expired token or a missing audience, and nothing distinguished them.
   *
   * <p>The tag is the error <b>code</b>, never the description: Spring's description embeds the
   * decode failure verbatim ("An error occurred while attempting to decode the Jwt: …") and can
   * therefore quote parts of the presented token, which must never reach an appender or a label
   * (REQ-OBS-004). The code set is fixed by RFC 6750, so the label stays bounded (REQ-OBS-011).
   */
  public static final String INGEST_AUTH_FAILURES = "basetool.ingest.auth.failures";

  /**
   * Bearer error: the token was rejected — malformed header, bad signature, wrong issuer, expired,
   * or a failed audience check. By far the widest bucket, and the one an operator hits first.
   */
  public static final String AUTH_INVALID_TOKEN = "invalid_token";

  /** Bearer error: the request itself was malformed (e.g. two authentication schemes). */
  public static final String AUTH_INVALID_REQUEST = "invalid_request";

  /** Bearer error: the token is valid but lacks a required scope. */
  public static final String AUTH_INSUFFICIENT_SCOPE = "insufficient_scope";

  /** Bearer error: anything the resource server raised without an RFC 6750 code. */
  public static final String AUTH_OTHER = "other";

  /**
   * Counter {@code basetool_ingest_service_account_token_total} — tag {@code outcome} ({@link
   * #SA_TOKEN_MINTED} / {@link #SA_TOKEN_CACHED} / {@link #SA_TOKEN_FAILED}).
   *
   * <p>Since ADR-0129 the gateway calls the backend under its OWN identity instead of relaying the
   * caller's token, so this grant sits on the critical path of every ingest write: if it fails,
   * nobody can send, and the failure is in a hop no client can see. The cached/minted split also
   * shows whether the token cache is working — a mint on every request means Keycloak is being
   * asked for a token per upload.
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
   * Error code: the caller authenticated successfully but its <em>client software</em> is not
   * approved for the ingest path — 403 (REQ-INGEST-011). Deliberately distinct from {@link
   * #CODE_ACCESS_DENIED}, which means the <em>user</em> lacks a permission: here the user is fully
   * entitled and it is the tool that is refused, and the extractor surfaces the problem detail
   * verbatim, so conflating the two would tell a member "you are not allowed" when the truth is
   * "use the official extractor".
   */
  public static final String CODE_CLIENT_NOT_ALLOWED = "CLIENT_NOT_ALLOWED";

  private MetricNames() {
    // Constants holder — not instantiable.
  }
}
