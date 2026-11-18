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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Moves the catalogue's own material references during a material merge (REQ-DATA-046): recipe
 * ingredients and dismantle returns, refinery yields, terminal prices, external aliases,
 * refined-from links and locations. Where both materials hold a row for the same terminal or
 * location, the row of the material that stays wins and the other is deleted.
 */
interface CatalogueMaterialReassignmentRepository extends Repository<Material, UUID> {

  /**
   * Repoints recipe ingredients and bumps their versions.
   *
   * @param retiredId the material merged away
   * @param survivorId the material that stays
   * @return the moved rows
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          UPDATE blueprint_ingredient SET material_id = :survivorId,
            version = version + 1, updated_at = CURRENT_TIMESTAMP
          WHERE material_id = :retiredId
          """,
      nativeQuery = true)
  int ingredients(@Param("retiredId") UUID retiredId, @Param("survivorId") UUID survivorId);

  /**
   * Repoints dismantle returns and bumps their versions.
   *
   * @param retiredId the material merged away
   * @param survivorId the material that stays
   * @return the moved rows
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          UPDATE blueprint_dismantle_return SET material_id = :survivorId,
            version = version + 1, updated_at = CURRENT_TIMESTAMP
          WHERE material_id = :retiredId
          """,
      nativeQuery = true)
  int dismantleReturns(@Param("retiredId") UUID retiredId, @Param("survivorId") UUID survivorId);

  /**
   * Deletes the merged material's refinery yields at the terminals where the material that stays
   * has one.
   *
   * @param retiredId the material merged away
   * @param survivorId the material that stays
   * @return the deleted rows
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          DELETE FROM refinery_yield retired WHERE retired.material_id = :retiredId
          AND EXISTS (SELECT 1 FROM refinery_yield kept WHERE kept.material_id = :survivorId
                      AND kept.terminal_id = retired.terminal_id)
          """,
      nativeQuery = true)
  int dropCollidingYields(@Param("retiredId") UUID retiredId, @Param("survivorId") UUID survivorId);

  /**
   * Repoints the remaining refinery yields and bumps their versions.
   *
   * @param retiredId the material merged away
   * @param survivorId the material that stays
   * @return the moved rows
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          UPDATE refinery_yield SET material_id = :survivorId,
            version = version + 1, updated_at = CURRENT_TIMESTAMP
          WHERE material_id = :retiredId
          """,
      nativeQuery = true)
  int yields(@Param("retiredId") UUID retiredId, @Param("survivorId") UUID survivorId);

  /**
   * Deletes the merged material's prices at the terminals where the material that stays has one.
   *
   * @param retiredId the material merged away
   * @param survivorId the material that stays
   * @return the deleted rows
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          DELETE FROM material_price retired WHERE retired.material_id = :retiredId
          AND EXISTS (SELECT 1 FROM material_price kept WHERE kept.material_id = :survivorId
                      AND kept.terminal_id = retired.terminal_id)
          """,
      nativeQuery = true)
  int dropCollidingPrices(@Param("retiredId") UUID retiredId, @Param("survivorId") UUID survivorId);

  /**
   * Repoints the remaining terminal prices and bumps their versions.
   *
   * @param retiredId the material merged away
   * @param survivorId the material that stays
   * @return the moved rows
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          UPDATE material_price SET material_id = :survivorId,
            version = version + 1, updated_at = CURRENT_TIMESTAMP
          WHERE material_id = :retiredId
          """,
      nativeQuery = true)
  int prices(@Param("retiredId") UUID retiredId, @Param("survivorId") UUID survivorId);

  /**
   * Repoints external name aliases and bumps their versions.
   *
   * @param retiredId the material merged away
   * @param survivorId the material that stays
   * @return the moved rows
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          UPDATE material_external_alias SET material_id = :survivorId,
            version = version + 1, updated_at = CURRENT_TIMESTAMP
          WHERE material_id = :retiredId
          """,
      nativeQuery = true)
  int aliases(@Param("retiredId") UUID retiredId, @Param("survivorId") UUID survivorId);

  /**
   * Repoints the refined-from links of every other material and bumps their versions; the two
   * merged rows are left to the merge, which holds them as managed entities.
   *
   * @param retiredId the material merged away
   * @param survivorId the material that stays
   * @return the moved rows
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          UPDATE material SET refined_material_id = :survivorId,
            version = version + 1, updated_at = CURRENT_TIMESTAMP
          WHERE refined_material_id = :retiredId AND id NOT IN (:retiredId, :survivorId)
          """,
      nativeQuery = true)
  int refinedFrom(@Param("retiredId") UUID retiredId, @Param("survivorId") UUID survivorId);

  /**
   * Deletes the merged material's locations the material that stays already has.
   *
   * @param retiredId the material merged away
   * @param survivorId the material that stays
   * @return the deleted rows
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          DELETE FROM material_occurrence retired WHERE retired.material_id = :retiredId
          AND EXISTS (SELECT 1 FROM material_occurrence kept WHERE kept.material_id = :survivorId
                      AND kept.star_system_id IS NOT DISTINCT FROM retired.star_system_id
                      AND kept.planet_id IS NOT DISTINCT FROM retired.planet_id
                      AND kept.moon_id IS NOT DISTINCT FROM retired.moon_id
                      AND kept.orbit_id IS NOT DISTINCT FROM retired.orbit_id
                      AND kept.poi_id IS NOT DISTINCT FROM retired.poi_id)
          """,
      nativeQuery = true)
  int dropCollidingOccurrences(
      @Param("retiredId") UUID retiredId, @Param("survivorId") UUID survivorId);

  /**
   * Repoints the remaining locations and bumps their versions.
   *
   * @param retiredId the material merged away
   * @param survivorId the material that stays
   * @return the moved rows
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          UPDATE material_occurrence SET material_id = :survivorId,
            version = version + 1, updated_at = CURRENT_TIMESTAMP
          WHERE material_id = :retiredId
          """,
      nativeQuery = true)
  int occurrences(@Param("retiredId") UUID retiredId, @Param("survivorId") UUID survivorId);
}
