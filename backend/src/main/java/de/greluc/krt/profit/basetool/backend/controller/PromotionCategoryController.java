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
import de.greluc.krt.profit.basetool.backend.model.dto.PromotionCategoryResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PromotionCategoryWriteRequest;
import de.greluc.krt.profit.basetool.backend.service.PromotionCategoryService;
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

/** REST endpoints for {@code PromotionCategory} management. */
@RestController
@RequestMapping("/api/v1/promotion/categories")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "Promotion Categories", description = "Manage promotion system evaluation categories.")
@SecurityRequirement(name = "bearer-jwt")
public class PromotionCategoryController {

  private final PromotionCategoryService service;

  /**
   * Returns a paginated list of every {@link PromotionCategoryResponse} across all topics with sort
   * fields restricted to {@link PromotionCategoryService#SORTABLE_FIELDS}.
   *
   * @param page zero-based page index, or {@code null} for the default
   * @param size page size, or {@code null} for the default
   * @param sort comma-separated sort spec ({@code field,direction}), or {@code null} for the
   *     default
   * @return a {@link PageResponse} of categories
   */
  @GetMapping
  @Operation(summary = "List all promotion categories (paginated).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Paginated list of categories."),
    @ApiResponse(responseCode = "401", description = "Authentication required.")
  })
  public PageResponse<PromotionCategoryResponse> list(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page,
            size,
            sort,
            PromotionCategoryService.SORTABLE_FIELDS,
            PromotionCategoryService.DEFAULT_SORT_FIELD);
    return PageResponse.of(service.list(pageable));
  }

  /**
   * Returns a paginated list of the {@link PromotionCategoryResponse} entries that belong to the
   * given parent {@link de.greluc.krt.profit.basetool.backend.model.PromotionTopic}.
   *
   * @param topicId identifier of the parent promotion topic
   * @param page zero-based page index, or {@code null} for the default
   * @param size page size, or {@code null} for the default
   * @param sort comma-separated sort spec ({@code field,direction}), or {@code null} for the
   *     default
   * @return a {@link PageResponse} of categories scoped to the topic
   */
  @GetMapping("/by-topic/{topicId}")
  @Operation(summary = "List categories for a specific topic (paginated).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Paginated list of categories for the topic.")
  })
  public PageResponse<PromotionCategoryResponse> listByTopic(
      @PathVariable UUID topicId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page,
            size,
            sort,
            PromotionCategoryService.SORTABLE_FIELDS,
            PromotionCategoryService.DEFAULT_SORT_FIELD);
    return PageResponse.of(service.listByTopic(topicId, pageable));
  }

  /**
   * Returns every {@link PromotionCategoryResponse} for the given topic ordered by {@code
   * sortOrder}, used by the promotion UI to render the full evaluation table without pagination.
   *
   * @param topicId identifier of the parent promotion topic
   * @return the topic's categories in display order
   */
  @GetMapping("/by-topic/{topicId}/all")
  @Operation(
      summary = "List all categories for a specific topic ordered by sortOrder (no pagination).")
  @ApiResponses({@ApiResponse(responseCode = "200", description = "All categories for the topic.")})
  public List<PromotionCategoryResponse> listAllByTopic(@PathVariable UUID topicId) {
    return service.listAllByTopic(topicId);
  }

  /**
   * Returns a single {@link PromotionCategoryResponse} by identifier, or HTTP 404 if it does not
   * exist.
   *
   * @param id identifier of the promotion category
   * @return the matching category
   */
  @GetMapping("/{id}")
  @Operation(summary = "Get a single promotion category by ID.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Category found."),
    @ApiResponse(responseCode = "404", description = "Category not found.")
  })
  public PromotionCategoryResponse get(@PathVariable UUID id) {
    return service.get(id);
  }

  /**
   * Persists a new {@link de.greluc.krt.profit.basetool.backend.model.PromotionCategory} attached
   * to the topic referenced by the request. Restricted to ADMIN or OFFICER callers.
   *
   * @param request validated payload describing the new category
   * @return the persisted category in its response form
   */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Create a new promotion category. Requires ADMIN or OFFICER role.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Category created."),
    @ApiResponse(responseCode = "400", description = "Validation failed."),
    @ApiResponse(responseCode = "403", description = "Insufficient permissions."),
    @ApiResponse(responseCode = "404", description = "Referenced topic not found.")
  })
  public PromotionCategoryResponse create(
      @Valid @RequestBody PromotionCategoryWriteRequest request) {
    return service.create(request);
  }

  /**
   * Updates the {@link de.greluc.krt.profit.basetool.backend.model.PromotionCategory} identified by
   * {@code id}. The {@code version} field in the request body guards against concurrent edits and
   * surfaces as HTTP 409 on conflict.
   *
   * @param id identifier of the category to update
   * @param request validated payload with the new field values and the previously fetched {@code
   *     version}
   * @return the updated category in its response form
   */
  @PutMapping("/{id}")
  @Operation(summary = "Update a promotion category. Requires ADMIN or OFFICER role.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Category updated."),
    @ApiResponse(responseCode = "400", description = "Validation failed."),
    @ApiResponse(responseCode = "404", description = "Category or topic not found."),
    @ApiResponse(responseCode = "409", description = "Optimistic lock conflict.")
  })
  public PromotionCategoryResponse update(
      @PathVariable UUID id,
      @Validated({Default.class, OnUpdate.class}) @RequestBody
          PromotionCategoryWriteRequest request) {
    return service.update(id, request);
  }

  /**
   * Permanently removes the promotion category identified by {@code id}. Restricted to ADMIN or
   * OFFICER callers.
   *
   * @param id identifier of the category to delete
   */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Delete a promotion category. Requires ADMIN or OFFICER role.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Category deleted."),
    @ApiResponse(responseCode = "404", description = "Category not found.")
  })
  public void delete(@PathVariable UUID id) {
    service.delete(id);
  }
}
