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

import de.greluc.krt.profit.basetool.backend.mapper.StarSystemMapper;
import de.greluc.krt.profit.basetool.backend.model.StarSystem;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.StarSystemDto;
import de.greluc.krt.profit.basetool.backend.service.StarSystemService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import de.greluc.krt.profit.basetool.backend.web.PaginationUtil;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the star-system reference table. UEX owns the bulk of the data; this controller
 * adds the admin-mutable CRUD for systems UEX doesn't know about yet.
 *
 * <p>REQ-SEC-052: the class-level {@code @PreAuthorize("isAuthenticated()")} is the floor, not the
 * ceiling — it is stated here so an endpoint added later inherits it rather than relying on a URL
 * matcher elsewhere being right, and a method-level gate still wins where one is present.
 */
@RestController
@RequestMapping("/api/v1/star-systems")
@RequiredArgsConstructor
@Transactional
@PreAuthorize("isAuthenticated()")
public class StarSystemController {

  private final StarSystemService starSystemService;
  private final StarSystemMapper starSystemMapper;

  /**
   * Returns paged star-system DTOs.
   *
   * @return paged star-system DTOs
   */
  @GetMapping
  public PageResponse<StarSystemDto> getAllStarSystems(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(page, size, sort, Set.of("name", "id"), "name");
    Page<StarSystem> p = starSystemService.getAllStarSystems(pageable);
    return PageResponse.of(p.map(starSystemMapper::toDto));
  }

  /**
   * Returns the star-system DTO.
   *
   * @param id star system id
   * @return the star-system DTO
   */
  @GetMapping("/{id}")
  public StarSystemDto getStarSystem(@PathVariable @NotNull UUID id) {
    return starSystemMapper.toDto(starSystemService.getStarSystem(id));
  }

  /**
   * Creates a star system manually. Duplicate name (case-insensitive) → 409.
   *
   * @param starSystem create payload
   * @return the persisted DTO
   */
  @PostMapping
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public StarSystemDto createStarSystem(@RequestBody @NotNull StarSystemDto starSystem) {
    var toCreate = starSystemMapper.toEntity(starSystem);
    // L-7: never honour a client-supplied id/version on create — a non-null id routes save() to
    // merge() (UPSERT) and could overwrite another row (same mass-assignment class as H-2).
    toCreate.setId(null);
    toCreate.setVersion(null);
    return starSystemMapper.toDto(starSystemService.createStarSystem(toCreate));
  }

  /**
   * Updates name + description of a star system. UEX-imported metadata is untouched.
   *
   * @param id star system id
   * @param starSystem update payload
   * @return the persisted DTO
   */
  @PutMapping("/{id}")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public StarSystemDto updateStarSystem(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull StarSystemDto starSystem) {
    return starSystemMapper.toDto(
        starSystemService.updateStarSystem(id, starSystemMapper.toEntity(starSystem)));
  }

  /**
   * Deletes a star system. Rejected when any location still references the system.
   *
   * @param id star system id
   */
  @DeleteMapping("/{id}")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public void deleteStarSystem(@PathVariable @NotNull UUID id) {
    starSystemService.deleteStarSystem(id);
  }
}
