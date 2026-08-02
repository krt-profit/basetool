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

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the bound on the rate-limit bucket maps (security audit INGEST-RATELIMIT-1). An unbounded
 * map keyed on caller identity is itself a DoS vector: an attacker rotating the key on every
 * request would grow it until the gateway exhausts heap. These tests assert both halves of the
 * mitigation — the hard cap, and the access-ordering that decides <em>which</em> entry is dropped.
 */
class RateLimitBucketsTest {

  private static Bucket bucket() {
    return Bucket.builder()
        .addLimit(Bandwidth.builder().capacity(1).refillGreedy(1, Duration.ofMinutes(1)).build())
        .build();
  }

  @Test
  void neverGrowsBeyondTheConfiguredCap() {
    Map<String, Bucket> buckets = RateLimitBuckets.boundedLru(10);

    for (int i = 0; i < 1_000; i++) {
      buckets.computeIfAbsent("ip-" + i, key -> bucket());
    }

    assertThat(buckets).hasSize(10);
  }

  @Test
  void evictsTheLeastRecentlyUsedKeyRatherThanTheOldestInsert() {
    // Access ordering is what keeps a steadily-active caller's bucket alive while a flood of
    // one-shot keys churns through the map.
    Map<String, Bucket> buckets = RateLimitBuckets.boundedLru(2);
    buckets.computeIfAbsent("steady", key -> bucket());
    buckets.computeIfAbsent("other", key -> bucket());

    // Touch "steady" so "other" becomes the eldest by access order, then force one eviction.
    buckets.get("steady");
    buckets.computeIfAbsent("newcomer", key -> bucket());

    assertThat(buckets).containsKeys("steady", "newcomer").doesNotContainKey("other");
  }

  @Test
  void reusesTheBucketForAKnownKey() {
    Map<String, Bucket> buckets = RateLimitBuckets.boundedLru(10);

    Bucket first = buckets.computeIfAbsent("ip-1", key -> bucket());
    Bucket second = buckets.computeIfAbsent("ip-1", key -> bucket());

    assertThat(second).isSameAs(first);
  }
}
