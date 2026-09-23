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
 * Type-safe configuration for structured logging, MDC correlation and slow-request detection.
 *
 * <p>Bound under the {@code app.logging.*} prefix in {@code application*.yml}. Because all
 * components are validated via Jakarta-Validation, any misconfiguration fails the application
 * context start early (see {@code LoggingPropertiesTest} for the contract). An immutable record
 * (BE-MOD-04).
 *
 * @param correlationIdHeader the HTTP header used to accept an inbound correlation id and echo the
 *     effective one back; {@code X-Correlation-Id} by default, matching the widely used de-facto
 *     standard across gateways and proxies
 * @param correlationIdMdcKey the MDC key under which the correlation id is stored for the duration
 *     of a request; must stay in sync with the {@code %X{correlationId}} placeholder in {@code
 *     logback-spring.xml}
 * @param userIdMdcKey the MDC key under which the authenticated user's JWT {@code sub} claim is
 *     stored; intentionally limited to {@code sub} to avoid leaking names, emails or token contents
 *     into log files
 * @param orgUnitIdMdcKey the MDC key under which {@link
 *     de.greluc.krt.profit.basetool.backend.logging.CorrelationIdFilter} stores the resolved
 *     OrgUnit context of the current request — the caller's active OrgUnit (Staffel or
 *     Spezialkommando, possibly the union of memberships), or the sentinel {@code anonymous} /
 *     {@code none} / {@code all} when no single OrgUnit applies; keep it in sync with the {@code
 *     %X{orgUnitId}} placeholder in {@code logback-spring.xml}
 * @param slowRequestThresholdMs requests taking longer than this threshold (in milliseconds) are
 *     logged at {@code WARN} instead of {@code INFO} by {@code RequestLoggingFilter}; set it to a
 *     large value to disable
 * @param structuredEnabled the feature flag for structured (JSON) logging; {@code
 *     logback-spring.xml} activates the JSON appender only when this is {@code true} (typically in
 *     production)
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
