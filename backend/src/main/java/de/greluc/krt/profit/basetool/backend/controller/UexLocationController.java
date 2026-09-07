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

import de.greluc.krt.profit.basetool.backend.model.dto.UexLocationDto;
import de.greluc.krt.profit.basetool.backend.service.PersonalInventoryItemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only typeahead endpoint backing the personal-inventory location picker. Returns a combined
 * list of UEX cities and space stations from the locally synced mirror.
 */
@RestController
@RequestMapping("/api/v1/uex/locations")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "UEX Locations", description = "Lookup UEX cities and space stations.")
@SecurityRequirement(name = "bearer-jwt")
public class UexLocationController {

  /** Hard cap to keep typeahead payloads small. */
  private static final int DEFAULT_LIMIT = 25;

  private static final int MAX_LIMIT = 2000;

  private final PersonalInventoryItemService service;

  /**
   * Combined typeahead over UEX cities and space stations. {@code limit} is clamped to {@code [1,
   * 2000]}; missing limit falls back to {@link #DEFAULT_LIMIT}.
   *
   * @param q optional case-insensitive substring filter
   * @param limit optional result cap
   * @return matching locations alphabetically sorted
   */
  @GetMapping("/search")
  @Operation(summary = "Search UEX cities and space stations by name (case insensitive).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Up to 2000 matches, sorted alphabetically."),
    @ApiResponse(responseCode = "401", description = "Authentication required.")
  })
  public List<UexLocationDto> search(
      @RequestParam(required = false) String q, @RequestParam(required = false) Integer limit) {
    int effectiveLimit = limit == null ? DEFAULT_LIMIT : Math.min(MAX_LIMIT, Math.max(1, limit));
    return service.searchLocations(q, effectiveLimit);
  }
}
