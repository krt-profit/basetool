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

package de.greluc.krt.profit.basetool.backend.exchange.web;

import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeBankReadService;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeCaller;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeFeedReader;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankAccountPageDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankBalanceSeriesDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankBookingPageDto;
import de.greluc.krt.profit.basetool.backend.platform.api.SubjectAuthentication;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The bank accounts the member views, their bookings and balance series, for an exchange client,
 * reachable only from the ingest gateway (REQ-BXCH-002). The bank switch gates every route here
 * (REQ-BXCH-006).
 */
@RestController
@RequestMapping("/api/v1/exchange/bank/accounts")
@RequiredArgsConstructor
@Tag(name = "Exchange — bank", description = "The bank accounts the member views")
public class ExchangeBankController {

  /** Serves the reads through the bank access policy. */
  private final ExchangeBankReadService readService;

  /**
   * Returns a snapshot page of the accounts the member views.
   *
   * @param cursor the cursor of the last page, or absent for the first one
   * @param limit the page size, at most 1000
   * @param authentication the relayed acting member the gate admitted
   * @return the page
   */
  @NotNull
  @GetMapping
  @PreAuthorize("@exchangeGate.allows('exchange.bank.read', authentication)")
  @Operation(
      summary = "Exchange: the bank accounts I view",
      description =
          "Gateway-only. The active accounts the member views, the KRT account first and the"
              + " rest by account number, with balance, target, 30-day trend and whether the member"
              + " may raise a request and up to which limit. No holders. Foreign bank data: memory"
              + " only, at most 7 days.")
  @ApiResponse(responseCode = "200", description = "The page")
  @ApiResponse(responseCode = "410", description = "The cursor has expired (CURSOR_EXPIRED)")
  public ResponseEntity<ExchangeBankAccountPageDto> accounts(
      @Nullable @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "" + ExchangeFeedReader.DEFAULT_LIMIT) int limit,
      @NotNull Authentication authentication) {
    return ResponseEntity.ok(readService.accounts(caller(authentication), cursor, limit));
  }

  /**
   * Returns a snapshot page of a viewable account's bookings, newest first.
   *
   * @param id the account
   * @param from inclusive lower bound on the booking instant, or absent
   * @param to inclusive upper bound on the booking instant, or absent
   * @param cursor the cursor of the last page, or absent for the first one
   * @param limit the page size, at most 1000
   * @param authentication the relayed acting member the gate admitted
   * @return the page
   */
  @NotNull
  @GetMapping("/{id}/bookings")
  @PreAuthorize("@exchangeGate.allows('exchange.bank.read', authentication)")
  @Operation(
      summary = "Exchange: the bookings of an account I view",
      description =
          "Gateway-only. The member detail page's redaction: no holders and no staff note; the"
              + " counterparty's handle is null when they withhold their name. A hidden, closed or"
              + " unknown account answers 404 NOT_FOUND. Foreign bank data: memory only, at most 7"
              + " days.")
  @ApiResponse(responseCode = "200", description = "The page")
  @ApiResponse(responseCode = "404", description = "Hidden, closed or unknown (NOT_FOUND)")
  @ApiResponse(responseCode = "410", description = "The cursor has expired (CURSOR_EXPIRED)")
  public ResponseEntity<ExchangeBankBookingPageDto> bookings(
      @NotNull @PathVariable UUID id,
      @Nullable @RequestParam(required = false) Instant from,
      @Nullable @RequestParam(required = false) Instant to,
      @Nullable @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "" + ExchangeFeedReader.DEFAULT_LIMIT) int limit,
      @NotNull Authentication authentication) {
    return ResponseEntity.ok(
        readService.bookings(caller(authentication), id, from, to, cursor, limit));
  }

  /**
   * Returns the daily balance series of a viewable account.
   *
   * @param id the account
   * @param from inclusive period start
   * @param to inclusive period end, at most 365 days after {@code from}
   * @param authentication the relayed acting member the gate admitted
   * @return the series
   */
  @NotNull
  @GetMapping("/{id}/balance-series")
  @PreAuthorize("@exchangeGate.allows('exchange.bank.read', authentication)")
  @Operation(
      summary = "Exchange: the balance series of an account I view",
      description =
          "Gateway-only. One end-of-day balance per UTC day of [from, to], at most 365 days. A"
              + " hidden, closed or unknown account answers 404 NOT_FOUND. Foreign bank data:"
              + " memory only, at most 7 days.")
  @ApiResponse(responseCode = "200", description = "The series")
  @ApiResponse(responseCode = "400", description = "A bound is missing or the span is too long")
  @ApiResponse(responseCode = "404", description = "Hidden, closed or unknown (NOT_FOUND)")
  public ResponseEntity<ExchangeBankBalanceSeriesDto> balanceSeries(
      @NotNull @PathVariable UUID id,
      @Nullable @RequestParam(required = false) Instant from,
      @Nullable @RequestParam(required = false) Instant to,
      @NotNull Authentication authentication) {
    return ResponseEntity.ok(readService.balanceSeries(caller(authentication), id, from, to));
  }

  /**
   * Reads the relayed caller.
   *
   * @param authentication the relayed acting member the gate admitted
   * @return the caller
   */
  @NotNull
  private static ExchangeCaller caller(@NotNull Authentication authentication) {
    return ExchangeCaller.of((SubjectAuthentication) authentication);
  }
}
