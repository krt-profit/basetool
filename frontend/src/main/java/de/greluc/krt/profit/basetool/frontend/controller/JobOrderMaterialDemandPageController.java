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

import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDemandGroupDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDemandOverviewDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDemandRowDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Frontend controller for the read-only cross-order material-demand overview (REQ-ORDERS-034): what
 * the caller's unit still has to gather across all open orders.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/orders")
@RequiredArgsConstructor
@Slf4j
public class JobOrderMaterialDemandPageController {

  /**
   * The empty overview rendered when the backend read fails, so the page degrades to its empty
   * state instead of a full-page error.
   */
  private static final MaterialDemandOverviewDto EMPTY_OVERVIEW =
      new MaterialDemandOverviewDto(List.of());

  /** Loads the aggregated demand from the backend. */
  private final BackendApiClient backendApiClient;

  /**
   * Renders the cross-order material demand at {@code /orders/material-demand}, as scoped by the
   * backend.
   *
   * <p>A backend failure degrades to an empty overview. {@code ?fragment=results} returns only the
   * {@code demandResults} fragment for live sync (REQ-FE-010).
   *
   * @param fragment when {@code results}, render only the {@code demandResults} fragment
   * @param model Thymeleaf model populated with {@code demand}
   * @return the {@code orders-material-demand} view name, or its {@code demandResults} fragment
   */
  @NotNull
  @GetMapping("/material-demand")
  @PreAuthorize("isAuthenticated()")
  public String viewMaterialDemand(
      @RequestParam(name = "fragment", required = false) String fragment, Model model) {
    MaterialDemandOverviewDto demand = EMPTY_OVERVIEW;
    try {
      demand =
          backendApiClient.get("/api/v1/orders/material-demand", MaterialDemandOverviewDto.class);
    } catch (BackendServiceException e) {
      log.warn("Could not load the cross-order material demand: {}", e.getMessage());
    }
    if (demand == null) {
      demand = EMPTY_OVERVIEW;
    }

    model.addAttribute("demand", demand);
    model.addAttribute("materialOptions", materialOptions(demand));
    if ("results".equals(fragment)) {
      return "orders-material-demand :: demandResults";
    }
    return "orders-material-demand";
  }

  /**
   * Collects the distinct materials the overview shows, sorted case-insensitively by name, for the
   * filter panel's multi-select.
   *
   * @param demand the overview being rendered; never {@code null}
   * @return the distinct materials, name-ordered; empty when nothing is shown
   */
  @NotNull
  private static List<MaterialDto> materialOptions(@NotNull MaterialDemandOverviewDto demand) {
    Map<UUID, MaterialDto> byId = new LinkedHashMap<>();
    for (MaterialDemandGroupDto group : demand.groups()) {
      for (MaterialDemandRowDto row : group.materials()) {
        if (row.material() != null && row.material().id() != null) {
          byId.putIfAbsent(row.material().id(), row.material());
        }
      }
    }
    return byId.values().stream()
        .sorted(
            Comparator.comparing(
                material -> material.name() == null ? "" : material.name(),
                String.CASE_INSENSITIVE_ORDER))
        .toList();
  }
}
