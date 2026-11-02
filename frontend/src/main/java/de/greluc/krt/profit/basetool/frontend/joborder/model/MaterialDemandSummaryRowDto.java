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
import de.greluc.krt.profit.basetool.frontend.kernel.model.SquadronReferenceDto;

/**
 * Frontend mirror of the backend {@code MaterialDemandSummaryRowDto}: one open material bucket of
 * one responsible org unit (REQ-ORDERS-034, REQ-UI-034).
 *
 * @param material the bucket's material, with its {@code quantityType}
 * @param qualityRequirement the code of the bucket's quality tier
 * @param qualityTier the bucket's quality tier
 * @param outstandingAmount what still has to be gathered
 * @param orgUnit the responsible org unit; {@code null} for the fallback group
 */
@DtoMirror
public record MaterialDemandSummaryRowDto(
    MaterialDto material,
    String qualityRequirement,
    QualityTierDto qualityTier,
    Double outstandingAmount,
    SquadronReferenceDto orgUnit) {}
