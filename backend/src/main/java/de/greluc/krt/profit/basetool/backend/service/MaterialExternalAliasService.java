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

import de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialExternalAlias;
import de.greluc.krt.profit.basetool.backend.model.MaterialExternalAliasSource;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExternalAliasWriteRequest;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExternalAliasRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business operations on {@link MaterialExternalAlias}. The service is the single seam between the
 * admin REST controller and the JPA repository — controllers must not inject the repository
 * directly (enforced by the {@code controllerLayerShouldNotDependOnRepositoryLayer} ArchUnit rule).
 *
 * <p>{@code createdBy} is stamped from the JWT principal name on every create. The V108 seed
 * inserts use the literal {@code "system"} so admin-created rows are distinguishable from the
 * R1-seeded fuzzy / manual aliases in audit views.
 *
 * <p>The {@code (sourceSystem, externalName)} uniqueness is case-INSENSITIVE — matching the
 * resolution lookup, so {@link #resolveMaterialByAlias} can never see two candidate rows
 * (REQ-REFINERY-010). It is enforced both by the V146 DB unique index on {@code (source_system,
 * LOWER(external_name))} (catch-all defence) and pre-emptively here so the caller gets a clean
 * {@link DuplicateEntityException} → 409 instead of a generic {@code
 * DataIntegrityViolationException}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MaterialExternalAliasService {

  private final MaterialExternalAliasRepository repository;
  private final MaterialRepository materialRepository;
  private final AuthHelperService authHelperService;

  /**
   * Returns every alias sorted by external name. Drives the admin table view.
   *
   * @return all alias rows, sorted alphabetically by external_name
   */
  public List<MaterialExternalAlias> findAll() {
    return repository.findAllByOrderByExternalNameAsc();
  }

  /**
   * Looks up an alias by id.
   *
   * @param id alias UUID
   * @return the alias entity
   * @throws NotFoundException if no row exists for the given id
   */
  public MaterialExternalAlias findById(UUID id) {
    return repository
        .findById(id)
        .orElseThrow(
            () -> new NotFoundException("Material external alias " + id + " does not exist."));
  }

  /**
   * Resolution-chain lookup consumed by the R3 SC Wiki commodity sync (and the R6 UEX counterpart).
   * Case-insensitive on {@code externalName} so a patch-version casing drift on the upstream side
   * still resolves to the curated row.
   *
   * @param sourceSystem catalogue the alias belongs to
   * @param externalName the external commodity name (case-insensitive match)
   * @return the resolved material if an alias exists, {@code null} otherwise
   */
  public Material resolveMaterialByAlias(
      MaterialExternalAliasSource sourceSystem, String externalName) {
    if (externalName == null || externalName.isBlank()) {
      return null;
    }
    return repository
        .findBySourceSystemAndExternalNameIgnoreCase(sourceSystem, externalName)
        .map(MaterialExternalAlias::getMaterial)
        .orElse(null);
  }

  /**
   * Returns every alias of one source system. Consumed by the refinery import, which folds the
   * {@code REFINERY_SCREEN} alias names through the shared canonicalizer and uses them as
   * containment anchors for game-UI-truncated reads (REQ-REFINERY-004 stage 3).
   *
   * @param sourceSystem catalogue the aliases belong to
   * @return all alias rows of that source
   */
  public List<MaterialExternalAlias> findBySourceSystem(MaterialExternalAliasSource sourceSystem) {
    return repository.findBySourceSystem(sourceSystem);
  }

  /**
   * Persists a new alias. Validates that the referenced material exists and that no alias with the
   * same {@code (sourceSystem, externalName)} exists yet — compared case-insensitively, matching
   * the V146 unique index and the resolution lookup (REQ-REFINERY-010); on a duplicate the row is
   * NOT saved and a {@link DuplicateEntityException} is thrown so the controller can map it to HTTP
   * 409.
   *
   * <p>{@code createdBy} is stamped from the authenticated principal — {@code "system"} when no
   * principal can be resolved (defensive default; the controller's {@code @PreAuthorize} gate
   * already requires {@code ROLE_ADMIN}, but tests run without a full security context).
   *
   * @param request validated create payload
   * @return the persisted alias row
   * @throws NotFoundException if {@code request.materialId()} does not point at a known material
   * @throws DuplicateEntityException if an alias for the same source / external name already exists
   */
  @Transactional
  public MaterialExternalAlias create(MaterialExternalAliasWriteRequest request) {
    MaterialExternalAliasSource source =
        MaterialExternalAliasSource.valueOf(request.sourceSystem());
    Material material =
        materialRepository
            .findById(request.materialId())
            .orElseThrow(
                () ->
                    new NotFoundException("Material " + request.materialId() + " does not exist."));
    assertNoAliasConflict(source, request.externalName(), null);

    MaterialExternalAlias alias = new MaterialExternalAlias();
    applyWritableFields(alias, material, source, request);
    alias.setCreatedBy(currentPrincipalNameOrSystem());
    MaterialExternalAlias saved = repository.save(alias);
    log.info(
        "Admin alias created: source={} externalName='{}' material={} by={}",
        source,
        saved.getExternalName(),
        material.getName(),
        saved.getCreatedBy());
    return saved;
  }

  /**
   * Applies an update to an existing alias. The {@code version} on the request must match the row's
   * current {@code @Version} or Hibernate raises an optimistic-lock failure → HTTP 409 via {@link
   * de.greluc.krt.profit.basetool.backend.exception.GlobalExceptionHandler}.
   *
   * @param id alias UUID to update
   * @param request validated update payload
   * @return the persisted alias row
   * @throws NotFoundException if {@code id} or {@code request.materialId()} does not exist
   * @throws DuplicateEntityException if the new {@code (sourceSystem, externalName)} collides
   *     case-insensitively with a different row (REQ-REFINERY-010)
   */
  @Transactional
  public MaterialExternalAlias update(UUID id, MaterialExternalAliasWriteRequest request) {
    MaterialExternalAlias alias = findById(id);
    OptimisticLock.check(alias.getVersion(), request.version(), MaterialExternalAlias.class, id);
    MaterialExternalAliasSource source =
        MaterialExternalAliasSource.valueOf(request.sourceSystem());
    Material material =
        materialRepository
            .findById(request.materialId())
            .orElseThrow(
                () ->
                    new NotFoundException("Material " + request.materialId() + " does not exist."));
    assertNoAliasConflict(source, request.externalName(), id);

    applyWritableFields(alias, material, source, request);
    // saveAndFlush so the returned entity carries the flushed @Version — the alias edit form writes
    // it back into its hidden version input in place (no reload), so a stale save version would 409
    // the next consecutive edit of the same alias.
    MaterialExternalAlias saved = repository.saveAndFlush(alias);
    log.info("Admin alias updated: id={} by={}", saved.getId(), currentPrincipalNameOrSystem());
    return saved;
  }

  /**
   * Removes an alias by id. No referential side-effects: no other table references {@code
   * material_external_alias}.
   *
   * @param id alias UUID to delete
   * @throws NotFoundException if no row exists for the given id
   */
  @Transactional
  public void delete(UUID id) {
    MaterialExternalAlias alias = findById(id);
    repository.delete(alias);
    log.info("Admin alias deleted: id={} by={}", id, currentPrincipalNameOrSystem());
  }

  /**
   * Guards the case-insensitive {@code (sourceSystem, externalName)} uniqueness (REQ-REFINERY-010)
   * before a create / update flushes, so the caller gets a clean {@link DuplicateEntityException} →
   * 409 rather than the DB unique index's generic {@code DataIntegrityViolationException}.
   *
   * @param source the alias source system
   * @param externalName the external name to check (matched case-insensitively)
   * @param excludeId the row being updated, excluded from the collision check, or {@code null} on a
   *     create so every existing row counts
   * @throws DuplicateEntityException if a different row already holds the same source / external
   *     name
   */
  private void assertNoAliasConflict(
      MaterialExternalAliasSource source, String externalName, @Nullable UUID excludeId) {
    repository
        .findBySourceSystemAndExternalNameIgnoreCase(source, externalName)
        .filter(existing -> excludeId == null || !existing.getId().equals(excludeId))
        .ifPresent(
            existing -> {
              throw new DuplicateEntityException(
                  "Alias '"
                      + externalName
                      + "' already exists for source "
                      + source
                      + " (case-insensitive match: '"
                      + existing.getExternalName()
                      + "').");
            });
  }

  /**
   * Copies the writable fields of a create / update request onto an alias entity — the material
   * reference, source system, external name / key / uuid / code and note. The {@code createdBy}
   * stamp is intentionally not touched here so it stays create-only.
   *
   * @param alias the target entity (new or managed)
   * @param material the resolved material reference
   * @param source the resolved source system
   * @param request the validated write payload
   */
  private void applyWritableFields(
      MaterialExternalAlias alias,
      Material material,
      MaterialExternalAliasSource source,
      MaterialExternalAliasWriteRequest request) {
    alias.setMaterial(material);
    alias.setSourceSystem(source);
    alias.setExternalName(request.externalName());
    alias.setExternalKey(request.externalKey());
    alias.setExternalUuid(request.externalUuid());
    alias.setExternalCode(request.externalCode());
    alias.setNote(request.note());
  }

  /**
   * Resolves the JWT principal name for the {@code createdBy} stamp, defaulting to {@code "system"}
   * when no principal is available. Centralised here so create / update / delete log lines stay
   * consistent.
   *
   * @return JWT subject of the caller, or {@code "system"} if the security context is empty
   */
  private String currentPrincipalNameOrSystem() {
    return authHelperService.currentAuthentication().map(Authentication::getName).orElse("system");
  }
}
