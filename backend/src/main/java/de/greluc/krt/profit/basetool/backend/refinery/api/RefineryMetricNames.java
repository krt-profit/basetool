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

package de.greluc.krt.profit.basetool.backend.refinery.api;

/**
 * Backend {@code basetool_*} metric names, tag keys and bounded tag values of the refinery module
 * (REQ-OBS-011). The names and values are part of the dashboards and alert rules under {@code
 * monitoring/}.
 */
public final class RefineryMetricNames {

  /** Gauge {@code basetool_refinery_order_open_count} — tag {@code status}. */
  public static final String REFINERY_ORDER_OPEN = "basetool.refinery.order.open.count";

  /** Gauge {@code basetool_refinery_order_open_oldest_age_seconds}. */
  public static final String REFINERY_ORDER_OPEN_OLDEST_AGE =
      "basetool.refinery.order.open.oldest.age";

  private RefineryMetricNames() {}
}
