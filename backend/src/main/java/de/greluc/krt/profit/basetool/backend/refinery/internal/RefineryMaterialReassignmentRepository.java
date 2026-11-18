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

package de.greluc.krt.profit.basetool.backend.refinery.internal;

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Repoints the refinery goods of a material during a material merge (REQ-DATA-046). */
interface RefineryMaterialReassignmentRepository extends Repository<RefineryGood, UUID> {

  /**
   * Lists the refinery orders whose goods put in or take out the merged material.
   *
   * @param retiredId the material merged away
   * @return the distinct orders
   */
  @Query(
      value =
          "SELECT DISTINCT refinery_order_id FROM refinery_good"
              + " WHERE input_material_id = :retiredId OR output_material_id = :retiredId",
      nativeQuery = true)
  @NotNull
  List<UUID> orders(@Param("retiredId") @NotNull UUID retiredId);

  /**
   * Moves the goods putting in the merged material and bumps their versions.
   *
   * @param retiredId the material merged away
   * @param survivorId the material that stays
   * @return the moved rows
   */
  @Query(
      value =
          """
          WITH moved AS (
            UPDATE refinery_good
            SET input_material_id = :survivorId, version = version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE input_material_id = :retiredId
            RETURNING id
          ) SELECT id FROM moved
          """,
      nativeQuery = true)
  @NotNull
  List<UUID> inputs(
      @Param("retiredId") @NotNull UUID retiredId, @Param("survivorId") @NotNull UUID survivorId);

  /**
   * Moves the goods taking out the merged material and bumps their versions.
   *
   * @param retiredId the material merged away
   * @param survivorId the material that stays
   * @return the moved rows
   */
  @Query(
      value =
          """
          WITH moved AS (
            UPDATE refinery_good
            SET output_material_id = :survivorId, version = version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE output_material_id = :retiredId
            RETURNING id
          ) SELECT id FROM moved
          """,
      nativeQuery = true)
  @NotNull
  List<UUID> outputs(
      @Param("retiredId") @NotNull UUID retiredId, @Param("survivorId") @NotNull UUID survivorId);
}
