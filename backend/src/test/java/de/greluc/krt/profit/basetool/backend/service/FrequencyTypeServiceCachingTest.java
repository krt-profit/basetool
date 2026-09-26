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
import de.greluc.krt.profit.basetool.backend.model.FrequencyType;
import de.greluc.krt.profit.basetool.backend.repository.FrequencyTypeRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.SimpleKey;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for the caching annotations on {@link FrequencyTypeService}: every mutator
 * evicts the cache, and the two-argument {@code (Boolean active, Pageable pageable)} read uses a
 * {@link SimpleKey} so filter combinations do not collide.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FrequencyTypeServiceCachingTest {

  @Autowired private FrequencyTypeService frequencyTypeService;
  @Autowired private FrequencyTypeRepository frequencyTypeRepository;
  @Autowired private CacheManager cacheManager;

  private FrequencyType uhf;
  private FrequencyType vhf;

  @BeforeEach
  void seedAndClearCache() {
    frequencyTypeRepository.deleteAll();

    uhf = new FrequencyType();
    uhf.setName("UHF");
    uhf.setActive(true);
    frequencyTypeRepository.save(uhf);

    vhf = new FrequencyType();
    vhf.setName("VHF");
    vhf.setActive(true);
    frequencyTypeRepository.save(vhf);

    cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
  }

  private Cache cache() {
    Cache cache = cacheManager.getCache(CacheConfig.FREQUENCY_TYPES_CACHE);
    assertNotNull(cache, "frequencyTypes cache must be registered by CacheConfig");
    return cache;
  }

  @Test
  void getFrequencyType_populatesCacheKeyedByIdAndReturnsCachedInstanceOnSecondCall() {
    FrequencyType first = frequencyTypeService.getFrequencyType(uhf.getId());

    Cache.ValueWrapper entry = cache().get(uhf.getId());
    assertNotNull(entry, "getFrequencyType(id) must populate the cache under the id key");
    assertSame(first, entry.get(), "cache entry must reference the same instance as the read");

    FrequencyType second = frequencyTypeService.getFrequencyType(uhf.getId());
    assertSame(first, second, "second read must come from the cache, not the repository");
  }

  @Test
  void getAllFrequencyTypes_usesSimpleKeyForBothArgumentsSoActiveFilterAndPageAreIsolated() {
    Pageable pageable = PageRequest.of(0, 10, Sort.by("sortIndex"));

    frequencyTypeService.getAllFrequencyTypes(null, pageable);
    frequencyTypeService.getAllFrequencyTypes(Boolean.TRUE, pageable);
    frequencyTypeService.getAllFrequencyTypes(Boolean.FALSE, pageable);

    assertNotNull(cache().get(new SimpleKey(null, pageable)), "null-filter variant must be cached");
    assertNotNull(
        cache().get(new SimpleKey(Boolean.TRUE, pageable)), "active=true variant must be cached");
    assertNotNull(
        cache().get(new SimpleKey(Boolean.FALSE, pageable)),
        "active=false variant must be cached separately from null and true");
  }

  @Test
  void getAllFrequencyTypes_cachesDifferentPagesIndependently() {
    Pageable firstPage = PageRequest.of(0, 5, Sort.by("sortIndex"));
    Pageable secondPage = PageRequest.of(1, 5, Sort.by("sortIndex"));

    frequencyTypeService.getAllFrequencyTypes(null, firstPage);
    frequencyTypeService.getAllFrequencyTypes(null, secondPage);

    assertNotNull(cache().get(new SimpleKey(null, firstPage)), "first page cached");
    assertNotNull(cache().get(new SimpleKey(null, secondPage)), "second page cached");
  }

  @Test
  void createFrequencyType_evictsAllEntries() {
    primeCacheWithEverything();

    FrequencyType hf = new FrequencyType();
    hf.setName("HF");
    frequencyTypeService.createFrequencyType(hf);

    assertCacheIsEmpty("create must evict every entry");
  }

  @Test
  void updateFrequencyType_evictsAllEntries() {
    primeCacheWithEverything();

    FrequencyType payload = new FrequencyType();
    payload.setName("UHF-2");
    payload.setDescription("renamed for the test");
    payload.setActive(true);
    frequencyTypeService.updateFrequencyType(uhf.getId(), payload);

    assertCacheIsEmpty("update must evict every entry");
  }

  @Test
  void deleteFrequencyType_softDeleteEvictsAllEntries() {
    primeCacheWithEverything();

    frequencyTypeService.deleteFrequencyType(uhf.getId());

    assertCacheIsEmpty("soft-delete must evict every entry");
  }

  @Test
  void activateFrequencyType_evictsAllEntries() {
    uhf.setActive(false);
    frequencyTypeRepository.save(uhf);
    primeCacheWithEverything();

    frequencyTypeService.activateFrequencyType(uhf.getId());

    assertCacheIsEmpty("activate must evict every entry");
  }

  @Test
  void reorderFrequencyTypes_evictsAllEntries() {
    primeCacheWithEverything();

    frequencyTypeService.reorderFrequencyTypes(List.of(vhf.getId(), uhf.getId()));

    assertCacheIsEmpty("reorder must evict every entry");
  }

  @Test
  void cacheRepopulatesAfterMutation() {
    frequencyTypeService.getFrequencyType(uhf.getId());
    FrequencyType hf = new FrequencyType();
    hf.setName("HF-Repop");
    frequencyTypeService.createFrequencyType(hf);
    assertNull(cache().get(uhf.getId()));

    frequencyTypeService.getFrequencyType(uhf.getId());

    assertNotNull(
        cache().get(uhf.getId()),
        "cache must repopulate on the next read so the next mutator exercises eviction again");
  }

  private void primeCacheWithEverything() {
    Pageable pageable = PageRequest.of(0, 10, Sort.by("sortIndex"));
    frequencyTypeService.getAllFrequencyTypes(null, pageable);
    frequencyTypeService.getAllFrequencyTypes(Boolean.TRUE, pageable);
    frequencyTypeService.getFrequencyType(uhf.getId());
    frequencyTypeService.getFrequencyType(vhf.getId());

    assertNotNull(cache().get(new SimpleKey(null, pageable)));
    assertNotNull(cache().get(new SimpleKey(Boolean.TRUE, pageable)));
    assertNotNull(cache().get(uhf.getId()));
    assertNotNull(cache().get(vhf.getId()));
  }

  private void assertCacheIsEmpty(String reason) {
    Pageable pageable = PageRequest.of(0, 10, Sort.by("sortIndex"));
    assertNull(cache().get(new SimpleKey(null, pageable)), reason + " (null-filter list)");
    assertNull(cache().get(new SimpleKey(Boolean.TRUE, pageable)), reason + " (active=true list)");
    assertNull(cache().get(uhf.getId()), reason + " (uhf entry)");
    assertNull(cache().get(vhf.getId()), reason + " (vhf entry)");
  }
}
