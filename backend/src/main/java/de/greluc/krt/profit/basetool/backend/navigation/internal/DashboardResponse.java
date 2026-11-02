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

package de.greluc.krt.profit.basetool.backend.navigation.internal;

import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * A member's dashboard of one mode: the layout version and the instances in display order. While
 * the member never changed the layout the instances are the mode's default (REQ-UI-034).
 *
 * @param mode the mode
 * @param version the layout version the next layout write echoes
 * @param customised whether the member changed the default
 * @param widgets the instances in display order
 */
public record DashboardResponse(
    @NotNull DashboardMode mode,
    long version,
    boolean customised,
    @NotNull List<DashboardWidgetResponse> widgets) {

  /**
   * Copies the list so the record stays immutable.
   *
   * @param mode the mode
   * @param version the layout version
   * @param customised whether the member changed the default
   * @param widgets the instances in display order
   */
  public DashboardResponse {
    widgets = List.copyOf(widgets);
  }
}
