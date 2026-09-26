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

import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountDetailDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankBalanceSeriesDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankBookingDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankDashboardDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankHolderBookingDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankHolderDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankTransferFeeRateDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Renders the bank read surfaces: the dashboard {@code /bank} (REQ-BANK-016) and the account detail
 * {@code /bank/accounts/{id}}. The backend decides all visibility; this controller fetches and
 * fills the model.
 */
@Controller
@UsesLayoutModel
@RequiredArgsConstructor
@Slf4j
public class BankPageController {

  /** Response type for the bank-wide holder registry ({@code /api/v1/bank/holders}). */
  private static final ParameterizedTypeReference<List<BankHolderDto>> BANK_HOLDER_LIST =
      new ParameterizedTypeReference<>() {};

  /**
   * Response type for the all-kinds active org-unit option list ({@code
   * /api/v1/org-units/active-all-kinds}) feeding the external-counterparty picklist (REQ-BANK-044).
   */
  private static final ParameterizedTypeReference<List<OrgUnitMembershipOptionDto>>
      ORG_UNIT_OPTION_LIST = new ParameterizedTypeReference<>() {};

  /**
   * Response type for one page of an account's booking history ({@code
   * /api/v1/bank/accounts/{id}/transactions}).
   */
  private static final ParameterizedTypeReference<PageResponse<BankBookingDto>> BANK_BOOKING_PAGE =
      new ParameterizedTypeReference<>() {};

  /**
   * Response type for one page of a holder's custody history ({@code
   * /api/v1/bank/holders/{id}/transactions}).
   */
  private static final ParameterizedTypeReference<PageResponse<BankHolderBookingDto>>
      BANK_HOLDER_BOOKING_PAGE = new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Renders the bank dashboard (REQ-BANK-016) as card grid or table, alphabetical or grouped by
   * Bereich. The full page also carries the "Kontobewegung" modal ({@link #addMovementModalData}).
   *
   * @param layout {@code table} for the tabular view, otherwise the card grid
   * @param group {@code bereich} for the grouped view, otherwise alphabetical
   * @param fragment {@code "bankGrid"} to re-render only the grid
   * @param model Spring MVC model
   * @return the dashboard template, or its {@code bankGrid} fragment
   */
  @NotNull
  @GetMapping("/bank")
  @PreAuthorize("hasRole('" + Roles.BANK_EMPLOYEE + "')")
  public String dashboard(
      @RequestParam(required = false) String layout,
      @RequestParam(required = false) String group,
      @RequestParam(required = false) String fragment,
      Model model) {
    String effectiveLayout = "table".equals(layout) ? "table" : "card";
    String effectiveGroup = "bereich".equals(group) ? "bereich" : "alpha";
    BankDashboardDto dashboard =
        backendApiClient.get("/api/v1/bank/dashboard", BankDashboardDto.class);
    List<BankDashboardViewAssembler.BankDashboardCardView> cards =
        dashboard == null
            ? List.of()
            : BankAccountOrder.byName(
                dashboard.accounts().stream().map(BankDashboardViewAssembler::toCardView).toList(),
                card -> card.account().name());
    model.addAttribute("dashboard", dashboard);
    model.addAttribute("cards", cards);
    model.addAttribute("layout", effectiveLayout);
    model.addAttribute("group", effectiveGroup);
    model.addAttribute(
        "groups",
        "bereich".equals(effectiveGroup)
            ? BankDashboardViewAssembler.buildGroups(cards)
            : List.of());
    model.addAttribute("now", java.time.Instant.now().toString());
    if ("bankGrid".equals(fragment)) {
      return "bank-dashboard :: bankGrid";
    }
    boolean canBook =
        dashboard != null
            && dashboard.accounts() != null
            && dashboard.accounts().stream().anyMatch(a -> "ACTIVE".equals(a.status()));
    addMovementModalData(model, canBook);
    return "bank-dashboard";
  }

  /**
   * Adds the catalog data of the "Kontobewegung" direct-booking modal (REQ-BANK-023): holder
   * registry, org-unit picklist and transfer-fee rate. Account and user pickers are server-side
   * search comboboxes and need no preloaded roster.
   *
   * @param model the MVC model to populate
   * @param canBook whether the caller sees at least one active account; gates the modal
   */
  private void addMovementModalData(@NotNull Model model, boolean canBook) {
    model.addAttribute("canBook", canBook);
    List<BankHolderDto> holders = backendApiClient.get("/api/v1/bank/holders", BANK_HOLDER_LIST);
    model.addAttribute("holders", holders == null ? List.<BankHolderDto>of() : holders);
    model.addAttribute(
        "activeHolders",
        holders == null
            ? List.<BankHolderDto>of()
            : holders.stream().filter(BankHolderDto::active).toList());
    List<OrgUnitMembershipOptionDto> allOrgUnits =
        backendApiClient.get("/api/v1/org-units/active-all-kinds", ORG_UNIT_OPTION_LIST);
    model.addAttribute(
        "allOrgUnits", allOrgUnits == null ? List.<OrgUnitMembershipOptionDto>of() : allOrgUnits);
    model.addAttribute("transferFeeRate", fetchTransferFeeRate());
  }

  /**
   * Renders the account detail page: facts strip, balance chart, paged booking history and the
   * booking modals with the holder registry and transfer targets they need.
   *
   * @param id the account id
   * @param page zero-based booking page
   * @param size booking-history page size (10 / 50 / 100, default 50; REQ-BANK-051)
   * @param from optional period start ({@code yyyy-MM-dd}); default last 90 days
   * @param to optional period end ({@code yyyy-MM-dd}); default today
   * @param chartRange chart range ({@code 30d} / {@code 90d} / {@code 365d} / {@code all}); default
   *     {@code 90d} (REQ-BANK-049)
   * @param fragment {@code "bookings"}, {@code "balanceChart"} or {@code "accountBody"} to render
   *     only that fragment (REQ-FE-005); otherwise the full page
   * @param model Spring MVC model
   * @return the detail template or the requested fragment
   */
  @NotNull
  @GetMapping("/bank/accounts/{id}")
  @PreAuthorize("hasRole('" + Roles.BANK_EMPLOYEE + "')")
  public String accountDetail(
      @PathVariable @NotNull UUID id,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String chartRange,
      @RequestParam(required = false) String fragment,
      Model model) {
    if ("bookings".equals(fragment)) {
      return bookingsFragment(id, page, size, from, to, model);
    }
    if ("balanceChart".equals(fragment)) {
      return balanceChartFragment(id, chartRange, model);
    }
    BankAccountDetailDto detail =
        backendApiClient.get("/api/v1/bank/accounts/" + id, BankAccountDetailDto.class);
    addBookingsModel(id, page, size, from, to, model);
    addChartModel(id, chartRange, detail, model);
    List<BankHolderDto> holders = backendApiClient.get("/api/v1/bank/holders", BANK_HOLDER_LIST);

    model.addAttribute("detail", detail);
    model.addAttribute("holders", holders == null ? List.<BankHolderDto>of() : holders);
    model.addAttribute(
        "activeHolders",
        holders == null
            ? List.<BankHolderDto>of()
            : holders.stream().filter(BankHolderDto::active).toList());
    List<OrgUnitMembershipOptionDto> allOrgUnits =
        backendApiClient.get("/api/v1/org-units/active-all-kinds", ORG_UNIT_OPTION_LIST);
    model.addAttribute(
        "allOrgUnits", allOrgUnits == null ? List.<OrgUnitMembershipOptionDto>of() : allOrgUnits);
    model.addAttribute("transferFeeRate", fetchTransferFeeRate());
    if ("accountBody".equals(fragment)) {
      return "bank-account-detail :: accountBody";
    }
    return "bank-account-detail";
  }

  /**
   * Renders only the paged booking history for a period-filter or pager swap (REQ-FE-002); a
   * backend failure yields an empty page.
   *
   * @param id the account id
   * @param page zero-based booking page (clamped to 0)
   * @param size requested page size, or {@code null} for the default
   * @param from optional period start ({@code yyyy-MM-dd})
   * @param to optional period end ({@code yyyy-MM-dd})
   * @param model Spring MVC model to populate
   * @return the {@code bank-account-detail :: bookings} fragment view
   */
  @NotNull
  private String bookingsFragment(
      UUID id, Integer page, Integer size, String from, String to, Model model) {
    addBookingsModel(id, page, size, from, to, model);
    return "bank-account-detail :: bookings";
  }

  /**
   * Renders only the balance chart for a range swap (REQ-BANK-049); a failure yields an empty
   * chart.
   *
   * @param id the account id
   * @param chartRange the requested range key
   * @param model Spring MVC model to populate
   * @return the {@code bank-account-detail :: balanceChart} fragment view
   */
  @NotNull
  private String balanceChartFragment(UUID id, String chartRange, Model model) {
    BankAccountDetailDto detail = null;
    try {
      detail = backendApiClient.get("/api/v1/bank/accounts/" + id, BankAccountDetailDto.class);
    } catch (RuntimeException e) {
      log.warn("Error loading account {} for balance-chart fragment", id, e);
    }
    addChartModel(id, chartRange, detail, model);
    return "bank-account-detail :: balanceChart";
  }

  /**
   * Resolves the booking-history period, fetches the page and fills the bookings, period and
   * pagination attributes (REQ-BANK-051); a backend failure yields an empty page.
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
    BankAccountDetailSupport.HistoryPeriod period =
        BankAccountDetailSupport.resolveHistoryPeriod(from, to);
    PageResponse<BankBookingDto> bookings;
    try {
      bookings = fetchBookings(id, effectivePage, size, period.fromInstant(), period.toInstant());
    } catch (BackendServiceException e) {
      log.debug("Error loading bookings for account {}", id, e);
      bookings = null;
    } catch (RuntimeException e) {
      log.error("Error loading bookings for account {}", id, e);
      bookings = null;
    }
    model.addAttribute("bookings", bookings);
    model.addAttribute("historyFrom", period.fromDate());
    model.addAttribute("historyTo", period.toDate());
    model.addAttribute("pageSizes", BankAccountDetailSupport.PAGE_SIZES);
    model.addAttribute("historyBaseUrl", "/bank/accounts/" + id);
    model.addAttribute(
        "paginationBaseUrl",
        UriComponentsBuilder.fromPath("/bank/accounts/" + id)
            .queryParam("from", period.fromDate())
            .queryParam("to", period.toDate())
            .toUriString());
  }

  /**
   * Resolves the chart range, fetches the balance series and fills the chart attributes
   * (REQ-BANK-049); a backend failure yields an empty chart.
   *
   * @param id the account id
   * @param chartRange the requested range key
   * @param detail the account detail for the {@code "all"} range's start, or {@code null}
   * @param model the model to populate
   */
  private void addChartModel(UUID id, String chartRange, BankAccountDetailDto detail, Model model) {
    String range = BankAccountDetailSupport.normalizeChartRange(chartRange);
    Instant now = Instant.now();
    Instant createdAt =
        detail != null && detail.account() != null ? detail.account().createdAt() : null;
    Instant chartFrom = BankAccountDetailSupport.chartFromInstant(range, createdAt, now);
    BankBalanceSeriesDto series = null;
    try {
      series = fetchBalanceSeries(id, chartFrom, now);
    } catch (RuntimeException e) {
      log.warn("Error loading balance series for account {}", id, e);
    }
    model.addAttribute(
        "chart",
        BankBalanceChart.of(
            series == null ? null : series.points(),
            series == null ? null : series.balanceTarget()));
    model.addAttribute("chartRange", range);
    model.addAttribute("chartRanges", BankAccountDetailSupport.CHART_RANGES);
    model.addAttribute("chartBaseUrl", "/bank/accounts/" + id);
  }

  /**
   * Fetches one page of an account's booking history for a period.
   *
   * @param id the account id
   * @param page zero-based, already-clamped page index
   * @param size requested page size, or {@code null} for the backend default
   * @param from inclusive period start instant
   * @param to inclusive period end instant
   * @return the bookings page envelope
   */
  private PageResponse<BankBookingDto> fetchBookings(
      UUID id, int page, Integer size, Instant from, Instant to) {
    return backendApiClient.get(
        UriComponentsBuilder.fromPath("/api/v1/bank/accounts/" + id + "/transactions")
            .queryParam("page", page)
            .queryParam("size", size == null ? BankAccountDetailSupport.DEFAULT_PAGE_SIZE : size)
            .queryParam("from", from)
            .queryParam("to", to)
            .toUriString(),
        BANK_BOOKING_PAGE);
  }

  /**
   * Fetches an account's balance-over-time series for a period (REQ-BANK-049).
   *
   * @param id the account id
   * @param from inclusive period start instant
   * @param to inclusive period end instant
   * @return the balance series envelope
   */
  private BankBalanceSeriesDto fetchBalanceSeries(UUID id, Instant from, Instant to) {
    return backendApiClient.get(
        UriComponentsBuilder.fromPath("/api/v1/bank/accounts/" + id + "/balance-series")
            .queryParam("from", from)
            .queryParam("to", to)
            .toUriString(),
        BankBalanceSeriesDto.class);
  }

  /**
   * Renders the read-only holder detail page (REQ-BANK-032): header with custody total and the
   * paged custody history. The backend restricts employees to their own holder.
   *
   * @param id the holder id
   * @param page zero-based history page
   * @param fragment {@code "holderBookings"} to render only the history fragment (REQ-FE-002)
   * @param model Spring MVC model
   * @return the holder-detail template, or its {@code holderBookings} fragment
   */
  @NotNull
  @GetMapping("/bank/holders/{id}")
  @PreAuthorize("hasRole('" + Roles.BANK_EMPLOYEE + "')")
  public String holderDetail(
      @PathVariable @NotNull UUID id,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) String fragment,
      Model model) {
    if ("holderBookings".equals(fragment)) {
      return holderBookingsFragment(id, page, model);
    }
    int effectivePage = page == null || page < 0 ? 0 : page;
    BankHolderDto holder = backendApiClient.get("/api/v1/bank/holders/" + id, BankHolderDto.class);
    PageResponse<BankHolderBookingDto> bookings = fetchHolderBookings(id, effectivePage);
    model.addAttribute("holder", holder);
    model.addAttribute("bookings", bookings);
    model.addAttribute("paginationBaseUrl", "/bank/holders/" + id);
    return "bank-holder-detail";
  }

  /**
   * Renders only the paged custody history for a pager swap (REQ-FE-002); a backend failure yields
   * an empty page.
   *
   * @param id the holder id
   * @param page zero-based history page (clamped to 0)
   * @param model Spring MVC model populated with {@code bookings} and {@code paginationBaseUrl}
   * @return the {@code bank-holder-detail :: holderBookings} fragment view
   */
  @NotNull
  private String holderBookingsFragment(UUID id, Integer page, Model model) {
    int effectivePage = page == null || page < 0 ? 0 : page;
    PageResponse<BankHolderBookingDto> bookings;
    try {
      bookings = fetchHolderBookings(id, effectivePage);
    } catch (BackendServiceException e) {
      log.debug("Error loading holder bookings fragment for holder {}", id, e);
      bookings = null;
    } catch (Exception e) {
      log.error("Error loading holder bookings fragment for holder {}", id, e);
      bookings = null;
    }
    model.addAttribute("bookings", bookings);
    model.addAttribute("paginationBaseUrl", "/bank/holders/" + id);
    return "bank-holder-detail :: holderBookings";
  }

  /**
   * Fetches one page (size 20) of a holder's custody history.
   *
   * @param id the holder id
   * @param page zero-based, already-clamped page index
   * @return the history page envelope
   */
  private PageResponse<BankHolderBookingDto> fetchHolderBookings(UUID id, int page) {
    return backendApiClient.get(
        UriComponentsBuilder.fromPath("/api/v1/bank/holders/" + id + "/transactions")
            .queryParam("page", page)
            .queryParam("size", 20)
            .toUriString(),
        BANK_HOLDER_BOOKING_PAGE);
  }

  /**
   * Fetches the in-game transfer-fee rate for the booking-modal preview (REQ-BANK-033); failure or
   * absence yields {@link BigDecimal#ZERO}. The real fee is computed server-side.
   *
   * @return the fee rate as a fraction, never {@code null}
   */
  private BigDecimal fetchTransferFeeRate() {
    BankTransferFeeRateDto rate =
        backendApiClient.get("/api/v1/bank/transfer-fee-rate", BankTransferFeeRateDto.class);
    return rate == null || rate.rate() == null ? BigDecimal.ZERO : rate.rate();
  }
}
