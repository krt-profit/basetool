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

import de.greluc.krt.profit.basetool.backend.model.dto.DefaultBlueprintCreateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.DefaultBlueprintResponse;
import de.greluc.krt.profit.basetool.backend.service.DefaultBlueprintService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import de.greluc.krt.profit.basetool.backend.web.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only management of the default-blueprint set (REQ-INV-017): the products every user is
 * granted automatically (REQ-INV-016). The {@code ADMIN} role is enforced at this boundary; the
 * grant-to-everyone side effect of an add lives in the delegated service.
 */
@RestController
@RequestMapping("/api/v1/admin/default-blueprints")
@RequiredArgsConstructor
@PreAuthorize(Roles.HAS_ROLE_ADMIN)
@Tag(
    name = "Admin – Default Blueprints",
    description = "Administrator endpoints for curating the auto-granted default blueprint set.")
@SecurityRequirement(name = "bearer-jwt")
@Slf4j
public class AdminDefaultBlueprintController {

  private final DefaultBlueprintService service;

  /**
   * Lists the current default-blueprint set, alphabetically by product name.
   *
   * @return the default entries
   */
  @GetMapping
  @Operation(summary = "List the auto-granted default blueprints.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "The default set."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator.")
  })
  public List<DefaultBlueprintResponse> list() {
    return service.list();
  }

  /**
   * Adds a product to the default set and grants it to every existing user.
   *
   * @param request the product key to mark as default
   * @param adminUserId the calling admin's {@code app_user.id}, stamped as the creator
   * @return the persisted DTO
   */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Add a product to the default set and grant it to all users.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Default added and granted."),
    @ApiResponse(responseCode = "400", description = "Validation failed."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator."),
    @ApiResponse(responseCode = "404", description = "Product key matches no active product."),
    @ApiResponse(responseCode = "409", description = "Product is already a default.")
  })
  public DefaultBlueprintResponse add(
      @Valid @RequestBody DefaultBlueprintCreateRequest request, @CurrentUserId UUID adminUserId) {
    // created_by is a provenance column that also holds the literal "system" for seeded
    // rows, so it stays text and takes the rendered id rather than a foreign key.
    return service.add(request.productKey(), adminUserId.toString());
  }

  /**
   * Removes a product from the default set. Users keep blueprints already granted to them.
   *
   * @param id default-blueprint entry id
   */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Remove a product from the default set (existing grants are kept).")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Default removed."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator."),
    @ApiResponse(responseCode = "404", description = "Default not found.")
  })
  public void remove(@PathVariable UUID id) {
    service.remove(id);
  }
}
