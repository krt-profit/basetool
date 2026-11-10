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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderCreateOptionsDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderCreationOptions;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes current order creation choices under the caller's active unit context. */
@RestController
@RequestMapping("/api/v1/orders/create-options")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class JobOrderCreateOptionsController {

  private final JobOrderCreationOptions creationOptions;

  /**
   * Returns current eligible choices and the writer capability of each offered side.
   *
   * @return current creation options, including a false capability when none is available
   */
  @GetMapping
  @Operation(summary = "Get current order creation capability and unit choices")
  public JobOrderCreateOptionsDto current() {
    return creationOptions.current();
  }
}
