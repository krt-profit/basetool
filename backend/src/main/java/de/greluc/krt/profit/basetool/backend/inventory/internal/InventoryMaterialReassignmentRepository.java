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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Moves the stock entries and stock floors of a material during a material merge (REQ-DATA-046). A
 * stock floor is unique per org unit, material and quality tier, so a floor of the merged material
 * that meets one of the material that stays is folded into it.
 */
interface InventoryMaterialReassignmentRepository extends Repository<InventoryItem, UUID> {

  /**
   * Locks both materials' stock floors in id order and reads their settings for the audit trail.
   *
   * @param retiredId the material merged away
   * @param survivorId the material that stays
   * @return the floor settings
   */
  @Query(
      value =
          "SELECT id, org_unit_id AS unitId, minimum_amount AS minimum, target_amount AS"
              + " targetAmount FROM inventory_stock_level WHERE material_id IN (:retiredId,"
              + " :survivorId) ORDER BY id FOR UPDATE",
      nativeQuery = true)
  @NotNull
  List<InventoryItemReassignmentRepository.FloorState> floorStates(
      @Param("retiredId") @NotNull UUID retiredId, @Param("survivorId") @NotNull UUID survivorId);

  /**
   * Moves the stock entries of the merged material without changing amounts or allocations.
   *
   * @param retiredId the material merged away
   * @param survivorId the material that stays
   * @return the moved rows
   */
  @Query(
      value =
          """
          WITH moved AS (
          UPDATE inventory_item SET material_id = :survivorId,
          version = version + 1, updated_at = CURRENT_TIMESTAMP
          WHERE material_id = :retiredId RETURNING id
          ) SELECT id FROM moved
          """,
      nativeQuery = true)
  @NotNull
  List<UUID> stock(
      @Param("retiredId") @NotNull UUID retiredId, @Param("survivorId") @NotNull UUID survivorId);

  /**
   * Keeps the higher minimum and target on the floor of the material that stays where both
   * materials have one for the same org unit and quality tier.
   *
   * @param retiredId the material merged away
   * @param survivorId the material that stays
   * @return the floors that took the settings
   */
  @Query(
      value =
          """
          WITH moved AS (
          UPDATE inventory_stock_level target
          SET minimum_amount = GREATEST(target.minimum_amount, source.minimum_amount),
          target_amount = GREATEST(target.target_amount, source.target_amount),
          low_notified_at = NULL, version = target.version + 1, updated_at = CURRENT_TIMESTAMP
          FROM inventory_stock_level source
          WHERE source.material_id = :retiredId AND target.material_id = :survivorId
          AND source.org_unit_id = target.org_unit_id
          AND source.quality_tier_id IS NOT DISTINCT FROM target.quality_tier_id
          RETURNING target.id
          ) SELECT id FROM moved
          """,
      nativeQuery = true)
  @NotNull
  List<UUID> combineFloors(
      @Param("retiredId") @NotNull UUID retiredId, @Param("survivorId") @NotNull UUID survivorId);

  /**
   * Deletes the floors of the merged material whose settings {@link #combineFloors} kept.
   *
   * @param retiredId the material merged away
   * @param survivorId the material that stays
   * @return the deleted floors
   */
  @Query(
      value =
          """
          WITH removed AS (
          DELETE FROM inventory_stock_level source WHERE source.material_id = :retiredId
          AND EXISTS (SELECT 1 FROM inventory_stock_level target
          WHERE target.material_id = :survivorId AND target.org_unit_id = source.org_unit_id
          AND target.quality_tier_id IS NOT DISTINCT FROM source.quality_tier_id)
          RETURNING source.id
          ) SELECT id FROM removed
          """,
      nativeQuery = true)
  @NotNull
  List<UUID> retireCombinedFloors(
      @Param("retiredId") @NotNull UUID retiredId, @Param("survivorId") @NotNull UUID survivorId);

  /**
   * Moves the other floors of the merged material and re-arms their notification.
   *
   * @param retiredId the material merged away
   * @param survivorId the material that stays
   * @return the moved floors
   */
  @Query(
      value =
          """
          WITH moved AS (
          UPDATE inventory_stock_level SET material_id = :survivorId, low_notified_at = NULL,
          version = version + 1, updated_at = CURRENT_TIMESTAMP
          WHERE material_id = :retiredId RETURNING id
          ) SELECT id FROM moved
          """,
      nativeQuery = true)
  @NotNull
  List<UUID> floors(
      @Param("retiredId") @NotNull UUID retiredId, @Param("survivorId") @NotNull UUID survivorId);

  /**
   * Lists the stock lots of both materials, so their advisory locks can be taken in order.
   *
   * @param retiredId the material merged away
   * @param survivorId the material that stays
   * @return the member, location, quality and stolen marker of each lot
   */
  @Query(
      value =
          """
          SELECT DISTINCT user_id AS memberId, location_id AS locationId, quality AS quality,
          stolen AS stolen FROM inventory_item WHERE material_id IN (:retiredId, :survivorId)
          """,
      nativeQuery = true)
  @NotNull
  List<MaterialLot> lots(
      @Param("retiredId") @NotNull UUID retiredId, @Param("survivorId") @NotNull UUID survivorId);

  /** One stock lot of a material a merge touches. */
  interface MaterialLot {

    /**
     * Returns the stock holder whose lot lock is taken.
     *
     * @return the stock holder
     */
    @NotNull
    UUID getMemberId();

    /**
     * Returns the stock location.
     *
     * @return the location
     */
    @NotNull
    UUID getLocationId();

    /**
     * Returns the lot's quality.
     *
     * @return the quality, or {@code null} when the stock carries none
     */
    @Nullable
    Integer getQuality();

    /**
     * Returns the lot's stolen marker.
     *
     * @return whether the stock is stolen
     */
    boolean getStolen();
  }
}
