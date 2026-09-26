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

package de.greluc.krt.profit.basetool.backend.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.jetbrains.annotations.NotNull;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Caffeine-backed cache manager with per-cache TTLs.
 *
 * <ul>
 *   <li><b>Master data and the blueprint family index (12 h)</b> — evicted on writes and by the
 *       sync sweeps; the TTL is only a backstop.
 *   <li><b>Squadrons (6 h)</b> — evicted by {@code SquadronService} on writes.
 *   <li><b>Roles (2 min)</b> — short so permission changes propagate quickly.
 * </ul>
 *
 * <p>All caches hold at most 1000 entries, record statistics and never cache {@code null}.
 */
@Configuration
@EnableCaching
public class CacheConfig {

  /** Maximum number of entries per cache. */
  private static final long MAX_CACHE_SIZE = 1000;

  /**
   * TTL for quasi-static master data. Only a backstop: admin editors trigger {@code @CacheEvict}
   * and the UEX / SC Wiki sync sweeps evict on completion (REQ-DATA-011), so freshness comes from
   * the eviction, not the TTL — which lets it be long (12 h) to avoid re-querying data that almost
   * never changes.
   */
  private static final Duration MASTER_DATA_TTL = Duration.ofHours(12);

  /**
   * TTL for squadron context — org-structure, changed only by admin lifecycle actions that evict.
   * Long backstop (6 h) for the same reason as the master data, kept a notch shorter because the
   * squadron catalogue also drives the active-squadron switcher.
   */
  private static final Duration SQUADRONS_TTL = Duration.ofHours(6);

  /** TTL for permission-sensitive role data, kept short so role changes propagate quickly. */
  private static final Duration ROLES_TTL = Duration.ofMinutes(2);

  /** Cache name for the city reference catalogue. */
  public static final String CITIES_CACHE = "cities";

  /** Cache name for the frequency-type reference catalogue. */
  public static final String FREQUENCY_TYPES_CACHE = "frequencyTypes";

  /** Cache name for the job-type reference catalogue. */
  public static final String JOB_TYPES_CACHE = "jobTypes";

  /** Cache name for the squadron reference catalogue. */
  public static final String SQUADRONS_CACHE = "squadrons";

  /** Cache name for the location reference catalogue. */
  public static final String LOCATIONS_CACHE = "locations";

  /**
   * Cache name for the terminal reference catalogue (the large ~10 000-row price-matrix source,
   * read on the UEX-overrides admin page and the profit calculator). Both the paged list and the
   * by-id lookup share this cache; every admin visibility / loading-dock / auto-load mutator evicts
   * it, and the periodic UEX sweep evicts it on completion (REQ-DATA-011).
   */
  public static final String TERMINALS_CACHE = "terminals";

  /**
   * Cache name for the point-of-interest reference catalogue. Both the paged list and the by-id
   * lookup share this cache; the admin loading-dock override mutators evict it, and the periodic
   * UEX sweep evicts it on completion (REQ-DATA-011).
   */
  public static final String POIS_CACHE = "pois";

  /**
   * Cache name for the outpost reference catalogue. Both the paged list and the by-id lookup share
   * this cache; the admin loading-dock override mutators evict it, and the periodic UEX sweep
   * evicts it on completion (REQ-DATA-011).
   */
  public static final String OUTPOSTS_CACHE = "outposts";

  /** Cache name for the manufacturer reference catalogue. */
  public static final String MANUFACTURERS_CACHE = "manufacturers";

  /** Cache name for the material-category reference catalogue. */
  public static final String MATERIAL_CATEGORIES_CACHE = "materialCategories";

  /** Cache name for the material reference catalogue. */
  public static final String MATERIALS_CACHE = "materials";

  /**
   * Cache name for single-material lookups by id, separate from {@link #MATERIALS_CACHE} so list
   * entries cannot evict them; both are evicted together on every material write and sync.
   */
  public static final String MATERIAL_BY_ID_CACHE = "materialById";

  /** Cache name for the refining-method reference catalogue. */
  public static final String REFINING_METHODS_CACHE = "refiningMethods";

  /** Cache name for the role / permission catalogue. */
  public static final String ROLES_CACHE = "roles";

  /** Cache name for the ship-type reference catalogue. */
  public static final String SHIP_TYPES_CACHE = "shipTypes";

  /** Cache name for the star-system reference catalogue. */
  public static final String STAR_SYSTEMS_CACHE = "starSystems";

  /**
   * Cache name for the blueprint variant-family index ({@code familyKey -> product keys}) built
   * from the active blueprint master; uses the master-data TTL.
   */
  public static final String BLUEPRINT_FAMILY_INDEX_CACHE = "blueprintFamilyIndex";

  /**
   * Builds the shared {@link CacheManager} with a Caffeine spec per cache.
   *
   * <p>Every cache name is pre-registered via {@link
   * CaffeineCacheManager#registerCustomCache(String, com.github.benmanes.caffeine.cache.Cache)}, so
   * an unknown cache name fails at startup.
   *
   * @return configured Caffeine cache manager with per-cache TTLs
   */
  @NotNull
  @Bean
  public CacheManager cacheManager() {
    CaffeineCacheManager manager = new CaffeineCacheManager();
    manager.setAllowNullValues(false);

    register(manager, CITIES_CACHE, MASTER_DATA_TTL);
    register(manager, TERMINALS_CACHE, MASTER_DATA_TTL);
    register(manager, POIS_CACHE, MASTER_DATA_TTL);
    register(manager, OUTPOSTS_CACHE, MASTER_DATA_TTL);
    register(manager, FREQUENCY_TYPES_CACHE, MASTER_DATA_TTL);
    register(manager, JOB_TYPES_CACHE, MASTER_DATA_TTL);
    register(manager, LOCATIONS_CACHE, MASTER_DATA_TTL);
    register(manager, MANUFACTURERS_CACHE, MASTER_DATA_TTL);
    register(manager, MATERIAL_CATEGORIES_CACHE, MASTER_DATA_TTL);
    register(manager, MATERIALS_CACHE, MASTER_DATA_TTL);
    register(manager, MATERIAL_BY_ID_CACHE, MASTER_DATA_TTL);
    register(manager, REFINING_METHODS_CACHE, MASTER_DATA_TTL);
    register(manager, SHIP_TYPES_CACHE, MASTER_DATA_TTL);
    register(manager, STAR_SYSTEMS_CACHE, MASTER_DATA_TTL);
    register(manager, BLUEPRINT_FAMILY_INDEX_CACHE, MASTER_DATA_TTL);

    register(manager, SQUADRONS_CACHE, SQUADRONS_TTL);
    register(manager, ROLES_CACHE, ROLES_TTL);

    return manager;
  }

  /**
   * Registers a Caffeine cache under {@code name} with the shared sizing and statistics policy.
   *
   * @param manager target Spring cache manager
   * @param name cache name as referenced from {@code @Cacheable}
   * @param ttl expire-after-write duration
   */
  private static void register(@NotNull CaffeineCacheManager manager, String name, Duration ttl) {
    manager.registerCustomCache(
        name,
        Caffeine.newBuilder()
            .maximumSize(MAX_CACHE_SIZE)
            .expireAfterWrite(ttl)
            .recordStats()
            .build());
  }
}
