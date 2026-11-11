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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Reads linked money attempts owned by an aUEC order. */
interface JobOrderLinkedMoneyAttemptRepository
    extends JpaRepository<JobOrderLinkedMoneyAttempt, UUID> {

  Optional<JobOrderLinkedMoneyAttempt> findByOrderIdAndOperationIdAndActiveTrue(
      UUID orderId, UUID operationId);

  boolean existsByOrderIdAndOperationIdAndActiveTrue(UUID orderId, UUID operationId);

  List<JobOrderLinkedMoneyAttempt> findAllByOrderIdAndActiveTrue(UUID orderId);

  List<JobOrderLinkedMoneyAttempt> findAllByOrderIdOrderBySubmittedAtAscIdAsc(UUID orderId);

  @Query(
      "SELECT a.orderId FROM JobOrderLinkedMoneyAttempt a WHERE a.id = :id AND a.purposeRef ="
          + " :purposeRef")
  Optional<UUID> orderIdFor(UUID id, UUID purposeRef);

  @Query(
      """
      SELECT a.id FROM JobOrderLinkedMoneyAttempt a
      WHERE a.orderId = :orderId AND a.operationId = :operationId
        AND a.revision = (SELECT max(r.revision) FROM JobOrderLinkedMoneyAttempt r
          WHERE r.orderId = :orderId AND r.operationId = :operationId)
      """)
  Optional<UUID> latestAttemptId(UUID orderId, UUID operationId);

  @Query(
      """
      SELECT a FROM JobOrderLinkedMoneyAttempt a
      WHERE a.orderId = :orderId AND a.active = true AND a.actualPayment = false
        AND a.state = de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderLinkedMoneyState.WAITING
        AND a.kind NOT IN (
          de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyKind.REQUESTER_RETURN,
          de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyKind.SETTLEMENT)
      ORDER BY a.requestId
      """)
  List<JobOrderLinkedMoneyAttempt> unexecutedWaiting(UUID orderId);

  @Query(
      """
      SELECT a.kind AS kind, a.amount AS amount, a.actualPayment AS actualPayment
      FROM JobOrderLinkedMoneyAttempt a WHERE a.id = :id
      """)
  Optional<PaymentImpact> impactOf(UUID id);

  /** Stable effect terms used to plan the order's priority guard. */
  interface PaymentImpact {
    /**
     * Identifies the monetary effect.
     *
     * @return the frozen order purpose
     */
    JobOrderMoneyKind getKind();

    /**
     * Returns the principal which could change net delivery.
     *
     * @return the exact submitted amount
     */
    java.math.BigDecimal getAmount();

    /**
     * Tells whether reversing the Bank record can reverse the order effect.
     *
     * @return whether a real player payment was reported
     */
    boolean getActualPayment();
  }

  long countByActiveTrueAndState(JobOrderLinkedMoneyState state);

  @Query(
      """
      SELECT min(a.submittedAt) FROM JobOrderLinkedMoneyAttempt a
      WHERE a.active = true
        AND a.state = de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderLinkedMoneyState.WAITING
      """)
  Optional<Instant> oldestWaiting();

  @Query(
      value =
          """
          SELECT count(*) FROM job_order_linked_money_attempt a
          LEFT JOIN job_order_money_operation m
            ON m.order_id = a.order_id AND m.operation_id = a.operation_id
          WHERE a.active AND (a.state = 'SUBMITTING'
            OR (a.state IN ('POSTED', 'AUTHORISED', 'REVERSED', 'CLARIFICATION')) <> (m.id IS NOT NULL)
            OR (m.id IS NOT NULL AND (NOT m.bank_handling OR a.kind <> m.kind OR a.amount <> m.amount
              OR a.holder_id IS DISTINCT FROM m.holder_id
              OR a.account_holding_id IS DISTINCT FROM m.account_holding_id)))
          """,
      nativeQuery = true)
  long countFinancialContradictions();

  @Query(
      value =
          """
          SELECT count(*) FROM job_order_linked_money_attempt a
          WHERE a.active AND a.state = 'CLARIFICATION'
            AND NOT EXISTS (SELECT 1 FROM job_order_money_correction_entry c
              WHERE c.order_id = a.order_id AND c.original_operation_id = a.operation_id)
          """,
      nativeQuery = true)
  long countUnresolvedClarifications();

  @Query(
      value =
          """
          SELECT EXISTS (SELECT 1 FROM job_order_linked_money_attempt
          WHERE order_id = :orderId AND active AND actual_payment
            AND state IN ('WAITING', 'REJECTED', 'CANCELLED'))
          """,
      nativeQuery = true)
  boolean hasUnresolvedActualPayment(UUID orderId);

  @Query(
      value =
          """
          SELECT actor_id AS user_id FROM job_order_linked_money_attempt WHERE id = :id
          UNION
          SELECT selected_user_id FROM job_order_linked_money_attempt WHERE id = :id
          UNION
          SELECT counterparty_user_id FROM job_order_linked_money_attempt WHERE id = :id
          UNION
          SELECT h.user_id FROM job_order_linked_money_attempt a
          JOIN job_order_money_holder h ON h.id IN (a.holder_id, a.destination_holder_id)
          WHERE a.id = :id
          UNION
          SELECT COALESCE(r.user_id, h.user_id) FROM job_order_linked_money_attempt a
          JOIN job_order_money_recipient r ON r.id = a.recipient_id
          LEFT JOIN job_order_money_holder h ON h.id = r.holder_id
          WHERE a.id = :id
          """,
      nativeQuery = true)
  List<UUID> identityAccounts(UUID id);
}
