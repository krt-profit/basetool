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
import de.greluc.krt.profit.basetool.backend.model.Manufacturer;
import de.greluc.krt.profit.basetool.backend.repository.ManufacturerRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cached read service and admin visibility toggle for the manufacturer catalog, which {@link
 * UexManufacturerService} owns. The cache is evicted on visibility changes and after each sync
 * sweep.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ManufacturerService {

  private final ManufacturerRepository manufacturerRepository;

  /**
   * Returns a cached, paged manufacturer list.
   *
   * @param pageable page request (whitelisted sort fields applied by the controller)
   * @param includeHidden true to include manufacturers marked hidden
   * @return cached page result
   */
  @Cacheable(cacheNames = CacheConfig.MANUFACTURERS_CACHE)
  public Page<Manufacturer> getAllManufacturers(@NotNull Pageable pageable, boolean includeHidden) {
    if (includeHidden) {
      return manufacturerRepository.findAll(pageable);
    }
    return manufacturerRepository.findByHiddenFalse(pageable);
  }

  /**
   * Looks up a single manufacturer by id.
   *
   * @param id manufacturer primary key
   * @return the manufacturer
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when no manufacturer
   *     matches
   */
  @Cacheable(cacheNames = CacheConfig.MANUFACTURERS_CACHE)
  public Manufacturer getManufacturer(@NotNull UUID id) {
    return Entities.require(manufacturerRepository.findById(id), "Manufacturer not found");
  }

  /**
   * Sets the {@code hidden} flag on a manufacturer and evicts the manufacturer cache.
   *
   * @param id manufacturer primary key
   * @param hidden new flag value
   * @return the persisted manufacturer
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.MANUFACTURERS_CACHE, allEntries = true)
  public Manufacturer updateManufacturerVisibility(@NotNull UUID id, boolean hidden) {
    Manufacturer manufacturer = getManufacturer(id);
    manufacturer.setHidden(hidden);
    return manufacturerRepository.save(manufacturer);
  }
}
