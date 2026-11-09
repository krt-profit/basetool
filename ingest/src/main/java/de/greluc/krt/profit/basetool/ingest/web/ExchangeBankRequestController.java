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

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The member's own bank booking requests on the gateway (REQ-BXCH-003): a snapshot and its change
 * feed, the paging checked before the relay and the answer against its schema after it. The bank
 * switch and the route scope are checked by the gate before a handler runs.
 */
@RestController
@RequestMapping(ExchangeBankRequestController.REQUESTS)
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class ExchangeBankRequestController {

  /** The request routes on the gateway. */
  static final String REQUESTS = "/exchange/v1/bank/requests";

  /** The request routes below the relay prefix. */
  private static final String BANK_REQUESTS = "/bank/requests";

  private final ExchangeRelayResponses responses;

  /**
   * Returns a snapshot page or a feed page of the member's own booking requests (REQ-BXCH-003).
   *
   * @param cursor the cursor of the last page, or {@code null} for a new snapshot
   * @param limit the page size, 1 to 1000, or {@code null} for the default
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the page or a problem
   */
  @GetMapping
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> bankRequests(
      @Nullable @RequestParam(required = false) String cursor,
      @Nullable @RequestParam(required = false) String limit,
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    return responses.page(
        BANK_REQUESTS,
        "page.schema.json#/$defs/bankRequestPage",
        cursor,
        limit,
        request,
        acceptLanguage);
  }
}
