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

package de.greluc.krt.profit.basetool.backend.catalogue.web;

import de.greluc.krt.profit.basetool.backend.catalogue.internal.StarmapAdminService;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.StarmapLinkAction;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.StarmapObjectAdminDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.StarmapPlaceKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.StarmapPlaceLinkDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.StarmapVisibilityOverride;
import de.greluc.krt.profit.basetool.backend.kernel.PageResponse;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import de.greluc.krt.profit.basetool.backend.platform.api.PaginationUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only starmap endpoints (REQ-P4K-034…038, ADR-0345).
 *
 * <ul>
 *   <li>{@code GET /objects} — the starmap objects with their visibility, filtered and paged.
 *   <li>{@code PUT /objects/{id}/visibility} — set or clear an object's visibility override.
 *   <li>{@code GET /places/{kind}} — one UEX location table's rows with their links.
 *   <li>{@code PUT /places/{kind}/{id}/link} — set, clear or release a row's link by hand.
 * </ul>
 *
 * <p>A storage place on an object is created through {@code POST /api/v1/locations}.
 */
@RestController
@RequestMapping("/api/v1/catalog/admin/starmap")
@RequiredArgsConstructor
@PreAuthorize(Roles.HAS_ROLE_ADMIN)
@Tag(
    name = "Admin – Starmap",
    description =
        "Administrator endpoints for the P4K starmap tree, its visibility and the links of the UEX"
            + " locations to it.")
@SecurityRequirement(name = "bearer-jwt")
public class AdminStarmapController {

  private final StarmapAdminService service;

  /**
   * An admin's visibility override.
   *
   * @param override the override, or {@code null} to let the rule decide
   * @param version the object version the admin saw
   */
  public record StarmapVisibilityRequest(
      @Nullable StarmapVisibilityOverride override, @NotNull Long version) {}

  /**
   * An admin's change of a UEX location row's link, compare-and-set on the link the admin saw.
   *
   * @param action set, clear or release
   * @param starmapObjectId the object to link for {@code SET}, else ignored
   * @param expectedStarmapObjectId the link the admin saw, or {@code null}
   * @param expectedManual the manual flag the admin saw
   */
  public record StarmapLinkRequest(
      @NotNull StarmapLinkAction action,
      @Nullable UUID starmapObjectId,
      @Nullable UUID expectedStarmapObjectId,
      @NotNull Boolean expectedManual) {}

  /**
   * Pages the starmap objects.
   *
   * @param starSystem the star system folder, or all
   * @param type the type, or all
   * @param query a fragment of the class name or either name, or none
   * @param page zero-based page index
   * @param size page size
   * @param sort sort terms over {@code className}, {@code type} and {@code starSystem}
   * @return the page of objects
   */
  @GetMapping("/objects")
  @Operation(summary = "List the P4K starmap objects with their visibility.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "The objects."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator.")
  })
  public PageResponse<StarmapObjectAdminDto> objects(
      @RequestParam(required = false) @Nullable String starSystem,
      @RequestParam(required = false) @Nullable String type,
      @RequestParam(required = false) @Nullable String query,
      @RequestParam(required = false) @Nullable Integer page,
      @RequestParam(required = false) @Nullable Integer size,
      @RequestParam(required = false) @Nullable String sort) {
    return PageResponse.of(
        service.objects(
            starSystem,
            type,
            StringNormalization.trimToNull(query),
            PaginationUtil.createPageRequest(
                page, size, sort, Set.of("className", "type", "starSystem"), "className")));
  }

  /**
   * Sets or clears an object's visibility override.
   *
   * @param id the object
   * @param request the override and the version the admin saw
   * @return the object after the change
   */
  @PutMapping("/objects/{id}/visibility")
  @Operation(summary = "Set a starmap object visible or hidden, or back to the rule.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "The object."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator."),
    @ApiResponse(responseCode = "404", description = "No object with the given id."),
    @ApiResponse(responseCode = "409", description = "The object changed since it was read.")
  })
  public StarmapObjectAdminDto overrideVisibility(
      @PathVariable("id") UUID id, @RequestBody @Valid StarmapVisibilityRequest request) {
    return service.overrideVisibility(id, request.override(), request.version());
  }

  /**
   * Pages the rows of one UEX location table with their links.
   *
   * @param kind the table
   * @param query a fragment of the row's name, or none
   * @param unlinkedOnly whether only unlinked rows are listed
   * @param page zero-based page index
   * @param size page size
   * @return the page of rows, ordered by name
   */
  @GetMapping("/places/{kind}")
  @Operation(summary = "List one UEX location table's rows with their starmap links.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "The rows."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator.")
  })
  public PageResponse<StarmapPlaceLinkDto> places(
      @PathVariable("kind") StarmapPlaceKind kind,
      @RequestParam(required = false) @Nullable String query,
      @RequestParam(required = false, defaultValue = "false") boolean unlinkedOnly,
      @RequestParam(required = false) @Nullable Integer page,
      @RequestParam(required = false) @Nullable Integer size) {
    return PageResponse.of(
        service.places(
            kind, query, unlinkedOnly, PaginationUtil.createUnsortedPageRequest(page, size)));
  }

  /**
   * Sets, clears or releases a row's link by hand.
   *
   * @param kind the table
   * @param id the row
   * @param request the change and the link the admin saw
   * @return the row after the change
   */
  @PutMapping("/places/{kind}/{id}/link")
  @Operation(summary = "Set, clear or release a UEX location's starmap link by hand.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "The row."),
    @ApiResponse(responseCode = "400", description = "A link names no object."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator."),
    @ApiResponse(responseCode = "404", description = "No row or object with the given id."),
    @ApiResponse(responseCode = "409", description = "The row's link changed since it was read.")
  })
  public StarmapPlaceLinkDto changeLink(
      @PathVariable("kind") StarmapPlaceKind kind,
      @PathVariable("id") UUID id,
      @RequestBody @Valid StarmapLinkRequest request) {
    return service.changeLink(
        kind,
        id,
        request.action(),
        request.starmapObjectId(),
        request.expectedStarmapObjectId(),
        request.expectedManual());
  }
}
