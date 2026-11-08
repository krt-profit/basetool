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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditChangedFields;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialCategory;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialDeletionObserver;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialMapper;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialPriceOverviewDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.kernel.AbstractEntity;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.LikePatterns;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.platform.api.CacheConfig;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads materials and maintains their admin-owned fields: category, refined-material mapping and
 * the manual {@code isJobOrder} / {@code isManualRawMaterial} flags. The catalog itself comes from
 * {@link UexCommodityService}; every write evicts all material caches.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MaterialService {

  private final MaterialRepository materialRepository;
  private final MaterialPriceRepository materialPriceRepository;
  private final MaterialCategoryRepository materialCategoryRepository;
  private final MaterialMapper materialMapper;
  private final MaterialDeletionObserver materialDeletionObserver;
  private final AuditRecorder auditRecorder;

  /**
   * Returns the cached page of all materials, including hidden ones.
   *
   * @param pageable page request
   * @return cached paged list of all material read models
   */
  @Cacheable(cacheNames = CacheConfig.MATERIALS_CACHE, key = "'all-' + #pageable")
  public Page<MaterialDto> getAllMaterials(@NotNull Pageable pageable) {
    return materialRepository.findAll(pageable).map(materialMapper::toDto);
  }

  /**
   * Returns the cached page of visible materials ({@code is_visible = true}) for the public and
   * trading catalog.
   *
   * @param pageable page request
   * @return cached paged list of visible material read models
   */
  @Cacheable(cacheNames = CacheConfig.MATERIALS_CACHE, key = "'visible-' + #pageable")
  public Page<MaterialDto> getVisibleMaterials(@NotNull Pageable pageable) {
    return materialRepository
        .findByIsVisibleTrueAndP4kDeletedAtIsNullAndP4kHiddenAtIsNull(pageable)
        .map(materialMapper::toDto);
  }

  /**
   * Pages the materials whose name contains {@code query} case-insensitively; not cached, because
   * the search term would make the cache key unbounded.
   *
   * @param query the search fragment, already stripped and non-blank
   * @param includeHidden true to include materials with {@code is_visible = false}
   * @param pageable page request
   * @return paged matching material read models
   */
  public Page<MaterialDto> searchMaterials(
      @NotNull String query, boolean includeHidden, @NotNull Pageable pageable) {
    return (includeHidden
            ? materialRepository.findByNameContainingIgnoreCase(query, pageable)
            : materialRepository.searchPickableByName(query, pageable))
        .map(materialMapper::toDto);
  }

  /**
   * Variant that eager-fetches the per-terminal price list via {@code @EntityGraph} on the
   * repository — used by views that show prices inline.
   *
   * @param pageable page request
   * @return paged list of materials with prices eagerly loaded
   */
  public Page<Material> getAllMaterialsWithPrices(@NotNull Pageable pageable) {
    return materialRepository.findAllWithPrices(pageable);
  }

  /**
   * Returns the per-material best buy and best sell summary for the materials-overview page.
   *
   * @param name optional case-insensitive substring filter (empty = all)
   * @param pageable page request
   * @return paged price-overview DTOs
   */
  public Page<MaterialPriceOverviewDto> getMaterialPriceOverview(
      @NotNull String name, @NotNull Pageable pageable) {
    return materialRepository.getMaterialPriceOverview(LikePatterns.escapeNullable(name), pageable);
  }

  /**
   * Returns id, name and quantity type of every visible material for typeaheads, unbounded.
   *
   * @return all visible materials as reference DTOs
   */
  public List<MaterialReferenceDto> findAllReference() {
    return materialRepository.findAllReference();
  }

  /**
   * Pages visible materials whose name contains {@code search} literally and case-insensitively,
   * for the material pickers (REQ-FE-016). Uncached.
   *
   * @param search the name fragment, or {@code null}/blank for the unfiltered first page
   * @param jobOrderOnly when true, only {@code isJobOrder = true} materials
   * @param rawOnly when true, only refinery inputs (RAW type or manually raw-flagged)
   * @param pageable page request from the whitelisted picker sort
   * @return one page of matching visible materials with the refined material initialized
   */
  public Page<Material> searchPicker(
      @Nullable String search, boolean jobOrderOnly, boolean rawOnly, @NotNull Pageable pageable) {
    return materialRepository.searchPicker(
        LikePatterns.escapeNullable(search), jobOrderOnly, MaterialType.RAW, rawOnly, pageable);
  }

  /**
   * Returns the material read model from the by-id cache.
   *
   * @param id material primary key
   * @return the material read model
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when no match
   */
  @Cacheable(cacheNames = CacheConfig.MATERIAL_BY_ID_CACHE)
  public MaterialDto getMaterial(@NotNull UUID id) {
    return materialMapper.toDetailDto(requireMaterial(id));
  }

  /**
   * Loads the managed material entity a mutator edits; it never comes from the cache.
   *
   * @param id material primary key
   * @return the managed entity
   */
  private Material requireMaterial(UUID id) {
    return Entities.require(materialRepository.findById(id), "Material not found");
  }

  /**
   * Paged per-material price list.
   *
   * @param id material primary key
   * @param pageable page request
   * @return paged price DTOs (one per terminal that trades this material)
   */
  public Page<MaterialPriceDto> getMaterialPrices(@NotNull UUID id, @NotNull Pageable pageable) {
    return materialPriceRepository.findPricesByMaterialId(id, pageable);
  }

  /**
   * Per-material selling-terminal projection used by the inventory page to suggest where to sell.
   *
   * @param id material primary key
   * @return list of selling-terminal DTOs (terminal + sell price)
   */
  public List<MaterialSellingTerminalDto> getMaterialTerminals(@NotNull UUID id) {
    return materialPriceRepository.findSellingTerminalsByMaterialId(id);
  }

  /**
   * Returns the material × terminal matrix with up to four optional filters (REQ-UI-014); a {@code
   * null} or empty filter argument leaves that dimension unconstrained.
   *
   * @param materialNames material names to keep, or {@code null}/empty for all
   * @param starSystems star-system names to keep, or {@code null}/empty for all
   * @param hasLoadingDock {@code TRUE} to keep only loading-dock terminals, {@code null} for all
   * @param isAutoLoad {@code TRUE} to keep only auto-load terminals, {@code null} for all
   * @param pageable page request
   * @return paged (filtered) matrix items
   */
  public Page<MaterialMatrixItemDto> getMatrixItems(
      Collection<String> materialNames,
      Collection<String> starSystems,
      Boolean hasLoadingDock,
      Boolean isAutoLoad,
      @NotNull Pageable pageable) {
    return materialPriceRepository.findMatrixItems(
        emptyToNull(materialNames), emptyToNull(starSystems), hasLoadingDock, isAutoLoad, pageable);
  }

  /**
   * Maps an empty {@code IN}-filter collection to {@code null}, the repository's "no filter" value.
   *
   * @param values the filter values, possibly {@code null} or empty
   * @param <T> the element type
   * @return {@code null} when {@code values} is {@code null} or empty, otherwise {@code values}
   */
  @Nullable
  private static <T> Collection<T> emptyToNull(Collection<T> values) {
    return values == null || values.isEmpty() ? null : values;
  }

  /**
   * Returns only the materials flagged as job-order materials, sorted by name. Used by the
   * job-order create form's material picker.
   *
   * @return job-order materials in alphabetical order
   */
  public List<Material> getAllJobOrderMaterials() {
    return materialRepository.findPickableJobOrderMaterials();
  }

  /**
   * Persists a manually entered material that UEX does not provide, stamped {@code
   * sourceSystems=MANUAL}. UEX-imported columns stay {@code null}; {@code refinedMaterialId} is
   * only accepted for a raw material.
   *
   * @param dto validated create payload
   * @return the persisted material
   * @throws BadRequestException when {@code type}/{@code quantityType} cannot be parsed, or when
   *     {@code refinedMaterialId} is set on a non-raw material
   * @throws NotFoundException when {@code refinedMaterialId} or {@code categoryId} does not exist
   */
  @Transactional
  @EvictAllMaterialCaches
  public Material createMaterial(@NotNull MaterialCreateDto dto) {
    MaterialType type;
    try {
      type = MaterialType.valueOf(dto.type());
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("Unknown material type: " + dto.type(), e);
    }
    QuantityType quantityType;
    try {
      quantityType = QuantityType.valueOf(dto.quantityType());
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("Unknown quantity type: " + dto.quantityType(), e);
    }

    boolean isManualRaw = Boolean.TRUE.equals(dto.isManualRawMaterial());
    boolean isRefineryInput = MaterialType.RAW.equals(type) || isManualRaw;

    if (dto.refinedMaterialId() != null && !isRefineryInput) {
      throw new BadRequestException(
          "refinedMaterialId may only be set on raw materials (type=RAW or"
              + " isManualRawMaterial=true)");
    }

    Material material = new Material();
    material.setName(dto.name());
    material.setType(type);
    material.setQuantityType(quantityType);
    material.setDescription(dto.description());
    material.setIsManualRawMaterial(isManualRaw);
    material.setIsJobOrder(Boolean.TRUE.equals(dto.isJobOrder()));
    material.setIsIllegal(Boolean.TRUE.equals(dto.isIllegal()) ? 1 : 0);
    material.setIsVolatileQt(Boolean.TRUE.equals(dto.isVolatileQt()) ? 1 : 0);
    material.setIsVolatileTime(Boolean.TRUE.equals(dto.isVolatileTime()) ? 1 : 0);
    material.setSourceSystems(MaterialSourceSystem.MANUAL);

    if (dto.refinedMaterialId() != null) {
      Material refined =
          Entities.require(
              materialRepository.findById(dto.refinedMaterialId()), "Refined material not found");
      material.setRefinedMaterial(refined);
    }

    if (dto.categoryId() != null) {
      MaterialCategory category =
          Entities.require(
              materialCategoryRepository.findById(dto.categoryId()), "Material category not found");
      material.setCategory(category);
    }

    Material saved = materialRepository.save(material);
    auditRecorder.record(
        AuditEventType.CATALOGUE_MATERIAL_CREATED,
        saved.getId(),
        saved.getName(),
        null,
        AuditDetails.of("type", type).with("quantityType", quantityType));
    return saved;
  }

  /**
   * Updates the admin-maintained fields of a material; UEX-imported fields are not changed. Unknown
   * refined-material or category ids resolve to {@code null}. A changed resource group or refined
   * material of a P4K-known material becomes an admin override ({@link MaterialP4kOverride}).
   *
   * @param id material primary key
   * @param materialDetails the new values, including the expected version
   * @return the persisted material
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the supplied
   *     version is stale
   */
  @Transactional
  @EvictAllMaterialCaches
  public Material updateMaterial(@NotNull UUID id, @NotNull Material materialDetails) {
    Material material = requireMaterial(id);

    OptimisticLock.checkOptionalClient(
        material.getVersion(), materialDetails.getVersion(), Material.class, id);

    AuditChangedFields changed = changedFields(material, materialDetails);
    if (!changed.isEmpty()) {
      auditRecorder.record(
          AuditEventType.CATALOGUE_MATERIAL_UPDATED,
          id,
          materialDetails.getName(),
          null,
          changed.toDetails());
    }
    material.setName(materialDetails.getName());
    material.setType(materialDetails.getType());
    material.setDescription(materialDetails.getDescription());
    material.setQuantityType(materialDetails.getQuantityType());
    material.setIsManualRawMaterial(materialDetails.getIsManualRawMaterial());
    material.setIsJobOrder(materialDetails.getIsJobOrder());
    if (materialDetails.getIsVisible() != null) {
      material.setIsVisible(materialDetails.getIsVisible());
    }

    UUID refinedBefore = idOf(material.getRefinedMaterial());
    if (materialDetails.getRefinedMaterial() != null
        && materialDetails.getRefinedMaterial().getId() != null) {
      Material refined =
          materialRepository.findById(materialDetails.getRefinedMaterial().getId()).orElse(null);
      material.setRefinedMaterial(refined);
    } else {
      material.setRefinedMaterial(null);
    }

    UUID categoryBefore = idOf(material.getCategory());
    if (materialDetails.getCategory() != null && materialDetails.getCategory().getId() != null) {
      MaterialCategory category =
          materialCategoryRepository.findById(materialDetails.getCategory().getId()).orElse(null);
      material.setCategory(category);
    } else {
      material.setCategory(null);
    }

    overrideIfChanged(
        material,
        MaterialP4kOverride.REFINED_MATERIAL,
        refinedBefore,
        material.getRefinedMaterial());
    overrideIfChanged(
        material, MaterialP4kOverride.RESOURCE_GROUP, categoryBefore, material.getCategory());
    return materialRepository.save(material);
  }

  /**
   * Marks a field the P4K leads as an admin override when an edit changes it on a P4K-known
   * material, and audits the override once it is set (REQ-P4K-024, REQ-P4K-025).
   *
   * @param material the edited material
   * @param field the field
   * @param before the id the field held before the edit
   * @param after the related row the field holds now
   */
  private void overrideIfChanged(
      @NotNull Material material,
      @NotNull MaterialP4kOverride field,
      @Nullable UUID before,
      @Nullable AbstractEntity<UUID> after) {
    UUID afterId = idOf(after);
    if (material.getP4kUuid() == null || Objects.equals(before, afterId) || field.isSet(material)) {
      return;
    }
    field.set(material, true);
    auditRecorder.record(
        AuditEventType.CATALOGUE_MATERIAL_P4K_OVERRIDE_SET,
        material.getId(),
        material.getName(),
        null,
        AuditDetails.of("field", field.name()).with("valueId", afterId));
  }

  /**
   * Hands an overridden field of a material back to the P4K import: the next APPLY writes the P4K
   * value again (REQ-P4K-024, REQ-P4K-025). A field without an override stays as it is.
   *
   * @param id material primary key
   * @param field the field to release
   * @param version the material version the admin saw
   * @return the material
   * @throws NotFoundException when no material has the id
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the version is
   *     stale
   */
  @Transactional
  @EvictAllMaterialCaches
  public Material releaseP4kOverride(
      @NotNull UUID id, @NotNull MaterialP4kOverride field, long version) {
    Material material = requireMaterial(id);
    OptimisticLock.checkRequired(material.getVersion(), version, Material.class, id);
    if (!field.isSet(material)) {
      return material;
    }
    field.set(material, false);
    auditRecorder.record(
        AuditEventType.CATALOGUE_MATERIAL_P4K_OVERRIDE_RELEASED,
        id,
        material.getName(),
        null,
        AuditDetails.of("field", field.name()));
    return materialRepository.saveAndFlush(material);
  }

  /**
   * Names the fields an update changes; the visibility switch counts only when the update sets it.
   *
   * @param material the stored material
   * @param details the incoming values
   * @return the changed field names
   */
  @NotNull
  private static AuditChangedFields changedFields(
      @NotNull Material material, @NotNull Material details) {
    AuditChangedFields changed =
        AuditChangedFields.create()
            .compare("name", material.getName(), details.getName())
            .compare("type", material.getType(), details.getType())
            .compare("description", material.getDescription(), details.getDescription())
            .compare("quantityType", material.getQuantityType(), details.getQuantityType())
            .compare(
                "isManualRawMaterial",
                material.getIsManualRawMaterial(),
                details.getIsManualRawMaterial())
            .compare("isJobOrder", material.getIsJobOrder(), details.getIsJobOrder())
            .compare(
                "refinedMaterial",
                idOf(material.getRefinedMaterial()),
                idOf(details.getRefinedMaterial()))
            .compare("category", idOf(material.getCategory()), idOf(details.getCategory()));
    if (details.getIsVisible() != null) {
      changed.compare("isVisible", material.getIsVisible(), details.getIsVisible());
    }
    return changed;
  }

  /**
   * The id of an optional related row, for comparing references by identity.
   *
   * @param entity the related row, or {@code null}
   * @return its id, or {@code null} when there is no row
   */
  @Nullable
  private static UUID idOf(@Nullable AbstractEntity<UUID> entity) {
    return entity == null ? null : entity.getId();
  }

  /**
   * Deletes a material together with its minimum stock levels. Backend rejects the delete when any
   * inventory item or price row still references it (FK constraint surfaces as 409 via the global
   * handler).
   *
   * @param id material primary key
   */
  @Transactional
  @EvictAllMaterialCaches
  public void deleteMaterial(@NotNull UUID id) {
    Material material = requireMaterial(id);
    materialDeletionObserver.beforeMaterialDelete(id);
    materialRepository.delete(material);
    auditRecorder.record(
        AuditEventType.CATALOGUE_MATERIAL_DELETED, id, material.getName(), null, null);
  }
}
