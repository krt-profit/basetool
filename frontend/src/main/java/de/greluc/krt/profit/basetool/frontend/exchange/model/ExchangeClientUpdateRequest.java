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

package de.greluc.krt.profit.basetool.frontend.exchange.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.List;

/**
 * Edits a registered exchange client (REQ-XCH-003); the backend validates it.
 *
 * @param displayName the product name
 * @param capabilities the capabilities to grant, as their scope values
 * @param minClientVersion the oldest served release, or {@code null}
 * @param contactUrl the privacy statement and security contact, or {@code null}
 * @param requestsPerMinute the per-minute limit override, or {@code null}
 * @param writesPerDay the daily write quota override, or {@code null}
 * @param version the optimistic-lock version the admin last saw
 */
@DtoMirror
public record ExchangeClientUpdateRequest(
    String displayName,
    List<String> capabilities,
    String minClientVersion,
    String contactUrl,
    Integer requestsPerMinute,
    Integer writesPerDay,
    Long version) {}
