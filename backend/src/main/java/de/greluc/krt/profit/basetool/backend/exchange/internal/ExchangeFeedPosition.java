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

import java.util.Comparator;
import org.jetbrains.annotations.NotNull;

/**
 * A position in the change feed: the writing transaction's id, then the sequence number within it
 * (REQ-XCH-013, ADR-0224).
 *
 * <p>Only entries of finished transactions are read, so no entry can later appear before a position
 * a reader has passed.
 *
 * @param tx the writing transaction's id
 * @param seq the sequence number
 */
public record ExchangeFeedPosition(long tx, long seq) implements Comparable<ExchangeFeedPosition> {

  /** The position before every entry. */
  public static final ExchangeFeedPosition START = new ExchangeFeedPosition(0, 0);

  private static final Comparator<ExchangeFeedPosition> ORDER =
      Comparator.comparingLong(ExchangeFeedPosition::tx)
          .thenComparingLong(ExchangeFeedPosition::seq);

  @Override
  public int compareTo(@NotNull ExchangeFeedPosition other) {
    return ORDER.compare(this, other);
  }

  /**
   * Whether this position lies before another.
   *
   * @param other the other position
   * @return {@code true} when this one sorts first
   */
  public boolean isBefore(@NotNull ExchangeFeedPosition other) {
    return compareTo(other) < 0;
  }
}
