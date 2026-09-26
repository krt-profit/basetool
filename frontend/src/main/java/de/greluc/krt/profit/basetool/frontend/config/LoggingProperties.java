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

package de.greluc.krt.profit.basetool.frontend.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe configuration for structured logging, MDC correlation and slow-request and slow
 * WebClient call detection in the frontend, bound under {@code app.logging.*}. Invalid values fail
 * the context start.
 *
 * @param correlationIdHeader HTTP header used to accept an inbound correlation id and echo the
 *     effective one back
 * @param correlationIdMdcKey MDC key for the correlation id; must match the {@code
 *     %X{correlationId}} pattern
 * @param userIdMdcKey MDC key for the JWT {@code sub} claim
 * @param slowRequestThresholdMs requests slower than this are logged at WARN by {@code
 *     RequestLoggingFilter}
 * @param slowBackendCallThresholdMs WebClient calls slower than this are logged at WARN by {@code
 *     WebClientLoggingFilter}
 * @param structuredEnabled feature flag for structured (JSON) logging, activated in {@code
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
