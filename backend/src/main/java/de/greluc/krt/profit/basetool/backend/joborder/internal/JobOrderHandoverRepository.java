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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderHandover;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data repository for Job Order Handover. */
@Repository
public interface JobOrderHandoverRepository extends JpaRepository<JobOrderHandover, UUID> {
  /** Derived Spring-Data query - returns entities matching {@code JobOrderId}. */
  List<JobOrderHandover> findByJobOrderId(UUID jobOrderId);

  /**
   * {@code true} iff at least one material handover exists for the given job order. Backs the
   * whole-order delivery freeze of the requester-edit gate (REQ-ORDERS-023): a requesting owner may
   * only edit an order that has not yet had any delivery recorded.
   *
   * @param jobOrderId the owning job order id; never {@code null}.
   * @return {@code true} iff any {@link JobOrderHandover} references the order.
   */
  boolean existsByJobOrderId(UUID jobOrderId);

  /**
   * Replaces a {@code recipient_handle} matching {@code handle} case-insensitively with the erasure
   * sentinel (REQ-SEC-062).
   *
   * <p>The column is free text with no user id, so it is matched on text alone and may over-match;
   * the admin reviews the Personensuche hits before granting.
   *
   * @param handle the recipient spelling to erase; compared case-insensitively
   * @param sentinel {@code HandleAnonymisation#SENTINEL}
   * @return the number of rows rewritten
   */
  @Modifying
  @Query(
      """
      UPDATE JobOrderHandover h SET h.recipientHandle = :sentinel
      WHERE lower(h.recipientHandle) = lower(:handle)
      AND h.recipientHandle <> :sentinel
      """)
  int anonymiseRecipientHandle(@Param("handle") String handle, @Param("sentinel") String sentinel);

  /**
   * Replaces the handing-over handle snapshot of the given member with the erasure sentinel
   * (REQ-SEC-062), matched by {@code handing_over_user_id}.
   *
   * @param userId the member whose snapshots are erased
   * @param sentinel {@code HandleAnonymisation#SENTINEL}
   * @return the number of rows rewritten
   */
  @Modifying
  @Query(
      value =
          """
          UPDATE job_order_handover SET handing_over_handle = :sentinel
          WHERE handing_over_user_id = :userId AND handing_over_handle <> :sentinel
          """,
      nativeQuery = true)
  int anonymiseHandingOverHandle(@Param("userId") UUID userId, @Param("sentinel") String sentinel);

  /**
   * Replaces the recipient and the handing-over handle of every handover of the given orders, for
   * the name retention (REQ-DATA-047); a handover that names no handing-over member keeps none.
   *
   * @param orderIds the locked orders
   * @param sentinel {@code HandleAnonymisation#SENTINEL}
   * @return the number of handovers changed
   */
  @Modifying
  @Query(
      value =
          """
          UPDATE job_order_handover SET recipient_handle = :sentinel,
            handing_over_handle = CASE WHEN handing_over_handle IS NULL THEN NULL ELSE :sentinel END
          WHERE job_order_id IN (:orderIds)
            AND (recipient_handle <> :sentinel OR handing_over_handle <> :sentinel)
          """,
      nativeQuery = true)
  int clearNames(@Param("orderIds") List<UUID> orderIds, @Param("sentinel") String sentinel);
}
