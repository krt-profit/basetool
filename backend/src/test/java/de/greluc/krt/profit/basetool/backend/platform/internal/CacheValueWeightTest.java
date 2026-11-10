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

package de.greluc.krt.profit.basetool.backend.platform.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.benmanes.caffeine.cache.Caffeine;
import de.greluc.krt.profit.basetool.backend.catalogue.api.TerminalDto;
import de.greluc.krt.profit.basetool.backend.platform.api.CacheValueWeight;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

/** Exercises admission bounds independently of catalogue reads and invalidation wiring. */
class CacheValueWeightTest {
  @Test
  void weighsRecordsPagesAndCompleteIndexesByTheirRetainedShape() {
    TerminalDto row = terminal(1);
    int single = CacheValueWeight.weigh("one", row, Integer.MAX_VALUE);
    int page =
        CacheValueWeight.weigh(
            "page", new PageImpl<>(List.of(row, terminal(2))), Integer.MAX_VALUE);
    int index =
        CacheValueWeight.weigh("index", Map.of("family", Set.of("one", "two")), Integer.MAX_VALUE);
    assertTrue(single > 0 && single < 4096);
    assertTrue(page > single);
    assertTrue(index > 256 && index < 4096);
  }

  @Test
  void aLargePageCannotDisplaceAnUnboundedWorkingSet() {
    var cache =
        Caffeine.newBuilder()
            .maximumWeight(4096)
            .weigher((Object key, Object value) -> CacheValueWeight.weigh(key, value, 4096))
            .recordStats()
            .build();
    cache.put("small", terminal(1));
    List<TerminalDto> rows = new ArrayList<>();
    for (int index = 0; index < 100; index++) {
      rows.add(terminal(index));
    }
    cache.put("large", new PageImpl<>(rows));
    cache.cleanUp();
    assertNull(cache.getIfPresent("large"));
    assertNotNull(cache.getIfPresent("small"));
    assertTrue(cache.stats().evictionCount() > 0);
    assertTrue(cache.policy().eviction().orElseThrow().weightedSize().orElseThrow() <= 4096);
    cache.invalidateAll();
    cache.cleanUp();
    assertNull(cache.getIfPresent("small"));
  }

  @Test
  void rejectsUnknownShapesAndTraversesCyclesWithoutRecursiveOverflow() {
    assertEquals(Integer.MAX_VALUE, CacheValueWeight.weigh("unknown", new Object(), 4096));
    List<Object> cyclic = new ArrayList<>();
    cyclic.add(cyclic);
    assertTrue(CacheValueWeight.weigh("cycle", cyclic, 4096) < 4096);
    List<Object> deep = new ArrayList<>();
    List<Object> leaf = deep;
    for (int index = 0; index < 10000; index++) {
      var next = new ArrayList<Object>();
      leaf.add(next);
      leaf = next;
    }
    assertEquals(Integer.MAX_VALUE, CacheValueWeight.weigh("deep", deep, 4096));
  }

  @Test
  void everyCachedDtoFamilyRemainsAdmissible() throws ReflectiveOperationException {
    List<String> names =
        List.of(
            "catalogue.api.CityDto",
            "catalogue.api.TerminalDto",
            "catalogue.api.PoiDto",
            "catalogue.api.OutpostDto",
            "catalogue.api.FrequencyTypeDto",
            "catalogue.api.JobTypeDto",
            "catalogue.api.LocationDto",
            "catalogue.api.ManufacturerDto",
            "catalogue.api.MaterialCategoryDto",
            "catalogue.api.MaterialDto",
            "catalogue.api.RefiningMethodDto",
            "catalogue.api.ShipTypeDto",
            "catalogue.api.StarSystemDto",
            "identity.internal.RoleDto",
            "orgunit.api.SquadronDto");
    for (String name : names) {
      Class<?> type = Class.forName("de.greluc.krt.profit.basetool.backend." + name);
      Object row = fixture(type, new HashSet<>());
      int weight = CacheValueWeight.weigh("fixture", row, 1024 * 1024);
      assertTrue(weight > 0 && weight < 1024 * 1024, name + " must remain cacheable");
    }
  }

  private static Object fixture(Class<?> type, Set<Class<?>> ancestors)
      throws ReflectiveOperationException {
    if (type == String.class) {
      return "synthetic";
    }
    if (type == UUID.class) {
      return new UUID(0, 1);
    }
    if (type == Instant.class) {
      return Instant.EPOCH;
    }
    if (type == Boolean.class || type == boolean.class) {
      return false;
    }
    if (type == Integer.class || type == int.class) {
      return 1;
    }
    if (type == Long.class || type == long.class) {
      return 1L;
    }
    if (type == Double.class || type == double.class) {
      return 1.0;
    }
    if (type == BigDecimal.class) {
      return BigDecimal.ONE;
    }
    if (List.class.isAssignableFrom(type)) {
      return List.of();
    }
    if (Set.class.isAssignableFrom(type)) {
      return Set.of();
    }
    if (Map.class.isAssignableFrom(type)) {
      return Map.of();
    }
    if (type.isEnum()) {
      return type.getEnumConstants()[0];
    }
    if (!type.isRecord() || !ancestors.add(type)) {
      return null;
    }
    RecordComponent[] components = type.getRecordComponents();
    Class<?>[] types = new Class<?>[components.length];
    Object[] values = new Object[components.length];
    for (int index = 0; index < components.length; index++) {
      types[index] = components[index].getType();
      values[index] = fixture(types[index], ancestors);
    }
    ancestors.remove(type);
    return type.getDeclaredConstructor(types).newInstance(values);
  }

  static TerminalDto terminal(int index) {
    return new TerminalDto(
        new UUID(0, index),
        "Terminal " + index,
        "Port " + index,
        "Stanton",
        "Hurston",
        "Lorville",
        null,
        true,
        true,
        false,
        false,
        true,
        true,
        Instant.EPOCH,
        32,
        true,
        true,
        true,
        "4.5",
        false);
  }
}
