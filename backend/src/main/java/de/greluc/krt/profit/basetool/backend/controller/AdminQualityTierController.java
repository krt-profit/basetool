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

import de.greluc.krt.profit.basetool.backend.model.dto.QualityTierDto;
import de.greluc.krt.profit.basetool.backend.model.dto.QualityTierWriteDto;
import de.greluc.krt.profit.basetool.backend.service.QualityTierService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Administrator maintenance of the quality-tier catalogue (REQ-ORDERS-036). */
@RestController
@RequestMapping("/api/v1/admin/quality-tiers")
@RequiredArgsConstructor
@PreAuthorize(Roles.HAS_ROLE_ADMIN)
@Tag(name = "Admin – Quality tiers", description = "Administrator endpoints for the quality tiers.")
@SecurityRequirement(name = "bearer-jwt")
public class AdminQualityTierController {

  private final QualityTierService qualityTierService;

  /**
   * Lists the whole catalogue, inactive tiers included.
   *
   * @return every tier in picker order
   */
  @GetMapping
  @Operation(summary = "List every quality tier.")
  public List<QualityTierDto> listAllQualityTiers() {
    return qualityTierService.listAll();
  }

  /**
   * Adds a tier.
   *
   * @param dto the new tier
   * @return the persisted tier
   */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Add a quality tier.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Tier added."),
    @ApiResponse(responseCode = "400", description = "Validation failed."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator."),
    @ApiResponse(responseCode = "409", description = "Code or floor already used.")
  })
  public QualityTierDto createQualityTier(@NotNull @Valid @RequestBody QualityTierWriteDto dto) {
    return qualityTierService.create(dto);
  }

  /**
   * Changes a tier; the floor of a referenced tier stays fixed.
   *
   * @param id the tier
   * @param dto the new state with the version read
   * @return the persisted tier
   */
  @PutMapping("/{id}")
  @Operation(summary = "Change a quality tier.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Tier changed."),
    @ApiResponse(responseCode = "400", description = "Validation failed."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator."),
    @ApiResponse(responseCode = "404", description = "Tier not found."),
    @ApiResponse(
        responseCode = "409",
        description = "Stale version, duplicate code or floor, or the floor of a used tier.")
  })
  public QualityTierDto updateQualityTier(
      @PathVariable UUID id, @NotNull @Valid @RequestBody QualityTierWriteDto dto) {
    return qualityTierService.update(id, dto);
  }

  /**
   * Deletes a tier nothing references.
   *
   * @param id the tier
   */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Delete an unused quality tier.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Tier deleted."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator."),
    @ApiResponse(responseCode = "404", description = "Tier not found."),
    @ApiResponse(responseCode = "409", description = "Tier is in use or is the base tier.")
  })
  public void deleteQualityTier(@PathVariable UUID id) {
    qualityTierService.delete(id);
  }
}
