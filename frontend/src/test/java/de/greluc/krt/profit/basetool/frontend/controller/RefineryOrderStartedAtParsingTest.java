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
 * Regressionstest fuer den Bugfix der doppelten Sommerzeit-Umrechnung in Raffinerieauftraegen. Der
 * Parser MUSS ISO-Instants (UTC mit 'Z'), ISO-Offsets und reine Datumseingaben deterministisch als
 * UTC-Instant liefern und DARF keine {@code ZoneId.systemDefault()}-Semantik verwenden.
 */
class RefineryOrderStartedAtParsingTest {

  @Test
  void shouldParseIsoInstantWithZAsUtc() {
    // Given: 16:30 Europe/Berlin Sommerzeit == 14:30:00Z
    String input = "2026-04-19T14:30:00Z";

    // When
    Instant parsed = RefineryOrderWriteController.parseStartedAt(input);

    // Then
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
    // Given: Sommerzeit Europe/Berlin (+02:00), User-Eingabe 16:30 lokal
    String input = "2026-04-19T16:30:00+02:00";

    Instant parsed = RefineryOrderWriteController.parseStartedAt(input);

    assertEquals(Instant.parse("2026-04-19T14:30:00Z"), parsed);
    assertEquals(OffsetDateTime.parse(input).toInstant(), parsed);
  }

  @Test
  void shouldParseIsoOffsetDateTimeWinterTime() {
    // Given: Winterzeit Europe/Berlin (+01:00), User-Eingabe 16:30 lokal
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
    // Defensiver Fallback: Ein LocalDateTime ohne Zone darf KEINE
    // System-Default-Zone anwenden (frueherer Bug: ZoneId.systemDefault()),
    // da dies zu doppelter DST-Umrechnung fuehrt.
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
    // Given: Ein Instant, als ISO-String serialisiert
    Instant original =
        OffsetDateTime.of(2026, 4, 19, 16, 30, 0, 0, ZoneOffset.ofHours(2)).toInstant();

    // When: durch den Parser geschickt
    Instant roundTripped = RefineryOrderWriteController.parseStartedAt(original.toString());

    // Then: exakt derselbe Instant
    assertEquals(original, roundTripped);
  }
}
