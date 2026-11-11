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

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Loads accepted money facts and outcomes scoped to their order. */
interface JobOrderMoneyOperationRepository extends JpaRepository<JobOrderMoneyOperation, UUID> {

  Optional<JobOrderMoneyOperation> findByOrderIdAndOperationId(UUID orderId, UUID operationId);

  List<JobOrderMoneyOperation> findAllByOrderIdOrderByRecordedAtAscIdAsc(UUID orderId);

  @Modifying
  @Query(
      value =
          """
          UPDATE job_order_money_operation
          SET selected_accountless_name = NULL
          WHERE order_id IN (:orderIds) AND lower(selected_accountless_name) IN
                (SELECT lower(name) FROM unnest(CAST(:spellings AS text[])) AS names(name))
          """,
      nativeQuery = true)
  int anonymiseSubmittedNames(String[] spellings, List<UUID> orderIds);

  @Query(
      """
      SELECT o.amount FROM JobOrderMoneyOperation o
        JOIN JobOrderMoneyHolder h ON h.id = o.holderId
      WHERE h.id = :holderId OR h.mergedIntoId = :holderId
      """)
  Stream<BigDecimal> amountsForHolder(UUID holderId);
}
