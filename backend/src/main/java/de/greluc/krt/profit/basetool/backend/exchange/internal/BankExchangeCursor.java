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
import de.greluc.krt.profit.basetool.backend.bank.api.BankStaffQueueReads;
import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeProblemException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The opaque snapshot cursors of the bank reads (REQ-BXCH-002): {@code a1.<accountId>} continues
 * the account list after that account, {@code b1.<epochSecond>.<nano>.<postingId>} the bookings
 * after that booking, {@code q1.<open>.<toApprove>} the two staff-queue sections, each after {@code
 * <epochSecond>.<nano>.<requestId>} or from the start ({@code s}). A value the server did not issue
 * answers {@code 410 CURSOR_EXPIRED}.
 */
final class BankExchangeCursor {

  private static final String UUID_PATTERN =
      "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})";

  private static final Pattern ACCOUNT = Pattern.compile("^a1\\." + UUID_PATTERN + "$");

  private static final Pattern BOOKING =
      Pattern.compile("^b1\\.(\\d{1,12})\\.(\\d{1,9})\\." + UUID_PATTERN + "$");

  private static final String QUEUE_KEY = "(s|(\\d{1,12})\\.(\\d{1,9})\\." + UUID_PATTERN + ")";

  private static final Pattern QUEUE =
      Pattern.compile("^q1\\." + QUEUE_KEY + "\\." + QUEUE_KEY + "$");

  /** The queue key of a section read from its start. */
  private static final String QUEUE_START = "s";

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

  /**
   * Writes the cursor after the last item of each staff-queue section.
   *
   * @param open the last {@code open} item delivered so far, or {@code null}
   * @param toApprove the last {@code toApprove} item delivered so far, or {@code null}
   * @return the opaque value
   */
  static @NotNull String afterQueue(
      @Nullable BankStaffQueueReads.QueueKey open,
      @Nullable BankStaffQueueReads.QueueKey toApprove) {
    return "q1." + queueKey(open) + "." + queueKey(toApprove);
  }

  /**
   * Reads a staff-queue cursor a client echoed.
   *
   * @param value the cursor
   * @return the last item delivered of each section
   * @throws ExchangeProblemException {@code 410 CURSOR_EXPIRED} for a value the server did not
   *     issue
   */
  static @NotNull QueuePosition parseQueue(@NotNull String value) {
    Matcher matcher = QUEUE.matcher(value);
    if (!matcher.matches()) {
      throw ExchangeProblemException.cursorExpired();
    }
    try {
      return new QueuePosition(queueKey(matcher, 1), queueKey(matcher, 5));
    } catch (DateTimeException _) {
      throw ExchangeProblemException.cursorExpired();
    }
  }

  /**
   * Writes one section's key.
   *
   * @param key the last item delivered, or {@code null}
   * @return its cursor part
   */
  private static @NotNull String queueKey(@Nullable BankStaffQueueReads.QueueKey key) {
    if (key == null) {
      return QUEUE_START;
    }
    Instant at = key.createdAt();
    return at.getEpochSecond() + "." + at.getNano() + "." + key.requestId();
  }

  /**
   * Reads one section's key from the matched groups.
   *
   * @param matcher the matched cursor
   * @param first the group of the whole key; the second, nano and id follow it
   * @return the key, or {@code null} for a section read from its start
   */
  private static @Nullable BankStaffQueueReads.QueueKey queueKey(
      @NotNull Matcher matcher, int first) {
    if (QUEUE_START.equals(matcher.group(first))) {
      return null;
    }
    Instant at =
        Instant.ofEpochSecond(
            Long.parseLong(matcher.group(first + 1)), Long.parseLong(matcher.group(first + 2)));
    return new BankStaffQueueReads.QueueKey(at, UUID.fromString(matcher.group(first + 3)));
  }

  /**
   * Where each staff-queue section continues.
   *
   * @param open the last {@code open} item delivered, or {@code null}
   * @param toApprove the last {@code toApprove} item delivered, or {@code null}
   */
  record QueuePosition(
      @Nullable BankStaffQueueReads.QueueKey open,
      @Nullable BankStaffQueueReads.QueueKey toApprove) {}
}
