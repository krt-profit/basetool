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
import de.greluc.krt.profit.basetool.backend.support.Roles;
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
 * Admin-only counterpart of {@link PersonalInventoryController}: lets administrators manage the
 * personal inventory of any user. The owner identifier is taken from the URL path ({@code
 * /{userId}}) instead of from the JWT.
 */
@RestController
@RequestMapping("/api/v1/admin/personal-inventory")
@RequiredArgsConstructor
@PreAuthorize(Roles.HAS_ROLE_ADMIN)
@Tag(
    name = "Admin – Personal Inventory",
    description = "Administrator endpoints for managing any user's personal inventory.")
@SecurityRequirement(name = "bearerAuth")
@Slf4j
public class AdminPersonalInventoryController {

  private final PersonalInventoryItemService service;

  /**
   * Lists a target user's personal-inventory items.
   *
   * @param userId target user's {@code app_user.id}
   * @return paged response DTOs
   */
  @GetMapping("/{userId}")
  @Operation(summary = "List a specific user's personal inventory entries.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Paginated list."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator.")
  })
  public PageResponse<PersonalInventoryItemResponse> listForUser(
      @PathVariable UUID userId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) String q) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page,
            size,
            sort,
            PersonalInventoryItemService.SORTABLE_FIELDS,
            PersonalInventoryItemService.DEFAULT_SORT_FIELD);
    Page<PersonalInventoryItemResponse> result = service.listForUser(userId, q, pageable);
    return PageResponse.of(result);
  }

  /**
   * Creates an item on behalf of the target user.
   *
   * @param userId target user's {@code app_user.id}
   * @param request create payload
   * @return the persisted DTO
   */
  @PostMapping("/{userId}")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Create a personal inventory entry on behalf of the given user.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Item created."),
    @ApiResponse(responseCode = "400", description = "Validation failed."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator."),
    @ApiResponse(responseCode = "404", description = "Referenced UEX location does not exist.")
  })
  public PersonalInventoryItemResponse createForUser(
      @PathVariable UUID userId, @Valid @RequestBody PersonalInventoryItemCreateRequest request) {
    return service.createForUser(userId, request);
  }

  /**
   * Updates any personal-inventory item by id (admins are trusted to know the id).
   *
   * @param id item id
   * @param request update payload (carries the expected version)
   * @return the persisted DTO
   */
  @PutMapping("/items/{id}")
  @Operation(summary = "Update any personal inventory entry by id.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Item updated."),
    @ApiResponse(responseCode = "400", description = "Validation failed."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator."),
    @ApiResponse(responseCode = "404", description = "Item not found or location unknown."),
    @ApiResponse(responseCode = "409", description = "Optimistic lock conflict.")
  })
  public PersonalInventoryItemResponse updateForUser(
      @PathVariable UUID id, @Valid @RequestBody PersonalInventoryItemUpdateRequest request) {
    return service.updateForUser(id, request);
  }

  /**
   * Deletes any personal-inventory item by id. Owner sub is logged at INFO for the audit trail.
   *
   * @param id item id
   */
  @DeleteMapping("/items/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Delete any personal inventory entry by id.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Item deleted."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator."),
    @ApiResponse(responseCode = "404", description = "Item not found.")
  })
  public void deleteForUser(@PathVariable UUID id) {
    service.deleteForUser(id);
  }
}
