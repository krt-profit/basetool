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

import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Age of the last UEX sweep in the coarsest unit that still reads naturally, for the trade pages'
 * freshness hint.
 *
 * @param syncedAt the instant of the last sweep
 * @param unit the message-key suffix: {@code now}, {@code minutes}, {@code hours} or {@code days}
 * @param amount the number of units; {@code 0} for {@code now}
 */
public record UexAge(@NotNull Instant syncedAt, @NotNull String unit, long amount) {

  /** Minutes in an hour. */
  private static final long MINUTES_PER_HOUR = 60;

  /** Hours below which the age is still told in hours rather than days. */
  private static final long HOURS_BEFORE_DAYS = 48;

  /**
   * The age of a sweep at {@code now}; a sweep stamped in the future counts as just now.
   *
   * @param syncedAt the sweep instant, or {@code null} when no sweep is known
   * @param now the reference instant
   * @return the age, or {@code null} when {@code syncedAt} is {@code null}
   */
  @Nullable
  @Contract("null, _ -> null; !null, _ -> !null")
  public static UexAge of(@Nullable Instant syncedAt, @NotNull Instant now) {
    if (syncedAt == null) {
      return null;
    }
    Duration age = Duration.between(syncedAt, now);
    if (age.isNegative()) {
      age = Duration.ZERO;
    }
    long minutes = age.toMinutes();
    if (minutes < 1) {
      return new UexAge(syncedAt, "now", 0);
    }
    if (minutes < MINUTES_PER_HOUR) {
      return new UexAge(syncedAt, "minutes", minutes);
    }
    long hours = age.toHours();
    if (hours < HOURS_BEFORE_DAYS) {
      return new UexAge(syncedAt, "hours", hours);
    }
    return new UexAge(syncedAt, "days", age.toDays());
  }

  /**
   * The newest {@code uexSyncedAt} of a terminal catalogue page; terminals are the first step of
   * every UEX sweep, so this is when the last sweep ran.
   *
   * @param terminals the terminal catalogue, or {@code null}
   * @return the newest sweep instant, or {@code null} when no terminal carries one
   */
  @Nullable
  public static Instant latestSync(@Nullable PageResponse<Map<String, Object>> terminals) {
    if (terminals == null || terminals.content() == null) {
      return null;
    }
    Instant latest = null;
    for (Map<String, Object> terminal : terminals.content()) {
      Instant synced = terminal == null ? null : parse(terminal.get("uexSyncedAt"));
      if (synced != null && (latest == null || synced.isAfter(latest))) {
        latest = synced;
      }
    }
    return latest;
  }

  /**
   * Reads an instant from a decoded JSON value.
   *
   * @param value an {@link Instant} or an ISO-8601 string; anything else yields {@code null}
   * @return the instant, or {@code null} when the value is absent or unreadable
   */
  @Nullable
  private static Instant parse(@Nullable Object value) {
    if (value instanceof Instant instant) {
      return instant;
    }
    if (!(value instanceof String text) || text.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(text.trim());
    } catch (DateTimeParseException _) {
      return null;
    }
  }
}
