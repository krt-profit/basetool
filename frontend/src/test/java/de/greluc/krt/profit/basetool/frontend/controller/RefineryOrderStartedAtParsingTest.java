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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Tests that the refinery-order start-time parser reads ISO instants, ISO offsets and plain dates
 * deterministically as UTC, independent of the system default zone.
 */
class RefineryOrderStartedAtParsingTest {

  @Test
  void shouldParseIsoInstantWithZAsUtc() {
    String input = "2026-04-19T14:30:00Z";

    Instant parsed = RefineryOrderWriteController.parseStartedAt(input);

    assertEquals(Instant.parse("2026-04-19T14:30:00Z"), parsed);
  }

  @Test
  void shouldParseIsoInstantWithMillis() {
    String input = "2026-04-19T14:30:00.000Z";

    Instant parsed = RefineryOrderWriteController.parseStartedAt(input);

    assertEquals(Instant.parse("2026-04-19T14:30:00Z"), parsed);
  }

  @Test
  void shouldParseIsoOffsetDateTimeSummerTime() {
    String input = "2026-04-19T16:30:00+02:00";

    Instant parsed = RefineryOrderWriteController.parseStartedAt(input);

    assertEquals(Instant.parse("2026-04-19T14:30:00Z"), parsed);
    assertEquals(OffsetDateTime.parse(input).toInstant(), parsed);
  }

  @Test
  void shouldParseIsoOffsetDateTimeWinterTime() {
    String input = "2026-11-15T16:30:00+01:00";

    Instant parsed = RefineryOrderWriteController.parseStartedAt(input);

    assertEquals(Instant.parse("2026-11-15T15:30:00Z"), parsed);
  }

  @Test
  void shouldParseDateOnlyAsUtcStartOfDay() {
    String input = "2026-04-19";

    Instant parsed = RefineryOrderWriteController.parseStartedAt(input);

    assertEquals(Instant.parse("2026-04-19T00:00:00Z"), parsed);
  }

  @Test
  void shouldTreatLocalDateTimeWithoutZoneAsUtcDefensively() {
    String input = "2026-04-19T14:30";

    Instant parsed = RefineryOrderWriteController.parseStartedAt(input);

    assertEquals(Instant.parse("2026-04-19T14:30:00Z"), parsed);
  }

  @Test
  void shouldReturnNowForNullOrBlank() {
    Instant parsedNull = RefineryOrderWriteController.parseStartedAt(null);
    Instant parsedBlank = RefineryOrderWriteController.parseStartedAt("   ");

    assertNotNull(parsedNull);
    assertNotNull(parsedBlank);
  }

  @Test
  void shouldBeIdempotentAcrossRoundTrip() {
    Instant original =
        OffsetDateTime.of(2026, 4, 19, 16, 30, 0, 0, ZoneOffset.ofHours(2)).toInstant();

    Instant roundTripped = RefineryOrderWriteController.parseStartedAt(original.toString());

    assertEquals(original, roundTripped);
  }
}
