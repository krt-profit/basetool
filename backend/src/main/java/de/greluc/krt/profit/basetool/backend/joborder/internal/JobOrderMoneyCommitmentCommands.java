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
import de.greluc.krt.profit.basetool.backend.joborder.api.MoneyCommitmentChangeDto.Reason;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyService;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies a unit's planning changes under the order's current contribution authority. */
@Service
@RequiredArgsConstructor
public class JobOrderMoneyCommitmentCommands {
  private final JobOrderEditor editor;
  private final MaterialClaimAuthority authority;
  private final OrgUnitRepository units;
  private final ProfitHierarchyService hierarchy;
  private final JobOrderParticipationCommands participations;
  private final JobOrderMoneyCommitmentRepository commitments;
  private final JobOrderMoneyCommitments history;

  /**
   * Records or revises the observed unit's outstanding commitment.
   *
   * @param orderId the order receiving the contribution
   * @param unitId the authorized responsible or supporting unit
   * @param input the intended outstanding amount and observed commitment version
   */
  @Transactional
  public void put(UUID orderId, UUID unitId, WriteMoneyCommitmentDto input) {
    BigDecimal amount = WholeAuec.nonnegative(input.amount());
    editor.editMoney(
        orderId,
        order -> {
          apply(order, unitId, input, amount);
          return new JobOrderEditor.MoneyEdit<>(Boolean.TRUE, JobOrderEdit.unchanged(order));
        });
  }

  private void apply(
      JobOrder order, UUID unitId, WriteMoneyCommitmentDto input, BigDecimal amount) {
    if (!authority.canManage(order, unitId)) {
      throw new AccessDeniedException("orders.money.commitment.forbidden");
    }
    if (!order.getStatus().isActive()) {
      throw new BadRequestException("orders.money.commitment.inactive");
    }
    boolean responsible = unitId.equals(OrgUnit.idOf(order.getResponsibleOrgUnit()));
    final OrgUnit unit =
        responsible
            ? order.getResponsibleOrgUnit()
            : units
                .findById(unitId)
                .filter(candidate ->
                    JobOrderSupportService.eligible(order, candidate, hierarchy.eligibleUnitIds()))
                .orElseThrow(() -> new BadRequestException("orders.money.commitment.ineligible"));
    var current = commitments.findByOrderIdAndUnitIdAndEndedAtIsNull(order.getId(), unitId);
    if (!Objects.equals(
            current.map(JobOrderMoneyCommitment::getId).orElse(null), input.commitmentId())
        || (current.isEmpty() && input.version() != null)) {
      throw new BusinessConflictException("orders.money.commitment.changed");
    }
    if (current.isPresent()) {
      var existing = current.orElseThrow();
      OptimisticLock.check(
          existing.getVersion(), input.version(), JobOrderMoneyCommitment.class, existing.getId());
      if (existing.getOutstanding().compareTo(amount) == 0) {
        return;
      }
      if (amount.signum() == 0) {
        history.end(existing, Reason.WITHDRAWN);
        return;
      }
      BigDecimal previous = existing.getOutstanding();
      existing.revise(amount);
      history.record(existing, Reason.REVISED, previous, null);
      return;
    }
    if (amount.signum() == 0) {
      return;
    }
    UUID participationId =
        responsible
            ? null
            : participations
                .enroll(order, unit, JobOrderParticipationCommands.Enrollment.FIRST_PROMISE)
                .getId();
    var commitment =
        commitments.save(new JobOrderMoneyCommitment(order, unit, participationId, amount));
    history.record(commitment, Reason.OPENED, BigDecimal.ZERO, null);
  }
}
