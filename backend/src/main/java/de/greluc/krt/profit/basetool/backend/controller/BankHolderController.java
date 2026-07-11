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

import de.greluc.krt.profit.basetool.backend.model.dto.BankHolderBookingDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankHolderDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankTransactionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.request.BankHolderTransferRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.RegisterBankHolderRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.UpdateBankHolderRequest;
import de.greluc.krt.profit.basetool.backend.service.BankHolderService;
import de.greluc.krt.profit.basetool.backend.service.BankLedgerService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import de.greluc.krt.profit.basetool.backend.web.PaginationUtil;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the bank-local holder registry (epic #556, REQ-BANK-003): listing with global
 * custody totals, manual registration via the user lookup, activity toggling, and the holder→holder
 * Umbuchung (REQ-BANK-031). Reads and the Umbuchung are open to all bank staff (the holder menu,
 * REQ-BANK-030); manual registration and (de)activation are management-only.
 */
@RestController
@RequestMapping("/api/v1/bank/holders")
@RequiredArgsConstructor
public class BankHolderController {

  private static final Set<String> BOOKING_SORT_FIELDS = Set.of("createdAt", "id");

  private final BankHolderService bankHolderService;
  private final BankLedgerService bankLedgerService;

  /**
   * Lists the full holder registry with cross-account custody totals.
   *
   * @return every holder row, ordered by handle
   */
  @Operation(summary = "List the bank holder registry")
  @GetMapping
  @PreAuthorize("hasRole('" + Roles.BANK_EMPLOYEE + "')")
  @Transactional(readOnly = true)
  public List<BankHolderDto> getHolders() {
    return bankHolderService.getHolders();
  }

  /**
   * Reads one holder row with its global custody total (REQ-BANK-032) — the header of the holder
   * detail page. A bank employee may read only their own holder; management any holder (the {@code
   * canSeeHolder} gate enforces it).
   *
   * @param id the holder
   * @return the holder DTO incl. global custody total
   */
  @Operation(summary = "Read one bank holder incl. global custody total")
  @GetMapping("/{id}")
  @PreAuthorize("@bankSecurityService.canSeeHolder(#id, authentication)")
  @Transactional(readOnly = true)
  public BankHolderDto getHolder(@PathVariable @NotNull UUID id) {
    return bankHolderService.getHolder(id);
  }

  /**
   * Pages over one holder's custody history, newest first by default (REQ-BANK-032): every booking
   * that touched the holder's global stash, with the account it moved on and — for an Umbuchung —
   * the counter holder. A bank employee may read only their own holder's history; management any
   * (the {@code canSeeHolder} gate enforces it).
   *
   * @param id the holder
   * @param page zero-based page index
   * @param size page size
   * @param sort whitelisted sort spec
   * @return one page of the holder's booking rows
   */
  @Operation(summary = "List the custody history of a bank holder (paged)")
  @GetMapping("/{id}/transactions")
  @PreAuthorize("@bankSecurityService.canSeeHolder(#id, authentication)")
  @Transactional(readOnly = true)
  public PageResponse<BankHolderBookingDto> getHolderBookings(
      @PathVariable @NotNull UUID id,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    String effectiveSort = sort == null || sort.isBlank() ? "createdAt,desc" : sort;
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page, size, effectiveSort, BOOKING_SORT_FIELDS, "createdAt");
    Page<BankHolderBookingDto> result = bankHolderService.getHolderBookings(id, pageable);
    return PageResponse.of(result);
  }

  /**
   * Registers a basetool user as holder (REQ-BANK-003).
   *
   * @param request the user to register
   * @return the created holder row
   */
  @Operation(summary = "Register a user as bank holder (management)")
  @PostMapping
  @PreAuthorize("hasRole('" + Roles.BANK_MANAGEMENT + "')")
  @Transactional
  @ResponseStatus(HttpStatus.CREATED)
  public BankHolderDto registerHolder(@RequestBody @Valid RegisterBankHolderRequest request) {
    return bankHolderService.registerHolder(request);
  }

  /**
   * Toggles a holder's active flag (REQ-BANK-003).
   *
   * @param id the holder row
   * @param request the new flag plus echoed version
   * @return the updated holder row
   */
  @Operation(summary = "Activate or deactivate a bank holder (management)")
  @PatchMapping("/{id}")
  @PreAuthorize("hasRole('" + Roles.BANK_MANAGEMENT + "')")
  @Transactional
  public BankHolderDto updateHolder(
      @PathVariable @NotNull UUID id, @RequestBody @Valid UpdateBankHolderRequest request) {
    return bankHolderService.updateHolder(id, request);
  }

  /**
   * Books a holder→holder Umbuchung (REQ-BANK-031, ADR-0039): moves custody between two holders
   * without touching any account, so bank staff can reconcile imbalances and stay payout-capable.
   * Open to any bank employee (no per-account grant needed — it touches no account); the source
   * holder may go negative.
   *
   * @param request validated holder-transfer payload
   * @return acknowledgement of the created {@code HOLDER_TRANSFER} transaction
   */
  @Operation(summary = "Book a holder-to-holder Umbuchung")
  @PostMapping("/transfer")
  @PreAuthorize("hasRole('" + Roles.BANK_EMPLOYEE + "')")
  @Transactional
  @ResponseStatus(HttpStatus.CREATED)
  public BankTransactionDto transfer(@RequestBody @Valid BankHolderTransferRequest request) {
    return bankLedgerService.bookHolderTransfer(request);
  }
}
