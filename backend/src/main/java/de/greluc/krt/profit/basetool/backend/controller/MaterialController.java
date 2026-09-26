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

package de.greluc.krt.profit.basetool.backend.controller;

import de.greluc.krt.profit.basetool.backend.mapper.MaterialMapper;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialCreateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialMatrixItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialPriceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialPriceOverviewDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialSellingTerminalDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.service.MaterialService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import de.greluc.krt.profit.basetool.backend.web.PaginationUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for materials: catalog, price lists, price overview, material × terminal matrix,
 * job-order sub-catalog and reference projection. Mutations are ADMIN/OFFICER and touch only
 * admin-maintained fields.
 *
 * <p>The class-level {@code isAuthenticated()} gate is the floor for every endpoint; a method-level
 * gate takes precedence (REQ-SEC-052).
 */
@RestController
@RequestMapping("/api/v1/materials")
@RequiredArgsConstructor
@Transactional
@PreAuthorize("isAuthenticated()")
public class MaterialController {

  private final MaterialService materialService;
  private final MaterialMapper materialMapper;

  /**
   * Pages the material list, visible materials only unless {@code includeHidden} is set.
   *
   * <p>{@code hasTerminals=true} returns the price-eager projection, limited to materials with a
   * price row.
   *
   * @param hasTerminals when true, return the price-eager projection
   * @param includeHidden when true (admin), also return materials with {@code is_visible = false}
   * @return paged material DTOs
   */
  @GetMapping
  public PageResponse<MaterialDto> getAllMaterials(
      @RequestParam(required = false, defaultValue = "false") Boolean hasTerminals,
      @RequestParam(required = false, defaultValue = "false") Boolean includeHidden,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(page, size, sort, Set.of("name", "type", "id"), "name");
    Page<Material> p;
    if (Boolean.TRUE.equals(hasTerminals)) {
      p = materialService.getAllMaterialsWithPrices(pageable);
    } else if (Boolean.TRUE.equals(includeHidden)) {
      p = materialService.getAllMaterials(pageable);
    } else {
      p = materialService.getVisibleMaterials(pageable);
    }
    return PageResponse.of(p.map(materialMapper::toDto));
  }

  /**
   * Returns only the materials with {@code isJobOrder=true} (sorted alphabetically). Drives the
   * job-order create form's material picker.
   *
   * @return job-order materials
   */
  @Operation(
      summary = "Get all job-order materials",
      description = "Returns all materials marked as isJobOrder=true, sorted by name.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "List of job-order materials returned successfully")
  })
  @GetMapping("/job-order")
  public List<MaterialDto> getJobOrderMaterials() {
    return materialService.getAllJobOrderMaterials().stream().map(materialMapper::toDto).toList();
  }

  /**
   * Returns every visible material as a lightweight typeahead projection (id, name, quantity type),
   * unbounded; bounded pickers use {@link #searchMaterials(String, Boolean, Boolean, Integer,
   * Integer, String)}.
   *
   * @return all visible materials as reference DTOs
   */
  @GetMapping("/lookup")
  public List<MaterialReferenceDto> lookupMaterials() {
    return materialService.findAllReference();
  }

  /**
   * Pages visible materials whose name contains {@code search}, case-insensitively, for the
   * material pickers (REQ-FE-016), optionally narrowed to job-order materials or refinery inputs.
   *
   * @param search optional name fragment; {@code null}/blank returns the unfiltered first page
   * @param jobOrderOnly when true, only {@code isJobOrder = true} materials
   * @param rawOnly when true, only refinery inputs (RAW type or manually raw-flagged)
   * @param page zero-based page index (defaulted by {@link PaginationUtil})
   * @param size page size (clamped by {@link PaginationUtil})
   * @param sort whitelisted sort ({@code name} or {@code id}), default name ascending
   * @return one page of matching visible material DTOs
   */
  @GetMapping("/search")
  public PageResponse<MaterialDto> searchMaterials(
      @RequestParam(required = false) String search,
      @RequestParam(required = false, defaultValue = "false") Boolean jobOrderOnly,
      @RequestParam(required = false, defaultValue = "false") Boolean rawOnly,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(page, size, sort, Set.of("name", "id"), "name");
    return PageResponse.of(
        materialService
            .searchPicker(
                search, Boolean.TRUE.equals(jobOrderOnly), Boolean.TRUE.equals(rawOnly), pageable)
            .map(materialMapper::toDto));
  }

  /**
   * Per-material best buy / best sell summary used by the materials-overview page.
   *
   * @param name optional substring filter
   * @return paged price-overview DTOs
   */
  @GetMapping("/prices-overview")
  public PageResponse<MaterialPriceOverviewDto> getMaterialPriceOverview(
      @RequestParam(required = false) String name,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page, size, sort, Set.of("name", "id", "minPriceBuy", "maxPriceSell"), "name");
    Page<MaterialPriceOverviewDto> p = materialService.getMaterialPriceOverview(name, pageable);
    return PageResponse.of(p);
  }

  /**
   * Pages the material × terminal price matrix with optional server-side filters (ADR-0105). Absent
   * parameters apply no filter on that dimension.
   *
   * @param page zero-based page index (optional)
   * @param size page size (optional; clamped by {@link PaginationUtil})
   * @param sort sort expression (optional; whitelisted fields only)
   * @param materialNames exact material names to keep (repeatable), or absent for all
   * @param starSystems exact star-system names to keep (repeatable), or absent for all
   * @param hasLoadingDock {@code true} to keep only terminals with a loading dock
   * @param isAutoLoad {@code true} to keep only terminals with automatic cargo loading
   * @return paged (filtered) matrix items
   */
  @GetMapping("/matrix")
  public PageResponse<MaterialMatrixItemDto> getMaterialMatrixItems(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) List<String> materialNames,
      @RequestParam(required = false) List<String> starSystems,
      @RequestParam(required = false) Boolean hasLoadingDock,
      @RequestParam(required = false) Boolean isAutoLoad) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page, size, sort, Set.of("material.name", "terminal.name", "id"), "material.name");
    Page<MaterialMatrixItemDto> p =
        materialService.getMatrixItems(
            materialNames, starSystems, hasLoadingDock, isAutoLoad, pageable);
    return PageResponse.of(p);
  }

  /**
   * Returns the material DTO.
   *
   * @param id material id
   * @return the material DTO
   */
  @GetMapping("/{id}")
  public MaterialDto getMaterial(@PathVariable @NotNull UUID id) {
    return materialMapper.toDto(materialService.getMaterial(id));
  }

  /**
   * Per-material price list across terminals.
   *
   * @param id material id
   * @return paged price DTOs
   */
  @GetMapping("/{id}/prices")
  public PageResponse<MaterialPriceDto> getMaterialPrices(
      @PathVariable @NotNull UUID id,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page,
            size,
            sort,
            Set.of("terminal.name", "priceBuy", "priceSell", "id"),
            "terminal.name");
    Page<MaterialPriceDto> p = materialService.getMaterialPrices(id, pageable);
    return PageResponse.of(p);
  }

  /**
   * Terminals that trade this material, with their sell-side prices. Used by the inventory page to
   * suggest sale destinations.
   *
   * @param id material id
   * @return selling-terminal DTOs
   */
  @GetMapping("/{id}/terminals")
  public List<MaterialSellingTerminalDto> getMaterialTerminals(@PathVariable @NotNull UUID id) {
    return materialService.getMaterialTerminals(id);
  }

  /**
   * Creates a material manually from a {@link MaterialCreateDto}; the service resolves the
   * referenced ids and stamps {@code sourceSystems=MANUAL}.
   *
   * @param material create payload
   * @return the persisted DTO
   */
  @PostMapping
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public MaterialDto createMaterial(@RequestBody @Valid @NotNull MaterialCreateDto material) {
    return materialMapper.toDto(materialService.createMaterial(material));
  }

  /**
   * Updates the admin-mutable fields of a material; UEX-imported numeric fields stay untouched.
   *
   * @param id material id
   * @param material update payload (carries the expected version)
   * @return the persisted DTO
   */
  @PutMapping("/{id}")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public MaterialDto updateMaterial(
      @PathVariable @NotNull UUID id, @RequestBody @Valid @NotNull MaterialDto material) {
    return materialMapper.toDto(
        materialService.updateMaterial(id, materialMapper.toEntity(material)));
  }

  /**
   * Deletes a material. Rejected when any inventory item or price row references it.
   *
   * @param id material id
   */
  @DeleteMapping("/{id}")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public void deleteMaterial(@PathVariable @NotNull UUID id) {
    materialService.deleteMaterial(id);
  }
}
