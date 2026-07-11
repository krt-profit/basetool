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

import de.greluc.krt.profit.basetool.backend.mapper.SpecialCommandMapper;
import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.SpecialCommandDto;
import de.greluc.krt.profit.basetool.backend.service.SpecialCommandService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import de.greluc.krt.profit.basetool.backend.web.PaginationUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for {@link SpecialCommand} — the Spezialkommando tenant kind. Mirrors the {@link
 * SquadronController} CRUD surface except for the per-row promotion toggle, which has no
 * counterpart on Spezialkommandos by data-layer constraint.
 *
 * <p>All write paths are ADMIN-gated, matching the SK-administration decision recorded in {@code
 * SPEZIALKOMMANDO_PLAN.md} §2 (D2): SK lifecycle is admin-only; per-SK Lead capabilities for
 * membership management are a separate authorisation surface that lives on the membership endpoints
 * (R5.b). The list endpoint is open to any authenticated caller so the owner picker fragment can
 * populate its dropdown without elevated rights.
 */
@RestController
@RequestMapping("/api/v1/special-commands")
@RequiredArgsConstructor
@Transactional
public class SpecialCommandController {

  private static final Set<String> ALLOWED_SORT = Set.of("name", "shorthand", "id");

  private final SpecialCommandService specialCommandService;
  private final SpecialCommandMapper specialCommandMapper;

  /**
   * Paged list of Spezialkommandos for the admin overview and the owner-picker dropdown. The {@code
   * includeInactive=true} flavour requires ADMIN — soft-deleted SK descriptions can carry internal
   * context, mirroring the {@link SquadronController#getAllSquadrons} guard.
   *
   * @param page page number, defaults to 0.
   * @param size page size, defaults to the platform default.
   * @param sort sort spec; whitelisted to {@code name}, {@code shorthand}, {@code id}.
   * @param includeInactive include soft-deleted rows; ADMIN-only.
   * @param authentication Spring Security authentication, injected for the inactive-flag gate.
   * @return paged Spezialkommando DTOs.
   * @throws org.springframework.security.access.AccessDeniedException if {@code includeInactive} is
   *     requested without ROLE_ADMIN.
   */
  @GetMapping
  @PreAuthorize("isAuthenticated()")
  @Operation(
      summary = "List Spezialkommandos",
      description =
          "Returns a paged list of Spezialkommandos. Admins may include soft-deleted rows via"
              + " includeInactive=true; everyone else sees only active rows. Use this endpoint to"
              + " populate the owner-picker dropdown on create forms.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Paged list of Spezialkommandos."),
    @ApiResponse(
        responseCode = "403",
        description = "includeInactive=true requested without ROLE_ADMIN."),
    @ApiResponse(responseCode = "401", description = "Caller is not authenticated.")
  })
  public PageResponse<SpecialCommandDto> getAllSpecialCommands(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "false") boolean includeInactive,
      org.springframework.security.core.Authentication authentication) {
    if (includeInactive
        && (authentication == null
            || authentication.getAuthorities().stream()
                .noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())))) {
      throw new org.springframework.security.access.AccessDeniedException(
          "includeInactive=true requires ROLE_ADMIN");
    }
    Pageable pageable = PaginationUtil.createPageRequest(page, size, sort, ALLOWED_SORT, "name");
    Page<SpecialCommand> p = specialCommandService.getAllSpecialCommands(pageable, includeInactive);
    return PageResponse.of(p.map(specialCommandMapper::toDto));
  }

  /**
   * Returns a single Spezialkommando by id. Used by the admin detail page and the membership
   * management UI to resolve a chip click. ADMIN-only because the inactive-row visibility rules on
   * {@link #getAllSpecialCommands} cannot be enforced on a single-row endpoint without complicating
   * the surface.
   *
   * @param id Spezialkommando id.
   * @return the matching DTO.
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException if no SK matches.
   */
  @GetMapping("/{id}")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  @Operation(
      summary = "Get a Spezialkommando by id",
      description = "Returns the full DTO for the requested Spezialkommando. ADMIN-only.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Matching Spezialkommando."),
    @ApiResponse(responseCode = "404", description = "No Spezialkommando matches the given id.")
  })
  public SpecialCommandDto getSpecialCommand(@PathVariable @NotNull UUID id) {
    return specialCommandMapper.toDto(specialCommandService.getSpecialCommandById(id));
  }

  /**
   * Creates a new Spezialkommando. Duplicate name → 409 from the service-layer uniqueness check
   * before the DB UNIQUE constraint trips. ADMIN-only.
   *
   * @param dto create payload; validated by Jakarta annotations on {@link SpecialCommandDto}.
   * @return the persisted DTO.
   */
  @PostMapping
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  @Operation(
      summary = "Create a Spezialkommando",
      description = "Creates a new Spezialkommando. Name must be unique across all org units.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Created."),
    @ApiResponse(responseCode = "400", description = "Validation error on the inbound payload."),
    @ApiResponse(responseCode = "403", description = "Caller does not hold ROLE_ADMIN."),
    @ApiResponse(responseCode = "409", description = "Name collides with an existing org unit.")
  })
  public SpecialCommandDto createSpecialCommand(@RequestBody @Valid SpecialCommandDto dto) {
    var toCreate = specialCommandMapper.toEntity(dto);
    // L-7: strip client-supplied id/version so create cannot become a merge()-UPSERT of another
    // row.
    toCreate.setId(null);
    toCreate.setVersion(null);
    return specialCommandMapper.toDto(specialCommandService.createSpecialCommand(toCreate));
  }

  /**
   * Updates an existing Spezialkommando. Carries the optimistic-lock version in the DTO body.
   * Duplicate name (against a different row) → 409. ADMIN-only.
   *
   * @param id Spezialkommando id.
   * @param dto update payload; validated by Jakarta annotations on {@link SpecialCommandDto}.
   * @return the persisted DTO with the bumped version.
   */
  @PutMapping("/{id}")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  @Operation(
      summary = "Update a Spezialkommando",
      description =
          "Updates an existing Spezialkommando with optimistic-lock and duplicate-name checks.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Updated."),
    @ApiResponse(responseCode = "400", description = "Validation error on the inbound payload."),
    @ApiResponse(responseCode = "403", description = "Caller does not hold ROLE_ADMIN."),
    @ApiResponse(responseCode = "404", description = "No Spezialkommando matches the given id."),
    @ApiResponse(
        responseCode = "409",
        description = "Optimistic-lock conflict OR name collides with a different row.")
  })
  public SpecialCommandDto updateSpecialCommand(
      @PathVariable @NotNull UUID id, @RequestBody @Valid SpecialCommandDto dto) {
    return specialCommandMapper.toDto(specialCommandService.updateSpecialCommand(id, dto));
  }

  /**
   * Soft-deletes a Spezialkommando by flipping {@code active = false}. Memberships and any
   * aggregate that already references the SK as an owner stay in place; only the active-list
   * dropdowns and the owner picker hide the row from then on. ADMIN-only.
   *
   * @param id Spezialkommando id.
   */
  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  @Operation(
      summary = "Soft-delete a Spezialkommando",
      description =
          "Soft-deletes the Spezialkommando by flipping active=false. Reversible via the activate"
              + " endpoint.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Soft-deleted."),
    @ApiResponse(responseCode = "403", description = "Caller does not hold ROLE_ADMIN."),
    @ApiResponse(responseCode = "404", description = "No Spezialkommando matches the given id.")
  })
  public void deleteSpecialCommand(@PathVariable @NotNull UUID id) {
    specialCommandService.deleteSpecialCommand(id);
  }

  /**
   * Reverses a soft-delete. ADMIN-only.
   *
   * @param id Spezialkommando id.
   */
  @PostMapping("/{id}/activate")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  @Operation(
      summary = "Activate a soft-deleted Spezialkommando",
      description = "Flips active=true on a previously soft-deleted Spezialkommando.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Activated."),
    @ApiResponse(responseCode = "403", description = "Caller does not hold ROLE_ADMIN."),
    @ApiResponse(responseCode = "404", description = "No Spezialkommando matches the given id.")
  })
  public void activateSpecialCommand(@PathVariable @NotNull UUID id) {
    specialCommandService.activateSpecialCommand(id);
  }

  /**
   * Per-SK profit-eligibility toggle. Admins flip the flag to opt a Spezialkommando in or out of
   * the Job-Order responsible (processing) picker. Only Profit-department SKs should carry {@code
   * true}; SKs of other departments stay {@code false} (they may place orders but not process
   * them). ADMIN-only and isolated from the regular update path so an accidental description edit
   * cannot change a SK's eligibility.
   *
   * @param id Spezialkommando id.
   * @param body request payload {@code { "eligible": true|false }}.
   * @return the updated DTO with the new flag value.
   */
  @PatchMapping("/{id}/profit-eligible")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  @Operation(
      summary = "Toggle Spezialkommando profit-eligibility",
      description =
          "Sets whether the Spezialkommando may be selected as the responsible (processing) org"
              + " unit of a Job Order. ADMIN-only.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Updated."),
    @ApiResponse(responseCode = "403", description = "Caller does not hold ROLE_ADMIN."),
    @ApiResponse(responseCode = "404", description = "No Spezialkommando matches the given id.")
  })
  public SpecialCommandDto setProfitEligible(
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid SpecialCommandProfitEligibleToggleRequest body) {
    return specialCommandMapper.toDto(specialCommandService.setProfitEligible(id, body.eligible()));
  }

  /**
   * Request body for the per-SK profit-eligibility toggle endpoint. Carries a single boolean so a
   * future expansion can be added without breaking the wire format.
   *
   * @param eligible new value of {@code SpecialCommand.isProfitEligible}.
   */
  public record SpecialCommandProfitEligibleToggleRequest(boolean eligible) {}
}
