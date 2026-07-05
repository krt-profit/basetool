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
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

/**
 * Evicts the master-data Caffeine caches whose backing tables are bulk-rewritten by the periodic
 * UEX / SC Wiki sync sweeps.
 *
 * <p>The admin CRUD services (e.g. {@link MaterialService}, {@code ManufacturerService}) evict
 * their own cache on every user-facing write, but the {@code Uex*SyncService} / {@code
 * ScWiki*SyncService} background jobs write directly to the repositories and therefore bypass those
 * {@code @CacheEvict} hooks. Before this service the only reconciliation was the 12-hour
 * master-data TTL (REQ-DATA-007, CACHE-SYNC-EVICT-001), so a freshly-synced
 * material/manufacturer/ship-type/star-system/refining-method could stay invisible for up to the
 * TTL. Each scheduler now calls the matching {@code evict…} method once its sweep has finished (in
 * a {@code finally}, so committed steps are reconciled even after a mid-sweep step failure),
 * collapsing the stale window to "next read after the sync".
 *
 * <p>The per-sweep cache-name sets are the single source of truth for "which caches a sync can make
 * stale"; a new cache added over a synced table must be added to the matching set here (and is
 * pinned by {@code MasterDataCacheEvictionServiceTest}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MasterDataCacheEvictionService {

  /**
   * Caches the UEX sweep ({@code UexScheduler}) can make stale: it rewrites cities, star systems,
   * the commodity/material catalogue, manufacturers, ship types (vehicles) and refining methods.
   */
  static final List<String> UEX_SYNCED_CACHES =
      List.of(
          CacheConfig.CITIES_CACHE,
          CacheConfig.STAR_SYSTEMS_CACHE,
          CacheConfig.MATERIALS_CACHE,
          CacheConfig.MATERIAL_BY_ID_CACHE,
          CacheConfig.MANUFACTURERS_CACHE,
          CacheConfig.SHIP_TYPES_CACHE,
          CacheConfig.REFINING_METHODS_CACHE);

  /**
   * Caches the SC Wiki sweep ({@code ScWikiScheduler}) can make stale: it merges commodities
   * (materials), vehicles (ship types), manufacturers and rebuilds the blueprint master that backs
   * the blueprint variant-family index (CACHE-DIST-03 — that index has no per-write evict hook).
   */
  static final List<String> SCWIKI_SYNCED_CACHES =
      List.of(
          CacheConfig.MATERIALS_CACHE,
          CacheConfig.MATERIAL_BY_ID_CACHE,
          CacheConfig.SHIP_TYPES_CACHE,
          CacheConfig.MANUFACTURERS_CACHE,
          CacheConfig.BLUEPRINT_FAMILY_INDEX_CACHE);

  private final CacheManager cacheManager;

  /** Evicts every cache the UEX sync sweep can make stale. Call after a UEX sweep completes. */
  public void evictUexSyncedMasterData() {
    evict(UEX_SYNCED_CACHES, "UEX");
  }

  /** Evicts every cache the SC Wiki sync sweep can make stale. Call after an SC Wiki sweep. */
  public void evictScWikiSyncedMasterData() {
    evict(SCWIKI_SYNCED_CACHES, "SC Wiki");
  }

  /**
   * Clears each named cache, tolerating a missing cache (logged at WARN — a name here that is not
   * registered in {@link CacheConfig} is a wiring mistake, not a runtime failure).
   *
   * @param cacheNames the caches to clear
   * @param sweep the sweep label for the log line
   */
  private void evict(@NotNull List<String> cacheNames, @NotNull String sweep) {
    for (String name : cacheNames) {
      Cache cache = cacheManager.getCache(name);
      if (cache != null) {
        cache.clear();
      } else {
        log.warn(
            "Cannot evict cache '{}' after {} sync — not registered in the CacheManager",
            name,
            sweep);
      }
    }
    log.debug("Evicted {} master-data cache(s) after the {} sync sweep", cacheNames.size(), sweep);
  }
}
