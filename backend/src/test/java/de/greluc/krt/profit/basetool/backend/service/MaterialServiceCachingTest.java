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
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
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
 * Spring-Boot integration tests for the material caches (CACHE-02). Verifies that {@code
 * getMaterial} lands in the dedicated {@link CacheConfig#MATERIAL_BY_ID_CACHE} (never the list
 * catalogue), that the list reads populate {@link CacheConfig#MATERIALS_CACHE} under their prefixed
 * keys, and that a material write evicts <b>both</b> caches so a single-entity entry can never
 * survive a rename/delete while the list entry is dropped. Runs in a rolled-back transaction, so it
 * seeds its own two materials without disturbing the seeded catalogue.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MaterialServiceCachingTest {

  @Autowired private MaterialService materialService;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private CacheManager cacheManager;

  private Material alpha;
  private Material beta;

  @BeforeEach
  void seedAndClearCache() {
    alpha = newMaterial("CachingTest Alpha Ore");
    beta = newMaterial("CachingTest Beta Ore");
    materialRepository.save(alpha);
    materialRepository.save(beta);

    cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
  }

  private static Material newMaterial(String name) {
    Material material = new Material();
    material.setName(name);
    material.setType(MaterialType.RAW);
    // quantityType / isVisible / isJobOrder / isManualRawMaterial carry NOT-NULL entity defaults.
    return material;
  }

  private Cache byIdCache() {
    Cache cache = cacheManager.getCache(CacheConfig.MATERIAL_BY_ID_CACHE);
    assertNotNull(cache, "materialById cache must be registered by CacheConfig");
    return cache;
  }

  private Cache listCache() {
    Cache cache = cacheManager.getCache(CacheConfig.MATERIALS_CACHE);
    assertNotNull(cache, "materials cache must be registered by CacheConfig");
    return cache;
  }

  @Test
  void getMaterial_populatesTheByIdCacheOnly_notTheListCache() {
    Material first = materialService.getMaterial(alpha.getId());

    Cache.ValueWrapper entry = byIdCache().get(alpha.getId());
    assertNotNull(entry, "getMaterial(id) must populate MATERIAL_BY_ID_CACHE under the id key");
    assertSame(first, entry.get(), "cache entry must reference the same instance as the read");
    assertNull(
        listCache().get(alpha.getId()),
        "getMaterial(id) must NOT populate the list catalogue cache — the whole point of CACHE-02 "
            + "is that by-id lookups and list-page sweeps no longer share a budget");

    Material second = materialService.getMaterial(alpha.getId());
    assertSame(first, second, "second read must come from the cache, not the repository");
  }

  @Test
  void getAllMaterials_and_getVisibleMaterials_populateTheListCacheUnderPrefixedKeys() {
    Pageable pageable = PageRequest.of(0, 10, Sort.by("name"));

    materialService.getAllMaterials(pageable);
    materialService.getVisibleMaterials(pageable);

    assertNotNull(
        listCache().get("all-" + pageable),
        "getAllMaterials must cache under the 'all-'+pageable key");
    assertNotNull(
        listCache().get("visible-" + pageable),
        "getVisibleMaterials must cache under the 'visible-'+pageable key (distinct from 'all-')");
  }

  @Test
  void deleteMaterial_evictsBothTheListAndByIdCaches() {
    Pageable pageable = PageRequest.of(0, 10, Sort.by("name"));
    materialService.getMaterial(beta.getId());
    materialService.getAllMaterials(pageable);
    // Sanity: both caches primed
    assertNotNull(byIdCache().get(beta.getId()));
    assertNotNull(listCache().get("all-" + pageable));

    materialService.deleteMaterial(beta.getId());

    assertNull(byIdCache().get(beta.getId()), "delete must evict the by-id cache");
    assertNull(listCache().get("all-" + pageable), "delete must evict the list cache");
  }

  @Test
  void byIdCacheRepopulatesAfterAWriteEvictsIt() {
    materialService.getMaterial(alpha.getId());
    // A write to any material evicts the whole by-id cache (allEntries=true).
    materialService.deleteMaterial(beta.getId());
    assertNull(byIdCache().get(alpha.getId()), "the surviving material's entry is evicted too");

    materialService.getMaterial(alpha.getId());

    assertNotNull(
        byIdCache().get(alpha.getId()),
        "cache must repopulate on the next read so the next write exercises eviction again");
  }
}
