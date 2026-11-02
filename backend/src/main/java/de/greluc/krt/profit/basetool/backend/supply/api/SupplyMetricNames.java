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

package de.greluc.krt.profit.basetool.backend.supply.api;

/**
 * Backend {@code basetool_*} metric names, tag keys and bounded tag values of the supply module
 * (REQ-OBS-011). The names and values are part of the dashboards and alert rules under {@code
 * monitoring/}.
 */
public final class SupplyMetricNames {

  /**
   * Counter {@code basetool_supply_needs_total} — needs by lifecycle event, tag {@code event}
   * (REQ-OBS-011).
   */
  public static final String SUPPLY_NEEDS = "basetool.supply.needs";

  /**
   * Counter {@code basetool_supply_donations_total} — donations by the state they reached, tag
   * {@code status} (REQ-OBS-011).
   */
  public static final String SUPPLY_DONATIONS = "basetool.supply.donations";

  /** Gauge {@code basetool_supply_needs_open} — needs open for donations. */
  public static final String SUPPLY_NEEDS_OPEN = "basetool.supply.needs.open";

  /** Gauge {@code basetool_supply_donations_pledged_open} — pledges awaiting their receipt. */
  public static final String SUPPLY_DONATIONS_PLEDGED_OPEN =
      "basetool.supply.donations.pledged.open";

  private SupplyMetricNames() {}
}
