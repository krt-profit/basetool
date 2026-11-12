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
import de.greluc.krt.profit.basetool.backend.inventory.api.OrderMaterialScope;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderAuditLabel;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderParticipation;
import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialContributionChange;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Applies changes to one durable order and Staffel relationship under its coordination lock. */
@Service
@RequiredArgsConstructor
class JobOrderParticipationCommands {
  private final JobOrderParticipationRepository participations;
  private final MaterialClaimRepository claims;
  private final MaterialPromiseCoordinator materialPromises;
  private final JobOrderMoneyCommitments moneyCommitments;
  private final AuditRecorder audit;
  private final JobOrderChangeEffects effects;

  /** The action that starts a Staffel participation. */
  enum Enrollment {
    EXPLICIT,
    FIRST_PROMISE
  }

  /**
   * Starts support or returns the same existing active participation.
   *
   * @param order the authorized order with its affected material resources held
   * @param staffel the authorized eligible supporting Staffel
   * @param action whether support is requested explicitly or starts with the first promise
   * @return the active participation shared with its material promises
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public JobOrderParticipation enroll(JobOrder order, OrgUnit staffel, Enrollment action) {
    materialPromises.lockParticipation(order.getId(), staffel.getId());
    var participation =
        participations
            .findByJobOrderIdAndClaimingOrgUnitId(order.getId(), staffel.getId())
            .orElseGet(() -> {
              var fresh = new JobOrderParticipation();
              fresh.setJobOrder(order);
              fresh.setClaimingOrgUnit(staffel);
              return fresh;
            });
    if (participation.isActive()) {
      return participation;
    }
    participation.setActive(true);
    JobOrderParticipation active =
        participation.isNew() ? participations.save(participation) : participation;
    audit.record(
        AuditEventType.JOB_ORDER_PARTICIPATION_ENROLLED,
        order.getId(),
        JobOrderAuditLabel.of(order),
        null,
        AuditDetails.of("participation", active.getId())
            .with("claimingOrgUnit", staffel.getId())
            .with("reason", action.name()));
    if (action == Enrollment.EXPLICIT) {
      effects.participationChanged(
          order, staffel.getId(), JobOrderChangeEffects.ParticipationChange.ENROLLED);
    }
    return active;
  }

  /**
   * Removes a new responsible Staffel from active support while retaining its contribution lines.
   *
   * @param order the order carrying the new responsibility
   * @param target the new responsible unit
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void becomeResponsible(JobOrder order, OrgUnit target) {
    if (target.getKind() != OrgUnitKind.SQUADRON) {
      return;
    }
    materialPromises.lockParticipation(order.getId(), target.getId());
    participations
        .findByJobOrderIdAndClaimingOrgUnitId(order.getId(), target.getId())
        .filter(JobOrderParticipation::isActive)
        .ifPresent(row -> end(row, JobOrderChangeEffects.ParticipationChange.BECAME_RESPONSIBLE));
  }

  /**
   * Withdraws the observed active support and ends its open promises in the same transaction.
   *
   * @param order the order with complete material resources held
   * @param unitId the authorized supporting Staffel
   * @param expectedVersion the observed participation version
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void withdraw(JobOrder order, UUID unitId, Long expectedVersion) {
    materialPromises.lockParticipation(order.getId(), unitId);
    var row =
        Entities.require(
            participations.findByJobOrderIdAndClaimingOrgUnitId(order.getId(), unitId),
            () -> "Participation not found for Staffel " + unitId);
    OptimisticLock.checkRequired(
        row.getVersion(), expectedVersion, JobOrderParticipation.class, row.getId());
    if (unitId.equals(order.getResponsibleOrgUnit().getId())) {
      throw new BadRequestException("orders.claim.claimingUnit.ineligible");
    }
    if (!row.isActive()) {
      return;
    }
    var open = claims.findByParticipationIdOrderById(row.getId());
    materialPromises.requireLocked(
        open.stream()
            .map(claim -> new OrderMaterialScope(order.getId(), claim.getMaterial().getId()))
            .distinct()
            .toList());
    open.forEach(
        claim -> materialPromises.end(claim, MaterialContributionChange.Reason.PROMISE_WITHDRAWN));
    moneyCommitments.endForParticipation(order, unitId);
    end(row, JobOrderChangeEffects.ParticipationChange.WITHDRAWN);
  }

  private void end(JobOrderParticipation row, JobOrderChangeEffects.ParticipationChange reason) {
    row.setActive(false);
    JobOrder order = row.getJobOrder();
    var unitId = row.getClaimingOrgUnit().getId();
    audit.record(
        AuditEventType.JOB_ORDER_PARTICIPATION_ENDED,
        order.getId(),
        JobOrderAuditLabel.of(order),
        null,
        AuditDetails.of("participation", row.getId())
            .with("claimingOrgUnit", unitId)
            .with("reason", reason.name()));
    effects.participationChanged(order, unitId, reason);
  }
}
