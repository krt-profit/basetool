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
 * Data transfer record carrying Job Order Reference payload — a lightweight order projection for
 * typeaheads and pickers (refinery-order picker, Lager "Auftrag" dropdown).
 *
 * @param id order primary key
 * @param displayId human-readable sequential id
 * @param handle contact handle
 * @param status lifecycle status
 * @param requestingOrgUnit the customer org unit the order is placed for (slim reference); {@code
 *     null} only on pre-rework rows not yet backfilled. Lets the refinery-order store picker label
 *     each option with its requesting org unit so a same-material order can be distinguished from a
 *     foreign SK-public one.
 * @param materials the MATERIAL-order material lines; empty for an ITEM order (which has no {@code
 *     job_order_material} rows). Kept for callers that display the material lines.
 * @param requiredMaterialIds the distinct material ids the order requires across <em>both</em>
 *     order kinds (ITEM-derived materials included). Unlike {@code materials} this is never empty
 *     for an ITEM order, so the Lager picker and the refinery-order store picker can correctly hide
 *     an order whose requirements do not include a given row's material (REQ-ORDERS-018).
 * @param requiredGameItemIds the distinct game-item ids an ITEM order's lines request — the
 *     game-item sibling of {@code requiredMaterialIds} (REQ-INV-031): the Lager item-mode picker
 *     hides orders that do not request a row's game item. Always empty for a MATERIAL order (which
 *     accepts no item-stock link); a parallel set, not a replacement — material rows stay linkable
 *     to ITEM orders through {@code requiredMaterialIds}.
 */
public record JobOrderReferenceDto(
    UUID id,
    Integer displayId,
    String handle,
    JobOrderStatus status,
    SquadronReferenceDto requestingOrgUnit,
    List<JobOrderMaterialDto> materials,
    List<UUID> requiredMaterialIds,
    List<UUID> requiredGameItemIds) {}
