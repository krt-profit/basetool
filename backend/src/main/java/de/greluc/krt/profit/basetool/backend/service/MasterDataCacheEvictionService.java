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
 * Evicts the master-data caches whose tables the UEX / SC Wiki / P4K sync jobs rewrite directly,
 * bypassing the CRUD services' own {@code @CacheEvict} hooks (REQ-DATA-007).
 *
 * <p>Each job calls the matching {@code evict…} method after its sweep, also after a partial
 * failure. A new cache over a synced table must be added to the matching cache-name set here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MasterDataCacheEvictionService {

  /**
   * Caches the UEX sweep ({@code UexScheduler}) can make stale: it rewrites cities, star systems,
   * locations, terminals, points of interest, outposts, the commodity/material catalogue,
   * manufacturers, ship types (vehicles) and refining methods.
   */
  static final List<String> UEX_SYNCED_CACHES =
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

  /**
   * Caches a P4K catalog apply can make stale: materials, manufacturers, ship types and the
   * blueprint master.
   */
  static final List<String> P4K_SYNCED_CACHES =
      List.of(
          CacheConfig.MATERIALS_CACHE,
          CacheConfig.MATERIAL_BY_ID_CACHE,
          CacheConfig.MANUFACTURERS_CACHE,
          CacheConfig.SHIP_TYPES_CACHE,
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

  /** Evicts every cache a P4K catalog apply can make stale. Call after a successful APPLY run. */
  public void evictP4kSyncedMasterData() {
    evict(P4K_SYNCED_CACHES, "P4K import");
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
