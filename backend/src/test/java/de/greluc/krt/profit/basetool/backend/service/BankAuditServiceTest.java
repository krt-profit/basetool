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

package de.greluc.krt.profit.basetool.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.mapper.BankAuditEventMapper;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEvent;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEventType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankAuditEventRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.ApiClientMetricsProperties;
import de.greluc.krt.profit.basetool.backend.support.BoundProperties;
import de.greluc.krt.profit.basetool.backend.support.ClientAttribution;
import de.greluc.krt.profit.basetool.backend.support.IngestGatewayProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Unit tests for {@link BankAuditService#record}: the actor is resolved from the security context
 * and snapshotted by handle (the trail must survive user deletion, REQ-BANK-012), every call
 * persists exactly one row, callers without a resolvable user fall back to the {@code system}
 * actor, and every row records the bounded originating client (REQ-AUDIT-005).
 */
@ExtendWith(MockitoExtension.class)
class BankAuditServiceTest {

  @Mock private BankAuditEventRepository auditEventRepository;
  @Mock private AuthHelperService authHelperService;
  @Mock private UserRepository userRepository;
  @Mock private BankAccountRepository accountRepository;
  @Mock private BankAuditEventMapper bankAuditEventMapper;

  @Spy
  private ClientAttribution clientAttribution =
      new ClientAttribution(
          BoundProperties.defaults(ApiClientMetricsProperties.class),
          BoundProperties.defaults(IngestGatewayProperties.class));

  @Spy private MeterRegistry meterRegistry = new SimpleMeterRegistry();

  @InjectMocks private BankAuditService bankAuditService;

  @Test
  void record_persistsExactlyOneRowWithActorSnapshot() {
    UUID actorId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    User actor = new User();
    actor.setId(actorId);
    actor.setUsername("banker_jo");
    when(authHelperService.currentUserId()).thenReturn(Optional.of(actorId));
    when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
    when(auditEventRepository.save(any(BankAuditEvent.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    bankAuditService.record(
        BankAuditEventType.DEPOSIT_BOOKED, accountId, null, null, "+100 aUEC @greluc");

    ArgumentCaptor<BankAuditEvent> saved = ArgumentCaptor.forClass(BankAuditEvent.class);
    verify(auditEventRepository).save(saved.capture());
    BankAuditEvent row = saved.getValue();
    assertEquals(actorId, row.getActorUserId());
    assertEquals("banker_jo", row.getActorHandle());
    assertEquals(BankAuditEventType.DEPOSIT_BOOKED, row.getEventType());
    assertEquals(accountId, row.getAccountId());
    assertEquals("+100 aUEC @greluc", row.getDetails());
    assertNotNull(row.getOccurredAt());
    assertEquals(
        1.0,
        meterRegistry
            .get(MetricNames.BANK_AUDIT_EVENTS)
            .tag(MetricNames.TAG_EVENT_TYPE, BankAuditEventType.DEPOSIT_BOOKED.name())
            .counter()
            .count(),
        "recording a bank audit event must increment basetool_bank_audit_events_total{event_type}");
  }

  @Test
  void record_fallsBackToSystemActorWithoutResolvableUser() {
    when(authHelperService.currentUserId()).thenReturn(Optional.empty());
    when(auditEventRepository.save(any(BankAuditEvent.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    bankAuditService.record(BankAuditEventType.WIPE_RESET_EXECUTED, null, null, null, "x");

    ArgumentCaptor<BankAuditEvent> saved = ArgumentCaptor.forClass(BankAuditEvent.class);
    verify(auditEventRepository).save(saved.capture());
    assertEquals("system", saved.getValue().getActorHandle());
  }

  @Test
  void purgeBefore_deletesRowsAndRecordsPurgeMarker() {
    Instant before = Instant.parse("2026-01-01T00:00:00Z");
    when(authHelperService.currentUserId()).thenReturn(Optional.empty());
    when(auditEventRepository.deleteByOccurredAtBefore(before)).thenReturn(3);
    when(auditEventRepository.save(any(BankAuditEvent.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    int deleted = bankAuditService.purgeBefore(before);

    assertEquals(3, deleted);
    verify(auditEventRepository).deleteByOccurredAtBefore(before);
    ArgumentCaptor<BankAuditEvent> saved = ArgumentCaptor.forClass(BankAuditEvent.class);
    verify(auditEventRepository).save(saved.capture());
    assertEquals(BankAuditEventType.AUDIT_LOG_PURGED, saved.getValue().getEventType());
    assertTrue(saved.getValue().getDetails().contains("deleted=3"), "details carry the count");
  }

  /**
   * A bearer-token authentication carrying one {@code azp}, the shape a real client request has.
   *
   * @param azp the authorized-party claim, or {@code null} to omit it entirely
   * @return the authentication to hand back from {@code AuthHelperService#currentAuthentication()}
   */
  private static Authentication tokenFrom(String azp) {
    Jwt.Builder jwt =
        Jwt.withTokenValue("t").header("alg", "none").claim("sub", UUID.randomUUID().toString());
    if (azp != null) {
      jwt.claim("azp", azp);
    }
    return new JwtAuthenticationToken(jwt.build());
  }

  /**
   * Records one deposit event and returns the row that was handed to the repository.
   *
   * @return the captured bank audit row
   */
  private BankAuditEvent recordAndCapture() {
    when(auditEventRepository.save(any(BankAuditEvent.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    bankAuditService.record(BankAuditEventType.DEPOSIT_BOOKED, null, null, null, null);
    ArgumentCaptor<BankAuditEvent> saved = ArgumentCaptor.forClass(BankAuditEvent.class);
    verify(auditEventRepository).save(saved.capture());
    return saved.getValue();
  }

  @Test
  void record_stampsAKnownClientVerbatim() {
    when(authHelperService.currentAuthentication())
        .thenReturn(Optional.of(tokenFrom("basetool-android")));

    assertEquals("basetool-android", recordAndCapture().getClientId());
  }

  @Test
  void record_collapsesAnUnknownClientToTheBoundedBucket() {
    when(authHelperService.currentAuthentication())
        .thenReturn(Optional.of(tokenFrom("some-other-client")));

    assertEquals(MetricNames.CLIENT_ID_OTHER, recordAndCapture().getClientId());
  }

  @Test
  void record_withoutATokenStampsNoneRatherThanNull() {
    when(authHelperService.currentAuthentication()).thenReturn(Optional.empty());

    BankAuditEvent row = recordAndCapture();

    assertEquals(MetricNames.CLIENT_ID_NONE, row.getClientId());
    assertNotNull(row.getClientId());
  }

  @Test
  void record_withATokenlessAuthenticationStampsNone() {
    when(authHelperService.currentAuthentication())
        .thenReturn(Optional.of(new TestingAuthenticationToken("principal", "creds")));

    assertEquals(MetricNames.CLIENT_ID_NONE, recordAndCapture().getClientId());
  }
}
