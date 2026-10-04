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

package de.greluc.krt.profit.basetool.backend.service.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.identity.api.events.MemberDepartedEvent;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClient;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeClientRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeClientRevocationRepository;
import de.greluc.krt.profit.basetool.backend.service.AuditService;
import de.greluc.krt.profit.basetool.backend.service.KeycloakService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class ExchangeDepartureServiceTest {

  private static final UUID MEMBER = UUID.fromString("5f1d2c3b-0000-0000-0000-0000000000f1");

  private final ExchangeClientRepository clientRepository = mock(ExchangeClientRepository.class);
  private final ExchangeClientRevocationRepository revocationRepository =
      mock(ExchangeClientRevocationRepository.class);
  private final ExchangeRevocationMirror revocationMirror = mock(ExchangeRevocationMirror.class);
  private final KeycloakService keycloakService = mock(KeycloakService.class);
  private final AuditService auditService = mock(AuditService.class);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final ExchangeDepartureService service = service();

  @Test
  void withoutRegistryClientsNothingHappens() {
    when(clientRepository.findAll()).thenReturn(List.of());

    service.onDeparture(departure());

    verifyNoInteractions(revocationMirror, revocationRepository, keycloakService, auditService);
  }

  @Test
  void everyClientIsRevokedTheConsentsRemovedAndTheSessionsEnded() {
    ExchangeClient versekit = client("versekit");
    ExchangeClient other = client("other-app");
    when(clientRepository.findAll()).thenReturn(List.of(versekit, other));

    service.onDeparture(departure());

    verify(revocationMirror).revoke(eq("versekit"), eq(MEMBER), any());
    verify(revocationMirror).revoke(eq("other-app"), eq(MEMBER), any());
    verify(revocationRepository).upsert(eq(versekit.getId()), eq(MEMBER), any());
    verify(revocationRepository).upsert(eq(other.getId()), eq(MEMBER), any());
    verify(keycloakService).revokeConsent(MEMBER, "versekit");
    verify(keycloakService).revokeConsent(MEMBER, "other-app");
    verify(keycloakService).logoutUser(MEMBER);
    verify(auditService)
        .record(eq(AuditEventType.EXCHANGE_MEMBER_DEPARTED), eq(null), eq(null), eq(MEMBER), any());
    assertThat(count(ExchangeDepartureService.OUTCOME_DONE)).isEqualTo(1);
  }

  @Test
  void aFailedStepDoesNotStopTheOthersAndIsCounted() {
    when(clientRepository.findAll()).thenReturn(List.of(client("versekit")));
    doThrow(new IllegalStateException("keycloak down"))
        .when(keycloakService)
        .revokeConsent(any(), any());

    service.onDeparture(departure());

    verify(revocationMirror).revoke(eq("versekit"), eq(MEMBER), any());
    verify(keycloakService).logoutUser(MEMBER);
    assertThat(count(ExchangeDepartureService.OUTCOME_FAILED)).isEqualTo(1);
  }

  @Test
  void keycloakEndsTheSessionsBeforeTheRevocationsAreStamped() {
    ExchangeClient versekit = client("versekit");
    when(clientRepository.findAll()).thenReturn(List.of(versekit));
    AtomicReference<Instant> loggedOut = new AtomicReference<>();
    doAnswer(
            invocation -> {
              loggedOut.set(Instant.now());
              return null;
            })
        .when(keycloakService)
        .logoutUser(MEMBER);
    InOrder order = inOrder(keycloakService, revocationMirror, revocationRepository);

    service.onDeparture(departure());

    order.verify(keycloakService).revokeConsent(MEMBER, "versekit");
    order.verify(keycloakService).logoutUser(MEMBER);
    ArgumentCaptor<Instant> stamped = ArgumentCaptor.forClass(Instant.class);
    order.verify(revocationMirror).revoke(eq("versekit"), eq(MEMBER), stamped.capture());
    order.verify(revocationRepository).upsert(versekit.getId(), MEMBER, stamped.getValue());
    assertThat(stamped.getValue()).isAfterOrEqualTo(loggedOut.get());
  }

  @Test
  void aFailedLogoutStillStampsTheRevocationsWithATimeReadAfterIt() {
    ExchangeClient versekit = client("versekit");
    when(clientRepository.findAll()).thenReturn(List.of(versekit));
    AtomicReference<Instant> failedAt = new AtomicReference<>();
    doAnswer(
            invocation -> {
              failedAt.set(Instant.now());
              throw new IllegalStateException("keycloak down");
            })
        .when(keycloakService)
        .logoutUser(MEMBER);

    service.onDeparture(departure());

    ArgumentCaptor<Instant> stamped = ArgumentCaptor.forClass(Instant.class);
    verify(revocationMirror).revoke(eq("versekit"), eq(MEMBER), stamped.capture());
    verify(revocationRepository).upsert(versekit.getId(), MEMBER, stamped.getValue());
    assertThat(stamped.getValue())
        .as("a token refreshed while Keycloak failed is not issued after the stamp")
        .isAfterOrEqualTo(failedAt.get());
    assertThat(count(ExchangeDepartureService.OUTCOME_FAILED)).isEqualTo(1);
  }

  /**
   * Builds the service with a transaction manager that runs the work in place.
   *
   * @return the service
   */
  private @NotNull ExchangeDepartureService service() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    return new ExchangeDepartureService(
        clientRepository,
        revocationRepository,
        revocationMirror,
        keycloakService,
        auditService,
        meterRegistry,
        transactionManager);
  }

  /**
   * A departure of the test member.
   *
   * @return the event
   */
  private static @NotNull MemberDepartedEvent departure() {
    return new MemberDepartedEvent(MEMBER, MemberDepartedEvent.REASON_DISABLED);
  }

  /**
   * A registry client.
   *
   * @param clientId the client id
   * @return the client
   */
  private static @NotNull ExchangeClient client(@NotNull String clientId) {
    ExchangeClient client = new ExchangeClient();
    client.setId(
        UUID.nameUUIDFromBytes(clientId.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    client.setClientId(clientId);
    return client;
  }

  /**
   * Reads one departure counter.
   *
   * @param outcome the outcome
   * @return the count
   */
  private double count(@NotNull String outcome) {
    return meterRegistry
        .counter(MetricNames.EXCHANGE_DEPARTURES, MetricNames.TAG_OUTCOME, outcome)
        .count();
  }
}
