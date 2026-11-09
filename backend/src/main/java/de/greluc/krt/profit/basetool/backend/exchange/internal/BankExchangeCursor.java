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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import de.greluc.krt.profit.basetool.backend.bank.api.BankAccountReads;
import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeProblemException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;

/**
 * The opaque snapshot cursors of the bank reads (REQ-BXCH-002): {@code a1.<accountId>} continues
 * the account list after that account, {@code b1.<epochSecond>.<nano>.<postingId>} the bookings
 * after that booking. A value the server did not issue answers {@code 410 CURSOR_EXPIRED}.
 */
final class BankExchangeCursor {

  private static final String UUID_PATTERN =
      "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})";

  private static final Pattern ACCOUNT = Pattern.compile("^a1\\." + UUID_PATTERN + "$");

  private static final Pattern BOOKING =
      Pattern.compile("^b1\\.(\\d{1,12})\\.(\\d{1,9})\\." + UUID_PATTERN + "$");

  /** Not instantiable. */
  private BankExchangeCursor() {}

  /**
   * Writes the cursor after an account.
   *
   * @param accountId the last account delivered
   * @return the opaque value
   */
  static @NotNull String afterAccount(@NotNull UUID accountId) {
    return "a1." + accountId;
  }

  /**
   * Reads an account cursor a client echoed.
   *
   * @param value the cursor
   * @return the last account delivered
   * @throws ExchangeProblemException {@code 410 CURSOR_EXPIRED} for a value the server did not
   *     issue
   */
  static @NotNull UUID parseAccount(@NotNull String value) {
    Matcher matcher = ACCOUNT.matcher(value);
    if (!matcher.matches()) {
      throw ExchangeProblemException.cursorExpired();
    }
    return UUID.fromString(matcher.group(1));
  }

  /**
   * Writes the cursor after a booking.
   *
   * @param key the last booking delivered
   * @return the opaque value
   */
  static @NotNull String afterBooking(@NotNull BankAccountReads.BookingKey key) {
    Instant at = key.createdAt();
    return "b1." + at.getEpochSecond() + "." + at.getNano() + "." + key.postingId();
  }

  /**
   * Reads a booking cursor a client echoed.
   *
   * @param value the cursor
   * @return the last booking delivered
   * @throws ExchangeProblemException {@code 410 CURSOR_EXPIRED} for a value the server did not
   *     issue
   */
  static @NotNull BankAccountReads.BookingKey parseBooking(@NotNull String value) {
    Matcher matcher = BOOKING.matcher(value);
    if (!matcher.matches()) {
      throw ExchangeProblemException.cursorExpired();
    }
    try {
      Instant at =
          Instant.ofEpochSecond(Long.parseLong(matcher.group(1)), Long.parseLong(matcher.group(2)));
      return new BankAccountReads.BookingKey(at, UUID.fromString(matcher.group(3)));
    } catch (DateTimeException _) {
      throw ExchangeProblemException.cursorExpired();
    }
  }
}
