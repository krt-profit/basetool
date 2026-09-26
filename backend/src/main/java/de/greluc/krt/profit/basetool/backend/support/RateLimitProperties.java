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
 * Configuration under {@code app.rate-limit.*} for the rate-limiting filter.
 *
 * <p>The global Bucket4j bucket applies to every request matching {@code paths}; {@code rules}
 * overlay tighter per-pattern budgets. When several apply, all must have a token left or the
 * request gets 429.
 *
 * @param enabled whether the rate limiter is enabled
 * @param paths the Ant-style path patterns to protect (e.g. {@code /api/**})
 * @param capacity the global bucket capacity
 * @param refillTokens the tokens refilled into the global bucket per period
 * @param refillPeriod the global refill period
 * @param rules optional per-endpoint rules, each with its own per-client-IP bucket keyed by {@link
 *     Rule#name() name}; their paths should lie inside {@code paths}
 * @param trustedProxies trusted reverse-proxy addresses or CIDR ranges; only then is {@code
 *     X-Forwarded-For} honoured, walked right-to-left to the first untrusted hop. Empty disables
 *     it; {@code "*"} is ignored (REQ-SEC-011)
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
   * connect, in addition to the per-IP buckets.
   *
   * @param enabled whether the per-subject budget is enforced
   * @param capacity the tokens a single subject may spend within {@code refillPeriod}
   * @param refillTokens the tokens returned to a subject's bucket each {@code refillPeriod}
   * @param refillPeriod the refill window for {@code refillTokens}
   * @param export the separate, tighter per-subject budget for export and report reads; see {@link
   *     Export}
   */
  public record Subject(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("120") @Min(1) int capacity,
      @DefaultValue("120") @Min(1) int refillTokens,
      @DefaultValue("1m") @NotNull Duration refillPeriod,
      @DefaultValue @Valid @NotNull Export export) {}

  /**
   * Per-subject budget for the export, statement, report and PDF endpoints (REQ-SEC-033), prefix
   * {@code app.rate-limit.subject.export}; a bucket separate from the write budget.
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
   * Per-endpoint rate-limit overlay, matching when the method is in {@code methods} (empty = any)
   * and the URI matches a pattern in {@code paths}. Its bucket key is {@code clientIp + "|rule:" +
   * name}.
   *
   * @param name the bucket-key suffix; rules with the same name share a bucket, so keep it unique
   * @param paths the Ant-style URI patterns; should lie inside {@link RateLimitProperties#paths()}
   * @param methods the HTTP methods, case-insensitive; empty means any
   * @param capacity the rule's bucket capacity
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
