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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;

/**
 * Composed cache annotation that flushes both material caches ({@link CacheConfig#MATERIALS_CACHE}
 * and {@link CacheConfig#MATERIAL_BY_ID_CACHE}, {@code allEntries = true}) in one place. Every
 * material mutation (create / update / delete) carries it instead of repeating the identical
 * two-entry {@code @Caching(evict = …)} block, so the eviction set has a single source of truth and
 * a newly added material cache is wired in exactly once. Spring resolves the meta-annotated
 * {@code @Caching} through its standard merged-annotation lookup, so this behaves identically to
 * the inline block it replaced.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Caching(
    evict = {
      @CacheEvict(cacheNames = CacheConfig.MATERIALS_CACHE, allEntries = true),
      @CacheEvict(cacheNames = CacheConfig.MATERIAL_BY_ID_CACHE, allEntries = true)
    })
public @interface EvictAllMaterialCaches {}
