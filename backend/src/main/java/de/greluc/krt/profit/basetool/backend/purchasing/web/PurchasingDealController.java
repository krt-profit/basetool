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
import de.greluc.krt.profit.basetool.backend.purchasing.api.DealStatus;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.AcceptDeliveryRequest;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.CreateDealRequest;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.DealDto;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.PurchasingDealService;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.PurchasingDeliveryService;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.PurchasingSettlementService;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.PurchasingTrancheService;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.PurchasingVersionRequest;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.RecordDeliveryRequest;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.RequestTrancheRequest;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.SettleDealRequest;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.UpdateDealRequest;
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
 * The trade deals (REQ-PUR-007 … REQ-PUR-022). Who may read or change a deal is decided in the
 * services: the delegate trades, the raising unit's lead or a Logistician decides deliveries, the
 * governing leadership and the bank read.
 */
@RestController
@RequestMapping("/api/v1/purchasing/deals")
@RequiredArgsConstructor
@PreAuthorize(Roles.HAS_ROLE_KRT_MEMBER)
public class PurchasingDealController {

  private static final Set<String> SORT_FIELDS = Set.of("createdAt", "dealNo", "total", "status");

  private final PurchasingDealService dealService;
  private final PurchasingTrancheService trancheService;
  private final PurchasingDeliveryService deliveryService;
  private final PurchasingSettlementService settlementService;

  /**
   * Pages over the deals the caller may read.
   *
   * @param status only this state
   * @param needId only the deals of this need
   * @param mine only the caller's own deals
   * @param overdue only the deals whose settlement is overdue
   * @param page zero-based page index
   * @param size page size
   * @param sort whitelisted sort spec
   * @return one page of deals
   */
  @Operation(summary = "List the deals the caller may read (paged)")
  @GetMapping
  public PageResponse<DealDto> list(
      @RequestParam(required = false) DealStatus status,
      @RequestParam(required = false) UUID needId,
      @RequestParam(defaultValue = "false") boolean mine,
      @RequestParam(defaultValue = "false") boolean overdue,
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
    return PageResponse.of(dealService.list(status, needId, mine, overdue, pageable));
  }

  /**
   * Reads one deal.
   *
   * @param id the deal
   * @return the deal with its tranches, deliveries and settlement
   */
  @Operation(summary = "Read one deal")
  @GetMapping("/{id}")
  public DealDto get(@PathVariable UUID id) {
    return dealService.get(id);
  }

  /**
   * Creates a draft deal.
   *
   * @param request the deal
   * @return the created draft
   */
  @Operation(summary = "Create a draft deal")
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public DealDto create(@NotNull @Valid @RequestBody CreateDealRequest request) {
    return dealService.create(request);
  }

  /**
   * Edits a draft deal.
   *
   * @param id the deal
   * @param request the new values and the echoed version
   * @return the edited draft
   */
  @Operation(summary = "Edit a draft deal")
  @PutMapping("/{id}")
  public DealDto update(
      @PathVariable UUID id, @NotNull @Valid @RequestBody UpdateDealRequest request) {
    return dealService.update(id, request);
  }

  /**
   * Cancels a deal that holds no confirmed money.
   *
   * @param id the deal
   * @param request the echoed version
   * @return the cancelled deal
   */
  @Operation(summary = "Cancel a deal before any money was confirmed")
  @PostMapping("/{id}/cancel")
  public DealDto cancel(
      @PathVariable UUID id, @NotNull @Valid @RequestBody PurchasingVersionRequest request) {
    return dealService.cancel(id, request.version());
  }

  /**
   * Requests a money tranche of the deal from the bank.
   *
   * @param id the deal
   * @param request kind and amount
   * @return the deal with the new tranche
   */
  @Operation(summary = "Request a money tranche of a deal from the bank")
  @PostMapping("/{id}/tranches")
  @ResponseStatus(HttpStatus.CREATED)
  public DealDto requestTranche(
      @PathVariable UUID id, @NotNull @Valid @RequestBody RequestTrancheRequest request) {
    return trancheService.request(id, request);
  }

  /**
   * Records a handover of goods.
   *
   * @param id the deal
   * @param request the delivered amount and location
   * @return the deal with the new delivery
   */
  @Operation(summary = "Record a delivery of a deal")
  @PostMapping("/{id}/deliveries")
  @ResponseStatus(HttpStatus.CREATED)
  public DealDto recordDelivery(
      @PathVariable UUID id, @NotNull @Valid @RequestBody RecordDeliveryRequest request) {
    return deliveryService.record(id, request);
  }

  /**
   * Decides a delivery and books the accepted part into the unit's pool.
   *
   * @param id the deal
   * @param deliveryId the delivery
   * @param request accepted and rejected amounts, quality and location
   * @return the deal after the decision
   */
  @Operation(summary = "Accept or reject a delivery of a deal")
  @PostMapping("/{id}/deliveries/{deliveryId}/accept")
  public DealDto acceptDelivery(
      @PathVariable UUID id,
      @PathVariable UUID deliveryId,
      @NotNull @Valid @RequestBody AcceptDeliveryRequest request) {
    return deliveryService.accept(id, deliveryId, request);
  }

  /**
   * Closes the delivery of a deal.
   *
   * @param id the deal
   * @return the closed deal
   */
  @Operation(summary = "Close the delivery of a deal")
  @PostMapping("/{id}/close-delivery")
  public DealDto closeDelivery(@PathVariable UUID id) {
    return deliveryService.close(id);
  }

  /**
   * Records or replaces the settlement of a deal.
   *
   * @param id the deal
   * @param request money paid, loss and the echoed settlement version
   * @return the deal with its settlement
   */
  @Operation(summary = "Record the settlement of a deal")
  @PutMapping("/{id}/settlement")
  public DealDto settle(
      @PathVariable UUID id, @NotNull @Valid @RequestBody SettleDealRequest request) {
    return settlementService.settle(id, request);
  }
}
