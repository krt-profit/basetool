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
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Stores accepted money facts and reserves refused attempts within their order. */
interface JobOrderMoneyOperationRepository extends JpaRepository<JobOrderMoneyOperation, UUID> {

  Optional<JobOrderMoneyOperation> findByOrderIdAndOperationId(UUID orderId, UUID operationId);

  boolean existsByOrderIdAndOperationId(UUID orderId, UUID operationId);

  List<JobOrderMoneyOperation> findAllByOrderIdAndOperationIdIn(
      UUID orderId, Set<UUID> operationIds);

  List<JobOrderMoneyOperation> findAllByOrderIdOrderByRecordedAtAscIdAsc(UUID orderId);

  @Query(
      value =
          """
          SELECT peer.order_id AS "orderId", peer.operation_id AS "operationId",
                 COALESCE(canonical.user_id, holder.user_id) AS "userId"
          FROM job_order_money_operation original
          JOIN job_order_money_operation peer
            ON peer.order_id = original.peer_order_id
            AND peer.operation_id = original.operation_id
            AND peer.peer_order_id = original.order_id
          JOIN job_order_money_holder holder ON holder.order_id = peer.order_id
            AND holder.id = peer.holder_id
          LEFT JOIN job_order_money_holder canonical ON canonical.order_id = peer.order_id
            AND canonical.id = holder.merged_into_id
          WHERE original.order_id = :orderId AND peer.order_id IN (:peerIds)
            AND original.amount = peer.amount
            AND ((original.kind = 'PURPOSE_INFLOW' AND peer.kind = 'PURPOSE_OUTFLOW')
              OR (original.kind = 'PURPOSE_OUTFLOW' AND peer.kind = 'PURPOSE_INFLOW'))
          """,
      nativeQuery = true)
  List<PairedCorrectionCandidate> pairedCorrectionCandidates(UUID orderId, Set<UUID> peerIds);

  /** Reciprocal original and its current canonical holder account. */
  interface PairedCorrectionCandidate {

    /**
     * Identifies the authorized counterpart order.
     *
     * @return the permitted paired order
     */
    UUID getOrderId();

    /**
     * Identifies the immutable movement common to both legs.
     *
     * @return the logical identity of both originals
     */
    UUID getOperationId();

    /**
     * Resolves the counterpart account through its canonical holder.
     *
     * @return the current linked holder account, if present
     */
    @Nullable
    UUID getUserId();
  }

  @Query(
      value =
          """
          WITH affected AS (
            SELECT holder_id, destination_holder_id, recipient_id
            FROM job_order_money_operation WHERE order_id = :orderId AND operation_id IN (:operations)
          ), affected_recipients AS (
            SELECT COALESCE(corrected_into_id, id) AS id FROM job_order_money_recipient
            WHERE order_id = :orderId AND id IN (SELECT recipient_id FROM affected)
          )
          SELECT COALESCE(current_holder.user_id, h.user_id) FROM job_order_money_holder h
          LEFT JOIN job_order_money_holder current_holder ON current_holder.id = h.merged_into_id
          WHERE h.order_id = :orderId AND h.id IN (
            SELECT holder_id FROM affected
            UNION SELECT destination_holder_id FROM affected
            UNION SELECT r.holder_id FROM job_order_money_recipient r
              WHERE r.id IN (SELECT id FROM affected_recipients)
          ) AND COALESCE(current_holder.user_id, h.user_id) IS NOT NULL
          UNION
          SELECT r.user_id FROM job_order_money_recipient r
            WHERE r.order_id = :orderId AND r.id IN (SELECT id FROM affected_recipients)
              AND r.user_id IS NOT NULL
          """,
      nativeQuery = true)
  Set<UUID> accountsAffectedByCorrection(UUID orderId, Set<UUID> operations);

  @Query(
      value =
          """
          SELECT EXISTS (
              SELECT 1 FROM job_order_money_refused_attempt
              WHERE order_id = :orderId AND operation_id = :operationId
          )
          """,
      nativeQuery = true)
  boolean isRefused(UUID orderId, UUID operationId);

  @Modifying
  @Query(
      value =
          """
          INSERT INTO job_order_money_refused_attempt (order_id, operation_id)
          VALUES (:orderId, :operationId)
          """,
      nativeQuery = true)
  void reserveRefusal(UUID orderId, UUID operationId);

  @Modifying
  @Query(
      value =
          """
          UPDATE job_order_money_operation p
          SET selected_accountless_name = NULL
          WHERE p.order_id IN (:orderIds) AND p.selected_accountless_name IS NOT NULL
            AND (lower(p.selected_accountless_name) IN
                   (SELECT lower(name) FROM unnest(CAST(:spellings AS text[])) AS names(name))
                 OR EXISTS (
                   SELECT 1 FROM job_order_money_holder original
                   JOIN job_order_money_holder linked
                     ON linked.id = COALESCE(original.merged_into_id, original.id)
                   WHERE linked.user_id = :userId
                     AND original.id = CASE
                       WHEN p.kind = 'PROVISION' THEN p.holder_id
                       WHEN p.kind IN ('COLLECTION', 'REQUESTER_RETURN') THEN p.destination_holder_id
                     END
                 ))
          """,
      nativeQuery = true)
  int anonymiseSubmittedNames(UUID userId, String[] spellings, List<UUID> orderIds);
}
