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

import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.mapper.MemberEvaluationMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.MemberEvaluation;
import de.greluc.krt.profit.basetool.backend.model.PromotionCategory;
import de.greluc.krt.profit.basetool.backend.model.dto.MemberEvaluationResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.MemberEvaluationUpdateRequest;
import de.greluc.krt.profit.basetool.backend.repository.MemberEvaluationRepository;
import de.greluc.krt.profit.basetool.backend.repository.PromotionCategoryRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain service for {@link MemberEvaluation}.
 *
 * <p>Writes are limited to ADMIN and OFFICER; a non-admin must pass both {@link
 * #assertCallerMayEditCategory} and {@link #assertCallerMayEvaluateUser}. Personal reads are
 * filtered by the caller's user id.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class MemberEvaluationService {

  public static final Set<String> SORTABLE_FIELDS =
      Set.of("id", "userId", "assignedLevel", "createdAt", "updatedAt");
  public static final String DEFAULT_SORT_FIELD = "updatedAt";

  private final MemberEvaluationRepository repository;
  private final PromotionCategoryRepository categoryRepository;
  private final MemberEvaluationMapper mapper;
  private final OwnerScopeService ownerScopeService;
  private final OrgUnitMembershipQueryService orgUnitMembershipQueryService;
  private final AuthHelperService authHelperService;
  private final AuditService auditService;

  /**
   * Returns all evaluations for the given user (JWT-sub filtered – data isolation), additionally
   * scoped to the caller's active squadron so a multi-squadron member's "my evaluations" only shows
   * the active squadron's grades.
   */
  public List<MemberEvaluationResponse> listForUser(@NotNull UUID userId) {
    if (!ownerScopeService.isPromotionFeatureEnabledForCurrentScope()
        || !ownerScopeService.hasPromotionReadAccess()) {
      return List.of();
    }
    UUID scope = ownerScopeService.currentSquadronId().orElse(null);
    return repository.findAllByUserIdScoped(userId, scope).stream()
        .map(mapper::toResponse)
        .toList();
  }

  /** Returns paginated evaluations for the given user, scoped to the active squadron. */
  public Page<MemberEvaluationResponse> listForUserPaged(
      @NotNull UUID userId, @NotNull Pageable pageable) {
    if (!ownerScopeService.isPromotionFeatureEnabledForCurrentScope()
        || !ownerScopeService.hasPromotionReadAccess()) {
      return Page.empty(pageable);
    }
    UUID scope = ownerScopeService.currentSquadronId().orElse(null);
    return repository.findAllByUserIdScoped(userId, scope, pageable).map(mapper::toResponse);
  }

  /** Returns all evaluations (admin view, all users) scoped to the active squadron. */
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public Page<MemberEvaluationResponse> listAll(@NotNull Pageable pageable) {
    if (!ownerScopeService.isPromotionFeatureEnabledForCurrentScope()
        || !ownerScopeService.hasPromotionReadAccess()) {
      return Page.empty(pageable);
    }
    UUID scope = ownerScopeService.currentSquadronId().orElse(null);
    return repository.findAllScoped(scope, pageable).map(mapper::toResponse);
  }

  /**
   * Upserts (create or update) an evaluation for a user/category combination. ADMIN or OFFICER of
   * the category's owning squadron.
   */
  @Transactional
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public MemberEvaluationResponse upsert(
      @NotNull UUID userId,
      @NotNull UUID categoryId,
      @NotNull MemberEvaluationUpdateRequest request) {
    ownerScopeService.assertPromotionFeatureEnabled();
    PromotionCategory category =
        Entities.require(
            categoryRepository.findById(categoryId),
            () -> "PromotionCategory not found: " + categoryId);
    assertCallerMayEditCategory(category);
    assertCallerMayEvaluateUser(userId);

    MemberEvaluation entity =
        repository
            .findByUserIdAndCategoryId(userId, categoryId)
            .orElseGet(() -> MemberEvaluation.builder().userId(userId).category(category).build());

    if (entity.getId() != null) {
      OptimisticLock.check(
          entity.getVersion(), request.version(), MemberEvaluation.class, entity.getId());
    }

    boolean isNew = entity.getId() == null;
    entity.setAssignedLevel(request.assignedLevel());
    MemberEvaluation saved = repository.save(entity);
    auditService.record(
        isNew
            ? AuditEventType.PROMOTION_EVALUATION_CREATED
            : AuditEventType.PROMOTION_EVALUATION_UPDATED,
        category.getId(),
        categoryLabel(category),
        userId,
        AuditDetails.of("level", request.assignedLevel()));
    log.info(
        "Upserted MemberEvaluation userId={} categoryId={} level={}",
        userId,
        categoryId,
        request.assignedLevel());
    return mapper.toResponse(saved);
  }

  /** Deletes an evaluation entry (removes the assigned level). */
  @Transactional
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public void delete(@NotNull UUID id) {
    ownerScopeService.assertPromotionFeatureEnabled();
    MemberEvaluation entity =
        Entities.require(repository.findById(id), () -> "MemberEvaluation not found: " + id);
    assertCallerMayEditCategory(entity.getCategory());
    assertCallerMayEvaluateUser(entity.getUserId());
    PromotionCategory category = entity.getCategory();
    UUID subjectId = category != null ? category.getId() : null;
    String label = categoryLabel(category);
    UUID targetUserId = entity.getUserId();
    repository.delete(entity);
    auditService.record(
        AuditEventType.PROMOTION_EVALUATION_DELETED, subjectId, label, targetUserId, null);
    log.info("Deleted MemberEvaluation id={}", id);
  }

  /**
   * Builds the non-personal audit label for an evaluation: {@code topic / category}, without any
   * member handle.
   *
   * @param category the graded category, or {@code null}
   * @return the {@code topic / category} label, or {@code "—"} when no category is present
   */
  private static @NotNull String categoryLabel(@Nullable PromotionCategory category) {
    if (category == null) {
      return "—";
    }
    var topic = category.getTopic();
    return (topic != null ? topic.getName() + " / " : "") + category.getName();
  }

  private void assertCallerMayEditCategory(PromotionCategory category) {
    if (category == null
        || category.getTopic() == null
        || category.getTopic().getOwningSquadron() == null) {
      return;
    }
    if (!ownerScopeService.canEditSquadron(category.getTopic().getOwningSquadron().getId())) {
      throw new AccessDeniedException(
          "Caller's squadron context does not allow editing evaluations of this scope");
    }
  }

  /**
   * Asserts that the caller may evaluate the given member: admins pass, others must be able to edit
   * at least one of the member's Staffeln (REQ-ORG-017). Fails closed on a malformed id.
   *
   * @param userId the {@code app_user.id} of the member being evaluated; never {@code null}.
   */
  private void assertCallerMayEvaluateUser(@NotNull UUID userId) {
    if (authHelperService.isAdmin()) {
      return;
    }
    List<UUID> staffelIds = orgUnitMembershipQueryService.findStaffelMembershipOrgUnitIds(userId);
    if (staffelIds.stream().noneMatch(ownerScopeService::canEditSquadron)) {
      throw new AccessDeniedException(
          "Caller's squadron context does not allow evaluating this member");
    }
  }
}
