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
import java.util.concurrent.TimeUnit;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring configuration for Cache. */
@Configuration
@EnableCaching
public class CacheConfig {

  public static final String STATIC_DATA_CACHE = "staticData";

  /**
   * Caffeine-backed {@link CacheManager} exposing the {@link #STATIC_DATA_CACHE} cache (10-minute
   * TTL, max 1000 entries). Used by {@code BackendApiClient.getCached(...)} for slow-changing
   * lookup data.
   *
   * <p>{@code recordStats()} keeps Caffeine's hit/miss counters, which Spring Boot's cache metrics
   * auto-configuration binds as {@code cache_*} meters on {@code /actuator/prometheus}
   * (REQ-OBS-005, epic #936) — without it the hit-ratio panels of the monitoring dashboards would
   * read zero forever. The counters are plain {@code LongAdder}s; the overhead is negligible
   * against the backend HTTP round-trip each miss saves.
   */
  @Bean
  public CacheManager cacheManager() {
    CaffeineCacheManager cacheManager = new CaffeineCacheManager(STATIC_DATA_CACHE);
    cacheManager.setCaffeine(
        Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .maximumSize(1000)
            .recordStats());
    return cacheManager;
  }
}
