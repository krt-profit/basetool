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
import de.greluc.krt.profit.basetool.frontend.model.dto.BankBookingDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankDashboardAccountDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankDashboardDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankHolderBookingDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankHolderDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankTransferFeeRateDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserReferenceDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.math.BigDecimal;
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
   * Renders the bank dashboard (REQ-BANK-016): one KPI card per visible account plus the
   * management-only totals strip. The cards are sorted alphabetically (case-insensitive) by account
   * name regardless of the order the backend returns, so the grid reads the same for both the
   * management (all accounts) and employee (granted accounts) perspectives.
   *
   * @param model Spring MVC model
   * @return the dashboard template
   */
  @GetMapping("/bank")
  @PreAuthorize("hasRole('" + Roles.BANK_EMPLOYEE + "')")
  public String dashboard(Model model) {
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
    model.addAttribute("now", java.time.Instant.now().toString());
    return "bank-dashboard";
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
   * @param fragment when {@code "bookings"} only the paged booking-history fragment is rendered
   *     (AJAX pager swap, REQ-FE-002); when {@code "accountBody"} the whole account body (facts,
   *     bookings and the booking modals) is re-rendered in place after a money write (REQ-FE-005)
   *     so the balance and booking history refresh without a reload; otherwise the full page is
   *     returned
   * @param model Spring MVC model
   * @return the detail template, or its {@code bookings} / {@code accountBody} fragment for an AJAX
   *     swap
   */
  @GetMapping("/bank/accounts/{id}")
  @PreAuthorize("hasRole('" + Roles.BANK_EMPLOYEE + "')")
  public String accountDetail(
      @PathVariable @NotNull UUID id,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) String fragment,
      Model model) {
    if ("bookings".equals(fragment)) {
      return bookingsFragment(id, page, model);
    }
    int effectivePage = page == null || page < 0 ? 0 : page;
    BankAccountDetailDto detail =
        backendApiClient.get("/api/v1/bank/accounts/" + id, BankAccountDetailDto.class);
    PageResponse<BankBookingDto> bookings = fetchBookings(id, effectivePage);
    List<BankHolderDto> holders = backendApiClient.get("/api/v1/bank/holders", BANK_HOLDER_LIST);
    PageResponse<BankAccountDto> accounts =
        backendApiClient.get("/api/v1/bank/accounts?size=500", BANK_ACCOUNT_PAGE);

    model.addAttribute("detail", detail);
    model.addAttribute("bookings", bookings);
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
    model.addAttribute("paginationBaseUrl", "/bank/accounts/" + id);
    // User lookup feeds the deposit/withdrawal counterparty picker (Einzahler / Empfänger,
    // REQ-BANK-044) on the always-present booking modals, so it is fetched on every detail render
    // (the /lookup gate now admits BANK_EMPLOYEE). Approval limits are read-only on this surface
    // (REQ-BANK-041) — they are configured only in the org-unit bank — so the lookup no longer
    // feeds a limit editor here.
    List<UserReferenceDto> lookup =
        backendApiClient.get("/api/v1/users/lookup", USER_REFERENCE_LIST);
    model.addAttribute("users", lookup == null ? List.<UserReferenceDto>of() : lookup);
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
   * Renders just the paged booking-history block for an AJAX pager swap (REQ-FE-002). Fetches only
   * the requested bookings page — the account detail, holder registry and transfer-target accounts
   * the full page loads (only the modals need them) are skipped. A backend failure degrades to an
   * empty page so the swapped-in fragment shows its empty state rather than injecting an error page
   * into the sub-table; the reverse-button (delegated) and {@code .utc-time} localiser (re-run on
   * {@code krt:swapped}) keep working on the swapped-in rows.
   *
   * @param id the account id
   * @param page zero-based booking page (clamped to 0)
   * @param model Spring MVC model populated with {@code bookings} and {@code paginationBaseUrl}
   * @return the {@code bank-account-detail :: bookings} fragment view
   */
  private String bookingsFragment(UUID id, Integer page, Model model) {
    int effectivePage = page == null || page < 0 ? 0 : page;
    PageResponse<BankBookingDto> bookings;
    try {
      bookings = fetchBookings(id, effectivePage);
    } catch (Exception e) {
      log.error("Error loading bookings fragment for account {}", id, e);
      bookings = null;
    }
    model.addAttribute("bookings", bookings);
    model.addAttribute("paginationBaseUrl", "/bank/accounts/" + id);
    return "bank-account-detail :: bookings";
  }

  /**
   * Fetches one page of an account's booking history (page size 20) from the backend transactions
   * endpoint — the single source of the booking query shared by the full-page render and the {@link
   * #bookingsFragment} AJAX swap.
   *
   * @param id the account id whose transactions to page through
   * @param page zero-based, already-clamped page index
   * @return the requested bookings page envelope
   */
  private PageResponse<BankBookingDto> fetchBookings(UUID id, int page) {
    return backendApiClient.get(
        UriComponentsBuilder.fromPath("/api/v1/bank/accounts/" + id + "/transactions")
            .queryParam("page", page)
            .queryParam("size", 20)
            .toUriString(),
        BANK_BOOKING_PAGE);
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
