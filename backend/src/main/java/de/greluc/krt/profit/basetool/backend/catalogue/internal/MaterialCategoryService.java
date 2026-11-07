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

import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialCategoryDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialCategoryMapper;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.platform.api.CacheConfig;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cached reads of the commodity categories. The P4K import owns them (REQ-P4K-025): admins no
 * longer create, rename or delete a category.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MaterialCategoryService {

  private final MaterialCategoryRepository repository;
  private final MaterialCategoryMapper mapper;

  /**
   * Returns the categories a picker offers, sorted by name: a group the P4K sweep stamped or an
   * admin hid is left out (REQ-P4K-013).
   *
   * @return the live categories sorted by name ascending
   */
  @Cacheable(cacheNames = CacheConfig.MATERIAL_CATEGORIES_CACHE)
  public List<MaterialCategoryDto> findAll() {
    return repository
        .findByP4kDeletedAtIsNullAndP4kHiddenAtIsNull(Sort.by("name").ascending())
        .stream()
        .map(mapper::toDto)
        .toList();
  }

  /**
   * Returns the category.
   *
   * @param id category primary key
   * @return the category read model
   * @throws NotFoundException when no category matches
   */
  @Cacheable(cacheNames = CacheConfig.MATERIAL_CATEGORIES_CACHE)
  public MaterialCategoryDto findById(UUID id) {
    return mapper.toDto(Entities.require(repository.findById(id), "MaterialCategory not found"));
  }
}
