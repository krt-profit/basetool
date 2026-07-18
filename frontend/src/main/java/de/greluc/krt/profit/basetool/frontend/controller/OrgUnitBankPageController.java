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

import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountRefDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankBalanceSeriesDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankBookingDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankBookingRequestDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitBankAccountDetailDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitBankAccountSettingsDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitBankBalanceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.service.ParallelPageLoader;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Renders the org-unit bank view (epic #666 + REQ-BANK-034..038): the balance cards of every
 * account the caller may view, the read-only account drill-in (history + Halter-redacted
 * statement), the responsible-holder/OL settings (balance target + configurable visibility), and
 * the officer/lead booking-request flow. This is the org-unit-facing surface — reachable by any KRT
 * member (the cartel account is visible to all, REQ-BANK-037, and a member may have been granted
 * access to other accounts), deliberately <em>not</em> {@code BANK_EMPLOYEE}; the backend seam
 * decides the actual data per account. Booking/settings writes are AJAX swaps via {@code
 * /api/proxy/org-units/bank/**} (no reload, REQ-FE-005).
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class OrgUnitBankPageController {

  /** Any KRT member (or above) may reach the page; the backend seam scopes the data per account. */
  private static final String MEMBER_OR_ABOVE =
      "hasAnyRole('"
          + Roles.ADMIN
          + "','"
          + Roles.OFFICER
          + "','"
          + Roles.LOGISTICIAN
          + "','"
          + Roles.MISSION_MANAGER
          + "','"
          + Roles.KRT_MEMBER
          + "')";

  private final BackendApiClient backendApiClient;
  private final ParallelPageLoader parallelPageLoader;

  /** Response type for the caller's viewable balance cards ({@code /bank/balances}). */
  private static final ParameterizedTypeReference<List<OrgUnitBankBalanceDto>> BALANCE_LIST_TYPE =
      new ParameterizedTypeReference<>() {};

  /**
   * Response type for the booking-request lists ({@code /bank/requests} and its {@code /foreign}
   * variant), both of which return a bare list of requests.
   */
  private static final ParameterizedTypeReference<List<BankBookingRequestDto>>
      BOOKING_REQUEST_LIST_TYPE = new ParameterizedTypeReference<>() {};

  /** Response type for the active transfer/deposit target accounts ({@code /transfer-targets}). */
  private static final ParameterizedTypeReference<List<BankAccountRefDto>> ACCOUNT_REF_LIST_TYPE =
      new ParameterizedTypeReference<>() {};

  /** Response type for one paginated page of an account's booking history. */
  private static final ParameterizedTypeReference<PageResponse<BankBookingDto>>
      BANK_BOOKING_PAGE_TYPE = new ParameterizedTypeReference<>() {};

  /**
   * Renders the overview page (or one of its fragments for an in-place swap). The Konten list is
   * shown as a card grid (default) or a dense table, chosen per user; the choice rides on the
   * {@code layout} query parameter and is persisted client-side by {@code bank.js} (mirroring the
   * dashboard, REQ-BANK-016, but without the by-Bereich grouping). An {@code orgUnitBankAccounts}
   * fragment request re-renders just the switchable account list for the view-toggle swap
   * (REQ-FE-005); an {@code orgUnitBank} fragment request re-renders the tabs + both request
   * regions after a booking write.
   *
   * @param layout {@code table} for the dense table, otherwise the default card grid
   * @param fragment when {@code "orgUnitBankAccounts"} only the switchable account list is
   *     re-rendered (view-toggle swap); when {@code "orgUnitBank"} the tabs + own-request region
   * @param model Spring MVC model
   * @return the template, or its {@code orgUnitBankAccounts} / {@code orgUnitBank} fragment view
   */
  @GetMapping("/org-unit-bank")
  @PreAuthorize(MEMBER_OR_ABOVE)
  public String orgUnitBank(
      @RequestParam(required = false) String layout,
      @RequestParam(required = false) String fragment,
      Model model) {
    String effectiveLayout = "table".equals(layout) ? "table" : "card";
    model.addAttribute("layout", effectiveLayout);

    // Pure account-list sub-fragment swap (view toggle, REQ-BANK-021): only the balances + their
    // scaled sparklines are rendered, so the own/foreign request lists + transfer targets the full
    // page loads are skipped. Mirrors the account-detail method's pure sub-fragment short-circuits.
    if ("orgUnitBankAccounts".equals(fragment)) {
      List<OrgUnitBankBalanceDto> accounts =
          BankAccountOrder.byName(fetchBalances(), OrgUnitBankBalanceDto::accountName);
      model.addAttribute("balances", accounts);
      model.addAttribute("sparks", sparksByAccountId(accounts));
      return "org-unit-bank :: orgUnitBankAccounts";
    }

    // Balances, own-requests, foreign-requests and the all-active-account list are independent
    // reads; fetch them concurrently via ParallelPageLoader (which relays the bearer token +
    // active-OrgUnit header to the worker threads) instead of serial round-trips. Each helper
    // degrades to an empty list on failure, so allOf().join() never throws and the page renders
    // exactly as the serial version. The all-active list (transfer-targets) is now always fetched:
    // it is both the transfer destination picker (REQ-BANK-040) AND the deposit source picker
    // (REQ-BANK-042 — a deposit may target any active account, not just a viewable one).
    CompletableFuture<List<OrgUnitBankBalanceDto>> balancesFuture =
        parallelPageLoader.loadAsync(this::fetchBalances);
    CompletableFuture<List<BankBookingRequestDto>> ownRequestsFuture =
        parallelPageLoader.loadAsync(this::fetchOwnRequests);
    CompletableFuture<List<BankBookingRequestDto>> foreignRequestsFuture =
        parallelPageLoader.loadAsync(this::fetchForeignRequests);
    CompletableFuture<List<BankAccountRefDto>> transferTargetsFuture =
        parallelPageLoader.loadAsync(this::fetchTransferTargets);
    CompletableFuture.allOf(
            balancesFuture, ownRequestsFuture, foreignRequestsFuture, transferTargetsFuture)
        .join();

    List<OrgUnitBankBalanceDto> safeBalances =
        BankAccountOrder.byName(balancesFuture.join(), OrgUnitBankBalanceDto::accountName);
    model.addAttribute("balances", safeBalances);
    model.addAttribute("ownRequests", ownRequestsFuture.join());
    model.addAttribute("sparks", sparksByAccountId(safeBalances));
    // canRequest = the caller may view a REQUEST-CAPABLE account, i.e. may raise a
    // withdrawal/transfer request against it (REQ-BANK-039). Drives the withdrawal/transfer type
    // options and which source-account options carry a debit affordance + approval limit.
    boolean anyCanRequest = safeBalances.stream().anyMatch(OrgUnitBankBalanceDto::canRequest);
    model.addAttribute("anyCanRequest", anyCanRequest);
    // "Fremde Anträge" tab (REQ-BANK-041/-046): requests the caller may act on.
    List<BankBookingRequestDto> foreignRequests = foreignRequestsFuture.join();
    model.addAttribute("foreignRequests", foreignRequests);
    // Show the tab when the caller manages a REQUEST-CAPABLE account (canManageSettings on an
    // ORG_UNIT/AREA/CARTEL account == its responsible holder) OR when the band-routed list already
    // carries a request for them — the latter covers the KRT-account middle-band approver (the
    // Bankleitung, ADR-0109), who is not the account's responsible holder but sees its
    // BANK_MANAGEMENT-band requests (REQ-BANK-047). SPECIAL is never request-capable, so an
    // OL/management user who can only configure a Sonderkonto's visibility still does not see the
    // tab
    // unless a routed request exists.
    model.addAttribute(
        "hasResponsibleAccounts",
        safeBalances.stream().anyMatch(b -> b.canManageSettings() && b.canRequest())
            || !foreignRequests.isEmpty());
    // Deposit / transfer-destination source: every active account (REQ-BANK-040/-042), ordered A→Z
    // by name like every other account picker (BankAccountOrder).
    List<BankAccountRefDto> transferTargets =
        BankAccountOrder.byName(transferTargetsFuture.join(), BankAccountRefDto::name);
    model.addAttribute("requestTransferTargets", transferTargets);
    // The request CTA + modal are shown whenever a request is possible at all — since a deposit may
    // target any active account, that is whenever at least one active account exists
    // (REQ-BANK-042),
    // even for a caller who oversees / may view none.
    model.addAttribute("canRequestAny", !transferTargets.isEmpty());
    // Per-account debit affordance for the merged source picker: the ids of the caller's
    // request-capable (withdrawal/transfer) accounts and their resolved approval limits. The
    // template marks each active-account <option> with these so the JS can show all options for a
    // deposit but only the debitable ones for a withdrawal/transfer, carrying the limit warning.
    Set<UUID> debitableAccountIds =
        safeBalances.stream()
            .filter(OrgUnitBankBalanceDto::canRequest)
            .map(OrgUnitBankBalanceDto::accountId)
            .collect(Collectors.toSet());
    model.addAttribute("debitableAccountIds", debitableAccountIds);
    Map<UUID, BigDecimal> requestLimits = new LinkedHashMap<>();
    for (OrgUnitBankBalanceDto b : safeBalances) {
      if (b.canRequest() && b.approvalLimit() != null) {
        requestLimits.put(b.accountId(), b.approvalLimit());
      }
    }
    model.addAttribute("requestLimits", requestLimits);
    if ("orgUnitBank".equals(fragment)) {
      return "org-unit-bank :: orgUnitBank";
    }
    return "org-unit-bank";
  }

  /**
   * Fetches the caller's viewable balance cards, degrading to an empty list on backend failure.
   *
   * @return the balance cards, or an empty list when the backend call fails or returns nothing
   */
  private List<OrgUnitBankBalanceDto> fetchBalances() {
    try {
      List<OrgUnitBankBalanceDto> balances =
          backendApiClient.get("/api/v1/org-units/bank/balances", BALANCE_LIST_TYPE);
      if (balances != null) {
        return balances;
      }
    } catch (BackendServiceException e) {
      log.debug("Failed to fetch org-unit bank balances", e);
    } catch (RuntimeException e) {
      log.warn("Failed to fetch org-unit bank balances", e);
    }
    return List.of();
  }

  /**
   * Fetches the caller's own booking requests, degrading to an empty list on any backend failure.
   *
   * @return the caller's requests, or an empty list when the backend call fails or returns nothing
   */
  private List<BankBookingRequestDto> fetchOwnRequests() {
    try {
      List<BankBookingRequestDto> requests =
          backendApiClient.get("/api/v1/org-units/bank/requests", BOOKING_REQUEST_LIST_TYPE);
      if (requests != null) {
        return requests;
      }
    } catch (BackendServiceException e) {
      log.debug("Failed to fetch org-unit bank own requests", e);
    } catch (RuntimeException e) {
      log.warn("Failed to fetch org-unit bank own requests", e);
    }
    return List.of();
  }

  /**
   * Fetches the requests on the accounts the caller is responsible for, empty on backend failure.
   *
   * @return the foreign requests, or an empty list when the backend call fails or returns nothing
   */
  private List<BankBookingRequestDto> fetchForeignRequests() {
    try {
      List<BankBookingRequestDto> requests =
          backendApiClient.get(
              "/api/v1/org-units/bank/requests/foreign", BOOKING_REQUEST_LIST_TYPE);
      if (requests != null) {
        return requests;
      }
    } catch (BackendServiceException e) {
      log.debug("Failed to fetch org-unit bank foreign requests", e);
    } catch (RuntimeException e) {
      log.warn("Failed to fetch org-unit bank foreign requests", e);
    }
    return List.of();
  }

  /**
   * Fetches the active accounts offered as transfer-request destinations (REQ-BANK-040).
   *
   * @return the transfer targets, or an empty list when the backend call fails or returns nothing
   */
  private List<BankAccountRefDto> fetchTransferTargets() {
    try {
      List<BankAccountRefDto> targets =
          backendApiClient.get("/api/v1/org-units/bank/transfer-targets", ACCOUNT_REF_LIST_TYPE);
      if (targets != null) {
        return targets;
      }
    } catch (BackendServiceException e) {
      log.debug("Failed to fetch org-unit bank transfer targets", e);
    } catch (RuntimeException e) {
      log.warn("Failed to fetch org-unit bank transfer targets", e);
    }
    return List.of();
  }

  /**
   * Renders the read-only account drill-in (REQ-BANK-038) — the balance chart (REQ-BANK-049) +
   * history (Halter redacted) with its period filter (REQ-BANK-051) + the Kontoauszug export —
   * plus, for the responsible holder / OL, the settings region (balance target + configurable
   * visibility). The {@code orgUnitBankBookings} fragment re-renders the paginated/filtered history
   * in place, the {@code orgUnitBalanceChart} fragment re-renders the chart on a range change, and
   * the {@code orgUnitBankSettings} fragment re-renders the facts + settings region after a
   * target/visibility write.
   *
   * @param id the account id
   * @param page zero-based booking-history page index
   * @param size booking-history page size (10 / 50 / 100, default 50; REQ-BANK-051)
   * @param from optional booking-history period start ({@code yyyy-MM-dd}); default last 90 days
   * @param to optional booking-history period end ({@code yyyy-MM-dd}); default today
   * @param chartRange balance-chart range key ({@code 30d}/{@code 90d}/{@code 365d}/{@code all})
   * @param fragment {@code "orgUnitBankBookings"} (period/pager swap), {@code
   *     "orgUnitBalanceChart"} (range swap) or {@code "orgUnitBankSettings"} (settings swap), else
   *     the full page
   * @param model Spring MVC model
   * @return the template, or one of its fragment views
   */
  @GetMapping("/org-unit-bank/accounts/{id}")
  @PreAuthorize(MEMBER_OR_ABOVE)
  public String orgUnitBankAccount(
      @PathVariable UUID id,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String chartRange,
      @RequestParam(required = false) String fragment,
      Model model) {
    // Pure sub-fragment swaps skip the detail/settings fetch they do not render.
    if ("orgUnitBankBookings".equals(fragment)) {
      addBookingsModel(id, page, size, from, to, model);
      return "org-unit-bank-account-detail :: orgUnitBankBookings";
    }
    if ("orgUnitBalanceChart".equals(fragment)) {
      OrgUnitBankAccountDetailDto chartDetail = null;
      try {
        chartDetail =
            backendApiClient.get(
                "/api/v1/org-units/bank/accounts/" + id, OrgUnitBankAccountDetailDto.class);
      } catch (RuntimeException e) {
        log.warn("Error loading org-unit account {} for balance-chart fragment", id, e);
      }
      addChartModel(id, chartRange, chartDetail, model);
      return "org-unit-bank-account-detail :: orgUnitBalanceChart";
    }

    OrgUnitBankAccountDetailDto detail =
        backendApiClient.get(
            "/api/v1/org-units/bank/accounts/" + id, OrgUnitBankAccountDetailDto.class);
    model.addAttribute("detail", detail);

    boolean canManage =
        detail != null
            && (detail.canSetTarget()
                || detail.canConfigureVisibility()
                || detail.canConfigureApprovalLimits());
    OrgUnitBankAccountSettingsDto settings = null;
    if (canManage) {
      settings =
          backendApiClient.get(
              "/api/v1/org-units/bank/accounts/" + id + "/settings",
              OrgUnitBankAccountSettingsDto.class);
    }
    // The individual-visibility and individual-limit pickers are now server-side search comboboxes
    // (#1193, data-krt-combobox="remote-users"): the roster is fetched on demand via /users/search,
    // so no full user list is preloaded into the model here.
    model.addAttribute("settings", settings);

    if ("orgUnitBankSettings".equals(fragment)) {
      return "org-unit-bank-account-detail :: orgUnitBankSettings";
    }
    // Full page: also render the chart and the paged/filtered history.
    addBookingsModel(id, page, size, from, to, model);
    addChartModel(id, chartRange, detail, model);
    return "org-unit-bank-account-detail";
  }

  /**
   * Resolves the booking-history period (default last 90 days) and fills the bookings + period +
   * pagination model attributes shared by the full page and the {@code orgUnitBankBookings}
   * fragment (REQ-BANK-051). A backend failure degrades to an empty page so the table shows its
   * empty state.
   *
   * @param id the account id
   * @param page zero-based page (clamped to 0)
   * @param size requested page size, or {@code null} for the default
   * @param from optional period start ({@code yyyy-MM-dd})
   * @param to optional period end ({@code yyyy-MM-dd})
   * @param model the model to populate
   */
  private void addBookingsModel(
      UUID id, Integer page, Integer size, String from, String to, Model model) {
    int effectivePage = page == null || page < 0 ? 0 : page;
    int effectiveSize = size == null ? BankAccountDetailSupport.DEFAULT_PAGE_SIZE : size;
    BankAccountDetailSupport.HistoryPeriod period =
        BankAccountDetailSupport.resolveHistoryPeriod(from, to);
    PageResponse<BankBookingDto> bookings = null;
    try {
      bookings =
          backendApiClient.get(
              UriComponentsBuilder.fromPath(
                      "/api/v1/org-units/bank/accounts/" + id + "/transactions")
                  .queryParam("page", effectivePage)
                  .queryParam("size", effectiveSize)
                  .queryParam("from", period.fromInstant())
                  .queryParam("to", period.toInstant())
                  .toUriString(),
              BANK_BOOKING_PAGE_TYPE);
    } catch (RuntimeException e) {
      log.warn("Error loading org-unit bookings for account {}", id, e);
    }
    model.addAttribute("bookings", bookings);
    model.addAttribute("historyFrom", period.fromDate());
    model.addAttribute("historyTo", period.toDate());
    model.addAttribute("pageSizes", BankAccountDetailSupport.PAGE_SIZES);
    model.addAttribute("historyBaseUrl", "/org-unit-bank/accounts/" + id);
    model.addAttribute(
        "paginationBaseUrl",
        UriComponentsBuilder.fromPath("/org-unit-bank/accounts/" + id)
            .queryParam("from", period.fromDate())
            .queryParam("to", period.toDate())
            .toUriString());
  }

  /**
   * Resolves the balance-chart range (default 90 days), fetches its balance series and fills the
   * chart model attributes shared by the full page and the {@code orgUnitBalanceChart} fragment
   * (REQ-BANK-049). A backend failure degrades to an empty chart.
   *
   * @param id the account id
   * @param chartRange the requested range key
   * @param detail the account detail (for the {@code "all"} range's start), or {@code null}
   * @param model the model to populate
   */
  private void addChartModel(
      UUID id, String chartRange, OrgUnitBankAccountDetailDto detail, Model model) {
    String range = BankAccountDetailSupport.normalizeChartRange(chartRange);
    Instant now = Instant.now();
    Instant createdAt =
        detail != null && detail.detail() != null && detail.detail().account() != null
            ? detail.detail().account().createdAt()
            : null;
    Instant chartFrom = BankAccountDetailSupport.chartFromInstant(range, createdAt, now);
    BankBalanceSeriesDto series = null;
    try {
      series =
          backendApiClient.get(
              UriComponentsBuilder.fromPath(
                      "/api/v1/org-units/bank/accounts/" + id + "/balance-series")
                  .queryParam("from", chartFrom)
                  .queryParam("to", now)
                  .toUriString(),
              BankBalanceSeriesDto.class);
    } catch (RuntimeException e) {
      log.warn("Error loading org-unit balance series for account {}", id, e);
    }
    model.addAttribute(
        "chart",
        BankBalanceChart.of(
            series == null ? null : series.points(),
            series == null ? null : series.balanceTarget()));
    model.addAttribute("chartRange", range);
    model.addAttribute("chartRanges", BankAccountDetailSupport.CHART_RANGES);
    model.addAttribute("chartBaseUrl", "/org-unit-bank/accounts/" + id);
  }

  /**
   * Pre-scales each balance card's 30-day end-of-day series into its SVG sparkline polyline ({@link
   * BankSparkline}), keyed by account id.
   *
   * @param balances the visible balance cards (never {@code null})
   * @return account id to its scaled sparkline; same iteration order as {@code balances}
   */
  private static Map<UUID, BankSparkline.Spark> sparksByAccountId(
      List<OrgUnitBankBalanceDto> balances) {
    Map<UUID, BankSparkline.Spark> sparks = new LinkedHashMap<>();
    for (OrgUnitBankBalanceDto balance : balances) {
      sparks.put(balance.accountId(), BankSparkline.of(balance.sparkline()));
    }
    return sparks;
  }
}
