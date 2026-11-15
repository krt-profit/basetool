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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import de.greluc.krt.profit.basetool.backend.supply.api.DonationStatus;
import de.greluc.krt.profit.basetool.backend.supply.api.SupplyMetricNames;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

/**
 * The supply side of a patch wipe (REQ-PATCH-013, REQ-PATCH-017, REQ-PATCH-018): every pledge on a
 * wiped row is withdrawn with the patch change as its reason and the usual notice, the supply trail
 * gets one summary event, and the open supply pages and the donation counter follow only the
 * commit.
 */
@ExtendWith(MockitoExtension.class)
class SupplyPatchWipeServiceTest {

  private static final UUID PATCH_CHANGE = UUID.randomUUID();

  @Mock private SupplyDonationRepository donationRepository;
  @Mock private AuditRecorder auditRecorder;
  @Mock private AuthHelperService authHelperService;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private LiveSyncPublisher liveSyncPublisher;

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private SupplyPatchWipeService service;

  @BeforeEach
  void setUp() {
    TransactionSynchronizationManager.initSynchronization();
    service =
        new SupplyPatchWipeService(
            donationRepository,
            auditRecorder,
            authHelperService,
            eventPublisher,
            liveSyncPublisher,
            meterRegistry);
  }

  @AfterEach
  void tearDown() {
    TransactionSynchronizationManager.clearSynchronization();
  }

  @Test
  void eachPledgeIsWithdrawnWithThePatchChangeAsItsReason() {
    UUID row = UUID.randomUUID();
    UUID unit = UUID.randomUUID();
    SupplyDonation pledge = pledgeFor(row, unit);

    int withdrawn = service.withdraw(List.of(row), PATCH_CHANGE, "4.4");
    TransactionSynchronizationUtils.invokeAfterCommit(
        TransactionSynchronizationManager.getSynchronizations());

    assertThat(withdrawn).isEqualTo(1);
    assertThat(pledge.getStatus()).isEqualTo(DonationStatus.WITHDRAWN);
    assertThat(pledge.getWithdrawnByPatchChange()).isEqualTo(PATCH_CHANGE);
    verify(eventPublisher).publishEvent(any(NoticeEvent.class));
    verify(auditRecorder)
        .record(
            eq(AuditEventType.DONATIONS_WITHDRAWN_BY_PATCH),
            eq(PATCH_CHANGE),
            eq("4.4"),
            isNull(),
            argThat(details -> details.toString().contains("withdrawn=1")));
    verify(liveSyncPublisher)
        .publishAfterCommit(
            LiveSyncTopic.global(LiveSyncTopicClass.SUPPLY_NEEDS),
            List.of("needs", "mine", "stats"));
    verify(liveSyncPublisher)
        .publishAfterCommit(
            LiveSyncTopic.of(LiveSyncTopicClass.SUPPLY_DONATIONS, unit), List.of("donations"));
    assertThat(withdrawnCount()).isEqualTo(1.0);
  }

  @Test
  void theWithdrawnDonationsAreCountedOnlyOnceTheTriggerCommits() {
    UUID row = UUID.randomUUID();
    pledgeFor(row, UUID.randomUUID());

    service.withdraw(List.of(row), PATCH_CHANGE, "4.4");

    assertThat(withdrawnCount()).as("nothing is counted inside the transaction").isZero();
    TransactionSynchronizationUtils.invokeAfterCompletion(
        TransactionSynchronizationManager.getSynchronizations(),
        TransactionSynchronization.STATUS_ROLLED_BACK);
    assertThat(withdrawnCount()).as("a refused trigger counts nothing").isZero();
  }

  @Test
  void withoutPledgesOnlyTheSummaryIsRecorded() {
    UUID row = UUID.randomUUID();
    when(donationRepository.lockPledgedOnRows(List.of(row))).thenReturn(List.of());
    when(donationRepository.findAllById(List.of())).thenReturn(List.of());
    when(authHelperService.currentUserId()).thenReturn(Optional.empty());

    assertThat(service.withdraw(List.of(row), PATCH_CHANGE, "4.4")).isZero();

    verify(auditRecorder)
        .record(
            eq(AuditEventType.DONATIONS_WITHDRAWN_BY_PATCH),
            eq(PATCH_CHANGE),
            eq("4.4"),
            isNull(),
            argThat(details -> details.toString().contains("withdrawn=0")));
    verifyNoInteractions(eventPublisher, liveSyncPublisher);
    assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
  }

  /**
   * Stubs one pledge on a row, received by a unit, as the locking read finds it.
   *
   * @param row the pledged stock row
   * @param unit the receiving org unit
   * @return the pledge
   */
  private SupplyDonation pledgeFor(UUID row, UUID unit) {
    SupplyDonation pledge = new SupplyDonation();
    pledge.setId(UUID.randomUUID());
    pledge.setReceivingOrgUnitId(unit);
    pledge.setStatus(DonationStatus.PLEDGED);
    when(donationRepository.lockPledgedOnRows(List.of(row))).thenReturn(List.of(pledge.getId()));
    when(donationRepository.findAllById(List.of(pledge.getId()))).thenReturn(List.of(pledge));
    when(authHelperService.currentUserId()).thenReturn(Optional.empty());
    return pledge;
  }

  /**
   * Reads the withdrawn-donation counter.
   *
   * @return its count
   */
  private double withdrawnCount() {
    return meterRegistry
        .counter(SupplyMetricNames.SUPPLY_DONATIONS, MetricNames.TAG_STATUS, "WITHDRAWN")
        .count();
  }
}
