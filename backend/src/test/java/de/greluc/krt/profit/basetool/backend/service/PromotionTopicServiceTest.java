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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.PromotionTopicMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.PromotionTopic;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.dto.PromotionTopicResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PromotionTopicWriteRequest;
import de.greluc.krt.profit.basetool.backend.repository.PromotionTopicRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class PromotionTopicServiceTest {

  @Mock private PromotionTopicRepository repository;

  @Mock private PromotionTopicMapper mapper;

  @Mock private OwnerScopeService ownerScopeService;

  @Mock private AuditService auditService;

  @InjectMocks private PromotionTopicService service;

  /**
   * Default-on the per-squadron promotion-feature flag so the existing fixtures that exercise the
   * "happy path" do not get short-circuited to empty by the new gate. {@code lenient()} keeps
   * Mockito from failing tests that never trigger the gate (e.g. the validation paths that throw
   * before the gate check).
   */
  @BeforeEach
  void enablePromotionFeatureFlag() {
    lenient().when(ownerScopeService.isPromotionFeatureEnabledForCurrentScope()).thenReturn(true);
    lenient().when(ownerScopeService.hasPromotionReadAccess()).thenReturn(true);
  }

  @Test
  void listAll_shouldReturnMappedTopics() {
    PromotionTopic topic = PromotionTopic.builder().name("Grundlagen").sortOrder(0).build();
    PromotionTopicResponse response =
        new PromotionTopicResponse(UUID.randomUUID(), 0L, "Grundlagen", null, 0, null, null, null);
    when(ownerScopeService.currentSquadronId()).thenReturn(Optional.empty());
    when(repository.findAllScoped((UUID) null)).thenReturn(List.of(topic));
    when(mapper.toResponse(topic)).thenReturn(response);

    List<PromotionTopicResponse> result = service.listAll();

    assertEquals(1, result.size());
    assertEquals("Grundlagen", result.get(0).name());
  }

  @Test
  void get_shouldReturnTopic_whenFound() {
    UUID id = UUID.randomUUID();
    PromotionTopic topic = PromotionTopic.builder().name("Grundlagen").sortOrder(0).build();
    PromotionTopicResponse response =
        new PromotionTopicResponse(id, 0L, "Grundlagen", null, 0, null, null, null);
    when(repository.findById(id)).thenReturn(Optional.of(topic));
    when(mapper.toResponse(topic)).thenReturn(response);

    PromotionTopicResponse result = service.get(id);

    assertEquals("Grundlagen", result.name());
  }

  @Test
  void get_shouldThrow_whenNotFound() {
    UUID id = UUID.randomUUID();
    when(repository.findById(id)).thenReturn(Optional.empty());

    assertThrows(NotFoundException.class, () -> service.get(id));
  }

  @Test
  void create_shouldSaveAndReturnTopic() {
    PromotionTopicWriteRequest request =
        new PromotionTopicWriteRequest("Grundlagen", null, 0, null);
    PromotionTopic entity = PromotionTopic.builder().name("Grundlagen").sortOrder(0).build();
    PromotionTopicResponse response =
        new PromotionTopicResponse(UUID.randomUUID(), 0L, "Grundlagen", null, 0, null, null, null);
    Squadron squadron = new Squadron();
    squadron.setId(UUID.randomUUID());
    squadron.setShorthand("IRI");
    when(ownerScopeService.currentSquadron()).thenReturn(Optional.of(squadron));
    when(mapper.toEntity(request)).thenReturn(entity);
    when(repository.save(entity)).thenReturn(entity);
    when(mapper.toResponse(entity)).thenReturn(response);

    PromotionTopicResponse result = service.create(request);

    assertEquals("Grundlagen", result.name());
    verify(repository).save(entity);
    verify(auditService)
        .record(
            eq(AuditEventType.PROMOTION_TOPIC_CREATED),
            any(),
            eq("Grundlagen"),
            isNull(),
            isNull());
  }

  @Test
  void update_shouldThrow_whenVersionMismatch() {
    UUID id = UUID.randomUUID();
    PromotionTopic entity = PromotionTopic.builder().name("Grundlagen").sortOrder(0).build();
    entity.setVersion(1L);
    PromotionTopicWriteRequest request =
        new PromotionTopicWriteRequest("Grundlagen neu", null, 1, 0L);
    when(repository.findById(id)).thenReturn(Optional.of(entity));

    assertThrows(ObjectOptimisticLockingFailureException.class, () -> service.update(id, request));
  }

  @Test
  void update_shouldUpdateAndReturn_whenVersionMatches() {
    UUID id = UUID.randomUUID();
    PromotionTopic entity = PromotionTopic.builder().name("Grundlagen").sortOrder(0).build();
    entity.setVersion(0L);
    PromotionTopicWriteRequest request =
        new PromotionTopicWriteRequest("Grundlagen neu", null, 1, 0L);
    PromotionTopicResponse response =
        new PromotionTopicResponse(id, 1L, "Grundlagen neu", null, 1, null, null, null);
    when(repository.findById(id)).thenReturn(Optional.of(entity));
    when(repository.save(entity)).thenReturn(entity);
    when(mapper.toResponse(entity)).thenReturn(response);

    PromotionTopicResponse result = service.update(id, request);

    assertEquals("Grundlagen neu", result.name());
    verify(mapper).updateEntity(entity, request);
    verify(auditService)
        .record(eq(AuditEventType.PROMOTION_TOPIC_UPDATED), any(), any(), isNull(), isNull());
  }

  @Test
  void delete_shouldCallRepositoryDelete() {
    UUID id = UUID.randomUUID();
    PromotionTopic entity = PromotionTopic.builder().name("Grundlagen").sortOrder(0).build();
    when(repository.findById(id)).thenReturn(Optional.of(entity));

    service.delete(id);

    verify(repository).delete(entity);
    verify(auditService)
        .record(
            eq(AuditEventType.PROMOTION_TOPIC_DELETED),
            eq(id),
            eq("Grundlagen"),
            isNull(),
            isNull());
  }
}
