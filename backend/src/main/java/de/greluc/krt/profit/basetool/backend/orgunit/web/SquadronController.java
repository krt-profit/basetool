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

package de.greluc.krt.profit.basetool.backend.orgunit.web;

import de.greluc.krt.profit.basetool.backend.kernel.PageResponse;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronDto;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronMapper;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronService;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.platform.api.PaginationUtil;
import jakarta.validation.Valid;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the squadron reference table. Mutations and activate are ADMIN-only.
 *
 * <p>The class-level {@code isAuthenticated()} gate is the floor for every endpoint; a method-level
 * gate still wins (REQ-SEC-052).
 */
@RestController
@RequestMapping("/api/v1/squadrons")
@RequiredArgsConstructor
@Transactional
@PreAuthorize("isAuthenticated()")
public class SquadronController {

  private static final Set<String> ALLOWED_SORT = Set.of("name", "shorthand", "id");

  private final SquadronService squadronService;
  private final SquadronMapper squadronMapper;
  private final AuthHelperService authHelperService;

  /**
   * Paged squadron list. {@code includeInactive=true} is ADMIN-only because soft-deleted squadron
   * descriptions can carry internal context.
   *
   * @return paged squadron DTOs
   */
  @GetMapping
  public PageResponse<SquadronDto> getAllSquadrons(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "false") boolean includeInactive) {
    if (includeInactive && !authHelperService.isAdmin()) {
      throw new AccessDeniedException("includeInactive=true requires ROLE_ADMIN");
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
}
