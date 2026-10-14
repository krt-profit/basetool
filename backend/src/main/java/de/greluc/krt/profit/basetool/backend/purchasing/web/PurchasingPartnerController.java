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

package de.greluc.krt.profit.basetool.backend.purchasing.web;

import de.greluc.krt.profit.basetool.backend.kernel.PageResponse;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.platform.api.PaginationUtil;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerKind;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerStatus;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.CreatePartnerRequest;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.PartnerDto;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.PurchasingPartnerService;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.PurchasingVersionRequest;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.UpdatePartnerRequest;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
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
 * The counterparties of the trade deals (REQ-PUR-004, REQ-PUR-006). The delegates, the governing
 * leadership and admin maintain them; the service enforces that circle.
 */
@RestController
@RequestMapping("/api/v1/purchasing/partners")
@RequiredArgsConstructor
@PreAuthorize(Roles.HAS_ROLE_KRT_MEMBER)
public class PurchasingPartnerController {

  private static final Set<String> SORT_FIELDS = Set.of("name", "createdAt", "status", "kind");

  private final PurchasingPartnerService partnerService;

  /**
   * Pages over the partners.
   *
   * @param query a name fragment
   * @param kind only this kind
   * @param status only this status
   * @param page zero-based page index
   * @param size page size
   * @param sort whitelisted sort spec
   * @return one page of partners with their history
   */
  @Operation(summary = "List the trade partners (paged)")
  @GetMapping
  public PageResponse<PartnerDto> list(
      @RequestParam(required = false) String query,
      @RequestParam(required = false) PartnerKind kind,
      @RequestParam(required = false) PartnerStatus status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page, size, sort == null || sort.isBlank() ? "name,asc" : sort, SORT_FIELDS, "name");
    return PageResponse.of(partnerService.list(query, kind, status, pageable));
  }

  /**
   * Reads one partner.
   *
   * @param id the partner
   * @return the partner with its history
   */
  @Operation(summary = "Read one trade partner")
  @GetMapping("/{id}")
  public PartnerDto get(@PathVariable UUID id) {
    return partnerService.get(id);
  }

  /**
   * Creates a partner.
   *
   * @param request the partner
   * @return the created partner
   */
  @Operation(summary = "Create a trade partner")
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public PartnerDto create(@NotNull @Valid @RequestBody CreatePartnerRequest request) {
    return partnerService.create(request);
  }

  /**
   * Edits a partner.
   *
   * @param id the partner
   * @param request the new values and the echoed version
   * @return the edited partner
   */
  @Operation(summary = "Edit a trade partner")
  @PutMapping("/{id}")
  public PartnerDto update(
      @PathVariable UUID id, @NotNull @Valid @RequestBody UpdatePartnerRequest request) {
    return partnerService.update(id, request);
  }

  /**
   * Blocks a partner so no new deal or tranche can use it.
   *
   * @param id the partner
   * @param request the echoed version
   * @return the blocked partner
   */
  @Operation(summary = "Block a trade partner")
  @PostMapping("/{id}/block")
  public PartnerDto block(
      @PathVariable UUID id, @NotNull @Valid @RequestBody PurchasingVersionRequest request) {
    return partnerService.block(id, request.version());
  }

  /**
   * Lifts the block of a partner.
   *
   * @param id the partner
   * @param request the echoed version
   * @return the unblocked partner
   */
  @Operation(summary = "Unblock a trade partner")
  @PostMapping("/{id}/unblock")
  public PartnerDto unblock(
      @PathVariable UUID id, @NotNull @Valid @RequestBody PurchasingVersionRequest request) {
    return partnerService.unblock(id, request.version());
  }

  /**
   * Erases the handle and the notes of an individual partner; the deals stay.
   *
   * @param id the partner
   * @param request the echoed version
   * @return the erased partner
   */
  @Operation(summary = "Erase an individual trade partner's handle and notes")
  @PostMapping("/{id}/erase")
  public PartnerDto erase(
      @PathVariable UUID id, @NotNull @Valid @RequestBody PurchasingVersionRequest request) {
    return partnerService.erase(id, request.version());
  }
}
