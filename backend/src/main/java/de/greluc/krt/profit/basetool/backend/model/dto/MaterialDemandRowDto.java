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
 * One aggregated {@code (material, quality)} bucket of the cross-order material-demand overview,
 * summed over every non-terminal order of one responsible org unit (REQ-ORDERS-034).
 *
 * @param material the bucket's material, with its {@code quantityType} for formatting
 * @param qualityRequirement the quality bucket ({@code GOOD} or {@code NONE})
 * @param requiredAmount the summed outstanding demand still to be procured
 * @param bookedAmount the summed inventory linked to those orders at or above the bucket's quality
 *     floor ({@code GOOD}: 650)
 * @param claimedAmount the summed material claims on those orders' buckets (REQ-ORDERS-024)
 * @param outstandingAmount {@code requiredAmount − bookedAmount}, floored at 0; ignores claims and
 *     differs from {@link AggregatedMaterialDto}'s {@code openAmount}
 * @param orders the contributing orders' shares, ordered by {@code displayId}
 */
public record MaterialDemandRowDto(
    MaterialDto material,
    QualityRequirement qualityRequirement,
    Double requiredAmount,
    Double bookedAmount,
    Double claimedAmount,
    Double outstandingAmount,
    List<MaterialDemandOrderShareDto> orders) {}
