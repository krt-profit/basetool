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

import org.jetbrains.annotations.NotNull;

/**
 * One open material line of a single detailed demand order: its requirement less the stock linked
 * to it, clamped at zero (REQ-XCH-047).
 *
 * @param material the material, with its id as {@code bt}
 * @param minQuality the lowest quality that satisfies the line
 * @param openQuantity the amount the order still needs
 */
public record ExchangeDemandOrderMaterialDto(
    @NotNull ExchangeItemRefDto material,
    int minQuality,
    @NotNull ExchangeQuantityDto openQuantity) {}
