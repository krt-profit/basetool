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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * confirm or reject, one status segment at a time, with the holder registry for the confirm modal.
 * Decisions are AJAX writes that swap the {@code requestQueue} fragment.
 */
@Controller
@UsesLayoutModel
@RequiredArgsConstructor
@Slf4j
public class BankRequestQueuePageController {

  /** Lifecycle states of a booking request, in canonical display order. */
  private static final List<String> STATUS_ORDER =
      List.of("PENDING", "CONFIRMED", "REJECTED", "CANCELLED");

  /** The status segment the queue opens on. */
  private static final String DEFAULT_SEGMENT = "PENDING";

  /** The segment that shows every lifecycle state, the withdrawn ones included. */
  private static final String ALL_SEGMENT = "ALL";

  /** The single-status segments, each with a count in the segmented control. */
  private static final List<String> COUNTED_SEGMENTS = List.of("PENDING", "CONFIRMED", "REJECTED");

  /** Page size of the listed requests. */
  private static final int QUEUE_SIZE = 200;

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
   * Renders the queue, or its {@code requestQueue} fragment after a decision or segment change.
   *
   * @param status the status segment ({@code PENDING}, {@code CONFIRMED}, {@code REJECTED} or
   *     {@code ALL}); {@code null} opens {@code PENDING}, and a comma-separated list of states is
   *     mapped onto the matching segment
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
    String segment = resolveSegment(status);
    PageResponse<BankBookingRequestDto> pending = fetchRequests(List.of("PENDING"), QUEUE_SIZE);
    PageResponse<BankBookingRequestDto> requests =
        switch (segment) {
          case DEFAULT_SEGMENT -> pending;
          case ALL_SEGMENT -> fetchRequests(STATUS_ORDER, QUEUE_SIZE);
          default -> fetchRequests(List.of(segment), QUEUE_SIZE);
        };
    Map<String, Long> counts = new LinkedHashMap<>();
    for (String counted : COUNTED_SEGMENTS) {
      PageResponse<BankBookingRequestDto> source;
      if (DEFAULT_SEGMENT.equals(counted)) {
        source = pending;
      } else if (counted.equals(segment)) {
        source = requests;
      } else {
        source = fetchRequests(List.of(counted), 1);
      }
      counts.put(counted, source == null ? 0L : source.totalElements());
    }
    long waitingForYou =
        pending == null
            ? 0L
            : pending.content().stream()
                .filter(BankRequestQueuePageController::awaitsTheBank)
                .count();
    List<BankHolderDto> holders = backendApiClient.get("/api/v1/bank/holders", BANK_HOLDER_LIST);
    model.addAttribute("requests", requests);
    model.addAttribute("selectedStatus", segment);
    model.addAttribute("statusCounts", counts);
    model.addAttribute("statusOptions", statusOptions(counts));
    model.addAttribute("waitingForYouCount", waitingForYou);
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
   * Whether a pending request is ready for the bank's decision: it needs no owner approval, or the
   * approval has been granted in-app (REQ-BANK-041).
   *
   * @param request the request to check
   * @return {@code true} when the request is pending and no approval step is outstanding
   */
  static boolean awaitsTheBank(@NotNull BankBookingRequestDto request) {
    return "PENDING".equals(request.status())
        && (!request.requiresOwnerApproval() || request.ownerApprovalGranted());
  }

  /**
   * Builds the options of the status segmented control: the three single-status segments with their
   * counts, then {@code ALL} without one.
   *
   * @param counts the request count per single-status segment
   * @return the options in display order, each with {@code value}, {@code labelKey} and, for a
   *     single status, {@code count}
   */
  @NotNull
  private static List<Map<String, Object>> statusOptions(@NotNull Map<String, Long> counts) {
    List<Map<String, Object>> options =
        new ArrayList<>(
            COUNTED_SEGMENTS.stream()
                .map(
                    s ->
                        Map.<String, Object>of(
                            "value",
                            s,
                            "labelKey",
                            "bank.request.status." + s,
                            "count",
                            counts.getOrDefault(s, 0L)))
                .toList());
    options.add(Map.of("value", ALL_SEGMENT, "labelKey", "filter.all"));
    return List.copyOf(options);
  }

  /**
   * Fetches one page of the requests in the given states the caller may act on.
   *
   * @param statuses the lifecycle states to include
   * @param size the page size
   * @return the page, or {@code null} when the backend answered with nothing
   */
  @Nullable
  private PageResponse<BankBookingRequestDto> fetchRequests(
      @NotNull List<String> statuses, int size) {
    UriComponentsBuilder uri =
        UriComponentsBuilder.fromPath("/api/v1/bank/requests").queryParam("size", size);
    statuses.forEach(s -> uri.queryParam("status", s));
    return backendApiClient.get(uri.toUriString(), BOOKING_REQUEST_PAGE);
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
   * Resolves the {@code status} parameter into a segment. {@code null}, blank or no known state
   * opens {@code PENDING}; {@code ALL} stays {@code ALL}; one counted state selects its segment;
   * any other combination of known states, a lone {@code CANCELLED} included, shows {@code ALL}.
   *
   * @param status the raw {@code status} query parameter, or {@code null} when absent
   * @return the segment to show, never {@code null}
   */
  @NotNull
  static String resolveSegment(@Nullable String status) {
    if (status == null || status.isBlank()) {
      return DEFAULT_SEGMENT;
    }
    Set<String> requested =
        Arrays.stream(status.split(","))
            .map(String::trim)
            .map(s -> s.toUpperCase(Locale.ROOT))
            .collect(Collectors.toSet());
    if (requested.contains(ALL_SEGMENT)) {
      return ALL_SEGMENT;
    }
    List<String> known = STATUS_ORDER.stream().filter(requested::contains).toList();
    if (known.isEmpty()) {
      return DEFAULT_SEGMENT;
    }
    if (known.size() == 1 && COUNTED_SEGMENTS.contains(known.get(0))) {
      return known.get(0);
    }
    return ALL_SEGMENT;
  }
}
