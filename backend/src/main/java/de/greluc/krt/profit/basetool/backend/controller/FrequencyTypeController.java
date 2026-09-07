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

import de.greluc.krt.profit.basetool.backend.mapper.FrequencyTypeMapper;
import de.greluc.krt.profit.basetool.backend.model.FrequencyType;
import de.greluc.krt.profit.basetool.backend.model.dto.FrequencyTypeDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.service.FrequencyTypeService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import de.greluc.krt.profit.basetool.backend.web.PaginationUtil;
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
 * REST surface for the frequency-type reference table. Supports drag-and-drop reorder via the
 * dedicated {@code /reorder} endpoint; mutations are OFFICER/ADMIN.
 *
 * <p>REQ-SEC-052: the class-level {@code @PreAuthorize("isAuthenticated()")} is the floor, not the
 * ceiling — it is stated here so an endpoint added later inherits it rather than relying on a URL
 * matcher elsewhere being right, and a method-level gate still wins where one is present.
 */
@RestController
@RequestMapping("/api/v1/frequency-types")
@RequiredArgsConstructor
@Transactional
@PreAuthorize("isAuthenticated()")
public class FrequencyTypeController {

  private final FrequencyTypeService frequencyTypeService;
  private final FrequencyTypeMapper frequencyTypeMapper;

  /**
   * Paged list with optional {@code active} filter. Default sort is {@code sortIndex} so the UI
   * dropdown reflects the admin-curated order.
   *
   * @return paged frequency-type DTOs
   */
  @GetMapping
  public PageResponse<FrequencyTypeDto> getAllFrequencyTypes(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) Boolean active) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page, size, sort, Set.of("name", "id", "active", "sortIndex"), "sortIndex");
    Page<FrequencyType> p = frequencyTypeService.getAllFrequencyTypes(active, pageable);
    return PageResponse.of(p.map(frequencyTypeMapper::toDto));
  }

  /**
   * Returns the DTO.
   *
   * @param id frequency type id
   * @return the DTO
   */
  @GetMapping("/{id}")
  public FrequencyTypeDto getFrequencyType(@PathVariable @NotNull UUID id) {
    return frequencyTypeMapper.toDto(frequencyTypeService.getFrequencyType(id));
  }

  /**
   * Creates a frequency type; the service assigns the next sort index.
   *
   * @param frequencyType create payload
   * @return the persisted DTO
   */
  @PostMapping
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public FrequencyTypeDto createFrequencyType(
      @RequestBody @NotNull FrequencyTypeDto frequencyType) {
    var toCreate = frequencyTypeMapper.toEntity(frequencyType);
    // L-7: strip client-supplied id/version so create cannot become a merge()-UPSERT of another
    // row.
    toCreate.setId(null);
    toCreate.setVersion(null);
    return frequencyTypeMapper.toDto(frequencyTypeService.createFrequencyType(toCreate));
  }

  /**
   * Updates a frequency type. Sort index is preserved — use {@link #reorderFrequencyTypes} to
   * change the order.
   *
   * @param id frequency type id
   * @param frequencyType update payload
   * @return the persisted DTO
   */
  @PutMapping("/{id}")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public FrequencyTypeDto updateFrequencyType(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull FrequencyTypeDto frequencyType) {
    return frequencyTypeMapper.toDto(
        frequencyTypeService.updateFrequencyType(id, frequencyTypeMapper.toEntity(frequencyType)));
  }

  /**
   * Soft-deletes a frequency type.
   *
   * @param id frequency type id
   */
  @DeleteMapping("/{id}")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public void deleteFrequencyType(@PathVariable @NotNull UUID id) {
    frequencyTypeService.deleteFrequencyType(id);
  }

  /**
   * Reverses a soft-delete. ADMIN-only.
   *
   * @param id frequency type id
   */
  @PostMapping("/{id}/activate")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public void activateFrequencyType(@PathVariable @NotNull UUID id) {
    frequencyTypeService.activateFrequencyType(id);
  }

  /**
   * Drag-and-drop reorder. Position of each id in the supplied list becomes the row's new sort
   * index. ADMIN-only.
   *
   * @param ids ids in the desired new order
   */
  @PostMapping("/reorder")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public void reorderFrequencyTypes(@RequestBody @NotNull List<UUID> ids) {
    frequencyTypeService.reorderFrequencyTypes(ids);
  }
}
