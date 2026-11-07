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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialCategory;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialCategoryDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.MasterDataCacheEvictionService;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.MaterialCategoryRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.MaterialCategoryService;
import de.greluc.krt.profit.basetool.backend.platform.api.CacheConfig;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.SimpleKey;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring-Boot integration tests for the {@code @Cacheable} annotations on {@link
 * MaterialCategoryService} and their eviction after a P4K apply. The {@code findAll()} method is
 * no-arg and therefore keyed by {@link SimpleKey#EMPTY}, which is verified explicitly so a refactor
 * adding parameters to {@code findAll} cannot silently shift the cache key without breaking the
 * test.
 */
@SpringBootTest
@Transactional
class MaterialCategoryServiceCachingTest {

  @Autowired private MaterialCategoryService materialCategoryService;
  @Autowired private MaterialCategoryRepository materialCategoryRepository;
  @Autowired private CacheManager cacheManager;
  @Autowired private MasterDataCacheEvictionService cacheEvictionService;

  private MaterialCategory ore;
  private MaterialCategory good;

  @BeforeEach
  void seedAndClearCache() {
    materialCategoryRepository.deleteAll();

    ore = new MaterialCategory();
    ore.setName("Refinable Ore");
    materialCategoryRepository.save(ore);

    good = new MaterialCategory();
    good.setName("Manufactured Good");
    materialCategoryRepository.save(good);

    cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
  }

  private Cache cache() {
    Cache cache = cacheManager.getCache(CacheConfig.MATERIAL_CATEGORIES_CACHE);
    assertNotNull(cache, "materialCategories cache must be registered by CacheConfig");
    return cache;
  }

  @Test
  void findAll_populatesCacheKeyedBySimpleKeyEmpty() {
    materialCategoryService.findAll();

    Cache.ValueWrapper entry = cache().get(SimpleKey.EMPTY);
    assertNotNull(
        entry,
        "findAll() takes no arguments so Spring's default key generator must use "
            + "SimpleKey.EMPTY; a refactor that adds parameters here would silently change the "
            + "cache key and break the eviction contract.");
  }

  @Test
  void findById_populatesCacheKeyedByIdAndReturnsCachedInstanceOnSecondCall() {
    MaterialCategoryDto first = materialCategoryService.findById(ore.getId());

    Cache.ValueWrapper entry = cache().get(ore.getId());
    assertNotNull(entry, "findById(id) must populate the cache under the id key");
    assertSame(first, entry.get(), "cache entry must reference the same instance as the read");

    MaterialCategoryDto second = materialCategoryService.findById(ore.getId());
    assertSame(first, second, "second read must come from the cache, not the repository");
  }

  @Test
  void findById_returnsSeparateCacheEntriesForDifferentIds() {
    materialCategoryService.findById(ore.getId());
    materialCategoryService.findById(good.getId());

    assertNotNull(cache().get(ore.getId()), "ore cached under its own id");
    assertNotNull(cache().get(good.getId()), "good cached under its own id");
  }

  @Test
  void aP4kApplyEvictsAllEntries() {
    primeCacheWithEverything();

    cacheEvictionService.evictP4kSyncedMasterData();

    assertCacheIsEmpty("a P4K apply must evict every entry");
  }

  @Test
  void findAll_leavesOutAGroupTheP4kStamped() {
    good.setP4kDeletedAt(Instant.now());
    materialCategoryRepository.flush();

    assertEquals(
        List.of("Refinable Ore"),
        materialCategoryService.findAll().stream().map(MaterialCategoryDto::name).toList());
  }

  private void primeCacheWithEverything() {
    materialCategoryService.findAll();
    materialCategoryService.findById(ore.getId());
    materialCategoryService.findById(good.getId());

    assertNotNull(cache().get(SimpleKey.EMPTY));
    assertNotNull(cache().get(ore.getId()));
    assertNotNull(cache().get(good.getId()));
  }

  private void assertCacheIsEmpty(String reason) {
    assertNull(cache().get(SimpleKey.EMPTY), reason + " (findAll entry)");
    assertNull(cache().get(ore.getId()), reason + " (ore entry)");
    assertNull(cache().get(good.getId()), reason + " (good entry)");
  }
}
