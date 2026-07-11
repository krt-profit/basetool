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

import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.service.BlueprintService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import de.greluc.krt.profit.basetool.backend.web.PaginationUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only read surface for synced crafting blueprints and their requirement-group stat graph.
 * Backs the {@code /admin/blueprints} frontend page.
 *
 * <p>Class-level {@code @PreAuthorize("hasRole('ADMIN')")}: synced game reference data is an
 * administration concern (mirrors {@link SyncReportController}). Read-only — the SC Wiki sync is
 * the only writer. Blueprints are not org-unit-scoped, so no {@code OwnerScopeService} gating
 * applies.
 */
@RestController
@RequestMapping("/api/v1/blueprints")
@RequiredArgsConstructor
@Transactional(readOnly = true)
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class BlueprintController {

  private final BlueprintService blueprintService;

  /**
   * Returns one page of active blueprints with their ingredients and per-slot stat modifiers,
   * optionally filtered by a case-insensitive substring of the output-item name or Wiki key.
   *
   * @param search optional output-name / key filter
   * @param page zero-based page index (optional)
   * @param size page size (optional)
   * @param sort sort expression over the whitelist {@code outputName,craftTimeSeconds,scwikiKey,id}
   * @return a page of blueprint DTOs
   */
  @Operation(
      summary = "List synced crafting blueprints",
      description =
          "Admin-only. Returns synced SC Wiki blueprints with their ingredients and the per-slot "
              + "stat modifiers (requirement groups), filtered by output-item name / Wiki key.")
  @ApiResponses({@ApiResponse(responseCode = "200", description = "Blueprint page returned")})
  @GetMapping
  public PageResponse<BlueprintDto> getBlueprints(
      @RequestParam(required = false) String search,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page,
            size,
            sort,
            Set.of("outputName", "craftTimeSeconds", "scwikiKey", "id"),
            "outputName");
    Page<BlueprintDto> p = blueprintService.getBlueprints(search, pageable);
    return PageResponse.of(p);
  }
}
