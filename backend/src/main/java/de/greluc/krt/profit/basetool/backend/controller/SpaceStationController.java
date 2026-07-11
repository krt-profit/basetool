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

import de.greluc.krt.profit.basetool.backend.mapper.SpaceStationMapper;
import de.greluc.krt.profit.basetool.backend.model.SpaceStation;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.SpaceStationDto;
import de.greluc.krt.profit.basetool.backend.service.SpaceStationService;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-mostly REST surface over the space-station catalogue. UEX owns the table content; the
 * override endpoints let admins/officers pin {@code hasLoadingDock} so the next UEX sweep cannot
 * reset a manual correction.
 */
@RestController
@RequestMapping("/api/v1/space-stations")
@RequiredArgsConstructor
@Transactional
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class SpaceStationController {

  private final SpaceStationService spaceStationService;
  private final SpaceStationMapper spaceStationMapper;

  /**
   * Paged space-station list with whitelist-enforced sort.
   *
   * @param page zero-based page index (optional)
   * @param size page size (optional)
   * @param sort sort spec, restricted to the whitelist
   * @return paged space-station DTOs
   */
  @GetMapping
  public PageResponse<SpaceStationDto> getAllSpaceStations(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page, size, sort, Set.of("name", "id", "starSystemName"), "name");
    Page<SpaceStation> p = spaceStationService.getAllSpaceStations(pageable);
    return PageResponse.of(p.map(spaceStationMapper::toDto));
  }

  /**
   * Returns a single space-station DTO.
   *
   * @param id space station id
   * @return the space-station DTO
   */
  @GetMapping("/{id}")
  public SpaceStationDto getSpaceStation(@PathVariable @NotNull UUID id) {
    return spaceStationMapper.toDto(spaceStationService.getSpaceStation(id));
  }

  /**
   * Pins {@code hasLoadingDock} to {@code value} and marks it as admin-overridden so the next UEX
   * sweep skips writing the column.
   *
   * @param id space station id
   * @param value desired {@code hasLoadingDock} value
   * @return the persisted space-station DTO
   */
  @PatchMapping("/{id}/loading-dock")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public SpaceStationDto setLoadingDockOverride(
      @PathVariable @NotNull UUID id, @RequestParam boolean value) {
    return spaceStationMapper.toDto(spaceStationService.setLoadingDockOverride(id, value));
  }

  /**
   * Clears the admin pin on the space station's {@code hasLoadingDock} flag. The value column keeps
   * its current state until the next UEX sweep restores the upstream value.
   *
   * @param id space station id
   * @return the persisted space-station DTO
   */
  @DeleteMapping("/{id}/loading-dock-override")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public SpaceStationDto clearLoadingDockOverride(@PathVariable @NotNull UUID id) {
    return spaceStationMapper.toDto(spaceStationService.clearLoadingDockOverride(id));
  }
}
