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
import de.greluc.krt.profit.basetool.backend.mapper.PromotionTopicMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.PromotionTopic;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.dto.PromotionTopicResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PromotionTopicWriteRequest;
import de.greluc.krt.profit.basetool.backend.repository.PromotionTopicRepository;
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

/**
 * Domain service for {@link PromotionTopic} CRUD operations. Topics carry the squadron scope for
 * the entire promotion subtree (categories, level contents, rank requirements, member evaluations
 * all derive their squadron from {@code topic.owningSquadron}); Officer-of-squadron-X may read and
 * edit topics owned by squadron X, Admin may operate across squadrons or focus via the sidebar
 * switcher.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class PromotionTopicService {

  /** Sort fields allowed on the paginated list endpoint. */
  public static final Set<String> SORTABLE_FIELDS =
      Set.of("id", "name", "sortOrder", "createdAt", "updatedAt");

  /** Default sort field used when the caller did not supply one. */
  public static final String DEFAULT_SORT_FIELD = "sortOrder";

  private final PromotionTopicRepository repository;
  private final PromotionTopicMapper mapper;
  private final OwnerScopeService ownerScopeService;
  private final AuditService auditService;

  /**
   * Returns a paginated slice of every {@link PromotionTopicResponse} visible to the caller. The
   * controller validates the caller-supplied sort against {@link #SORTABLE_FIELDS} before this
   * method is invoked. For Officer / KRT Member callers the result is restricted to their home
   * squadron; for Admin with the switcher set, to the focused squadron; for Admin in "all
   * squadrons" mode the result spans every squadron.
   *
   * @param pageable Spring Data paging and sorting parameters
   * @return a page of promotion topics
   */
  public Page<PromotionTopicResponse> list(@NotNull Pageable pageable) {
    if (!ownerScopeService.isPromotionFeatureEnabledForCurrentScope()
        || !ownerScopeService.hasPromotionReadAccess()) {
      return Page.empty(pageable);
    }
    UUID scope = ownerScopeService.currentSquadronId().orElse(null);
    return repository.findAllScoped(scope, pageable).map(mapper::toResponse);
  }

  /**
   * Returns every {@link PromotionTopicResponse} ordered by {@code sortOrder}, scoped exactly as
   * {@link #list(Pageable)}. Used by the promotion UI to render the full topic list without
   * pagination.
   *
   * @return visible topics in display order
   */
  public List<PromotionTopicResponse> listAll() {
    if (!ownerScopeService.isPromotionFeatureEnabledForCurrentScope()
        || !ownerScopeService.hasPromotionReadAccess()) {
      return List.of();
    }
    UUID scope = ownerScopeService.currentSquadronId().orElse(null);
    return repository.findAllScoped(scope).stream().map(mapper::toResponse).toList();
  }

  /**
   * Resolves a single {@link PromotionTopicResponse} by identifier. Rejects with {@link
   * AccessDeniedException} when the caller's squadron context does not match the topic's owning
   * squadron (and the caller is not Admin).
   *
   * @param id identifier of the topic
   * @return the matching topic in response form
   * @throws NotFoundException if no topic exists for that id
   * @throws AccessDeniedException if the caller's squadron does not match
   */
  public PromotionTopicResponse get(@NotNull UUID id) {
    PromotionTopic entity = load(id);
    assertCallerMayAccess(entity);
    ownerScopeService.assertPromotionFeatureEnabled();
    return mapper.toResponse(entity);
  }

  /**
   * Persists a new {@link PromotionTopic}. Auto-stamps the owning squadron from the caller's active
   * context ({@link OwnerScopeService#currentSquadron()}) so Officers always tag their own squadron
   * and Admins must focus the switcher before creating (Admin in "all squadrons" mode is rejected
   * with HTTP 400, mirroring the JobOrder create contract).
   *
   * @param request validated payload describing the new topic
   * @return the persisted topic in response form
   * @throws BadRequestException when the caller has no active squadron context
   */
  @Transactional
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public PromotionTopicResponse create(@NotNull PromotionTopicWriteRequest request) {
    ownerScopeService.assertPromotionFeatureEnabled();
    if (ownerScopeService.hasAmbiguousStaffelContext()) {
      throw new BadRequestException(
          "You belong to two Staffeln — pin the Staffel this promotion topic belongs to via the"
              + " sidebar switcher before creating it.");
    }
    Squadron squadron =
        ownerScopeService
            .currentSquadron()
            .orElseThrow(
                () ->
                    new BadRequestException(
                        "No active squadron context — admins in 'all squadrons' mode must focus a"
                            + " squadron via the sidebar switcher before creating a promotion"
                            + " topic."));
    PromotionTopic entity = mapper.toEntity(request);
    entity.setOwningSquadron(squadron);
    PromotionTopic saved = repository.save(entity);
    auditService.record(
        AuditEventType.PROMOTION_TOPIC_CREATED, saved.getId(), saved.getName(), null, null);
    log.info(
        "Created PromotionTopic id={} name={} squadron={}",
        saved.getId(),
        saved.getName(),
        squadron.getShorthand());
    return mapper.toResponse(saved);
  }

  /**
   * Updates the topic identified by {@code id}. The caller-supplied {@code version} is compared
   * against the loaded entity and a mismatch produces an {@link
   * ObjectOptimisticLockingFailureException} that surfaces as HTTP 409. The owning squadron is
   * immutable post-create — admins cannot reassign a topic to a different squadron through this
   * endpoint.
   *
   * @param id identifier of the topic to update
   * @param request validated payload with the new field values and the previously fetched {@code
   *     version}
   * @return the updated topic in response form
   * @throws NotFoundException if no topic exists for that id
   * @throws AccessDeniedException if the caller's squadron does not match
   * @throws ObjectOptimisticLockingFailureException if the request's {@code version} no longer
   *     matches the persisted entity
   */
  @Transactional
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public PromotionTopicResponse update(
      @NotNull UUID id, @NotNull PromotionTopicWriteRequest request) {
    ownerScopeService.assertPromotionFeatureEnabled();
    PromotionTopic entity = load(id);
    assertCallerMayEdit(entity);
    OptimisticLock.check(entity.getVersion(), request.version(), PromotionTopic.class, id);
    mapper.updateEntity(entity, request);
    PromotionTopic saved = repository.save(entity);
    auditService.record(
        AuditEventType.PROMOTION_TOPIC_UPDATED, saved.getId(), saved.getName(), null, null);
    log.info("Updated PromotionTopic id={}", id);
    return mapper.toResponse(saved);
  }

  /**
   * Permanently removes the topic identified by {@code id}, cascading the orphan removal to its
   * categories. Restricted to ADMIN or OFFICER callers whose squadron matches the topic.
   *
   * @param id identifier of the topic to delete
   * @throws NotFoundException if no topic exists for that id
   * @throws AccessDeniedException if the caller's squadron does not match
   */
  @Transactional
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public void delete(@NotNull UUID id) {
    ownerScopeService.assertPromotionFeatureEnabled();
    PromotionTopic entity = load(id);
    assertCallerMayEdit(entity);
    String label = entity.getName();
    repository.delete(entity);
    auditService.record(AuditEventType.PROMOTION_TOPIC_DELETED, id, label, null, null);
    log.info("Deleted PromotionTopic id={}", id);
  }

  @NotNull
  private PromotionTopic load(@NotNull UUID id) {
    return Entities.require(repository.findById(id), () -> "PromotionTopic not found: " + id);
  }

  private void assertCallerMayAccess(@NotNull PromotionTopic topic) {
    if (topic.getOwningSquadron() == null) {
      return;
    }
    if (!ownerScopeService.canSeeSquadron(topic.getOwningSquadron().getId())) {
      throw new AccessDeniedException(
          "Caller's squadron context does not match the topic's owning squadron");
    }
  }

  private void assertCallerMayEdit(@NotNull PromotionTopic topic) {
    if (topic.getOwningSquadron() == null) {
      return;
    }
    if (!ownerScopeService.canEditSquadron(topic.getOwningSquadron().getId())) {
      throw new AccessDeniedException(
          "Caller's squadron context does not allow editing the topic's owning squadron");
    }
  }
}
