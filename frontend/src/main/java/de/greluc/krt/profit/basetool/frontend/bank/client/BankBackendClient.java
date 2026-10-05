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

package de.greluc.krt.profit.basetool.frontend.bank.client;

import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountDetailDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountLifecycleRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountRefDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankBalanceSeriesDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankBookingDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankBookingOutcomeDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankBookingRequestDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankDashboardDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankDepositRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankGrantDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankHolderBookingDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankHolderDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankHolderTransferRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankTransactionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankTransferFeeRateDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankTransferRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankWipeResetResultDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankWithdrawalRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.CancelBankBookingRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.ConfirmBankBookingRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.CreateBankAccountRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.CreateBankBookingRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.CreateBankGrantRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitBalanceTargetRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitBankAccountDetailDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitBankAccountSettingsDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitBankBalanceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.RegisterBankHolderRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.RejectBankBookingRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.RenameBankAccountRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.ReverseBankTransactionRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.SetBankApprovalLimitRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.SetBankBalanceTargetRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.SetCartelApprovalTiersRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.UpdateBankBookingRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.UpdateBankGrantRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.UpdateBankHolderRequest;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Typed backend client of the bank domain: the staff surfaces under {@code /api/v1/bank}, the
 * org-unit bank view under {@code /api/v1/org-units/bank}, their PDF exports and the admin wipe
 * reset (REQ-BANK-*), over {@link BackendApiClient} (plan §5.9, ADR-0032).
 *
 * <p>Every write sends the backend's own request record and decodes its answer into the mirrored
 * response record; the path-only org-unit writes relay the browser's body, which the backend does
 * not bind.
 */
@Service
@RequiredArgsConstructor
public class BankBackendClient {

  private static final ParameterizedTypeReference<List<BankHolderDto>> HOLDER_LIST =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<OrgUnitMembershipOptionDto>>
      ORG_UNIT_OPTION_LIST = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<BankBookingDto>> BOOKING_PAGE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<BankHolderBookingDto>>
      HOLDER_BOOKING_PAGE = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<BankGrantDto>> GRANT_LIST =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<BankAccountDto>> ACCOUNT_PAGE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<BankBookingRequestDto>>
      BOOKING_REQUEST_PAGE = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<OrgUnitBankBalanceDto>> BALANCE_LIST =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<BankBookingRequestDto>>
      BOOKING_REQUEST_LIST = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<BankAccountRefDto>> ACCOUNT_REF_LIST =
      new ParameterizedTypeReference<>() {};

  /** Sends every call through the one filter chain and error mapping. */
  private final BackendApiClient backendApiClient;

  /**
   * Reads the bank dashboard with every account the caller may see (REQ-BANK-016).
   *
   * @return the dashboard, or {@code null} when the backend sent no body
   */
  @Nullable
  public BankDashboardDto dashboard() {
    return backendApiClient.get("/api/v1/bank/dashboard", BankDashboardDto.class);
  }

  /**
   * Reads the bank-wide holder registry with custody totals.
   *
   * @return the holders, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<BankHolderDto> holders() {
    return backendApiClient.get("/api/v1/bank/holders", HOLDER_LIST);
  }

  /**
   * Reads one holder with its custody total (REQ-BANK-032).
   *
   * @param id the holder id
   * @return the holder, or {@code null} when the backend sent no body
   */
  @Nullable
  public BankHolderDto holder(@NotNull UUID id) {
    return backendApiClient.get("/api/v1/bank/holders/{id}", BankHolderDto.class, id);
  }

  /**
   * Reads one page of twenty rows of a holder's custody history.
   *
   * @param id the holder id
   * @param page the zero-based page index
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<BankHolderBookingDto> holderBookings(@NotNull UUID id, int page) {
    return backendApiClient.get(
        "/api/v1/bank/holders/{id}/transactions?page={page}&size=20",
        HOLDER_BOOKING_PAGE,
        id,
        page);
  }

  /**
   * Reads the active org units of every kind for the external-counterparty picklist (REQ-BANK-044).
   *
   * @return the options, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<OrgUnitMembershipOptionDto> activeOrgUnitsAllKinds() {
    return backendApiClient.get("/api/v1/org-units/active-all-kinds", ORG_UNIT_OPTION_LIST);
  }

  /**
   * Reads the active org units of every kind through the catalogue cache.
   *
   * @return the options, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<OrgUnitMembershipOptionDto> cachedActiveOrgUnitsAllKinds() {
    return backendApiClient.getCached(
        CachedCatalog.ORG_UNITS_ACTIVE_ALL_KINDS, ORG_UNIT_OPTION_LIST);
  }

  /**
   * Reads one account with its KPIs and Konto-Info.
   *
   * @param id the account id
   * @return the account detail, or {@code null} when the backend sent no body
   */
  @Nullable
  public BankAccountDetailDto account(@NotNull UUID id) {
    return backendApiClient.get("/api/v1/bank/accounts/{id}", BankAccountDetailDto.class, id);
  }

  /**
   * Reads one page of an account's booking history for a period (REQ-BANK-051).
   *
   * @param id the account id
   * @param page the zero-based page index
   * @param size the page size
   * @param from inclusive period start
   * @param to inclusive period end
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<BankBookingDto> accountBookings(
      @NotNull UUID id, int page, int size, @NotNull Instant from, @NotNull Instant to) {
    String uri =
        UriComponentsBuilder.fromPath("/api/v1/bank/accounts/{id}/transactions")
            .queryParam("page", page)
            .queryParam("size", size)
            .queryParam("from", from)
            .queryParam("to", to)
            .encode()
            .build()
            .toUriString();
    return backendApiClient.get(uri, BOOKING_PAGE, id);
  }

  /**
   * Reads an account's balance-over-time series for a period (REQ-BANK-049).
   *
   * @param id the account id
   * @param from inclusive period start
   * @param to inclusive period end
   * @return the series, or {@code null} when the backend sent no body
   */
  @Nullable
  public BankBalanceSeriesDto balanceSeries(
      @NotNull UUID id, @NotNull Instant from, @NotNull Instant to) {
    String uri =
        UriComponentsBuilder.fromPath("/api/v1/bank/accounts/{id}/balance-series")
            .queryParam("from", from)
            .queryParam("to", to)
            .encode()
            .build()
            .toUriString();
    return backendApiClient.get(uri, BankBalanceSeriesDto.class, id);
  }

  /**
   * Reads the in-game transfer-fee rate for the booking-modal preview (REQ-BANK-033).
   *
   * @return the rate, or {@code null} when the backend sent no body
   */
  @Nullable
  public BankTransferFeeRateDto transferFeeRate() {
    return backendApiClient.get("/api/v1/bank/transfer-fee-rate", BankTransferFeeRateDto.class);
  }

  /**
   * Reads the bank grants, filtered by grantee when one is given, otherwise by account when one is
   * given, otherwise unfiltered.
   *
   * @param userId the grantee to filter by, or {@code null}
   * @param accountId the account to filter by when no grantee is given, or {@code null}
   * @return the grants, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<BankGrantDto> grants(@Nullable UUID userId, @Nullable UUID accountId) {
    UriComponentsBuilder grantsUri = UriComponentsBuilder.fromPath("/api/v1/bank/grants");
    if (userId != null) {
      grantsUri.queryParam("userId", userId);
    } else if (accountId != null) {
      grantsUri.queryParam("accountId", accountId);
    }
    return backendApiClient.get(grantsUri.toUriString(), GRANT_LIST);
  }

  /**
   * Reads one page of the accounts the caller may see, sorted by name (REQ-BANK-053).
   *
   * @param page the zero-based page index
   * @param size the page size
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<BankAccountDto> accountPage(int page, int size) {
    return backendApiClient.get(
        "/api/v1/bank/accounts?page={page}&size={size}&sort=name,asc", ACCOUNT_PAGE, page, size);
  }

  /**
   * Reads a one-row page holding the singleton {@code CARTEL} account (REQ-BANK-047).
   *
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<BankAccountDto> cartelAccountPage() {
    return backendApiClient.get("/api/v1/bank/accounts?type=CARTEL&size=1", ACCOUNT_PAGE);
  }

  /**
   * Reads a one-row page of the active accounts, whose total says whether any exists.
   *
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<BankAccountDto> activeAccountProbe() {
    return backendApiClient.get("/api/v1/bank/accounts?status=ACTIVE&size=1", ACCOUNT_PAGE);
  }

  /**
   * Reads one page of the booking requests in the given states the caller may act on
   * (REQ-BANK-023).
   *
   * @param statuses the lifecycle states to include, each sent as its own {@code status} value
   * @param size the page size
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<BankBookingRequestDto> bookingRequests(
      @NotNull List<String> statuses, int size) {
    UriComponentsBuilder uri =
        UriComponentsBuilder.fromPath("/api/v1/bank/requests").queryParam("size", size);
    statuses.forEach(s -> uri.queryParam("status", s));
    return backendApiClient.get(uri.toUriString(), BOOKING_REQUEST_PAGE);
  }

  /**
   * Searches the active accounts by name or account number for the account picker, sorted by name
   * (REQ-BANK-053).
   *
   * @param query the filter, empty to match all
   * @param size the number of matches to return
   * @return the page of matching accounts, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<BankAccountDto> searchActiveAccounts(@NotNull String query, int size) {
    String uri =
        UriComponentsBuilder.fromPath("/api/v1/bank/accounts")
            .queryParam("status", "ACTIVE")
            .queryParam("size", size)
            .queryParam("sort", "name,asc")
            .toUriString();
    return backendApiClient.get(uri + "&query={query}", ACCOUNT_PAGE, query);
  }

  /**
   * Books a deposit (REQ-BANK-004).
   *
   * @param request the deposit
   * @return the booked transaction, or {@code null} when the backend sent no body
   */
  @Nullable
  public BankTransactionDto deposit(@NotNull BankDepositRequest request) {
    return backendApiClient.post("/api/v1/bank/deposits", request, BankTransactionDto.class);
  }

  /**
   * Books a withdrawal, or files a pending request when it exceeds the KRT ceiling (REQ-BANK-047).
   *
   * @param request the withdrawal
   * @return the outcome, or {@code null} when the backend sent no body
   */
  @Nullable
  public BankBookingOutcomeDto withdrawal(@NotNull BankWithdrawalRequest request) {
    return backendApiClient.post("/api/v1/bank/withdrawals", request, BankBookingOutcomeDto.class);
  }

  /**
   * Books an account-to-account transfer, or files a pending request when it exceeds the KRT
   * ceiling (REQ-BANK-047).
   *
   * @param request the transfer
   * @return the outcome, or {@code null} when the backend sent no body
   */
  @Nullable
  public BankBookingOutcomeDto transfer(@NotNull BankTransferRequest request) {
    return backendApiClient.post("/api/v1/bank/transfers", request, BankBookingOutcomeDto.class);
  }

  /**
   * Books a holder-to-holder custody move (REQ-BANK-031).
   *
   * @param request the Umbuchung
   * @return the booked transaction, or {@code null} when the backend sent no body
   */
  @Nullable
  public BankTransactionDto holderTransfer(@NotNull BankHolderTransferRequest request) {
    return backendApiClient.post(
        "/api/v1/bank/holders/transfer", request, BankTransactionDto.class);
  }

  /**
   * Reverses one transaction.
   *
   * @param id the transaction
   * @param request the correction note
   * @return the reversal transaction, or {@code null} when the backend sent no body
   */
  @Nullable
  public BankTransactionDto reverseTransaction(
      @NotNull UUID id, @NotNull ReverseBankTransactionRequest request) {
    return backendApiClient.post(
        "/api/v1/bank/transactions/{id}/reversal", request, BankTransactionDto.class, id);
  }

  /**
   * Confirms a pending booking request (REQ-BANK-023).
   *
   * @param id the request
   * @param request the recorded holders and the echoed version
   * @return the confirmed request, or {@code null} when the backend sent no body
   */
  @Nullable
  public BankBookingRequestDto confirmRequest(
      @NotNull UUID id, @NotNull ConfirmBankBookingRequest request) {
    return backendApiClient.post(
        "/api/v1/bank/requests/{id}/confirm", request, BankBookingRequestDto.class, id);
  }

  /**
   * Rejects a pending booking request (REQ-BANK-023).
   *
   * @param id the request
   * @param request the reason and the echoed version
   * @return the rejected request, or {@code null} when the backend sent no body
   */
  @Nullable
  public BankBookingRequestDto rejectRequest(
      @NotNull UUID id, @NotNull RejectBankBookingRequest request) {
    return backendApiClient.post(
        "/api/v1/bank/requests/{id}/reject", request, BankBookingRequestDto.class, id);
  }

  /**
   * Creates an account (REQ-BANK-030).
   *
   * @param request the new account
   * @return the created account, or {@code null} when the backend sent no body
   */
  @Nullable
  public BankAccountDto createAccount(@NotNull CreateBankAccountRequest request) {
    return backendApiClient.post("/api/v1/bank/accounts", request, BankAccountDto.class);
  }

  /**
   * Renames an account.
   *
   * @param id the account
   * @param request the new name and the echoed version
   * @return the updated account, or {@code null} when the backend sent no body
   */
  @Nullable
  public BankAccountDto renameAccount(@NotNull UUID id, @NotNull RenameBankAccountRequest request) {
    return backendApiClient.patch("/api/v1/bank/accounts/{id}", request, BankAccountDto.class, id);
  }

  /**
   * Sets or clears an account's balance target (REQ-BANK-036).
   *
   * @param id the account
   * @param request the target and the echoed version
   * @return the updated account, or {@code null} when the backend sent no body
   */
  @Nullable
  public BankAccountDto setBalanceTarget(
      @NotNull UUID id, @NotNull SetBankBalanceTargetRequest request) {
    return backendApiClient.patch(
        "/api/v1/bank/accounts/{id}/balance-target", request, BankAccountDto.class, id);
  }

  /**
   * Sets or clears the KRT account's approval thresholds (REQ-BANK-047).
   *
   * @param id the KRT account
   * @param request the thresholds and the echoed version
   * @return the updated account, or {@code null} when the backend sent no body
   */
  @Nullable
  public BankAccountDto setApprovalTiers(
      @NotNull UUID id, @NotNull SetCartelApprovalTiersRequest request) {
    return backendApiClient.patch(
        "/api/v1/bank/accounts/{id}/approval-tiers", request, BankAccountDto.class, id);
  }

  /**
   * Closes an account.
   *
   * @param id the account
   * @param request the echoed version
   * @return the updated account, or {@code null} when the backend sent no body
   */
  @Nullable
  public BankAccountDto closeAccount(
      @NotNull UUID id, @NotNull BankAccountLifecycleRequest request) {
    return backendApiClient.post(
        "/api/v1/bank/accounts/{id}/close", request, BankAccountDto.class, id);
  }

  /**
   * Reopens an account.
   *
   * @param id the account
   * @param request the echoed version
   * @return the updated account, or {@code null} when the backend sent no body
   */
  @Nullable
  public BankAccountDto reopenAccount(
      @NotNull UUID id, @NotNull BankAccountLifecycleRequest request) {
    return backendApiClient.post(
        "/api/v1/bank/accounts/{id}/reopen", request, BankAccountDto.class, id);
  }

  /**
   * Registers a holder (REQ-BANK-003).
   *
   * @param request the user to register
   * @return the created holder, or {@code null} when the backend sent no body
   */
  @Nullable
  public BankHolderDto registerHolder(@NotNull RegisterBankHolderRequest request) {
    return backendApiClient.post("/api/v1/bank/holders", request, BankHolderDto.class);
  }

  /**
   * Toggles a holder's activity.
   *
   * @param id the holder row
   * @param request the new flag and the echoed version
   * @return the updated holder, or {@code null} when the backend sent no body
   */
  @Nullable
  public BankHolderDto updateHolder(@NotNull UUID id, @NotNull UpdateBankHolderRequest request) {
    return backendApiClient.patch("/api/v1/bank/holders/{id}", request, BankHolderDto.class, id);
  }

  /**
   * Creates a grant (REQ-BANK-009).
   *
   * @param request the grantee, the account and the initial flags
   * @return the created grant, or {@code null} when the backend sent no body
   */
  @Nullable
  public BankGrantDto createGrant(@NotNull CreateBankGrantRequest request) {
    return backendApiClient.post("/api/v1/bank/grants", request, BankGrantDto.class);
  }

  /**
   * Changes a grant's capability flags.
   *
   * @param userId the grantee half of the composite key
   * @param accountId the account half of the composite key
   * @param request the three flags and the echoed version
   * @return the updated grant, or {@code null} when the backend sent no body
   */
  @Nullable
  public BankGrantDto updateGrant(
      @NotNull UUID userId, @NotNull UUID accountId, @NotNull UpdateBankGrantRequest request) {
    return backendApiClient.patch(
        "/api/v1/bank/grants/{userId}/{accountId}", request, BankGrantDto.class, userId, accountId);
  }

  /**
   * Revokes a grant.
   *
   * @param userId the grantee half of the composite key
   * @param accountId the account half of the composite key
   */
  public void deleteGrant(@NotNull UUID userId, @NotNull UUID accountId) {
    backendApiClient.delete(
        "/api/v1/bank/grants/{userId}/{accountId}", Void.class, userId, accountId);
  }

  /**
   * Downloads an account statement PDF for a period (REQ-BANK-014).
   *
   * @param id the account id
   * @param from period start
   * @param to period end
   * @param userTimeZone the caller's IANA time zone, forwarded when not blank
   * @return the PDF bytes, or {@code null} when the backend sent no body
   */
  public byte @Nullable [] accountStatement(
      @NotNull UUID id, @NotNull Instant from, @NotNull Instant to, @Nullable String userTimeZone) {
    String uri =
        UriComponentsBuilder.fromPath("/api/v1/bank/accounts/{id}/statement")
            .queryParam("from", from)
            .queryParam("to", to)
            .encode()
            .build()
            .toUriString();
    return pdf(uri, new Object[] {id}, userTimeZone);
  }

  /**
   * Downloads the management three-month report PDF (REQ-BANK-015).
   *
   * @param userTimeZone the caller's IANA time zone, forwarded when not blank
   * @return the PDF bytes, or {@code null} when the backend sent no body
   */
  public byte @Nullable [] threeMonthReport(@Nullable String userTimeZone) {
    return pdf("/api/v1/bank/export/three-month-report", new Object[0], userTimeZone);
  }

  /**
   * Runs the admin wipe reset of every account and holder stash (REQ-BANK-013).
   *
   * @return the affected counts, or {@code null} when the backend sent no body
   */
  @Nullable
  public BankWipeResetResultDto wipeReset() {
    return backendApiClient.post(
        "/api/v1/bank/admin/wipe-reset", Map.of(), BankWipeResetResultDto.class);
  }

  /**
   * Reads the caller's viewable org-unit bank balance cards with their 30-day sparklines.
   *
   * @return the balance cards, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<OrgUnitBankBalanceDto> orgUnitBalances() {
    return backendApiClient.get("/api/v1/org-units/bank/balances", BALANCE_LIST);
  }

  /**
   * Reads the caller's own org-unit booking requests (REQ-BANK-022).
   *
   * @return the requests, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<BankBookingRequestDto> ownOrgUnitRequests() {
    return backendApiClient.get("/api/v1/org-units/bank/requests", BOOKING_REQUEST_LIST);
  }

  /**
   * Reads the booking requests on the accounts the caller is responsible for.
   *
   * @return the requests, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<BankBookingRequestDto> foreignOrgUnitRequests() {
    return backendApiClient.get("/api/v1/org-units/bank/requests/foreign", BOOKING_REQUEST_LIST);
  }

  /**
   * Reads the active accounts offered as transfer-request destinations (REQ-BANK-040).
   *
   * @return the targets, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<BankAccountRefDto> orgUnitTransferTargets() {
    return backendApiClient.get("/api/v1/org-units/bank/transfer-targets", ACCOUNT_REF_LIST);
  }

  /**
   * Reads the redacted org-unit view of one account with the caller's rights on it (REQ-BANK-038).
   *
   * @param id the account id
   * @return the account detail, or {@code null} when the backend sent no body
   */
  @Nullable
  public OrgUnitBankAccountDetailDto orgUnitAccount(@NotNull UUID id) {
    return backendApiClient.get(
        "/api/v1/org-units/bank/accounts/{id}", OrgUnitBankAccountDetailDto.class, id);
  }

  /**
   * Reads the holder/OL settings of one account (REQ-BANK-035, REQ-BANK-041).
   *
   * @param id the account id
   * @return the settings, or {@code null} when the backend sent no body
   */
  @Nullable
  public OrgUnitBankAccountSettingsDto orgUnitAccountSettings(@NotNull UUID id) {
    return backendApiClient.get(
        "/api/v1/org-units/bank/accounts/{id}/settings", OrgUnitBankAccountSettingsDto.class, id);
  }

  /**
   * Reads one page of an account's redacted booking history for a period (REQ-BANK-051).
   *
   * @param id the account id
   * @param page the zero-based page index
   * @param size the page size
   * @param from inclusive period start
   * @param to inclusive period end
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<BankBookingDto> orgUnitAccountBookings(
      @NotNull UUID id, int page, int size, @NotNull Instant from, @NotNull Instant to) {
    String uri =
        UriComponentsBuilder.fromPath("/api/v1/org-units/bank/accounts/{id}/transactions")
            .queryParam("page", page)
            .queryParam("size", size)
            .queryParam("from", from)
            .queryParam("to", to)
            .encode()
            .build()
            .toUriString();
    return backendApiClient.get(uri, BOOKING_PAGE, id);
  }

  /**
   * Reads an account's balance-over-time series through the org-unit view (REQ-BANK-049).
   *
   * @param id the account id
   * @param from inclusive period start
   * @param to inclusive period end
   * @return the series, or {@code null} when the backend sent no body
   */
  @Nullable
  public BankBalanceSeriesDto orgUnitBalanceSeries(
      @NotNull UUID id, @NotNull Instant from, @NotNull Instant to) {
    String uri =
        UriComponentsBuilder.fromPath("/api/v1/org-units/bank/accounts/{id}/balance-series")
            .queryParam("from", from)
            .queryParam("to", to)
            .encode()
            .build()
            .toUriString();
    return backendApiClient.get(uri, BankBalanceSeriesDto.class, id);
  }

  /**
   * Raises a booking request against an overseen org unit's account (REQ-BANK-022).
   *
   * @param request the new request
   * @return the created request, or {@code null} when the backend sent no body
   */
  @Nullable
  public BankBookingRequestDto createOrgUnitRequest(@NotNull CreateBankBookingRequest request) {
    return backendApiClient.post(
        "/api/v1/org-units/bank/requests", request, BankBookingRequestDto.class);
  }

  /**
   * Cancels the caller's own pending booking request (REQ-BANK-022).
   *
   * @param id the request
   * @param request the echoed version
   * @return the cancelled request, or {@code null} when the backend sent no body
   */
  @Nullable
  public BankBookingRequestDto cancelOrgUnitRequest(
      @NotNull UUID id, @NotNull CancelBankBookingRequest request) {
    return backendApiClient.post(
        "/api/v1/org-units/bank/requests/{id}/cancel", request, BankBookingRequestDto.class, id);
  }

  /**
   * Corrects the caller's own still-pending, unapproved booking request (REQ-BANK-056).
   *
   * @param id the request
   * @param request the corrected values with the echoed version
   * @return the updated request, or {@code null} when the backend sent no body
   */
  @Nullable
  public BankBookingRequestDto updateOrgUnitRequest(
      @NotNull UUID id, @NotNull UpdateBankBookingRequest request) {
    return backendApiClient.put(
        "/api/v1/org-units/bank/requests/{id}", request, BankBookingRequestDto.class, id);
  }

  /**
   * Sets or clears an account's balance target through the org-unit view (REQ-BANK-036).
   *
   * @param id the account
   * @param request the target and the echoed version
   * @return the refreshed settings, or {@code null} when the backend sent no body
   */
  @Nullable
  public OrgUnitBankAccountSettingsDto setOrgUnitBalanceTarget(
      @NotNull UUID id, @NotNull OrgUnitBalanceTargetRequest request) {
    return backendApiClient.put(
        "/api/v1/org-units/bank/accounts/{id}/balance-target",
        request,
        OrgUnitBankAccountSettingsDto.class,
        id);
  }

  /**
   * Grants a role bucket view access to an account (REQ-BANK-035).
   *
   * @param id the account
   * @param roleCode the role bucket, already checked to be a constant name
   * @param body the browser's body, which the backend does not bind
   * @return the refreshed settings, or {@code null} when the backend sent no body
   */
  @Nullable
  public OrgUnitBankAccountSettingsDto addRoleVisibility(
      @NotNull UUID id, @NotNull String roleCode, @NotNull Map<String, Object> body) {
    return backendApiClient.post(
        "/api/v1/org-units/bank/accounts/{id}/visibility/role/{roleCode}",
        body,
        OrgUnitBankAccountSettingsDto.class,
        id,
        roleCode);
  }

  /**
   * Revokes a role bucket's view access to an account (REQ-BANK-035).
   *
   * @param id the account
   * @param roleCode the role bucket, already checked to be a constant name
   * @return the refreshed settings, or {@code null} when the backend sent no body
   */
  @Nullable
  public OrgUnitBankAccountSettingsDto removeRoleVisibility(
      @NotNull UUID id, @NotNull String roleCode) {
    return backendApiClient.delete(
        "/api/v1/org-units/bank/accounts/{id}/visibility/role/{roleCode}",
        OrgUnitBankAccountSettingsDto.class,
        id,
        roleCode);
  }

  /**
   * Toggles the all-members view grant of an account (REQ-BANK-035).
   *
   * @param id the account
   * @param enabled whether all members may view the account
   * @param body the browser's body, which the backend does not bind
   * @return the refreshed settings, or {@code null} when the backend sent no body
   */
  @Nullable
  public OrgUnitBankAccountSettingsDto setAllMembersVisibility(
      @NotNull UUID id, boolean enabled, @NotNull Map<String, Object> body) {
    return backendApiClient.put(
        "/api/v1/org-units/bank/accounts/{id}/visibility/all-members/{enabled}",
        body,
        OrgUnitBankAccountSettingsDto.class,
        id,
        enabled);
  }

  /**
   * Toggles the area-cascade view grant of a Bereichskonto (REQ-BANK-048).
   *
   * @param id the account
   * @param enabled whether the whole area cascade may view the account
   * @param body the browser's body, which the backend does not bind
   * @return the refreshed settings, or {@code null} when the backend sent no body
   */
  @Nullable
  public OrgUnitBankAccountSettingsDto setAreaMembersVisibility(
      @NotNull UUID id, boolean enabled, @NotNull Map<String, Object> body) {
    return backendApiClient.put(
        "/api/v1/org-units/bank/accounts/{id}/visibility/area-members/{enabled}",
        body,
        OrgUnitBankAccountSettingsDto.class,
        id,
        enabled);
  }

  /**
   * Grants an individual user view access to an account (REQ-BANK-035).
   *
   * @param id the account
   * @param userId the user to grant
   * @param body the browser's body, which the backend does not bind
   * @return the refreshed settings, or {@code null} when the backend sent no body
   */
  @Nullable
  public OrgUnitBankAccountSettingsDto addUserVisibility(
      @NotNull UUID id, @NotNull UUID userId, @NotNull Map<String, Object> body) {
    return backendApiClient.post(
        "/api/v1/org-units/bank/accounts/{id}/visibility/user/{userId}",
        body,
        OrgUnitBankAccountSettingsDto.class,
        id,
        userId);
  }

  /**
   * Revokes an individual user's view access to an account (REQ-BANK-035).
   *
   * @param id the account
   * @param userId the user to revoke
   * @return the refreshed settings, or {@code null} when the backend sent no body
   */
  @Nullable
  public OrgUnitBankAccountSettingsDto removeUserVisibility(
      @NotNull UUID id, @NotNull UUID userId) {
    return backendApiClient.delete(
        "/api/v1/org-units/bank/accounts/{id}/visibility/user/{userId}",
        OrgUnitBankAccountSettingsDto.class,
        id,
        userId);
  }

  /**
   * Sets a role bucket's approval limit on an account (REQ-BANK-041).
   *
   * @param id the account
   * @param roleCode the role bucket, already checked to be a constant name
   * @param request the limit
   * @return the refreshed settings, or {@code null} when the backend sent no body
   */
  @Nullable
  public OrgUnitBankAccountSettingsDto setRoleApprovalLimit(
      @NotNull UUID id, @NotNull String roleCode, @NotNull SetBankApprovalLimitRequest request) {
    return backendApiClient.put(
        "/api/v1/org-units/bank/accounts/{id}/approval-limit/role/{roleCode}",
        request,
        OrgUnitBankAccountSettingsDto.class,
        id,
        roleCode);
  }

  /**
   * Clears a role bucket's approval limit on an account (REQ-BANK-041).
   *
   * @param id the account
   * @param roleCode the role bucket, already checked to be a constant name
   * @return the refreshed settings, or {@code null} when the backend sent no body
   */
  @Nullable
  public OrgUnitBankAccountSettingsDto clearRoleApprovalLimit(
      @NotNull UUID id, @NotNull String roleCode) {
    return backendApiClient.delete(
        "/api/v1/org-units/bank/accounts/{id}/approval-limit/role/{roleCode}",
        OrgUnitBankAccountSettingsDto.class,
        id,
        roleCode);
  }

  /**
   * Sets the all-members approval limit on an account (REQ-BANK-041).
   *
   * @param id the account
   * @param request the limit
   * @return the refreshed settings, or {@code null} when the backend sent no body
   */
  @Nullable
  public OrgUnitBankAccountSettingsDto setAllMembersApprovalLimit(
      @NotNull UUID id, @NotNull SetBankApprovalLimitRequest request) {
    return backendApiClient.put(
        "/api/v1/org-units/bank/accounts/{id}/approval-limit/all-members",
        request,
        OrgUnitBankAccountSettingsDto.class,
        id);
  }

  /**
   * Clears the all-members approval limit on an account (REQ-BANK-041).
   *
   * @param id the account
   * @return the refreshed settings, or {@code null} when the backend sent no body
   */
  @Nullable
  public OrgUnitBankAccountSettingsDto clearAllMembersApprovalLimit(@NotNull UUID id) {
    return backendApiClient.delete(
        "/api/v1/org-units/bank/accounts/{id}/approval-limit/all-members",
        OrgUnitBankAccountSettingsDto.class,
        id);
  }

  /**
   * Sets the area-cascade approval limit on a Bereichskonto (REQ-BANK-048).
   *
   * @param id the account
   * @param request the limit
   * @return the refreshed settings, or {@code null} when the backend sent no body
   */
  @Nullable
  public OrgUnitBankAccountSettingsDto setAreaMembersApprovalLimit(
      @NotNull UUID id, @NotNull SetBankApprovalLimitRequest request) {
    return backendApiClient.put(
        "/api/v1/org-units/bank/accounts/{id}/approval-limit/area-members",
        request,
        OrgUnitBankAccountSettingsDto.class,
        id);
  }

  /**
   * Clears the area-cascade approval limit on a Bereichskonto (REQ-BANK-048).
   *
   * @param id the account
   * @return the refreshed settings, or {@code null} when the backend sent no body
   */
  @Nullable
  public OrgUnitBankAccountSettingsDto clearAreaMembersApprovalLimit(@NotNull UUID id) {
    return backendApiClient.delete(
        "/api/v1/org-units/bank/accounts/{id}/approval-limit/area-members",
        OrgUnitBankAccountSettingsDto.class,
        id);
  }

  /**
   * Sets an individual user's approval limit on an account (REQ-BANK-041).
   *
   * @param id the account
   * @param userId the user the limit addresses
   * @param request the limit
   * @return the refreshed settings, or {@code null} when the backend sent no body
   */
  @Nullable
  public OrgUnitBankAccountSettingsDto setUserApprovalLimit(
      @NotNull UUID id, @NotNull UUID userId, @NotNull SetBankApprovalLimitRequest request) {
    return backendApiClient.put(
        "/api/v1/org-units/bank/accounts/{id}/approval-limit/user/{userId}",
        request,
        OrgUnitBankAccountSettingsDto.class,
        id,
        userId);
  }

  /**
   * Clears an individual user's approval limit on an account (REQ-BANK-041).
   *
   * @param id the account
   * @param userId the user whose limit to clear
   * @return the refreshed settings, or {@code null} when the backend sent no body
   */
  @Nullable
  public OrgUnitBankAccountSettingsDto clearUserApprovalLimit(
      @NotNull UUID id, @NotNull UUID userId) {
    return backendApiClient.delete(
        "/api/v1/org-units/bank/accounts/{id}/approval-limit/user/{userId}",
        OrgUnitBankAccountSettingsDto.class,
        id,
        userId);
  }

  /**
   * Grants the responsible holder's in-app approval of an over-limit request (REQ-BANK-041).
   *
   * @param id the request
   * @param body the browser's body, which the backend does not bind
   * @return the updated request, or {@code null} when the backend sent no body
   */
  @Nullable
  public BankBookingRequestDto grantOwnerApproval(
      @NotNull UUID id, @NotNull Map<String, Object> body) {
    return backendApiClient.post(
        "/api/v1/org-units/bank/requests/{id}/owner-approval",
        body,
        BankBookingRequestDto.class,
        id);
  }

  /**
   * Revokes a previously granted in-app approval (REQ-BANK-041).
   *
   * @param id the request
   * @return the updated request, or {@code null} when the backend sent no body
   */
  @Nullable
  public BankBookingRequestDto revokeOwnerApproval(@NotNull UUID id) {
    return backendApiClient.delete(
        "/api/v1/org-units/bank/requests/{id}/owner-approval", BankBookingRequestDto.class, id);
  }

  /**
   * Downloads the holder-redacted statement PDF of an account the caller may view (REQ-BANK-038).
   *
   * @param id the account id
   * @param from period start
   * @param to period end
   * @param userTimeZone the caller's IANA time zone, forwarded when not blank
   * @return the PDF bytes, or {@code null} when the backend sent no body
   */
  public byte @Nullable [] orgUnitAccountStatement(
      @NotNull UUID id, @NotNull Instant from, @NotNull Instant to, @Nullable String userTimeZone) {
    String uri =
        UriComponentsBuilder.fromPath("/api/v1/org-units/bank/accounts/{id}/statement")
            .queryParam("from", from)
            .queryParam("to", to)
            .encode()
            .build()
            .toUriString();
    return backendApiClient.execute(
        HttpMethod.GET,
        uri,
        webClient ->
            webClient
                .get()
                .uri(uri, id)
                .headers(
                    h -> {
                      if (userTimeZone != null && !userTimeZone.isBlank()) {
                        h.set("X-User-Time-Zone", userTimeZone);
                      }
                    }),
        spec -> spec.bodyToMono(byte[].class));
  }

  /**
   * Fetches one backend PDF, forwarding the caller's time zone when present.
   *
   * @param uri the backend URI template with its query
   * @param uriVariables the values expanded into the template, in order
   * @param userTimeZone the zone header to forward, or {@code null}
   * @return the PDF bytes, or {@code null} when the backend sent no body
   */
  private byte @Nullable [] pdf(
      @NotNull String uri, @NotNull Object[] uriVariables, @Nullable String userTimeZone) {
    return backendApiClient.execute(
        HttpMethod.GET,
        uri,
        webClient ->
            webClient
                .get()
                .uri(uri, uriVariables)
                .headers(
                    h -> {
                      if (userTimeZone != null && !userTimeZone.isBlank()) {
                        h.set("X-User-Time-Zone", userTimeZone);
                      }
                    }),
        spec -> spec.bodyToMono(byte[].class));
  }
}
