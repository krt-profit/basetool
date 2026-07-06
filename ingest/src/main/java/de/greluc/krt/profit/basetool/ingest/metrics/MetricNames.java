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

  /** Error code: the identity provider (Keycloak JWKS) was unreachable — retryable 503. */
  public static final String CODE_SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";

  private MetricNames() {
    // Constants holder — not instantiable.
  }
}
