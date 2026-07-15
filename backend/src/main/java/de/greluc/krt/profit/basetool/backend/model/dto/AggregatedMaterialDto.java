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

import de.greluc.krt.profit.basetool.backend.model.QualityRequirement;
import java.util.List;

/**
 * One aggregation row of an item order's internal material view: the still-outstanding quantity of
 * a single material needed across the whole order at one quality level. A material required in both
 * qualities yields two rows (one {@code GOOD}, one {@code NONE}); the display formats {@code
 * totalQuantity} per {@code material.quantityType} (SCU vs Stück).
 *
 * @param material the aggregated material, with its {@code quantityType} for unit-aware formatting
 * @param qualityRequirement the quality bucket this row sums ({@code GOOD} or {@code NONE})
 * @param totalQuantity the summed <b>outstanding</b> required quantity across all item lines for
 *     this material+quality — only the demand of the units not yet manufactured ({@code
 *     requiredQuantity × (amount − manufacturedAmount) / amount} per line, REQ-ORDERS-025), so it
 *     shrinks as production is booked and is 0 once every line for this bucket is fully
 *     manufactured
 * @param currentStock the total stock of inventory linked to this order for this material at or
 *     above the bucket's quality floor ({@code GOOD} → 650, {@code NONE} → no floor); drives the
 *     collection-progress shown in the order-overview list, mirroring the MATERIAL requirement
 *     rows. {@code 0.0} when nothing is linked; {@code null} only on the neutral rows emitted by
 *     {@code JobOrderItemService.aggregateMaterials} before {@code JobOrderService} enriches them
 * @param claims the per-squadron claims on this bucket; populated only for public SK orders (Phase
 *     5, #345), empty otherwise
 * @param openAmount {@code totalQuantity − Σ claims} for the bucket; {@code null} for non-SK
 *     orders, a non-null value (possibly 0) for SK orders
 */
public record AggregatedMaterialDto(
    MaterialDto material,
    QualityRequirement qualityRequirement,
    Double totalQuantity,
    Double currentStock,
    List<ClaimDto> claims,
    Double openAmount) {}
