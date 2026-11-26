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

/**
 * Estimated sale value of the entries marked in "Mein Lager", at the terminal price the member
 * chose in the profile (REQ-INV-072).
 *
 * @param basis the price basis the value uses
 * @param total the summed value of the marked materials, each rounded to whole aUEC like a group's
 * @param unpricedMaterials the marked materials without a sale price: a {@code PIECE} material or
 *     one no terminal buys
 * @param stolenExcluded whether a marked, priced material holds stock marked „gestohlen" that
 *     {@code total} leaves out
 */
public record InventorySelectionSaleValueDto(
    @NotNull SalePriceBasis basis,
    @NotNull BigDecimal total,
    int unpricedMaterials,
    boolean stolenExcluded) {}
