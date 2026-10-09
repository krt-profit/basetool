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
 * A material's read-only classification, which a client uses to route a lot to its own lists
 * (REQ-XCH-016); a flag UEX does not know for the material is left out.
 *
 * <p>None of it decides a lot's quality: every material lot keeps the quality it is booked at.
 *
 * @param type {@code RAW}, {@code REFINED} or {@code NO_REFINE}
 * @param commodity whether the material is listed in UEX's commodity catalogue
 * @param mineral UEX: whether it is a mined mineral
 * @param harvestable UEX: whether it is harvested
 * @param raw UEX: whether it is an unrefined raw material
 * @param refined UEX: whether it is the product of a refinery
 * @param buyable UEX: whether a terminal sells it
 * @param sellable UEX: whether a terminal buys it
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExchangeMaterialKindDto(
    @NotNull String type,
    boolean commodity,
    @Nullable Boolean mineral,
    @Nullable Boolean harvestable,
    @Nullable Boolean raw,
    @Nullable Boolean refined,
    @Nullable Boolean buyable,
    @Nullable Boolean sellable) {}
