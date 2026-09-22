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

package de.greluc.krt.profit.basetool.ingest.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Rate-limit budgets for the ingest endpoints (prefix {@code app.rate-limit}). The new ingress must
 * not become a way to hammer the backend's import endpoints, so each caller gets a token bucket
 * refilled on a fixed interval (REQ-INGEST-005). Two keys, two budgets:
 *
 * <ul>
 *   <li><b>Per authenticated JWT subject</b> ({@link
 *       de.greluc.krt.profit.basetool.ingest.ratelimit.SubjectRateLimiter}) — the enforceable
 *       control, sized by {@code capacity} / {@code refillTokens}.
 *   <li><b>Per source IP</b> ({@link
 *       de.greluc.krt.profit.basetool.ingest.filter.RateLimitingFilter}) — a coarse pre-auth front
 *       line, sized by its own, looser {@code ipCapacity} / {@code ipRefillTokens}. It used to
 *       share the subject budget, which made one CGNAT household or one office NAT share 30
 *       requests a minute between every member behind it — the IP limiter throttled legitimate
 *       members before the subject limiter, the one that actually identifies them, ever got a say.
 * </ul>
 *
 * <p>Both buckets refill over the same {@code refillPeriod}; the factory is {@link
 * de.greluc.krt.profit.basetool.ingest.ratelimit.RateLimitBuckets#newBucket(int, int, Duration)}.
 *
 * @param enabled master switch; set {@code false} (e.g. in the e2e stack) to disable throttling
 *     entirely
 * @param capacity per-subject bucket size: the maximum burst of ingest calls one member may make
 * @param refillTokens tokens added back to the per-subject bucket every {@code refillPeriod}
 * @param refillPeriod refill cadence shared by both buckets
 * @param ipCapacity per-IP bucket size. Deliberately looser than {@code capacity} because several
 *     members can share one public address; overridable via {@code APP_RATE_LIMIT_IP_CAPACITY}
 * @param ipRefillTokens tokens added back to the per-IP bucket every {@code refillPeriod};
 *     overridable via {@code APP_RATE_LIMIT_IP_REFILL_TOKENS}
 */
@Validated
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
    @DefaultValue("true") boolean enabled,
    @Min(1) @DefaultValue("30") int capacity,
    @Min(1) @DefaultValue("30") int refillTokens,
    @NotNull @DefaultValue("PT1M") Duration refillPeriod,
    @Min(1) @DefaultValue("120") int ipCapacity,
    @Min(1) @DefaultValue("120") int ipRefillTokens) {}
