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

import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.RankRequirementResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.RankRequirementWriteRequest;
import de.greluc.krt.profit.basetool.backend.service.RankRequirementService;
import de.greluc.krt.profit.basetool.backend.validation.OnUpdate;
import de.greluc.krt.profit.basetool.backend.web.PaginationUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.groups.Default;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** REST endpoints for {@code RankRequirement} management. */
@RestController
@RequestMapping("/api/v1/promotion/rank-requirements")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "Rank Requirements", description = "Manage rank promotion requirements.")
@SecurityRequirement(name = "bearer-jwt")
public class RankRequirementController {

  private final RankRequirementService service;

  /**
   * Returns a paginated list of every {@link RankRequirementResponse} across all rank transitions
   * with sort fields restricted to {@link RankRequirementService#SORTABLE_FIELDS}.
   *
   * @param page zero-based page index, or {@code null} for the default
   * @param size page size, or {@code null} for the default
   * @param sort comma-separated sort spec ({@code field,direction}), or {@code null} for the
   *     default
   * @return a {@link PageResponse} of rank requirements
   */
  @GetMapping
  @Operation(summary = "List all rank requirements (paginated).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Paginated list of rank requirements."),
    @ApiResponse(responseCode = "401", description = "Authentication required.")
  })
  public PageResponse<RankRequirementResponse> list(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page,
            size,
            sort,
            RankRequirementService.SORTABLE_FIELDS,
            RankRequirementService.DEFAULT_SORT_FIELD);
    return PageResponse.of(service.list(pageable));
  }

  /**
   * Returns every {@link RankRequirementResponse} that applies to the promotion path from {@code
   * fromRank} to {@code toRank}, used by the eligibility engine to evaluate whether a member
   * qualifies for that specific rank step.
   *
   * @param fromRank ordinal of the current rank
   * @param toRank ordinal of the rank being promoted to
   * @return the rank requirements applicable to that transition
   */
  @GetMapping("/by-ranks")
  @Operation(summary = "List rank requirements for a specific rank transition.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Requirements for the rank transition.")
  })
  public List<RankRequirementResponse> listByRanks(
      @RequestParam int fromRank, @RequestParam int toRank) {
    return service.listByRanks(fromRank, toRank);
  }

  /**
   * Returns a single {@link RankRequirementResponse} by identifier, or HTTP 404 if it does not
   * exist.
   *
   * @param id identifier of the rank requirement
   * @return the matching rank requirement
   */
  @GetMapping("/{id}")
  @Operation(summary = "Get a single rank requirement by ID.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Rank requirement found."),
    @ApiResponse(responseCode = "404", description = "Not found.")
  })
  public RankRequirementResponse get(@PathVariable UUID id) {
    return service.get(id);
  }

  /**
   * Persists a new {@link de.greluc.krt.profit.basetool.backend.model.RankRequirement} describing
   * the minimum score and conditions for a rank transition. Restricted to ADMIN or OFFICER callers.
   *
   * @param request validated payload describing the new rank requirement
   * @return the persisted rank requirement in its response form
   */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Create a new rank requirement. Requires ADMIN or OFFICER role.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Rank requirement created."),
    @ApiResponse(responseCode = "400", description = "Validation failed."),
    @ApiResponse(responseCode = "403", description = "Insufficient permissions.")
  })
  public RankRequirementResponse create(@Valid @RequestBody RankRequirementWriteRequest request) {
    return service.create(request);
  }

  /**
   * Updates the rank requirement identified by {@code id}. The {@code version} field in the request
   * body guards against concurrent edits and surfaces as HTTP 409 on conflict.
   *
   * @param id identifier of the rank requirement to update
   * @param request validated payload with the new field values and the previously fetched {@code
   *     version}
   * @return the updated rank requirement in its response form
   */
  @PutMapping("/{id}")
  @Operation(summary = "Update a rank requirement. Requires ADMIN or OFFICER role.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Rank requirement updated."),
    @ApiResponse(responseCode = "400", description = "Validation failed."),
    @ApiResponse(responseCode = "404", description = "Not found."),
    @ApiResponse(responseCode = "409", description = "Optimistic lock conflict.")
  })
  public RankRequirementResponse update(
      @PathVariable UUID id,
      @Validated({Default.class, OnUpdate.class}) @RequestBody
          RankRequirementWriteRequest request) {
    return service.update(id, request);
  }

  /**
   * Permanently removes the rank requirement identified by {@code id}. Restricted to ADMIN or
   * OFFICER callers.
   *
   * @param id identifier of the rank requirement to delete
   */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Delete a rank requirement. Requires ADMIN or OFFICER role.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Deleted."),
    @ApiResponse(responseCode = "404", description = "Not found.")
  })
  public void delete(@PathVariable UUID id) {
    service.delete(id);
  }
}
