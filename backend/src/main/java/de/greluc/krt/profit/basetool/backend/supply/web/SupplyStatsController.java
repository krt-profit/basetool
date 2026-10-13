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

package de.greluc.krt.profit.basetool.backend.supply.web;

import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.supply.internal.SupplyStatsDto;
import de.greluc.krt.profit.basetool.backend.supply.internal.SupplyStatsService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The donation statistics per receiving unit and month (REQ-SUP-013). */
@RestController
@RequestMapping("/api/v1/supply/stats")
@RequiredArgsConstructor
@PreAuthorize(Roles.HAS_ROLE_KRT_MEMBER)
public class SupplyStatsController {

  private final SupplyStatsService statsService;

  /**
   * Computes the statistics of the last months for the units the caller may see.
   *
   * @param months how many months back, the current one included
   * @return the figures; the per-donor totals only for the governing leadership
   */
  @Operation(summary = "Read the donation statistics per receiving unit and month")
  @GetMapping
  public SupplyStatsDto stats(@RequestParam(defaultValue = "6") int months) {
    return statsService.stats(months);
  }
}
