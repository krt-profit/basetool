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
import org.jetbrains.annotations.NotNull;

/**
 * Outbound projection of a single {@code InventoryItem} stock row.
 *
 * <p>Exactly one of {@code material} (with {@code quality}) and {@code gameItem} is set
 * (REQ-INV-029). The allocation lists and rest figures carry the job-order and mission splits
 * (REQ-INV-027); {@code canEdit} is the server's answer whether the caller may write this row
 * (REQ-SEC-030).
 */
public record InventoryItemDto(
    UUID id,
    UserReferenceDto user,
    MaterialReferenceDto material,
    InventoryGameItemReferenceDto gameItem,
    LocationReferenceDto location,
    Integer quality,
    Double amount,
    Boolean personal,
    List<JobOrderAllocationDto> jobOrderAllocations,
    Double jobOrderRest,
    List<MissionAllocationDto> missionAllocations,
    Double missionRest,
    String note,
    SquadronReferenceDto owningSquadron,
    Long version,
    Boolean canEdit,
    Instant createdAt) {

  /**
   * Returns a copy of this projection with {@link #version} replaced, for write paths whose
   * force-increment is applied only at commit.
   *
   * @param newVersion the version the client should echo on its next write.
   * @return a copy of this DTO carrying {@code newVersion}.
   */
  @NotNull
  public InventoryItemDto withVersion(Long newVersion) {
    return new InventoryItemDto(
        id,
        user,
        material,
        gameItem,
        location,
        quality,
        amount,
        personal,
        jobOrderAllocations,
        jobOrderRest,
        missionAllocations,
        missionRest,
        note,
        owningSquadron,
        newVersion,
        canEdit,
        createdAt);
  }
}
