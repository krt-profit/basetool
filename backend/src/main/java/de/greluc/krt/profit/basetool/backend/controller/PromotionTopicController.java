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
import de.greluc.krt.profit.basetool.backend.model.dto.PromotionTopicResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PromotionTopicWriteRequest;
import de.greluc.krt.profit.basetool.backend.service.PromotionTopicService;
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

/** REST endpoints for {@code PromotionTopic} management. */
@RestController
@RequestMapping("/api/v1/promotion/topics")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "Promotion Topics", description = "Manage promotion system topic groups.")
@SecurityRequirement(name = "bearer-jwt")
public class PromotionTopicController {

  private final PromotionTopicService service;

  /**
   * Returns a paginated list of every {@link PromotionTopicResponse} with sort fields restricted to
   * {@link PromotionTopicService#SORTABLE_FIELDS}.
   *
   * @param page zero-based page index, or {@code null} for the default
   * @param size page size, or {@code null} for the default
   * @param sort comma-separated sort spec ({@code field,direction}), or {@code null} for the
   *     default
   * @return a {@link PageResponse} of promotion topics
   */
  @GetMapping
  @Operation(summary = "List all promotion topics (paginated).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Paginated list of topics."),
    @ApiResponse(responseCode = "401", description = "Authentication required.")
  })
  public PageResponse<PromotionTopicResponse> list(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page,
            size,
            sort,
            PromotionTopicService.SORTABLE_FIELDS,
            PromotionTopicService.DEFAULT_SORT_FIELD);
    return PageResponse.of(service.list(pageable));
  }

  /**
   * Returns every {@link PromotionTopicResponse} ordered by {@code sortOrder}, used by the
   * promotion UI to render the full topic list without pagination.
   *
   * @return every topic in display order
   */
  @GetMapping("/all")
  @Operation(summary = "List all promotion topics ordered by sortOrder (no pagination).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "All topics ordered by sortOrder.")
  })
  public List<PromotionTopicResponse> listAll() {
    return service.listAll();
  }

  /**
   * Returns a single {@link PromotionTopicResponse} by identifier, or HTTP 404 if it does not
   * exist.
   *
   * @param id identifier of the promotion topic
   * @return the matching topic
   */
  @GetMapping("/{id}")
  @Operation(summary = "Get a single promotion topic by ID.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Topic found."),
    @ApiResponse(responseCode = "404", description = "Topic not found.")
  })
  public PromotionTopicResponse get(@PathVariable UUID id) {
    return service.get(id);
  }

  /**
   * Persists a new {@link de.greluc.krt.profit.basetool.backend.model.PromotionTopic}. Restricted
   * to ADMIN or OFFICER callers.
   *
   * @param request validated payload describing the new topic
   * @return the persisted topic in its response form
   */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Create a new promotion topic. Requires ADMIN or OFFICER role.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Topic created."),
    @ApiResponse(responseCode = "400", description = "Validation failed."),
    @ApiResponse(responseCode = "403", description = "Insufficient permissions.")
  })
  public PromotionTopicResponse create(@Valid @RequestBody PromotionTopicWriteRequest request) {
    return service.create(request);
  }

  /**
   * Updates the promotion topic identified by {@code id}. The {@code version} field in the request
   * body guards against concurrent edits and surfaces as HTTP 409 on conflict.
   *
   * @param id identifier of the topic to update
   * @param request validated payload with the new field values and the previously fetched {@code
   *     version}
   * @return the updated topic in its response form
   */
  @PutMapping("/{id}")
  @Operation(summary = "Update a promotion topic. Requires ADMIN or OFFICER role.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Topic updated."),
    @ApiResponse(responseCode = "400", description = "Validation failed."),
    @ApiResponse(responseCode = "404", description = "Topic not found."),
    @ApiResponse(responseCode = "409", description = "Optimistic lock conflict.")
  })
  public PromotionTopicResponse update(
      @PathVariable UUID id,
      @Validated({Default.class, OnUpdate.class}) @RequestBody PromotionTopicWriteRequest request) {
    return service.update(id, request);
  }

  /**
   * Permanently removes the promotion topic identified by {@code id}, also cascading deletes to the
   * topic's categories. Restricted to ADMIN or OFFICER callers.
   *
   * @param id identifier of the topic to delete
   */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Delete a promotion topic. Requires ADMIN or OFFICER role.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Topic deleted."),
    @ApiResponse(responseCode = "404", description = "Topic not found.")
  })
  public void delete(@PathVariable UUID id) {
    service.delete(id);
  }
}
