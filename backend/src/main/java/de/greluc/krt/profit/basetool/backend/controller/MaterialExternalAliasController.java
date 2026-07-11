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

import de.greluc.krt.profit.basetool.backend.mapper.MaterialExternalAliasMapper;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExternalAliasDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExternalAliasWriteRequest;
import de.greluc.krt.profit.basetool.backend.service.MaterialExternalAliasService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import de.greluc.krt.profit.basetool.backend.validation.OnUpdate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.groups.Default;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only CRUD for the curated cross-reference table that maps external commodity names (from
 * UEX or SC Wiki) onto local {@link de.greluc.krt.profit.basetool.backend.model.Material} rows.
 *
 * <p>Class-level {@code @PreAuthorize("hasRole('ADMIN')")} gates every endpoint — the table is pure
 * reference data managed by admins; non-admins should never see or change it. The R3 SC Wiki
 * commodity sync reads the table through {@link
 * MaterialExternalAliasService#resolveMaterialByAlias} (service-layer, not HTTP), so no public read
 * endpoint is needed.
 */
@RestController
@RequestMapping("/api/v1/material-external-aliases")
@RequiredArgsConstructor
@Transactional
@PreAuthorize(Roles.HAS_ROLE_ADMIN)
public class MaterialExternalAliasController {

  private final MaterialExternalAliasService service;
  private final MaterialExternalAliasMapper mapper;

  /**
   * Returns every alias sorted by external name. Drives the admin table view.
   *
   * <p><b>Intentional deviation from the {@code Pageable} + {@code PageResponse} list
   * convention:</b> {@code material_external_alias} is a small, hand-curated reference table (a few
   * seed rows plus occasional admin additions) that the admin UI renders in full on one page.
   * Paginating it would add an empty wrapper and a sort-field whitelist for no benefit, so the
   * endpoint returns the whole list. Add pagination only if the table ever grows large enough to
   * need it.
   *
   * @return list of alias DTOs
   */
  @Operation(
      summary = "List all material external aliases",
      description = "Admin-only. Returns the curated cross-reference table, sorted alphabetically.")
  @ApiResponses({@ApiResponse(responseCode = "200", description = "Alias list returned")})
  @GetMapping
  public List<MaterialExternalAliasDto> listAliases() {
    return service.findAll().stream().map(mapper::toDto).toList();
  }

  /**
   * Returns a single alias by id.
   *
   * @param id alias UUID
   * @return alias DTO
   */
  @Operation(summary = "Get a single material external alias by id")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Alias returned"),
    @ApiResponse(responseCode = "404", description = "Alias not found")
  })
  @GetMapping("/{id}")
  public MaterialExternalAliasDto getAlias(@PathVariable @NotNull UUID id) {
    return mapper.toDto(service.findById(id));
  }

  /**
   * Creates a new alias. The service validates that the referenced material exists and that the
   * {@code (sourceSystem, externalName)} pair is unique — compared case-insensitively, matching the
   * resolution lookup (REQ-REFINERY-010).
   *
   * @param request validated create payload
   * @return the persisted alias, 201 Created
   */
  @Operation(summary = "Create a new material external alias")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Alias created"),
    @ApiResponse(responseCode = "400", description = "Validation error"),
    @ApiResponse(responseCode = "404", description = "Linked material not found"),
    @ApiResponse(
        responseCode = "409",
        description = "Duplicate alias for source / external name (case-insensitive)")
  })
  @PostMapping
  public ResponseEntity<MaterialExternalAliasDto> createAlias(
      @RequestBody @Valid @NotNull MaterialExternalAliasWriteRequest request) {
    MaterialExternalAliasDto dto = mapper.toDto(service.create(request));
    return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED).body(dto);
  }

  /**
   * Updates an existing alias. Optimistic-lock token on the request body must match the row's
   * current version or Hibernate raises a {@link
   * org.springframework.orm.ObjectOptimisticLockingFailureException} → 409.
   *
   * @param id alias UUID
   * @param request validated update payload (carries the expected version)
   * @return the persisted alias
   */
  @Operation(summary = "Update an existing material external alias")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Alias updated"),
    @ApiResponse(responseCode = "400", description = "Validation error"),
    @ApiResponse(responseCode = "404", description = "Alias or linked material not found"),
    @ApiResponse(
        responseCode = "409",
        description = "Stale version or duplicate external name (case-insensitive)")
  })
  @PutMapping("/{id}")
  public MaterialExternalAliasDto updateAlias(
      @PathVariable @NotNull UUID id,
      @RequestBody @Validated({Default.class, OnUpdate.class}) @NotNull
          MaterialExternalAliasWriteRequest request) {
    return mapper.toDto(service.update(id, request));
  }

  /**
   * Deletes an alias.
   *
   * @param id alias UUID
   * @return 204 No Content on success
   */
  @Operation(summary = "Delete a material external alias")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Alias deleted"),
    @ApiResponse(responseCode = "404", description = "Alias not found")
  })
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> deleteAlias(@PathVariable @NotNull UUID id) {
    service.delete(id);
    return ResponseEntity.noContent().build();
  }
}
