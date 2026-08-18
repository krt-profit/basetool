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

import de.greluc.krt.profit.basetool.backend.model.BankBookingRequestStatus;
import de.greluc.krt.profit.basetool.backend.model.dto.BankBookingRequestDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.request.ConfirmBankBookingRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.RejectBankBookingRequest;
import de.greluc.krt.profit.basetool.backend.service.BankBookingRequestService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import de.greluc.krt.profit.basetool.backend.web.PaginationUtil;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The bank-staff side of the confirm-before-post booking requests (epic #666 F2, REQ-BANK-023),
 * living under {@code /api/v1/bank/requests} — inside the {@code /api/v1/bank/**} space whose
 * authenticated catch-all plus this class-level {@code BANK_EMPLOYEE} gate keep org-unit
 * officers/leads out entirely; they raise and cancel requests through {@code
 * /api/v1/org-units/bank} instead. The per-account capability (confirm) and visibility (reject,
 * queue) decisions depend on the request's account, which is only known after the request is
 * loaded, so they are enforced inside {@link BankBookingRequestService} rather than in a method
 * {@code @PreAuthorize} expression.
 */
@RestController
@RequestMapping("/api/v1/bank/requests")
@RequiredArgsConstructor
@PreAuthorize(Roles.HAS_ROLE_BANK_EMPLOYEE)
public class BankRequestController {

  private static final Set<String> QUEUE_SORT_FIELDS = Set.of("createdAt", "amount", "id");

  private final BankBookingRequestService bankBookingRequestService;

  /**
   * Pages over the booking requests in the given lifecycle state the caller may act on
   * (REQ-BANK-023). Management sees every account; an employee sees only requests on accounts they
   * are granted on. Defaults to the {@code PENDING} work queue, newest first.
   *
   * @param status the lifecycle states to include (repeatable or comma-separated); defaults to
   *     {@code PENDING} when omitted
   * @param page zero-based page index
   * @param size page size
   * @param sort whitelisted sort spec
   * @return one page of requests visible to the caller in any of those states
   */
  @Operation(summary = "List booking requests for the bank-staff confirmation queue (paged)")
  @GetMapping
  public PageResponse<BankBookingRequestDto> getQueue(
      @RequestParam(required = false) Set<BankBookingRequestStatus> status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    Set<BankBookingRequestStatus> effectiveStatuses =
        status == null || status.isEmpty() ? Set.of(BankBookingRequestStatus.PENDING) : status;
    String effectiveSort = sort == null || sort.isBlank() ? "createdAt,desc" : sort;
    Pageable pageable =
        PaginationUtil.createPageRequest(page, size, effectiveSort, QUEUE_SORT_FIELDS, "createdAt");
    Page<BankBookingRequestDto> result =
        bankBookingRequestService.listQueue(effectiveStatuses, pageable);
    return PageResponse.of(result);
  }

  /**
   * Confirms a pending request: records the holder(s) and books the deposit / withdrawal / transfer
   * onto the ledger (REQ-BANK-023/-040/-041). Requires the per-account capability matching the
   * request type and, for an over-limit request, the responsible-holder approval attestation — both
   * enforced in the service.
   *
   * @param id the request to confirm
   * @param request the holder(s), the over-limit approval attestation and the echoed version
   * @param authentication the current authentication (capability check)
   * @return the confirmed request
   */
  @Operation(summary = "Confirm a pending booking request and book it onto the ledger")
  @PostMapping("/{id}/confirm")
  public BankBookingRequestDto confirm(
      @PathVariable UUID id,
      @Valid @RequestBody ConfirmBankBookingRequest request,
      Authentication authentication) {
    return bankBookingRequestService.confirm(
        id,
        request.holderId(),
        request.destinationHolderId(),
        request.ownerApprovalConfirmed(),
        request.staffNote(),
        request.version(),
        authentication);
  }

  /**
   * Rejects a pending request with a reason; no money moves (REQ-BANK-023). Requires visibility of
   * the request's account, enforced in the service.
   *
   * @param id the request to reject
   * @param request the rejection reason and the echoed version
   * @param authentication the current authentication (visibility check)
   * @return the rejected request
   */
  @Operation(summary = "Reject a pending booking request with a reason")
  @PostMapping("/{id}/reject")
  public BankBookingRequestDto reject(
      @PathVariable UUID id,
      @Valid @RequestBody RejectBankBookingRequest request,
      Authentication authentication) {
    return bankBookingRequestService.reject(
        id, request.reason(), request.version(), authentication);
  }
}
