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

package de.greluc.krt.profit.basetool.frontend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests of {@link UexAge}: the unit chosen for an age and the newest sweep of a catalogue. */
class UexAgeTest {

  /** The reference instant of every case. */
  private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");

  /** Each age reads in the coarsest natural unit. */
  @Test
  void of_picksTheUnitByAge() {
    assertThat(UexAge.of(NOW.minusSeconds(30), NOW))
        .extracting(UexAge::unit, UexAge::amount)
        .containsExactly("now", 0L);
    assertThat(UexAge.of(NOW.minus(Duration.ofMinutes(14)), NOW))
        .extracting(UexAge::unit, UexAge::amount)
        .containsExactly("minutes", 14L);
    assertThat(UexAge.of(NOW.minus(Duration.ofHours(5)), NOW))
        .extracting(UexAge::unit, UexAge::amount)
        .containsExactly("hours", 5L);
    assertThat(UexAge.of(NOW.minus(Duration.ofHours(47)), NOW))
        .extracting(UexAge::unit, UexAge::amount)
        .containsExactly("hours", 47L);
    assertThat(UexAge.of(NOW.minus(Duration.ofDays(3)), NOW))
        .extracting(UexAge::unit, UexAge::amount)
        .containsExactly("days", 3L);
  }

  /** No sweep yields no age, and a sweep stamped in the future counts as just now. */
  @Test
  void of_handlesMissingAndFutureSweeps() {
    assertThat(UexAge.of(null, NOW)).isNull();
    assertThat(UexAge.of(NOW.plusSeconds(600), NOW)).extracting(UexAge::unit).isEqualTo("now");
  }

  /** The newest readable sweep wins; unreadable and missing values are skipped. */
  @Test
  void latestSync_returnsTheNewestReadableSweep() {
    Map<String, Object> missing = new HashMap<>();
    missing.put("uexSyncedAt", null);
    PageResponse<Map<String, Object>> terminals =
        new PageResponse<>(
            List.of(
                Map.of("uexSyncedAt", "2026-10-03T10:00:00Z"),
                Map.of("uexSyncedAt", "2026-10-03T11:30:00Z"),
                Map.of("uexSyncedAt", "not a date"),
                missing),
            0,
            10,
            4,
            1,
            List.of());

    assertThat(UexAge.latestSync(terminals)).isEqualTo(Instant.parse("2026-10-03T11:30:00Z"));
    assertThat(UexAge.latestSync(null)).isNull();
    assertThat(UexAge.latestSync(new PageResponse<>(List.of(), 0, 10, 0, 0, List.of()))).isNull();
  }
}
