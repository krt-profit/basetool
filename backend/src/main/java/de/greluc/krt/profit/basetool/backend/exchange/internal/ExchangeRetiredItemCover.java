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

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * The falls of P4K-retired items a stock change set offers to cover its rises of the same items: a
 * rise they cover is a move between lots and applies, any other rise of a retired item is refused
 * {@code ITEM_RETIRED} (REQ-P4K-013, REQ-XCH-016).
 *
 * <p>Every fall of a batch counts from the start, whatever its position, so a move works in either
 * order; a fall the plan then refuses gives its amount back, and a rise the plan applies uses its
 * amount up.
 */
final class ExchangeRetiredItemCover {

  private final Map<UUID, BigDecimal> left = new HashMap<>();

  /**
   * Counts the fall an op claims, its expected quantity above its target; a rise claims none.
   *
   * @param item the retired item
   * @param expected the op's rounded expected quantity
   * @param target the op's rounded target quantity
   */
  void offer(@NotNull UUID item, @NotNull BigDecimal expected, @NotNull BigDecimal target) {
    BigDecimal fall = expected.subtract(target);
    if (fall.signum() > 0) {
      left.merge(item, fall, BigDecimal::add);
    }
  }

  /**
   * Whether the falls still unused cover a rise.
   *
   * @param item the retired item
   * @param rise the amount the rise books in
   * @return {@code true} when the rise is a move and may apply
   */
  boolean covers(@NotNull UUID item, @NotNull BigDecimal rise) {
    return left.getOrDefault(item, BigDecimal.ZERO).compareTo(rise) >= 0;
  }

  /**
   * Books a decided op against the cover: an applied rise uses its amount up, a refused fall gives
   * its claimed amount back. An applied op found its lot at {@code expected}.
   *
   * @param item the retired item
   * @param applied whether the plan writes the op
   * @param expected the op's rounded expected quantity
   * @param target the op's rounded target quantity
   */
  void settle(
      @NotNull UUID item,
      boolean applied,
      @NotNull BigDecimal expected,
      @NotNull BigDecimal target) {
    BigDecimal rise = target.subtract(expected);
    if (applied ? rise.signum() > 0 : rise.signum() < 0) {
      left.merge(item, applied ? rise.negate() : rise, BigDecimal::add);
    }
  }
}
