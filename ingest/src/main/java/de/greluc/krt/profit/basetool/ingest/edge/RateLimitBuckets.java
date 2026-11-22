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

package de.greluc.krt.profit.basetool.ingest.edge;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.jetbrains.annotations.NotNull;

/**
 * Factory for the token buckets and bounded bucket maps of the ingest rate limiters
 * (REQ-INGEST-005).
 *
 * <p>Bucket maps are synchronized, access-ordered LRU maps capped in size, so key rotation cannot
 * exhaust memory, and a key idle for {@link #IDLE_EXPIRY} is dropped, so no IP address or member
 * key is held longer than the privacy policy states.
 */
public final class RateLimitBuckets {

  /** How long a key may stay unused before its bucket is dropped. */
  public static final Duration IDLE_EXPIRY = Duration.ofHours(1);

  private RateLimitBuckets() {}

  /**
   * Builds a thread-safe, access-ordered LRU map of token buckets with a hard upper bound on the
   * number of tracked keys and an idle expiry of {@link #IDLE_EXPIRY}.
   *
   * @param maxEntries the maximum number of distinct keys tracked at once; the least-recently-used
   *     entry is evicted beyond it.
   * @return a synchronized bounded map suitable for {@code computeIfAbsent} bucket lookup.
   */
  public static Map<String, Bucket> boundedLru(int maxEntries) {
    return boundedLru(maxEntries, IDLE_EXPIRY, System::nanoTime);
  }

  /**
   * Builds the bounded map with an explicit idle expiry and clock.
   *
   * @param maxEntries the maximum number of distinct keys tracked at once
   * @param idleExpiry how long a key may stay unused before it is dropped
   * @param nanoClock the monotonic clock in nanoseconds
   * @return a synchronized bounded map suitable for {@code computeIfAbsent} bucket lookup.
   */
  static Map<String, Bucket> boundedLru(
      int maxEntries, @NotNull Duration idleExpiry, @NotNull LongSupplier nanoClock) {
    return Collections.synchronizedMap(
        new LruBucketMap(maxEntries, idleExpiry.toNanos(), nanoClock));
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

  /**
   * Access-ordered {@link LinkedHashMap} that evicts its eldest entry past the configured cap and
   * drops entries idle longer than the expiry before every lookup.
   */
  private static final class LruBucketMap extends LinkedHashMap<String, Bucket> {

    private static final long serialVersionUID = 1L;
    private final int maxEntries;
    private final long idleNanos;
    private final transient LongSupplier nanoClock;
    private final HashMap<String, Long> lastAccess = new HashMap<>();

    LruBucketMap(int maxEntries, long idleNanos, LongSupplier nanoClock) {
      super(16, 0.75f, true);
      this.maxEntries = maxEntries;
      this.idleNanos = idleNanos;
      this.nanoClock = nanoClock;
    }

    @Override
    public Bucket get(Object key) {
      Bucket bucket = super.get(key);
      if (bucket != null && key instanceof String name) {
        lastAccess.put(name, nanoClock.getAsLong());
      }
      return bucket;
    }

    @Override
    public Bucket computeIfAbsent(
        String key, Function<? super String, ? extends Bucket> mappingFunction) {
      long now = nanoClock.getAsLong();
      dropIdle(now);
      Bucket bucket = super.computeIfAbsent(key, mappingFunction);
      lastAccess.put(key, now);
      return bucket;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
      boolean evict = size() > maxEntries;
      if (evict) {
        lastAccess.remove(eldest.getKey());
      }
      return evict;
    }

    private void dropIdle(long now) {
      Iterator<Map.Entry<String, Bucket>> entries = entrySet().iterator();
      while (entries.hasNext()) {
        String key = entries.next().getKey();
        Long seen = lastAccess.get(key);
        if (seen != null && now - seen < idleNanos) {
          return;
        }
        entries.remove();
        lastAccess.remove(key);
      }
    }
  }
}
