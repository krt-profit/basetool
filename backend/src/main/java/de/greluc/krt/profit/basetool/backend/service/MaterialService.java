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
import de.greluc.krt.profit.basetool.backend.repository.MaterialCategoryRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialPriceRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.support.LikePatterns;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read service for materials plus the admin-mutable subset of fields.
 *
 * <p>The catalog itself comes from {@link UexCommodityService}; this service owns the
 * project-specific fields that admins maintain by hand: category, refined-material mapping, the
 * manual {@code isJobOrder} and {@code isManualRawMaterial} flags (overrides when UEX gets the
 * refinable/refined classification wrong). Cache eviction is {@code allEntries=true} because the
 * materials catalog drives many downstream views — surgical eviction is not worth the complexity.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MaterialService {

  private final MaterialRepository materialRepository;
  private final MaterialPriceRepository materialPriceRepository;
  private final MaterialCategoryRepository materialCategoryRepository;

  /**
   * Returns cached paged list of all materials. Distinct cache key prefix ({@code all-}) so this
   * admin/full view never collides with {@link #getVisibleMaterials(Pageable)} — both share {@code
   * MATERIALS_CACHE} and the default {@code SimpleKeyGenerator} keys solely on the {@code Pageable}
   * argument, which would otherwise serve one method's result for the other.
   *
   * @param pageable page request
   * @return cached paged list of all materials
   */
  @Cacheable(cacheNames = CacheConfig.MATERIALS_CACHE, key = "'all-' + #pageable")
  public Page<Material> getAllMaterials(@NotNull Pageable pageable) {
    return materialRepository.findAll(pageable);
  }

  /**
   * Returns the cached paged list of <b>visible</b> materials only ({@code is_visible = true}).
   * Drives the public/trading catalog list: wiki-only commodities imported invisible (§4.3) are
   * excluded so they don't pollute trading flows until an admin reviews them. The admin catalog
   * passes {@code includeHidden=true} and goes through {@link #getAllMaterials(Pageable)} instead.
   * The {@code visible-} key prefix keeps it from colliding with {@code getAllMaterials} in the
   * shared cache.
   *
   * @param pageable page request
   * @return cached paged list of visible materials
   */
  @Cacheable(cacheNames = CacheConfig.MATERIALS_CACHE, key = "'visible-' + #pageable")
  public Page<Material> getVisibleMaterials(@NotNull Pageable pageable) {
    return materialRepository.findByIsVisibleTrue(pageable);
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
   * Projection used by the materials-overview page: per-material best buy / best sell summary
   * (filtered by name).
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
   * Lightweight projection used by typeaheads — only id and name.
   *
   * @return all materials as reference DTOs
   */
  public List<de.greluc.krt.profit.basetool.backend.model.dto.MaterialReferenceDto>
      findAllReference() {
    return materialRepository.findAllReference();
  }

  /**
   * Returns the material. Cached in the dedicated {@link CacheConfig#MATERIAL_BY_ID_CACHE} (not the
   * list catalogue) so a by-id lookup and a paged-list read cannot evict each other under a shared
   * size budget. The returned entity is cache-shared — callers must treat it read-only and map it
   * to a DTO before returning; the write paths re-load a managed instance via Spring
   * self-invocation (which bypasses this cache proxy) so they never re-save this cached copy
   * (L4/CACHE-03).
   *
   * @param id material primary key
   * @return the material
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when no match
   */
  @Cacheable(cacheNames = CacheConfig.MATERIAL_BY_ID_CACHE)
  public Material getMaterial(@NotNull UUID id) {
    return materialRepository
        .findById(id)
        .orElseThrow(
            () ->
                new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                    "Material not found"));
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
  public List<de.greluc.krt.profit.basetool.backend.model.dto.MaterialSellingTerminalDto>
      getMaterialTerminals(@NotNull UUID id) {
    return materialPriceRepository.findSellingTerminalsByMaterialId(id);
  }

  /**
   * Returns the full material × terminal matrix used by the matrix overview page. The frontend
   * pulls everything in one query and filters in memory.
   *
   * @param pageable page request
   * @return paged matrix items
   */
  public Page<MaterialMatrixItemDto> getAllMatrixItems(@NotNull Pageable pageable) {
    return materialPriceRepository.findAllMatrixItems(pageable);
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
   * Persists a manually-entered material from the admin UI. Used when UEX has not (yet) published a
   * commodity that the squadron needs — e.g. a refinery raw input that exists in-game but is
   * missing from {@code get_commodities_prices_all/}. The server unconditionally stamps {@code
   * sourceSystems=MANUAL} on the persisted row (Audit + UI badge, surfaced via the derived {@code
   * isManualEntry} wire field); the next UEX sync flips it off {@code MANUAL} automatically once
   * UEX picks the commodity up (see {@code UexCommodityService}).
   *
   * <p>UEX-imported columns ({@code idCommodity}, {@code code}, {@code slug}, {@code priceBuy} …)
   * are left {@code null} — they get populated by the sync's name-match fallback once UEX exposes
   * the commodity. {@code refinedMaterialId} is only honoured when the entity is classified as a
   * raw material (either {@code type=RAW} or {@code isManualRawMaterial=true}); otherwise the
   * request is rejected so a non-raw material cannot accidentally point at a refined output.
   *
   * @param dto validated create payload
   * @return the persisted material
   * @throws BadRequestException when {@code type}/{@code quantityType} cannot be parsed, or when
   *     {@code refinedMaterialId} is set on a non-raw material
   * @throws NotFoundException when {@code refinedMaterialId} or {@code categoryId} reference a row
   *     that does not exist
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
          materialRepository
              .findById(dto.refinedMaterialId())
              .orElseThrow(() -> new NotFoundException("Refined material not found"));
      material.setRefinedMaterial(refined);
    }

    if (dto.categoryId() != null) {
      MaterialCategory category =
          materialCategoryRepository
              .findById(dto.categoryId())
              .orElseThrow(() -> new NotFoundException("Material category not found"));
      material.setCategory(category);
    }

    return materialRepository.save(material);
  }

  /**
   * Updates the admin-maintained fields on a material (name, type, description, quantity type,
   * manual flags, visibility, refined-material link, category). UEX-imported fields are NOT mutable
   * here — those come from {@link UexCommodityService} and any manual override would be silently
   * overwritten on the next sync.
   *
   * <p>Refined-material and category references are resolved by id; unknown ids fall back to {@code
   * null} rather than raising (legacy data may carry stale references).
   *
   * @param id material primary key
   * @param materialDetails transient entity carrying the new values (also the expected version)
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
    // Visibility is admin-toggleable (§4.3 review of wiki-only commodities). Null-guarded so a DTO
    // that omits the field cannot null the NOT NULL column on an unrelated edit.
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
