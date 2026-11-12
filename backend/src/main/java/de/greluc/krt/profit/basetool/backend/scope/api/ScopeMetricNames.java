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

package de.greluc.krt.profit.basetool.backend.scope.api;

/**
 * Backend {@code basetool_*} metric names, tag keys and bounded tag values of the scope module
 * (REQ-OBS-011). The names and values are part of the dashboards under {@code monitoring/}.
 */
public final class ScopeMetricNames {

  /**
   * Counter {@code basetool_active_org_unit_choice_writes_total{target}} — every stored change of a
   * member's active org unit, by what the member chose; neither the unit nor the member is a label
   * (REQ-ORG-031).
   */
  public static final String ACTIVE_ORG_UNIT_CHOICE_WRITES =
      "basetool.active.org.unit.choice.writes";

  /** Tag key: what the member chose, {@link #TARGET_UNIT} or {@link #TARGET_ALL}. */
  public static final String TAG_TARGET = "target";

  /** {@code target} of a choice of one org unit. */
  public static final String TARGET_UNIT = "unit";

  /** {@code target} of a choice of all of the member's org units. */
  public static final String TARGET_ALL = "all";

  private ScopeMetricNames() {}
}
