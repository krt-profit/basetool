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

package de.greluc.krt.profit.basetool.backend.service.exchange;

import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeProblemException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A position in a resource's snapshot or change feed, opaque to clients (REQ-XCH-013).
 *
 * <p>{@code s1.<tx>.<seq>.<id>} continues a snapshot after the row {@code id}, taken at the feed
 * position {@code (tx, seq)}; {@code f1.<tx>.<seq>} is a feed position.
 *
 * @param position the feed position: the snapshot's, or the last change delivered
 * @param afterId the last snapshot row delivered, or {@code null} for a feed position
 */
public record ExchangeFeedCursor(@NotNull ExchangeFeedPosition position, @Nullable UUID afterId) {

  private static final String NUMBER = "(\\d{1,19})";

  private static final Pattern SNAPSHOT =
      Pattern.compile(
          "^s1\\."
              + NUMBER
              + "\\."
              + NUMBER
              + "\\.([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$");

  private static final Pattern FEED = Pattern.compile("^f1\\." + NUMBER + "\\." + NUMBER + "$");

  /**
   * A snapshot position.
   *
   * @param position the feed position the snapshot was taken at
   * @param afterId the last row delivered
   * @return the cursor
   */
  public static @NotNull ExchangeFeedCursor snapshot(
      @NotNull ExchangeFeedPosition position, @NotNull UUID afterId) {
    return new ExchangeFeedCursor(position, afterId);
  }

  /**
   * A feed position.
   *
   * @param position the last change delivered
   * @return the cursor
   */
  public static @NotNull ExchangeFeedCursor feed(@NotNull ExchangeFeedPosition position) {
    return new ExchangeFeedCursor(position, null);
  }

  /**
   * Reads a cursor a client echoed.
   *
   * @param value the cursor
   * @return the position
   * @throws ExchangeProblemException {@code 410 CURSOR_EXPIRED} for a value the server did not
   *     issue, so the client starts over with a snapshot
   */
  public static @NotNull ExchangeFeedCursor parse(@NotNull String value) {
    Matcher feed = FEED.matcher(value);
    if (feed.matches()) {
      return feed(position(feed.group(1), feed.group(2)));
    }
    Matcher snapshot = SNAPSHOT.matcher(value);
    if (snapshot.matches()) {
      return snapshot(
          position(snapshot.group(1), snapshot.group(2)), UUID.fromString(snapshot.group(3)));
    }
    throw ExchangeProblemException.cursorExpired();
  }

  /**
   * Whether this position continues a snapshot.
   *
   * @return {@code true} for a snapshot position
   */
  public boolean isSnapshot() {
    return afterId != null;
  }

  /**
   * Writes the cursor for a client.
   *
   * @return the opaque value
   */
  public @NotNull String format() {
    String at = position.tx() + "." + position.seq();
    return afterId == null ? "f1." + at : "s1." + at + "." + afterId;
  }

  /**
   * Parses a position whose numbers may overflow.
   *
   * @param tx the transaction id's digits
   * @param seq the sequence number's digits
   * @return the position
   * @throws ExchangeProblemException when a number overflows
   */
  private static @NotNull ExchangeFeedPosition position(@NotNull String tx, @NotNull String seq) {
    try {
      return new ExchangeFeedPosition(Long.parseLong(tx), Long.parseLong(seq));
    } catch (NumberFormatException e) {
      throw ExchangeProblemException.cursorExpired();
    }
  }
}
