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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.exception.ExternalServiceException;
import de.greluc.krt.profit.basetool.backend.repository.NotificationRepository;
import de.greluc.krt.profit.basetool.backend.service.AuditService;
import de.greluc.krt.profit.basetool.backend.service.KeycloakService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/** Disconnecting a whole client ends its Keycloak sessions before the revocation is stamped. */
class ConnectedAppsServiceTest {

  private static final UUID MEMBER = UUID.fromString("5f1d2c3b-0000-0000-0000-0000000000c1");
  private static final String CLIENT_ID = "basetool-sc-extractor";

  private final ExchangeClientRepository clientRepository = mock(ExchangeClientRepository.class);
  private final ExchangeClientRevocationRepository revocationRepository =
      mock(ExchangeClientRevocationRepository.class);
  private final ExchangeRevocationMirror revocationMirror = mock(ExchangeRevocationMirror.class);
  private final KeycloakService keycloakService = mock(KeycloakService.class);
  private final AuditService auditService = mock(AuditService.class);
  private final ConnectedAppsService service =
      new ConnectedAppsService(
          clientRepository,
          mock(ExchangeInstallationRepository.class),
          revocationRepository,
          revocationMirror,
          keycloakService,
          auditService,
          new SimpleMeterRegistry(),
          mock(NotificationRepository.class),
          mock(ExchangeJournalRepository.class),
          mock(ExchangeEntryLabels.class));

  private final ExchangeClient client = new ExchangeClient();

  @BeforeEach
  void setUp() {
    client.setId(UUID.randomUUID());
    client.setClientId(CLIENT_ID);
    when(clientRepository.findWithCapabilitiesByClientId(CLIENT_ID))
        .thenReturn(Optional.of(client));
  }

  @Test
  void keycloakEndsTheSessionsBeforeTheRevocationIsStamped() {
    InOrder order = inOrder(keycloakService, revocationMirror, revocationRepository);

    service.disconnectClient(MEMBER, CLIENT_ID);

    order.verify(keycloakService).revokeConsent(MEMBER, CLIENT_ID);
    order.verify(keycloakService).endSessionsHeldOnlyBy(MEMBER, CLIENT_ID);
    order.verify(revocationMirror).revoke(eq(CLIENT_ID), eq(MEMBER), any());
    order.verify(revocationRepository).upsert(eq(client.getId()), eq(MEMBER), any());
    verify(auditService)
        .record(
            eq(AuditEventType.EXCHANGE_CLIENT_DISCONNECTED),
            eq(client.getId()),
            eq(CLIENT_ID),
            eq(MEMBER),
            any());
  }

  @Test
  void aSharedSessionLosesOnlyTheClientBeforeTheRevocationIsStamped() {
    when(keycloakService.endSessionsHeldOnlyBy(MEMBER, CLIENT_ID)).thenReturn(1);
    InOrder order = inOrder(keycloakService, revocationMirror);

    service.disconnectClient(MEMBER, CLIENT_ID);

    order.verify(keycloakService).endSessionsHeldOnlyBy(MEMBER, CLIENT_ID);
    order.verify(keycloakService).endClientInSharedSessions(MEMBER, CLIENT_ID);
    order.verify(revocationMirror).revoke(eq(CLIENT_ID), eq(MEMBER), any());
    verify(keycloakService, never()).logoutUser(any());
  }

  @Test
  void withoutASharedSessionTheExtensionIsNotCalled() {
    service.disconnectClient(MEMBER, CLIENT_ID);

    verify(keycloakService, never()).endClientInSharedSessions(any(), any());
  }

  @Test
  void aSharedSessionKeycloakCannotEndFailsTheDisconnectBeforeAnythingIsWritten() {
    when(keycloakService.endSessionsHeldOnlyBy(MEMBER, CLIENT_ID)).thenReturn(1);
    doThrow(new IllegalStateException("down"))
        .when(keycloakService)
        .endClientInSharedSessions(MEMBER, CLIENT_ID);

    assertThatThrownBy(() -> service.disconnectClient(MEMBER, CLIENT_ID))
        .isInstanceOf(ExternalServiceException.class);

    verifyNoInteractions(revocationMirror, auditService);
  }

  @Test
  void theRevocationTimeIsReadAfterTheSessionsEnded() {
    AtomicReference<Instant> ended = new AtomicReference<>();
    when(keycloakService.endSessionsHeldOnlyBy(MEMBER, CLIENT_ID))
        .thenAnswer(
            invocation -> {
              ended.set(Instant.now());
              return 1;
            });

    service.disconnectClient(MEMBER, CLIENT_ID);

    ArgumentCaptor<Instant> stamped = ArgumentCaptor.forClass(Instant.class);
    verify(revocationMirror).revoke(eq(CLIENT_ID), eq(MEMBER), stamped.capture());
    verify(revocationRepository).upsert(client.getId(), MEMBER, stamped.getValue());
    assertThat(stamped.getValue())
        .as("a token refreshed before Keycloak ended the session is not issued after the stamp")
        .isAfterOrEqualTo(ended.get());
  }

  @Test
  void sessionsKeycloakCannotEndFailTheDisconnectBeforeAnythingIsWritten() {
    doThrow(new IllegalStateException("down"))
        .when(keycloakService)
        .endSessionsHeldOnlyBy(MEMBER, CLIENT_ID);

    assertThatThrownBy(() -> service.disconnectClient(MEMBER, CLIENT_ID))
        .isInstanceOf(ExternalServiceException.class);

    verifyNoInteractions(revocationMirror, auditService);
    verify(revocationRepository, never()).upsert(any(), any(), any());
  }

  @Test
  void aConsentKeycloakCannotRemoveFailsTheDisconnectBeforeAnythingIsWritten() {
    doThrow(new IllegalStateException("down"))
        .when(keycloakService)
        .revokeConsent(MEMBER, CLIENT_ID);

    assertThatThrownBy(() -> service.disconnectClient(MEMBER, CLIENT_ID))
        .isInstanceOf(ExternalServiceException.class);

    verify(keycloakService, never()).endSessionsHeldOnlyBy(any(), any());
    verifyNoInteractions(revocationMirror, auditService);
  }
}
