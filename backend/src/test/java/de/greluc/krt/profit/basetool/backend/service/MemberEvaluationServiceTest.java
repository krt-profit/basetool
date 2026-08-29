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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.mapper.MemberEvaluationMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.MemberEvaluation;
import de.greluc.krt.profit.basetool.backend.model.PromotionCategory;
import de.greluc.krt.profit.basetool.backend.model.PromotionLevel;
import de.greluc.krt.profit.basetool.backend.model.dto.MemberEvaluationResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.MemberEvaluationUpdateRequest;
import de.greluc.krt.profit.basetool.backend.repository.MemberEvaluationRepository;
import de.greluc.krt.profit.basetool.backend.repository.PromotionCategoryRepository;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class MemberEvaluationServiceTest {

  /** Stand-in {@code app_user.id}s; the column is a UUID foreign key since V235. */
  private static final UUID USER_A = UUID.fromString("00000000-0000-4000-8000-0000000000aa");

  private static final UUID USER_B = UUID.fromString("00000000-0000-4000-8000-0000000000bb");

  @Mock private MemberEvaluationRepository repository;

  @Mock private PromotionCategoryRepository categoryRepository;

  @Mock private MemberEvaluationMapper mapper;

  @Mock private OwnerScopeService ownerScopeService;

  @Mock private OrgUnitMembershipQueryService orgUnitMembershipQueryService;

  @Mock private AuthHelperService authHelperService;

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

  @InjectMocks private MemberEvaluationService service;

  @Test
  void listForUser_shouldOnlyReturnOwnEvaluationsScopedToSquadron() {
    // Given – data isolation: only evaluations for "user-A" in the active squadron are returned
    UUID userA = USER_A;
    UUID userB = USER_B;
    UUID scopeId = UUID.randomUUID();
    MemberEvaluation evalA =
        MemberEvaluation.builder().userId(userA).assignedLevel(PromotionLevel.LEVEL_A).build();
    MemberEvaluationResponse responseA =
        new MemberEvaluationResponse(
            UUID.randomUUID(),
            0L,
            userA,
            UUID.randomUUID(),
            "Cat",
            UUID.randomUUID(),
            "Topic",
            PromotionLevel.LEVEL_A,
            null,
            null);
    when(ownerScopeService.currentSquadronId()).thenReturn(Optional.of(scopeId));
    when(repository.findAllByUserIdScoped(userA, scopeId)).thenReturn(List.of(evalA));
    when(mapper.toResponse(evalA)).thenReturn(responseA);

    // When
    List<MemberEvaluationResponse> result = service.listForUser(userA);

    // Then – only user-A's evaluations are returned, scoped to the active squadron; user-B's are
    // never fetched.
    assertEquals(1, result.size());
    assertEquals(userA, result.get(0).userId());
    verify(repository).findAllByUserIdScoped(userA, scopeId);
    verify(repository, never()).findAllByUserIdScoped(eq(userB), any());
  }

  @Test
  void upsert_shouldCreateNewEvaluation_whenNoneExists() {
    // Given
    UUID userId = USER_A;
    UUID categoryId = UUID.randomUUID();
    PromotionCategory category =
        PromotionCategory.builder().name("Flug Kenntnisse").sortOrder(0).build();
    MemberEvaluationUpdateRequest request =
        new MemberEvaluationUpdateRequest(null, PromotionLevel.LEVEL_B);
    MemberEvaluation saved =
        MemberEvaluation.builder()
            .userId(userId)
            .category(category)
            .assignedLevel(PromotionLevel.LEVEL_B)
            .build();
    MemberEvaluationResponse response =
        new MemberEvaluationResponse(
            UUID.randomUUID(),
            0L,
            userId,
            categoryId,
            "Flug Kenntnisse",
            UUID.randomUUID(),
            "Grundlagen",
            PromotionLevel.LEVEL_B,
            null,
            null);
    when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));
    when(repository.findByUserIdAndCategoryId(userId, categoryId)).thenReturn(Optional.empty());
    when(repository.save(any(MemberEvaluation.class))).thenReturn(saved);
    when(mapper.toResponse(saved)).thenReturn(response);
    when(authHelperService.isAdmin()).thenReturn(true);

    // When
    MemberEvaluationResponse result = service.upsert(userId, categoryId, request);

    // Then
    assertEquals(PromotionLevel.LEVEL_B, result.assignedLevel());
    verify(repository).save(any(MemberEvaluation.class));
    // A brand-new (user, category) grading records a CREATED event; the id-less fixture category
    // leaves the subject id null, while the target now always carries the evaluated member's id --
    // it used to be null here because the fixture's sub was an unparseable string and the service
    // swallowed that (V235 / ADR-0142 made the column a UUID, so there is nothing left to fail).
    verify(auditService)
        .record(
            eq(AuditEventType.PROMOTION_EVALUATION_CREATED),
            isNull(),
            eq("Flug Kenntnisse"),
            eq(userId),
            argThat(d -> d != null && d.toString().equals("level=LEVEL_B")));
  }

  @Test
  void upsert_shouldRecordUpdated_whenExistingEvaluationVersionMatches() {
    // Given: an existing grading for a member with a parseable sub; the version matches so the
    // upsert overwrites the level and records an UPDATED event carrying the member as target.
    UUID userId = UUID.randomUUID();
    UUID categoryId = UUID.randomUUID();
    UUID evalId = UUID.randomUUID();
    PromotionCategory category =
        PromotionCategory.builder().name("Flug Kenntnisse").sortOrder(0).build();
    MemberEvaluation existing =
        MemberEvaluation.builder()
            .userId(userId)
            .category(category)
            .assignedLevel(PromotionLevel.LEVEL_A)
            .build();
    existing.setId(evalId);
    existing.setVersion(2L);
    MemberEvaluationUpdateRequest request =
        new MemberEvaluationUpdateRequest(2L, PromotionLevel.LEVEL_B);
    MemberEvaluationResponse response =
        new MemberEvaluationResponse(
            evalId,
            3L,
            userId,
            categoryId,
            "Flug Kenntnisse",
            UUID.randomUUID(),
            "Grundlagen",
            PromotionLevel.LEVEL_B,
            null,
            null);
    when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));
    when(repository.findByUserIdAndCategoryId(userId, categoryId))
        .thenReturn(Optional.of(existing));
    when(authHelperService.isAdmin()).thenReturn(true);
    when(repository.save(existing)).thenReturn(existing);
    when(mapper.toResponse(existing)).thenReturn(response);

    // When
    service.upsert(userId, categoryId, request);

    // Then
    verify(auditService)
        .record(
            eq(AuditEventType.PROMOTION_EVALUATION_UPDATED),
            isNull(),
            eq("Flug Kenntnisse"),
            eq(userId),
            argThat(d -> d != null && d.toString().equals("level=LEVEL_B")));
  }

  @Test
  void upsert_shouldThrow_whenVersionMismatch() {
    // Given
    UUID userId = USER_A;
    UUID categoryId = UUID.randomUUID();
    UUID evalId = UUID.randomUUID();
    PromotionCategory category =
        PromotionCategory.builder().name("Flug Kenntnisse").sortOrder(0).build();
    MemberEvaluation existing =
        MemberEvaluation.builder()
            .userId(userId)
            .category(category)
            .assignedLevel(PromotionLevel.LEVEL_A)
            .build();
    existing.setId(evalId);
    existing.setVersion(1L);
    MemberEvaluationUpdateRequest request =
        new MemberEvaluationUpdateRequest(0L, PromotionLevel.LEVEL_B);
    when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));
    when(repository.findByUserIdAndCategoryId(userId, categoryId))
        .thenReturn(Optional.of(existing));
    when(authHelperService.isAdmin()).thenReturn(true);

    // When / Then
    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> service.upsert(userId, categoryId, request));
  }

  @Test
  void upsert_shouldThrow_whenCategoryNotFound() {
    // Given
    UUID categoryId = UUID.randomUUID();
    when(categoryRepository.findById(categoryId)).thenReturn(Optional.empty());

    // When / Then
    assertThrows(
        EntityNotFoundException.class,
        () ->
            service.upsert(
                USER_A,
                categoryId,
                new MemberEvaluationUpdateRequest(null, PromotionLevel.LEVEL_A)));
  }

  @Test
  void upsert_shouldDenyOfficer_evaluatingForeignSquadronMember() {
    // Gap-fill security audit: an OFFICER may not write an evaluation for a member whose home
    // Staffel is outside the caller's editable scope, even when the category belongs to the
    // officer's own squadron. Without the target-member scope check this was a cross-tenant write.
    UUID foreignMemberId = UUID.randomUUID();
    UUID categoryId = UUID.randomUUID();
    UUID foreignStaffelId = UUID.randomUUID();
    PromotionCategory category =
        PromotionCategory.builder().name("Flug Kenntnisse").sortOrder(0).build();
    MemberEvaluationUpdateRequest request =
        new MemberEvaluationUpdateRequest(null, PromotionLevel.LEVEL_B);

    when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));
    when(authHelperService.isAdmin()).thenReturn(false);
    when(orgUnitMembershipQueryService.findStaffelMembershipOrgUnitIds(foreignMemberId))
        .thenReturn(java.util.List.of(foreignStaffelId));
    when(ownerScopeService.canEditSquadron(foreignStaffelId)).thenReturn(false);

    // When / Then
    assertThrows(
        AccessDeniedException.class, () -> service.upsert(foreignMemberId, categoryId, request));
    verify(repository, never()).save(any(MemberEvaluation.class));
  }

  @Test
  void upsert_shouldAllowOfficer_evaluatingOwnSquadronMember() {
    // The legitimate path: an OFFICER evaluating a member of a Staffel within their editable scope.
    UUID memberId = UUID.randomUUID();
    UUID categoryId = UUID.randomUUID();
    UUID ownStaffelId = UUID.randomUUID();
    PromotionCategory category =
        PromotionCategory.builder().name("Flug Kenntnisse").sortOrder(0).build();
    MemberEvaluationUpdateRequest request =
        new MemberEvaluationUpdateRequest(null, PromotionLevel.LEVEL_B);
    MemberEvaluation saved =
        MemberEvaluation.builder()
            .userId(memberId)
            .category(category)
            .assignedLevel(PromotionLevel.LEVEL_B)
            .build();
    MemberEvaluationResponse response =
        new MemberEvaluationResponse(
            UUID.randomUUID(),
            0L,
            memberId,
            categoryId,
            "Flug Kenntnisse",
            UUID.randomUUID(),
            "Grundlagen",
            PromotionLevel.LEVEL_B,
            null,
            null);

    when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));
    when(authHelperService.isAdmin()).thenReturn(false);
    when(orgUnitMembershipQueryService.findStaffelMembershipOrgUnitIds(memberId))
        .thenReturn(java.util.List.of(ownStaffelId));
    when(ownerScopeService.canEditSquadron(ownStaffelId)).thenReturn(true);
    when(repository.findByUserIdAndCategoryId(memberId, categoryId)).thenReturn(Optional.empty());
    when(repository.save(any(MemberEvaluation.class))).thenReturn(saved);
    when(mapper.toResponse(saved)).thenReturn(response);

    // When
    MemberEvaluationResponse result = service.upsert(memberId, categoryId, request);

    // Then
    assertEquals(PromotionLevel.LEVEL_B, result.assignedLevel());
    verify(repository).save(any(MemberEvaluation.class));
  }

  @Test
  void delete_shouldCallRepositoryDelete() {
    // Given
    UUID id = UUID.randomUUID();
    MemberEvaluation entity =
        MemberEvaluation.builder().userId(USER_A).assignedLevel(PromotionLevel.LEVEL_A).build();
    when(repository.findById(id)).thenReturn(Optional.of(entity));
    when(authHelperService.isAdmin()).thenReturn(true);

    // When
    service.delete(id);

    // Then
    verify(repository).delete(entity);
    verify(auditService)
        .record(eq(AuditEventType.PROMOTION_EVALUATION_DELETED), any(), any(), any(), isNull());
  }
}
