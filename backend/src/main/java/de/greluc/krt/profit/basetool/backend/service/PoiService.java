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
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.Poi;
import de.greluc.krt.profit.basetool.backend.repository.PoiRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read service plus admin-override mutators for the POI catalogue. The records themselves are owned
 * by {@link UexUniverseSyncService}; this service only exposes the read API and the admin-only
 * {@code hasLoadingDock} pin used by the UEX-overrides admin page. Read methods are cached against
 * {@link CacheConfig#POIS_CACHE}; the override mutators evict the whole cache, and the periodic
 * {@link UexUniverseSyncService} sweep evicts it on completion (via {@code
 * MasterDataCacheEvictionService}, CACHE-SYNC-EVICT-001), so background-sync writes are visible on
 * the next read; the 12-hour master-data TTL is only the backstop.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PoiService {

  private final PoiRepository poiRepository;

  /**
   * Returns the paged POI catalogue.
   *
   * @param pageable page request
   * @return one page of POIs, sorted by the pageable's sort
   */
  @Cacheable(cacheNames = CacheConfig.POIS_CACHE)
  public Page<Poi> getAllPois(Pageable pageable) {
    return poiRepository.findAll(pageable);
  }

  /**
   * Returns a single POI by primary key.
   *
   * @param id POI primary key
   * @return the managed POI entity
   * @throws NotFoundException when no POI matches the id
   */
  @Cacheable(cacheNames = CacheConfig.POIS_CACHE)
  public Poi getPoi(UUID id) {
    return poiRepository.findById(id).orElseThrow(() -> new NotFoundException("POI not found"));
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
    Poi poi = getPoi(id);
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
    Poi poi = getPoi(id);
    poi.setHasLoadingDockOverridden(false);
    return poiRepository.save(poi);
  }
}
