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

import de.greluc.krt.profit.basetool.backend.mapper.ShipMapper;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.ShipTypeDto;
import de.greluc.krt.profit.basetool.backend.service.ShipTypeService;
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
 * Read-mostly REST surface over the ship-type catalog plus the admin-only visibility toggle. The
 * catalog is owned by {@code UexVehicleService}.
 */
@RestController
@RequestMapping("/api/v1/ship-types")
@RequiredArgsConstructor
@Transactional
public class ShipTypeController {

  private final ShipTypeService shipTypeService;
  private final ShipMapper shipMapper;

  /**
   * Paged list with whitelist-enforced sort. Uses the slim {@code shipTypeToDto} projection to
   * avoid exposing the heavy fields needed elsewhere.
   *
   * @return paged ship-type DTOs
   */
  @GetMapping
  public PageResponse<ShipTypeDto> getAllShipTypes(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "false") boolean includeHidden) {
    Pageable pageable =
        PaginationUtil.createPageRequest(page, size, sort, Set.of("name", "id"), "name");
    Page<ShipType> p = shipTypeService.getAllShipTypes(pageable, includeHidden);
    return PageResponse.of(p.map(shipMapper::shipTypeToDto));
  }

  /**
   * Returns the ship type DTO.
   *
   * @param id ship type id
   * @return the ship type DTO
   */
  @GetMapping("/{id}")
  public ShipTypeDto getShipType(@PathVariable @NotNull UUID id) {
    return shipMapper.shipTypeToDto(shipTypeService.getShipType(id));
  }

  /**
   * Toggles the hidden flag. ADMIN-only.
   *
   * @param id ship type id
   * @param hidden new flag value
   * @return the persisted DTO
   */
  @PutMapping("/{id}/visibility")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public ShipTypeDto updateShipTypeVisibility(
      @PathVariable @NotNull UUID id, @RequestParam boolean hidden) {
    return shipMapper.shipTypeToDto(shipTypeService.updateShipTypeVisibility(id, hidden));
  }
}
