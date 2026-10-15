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
 * One snapshot page of the org stock, with the units the member currently reads (REQ-XCH-042).
 *
 * @param items the lots on this page
 * @param removed the tombstones on this page; always empty on a snapshot page
 * @param units the units whose pools the member reads; a unit not listed is gone with its lots
 * @param nextCursor the next snapshot page, or after the last page the feed position the snapshot
 *     was taken at
 * @param hasMore whether a further page is ready now
 */
public record ExchangeOrgStockPageDto(
    @NotNull @Unmodifiable List<ExchangeOrgStockLotDto> items,
    @NotNull @Unmodifiable List<ExchangeTombstoneDto> removed,
    @NotNull @Unmodifiable List<ExchangeOrgUnitDto> units,
    @Nullable String nextCursor,
    boolean hasMore) {}
