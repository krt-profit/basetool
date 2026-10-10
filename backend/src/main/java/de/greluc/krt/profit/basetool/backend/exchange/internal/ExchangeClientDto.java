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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A registry client as the admin sees it (REQ-XCH-003).
 *
 * @param id the registry id
 * @param clientId the Keycloak client id
 * @param displayName the product name
 * @param status whether the client may use the exchange
 * @param capabilities the granted capabilities, in declaration order
 * @param minClientVersion the oldest served release, or {@code null}
 * @param contactUrl the privacy statement and security contact, or {@code null}
 * @param requestsPerMinute the per-minute limit override, or {@code null}
 * @param writesPerDay the daily write quota override, or {@code null}
 * @param createdAt when the client was registered
 * @param updatedAt when the client last changed, or {@code null}
 * @param version the optimistic-lock version
 */
public record ExchangeClientDto(
    UUID id,
    String clientId,
    String displayName,
    ExchangeClientStatus status,
    List<ExchangeCapability> capabilities,
    String minClientVersion,
    String contactUrl,
    Integer requestsPerMinute,
    Integer writesPerDay,
    Instant createdAt,
    Instant updatedAt,
    Long version) {}
