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
import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankBookingRequestDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankHolderDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankTransferFeeRateDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Renders the bank-staff confirmation queue (REQ-BANK-023): the booking requests the caller may
 * confirm or reject, filtered by status, with the holder registry for the confirm modal. Decisions
 * are AJAX writes that swap the {@code requestQueue} fragment.
 */
@Controller
@UsesLayoutModel
@RequiredArgsConstructor
@Slf4j
public class BankRequestQueuePageController {

  /** Lifecycle states the queue filter offers, in canonical display order. */
  private static final List<String> STATUS_ORDER =
      List.of("PENDING", "CONFIRMED", "REJECTED", "CANCELLED");

  /** Response type for one page of booking requests ({@code /api/v1/bank/requests}). */
  private static final ParameterizedTypeReference<PageResponse<BankBookingRequestDto>>
      BOOKING_REQUEST_PAGE = new ParameterizedTypeReference<>() {};

  /**
   * Response type for the bank-wide holder registry ({@code /api/v1/bank/holders}) feeding the
   * confirm modal's holder select.
   */
  private static final ParameterizedTypeReference<List<BankHolderDto>> BANK_HOLDER_LIST =
      new ParameterizedTypeReference<>() {};

  /**
   * Response type for the account list ({@code /api/v1/bank/accounts}) feeding the direct-booking
   * modal's source and destination selectors (REQ-BANK-023).
   */
  private static final ParameterizedTypeReference<PageResponse<BankAccountDto>> BANK_ACCOUNT_PAGE =
      new ParameterizedTypeReference<>() {};

  /**
   * Response type for the all-kinds active org-unit option list ({@code
   * /api/v1/org-units/active-all-kinds}) feeding the external-counterparty picklist (REQ-BANK-044).
   */
  private static final ParameterizedTypeReference<List<OrgUnitMembershipOptionDto>>
      ORG_UNIT_OPTION_LIST = new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Renders the queue, or its {@code requestQueue} fragment after a decision or filter change.
   *
   * @param status comma-separated status filter; {@code null} defaults to {@code PENDING}, the
   *     {@code NONE} sentinel shows an empty table
   * @param fragment {@code "requestQueue"} to re-render only the queue table
   * @param model Spring MVC model
   * @return the template, or its {@code requestQueue} fragment
   */
  @NotNull
  @GetMapping("/bank/requests")
  @PreAuthorize("hasRole('" + Roles.BANK_EMPLOYEE + "')")
  public String queue(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String fragment,
      Model model) {
    List<String> selectedStatuses = resolveStatuses(status);
    PageResponse<BankBookingRequestDto> requests = null;
    if (!selectedStatuses.isEmpty()) {
      UriComponentsBuilder uri =
          UriComponentsBuilder.fromPath("/api/v1/bank/requests").queryParam("size", 200);
      selectedStatuses.forEach(s -> uri.queryParam("status", s));
      requests = backendApiClient.get(uri.toUriString(), BOOKING_REQUEST_PAGE);
    }
    List<BankHolderDto> holders = backendApiClient.get("/api/v1/bank/holders", BANK_HOLDER_LIST);
    model.addAttribute("requests", requests);
    model.addAttribute("selectedStatuses", selectedStatuses);
    model.addAttribute("holders", holders == null ? List.<BankHolderDto>of() : holders);
    model.addAttribute(
        "activeHolders",
        holders == null
            ? List.<BankHolderDto>of()
            : holders.stream().filter(BankHolderDto::active).toList());
    if ("requestQueue".equals(fragment)) {
      return "bank-requests :: requestQueue";
    }
    PageResponse<BankAccountDto> activeProbe =
        backendApiClient.get("/api/v1/bank/accounts?status=ACTIVE&size=1", BANK_ACCOUNT_PAGE);
    model.addAttribute("canBook", activeProbe != null && activeProbe.totalElements() > 0);
    List<OrgUnitMembershipOptionDto> allOrgUnits =
        backendApiClient.get("/api/v1/org-units/active-all-kinds", ORG_UNIT_OPTION_LIST);
    model.addAttribute(
        "allOrgUnits", allOrgUnits == null ? List.<OrgUnitMembershipOptionDto>of() : allOrgUnits);
    model.addAttribute("transferFeeRate", fetchTransferFeeRate());
    return "bank-requests";
  }

  /**
   * Fetches the in-game transfer-fee rate for the direct-booking modal's preview (REQ-BANK-033);
   * failure or absence yields {@link BigDecimal#ZERO}. The real fee is computed server-side.
   *
   * @return the fee rate as a fraction, never {@code null}
   */
  private BigDecimal fetchTransferFeeRate() {
    BankTransferFeeRateDto rate =
        backendApiClient.get("/api/v1/bank/transfer-fee-rate", BankTransferFeeRateDto.class);
    return rate == null || rate.rate() == null ? BigDecimal.ZERO : rate.rate();
  }

  /**
   * Resolves the {@code status} parameter into the lifecycle states to show, in canonical order,
   * de-duplicated and restricted to known states. {@code null} defaults to {@code PENDING}; the
   * {@code NONE} sentinel yields an empty list.
   *
   * @param status the raw {@code status} query parameter, or {@code null} when absent or blank
   * @return the lifecycle states to show, possibly empty
   */
  private List<String> resolveStatuses(@Nullable String status) {
    if (status == null) {
      return List.of("PENDING");
    }
    Set<String> requested =
        Arrays.stream(status.split(",")).map(String::trim).collect(Collectors.toSet());
    return STATUS_ORDER.stream().filter(requested::contains).toList();
  }
}
