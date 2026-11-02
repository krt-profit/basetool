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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierDto;
import de.greluc.krt.profit.basetool.backend.kernel.SquadronReferenceDto;
import org.jetbrains.annotations.Nullable;

/**
 * One open {@code (material, quality)} bucket of one responsible org unit in the material-demand
 * summary (REQ-ORDERS-034, REQ-UI-034).
 *
 * @param material the bucket's material, with its {@code quantityType} for formatting
 * @param qualityRequirement the code of the bucket's quality tier
 * @param qualityTier the bucket's quality tier
 * @param outstandingAmount what still has to be gathered, greater than zero
 * @param orgUnit the responsible org unit; {@code null} only for the fallback group
 */
public record MaterialDemandSummaryRowDto(
    MaterialDto material,
    String qualityRequirement,
    QualityTierDto qualityTier,
    Double outstandingAmount,
    @Nullable SquadronReferenceDto orgUnit) {}
