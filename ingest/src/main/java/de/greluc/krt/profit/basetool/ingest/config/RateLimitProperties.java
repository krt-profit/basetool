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
 * The per-IP rate-limit budget of the exchange routes (prefix {@code app.rate-limit},
 * REQ-INGEST-005), enforced by {@link
 * de.greluc.krt.profit.basetool.ingest.edge.RateLimitingFilter}.
 *
 * @param enabled master switch; {@code false} disables throttling entirely
 * @param refillPeriod refill cadence of the per-IP bucket
 * @param ipCapacity per-IP bucket size, loose because members may share an address
 * @param ipRefillTokens tokens added back to the per-IP bucket every {@code refillPeriod}
 */
@Validated
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
    @DefaultValue("true") boolean enabled,
    @NotNull @DefaultValue("PT1M") Duration refillPeriod,
    @Min(1) @DefaultValue("120") int ipCapacity,
    @Min(1) @DefaultValue("120") int ipRefillTokens) {}
