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
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalInventoryItemCreateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalInventoryItemResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalInventoryItemUpdateRequest;
import de.greluc.krt.profit.basetool.backend.service.PersonalInventoryItemService;
import de.greluc.krt.profit.basetool.backend.web.CurrentUserId;
import de.greluc.krt.profit.basetool.backend.web.PaginationUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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

/**
 * REST endpoints for the user-facing personal inventory. Every method enforces data isolation by
 * deriving the owner identifier from the JWT {@code sub} claim and never accepting it from the
 * request body.
 */
@RestController
@RequestMapping("/api/v1/personal-inventory")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "Personal Inventory", description = "Per-user personal inventory entries.")
@SecurityRequirement(name = "bearerAuth")
@Slf4j
public class PersonalInventoryController {

  private final PersonalInventoryItemService service;

  /**
   * Lists the caller's own personal-inventory items. Owner is taken from the JWT {@code sub} —
   * never from the request body — so a caller cannot view another user's items.
   *
   * @return paged response DTOs
   */
  @GetMapping
  @Operation(
      summary = "List own personal inventory entries (paginated, sortable, optional name filter).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Paginated list of the caller's items."),
    @ApiResponse(responseCode = "401", description = "Authentication required.")
  })
  public PageResponse<PersonalInventoryItemResponse> list(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) String q,
      @CurrentUserId UUID ownerSub) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page,
            size,
            sort,
            PersonalInventoryItemService.SORTABLE_FIELDS,
            PersonalInventoryItemService.DEFAULT_SORT_FIELD);
    Page<PersonalInventoryItemResponse> result = service.listOwn(ownerSub, q, pageable);
    return PageResponse.of(result);
  }

  /**
   * Fetches one of the caller's own items. 404 for unknown id OR cross-owner attempt (the two cases
   * are intentionally indistinguishable on the wire).
   *
   * @param id item id
   * @return the item DTO
   */
  @GetMapping("/{id}")
  @Operation(summary = "Fetch a single personal inventory entry owned by the caller.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Item found."),
    @ApiResponse(responseCode = "404", description = "Not found or not owned by caller.")
  })
  public PersonalInventoryItemResponse get(@PathVariable UUID id, @CurrentUserId UUID ownerSub) {
    return service.getOwn(ownerSub, id);
  }

  /**
   * Creates a new personal-inventory item owned by the caller.
   *
   * @param request create payload
   * @return the persisted DTO
   */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Create a new personal inventory entry for the caller.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Item created."),
    @ApiResponse(responseCode = "400", description = "Validation failed."),
    @ApiResponse(responseCode = "404", description = "Referenced UEX location does not exist.")
  })
  public PersonalInventoryItemResponse create(
      @Valid @RequestBody PersonalInventoryItemCreateRequest request,
      @CurrentUserId UUID ownerSub) {
    return service.createOwn(ownerSub, request);
  }

  /**
   * Updates one of the caller's own items.
   *
   * @param id item id
   * @param request update payload (carries the expected version)
   * @return the persisted DTO
   */
  @PutMapping("/{id}")
  @Operation(summary = "Update an existing personal inventory entry owned by the caller.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Item updated."),
    @ApiResponse(responseCode = "400", description = "Validation failed."),
    @ApiResponse(
        responseCode = "404",
        description = "Item not found, not owned, or location unknown."),
    @ApiResponse(responseCode = "409", description = "Optimistic lock conflict.")
  })
  public PersonalInventoryItemResponse update(
      @PathVariable UUID id,
      @Valid @RequestBody PersonalInventoryItemUpdateRequest request,
      @CurrentUserId UUID ownerSub) {
    return service.updateOwn(ownerSub, id, request);
  }

  /**
   * Deletes one of the caller's own items.
   *
   * @param id item id
   */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Delete one of the caller's personal inventory entries.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Item deleted."),
    @ApiResponse(responseCode = "404", description = "Not found or not owned by caller.")
  })
  public void delete(@PathVariable UUID id, @CurrentUserId UUID ownerSub) {
    service.deleteOwn(ownerSub, id);
  }
}
