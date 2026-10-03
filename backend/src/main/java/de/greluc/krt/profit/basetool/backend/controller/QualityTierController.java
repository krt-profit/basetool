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

import de.greluc.krt.profit.basetool.backend.model.dto.QualityTierDto;
import de.greluc.krt.profit.basetool.backend.service.QualityTierService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read access to the quality-tier catalogue for every member (REQ-ORDERS-036). */
@RestController
@RequestMapping("/api/v1/quality-tiers")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "Quality tiers", description = "The quality tiers a material requirement is stated in.")
@SecurityRequirement(name = "bearer-jwt")
public class QualityTierController {

  private final QualityTierService qualityTierService;

  /**
   * Lists the catalogue in picker order.
   *
   * @param includeInactive {@code true} adds the tiers no longer offered for new requirements
   * @return the tiers
   */
  @GetMapping
  @Operation(summary = "List the quality tiers, active ones only unless includeInactive is set.")
  public List<QualityTierDto> listQualityTiers(
      @RequestParam(name = "includeInactive", defaultValue = "false") boolean includeInactive) {
    return includeInactive ? qualityTierService.listAll() : qualityTierService.listActive();
  }
}
