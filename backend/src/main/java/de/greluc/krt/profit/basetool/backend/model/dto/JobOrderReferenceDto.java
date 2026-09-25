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

import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import java.util.List;
import java.util.UUID;

/**
 * Lightweight job-order projection for typeaheads and pickers (refinery-order picker, Lager
 * "Auftrag" dropdown).
 *
 * @param id order primary key
 * @param displayId human-readable sequential id
 * @param handle contact handle
 * @param status lifecycle status
 * @param requestingOrgUnit the customer org unit the order is placed for; may be {@code null}
 * @param materials the MATERIAL-order material lines; empty for an ITEM order
 * @param requiredMaterialIds the distinct material ids the order requires across both order kinds;
 *     never empty for an ITEM order (REQ-ORDERS-018)
 * @param requiredGameItemIds the distinct game-item ids an ITEM order's lines request; always empty
 *     for a MATERIAL order (REQ-INV-031)
 * @param materialNeeds the outstanding amount per {@code (material, quality)} bucket for both order
 *     kinds; empty unless requested with {@code withNeeds=true} (REQ-INV-039)
 * @param gameItemNeeds the outstanding count per game item of an ITEM order; empty unless requested
 *     with {@code withNeeds=true} and always empty for a MATERIAL order
 */
public record JobOrderReferenceDto(
    UUID id,
    Integer displayId,
    String handle,
    JobOrderStatus status,
    SquadronReferenceDto requestingOrgUnit,
    List<JobOrderMaterialDto> materials,
    List<UUID> requiredMaterialIds,
    List<UUID> requiredGameItemIds,
    List<JobOrderMaterialNeedDto> materialNeeds,
    List<JobOrderGameItemNeedDto> gameItemNeeds) {}
