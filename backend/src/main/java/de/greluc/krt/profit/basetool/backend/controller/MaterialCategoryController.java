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

import de.greluc.krt.profit.basetool.backend.mapper.MaterialCategoryMapper;
import de.greluc.krt.profit.basetool.backend.model.MaterialCategory;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialCategoryDto;
import de.greluc.krt.profit.basetool.backend.service.MaterialCategoryService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the material-category reference table. Read is public; mutations are
 * ADMIN/OFFICER only.
 *
 * <p>REQ-SEC-052: the class-level {@code @PreAuthorize("isAuthenticated()")} is the floor, not the
 * ceiling — it is stated here so an endpoint added later inherits it rather than relying on a URL
 * matcher elsewhere being right, and a method-level gate still wins where one is present.
 */
@RestController
@RequestMapping("/api/v1/material-categories")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MaterialCategoryController {

  private final MaterialCategoryService service;
  private final MaterialCategoryMapper mapper;

  /**
   * Returns all categories sorted alphabetically.
   *
   * @return all categories sorted alphabetically
   */
  @GetMapping
  public List<MaterialCategoryDto> getAll() {
    return service.findAll().stream().map(mapper::toDto).toList();
  }

  /**
   * Returns the category DTO.
   *
   * @param id category id
   * @return the category DTO
   */
  @GetMapping("/{id}")
  public MaterialCategoryDto getById(@PathVariable UUID id) {
    return mapper.toDto(service.findById(id));
  }

  /**
   * Creates a new category.
   *
   * @param dto create payload
   * @return the persisted DTO
   */
  @PostMapping
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public MaterialCategoryDto create(@RequestBody MaterialCategoryDto dto) {
    MaterialCategory category = mapper.toEntity(dto);
    // L-7: strip client-supplied id/version so create cannot become a merge()-UPSERT of another
    // row.
    category.setId(null);
    category.setVersion(null);
    MaterialCategory saved = service.create(category);
    return mapper.toDto(saved);
  }

  /**
   * Updates an existing category.
   *
   * @param id category id
   * @param dto update payload
   * @return the persisted DTO
   */
  @PutMapping("/{id}")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public MaterialCategoryDto update(@PathVariable UUID id, @RequestBody MaterialCategoryDto dto) {
    MaterialCategory category = mapper.toEntity(dto);
    MaterialCategory updated = service.update(id, category);
    return mapper.toDto(updated);
  }

  /**
   * Deletes a category. Rejected with 409 when any material still references it.
   *
   * @param id category id
   */
  @DeleteMapping("/{id}")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public void delete(@PathVariable UUID id) {
    service.delete(id);
  }
}
