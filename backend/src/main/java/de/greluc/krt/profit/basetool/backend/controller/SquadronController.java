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

import de.greluc.krt.profit.basetool.backend.mapper.SquadronMapper;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.SquadronDto;
import de.greluc.krt.profit.basetool.backend.service.SquadronService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import de.greluc.krt.profit.basetool.backend.web.PaginationUtil;
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
 * REST surface for the squadron reference table. Mutations are OFFICER/ADMIN; activate is
 * ADMIN-only.
 */
@RestController
@RequestMapping("/api/v1/squadrons")
@RequiredArgsConstructor
@Transactional
public class SquadronController {

  private static final Set<String> ALLOWED_SORT = Set.of("name", "shorthand", "id");

  private final SquadronService squadronService;
  private final SquadronMapper squadronMapper;

  /**
   * Paged list with {@code includeInactive} for the admin view. The {@code includeInactive=true}
   * flavour is admin-only — soft-deleted squadron descriptions can carry internal context, and the
   * public {@code /api/v1/squadrons} matcher is {@code permitAll} so a guest could otherwise
   * enumerate every archived squadron (audit finding M-6).
   *
   * @return paged squadron DTOs
   */
  @GetMapping
  public PageResponse<SquadronDto> getAllSquadrons(
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
    Page<Squadron> p = squadronService.getAllSquadrons(pageable, includeInactive);
    return PageResponse.of(p.map(squadronMapper::toDto));
  }

  /**
   * Creates a new squadron. Duplicate name → 409.
   *
   * @param squadron create payload
   * @return the persisted DTO
   */
  @PostMapping
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public SquadronDto createSquadron(@RequestBody @Valid SquadronDto squadron) {
    var toCreate = squadronMapper.toEntity(squadron);
    // L-7: strip client-supplied id/version so create cannot become a merge()-UPSERT of another
    // row.
    toCreate.setId(null);
    toCreate.setVersion(null);
    return squadronMapper.toDto(squadronService.createSquadron(toCreate));
  }

  /**
   * Updates a squadron. Carries optimistic-lock version in the DTO body.
   *
   * @param id squadron id
   * @param squadron update payload
   * @return the persisted DTO
   */
  @PutMapping("/{id}")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public SquadronDto updateSquadron(
      @PathVariable @NotNull UUID id, @RequestBody @Valid SquadronDto squadron) {
    return squadronMapper.toDto(squadronService.updateSquadron(id, squadron));
  }

  /**
   * Soft-deletes a squadron.
   *
   * @param id squadron id
   */
  @DeleteMapping("/{id}")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public void deleteSquadron(@PathVariable @NotNull UUID id) {
    squadronService.deleteSquadron(id);
  }

  /**
   * Reverses a soft-delete. ADMIN-only.
   *
   * @param id squadron id
   */
  @PostMapping("/{id}/activate")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public void activateSquadron(@PathVariable @NotNull UUID id) {
    squadronService.activateSquadron(id);
  }

  /**
   * Per-squadron promotion-system feature toggle. Admins flip the flag to hide / re-expose the
   * promotion menu for a whole squadron without losing any data. ADMIN-only — the regular {@code
   * PUT /api/v1/squadrons/{id}} update path intentionally does NOT touch this flag so an accidental
   * description edit cannot disable the feature.
   *
   * @param id squadron id
   * @param body request payload {@code { "enabled": true|false }}
   * @return the updated squadron DTO with the new flag value
   */
  @PatchMapping("/{id}/promotion-enabled")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public SquadronDto setPromotionEnabled(
      @PathVariable @NotNull UUID id, @RequestBody @Valid SquadronPromotionToggleRequest body) {
    return squadronMapper.toDto(squadronService.setPromotionEnabled(id, body.enabled()));
  }

  /**
   * Per-squadron profit-eligibility toggle. Admins flip the flag to opt a squadron in or out of the
   * Job-Order responsible (processing) picker without losing any data. ADMIN-only and isolated from
   * the regular {@code PUT /api/v1/squadrons/{id}} update path so an accidental description edit
   * cannot change a squadron's eligibility.
   *
   * @param id squadron id
   * @param body request payload {@code { "eligible": true|false }}
   * @return the updated squadron DTO with the new flag value
   */
  @PatchMapping("/{id}/profit-eligible")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public SquadronDto setProfitEligible(
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid SquadronProfitEligibleToggleRequest body) {
    return squadronMapper.toDto(squadronService.setProfitEligible(id, body.eligible()));
  }

  /**
   * Request body for the per-squadron promotion-feature toggle endpoint. Carries a single boolean
   * so a future expansion (e.g. effective-from date) can be added without breaking the wire format.
   *
   * @param enabled new value of {@code Squadron.isPromotionEnabled}
   */
  public record SquadronPromotionToggleRequest(boolean enabled) {}

  /**
   * Request body for the per-squadron profit-eligibility toggle endpoint. Carries a single boolean
   * so a future expansion can be added without breaking the wire format.
   *
   * @param eligible new value of {@code Squadron.isProfitEligible}
   */
  public record SquadronProfitEligibleToggleRequest(boolean eligible) {}
}
