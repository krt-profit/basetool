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
 * Factory for the bounded token-bucket maps the ingest rate limiters key on (REQ-INGEST-005).
 *
 * <p>An unbounded {@code ConcurrentHashMap} keyed on caller identity (source IP or JWT subject) is
 * itself a denial-of-service vector: an attacker who rotates the key on every request grows the map
 * without limit until the gateway exhausts heap (security audit INGEST-RATELIMIT-1). This factory
 * returns an access-ordered LRU map capped at {@code maxEntries}, so the least-recently-seen bucket
 * is evicted once the cap is reached and memory stays bounded regardless of key churn. The map is
 * wrapped in {@link Collections#synchronizedMap} so its {@code computeIfAbsent} is atomic under the
 * map's own monitor — the limiters call it concurrently from request threads.
 */
public final class RateLimitBuckets {

  private RateLimitBuckets() {
    // utility
  }

  /**
   * Builds a thread-safe, access-ordered LRU map of token buckets with a hard upper bound on the
   * number of tracked keys.
   *
   * @param maxEntries the maximum number of distinct keys (IPs / subjects) tracked at once; the
   *     least-recently-used entry is evicted when a new key would exceed this bound.
   * @return a synchronized bounded map suitable for {@code computeIfAbsent} bucket lookup.
   */
  public static Map<String, Bucket> boundedLru(int maxEntries) {
    return Collections.synchronizedMap(new LruBucketMap(maxEntries));
  }

  /**
   * Builds one greedy-refill token bucket — the single factory both ingest limiters use, so the
   * per-IP and the per-subject bucket cannot drift apart in shape and differ only in the budget
   * they are handed.
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
