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
import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountRefDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankBalanceSeriesDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankBookingDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankBookingRequestDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankDashboardDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankGrantDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankHolderBookingDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankHolderDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankTransferFeeRateDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankWipeResetResultDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitBankAccountDetailDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitBankAccountSettingsDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitBankBalanceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
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
 * <p>The write relays forward the browser's JSON payload unchanged and return the backend's JSON
 * answer as a raw map, so the browser contract stays the backend's own.
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

  private static final ParameterizedTypeReference<PageResponse<Map<String, Object>>>
      ACCOUNT_SEARCH_PAGE = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<OrgUnitBankBalanceDto>> BALANCE_LIST =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<BankBookingRequestDto>>
      BOOKING_REQUEST_LIST = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<BankAccountRefDto>> ACCOUNT_REF_LIST =
      new ParameterizedTypeReference<>() {};

  /** The template variables of a relay whose path has none. */
  private static final Object[] NO_VARIABLES = {};

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
   * @return the page of raw account records, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<Map<String, Object>> searchActiveAccounts(@NotNull String query, int size) {
    String uri =
        UriComponentsBuilder.fromPath("/api/v1/bank/accounts")
            .queryParam("status", "ACTIVE")
            .queryParam("size", size)
            .queryParam("sort", "name,asc")
            .toUriString();
    return backendApiClient.get(uri + "&query={query}", ACCOUNT_SEARCH_PAGE, query);
  }

  /**
   * Books a deposit.
   *
   * @param body the browser's booking payload
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  public Map<String, Object> deposit(@NotNull Map<String, Object> body) {
    return postMap("/api/v1/bank/deposits", body, NO_VARIABLES);
  }

  /**
   * Books a withdrawal.
   *
   * @param body the browser's booking payload
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  public Map<String, Object> withdrawal(@NotNull Map<String, Object> body) {
    return postMap("/api/v1/bank/withdrawals", body, NO_VARIABLES);
  }

  /**
   * Books an account-to-account transfer.
   *
   * @param body the browser's booking payload
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  public Map<String, Object> transfer(@NotNull Map<String, Object> body) {
    return postMap("/api/v1/bank/transfers", body, NO_VARIABLES);
  }

  /**
   * Books a holder-to-holder custody move (REQ-BANK-031).
   *
   * @param body the browser's Umbuchung payload
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  public Map<String, Object> holderTransfer(@NotNull Map<String, Object> body) {
    return postMap("/api/v1/bank/holders/transfer", body, NO_VARIABLES);
  }

  /**
   * Reverses one transaction.
   *
   * @param id the transaction
   * @param body the browser's correction-note payload
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  public Map<String, Object> reverseTransaction(
      @NotNull UUID id, @NotNull Map<String, Object> body) {
    return postMap("/api/v1/bank/transactions/{id}/reversal", body, id);
  }

  /**
   * Confirms a pending booking request (REQ-BANK-023).
   *
   * @param id the request
   * @param body the browser's confirm payload
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  public Map<String, Object> confirmRequest(@NotNull UUID id, @NotNull Map<String, Object> body) {
    return postMap("/api/v1/bank/requests/{id}/confirm", body, id);
  }

  /**
   * Rejects a pending booking request (REQ-BANK-023).
   *
   * @param id the request
   * @param body the browser's reject payload
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  public Map<String, Object> rejectRequest(@NotNull UUID id, @NotNull Map<String, Object> body) {
    return postMap("/api/v1/bank/requests/{id}/reject", body, id);
  }

  /**
   * Creates an account (REQ-BANK-030).
   *
   * @param body the browser's creation payload
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  public Map<String, Object> createAccount(@NotNull Map<String, Object> body) {
    return postMap("/api/v1/bank/accounts", body, NO_VARIABLES);
  }

  /**
   * Renames an account.
   *
   * @param id the account
   * @param body the browser's rename payload
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  public Map<String, Object> renameAccount(@NotNull UUID id, @NotNull Map<String, Object> body) {
    return patchMap("/api/v1/bank/accounts/{id}", body, id);
  }

  /**
   * Sets or clears an account's balance target (REQ-BANK-036).
   *
   * @param id the account
   * @param body the browser's target payload
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  public Map<String, Object> setBalanceTarget(@NotNull UUID id, @NotNull Map<String, Object> body) {
    return patchMap("/api/v1/bank/accounts/{id}/balance-target", body, id);
  }

  /**
   * Sets or clears the KRT account's approval thresholds (REQ-BANK-047).
   *
   * @param id the KRT account
   * @param body the browser's thresholds payload
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  public Map<String, Object> setApprovalTiers(@NotNull UUID id, @NotNull Map<String, Object> body) {
    return patchMap("/api/v1/bank/accounts/{id}/approval-tiers", body, id);
  }

  /**
   * Closes an account.
   *
   * @param id the account
   * @param body the browser's lifecycle payload
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  public Map<String, Object> closeAccount(@NotNull UUID id, @NotNull Map<String, Object> body) {
    return postMap("/api/v1/bank/accounts/{id}/close", body, id);
  }

  /**
   * Reopens an account.
   *
   * @param id the account
   * @param body the browser's lifecycle payload
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  public Map<String, Object> reopenAccount(@NotNull UUID id, @NotNull Map<String, Object> body) {
    return postMap("/api/v1/bank/accounts/{id}/reopen", body, id);
  }

  /**
   * Registers a holder.
   *
   * @param body the browser's registration payload
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  public Map<String, Object> registerHolder(@NotNull Map<String, Object> body) {
    return postMap("/api/v1/bank/holders", body, NO_VARIABLES);
  }

  /**
   * Toggles a holder's activity.
   *
   * @param id the holder row
   * @param body the browser's toggle payload
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  public Map<String, Object> updateHolder(@NotNull UUID id, @NotNull Map<String, Object> body) {
    return patchMap("/api/v1/bank/holders/{id}", body, id);
  }

  /**
   * Creates a grant.
   *
   * @param body the browser's creation payload
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  public Map<String, Object> createGrant(@NotNull Map<String, Object> body) {
    return postMap("/api/v1/bank/grants", body, NO_VARIABLES);
  }

  /**
   * Changes a grant's capability flags.
   *
   * @param userId the grantee half of the composite key
   * @param accountId the account half of the composite key
   * @param body the browser's flag payload
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  public Map<String, Object> updateGrant(
      @NotNull UUID userId, @NotNull UUID accountId, @NotNull Map<String, Object> body) {
    return patchMap("/api/v1/bank/grants/{userId}/{accountId}", body, userId, accountId);
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
   * @param body the browser's create payload
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  public Map<String, Object> createOrgUnitRequest(@NotNull Map<String, Object> body) {
    return postMap("/api/v1/org-units/bank/requests", body, NO_VARIABLES);
  }

  /**
   * Cancels the caller's own pending booking request (REQ-BANK-022).
   *
   * @param id the request
   * @param body the browser's lifecycle payload
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  public Map<String, Object> cancelOrgUnitRequest(
      @NotNull UUID id, @NotNull Map<String, Object> body) {
    return postMap("/api/v1/org-units/bank/requests/{id}/cancel", body, id);
  }

  /**
   * Corrects the caller's own still-pending, unapproved booking request (REQ-BANK-056).
   *
   * @param id the request
   * @param body the browser's corrected values with the echoed version
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  public Map<String, Object> updateOrgUnitRequest(
      @NotNull UUID id, @NotNull Map<String, Object> body) {
    return putMap("/api/v1/org-units/bank/requests/{id}", body, id);
  }

  /**
   * Sets or clears an account's balance target through the org-unit view (REQ-BANK-036).
   *
   * @param id the account
   * @param body the browser's target payload
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  public Map<String, Object> setOrgUnitBalanceTarget(
      @NotNull UUID id, @NotNull Map<String, Object> body) {
    return putMap("/api/v1/org-units/bank/accounts/{id}/balance-target", body, id);
  }

  /**
   * Grants a role bucket view access to an account (REQ-BANK-035).
   *
   * @param id the account
   * @param roleCode the role bucket, already checked to be a constant name
   * @param body the browser's payload, empty for this path-only write
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  public Map<String, Object> addRoleVisibility(
      @NotNull UUID id, @NotNull String roleCode, @NotNull Map<String, Object> body) {
    return postMap(
        "/api/v1/org-units/bank/accounts/{id}/visibility/role/{roleCode}", body, id, roleCode);
  }

  /**
   * Revokes a role bucket's view access to an account (REQ-BANK-035).
   *
   * @param id the account
   * @param roleCode the role bucket, already checked to be a constant name
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  public Map<String, Object> removeRoleVisibility(@NotNull UUID id, @NotNull String roleCode) {
    return deleteMap(
        "/api/v1/org-units/bank/accounts/{id}/visibility/role/{roleCode}", id, roleCode);
  }

  /**
   * Toggles the all-members view grant of an account (REQ-BANK-035).
   *
   * @param id the account
   * @param enabled whether all members may view the account
   * @param body the browser's payload, empty for this path-only write
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  public Map<String, Object> setAllMembersVisibility(
      @NotNull UUID id, boolean enabled, @NotNull Map<String, Object> body) {
    return putMap(
        "/api/v1/org-units/bank/accounts/{id}/visibility/all-members/{enabled}", body, id, enabled);
  }

  /**
   * Toggles the area-cascade view grant of a Bereichskonto (REQ-BANK-048).
   *
   * @param id the account
   * @param enabled whether the whole area cascade may view the account
   * @param body the browser's payload, empty for this path-only write
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  public Map<String, Object> setAreaMembersVisibility(
      @NotNull UUID id, boolean enabled, @NotNull Map<String, Object> body) {
    return putMap(
        "/api/v1/org-units/bank/accounts/{id}/visibility/area-members/{enabled}",
        body,
        id,
        enabled);
  }

  /**
   * Grants an individual user view access to an account (REQ-BANK-035).
   *
   * @param id the account
   * @param userId the user to grant
   * @param body the browser's payload, empty for this path-only write
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  public Map<String, Object> addUserVisibility(
      @NotNull UUID id, @NotNull UUID userId, @NotNull Map<String, Object> body) {
    return postMap(
        "/api/v1/org-units/bank/accounts/{id}/visibility/user/{userId}", body, id, userId);
  }

  /**
   * Revokes an individual user's view access to an account (REQ-BANK-035).
   *
   * @param id the account
   * @param userId the user to revoke
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  public Map<String, Object> removeUserVisibility(@NotNull UUID id, @NotNull UUID userId) {
    return deleteMap("/api/v1/org-units/bank/accounts/{id}/visibility/user/{userId}", id, userId);
  }

  /**
   * Sets a role bucket's approval limit on an account (REQ-BANK-041).
   *
   * @param id the account
   * @param roleCode the role bucket, already checked to be a constant name
   * @param body the browser's limit payload
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  public Map<String, Object> setRoleApprovalLimit(
      @NotNull UUID id, @NotNull String roleCode, @NotNull Map<String, Object> body) {
    return putMap(
        "/api/v1/org-units/bank/accounts/{id}/approval-limit/role/{roleCode}", body, id, roleCode);
  }

  /**
   * Clears a role bucket's approval limit on an account (REQ-BANK-041).
   *
   * @param id the account
   * @param roleCode the role bucket, already checked to be a constant name
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  public Map<String, Object> clearRoleApprovalLimit(@NotNull UUID id, @NotNull String roleCode) {
    return deleteMap(
        "/api/v1/org-units/bank/accounts/{id}/approval-limit/role/{roleCode}", id, roleCode);
  }

  /**
   * Sets the all-members approval limit on an account (REQ-BANK-041).
   *
   * @param id the account
   * @param body the browser's limit payload
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  public Map<String, Object> setAllMembersApprovalLimit(
      @NotNull UUID id, @NotNull Map<String, Object> body) {
    return putMap("/api/v1/org-units/bank/accounts/{id}/approval-limit/all-members", body, id);
  }

  /**
   * Clears the all-members approval limit on an account (REQ-BANK-041).
   *
   * @param id the account
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  public Map<String, Object> clearAllMembersApprovalLimit(@NotNull UUID id) {
    return deleteMap("/api/v1/org-units/bank/accounts/{id}/approval-limit/all-members", id);
  }

  /**
   * Sets the area-cascade approval limit on a Bereichskonto (REQ-BANK-048).
   *
   * @param id the account
   * @param body the browser's limit payload
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  public Map<String, Object> setAreaMembersApprovalLimit(
      @NotNull UUID id, @NotNull Map<String, Object> body) {
    return putMap("/api/v1/org-units/bank/accounts/{id}/approval-limit/area-members", body, id);
  }

  /**
   * Clears the area-cascade approval limit on a Bereichskonto (REQ-BANK-048).
   *
   * @param id the account
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  public Map<String, Object> clearAreaMembersApprovalLimit(@NotNull UUID id) {
    return deleteMap("/api/v1/org-units/bank/accounts/{id}/approval-limit/area-members", id);
  }

  /**
   * Sets an individual user's approval limit on an account (REQ-BANK-041).
   *
   * @param id the account
   * @param userId the user the limit addresses
   * @param body the browser's limit payload
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  public Map<String, Object> setUserApprovalLimit(
      @NotNull UUID id, @NotNull UUID userId, @NotNull Map<String, Object> body) {
    return putMap(
        "/api/v1/org-units/bank/accounts/{id}/approval-limit/user/{userId}", body, id, userId);
  }

  /**
   * Clears an individual user's approval limit on an account (REQ-BANK-041).
   *
   * @param id the account
   * @param userId the user whose limit to clear
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  public Map<String, Object> clearUserApprovalLimit(@NotNull UUID id, @NotNull UUID userId) {
    return deleteMap(
        "/api/v1/org-units/bank/accounts/{id}/approval-limit/user/{userId}", id, userId);
  }

  /**
   * Grants the responsible holder's in-app approval of an over-limit request (REQ-BANK-041).
   *
   * @param id the request
   * @param body the browser's payload, empty for this path-only write
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  public Map<String, Object> grantOwnerApproval(
      @NotNull UUID id, @NotNull Map<String, Object> body) {
    return postMap("/api/v1/org-units/bank/requests/{id}/owner-approval", body, id);
  }

  /**
   * Revokes a previously granted in-app approval (REQ-BANK-041).
   *
   * @param id the request
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  public Map<String, Object> revokeOwnerApproval(@NotNull UUID id) {
    return deleteMap("/api/v1/org-units/bank/requests/{id}/owner-approval", id);
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

  /**
   * Posts a relayed payload and returns the backend's JSON answer as a raw map.
   *
   * @param uriTemplate the backend endpoint as a URI template
   * @param body the relayed payload
   * @param uriVariables the values expanded into the template, in order
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  @SuppressWarnings("unchecked")
  private Map<String, Object> postMap(
      @NotNull String uriTemplate,
      @NotNull Map<String, Object> body,
      @NotNull Object... uriVariables) {
    return backendApiClient.post(uriTemplate, body, Map.class, uriVariables);
  }

  /**
   * Patches with a relayed payload and returns the backend's JSON answer as a raw map.
   *
   * @param uriTemplate the backend endpoint as a URI template
   * @param body the relayed payload
   * @param uriVariables the values expanded into the template, in order
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  @SuppressWarnings("unchecked")
  private Map<String, Object> patchMap(
      @NotNull String uriTemplate,
      @NotNull Map<String, Object> body,
      @NotNull Object... uriVariables) {
    return backendApiClient.patch(uriTemplate, body, Map.class, uriVariables);
  }

  /**
   * Puts a relayed payload and returns the backend's JSON answer as a raw map.
   *
   * @param uriTemplate the backend endpoint as a URI template
   * @param body the relayed payload
   * @param uriVariables the values expanded into the template, in order
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  @SuppressWarnings("unchecked")
  private Map<String, Object> putMap(
      @NotNull String uriTemplate,
      @NotNull Map<String, Object> body,
      @NotNull Object... uriVariables) {
    return backendApiClient.put(uriTemplate, body, Map.class, uriVariables);
  }

  /**
   * Deletes and returns the backend's JSON answer as a raw map.
   *
   * @param uriTemplate the backend endpoint as a URI template
   * @param uriVariables the values expanded into the template, in order
   * @return the backend's answer, or {@code null} when it sent no body
   */
  @Nullable
  @SuppressWarnings("unchecked")
  private Map<String, Object> deleteMap(
      @NotNull String uriTemplate, @NotNull Object... uriVariables) {
    return backendApiClient.delete(uriTemplate, Map.class, uriVariables);
  }
}
