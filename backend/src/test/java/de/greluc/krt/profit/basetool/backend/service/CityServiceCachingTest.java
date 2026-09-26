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
import de.greluc.krt.profit.basetool.backend.model.City;
import de.greluc.krt.profit.basetool.backend.repository.CityRepository;
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
 * {@link CityService}. The tests load a real {@link CacheManager} (Caffeine-backed in the test
 * profile) and inspect the cache contents directly, so the assertions cover both that the cache is
 * actually populated on the read path and that every mutator clears it.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CityServiceCachingTest {

  @Autowired private CityService cityService;
  @Autowired private CityRepository cityRepository;
  @Autowired private CacheManager cacheManager;

  private City alpha;
  private City beta;

  @BeforeEach
  void seedAndClearCache() {
    cityRepository.deleteAll();

    alpha = new City();
    alpha.setName("Alpha City");
    cityRepository.save(alpha);

    beta = new City();
    beta.setName("Beta City");
    cityRepository.save(beta);

    cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
  }

  private Cache cache() {
    Cache cache = cacheManager.getCache(CacheConfig.CITIES_CACHE);
    assertNotNull(cache, "cities cache must be registered by CacheConfig");
    return cache;
  }

  @Test
  void getCity_populatesCacheKeyedByIdOnFirstCall() {
    cityService.getCity(alpha.getId());

    Cache.ValueWrapper entry = cache().get(alpha.getId());
    assertNotNull(entry, "getCity(id) must populate the cities cache under the id key");
    assertSame(
        cityService.getCity(alpha.getId()),
        entry.get(),
        "second getCity(id) must return the cached instance");
  }

  @Test
  void getCity_returnsSeparateCacheEntriesForDifferentIds() {
    cityService.getCity(alpha.getId());
    cityService.getCity(beta.getId());

    assertNotNull(cache().get(alpha.getId()), "alpha must be cached under its own id");
    assertNotNull(cache().get(beta.getId()), "beta must be cached under its own id");
  }

  @Test
  void getAllCities_populatesCacheKeyedByPageable() {
    Pageable pageable = PageRequest.of(0, 10, Sort.by("name"));

    cityService.getAllCities(pageable);

    Cache.ValueWrapper entry = cache().get(pageable);
    assertNotNull(entry, "getAllCities(pageable) must populate the cache under the pageable key");
  }

  @Test
  void getAllCities_cachesDifferentPageablesSeparately() {
    Pageable firstPage = PageRequest.of(0, 10, Sort.by("name"));
    Pageable secondPage = PageRequest.of(1, 10, Sort.by("name"));

    cityService.getAllCities(firstPage);
    cityService.getAllCities(secondPage);

    assertNotNull(cache().get(firstPage), "first page must be cached under its own key");
    assertNotNull(cache().get(secondPage), "second page must be cached under its own key");
  }

  @Test
  void setLoadingDockOverride_evictsAllEntries() {
    Pageable pageable = PageRequest.of(0, 10, Sort.by("name"));
    cityService.getAllCities(pageable);
    cityService.getCity(alpha.getId());
    cityService.getCity(beta.getId());
    assertNotNull(cache().get(pageable));
    assertNotNull(cache().get(alpha.getId()));
    assertNotNull(cache().get(beta.getId()));

    cityService.setLoadingDockOverride(alpha.getId(), true);

    assertNull(cache().get(pageable), "page entry must be evicted after override");
    assertNull(cache().get(alpha.getId()), "alpha entry must be evicted after override");
    assertNull(cache().get(beta.getId()), "beta entry must be evicted alongside alpha");
  }

  @Test
  void clearLoadingDockOverride_evictsAllEntries() {
    alpha.setHasLoadingDockOverridden(true);
    cityRepository.save(alpha);
    Pageable pageable = PageRequest.of(0, 10, Sort.by("name"));
    cityService.getAllCities(pageable);
    cityService.getCity(alpha.getId());
    assertNotNull(cache().get(pageable));
    assertNotNull(cache().get(alpha.getId()));

    cityService.clearLoadingDockOverride(alpha.getId());

    assertNull(cache().get(pageable), "page entry must be evicted after clearing the override");
    assertNull(
        cache().get(alpha.getId()), "alpha entry must be evicted after clearing the override");
  }

  @Test
  void cacheRepopulatesAfterEviction() {
    cityService.getCity(alpha.getId());
    cityService.setLoadingDockOverride(alpha.getId(), true);
    assertNull(cache().get(alpha.getId()));

    cityService.getCity(alpha.getId());

    assertNotNull(
        cache().get(alpha.getId()),
        "cache must repopulate on the next read after an eviction so the next mutator cycle "
            + "exercises the eviction path again");
  }

  @Test
  void cacheMissesOnUnknownIdDoNotPoisonTheCacheWithNullEntries() {
    UUID unknownId = UUID.randomUUID();

    try {
      cityService.getCity(unknownId);
    } catch (Exception ignored) {
    }

    assertNull(
        cache().get(unknownId),
        "an exception on the @Cacheable path must NOT store a tombstone in the cache, so the "
            + "next call retries the repository instead of permanently shadowing the miss");
  }
}
