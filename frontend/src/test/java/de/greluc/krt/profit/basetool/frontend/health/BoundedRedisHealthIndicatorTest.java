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
 * Unit tests for the wall-clock bound of {@link BoundedRedisHealthIndicator}: a never-completing
 * delegate is cut off as {@code DOWN} within the bound, and a healthy result passes through
 * (ADR-0114).
 */
class BoundedRedisHealthIndicatorTest {

  @Test
  void hangingDelegateIsCutOffAsDownWithinTheBound() {
    BoundedRedisHealthIndicator indicator =
        new BoundedRedisHealthIndicator(Mono::never, Duration.ofMillis(200));

    Instant start = Instant.now();
    Health health = indicator.health().block(Duration.ofSeconds(5));
    Duration elapsed = Duration.between(start, Instant.now());

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
    Health up = Health.up().withDetail("version", "7.4.0").build();
    BoundedRedisHealthIndicator indicator =
        new BoundedRedisHealthIndicator(() -> Mono.just(up), Duration.ofSeconds(1));

    Health health = indicator.health().block(Duration.ofSeconds(5));

    assertNotNull(health, "the bounded check must emit a health result");
    assertEquals(Status.UP, health.getStatus(), "a healthy delegate must stay UP");
    assertEquals("7.4.0", health.getDetails().get("version"), "delegate details must pass through");
  }
}
