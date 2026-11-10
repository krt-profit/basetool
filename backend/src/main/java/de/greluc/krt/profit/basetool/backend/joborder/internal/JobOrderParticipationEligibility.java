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
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderAuditLabel;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderParticipation;
import de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Ends ineligible active support independently of outstanding material promises. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class JobOrderParticipationEligibility {
  private static final int PARTICIPATION_BATCH_SIZE = 200;
  private final JobOrderParticipationRepository participations;
  private final ProfitHierarchyService hierarchy;
  private final AuditRecorder audit;
  private final JobOrderChangeEffects effects;

  /**
   * Ends current support in changed units that no longer belong to active Profit.
   *
   * @param affectedUnitIds units whose eligibility changed under the hierarchy lock
   * @return the number of active participations ended
   */
  public int endForUnits(@NotNull Set<UUID> affectedUnitIds) {
    Set<UUID> eligible = hierarchy.eligibleUnitIds();
    Set<UUID> ineligible =
        affectedUnitIds.stream()
            .filter(id -> !eligible.contains(id))
            .collect(Collectors.toUnmodifiableSet());
    if (ineligible.isEmpty()) {
      return 0;
    }
    return end(() ->
        participations.findByActiveTrueAndClaimingOrgUnitIdInOrderById(
            ineligible, PageRequest.of(0, PARTICIPATION_BATCH_SIZE)));
  }

  /**
   * Ends one existing order's active support outside the current eligible unit set.
   *
   * @param orderId the order inspected during startup maintenance
   * @return the number of active participations ended
   */
  public int endForOrder(@NotNull UUID orderId) {
    Set<UUID> eligible = hierarchy.eligibleUnitIds();
    return end(() ->
        participations.findIneligibleActiveByOrder(
            orderId, eligible, PageRequest.of(0, PARTICIPATION_BATCH_SIZE)));
  }

  private int end(@NotNull Supplier<List<JobOrderParticipation>> nextBatch) {
    int ended = 0;
    while (true) {
      List<JobOrderParticipation> batch = nextBatch.get();
      if (batch.isEmpty()) {
        return ended;
      }
      for (JobOrderParticipation participation : batch) {
        participation.setActive(false);
        var order = participation.getJobOrder();
        UUID unitId = participation.getClaimingOrgUnit().getId();
        audit.record(
            AuditEventType.JOB_ORDER_PARTICIPATION_ENDED,
            order.getId(),
            JobOrderAuditLabel.of(order),
            null,
            AuditDetails.of("participation", participation.getId())
                .with("claimingOrgUnit", unitId)
                .with("reason", "UNIT_INELIGIBLE"));
        effects.participationEnded(order, unitId);
      }
      participations.flush();
      ended += batch.size();
    }
  }
}
