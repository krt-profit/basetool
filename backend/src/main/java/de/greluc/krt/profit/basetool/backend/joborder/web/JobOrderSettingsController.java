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

import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderSettingsDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only typed view of the job-order settings, open to every authenticated caller. */
@RestController
@RequestMapping("/api/v1/orders/settings")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class JobOrderSettingsController {

  private final JobOrderSettingsService service;

  /**
   * Returns the ageing thresholds that colour an open job order.
   *
   * @return the yellow and red thresholds in days
   */
  @GetMapping
  @Operation(summary = "Get the job-order ageing thresholds.")
  public JobOrderSettingsDto settings() {
    return service.read();
  }
}
