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

package de.greluc.krt.profit.basetool.backend.purchasing.api;

import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;

/**
 * Backend {@code basetool_*} metric names, tag keys and bounded tag values of the purchasing module
 * (REQ-OBS-011). The names and values are part of the dashboards and alert rules under {@code
 * monitoring/}.
 */
public final class PurchasingMetricNames {

  /**
   * Counter {@code basetool_purchasing_deals_total} — deals by lifecycle event, tag {@code event}
   * (REQ-OBS-011).
   */
  public static final String PURCHASING_DEALS = "basetool.purchasing.deals";

  /**
   * Counter {@code basetool_purchasing_tranches_total} — money tranches, tags {@code kind} ({@link
   * MetricNames#TAG_KIND}) and {@code outcome} ({@link MetricNames#TAG_OUTCOME}: requested,
   * confirmed, refused, cancelled) (REQ-OBS-011).
   */
  public static final String PURCHASING_TRANCHES = "basetool.purchasing.tranches";

  /** Gauge {@code basetool_purchasing_deals} — deals by state, tag {@code status}. */
  public static final String PURCHASING_DEALS_BY_STATUS = "basetool.purchasing.deals.by.status";

  /** Gauge {@code basetool_purchasing_settlements_overdue} — deals past their settlement date. */
  public static final String PURCHASING_SETTLEMENTS_OVERDUE =
      "basetool.purchasing.settlements.overdue";

  /** Gauge {@code basetool_purchasing_paused} — {@code 1} while purchasing is paused globally. */
  public static final String PURCHASING_PAUSED = "basetool.purchasing.paused";

  /** Gauge {@code basetool_purchasing_delegates_active} — active Handelsbeauftragte. */
  public static final String PURCHASING_DELEGATES_ACTIVE = "basetool.purchasing.delegates.active";

  private PurchasingMetricNames() {}
}
