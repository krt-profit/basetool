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

import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintCraftabilityDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportApplyRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportPreviewDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportResultDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintBatchCreateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintBatchResult;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintBulkDeleteResult;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintCreateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintRecipeResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintUpdateRequest;
import de.greluc.krt.profit.basetool.backend.service.BlueprintCraftabilityService;
import de.greluc.krt.profit.basetool.backend.service.BlueprintImportService;
import de.greluc.krt.profit.basetool.backend.service.PersonalBlueprintService;
import de.greluc.krt.profit.basetool.backend.service.UserService;
import de.greluc.krt.profit.basetool.backend.web.CurrentUserSub;
import de.greluc.krt.profit.basetool.backend.web.PaginationUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
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
 * REST endpoints for the user-facing personal-blueprint set (#327). Every method derives the owner
 * from the JWT {@code sub} and never accepts it from the request, enforcing per-user data
 * isolation.
 */
@RestController
@RequestMapping("/api/v1/personal-blueprints")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "Personal Blueprints", description = "Per-user owned crafting blueprints (#327).")
@SecurityRequirement(name = "bearerAuth")
public class PersonalBlueprintController {

  private final PersonalBlueprintService service;
  private final BlueprintImportService importService;
  private final BlueprintCraftabilityService craftabilityService;
  private final UserService userService;

  /**
   * Lists the caller's owned blueprints (paginated, sortable, optional product-name filter).
   *
   * @param page optional zero-based page index
   * @param size optional page size
   * @param sort optional sort expression over the whitelist
   * @param q optional case-insensitive product-name filter
   * @param ownerSub the caller's JWT {@code sub} claim
   * @return paged response DTOs
   */
  @GetMapping
  @Operation(summary = "List the caller's owned blueprints (paginated, sortable, name filter).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Paginated list of the caller's blueprints."),
    @ApiResponse(responseCode = "401", description = "Authentication required.")
  })
  public PageResponse<PersonalBlueprintResponse> list(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) String q,
      @CurrentUserSub String ownerSub) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page,
            size,
            sort,
            PersonalBlueprintService.SORTABLE_FIELDS,
            PersonalBlueprintService.DEFAULT_SORT_FIELD);
    Page<PersonalBlueprintResponse> result = service.listOwn(ownerSub, q, pageable);
    return PageResponse.of(result);
  }

  /**
   * Adds a single blueprint to the caller's owned set.
   *
   * @param request the add payload
   * @param ownerSub the caller's JWT {@code sub} claim
   * @return the persisted DTO
   */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Add a blueprint to the caller's owned set.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Blueprint added."),
    @ApiResponse(responseCode = "400", description = "Validation failed."),
    @ApiResponse(responseCode = "404", description = "Product key matches no active product."),
    @ApiResponse(responseCode = "409", description = "Blueprint already owned.")
  })
  public PersonalBlueprintResponse add(
      @Valid @RequestBody PersonalBlueprintCreateRequest request, @CurrentUserSub String ownerSub) {
    return service.add(ownerSub, request);
  }

  /**
   * Adds several blueprints in one call (multi-select). Already-owned / unresolvable keys are
   * skipped, not rejected; the response summarizes the outcome.
   *
   * @param request the batch of product keys
   * @param ownerSub the caller's JWT {@code sub} claim
   * @return a summary of added vs. skipped keys
   */
  @PostMapping("/batch")
  @Operation(summary = "Add several blueprints at once (multi-select); skips owned/unknown keys.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Batch processed; see the summary."),
    @ApiResponse(responseCode = "400", description = "Validation failed.")
  })
  public PersonalBlueprintBatchResult addBatch(
      @Valid @RequestBody PersonalBlueprintBatchCreateRequest request,
      @CurrentUserSub String ownerSub) {
    return service.addBatch(ownerSub, request.productKeys());
  }

  /**
   * Updates an owned blueprint's acquisition date / note.
   *
   * @param id entry id
   * @param request the update payload (carries the expected version)
   * @param ownerSub the caller's JWT {@code sub} claim
   * @return the persisted DTO
   */
  @PutMapping("/{id}")
  @Operation(summary = "Update an owned blueprint's acquisition date / note.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Blueprint updated."),
    @ApiResponse(responseCode = "400", description = "Validation failed."),
    @ApiResponse(responseCode = "404", description = "Not found or not owned by caller."),
    @ApiResponse(responseCode = "409", description = "Optimistic lock conflict.")
  })
  public PersonalBlueprintResponse update(
      @PathVariable UUID id,
      @Valid @RequestBody PersonalBlueprintUpdateRequest request,
      @CurrentUserSub String ownerSub) {
    return service.update(ownerSub, id, request);
  }

  /**
   * Removes a blueprint from the caller's owned set.
   *
   * @param id entry id
   * @param ownerSub the caller's JWT {@code sub} claim
   */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Remove a blueprint from the caller's owned set.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Blueprint removed."),
    @ApiResponse(responseCode = "404", description = "Not found or not owned by caller.")
  })
  public void delete(@PathVariable UUID id, @CurrentUserSub String ownerSub) {
    service.delete(ownerSub, id);
  }

  /**
   * Clears the caller's entire <em>removable</em> owned-blueprint set in one call — the "delete all
   * my blueprints" action (REQ-INV-023). The auto-granted, non-removable default blueprints
   * (REQ-INV-016) are preserved. Returns the number of blueprints removed so the UI can confirm the
   * outcome; a set that held only defaults (or was already empty) yields {@code 0}.
   *
   * @param ownerSub the caller's JWT {@code sub} claim
   * @return the count of removed blueprints
   */
  @DeleteMapping
  @Operation(
      summary = "Clear the caller's removable owned blueprints (keeps auto-granted defaults).")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Removable blueprints cleared; the removed count is returned."),
    @ApiResponse(responseCode = "401", description = "Authentication required.")
  })
  public PersonalBlueprintBulkDeleteResult deleteAll(@CurrentUserSub String ownerSub) {
    return new PersonalBlueprintBulkDeleteResult(service.deleteAllOwn(ownerSub));
  }

  /**
   * Returns the SC Wiki recipe graph (ingredients + per-quality stat contributions) of one of the
   * caller's owned blueprints, backing the Personal Inventory blueprint view's expandable "Zutaten
   * &amp; Stats" detail (#327). Owner-scoped: a foreign or unknown id yields 404.
   *
   * @param id owned-blueprint entry id
   * @param ownerSub the caller's JWT {@code sub} claim
   * @return the recipe view for the owned product
   */
  @GetMapping("/{id}/recipe")
  @Operation(
      summary =
          "Get the recipe (ingredients + per-quality stat contributions) of an owned blueprint.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Recipe view for the owned blueprint."),
    @ApiResponse(responseCode = "401", description = "Authentication required."),
    @ApiResponse(responseCode = "404", description = "Not found or not owned by caller.")
  })
  public PersonalBlueprintRecipeResponse recipe(
      @PathVariable UUID id, @CurrentUserSub String ownerSub) {
    return service.recipeForOwn(ownerSub, id);
  }

  /**
   * Returns, for every blueprint the caller owns, whether and how many times it can be crafted from
   * the caller's own "My Inventory" stock — the craftability annotation of the Personal Inventory
   * blueprint view (#781, REQ-INV-019). Strictly owner-scoped: owned blueprints, stock and refinery
   * yield all come from the caller. Read-only; RESOURCE ingredients and the PIECE-material-bridged
   * ITEM ingredients (hand-mined gems, ADR-0046) are evaluated, craftable sub-assemblies and
   * unresolved items are not.
   *
   * @param includeRefinery whether to fold the caller's {@code OPEN}/{@code IN_PROGRESS} refinery
   *     yield into the {@code *WithRefinery} figures (default {@code false})
   * @param ownerSub the caller's JWT {@code sub} claim
   * @param auth the caller's JWT authentication
   * @return one craftability entry per owned blueprint
   */
  @GetMapping("/craftability")
  @Operation(
      summary =
          "Craftability of the caller's owned blueprints from their own stock (RESOURCE + bridged"
              + " PIECE items).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Per-blueprint craftability for the caller."),
    @ApiResponse(responseCode = "401", description = "Authentication required.")
  })
  public List<BlueprintCraftabilityDto> craftability(
      @RequestParam(name = "includeRefinery", required = false, defaultValue = "false")
          boolean includeRefinery,
      @CurrentUserSub String ownerSub,
      JwtAuthenticationToken auth) {
    UUID userId = userService.getUserIdFromJwt(auth.getToken());
    return craftabilityService.computeForOwner(ownerSub, userId, includeRefinery);
  }

  /**
   * Previews a blueprint export import (SCMDB log-watcher or Basetool Blueprint Extractor JSON):
   * parses the uploaded file, matches each blueprint name against the master product list, and
   * returns per-name resolution rows for the caller to review. Nothing is persisted.
   *
   * @param file the uploaded blueprint export JSON
   * @param ownerSub the caller's JWT {@code sub} claim
   * @return the per-name preview with status counts
   */
  @PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @Operation(summary = "Preview a blueprint import (SCMDB or BP Extractor JSON; no writes).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Preview computed."),
    @ApiResponse(responseCode = "400", description = "File empty, malformed, or wrong format."),
    @ApiResponse(responseCode = "401", description = "Authentication required.")
  })
  public BlueprintImportPreviewDto previewImport(
      @RequestParam("file") @NotNull MultipartFile file, @CurrentUserSub String ownerSub) {
    return importService.previewImport(ownerSub, file);
  }

  /**
   * Applies the caller's reviewed import resolutions: creates the missing owned-blueprint rows and
   * learns an alias for every manual pick. Blank or unresolvable choices are skipped.
   *
   * @param request the per-name resolutions
   * @param ownerSub the caller's JWT {@code sub} claim
   * @return a summary of added / learned / skipped / already-owned counts
   */
  @PostMapping("/import/apply")
  @Operation(summary = "Apply reviewed import resolutions; learns aliases for manual picks.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Import applied; see the summary."),
    @ApiResponse(responseCode = "400", description = "Validation failed."),
    @ApiResponse(responseCode = "401", description = "Authentication required.")
  })
  public BlueprintImportResultDto applyImport(
      @Valid @RequestBody BlueprintImportApplyRequest request, @CurrentUserSub String ownerSub) {
    return importService.applyImport(ownerSub, request.resolutions());
  }
}
