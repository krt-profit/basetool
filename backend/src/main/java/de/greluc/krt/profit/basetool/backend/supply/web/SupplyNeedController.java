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

package de.greluc.krt.profit.basetool.backend.supply.web;

import de.greluc.krt.profit.basetool.backend.kernel.PageResponse;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.platform.api.PaginationUtil;
import de.greluc.krt.profit.basetool.backend.supply.api.NeedStatus;
import de.greluc.krt.profit.basetool.backend.supply.internal.CreateNeedRequest;
import de.greluc.krt.profit.basetool.backend.supply.internal.NeedDto;
import de.greluc.krt.profit.basetool.backend.supply.internal.SupplyNeedService;
import de.greluc.krt.profit.basetool.backend.supply.internal.UpdateNeedRequest;
import de.greluc.krt.profit.basetool.backend.supply.internal.VersionRequest;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The needs of the raising units (REQ-SUP-003 … REQ-SUP-006). Every member reads the open ones; the
 * unit-scoped checks of who may write live in {@link SupplyNeedService}.
 */
@RestController
@RequestMapping("/api/v1/supply/needs")
@RequiredArgsConstructor
@PreAuthorize(Roles.HAS_ROLE_KRT_MEMBER)
public class SupplyNeedController {

  private static final Set<String> SORT_FIELDS =
      Set.of("createdAt", "amount", "priority", "status");

  private final SupplyNeedService needService;

  /**
   * Pages over the needs the caller may see.
   *
   * @param orgUnitId only this raising unit
   * @param status only these states; the visible ones when omitted
   * @param purchasable only purchasable or only non-purchasable needs
   * @param materialId only this material
   * @param page zero-based page index
   * @param size page size
   * @param sort whitelisted sort spec
   * @return one page of needs
   */
  @Operation(summary = "List the needs the caller may see (paged)")
  @GetMapping
  public PageResponse<NeedDto> list(
      @RequestParam(required = false) UUID orgUnitId,
      @RequestParam(required = false) Set<NeedStatus> status,
      @RequestParam(required = false) Boolean purchasable,
      @RequestParam(required = false) UUID materialId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page,
            size,
            sort == null || sort.isBlank() ? "createdAt,desc" : sort,
            SORT_FIELDS,
            "createdAt");
    return PageResponse.of(
        needService.list(
            orgUnitId, status == null ? Set.of() : status, purchasable, materialId, pageable));
  }

  /**
   * Lists the open needs of one unit, for the pledge form's picker.
   *
   * @param orgUnitId the raising unit
   * @return the unit's open needs
   */
  @Operation(summary = "List the open needs of one raising unit")
  @GetMapping("/open")
  public List<NeedDto> open(@RequestParam UUID orgUnitId) {
    return needService.openNeedsOf(orgUnitId);
  }

  /**
   * Reads one need.
   *
   * @param id the need
   * @return the need
   */
  @Operation(summary = "Read one need")
  @GetMapping("/{id}")
  public NeedDto get(@PathVariable UUID id) {
    return needService.get(id);
  }

  /**
   * Raises a need.
   *
   * @param request the need
   * @return the raised need
   */
  @Operation(summary = "Raise a need")
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public NeedDto create(@NotNull @Valid @RequestBody CreateNeedRequest request) {
    return needService.create(request);
  }

  /**
   * Edits an open or paused need.
   *
   * @param id the need
   * @param request the new values and the echoed version
   * @return the edited need
   */
  @Operation(summary = "Edit an open or paused need")
  @PutMapping("/{id}")
  public NeedDto update(
      @PathVariable UUID id, @NotNull @Valid @RequestBody UpdateNeedRequest request) {
    return needService.update(id, request);
  }

  /**
   * Withdraws a need.
   *
   * @param id the need
   * @param request the echoed version
   * @return the withdrawn need
   */
  @Operation(summary = "Withdraw a need")
  @PostMapping("/{id}/withdraw")
  public NeedDto withdraw(
      @PathVariable UUID id, @NotNull @Valid @RequestBody VersionRequest request) {
    return needService.withdraw(id, request.version());
  }

  /**
   * Pauses an open need.
   *
   * @param id the need
   * @param request the echoed version
   * @return the paused need
   */
  @Operation(summary = "Pause an open need")
  @PostMapping("/{id}/pause")
  public NeedDto pause(@PathVariable UUID id, @NotNull @Valid @RequestBody VersionRequest request) {
    return needService.pause(id, request.version());
  }

  /**
   * Opens a paused need again.
   *
   * @param id the need
   * @param request the echoed version
   * @return the reopened need
   */
  @Operation(summary = "Resume a paused need")
  @PostMapping("/{id}/resume")
  public NeedDto resume(
      @PathVariable UUID id, @NotNull @Valid @RequestBody VersionRequest request) {
    return needService.resume(id, request.version());
  }
}
