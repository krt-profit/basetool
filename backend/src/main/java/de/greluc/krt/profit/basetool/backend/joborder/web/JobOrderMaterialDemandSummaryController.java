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

package de.greluc.krt.profit.basetool.backend.joborder.web;

import de.greluc.krt.profit.basetool.backend.joborder.internal.MaterialDemandSummaryDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.MaterialDemandSummaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The material-demand summary behind the dashboard's Materialbedarf widget (REQ-ORDERS-034,
 * REQ-UI-034): the open buckets of the cross-order overview in brief.
 */
@RestController
@RequestMapping("/api/v1/orders/material-demand")
@RequiredArgsConstructor
@Tag(name = "Orders", description = "Operations related to job orders")
public class JobOrderMaterialDemandSummaryController {

  private static final int MAX_ROWS = 50;

  private final MaterialDemandSummaryService summaryService;

  /**
   * Summarises the open material demand the caller may see.
   *
   * @param orgUnitId only this responsible unit, absent for every unit in the caller's scope
   * @param limit the most rows, clamped to 1&ndash;50
   * @return the count of open buckets and the first rows
   */
  @GetMapping("/summary")
  @Operation(
      summary = "Get the open material demand in brief",
      description =
          "Returns the number of open (material, quality) buckets of the cross-order material"
              + " demand and the first rows, scoped like the full overview.")
  @PreAuthorize("isAuthenticated()")
  public @NotNull MaterialDemandSummaryDto summary(
      @RequestParam(required = false) UUID orgUnitId,
      @RequestParam(required = false, defaultValue = "5") int limit) {
    return summaryService.summary(orgUnitId, Math.clamp(limit, 1, MAX_ROWS));
  }
}
