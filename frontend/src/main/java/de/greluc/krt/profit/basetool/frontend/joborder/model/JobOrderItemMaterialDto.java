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

package de.greluc.krt.profit.basetool.frontend.joborder.model;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.QualityTierDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code JobOrderItemMaterialDto}: one snapshotted material
 * requirement of an ordered item line. {@code qualityRequirement} is the {@code GOOD}/{@code NONE}
 * name as a string.
 *
 * @param id the requirement row id
 * @param material the required material (carries {@code quantityType} for unit-aware display)
 * @param requiredQuantity the amount needed for the line
 * @param qualityRequirement the code of the bucket's quality tier
 * @param qualityTier the bucket's quality tier, with its labels and floor
 * @param version optimistic-lock version
 */
@DtoMirror
public record JobOrderItemMaterialDto(
    UUID id,
    MaterialDto material,
    Double requiredQuantity,
    String qualityRequirement,
    QualityTierDto qualityTier,
    Long version) {}
