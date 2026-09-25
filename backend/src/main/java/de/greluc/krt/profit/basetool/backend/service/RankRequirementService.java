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

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.RankRequirementMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.PromotionCategory;
import de.greluc.krt.profit.basetool.backend.model.PromotionTopic;
import de.greluc.krt.profit.basetool.backend.model.RankRequirement;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.dto.RankRequirementResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.RankRequirementWriteRequest;
import de.greluc.krt.profit.basetool.backend.repository.PromotionCategoryRepository;
import de.greluc.krt.profit.basetool.backend.repository.PromotionTopicRepository;
import de.greluc.krt.profit.basetool.backend.repository.RankRequirementRepository;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Domain service for {@link RankRequirement} CRUD operations. */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class RankRequirementService {

  public static final Set<String> SORTABLE_FIELDS =
      Set.of("id", "fromRank", "toRank", "minimumLevel", "requiredCount", "createdAt", "updatedAt");
  public static final String DEFAULT_SORT_FIELD = "fromRank";

  private final RankRequirementRepository repository;
  private final PromotionTopicRepository topicRepository;
  private final PromotionCategoryRepository categoryRepository;
  private final RankRequirementMapper mapper;
  private final OwnerScopeService ownerScopeService;
  private final AuditService auditService;

  /**
   * Returns a paginated slice of every {@link RankRequirementResponse} across all rank transitions.
   * The controller validates the caller-supplied sort against {@link #SORTABLE_FIELDS} before this
   * method is invoked.
   *
   * @param pageable Spring Data paging and sorting parameters
   * @return a page of rank requirements
   */
  public Page<RankRequirementResponse> list(@NotNull Pageable pageable) {
    if (!ownerScopeService.isPromotionFeatureEnabledForCurrentScope()
        || !ownerScopeService.hasPromotionReadAccess()) {
      return Page.empty(pageable);
    }
    UUID scope = ownerScopeService.currentSquadronId().orElse(null);
    return repository.findAllScoped(scope, pageable).map(mapper::toResponse);
  }

  /**
   * Returns every {@link RankRequirementResponse} that applies to the promotion path from {@code
   * fromRank} to {@code toRank}, used by the eligibility engine to evaluate whether a member
   * qualifies for that rank step.
   *
   * @param fromRank ordinal of the current rank
   * @param toRank ordinal of the rank being promoted to
   * @return the rank requirements applicable to that transition
   */
  public List<RankRequirementResponse> listByRanks(int fromRank, int toRank) {
    if (!ownerScopeService.isPromotionFeatureEnabledForCurrentScope()
        || !ownerScopeService.hasPromotionReadAccess()) {
      return List.of();
    }
    UUID scope = ownerScopeService.currentSquadronId().orElse(null);
    return repository.findScopedByFromRankAndToRank(fromRank, toRank, scope).stream()
        .map(mapper::toResponse)
        .toList();
  }

  /**
   * Resolves a single {@link RankRequirementResponse} by identifier.
   *
   * @param id identifier of the rank requirement
   * @return the matching rank requirement in response form
   * @throws NotFoundException if no rank requirement exists for that id
   * @throws AccessDeniedException if the caller's squadron context does not match the requirement's
   *     owning squadron
   */
  public RankRequirementResponse get(@NotNull UUID id) {
    ownerScopeService.assertPromotionFeatureEnabled();
    RankRequirement entity = load(id);
    assertCallerMaySee(entity);
    return mapper.toResponse(entity);
  }

  /**
   * Persists a new {@link RankRequirement}, wiring optional topic and category associations from
   * the request. Restricted to ADMIN or OFFICER callers via {@link PreAuthorize}.
   *
   * <p>Auto-stamps the owning squadron from the caller's active context ({@link
   * OwnerScopeService#currentSquadron()}) so Officers always tag their own squadron and Admins must
   * focus the sidebar switcher before creating (Admin in "all squadrons" mode is rejected with HTTP
   * 400, mirroring the {@code PromotionTopic} create contract). When the request references a topic
   * or category, that reference must belong to the same squadron — a cross-squadron reference is
   * rejected with HTTP 400.
   *
   * @param request validated payload describing the new rank requirement
   * @return the persisted rank requirement in response form
   * @throws NotFoundException if a referenced topic or category does not exist
   * @throws BadRequestException when the caller has no active squadron context, or a referenced
   *     topic/category belongs to a different squadron
   */
  @Transactional
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public RankRequirementResponse create(@NotNull RankRequirementWriteRequest request) {
    ownerScopeService.assertPromotionFeatureEnabled();
    validateSingleRankStep(request.fromRank(), request.toRank());
    if (ownerScopeService.hasAmbiguousStaffelContext()) {
      throw new BadRequestException(
          "You belong to two Staffeln — pin the Staffel this rank requirement belongs to via the"
              + " sidebar switcher before creating it.");
    }
    Squadron squadron =
        ownerScopeService
            .currentSquadron()
            .orElseThrow(
                () ->
                    new BadRequestException(
                        "No active squadron context — admins in 'all squadrons' mode must focus a"
                            + " squadron via the sidebar switcher before creating a rank"
                            + " requirement."));
    RankRequirement entity = mapper.toEntity(request);
    PromotionTopic resolvedTopic = resolveTopic(request.topicId());
    PromotionCategory resolvedCategory = resolveCategory(request.categoryId());
    assertReferencesBelongToSquadron(resolvedTopic, resolvedCategory, squadron);
    entity.setOwningSquadron(squadron);
    entity.setTopic(resolvedTopic);
    entity.setCategory(resolvedCategory);
    RankRequirement saved = repository.save(entity);
    auditService.record(
        AuditEventType.PROMOTION_RANK_REQUIREMENT_CREATED,
        saved.getId(),
        rankRequirementLabel(saved),
        null,
        rankRequirementDetails(saved));
    log.info(
        "Created RankRequirement id={} {}->{} squadron={}",
        saved.getId(),
        saved.getFromRank(),
        saved.getToRank(),
        squadron.getShorthand());
    return mapper.toResponse(saved);
  }

  /**
   * Updates the rank requirement identified by {@code id} and re-resolves its optional topic and
   * category references. The caller-supplied {@code version} is compared against the loaded entity
   * and a mismatch produces an {@link ObjectOptimisticLockingFailureException} that surfaces as
   * HTTP 409.
   *
   * @param id identifier of the rank requirement to update
   * @param request validated payload with the new field values and the previously fetched {@code
   *     version}
   * @return the updated rank requirement in response form
   * @throws NotFoundException if the requirement or any referenced topic/category does not exist
   * @throws ObjectOptimisticLockingFailureException if the request's {@code version} no longer
   *     matches the persisted entity
   */
  @Transactional
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public RankRequirementResponse update(
      @NotNull UUID id, @NotNull RankRequirementWriteRequest request) {
    ownerScopeService.assertPromotionFeatureEnabled();
    validateSingleRankStep(request.fromRank(), request.toRank());
    RankRequirement entity = load(id);
    assertCallerMayEdit(entity);
    OptimisticLock.check(entity.getVersion(), request.version(), RankRequirement.class, id);
    mapper.updateEntity(entity, request);
    PromotionTopic resolvedTopic = resolveTopic(request.topicId());
    PromotionCategory resolvedCategory = resolveCategory(request.categoryId());
    assertReferencesBelongToSquadron(resolvedTopic, resolvedCategory, entity.getOwningSquadron());
    entity.setTopic(resolvedTopic);
    entity.setCategory(resolvedCategory);
    RankRequirement saved = repository.save(entity);
    auditService.record(
        AuditEventType.PROMOTION_RANK_REQUIREMENT_UPDATED,
        saved.getId(),
        rankRequirementLabel(saved),
        null,
        rankRequirementDetails(saved));
    log.info("Updated RankRequirement id={}", id);
    return mapper.toResponse(saved);
  }

  /**
   * Permanently removes the rank requirement identified by {@code id}. Restricted to ADMIN or
   * OFFICER callers.
   *
   * @param id identifier of the rank requirement to delete
   * @throws NotFoundException if no rank requirement exists for that id
   */
  @Transactional
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public void delete(@NotNull UUID id) {
    ownerScopeService.assertPromotionFeatureEnabled();
    RankRequirement entity = load(id);
    assertCallerMayEdit(entity);
    String label = rankRequirementLabel(entity);
    repository.delete(entity);
    auditService.record(AuditEventType.PROMOTION_RANK_REQUIREMENT_DELETED, id, label, null, null);
    log.info("Deleted RankRequirement id={}", id);
  }

  /**
   * Builds the non-personal audit subject label for a rank requirement: the rank step it governs,
   * e.g. {@code "20->19"}.
   *
   * @param entity the rank requirement; never {@code null}
   * @return the {@code fromRank->toRank} label
   */
  private static @NotNull String rankRequirementLabel(@NotNull RankRequirement entity) {
    return entity.getFromRank() + "->" + entity.getToRank();
  }

  /**
   * Builds the compact, non-PII audit details payload for a rank requirement create/update: the
   * minimum level enum and required count, e.g. {@code "level=BRONZE count=3"}.
   *
   * @param entity the rank requirement; never {@code null}
   * @return the details string
   */
  private static @NotNull String rankRequirementDetails(@NotNull RankRequirement entity) {
    return "level=" + entity.getMinimumLevel() + " count=" + entity.getRequiredCount();
  }

  private void assertCallerMaySee(@NotNull RankRequirement entity) {
    if (entity.getOwningSquadron() == null) {
      return;
    }
    if (!ownerScopeService.canSeeSquadron(entity.getOwningSquadron().getId())) {
      throw new AccessDeniedException(
          "Caller's squadron context does not match the rank requirement's owning squadron");
    }
  }

  private void assertCallerMayEdit(@NotNull RankRequirement entity) {
    if (entity.getOwningSquadron() == null) {
      return;
    }
    if (!ownerScopeService.canEditSquadron(entity.getOwningSquadron().getId())) {
      throw new AccessDeniedException(
          "Caller's squadron context does not allow editing this rank requirement");
    }
  }

  /**
   * Asserts that an optional topic and category both belong to {@code squadron}. A {@code null}
   * topic or category is allowed (a requirement may be topic-scoped, category-scoped, or global); a
   * non-null reference owned by a different squadron is rejected so a requirement can never link to
   * another squadron's catalog.
   *
   * @param topic the resolved topic reference, or {@code null}
   * @param category the resolved category reference, or {@code null}
   * @param squadron the squadron the requirement is (being) owned by; never {@code null}
   * @throws BadRequestException if a non-null reference belongs to a different squadron
   */
  private static void assertReferencesBelongToSquadron(
      @Nullable PromotionTopic topic,
      @Nullable PromotionCategory category,
      @NotNull Squadron squadron) {
    if (topic != null
        && topic.getOwningSquadron() != null
        && !squadron.getId().equals(topic.getOwningSquadron().getId())) {
      throw new BadRequestException(
          "The referenced promotion topic belongs to a different squadron.");
    }
    PromotionTopic categoryTopic = category != null ? category.getTopic() : null;
    if (categoryTopic != null
        && categoryTopic.getOwningSquadron() != null
        && !squadron.getId().equals(categoryTopic.getOwningSquadron().getId())) {
      throw new BadRequestException(
          "The referenced promotion category belongs to a different squadron.");
    }
  }

  /**
   * Enforces that a rank requirement always describes a single-step promotion ({@code fromRank -
   * toRank == 1}), e.g. {@code 20 -> 19}. Multi-step transitions like {@code 20 -> 18} would be
   * structurally ambiguous for the eligibility evaluator (which rules apply, the source or the
   * target rank's?) and are therefore rejected at the service boundary.
   *
   * <p>Lower {@code rank} ordinals denote higher ranks in this codebase, so a valid promotion
   * always decreases the ordinal by exactly one.
   *
   * @param fromRank ordinal of the current rank
   * @param toRank ordinal of the rank being promoted to
   * @throws BadRequestException if the difference is anything other than {@code 1}
   */
  private static void validateSingleRankStep(int fromRank, int toRank) {
    if (fromRank - toRank != 1) {
      throw new BadRequestException("error.rank_requirement.invalid_step");
    }
  }

  @NotNull
  private RankRequirement load(@NotNull UUID id) {
    return Entities.require(repository.findById(id), () -> "RankRequirement not found: " + id);
  }

  @Contract("null -> null")
  @Nullable
  private PromotionTopic resolveTopic(@Nullable UUID topicId) {
    if (topicId == null) {
      return null;
    }
    return Entities.require(
        topicRepository.findById(topicId), () -> "PromotionTopic not found: " + topicId);
  }

  @Contract("null -> null")
  @Nullable
  private PromotionCategory resolveCategory(@Nullable UUID categoryId) {
    if (categoryId == null) {
      return null;
    }
    return Entities.require(
        categoryRepository.findById(categoryId),
        () -> "PromotionCategory not found: " + categoryId);
  }
}
