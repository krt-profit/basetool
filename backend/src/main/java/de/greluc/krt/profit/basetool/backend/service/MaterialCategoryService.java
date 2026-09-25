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
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.MaterialCategory;
import de.greluc.krt.profit.basetool.backend.repository.MaterialCategoryRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cached CRUD service for the admin-defined {@code material_category} reference table.
 *
 * <p>Lists are sorted alphabetically; every mutator evicts {@link
 * CacheConfig#MATERIAL_CATEGORIES_CACHE}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MaterialCategoryService {

  private final MaterialCategoryRepository repository;

  /**
   * Returns all categories sorted by name ascending. Cached as a single entry keyed by the empty
   * argument list because the sort is fixed.
   *
   * @return all categories sorted by name ascending
   */
  @Cacheable(cacheNames = CacheConfig.MATERIAL_CATEGORIES_CACHE)
  public List<MaterialCategory> findAll() {
    return repository.findAll(Sort.by("name").ascending());
  }

  /**
   * Returns the category.
   *
   * @param id category primary key
   * @return the category
   * @throws NotFoundException when no category matches
   */
  @Cacheable(cacheNames = CacheConfig.MATERIAL_CATEGORIES_CACHE)
  public MaterialCategory findById(UUID id) {
    return Entities.require(repository.findById(id), "MaterialCategory not found");
  }

  /**
   * Persists a new category.
   *
   * @param category transient entity
   * @return the persisted category
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.MATERIAL_CATEGORIES_CACHE, allEntries = true)
  public MaterialCategory create(MaterialCategory category) {
    return repository.save(category);
  }

  /**
   * Updates the name of an existing category. Only {@code name} is mutable; description and
   * structural fields stay untouched.
   *
   * @param id category primary key
   * @param category transient entity carrying the new name
   * @return the persisted category
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.MATERIAL_CATEGORIES_CACHE, allEntries = true)
  public MaterialCategory update(UUID id, MaterialCategory category) {
    MaterialCategory existing = findById(id);
    existing.setName(category.getName());
    return repository.save(existing);
  }

  /**
   * Deletes a category. The backend rejects the delete with {@link
   * de.greluc.krt.profit.basetool.backend.exception.EntityInUseException} when any material still
   * references the category.
   *
   * @param id category primary key
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.MATERIAL_CATEGORIES_CACHE, allEntries = true)
  public void delete(UUID id) {
    MaterialCategory existing = findById(id);
    repository.delete(existing);
  }
}
