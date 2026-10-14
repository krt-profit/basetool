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

package de.greluc.krt.profit.basetool.backend.inventory.api;

import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationReferenceDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.backend.identity.api.UserReferenceDto;
import de.greluc.krt.profit.basetool.backend.kernel.SquadronReferenceDto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Outbound projection of a single {@code InventoryItem} stock row.
 *
 * <p>Exactly one of {@code material} (with {@code quality}) and {@code gameItem} is set
 * (REQ-INV-029). The allocation lists and rest figures carry the job-order and mission splits
 * (REQ-INV-027); {@code stolen} is the „gestohlen" marker (REQ-INV-053); {@code canEdit} is the
 * server's answer whether the caller may write this row, scope and then owner or
 * Logistician-or-above (REQ-SEC-047).
 */
public record InventoryItemDto(
    UUID id,
    @Nullable UserReferenceDto user,
    @Nullable MaterialReferenceDto material,
    @Nullable InventoryGameItemReferenceDto gameItem,
    @Nullable LocationReferenceDto location,
    @Nullable Integer quality,
    Double amount,
    Boolean personal,
    Boolean stolen,
    List<JobOrderAllocationDto> jobOrderAllocations,
    Double jobOrderRest,
    List<MissionAllocationDto> missionAllocations,
    Double missionRest,
    List<DonationAllocationDto> donationAllocations,
    @Nullable String note,
    @Nullable SquadronReferenceDto owningSquadron,
    Long version,
    Boolean canEdit,
    Instant createdAt) {

  /**
   * Creates a projection of a row that carries no donation pledge.
   *
   * @param id the row id
   * @param user the holder
   * @param material the material, or {@code null} for a game item
   * @param gameItem the game item, or {@code null} for a material
   * @param location the location
   * @param quality the quality of a material
   * @param amount the amount held
   * @param personal whether the row is personal
   * @param stolen whether the row is marked stolen
   * @param jobOrderAllocations the job-order slices
   * @param jobOrderRest the unallocated job-order remainder
   * @param missionAllocations the mission slices
   * @param missionRest the unallocated mission remainder
   * @param note the row's note
   * @param owningSquadron the owning org unit
   * @param version the optimistic-locking version
   * @param canEdit whether the caller may write the row
   * @param createdAt the creation time
   */
  public InventoryItemDto(
      UUID id,
      @Nullable UserReferenceDto user,
      @Nullable MaterialReferenceDto material,
      @Nullable InventoryGameItemReferenceDto gameItem,
      @Nullable LocationReferenceDto location,
      @Nullable Integer quality,
      Double amount,
      Boolean personal,
      Boolean stolen,
      List<JobOrderAllocationDto> jobOrderAllocations,
      Double jobOrderRest,
      List<MissionAllocationDto> missionAllocations,
      Double missionRest,
      @Nullable String note,
      @Nullable SquadronReferenceDto owningSquadron,
      Long version,
      Boolean canEdit,
      Instant createdAt) {
    this(
        id,
        user,
        material,
        gameItem,
        location,
        quality,
        amount,
        personal,
        stolen,
        jobOrderAllocations,
        jobOrderRest,
        missionAllocations,
        missionRest,
        List.of(),
        note,
        owningSquadron,
        version,
        canEdit,
        createdAt);
  }

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
        stolen,
        jobOrderAllocations,
        jobOrderRest,
        missionAllocations,
        missionRest,
        donationAllocations,
        note,
        owningSquadron,
        newVersion,
        canEdit,
        createdAt);
  }
}
