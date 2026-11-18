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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterial;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Repoints the job-order module's material references during a material merge (REQ-DATA-046). */
interface JobOrderMaterialReassignmentRepository extends Repository<JobOrderMaterial, UUID> {

  /**
   * Lists the job orders a row of the merged material belongs to, whose rooms hear of the move.
   *
   * @param retiredId the material merged away
   * @return the distinct orders
   */
  @Query(
      value =
          """
          SELECT job_order_id FROM job_order_material WHERE material_id = :retiredId
          UNION SELECT i.job_order_id FROM job_order_item_material m
            JOIN job_order_item i ON i.id = m.job_order_item_id WHERE m.material_id = :retiredId
          UNION SELECT h.job_order_id FROM job_order_handover_item hi
            JOIN job_order_handover h ON h.id = hi.job_order_handover_id
            WHERE hi.material_id = :retiredId
          UNION SELECT job_order_id FROM material_claim WHERE material_id = :retiredId
          UNION SELECT job_order_id FROM material_provision_credit WHERE material_id = :retiredId
          """,
      nativeQuery = true)
  @NotNull
  List<UUID> orders(@Param("retiredId") @NotNull UUID retiredId);

  /**
   * Moves the material lines of the merged material and bumps their versions.
   *
   * @param retiredId the material merged away
   * @param survivorId the material that stays
   * @return the moved rows
   */
  @Query(
      value =
          """
          WITH moved AS (
            UPDATE job_order_material
            SET material_id = :survivorId, version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE material_id = :retiredId
            RETURNING id
          ) SELECT id FROM moved
          """,
      nativeQuery = true)
  @NotNull
  List<UUID> lines(
      @Param("retiredId") @NotNull UUID retiredId, @Param("survivorId") @NotNull UUID survivorId);

  /**
   * Moves the item-order material lines of the merged material and bumps their versions.
   *
   * @param retiredId the material merged away
   * @param survivorId the material that stays
   * @return the moved rows
   */
  @Query(
      value =
          """
          WITH moved AS (
            UPDATE job_order_item_material
            SET material_id = :survivorId, version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE material_id = :retiredId
            RETURNING id
          ) SELECT id FROM moved
          """,
      nativeQuery = true)
  @NotNull
  List<UUID> itemLines(
      @Param("retiredId") @NotNull UUID retiredId, @Param("survivorId") @NotNull UUID survivorId);

  /**
   * Moves the handover lines of the merged material and bumps their versions.
   *
   * @param retiredId the material merged away
   * @param survivorId the material that stays
   * @return the moved rows
   */
  @Query(
      value =
          """
          WITH moved AS (
            UPDATE job_order_handover_item
            SET material_id = :survivorId, version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE material_id = :retiredId
            RETURNING id
          ) SELECT id FROM moved
          """,
      nativeQuery = true)
  @NotNull
  List<UUID> handovers(
      @Param("retiredId") @NotNull UUID retiredId, @Param("survivorId") @NotNull UUID survivorId);

  /**
   * Moves the claims on the merged material and bumps their versions.
   *
   * @param retiredId the material merged away
   * @param survivorId the material that stays
   * @return the moved rows
   */
  @Query(
      value =
          """
          WITH moved AS (
            UPDATE material_claim
            SET material_id = :survivorId, version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE material_id = :retiredId
            RETURNING id
          ) SELECT id FROM moved
          """,
      nativeQuery = true)
  @NotNull
  List<UUID> claims(
      @Param("retiredId") @NotNull UUID retiredId, @Param("survivorId") @NotNull UUID survivorId);

  /**
   * Moves the provision credits of the merged material and bumps their versions.
   *
   * @param retiredId the material merged away
   * @param survivorId the material that stays
   * @return the moved rows
   */
  @Query(
      value =
          """
          WITH moved AS (
            UPDATE material_provision_credit
            SET material_id = :survivorId, version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE material_id = :retiredId
            RETURNING id
          ) SELECT id FROM moved
          """,
      nativeQuery = true)
  @NotNull
  List<UUID> credits(
      @Param("retiredId") @NotNull UUID retiredId, @Param("survivorId") @NotNull UUID survivorId);
}
