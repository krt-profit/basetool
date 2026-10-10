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
 * One open material line of the org demand (REQ-XCH-018).
 *
 * @param material the material, with its id as {@code bt}
 * @param rawRefs the raw ores that refine into it; possibly empty
 * @param minQuality the lowest quality that satisfies the line
 * @param openQuantity the amount still needed
 * @param source {@code material-order}, or {@code item-order} for what item orders resolve to
 */
public record ExchangeDemandMaterialDto(
    @NotNull ExchangeItemRefDto material,
    @NotNull @Unmodifiable List<ExchangeItemRefDto> rawRefs,
    int minQuality,
    @NotNull ExchangeQuantityDto openQuantity,
    @NotNull String source) {}
