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
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Caffeine-backed Spring cache manager for the project's reference-data caches.
 *
 * <p>Per-cache TTLs (L-4 from the performance audit) instead of one global value, because the
 * caches have very different freshness requirements:
 *
 * <ul>
 *   <li><b>Master data (30 min)</b> — cities, materials (list + by-id), ship types, locations,
 *       frequency types, job types, manufacturers, refining methods, star systems, material
 *       categories, and the blueprint variant-family index (rebuilt from the active blueprint
 *       master; no per-write evict hook of its own). Quasi-static: editor flows already trigger
 *       {@code @CacheEvict (allEntries=true)} on writes, and the periodic UEX / SC Wiki sync sweeps
 *       evict the caches they rewrite on completion (via {@code MasterDataCacheEvictionService},
 *       CACHE-SYNC-EVICT-001), so a stale entry only survives until the next admin write, the next
 *       sync sweep, or the 30 min lapse — whichever comes first. The previous 2 min global TTL kept
 *       paying the underlying DB-hit cost 15× per hour for data that almost never changes.
 *   <li><b>Squadrons (10 min)</b> — slower than master data because admins toggle the active
 *       squadron mid-session via the {@code X-Active-Org-Unit-Id} header, but the squadron entity
 *       itself rarely changes. {@code SquadronService} already evicts on writes.
 *   <li><b>Roles (2 min)</b> — kept short. A Keycloak role change should propagate quickly so a
 *       freshly-elevated officer does not stare at a 30 min cached "you don't have permission"
 *       page; 2 min is the audit's recommended fast-propagation floor for permission-sensitive
 *       data.
 * </ul>
 *
 * <p>All caches share the same Caffeine sizing ({@code maximumSize=1000}, statistics on) and the
 * {@code setAllowNullValues=false} contract — a missed lookup must remain a miss so the next call
 * retries instead of caching the absence. The {@link CaffeineCacheManager#registerCustomCache} API
 * lets us keep one manager bean while giving each cache its own builder, instead of falling back to
 * {@code SimpleCacheManager} (which would change the bean type and trip the existing {@code
 * CacheConfigTest.cacheManagerIsTheCaffeineBackedOne} assertion).
 */
@Configuration
@EnableCaching
public class CacheConfig {

  /** Default cache size in entries — the same value historically used for every cache. */
  private static final long MAX_CACHE_SIZE = 1000;

  /** TTL for quasi-static master data; explicit {@code @CacheEvict} on writes handles updates. */
  private static final Duration MASTER_DATA_TTL = Duration.ofMinutes(30);

  /** TTL for squadron context — slower than master data, still infrequently changing. */
  private static final Duration SQUADRONS_TTL = Duration.ofMinutes(10);

  /** TTL for permission-sensitive data; Keycloak role changes must propagate quickly. */
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

  /** Cache name for the manufacturer reference catalogue. */
  public static final String MANUFACTURERS_CACHE = "manufacturers";

  /** Cache name for the material-category reference catalogue. */
  public static final String MATERIAL_CATEGORIES_CACHE = "materialCategories";

  /** Cache name for the material reference catalogue. */
  public static final String MATERIALS_CACHE = "materials";

  /**
   * Cache name for single-material by-id lookups. Kept separate from {@link #MATERIALS_CACHE} so
   * the unbounded per-{@code Pageable} list entries of the list catalogue cannot evict a hot
   * single-entity lookup (and vice-versa) when they share one {@code maximumSize} budget
   * (CACHE-02). Both caches are evicted together on every material write and on the UEX / SC Wiki
   * sync sweep.
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
   * Cache name for the blueprint variant-family index ({@code familyKey -> product keys}). Built
   * once from the ~1600-row active blueprint master, it backs the family-aware owner drill-down on
   * the availability overview (#364), keeping the per-expand query bounded to a family's product
   * keys instead of a table scan. Master-data TTL; the catalog only changes on the SC Wiki
   * blueprint sync.
   */
  public static final String BLUEPRINT_FAMILY_INDEX_CACHE = "blueprintFamilyIndex";

  /**
   * Builds the shared {@link CacheManager} with per-cache Caffeine specs. Every cache name is
   * pre-registered via {@link CaffeineCacheManager#registerCustomCache(String,
   * com.github.benmanes.caffeine.cache.Cache)} so an unknown name on a {@code @Cacheable}
   * annotation throws at startup rather than silently creating a default-policy cache.
   *
   * @return configured Caffeine cache manager with per-cache TTLs (see class Javadoc)
   */
  @Bean
  public CacheManager cacheManager() {
    CaffeineCacheManager manager = new CaffeineCacheManager();
    manager.setAllowNullValues(false);

    register(manager, CITIES_CACHE, MASTER_DATA_TTL);
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
   * Registers a Caffeine cache under {@code name} with the shared sizing/statistics policy and the
   * supplied TTL. Centralised here so changing the shared sizing later is a one-line edit and the
   * per-cache lines above stay focused on the policy decision.
   *
   * @param manager target Spring cache manager
   * @param name cache name (the constant referenced from {@code @Cacheable(cacheNames = …)})
   * @param ttl write-expire duration
   */
  private static void register(CaffeineCacheManager manager, String name, Duration ttl) {
    manager.registerCustomCache(
        name,
        Caffeine.newBuilder()
            .maximumSize(MAX_CACHE_SIZE)
            .expireAfterWrite(ttl)
            .recordStats()
            .build());
  }
}
