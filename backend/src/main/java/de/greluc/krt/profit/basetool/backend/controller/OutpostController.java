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

import de.greluc.krt.profit.basetool.backend.mapper.OutpostMapper;
import de.greluc.krt.profit.basetool.backend.model.Outpost;
import de.greluc.krt.profit.basetool.backend.model.dto.OutpostDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.service.OutpostService;
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
 * Read-mostly REST surface over the outpost catalogue. UEX owns the table content; the override
 * endpoints let admins/officers pin {@code hasLoadingDock} so the next UEX sweep cannot reset a
 * manual correction.
 */
@RestController
@RequestMapping("/api/v1/outposts")
@RequiredArgsConstructor
@Transactional
@PreAuthorize(Roles.HAS_ROLE_ADMIN)
public class OutpostController {

  private final OutpostService outpostService;
  private final OutpostMapper outpostMapper;

  /**
   * Paged outpost list with whitelist-enforced sort.
   *
   * @param page zero-based page index (optional)
   * @param size page size (optional)
   * @param sort sort spec, restricted to the whitelist
   * @return paged outpost DTOs
   */
  @GetMapping
  public PageResponse<OutpostDto> getAllOutposts(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page, size, sort, Set.of("name", "id", "starSystemName"), "name");
    Page<Outpost> p = outpostService.getAllOutposts(pageable);
    return PageResponse.of(p.map(outpostMapper::toDto));
  }

  /**
   * Returns a single outpost DTO.
   *
   * @param id outpost id
   * @return the outpost DTO
   */
  @GetMapping("/{id}")
  public OutpostDto getOutpost(@PathVariable @NotNull UUID id) {
    return outpostMapper.toDto(outpostService.getOutpost(id));
  }

  /**
   * Pins {@code hasLoadingDock} to {@code value} and marks it as admin-overridden so the next UEX
   * sweep skips writing the column.
   *
   * @param id outpost id
   * @param value desired {@code hasLoadingDock} value
   * @return the persisted outpost DTO
   */
  @PatchMapping("/{id}/loading-dock")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public OutpostDto setLoadingDockOverride(
      @PathVariable @NotNull UUID id, @RequestParam boolean value) {
    return outpostMapper.toDto(outpostService.setLoadingDockOverride(id, value));
  }

  /**
   * Clears the admin pin on the outpost's {@code hasLoadingDock} flag. The value column keeps its
   * current state until the next UEX sweep restores the upstream value.
   *
   * @param id outpost id
   * @return the persisted outpost DTO
   */
  @DeleteMapping("/{id}/loading-dock-override")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public OutpostDto clearLoadingDockOverride(@PathVariable @NotNull UUID id) {
    return outpostMapper.toDto(outpostService.clearLoadingDockOverride(id));
  }
}
