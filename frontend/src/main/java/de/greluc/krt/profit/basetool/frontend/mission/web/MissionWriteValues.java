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

package de.greluc.krt.profit.basetool.frontend.mission.web;

import de.greluc.krt.profit.basetool.logging.LogSafe;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Parses the JSON and datetime values of mission write adapters. */
@Slf4j
final class MissionWriteValues {

  private MissionWriteValues() {}

  private static final ZoneId MISSION_TIME_ZONE = ZoneId.of("Europe/Berlin");

  /**
   * Reads a browser JSON value as a UUID.
   *
   * @param raw the value, or {@code null}
   * @return the UUID, or {@code null} when the value is absent or blank
   * @throws IllegalArgumentException when the value is not a UUID
   */
  @Nullable
  static UUID uuidOrNull(@Nullable Object raw) {
    if (raw == null || String.valueOf(raw).isBlank()) {
      return null;
    }
    return UUID.fromString(String.valueOf(raw).trim());
  }

  /**
   * Reads a browser JSON value as text.
   *
   * @param raw the value, or {@code null}
   * @return the text, or {@code null} when the value is absent or blank
   */
  @Nullable
  static String textOrNull(@Nullable Object raw) {
    if (raw == null || String.valueOf(raw).isBlank()) {
      return null;
    }
    return String.valueOf(raw);
  }

  /**
   * Reads a browser JSON value as an optimistic-lock version.
   *
   * @param raw the value, or {@code null}
   * @return the version, or {@code 0} when the value is absent
   * @throws NumberFormatException when the value is neither a number nor numeric text
   */
  @NotNull
  static Long versionOrZero(@Nullable Object raw) {
    if (raw == null) {
      return 0L;
    }
    if (raw instanceof Number number) {
      return number.longValue();
    }
    return Long.valueOf(String.valueOf(raw).trim());
  }

  /**
   * Parses a hidden datetime-input value into an {@link Instant}.
   *
   * <p>Accepts a zone- or offset-bearing value, a zoneless local datetime of any fractional-second
   * precision, or a bare date; zoneless values are interpreted in {@link #MISSION_TIME_ZONE}.
   *
   * @param dateTimeStr the hidden input value; {@code null}/blank yields {@code null}
   * @return the parsed instant, or {@code null} if the value is blank or unparseable
   */
  @Nullable
  static Instant parseToInstant(String dateTimeStr) {
    if (dateTimeStr == null || dateTimeStr.isBlank()) {
      return null;
    }
    final String value = dateTimeStr.trim();
    try {
      if (value.length() == 10) {
        return LocalDate.parse(value).atStartOfDay(MISSION_TIME_ZONE).toInstant();
      }
      TemporalAccessor parsed =
          DateTimeFormatter.ISO_DATE_TIME.parseBest(
              value, OffsetDateTime::from, LocalDateTime::from);
      return parsed instanceof OffsetDateTime odt
          ? odt.toInstant()
          : ((LocalDateTime) parsed).atZone(MISSION_TIME_ZONE).toInstant();
    } catch (java.time.DateTimeException e) {
      log.warn("Failed to parse datetime string: {}", LogSafe.text(value, 64), e);
      return null;
    }
  }
}
