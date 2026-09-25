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

import java.util.List;

/**
 * Frontend mirror of the backend {@code AggregatedMaterialDto}: one material at one quality, summed
 * across an item order.
 *
 * @param material the aggregated material (carries {@code quantityType} for unit-aware display)
 * @param qualityRequirement the quality bucket name ({@code GOOD} or {@code NONE})
 * @param totalQuantity the summed required quantity for this material+quality
 * @param currentStock the stock linked to the order for this material at or above the bucket's
 *     quality floor
 * @param claims the per-squadron claims on this bucket (empty for non-SK orders)
 * @param openAmount {@code totalQuantity − Σ claims}; {@code null} for non-SK orders
 */
public record AggregatedMaterialDto(
    MaterialDto material,
    @BackendEnumAsString String qualityRequirement,
    Double totalQuantity,
    Double currentStock,
    List<ClaimDto> claims,
    Double openAmount) {}
