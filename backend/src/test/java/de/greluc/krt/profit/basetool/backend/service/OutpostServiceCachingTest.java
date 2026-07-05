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
import de.greluc.krt.profit.basetool.backend.model.Outpost;
import de.greluc.krt.profit.basetool.backend.repository.OutpostRepository;
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
 * {@link OutpostService}. Loads a real Caffeine-backed {@link CacheManager} and inspects the cache
 * directly, covering both that the read path populates the outpost cache and that each loading-dock
 * override mutator clears it.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OutpostServiceCachingTest {

  @Autowired private OutpostService outpostService;
  @Autowired private OutpostRepository outpostRepository;
  @Autowired private CacheManager cacheManager;

  private Outpost alpha;
  private Outpost beta;

  @BeforeEach
  void seedAndClearCache() {
    outpostRepository.deleteAll();

    alpha = new Outpost();
    alpha.setName("Alpha Outpost");
    outpostRepository.save(alpha);

    beta = new Outpost();
    beta.setName("Beta Outpost");
    outpostRepository.save(beta);

    cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
  }

  private Cache cache() {
    Cache cache = cacheManager.getCache(CacheConfig.OUTPOSTS_CACHE);
    assertNotNull(cache, "outposts cache must be registered by CacheConfig");
    return cache;
  }

  @Test
  void getOutpost_populatesCacheKeyedByIdOnFirstCall() {
    outpostService.getOutpost(alpha.getId());

    Cache.ValueWrapper entry = cache().get(alpha.getId());
    assertNotNull(entry, "getOutpost(id) must populate the outposts cache under the id key");
    assertSame(
        outpostService.getOutpost(alpha.getId()),
        entry.get(),
        "second getOutpost(id) must return the cached instance");
  }

  @Test
  void getAllOutposts_populatesCacheKeyedByPageable() {
    Pageable pageable = PageRequest.of(0, 10, Sort.by("name"));

    outpostService.getAllOutposts(pageable);

    assertNotNull(
        cache().get(pageable), "getAllOutposts(pageable) must populate the cache under its key");
  }

  @Test
  void setLoadingDockOverride_evictsAllEntries() {
    primeCache();

    outpostService.setLoadingDockOverride(alpha.getId(), true);

    assertCleared();
  }

  @Test
  void clearLoadingDockOverride_evictsAllEntries() {
    alpha.setHasLoadingDockOverridden(true);
    outpostRepository.save(alpha);
    primeCache();

    outpostService.clearLoadingDockOverride(alpha.getId());

    assertCleared();
  }

  @Test
  void cacheMissesOnUnknownIdDoNotPoisonTheCacheWithNullEntries() {
    UUID unknownId = UUID.randomUUID();

    try {
      outpostService.getOutpost(unknownId);
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
    outpostService.getAllOutposts(pageable);
    outpostService.getOutpost(alpha.getId());
    outpostService.getOutpost(beta.getId());
    assertNotNull(cache().get(pageable));
    assertNotNull(cache().get(alpha.getId()));
    assertNotNull(cache().get(beta.getId()));
  }

  /** Asserts the whole outpost cache was cleared (page entry and both by-id entries gone). */
  private void assertCleared() {
    Pageable pageable = PageRequest.of(0, 10, Sort.by("name"));
    assertNull(cache().get(pageable), "page entry must be evicted after the mutation");
    assertNull(cache().get(alpha.getId()), "alpha entry must be evicted after the mutation");
    assertNull(cache().get(beta.getId()), "beta entry must be evicted alongside alpha");
  }
}
