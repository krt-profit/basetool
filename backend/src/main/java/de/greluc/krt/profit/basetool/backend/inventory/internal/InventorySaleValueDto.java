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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import java.math.BigDecimal;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Estimated sale value of one material group of "Mein Lager": its legitimate amount at the terminal
 * price the member chose in the profile (REQ-INV-072).
 *
 * @param basis the price basis the value uses
 * @param pricePerScu the price of that basis in aUEC per SCU
 * @param terminalName the terminal paying {@code pricePerScu}, or {@code null} for {@link
 *     SalePriceBasis#AVERAGE}
 * @param terminalCount the number of terminals that buy the material
 * @param value the amount without stolen stock times {@code pricePerScu}, in whole aUEC
 * @param unvaluedStolenAmount the SCU marked „gestohlen" that {@code value} leaves out; {@code 0}
 *     when the group holds none
 */
public record InventorySaleValueDto(
    @NotNull SalePriceBasis basis,
    @NotNull BigDecimal pricePerScu,
    @Nullable String terminalName,
    int terminalCount,
    @NotNull BigDecimal value,
    double unvaluedStolenAmount) {}
