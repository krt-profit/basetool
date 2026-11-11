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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderAuditLabel;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import de.greluc.krt.profit.basetool.backend.joborder.api.MoneyCommitmentChangeDto.Reason;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Records the order's commitment history and transactional change effects. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
class JobOrderMoneyCommitments {
  private static final int COMMITMENT_BATCH_SIZE = 200;
  private final JobOrderMoneyCommitmentRepository commitments;
  private final JobOrderMoneyCommitmentChangeRepository changes;
  private final AuditRecorder audit;
  private final JobOrderChangeEffects effects;

  /**
   * Ends all current commitments on an order leaving active work.
   *
   * @param order the order held by its lifecycle mutation
   */
  public void endOpenForOrder(JobOrder order) {
    if (order.getType() == JobOrderType.AUEC) {
      commitments
          .findByOrderIdAndEndedAtIsNullOrderById(order.getId())
          .forEach(commitment -> end(commitment, Reason.ORDER_ENDED));
    }
  }

  /**
   * Ends the current commitment when its Staffel withdraws its participation.
   *
   * @param order the locked order
   * @param unitId the withdrawing Staffel
   */
  public void endForParticipation(JobOrder order, UUID unitId) {
    if (order.getType() == JobOrderType.AUEC) {
      commitments
          .findByOrderIdAndUnitIdAndEndedAtIsNull(order.getId(), unitId)
          .ifPresent(commitment -> end(commitment, Reason.PARTICIPATION_ENDED));
    }
  }

  /**
   * Ends current commitments in units that lost eligibility under the hierarchy lock.
   *
   * @param ineligibleUnits affected units outside active Profit
   * @return the number of ended commitments
   */
  public int endForUnits(Set<UUID> ineligibleUnits) {
    if (ineligibleUnits.isEmpty()) {
      return 0;
    }
    int ended = 0;
    while (true) {
      var batch =
          commitments.findByUnitIdInAndEndedAtIsNullOrderById(
              ineligibleUnits, PageRequest.of(0, COMMITMENT_BATCH_SIZE));
      if (batch.isEmpty()) {
        return ended;
      }
      batch.forEach(commitment -> end(commitment, Reason.UNIT_INELIGIBLE));
      commitments.flush();
      ended += batch.size();
    }
  }

  /**
   * Ends unavailable commitments in one startup reconciliation candidate.
   *
   * @param orderId the order being reconciled
   * @param eligibleUnits units still eligible under the locked hierarchy
   * @return the number of ended commitments
   */
  public int endUnavailableForOrder(UUID orderId, Set<UUID> eligibleUnits) {
    int ended = 0;
    for (var commitment : commitments.findByOrderIdAndEndedAtIsNullOrderById(orderId)) {
      if (!eligibleUnits.contains(commitment.getUnit().getId())) {
        end(commitment, Reason.UNIT_INELIGIBLE);
        ended++;
      } else if (!commitment.getOrder().getStatus().isActive()) {
        end(commitment, Reason.ORDER_ENDED);
        ended++;
      }
    }
    return ended;
  }

  void end(JobOrderMoneyCommitment commitment, Reason reason) {
    BigDecimal before = commitment.getOutstanding();
    commitment.end();
    record(commitment, reason, before, null);
  }

  void record(
      JobOrderMoneyCommitment commitment,
      Reason reason,
      BigDecimal before,
      @Nullable UUID operationId) {
    changes.save(new JobOrderMoneyCommitmentChange(commitment, reason, before, operationId));
    JobOrder order = commitment.getOrder();
    UUID unitId = commitment.getUnit().getId();
    AuditDetails details =
        AuditDetails.of("commitment", commitment.getId())
            .with("unit", unitId)
            .with("reason", reason.name())
            .with("before", before)
            .with("outstanding", commitment.getOutstanding());
    if (operationId != null) {
      details = details.with("operationId", operationId);
    }
    audit.record(
        AuditEventType.JOB_ORDER_MONEY_COMMITMENT_CHANGED,
        order.getId(),
        JobOrderAuditLabel.of(order),
        null,
        details);
    effects.moneyCommitmentChanged(order, unitId);
  }
}
