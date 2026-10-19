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

package de.greluc.krt.profit.basetool.backend.refinery.api;

import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialDto;
import de.greluc.krt.profit.basetool.backend.kernel.QualityValue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Refinery good of a refinery order.
 *
 * <p>An {@code inputQuantity} of {@code 0} means the input amount was not entered
 * (REQ-REFINERY-025). {@code yieldBonusPercent} is a read-only UEX enrichment: the refinery's bonus
 * (positive) or malus (negative) for {@code inputMaterial}, or {@code null} when unknown. Ignored
 * on write.
 */
public record RefineryGoodDto(
    @Nullable UUID id,
    @SuppressWarnings("MultipleNullnessAnnotations") @Nullable @NotNull MaterialDto inputMaterial,
    @NotNull @Min(0) Integer inputQuantity,
    @Nullable MaterialDto outputMaterial,
    @NotNull @Min(1) Integer outputQuantity,
    @QualityValue Integer quality,
    @Nullable Integer yieldBonusPercent) {}
