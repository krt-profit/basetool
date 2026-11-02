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

package de.greluc.krt.profit.basetool.frontend.dashboard.web;

import de.greluc.krt.profit.basetool.frontend.dashboard.client.DashboardBackendClient;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardCatalogueResponse;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardMode;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardWidgetDescriptor;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardWidgetType;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The frontend's view of the backend's dashboard widget catalogue (REQ-UI-034): the one place that
 * knows the widget types, their modes, columns, options, defaults and choices. The backend serves
 * it through the catalogue cache, so a read here costs no request after the first.
 */
@Component
@RequiredArgsConstructor
public class DashboardCatalogue {

  private final DashboardBackendClient client;

  /**
   * The whole catalogue.
   *
   * @return the catalogue the backend serves
   * @throws BackendServiceException when the backend sent no catalogue
   */
  public @NotNull DashboardCatalogueResponse snapshot() {
    DashboardCatalogueResponse read = client.catalogue();
    if (read == null) {
      throw unavailable("The backend sent no dashboard catalogue");
    }
    return read;
  }

  /**
   * The catalogue entry of a widget type.
   *
   * @param type the widget type
   * @return its entry
   * @throws BackendServiceException when the backend does not serve the type
   */
  public @NotNull DashboardWidgetDescriptor descriptor(@NotNull DashboardWidgetType type) {
    return snapshot()
        .find(type)
        .orElseThrow(() -> unavailable("The dashboard catalogue lacks " + type));
  }

  /**
   * The widgets of a mode in the order of the default dashboard, wide column first.
   *
   * @param mode the mode
   * @return the catalogue entries of the mode
   */
  public @NotNull List<DashboardWidgetDescriptor> ofMode(@NotNull DashboardMode mode) {
    return snapshot().widgets().stream()
        .filter(widget -> widget.mode() == mode)
        .sorted(Comparator.comparing(DashboardWidgetDescriptor::defaultColumn))
        .toList();
  }

  private static @NotNull BackendServiceException unavailable(@NotNull String message) {
    return new BackendServiceException(message, null, HttpStatus.BAD_GATEWAY.value());
  }
}
