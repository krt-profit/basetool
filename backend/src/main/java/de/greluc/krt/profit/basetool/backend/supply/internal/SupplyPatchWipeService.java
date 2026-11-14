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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import de.greluc.krt.profit.basetool.backend.supply.api.DonationStatus;
import de.greluc.krt.profit.basetool.backend.supply.api.SupplyMetricNames;
import de.greluc.krt.profit.basetool.backend.supply.api.SupplyPatchWipe;
import de.greluc.krt.profit.basetool.backend.supply.api.events.SupplyNotices;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Withdraws the pledges on stock a game patch wiped (REQ-PATCH-013): each pledge is withdrawn as
 * its donor would withdraw it, with the patch change as its reason, and the supply trail gets one
 * summary event instead of one event per pledge (REQ-PATCH-017).
 */
@Service
@RequiredArgsConstructor
public class SupplyPatchWipeService implements SupplyPatchWipe {

  /** The sections of the shared supply room a withdrawal changes. */
  private static final List<String> NEED_SECTIONS = List.of("needs", "mine", "stats");

  private final SupplyDonationRepository donationRepository;
  private final AuditRecorder auditRecorder;
  private final AuthHelperService authHelperService;
  private final ApplicationEventPublisher eventPublisher;
  private final LiveSyncPublisher liveSyncPublisher;
  private final MeterRegistry meterRegistry;

  @Override
  @Transactional(readOnly = true)
  public long countWithdrawable(@NotNull Collection<UUID> inventoryItemIds) {
    return donationRepository.countPledgedOnRows(inventoryItemIds);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public int withdraw(
      @NotNull Collection<UUID> inventoryItemIds,
      @NotNull UUID patchChangeId,
      @NotNull String patchLabel) {
    List<SupplyDonation> pledges =
        donationRepository.findAllById(donationRepository.lockPledgedOnRows(inventoryItemIds));
    UUID actor = authHelperService.currentUserId().orElse(null);
    Set<UUID> units = new LinkedHashSet<>();
    for (SupplyDonation pledge : pledges) {
      pledge.setStatus(DonationStatus.WITHDRAWN);
      pledge.setWithdrawnByPatchChange(patchChangeId);
      units.add(pledge.getReceivingOrgUnitId());
      eventPublisher.publishEvent(SupplyNotices.withdrawn(pledge.getId(), actor));
    }
    auditRecorder.record(
        AuditEventType.DONATIONS_WITHDRAWN_BY_PATCH,
        patchChangeId,
        patchLabel,
        null,
        AuditDetails.of("withdrawn", pledges.size()).with("patchChange", patchChangeId));
    if (!pledges.isEmpty()) {
      meterRegistry
          .counter(
              SupplyMetricNames.SUPPLY_DONATIONS,
              MetricNames.TAG_STATUS,
              DonationStatus.WITHDRAWN.name())
          .increment(pledges.size());
      liveSyncPublisher.publishAfterCommit(
          LiveSyncTopic.global(LiveSyncTopicClass.SUPPLY_NEEDS), NEED_SECTIONS);
      units.forEach(unit ->
          liveSyncPublisher.publishAfterCommit(
              LiveSyncTopic.of(LiveSyncTopicClass.SUPPLY_DONATIONS, unit), List.of("donations")));
    }
    return pledges.size();
  }
}
