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
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe configuration for MDC correlation, slow-request detection and slow backend-relay
 * detection in the ingest gateway. Bound under {@code app.logging.*} through the canonical record
 * constructor; an invalid value fails the context start early. Module-local twin of the
 * backend/frontend {@code LoggingProperties} (REQ-OBS-001/-002), carried here so the gateway's
 * logging is configured through the same {@code APP_LOGGING_*} keys instead of hard-coded
 * constants.
 *
 * <p>There is no {@code orgUnitId} key: the gateway relays drafts and owns no squadron-scoped data,
 * so that MDC field would be permanently empty (REQ-ORG-007 applies to the backend/frontend only).
 *
 * @param correlationIdHeader HTTP header used to accept an inbound correlation id, to echo the
 *     effective one back, and to relay it to the backend on the outbound import call
 * @param correlationIdMdcKey MDC key for the correlation id; must match the {@code
 *     %X{correlationId}} pattern in {@code logback-spring.xml}
 * @param userIdMdcKey MDC key for the JWT {@code sub} claim (intentionally never a name or e-mail —
 *     REQ-OBS-004)
 * @param slowRequestThresholdMs inbound requests slower than this are logged at WARN by {@code
 *     RequestLoggingFilter}
 * @param slowBackendCallThresholdMs outbound backend relays slower than this get the {@code Slow
 *     backend call} marker from {@code WebClientLoggingFilter}
 * @param structuredEnabled feature flag mirroring the backend/frontend key so the three modules
 *     share one configuration surface; the JSON appender itself is profile-gated in {@code
 *     logback-spring.xml}
 */
@Validated
@ConfigurationProperties(prefix = "app.logging")
public record LoggingProperties(
    @NotBlank @DefaultValue("X-Correlation-Id") String correlationIdHeader,
    @NotBlank @DefaultValue("correlationId") String correlationIdMdcKey,
    @NotBlank @DefaultValue("userId") String userIdMdcKey,
    @Min(0) @DefaultValue("2000") long slowRequestThresholdMs,
    @Min(0) @DefaultValue("1500") long slowBackendCallThresholdMs,
    @DefaultValue("false") boolean structuredEnabled) {}
