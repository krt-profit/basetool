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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.mapper.AuditEventMapper;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.AuditDomain;
import de.greluc.krt.profit.basetool.backend.model.AuditEvent;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.AuditEventRepository;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Unit tests for {@link AuditService}: {@code record} derives the {@link AuditDomain} from the
 * event type, snapshots the actor handle (the trail must survive user deletion, REQ-AUDIT-001),
 * falls back to the {@code system} actor when no user resolves, clamps an over-long subject label,
 * stamps the bounded originating-client label (REQ-AUDIT-005), and {@code getEvents} delegates to
 * the filtered repository query for the selected domain.
 */
@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

  @Mock private AuditEventRepository auditEventRepository;
  @Mock private AuthHelperService authHelperService;
  @Mock private UserRepository userRepository;
  @Mock private AuditEventMapper auditEventMapper;

  @Spy
  private ClientAttribution clientAttribution =
      new ClientAttribution(
          BoundProperties.defaults(ApiClientMetricsProperties.class),
          BoundProperties.defaults(IngestGatewayProperties.class));

  @Spy private MeterRegistry meterRegistry = new SimpleMeterRegistry();

  @InjectMocks private AuditService auditService;

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
   * Records one inventory event and returns the row that was handed to the repository.
   *
   * @return the captured audit row
   */
  private AuditEvent recordAndCapture() {
    when(auditEventRepository.save(any(AuditEvent.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    auditService.record(AuditEventType.INVENTORY_ITEM_CREATED, null, null, null, null);
    ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.forClass(AuditEvent.class);
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

    AuditEvent row = recordAndCapture();

    assertEquals(MetricNames.CLIENT_ID_NONE, row.getClientId());
    assertNotNull(row.getClientId());
  }

  @Test
  void record_withATokenlessAuthenticationStampsNone() {
    when(authHelperService.currentAuthentication())
        .thenReturn(Optional.of(new TestingAuthenticationToken("principal", "creds")));

    assertEquals(MetricNames.CLIENT_ID_NONE, recordAndCapture().getClientId());
  }

  @Test
  void record_derivesDomainFromEventTypeAndSnapshotsActor() {
    UUID actorId = UUID.randomUUID();
    UUID subjectId = UUID.randomUUID();
    User actor = new User();
    actor.setId(actorId);
    actor.setUsername("logi_jo");
    when(authHelperService.currentUserId()).thenReturn(Optional.of(actorId));
    when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
    when(auditEventRepository.save(any(AuditEvent.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    auditService.record(
        AuditEventType.INVENTORY_ITEM_CREATED,
        subjectId,
        "Quantanium @ Port Olisar",
        null,
        "qty=5.0");

    ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventRepository).save(saved.capture());
    AuditEvent row = saved.getValue();
    assertEquals(AuditDomain.INVENTORY, row.getDomain());
    assertEquals(AuditEventType.INVENTORY_ITEM_CREATED, row.getEventType());
    assertEquals(actorId, row.getActorUserId());
    assertEquals("logi_jo", row.getActorHandle());
    assertEquals(subjectId, row.getSubjectId());
    assertEquals("Quantanium @ Port Olisar", row.getSubjectLabel());
    assertEquals("qty=5.0", row.getDetails());
    assertNotNull(row.getOccurredAt());
    assertEquals(
        1.0d,
        meterRegistry
            .get(MetricNames.AUDIT_EVENTS)
            .tag(MetricNames.TAG_DOMAIN, AuditDomain.INVENTORY.name())
            .counter()
            .count());
  }

  @Test
  void record_fallsBackToSystemActorWithoutResolvableUser() {
    when(authHelperService.currentUserId()).thenReturn(Optional.empty());
    when(auditEventRepository.save(any(AuditEvent.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    auditService.record(
        AuditEventType.REFINERY_METHODS_SYNCED, null, null, null, "source=UEX added=2 updated=1");

    ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventRepository).save(saved.capture());
    AuditEvent row = saved.getValue();
    assertEquals("system", row.getActorHandle());
    assertEquals(AuditDomain.REFINERY, row.getDomain());
    assertNull(row.getActorUserId());
  }

  @Test
  void record_clampsOverlongSubjectLabel() {
    when(authHelperService.currentUserId()).thenReturn(Optional.empty());
    when(auditEventRepository.save(any(AuditEvent.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    String overlong = "x".repeat(400);

    auditService.record(AuditEventType.JOB_ORDER_CREATED, UUID.randomUUID(), overlong, null, "d");

    ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventRepository).save(saved.capture());
    assertEquals(255, saved.getValue().getSubjectLabel().length());
  }

  @Test
  void purgeBefore_deletesDomainRowsAndRecordsPurgeMarker() {
    Instant before = Instant.parse("2026-01-01T00:00:00Z");
    when(authHelperService.currentUserId()).thenReturn(Optional.empty());
    when(auditEventRepository.deleteByDomainAndOccurredAtBefore(AuditDomain.REFINERY, before))
        .thenReturn(7);
    when(auditEventRepository.save(any(AuditEvent.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    int deleted = auditService.purgeBefore(AuditDomain.REFINERY, before);

    assertEquals(7, deleted);
    verify(auditEventRepository).deleteByDomainAndOccurredAtBefore(AuditDomain.REFINERY, before);
    ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventRepository).save(saved.capture());
    AuditEvent marker = saved.getValue();
    assertEquals(AuditEventType.REFINERY_AUDIT_PURGED, marker.getEventType());
    assertEquals(AuditDomain.REFINERY, marker.getDomain());
    assertTrue(marker.getDetails().contains("deleted=7"), "details carry the deleted count");
    assertTrue(marker.getDetails().contains("before="), "details carry the cutoff");
  }

  @Test
  void getEvents_queriesFilteredBySelectedDomain() {
    Pageable pageable = Pageable.unpaged();
    AuditEvent event = AuditEvent.builder().domain(AuditDomain.JOB_ORDER).build();
    Page<AuditEvent> page = new PageImpl<>(java.util.List.of(event));
    when(auditEventRepository.findFiltered(
            eq(AuditDomain.JOB_ORDER), any(), any(), any(), any(), any(), eq(pageable)))
        .thenReturn(page);

    Instant from = Instant.parse("2026-01-01T00:00:00Z");
    auditService.getEvents(AuditDomain.JOB_ORDER, from, null, null, null, null, pageable);

    verify(auditEventRepository)
        .findFiltered(
            eq(AuditDomain.JOB_ORDER), eq(from), any(), any(), any(), any(), eq(pageable));
    verify(auditEventMapper).toDto(event);
  }
}
