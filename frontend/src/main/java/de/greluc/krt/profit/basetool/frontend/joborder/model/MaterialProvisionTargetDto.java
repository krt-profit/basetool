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
import de.greluc.krt.profit.basetool.frontend.kernel.model.SquadronReferenceDto;
import java.util.UUID;

/**
 * An authorized open promise suitable for a declared new material provision.
 *
 * @param claimId the selected current contribution
 * @param claimVersion its observed independent version
 * @param claimingOrgUnit the permitted contributing Staffel
 * @param openAmount its current outstanding promise
 * @param minQuality the promise's required quality floor
 * @param wholePieces whether its material uses whole pieces rather than SCU
 */
@DtoMirror
public record MaterialProvisionTargetDto(
    UUID claimId,
    Long claimVersion,
    SquadronReferenceDto claimingOrgUnit,
    double openAmount,
    int minQuality,
    boolean wholePieces) {}
