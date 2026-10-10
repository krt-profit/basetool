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
import de.greluc.krt.profit.basetool.frontend.model.DtoMirror;
import java.util.List;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code JobOrderMaterialDto}. {@code claims} and {@code openAmount}
 * are filled only for public SK orders; a {@code null} {@code openAmount} hides the claim columns.
 *
 * @param id material-line primary key
 * @param material the required material (carries {@code quantityType} for unit-aware display)
 * @param minQuality the floor of the line's quality tier, or {@code null} for the base tier
 * @param qualityTier the line's quality tier, with its labels and floor
 * @param amount the required amount
 * @param currentStock the summed linked-inventory stock
 * @param claims the per-squadron claims on this bucket (empty for non-SK orders)
 * @param openAmount {@code required − Σ claims}; {@code null} for non-SK orders
 * @param version optimistic-lock version
 */
@DtoMirror
public record JobOrderMaterialDto(
    UUID id,
    MaterialDto material,
    Integer minQuality,
    QualityTierDto qualityTier,
    Double amount,
    Double currentStock,
    List<ClaimDto> claims,
    Double openAmount,
    Long version) {}
