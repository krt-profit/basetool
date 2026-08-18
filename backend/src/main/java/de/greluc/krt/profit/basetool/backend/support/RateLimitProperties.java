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
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties under {@code app.rate-limit.*}.
 *
 * <p>Consumed by {@code de.greluc.krt.profit.basetool.backend.filter.RateLimitingFilter}. The
 * {@code capacity} / {@code refillTokens} / {@code refillPeriod} triple defines the GLOBAL Bucket4j
 * bucket that applies to every request matching {@code paths}; the optional {@link #getRules()
 * rules} list overlays tighter per-pattern budgets on top (e.g. tighter limits for anonymous-spam
 * POST endpoints — audit finding L-5, 2026-05-20). When both apply, ALL matching buckets must have
 * a token left or the request is rejected with 429.
 *
 * <p>{@code trustedProxies} controls whether the filter honors {@code X-Forwarded-For} from a
 * reverse proxy. The defaults (5000 tokens, refilled 5000/min) are tuned for the project's typical
 * mission-planning workload, including a mission manager rapidly assembling a large operation's
 * crew and participant roster (the auth-gated crew endpoints rely on this global budget) — adjust
 * per environment, not via global wildcards.
 *
 * <p>Lives in the dependency-leaf {@code support} package (not {@code config}) so the {@code
 * filter} layer can read it without a {@code filter} &rarr; {@code config} package cycle; it
 * depends only on Lombok / Jakarta-validation / Spring-Boot and is registered via
 * {@code @ConfigurationPropertiesScan} regardless of package.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {
  /** Enable/disable the rate limiter globally. */
  private boolean enabled = true;

  /** Ant-style path patterns to protect (e.g. /api/**) */
  @NotEmpty private List<String> paths = java.util.List.of("/api/**");

  /** Bucket capacity (max tokens). */
  @Min(1)
  private int capacity = 5000;

  /** Tokens refilled per period. */
  @Min(1)
  private int refillTokens = 5000;

  /** Refill period. */
  @NotNull private Duration refillPeriod = Duration.ofMinutes(1);

  /**
   * Optional list of endpoint-specific rate-limit rules layered on top of the global bucket. Each
   * rule gets its own Bucket4j bucket per client IP, keyed by the rule's {@link Rule#getName()
   * name}. The filter checks the tightest rule first and aborts on the first depleted bucket, so
   * spam against an anonymous-reachable POST endpoint trips the per-rule budget before it touches
   * the loose global budget (audit finding L-5, 2026-05-20). A rule's {@link Rule#getPaths()}
   * should still be covered by {@link #getPaths()} — paths outside the global umbrella skip the
   * filter entirely.
   */
  private List<@Valid Rule> rules = new ArrayList<>();

  /**
   * Trusted reverse-proxy addresses or CIDR ranges, consumed by {@code ClientIpContextFilter}. Only
   * when the immediate peer matches one of these does client-IP resolution honour {@code
   * X-Forwarded-For}; the chain is then walked right-to-left and the first hop that is NOT in this
   * list is taken as the client. An empty list disables {@code X-Forwarded-For} entirely. The
   * literal {@code "*"} is NOT a valid entry and is silently ignored - blanket trust would let any
   * client spoof the header and bypass IP-based rate limiting.
   *
   * <p>The key stays under {@code app.rate-limit} for continuity with the deployed configuration
   * even though resolution now serves more than the rate limiter (REQ-SEC-011).
   */
  private List<String> trustedProxies = new java.util.ArrayList<>();

  /**
   * Per-endpoint rate-limit overlay. Matches a request when (1) the request method is contained in
   * {@link #getMethods()} (case-insensitive, empty = any method) AND (2) the request URI matches at
   * least one Ant-style pattern in {@link #getPaths()}. Bucket key is {@code clientIp + "|rule:" +
   * name}, so rules with the same {@link #getName()} share buckets across different request methods
   * — typically not desired; pick distinct names per logical surface.
   */
  @Data
  public static class Rule {
    /**
     * Stable identifier used as the bucket-key suffix. Two rules with the same {@code name}
     * collapse into one bucket, which is virtually never intended — keep names unique per logical
     * endpoint group.
     */
    @NotBlank private String name;

    /**
     * Ant-style URI patterns this rule applies to. Should be a subset of the global {@link
     * RateLimitProperties#getPaths()} umbrella; paths outside that umbrella never reach the filter.
     */
    @NotEmpty private List<String> paths;

    /**
     * HTTP methods the rule applies to (e.g. {@code POST}, {@code PUT}). Empty / {@code null} means
     * any method. Matching is case-insensitive.
     */
    private List<String> methods = new ArrayList<>();

    /** Bucket capacity (max tokens). */
    @Min(1)
    private int capacity;

    /** Tokens refilled per period. */
    @Min(1)
    private int refillTokens;

    /** Refill period. */
    @NotNull private Duration refillPeriod;
  }
}
