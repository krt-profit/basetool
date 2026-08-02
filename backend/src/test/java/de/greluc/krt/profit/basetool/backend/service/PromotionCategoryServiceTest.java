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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.greluc.krt.profit.basetool.backend.mapper.PromotionCategoryMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.PromotionCategory;
import de.greluc.krt.profit.basetool.backend.model.PromotionTopic;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.dto.PromotionCategoryResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PromotionCategoryWriteRequest;
import de.greluc.krt.profit.basetool.backend.repository.PromotionCategoryRepository;
import de.greluc.krt.profit.basetool.backend.repository.PromotionTopicRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class PromotionCategoryServiceTest {

  @Mock private PromotionCategoryRepository repository;

  @Mock private PromotionTopicRepository topicRepository;

  @Mock private PromotionCategoryMapper mapper;

  @Mock private OwnerScopeService ownerScopeService;

  @Mock private AuditService auditService;

  @InjectMocks private PromotionCategoryService service;

  /**
   * Default-on the per-squadron promotion-feature flag so the existing fixtures that exercise the
   * "happy path" don't get short-circuited to empty by the new gate. {@code lenient()} keeps
   * Mockito from failing tests that never trigger the gate (e.g. the validation paths that throw
   * before the gate check).
   */
  @BeforeEach
  void enablePromotionFeatureFlag() {
    lenient().when(ownerScopeService.isPromotionFeatureEnabledForCurrentScope()).thenReturn(true);
    lenient().when(ownerScopeService.hasPromotionReadAccess()).thenReturn(true);
  }

  @Test
  void listAllByTopic_shouldReturnCategoriesScopedToSquadron() {
    // Given
    UUID topicId = UUID.randomUUID();
    UUID scopeId = UUID.randomUUID();
    PromotionTopic topic = PromotionTopic.builder().name("Grundlagen").sortOrder(0).build();
    PromotionCategory category =
        PromotionCategory.builder().topic(topic).name("Flug Kenntnisse").sortOrder(0).build();
    PromotionCategoryResponse response =
        new PromotionCategoryResponse(
            UUID.randomUUID(), 0L, topicId, "Grundlagen", "Flug Kenntnisse", null, 0, null, null);
    when(ownerScopeService.currentSquadronId()).thenReturn(Optional.of(scopeId));
    when(repository.findAllByTopicIdScopedOrdered(topicId, scopeId)).thenReturn(List.of(category));
    when(mapper.toResponse(category)).thenReturn(response);

    // When
    List<PromotionCategoryResponse> result = service.listAllByTopic(topicId);

    // Then: the active squadron is forwarded to the scoped finder.
    assertEquals(1, result.size());
    assertEquals("Flug Kenntnisse", result.get(0).name());
    verify(repository).findAllByTopicIdScopedOrdered(topicId, scopeId);
  }

  @Test
  void get_shouldThrow_whenNotFound() {
    // Given
    UUID id = UUID.randomUUID();
    when(repository.findById(id)).thenReturn(Optional.empty());

    // When / Then
    assertThrows(EntityNotFoundException.class, () -> service.get(id));
  }

  @Test
  void get_shouldRejectCrossSquadron() {
    // Given: a category whose topic is owned by a squadron the caller may not see.
    UUID id = UUID.randomUUID();
    UUID squadronId = UUID.randomUUID();
    Squadron owner = new Squadron();
    owner.setId(squadronId);
    PromotionTopic topic = PromotionTopic.builder().name("Grundlagen").sortOrder(0).build();
    topic.setOwningSquadron(owner);
    PromotionCategory entity =
        PromotionCategory.builder().topic(topic).name("Flug Kenntnisse").sortOrder(0).build();
    when(repository.findById(id)).thenReturn(Optional.of(entity));
    when(ownerScopeService.canSeeSquadron(squadronId)).thenReturn(false);

    // When / Then
    assertThrows(AccessDeniedException.class, () -> service.get(id));
  }

  @Test
  void create_shouldThrow_whenTopicNotFound() {
    // Given
    UUID topicId = UUID.randomUUID();
    PromotionCategoryWriteRequest request =
        new PromotionCategoryWriteRequest(topicId, "Flug Kenntnisse", null, 0, null);
    when(topicRepository.findById(topicId)).thenReturn(Optional.empty());

    // When / Then
    assertThrows(EntityNotFoundException.class, () -> service.create(request));
  }

  @Test
  void create_shouldSaveAndReturnCategory() {
    // Given
    UUID topicId = UUID.randomUUID();
    PromotionTopic topic = PromotionTopic.builder().name("Grundlagen").sortOrder(0).build();
    PromotionCategoryWriteRequest request =
        new PromotionCategoryWriteRequest(topicId, "Flug Kenntnisse", null, 0, null);
    PromotionCategory entity =
        PromotionCategory.builder().name("Flug Kenntnisse").sortOrder(0).build();
    PromotionCategoryResponse response =
        new PromotionCategoryResponse(
            UUID.randomUUID(), 0L, topicId, "Grundlagen", "Flug Kenntnisse", null, 0, null, null);
    when(topicRepository.findById(topicId)).thenReturn(Optional.of(topic));
    when(mapper.toEntity(request)).thenReturn(entity);
    when(repository.save(entity)).thenReturn(entity);
    when(mapper.toResponse(entity)).thenReturn(response);

    // When
    PromotionCategoryResponse result = service.create(request);

    // Then
    assertEquals("Flug Kenntnisse", result.name());
    verify(repository).save(entity);
    verify(auditService)
        .record(
            eq(AuditEventType.PROMOTION_CATEGORY_CREATED),
            any(),
            eq("Grundlagen / Flug Kenntnisse"),
            isNull(),
            isNull());
  }

  /**
   * REQ-OBS-004 / CWE-117: the category name is admin-entered free text that reaches the creation
   * log line verbatim. A newline plus a fabricated level prefix would otherwise read as a genuine
   * second log line during triage, so the value must go through {@code LogSafe} first.
   */
  @Test
  void create_sanitisesTheCategoryNameBeforeLoggingIt() {
    UUID topicId = UUID.randomUUID();
    String forged = "Flug\nERROR --- forged line";
    PromotionTopic topic = PromotionTopic.builder().name("Grundlagen").sortOrder(0).build();
    PromotionCategoryWriteRequest request =
        new PromotionCategoryWriteRequest(topicId, forged, null, 0, null);
    PromotionCategory entity = PromotionCategory.builder().name(forged).sortOrder(0).build();
    PromotionCategoryResponse response =
        new PromotionCategoryResponse(
            UUID.randomUUID(), 0L, topicId, "Grundlagen", forged, null, 0, null, null);
    when(topicRepository.findById(topicId)).thenReturn(Optional.of(topic));
    when(mapper.toEntity(request)).thenReturn(entity);
    when(repository.save(entity)).thenReturn(entity);
    when(mapper.toResponse(entity)).thenReturn(response);

    Logger logger = (Logger) LoggerFactory.getLogger(PromotionCategoryService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      service.create(request);

      String line =
          appender.list.stream()
              .map(ILoggingEvent::getFormattedMessage)
              .filter(m -> m.contains("Created PromotionCategory"))
              .findFirst()
              .orElseThrow(() -> new AssertionError("expected the creation log line"));
      assertFalse(line.contains("\n"), "no control character may survive into the log: " + line);
      assertTrue(line.contains("Flug?ERROR"), "the value is kept, only neutralised: " + line);
    } finally {
      logger.detachAppender(appender);
    }
  }

  @Test
  void update_shouldThrow_whenVersionMismatch() {
    // Given
    UUID id = UUID.randomUUID();
    UUID topicId = UUID.randomUUID();
    PromotionCategory entity =
        PromotionCategory.builder().name("Flug Kenntnisse").sortOrder(0).build();
    entity.setVersion(1L);
    PromotionCategoryWriteRequest request =
        new PromotionCategoryWriteRequest(topicId, "Flug Kenntnisse neu", null, 1, 0L);
    when(repository.findById(id)).thenReturn(Optional.of(entity));

    // When / Then
    assertThrows(ObjectOptimisticLockingFailureException.class, () -> service.update(id, request));
  }

  @Test
  void delete_shouldCallRepositoryDelete() {
    // Given
    UUID id = UUID.randomUUID();
    PromotionCategory entity =
        PromotionCategory.builder().name("Flug Kenntnisse").sortOrder(0).build();
    when(repository.findById(id)).thenReturn(Optional.of(entity));

    // When
    service.delete(id);

    // Then
    verify(repository).delete(entity);
    verify(auditService)
        .record(
            eq(AuditEventType.PROMOTION_CATEGORY_DELETED),
            eq(id),
            eq("Flug Kenntnisse"),
            isNull(),
            isNull());
  }
}
