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

package de.greluc.krt.profit.basetool.backend.model.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * One material row of an extracted refinement order; duplicate material names across rows are
 * expected and never merged.
 *
 * @param rowIndex on-screen position, top row = 0; drives the draft's goods order
 * @param rawMaterialName material name verbatim, possibly truncated by the game UI
 * @param quality SC QUALITY column; range not enforced; {@code null} becomes {@code 0}
 * @param inputQuantity SC QTY column; {@code 0} skips the row with {@code SKIPPED_ZERO_QTY}
 * @param outputQuantity SC YIELD column; {@code null} when unquoted, reported as {@code
 *     UNQUOTED_ROW}
 * @param refine SC REFINE toggle; {@code false} skips the row with {@code SKIPPED_REFINE_OFF}
 * @param confidence derived read confidence in {@code [0,1]}
 * @param sourceImage screenshot file name the row was read from (provenance only)
 */
public record RefineryExtractGoodDto(
    @PositiveOrZero Integer rowIndex,
    @NotNull @Size(max = 255) String rawMaterialName,
    Integer quality,
    @NotNull @PositiveOrZero Integer inputQuantity,
    @PositiveOrZero Integer outputQuantity,
    @NotNull Boolean refine,
    @DecimalMin("0.0") @DecimalMax("1.0") Double confidence,
    @Size(max = 255) String sourceImage) {}
