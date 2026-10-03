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
import de.greluc.krt.profit.basetool.backend.model.MaterialCategory;
import de.greluc.krt.profit.basetool.backend.repository.MaterialCategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.SimpleKey;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring-Boot integration tests for the {@code @Cacheable} / {@code @CacheEvict} annotations on
 * {@link MaterialCategoryService}. The {@code findAll()} method is no-arg and therefore keyed by
 * {@link SimpleKey#EMPTY}, which is verified explicitly so a refactor adding parameters to {@code
 * findAll} cannot silently shift the cache key without breaking the test.
 */
@SpringBootTest
@Transactional
class MaterialCategoryServiceCachingTest {

  @Autowired private MaterialCategoryService materialCategoryService;
  @Autowired private MaterialCategoryRepository materialCategoryRepository;
  @Autowired private CacheManager cacheManager;

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
    MaterialCategory first = materialCategoryService.findById(ore.getId());

    Cache.ValueWrapper entry = cache().get(ore.getId());
    assertNotNull(entry, "findById(id) must populate the cache under the id key");
    assertSame(first, entry.get(), "cache entry must reference the same instance as the read");

    MaterialCategory second = materialCategoryService.findById(ore.getId());
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
  void create_evictsAllEntries() {
    primeCacheWithEverything();

    MaterialCategory consumable = new MaterialCategory();
    consumable.setName("Consumable");
    materialCategoryService.create(consumable);

    assertCacheIsEmpty("create must evict every entry");
  }

  @Test
  void update_evictsAllEntries() {
    primeCacheWithEverything();

    MaterialCategory rename = new MaterialCategory();
    rename.setName("Refinable Ore (renamed)");
    materialCategoryService.update(ore.getId(), rename);

    assertCacheIsEmpty("update must evict every entry");
  }

  @Test
  void delete_evictsAllEntries() {
    primeCacheWithEverything();

    materialCategoryService.delete(good.getId());

    assertCacheIsEmpty("delete must evict every entry");
  }

  @Test
  void cacheRepopulatesAfterMutation() {
    materialCategoryService.findById(ore.getId());
    MaterialCategory rename = new MaterialCategory();
    rename.setName("Refinable Ore (rename-2)");
    materialCategoryService.update(ore.getId(), rename);
    assertNull(cache().get(ore.getId()));

    materialCategoryService.findById(ore.getId());

    assertNotNull(
        cache().get(ore.getId()),
        "cache must repopulate on the next read so the next mutator exercises eviction again");
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
