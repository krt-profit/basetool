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
 * Configuration for MDC correlation and slow-request / slow-backend-relay detection in the ingest
 * gateway ({@code app.logging}, REQ-OBS-001/-002). Has no {@code orgUnitId} key.
 *
 * @param correlationIdHeader HTTP header used to accept, echo and relay the correlation id
 * @param correlationIdMdcKey MDC key for the correlation id; must match {@code %X{correlationId}}
 *     in {@code logback-spring.xml}
 * @param userIdMdcKey MDC key for the JWT {@code sub} claim, never a name or e-mail (REQ-OBS-004)
 * @param slowRequestThresholdMs inbound requests slower than this are logged at WARN
 * @param slowBackendCallThresholdMs outbound backend relays slower than this get the {@code Slow
 *     backend call} marker
 * @param structuredEnabled flag shared with the backend/frontend keys; the JSON appender itself is
 *     profile-gated
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
