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

import de.greluc.krt.profit.basetool.backend.exception.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException;
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.PersonalBlueprintMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.PersonalBlueprint;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintBatchResult;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintCreateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintRecipeResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintUpdateRequest;
import de.greluc.krt.profit.basetool.backend.repository.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.PersonalBlueprintRepository;
import de.greluc.krt.profit.basetool.backend.service.BlueprintProductService.ResolvedProduct;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import de.greluc.krt.profit.basetool.logging.LogSafe;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner-scoped domain service for personal blueprints. Every method takes the owner id explicitly
 * and never reads the security context.
 *
 * <p>A stale {@code version} raises {@link ObjectOptimisticLockingFailureException} and a duplicate
 * add {@link DuplicateEntityException}, both mapped to 409.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class PersonalBlueprintService {

  /** Whitelist of sort properties accepted on the owned-list endpoint. */
  public static final Set<String> SORTABLE_FIELDS =
      Set.of("productName", "acquiredAt", "createdAt", "updatedAt");

  /** Default sort property when the caller does not specify one. */
  public static final String DEFAULT_SORT_FIELD = "updatedAt";

  private final PersonalBlueprintRepository repository;
  private final PersonalBlueprintMapper mapper;
  private final BlueprintProductService blueprintProductService;
  private final GameItemRepository gameItemRepository;
  private final DefaultBlueprintKeyService defaultBlueprintKeyService;
  private final AuditService auditService;

  /**
   * Owner-scoped paged list of owned blueprints, optionally filtered by a case-insensitive product
   * name substring.
   *
   * @param ownerUserId {@code app_user.id} of the caller
   * @param query optional case-insensitive product-name filter
   * @param pageable page request (sort fields whitelisted by {@link #SORTABLE_FIELDS})
   * @return paged response DTOs
   */
  public Page<PersonalBlueprintResponse> listOwn(
      @NotNull UUID ownerUserId, @Nullable String query, @NotNull Pageable pageable) {
    Page<PersonalBlueprint> page =
        (query == null || query.isBlank())
            ? repository.findAllByOwnerUserId(ownerUserId, pageable)
            : repository.findAllByOwnerUserIdAndProductNameContainingIgnoreCase(
                ownerUserId, query.trim(), pageable);
    return page.map(this::toResponse);
  }

  /**
   * Adds a single blueprint to the caller's owned set. The product key is resolved against the
   * active master list and the canonical name + output item are stamped onto the new row.
   *
   * @param ownerUserId {@code app_user.id} of the caller
   * @param request the add payload (product key + optional acquisition date / note)
   * @return the persisted DTO
   * @throws NotFoundException if the product key matches no active product
   * @throws DuplicateEntityException if the caller already owns the product
   */
  @Transactional
  public PersonalBlueprintResponse add(
      @NotNull UUID ownerUserId, @NotNull PersonalBlueprintCreateRequest request) {
    ResolvedProduct product =
        Entities.require(
            blueprintProductService.resolveByProductKey(request.productKey()),
            () -> "Blueprint product not found: " + request.productKey());
    if (repository.existsByOwnerUserIdAndProductKey(ownerUserId, product.productKey())) {
      throw new DuplicateEntityException(
          "Blueprint '" + product.productName() + "' is already owned.");
    }
    PersonalBlueprint saved =
        repository.save(newOwned(ownerUserId, product, request.acquiredAt(), request.note()));
    auditService.record(
        AuditEventType.BLUEPRINT_ADDED,
        saved.getId(),
        product.productName(),
        ownerUserId,
        AuditDetails.of("product", product.productKey()));
    log.info(
        "Added personal blueprint id={} productKey='{}' ownerUserId={}",
        saved.getId(),
        product.productKey(),
        ownerUserId);
    return toResponse(saved);
  }

  /**
   * Adds several blueprints, skipping keys already owned, repeated in the request, or not resolving
   * to an active product.
   *
   * @param ownerUserId {@code app_user.id} of the caller
   * @param productKeys the product keys to add
   * @return a summary of how many keys were added vs. skipped and why
   */
  @NotNull
  @Transactional
  public PersonalBlueprintBatchResult addBatch(
      @NotNull UUID ownerUserId, @NotNull List<String> productKeys) {
    int added = 0;
    int alreadyOwned = 0;
    int unresolved = 0;
    Set<String> seenInRequest = new HashSet<>();
    for (String rawKey : productKeys) {
      Optional<ResolvedProduct> resolved =
          rawKey == null ? Optional.empty() : blueprintProductService.resolveByProductKey(rawKey);
      if (resolved.isEmpty()) {
        unresolved++;
        continue;
      }
      ResolvedProduct product = resolved.get();
      if (!seenInRequest.add(product.productKey())
          || repository.existsByOwnerUserIdAndProductKey(ownerUserId, product.productKey())) {
        alreadyOwned++;
        continue;
      }
      repository.save(newOwned(ownerUserId, product, null, null));
      added++;
    }
    if (added > 0) {
      auditService.record(
          AuditEventType.BLUEPRINT_BATCH_ADDED,
          null,
          null,
          ownerUserId,
          AuditDetails.of("added", added)
              .with("alreadyOwned", alreadyOwned)
              .with("unresolved", unresolved));
    }
    log.info(
        "Batch add for ownerUserId={}: added={} alreadyOwned={} unresolved={}",
        ownerUserId,
        added,
        alreadyOwned,
        unresolved);
    return new PersonalBlueprintBatchResult(added, alreadyOwned, unresolved);
  }

  /**
   * Updates the mutable fields ({@code acquiredAt}, {@code note}) of an owned blueprint with an
   * explicit optimistic-lock check.
   *
   * @param ownerUserId {@code app_user.id} of the caller
   * @param id entry primary key
   * @param request the update payload (carries the expected version)
   * @return the persisted DTO
   * @throws NotFoundException when the entry is missing or owned by someone else
   * @throws ObjectOptimisticLockingFailureException when the supplied version is stale
   */
  @Transactional
  public PersonalBlueprintResponse update(
      @NotNull UUID ownerUserId,
      @NotNull UUID id,
      @NotNull PersonalBlueprintUpdateRequest request) {
    PersonalBlueprint entity = loadOwn(ownerUserId, id);
    PersonalBlueprintResponse response = applyUpdate(entity, request);
    log.info("Updated personal blueprint id={} ownerUserId={}", id, ownerUserId);
    return response;
  }

  /**
   * Deletes an owned blueprint. 404 for an unknown id or a cross-owner attempt.
   *
   * @param ownerUserId {@code app_user.id} of the caller
   * @param id entry primary key
   * @throws NotFoundException when the entry is missing or owned by someone else
   */
  @Transactional
  public void delete(@NotNull UUID ownerUserId, @NotNull UUID id) {
    PersonalBlueprint entity = loadOwn(ownerUserId, id);
    requireRemovable(entity);
    repository.delete(entity);
    recordRemoved(entity);
    log.info("Deleted personal blueprint id={} ownerUserId={}", id, ownerUserId);
  }

  /**
   * Deletes all of the caller's removable blueprints in one bulk statement, keeping the
   * auto-granted defaults (REQ-INV-023, REQ-INV-016). Idempotent.
   *
   * @param ownerUserId {@code app_user.id} of the caller
   * @return the number of blueprints removed (never counts a preserved default)
   */
  @Transactional
  public int deleteAllOwn(@NotNull UUID ownerUserId) {
    int removed = repository.deleteRemovableByOwnerUserId(ownerUserId);
    if (removed > 0) {
      auditService.record(
          AuditEventType.BLUEPRINT_ALL_REMOVED,
          null,
          null,
          ownerUserId,
          AuditDetails.of("removed", removed));
    }
    log.info("Cleared {} personal blueprint(s) for ownerUserId={}", removed, ownerUserId);
    return removed;
  }

  /**
   * Returns the recipe graph of one of the caller's owned blueprints via {@link
   * BlueprintProductService#resolveRecipe}; an empty graph with the owned name when the product is
   * no longer listed.
   *
   * @param ownerUserId {@code app_user.id} of the caller
   * @param id owned-blueprint entry id
   * @return the recipe view (never {@code null}; empty graph when the product is unresolved)
   * @throws NotFoundException when the entry is missing or owned by someone else
   */
  @NotNull
  public PersonalBlueprintRecipeResponse recipeForOwn(@NotNull UUID ownerUserId, @NotNull UUID id) {
    PersonalBlueprint entity = loadOwn(ownerUserId, id);
    return blueprintProductService
        .resolveRecipe(entity.getProductKey())
        .orElseGet(
            () ->
                new PersonalBlueprintRecipeResponse(
                    entity.getProductName(), 0, List.of(), List.of()));
  }

  /**
   * Admin-scoped list of a target user's owned blueprints. Delegates to {@link #listOwn}; the ADMIN
   * gate lives on the controller.
   *
   * @param targetSub {@code app_user.id} of the user being inspected
   * @param query optional case-insensitive product-name filter
   * @param pageable page request
   * @return paged response DTOs
   */
  public Page<PersonalBlueprintResponse> listForUser(
      @NotNull UUID targetSub, @Nullable String query, @NotNull Pageable pageable) {
    return listOwn(targetSub, query, pageable);
  }

  /**
   * Admin-scoped single add on behalf of a target user. Delegates to {@link #add}.
   *
   * @param targetSub {@code app_user.id} of the user to add the blueprint for
   * @param request the add payload
   * @return the persisted DTO
   */
  @Transactional
  public PersonalBlueprintResponse addForUser(
      @NotNull UUID targetSub, @NotNull PersonalBlueprintCreateRequest request) {
    PersonalBlueprintResponse response = add(targetSub, request);
    log.info(
        "Admin added blueprint productKey='{}' ownerUserId={}",
        LogSafe.text(request.productKey(), 255),
        targetSub);
    return response;
  }

  /**
   * Admin-scoped multi-select add on behalf of a target user. Delegates to {@link #addBatch}.
   *
   * @param targetSub {@code app_user.id} of the user to add the blueprints for
   * @param productKeys the product keys to add
   * @return a summary of added vs. skipped keys
   */
  @NotNull
  @Transactional
  public PersonalBlueprintBatchResult addBatchForUser(
      @NotNull UUID targetSub, @NotNull List<String> productKeys) {
    PersonalBlueprintBatchResult result = addBatch(targetSub, productKeys);
    log.info("Admin batch add for ownerUserId={}: {}", targetSub, result);
    return result;
  }

  /**
   * Admin-scoped update by entry id alone, with the optimistic-lock check.
   *
   * @param id entry primary key
   * @param request the update payload (carries the expected version)
   * @return the persisted DTO
   * @throws NotFoundException when the entry id is unknown
   * @throws ObjectOptimisticLockingFailureException when the supplied version is stale
   */
  @Transactional
  public PersonalBlueprintResponse updateForUser(
      @NotNull UUID id, @NotNull PersonalBlueprintUpdateRequest request) {
    PersonalBlueprint entity =
        Entities.require(repository.findById(id), () -> "PersonalBlueprint not found: " + id);
    PersonalBlueprintResponse response = applyUpdate(entity, request);
    log.info("Admin updated blueprint id={} ownerUserId={}", id, entity.getOwnerUserId());
    return response;
  }

  /**
   * Admin-scoped delete by id alone. Logs the owner sub at INFO so the audit trail shows whose data
   * an admin call removed.
   *
   * @param id entry primary key
   * @throws NotFoundException when the entry id is unknown
   */
  @Transactional
  public void deleteForUser(@NotNull UUID id) {
    PersonalBlueprint entity =
        Entities.require(repository.findById(id), () -> "PersonalBlueprint not found: " + id);
    requireRemovable(entity);
    repository.delete(entity);
    recordRemoved(entity);
    log.info("Admin deleted blueprint id={} ownerUserId={}", id, entity.getOwnerUserId());
  }

  /**
   * Deletes the removable blueprints of every user in one bulk statement, keeping the auto-granted
   * defaults (REQ-INV-024, REQ-INV-016). Logged at WARN.
   *
   * @return the number of blueprints removed across all users (never counts a preserved default)
   */
  @Transactional
  public int deleteAllForAllUsers() {
    int removed = repository.deleteAllRemovable();
    if (removed > 0) {
      auditService.record(
          AuditEventType.BLUEPRINT_PURGED_ALL_USERS,
          null,
          null,
          null,
          AuditDetails.of("removed", removed));
    }
    log.warn("Admin cleared ALL {} removable personal blueprint(s) across every user", removed);
    return removed;
  }

  /**
   * Applies an optimistic-lock-checked update of the mutable fields ({@code acquiredAt}, {@code
   * note}) to an already-loaded managed entity. Shared by the owner-scoped {@link #update} and the
   * admin {@link #updateForUser}.
   *
   * @param entity the managed blueprint entity
   * @param request the update payload (carries the expected version)
   * @return the persisted DTO
   * @throws ObjectOptimisticLockingFailureException when the supplied version is stale
   */
  @NotNull
  private PersonalBlueprintResponse applyUpdate(
      @NotNull PersonalBlueprint entity, @NotNull PersonalBlueprintUpdateRequest request) {
    OptimisticLock.check(
        entity.getVersion(), request.version(), PersonalBlueprint.class, entity.getId());
    List<String> changed = new ArrayList<>();
    if (!Objects.equals(entity.getAcquiredAt(), request.acquiredAt())) {
      changed.add("acquiredAt");
    }
    if (!Objects.equals(entity.getNote(), request.note())) {
      changed.add("note");
    }
    entity.setAcquiredAt(request.acquiredAt());
    entity.setNote(request.note());
    PersonalBlueprint saved = repository.save(entity);
    if (!changed.isEmpty()) {
      auditService.record(
          AuditEventType.BLUEPRINT_UPDATED,
          saved.getId(),
          saved.getProductName(),
          saved.getOwnerUserId(),
          AuditDetails.of("changed", String.join(",", changed)));
    }
    return toResponse(saved);
  }

  /**
   * Records the removal of one owned blueprint in the Blueprints audit area.
   *
   * @param entity the blueprint just deleted
   */
  private void recordRemoved(@NotNull PersonalBlueprint entity) {
    auditService.record(
        AuditEventType.BLUEPRINT_REMOVED,
        entity.getId(),
        entity.getProductName(),
        entity.getOwnerUserId(),
        AuditDetails.of("product", entity.getProductKey()));
  }

  /**
   * Maps an owned blueprint to its response DTO; {@code removable} is {@code false} for a default
   * blueprint (REQ-INV-016).
   *
   * @param entity the owned blueprint
   * @return the response DTO with {@code removable} populated
   */
  @NotNull
  private PersonalBlueprintResponse toResponse(@NotNull PersonalBlueprint entity) {
    return mapper.toResponse(entity, !defaultBlueprintKeyService.isDefault(entity.getProductKey()));
  }

  /**
   * Refuses to delete an owned blueprint whose product is in the default set (REQ-INV-016).
   *
   * @param entity the owned blueprint about to be deleted
   * @throws BusinessConflictException when the entry is an auto-granted default
   */
  private void requireRemovable(@NotNull PersonalBlueprint entity) {
    if (defaultBlueprintKeyService.isDefault(entity.getProductKey())) {
      throw new BusinessConflictException("error.personalBlueprint.defaultNotRemovable");
    }
  }

  /**
   * Builds a new, unsaved owned-blueprint entity stamped with the resolved product. The output item
   * is attached as a lazy reference (no extra query) when the product resolved one.
   *
   * @param ownerUserId Keycloak {@code sub} of the owner
   * @param product the resolved product to stamp
   * @param acquiredAt optional acquisition time
   * @param note optional note
   * @return the new transient entity
   */
  @NotNull
  private PersonalBlueprint newOwned(
      UUID ownerUserId, ResolvedProduct product, Instant acquiredAt, String note) {
    PersonalBlueprint entity = new PersonalBlueprint();
    entity.setOwnerUserId(ownerUserId);
    entity.setProductKey(product.productKey());
    entity.setProductName(product.productName());
    if (product.outputItemId() != null) {
      entity.setOutputItem(gameItemRepository.getReferenceById(product.outputItemId()));
    }
    entity.setAcquiredAt(acquiredAt);
    entity.setNote(note);
    return entity;
  }

  /**
   * Loads an owned blueprint; an unknown id and another user's row both give 404.
   *
   * @param ownerUserId {@code app_user.id} of the caller
   * @param id entry primary key
   * @return the managed entity
   */
  @NotNull
  private PersonalBlueprint loadOwn(@NotNull UUID ownerUserId, @NotNull UUID id) {
    return repository
        .findByIdAndOwnerUserId(id, ownerUserId)
        .orElseThrow(
            () -> {
              log.warn(
                  "Access denied or not found: ownerUserId={} requested id={}", ownerUserId, id);
              return new NotFoundException("PersonalBlueprint not found: " + id);
            });
  }
}
