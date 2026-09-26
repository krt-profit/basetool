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

import de.greluc.krt.profit.basetool.frontend.model.dto.BankBalancePointDto;
import java.math.BigDecimal;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Scales an account's balance-over-time series (REQ-BANK-049) into the geometry of the detail
 * page's {@value #VIEW_W}&times;{@value #VIEW_H} SVG chart: balance line, area fill, min/max
 * gridlines and an optional dashed target line. Pure coordinate computation with no styling or
 * text, shared by the bank-staff and org-unit detail pages.
 */
public final class BankBalanceChart {

  /** SVG viewBox width in user units. */
  public static final double VIEW_W = 720d;

  /** SVG viewBox height in user units. */
  public static final double VIEW_H = 260d;

  /** Left plot edge — leaves a gutter for the right-aligned y-axis balance labels. */
  private static final double PLOT_L = 74d;

  /** Right plot edge. */
  private static final double PLOT_R = 708d;

  /** Top plot edge (padding above the highest value). */
  private static final double PLOT_T = 16d;

  /** Bottom plot edge (padding below the lowest value; x-axis dates are HTML, not SVG). */
  private static final double PLOT_B = 244d;

  /** X position of the right-aligned y-axis balance labels. */
  private static final double AXIS_LABEL_X = PLOT_L - 8d;

  /** Fraction of the value range added as head/foot room so the line never touches the edge. */
  private static final double RANGE_PAD = 0.08d;

  /** Utility class — not instantiable. */
  private BankBalanceChart() {}

  /**
   * The scaled chart geometry in {@value #VIEW_W}&times;{@value #VIEW_H} viewBox space; when {@link
   * #empty()} is {@code true} every other field is {@code null} or zero.
   *
   * @param empty {@code true} when the series has no points
   * @param linePoints the balance polyline {@code points} value, or {@code null} when empty
   * @param areaPoints the filled-area polygon {@code points}, or {@code null} when empty
   * @param targetY the y of the target line, or {@code null} when no target is set
   * @param maxValue the highest balance, or {@code null} when empty
   * @param maxLineY the y of the top gridline
   * @param minValue the lowest balance, or {@code null} when empty
   * @param minLineY the y of the bottom gridline
   * @param target the balance-target value, or {@code null} when no target is set
   * @param plotLeft the left plot edge x
   * @param plotRight the right plot edge x
   * @param axisLabelX the x of the right-aligned y-axis labels
   */
  public record Chart(
      boolean empty,
      @Nullable String linePoints,
      @Nullable String areaPoints,
      @Nullable Double targetY,
      @Nullable BigDecimal maxValue,
      double maxLineY,
      @Nullable BigDecimal minValue,
      double minLineY,
      @Nullable BigDecimal target,
      double plotLeft,
      double plotRight,
      double axisLabelX) {}

  /**
   * Scales a balance series (and its optional target) into the chart geometry.
   *
   * @param points the end-of-bucket balance points, oldest first; {@code null} or empty yields the
   *     empty-state {@link Chart}
   * @param target the account's balance target, folded into the value range so its dashed line is
   *     always visible; {@code null} when no target is set
   * @return the scaled chart geometry
   */
  @NotNull
  public static Chart of(@Nullable List<BankBalancePointDto> points, @Nullable BigDecimal target) {
    if (points == null || points.isEmpty()) {
      return new Chart(
          true, null, null, null, null, 0d, null, 0d, null, PLOT_L, PLOT_R, AXIS_LABEL_X);
    }
    BigDecimal min = points.getFirst().balance();
    BigDecimal max = min;
    for (BankBalancePointDto point : points) {
      if (point.balance().compareTo(min) < 0) {
        min = point.balance();
      }
      if (point.balance().compareTo(max) > 0) {
        max = point.balance();
      }
    }
    BigDecimal loValue = target != null && target.compareTo(min) < 0 ? target : min;
    BigDecimal hiValue = target != null && target.compareTo(max) > 0 ? target : max;
    double loBound = loValue.doubleValue();
    double hiBound = hiValue.doubleValue();
    double valueRange = hiBound - loBound;
    double pad =
        valueRange == 0 ? Math.max(Math.abs(hiBound) * RANGE_PAD, 1d) : valueRange * RANGE_PAD;
    double lo = loBound - pad;
    double span = (hiBound + pad) - lo;
    double plotW = PLOT_R - PLOT_L;
    double plotH = PLOT_B - PLOT_T;
    int n = points.size();
    StringBuilder line = new StringBuilder();
    double firstX = PLOT_L;
    double lastX = PLOT_R;
    for (int i = 0; i < n; i++) {
      double x = n == 1 ? PLOT_L + plotW / 2 : PLOT_L + (double) i / (n - 1) * plotW;
      double y = PLOT_B - (points.get(i).balance().doubleValue() - lo) / span * plotH;
      if (i > 0) {
        line.append(' ');
      }
      line.append(round1(x)).append(',').append(round1(y));
      if (i == 0) {
        firstX = x;
      }
      if (i == n - 1) {
        lastX = x;
      }
    }
    String area =
        line
            + " "
            + round1(lastX)
            + ','
            + round1(PLOT_B)
            + ' '
            + round1(firstX)
            + ','
            + round1(PLOT_B);
    Double targetY =
        target == null ? null : round1(PLOT_B - (target.doubleValue() - lo) / span * plotH);
    double maxLineY = round1(PLOT_B - (max.doubleValue() - lo) / span * plotH);
    double minLineY = round1(PLOT_B - (min.doubleValue() - lo) / span * plotH);
    return new Chart(
        false,
        line.toString(),
        area,
        targetY,
        max,
        maxLineY,
        min,
        minLineY,
        target,
        PLOT_L,
        PLOT_R,
        AXIS_LABEL_X);
  }

  /**
   * Rounds a coordinate to one decimal place to keep the emitted SVG attribute compact.
   *
   * @param value the raw coordinate
   * @return the coordinate rounded to one decimal
   */
  private static double round1(double value) {
    return Math.round(value * 10) / 10.0;
  }
}
