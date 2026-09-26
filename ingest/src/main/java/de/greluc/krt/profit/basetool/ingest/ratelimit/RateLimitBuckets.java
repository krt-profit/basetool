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

package de.greluc.krt.profit.basetool.ingest.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jetbrains.annotations.NotNull;

/**
 * Factory for the token buckets and bounded bucket maps of the ingest rate limiters
 * (REQ-INGEST-005).
 *
 * <p>Bucket maps are synchronized, access-ordered LRU maps capped in size, so key rotation cannot
 * exhaust memory.
 */
public final class RateLimitBuckets {

  private RateLimitBuckets() {}

  /**
   * Builds a thread-safe, access-ordered LRU map of token buckets with a hard upper bound on the
   * number of tracked keys.
   *
   * @param maxEntries the maximum number of distinct keys tracked at once; the least-recently-used
   *     entry is evicted beyond it.
   * @return a synchronized bounded map suitable for {@code computeIfAbsent} bucket lookup.
   */
  public static Map<String, Bucket> boundedLru(int maxEntries) {
    return Collections.synchronizedMap(new LruBucketMap(maxEntries));
  }

  /**
   * Builds a greedy-refill token bucket, used by both ingest limiters.
   *
   * @param capacity the maximum burst the bucket admits; must be positive
   * @param refillTokens the tokens added back every {@code refillPeriod}; must be positive
   * @param refillPeriod the refill cadence
   * @return a fresh, full bucket
   */
  public static @NotNull Bucket newBucket(
      int capacity, int refillTokens, @NotNull Duration refillPeriod) {
    Bandwidth limit =
        Bandwidth.builder().capacity(capacity).refillGreedy(refillTokens, refillPeriod).build();
    return Bucket.builder().addLimit(limit).build();
  }

  /** Access-ordered {@link LinkedHashMap} that evicts its eldest entry past the configured cap. */
  private static final class LruBucketMap extends LinkedHashMap<String, Bucket> {

    private static final long serialVersionUID = 1L;
    private final int maxEntries;

    LruBucketMap(int maxEntries) {
      super(16, 0.75f, true);
      this.maxEntries = maxEntries;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
      return size() > maxEntries;
    }
  }
}
