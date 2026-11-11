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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderParticipation;
import de.greluc.krt.profit.basetool.backend.joborder.api.MoneyCommitmentChangeDto.Reason;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyService;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Applies only an explicit new provision to its original commitment occurrence. */
@Service
@RequiredArgsConstructor
class JobOrderMoneyCommitmentCredit {
  private final JobOrderMoneyCommitmentRepository commitments;
  private final JobOrderMoneyCommitments history;
  private final MaterialClaimAuthority authority;
  private final ProfitHierarchyService hierarchy;
  private final JobOrderParticipationRepository participations;

  void assertAttributionAuthority(JobOrder order, UUID commitmentId) {
    JobOrderMoneyCommitment commitment = requireOccurrence(order, commitmentId);
    if (!authority.canCorrect(order, commitment.getUnit().getId())) {
      throw new AccessDeniedException("orders.money.commitment.forbidden");
    }
  }

  void assertCurrentTarget(JobOrder order, UUID commitmentId) {
    current(order, commitmentId);
  }

  /**
   * Credits at most the open amount and records the original operation in the same transaction.
   *
   * @param order the order held by the accepted money operation
   * @param operationId the stable identity of the accepted operation
   * @param commitmentId the explicitly selected commitment occurrence
   * @param amount the positive amount of genuine new provision
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void recordFreshProvision(
      JobOrder order, UUID operationId, UUID commitmentId, BigDecimal amount) {
    if (amount.signum() <= 0) {
      throw new IllegalArgumentException("Provision credit requires a positive amount.");
    }
    JobOrderMoneyCommitment commitment = current(order, commitmentId);
    BigDecimal before = commitment.getOutstanding();
    BigDecimal remaining = before.subtract(amount.min(before));
    Reason reason = Reason.CREDITED;
    if (remaining.signum() == 0) {
      commitment.end();
      reason = Reason.FULFILLED;
    } else {
      commitment.revise(remaining);
    }
    history.record(commitment, reason, before, operationId);
  }

  private JobOrderMoneyCommitment requireOccurrence(JobOrder order, UUID commitmentId) {
    return commitments
        .findByIdAndOrderId(commitmentId, order.getId())
        .orElseThrow(() -> new BusinessConflictException("orders.money.commitment.changed"));
  }

  private JobOrderMoneyCommitment current(JobOrder order, UUID commitmentId) {
    JobOrderMoneyCommitment commitment = requireOccurrence(order, commitmentId);
    UUID unitId = commitment.getUnit().getId();
    boolean responsible = unitId.equals(OrgUnit.idOf(order.getResponsibleOrgUnit()));
    if (!order.getStatus().isActive()
        || commitment.getEndedAt() != null
        || (!responsible && !isSupporting(order, unitId))) {
      throw new BusinessConflictException("orders.money.commitment.changed");
    }
    return commitment;
  }

  private boolean isSupporting(JobOrder order, UUID unitId) {
    return hierarchy.isEligible(unitId)
        && participations
            .findByJobOrderIdAndClaimingOrgUnitId(order.getId(), unitId)
            .filter(JobOrderParticipation::isActive)
            .isPresent();
  }
}
