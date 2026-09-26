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
import de.greluc.krt.profit.basetool.backend.mapper.PromotionLevelContentMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.PromotionCategory;
import de.greluc.krt.profit.basetool.backend.model.PromotionLevel;
import de.greluc.krt.profit.basetool.backend.model.PromotionLevelContent;
import de.greluc.krt.profit.basetool.backend.model.PromotionTopic;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.dto.PromotionLevelContentResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PromotionLevelContentWriteRequest;
import de.greluc.krt.profit.basetool.backend.repository.PromotionCategoryRepository;
import de.greluc.krt.profit.basetool.backend.repository.PromotionLevelContentRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class PromotionLevelContentServiceTest {

  @Mock private PromotionLevelContentRepository repository;

  @Mock private PromotionCategoryRepository categoryRepository;

  @Mock private PromotionLevelContentMapper mapper;

  @Mock private OwnerScopeService ownerScopeService;

  @Mock private AuditService auditService;

  /**
   * Default-on the per-squadron promotion-feature flag so the existing fixtures that exercise the
   * "happy path" do not get short-circuited to empty by the new gate. {@code lenient()} keeps
   * Mockito from failing tests that never trigger the gate.
   */
  @BeforeEach
  void enablePromotionFeatureFlag() {
    lenient().when(ownerScopeService.isPromotionFeatureEnabledForCurrentScope()).thenReturn(true);
    lenient().when(ownerScopeService.hasPromotionReadAccess()).thenReturn(true);
  }

  @InjectMocks private PromotionLevelContentService service;

  @Test
  void listByCategory_shouldReturnContentsScopedToSquadron() {
    UUID categoryId = UUID.randomUUID();
    UUID scopeId = UUID.randomUUID();
    PromotionCategory category =
        PromotionCategory.builder().name("Flug Kenntnisse").sortOrder(0).build();
    PromotionLevelContent content =
        PromotionLevelContent.builder()
            .category(category)
            .level(PromotionLevel.LEVEL_A)
            .description("Kann fliegen")
            .build();
    PromotionLevelContentResponse response =
        new PromotionLevelContentResponse(
            UUID.randomUUID(),
            0L,
            categoryId,
            "Flug Kenntnisse",
            PromotionLevel.LEVEL_A,
            "Kann fliegen",
            null,
            null);
    when(ownerScopeService.currentSquadronId()).thenReturn(Optional.of(scopeId));
    when(repository.findAllByCategoryIdScopedOrdered(categoryId, scopeId))
        .thenReturn(List.of(content));
    when(mapper.toResponse(content)).thenReturn(response);

    List<PromotionLevelContentResponse> result = service.listByCategory(categoryId);

    assertEquals(1, result.size());
    assertEquals(PromotionLevel.LEVEL_A, result.get(0).level());
    verify(repository).findAllByCategoryIdScopedOrdered(categoryId, scopeId);
  }

  @Test
  void get_shouldRejectCrossSquadron() {
    UUID id = UUID.randomUUID();
    UUID squadronId = UUID.randomUUID();
    Squadron owner = new Squadron();
    owner.setId(squadronId);
    PromotionTopic topic = PromotionTopic.builder().name("Grundlagen").sortOrder(0).build();
    topic.setOwningSquadron(owner);
    PromotionCategory category =
        PromotionCategory.builder().topic(topic).name("Flug Kenntnisse").sortOrder(0).build();
    PromotionLevelContent entity =
        PromotionLevelContent.builder()
            .category(category)
            .level(PromotionLevel.LEVEL_A)
            .description("Kann fliegen")
            .build();
    when(repository.findById(id)).thenReturn(Optional.of(entity));
    when(ownerScopeService.canSeeSquadron(squadronId)).thenReturn(false);

    assertThrows(AccessDeniedException.class, () -> service.get(id));
  }

  @Test
  void create_shouldThrow_whenCategoryNotFound() {
    UUID categoryId = UUID.randomUUID();
    PromotionLevelContentWriteRequest request =
        new PromotionLevelContentWriteRequest(
            categoryId, PromotionLevel.LEVEL_A, "Beschreibung", null);
    when(categoryRepository.findById(categoryId)).thenReturn(Optional.empty());

    assertThrows(NotFoundException.class, () -> service.create(request));
  }

  @Test
  void create_shouldSaveAndReturn() {
    UUID categoryId = UUID.randomUUID();
    PromotionCategory category =
        PromotionCategory.builder().name("Flug Kenntnisse").sortOrder(0).build();
    PromotionLevelContentWriteRequest request =
        new PromotionLevelContentWriteRequest(
            categoryId, PromotionLevel.LEVEL_A, "Beschreibung", null);
    PromotionLevelContent entity =
        PromotionLevelContent.builder()
            .level(PromotionLevel.LEVEL_A)
            .description("Beschreibung")
            .build();
    PromotionLevelContentResponse response =
        new PromotionLevelContentResponse(
            UUID.randomUUID(),
            0L,
            categoryId,
            "Flug Kenntnisse",
            PromotionLevel.LEVEL_A,
            "Beschreibung",
            null,
            null);
    when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));
    when(mapper.toEntity(request)).thenReturn(entity);
    when(repository.save(entity)).thenReturn(entity);
    when(mapper.toResponse(entity)).thenReturn(response);

    PromotionLevelContentResponse result = service.create(request);

    assertEquals(PromotionLevel.LEVEL_A, result.level());
    verify(repository).save(entity);
    verify(auditService)
        .record(
            eq(AuditEventType.PROMOTION_LEVEL_CONTENT_CREATED),
            any(),
            eq("Flug Kenntnisse / LEVEL_A"),
            isNull(),
            isNull());
  }

  /**
   * Pins that {@code update} maps its response from a {@code saveAndFlush}, not a plain {@code
   * save}: the level-content textarea writes the returned {@code @Version} back onto its {@code
   * data-lc-version} attribute in place (no re-swap), so a stale {@code save} version would 409 the
   * next consecutive edit. The create path (unchanged) still uses {@code save}.
   */
  @Test
  void update_flushesSoResponseVersionIsFresh() {
    UUID id = UUID.randomUUID();
    UUID categoryId = UUID.randomUUID();
    PromotionCategory category =
        PromotionCategory.builder().name("Flug Kenntnisse").sortOrder(0).build();
    PromotionLevelContent entity =
        PromotionLevelContent.builder()
            .category(category)
            .level(PromotionLevel.LEVEL_A)
            .description("Kann fliegen")
            .build();
    entity.setVersion(4L);
    PromotionLevelContentResponse response =
        new PromotionLevelContentResponse(
            id,
            5L,
            categoryId,
            "Flug Kenntnisse",
            PromotionLevel.LEVEL_A,
            "Kann jetzt besser fliegen",
            null,
            null);
    PromotionLevelContentWriteRequest request =
        new PromotionLevelContentWriteRequest(
            categoryId, PromotionLevel.LEVEL_A, "Kann jetzt besser fliegen", 4L);
    when(repository.findById(id)).thenReturn(Optional.of(entity));
    when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));
    when(repository.saveAndFlush(entity)).thenReturn(entity);
    when(mapper.toResponse(entity)).thenReturn(response);

    service.update(id, request);

    verify(repository).saveAndFlush(entity);
    verify(repository, never()).save(entity);
    verify(auditService)
        .record(
            eq(AuditEventType.PROMOTION_LEVEL_CONTENT_UPDATED),
            any(),
            eq("Flug Kenntnisse / LEVEL_A"),
            isNull(),
            isNull());
  }

  @Test
  void delete_shouldCallRepositoryDelete() {
    UUID id = UUID.randomUUID();
    PromotionLevelContent entity =
        PromotionLevelContent.builder().level(PromotionLevel.LEVEL_B).description("Test").build();
    when(repository.findById(id)).thenReturn(Optional.of(entity));

    service.delete(id);

    verify(repository).delete(entity);
    verify(auditService)
        .record(
            eq(AuditEventType.PROMOTION_LEVEL_CONTENT_DELETED),
            eq(id),
            eq("LEVEL_B"),
            isNull(),
            isNull());
  }

  @Test
  void get_shouldThrow_whenNotFound() {
    UUID id = UUID.randomUUID();
    when(repository.findById(id)).thenReturn(Optional.empty());

    assertThrows(NotFoundException.class, () -> service.get(id));
  }
}
