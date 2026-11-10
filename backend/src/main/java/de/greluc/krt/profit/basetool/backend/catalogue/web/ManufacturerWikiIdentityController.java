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

package de.greluc.krt.profit.basetool.backend.catalogue.web;

import de.greluc.krt.profit.basetool.backend.catalogue.internal.ManufacturerWikiDuplicateDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ManufacturerWikiIdentityService;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ManufacturerWikiPrimaryRequest;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only surface for manufacturers the SC Wiki lists more than once, backing the duplicate
 * section of {@code /admin/sync-reports/scwiki} (ADR-0356).
 */
@RestController
@RequestMapping("/api/v1/catalog/admin/manufacturer-wiki-duplicates")
@RequiredArgsConstructor
@PreAuthorize(Roles.HAS_ROLE_ADMIN)
public class ManufacturerWikiIdentityController {

  private final ManufacturerWikiIdentityService service;

  /**
   * Lists every manufacturer with more than one SC Wiki entry.
   *
   * @return the duplicated manufacturers, ordered by name
   */
  @NotNull
  @Operation(
      summary = "List manufacturers the SC Wiki lists twice",
      description =
          "Admin-only. Returns every manufacturer with more than one SC Wiki entry, each entry"
              + " marked primary or alias.")
  @ApiResponses({@ApiResponse(responseCode = "200", description = "Duplicates returned")})
  @GetMapping
  public List<ManufacturerWikiDuplicateDto> listManufacturerWikiDuplicates() {
    return service.listDuplicates();
  }

  /**
   * Makes one SC Wiki entry the manufacturer's primary link; the others stay aliases.
   *
   * @param manufacturerId the local manufacturer id
   * @param request the chosen Wiki entry and the version the admin last saw
   * @return the manufacturer with its Wiki entries after the choice
   */
  @NotNull
  @Operation(
      summary = "Choose a manufacturer's primary SC Wiki entry",
      description =
          "Admin-only. Makes the given SC Wiki entry the manufacturer's primary link; the other"
              + " entries stay aliases. Audited.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Primary entry set"),
    @ApiResponse(responseCode = "400", description = "Entry not of this manufacturer"),
    @ApiResponse(responseCode = "404", description = "Manufacturer not found"),
    @ApiResponse(responseCode = "409", description = "Stale version")
  })
  @PutMapping("/{manufacturerId}/primary")
  public ManufacturerWikiDuplicateDto chooseManufacturerWikiPrimary(
      @PathVariable UUID manufacturerId,
      @Valid @RequestBody ManufacturerWikiPrimaryRequest request) {
    return service.choosePrimary(manufacturerId, request);
  }
}
