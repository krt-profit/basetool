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

import de.greluc.krt.profit.basetool.backend.model.dto.LeitungViewDto;
import de.greluc.krt.profit.basetool.backend.service.LeitungViewService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read surface for the delegated Leitung page: returns the org units the caller may appoint into,
 * grouped by tier, with each unit's roster and capability flags (REQ-ROLE-004).
 *
 * <p>Gated only by {@code isAuthenticated()}: {@link LeitungViewService} limits the result to the
 * caller's own delegated reach, so a plain member receives empty tier lists.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/leitung")
public class LeitungController {

  private final LeitungViewService leitungViewService;

  /**
   * Returns the caller's delegated Leitung view.
   *
   * @param authentication the current authentication; never {@code null} (endpoint is
   *     authenticated-only).
   * @return the manageable units grouped by tier, with rosters and capability flags.
   */
  @GetMapping("/view")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "The org units the caller may appoint leadership ranks into, by tier")
  public LeitungViewDto view(@NotNull Authentication authentication) {
    return leitungViewService.buildView(authentication);
  }
}
