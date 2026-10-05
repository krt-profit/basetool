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

package de.greluc.krt.profit.basetool.backend.controller;

import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.model.dto.ProfitCalculationDto;
import de.greluc.krt.profit.basetool.backend.service.ProfitCalculationService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint backing the profit-calculation page. Delegates to {@link ProfitCalculationService};
 * restricted to authenticated members.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/materials/profit-calculation")
@RequiredArgsConstructor
public class ProfitCalculationController {

  private final ProfitCalculationService profitCalculationService;

  /**
   * Computes profit rows for the chosen ship across an optional star-system filter.
   *
   * @param shipId ship type id (capacity comes from this)
   * @param starSystemNames optional star system filter; null/empty = all systems
   * @return per-material profit rows sorted alphabetically
   */
  @GetMapping
  @PreAuthorize(
      "hasAnyRole('" + Roles.KRT_MEMBER + "', '" + Roles.OFFICER + "', '" + Roles.ADMIN + "')")
  public List<ProfitCalculationDto> getProfitCalculation(
      @RequestParam UUID shipId, @RequestParam(required = false) List<String> starSystemNames) {
    log.debug("GET profit calculation for shipId: {} and starSystems: {}", shipId, starSystemNames);
    return profitCalculationService.calculateProfit(shipId, starSystemNames);
  }
}
