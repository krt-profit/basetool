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

import de.greluc.krt.profit.basetool.backend.model.dto.OrgChartDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgChartPositionCreateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgChartPositionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgChartPositionUpdateRequest;
import de.greluc.krt.profit.basetool.backend.service.OrgChartService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the Profit-Bereich org chart. The chart is purely descriptive (it grants no
 * permissions), so read access is open to every authenticated user while every write is
 * ADMIN-gated. The whole chart is read in one call; the inline editor mutates it one position at a
 * time.
 *
 * <p>The user picker the editor needs is served by the existing {@code GET /api/v1/users/lookup} —
 * this controller intentionally adds no user-listing endpoint of its own.
 */
@RestController
@RequestMapping("/api/v1/org-chart")
@RequiredArgsConstructor
public class OrgChartController {

  private final OrgChartService orgChartService;

  /**
   * Returns the entire org chart (Bereichsleitung plus every active, profit-eligible Staffel and
   * SK) as one nested read model. Open to any authenticated caller.
   *
   * @return the assembled chart.
   */
  @GetMapping
  @PreAuthorize("isAuthenticated()")
  @Operation(
      summary = "Get the Profit-Bereich org chart",
      description =
          "Returns the full org chart: the Bereichsleitung on top and a column for every active,"
              + " profit-eligible Staffel and Spezialkommando below. Read-only and open to every"
              + " authenticated user; editing is ADMIN-only via the position endpoints.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "The assembled org chart."),
    @ApiResponse(responseCode = "401", description = "Caller is not authenticated.")
  })
  public OrgChartDto getOrgChart() {
    return orgChartService.getOrgChart();
  }

  /**
   * Assigns a user to a new functional-rank position. ADMIN-only. Scope/parent/cardinality/
   * uniqueness violations surface as 400 problem responses.
   *
   * @param request the assignment payload; validated by Jakarta annotations.
   * @return the persisted position as a flat DTO.
   */
  @PostMapping("/positions")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  @Operation(
      summary = "Assign a user to an org-chart position",
      description =
          "Creates a position binding a user to a functional rank within a scope (area leadership,"
              + " Staffel or SK). A Kommando (COMMAND_LEAD) may instead be created without a holder"
              + " and with a name, so its Stv. Kommandoleiter and Ensigns can be filled before its"
              + " Kommandoleiter is assigned; every other rank requires a holder and rejects a"
              + " name. Enforces the per-Staffel limits (≤4 Kommandos, ≤4 Ensign), the ≤2 SK-Leiter"
              + " limit, the scope/parent consistency rules and one-user-per-scope. ADMIN-only.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Position created."),
    @ApiResponse(
        responseCode = "400",
        description = "Validation error or a scope/parent/cardinality/uniqueness rule violation."),
    @ApiResponse(responseCode = "403", description = "Caller does not hold ROLE_ADMIN."),
    @ApiResponse(
        responseCode = "404",
        description = "Referenced user, OrgUnit or parent not found."),
    @ApiResponse(
        responseCode = "409",
        description = "A concurrent create raced a singleton uniqueness constraint.")
  })
  public OrgChartPositionDto createPosition(
      @RequestBody @Valid OrgChartPositionCreateRequest request) {
    return orgChartService.createPosition(request);
  }

  /**
   * Reassigns the holder and/or reorders an existing position. The rank and scope are immutable;
   * only the holder and the display order may change. Carries the optimistic-lock version in the
   * body. ADMIN-only.
   *
   * @param id the position id.
   * @param request the edit payload; validated by Jakarta annotations.
   * @return the updated position with the bumped version.
   */
  @PutMapping("/positions/{id}")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  @Operation(
      summary = "Reassign or reorder an org-chart position",
      description =
          "Changes the holder, the Kommando name (COMMAND_LEAD only) and/or the display order of an"
              + " existing position. The functional rank and scope cannot change (move = remove +"
              + " re-add). Assigning a holder to a still-leaderless Kommando is a reassign through"
              + " this endpoint. ADMIN-only.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Position updated."),
    @ApiResponse(
        responseCode = "400",
        description = "Validation error or the new holder already occupies the scope."),
    @ApiResponse(responseCode = "403", description = "Caller does not hold ROLE_ADMIN."),
    @ApiResponse(responseCode = "404", description = "Position or new holder not found."),
    @ApiResponse(responseCode = "409", description = "Optimistic-lock conflict (stale version).")
  })
  public OrgChartPositionDto updatePosition(
      @PathVariable @NotNull UUID id, @RequestBody @Valid OrgChartPositionUpdateRequest request) {
    return orgChartService.updatePosition(id, request);
  }

  /**
   * Vacates a Kommando's Kommandoleiter — clears the holder while keeping the Kommando, its name,
   * its Stv. and its Ensigns. Distinct from {@link #deletePosition}, which removes the whole
   * Kommando. The optimistic-lock version travels as a query parameter. ADMIN-only.
   *
   * @param id the Kommando ({@code COMMAND_LEAD}) position id.
   * @param version the optimistic-lock version the client last saw.
   * @return the updated, now-leaderless Kommando with the bumped version.
   */
  @DeleteMapping("/positions/{id}/leader")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  @Operation(
      summary = "Vacate a Kommando's Kommandoleiter",
      description =
          "Clears the Kommandoleiter (holder) of a Kommando (COMMAND_LEAD) while keeping the"
              + " Kommando, its name, its Stv. Kommandoleiter and its Ensigns. This is how a"
              + " Kommando outlives a departing leader instead of being deleted and rebuilt;"
              + " removing the whole Kommando is the position DELETE. Rejects any non-COMMAND_LEAD"
              + " rank, whose holder is mandatory — remove that position instead. ADMIN-only.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Kommandoleiter vacated; the Kommando stays."),
    @ApiResponse(responseCode = "400", description = "The position is not a Kommando."),
    @ApiResponse(responseCode = "403", description = "Caller does not hold ROLE_ADMIN."),
    @ApiResponse(responseCode = "404", description = "No position matches the given id."),
    @ApiResponse(responseCode = "409", description = "Optimistic-lock conflict (stale version).")
  })
  public OrgChartPositionDto vacateCommandLeader(
      @PathVariable @NotNull UUID id, @RequestParam("version") long version) {
    return orgChartService.vacateCommandLeader(id, version);
  }

  /**
   * Removes a position. Removing a Kommandoleiter cascades to its Stv. and the Ensigns under that
   * command. ADMIN-only.
   *
   * @param id the position id.
   */
  @DeleteMapping("/positions/{id}")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  @Operation(
      summary = "Remove an org-chart position",
      description =
          "Deletes the position. Removing a Kommando (COMMAND_LEAD) also removes its Stv."
              + " Kommandoleiter and the Ensigns reporting into it. ADMIN-only.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Position removed."),
    @ApiResponse(responseCode = "403", description = "Caller does not hold ROLE_ADMIN."),
    @ApiResponse(responseCode = "404", description = "No position matches the given id.")
  })
  public void deletePosition(@PathVariable @NotNull UUID id) {
    orgChartService.deletePosition(id);
  }
}
