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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.Set;
import org.hibernate.validator.constraints.URL;

/**
 * Registers an approved client (REQ-XCH-003); it starts {@code ACTIVE}. The capabilities must
 * include {@code exchange.connect}.
 *
 * @param clientId the Keycloak client id, lower-case letters, digits and hyphens
 * @param displayName the product name; Latin letters, digits, spaces and plain punctuation, never
 *     posing as the Basetool
 * @param capabilities the capabilities to grant
 * @param minClientVersion the oldest served release as {@code major.minor.patch}, or {@code null}
 * @param contactUrl an {@code https} URL of the privacy statement and security contact, or {@code
 *     null}
 * @param requestsPerMinute the per-minute limit override, at most {@value
 *     #MAX_REQUESTS_PER_MINUTE}, or {@code null} for the default
 * @param writesPerDay the daily write quota override, at most {@value #MAX_WRITES_PER_DAY}, or
 *     {@code null} for the default
 */
public record ExchangeClientCreateRequest(
    @NotBlank @Pattern(regexp = ExchangeClientCreateRequest.CLIENT_ID_PATTERN) String clientId,
    @NotBlank @Size(max = 100) String displayName,
    @NotNull @NotEmpty Set<@NotNull ExchangeCapability> capabilities,
    @Size(max = 32) @Pattern(regexp = ExchangeClientCreateRequest.VERSION_PATTERN)
        String minClientVersion,
    @Size(max = 500) @URL(protocol = "https") String contactUrl,
    @Positive
        @Max(
            value = ExchangeClientCreateRequest.MAX_REQUESTS_PER_MINUTE,
            message = "{error.exchange.client.requestsPerMinuteMax}")
        Integer requestsPerMinute,
    @Positive
        @Max(
            value = ExchangeClientCreateRequest.MAX_WRITES_PER_DAY,
            message = "{error.exchange.client.writesPerDayMax}")
        Integer writesPerDay) {

  /** The client id rule, identical to the database check. */
  public static final String CLIENT_ID_PATTERN = "^[a-z0-9][a-z0-9-]{1,62}$";

  /** The minimum-version rule: a semantic version with an optional pre-release or build suffix. */
  public static final String VERSION_PATTERN = "^\\d+\\.\\d+\\.\\d+([-+][0-9A-Za-z.-]+)?$";

  /** The highest per-minute override: ten times the gateway's default of 120 (REQ-XCH-023). */
  public static final int MAX_REQUESTS_PER_MINUTE = 1200;

  /** The highest daily write quota override: ten times the gateway's default of 500. */
  public static final int MAX_WRITES_PER_DAY = 5000;
}
