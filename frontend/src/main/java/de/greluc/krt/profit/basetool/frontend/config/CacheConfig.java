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
import org.jetbrains.annotations.NotNull;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Caffeine-backed {@link CacheManager} with one named cache per {@link CacheDomain}, each with its
 * own TTL ({@link CacheDomain#getTtl()}).
 *
 * <p>Freshness comes from per-domain eviction on mutation (REQ-DATA-007); the TTL is a backstop.
 * All caches record stats for the {@code cache_*} meters (REQ-OBS-005/-006).
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
  @NotNull
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
