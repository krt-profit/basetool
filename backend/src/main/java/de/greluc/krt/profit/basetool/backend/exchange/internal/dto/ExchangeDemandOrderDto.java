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
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * One open or in-progress order of a unit the member is a Logistician of, as the demand details
 * show it; it names no assignee (REQ-XCH-047).
 *
 * @param id the order's id
 * @param number the order's running number, shown as {@code #number}, or {@code null} before the
 *     database assigned it
 * @param type {@code MATERIAL} or {@code ITEM}
 * @param status {@code OPEN} or {@code IN_PROGRESS}
 * @param priority the order's place in the queue, or {@code null} when it has none
 * @param unit the unit responsible for the order
 * @param requestingUnit the unit that placed the order, or {@code null} when the order names none
 * @param handle the requester as the order's author typed it, or {@code null}
 * @param comment the order's comment, or {@code null}
 * @param materials the material lines the order still needs
 * @param items the item lines an item order still needs; empty for a material order
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExchangeDemandOrderDto(
    @NotNull UUID id,
    @Nullable Integer number,
    @NotNull String type,
    @NotNull String status,
    @Nullable Integer priority,
    @NotNull ExchangeDemandUnitDto unit,
    @Nullable ExchangeDemandUnitDto requestingUnit,
    @Nullable String handle,
    @Nullable String comment,
    @NotNull @Unmodifiable List<ExchangeDemandOrderMaterialDto> materials,
    @NotNull @Unmodifiable List<ExchangeDemandOrderItemDto> items) {}
