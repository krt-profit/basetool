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
import de.greluc.krt.profit.basetool.frontend.model.dto.TerminalDto;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
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

  /** The newest sweep wins; terminals without a sweep and missing rows are skipped. */
  @Test
  void latestSync_returnsTheNewestSweep() {
    PageResponse<TerminalDto> terminals =
        new PageResponse<>(
            Arrays.asList(
                terminal(Instant.parse("2026-10-03T10:00:00Z")),
                terminal(Instant.parse("2026-10-03T11:30:00Z")),
                terminal(null),
                null),
            0,
            10,
            4,
            1,
            List.of());

    assertThat(UexAge.latestSync(terminals)).isEqualTo(Instant.parse("2026-10-03T11:30:00Z"));
    assertThat(UexAge.latestSync(null)).isNull();
    assertThat(UexAge.latestSync(new PageResponse<>(List.of(), 0, 10, 0, 0, List.of()))).isNull();
  }

  /**
   * Builds a terminal row carrying only a sweep instant.
   *
   * @param uexSyncedAt the sweep instant, or {@code null}
   * @return the terminal
   */
  private static TerminalDto terminal(Instant uexSyncedAt) {
    return new TerminalDto(
        UUID.fromString("5b0f6c2e-8f1a-4d3b-9c7e-2a4d6f8b1c3e"),
        "Area 18 TDD",
        null,
        "Stanton",
        null,
        null,
        null,
        null,
        null,
        false,
        false,
        null,
        null,
        uexSyncedAt,
        false);
  }
}
