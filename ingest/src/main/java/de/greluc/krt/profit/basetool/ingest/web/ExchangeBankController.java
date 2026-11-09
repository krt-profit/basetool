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

package de.greluc.krt.profit.basetool.ingest.web;

import de.greluc.krt.profit.basetool.ingest.gate.ExchangeRequestContext;
import de.greluc.krt.profit.basetool.ingest.relay.ExchangeRelay;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The bank's read routes on the gateway (REQ-BXCH-002): the accounts the member views, their
 * bookings and balance series, each checked before the relay and against its schema after it. The
 * bank switch and the route scope are checked by the gate before a handler runs.
 */
@RestController
@RequestMapping(ExchangeBankController.ACCOUNTS)
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class ExchangeBankController {

  /** The account routes on the gateway. */
  static final String ACCOUNTS = "/exchange/v1/bank/accounts";

  /** The account routes below the relay prefix. */
  private static final String BANK_ACCOUNTS = "/bank/accounts";

  private final ExchangeRelay relay;
  private final ExchangeRelayResponses responses;

  /**
   * Returns a snapshot page of the bank accounts the member views (REQ-BXCH-002).
   *
   * @param cursor the cursor of the last page, or {@code null} for the first one
   * @param limit the page size, 1 to 1000, or {@code null} for the default
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the page or a problem
   */
  @GetMapping
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> bankAccounts(
      @Nullable @RequestParam(required = false) String cursor,
      @Nullable @RequestParam(required = false) String limit,
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    return responses.page(
        BANK_ACCOUNTS,
        "page.schema.json#/$defs/bankAccountPage",
        cursor,
        limit,
        request,
        acceptLanguage);
  }

  /**
   * Returns a snapshot page of the bookings of a bank account the member views, newest first
   * (REQ-BXCH-002).
   *
   * @param id the account's id
   * @param from the inclusive lower bound on the booking time, or {@code null}
   * @param to the inclusive upper bound on the booking time, or {@code null}
   * @param cursor the cursor of the last page, or {@code null} for the first one
   * @param limit the page size, 1 to 1000, or {@code null} for the default
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the page or a problem
   */
  @GetMapping("/{id}/bookings")
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> bankBookings(
      @NotNull @PathVariable String id,
      @Nullable @RequestParam(required = false) String from,
      @Nullable @RequestParam(required = false) String to,
      @Nullable @RequestParam(required = false) String cursor,
      @Nullable @RequestParam(required = false) String limit,
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    BankQuery query = BankQuery.of(id, from, to, false);
    if (!query.violations().isEmpty()) {
      return responses.schemaInvalid(query.violations());
    }
    return responses.page(
        BANK_ACCOUNTS + "/" + query.accountId() + "/bookings",
        "page.schema.json#/$defs/bankBookingPage",
        query.bounds()::addTo,
        cursor,
        limit,
        ExchangeController.PAGE_MAX_LIMIT,
        request,
        acceptLanguage);
  }

  /**
   * Returns the daily balance series of a bank account the member views over at most {@value
   * BankQuery#MAX_SERIES_DAYS} days (REQ-BXCH-002).
   *
   * @param id the account's id
   * @param from the inclusive start of the period
   * @param to the inclusive end of the period
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the series or a problem
   */
  @GetMapping("/{id}/balance-series")
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> bankBalanceSeries(
      @NotNull @PathVariable String id,
      @Nullable @RequestParam(required = false) String from,
      @Nullable @RequestParam(required = false) String to,
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    ExchangeRequestContext context = ExchangeRequestContext.of(request);
    if (context == null) {
      return responses.failed();
    }
    BankQuery query = BankQuery.of(id, from, to, true);
    if (!query.violations().isEmpty()) {
      return responses.schemaInvalid(query.violations());
    }
    return responses.relayed(
        relay.forward(
            HttpMethod.GET,
            ExchangeRelayResponses.backendTarget(
                BANK_ACCOUNTS + "/" + query.accountId() + "/balance-series", query.bounds()::addTo),
            null,
            context,
            acceptLanguage),
        "bank-balance-series.schema.json",
        List.of(),
        context,
        request);
  }
}
