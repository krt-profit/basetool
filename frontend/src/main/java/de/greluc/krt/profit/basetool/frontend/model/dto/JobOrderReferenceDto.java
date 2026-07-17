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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import java.util.List;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code JobOrderReferenceDto}. {@code requiredMaterialIds} carries
 * the order's distinct required material ids across both order kinds (ITEM-derived included) so the
 * Lager "Auftrag" dropdown and the refinery-order store "Auftrag" dropdown can hide an order that
 * does not require a row's material (REQ-ORDERS-018); {@code materials} stays the MATERIAL-order
 * lines (empty for ITEM orders). {@code requestingOrgUnit} labels the refinery store picker option
 * with the order's customer org unit (may be {@code null} on pre-rework rows). {@code
 * requiredGameItemIds} is the game-item sibling of {@code requiredMaterialIds} (REQ-INV-031): the
 * distinct game items an ITEM order's lines request, empty for MATERIAL orders — the item-view
 * order picker's gate.
 */
public record JobOrderReferenceDto(
    UUID id,
    Integer displayId,
    String handle,
    @BackendEnumAsString String status,
    SquadronReferenceDto requestingOrgUnit,
    List<JobOrderMaterialDto> materials,
    List<UUID> requiredMaterialIds,
    List<UUID> requiredGameItemIds) {}
