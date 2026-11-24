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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeMetricNames;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The registry notices (REQ-XCH-040, -041): a suspension, an activation, a raised version floor, a
 * removed capability and the global switch tell the holders of the installations, naming the
 * registry's display name; the bank switch tells the admins and the Bankleitung (REQ-BXCH-006);
 * changes that need no action tell nobody.
 */
@ExtendWith(MockitoExtension.class)
class ExchangeRegistryNoticeTest {

  private static final UUID CLIENT_ID = UUID.randomUUID();
  private static final UUID ADMIN = UUID.randomUUID();

  @Mock private ExchangeClientRepository clientRepository;
  @Mock private ExchangeRegistryMirrorSync mirrorSync;
  @Mock private AuditRecorder auditRecorder;
  @Mock private KnownExchangeClients knownExchangeClients;
  @Mock private ExchangeInstallationRepository installationRepository;
  @Mock private FirstPartyClientIds firstPartyClientIds;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private AuthHelperService authHelperService;

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private ExchangeRegistryService service;
  private ExchangeClient client;

  @BeforeEach
  void setUp() {
    TransactionSynchronizationManager.initSynchronization();
    service =
        new ExchangeRegistryService(
            clientRepository,
            mirrorSync,
            auditRecorder,
            meterRegistry,
            knownExchangeClients,
            installationRepository,
            firstPartyClientIds,
            eventPublisher,
            authHelperService);
    client = new ExchangeClient();
    client.setId(CLIENT_ID);
    client.setClientId("its-own-label");
    client.setDisplayName("Trade Helper");
    client.setStatus(ExchangeClientStatus.ACTIVE);
    client.setVersion(1L);
    client.setCapabilities(
        new HashSet<>(Set.of(ExchangeCapability.CONNECT, ExchangeCapability.STOCK_READ)));
    lenient().when(authHelperService.currentUserId()).thenReturn(Optional.of(ADMIN));
    lenient()
        .when(clientRepository.findWithCapabilitiesById(CLIENT_ID))
        .thenReturn(Optional.of(client));
    lenient()
        .when(clientRepository.saveAndFlush(any(ExchangeClient.class)))
        .thenAnswer(i -> i.getArgument(0));
  }

  @AfterEach
  void tearDown() {
    TransactionSynchronizationManager.clearSynchronization();
  }

  private List<NoticeEvent> published(int expected) {
    ArgumentCaptor<Object> captured = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher, times(expected)).publishEvent(captured.capture());
    return captured.getAllValues().stream().map(NoticeEvent.class::cast).toList();
  }

  private ExchangeClientUpdateRequest update(String minVersion, Set<ExchangeCapability> caps) {
    return new ExchangeClientUpdateRequest("Trade Helper", caps, minVersion, null, null, null, 1L);
  }

  @Test
  void aSuspensionTellsTheHoldersWithTheRegistryDisplayNameNotTheClientsLabel() {
    service.suspendClient(CLIENT_ID, 1L);

    NoticeEvent notice = published(1).getFirst();
    assertThat(notice.eventType()).isEqualTo(NotificationEventType.EXCHANGE_CLIENT_SUSPENDED);
    assertThat(notice.contextExchangeClientId()).isEqualTo(CLIENT_ID);
    assertThat(notice.actorSub()).isEqualTo(ADMIN);
    assertThat(notice.renderParams()).containsEntry("app", "Trade Helper");
    assertThat(notice.renderParams().values()).doesNotContain("its-own-label");
  }

  @Test
  void anActivationTellsTheHoldersAndSupersedesTheSuspension() {
    client.setStatus(ExchangeClientStatus.SUSPENDED);

    service.activateClient(CLIENT_ID, 1L);

    NoticeEvent notice = published(1).getFirst();
    assertThat(notice.eventType()).isEqualTo(NotificationEventType.EXCHANGE_CLIENT_ACTIVATED);
    assertThat(notice.resolvesNotificationTypes()).isNotEmpty();
  }

  @Test
  void suspendingAnAlreadySuspendedClientAnnouncesNothing() {
    client.setStatus(ExchangeClientStatus.SUSPENDED);

    service.suspendClient(CLIENT_ID, 1L);

    verify(eventPublisher, never()).publishEvent(any(Object.class));
  }

  @Test
  void raisingTheVersionFloorTellsTheHoldersToUpdate() {
    client.setMinClientVersion("1.2.0");

    service.updateClient(CLIENT_ID, update("1.3.0", client.getCapabilities()));

    NoticeEvent notice = published(1).getFirst();
    assertThat(notice.eventType()).isEqualTo(NotificationEventType.EXCHANGE_CLIENT_UPDATE_REQUIRED);
    assertThat(notice.renderParams()).containsEntry("version", "1.3.0");
  }

  @Test
  void loweringOrClearingTheVersionFloorAnnouncesNothing() {
    client.setMinClientVersion("1.2.0");
    service.updateClient(CLIENT_ID, update("1.1.0", client.getCapabilities()));
    service.updateClient(CLIENT_ID, update(null, client.getCapabilities()));

    verify(eventPublisher, never()).publishEvent(any(Object.class));
  }

  @Test
  void removingACapabilityTellsTheHoldersWhichOne() {
    service.updateClient(CLIENT_ID, update(null, Set.of(ExchangeCapability.CONNECT)));

    NoticeEvent notice = published(1).getFirst();
    assertThat(notice.eventType())
        .isEqualTo(NotificationEventType.EXCHANGE_CLIENT_CAPABILITY_REMOVED);
    assertThat(notice.renderParams().get("capabilities"))
        .isEqualTo(ExchangeCapability.STOCK_READ.getScope());
  }

  @Test
  void addingACapabilityAnnouncesNothing() {
    Set<ExchangeCapability> more = new HashSet<>(client.getCapabilities());
    more.add(ExchangeCapability.STOCK_WRITE);

    service.updateClient(CLIENT_ID, update(null, more));

    verify(eventPublisher, never()).publishEvent(any(Object.class));
  }

  @Test
  void switchingTheExchangeOffTellsEveryHolderAndOnClearsIt() {
    ExchangeSettings settings = new ExchangeSettings();
    settings.setEnabled(true);
    settings.setVersion(1L);
    when(mirrorSync.lockSettings()).thenReturn(settings);
    when(mirrorSync.saveSwitch(
            eq(ExchangeSwitch.GLOBAL),
            any(ExchangeSettings.class),
            anyBoolean(),
            any(Instant.class)))
        .thenAnswer(i -> {
          ExchangeSettings row = i.getArgument(1);
          row.setEnabled(i.getArgument(2));
          return row;
        });

    service.updateSwitch(ExchangeSwitch.GLOBAL, false, 1L);
    service.updateSwitch(ExchangeSwitch.GLOBAL, true, 1L);

    List<NoticeEvent> sent = published(2);
    assertThat(sent.get(0).eventType()).isEqualTo(NotificationEventType.EXCHANGE_SWITCHED_OFF);
    assertThat(sent.get(0).contextAllExchangeClients()).isTrue();
    assertThat(sent.get(1).eventType()).isEqualTo(NotificationEventType.EXCHANGE_SWITCHED_ON);
  }

  @Test
  void flippingTheBankSwitchTellsTheAdminsAndTheBankleitungAndSupersedesTheLastNotice() {
    ExchangeSettings settings = new ExchangeSettings();
    settings.setEnabled(true);
    when(mirrorSync.lockSettings()).thenReturn(settings);
    stubAreaSwitchWrites();

    service.updateSwitch(ExchangeSwitch.BANK, true, 0L);
    service.updateSwitch(ExchangeSwitch.BANK, false, 1L);
    service.updateSwitch(ExchangeSwitch.BANK, false, 2L);

    List<NoticeEvent> sent = published(2);
    assertThat(sent.get(0).eventType()).isEqualTo(NotificationEventType.BANK_EXCHANGE_SWITCHED_ON);
    assertThat(sent.get(1).eventType()).isEqualTo(NotificationEventType.BANK_EXCHANGE_SWITCHED_OFF);
    assertThat(sent.get(1).actorSub()).isEqualTo(ADMIN);
    assertThat(sent.get(1).contextAllExchangeClients())
        .as("the bank notice goes to roles, not to installation holders")
        .isFalse();
    assertThat(sent.get(1).resolvesNotificationTypes())
        .containsExactlyInAnyOrder(
            NotificationType.BANK_EXCHANGE_SWITCHED_ON,
            NotificationType.BANK_EXCHANGE_SWITCHED_OFF);
    assertThat(settings.getBankVersion()).isEqualTo(2L);
    verify(auditRecorder, times(2))
        .record(
            eq(AuditEventType.BANK_EXCHANGE_SWITCH_CHANGED),
            isNull(),
            isNull(),
            isNull(),
            any(AuditDetails.class));
  }

  @Test
  void flippingAJobOrderSwitchTellsTheAdminsWhichSwitchAndSupersedesOnlyThatSwitchsNotice() {
    ExchangeSettings settings = new ExchangeSettings();
    settings.setEnabled(true);
    when(mirrorSync.lockSettings()).thenReturn(settings);
    stubAreaSwitchWrites();

    service.updateSwitch(ExchangeSwitch.JOB_ORDERS, true, 0L);
    service.updateSwitch(ExchangeSwitch.JOB_ORDER_WRITES, true, 0L);
    service.updateSwitch(ExchangeSwitch.JOB_ORDER_WRITES, false, 1L);
    service.updateSwitch(ExchangeSwitch.JOB_ORDER_WRITES, false, 2L);

    List<NoticeEvent> sent = published(3);
    NoticeEvent familyOn = sent.get(0);
    NoticeEvent writesOn = sent.get(1);
    NoticeEvent writesOff = sent.get(2);
    assertThat(familyOn.eventType())
        .isEqualTo(NotificationEventType.JOB_ORDER_EXCHANGE_SWITCHED_ON);
    assertThat(familyOn.renderParams()).containsExactly(Map.entry("switchCode", "family"));
    assertThat(writesOn.eventType())
        .isEqualTo(NotificationEventType.JOB_ORDER_EXCHANGE_SWITCHED_ON);
    assertThat(writesOn.renderParams()).containsExactly(Map.entry("switchCode", "writes"));
    assertThat(writesOff.eventType())
        .isEqualTo(NotificationEventType.JOB_ORDER_EXCHANGE_SWITCHED_OFF);
    assertThat(writesOff.actorSub()).isEqualTo(ADMIN);
    assertThat(writesOff.contextAllExchangeClients())
        .as("the job-order notice goes to roles, not to installation holders")
        .isFalse();
    assertThat(writesOff.resolvesNotificationTypes())
        .containsExactlyInAnyOrder(
            NotificationType.JOB_ORDER_EXCHANGE_SWITCHED_ON,
            NotificationType.JOB_ORDER_EXCHANGE_SWITCHED_OFF);
    assertThat(writesOff.entityType())
        .as("a writes notice supersedes the writes switch's notice only")
        .isEqualTo(writesOn.entityType())
        .isNotEqualTo(familyOn.entityType());
    assertThat(settings.getJobOrdersVersion()).isEqualTo(1L);
    assertThat(settings.getJobOrderWritesVersion()).isEqualTo(2L);
    assertThat(settings.getBankVersion()).isZero();
    ArgumentCaptor<AuditDetails> details = ArgumentCaptor.forClass(AuditDetails.class);
    verify(auditRecorder, times(3))
        .record(
            eq(AuditEventType.JOB_ORDER_EXCHANGE_SWITCH_CHANGED),
            isNull(),
            isNull(),
            isNull(),
            details.capture());
    assertThat(details.getAllValues())
        .extracting(AuditDetails::toString)
        .containsExactly(
            "switch=family enabled=true",
            "switch=writes enabled=true",
            "switch=writes enabled=false");
  }

  @Test
  void everyJobOrderSwitchActionIsRegisteredAtZeroAndCountedOncePerCommittedFlip() {
    ExchangeSettings settings = new ExchangeSettings();
    settings.setEnabled(true);
    when(mirrorSync.lockSettings()).thenReturn(settings);
    stubAreaSwitchWrites();
    service.registerChangeCounters();
    for (String action :
        List.of(
            "job_order_switch_on",
            "job_order_switch_off",
            "job_order_writes_switch_on",
            "job_order_writes_switch_off")) {
      assertThat(changes(action)).as(action).isZero();
    }

    service.updateSwitch(ExchangeSwitch.JOB_ORDERS, true, 0L);
    service.updateSwitch(ExchangeSwitch.JOB_ORDERS, false, 1L);
    service.updateSwitch(ExchangeSwitch.JOB_ORDER_WRITES, true, 0L);
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(TransactionSynchronization::afterCommit);

    assertThat(changes("job_order_switch_on")).isEqualTo(1.0d);
    assertThat(changes("job_order_switch_off")).isEqualTo(1.0d);
    assertThat(changes("job_order_writes_switch_on")).isEqualTo(1.0d);
    assertThat(changes("job_order_writes_switch_off")).isZero();
  }

  /**
   * Reads one registry change counter.
   *
   * @param action the {@code action} label
   * @return the count
   */
  private double changes(String action) {
    return meterRegistry
        .get(ExchangeMetricNames.EXCHANGE_REGISTRY_CHANGES)
        .tag(ExchangeMetricNames.TAG_ACTION, action)
        .counter()
        .count();
  }

  /**
   * Answers an area switch write as the database does: it sets that switch alone and bumps its own
   * version.
   */
  private void stubAreaSwitchWrites() {
    when(mirrorSync.saveSwitch(
            any(ExchangeSwitch.class),
            any(ExchangeSettings.class),
            anyBoolean(),
            any(Instant.class)))
        .thenAnswer(i -> {
          ExchangeSwitch area = i.getArgument(0);
          ExchangeSettings row = i.getArgument(1);
          boolean enabled = i.getArgument(2);
          switch (area) {
            case GLOBAL -> row.setEnabled(enabled);
            case BANK -> {
              row.setBankEnabled(enabled);
              row.setBankVersion(row.getBankVersion() + 1);
            }
            case JOB_ORDERS -> {
              row.setJobOrdersEnabled(enabled);
              row.setJobOrdersVersion(row.getJobOrdersVersion() + 1);
            }
            case JOB_ORDER_WRITES -> {
              row.setJobOrderWritesEnabled(enabled);
              row.setJobOrderWritesVersion(row.getJobOrderWritesVersion() + 1);
            }
            case null -> throw new AssertionError("a switch write names its switch");
          }
          return row;
        });
  }

  @Test
  void aVersionFloorCountsAsRaisedOnlyWhenItGoesUp() {
    assertThat(ExchangeRegistryService.isRaised(null, "1.0.0")).isTrue();
    assertThat(ExchangeRegistryService.isRaised("1.2.0", "1.10.0")).isTrue();
    assertThat(ExchangeRegistryService.isRaised("1.2", "1.2.1")).isTrue();
    assertThat(ExchangeRegistryService.isRaised("1.2.0", "1.2.0")).isFalse();
    assertThat(ExchangeRegistryService.isRaised("2.0.0", "1.9.9")).isFalse();
    assertThat(ExchangeRegistryService.isRaised("1.0-beta", "1.0-rc")).isTrue();
  }
}
