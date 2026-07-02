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
import de.greluc.krt.profit.basetool.frontend.model.dto.BankBookingDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankBookingRequestDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitBankAccountDetailDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitBankAccountSettingsDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitBankBalanceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserReferenceDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.ParallelPageLoader;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.math.BigDecimal;
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

  /**
   * Renders the overview page (or its {@code orgUnitBank} fragment for an in-place swap after a
   * booking write).
   *
   * @param fragment when {@code "orgUnitBank"} only the balances + own-request region is
   *     re-rendered
   * @param model Spring MVC model
   * @return the template, or its {@code orgUnitBank} fragment view
   */
  @GetMapping("/org-unit-bank")
  @PreAuthorize(MEMBER_OR_ABOVE)
  public String orgUnitBank(@RequestParam(required = false) String fragment, Model model) {
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
    // carries a request for them — the latter covers the KRT-account middle-band approver
    // (Bereichsleiter Profit), who is not the account's responsible holder but sees its
    // AREA_LEAD_PROFIT-band requests (REQ-BANK-047). SPECIAL is never request-capable, so an
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
          backendApiClient.get(
              "/api/v1/org-units/bank/balances",
              new ParameterizedTypeReference<List<OrgUnitBankBalanceDto>>() {});
      if (balances != null) {
        return balances;
      }
    } catch (RuntimeException e) {
      log.warn("Failed to fetch org-unit bank balances");
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
          backendApiClient.get(
              "/api/v1/org-units/bank/requests",
              new ParameterizedTypeReference<List<BankBookingRequestDto>>() {});
      if (requests != null) {
        return requests;
      }
    } catch (RuntimeException e) {
      log.warn("Failed to fetch org-unit bank own requests");
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
              "/api/v1/org-units/bank/requests/foreign",
              new ParameterizedTypeReference<List<BankBookingRequestDto>>() {});
      if (requests != null) {
        return requests;
      }
    } catch (RuntimeException e) {
      log.warn("Failed to fetch org-unit bank foreign requests");
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
          backendApiClient.get(
              "/api/v1/org-units/bank/transfer-targets",
              new ParameterizedTypeReference<List<BankAccountRefDto>>() {});
      if (targets != null) {
        return targets;
      }
    } catch (RuntimeException e) {
      log.warn("Failed to fetch org-unit bank transfer targets");
    }
    return List.of();
  }

  /**
   * Renders the read-only account drill-in (REQ-BANK-038) — history (Halter redacted) + the
   * Kontoauszug export — plus, for the responsible holder / OL, the settings region (balance target
   * + configurable visibility). The {@code orgUnitBankBookings} fragment re-renders the paginated
   * history in place; the {@code orgUnitBankSettings} fragment re-renders the facts + settings
   * region after a target/visibility write.
   *
   * @param id the account id
   * @param page zero-based booking-history page index
   * @param fragment {@code "orgUnitBankBookings"} (pager swap) or {@code "orgUnitBankSettings"}
   *     (settings swap), else the full page
   * @param model Spring MVC model
   * @return the template, or one of its fragment views
   */
  @GetMapping("/org-unit-bank/accounts/{id}")
  @PreAuthorize(MEMBER_OR_ABOVE)
  public String orgUnitBankAccount(
      @PathVariable UUID id,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) String fragment,
      Model model) {
    OrgUnitBankAccountDetailDto detail =
        backendApiClient.get(
            "/api/v1/org-units/bank/accounts/" + id, OrgUnitBankAccountDetailDto.class);
    int effectivePage = page == null || page < 0 ? 0 : page;
    PageResponse<BankBookingDto> bookings =
        backendApiClient.get(
            "/api/v1/org-units/bank/accounts/" + id + "/transactions?page=" + effectivePage,
            new ParameterizedTypeReference<PageResponse<BankBookingDto>>() {});
    model.addAttribute("detail", detail);
    model.addAttribute("bookings", bookings);
    model.addAttribute("paginationBaseUrl", "/org-unit-bank/accounts/" + id);

    boolean canManage =
        detail != null
            && (detail.canSetTarget()
                || detail.canConfigureVisibility()
                || detail.canConfigureApprovalLimits());
    OrgUnitBankAccountSettingsDto settings = null;
    List<UserReferenceDto> users = List.of();
    if (canManage) {
      settings =
          backendApiClient.get(
              "/api/v1/org-units/bank/accounts/" + id + "/settings",
              OrgUnitBankAccountSettingsDto.class);
      // The user dropdown feeds both the individual-visibility and the individual-limit pickers.
      if (detail.canConfigureVisibility() || detail.canConfigureApprovalLimits()) {
        List<UserReferenceDto> lookup =
            backendApiClient.get(
                "/api/v1/users/lookup",
                new ParameterizedTypeReference<List<UserReferenceDto>>() {});
        users = lookup == null ? List.<UserReferenceDto>of() : lookup;
      }
    }
    model.addAttribute("settings", settings);
    model.addAttribute("users", users);

    if ("orgUnitBankBookings".equals(fragment)) {
      return "org-unit-bank-account-detail :: orgUnitBankBookings";
    }
    if ("orgUnitBankSettings".equals(fragment)) {
      return "org-unit-bank-account-detail :: orgUnitBankSettings";
    }
    return "org-unit-bank-account-detail";
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
