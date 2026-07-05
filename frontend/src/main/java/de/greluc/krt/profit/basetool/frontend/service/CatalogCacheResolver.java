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

package de.greluc.krt.profit.basetool.frontend.service;

import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.CacheOperationInvocationContext;
import org.springframework.cache.interceptor.CacheResolver;
import org.springframework.stereotype.Component;

/**
 * Resolves the per-domain Caffeine cache for a {@link BackendApiClient#getCached} invocation from
 * its {@link CachedCatalog} first argument (FE-CACHE-2). {@code @Cacheable} cannot compute {@code
 * cacheNames} dynamically, so a {@link CacheResolver} is the declarative seam that lets one
 * cached-read method target a different named cache per catalogue while keeping Caffeine's
 * proxy-based, concurrent- load-coalescing semantics — no programmatic {@code cacheManager.get/put}
 * in the hot path.
 */
@Component("catalogCacheResolver")
@RequiredArgsConstructor
public class CatalogCacheResolver implements CacheResolver {

  private final CacheManager cacheManager;

  /**
   * Returns the single named cache backing the {@link CachedCatalog}'s {@link CacheDomain}.
   *
   * @param context the cache-operation invocation whose first argument is the {@link CachedCatalog}
   * @return a one-element collection with the resolved domain cache
   * @throws IllegalStateException if the first argument is not a {@link CachedCatalog} or the
   *     domain cache is not registered (a wiring mistake, surfaced loudly at first call)
   */
  @Override
  public Collection<? extends Cache> resolveCaches(CacheOperationInvocationContext<?> context) {
    Object[] args = context.getArgs();
    if (args.length == 0 || !(args[0] instanceof CachedCatalog catalog)) {
      throw new IllegalStateException(
          "getCached must be invoked with a CachedCatalog as its first argument");
    }
    Cache cache = cacheManager.getCache(catalog.getDomain().getCacheName());
    if (cache == null) {
      throw new IllegalStateException(
          "No cache registered for domain " + catalog.getDomain() + " — check CacheConfig");
    }
    return List.of(cache);
  }
}
