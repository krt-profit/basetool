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

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.RankRequirementMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.PromotionLevel;
import de.greluc.krt.profit.basetool.backend.model.PromotionTopic;
import de.greluc.krt.profit.basetool.backend.model.RankRequirement;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.dto.RankRequirementResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.RankRequirementWriteRequest;
import de.greluc.krt.profit.basetool.backend.repository.PromotionCategoryRepository;
import de.greluc.krt.profit.basetool.backend.repository.PromotionTopicRepository;
import de.greluc.krt.profit.basetool.backend.repository.RankRequirementRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class RankRequirementServiceTest {

  @Mock private RankRequirementRepository repository;

  @Mock private PromotionTopicRepository topicRepository;

  @Mock private PromotionCategoryRepository categoryRepository;

  @Mock private RankRequirementMapper mapper;

  @Mock private OwnerScopeService ownerScopeService;

  @Mock private AuditService auditService;

  @InjectMocks private RankRequirementService service;

  private static final UUID SQUADRON_ID = UUID.randomUUID();

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

  private static Squadron squadron(UUID id) {
    Squadron s = new Squadron();
    s.setId(id);
    s.setName("Test");
    s.setShorthand("TST");
    return s;
  }

  private static RankRequirementResponse anyResponse() {
    return new RankRequirementResponse(
        UUID.randomUUID(),
        0L,
        20,
        19,
        null,
        null,
        null,
        null,
        PromotionLevel.LEVEL_A,
        3,
        null,
        null,
        null);
  }

  @Test
  void list_shouldScopeByCurrentSquadron() {
    // Given
    Pageable pageable = PageRequest.of(0, 20);
    RankRequirement req =
        RankRequirement.builder()
            .fromRank(20)
            .toRank(19)
            .minimumLevel(PromotionLevel.LEVEL_A)
            .requiredCount(3)
            .build();
    when(ownerScopeService.currentSquadronId()).thenReturn(Optional.of(SQUADRON_ID));
    when(repository.findAllScoped(SQUADRON_ID, pageable))
        .thenReturn(new PageImpl<>(List.of(req), pageable, 1));
    when(mapper.toResponse(req)).thenReturn(anyResponse());

    // When
    service.list(pageable);

    // Then: the active squadron is passed straight to the scoped finder.
    verify(repository).findAllScoped(SQUADRON_ID, pageable);
    verify(repository, never()).findAll(any(Pageable.class));
  }

  @Test
  void listByRanks_shouldReturnMappedRequirementsScopedToSquadron() {
    // Given
    RankRequirement req =
        RankRequirement.builder()
            .fromRank(20)
            .toRank(19)
            .minimumLevel(PromotionLevel.LEVEL_A)
            .requiredCount(3)
            .build();
    when(ownerScopeService.currentSquadronId()).thenReturn(Optional.of(SQUADRON_ID));
    when(repository.findScopedByFromRankAndToRank(20, 19, SQUADRON_ID)).thenReturn(List.of(req));
    when(mapper.toResponse(req)).thenReturn(anyResponse());

    // When
    List<RankRequirementResponse> result = service.listByRanks(20, 19);

    // Then
    assertEquals(1, result.size());
    verify(repository).findScopedByFromRankAndToRank(20, 19, SQUADRON_ID);
  }

  @Test
  void create_shouldStampOwningSquadronAndSave_global() {
    // Given: a "global" requirement (no topic, no category) — valid, scoped to the active squadron.
    RankRequirementWriteRequest request =
        new RankRequirementWriteRequest(
            20, 19, null, null, PromotionLevel.LEVEL_A, 3, "Global", null);
    RankRequirement entity =
        RankRequirement.builder()
            .fromRank(20)
            .toRank(19)
            .minimumLevel(PromotionLevel.LEVEL_A)
            .requiredCount(3)
            .build();
    Squadron squadron = squadron(SQUADRON_ID);
    when(ownerScopeService.currentSquadron()).thenReturn(Optional.of(squadron));
    when(mapper.toEntity(request)).thenReturn(entity);
    when(repository.save(entity)).thenReturn(entity);
    when(mapper.toResponse(entity)).thenReturn(anyResponse());

    // When
    service.create(request);

    // Then: the owner is stamped from the active squadron context.
    assertSame(squadron, entity.getOwningSquadron());
    verify(repository).save(entity);
    verify(auditService)
        .record(
            eq(AuditEventType.PROMOTION_RANK_REQUIREMENT_CREATED),
            any(),
            eq("20->19"),
            isNull(),
            eq("level=LEVEL_A count=3"));
  }

  @Test
  void create_shouldRejectWhenNoActiveSquadron() {
    // Given: admin in "all squadrons" mode (no pin) → no squadron to stamp.
    RankRequirementWriteRequest request =
        new RankRequirementWriteRequest(20, 19, null, null, PromotionLevel.LEVEL_A, 3, null, null);
    when(ownerScopeService.currentSquadron()).thenReturn(Optional.empty());

    // When / Then
    assertThrows(BadRequestException.class, () -> service.create(request));
    verify(repository, never()).save(any());
  }

  @Test
  void create_shouldRejectCrossSquadronTopicReference() {
    // Given: the caller is scoped to SQUADRON_ID but references a topic owned by another squadron.
    UUID foreignSquadronId = UUID.randomUUID();
    PromotionTopic foreignTopic = new PromotionTopic();
    foreignTopic.setId(UUID.randomUUID());
    foreignTopic.setOwningSquadron(squadron(foreignSquadronId));
    RankRequirementWriteRequest request =
        new RankRequirementWriteRequest(
            20, 19, foreignTopic.getId(), null, PromotionLevel.LEVEL_A, 3, null, null);
    RankRequirement entity =
        RankRequirement.builder()
            .fromRank(20)
            .toRank(19)
            .minimumLevel(PromotionLevel.LEVEL_A)
            .requiredCount(3)
            .build();
    when(ownerScopeService.currentSquadron()).thenReturn(Optional.of(squadron(SQUADRON_ID)));
    when(mapper.toEntity(request)).thenReturn(entity);
    when(topicRepository.findById(foreignTopic.getId())).thenReturn(Optional.of(foreignTopic));

    // When / Then
    assertThrows(BadRequestException.class, () -> service.create(request));
    verify(repository, never()).save(any());
  }

  @Test
  void create_shouldRejectMultiStepPromotion() {
    // Given
    RankRequirementWriteRequest request =
        new RankRequirementWriteRequest(20, 18, null, null, PromotionLevel.LEVEL_A, 1, null, null);

    // When / Then
    BadRequestException ex = assertThrows(BadRequestException.class, () -> service.create(request));
    assertEquals("error.rank_requirement.invalid_step", ex.getMessage());
    verifyNoInteractions(repository, mapper, topicRepository, categoryRepository);
  }

  @Test
  void create_shouldRejectReversePromotion() {
    // Given: lower-numbered fromRank than toRank (would be a demotion)
    RankRequirementWriteRequest request =
        new RankRequirementWriteRequest(19, 20, null, null, PromotionLevel.LEVEL_A, 1, null, null);

    // When / Then
    assertThrows(BadRequestException.class, () -> service.create(request));
    verifyNoInteractions(repository, mapper, topicRepository, categoryRepository);
  }

  @Test
  void get_shouldThrow_whenNotFound() {
    // Given
    UUID id = UUID.randomUUID();
    when(repository.findById(id)).thenReturn(Optional.empty());

    // When / Then
    assertThrows(NotFoundException.class, () -> service.get(id));
  }

  @Test
  void get_shouldRejectCrossSquadron() {
    // Given: a requirement owned by a squadron the caller may not see.
    UUID id = UUID.randomUUID();
    RankRequirement entity =
        RankRequirement.builder()
            .fromRank(20)
            .toRank(19)
            .minimumLevel(PromotionLevel.LEVEL_A)
            .requiredCount(3)
            .build();
    entity.setOwningSquadron(squadron(SQUADRON_ID));
    when(repository.findById(id)).thenReturn(Optional.of(entity));
    when(ownerScopeService.canSeeSquadron(SQUADRON_ID)).thenReturn(false);

    // When / Then
    assertThrows(AccessDeniedException.class, () -> service.get(id));
  }

  @Test
  void update_shouldThrow_whenVersionMismatch() {
    // Given
    UUID id = UUID.randomUUID();
    RankRequirement entity =
        RankRequirement.builder()
            .fromRank(20)
            .toRank(19)
            .minimumLevel(PromotionLevel.LEVEL_A)
            .requiredCount(3)
            .build();
    entity.setOwningSquadron(squadron(SQUADRON_ID));
    entity.setVersion(1L);
    var request =
        new RankRequirementWriteRequest(20, 19, null, null, PromotionLevel.LEVEL_A, 3, null, 0L);
    when(repository.findById(id)).thenReturn(Optional.of(entity));
    when(ownerScopeService.canEditSquadron(SQUADRON_ID)).thenReturn(true);

    // When / Then
    assertThrows(ObjectOptimisticLockingFailureException.class, () -> service.update(id, request));
  }

  @Test
  void update_shouldRejectMultiStepPromotion() {
    // Given
    UUID id = UUID.randomUUID();
    var request =
        new RankRequirementWriteRequest(20, 18, null, null, PromotionLevel.LEVEL_A, 1, null, 0L);

    // When / Then
    BadRequestException ex =
        assertThrows(BadRequestException.class, () -> service.update(id, request));
    assertEquals("error.rank_requirement.invalid_step", ex.getMessage());
    // The repository must not even be hit when input validation fails.
    verifyNoInteractions(repository, mapper, topicRepository, categoryRepository);
  }

  @Test
  void delete_shouldCallRepositoryDelete() {
    // Given
    UUID id = UUID.randomUUID();
    RankRequirement entity =
        RankRequirement.builder()
            .fromRank(20)
            .toRank(19)
            .minimumLevel(PromotionLevel.LEVEL_A)
            .requiredCount(3)
            .build();
    entity.setOwningSquadron(squadron(SQUADRON_ID));
    when(repository.findById(id)).thenReturn(Optional.of(entity));
    when(ownerScopeService.canEditSquadron(SQUADRON_ID)).thenReturn(true);

    // When
    service.delete(id);

    // Then
    verify(repository).delete(entity);
    verify(auditService)
        .record(
            eq(AuditEventType.PROMOTION_RANK_REQUIREMENT_DELETED),
            eq(id),
            eq("20->19"),
            isNull(),
            isNull());
  }

  @Test
  void delete_shouldRejectCrossSquadron() {
    // Given: a requirement owned by a squadron the caller may not edit.
    UUID id = UUID.randomUUID();
    RankRequirement entity =
        RankRequirement.builder()
            .fromRank(20)
            .toRank(19)
            .minimumLevel(PromotionLevel.LEVEL_A)
            .requiredCount(3)
            .build();
    entity.setOwningSquadron(squadron(SQUADRON_ID));
    when(repository.findById(id)).thenReturn(Optional.of(entity));
    when(ownerScopeService.canEditSquadron(SQUADRON_ID)).thenReturn(false);

    // When / Then
    assertThrows(AccessDeniedException.class, () -> service.delete(id));
    verify(repository, never()).delete(any());
  }
}
