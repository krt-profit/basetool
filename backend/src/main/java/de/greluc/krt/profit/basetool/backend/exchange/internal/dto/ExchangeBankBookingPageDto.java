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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * One snapshot page of a viewable account's bookings, newest first (REQ-BXCH-002); the bank has no
 * change feed, so {@code removed} is always empty.
 *
 * @param items the bookings, newest first
 * @param removed always empty
 * @param nextCursor the cursor of the next page, or {@code null} after the last one
 * @param hasMore whether a further page follows
 */
public record ExchangeBankBookingPageDto(
    @NotNull @Unmodifiable List<ExchangeBankBookingDto> items,
    @NotNull @Unmodifiable List<ExchangeTombstoneDto> removed,
    @Nullable String nextCursor,
    boolean hasMore) {}
