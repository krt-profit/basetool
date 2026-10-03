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

import de.greluc.krt.profit.basetool.backend.validation.QualityValue;
import de.greluc.krt.profit.basetool.backend.validation.QuantityAware;
import de.greluc.krt.profit.basetool.backend.validation.ValidQuantityAmount;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * One material line of a material job order create / update payload.
 *
 * <p>The amount is validated like an inventory book-in: {@code > 0}, whole for {@code PIECE}, SCU
 * rounded to three decimals at persistence. The line's quality tier is the one whose floor equals
 * {@code minQuality}, or the base tier when it is {@code null} (REQ-ORDERS-036).
 */
@ValidQuantityAmount
public record CreateJobOrderMaterialDto(
    @NotNull UUID materialId,
    @Nullable
        @QualityValue
        @Schema(
            description =
                "Mindestqualität einer Qualitätsstufe; null für die Stufe ohne Mindestqualität.",
            example = "650")
        Integer minQuality,
    @NotNull @Max(100_000) Double amount)
    implements QuantityAware {}
