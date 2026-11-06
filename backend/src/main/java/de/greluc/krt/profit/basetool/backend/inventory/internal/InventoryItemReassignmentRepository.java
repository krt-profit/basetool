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
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Moves inventory item references in the caller's reconciliation transaction. */
interface InventoryItemReassignmentRepository extends Repository<InventoryItem, UUID> {

  /**
   * Locks both identities' stock-floor settings in id order for an exact audit snapshot.
   *
   * @param retiredId duplicate item
   * @param survivorId canonical item
   * @return the protected floor settings before or after reassignment
   */
  @Query(
      value =
          "SELECT id, org_unit_id AS unitId, minimum_amount AS minimum, target_amount AS"
              + " targetAmount FROM inventory_stock_level WHERE game_item_id IN (:retiredId,"
              + " :survivorId) ORDER BY id FOR UPDATE",
      nativeQuery = true)
  List<FloorState> floorStates(
      @Param("retiredId") UUID retiredId, @Param("survivorId") UUID survivorId);

  /** The non-personal settings retained in a stock-floor reconciliation audit event. */
  interface FloorState {
    /**
     * Returns the floor whose configuration is captured.
     *
     * @return floor id
     */
    UUID getId();

    /**
     * Returns the unit the floor monitors.
     *
     * @return unit id
     */
    UUID getUnitId();

    /**
     * Returns the configured minimum quantity.
     *
     * @return minimum amount
     */
    double getMinimum();

    /**
     * Returns the configured target quantity.
     *
     * @return target amount
     */
    double getTargetAmount();
  }

  /**
   * Moves stock references without changing amounts or allocations.
   *
   * @param retiredId duplicate catalogue row
   * @param survivorId canonical catalogue row
   * @return affected row ids
   */
  @Query(
      value =
          """
          WITH moved AS (
          UPDATE inventory_item SET game_item_id = :survivorId,
          version = version + 1, updated_at = CURRENT_TIMESTAMP
          WHERE game_item_id = :retiredId RETURNING id
          ) SELECT id FROM moved
          """,
      nativeQuery = true)
  List<UUID> stock(@Param("retiredId") UUID retiredId, @Param("survivorId") UUID survivorId);

  /**
   * Keeps the strongest minimum and target on an existing canonical floor.
   *
   * @param retiredId duplicate catalogue row
   * @param survivorId canonical catalogue row
   * @return affected row ids
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
          WHERE source.game_item_id = :retiredId AND target.game_item_id = :survivorId
          AND source.org_unit_id = target.org_unit_id RETURNING target.id
          ) SELECT id FROM moved
          """,
      nativeQuery = true)
  List<UUID> combineFloors(
      @Param("retiredId") UUID retiredId, @Param("survivorId") UUID survivorId);

  /**
   * Removes a duplicate floor after its stronger settings were preserved.
   *
   * @param retiredId duplicate catalogue row
   * @param survivorId canonical catalogue row
   * @return affected row ids
   */
  @Query(
      value =
          """
          WITH removed AS (
          DELETE FROM inventory_stock_level source WHERE source.game_item_id = :retiredId
          AND EXISTS (SELECT 1 FROM inventory_stock_level target
          WHERE target.game_item_id = :survivorId AND target.org_unit_id = source.org_unit_id)
          RETURNING source.id
          ) SELECT id FROM removed
          """,
      nativeQuery = true)
  List<UUID> retireCombinedFloors(
      @Param("retiredId") UUID retiredId, @Param("survivorId") UUID survivorId);

  /**
   * Repoints non-colliding floors and re-arms their notification.
   *
   * @param retiredId duplicate catalogue row
   * @param survivorId canonical catalogue row
   * @return affected row ids
   */
  @Query(
      value =
          """
          WITH moved AS (
          UPDATE inventory_stock_level SET game_item_id = :survivorId, low_notified_at = NULL,
          version = version + 1, updated_at = CURRENT_TIMESTAMP
          WHERE game_item_id = :retiredId RETURNING id
          ) SELECT id FROM moved
          """,
      nativeQuery = true)
  List<UUID> floors(@Param("retiredId") UUID retiredId, @Param("survivorId") UUID survivorId);

  /**
   * Lists affected member lots for deterministic advisory locking of both identities.
   *
   * @param retiredId duplicate item
   * @param survivorId canonical item
   * @return affected member/location/stolen lots
   */
  @Query(
      value =
          """
          SELECT DISTINCT user_id AS memberId, location_id AS locationId, stolen AS stolen
          FROM inventory_item WHERE game_item_id IN (:retiredId, :survivorId)
          ORDER BY user_id, location_id, stolen
          """,
      nativeQuery = true)
  List<ReassignmentLot> lots(
      @Param("retiredId") UUID retiredId, @Param("survivorId") UUID survivorId);

  /** One member lot affected by reassignment. */
  interface ReassignmentLot {
    /**
     * Returns the stock holder whose lot lock is taken.
     *
     * @return stock holder id
     */
    UUID getMemberId();

    /**
     * Returns the stock location.
     *
     * @return location id
     */
    UUID getLocationId();

    /**
     * Returns the lot's stolen marker.
     *
     * @return whether the stock is stolen
     */
    boolean getStolen();
  }
}
