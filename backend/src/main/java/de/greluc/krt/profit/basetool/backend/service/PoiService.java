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

import de.greluc.krt.profit.basetool.backend.catalogue.api.PoiDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.Poi;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.PoiRepository;
import de.greluc.krt.profit.basetool.backend.config.CacheConfig;
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.PoiMapper;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cached read service for the POI catalogue plus the admin {@code hasLoadingDock} pin. The records
 * are written by {@link UexUniverseSyncService}; mutators here evict {@link
 * CacheConfig#POIS_CACHE}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PoiService {

  private final PoiRepository poiRepository;
  private final PoiMapper poiMapper;

  /**
   * Returns the paged POI catalogue.
   *
   * @param pageable page request
   * @return one page of POIs, sorted by the pageable's sort
   */
  @Cacheable(cacheNames = CacheConfig.POIS_CACHE)
  public Page<PoiDto> getAllPois(Pageable pageable) {
    return poiRepository.findAll(pageable).map(poiMapper::toDto);
  }

  /**
   * Returns a single POI by primary key.
   *
   * @param id POI primary key
   * @return the POI read model
   * @throws NotFoundException when no POI matches the id
   */
  @Cacheable(cacheNames = CacheConfig.POIS_CACHE)
  public PoiDto getPoi(UUID id) {
    return poiMapper.toDto(requirePoi(id));
  }

  /**
   * Loads the managed POI entity a mutator edits; it never comes from the cache.
   *
   * @param id POI primary key
   * @return the managed entity
   */
  private Poi requirePoi(UUID id) {
    return Entities.require(poiRepository.findById(id), "POI not found");
  }

  /**
   * Pins {@code hasLoadingDock} to the supplied value and marks the row as admin-overridden so the
   * next UEX sweep leaves the value column untouched.
   *
   * @param id POI primary key
   * @param value desired {@code hasLoadingDock} value
   * @return the persisted POI
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.POIS_CACHE, allEntries = true)
  public Poi setLoadingDockOverride(UUID id, boolean value) {
    Poi poi = requirePoi(id);
    poi.setHasLoadingDock(value);
    poi.setHasLoadingDockOverridden(true);
    return poiRepository.save(poi);
  }

  /**
   * Releases the admin pin on {@code hasLoadingDock}. The value column stays at its last value
   * until the next UEX sweep overwrites it from the upstream feed.
   *
   * @param id POI primary key
   * @return the persisted POI
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.POIS_CACHE, allEntries = true)
  public Poi clearLoadingDockOverride(UUID id) {
    Poi poi = requirePoi(id);
    poi.setHasLoadingDockOverridden(false);
    return poiRepository.save(poi);
  }
}
