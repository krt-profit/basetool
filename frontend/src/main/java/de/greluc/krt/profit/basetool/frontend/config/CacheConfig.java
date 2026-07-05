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
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
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
 * <p>All caches share the sizing/TTL policy (10-minute {@code expireAfterWrite}, {@code
 * maximumSize=1000}) and {@code recordStats()} — Spring Boot binds Caffeine's
 * hit/miss/eviction/size counters as {@code cache_*} meters per {@code cache} label
 * (REQ-OBS-005/-006), so the monitoring hit-ratio / eviction / size panels light up per domain
 * automatically. {@code setCacheNames(...)} pre-registers exactly the domain caches and disables
 * dynamic creation, so a {@code getCached} whose domain cache is missing fails loudly instead of
 * silently creating an unmonitored cache.
 */
@Configuration
@EnableCaching
public class CacheConfig {

  /**
   * Builds the shared cache manager with one named Caffeine cache per {@link CacheDomain}.
   *
   * @return the configured {@link CaffeineCacheManager}
   */
  @Bean
  public CacheManager cacheManager() {
    CaffeineCacheManager cacheManager = new CaffeineCacheManager();
    cacheManager.setCaffeine(
        Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .maximumSize(1000)
            .recordStats());
    cacheManager.setCacheNames(
        Arrays.stream(CacheDomain.values()).map(CacheDomain::getCacheName).toList());
    return cacheManager;
  }
}
