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

package de.greluc.krt.profit.basetool.frontend.view;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Template bean ({@code @relativeDays}) that counts calendar days between today and an instant in
 * the Europe/Berlin zone, for the relative date line of a list row (REQ-UI-027).
 */
@Component("relativeDays")
@RequiredArgsConstructor(access = lombok.AccessLevel.PACKAGE)
public class RelativeDays {

  /** The zone the squadron plans in; the list's absolute times are shown in it as well. */
  private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

  /** The clock "today" is read from. */
  @NotNull private final Clock clock;

  /** Creates the bean on the system clock. */
  @Autowired
  public RelativeDays() {
    this(Clock.systemUTC());
  }

  /**
   * Calendar days from today to the given instant's day: positive in the future, negative in the
   * past, {@code 0} today.
   *
   * @param instant the instant to compare; {@code null} yields {@code null}
   * @return the signed number of days, or {@code null} for a {@code null} instant
   */
  @Nullable
  @Contract("null -> null; !null -> !null")
  public Long days(@Nullable Instant instant) {
    if (instant == null) {
      return null;
    }
    LocalDate today = LocalDate.now(clock.withZone(ZONE));
    LocalDate day = instant.atZone(ZONE).toLocalDate();
    return ChronoUnit.DAYS.between(today, day);
  }
}
