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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItem;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Repoints the joborder module's catalogue references during an atomic item merge. */
interface JobOrderItemReassignmentRepository extends Repository<JobOrderItem, UUID> {

  /**
   * Lists the affected live-sync rooms before their references move.
   *
   * @param retiredId duplicate item
   * @return distinct affected room resource ids
   */
  @Query(
      value = "SELECT DISTINCT job_order_id FROM job_order_item WHERE game_item_id = :retiredId",
      nativeQuery = true)
  List<UUID> orders(@Param("retiredId") UUID retiredId);

  /**
   * Moves job_order_item references and bumps their optimistic-lock versions.
   *
   * @param retiredId duplicate catalogue row
   * @param survivorId canonical catalogue row
   * @return the affected row ids
   */
  @Query(
      value =
          """
          WITH moved AS (
            UPDATE job_order_item
            SET game_item_id = :survivorId, version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE game_item_id = :retiredId
            RETURNING id
          ) SELECT id FROM moved
          """,
      nativeQuery = true)
  @NotNull
  List<UUID> orderItems(
      @Param("retiredId") @NotNull UUID retiredId, @Param("survivorId") @NotNull UUID survivorId);
}
