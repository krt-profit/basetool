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

import de.greluc.krt.profit.basetool.backend.refinery.internal.RefinerySettingsDto;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefinerySettingsService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only typed view of the refinery settings, open to every authenticated caller. */
@RestController
@RequestMapping("/api/v1/refinery-orders/settings")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class RefinerySettingsController {

  private final RefinerySettingsService service;

  /**
   * Returns the refinery settings the order forms and totals need.
   *
   * @return the unit-to-SCU rounding mode
   */
  @GetMapping
  @Operation(summary = "Get the refinery settings.")
  public RefinerySettingsDto settings() {
    return service.read();
  }
}
