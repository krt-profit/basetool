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

package de.greluc.krt.profit.basetool.frontend.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import de.greluc.krt.profit.basetool.frontend.service.CacheDomain;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Caffeine-backed {@link CacheManager} exposing one named cache per {@link CacheDomain}
 * (FE-CACHE-2).
 *
 * <p>Slow-changing backend catalogues used to share a single {@code staticData} cache that any
 * admin mutation dropped wholesale — so a squadron toggle cold-started the 10&nbsp;000-row terminal
 * list, the material lists and every location list it never touched. Each catalogue now lives in
 * its domain's cache ({@code squadronCatalogue}, {@code orgUnitCatalogue}, {@code
 * materialCatalogue}, …) so a mutation evicts only the affected domain via {@code
 * BackendApiClient.evict(CacheDomain…)}. Routing is declarative: {@code getCached(CachedCatalog,
 * …)} is {@code @Cacheable} with a {@link
 * de.greluc.krt.profit.basetool.frontend.service.CatalogCacheResolver} that picks the domain cache
 * from the catalogue argument.
 *
 * <p>Each domain cache is registered with its <b>own</b> {@code expireAfterWrite} TTL (see {@link
 * CacheDomain#getTtl()}) — 6&nbsp;h for pure reference catalogues, 2&nbsp;h for the org-structure
 * catalogues and settings. The TTL is only a backstop: cacheability is eviction-gated
 * (REQ-DATA-007), so freshness comes from the per-mutation evict, not from the TTL. All caches keep
 * {@code maximumSize=1000} and {@code recordStats()} — Spring Boot binds Caffeine's
 * hit/miss/eviction/size counters as {@code cache_*} meters per {@code cache} label
 * (REQ-OBS-005/-006), so the monitoring hit-ratio / eviction / size panels light up per domain
 * automatically.
 */
@Configuration
@EnableCaching
public class CacheConfig {

  /** Shared entry cap for every domain cache. */
  private static final long MAX_CACHE_SIZE = 1000;

  /**
   * Builds the shared cache manager, registering one named Caffeine cache per {@link CacheDomain}
   * with the domain's own TTL. Pre-registering every domain (rather than {@code setCaffeine} + a
   * single spec) is what lets each domain carry a different {@code expireAfterWrite}.
   *
   * @return the configured {@link CaffeineCacheManager}
   */
  @Bean
  public CacheManager cacheManager() {
    CaffeineCacheManager cacheManager = new CaffeineCacheManager();
    for (CacheDomain domain : CacheDomain.values()) {
      cacheManager.registerCustomCache(
          domain.getCacheName(),
          Caffeine.newBuilder()
              .expireAfterWrite(domain.getTtl())
              .maximumSize(MAX_CACHE_SIZE)
              .recordStats()
              .build());
    }
    return cacheManager;
  }
}
