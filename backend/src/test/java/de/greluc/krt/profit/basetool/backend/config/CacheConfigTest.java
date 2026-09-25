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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.test.context.ActiveProfiles;

/**
 * Structural test for {@link CacheConfig}. Catches the common refactor mistake of declaring a
 * {@code public static final String FOO_CACHE = "foo"} constant on the config class but forgetting
 * to add the name to the {@code setCacheNames(...)} whitelist — the {@code @Cacheable} annotation
 * that references the constant would then throw {@code IllegalStateException: Cannot find cache
 * named 'foo'} only at runtime on the first request.
 */
@SpringBootTest
@ActiveProfiles("test")
class CacheConfigTest {

  @Autowired private CacheManager cacheManager;

  @Test
  void everyDeclaredCacheConstantIsRegisteredInTheCacheManager() {
    List<String> declared = reflectCacheNameConstants();
    assertTrue(
        declared.size() >= 13,
        "expected at least the 13 documented caches, found " + declared.size());

    Collection<String> registered = cacheManager.getCacheNames();

    List<String> missing = new ArrayList<>();
    for (String name : declared) {
      if (!registered.contains(name)) {
        missing.add(name);
      }
      assertNotNull(
          cacheManager.getCache(name),
          () -> "cache '" + name + "' must be resolvable via cacheManager.getCache()");
    }

    if (!missing.isEmpty()) {
      fail(
          "CacheConfig declares the following *_CACHE constants but the CacheManager does NOT "
              + "expose them — they were probably added to the constants block but not to the "
              + "setCacheNames(...) whitelist: "
              + missing);
    }
  }

  @Test
  void cacheManagerIsTheCaffeineBackedOne() {
    assertTrue(
        cacheManager instanceof CaffeineCacheManager,
        "CacheConfig must publish a CaffeineCacheManager — replacing it changes eviction"
            + " semantics");
  }

  private static List<String> reflectCacheNameConstants() {
    List<String> names = new ArrayList<>();
    for (Field field : CacheConfig.class.getDeclaredFields()) {
      int mods = field.getModifiers();
      boolean isPublicStaticFinalString =
          Modifier.isPublic(mods)
              && Modifier.isStatic(mods)
              && Modifier.isFinal(mods)
              && field.getType() == String.class;
      if (!isPublicStaticFinalString || !field.getName().endsWith("_CACHE")) {
        continue;
      }
      try {
        names.add((String) field.get(null));
      } catch (IllegalAccessException e) {
        throw new AssertionError("public static final String must be readable", e);
      }
    }
    return names;
  }
}
