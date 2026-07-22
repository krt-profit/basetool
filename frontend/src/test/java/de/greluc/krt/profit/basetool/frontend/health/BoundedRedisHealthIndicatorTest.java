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

package de.greluc.krt.profit.basetool.frontend.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import reactor.core.publisher.Mono;

/**
 * Unit guard for {@link BoundedRedisHealthIndicator}'s wall-clock bound (ADR-0114 follow-up). The
 * 2026-07-22 incident proved the Lettuce command timeout alone cannot bound the reactive health
 * {@code PING} — checks queued behind a wedged shared-connection acquisition for up to 836 seconds
 * on a build that already carried {@code spring.data.redis.timeout=2s}. These tests script the
 * delegate directly: a never-completing delegate must be cut off as {@code DOWN} within the bound,
 * and a healthy delegate's result must pass through untouched.
 */
class BoundedRedisHealthIndicatorTest {

  @Test
  void hangingDelegateIsCutOffAsDownWithinTheBound() {
    // Given: a delegate that never completes — the shape of the wedged Lettuce acquisition, which
    // no command timeout reaches.
    BoundedRedisHealthIndicator indicator =
        new BoundedRedisHealthIndicator(Mono::never, Duration.ofMillis(200));

    // When
    Instant start = Instant.now();
    Health health = indicator.health().block(Duration.ofSeconds(5));
    Duration elapsed = Duration.between(start, Instant.now());

    // Then: DOWN with the timeout detail, and well before the 5s Docker HEALTHCHECK budget the
    // bound exists to protect (a regression dropping the timeout operator would trip the block()
    // bound instead).
    assertNotNull(health, "the bounded check must emit a health result");
    assertEquals(Status.DOWN, health.getStatus(), "a timed-out PING must report DOWN");
    assertEquals(
        "health check timed out after 200 ms",
        health.getDetails().get("error"),
        "the DOWN health must carry the bound in its error detail");
    assertTrue(
        elapsed.compareTo(Duration.ofSeconds(2)) < 0,
        "the bound must cut the check off near the configured timeout, took " + elapsed);
  }

  @Test
  void healthyDelegatePassesThroughUntouched() {
    // Given: a delegate that answers promptly with its own detail.
    Health up = Health.up().withDetail("version", "7.4.0").build();
    BoundedRedisHealthIndicator indicator =
        new BoundedRedisHealthIndicator(() -> Mono.just(up), Duration.ofSeconds(1));

    // When
    Health health = indicator.health().block(Duration.ofSeconds(5));

    // Then: the delegate's health arrives unmodified — the bound is transparent on the happy path.
    assertNotNull(health, "the bounded check must emit a health result");
    assertEquals(Status.UP, health.getStatus(), "a healthy delegate must stay UP");
    assertEquals("7.4.0", health.getDetails().get("version"), "delegate details must pass through");
  }
}
