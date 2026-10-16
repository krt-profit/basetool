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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * One org-stock lot: one unit's shared stock of one material or item at one location, quality and
 * stolen state, summed over every holder (REQ-XCH-042); absent optional fields are left out.
 *
 * @param key the opaque key: {@code u:<unit>|} followed by the stock lot key
 * @param unit the unit whose pool holds the lot
 * @param material the material or item, with its id as {@code bt}
 * @param materialKind the material's classification, or {@code null} for an item
 * @param location the location
 * @param quality the quality, {@code 0} for an item
 * @param stolen whether the lot is marked stolen
 * @param quantity the summed amount with its unit
 * @param earmarked the amounts earmarked to job orders and to missions
 * @param entries the single rows, only in a pool of the member's Logistician units under {@code
 *     exchange.orgstock.details} (REQ-XCH-043), else {@code null}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExchangeOrgStockLotDto(
    @NotNull String key,
    @NotNull ExchangeOrgStockUnitDto unit,
    @NotNull ExchangeItemRefDto material,
    @Nullable ExchangeMaterialKindDto materialKind,
    @NotNull ExchangeLocationDto location,
    int quality,
    boolean stolen,
    @NotNull ExchangeQuantityDto quantity,
    @NotNull ExchangeEarmarkedDto earmarked,
    @Nullable @Unmodifiable List<ExchangeOrgStockEntryDto> entries) {}
