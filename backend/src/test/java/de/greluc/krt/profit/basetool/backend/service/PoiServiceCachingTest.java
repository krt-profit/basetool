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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import de.greluc.krt.profit.basetool.backend.config.CacheConfig;
import de.greluc.krt.profit.basetool.backend.model.Poi;
import de.greluc.krt.profit.basetool.backend.repository.PoiRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring-Boot integration tests for the {@code @Cacheable} / {@code @CacheEvict} annotations on
 * {@link PoiService}. Loads a real Caffeine-backed {@link CacheManager} and inspects the cache
 * directly, covering both that the read path populates the POI cache and that each loading-dock
 * override mutator clears it.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PoiServiceCachingTest {

  @Autowired private PoiService poiService;
  @Autowired private PoiRepository poiRepository;
  @Autowired private CacheManager cacheManager;

  private Poi alpha;
  private Poi beta;

  @BeforeEach
  void seedAndClearCache() {
    poiRepository.deleteAll();

    alpha = new Poi();
    alpha.setName("Alpha POI");
    poiRepository.save(alpha);

    beta = new Poi();
    beta.setName("Beta POI");
    poiRepository.save(beta);

    cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
  }

  private Cache cache() {
    Cache cache = cacheManager.getCache(CacheConfig.POIS_CACHE);
    assertNotNull(cache, "pois cache must be registered by CacheConfig");
    return cache;
  }

  @Test
  void getPoi_populatesCacheKeyedByIdOnFirstCall() {
    poiService.getPoi(alpha.getId());

    Cache.ValueWrapper entry = cache().get(alpha.getId());
    assertNotNull(entry, "getPoi(id) must populate the pois cache under the id key");
    assertSame(
        poiService.getPoi(alpha.getId()),
        entry.get(),
        "second getPoi(id) must return the cached instance");
  }

  @Test
  void getAllPois_populatesCacheKeyedByPageable() {
    Pageable pageable = PageRequest.of(0, 10, Sort.by("name"));

    poiService.getAllPois(pageable);

    assertNotNull(
        cache().get(pageable), "getAllPois(pageable) must populate the cache under its key");
  }

  @Test
  void setLoadingDockOverride_evictsAllEntries() {
    primeCache();

    poiService.setLoadingDockOverride(alpha.getId(), true);

    assertCleared();
  }

  @Test
  void clearLoadingDockOverride_evictsAllEntries() {
    alpha.setHasLoadingDockOverridden(true);
    poiRepository.save(alpha);
    primeCache();

    poiService.clearLoadingDockOverride(alpha.getId());

    assertCleared();
  }

  @Test
  void cacheMissesOnUnknownIdDoNotPoisonTheCacheWithNullEntries() {
    UUID unknownId = UUID.randomUUID();

    try {
      poiService.getPoi(unknownId);
    } catch (Exception ignored) {
      // expected — the service throws NotFoundException for the unknown id
    }

    assertNull(
        cache().get(unknownId),
        "an exception on the @Cacheable path must NOT store a tombstone that shadows later reads");
  }

  /**
   * Populates a page entry plus both by-id entries so an eviction assertion has something to clear.
   */
  private void primeCache() {
    Pageable pageable = PageRequest.of(0, 10, Sort.by("name"));
    poiService.getAllPois(pageable);
    poiService.getPoi(alpha.getId());
    poiService.getPoi(beta.getId());
    assertNotNull(cache().get(pageable));
    assertNotNull(cache().get(alpha.getId()));
    assertNotNull(cache().get(beta.getId()));
  }

  /** Asserts the whole POI cache was cleared (page entry and both by-id entries gone). */
  private void assertCleared() {
    Pageable pageable = PageRequest.of(0, 10, Sort.by("name"));
    assertNull(cache().get(pageable), "page entry must be evicted after the mutation");
    assertNull(cache().get(alpha.getId()), "alpha entry must be evicted after the mutation");
    assertNull(cache().get(beta.getId()), "beta entry must be evicted alongside alpha");
  }
}
