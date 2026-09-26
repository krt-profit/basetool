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

package de.greluc.krt.profit.basetool.backend.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Validated configuration under {@code app.logging.*} for structured logging, MDC correlation and
 * slow-request detection.
 *
 * @param correlationIdHeader the HTTP header carrying the inbound and echoed correlation id
 * @param correlationIdMdcKey the MDC key of the correlation id; must match {@code
 *     logback-spring.xml}
 * @param userIdMdcKey the MDC key of the caller's JWT {@code sub}; never names, emails or tokens
 * @param orgUnitIdMdcKey the MDC key under which {@link
 *     de.greluc.krt.profit.basetool.backend.logging.CorrelationIdFilter} stores the request's
 *     OrgUnit context or a sentinel; must match {@code logback-spring.xml}
 * @param slowRequestThresholdMs the duration in milliseconds above which a request is logged at
 *     WARN
 * @param structuredEnabled whether {@code logback-spring.xml} activates the JSON appender
 */
@Validated
@ConfigurationProperties(prefix = "app.logging")
public record LoggingProperties(
    @DefaultValue("X-Correlation-Id") @NotBlank String correlationIdHeader,
    @DefaultValue("correlationId") @NotBlank String correlationIdMdcKey,
    @DefaultValue("userId") @NotBlank String userIdMdcKey,
    @DefaultValue("orgUnitId") @NotBlank String orgUnitIdMdcKey,
    @DefaultValue("2000") @Min(0) long slowRequestThresholdMs,
    @DefaultValue("false") boolean structuredEnabled) {}
