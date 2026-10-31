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

package de.greluc.krt.profit.basetool.backend.promotion.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain service for {@link MemberEvaluation}.
 *
 * <p>Writes are limited to an admin and to officer reach: a non-admin needs reach in the category's
 * owning squadron and over the member (REQ-SEC-015). Personal reads are filtered by the caller's
 * user id.
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
  private final PromotionAccessPolicy promotionAccessPolicy;
  private final AuditRecorder auditRecorder;

  /**
   * Returns all evaluations for the given user (JWT-sub filtered – data isolation), additionally
   * scoped to the caller's active squadron so a multi-squadron member's "my evaluations" only shows
   * the active squadron's grades.
   */
  public List<MemberEvaluationResponse> listForUser(@NotNull UUID userId) {
    if (!promotionAccessPolicy.isFeatureEnabledForCurrentScope()
        || !promotionAccessPolicy.hasReadAccess()) {
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
    if (!promotionAccessPolicy.isFeatureEnabledForCurrentScope()
        || !promotionAccessPolicy.hasReadAccess()) {
      return Page.empty(pageable);
    }
    UUID scope = ownerScopeService.currentSquadronId().orElse(null);
    return repository.findAllByUserIdScoped(userId, scope, pageable).map(mapper::toResponse);
  }

  /** Returns all evaluations (admin view, all users) scoped to the active squadron. */
  @PreAuthorize(Roles.ADMIN_OR_OFFICER_REACH)
  public Page<MemberEvaluationResponse> listAll(@NotNull Pageable pageable) {
    if (!promotionAccessPolicy.isFeatureEnabledForCurrentScope()
        || !promotionAccessPolicy.hasReadAccess()) {
      return Page.empty(pageable);
    }
    UUID scope = ownerScopeService.currentSquadronId().orElse(null);
    return repository.findAllScoped(scope, pageable).map(mapper::toResponse);
  }

  /**
   * Upserts (create or update) an evaluation for a user/category combination. Restricted to an
   * admin, or a caller with officer reach in the category's owning squadron and over the member.
   */
  @Transactional
  @PreAuthorize(Roles.ADMIN_OR_OFFICER_REACH)
  public MemberEvaluationResponse upsert(
      @NotNull UUID userId,
      @NotNull UUID categoryId,
      @NotNull MemberEvaluationUpdateRequest request) {
    promotionAccessPolicy.assertFeatureEnabled();
    PromotionCategory category =
        Entities.require(
            categoryRepository.findById(categoryId),
            () -> "PromotionCategory not found: " + categoryId);
    assertCallerMayEditCategory(category);
    promotionAccessPolicy.assertOfficerOverMember(userId);

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
    auditRecorder.record(
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
  @PreAuthorize(Roles.ADMIN_OR_OFFICER_REACH)
  public void delete(@NotNull UUID id) {
    promotionAccessPolicy.assertFeatureEnabled();
    MemberEvaluation entity =
        Entities.require(repository.findById(id), () -> "MemberEvaluation not found: " + id);
    assertCallerMayEditCategory(entity.getCategory());
    promotionAccessPolicy.assertOfficerOverMember(entity.getUserId());
    PromotionCategory category = entity.getCategory();
    UUID subjectId = category != null ? category.getId() : null;
    String label = categoryLabel(category);
    UUID targetUserId = entity.getUserId();
    repository.delete(entity);
    auditRecorder.record(
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
    promotionAccessPolicy.assertOfficerIn(category.getTopic().getOwningSquadron().getId());
  }
}
