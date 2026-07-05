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

import de.greluc.krt.profit.basetool.frontend.model.dto.BankBookingRequestDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankHolderDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Renders the bank-staff confirmation queue (epic #666 F2, REQ-BANK-023): the booking requests the
 * caller may act on, with confirm (records the holder, books the ledger) and reject modals. Gated
 * to {@code BANK_EMPLOYEE} like the rest of the bank area; the backend scopes the list to the
 * accounts the caller can see. Confirm/reject are AJAX writes via {@code
 * /api/proxy/bank/requests/**} that swap the {@code requestQueue} fragment in place. The queue is a
 * single table with parallel status filters (Ausstehend / Best&auml;tigt / Abgelehnt /
 * Zur&uuml;ckgezogen) whose selection {@code bank.js} persists per user in {@code localStorage} and
 * replays through the {@code status} query parameter; the holder registry is fetched here so the
 * confirm modal's holder select works without a follow-up read.
 */
@Controller
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

  private final BackendApiClient backendApiClient;

  /**
   * Renders the queue (or its {@code requestQueue} fragment for an in-place swap after a decision
   * or a filter change).
   *
   * @param status the parallel status filter as a comma-separated list of lifecycle states; {@code
   *     null} (absent) is a first, unfiltered load and defaults to {@code PENDING}; the {@code
   *     NONE} sentinel is the deliberate "all filters off" selection (empty table)
   * @param fragment when {@code "requestQueue"} only the queue table is re-rendered (AJAX swap
   *     after confirm/reject or a filter toggle); otherwise the full page is returned
   * @param model Spring MVC model
   * @return the template, or its {@code requestQueue} fragment view
   */
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
    // Active holders feed the transfer-confirm destination-holder select (REQ-BANK-040): a
    // destination may only receive money on an active holder.
    model.addAttribute(
        "activeHolders",
        holders == null
            ? List.<BankHolderDto>of()
            : holders.stream().filter(BankHolderDto::active).toList());
    if ("requestQueue".equals(fragment)) {
      return "bank-requests :: requestQueue";
    }
    return "bank-requests";
  }

  /**
   * Resolves the raw {@code status} query parameter into the ordered, validated list of lifecycle
   * states to show. {@code null} — the parameter is absent (a first, unfiltered load) or a blank
   * value the global {@code emptyAsNull} string binder collapsed to {@code null} — defaults to the
   * {@code PENDING} work queue. Otherwise the comma-separated names are kept in canonical display
   * order, de-duplicated and filtered to the known states; the client sends the {@code NONE}
   * sentinel (an unknown status that filters out) for the deliberate "all filters off" selection,
   * so it yields an empty list distinct from the absent-parameter default.
   *
   * @param status the raw {@code status} query parameter, or {@code null} when absent/blank
   * @return the lifecycle states to show, in canonical display order (possibly empty)
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
