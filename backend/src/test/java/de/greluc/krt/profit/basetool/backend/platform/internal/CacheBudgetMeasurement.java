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

import com.github.benmanes.caffeine.cache.Caffeine;
import de.greluc.krt.profit.basetool.backend.catalogue.api.TerminalDto;
import de.greluc.krt.profit.basetool.backend.platform.api.CacheValueWeight;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.IntFunction;
import org.springframework.data.domain.PageImpl;

/** Isolated synthetic heap and hit-rate characterization; it starts no application context. */
public final class CacheBudgetMeasurement {
  private static Object retained;
  private static final int MEBIBYTE = 1024 * 1024;

  private CacheBudgetMeasurement() {}

  /**
   * Reports approximate retained deltas and a deterministic mixed-locality cache trace.
   *
   * @param args unused command-line arguments
   * @throws InterruptedException when the characterization is interrupted
   */
  public static void main(String[] args) throws InterruptedException {
    measure("terminal", 10000, CacheValueWeightTest::terminal);
    measure("page50", 200, index -> page(index * 50, 50));
    measure("page1000", 30, index -> page(index * 1000, 1000));
    measure("familyIndex10000", 5, CacheBudgetMeasurement::index);
    trace(false);
    trace(true);
  }

  private static void measure(String name, int count, IntFunction<Object> factory)
      throws InterruptedException {
    retained = null;
    factory.apply(0);
    collect();
    long before = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    var values = new ArrayList<Object>(count);
    for (int item = 0; item < count; item++) {
      values.add(factory.apply(item));
    }
    retained = values;
    collect();
    long delta = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() - before;
    int estimate = CacheValueWeight.weigh("fixture", values.getFirst(), Integer.MAX_VALUE);
    System.out.printf(
        "%s count=%d estimated_bytes=%d approximate_retained_bytes_per_value=%d%n",
        name, count, estimate, delta / count);
  }

  private static void collect() throws InterruptedException {
    System.gc();
    Thread.sleep(100);
    System.gc();
    Thread.sleep(100);
  }

  private static PageImpl<TerminalDto> page(int offset, int count) {
    List<TerminalDto> rows = new ArrayList<>(count);
    for (int row = 0; row < count; row++) {
      rows.add(CacheValueWeightTest.terminal(offset + row));
    }
    return new PageImpl<>(rows);
  }

  private static Map<String, Set<String>> index(int seed) {
    Map<String, Set<String>> families = new HashMap<>();
    for (int item = 0; item < 10000; item++) {
      families.put("family-" + seed + "-" + item, Set.of("product-" + seed + "-" + item));
    }
    return Map.copyOf(families);
  }

  private static void trace(boolean weighted) {
    var builder = Caffeine.newBuilder().recordStats();
    if (weighted) {
      builder
          .maximumWeight(16 * MEBIBYTE)
          .weigher((Object key, Object value) -> CacheValueWeight.weigh(key, value, 16 * MEBIBYTE));
    } else {
      builder.maximumSize(1000);
    }
    var cache = builder.build();
    var random = new Random(3267);
    for (int request = 0; request < 20000; request++) {
      int key = random.nextInt(10) == 0 ? random.nextInt(2000) : random.nextInt(20);
      cache.get(key, item -> page((Integer) item * 50, 50));
    }
    cache.cleanUp();
    long estimated =
        cache.asMap().values().stream()
            .mapToLong(value -> CacheValueWeight.weigh("fixture", value, Integer.MAX_VALUE))
            .sum();
    System.out.printf(
        "trace policy=%s hit_rate=%.5f retained_entries=%d estimated_retained_bytes=%d%n",
        weighted ? "weighted-16MiB" : "entries-1000",
        cache.stats().hitRate(),
        cache.estimatedSize(),
        estimated);
  }
}
