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
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.PromotionLevelContentMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.PromotionCategory;
import de.greluc.krt.profit.basetool.backend.model.PromotionLevelContent;
import de.greluc.krt.profit.basetool.backend.model.dto.PromotionLevelContentResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PromotionLevelContentWriteRequest;
import de.greluc.krt.profit.basetool.backend.repository.PromotionCategoryRepository;
import de.greluc.krt.profit.basetool.backend.repository.PromotionLevelContentRepository;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.support.Roles;
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

/** Domain service for {@link PromotionLevelContent} CRUD operations. */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class PromotionLevelContentService {

  public static final Set<String> SORTABLE_FIELDS = Set.of("id", "level", "createdAt", "updatedAt");
  public static final String DEFAULT_SORT_FIELD = "level";

  private final PromotionLevelContentRepository repository;
  private final PromotionCategoryRepository categoryRepository;
  private final PromotionLevelContentMapper mapper;
  private final OwnerScopeService ownerScopeService;
  private final AuditService auditService;

  /**
   * Returns a paginated slice of every {@link PromotionLevelContentResponse} across all categories.
   * The controller validates the caller-supplied sort against {@link #SORTABLE_FIELDS} before this
   * method is invoked.
   *
   * @param pageable Spring Data paging and sorting parameters
   * @return a page of level contents
   */
  public Page<PromotionLevelContentResponse> list(@NotNull Pageable pageable) {
    if (!ownerScopeService.isPromotionFeatureEnabledForCurrentScope()
        || !ownerScopeService.hasPromotionReadAccess()) {
      return Page.empty(pageable);
    }
    UUID scope = ownerScopeService.currentSquadronId().orElse(null);
    return repository.findAllScoped(scope, pageable).map(mapper::toResponse);
  }

  /**
   * Returns every {@link PromotionLevelContentResponse} for the given category ordered by {@link
   * de.greluc.krt.profit.basetool.backend.model.PromotionLevel}, used by the promotion UI to render
   * the rank-progression table without pagination.
   *
   * @param categoryId identifier of the parent category
   * @return the category's level contents in display order
   */
  public List<PromotionLevelContentResponse> listByCategory(@NotNull UUID categoryId) {
    if (!ownerScopeService.isPromotionFeatureEnabledForCurrentScope()
        || !ownerScopeService.hasPromotionReadAccess()) {
      return List.of();
    }
    UUID scope = ownerScopeService.currentSquadronId().orElse(null);
    return repository.findAllByCategoryIdScopedOrdered(categoryId, scope).stream()
        .map(mapper::toResponse)
        .toList();
  }

  /**
   * Resolves a single {@link PromotionLevelContentResponse} by identifier.
   *
   * @param id identifier of the level content
   * @return the matching level content in response form
   * @throws NotFoundException if no level content exists for that id
   * @throws AccessDeniedException if the caller's squadron context does not match the level
   *     content's owning squadron
   */
  public PromotionLevelContentResponse get(@NotNull UUID id) {
    ownerScopeService.assertPromotionFeatureEnabled();
    PromotionLevelContent entity = load(id);
    assertCallerMaySeeLevelContent(entity);
    return mapper.toResponse(entity);
  }

  /**
   * Persists a new {@link PromotionLevelContent} attached to the category referenced by the
   * request. Restricted to ADMIN or OFFICER callers via {@link PreAuthorize}.
   *
   * @param request validated payload describing the new level content
   * @return the persisted level content in response form
   * @throws NotFoundException if the referenced category does not exist
   */
  @Transactional
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public PromotionLevelContentResponse create(@NotNull PromotionLevelContentWriteRequest request) {
    ownerScopeService.assertPromotionFeatureEnabled();
    PromotionCategory category =
        Entities.require(
            categoryRepository.findById(request.categoryId()),
            () -> "PromotionCategory not found: " + request.categoryId());
    assertCallerMayEditCategory(category);
    PromotionLevelContent entity = mapper.toEntity(request);
    entity.setCategory(category);
    PromotionLevelContent saved = repository.save(entity);
    auditService.record(
        AuditEventType.PROMOTION_LEVEL_CONTENT_CREATED,
        saved.getId(),
        category.getName() + " / " + saved.getLevel(),
        null,
        null);
    log.info("Created PromotionLevelContent id={} level={}", saved.getId(), saved.getLevel());
    return mapper.toResponse(saved);
  }

  /**
   * Updates the level content identified by {@code id} and rebinds it to the category referenced by
   * the request. The caller-supplied {@code version} is compared against the loaded entity and a
   * mismatch produces an {@link ObjectOptimisticLockingFailureException} that surfaces as HTTP 409.
   *
   * @param id identifier of the level content to update
   * @param request validated payload with the new field values and the previously fetched {@code
   *     version}
   * @return the updated level content in response form
   * @throws NotFoundException if the level content or referenced category does not exist
   * @throws ObjectOptimisticLockingFailureException if the request's {@code version} no longer
   *     matches the persisted entity
   */
  @Transactional
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public PromotionLevelContentResponse update(
      @NotNull UUID id, @NotNull PromotionLevelContentWriteRequest request) {
    ownerScopeService.assertPromotionFeatureEnabled();
    PromotionLevelContent entity = load(id);
    assertCallerMayEditCategory(entity.getCategory());
    OptimisticLock.check(entity.getVersion(), request.version(), PromotionLevelContent.class, id);
    PromotionCategory category =
        Entities.require(
            categoryRepository.findById(request.categoryId()),
            () -> "PromotionCategory not found: " + request.categoryId());
    assertCallerMayEditCategory(category);
    mapper.updateEntity(entity, request);
    entity.setCategory(category);
    // saveAndFlush so the flushed @Version reaches the response — the level-content textarea writes
    // the returned version back onto its data-lc-version attribute in place (no re-swap, unlike the
    // category/topic paths), so a stale save() version 409s the next consecutive edit.
    PromotionLevelContent saved = repository.saveAndFlush(entity);
    auditService.record(
        AuditEventType.PROMOTION_LEVEL_CONTENT_UPDATED,
        saved.getId(),
        category.getName() + " / " + saved.getLevel(),
        null,
        null);
    log.info("Updated PromotionLevelContent id={}", id);
    return mapper.toResponse(saved);
  }

  /**
   * Permanently removes the level content identified by {@code id}. Restricted to ADMIN or OFFICER
   * callers.
   *
   * @param id identifier of the level content to delete
   * @throws NotFoundException if no level content exists for that id
   */
  @Transactional
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public void delete(@NotNull UUID id) {
    ownerScopeService.assertPromotionFeatureEnabled();
    PromotionLevelContent entity = load(id);
    assertCallerMayEditCategory(entity.getCategory());
    PromotionCategory category = entity.getCategory();
    String label = (category != null ? category.getName() + " / " : "") + entity.getLevel();
    repository.delete(entity);
    auditService.record(AuditEventType.PROMOTION_LEVEL_CONTENT_DELETED, id, label, null, null);
    log.info("Deleted PromotionLevelContent id={}", id);
  }

  @NotNull
  private PromotionLevelContent load(@NotNull UUID id) {
    return Entities.require(
        repository.findById(id), () -> "PromotionLevelContent not found: " + id);
  }

  private void assertCallerMayEditCategory(PromotionCategory category) {
    if (category == null
        || category.getTopic() == null
        || category.getTopic().getOwningSquadron() == null) {
      return;
    }
    if (!ownerScopeService.canEditSquadron(category.getTopic().getOwningSquadron().getId())) {
      throw new AccessDeniedException(
          "Caller's squadron context does not allow editing level contents of this scope");
    }
  }

  private void assertCallerMaySeeLevelContent(@NotNull PromotionLevelContent levelContent) {
    PromotionCategory category = levelContent.getCategory();
    if (category == null
        || category.getTopic() == null
        || category.getTopic().getOwningSquadron() == null) {
      return;
    }
    if (!ownerScopeService.canSeeSquadron(category.getTopic().getOwningSquadron().getId())) {
      throw new AccessDeniedException(
          "Caller's squadron context does not match this level content's owning squadron");
    }
  }
}
