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

import java.util.List;

/**
 * One row of an item order's material view: the outstanding quantity of one material across the
 * order at one quality bucket.
 *
 * @param material the aggregated material, with its {@code quantityType} for unit formatting
 * @param qualityRequirement the code of the bucket's quality tier, e.g. {@code GOOD} or {@code
 *     NONE}
 * @param qualityTier the bucket's quality tier
 * @param totalQuantity outstanding required quantity for units not yet manufactured
 *     (REQ-ORDERS-025); {@code 0} once all lines are done
 * @param currentStock linked stock attributed to this bucket (REQ-ORDERS-037); {@code null} only
 *     before the projection enriches the row
 * @param claims per-squadron claims; populated only for public SK orders
 * @param openAmount {@code totalQuantity} minus the claims; {@code null} for non-SK orders
 */
public record AggregatedMaterialDto(
    MaterialDto material,
    String qualityRequirement,
    QualityTierDto qualityTier,
    Double totalQuantity,
    Double currentStock,
    List<ClaimDto> claims,
    Double openAmount) {}
