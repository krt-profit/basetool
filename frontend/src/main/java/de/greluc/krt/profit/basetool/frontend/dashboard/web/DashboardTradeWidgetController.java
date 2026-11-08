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

package de.greluc.krt.profit.basetool.frontend.dashboard.web;

import de.greluc.krt.profit.basetool.frontend.bank.client.OrgUnitBankRequestsBackendClient;
import de.greluc.krt.profit.basetool.frontend.bank.model.BankBookingRequestDto;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardStatus;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardWidgetType;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.web.FragmentController;
import de.greluc.krt.profit.basetool.frontend.kernel.web.FragmentGetMapping;
import de.greluc.krt.profit.basetool.frontend.materialexchange.client.MaterialExchangeBackendClient;
import de.greluc.krt.profit.basetool.frontend.materialexchange.client.MaterialExchangeBackendClient.BoardFilter;
import de.greluc.krt.profit.basetool.frontend.materialexchange.model.MaterialExchangeOfferDto;
import de.greluc.krt.profit.basetool.frontend.materialexchange.model.MaterialRequestDto;
import de.greluc.krt.profit.basetool.frontend.supply.client.SupplyBackendClient;
import de.greluc.krt.profit.basetool.frontend.supply.model.NeedDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Serves the bodies of the trade widgets (REQ-UI-034): the open Materialbörse entries, the open
 * supply needs and the member's own bank requests. Each only reads what the backend lets the caller
 * read; a backend failure is answered by the central error mapping and shows that widget's error
 * state.
 */
@Controller
@FragmentController("/dashboard/widgets")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class DashboardTradeWidgetController {

  private static final String FRAGMENTS = "dashboard/fragments/trade-widgets :: ";

  private static final String BOARD_TAB = "alle";

  private static final String BOARD_SORT_NEWEST = "neu";

  private static final String NEED_OPEN = "OPEN";

  /** The request states "Offen" asks the backend for. */
  private static final List<String> REQUESTS_OPEN = List.of("PENDING");

  /** The request states "Entschieden" asks for: decided, rejected and withdrawn. */
  private static final List<String> REQUESTS_DECIDED =
      List.of("CONFIRMED", "REJECTED", "CANCELLED");

  private final MaterialExchangeBackendClient marketClient;

  private final SupplyBackendClient supplyClient;

  private final OrgUnitBankRequestsBackendClient bankRequestsClient;

  private final OwnOrgUnits ownOrgUnits;

  private final DashboardWidgetOptions options;

  /**
   * Renders the open Materialbörse entries, the newest first.
   *
   * @param model the Thymeleaf model
   * @param rows the rows shown
   * @param status the boards to read
   * @return the {@code market} fragment
   */
  @NotNull
  @FragmentGetMapping("/market")
  public String market(
      @NotNull Model model,
      @RequestParam(required = false) @Nullable Integer rows,
      @RequestParam(required = false) @Nullable DashboardStatus status) {
    DashboardStatus resolved = options.status(DashboardWidgetType.MARKET, status);
    int limit = options.rows(DashboardWidgetType.MARKET, rows);
    BoardFilter filter = new BoardFilter(BOARD_TAB, limit, null, null, BOARD_SORT_NEWEST, null);
    List<MarketWidgetRow> lines = new ArrayList<>();
    Locale locale = LocaleContextHolder.getLocale();
    long total = 0;
    if (resolved != DashboardStatus.REQUESTS) {
      PageResponse<MaterialExchangeOfferDto> offers = marketClient.offers(filter, false);
      if (offers != null) {
        offers.content().forEach(offer -> lines.add(MarketWidgetRow.of(offer, locale)));
        total += offers.totalElements();
      }
    }
    if (resolved != DashboardStatus.OFFERS) {
      PageResponse<MaterialRequestDto> wanted = marketClient.requests(filter);
      if (wanted != null) {
        wanted.content().forEach(request -> lines.add(MarketWidgetRow.of(request, locale)));
        total += wanted.totalElements();
      }
    }
    model.addAttribute("marketRows", MarketWidgetRow.newest(lines, limit));
    model.addAttribute("marketTotal", total);
    model.addAttribute("marketRequestsOnly", resolved == DashboardStatus.REQUESTS);
    return FRAGMENTS + "market";
  }

  /**
   * Renders the open supply needs, the newest first: those of the chosen or pinned unit, else the
   * union of the member's own units.
   *
   * @param model the Thymeleaf model
   * @param unit the chosen org unit, or {@code null} to follow the active context
   * @param rows the rows shown
   * @param status the coverage filter
   * @return the {@code supplyNeeds} fragment
   */
  @NotNull
  @FragmentGetMapping("/supply-needs")
  public String supplyNeeds(
      @NotNull Model model,
      @RequestParam(required = false) @Nullable UUID unit,
      @RequestParam(required = false) @Nullable Integer rows,
      @RequestParam(required = false) @Nullable DashboardStatus status) {
    DashboardStatus resolved = options.status(DashboardWidgetType.SUPPLY_NEEDS, status);
    UUID scope = unit != null ? unit : DashboardContext.of(model).activeUnitId();
    int limit = options.rows(DashboardWidgetType.SUPPLY_NEEDS, rows);
    String coverage = DashboardWidgetOptions.needCoverage(resolved);
    List<NeedDto> needs = new ArrayList<>();
    long total = 0;
    for (UUID read : scope != null ? Set.of(scope) : ownOrgUnits.ids()) {
      PageResponse<NeedDto> page = supplyClient.needs(read, NEED_OPEN, coverage, 0, limit);
      needs.addAll(PageResponse.contentOf(page));
      total += PageResponse.totalOf(page);
    }
    model.addAttribute(
        "needRows",
        needs.stream()
            .sorted(
                Comparator.comparing(
                    NeedDto::createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
            .limit(limit)
            .toList());
    model.addAttribute("needTotal", total);
    model.addAttribute("needShowsUnit", scope == null);
    model.addAttribute("needScope", scope);
    return FRAGMENTS + "supplyNeeds";
  }

  /**
   * Renders the member's own bank requests, the newest first.
   *
   * @param model the Thymeleaf model
   * @param rows the rows shown
   * @param status which requests to show
   * @return the {@code bankRequests} fragment
   */
  @NotNull
  @FragmentGetMapping("/bank-requests")
  public String bankRequests(
      @NotNull Model model,
      @RequestParam(required = false) @Nullable Integer rows,
      @RequestParam(required = false) @Nullable DashboardStatus status) {
    DashboardStatus resolved = options.status(DashboardWidgetType.BANK_REQUESTS, status);
    PageResponse<BankBookingRequestDto> own =
        bankRequestsClient.ownPage(
            requestStates(resolved), options.rows(DashboardWidgetType.BANK_REQUESTS, rows));
    model.addAttribute("bankRequestRows", own == null ? List.of() : own.content());
    model.addAttribute("bankRequestTotal", own == null ? 0 : own.totalElements());
    return FRAGMENTS + "bankRequests";
  }

  private static List<String> requestStates(@NotNull DashboardStatus status) {
    if (status == DashboardStatus.OPEN) {
      return REQUESTS_OPEN;
    }
    return status == DashboardStatus.DECIDED ? REQUESTS_DECIDED : List.of();
  }
}
