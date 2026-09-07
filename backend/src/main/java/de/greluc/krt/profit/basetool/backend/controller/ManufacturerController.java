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

import de.greluc.krt.profit.basetool.backend.mapper.ManufacturerMapper;
import de.greluc.krt.profit.basetool.backend.model.Manufacturer;
import de.greluc.krt.profit.basetool.backend.model.dto.ManufacturerDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.service.ManufacturerService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-mostly REST surface over the manufacturer catalog plus the admin-only visibility toggle. The
 * catalog itself is owned by {@code UexManufacturerService}; this controller only reads and flips
 * the {@code hidden} flag.
 *
 * <p>REQ-SEC-052: the class-level {@code @PreAuthorize("isAuthenticated()")} is the floor, not the
 * ceiling — it is stated here so an endpoint added later inherits it rather than relying on a URL
 * matcher elsewhere being right, and a method-level gate still wins where one is present.
 */
@RestController
@RequestMapping("/api/v1/manufacturers")
@RequiredArgsConstructor
@Transactional
@PreAuthorize("isAuthenticated()")
public class ManufacturerController {

  private final ManufacturerService manufacturerService;
  private final ManufacturerMapper manufacturerMapper;

  /**
   * Paged list with whitelist-enforced sort. {@code includeHidden=true} returns hidden entries
   * (admin view).
   *
   * @return paged manufacturer DTOs
   */
  @GetMapping
  public PageResponse<ManufacturerDto> getAllManufacturers(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "false") boolean includeHidden) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page, size, sort, Set.of("name", "abbreviation", "id"), "name");
    Page<Manufacturer> p = manufacturerService.getAllManufacturers(pageable, includeHidden);
    return PageResponse.of(p.map(manufacturerMapper::toDto));
  }

  /**
   * Returns the manufacturer DTO.
   *
   * @param id manufacturer id
   * @return the manufacturer DTO
   */
  @GetMapping("/{id}")
  public ManufacturerDto getManufacturer(@PathVariable @NotNull UUID id) {
    return manufacturerMapper.toDto(manufacturerService.getManufacturer(id));
  }

  /**
   * Toggles the hidden flag. ADMIN-only.
   *
   * @param id manufacturer id
   * @param hidden new flag value
   * @return the persisted DTO
   */
  @PutMapping("/{id}/visibility")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public ManufacturerDto updateManufacturerVisibility(
      @PathVariable @NotNull UUID id, @RequestParam boolean hidden) {
    return manufacturerMapper.toDto(manufacturerService.updateManufacturerVisibility(id, hidden));
  }
}
