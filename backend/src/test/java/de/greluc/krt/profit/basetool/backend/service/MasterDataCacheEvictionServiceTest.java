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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.config.CacheConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link MasterDataCacheEvictionService}. Pins which caches each sync sweep evicts
 * (the two sets are the single source of truth for "a sync can make these stale") and that a
 * missing cache is tolerated rather than throwing mid-sweep.
 */
@ExtendWith(MockitoExtension.class)
class MasterDataCacheEvictionServiceTest {

  @Mock private org.springframework.cache.CacheManager cacheManager;
  @Mock private org.springframework.cache.Cache cache;
  @InjectMocks private MasterDataCacheEvictionService service;

  @Test
  void evictUexSyncedMasterData_clearsEveryCacheTheUexSweepCanMakeStale() {
    when(cacheManager.getCache(anyString())).thenReturn(cache);

    service.evictUexSyncedMasterData();

    for (String name : MasterDataCacheEvictionService.UEX_SYNCED_CACHES) {
      verify(cacheManager).getCache(name);
    }
    verify(cache, times(MasterDataCacheEvictionService.UEX_SYNCED_CACHES.size())).clear();
  }

  @Test
  void evictScWikiSyncedMasterData_clearsEveryCacheTheScWikiSweepCanMakeStale() {
    when(cacheManager.getCache(anyString())).thenReturn(cache);

    service.evictScWikiSyncedMasterData();

    for (String name : MasterDataCacheEvictionService.SCWIKI_SYNCED_CACHES) {
      verify(cacheManager).getCache(name);
    }
    verify(cache, times(MasterDataCacheEvictionService.SCWIKI_SYNCED_CACHES.size())).clear();
  }

  @Test
  void evictP4kSyncedMasterData_clearsEveryCacheAP4kApplyCanMakeStale() {
    when(cacheManager.getCache(anyString())).thenReturn(cache);

    service.evictP4kSyncedMasterData();

    for (String name : MasterDataCacheEvictionService.P4K_SYNCED_CACHES) {
      verify(cacheManager).getCache(name);
    }
    verify(cache, times(MasterDataCacheEvictionService.P4K_SYNCED_CACHES.size())).clear();
  }

  @Test
  void evict_toleratesAnUnregisteredCacheWithoutThrowing() {
    // A name that resolves to null (not registered) must not abort the sweep-completion eviction.
    when(cacheManager.getCache(anyString())).thenReturn(null);

    assertDoesNotThrow(service::evictUexSyncedMasterData);
    assertDoesNotThrow(service::evictScWikiSyncedMasterData);
    assertDoesNotThrow(service::evictP4kSyncedMasterData);
  }

  @Test
  void uexSyncedSet_isExactlyTheUexWrittenMasterDataCaches() {
    assertEquals(
        List.of(
            CacheConfig.CITIES_CACHE,
            CacheConfig.STAR_SYSTEMS_CACHE,
            CacheConfig.LOCATIONS_CACHE,
            CacheConfig.TERMINALS_CACHE,
            CacheConfig.POIS_CACHE,
            CacheConfig.OUTPOSTS_CACHE,
            CacheConfig.MATERIALS_CACHE,
            CacheConfig.MATERIAL_BY_ID_CACHE,
            CacheConfig.MANUFACTURERS_CACHE,
            CacheConfig.SHIP_TYPES_CACHE,
            CacheConfig.REFINING_METHODS_CACHE),
        MasterDataCacheEvictionService.UEX_SYNCED_CACHES,
        "a new cache over a UEX-synced table must be added here or it lags the TTL after a sync");
  }

  @Test
  void scWikiSyncedSet_isExactlyTheScWikiWrittenMasterDataCaches() {
    assertEquals(
        List.of(
            CacheConfig.MATERIALS_CACHE,
            CacheConfig.MATERIAL_BY_ID_CACHE,
            CacheConfig.SHIP_TYPES_CACHE,
            CacheConfig.MANUFACTURERS_CACHE,
            CacheConfig.BLUEPRINT_FAMILY_INDEX_CACHE),
        MasterDataCacheEvictionService.SCWIKI_SYNCED_CACHES,
        "a new cache over an SC-Wiki-synced table must be added here or it lags the TTL after a"
            + " sync");
  }

  @Test
  void p4kSyncedSet_isExactlyTheP4kWrittenMasterDataCaches() {
    assertEquals(
        List.of(
            CacheConfig.MATERIALS_CACHE,
            CacheConfig.MATERIAL_BY_ID_CACHE,
            CacheConfig.MANUFACTURERS_CACHE,
            CacheConfig.SHIP_TYPES_CACHE,
            CacheConfig.BLUEPRINT_FAMILY_INDEX_CACHE),
        MasterDataCacheEvictionService.P4K_SYNCED_CACHES,
        "a new cache over a P4K-applied table must be added here or it lags the TTL after an"
            + " apply");
  }
}
