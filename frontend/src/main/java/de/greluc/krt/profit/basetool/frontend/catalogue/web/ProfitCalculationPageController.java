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

package de.greluc.krt.profit.basetool.frontend.catalogue.web;

import de.greluc.krt.profit.basetool.frontend.catalogue.client.CatalogueBackendClient;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.ShipTypeDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.TerminalDto;
import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Controller of the profit-calculation page ({@code /materials/profit-calculation}), offering ships
 * with SCU capacity (C2 Hercules by default) and the known star systems.
 */
@Slf4j
@Controller
@UsesLayoutModel
@RequestMapping("/materials/profit-calculation")
@RequiredArgsConstructor
public class ProfitCalculationPageController {

  /** Reads the cached ship-type and terminal catalogues. */
  private final CatalogueBackendClient catalogueClient;

  /**
   * Renders the profit-calculation page; a backend failure renders an empty form with a page-level
   * error.
   *
   * @param model model populated with {@code shipTypes}, {@code defaultShipId} and {@code
   *     starSystems}
   * @return the {@code materials-profit-calculation} view name
   */
  @NotNull
  @GetMapping
  @PreAuthorize(
      "hasAnyRole('" + Roles.KRT_MEMBER + "', '" + Roles.OFFICER + "', '" + Roles.ADMIN + "')")
  public String showProfitCalculationPage(Model model) {
    log.debug("Showing profit calculation page");
    try {
      PageResponse<ShipTypeDto> shipTypesPage = catalogueClient.shipTypesSorted();

      List<ShipTypeDto> shipTypes =
          (shipTypesPage != null && shipTypesPage.content() != null)
              ? shipTypesPage.content().stream()
                  .filter(s -> s.scu() != null && s.scu() > 0)
                  .toList()
              : List.of();

      model.addAttribute("shipTypes", shipTypes);

      shipTypes.stream()
          .filter(s -> s.name().contains("C2 Hercules Starlifter"))
          .findFirst()
          .ifPresent(c2 -> model.addAttribute("defaultShipId", c2.id()));

      PageResponse<TerminalDto> terminalsPage = catalogueClient.terminalCatalogue();

      Set<String> starSystems = new TreeSet<>();
      if (terminalsPage != null && terminalsPage.content() != null) {
        for (TerminalDto terminal : terminalsPage.content()) {
          String system = terminal.starSystemName();
          if (system != null && !system.isBlank()) {
            starSystems.add(system);
          }
        }
      }
      model.addAttribute("starSystems", starSystems);

    } catch (Exception e) {
      log.error("Error loading data for profit calculation page", e);
      model.addAttribute("error", "error.profit_calculation.load");
    }

    return "materials-profit-calculation";
  }
}
