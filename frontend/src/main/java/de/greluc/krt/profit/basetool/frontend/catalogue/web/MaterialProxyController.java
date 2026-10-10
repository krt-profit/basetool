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
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialSellingTerminalDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.ProfitCalculationDto;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated REST proxy from {@code /api/proxy/materials/**} to the backend's {@code
 * /api/v1/materials/**} read endpoints.
 */
@RestController
@RequestMapping("/api/proxy/materials")
@RequiredArgsConstructor
public class MaterialProxyController {

  /** Reads the selling terminals and the profit calculation. */
  private final CatalogueBackendClient catalogueClient;

  /**
   * Returns the terminals trading the given material.
   *
   * @param id material id
   * @return the selling terminals, never {@code null}
   */
  @GetMapping("/{id}/terminals")
  @PreAuthorize("isAuthenticated()")
  public List<MaterialSellingTerminalDto> getMaterialTerminals(@PathVariable UUID id) {
    List<MaterialSellingTerminalDto> response = catalogueClient.materialSellingTerminals(id);
    return response != null ? response : List.of();
  }

  /**
   * Forwards the profit-calculation query to the backend, passing each star-system name as a
   * separately encoded {@code starSystemNames} parameter (REQ-SEC-051). Without star systems the
   * calculation spans all systems.
   *
   * @param shipId chosen ship's id (defines capacity)
   * @param starSystemNames optional list of star-system names to constrain the source terminals
   * @return list of profit-calculation rows, never {@code null}
   */
  @GetMapping("/profit-calculation")
  @PreAuthorize("isAuthenticated()")
  public List<ProfitCalculationDto> getProfitCalculation(
      @RequestParam UUID shipId, @RequestParam(required = false) List<String> starSystemNames) {
    List<ProfitCalculationDto> response =
        catalogueClient.profitCalculation(shipId, starSystemNames);
    return response != null ? response : List.of();
  }
}
