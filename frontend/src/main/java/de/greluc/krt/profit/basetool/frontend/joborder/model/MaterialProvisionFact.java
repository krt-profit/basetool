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

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * One retained declaration of actual provision and its explicit contribution attribution.
 *
 * @param quantityAmount observed total or verified credited subset
 * @param quantityEvidence scope of the retained quantity and composition
 * @param suppliedQuality exact declared original quality, or null when only its floor is known
 * @param minimumSuppliedQuality verified quality floor retained without inventing an exact value
 * @param claimId the explicitly credited contribution
 * @param participationId the credited Staffel participation
 * @param claimingOrgUnitId the explicitly credited unit
 * @param qualityTierId the credited requirement's tier
 * @param creditedAmount the truthful amount attributed to that contribution
 */
@DtoMirror
public record MaterialProvisionFact(
    double quantityAmount,
    MaterialProvisionEvidence quantityEvidence,
    @Nullable Integer suppliedQuality,
    int minimumSuppliedQuality,
    UUID claimId,
    UUID participationId,
    UUID claimingOrgUnitId,
    UUID qualityTierId,
    double creditedAmount) {}
