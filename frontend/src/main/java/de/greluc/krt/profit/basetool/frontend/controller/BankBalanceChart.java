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
 * Scales an account balance-over-time series (REQ-BANK-049) into the geometry the detail page's
 * inline SVG line chart renders — the bigger sibling of {@link BankSparkline}. Where the sparkline
 * is a bare 96&times;26 polyline, this produces a full {@value #VIEW_W}&times;{@value #VIEW_H}
 * chart: the balance polyline, a soft area fill under it, two horizontal gridlines at the data
 * extremes (labelled with the min/max balance) and — when the account has a balance target — a
 * dashed reference line at the target level. Every colour comes from a design-system token via CSS
 * classes on the template's SVG elements; this helper only computes coordinates, so it holds no
 * styling and no user-visible string.
 *
 * <p>Pure computation, mirroring {@link BankSparkline}: shared by the bank-staff detail ({@code
 * BankPageController}) and the org-unit viewer detail ({@code OrgUnitBankPageController}) so both
 * draw the chart identically. The x-axis date range is rendered as localisable HTML around the SVG,
 * never as SVG text, so nothing here needs the user's time zone.
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
   * The scaled chart geometry ready for the template. All coordinates are in the {@value #VIEW_W}
   * &times;{@value #VIEW_H} viewBox space. When {@link #empty()} is {@code true} the template
   * renders the "no data" state instead of the SVG and every other field is {@code null}/zero.
   *
   * @param empty {@code true} when the series has no points (render the empty state)
   * @param linePoints the balance polyline {@code points} attribute value, or {@code null} when
   *     empty
   * @param areaPoints the filled-area polygon {@code points} (line closed down to the baseline), or
   *     {@code null} when empty
   * @param targetY the y of the dashed balance-target line, or {@code null} when no target is set
   * @param maxValue the highest balance in the series (top gridline label), or {@code null} when
   *     empty
   * @param maxLineY the y of the top gridline (the {@code maxValue} level)
   * @param minValue the lowest balance in the series (bottom gridline label), or {@code null} when
   *     empty
   * @param minLineY the y of the bottom gridline (the {@code minValue} level)
   * @param target the balance-target value (target line label), or {@code null} when no target is
   *     set
   * @param plotLeft the left plot edge x (gridline/target-line start)
   * @param plotRight the right plot edge x (gridline/target-line end)
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
    // Fold the target into the visible range so the dashed target line never falls off the chart.
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
