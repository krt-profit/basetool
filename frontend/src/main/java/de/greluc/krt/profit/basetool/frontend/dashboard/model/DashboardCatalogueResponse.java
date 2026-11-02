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

package de.greluc.krt.profit.basetool.frontend.dashboard.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;

/**
 * The dashboard widget catalogue as the backend serves it (REQ-UI-034).
 *
 * @param dayChoices the day counts the days option offers
 * @param rangeChoices the day counts the range option offers
 * @param rowChoices the row counts the rows option offers
 * @param statusChoices every status filter any widget offers, in display order
 * @param widgets the widget types in catalogue order
 */
@DtoMirror
public record DashboardCatalogueResponse(
    @NotNull List<Integer> dayChoices,
    @NotNull List<Integer> rangeChoices,
    @NotNull List<Integer> rowChoices,
    @NotNull List<DashboardStatus> statusChoices,
    @NotNull List<DashboardWidgetDescriptor> widgets) {

  /** The catalogue of a dashboard that could not be read: no widget and no choice. */
  public static final DashboardCatalogueResponse EMPTY =
      new DashboardCatalogueResponse(List.of(), List.of(), List.of(), List.of(), List.of());

  /**
   * Copies the lists so the record stays immutable.
   *
   * @param dayChoices the day counts
   * @param rangeChoices the range counts
   * @param rowChoices the row counts
   * @param statusChoices the status filters
   * @param widgets the widget types
   */
  public DashboardCatalogueResponse {
    dayChoices = List.copyOf(dayChoices);
    rangeChoices = List.copyOf(rangeChoices);
    rowChoices = List.copyOf(rowChoices);
    statusChoices = List.copyOf(statusChoices);
    widgets = List.copyOf(widgets);
  }

  /**
   * Finds the descriptor of a widget type.
   *
   * @param type the widget type
   * @return the descriptor, empty when the backend does not serve the type
   */
  public @NotNull Optional<DashboardWidgetDescriptor> find(@NotNull DashboardWidgetType type) {
    return widgets.stream().filter(widget -> widget.type() == type).findFirst();
  }
}
