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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Outbound projection of a single {@code InventoryItem} stock row. Since the Lager keeps every
 * contribution as its own append-only row (no destructive merge), {@code createdAt} carries the
 * row's creation instant so the UI can order the individual entries of a grouped stack oldest-first
 * and show when each contribution was recorded.
 *
 * <p>Variante C (REQ-INV-027) adds the two independent quantity splits: {@code jobOrderAllocations}
 * / {@code missionAllocations} carry the earmarked orders/missions each with their own amount, and
 * {@code jobOrderRest} / {@code missionRest} are the still-unallocated remainder per dimension
 * ({@code amount − Σ}) the UI renders as the rest-chip. The legacy single {@code jobOrderId} /
 * {@code missionId} scalars are still populated during the migration (they carry the first
 * allocation of each dimension, or {@code null}); they are removed once the stacking and all
 * consumers read the allocations.
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
