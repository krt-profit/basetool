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
import de.greluc.krt.profit.basetool.backend.model.dto.PromotionLevelContentResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PromotionLevelContentWriteRequest;
import de.greluc.krt.profit.basetool.backend.service.PromotionLevelContentService;
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

/** REST endpoints for {@code PromotionLevelContent} management. */
@RestController
@RequestMapping("/api/v1/promotion/level-contents")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(
    name = "Promotion Level Contents",
    description = "Manage level content definitions for promotion categories.")
@SecurityRequirement(name = "bearer-jwt")
public class PromotionLevelContentController {

  private final PromotionLevelContentService service;

  /**
   * Returns a paginated list of every {@link PromotionLevelContentResponse} across all categories
   * with sort fields restricted to {@link PromotionLevelContentService#SORTABLE_FIELDS}.
   *
   * @param page zero-based page index, or {@code null} for the default
   * @param size page size, or {@code null} for the default
   * @param sort comma-separated sort spec ({@code field,direction}), or {@code null} for the
   *     default
   * @return a {@link PageResponse} of level contents
   */
  @GetMapping
  @Operation(summary = "List all level contents (paginated).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Paginated list of level contents."),
    @ApiResponse(responseCode = "401", description = "Authentication required.")
  })
  public PageResponse<PromotionLevelContentResponse> list(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page,
            size,
            sort,
            PromotionLevelContentService.SORTABLE_FIELDS,
            PromotionLevelContentService.DEFAULT_SORT_FIELD);
    return PageResponse.of(service.list(pageable));
  }

  /**
   * Returns every {@link PromotionLevelContentResponse} attached to the given category, used to
   * render the per-rank expectations table for a single evaluation category.
   *
   * @param categoryId identifier of the parent {@link
   *     de.greluc.krt.profit.basetool.backend.model.PromotionCategory}
   * @return the level contents bound to that category
   */
  @GetMapping("/by-category/{categoryId}")
  @Operation(summary = "List all level contents for a specific category.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Level contents for the category.")
  })
  public List<PromotionLevelContentResponse> listByCategory(@PathVariable UUID categoryId) {
    return service.listByCategory(categoryId);
  }

  /**
   * Returns a single {@link PromotionLevelContentResponse} by identifier, or HTTP 404 if it does
   * not exist.
   *
   * @param id identifier of the level content
   * @return the matching level content
   */
  @GetMapping("/{id}")
  @Operation(summary = "Get a single level content by ID.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Level content found."),
    @ApiResponse(responseCode = "404", description = "Not found.")
  })
  public PromotionLevelContentResponse get(@PathVariable UUID id) {
    return service.get(id);
  }

  /**
   * Persists a new {@link de.greluc.krt.profit.basetool.backend.model.PromotionLevelContent},
   * binding the expectations of a single rank level to the referenced category. Restricted to ADMIN
   * or OFFICER callers.
   *
   * @param request validated payload describing the new level content
   * @return the persisted level content in its response form
   */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Create a new level content. Requires ADMIN or OFFICER role.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Level content created."),
    @ApiResponse(responseCode = "400", description = "Validation failed."),
    @ApiResponse(responseCode = "403", description = "Insufficient permissions."),
    @ApiResponse(responseCode = "404", description = "Referenced category not found.")
  })
  public PromotionLevelContentResponse create(
      @Valid @RequestBody PromotionLevelContentWriteRequest request) {
    return service.create(request);
  }

  /**
   * Updates the level content identified by {@code id}. The {@code version} field in the request
   * body guards against concurrent edits and surfaces as HTTP 409 on conflict.
   *
   * @param id identifier of the level content to update
   * @param request validated payload with the new field values and the previously fetched {@code
   *     version}
   * @return the updated level content in its response form
   */
  @PutMapping("/{id}")
  @Operation(summary = "Update a level content. Requires ADMIN or OFFICER role.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Level content updated."),
    @ApiResponse(responseCode = "400", description = "Validation failed."),
    @ApiResponse(responseCode = "404", description = "Not found."),
    @ApiResponse(responseCode = "409", description = "Optimistic lock conflict.")
  })
  public PromotionLevelContentResponse update(
      @PathVariable UUID id,
      @Validated({Default.class, OnUpdate.class}) @RequestBody
          PromotionLevelContentWriteRequest request) {
    return service.update(id, request);
  }

  /**
   * Permanently removes the level content identified by {@code id}. Restricted to ADMIN or OFFICER
   * callers.
   *
   * @param id identifier of the level content to delete
   */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Delete a level content. Requires ADMIN or OFFICER role.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Deleted."),
    @ApiResponse(responseCode = "404", description = "Not found.")
  })
  public void delete(@PathVariable UUID id) {
    service.delete(id);
  }
}
