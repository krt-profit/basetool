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

package de.greluc.krt.profit.basetool.backend.exchange.internal.dto;

import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * One page of the member's own booking requests, a snapshot page or a feed page (REQ-BXCH-003,
 * REQ-XCH-013).
 *
 * @param items the requests of the page, each with its current state
 * @param removed the tombstones of requests that no longer exist
 * @param nextCursor the cursor of the next snapshot page or of the feed
 * @param hasMore whether a further page follows now
 */
public record ExchangeBankRequestPageDto(
    @NotNull @Unmodifiable List<ExchangeBankRequestDto> items,
    @NotNull @Unmodifiable List<ExchangeTombstoneDto> removed,
    @NotNull String nextCursor,
    boolean hasMore) {}
