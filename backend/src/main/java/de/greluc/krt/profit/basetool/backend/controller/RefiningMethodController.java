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

import de.greluc.krt.profit.basetool.backend.mapper.RefiningMethodMapper;
import de.greluc.krt.profit.basetool.backend.model.RefiningMethod;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.RefiningMethodDto;
import de.greluc.krt.profit.basetool.backend.service.RefiningMethodService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import de.greluc.krt.profit.basetool.backend.web.PaginationUtil;
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
 * REST surface for the refining-method reference table. The data is owned by {@code
 * UexRefinerySyncService}; this controller adds the admin-mutable CRUD subset (name + description),
 * with read open to everyone.
 */
@RestController
@RequestMapping("/api/v1/refining-methods")
@RequiredArgsConstructor
@Transactional
public class RefiningMethodController {

  private final RefiningMethodService refiningMethodService;
  private final RefiningMethodMapper refiningMethodMapper;

  /**
   * Paged refining-method list.
   *
   * @return paged refining-method DTOs
   */
  @GetMapping
  public PageResponse<RefiningMethodDto> getAllRefiningMethods(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(page, size, sort, Set.of("name", "id"), "name");
    Page<RefiningMethod> p = refiningMethodService.getAllRefiningMethods(pageable);
    return PageResponse.of(p.map(refiningMethodMapper::toDto));
  }

  /**
   * Returns the refining method DTO.
   *
   * @param id refining method id
   * @return the refining method DTO
   */
  @GetMapping("/{id}")
  public RefiningMethodDto getRefiningMethod(@PathVariable @NotNull UUID id) {
    return refiningMethodMapper.toDto(refiningMethodService.getRefiningMethod(id));
  }

  /**
   * Creates a refining method manually (rare — UEX sync owns the catalog).
   *
   * @param refiningMethod create payload
   * @return the persisted DTO
   */
  @PostMapping
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public RefiningMethodDto createRefiningMethod(
      @RequestBody @NotNull RefiningMethodDto refiningMethod) {
    var toCreate = refiningMethodMapper.toEntity(refiningMethod);
    // L-7: strip client-supplied id/version so create cannot become a merge()-UPSERT of another
    // row.
    toCreate.setId(null);
    toCreate.setVersion(null);
    return refiningMethodMapper.toDto(refiningMethodService.createRefiningMethod(toCreate));
  }

  /**
   * Updates an admin-mutable subset (name + description). UEX-imported numeric ratings are not
   * touched.
   *
   * @param id refining method id
   * @param refiningMethod update payload
   * @return the persisted DTO
   */
  @PutMapping("/{id}")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public RefiningMethodDto updateRefiningMethod(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull RefiningMethodDto refiningMethod) {
    return refiningMethodMapper.toDto(
        refiningMethodService.updateRefiningMethod(
            id, refiningMethodMapper.toEntity(refiningMethod)));
  }

  /**
   * Deletes a refining method. Rejected when any refinery order references it.
   *
   * @param id refining method id
   */
  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public void deleteRefiningMethod(@PathVariable @NotNull UUID id) {
    refiningMethodService.deleteRefiningMethod(id);
  }
}
