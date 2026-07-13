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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Frontend mirror of the backend inventory-item projection. {@code createdAt} carries the row's
 * creation instant so the grouped-stack UI can order an append-only stack's entries oldest-first.
 *
 * <p>Since Variante C (REQ-INV-027) an entry's quantity is split independently across several job
 * orders ({@code jobOrderAllocations}, unassigned {@code jobOrderRest}) and several missions
 * ({@code missionAllocations}, unassigned {@code missionRest}) shown as chips. The scalar {@code
 * jobOrderId} / {@code missionId} fields remain during the soak until the backend column drop.
 */
public record InventoryItemDto(
    UUID id,
    UserReferenceDto user,
    MaterialReferenceDto material,
    LocationReferenceDto location,
    Integer quality,
    Double amount,
    Boolean personal,
    UUID jobOrderId,
    Integer jobOrderDisplayId,
    UUID missionId,
    String missionName,
    List<JobOrderAllocationDto> jobOrderAllocations,
    Double jobOrderRest,
    List<MissionAllocationDto> missionAllocations,
    Double missionRest,
    String note,
    SquadronReferenceDto owningSquadron,
    Long version,
    Instant createdAt) {}
