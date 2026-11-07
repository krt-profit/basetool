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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Moves the recipe, loadout, paint and shop references inside the catalogue module during a
 * verified item reconciliation.
 */
interface CatalogueItemReassignmentRepository extends Repository<GameItem, UUID> {

  /**
   * Repoints blueprint rows and bumps their optimistic-lock versions.
   *
   * @param retiredId retired catalogue item
   * @param survivorId canonical catalogue item
   * @return affected recipe rows
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          UPDATE blueprint SET output_item_id = :survivorId,
            version = version + 1, updated_at = CURRENT_TIMESTAMP
          WHERE output_item_id = :retiredId
          """,
      nativeQuery = true)
  int blueprints(@Param("retiredId") UUID retiredId, @Param("survivorId") UUID survivorId);

  /**
   * Repoints blueprint_ingredient rows and bumps their optimistic-lock versions.
   *
   * @param retiredId retired catalogue item
   * @param survivorId canonical catalogue item
   * @return affected recipe rows
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          UPDATE blueprint_ingredient SET game_item_id = :survivorId,
            version = version + 1, updated_at = CURRENT_TIMESTAMP
          WHERE game_item_id = :retiredId
          """,
      nativeQuery = true)
  int ingredients(@Param("retiredId") UUID retiredId, @Param("survivorId") UUID survivorId);

  /**
   * Repoints the loadout ports that hold the retired item by default (REQ-P4K-040).
   *
   * @param retiredId retired catalogue item
   * @param survivorId canonical catalogue item
   * @return affected ports
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          UPDATE ship_type_loadout_port SET game_item_id = :survivorId
          WHERE game_item_id = :retiredId
          """,
      nativeQuery = true)
  int loadoutPorts(@Param("retiredId") UUID retiredId, @Param("survivorId") UUID survivorId);

  /**
   * Moves the retired paint's ship links onto the survivor (REQ-P4K-041); a link the survivor
   * already holds stays with the retired item and goes with it.
   *
   * @param retiredId retired catalogue item
   * @param survivorId canonical catalogue item
   * @return moved links
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          UPDATE ship_type_paint SET game_item_id = :survivorId
          WHERE game_item_id = :retiredId
            AND ship_type_id NOT IN
              (SELECT held.ship_type_id FROM ship_type_paint held
               WHERE held.game_item_id = :survivorId)
          """,
      nativeQuery = true)
  int paints(@Param("retiredId") UUID retiredId, @Param("survivorId") UUID survivorId);

  /**
   * Moves the retired item's shop rows onto the survivor (REQ-P4K-044); a shop that already lists
   * the survivor keeps its row, and the retired item's row goes with it.
   *
   * @param retiredId retired catalogue item
   * @param survivorId canonical catalogue item
   * @return moved rows
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          UPDATE shop_item SET game_item_id = :survivorId
          WHERE game_item_id = :retiredId
            AND shop_id NOT IN
              (SELECT held.shop_id FROM shop_item held WHERE held.game_item_id = :survivorId)
          """,
      nativeQuery = true)
  int shopItems(@Param("retiredId") UUID retiredId, @Param("survivorId") UUID survivorId);
}
