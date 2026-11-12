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

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Stores participant history and redacts its retained accountless names. */
interface JobOrderMoneyHolderChangeRepository
    extends JpaRepository<JobOrderMoneyHolderChange, UUID> {

  List<JobOrderMoneyHolderChange> findAllByOrderIdOrderByRecordedAtAscIdAsc(UUID orderId);

  @Modifying
  @Query(
      value =
          """
          WITH spellings AS (
            SELECT lower(name) AS name FROM unnest(CAST(:spellings AS text[])) AS names(name)
          ), affected AS (
            SELECT c.id,
                   c.previous_name IS NOT NULL AND c.previous_name <> :sentinel
                     AND (linked.user_id = :userId OR lower(c.previous_name) IN
                          (SELECT name FROM spellings)) AS prior,
                   c.name IS NOT NULL AND c.name <> :sentinel
                     AND (linked.user_id = :userId OR lower(c.name) IN
                          (SELECT name FROM spellings)) AS current
            FROM job_order_money_holder_change c
            JOIN job_order_money_holder original ON original.id = c.holder_id
            JOIN job_order_money_holder linked ON linked.id = COALESCE(original.merged_into_id, original.id)
            WHERE c.order_id IN (:orderIds)
          )
          UPDATE job_order_money_holder_change c
          SET previous_name = CASE WHEN a.prior THEN :sentinel ELSE c.previous_name END,
              name = CASE WHEN a.current THEN :sentinel ELSE c.name END
          FROM affected a WHERE c.id = a.id AND (a.prior OR a.current)
          """,
      nativeQuery = true)
  int anonymiseNames(UUID userId, String[] spellings, List<UUID> orderIds, String sentinel);
}
