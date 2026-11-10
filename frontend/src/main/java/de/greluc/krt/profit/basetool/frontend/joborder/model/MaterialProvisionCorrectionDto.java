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

import de.greluc.krt.profit.basetool.frontend.inventory.model.InventoryRowVersionDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.BackendEnumAsString;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.List;
import java.util.UUID;

/**
 * Scoped correction of false original attribution or physical provision quantity.
 *
 * @param kind the false fact being corrected
 * @param version observed provision version
 * @param claimVersion observed original contribution version
 * @param amount truthful attribution or physical quantity
 * @param targetClaimId existing attribution target, or null to remove the label
 * @param targetClaimVersion observed attribution target version
 * @param stockVersions observed connected rows for a physical correction
 * @param targetStockRowId explicit connected row receiving a physical quantity increase
 */
@DtoMirror
public record MaterialProvisionCorrectionDto(
    @BackendEnumAsString String kind,
    Long version,
    Long claimVersion,
    Double amount,
    UUID targetClaimId,
    Long targetClaimVersion,
    List<InventoryRowVersionDto> stockVersions,
    UUID targetStockRowId) {}
