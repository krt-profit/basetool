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
 * One aggregated material bucket of the cross-order material-demand overview (REQ-ORDERS-034): the
 * demand for a single {@code (material, quality)} pair summed over <em>every</em> non-terminal
 * order of one responsible org unit, with the coverage that already exists for it. A material
 * needed in both qualities yields two rows (one {@code GOOD}, one {@code NONE}), matching the
 * bucket key the rest of the order domain uses; the display formats the amounts per {@code
 * material.quantityType}.
 *
 * <p>The three coverage figures are deliberately kept apart rather than folded into one number:
 * {@code bookedAmount} is physical stock already linked to the orders, while {@code claimedAmount}
 * is a signal-only promise (REQ-ORDERS-024) that has moved no inventory. Adding them would
 * overstate what a gathering run can actually count on.
 *
 * @param material the bucket's material, with its {@code quantityType} for unit-aware formatting
 * @param qualityRequirement the quality bucket this row sums ({@code GOOD} or {@code NONE})
 * @param requiredAmount the summed <b>outstanding</b> demand across the group's orders — what still
 *     has to be procured, with handed-over and already-manufactured shares excluded at the source
 * @param bookedAmount the summed inventory linked to those orders for this material at or above the
 *     bucket's quality floor ({@code GOOD} → 650, {@code NONE} → no floor); the same per-bucket sum
 *     the order's own material list shows, so a row reconciles with the orders behind it
 * @param claimedAmount the summed material claims lodged on those orders' buckets; {@code 0.0} when
 *     no contributing order is a public Spezialkommando order
 * @param outstandingAmount {@code requiredAmount − bookedAmount}, floored at 0 — the gathering gap
 *     the page exists to answer. Deliberately ignores {@code claimedAmount}: a claim is a promise,
 *     not stock, so it must not shrink the amount still to be collected. This is <em>not</em> the
 *     per-order {@code openAmount} of {@link AggregatedMaterialDto}, which is a claims figure
 *     ({@code required − claims}); the two answer different questions and are never interchangeable
 * @param orders the contributing orders and their individual shares, ordered by {@code displayId};
 *     the row's totals are exactly the sum over this list
 */
public record MaterialDemandRowDto(
    MaterialDto material,
    QualityRequirement qualityRequirement,
    Double requiredAmount,
    Double bookedAmount,
    Double claimedAmount,
    Double outstandingAmount,
    List<MaterialDemandOrderShareDto> orders) {}
