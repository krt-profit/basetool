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

import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportApplyRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportPreviewDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportResultDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintBatchCreateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintBatchResult;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintBulkDeleteResult;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintCreateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintUpdateRequest;
import de.greluc.krt.profit.basetool.backend.service.BlueprintImportService;
import de.greluc.krt.profit.basetool.backend.service.PersonalBlueprintService;
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
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.multipart.MultipartFile;

/**
 * Admin-only counterpart of {@link PersonalBlueprintController} (#327, Phase 7): lets
 * administrators manage any user's acquired blueprints and run the import on their behalf. The
 * target user is taken from the URL path ({@code /{userSub}}) instead of from the JWT; the {@code
 * ADMIN} role is enforced at this boundary while the delegated services stay {@code
 * sub}-parameterised.
 */
@RestController
@RequestMapping("/api/v1/admin/personal-blueprints")
@RequiredArgsConstructor
@PreAuthorize(Roles.HAS_ROLE_ADMIN)
@Tag(
    name = "Admin – Personal Blueprints",
    description = "Administrator endpoints for managing any user's acquired blueprints (#327).")
@SecurityRequirement(name = "bearerAuth")
@Slf4j
public class AdminPersonalBlueprintController {

  private final PersonalBlueprintService service;
  private final BlueprintImportService importService;

  /**
   * Lists a target user's owned blueprints (paginated, sortable, optional name filter).
   *
   * @param userSub target user's {@code app_user.id}
   * @param page optional zero-based page index
   * @param size optional page size
   * @param sort optional sort expression over the whitelist
   * @param q optional case-insensitive product-name filter
   * @return paged response DTOs
   */
  @GetMapping("/{userSub}")
  @Operation(summary = "List a specific user's owned blueprints.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Paginated list."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator.")
  })
  public PageResponse<PersonalBlueprintResponse> listForUser(
      @PathVariable UUID userSub,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) String q) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page,
            size,
            sort,
            PersonalBlueprintService.SORTABLE_FIELDS,
            PersonalBlueprintService.DEFAULT_SORT_FIELD);
    Page<PersonalBlueprintResponse> result = service.listForUser(userSub, q, pageable);
    return PageResponse.of(result);
  }

  /**
   * Adds a single blueprint on behalf of the target user.
   *
   * @param userSub target user's {@code app_user.id}
   * @param request the add payload
   * @return the persisted DTO
   */
  @PostMapping("/{userSub}")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Add a blueprint on behalf of the given user.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Blueprint added."),
    @ApiResponse(responseCode = "400", description = "Validation failed."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator."),
    @ApiResponse(responseCode = "404", description = "Product key matches no active product."),
    @ApiResponse(responseCode = "409", description = "Blueprint already owned.")
  })
  public PersonalBlueprintResponse addForUser(
      @PathVariable UUID userSub, @Valid @RequestBody PersonalBlueprintCreateRequest request) {
    return service.addForUser(userSub, request);
  }

  /**
   * Multi-select add on behalf of the target user; already-owned / unresolvable keys are skipped.
   *
   * @param userSub target user's {@code app_user.id}
   * @param request the batch of product keys
   * @return a summary of added vs. skipped keys
   */
  @PostMapping("/{userSub}/batch")
  @Operation(summary = "Add several blueprints on behalf of the given user (multi-select).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Batch processed; see the summary."),
    @ApiResponse(responseCode = "400", description = "Validation failed."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator.")
  })
  public PersonalBlueprintBatchResult addBatchForUser(
      @PathVariable UUID userSub, @Valid @RequestBody PersonalBlueprintBatchCreateRequest request) {
    return service.addBatchForUser(userSub, request.productKeys());
  }

  /**
   * Updates any owned blueprint by id (admins are trusted to know the id).
   *
   * @param id blueprint entry id
   * @param request the update payload (carries the expected version)
   * @return the persisted DTO
   */
  @PutMapping("/items/{id}")
  @Operation(summary = "Update any owned blueprint by id.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Blueprint updated."),
    @ApiResponse(responseCode = "400", description = "Validation failed."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator."),
    @ApiResponse(responseCode = "404", description = "Blueprint not found."),
    @ApiResponse(responseCode = "409", description = "Optimistic lock conflict.")
  })
  public PersonalBlueprintResponse updateForUser(
      @PathVariable UUID id, @Valid @RequestBody PersonalBlueprintUpdateRequest request) {
    return service.updateForUser(id, request);
  }

  /**
   * Deletes any owned blueprint by id. The owner sub is logged at INFO for the audit trail.
   *
   * @param id blueprint entry id
   */
  @DeleteMapping("/items/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Delete any owned blueprint by id.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Blueprint removed."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator."),
    @ApiResponse(responseCode = "404", description = "Blueprint not found.")
  })
  public void deleteForUser(@PathVariable UUID id) {
    service.deleteForUser(id);
  }

  /**
   * Admin global purge: clears the <em>removable</em> owned blueprints of <strong>all</strong>
   * users in one call — the "delete all users' blueprints" action (REQ-INV-024). The auto-granted,
   * non-removable default blueprints (REQ-INV-016) are preserved. The UI guards this with a
   * type-to-confirm warning; ADMIN is enforced at the class boundary. Returns the number of
   * blueprints removed across all users.
   *
   * @return the count of removed blueprints across all users
   */
  @DeleteMapping
  @Operation(
      summary = "Clear every user's removable owned blueprints (keeps auto-granted defaults).")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "All users' removable blueprints cleared; the removed count is returned."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator.")
  })
  public PersonalBlueprintBulkDeleteResult deleteAllForAllUsers() {
    return new PersonalBlueprintBulkDeleteResult(service.deleteAllForAllUsers());
  }

  /**
   * Previews a blueprint export import (SCMDB or Basetool BP Extractor) on behalf of the target
   * user. Nothing is persisted.
   *
   * @param userSub target user's {@code app_user.id}
   * @param file the uploaded blueprint export JSON
   * @return the per-name resolution preview
   */
  @PostMapping(value = "/{userSub}/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @Operation(summary = "Preview a blueprint import for the given user (no writes).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Preview computed."),
    @ApiResponse(responseCode = "400", description = "File empty, malformed, or wrong format."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator.")
  })
  public BlueprintImportPreviewDto previewImportForUser(
      @PathVariable UUID userSub, @RequestParam("file") @NotNull MultipartFile file) {
    return importService.previewImport(userSub, file);
  }

  /**
   * Applies reviewed blueprint-import resolutions on behalf of the target user.
   *
   * @param userSub target user's {@code app_user.id}
   * @param request the per-name resolutions
   * @return a summary of added / learned / skipped / already-owned counts
   */
  @PostMapping("/{userSub}/import/apply")
  @Operation(summary = "Apply reviewed import resolutions for the given user.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Import applied; see the summary."),
    @ApiResponse(responseCode = "400", description = "Validation failed."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator.")
  })
  public BlueprintImportResultDto applyImportForUser(
      @PathVariable UUID userSub, @Valid @RequestBody BlueprintImportApplyRequest request) {
    return importService.applyImport(userSub, request.resolutions());
  }
}
