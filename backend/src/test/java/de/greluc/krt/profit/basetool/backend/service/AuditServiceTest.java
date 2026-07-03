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

/**
 * Unit tests for {@link AuditService}: {@code record} derives the {@link AuditDomain} from the
 * event type, snapshots the actor handle (the trail must survive user deletion, REQ-AUDIT-001),
 * falls back to the {@code system} actor when no user resolves, clamps an over-long subject label,
 * and {@code getEvents} delegates to the filtered repository query for the selected domain.
 */
@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

  @Mock private AuditEventRepository auditEventRepository;
  @Mock private AuthHelperService authHelperService;
  @Mock private UserRepository userRepository;
  @Mock private AuditEventMapper auditEventMapper;

  // A real registry (spied) so the per-domain audit counter is genuinely recorded and assertable.
  @Spy private MeterRegistry meterRegistry = new SimpleMeterRegistry();

  @InjectMocks private AuditService auditService;

  @Test
  void record_derivesDomainFromEventTypeAndSnapshotsActor() {
    // Given
    UUID actorId = UUID.randomUUID();
    UUID subjectId = UUID.randomUUID();
    User actor = new User();
    actor.setId(actorId);
    actor.setUsername("logi_jo");
    when(authHelperService.currentUserId()).thenReturn(Optional.of(actorId));
    when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
    when(auditEventRepository.save(any(AuditEvent.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    // When
    auditService.record(
        AuditEventType.INVENTORY_ITEM_CREATED,
        subjectId,
        "Quantanium @ Port Olisar",
        null,
        "qty=5.0");

    // Then
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
    // The mutation is counted once under the bounded INVENTORY domain (REQ-OBS-011).
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
    // Given — a scheduled UEX sync has no security context.
    when(authHelperService.currentUserId()).thenReturn(Optional.empty());
    when(auditEventRepository.save(any(AuditEvent.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    // When
    auditService.record(
        AuditEventType.REFINERY_METHODS_SYNCED, null, null, null, "source=UEX added=2 updated=1");

    // Then
    ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventRepository).save(saved.capture());
    AuditEvent row = saved.getValue();
    assertEquals("system", row.getActorHandle());
    assertEquals(AuditDomain.REFINERY, row.getDomain());
    assertNull(row.getActorUserId());
  }

  @Test
  void record_clampsOverlongSubjectLabel() {
    // Given
    when(authHelperService.currentUserId()).thenReturn(Optional.empty());
    when(auditEventRepository.save(any(AuditEvent.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    String overlong = "x".repeat(400);

    // When
    auditService.record(AuditEventType.JOB_ORDER_CREATED, UUID.randomUUID(), overlong, null, "d");

    // Then
    ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventRepository).save(saved.capture());
    assertEquals(255, saved.getValue().getSubjectLabel().length());
  }

  @Test
  void purgeBefore_deletesDomainRowsAndRecordsPurgeMarker() {
    // Given — a scheduled-cutoff retention purge of one area (REQ-AUDIT-004).
    Instant before = Instant.parse("2026-01-01T00:00:00Z");
    when(authHelperService.currentUserId()).thenReturn(Optional.empty());
    when(auditEventRepository.deleteByDomainAndOccurredAtBefore(AuditDomain.REFINERY, before))
        .thenReturn(7);
    when(auditEventRepository.save(any(AuditEvent.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    // When
    int deleted = auditService.purgeBefore(AuditDomain.REFINERY, before);

    // Then — the bulk delete count is returned, and a purge marker for the same domain is recorded
    // with the count + cutoff (the marker is newer than the cutoff, so it survives the purge).
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
    // Given
    Pageable pageable = Pageable.unpaged();
    AuditEvent event = AuditEvent.builder().domain(AuditDomain.JOB_ORDER).build();
    Page<AuditEvent> page = new PageImpl<>(java.util.List.of(event));
    when(auditEventRepository.findFiltered(
            eq(AuditDomain.JOB_ORDER), any(), any(), any(), any(), eq(pageable)))
        .thenReturn(page);

    // When
    Instant from = Instant.parse("2026-01-01T00:00:00Z");
    auditService.getEvents(AuditDomain.JOB_ORDER, from, null, null, null, pageable);

    // Then
    verify(auditEventRepository)
        .findFiltered(eq(AuditDomain.JOB_ORDER), eq(from), any(), any(), any(), eq(pageable));
    verify(auditEventMapper).toDto(event);
  }
}
