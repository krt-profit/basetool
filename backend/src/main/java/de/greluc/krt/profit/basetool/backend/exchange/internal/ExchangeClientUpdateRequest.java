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
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.Set;
import org.hibernate.validator.constraints.URL;

/**
 * Replaces a registry client's editable fields (REQ-XCH-003); the client id and the status are
 * changed elsewhere, and a stale {@code version} yields 409.
 *
 * @param displayName the product name, under the same rules as on registration
 * @param capabilities the capabilities to grant, including {@code exchange.connect}
 * @param minClientVersion the oldest served release, or {@code null}
 * @param contactUrl an {@code https} URL of the privacy statement and security contact, or {@code
 *     null}
 * @param requestsPerMinute the per-minute limit override, at most {@value
 *     ExchangeClientCreateRequest#MAX_REQUESTS_PER_MINUTE}, or {@code null}
 * @param writesPerDay the daily write quota override, at most {@value
 *     ExchangeClientCreateRequest#MAX_WRITES_PER_DAY}, or {@code null}
 * @param version the optimistic-lock version the admin last saw
 */
public record ExchangeClientUpdateRequest(
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
        Integer writesPerDay,
    @NotNull @Min(0) Long version) {}
