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

import de.greluc.krt.profit.basetool.backend.validation.QuantityAware;
import de.greluc.krt.profit.basetool.backend.validation.ValidQuantityAmount;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Create payload for a new inventory entry, either a material row or a game-item row.
 *
 * @param userId the target user; {@code null} for a self-entry (the caller)
 * @param materialId the material for a material row; exactly one of {@code materialId} / {@code
 *     gameItemId} is set (REQ-INV-029)
 * @param gameItemId the game item for an item row; mutually exclusive with {@code materialId}
 * @param locationId the storage location; required
 * @param quality the quality in {@code [0, 1000]}; required for a material row, forbidden for a
 *     game-item row
 * @param amount the quantity; required, non-negative
 * @param personal {@code true} for a personal entry; cannot be combined with mission/job-order
 *     references
 * @param missionId optional mission reference
 * @param jobOrderId optional job-order reference
 * @param owningOrgUnitId optional owner-picker output; must be an org unit the target user belongs
 *     to, {@code null} auto-stamps
 * @param mergeStock per-action stock-merge opt-in for an {@code SCU} material (REQ-INV-026); {@code
 *     null} means {@code false}, ignored for {@code PIECE}
 * @param jobOrderAllocations optional split across several job orders (REQ-INV-027); supersedes
 *     {@link #jobOrderId} when non-empty, sum must not exceed {@link #amount}
 * @param missionAllocations optional split across several missions; supersedes {@link #missionId}
 *     when non-empty
 */
@ValidQuantityAmount
public record InventoryItemCreateDto(
    UUID userId,
    UUID materialId,
    UUID gameItemId,
    @NotNull UUID locationId,
    @Min(0) @Max(1000) Integer quality,
    @NotNull Double amount,
    Boolean personal,
    UUID missionId,
    UUID jobOrderId,
    UUID owningOrgUnitId,
    Boolean mergeStock,
    List<@Valid InventoryAllocationInput> jobOrderAllocations,
    List<@Valid InventoryAllocationInput> missionAllocations)
    implements QuantityAware {

  /**
   * Validates that exactly one of {@link #materialId} / {@link #gameItemId} is set (REQ-INV-029),
   * so the violation surfaces as a 400 instead of a DB integrity failure.
   *
   * @return {@code true} when exactly one catalog reference is set
   */
  @Schema(hidden = true)
  @AssertTrue(message = "{error.validation.inventory_catalog_xor}")
  public boolean isCatalogReferenceValid() {
    return (materialId == null) != (gameItemId == null);
  }

  /**
   * Validates that {@link #quality} is present for a material row and absent for a game-item row
   * (REQ-INV-029); skipped while the catalog XOR check fails.
   *
   * @return {@code true} when the quality presence matches the catalog kind
   */
  @Schema(hidden = true)
  @AssertTrue(message = "{error.validation.inventory_quality_by_kind}")
  public boolean isQualityConsistentWithCatalog() {
    if ((materialId == null) == (gameItemId == null)) {
      return true;
    }
    return materialId != null ? quality != null : quality == null;
  }

  /**
   * Validates that a game-item payload carries no mission reference (REQ-INV-031).
   *
   * @return {@code true} when a game-item payload carries no mission reference
   */
  @Schema(hidden = true)
  @AssertTrue(message = "{error.validation.inventory_item_no_mission}")
  public boolean isMissionFreeForGameItem() {
    return gameItemId == null
        || (missionId == null && (missionAllocations == null || missionAllocations.isEmpty()));
  }
}
