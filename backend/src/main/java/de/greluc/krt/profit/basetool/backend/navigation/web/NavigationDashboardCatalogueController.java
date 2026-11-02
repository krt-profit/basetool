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

package de.greluc.krt.profit.basetool.backend.navigation.web;

import de.greluc.krt.profit.basetool.backend.navigation.internal.DashboardCatalogueResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the dashboard widget catalogue (REQ-UI-034). The catalogue is the same for every caller
 * and holds no member data; which widgets a caller may use is decided by the frontend's gates and
 * by each widget's own endpoint.
 */
@RestController
@RequestMapping("/api/v1/navigation/dashboard-widgets")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Navigation", description = "The caller's own navigation preferences.")
@SecurityRequirement(name = "bearer-jwt")
public class NavigationDashboardCatalogueController {

  /**
   * Reads the widget catalogue.
   *
   * @return the widget types with their options, defaults and choices
   */
  @GetMapping
  @Operation(
      operationId = "readDashboardCatalogue",
      summary = "Read the dashboard widget catalogue.")
  @ApiResponse(responseCode = "200", description = "The catalogue.")
  public DashboardCatalogueResponse read() {
    return DashboardCatalogueResponse.current();
  }
}
