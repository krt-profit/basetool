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
import de.greluc.krt.profit.basetool.backend.mapper.PersonalBlueprintMapper;
import de.greluc.krt.profit.basetool.backend.model.PersonalBlueprint;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintBatchResult;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintCreateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintRecipeResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintUpdateRequest;
import de.greluc.krt.profit.basetool.backend.repository.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.PersonalBlueprintRepository;
import de.greluc.krt.profit.basetool.backend.service.BlueprintProductService.ResolvedProduct;
import de.greluc.krt.profit.basetool.backend.support.LogSafe;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import jakarta.persistence.EntityNotFoundException;
import java.util.HashSet;
import java.util.List;
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
 * Owner-scoped domain service for the personal-blueprint feature (#327). Every read and write is
 * scoped by the Keycloak {@code sub} so a caller can only ever see or mutate their own owned
 * blueprints. The service is {@code sub}-parameterised (it never reads the security context) so the
 * Phase 7 admin surface can reuse it for a target user.
 *
 * <p>Optimistic locking follows the project convention: the inbound update DTO carries the last
 * seen {@code version}; on mismatch an {@link ObjectOptimisticLockingFailureException} is raised so
 * the global handler maps it to HTTP 409. A duplicate add raises {@link DuplicateEntityException} →
 * 409 before the {@code (owner_sub, product_key)} unique constraint fires.
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

  /**
   * Owner-scoped paged list of owned blueprints, optionally filtered by a case-insensitive product
   * name substring.
   *
   * @param ownerSub Keycloak {@code sub} of the caller
   * @param query optional case-insensitive product-name filter
   * @param pageable page request (sort fields whitelisted by {@link #SORTABLE_FIELDS})
   * @return paged response DTOs
   */
  public Page<PersonalBlueprintResponse> listOwn(
      @NotNull String ownerSub, @Nullable String query, @NotNull Pageable pageable) {
    Page<PersonalBlueprint> page =
        (query == null || query.isBlank())
            ? repository.findAllByOwnerSub(ownerSub, pageable)
            : repository.findAllByOwnerSubAndProductNameContainingIgnoreCase(
                ownerSub, query.trim(), pageable);
    return page.map(this::toResponse);
  }

  /**
   * Adds a single blueprint to the caller's owned set. The product key is resolved against the
   * active master list and the canonical name + output item are stamped onto the new row.
   *
   * @param ownerSub Keycloak {@code sub} of the caller
   * @param request the add payload (product key + optional acquisition date / note)
   * @return the persisted DTO
   * @throws EntityNotFoundException if the product key matches no active product
   * @throws DuplicateEntityException if the caller already owns the product
   */
  @Transactional
  public PersonalBlueprintResponse add(
      @NotNull String ownerSub, @NotNull PersonalBlueprintCreateRequest request) {
    ResolvedProduct product =
        blueprintProductService
            .resolveByProductKey(request.productKey())
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        "Blueprint product not found: " + request.productKey()));
    if (repository.existsByOwnerSubAndProductKey(ownerSub, product.productKey())) {
      throw new DuplicateEntityException(
          "Blueprint '" + product.productName() + "' is already owned.");
    }
    PersonalBlueprint saved =
        repository.save(newOwned(ownerSub, product, request.acquiredAt(), request.note()));
    log.info(
        "Added personal blueprint id={} productKey='{}' ownerSub={}",
        saved.getId(),
        product.productKey(),
        ownerSub);
    return toResponse(saved);
  }

  /**
   * Multi-select add: resolves and inserts each requested product key, skipping (not failing) keys
   * the caller already owns, keys repeated within the request, and keys that resolve to no active
   * product. Bulk-safe — only new entities are persisted, no detaching bulk update runs.
   *
   * @param ownerSub Keycloak {@code sub} of the caller
   * @param productKeys the product keys to add
   * @return a summary of how many keys were added vs. skipped and why
   */
  @Transactional
  public PersonalBlueprintBatchResult addBatch(
      @NotNull String ownerSub, @NotNull List<String> productKeys) {
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
          || repository.existsByOwnerSubAndProductKey(ownerSub, product.productKey())) {
        alreadyOwned++;
        continue;
      }
      repository.save(newOwned(ownerSub, product, null, null));
      added++;
    }
    log.info(
        "Batch add for ownerSub={}: added={} alreadyOwned={} unresolved={}",
        ownerSub,
        added,
        alreadyOwned,
        unresolved);
    return new PersonalBlueprintBatchResult(added, alreadyOwned, unresolved);
  }

  /**
   * Updates the mutable fields ({@code acquiredAt}, {@code note}) of an owned blueprint with an
   * explicit optimistic-lock check.
   *
   * @param ownerSub Keycloak {@code sub} of the caller
   * @param id entry primary key
   * @param request the update payload (carries the expected version)
   * @return the persisted DTO
   * @throws EntityNotFoundException when the entry is missing or owned by someone else
   * @throws ObjectOptimisticLockingFailureException when the supplied version is stale
   */
  @Transactional
  public PersonalBlueprintResponse update(
      @NotNull String ownerSub, @NotNull UUID id, @NotNull PersonalBlueprintUpdateRequest request) {
    PersonalBlueprint entity = loadOwn(ownerSub, id);
    PersonalBlueprintResponse response = applyUpdate(entity, request);
    log.info("Updated personal blueprint id={} ownerSub={}", id, ownerSub);
    return response;
  }

  /**
   * Deletes an owned blueprint. 404 for an unknown id or a cross-owner attempt.
   *
   * @param ownerSub Keycloak {@code sub} of the caller
   * @param id entry primary key
   * @throws EntityNotFoundException when the entry is missing or owned by someone else
   */
  @Transactional
  public void delete(@NotNull String ownerSub, @NotNull UUID id) {
    PersonalBlueprint entity = loadOwn(ownerSub, id);
    requireRemovable(entity);
    repository.delete(entity);
    log.info("Deleted personal blueprint id={} ownerSub={}", id, ownerSub);
  }

  /**
   * Clears the caller's <em>removable</em> owned blueprints in one bulk statement — the "delete all
   * my blueprints" action (REQ-INV-023). The auto-granted, non-removable default blueprints
   * (REQ-INV-016) are preserved, exactly as the per-row {@link #delete} guard would, so a full
   * clear leaves the user's defaults intact rather than churning them against the provisioning
   * sweep. Idempotent: clearing an already-empty (or default-only) set removes nothing and returns
   * {@code 0}.
   *
   * @param ownerSub Keycloak {@code sub} of the caller
   * @return the number of blueprints removed (never counts a preserved default)
   */
  @Transactional
  public int deleteAllOwn(@NotNull String ownerSub) {
    int removed = repository.deleteRemovableByOwnerSub(ownerSub);
    log.info("Cleared {} personal blueprint(s) for ownerSub={}", removed, ownerSub);
    return removed;
  }

  /**
   * Owner-scoped recipe view for one of the caller's owned blueprints (#327): loads the entry (404
   * if missing or foreign), then resolves its product key to a representative SC Wiki recipe graph
   * (ingredients + per-quality stat modifiers) via {@link BlueprintProductService#resolveRecipe}.
   * If the master no longer lists the product the view degrades to an empty graph carrying the
   * owned-row product name, so the UI still has a label to show.
   *
   * @param ownerSub Keycloak {@code sub} of the caller
   * @param id owned-blueprint entry id
   * @return the recipe view (never {@code null}; empty graph when the product is unresolved)
   * @throws EntityNotFoundException when the entry is missing or owned by someone else
   */
  @NotNull
  public PersonalBlueprintRecipeResponse recipeForOwn(@NotNull String ownerSub, @NotNull UUID id) {
    PersonalBlueprint entity = loadOwn(ownerSub, id);
    return blueprintProductService
        .resolveRecipe(entity.getProductKey())
        .orElseGet(
            () ->
                new PersonalBlueprintRecipeResponse(
                    entity.getProductName(), 0, List.of(), List.of()));
  }

  // ---------------------------------------------------------------------------------
  // Admin-scoped variants (#327, Phase 7). The ADMIN role is enforced at the controller
  // boundary; these methods take the target sub / id directly. List / add / batch reuse
  // the owner-scoped methods with the target sub; update / delete resolve by id alone
  // (admins are trusted to know the id) and log the owner sub for the audit trail.
  // ---------------------------------------------------------------------------------

  /**
   * Admin-scoped list of a target user's owned blueprints. Delegates to {@link #listOwn}; the ADMIN
   * gate lives on the controller.
   *
   * @param targetSub Keycloak {@code sub} of the user being inspected
   * @param query optional case-insensitive product-name filter
   * @param pageable page request
   * @return paged response DTOs
   */
  public Page<PersonalBlueprintResponse> listForUser(
      @NotNull String targetSub, @Nullable String query, @NotNull Pageable pageable) {
    return listOwn(targetSub, query, pageable);
  }

  /**
   * Admin-scoped single add on behalf of a target user. Delegates to {@link #add}.
   *
   * @param targetSub Keycloak {@code sub} of the user to add the blueprint for
   * @param request the add payload
   * @return the persisted DTO
   */
  @Transactional
  public PersonalBlueprintResponse addForUser(
      @NotNull String targetSub, @NotNull PersonalBlueprintCreateRequest request) {
    PersonalBlueprintResponse response = add(targetSub, request);
    // The raw, client-supplied key — not the resolved product's — so it goes through LogSafe: a
    // member could otherwise paste a newline plus a fake log prefix and forge a second line
    // (CWE-117). 255 mirrors the column and the DTO's @Size.
    log.info(
        "Admin added blueprint productKey='{}' ownerSub={}",
        LogSafe.text(request.productKey(), 255),
        targetSub);
    return response;
  }

  /**
   * Admin-scoped multi-select add on behalf of a target user. Delegates to {@link #addBatch}.
   *
   * @param targetSub Keycloak {@code sub} of the user to add the blueprints for
   * @param productKeys the product keys to add
   * @return a summary of added vs. skipped keys
   */
  @Transactional
  public PersonalBlueprintBatchResult addBatchForUser(
      @NotNull String targetSub, @NotNull List<String> productKeys) {
    PersonalBlueprintBatchResult result = addBatch(targetSub, productKeys);
    log.info("Admin batch add for ownerSub={}: {}", targetSub, result);
    return result;
  }

  /**
   * Admin-scoped update by id alone (admins are trusted to know the id). The optimistic-lock check
   * still applies.
   *
   * @param id entry primary key
   * @param request the update payload (carries the expected version)
   * @return the persisted DTO
   * @throws EntityNotFoundException when the entry id is unknown
   * @throws ObjectOptimisticLockingFailureException when the supplied version is stale
   */
  @Transactional
  public PersonalBlueprintResponse updateForUser(
      @NotNull UUID id, @NotNull PersonalBlueprintUpdateRequest request) {
    PersonalBlueprint entity =
        repository
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException("PersonalBlueprint not found: " + id));
    PersonalBlueprintResponse response = applyUpdate(entity, request);
    log.info("Admin updated blueprint id={} ownerSub={}", id, entity.getOwnerSub());
    return response;
  }

  /**
   * Admin-scoped delete by id alone. Logs the owner sub at INFO so the audit trail shows whose data
   * an admin call removed.
   *
   * @param id entry primary key
   * @throws EntityNotFoundException when the entry id is unknown
   */
  @Transactional
  public void deleteForUser(@NotNull UUID id) {
    PersonalBlueprint entity =
        repository
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException("PersonalBlueprint not found: " + id));
    requireRemovable(entity);
    repository.delete(entity);
    log.info("Admin deleted blueprint id={} ownerSub={}", id, entity.getOwnerSub());
  }

  /**
   * Admin global purge: clears the <em>removable</em> owned blueprints of <strong>every</strong>
   * user in one bulk statement (REQ-INV-024). Preserves the auto-granted defaults (REQ-INV-016) so
   * the purge does not fight the default-provisioning sweep. The ADMIN gate lives on the
   * controller; logged at WARN because it removes data across all users at once.
   *
   * @return the number of blueprints removed across all users (never counts a preserved default)
   */
  @Transactional
  public int deleteAllForAllUsers() {
    int removed = repository.deleteAllRemovable();
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
    entity.setAcquiredAt(request.acquiredAt());
    entity.setNote(request.note());
    return toResponse(repository.save(entity));
  }

  /**
   * Maps an owned blueprint to its response DTO, computing the {@code removable} flag from the
   * cached default-blueprint key set: a default blueprint (REQ-INV-016) is non-removable so the UI
   * can hide its delete control.
   *
   * @param entity the owned blueprint
   * @return the response DTO with {@code removable} populated
   */
  @NotNull
  private PersonalBlueprintResponse toResponse(@NotNull PersonalBlueprint entity) {
    return mapper.toResponse(entity, !defaultBlueprintKeyService.isDefault(entity.getProductKey()));
  }

  /**
   * Guards a delete: refuses to remove an owned blueprint whose product is in the default set
   * (REQ-INV-016). The frontend already hides the delete control for these, so this is the
   * server-side enforcement for a hand-crafted request.
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
   * @param ownerSub Keycloak {@code sub} of the owner
   * @param product the resolved product to stamp
   * @param acquiredAt optional acquisition time
   * @param note optional note
   * @return the new transient entity
   */
  @NotNull
  private PersonalBlueprint newOwned(
      String ownerSub, ResolvedProduct product, java.time.Instant acquiredAt, String note) {
    PersonalBlueprint entity = new PersonalBlueprint();
    entity.setOwnerSub(ownerSub);
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
   * Owner-scoped load; 404 (via {@link EntityNotFoundException}) for an unknown id or a row owned
   * by a different user — the two cases are deliberately indistinguishable on the wire.
   *
   * @param ownerSub Keycloak {@code sub} of the caller
   * @param id entry primary key
   * @return the managed entity
   */
  @NotNull
  private PersonalBlueprint loadOwn(@NotNull String ownerSub, @NotNull UUID id) {
    return repository
        .findByIdAndOwnerSub(id, ownerSub)
        .orElseThrow(
            () -> {
              log.warn("Access denied or not found: ownerSub={} requested id={}", ownerSub, id);
              return new EntityNotFoundException("PersonalBlueprint not found: " + id);
            });
  }
}
