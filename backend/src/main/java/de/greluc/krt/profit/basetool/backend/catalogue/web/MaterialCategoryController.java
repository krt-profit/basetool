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

package de.greluc.krt.profit.basetool.backend.catalogue.web;

import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialCategoryDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.MaterialCategoryService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only REST surface of the commodity categories; the P4K import maintains them (REQ-P4K-025),
 * so no endpoint creates, renames or deletes one.
 */
@RestController
@RequestMapping("/api/v1/material-categories")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MaterialCategoryController {

  private final MaterialCategoryService service;

  /**
   * Returns the live categories sorted alphabetically.
   *
   * @return the categories a picker offers
   */
  @GetMapping
  public List<MaterialCategoryDto> getAll() {
    return service.findAll();
  }

  /**
   * Returns the category DTO.
   *
   * @param id category id
   * @return the category DTO
   */
  @GetMapping("/{id}")
  public MaterialCategoryDto getById(@PathVariable UUID id) {
    return service.findById(id);
  }
}
