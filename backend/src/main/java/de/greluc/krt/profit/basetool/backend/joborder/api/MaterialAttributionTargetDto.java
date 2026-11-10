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

package de.greluc.krt.profit.basetool.backend.joborder.api;

import de.greluc.krt.profit.basetool.backend.kernel.SquadronReferenceDto;
import java.util.UUID;

/**
 * A server-authorized retained historical attribution target.
 *
 * @param claimId existing contribution identity
 * @param claimVersion observed contribution version
 * @param claimingOrgUnit the permitted affected Staffel
 * @param ended whether the promise has ended
 * @param openAmount current outstanding amount, zero when ended
 * @param maxAmount truthful known quantity available for this explicit historical correction
 */
public record MaterialAttributionTargetDto(
    UUID claimId,
    Long claimVersion,
    SquadronReferenceDto claimingOrgUnit,
    boolean ended,
    double openAmount,
    double maxAmount) {}
