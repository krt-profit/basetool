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

import de.greluc.krt.profit.basetool.backend.mapper.PromotionCategoryMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.PromotionCategory;
import de.greluc.krt.profit.basetool.backend.model.PromotionTopic;
import de.greluc.krt.profit.basetool.backend.model.dto.PromotionCategoryResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PromotionCategoryWriteRequest;
import de.greluc.krt.profit.basetool.backend.repository.PromotionCategoryRepository;
import de.greluc.krt.profit.basetool.backend.repository.PromotionTopicRepository;
import de.greluc.krt.profit.basetool.backend.support.LogSafe;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain service for {@link PromotionCategory} CRUD operations. Write operations are restricted to
 * ADMIN and OFFICER roles.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class PromotionCategoryService {

  public static final Set<String> SORTABLE_FIELDS =
      Set.of("id", "name", "sortOrder", "createdAt", "updatedAt");
  public static final String DEFAULT_SORT_FIELD = "sortOrder";

  private final PromotionCategoryRepository repository;
  private final PromotionTopicRepository topicRepository;
  private final PromotionCategoryMapper mapper;
  private final OwnerScopeService ownerScopeService;
  private final AuditService auditService;

  /**
   * Returns a paginated slice of every {@link PromotionCategoryResponse} across all topics. The
   * controller validates the caller-supplied sort against {@link #SORTABLE_FIELDS} before this
   * method is invoked.
   *
   * @param pageable Spring Data paging and sorting parameters
   * @return a page of categories
   */
  public Page<PromotionCategoryResponse> list(@NotNull Pageable pageable) {
    if (!ownerScopeService.isPromotionFeatureEnabledForCurrentScope()
        || !ownerScopeService.hasPromotionReadAccess()) {
      return Page.empty(pageable);
    }
    UUID scope = ownerScopeService.currentSquadronId().orElse(null);
    return repository.findAllScoped(scope, pageable).map(mapper::toResponse);
  }

  /**
   * Returns a paginated slice of the {@link PromotionCategoryResponse} entries that belong to the
   * given {@link PromotionTopic}.
   *
   * @param topicId identifier of the parent topic
   * @param pageable Spring Data paging and sorting parameters
   * @return a page of categories scoped to the topic
   */
  public Page<PromotionCategoryResponse> listByTopic(
      @NotNull UUID topicId, @NotNull Pageable pageable) {
    if (!ownerScopeService.isPromotionFeatureEnabledForCurrentScope()
        || !ownerScopeService.hasPromotionReadAccess()) {
      return Page.empty(pageable);
    }
    UUID scope = ownerScopeService.currentSquadronId().orElse(null);
    return repository.findAllByTopicIdScoped(topicId, scope, pageable).map(mapper::toResponse);
  }

  /**
   * Returns every {@link PromotionCategoryResponse} for the given topic ordered by {@code
   * sortOrder}. Used by the promotion UI to render the full evaluation table without pagination.
   *
   * @param topicId identifier of the parent topic
   * @return the topic's categories in display order
   */
  public List<PromotionCategoryResponse> listAllByTopic(@NotNull UUID topicId) {
    if (!ownerScopeService.isPromotionFeatureEnabledForCurrentScope()
        || !ownerScopeService.hasPromotionReadAccess()) {
      return List.of();
    }
    UUID scope = ownerScopeService.currentSquadronId().orElse(null);
    return repository.findAllByTopicIdScopedOrdered(topicId, scope).stream()
        .map(mapper::toResponse)
        .toList();
  }

  /**
   * Resolves a single {@link PromotionCategoryResponse} by identifier.
   *
   * @param id identifier of the category
   * @return the matching category in response form
   * @throws EntityNotFoundException if no category exists for that id
   * @throws AccessDeniedException if the caller's squadron context does not match the category's
   *     owning squadron
   */
  public PromotionCategoryResponse get(@NotNull UUID id) {
    ownerScopeService.assertPromotionFeatureEnabled();
    PromotionCategory entity = load(id);
    assertCallerMaySeeCategory(entity);
    return mapper.toResponse(entity);
  }

  /**
   * Persists a new {@link PromotionCategory} attached to the topic referenced by the request.
   * Restricted to ADMIN or OFFICER callers via {@link PreAuthorize}.
   *
   * @param request validated payload describing the new category
   * @return the persisted category in response form
   * @throws EntityNotFoundException if the referenced topic does not exist
   */
  @Transactional
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public PromotionCategoryResponse create(@NotNull PromotionCategoryWriteRequest request) {
    ownerScopeService.assertPromotionFeatureEnabled();
    PromotionTopic topic =
        topicRepository
            .findById(request.topicId())
            .orElseThrow(
                () ->
                    new EntityNotFoundException("PromotionTopic not found: " + request.topicId()));
    assertCallerMayEditTopic(topic);
    PromotionCategory entity = mapper.toEntity(request);
    entity.setTopic(topic);
    PromotionCategory saved = repository.save(entity);
    auditService.record(
        AuditEventType.PROMOTION_CATEGORY_CREATED,
        saved.getId(),
        topic.getName() + " / " + saved.getName(),
        null,
        null);
    // The name is admin-entered free text and reaches the log verbatim, so it goes through LogSafe:
    // an embedded newline plus a fake level prefix would otherwise read as a second, forged log
    // line
    // during triage (CWE-117). 120 mirrors the DTO's @Size(MAX_SHORT_NAME).
    log.info(
        "Created PromotionCategory id={} name={}",
        saved.getId(),
        LogSafe.text(saved.getName(), 120));
    return mapper.toResponse(saved);
  }

  /**
   * Updates the category identified by {@code id} and rebinds it to the topic referenced by the
   * request. The caller-supplied {@code version} is compared against the loaded entity and a
   * mismatch produces an {@link ObjectOptimisticLockingFailureException} that surfaces as HTTP 409.
   *
   * @param id identifier of the category to update
   * @param request validated payload with the new field values and the previously fetched {@code
   *     version}
   * @return the updated category in response form
   * @throws EntityNotFoundException if the category or referenced topic does not exist
   * @throws ObjectOptimisticLockingFailureException if the request's {@code version} no longer
   *     matches the persisted entity
   */
  @Transactional
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public PromotionCategoryResponse update(
      @NotNull UUID id, @NotNull PromotionCategoryWriteRequest request) {
    ownerScopeService.assertPromotionFeatureEnabled();
    PromotionCategory entity = load(id);
    assertCallerMayEditTopic(entity.getTopic());
    OptimisticLock.check(entity.getVersion(), request.version(), PromotionCategory.class, id);
    PromotionTopic topic =
        topicRepository
            .findById(request.topicId())
            .orElseThrow(
                () ->
                    new EntityNotFoundException("PromotionTopic not found: " + request.topicId()));
    assertCallerMayEditTopic(topic);
    mapper.updateEntity(entity, request);
    entity.setTopic(topic);
    PromotionCategory saved = repository.save(entity);
    auditService.record(
        AuditEventType.PROMOTION_CATEGORY_UPDATED,
        saved.getId(),
        topic.getName() + " / " + saved.getName(),
        null,
        null);
    log.info("Updated PromotionCategory id={}", id);
    return mapper.toResponse(saved);
  }

  /**
   * Permanently removes the category identified by {@code id}, cascading the orphan removal to its
   * {@link de.greluc.krt.profit.basetool.backend.model.PromotionLevelContent} children. Restricted
   * to ADMIN or OFFICER callers.
   *
   * @param id identifier of the category to delete
   * @throws EntityNotFoundException if no category exists for that id
   */
  @Transactional
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public void delete(@NotNull UUID id) {
    ownerScopeService.assertPromotionFeatureEnabled();
    PromotionCategory entity = load(id);
    assertCallerMayEditTopic(entity.getTopic());
    PromotionTopic topic = entity.getTopic();
    String label = (topic != null ? topic.getName() + " / " : "") + entity.getName();
    repository.delete(entity);
    auditService.record(AuditEventType.PROMOTION_CATEGORY_DELETED, id, label, null, null);
    log.info("Deleted PromotionCategory id={}", id);
  }

  @NotNull
  private PromotionCategory load(@NotNull UUID id) {
    return repository
        .findById(id)
        .orElseThrow(() -> new EntityNotFoundException("PromotionCategory not found: " + id));
  }

  private void assertCallerMayEditTopic(PromotionTopic topic) {
    if (topic == null || topic.getOwningSquadron() == null) {
      return;
    }
    if (!ownerScopeService.canEditSquadron(topic.getOwningSquadron().getId())) {
      throw new AccessDeniedException(
          "Caller's squadron context does not allow editing this promotion topic's children");
    }
  }

  private void assertCallerMaySeeCategory(@NotNull PromotionCategory category) {
    PromotionTopic topic = category.getTopic();
    if (topic == null || topic.getOwningSquadron() == null) {
      return;
    }
    if (!ownerScopeService.canSeeSquadron(topic.getOwningSquadron().getId())) {
      throw new AccessDeniedException(
          "Caller's squadron context does not match this category's owning squadron");
    }
  }
}
