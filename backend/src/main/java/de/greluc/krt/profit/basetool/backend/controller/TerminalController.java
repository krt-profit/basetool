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

import de.greluc.krt.profit.basetool.backend.mapper.TerminalMapper;
import de.greluc.krt.profit.basetool.backend.model.Terminal;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.TerminalDto;
import de.greluc.krt.profit.basetool.backend.service.TerminalService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-mostly REST surface over the terminal catalog. UEX owns the table; the PUT endpoint is
 * intentionally narrow — only the {@code hidden} flag flows through, every other field passed in
 * the body is ignored.
 */
@RestController
@RequestMapping("/api/v1/terminals")
@RequiredArgsConstructor
@Transactional
public class TerminalController {

  private final TerminalService terminalService;
  private final TerminalMapper terminalMapper;

  /**
   * Paged terminal list with whitelist-enforced sort.
   *
   * @return paged terminal DTOs
   */
  @GetMapping
  public PageResponse<TerminalDto> getAllTerminals(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page, size, sort, Set.of("name", "id", "starSystemName"), "name");
    Page<Terminal> p = terminalService.getAllTerminals(pageable);
    return PageResponse.of(p.map(terminalMapper::toDto));
  }

  /**
   * Returns the terminal DTO.
   *
   * @param id terminal id
   * @return the terminal DTO
   */
  @GetMapping("/{id}")
  public TerminalDto getTerminal(@PathVariable @NotNull UUID id) {
    return terminalMapper.toDto(terminalService.getTerminal(id));
  }

  /**
   * Toggles the terminal's {@code hidden} flag. Only the {@code hidden} field from the body is
   * applied; everything else is ignored so an admin cannot rename a UEX-imported terminal via this
   * endpoint.
   *
   * @param id terminal id
   * @param terminalDto request body — only {@code hidden} is read
   * @return the persisted DTO
   */
  @PutMapping("/{id}")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public TerminalDto updateTerminal(
      @PathVariable @NotNull UUID id, @RequestBody @Valid @NotNull TerminalDto terminalDto) {
    // Here we just allow toggling visibility according to the requirement,
    // but we mimic a normal PUT. In the Admin view, we only change 'hidden'.
    return terminalMapper.toDto(terminalService.updateTerminalVisibility(id, terminalDto.hidden()));
  }

  /**
   * Pins {@code hasLoadingDock} to {@code value} and marks the terminal's loading-dock flag as
   * admin-overridden so the next UEX sweep skips writing the column.
   *
   * @param id terminal id
   * @param value desired {@code hasLoadingDock} value (1:1 to {@code true}/{@code false})
   * @return the persisted terminal DTO
   */
  @PatchMapping("/{id}/loading-dock")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public TerminalDto setLoadingDockOverride(
      @PathVariable @NotNull UUID id, @RequestParam boolean value) {
    return terminalMapper.toDto(terminalService.setLoadingDockOverride(id, value));
  }

  /**
   * Clears the admin pin on the terminal's {@code hasLoadingDock} flag and immediately reverts the
   * value column to the last UEX-reported state stored in {@code uexHasLoadingDock}, so the next
   * read sees the unpinned UEX value without waiting for the next sweep.
   *
   * @param id terminal id
   * @return the persisted terminal DTO
   */
  @DeleteMapping("/{id}/loading-dock-override")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public TerminalDto clearLoadingDockOverride(@PathVariable @NotNull UUID id) {
    return terminalMapper.toDto(terminalService.clearLoadingDockOverride(id));
  }

  /**
   * Pins {@code isAutoLoad} to {@code value} and marks the terminal's auto-load flag as
   * admin-overridden so the next UEX sweep skips writing the column.
   *
   * @param id terminal id
   * @param value desired {@code isAutoLoad} value
   * @return the persisted terminal DTO
   */
  @PatchMapping("/{id}/auto-load")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public TerminalDto setAutoLoadOverride(
      @PathVariable @NotNull UUID id, @RequestParam boolean value) {
    return terminalMapper.toDto(terminalService.setAutoLoadOverride(id, value));
  }

  /**
   * Clears the admin pin on the terminal's {@code isAutoLoad} flag and immediately reverts the
   * value column to the last UEX-reported state stored in {@code uexIsAutoLoad}, so the next read
   * sees the unpinned UEX value without waiting for the next sweep.
   *
   * @param id terminal id
   * @return the persisted terminal DTO
   */
  @DeleteMapping("/{id}/auto-load-override")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public TerminalDto clearAutoLoadOverride(@PathVariable @NotNull UUID id) {
    return terminalMapper.toDto(terminalService.clearAutoLoadOverride(id));
  }
}
