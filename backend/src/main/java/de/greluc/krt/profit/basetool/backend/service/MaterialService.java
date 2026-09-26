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

import de.greluc.krt.profit.basetool.backend.config.CacheConfig;
import de.greluc.krt.profit.basetool.backend.config.EvictAllMaterialCaches;
import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialCategory;
import de.greluc.krt.profit.basetool.backend.model.MaterialSourceSystem;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialCreateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialMatrixItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialPriceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialPriceOverviewDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialSellingTerminalDto;
import de.greluc.krt.profit.basetool.backend.repository.MaterialCategoryRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialPriceRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.support.CachedEntityGraphs;
import de.greluc.krt.profit.basetool.backend.support.LikePatterns;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import java.util.Collection;
import java.util.List;
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

  /**
   * Returns the cached page of all materials, including hidden ones.
   *
   * @param pageable page request
   * @return cached paged list of all materials
   */
  @Cacheable(cacheNames = CacheConfig.MATERIALS_CACHE, key = "'all-' + #pageable")
  public Page<Material> getAllMaterials(@NotNull Pageable pageable) {
    return CachedEntityGraphs.materials(materialRepository.findAll(pageable));
  }

  /**
   * Returns the cached page of visible materials ({@code is_visible = true}) for the public and
   * trading catalog.
   *
   * @param pageable page request
   * @return cached paged list of visible materials
   */
  @Cacheable(cacheNames = CacheConfig.MATERIALS_CACHE, key = "'visible-' + #pageable")
  public Page<Material> getVisibleMaterials(@NotNull Pageable pageable) {
    return CachedEntityGraphs.materials(materialRepository.findByIsVisibleTrue(pageable));
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
   * Returns the material from the by-id cache. The instance is shared across callers and must be
   * treated as read-only.
   *
   * @param id material primary key
   * @return the material
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when no match
   */
  @Cacheable(cacheNames = CacheConfig.MATERIAL_BY_ID_CACHE)
  public Material getMaterial(@NotNull UUID id) {
    return CachedEntityGraphs.material(
        Entities.require(materialRepository.findById(id), "Material not found"));
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
    return materialRepository.findAllByIsJobOrderTrueAndIsVisibleTrueOrderByNameAsc();
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

    return materialRepository.save(material);
  }

  /**
   * Updates the admin-maintained fields of a material; UEX-imported fields are not changed. Unknown
   * refined-material or category ids resolve to {@code null}.
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
    Material material = getMaterial(id);

    OptimisticLock.checkOptionalClient(
        material.getVersion(), materialDetails.getVersion(), Material.class, id);

    material.setName(materialDetails.getName());
    material.setType(materialDetails.getType());
    material.setDescription(materialDetails.getDescription());
    material.setQuantityType(materialDetails.getQuantityType());
    material.setIsManualRawMaterial(materialDetails.getIsManualRawMaterial());
    material.setIsJobOrder(materialDetails.getIsJobOrder());
    if (materialDetails.getIsVisible() != null) {
      material.setIsVisible(materialDetails.getIsVisible());
    }

    if (materialDetails.getRefinedMaterial() != null
        && materialDetails.getRefinedMaterial().getId() != null) {
      Material refined =
          materialRepository.findById(materialDetails.getRefinedMaterial().getId()).orElse(null);
      material.setRefinedMaterial(refined);
    } else {
      material.setRefinedMaterial(null);
    }

    if (materialDetails.getCategory() != null && materialDetails.getCategory().getId() != null) {
      MaterialCategory category =
          materialCategoryRepository.findById(materialDetails.getCategory().getId()).orElse(null);
      material.setCategory(category);
    } else {
      material.setCategory(null);
    }

    return materialRepository.save(material);
  }

  /**
   * Deletes a material. Backend rejects the delete when any inventory item or price row still
   * references it (FK constraint surfaces as 409 via the global handler).
   *
   * @param id material primary key
   */
  @Transactional
  @EvictAllMaterialCaches
  public void deleteMaterial(@NotNull UUID id) {
    Material material = getMaterial(id);
    materialRepository.delete(material);
  }
}
