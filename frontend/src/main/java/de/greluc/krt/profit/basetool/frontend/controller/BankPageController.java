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

import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountDetailDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankBalanceSeriesDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankBookingDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankDashboardAccountDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankDashboardDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankHolderBookingDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankHolderDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankTransferFeeRateDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserReferenceDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Renders the bank area's read surfaces (epic #556): the dashboard ({@code /bank}, D1 card grid,
 * REQ-BANK-016) and the account detail ({@code /bank/accounts/{id}}, K1 two-column layout). The
 * backend decides every visibility/capability question — this controller only fetches, scales the
 * sparkline series into SVG polyline points and fills the model.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class BankPageController {

  /** Response type for the bank-wide holder registry ({@code /api/v1/bank/holders}). */
  private static final ParameterizedTypeReference<List<BankHolderDto>> BANK_HOLDER_LIST =
      new ParameterizedTypeReference<>() {};

  /**
   * Response type for the paged transfer-target account list ({@code
   * /api/v1/bank/accounts?size=500}) that fills the transfer-destination select.
   */
  private static final ParameterizedTypeReference<PageResponse<BankAccountDto>> BANK_ACCOUNT_PAGE =
      new ParameterizedTypeReference<>() {};

  /**
   * Response type for the deposit/withdrawal counterparty picker lookup ({@code
   * /api/v1/users/lookup}, REQ-BANK-044).
   */
  private static final ParameterizedTypeReference<List<UserReferenceDto>> USER_REFERENCE_LIST =
      new ParameterizedTypeReference<>() {};

  /**
   * Response type for the all-kinds active org-unit option list ({@code
   * /api/v1/org-units/active-all-kinds}) feeding the external-counterparty unit picklist
   * (REQ-BANK-044, #994).
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
   * One dashboard card with its pre-scaled sparkline polyline, ready for the template.
   *
   * @param account the backend card payload
   * @param sparklinePoints SVG polyline {@code points} attribute value scaled to the 96x26 viewBox;
   *     {@code null} when the series is empty
   * @param flat {@code true} when the 30-day series never changes (renders the muted flat line)
   */
  public record BankDashboardCardView(
      BankDashboardAccountDto account, String sparklinePoints, boolean flat) {}

  /**
   * One vertical group of the by-Bereich dashboard view (REQ-BANK-016): a coloured header plus the
   * cards that belong to it. Fixed groups (KRT rubric, Sonderkonten, Ohne Bereich, Geschlossen)
   * carry an i18n {@code titleKey}; a Bereich group carries the live {@code bereichName} and a
   * {@code deptClass} (its Bereichsfarbe token class), both {@code null} for the fixed groups.
   *
   * @param key stable DOM/testing key ({@code krt} / {@code bereich:<id>} / {@code special} /
   *     {@code ungrouped} / {@code closed})
   * @param titleKey the i18n key for a fixed group's heading, or {@code null} for a Bereich group
   * @param bereichName the Bereich's display name for a Bereich group, or {@code null}
   * @param deptClass the Bereich department colour class ({@code bank-dept--<dept>}), or {@code
   *     null}
   * @param cards the group's cards, ordered
   */
  public record BankDashboardGroupView(
      String key,
      String titleKey,
      String bereichName,
      String deptClass,
      List<BankDashboardCardView> cards) {}

  /**
   * Renders the bank dashboard (REQ-BANK-016). Two per-user view options ride on the query string
   * and are persisted client-side by {@code bank.js}: a {@code layout} (card grid vs. table) and a
   * {@code group} mode (alphabetical vs. by Bereich). The cards are always sorted A→Z by name; the
   * by-Bereich view additionally chunks them into coloured vertical groups (KRT rubric → Bereich
   * groups → Sonderkonten → Ohne Bereich → Geschlossen). A {@code bankGrid} fragment request
   * re-renders just the switchable grid for the in-place toggle swap (REQ-FE-005). The full-page
   * render additionally assembles the header's direct-booking "Kontobewegung" modal ({@link
   * #addMovementModalData}, REQ-BANK-023); the fragment swap skips it.
   *
   * @param layout {@code table} for the tabular view, otherwise the default card grid
   * @param group {@code bereich} for the grouped view, otherwise the default alphabetical view
   * @param fragment when {@code "bankGrid"} only the switchable grid is re-rendered (toggle swap)
   * @param model Spring MVC model
   * @return the dashboard template, or its {@code bankGrid} fragment view
   */
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
    List<BankDashboardCardView> cards =
        dashboard == null
            ? List.of()
            : BankAccountOrder.byName(
                dashboard.accounts().stream().map(BankPageController::toCardView).toList(),
                card -> card.account().name());
    model.addAttribute("dashboard", dashboard);
    model.addAttribute("cards", cards);
    model.addAttribute("layout", effectiveLayout);
    model.addAttribute("group", effectiveGroup);
    model.addAttribute("groups", "bereich".equals(effectiveGroup) ? buildGroups(cards) : List.of());
    model.addAttribute("now", java.time.Instant.now().toString());
    if ("bankGrid".equals(fragment)) {
      return "bank-dashboard :: bankGrid";
    }
    // Full-page render only: assemble the direct-booking "Kontobewegung" modal's data
    // (REQ-BANK-023, #997). The CTA + modal live OUTSIDE the swapped bankGrid fragment, so a
    // view-toggle swap never re-reads these; a successful booking re-renders the grid in place.
    addMovementModalData(model);
    return "bank-dashboard";
  }

  /**
   * Assembles the shared "Kontobewegung" direct-booking modal's catalog data for the dashboard's
   * full-page render (REQ-BANK-023, #997). Every active account is both a bookable source and a
   * transfer destination on this non-account-scoped surface (a same-account transfer is rejected by
   * the backend, REQ-BANK-006); the holder registry, counterparty user lookup, all-kinds org-unit
   * picklist and the in-game transfer-fee rate feed the modal's selectors and live fee preview. The
   * modal books through the unchanged {@code /deposits} / {@code /withdrawals} / {@code /transfers}
   * endpoints, so this adds no new endpoint, audit event or metric. Mirrors {@code
   * BankRequestQueuePageController}.
   *
   * @param model the MVC model populated with the movement-modal catalogs and {@code canBook}
   */
  private void addMovementModalData(@NotNull Model model) {
    PageResponse<BankAccountDto> accounts =
        backendApiClient.get("/api/v1/bank/accounts?size=500", BANK_ACCOUNT_PAGE);
    List<BankAccountDto> activeAccounts =
        accounts == null
            ? List.of()
            : BankAccountOrder.byName(
                accounts.content().stream().filter(a -> "ACTIVE".equals(a.status())).toList(),
                BankAccountDto::name);
    model.addAttribute("movementAccounts", activeAccounts);
    model.addAttribute("transferTargets", activeAccounts);
    model.addAttribute("canBook", !activeAccounts.isEmpty());
    List<BankHolderDto> holders = backendApiClient.get("/api/v1/bank/holders", BANK_HOLDER_LIST);
    model.addAttribute("holders", holders == null ? List.<BankHolderDto>of() : holders);
    model.addAttribute(
        "activeHolders",
        holders == null
            ? List.<BankHolderDto>of()
            : holders.stream().filter(BankHolderDto::active).toList());
    List<UserReferenceDto> users =
        backendApiClient.get("/api/v1/users/lookup", USER_REFERENCE_LIST);
    model.addAttribute("users", users == null ? List.<UserReferenceDto>of() : users);
    List<OrgUnitMembershipOptionDto> allOrgUnits =
        backendApiClient.get("/api/v1/org-units/active-all-kinds", ORG_UNIT_OPTION_LIST);
    model.addAttribute(
        "allOrgUnits", allOrgUnits == null ? List.<OrgUnitMembershipOptionDto>of() : allOrgUnits);
    model.addAttribute("transferFeeRate", fetchTransferFeeRate());
  }

  /**
   * Chunks the A→Z card list into the by-Bereich groups (REQ-BANK-016), in display order: the KRT
   * rubric (CARTEL + KRT-bank), one group per Bereich (A→Z by Bereich name, each holding its AREA
   * account first then its Staffel/SK accounts A→Z), the Sonderkonten, the "Ohne Bereich" bucket
   * for org-unit accounts with no Bereich, and finally every closed account. Only non-empty groups
   * are emitted. The input order (A→Z by account name) is preserved within the KRT / Sonderkonten /
   * closed buckets.
   *
   * @param cards the account cards, already ordered A→Z by name
   * @return the ordered, non-empty groups for the by-Bereich view
   */
  private static List<BankDashboardGroupView> buildGroups(List<BankDashboardCardView> cards) {
    List<BankDashboardCardView> krt = new ArrayList<>();
    List<BankDashboardCardView> special = new ArrayList<>();
    List<BankDashboardCardView> ungrouped = new ArrayList<>();
    List<BankDashboardCardView> closed = new ArrayList<>();
    Map<UUID, List<BankDashboardCardView>> byBereich = new LinkedHashMap<>();
    Map<UUID, BankDashboardCardView> bereichMeta = new HashMap<>();
    for (BankDashboardCardView card : cards) {
      BankDashboardAccountDto account = card.account();
      if ("CLOSED".equals(account.status())) {
        closed.add(card);
        continue;
      }
      switch (account.type()) {
        case "CARTEL", "CARTEL_BANK" -> krt.add(card);
        case "SPECIAL" -> special.add(card);
        default -> {
          if (account.bereichId() != null) {
            byBereich.computeIfAbsent(account.bereichId(), k -> new ArrayList<>()).add(card);
            bereichMeta.putIfAbsent(account.bereichId(), card);
          } else {
            ungrouped.add(card);
          }
        }
      }
    }
    List<BankDashboardGroupView> groups = new ArrayList<>();
    if (!krt.isEmpty()) {
      groups.add(new BankDashboardGroupView("krt", "bank.dashboard.group.krt", null, null, krt));
    }
    byBereich.entrySet().stream()
        .sorted(
            Comparator.comparing(
                entry -> bereichNameOf(bereichMeta.get(entry.getKey())),
                String.CASE_INSENSITIVE_ORDER))
        .forEach(
            entry -> {
              BankDashboardCardView meta = bereichMeta.get(entry.getKey());
              List<BankDashboardCardView> groupCards = new ArrayList<>(entry.getValue());
              groupCards.sort(
                  Comparator.<BankDashboardCardView, Integer>comparing(
                          card -> "AREA".equals(card.account().type()) ? 0 : 1)
                      .thenComparing(card -> card.account().name(), String.CASE_INSENSITIVE_ORDER));
              groups.add(
                  new BankDashboardGroupView(
                      "bereich:" + entry.getKey(),
                      null,
                      meta.account().bereichName(),
                      deptClass(meta.account().bereichDepartment()),
                      groupCards));
            });
    if (!special.isEmpty()) {
      groups.add(
          new BankDashboardGroupView(
              "special", "bank.dashboard.group.special", null, null, special));
    }
    if (!ungrouped.isEmpty()) {
      groups.add(
          new BankDashboardGroupView(
              "ungrouped", "bank.dashboard.group.ungrouped", null, null, ungrouped));
    }
    if (!closed.isEmpty()) {
      groups.add(
          new BankDashboardGroupView("closed", "bank.dashboard.group.closed", null, null, closed));
    }
    return groups;
  }

  /**
   * The Bereich name of a card, or an empty string when absent — the sort key for ordering the
   * Bereich groups so a null name never trips the case-insensitive comparator.
   *
   * @param card any card of the Bereich group
   * @return the Bereich display name, or {@code ""} when unknown
   */
  private static String bereichNameOf(@Nullable BankDashboardCardView card) {
    String name = card == null ? null : card.account().bereichName();
    return name == null ? "" : name;
  }

  /**
   * Maps a Bereich department enum name to its design-system colour class ({@code
   * bank-dept--<dept>} over the {@code --color-dept-*} tokens), mirroring the org chart's {@code
   * oc-dept--*} convention.
   *
   * @param department the Bereich's department enum name, or {@code null}
   * @return the colour class, or {@code null} when the Bereich has no department
   */
  private static String deptClass(@Nullable String department) {
    return department == null
        ? null
        : "bank-dept--" + department.toLowerCase(Locale.ROOT).replace('_', '-');
  }

  /**
   * Renders the account detail page (K1): facts strip, paged booking history and the booking
   * modals. Since ADR-0039 holders are decoupled from accounts, so the page shows no per-account
   * holder distribution; the booking modals' holder selects list the bank-wide holder registry. The
   * modal selects need the holder registry and the caller's visible accounts (transfer
   * destinations) — both fetched here so the page works without follow-up AJAX reads.
   *
   * @param id the account id
   * @param page zero-based booking page
   * @param size booking-history page size (10 / 50 / 100, default 50; REQ-BANK-051)
   * @param from optional booking-history period start ({@code yyyy-MM-dd}); default last 90 days
   * @param to optional booking-history period end ({@code yyyy-MM-dd}); default today
   * @param chartRange balance-chart range key ({@code 30d} / {@code 90d} / {@code 365d} / {@code
   *     all}); default {@code 90d} (REQ-BANK-049)
   * @param fragment when {@code "bookings"} only the paged booking-history fragment is rendered
   *     (period filter + pager swap, REQ-FE-002); when {@code "balanceChart"} only the
   *     balance-chart fragment (range swap); when {@code "accountBody"} the whole account body
   *     (facts, chart, bookings and the booking modals) is re-rendered in place after a money write
   *     (REQ-FE-005) so the balance, chart and booking history refresh without a reload; otherwise
   *     the full page is returned
   * @param model Spring MVC model
   * @return the detail template, or its {@code bookings} / {@code balanceChart} / {@code
   *     accountBody} fragment for an AJAX swap
   */
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
    PageResponse<BankAccountDto> accounts =
        backendApiClient.get("/api/v1/bank/accounts?size=500", BANK_ACCOUNT_PAGE);

    model.addAttribute("detail", detail);
    model.addAttribute("holders", holders == null ? List.<BankHolderDto>of() : holders);
    model.addAttribute(
        "activeHolders",
        holders == null
            ? List.<BankHolderDto>of()
            : holders.stream().filter(BankHolderDto::active).toList());
    model.addAttribute(
        "transferTargets",
        accounts == null
            ? List.<BankAccountDto>of()
            : BankAccountOrder.byName(
                accounts.content().stream()
                    .filter(a -> !a.id().equals(id))
                    .filter(a -> "ACTIVE".equals(a.status()))
                    .toList(),
                BankAccountDto::name));
    // User lookup feeds the deposit/withdrawal counterparty picker (Einzahler / Empfänger,
    // REQ-BANK-044) on the always-present booking modals, so it is fetched on every detail render
    // (the /lookup gate now admits BANK_EMPLOYEE). Approval limits are read-only on this surface
    // (REQ-BANK-041) — they are configured only in the org-unit bank — so the lookup no longer
    // feeds a limit editor here.
    List<UserReferenceDto> lookup =
        backendApiClient.get("/api/v1/users/lookup", USER_REFERENCE_LIST);
    model.addAttribute("users", lookup == null ? List.<UserReferenceDto>of() : lookup);
    // All active org units (all kinds) feed the external-counterparty unit picklist (REQ-BANK-044,
    // #994): an external Einzahler/Empfänger may be attributed to any org unit, not just a tool
    // user's own memberships. isAuthenticated on the backend, so a bank employee may read it.
    List<OrgUnitMembershipOptionDto> allOrgUnits =
        backendApiClient.get("/api/v1/org-units/active-all-kinds", ORG_UNIT_OPTION_LIST);
    model.addAttribute(
        "allOrgUnits", allOrgUnits == null ? List.<OrgUnitMembershipOptionDto>of() : allOrgUnits);
    // The in-game transfer-fee rate (ADR-0052, REQ-BANK-033) drives the live "Gebühr / wird
    // abgebucht" preview in the withdraw/transfer modals (bank.js). It rides on <main>, which
    // survives the accountBody swap, so it is fetched once on the full-page render.
    model.addAttribute("transferFeeRate", fetchTransferFeeRate());
    if ("accountBody".equals(fragment)) {
      return "bank-account-detail :: accountBody";
    }
    return "bank-account-detail";
  }

  /**
   * Renders just the paged booking-history block for an AJAX period-filter / pager swap
   * (REQ-FE-002). Fetches only the requested bookings page — the account detail, chart, holder
   * registry and transfer-target accounts the full page loads are skipped. A backend failure
   * degrades to an empty page (see {@link #addBookingsModel}) so the swapped-in fragment shows its
   * empty state rather than injecting an error page into the sub-table; the reverse-button
   * (delegated) and {@code .utc-time} localiser (re-run on {@code krt:swapped}) keep working on the
   * swapped-in rows.
   *
   * @param id the account id
   * @param page zero-based booking page (clamped to 0)
   * @param size requested page size, or {@code null} for the default
   * @param from optional period start ({@code yyyy-MM-dd})
   * @param to optional period end ({@code yyyy-MM-dd})
   * @param model Spring MVC model populated with the bookings + period + pagination attributes
   * @return the {@code bank-account-detail :: bookings} fragment view
   */
  private String bookingsFragment(
      UUID id, Integer page, Integer size, String from, String to, Model model) {
    addBookingsModel(id, page, size, from, to, model);
    return "bank-account-detail :: bookings";
  }

  /**
   * Renders just the balance-chart block for an AJAX range swap (REQ-BANK-049). Re-fetches the
   * account detail only to resolve the creation instant the {@code "all"} range needs (the balance
   * target rides on the series payload itself); a failure degrades to an empty chart.
   *
   * @param id the account id
   * @param chartRange the requested range key
   * @param model Spring MVC model populated with the chart attributes
   * @return the {@code bank-account-detail :: balanceChart} fragment view
   */
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
   * Resolves the booking-history period (default last 90 days), fetches the requested page for it
   * and fills the bookings + period-filter + pagination model attributes shared by the full page
   * and the {@code bookings} fragment (REQ-BANK-051). A backend failure degrades to an empty page
   * so the table shows its empty state instead of an error.
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
   * Resolves the balance-chart range (default 90 days), fetches its balance series and fills the
   * chart model attributes shared by the full page and the {@code balanceChart} fragment
   * (REQ-BANK-049). A backend failure degrades to an empty chart.
   *
   * @param id the account id
   * @param chartRange the requested range key
   * @param detail the account detail (for the {@code "all"} range's start), or {@code null}
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
   * Fetches one page of an account's booking history for a period from the backend transactions
   * endpoint — the single source of the booking query shared by the full-page render and the {@link
   * #bookingsFragment} AJAX swap.
   *
   * @param id the account id whose transactions to page through
   * @param page zero-based, already-clamped page index
   * @param size requested page size, or {@code null} for the backend default
   * @param from inclusive period start instant
   * @param to inclusive period end instant
   * @return the requested bookings page envelope
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
   * Fetches an account's balance-over-time series for a period from the backend balance-series
   * endpoint (REQ-BANK-049), the single source shared by the full page and the {@code balanceChart}
   * fragment swap.
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
   * Renders the holder detail page (REQ-BANK-032): the holder's header (handle, status, global
   * custody total) plus the paged custody history — every booking that touched the holder's stash.
   * A bank employee reaches only their own holder, management any (the backend {@code canSeeHolder}
   * gate enforces it; a forbidden id surfaces as the backend error). The page is read-only — no
   * modals, no money writes.
   *
   * @param id the holder id
   * @param page zero-based history page
   * @param fragment when {@code "holderBookings"} only the paged history fragment is rendered (AJAX
   *     pager swap, REQ-FE-002); otherwise the full page is returned
   * @param model Spring MVC model
   * @return the holder-detail template, or its {@code holderBookings} fragment for an AJAX swap
   */
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
   * Renders just the paged custody-history block for an AJAX pager swap (REQ-FE-002). Fetches only
   * the requested history page — the holder header the full page loads is skipped. A backend
   * failure degrades to an empty page so the swapped-in fragment shows its empty state rather than
   * injecting an error page into the sub-table; the {@code .utc-time} localiser (re-run on {@code
   * krt:swapped}) keeps the swapped-in rows live.
   *
   * @param id the holder id
   * @param page zero-based history page (clamped to 0)
   * @param model Spring MVC model populated with {@code bookings} and {@code paginationBaseUrl}
   * @return the {@code bank-holder-detail :: holderBookings} fragment view
   */
  private String holderBookingsFragment(UUID id, Integer page, Model model) {
    int effectivePage = page == null || page < 0 ? 0 : page;
    PageResponse<BankHolderBookingDto> bookings;
    try {
      bookings = fetchHolderBookings(id, effectivePage);
    } catch (Exception e) {
      log.error("Error loading holder bookings fragment for holder {}", id, e);
      bookings = null;
    }
    model.addAttribute("bookings", bookings);
    model.addAttribute("paginationBaseUrl", "/bank/holders/" + id);
    return "bank-holder-detail :: holderBookings";
  }

  /**
   * Fetches one page of a holder's custody history (page size 20) from the backend holder
   * transactions endpoint — the single source of the history query shared by the full-page render
   * and the {@link #holderBookingsFragment} AJAX swap.
   *
   * @param id the holder id whose history to page through
   * @param page zero-based, already-clamped page index
   * @return the requested history page envelope
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
   * Fetches the current in-game transfer-fee rate for the booking-modal preview (ADR-0052,
   * REQ-BANK-033); a backend failure or absent rate degrades to {@link BigDecimal#ZERO} so the page
   * still renders (the preview then simply shows no fee). The authoritative fee is always computed
   * server-side at booking time.
   *
   * @return the fee rate as a fraction, never {@code null}
   */
  private BigDecimal fetchTransferFeeRate() {
    BankTransferFeeRateDto rate =
        backendApiClient.get("/api/v1/bank/transfer-fee-rate", BankTransferFeeRateDto.class);
    return rate == null || rate.rate() == null ? BigDecimal.ZERO : rate.rate();
  }

  /**
   * Wraps one backend card payload with its scaled sparkline polyline ({@link BankSparkline}),
   * ready for the template.
   *
   * @param account the backend card payload
   * @return the card view with pre-computed points
   */
  private static BankDashboardCardView toCardView(@NotNull BankDashboardAccountDto account) {
    BankSparkline.Spark spark = BankSparkline.of(account.sparkline());
    return new BankDashboardCardView(account, spark.points(), spark.flat());
  }
}
