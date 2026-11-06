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

package de.greluc.krt.profit.basetool.backend.blueprint.internal;

import de.greluc.krt.profit.basetool.backend.blueprint.api.PersonalBlueprint;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Repoints the blueprint module's catalogue references during an atomic item merge. */
interface BlueprintItemReassignmentRepository extends Repository<PersonalBlueprint, UUID> {

  /**
   * Lists the affected live-sync rooms before their references move.
   *
   * @param retiredId duplicate item
   * @return distinct affected room resource ids
   */
  @Query(
      value =
          "SELECT DISTINCT owner_user_id FROM personal_blueprint WHERE output_item_id = :retiredId",
      nativeQuery = true)
  List<UUID> owners(@Param("retiredId") UUID retiredId);

  /**
   * Moves personal_blueprint references and bumps their optimistic-lock versions.
   *
   * @param retiredId duplicate catalogue row
   * @param survivorId canonical catalogue row
   * @return the affected row ids
   */
  @Query(
      value =
          """
          WITH moved AS (
            UPDATE personal_blueprint
            SET output_item_id = :survivorId, version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE output_item_id = :retiredId
            RETURNING id
          ) SELECT id FROM moved
          """,
      nativeQuery = true)
  @NotNull
  List<UUID> personalBlueprints(
      @Param("retiredId") @NotNull UUID retiredId, @Param("survivorId") @NotNull UUID survivorId);

  /**
   * Moves default_blueprint references and bumps their optimistic-lock versions.
   *
   * @param retiredId duplicate catalogue row
   * @param survivorId canonical catalogue row
   * @return the affected row ids
   */
  @Query(
      value =
          """
          WITH moved AS (
            UPDATE default_blueprint
            SET output_item_id = :survivorId, version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE output_item_id = :retiredId
            RETURNING id
          ) SELECT id FROM moved
          """,
      nativeQuery = true)
  @NotNull
  List<UUID> defaultBlueprints(
      @Param("retiredId") @NotNull UUID retiredId, @Param("survivorId") @NotNull UUID survivorId);

  /**
   * Moves blueprint_external_alias references and bumps their optimistic-lock versions.
   *
   * @param retiredId duplicate catalogue row
   * @param survivorId canonical catalogue row
   * @return the affected row ids
   */
  @Query(
      value =
          """
          WITH moved AS (
            UPDATE blueprint_external_alias
            SET output_item_id = :survivorId, version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE output_item_id = :retiredId
            RETURNING id
          ) SELECT id FROM moved
          """,
      nativeQuery = true)
  @NotNull
  List<UUID> externalAliases(
      @Param("retiredId") @NotNull UUID retiredId, @Param("survivorId") @NotNull UUID survivorId);
}
