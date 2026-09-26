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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;

/**
 * Frontend mirror of one refinery-order good.
 *
 * <p>{@code yieldBonusPercent} is a read-only backend enrichment (positive = bonus, negative =
 * malus, {@code null} = no yield known) and is ignored on submit.
 */
public record RefineryGoodDto(
    UUID id,
    MaterialDto inputMaterial,
    @Min(1) Integer inputQuantity,
    MaterialDto outputMaterial,
    @Min(1) Integer outputQuantity,
    @Min(0) @Max(1000) Integer quality,
    Integer yieldBonusPercent) {}
