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

package de.greluc.krt.profit.basetool.backend.support;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties under {@code app.rate-limit.*}.
 *
 * <p>Consumed by {@code de.greluc.krt.profit.basetool.backend.filter.RateLimitingFilter}. The
 * {@code capacity} / {@code refillTokens} / {@code refillPeriod} triple defines the GLOBAL Bucket4j
 * bucket that applies to every request matching {@code paths}; the optional {@code rules} list
 * overlays tighter per-pattern budgets on top (e.g. tighter limits for anonymous-spam POST
 * endpoints — audit finding L-5, 2026-05-20). When both apply, ALL matching buckets must have a
 * token left or the request is rejected with 429.
 *
 * <p>{@code trustedProxies} controls whether the filter honors {@code X-Forwarded-For} from a
 * reverse proxy. The defaults (5000 tokens, refilled 5000/min) are tuned for the project's typical
 * mission-planning workload, including a mission manager rapidly assembling a large operation's
 * crew and participant roster (the auth-gated crew endpoints rely on this global budget) — adjust
 * per environment, not via global wildcards.
 *
 * <p>Lives in the dependency-leaf {@code support} package (not {@code config}) so the {@code
 * filter} layer can read it without a {@code filter} &rarr; {@code config} package cycle; it
 * depends only on Jakarta-validation / Spring-Boot and is registered via
 * {@code @ConfigurationPropertiesScan} regardless of package. An immutable record (BE-MOD-04).
 *
 * @param enabled enable/disable the rate limiter globally
 * @param paths the Ant-style path patterns to protect (e.g. {@code /api/**})
 * @param capacity the global bucket capacity (max tokens)
 * @param refillTokens the tokens refilled into the global bucket per period
 * @param refillPeriod the global refill period
 * @param rules the optional endpoint-specific rate-limit rules layered on top of the global bucket.
 *     Each rule gets its own Bucket4j bucket per client IP, keyed by the rule's {@link Rule#name()
 *     name}. The filter checks the tightest rule first and aborts on the first depleted bucket, so
 *     spam against an anonymous-reachable POST endpoint trips the per-rule budget before it touches
 *     the loose global budget (audit finding L-5, 2026-05-20). A rule's {@link Rule#paths()} should
 *     still be covered by {@code paths} — paths outside the global umbrella skip the filter
 *     entirely.
 * @param trustedProxies the trusted reverse-proxy addresses or CIDR ranges, consumed by {@code
 *     ClientIpContextFilter}. Only when the immediate peer matches one of these does client-IP
 *     resolution honour {@code X-Forwarded-For}; the chain is then walked right-to-left and the
 *     first hop that is NOT in this list is taken as the client. An empty list disables {@code
 *     X-Forwarded-For} entirely. The literal {@code "*"} is NOT a valid entry and is silently
 *     ignored — blanket trust would let any client spoof the header and bypass IP-based rate
 *     limiting. The key stays under {@code app.rate-limit} for continuity with the deployed
 *     configuration even though resolution now serves more than the rate limiter (REQ-SEC-011).
 * @param subject the per-subject budget; see {@link Subject}
 */
@Validated
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("/api/**") @NotEmpty List<String> paths,
    @DefaultValue("5000") @Min(1) int capacity,
    @DefaultValue("5000") @Min(1) int refillTokens,
    @DefaultValue("1m") @NotNull Duration refillPeriod,
    @DefaultValue List<@Valid Rule> rules,
    @DefaultValue List<String> trustedProxies,
    @DefaultValue @Valid Subject subject) {

  /**
   * Per-authenticated-subject budget (REQ-SEC-033), applied to API writes and the notification SSE
   * connect.
   *
   * <p>Complements rather than replaces the per-IP buckets above. A client can rotate its apparent
   * address — behind CGNAT many legitimate users even share one — but the JWT {@code sub} is bound
   * to a Keycloak identity and cannot be chosen by the caller, so this is the budget that actually
   * bounds how hard one authenticated account can drive the API.
   *
   * @param enabled whether the per-subject budget is enforced; the e2e stack turns rate limiting
   *     off wholesale
   * @param capacity the tokens a single subject may spend within {@code refillPeriod}
   * @param refillTokens the tokens returned to a subject's bucket each {@code refillPeriod}
   * @param refillPeriod the refill window for {@code refillTokens}
   * @param export the separate, much tighter per-subject budget for the expensive export and report
   *     reads (APPSEC-10, owner decision 2026-09-22); see {@link Export}
   */
  public record Subject(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("120") @Min(1) int capacity,
      @DefaultValue("120") @Min(1) int refillTokens,
      @DefaultValue("1m") @NotNull Duration refillPeriod,
      @DefaultValue @Valid @NotNull Export export) {}

  /**
   * Per-subject budget for the export, statement, report and PDF endpoints (REQ-SEC-033 carve-out,
   * APPSEC-10) — prefix {@code app.rate-limit.subject.export}.
   *
   * <p>These are GETs, so the write-only default of {@link Subject} skips them, yet each one
   * renders a whole document: a PDF over a period's postings, a member's complete Art. 15 export,
   * an audit trail. A client looping on one of them costs orders of magnitude more than the cheap
   * reads the per-IP budget is sized for. The bucket is keyed on the subject like the write budget
   * but is separate from it, so a burst of downloads never eats into the account's ordinary writes.
   *
   * @param enabled whether the export budget is enforced; it also needs {@link Subject#enabled()}
   * @param capacity the export requests a single subject may make within {@code refillPeriod}
   * @param refillTokens the tokens returned to a subject's export bucket each {@code refillPeriod}
   * @param refillPeriod the refill window for {@code refillTokens}
   */
  public record Export(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("10") @Min(1) int capacity,
      @DefaultValue("10") @Min(1) int refillTokens,
      @DefaultValue("1m") @NotNull Duration refillPeriod) {}

  /**
   * Per-endpoint rate-limit overlay. Matches a request when (1) the request method is contained in
   * {@code methods} (case-insensitive, empty = any method) AND (2) the request URI matches at least
   * one Ant-style pattern in {@code paths}. Bucket key is {@code clientIp + "|rule:" + name}, so
   * rules with the same {@code name} share buckets across different request methods — typically not
   * desired; pick distinct names per logical surface.
   *
   * @param name the stable identifier used as the bucket-key suffix. Two rules with the same {@code
   *     name} collapse into one bucket, which is virtually never intended — keep names unique per
   *     logical endpoint group.
   * @param paths the Ant-style URI patterns this rule applies to; should be a subset of the global
   *     {@link RateLimitProperties#paths()} umbrella, since paths outside it never reach the filter
   * @param methods the HTTP methods the rule applies to (e.g. {@code POST}, {@code PUT}); empty
   *     means any method, and matching is case-insensitive
   * @param capacity the rule's bucket capacity (max tokens)
   * @param refillTokens the tokens refilled into the rule's bucket per period
   * @param refillPeriod the rule's refill period
   */
  public record Rule(
      @NotBlank String name,
      @NotEmpty List<String> paths,
      @DefaultValue List<String> methods,
      @Min(1) int capacity,
      @Min(1) int refillTokens,
      @NotNull Duration refillPeriod) {}
}
