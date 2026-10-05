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

package de.greluc.krt.profit.basetool.frontend.controller;

import de.greluc.krt.profit.basetool.frontend.bank.client.BankBackendClient;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountLifecycleRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankDepositRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankHolderTransferRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankTransferRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankWithdrawalRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.ConfirmBankBookingRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.CreateBankAccountRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.CreateBankGrantRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.RegisterBankHolderRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.RejectBankBookingRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.RenameBankAccountRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.ReverseBankTransactionRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.SetBankBalanceTargetRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.SetCartelApprovalTiersRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.UpdateBankGrantRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.UpdateBankHolderRequest;
import de.greluc.krt.profit.basetool.frontend.support.PickerSearch;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * AJAX proxy for every bank mutation ({@code /api/proxy/bank/**}), forwarding the JSON body to the
 * matching {@code /api/v1/bank/**} endpoint. Backend RFC 7807 errors, including the bank 409 codes,
 * reach the browser as localized JSON; all authorization is decided by the backend.
 */
@RestController
@RequestMapping("/api/proxy/bank")
@RequiredArgsConstructor
@Slf4j
public class BankProxyController {

  /**
   * Number of matches one account-picker fetch returns: one more than the combobox renders ({@link
   * PickerSearch#PAGE_SIZE}), so the component can show its "keep typing" hint.
   */
  private static final int ACCOUNT_SEARCH_PAGE_SIZE = PickerSearch.PAGE_SIZE;

  /** The bank domain's backend calls. */
  private final BankBackendClient bankClient;

  /**
   * Server-side account search for the {@code remote-bank-accounts} combobox (REQ-BANK-053,
   * ADR-0106): forwards to the caller-scoped {@code /api/v1/bank/accounts} list, restricted to
   * active accounts matching the query, sorted by name. Returns an empty list on backend failure.
   *
   * @param query the name / account-number filter, or {@code null} / blank to match all
   * @return the matching active accounts, never {@code null}
   */
  @GetMapping("/accounts/search")
  @PreAuthorize("isAuthenticated()")
  public List<BankAccountDto> searchAccounts(@RequestParam(required = false) String query) {
    PageResponse<BankAccountDto> response =
        bankClient.searchActiveAccounts(query == null ? "" : query, ACCOUNT_SEARCH_PAGE_SIZE);
    return response != null && response.content() != null ? response.content() : List.of();
  }

  /**
   * Forwards a deposit booking.
   *
   * @param request the deposit
   * @return the booked transaction, or an empty object for a bodyless answer
   */
  @PostMapping("/deposits")
  @PreAuthorize("isAuthenticated()")
  @ResponseStatus(HttpStatus.CREATED)
  public Object bookDeposit(@RequestBody @NotNull BankDepositRequest request) {
    return orEmpty(bankClient.deposit(request));
  }

  /**
   * Forwards a withdrawal booking.
   *
   * @param request the withdrawal
   * @return the booking outcome, or an empty object for a bodyless answer
   */
  @PostMapping("/withdrawals")
  @PreAuthorize("isAuthenticated()")
  @ResponseStatus(HttpStatus.CREATED)
  public Object bookWithdrawal(@RequestBody @NotNull BankWithdrawalRequest request) {
    return orEmpty(bankClient.withdrawal(request));
  }

  /**
   * Forwards an account-to-account transfer.
   *
   * @param request the transfer
   * @return the booking outcome, or an empty object for a bodyless answer
   */
  @PostMapping("/transfers")
  @PreAuthorize("isAuthenticated()")
  @ResponseStatus(HttpStatus.CREATED)
  public Object bookTransfer(@RequestBody @NotNull BankTransferRequest request) {
    return orEmpty(bankClient.transfer(request));
  }

  /**
   * Forwards a holder→holder Umbuchung (REQ-BANK-031): moves custody between two holders, touching
   * no account. Open to bank employees on the backend.
   *
   * @param request the source and destination holder, amount and note
   * @return the booked transaction, or an empty object for a bodyless answer
   */
  @PostMapping("/holders/transfer")
  @PreAuthorize("isAuthenticated()")
  @ResponseStatus(HttpStatus.CREATED)
  public Object bookHolderTransfer(@RequestBody @NotNull BankHolderTransferRequest request) {
    return orEmpty(bankClient.holderTransfer(request));
  }

  /**
   * Forwards a reversal of one transaction (management-only on the backend).
   *
   * @param id the transaction to reverse
   * @param request the optional correction note
   * @return the reversal transaction, or an empty object for a bodyless answer
   */
  @PostMapping("/transactions/{id}/reversal")
  @PreAuthorize("isAuthenticated()")
  @ResponseStatus(HttpStatus.CREATED)
  public Object reverseTransaction(
      @PathVariable @NotNull UUID id,
      @RequestBody(required = false) @Nullable ReverseBankTransactionRequest request) {
    return orEmpty(
        bankClient.reverseTransaction(
            id, request == null ? new ReverseBankTransactionRequest(null) : request));
  }

  /**
   * Forwards a bank employee's confirmation of a pending booking request, recording the holder and
   * booking the ledger (REQ-BANK-023).
   *
   * @param id the request to confirm
   * @param request the recorded holders and the echoed version
   * @return the confirmed request, or an empty object for a bodyless answer
   */
  @PostMapping("/requests/{id}/confirm")
  @PreAuthorize("isAuthenticated()")
  public Object confirmBookingRequest(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull ConfirmBankBookingRequest request) {
    return orEmpty(bankClient.confirmRequest(id, request));
  }

  /**
   * Forwards a bank employee's rejection of a pending booking request with a reason (REQ-BANK-023).
   *
   * @param id the request to reject
   * @param request the reason and the echoed version
   * @return the rejected request, or an empty object for a bodyless answer
   */
  @PostMapping("/requests/{id}/reject")
  @PreAuthorize("isAuthenticated()")
  public Object rejectBookingRequest(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull RejectBankBookingRequest request) {
    return orEmpty(bankClient.rejectRequest(id, request));
  }

  /**
   * Forwards an account creation (the backend lets management create any type and employees create
   * SPECIAL only, REQ-BANK-030; singleton 409s surface inline).
   *
   * @param request the new account
   * @return the created account, or an empty object for a bodyless answer
   */
  @PostMapping("/accounts")
  @PreAuthorize("isAuthenticated()")
  @ResponseStatus(HttpStatus.CREATED)
  public Object createAccount(@RequestBody @NotNull CreateBankAccountRequest request) {
    return orEmpty(bankClient.createAccount(request));
  }

  /**
   * Forwards an account rename.
   *
   * @param id the account
   * @param request the new name and the echoed version
   * @return the updated account, or an empty object for a bodyless answer
   */
  @PatchMapping("/accounts/{id}")
  @PreAuthorize("isAuthenticated()")
  public Object renameAccount(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull RenameBankAccountRequest request) {
    return orEmpty(bankClient.renameAccount(id, request));
  }

  /**
   * Forwards setting/clearing an account's balance target (REQ-BANK-036). A {@code null} target
   * clears it.
   *
   * @param id the account
   * @param request the target and the echoed version
   * @return the updated account, or an empty object for a bodyless answer
   */
  @PatchMapping("/accounts/{id}/balance-target")
  @PreAuthorize("isAuthenticated()")
  public Object setBalanceTarget(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull SetBankBalanceTargetRequest request) {
    return orEmpty(bankClient.setBalanceTarget(id, request));
  }

  /**
   * Forwards setting/clearing the KRT-account (CARTEL) 3-stage approval thresholds T1/T2
   * (REQ-BANK-047). Bank-management-only, enforced by the backend. A {@code null} ceiling clears
   * that band.
   *
   * @param id the KRT account
   * @param request the two ceilings and the echoed version
   * @return the updated account, or an empty object for a bodyless answer
   */
  @PatchMapping("/accounts/{id}/approval-tiers")
  @PreAuthorize("isAuthenticated()")
  public Object setCartelApprovalTiers(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull SetCartelApprovalTiersRequest request) {
    return orEmpty(bankClient.setApprovalTiers(id, request));
  }

  /**
   * Forwards an account close (zero-balance rule enforced by the backend, 409 inline).
   *
   * @param id the account
   * @param request the echoed version
   * @return the updated account, or an empty object for a bodyless answer
   */
  @PostMapping("/accounts/{id}/close")
  @PreAuthorize("isAuthenticated()")
  public Object closeAccount(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull BankAccountLifecycleRequest request) {
    return orEmpty(bankClient.closeAccount(id, request));
  }

  /**
   * Forwards an account reopen.
   *
   * @param id the account
   * @param request the echoed version
   * @return the updated account, or an empty object for a bodyless answer
   */
  @PostMapping("/accounts/{id}/reopen")
  @PreAuthorize("isAuthenticated()")
  public Object reopenAccount(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull BankAccountLifecycleRequest request) {
    return orEmpty(bankClient.reopenAccount(id, request));
  }

  /**
   * Forwards a holder registration.
   *
   * @param request the user to register
   * @return the created holder row, or an empty object for a bodyless answer
   */
  @PostMapping("/holders")
  @PreAuthorize("isAuthenticated()")
  @ResponseStatus(HttpStatus.CREATED)
  public Object registerHolder(@RequestBody @NotNull RegisterBankHolderRequest request) {
    return orEmpty(bankClient.registerHolder(request));
  }

  /**
   * Forwards a holder activity toggle.
   *
   * @param id the holder row
   * @param request the new flag and the echoed version
   * @return the updated holder row, or an empty object for a bodyless answer
   */
  @PatchMapping("/holders/{id}")
  @PreAuthorize("isAuthenticated()")
  public Object updateHolder(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull UpdateBankHolderRequest request) {
    return orEmpty(bankClient.updateHolder(id, request));
  }

  /**
   * Forwards a grant creation (grantee-role rule enforced by the backend, 409 inline).
   *
   * @param request the grantee, the account and the initial flags
   * @return the created grant row, or an empty object for a bodyless answer
   */
  @PostMapping("/grants")
  @PreAuthorize("isAuthenticated()")
  @ResponseStatus(HttpStatus.CREATED)
  public Object createGrant(@RequestBody @NotNull CreateBankGrantRequest request) {
    return orEmpty(bankClient.createGrant(request));
  }

  /**
   * Forwards a grant flag change from the matrix toggles.
   *
   * @param userId the grantee half of the composite key
   * @param accountId the account half of the composite key
   * @param request the three flags and the echoed version
   * @return the updated grant row, or an empty object for a bodyless answer
   */
  @PatchMapping("/grants/{userId}/{accountId}")
  @PreAuthorize("isAuthenticated()")
  public Object updateGrant(
      @PathVariable @NotNull UUID userId,
      @PathVariable @NotNull UUID accountId,
      @RequestBody @NotNull UpdateBankGrantRequest request) {
    return orEmpty(bankClient.updateGrant(userId, accountId, request));
  }

  /**
   * Forwards a grant revocation.
   *
   * @param userId the grantee half of the composite key
   * @param accountId the account half of the composite key
   */
  @DeleteMapping("/grants/{userId}/{accountId}")
  @PreAuthorize("isAuthenticated()")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteGrant(
      @PathVariable @NotNull UUID userId, @PathVariable @NotNull UUID accountId) {
    bankClient.deleteGrant(userId, accountId);
  }

  /**
   * Returns the backend's answer, or an empty object for a bodyless 2xx, keeping the browser
   * contract uniform.
   *
   * @param response the backend's answer, or {@code null}
   * @return the answer, or an empty map
   */
  @NotNull
  private static Object orEmpty(@Nullable Object response) {
    return response == null ? Map.of() : response;
  }
}
