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

import de.greluc.krt.profit.basetool.backend.model.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.model.dto.BankBookingOutcomeDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankBookingRequestDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankTransactionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankTransferFeeRateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.request.BankDepositRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.BankTransferRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.BankWithdrawalRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.ReverseBankTransactionRequest;
import de.greluc.krt.profit.basetool.backend.service.BankBookingGuards;
import de.greluc.krt.profit.basetool.backend.service.BankLedgerService;
import de.greluc.krt.profit.basetool.backend.service.BankSecurityService;
import de.greluc.krt.profit.basetool.backend.service.BankTransferFeeService;
import de.greluc.krt.profit.basetool.backend.service.OrgUnitBankAccessService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the booking flows (epic #556, REQ-BANK-004/-011): deposits, withdrawals,
 * account-to-account transfers and reversals (holder→holder Umbuchungen live on {@code
 * BankHolderController}, REQ-BANK-031). The capability gates evaluate the caller's grant flags on
 * the affected account via {@code BankSecurityService} — management and admins pass unrestricted;
 * reversals are management-only (spec open-question 3, v1 decision).
 */
@RestController
@RequestMapping("/api/v1/bank")
@RequiredArgsConstructor
public class BankBookingController {

  private final BankLedgerService bankLedgerService;
  private final BankBookingGuards bankBookingGuards;
  private final BankSecurityService bankSecurityService;
  private final BankTransferFeeService bankTransferFeeService;
  private final OrgUnitBankAccessService orgUnitBankAccessService;

  /**
   * Returns the current in-game transfer-fee rate (ADR-0052, REQ-BANK-033) so the booking modals
   * can render a live "Gebühr / wird abgebucht" preview (fee plus the gross debited) as the staffer
   * types the amount. Open to all bank staff (it reveals only the org-wide rate, no account data).
   *
   * @return the current fee rate (fraction in {@code [0, 1)})
   */
  @Operation(summary = "Read the current in-game transfer-fee rate")
  @GetMapping("/transfer-fee-rate")
  @PreAuthorize(Roles.HAS_ROLE_BANK_EMPLOYEE)
  @Transactional(readOnly = true)
  public BankTransferFeeRateDto getTransferFeeRate() {
    return new BankTransferFeeRateDto(bankTransferFeeService.resolveTransferFeeRate());
  }

  /**
   * Books a deposit onto an account the caller may deposit to (REQ-BANK-009).
   *
   * @param request validated deposit payload
   * @return acknowledgement of the created transaction
   */
  @Operation(summary = "Book a deposit")
  @PostMapping("/deposits")
  @PreAuthorize(
      "hasRole('"
          + Roles.BANK_EMPLOYEE
          + "') and @bankSecurityService.canDeposit(#request.accountId, authentication)")
  @Transactional
  @ResponseStatus(HttpStatus.CREATED)
  public BankTransactionDto bookDeposit(@RequestBody @Valid BankDepositRequest request) {
    return bankLedgerService.bookDeposit(request);
  }

  /**
   * Books a withdrawal from an account the caller may withdraw from (REQ-BANK-009), guarded by the
   * no-overdraft rule (REQ-BANK-006). For the KRT ({@code CARTEL}) account a plain bank employee
   * may book directly only up to the employee ceiling {@code T1}; above it the attempt is not
   * rejected but filed as a band-routed approval request (REQ-BANK-047, ADR-0109) and the response
   * carries a {@code pendingRequest} instead of a {@code transaction} with status {@code 202}.
   *
   * @param request validated withdrawal payload
   * @return {@code 201} with the booked transaction, or {@code 202} with the filed pending request
   */
  @Operation(summary = "Book a withdrawal (over-ceiling KRT amounts become an approval request)")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Withdrawal booked onto the ledger"),
    @ApiResponse(
        responseCode = "202",
        description = "Over the KRT employee ceiling: filed as a pending approval request")
  })
  @PostMapping("/withdrawals")
  @PreAuthorize(
      "hasRole('"
          + Roles.BANK_EMPLOYEE
          + "') and @bankSecurityService.canWithdraw(#request.accountId, authentication)")
  @Transactional
  public ResponseEntity<BankBookingOutcomeDto> bookWithdrawal(
      @RequestBody @Valid BankWithdrawalRequest request) {
    // REQ-BANK-047/ADR-0109: a plain bank employee may directly withdraw from the KRT account only
    // up to the employee ceiling T1; above it the attempt is filed as a band-routed approval
    // request (Bankleitung / Organisationsleitung) instead of being booked.
    if (bankBookingGuards.exceedsCartelDirectBookingCeiling(
        request.accountId(), request.amount())) {
      BankBookingRequestDto raised =
          orgUnitBankAccessService.raiseCartelDirectBookingRequest(
              request.accountId(),
              BankBookingRequestType.WITHDRAWAL,
              request.amount(),
              request.note(),
              request.justification(),
              null);
      return ResponseEntity.accepted().body(BankBookingOutcomeDto.requestRaised(raised));
    }
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(BankBookingOutcomeDto.booked(bankLedgerService.bookWithdrawal(request)));
  }

  /**
   * Books an account-to-account transfer: {@code can_transfer} on the source account is the gate;
   * the destination must be visible to the caller (REQ-BANK-011 destination rule) — evaluated here
   * and handed to the service.
   *
   * @param request validated transfer payload
   * @param authentication the caller's authentication (for the destination-visibility check)
   * @return acknowledgement of the created transaction
   */
  @Operation(
      summary = "Book an account-to-account transfer (over-ceiling KRT amounts become a request)")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Transfer booked onto the ledger"),
    @ApiResponse(
        responseCode = "202",
        description = "Over the KRT employee ceiling: filed as a pending approval request")
  })
  @PostMapping("/transfers")
  @PreAuthorize(
      "hasRole('"
          + Roles.BANK_EMPLOYEE
          + "') and @bankSecurityService.canTransfer(#request.sourceAccountId, authentication)")
  @Transactional
  public ResponseEntity<BankBookingOutcomeDto> bookTransfer(
      @RequestBody @Valid BankTransferRequest request, Authentication authentication) {
    // REQ-BANK-047/ADR-0109: a plain bank employee may directly transfer FROM the KRT account only
    // up to the employee ceiling T1; above it the attempt is filed as a band-routed approval
    // request (Bankleitung / Organisationsleitung) instead of being booked.
    if (bankBookingGuards.exceedsCartelDirectBookingCeiling(
        request.sourceAccountId(), request.amount())) {
      BankBookingRequestDto raised =
          orgUnitBankAccessService.raiseCartelDirectBookingRequest(
              request.sourceAccountId(),
              BankBookingRequestType.TRANSFER,
              request.amount(),
              request.note(),
              request.justification(),
              request.destinationAccountId());
      return ResponseEntity.accepted().body(BankBookingOutcomeDto.requestRaised(raised));
    }
    boolean destinationVisible =
        bankSecurityService.canSee(request.destinationAccountId(), authentication);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            BankBookingOutcomeDto.booked(
                bankLedgerService.bookTransfer(request, destinationVisible)));
  }

  /**
   * Reverses a transaction with a negated-mirror correction booking (REQ-BANK-004, ADR-0010).
   *
   * @param id the transaction to reverse
   * @param request optional correction note
   * @return acknowledgement of the created reversal
   */
  @Operation(summary = "Reverse a transaction (management)")
  @PostMapping("/transactions/{id}/reversal")
  @PreAuthorize(Roles.HAS_ROLE_BANK_MANAGEMENT)
  @Transactional
  @ResponseStatus(HttpStatus.CREATED)
  public BankTransactionDto reverseTransaction(
      @PathVariable @NotNull UUID id,
      @RequestBody(required = false) @Valid ReverseBankTransactionRequest request) {
    return bankLedgerService.reverseTransaction(id, request == null ? null : request.note());
  }
}
