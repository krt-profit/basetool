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

import java.math.BigDecimal;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Scales a backend 30-day end-of-day balance series into the inline SVG sparkline the bank surfaces
 * render (D1 mockup, REQ-BANK-016). Shared by the bank dashboard ({@code BankPageController}) and
 * the org-unit officer/lead balance page ({@code OrgUnitBankPageController}) so both draw the trend
 * identically — same 96×26 viewBox, same padding, same flat-series fallback.
 */
public final class BankSparkline {

  /** ViewBox width of the sparkline (matches the D1 mockup's 96×26 SVG). */
  private static final double SPARK_WIDTH = 96d;

  /** ViewBox height of the sparkline. */
  private static final double SPARK_HEIGHT = 26d;

  /** Vertical padding inside the sparkline viewBox so extremes do not clip. */
  private static final double SPARK_PAD = 2d;

  /** Utility class — not instantiable. */
  private BankSparkline() {}

  /**
   * One scaled sparkline ready for the template.
   *
   * @param points SVG polyline {@code points} attribute value scaled to the 96×26 viewBox, or
   *     {@code null} when the series is empty (the template then omits the {@code <polyline>})
   * @param flat {@code true} when the 30-day series never changes (renders the muted flat line)
   */
  public record Spark(@Nullable String points, boolean flat) {}

  /**
   * Scales an end-of-day balance series into the 96×26 sparkline polyline. A flat series renders as
   * a mid-height line; an empty or {@code null} series yields {@code points == null} and {@code
   * flat == true}, so the caller omits the SVG.
   *
   * @param series the end-of-day balances, oldest first; may be {@code null} or empty
   * @return the scaled polyline points and the flat flag
   */
  @NotNull
  public static Spark of(@Nullable List<BigDecimal> series) {
    if (series == null || series.isEmpty()) {
      return new Spark(null, true);
    }
    BigDecimal min = series.getFirst();
    BigDecimal max = series.getFirst();
    for (BigDecimal v : series) {
      if (v.compareTo(min) < 0) {
        min = v;
      }
      if (v.compareTo(max) > 0) {
        max = v;
      }
    }
    boolean flat = max.compareTo(min) == 0;
    double range = flat ? 1d : max.subtract(min).doubleValue();
    double stepX = series.size() > 1 ? SPARK_WIDTH / (series.size() - 1) : SPARK_WIDTH;
    StringBuilder points = new StringBuilder();
    for (int i = 0; i < series.size(); i++) {
      double x = series.size() > 1 ? i * stepX : SPARK_WIDTH / 2;
      double normalized = flat ? 0.5d : series.get(i).subtract(min).doubleValue() / range;
      double y = SPARK_HEIGHT - SPARK_PAD - normalized * (SPARK_HEIGHT - 2 * SPARK_PAD);
      if (i > 0) {
        points.append(' ');
      }
      points.append(Math.round(x * 10) / 10.0).append(',').append(Math.round(y * 10) / 10.0);
    }
    return new Spark(points.toString(), flat);
  }
}
