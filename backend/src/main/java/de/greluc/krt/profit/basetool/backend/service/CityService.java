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
import de.greluc.krt.profit.basetool.backend.model.City;
import de.greluc.krt.profit.basetool.backend.repository.CityRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read service and admin {@code hasLoadingDock} override for the city catalogue, whose records
 * {@link UexUniverseSyncService} owns.
 *
 * <p>Reads are cached in {@link CacheConfig#CITIES_CACHE}; the override mutators and each UEX sweep
 * evict it.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CityService {

  private final CityRepository cityRepository;

  /**
   * Returns the paged city catalogue. The page is cached per {@link Pageable} (default key
   * generator) so list-page renders that hit the same sort/page combo skip the repository.
   *
   * @param pageable page request
   * @return one page of cities, sorted by the pageable's sort
   */
  @Cacheable(cacheNames = CacheConfig.CITIES_CACHE)
  public Page<City> getAllCities(Pageable pageable) {
    return cityRepository.findAll(pageable);
  }

  /**
   * Returns a single city by primary key.
   *
   * @param id city primary key
   * @return the managed city entity
   * @throws NotFoundException when no city matches the id
   */
  @Cacheable(cacheNames = CacheConfig.CITIES_CACHE)
  public City getCity(UUID id) {
    return Entities.require(cityRepository.findById(id), "City not found");
  }

  /**
   * Pins {@code hasLoadingDock} to the given value and marks the row admin-overridden, so the UEX
   * sweep leaves it untouched.
   *
   * @param id city primary key
   * @param value desired {@code hasLoadingDock} value
   * @return the persisted city
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.CITIES_CACHE, allEntries = true)
  public City setLoadingDockOverride(UUID id, boolean value) {
    City city = getCity(id);
    city.setHasLoadingDock(value);
    city.setHasLoadingDockOverridden(true);
    return cityRepository.save(city);
  }

  /**
   * Releases the admin pin on {@code hasLoadingDock}; the value stays until the next UEX sweep
   * overwrites it.
   *
   * @param id city primary key
   * @return the persisted city
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.CITIES_CACHE, allEntries = true)
  public City clearLoadingDockOverride(UUID id) {
    City city = getCity(id);
    city.setHasLoadingDockOverridden(false);
    return cityRepository.save(city);
  }
}
