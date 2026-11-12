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
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitEligibilityObserver;
import de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchySnapshot;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains support, promises and live views atomically with organisational eligibility changes.
 */
@Service
@RequiredArgsConstructor
public class JobOrderEligibilityMaintenance implements OrgUnitEligibilityObserver {

  private static final int ORDER_BATCH_SIZE = 200;

  private final MaterialClaimService materialClaims;
  private final JobOrderParticipationEligibility participationEligibility;
  private final JobOrderMoneyCommitments moneyCommitments;
  private final ProfitHierarchyService hierarchy;
  private final JobOrderRepository orders;
  private final LiveSyncPublisher liveSyncPublisher;
  private final JobOrderChangeEffects effects;
  private final AuthHelperService auth;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void onEligibilityChanged(
      @NotNull Set<UUID> affectedUnitIds,
      @NotNull ProfitHierarchySnapshot previous,
      @NotNull ProfitHierarchySnapshot current) {
    int page = 0;
    boolean affected = false;
    List<UUID> batch;
    do {
      batch =
          orders.findIdsAffectedByUnits(affectedUnitIds, PageRequest.of(page++, ORDER_BATCH_SIZE));
      if (!batch.isEmpty() && !previous.equals(current)) {
        publishClarificationChanges(batch, previous, current);
      }
      for (UUID orderId : batch) {
        affected = true;
        publishOrderChanged(orderId);
      }
    } while (batch.size() == ORDER_BATCH_SIZE);
    int ended = materialClaims.endIneligibleClaimsWithinTransaction(affectedUnitIds);
    int endedParticipations = participationEligibility.endForUnits(affectedUnitIds);
    Set<UUID> eligible = current.eligibleUnitIds();
    int endedCommitments =
        moneyCommitments.endForUnits(
            affectedUnitIds.stream()
                .filter(id -> !eligible.contains(id))
                .collect(Collectors.toSet()));
    if (affected || ended > 0 || endedParticipations > 0 || endedCommitments > 0) {
      publishQueueChanged();
    }
  }

  /**
   * Reconciles one existing order through the same audited ending and live-update contract.
   *
   * @param orderId the initial-maintenance candidate
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void reconcileExistingPromises(@NotNull UUID orderId) {
    int ended = materialClaims.endIneligibleClaimsForOrderWithinTransaction(orderId);
    int endedParticipations = participationEligibility.endForOrder(orderId);
    int endedCommitments =
        moneyCommitments.endUnavailableForOrder(orderId, hierarchy.eligibleUnitIds());
    if (ended > 0 || endedParticipations > 0 || endedCommitments > 0) {
      publishOrderChanged(orderId);
      publishQueueChanged();
    }
  }

  private void publishClarificationChanges(
      List<UUID> batch, ProfitHierarchySnapshot previous, ProfitHierarchySnapshot current) {
    UUID actor = auth.currentUserId().orElse(null);
    for (JobOrder order : orders.findNotificationContextByIdIn(batch)) {
      boolean wasValid = JobOrderResponsibilityGuard.isValid(order, previous);
      boolean isValid = JobOrderResponsibilityGuard.isValid(order, current);
      if (wasValid && !isValid) {
        effects.publish(JobOrderLifecycleNotices.clarificationRequired(order, actor));
      } else if (!wasValid && isValid) {
        effects.publish(JobOrderLifecycleNotices.clarificationResolved(order, actor));
      }
    }
  }

  private void publishOrderChanged(UUID orderId) {
    liveSyncPublisher.publishAfterCommit(
        LiveSyncTopic.of(LiveSyncTopicClass.ORDER, orderId),
        List.of(
            "header",
            "actions",
            "dialogs",
            "materials",
            "aggregated",
            "items",
            "assignees",
            "participations",
            "money"));
  }

  private void publishQueueChanged() {
    liveSyncPublisher.publishAfterCommit(
        LiveSyncTopic.global(LiveSyncTopicClass.ORDERS_QUEUE), List.of("queue", "demand"));
  }
}
