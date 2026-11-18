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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Repoints the supply module's material references during a material merge (REQ-DATA-046). */
interface SupplyMaterialReassignmentRepository extends Repository<SupplyNeed, UUID> {

  /**
   * Lists the org units receiving a donation of the merged material, whose rooms hear of the move.
   *
   * @param retiredId the material merged away
   * @return the distinct receiving org units
   */
  @Query(
      value =
          "SELECT DISTINCT receiving_org_unit_id FROM supply_donation WHERE material_id ="
              + " :retiredId",
      nativeQuery = true)
  @NotNull
  List<UUID> receivingUnits(@Param("retiredId") @NotNull UUID retiredId);

  /**
   * Moves the needs of the merged material and bumps their versions.
   *
   * @param retiredId the material merged away
   * @param survivorId the material that stays
   * @return the moved rows
   */
  @Query(
      value =
          """
          WITH moved AS (
            UPDATE supply_need
            SET material_id = :survivorId, version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE material_id = :retiredId
            RETURNING id
          ) SELECT id FROM moved
          """,
      nativeQuery = true)
  @NotNull
  List<UUID> needs(
      @Param("retiredId") @NotNull UUID retiredId, @Param("survivorId") @NotNull UUID survivorId);

  /**
   * Moves the donations of the merged material and bumps their versions.
   *
   * @param retiredId the material merged away
   * @param survivorId the material that stays
   * @return the moved rows
   */
  @Query(
      value =
          """
          WITH moved AS (
            UPDATE supply_donation
            SET material_id = :survivorId, version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE material_id = :retiredId
            RETURNING id
          ) SELECT id FROM moved
          """,
      nativeQuery = true)
  @NotNull
  List<UUID> donations(
      @Param("retiredId") @NotNull UUID retiredId, @Param("survivorId") @NotNull UUID survivorId);
}
