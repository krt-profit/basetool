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
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Reads and stores order-local requester designations. */
interface JobOrderMoneyRecipientRepository extends JpaRepository<JobOrderMoneyRecipient, UUID> {

  Optional<JobOrderMoneyRecipient> findByOrderIdAndId(UUID orderId, UUID id);

  List<JobOrderMoneyRecipient> findAllByOrderIdOrderById(UUID orderId);

  @Query(
      """
      SELECT coalesce(resolved.userId, canonical.userId, holder.userId)
        FROM JobOrderMoneyRecipient r
        JOIN JobOrderMoneyRecipient resolved ON resolved.id = coalesce(r.correctedIntoId, r.id)
        LEFT JOIN JobOrderMoneyHolder holder ON holder.id = resolved.holderId
        LEFT JOIN JobOrderMoneyHolder canonical ON canonical.id = holder.mergedIntoId
      WHERE r.orderId = :orderId AND r.id = :id
      """)
  Optional<UUID> accountFor(UUID orderId, UUID id);

  @Modifying
  @Query(
      """
      UPDATE JobOrderMoneyRecipient r SET r.correctedIntoId = :target
      WHERE r.orderId = :orderId AND r.correctedIntoId = :source
      """)
  void redirectCorrections(UUID orderId, UUID source, UUID target);

  @Modifying
  @Query(
      """
      UPDATE JobOrderMoneyRecipient r SET r.userId = :target
      WHERE r.userId = :source AND r.orderId IN :orderIds AND r.correctedIntoId IS NULL
      """)
  int moveAccounts(UUID source, UUID target, List<UUID> orderIds);

  @Modifying
  @Query(
      """
      UPDATE JobOrderMoneyRecipient r SET r.userId = NULL, r.active = false
      WHERE r.userId = :userId AND r.orderId IN :orderIds
      """)
  void unlinkAccount(UUID userId, List<UUID> orderIds);

  @Modifying
  @Query(
      value =
          """
          UPDATE job_order_money_recipient SET accountless_name = :sentinel, active = false
          WHERE order_id IN (:orderIds) AND lower(accountless_name) IN (
              SELECT lower(name) FROM unnest(CAST(:spellings AS text[])) AS names(name))
            AND accountless_name IS DISTINCT FROM :sentinel
          """,
      nativeQuery = true)
  int anonymiseNames(String[] spellings, List<UUID> orderIds, String sentinel);

  @Modifying
  @Query(
      value =
          """
          UPDATE job_order_money_recipient r SET active = false
          FROM job_order_money_holder h
          JOIN job_order_money_holder canonical ON canonical.id = COALESCE(h.merged_into_id, h.id)
          WHERE r.holder_id = h.id AND r.order_id IN (:orderIds) AND r.active
            AND (canonical.user_id = :userId OR (canonical.user_id IS NULL AND
              lower(canonical.accountless_name) IN (
                SELECT lower(name) FROM unnest(CAST(:spellings AS text[])) AS names(name))))
          """,
      nativeQuery = true)
  int retireErasedHolderRecipients(UUID userId, String[] spellings, List<UUID> orderIds);
}
