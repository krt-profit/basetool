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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import de.greluc.krt.profit.basetool.backend.service.AuthHelperService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashSet;
import java.util.List;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The registry notices (REQ-XCH-040, -041): a suspension, an activation, a raised version floor, a
 * removed capability and the global switch tell the holders of the installations, naming the
 * registry's display name, and changes that need no action tell nobody.
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
            new SimpleMeterRegistry(),
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
    when(mirrorSync.saveSettings(any(ExchangeSettings.class))).thenAnswer(i -> i.getArgument(0));

    service.updateSettings(false, 1L);
    service.updateSettings(true, 1L);

    List<NoticeEvent> sent = published(2);
    assertThat(sent.get(0).eventType()).isEqualTo(NotificationEventType.EXCHANGE_SWITCHED_OFF);
    assertThat(sent.get(0).contextAllExchangeClients()).isTrue();
    assertThat(sent.get(1).eventType()).isEqualTo(NotificationEventType.EXCHANGE_SWITCHED_ON);
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
