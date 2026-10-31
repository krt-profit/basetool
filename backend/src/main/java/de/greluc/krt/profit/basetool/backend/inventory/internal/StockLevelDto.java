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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierDto;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitReferenceDto;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * A minimum stock level with its figures (REQ-INV-067, REQ-INV-068).
 *
 * @param id the level
 * @param version the level's optimistic-lock version, echoed on a change or delete
 * @param orgUnit the unit whose pool the level watches
 * @param material the watched material
 * @param qualityTier the tier, {@code null} for any quality; an inactive tier flags the level
 * @param minimum the amount below which the level is low
 * @param target the amount the level is filled up to
 * @param allocated the free stock allocated to the level
 * @param shortfall the target minus the allocated stock, never below zero
 * @param fillRatio the allocated stock divided by the minimum
 * @param low whether the allocated stock is below the minimum
 * @param editable whether the caller may change and delete the level
 */
public record StockLevelDto(
    UUID id,
    Long version,
    OrgUnitReferenceDto orgUnit,
    MaterialReferenceDto material,
    @Nullable QualityTierDto qualityTier,
    double minimum,
    double target,
    double allocated,
    double shortfall,
    double fillRatio,
    boolean low,
    boolean editable) {}
