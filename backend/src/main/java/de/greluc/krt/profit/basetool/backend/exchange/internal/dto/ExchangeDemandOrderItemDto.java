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

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One open item line of a single detailed item order: ordered less delivered less earmarked
 * (REQ-XCH-047); absent optional fields are left out.
 *
 * @param item the game item, with its id as {@code bt}
 * @param component the item's ship component data, or {@code null} for another item
 * @param openQuantity the pieces the order still needs
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExchangeDemandOrderItemDto(
    @NotNull ExchangeItemRefDto item,
    @Nullable ExchangeComponentDto component,
    @NotNull ExchangeQuantityDto openQuantity) {}
