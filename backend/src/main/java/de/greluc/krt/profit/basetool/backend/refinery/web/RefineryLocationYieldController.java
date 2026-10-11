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

package de.greluc.krt.profit.basetool.backend.refinery.web;

import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrderService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only refinery yield matrix of a location (UEX data), served under the location. */
@RestController
@RequestMapping("/api/v1/locations/{locationId}/refinery-yields")
@RequiredArgsConstructor
public class RefineryLocationYieldController {

  private final RefineryOrderService refineryOrderService;

  /**
   * Returns the UEX-derived refinery yield bonus/malus per input material for a refinery location,
   * as a {@code materialId → percent} map (positive = bonus, negative = malus, {@code 0} =
   * baseline).
   *
   * <p>An unknown or {@code null} location returns an empty map, not 404.
   *
   * @param locationId the chosen refinery location
   * @return per-material yield bonus map
   */
  @GetMapping
  @Operation(
      summary = "Get refinery yields of a location",
      description = "Returns the yield bonus or malus per input material at the given location.")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  public Map<UUID, Integer> getYieldsForLocation(@PathVariable @NotNull UUID locationId) {
    return refineryOrderService.getYieldBonusByMaterialForLocationId(locationId);
  }
}
