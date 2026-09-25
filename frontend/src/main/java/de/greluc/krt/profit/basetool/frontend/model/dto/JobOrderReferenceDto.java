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
 * Frontend mirror of the backend {@code JobOrderReferenceDto}, backing the order pickers.
 *
 * <p>{@code requiredMaterialIds} and {@code requiredGameItemIds} let pickers hide orders that do
 * not need a row's material or game item (REQ-ORDERS-018, REQ-INV-031). {@code materialNeeds} and
 * {@code gameItemNeeds} label options with the outstanding need (REQ-INV-039) and are empty unless
 * requested with {@code withNeeds=true}. {@code requestingOrgUnit} may be {@code null}.
 */
public record JobOrderReferenceDto(
    UUID id,
    Integer displayId,
    String handle,
    @BackendEnumAsString String status,
    SquadronReferenceDto requestingOrgUnit,
    List<JobOrderMaterialDto> materials,
    List<UUID> requiredMaterialIds,
    List<UUID> requiredGameItemIds,
    List<JobOrderMaterialNeedDto> materialNeeds,
    List<JobOrderGameItemNeedDto> gameItemNeeds) {}
