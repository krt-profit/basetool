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

import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeBankRequestService;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeCaller;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeFeedReader;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankRequestPageDto;
import de.greluc.krt.profit.basetool.backend.platform.api.SubjectAuthentication;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The member's own bank booking requests for an exchange client, reachable only from the ingest
 * gateway (REQ-BXCH-003). The bank switch gates every route here (REQ-BXCH-006).
 */
@RestController
@RequestMapping("/api/v1/exchange/bank/requests")
@RequiredArgsConstructor
@Tag(name = "Exchange — bank", description = "The bank accounts the member views")
public class ExchangeBankRequestController {

  /** Serves the snapshot and the feed of the member's own requests. */
  private final ExchangeBankRequestService requestService;

  /**
   * Returns a snapshot page or a feed page of the member's own requests.
   *
   * @param cursor the cursor of the last page, or absent for a new snapshot
   * @param limit the page size, at most 1000
   * @param authentication the relayed acting member the gate admitted
   * @return the page
   */
  @NotNull
  @GetMapping
  @PreAuthorize("@exchangeGate.allows('exchange.bank.read', authentication)")
  @Operation(
      summary = "Exchange: my own bank booking requests",
      description =
          "Gateway-only. Every booking request the member raised, in the Basetool or through any"
              + " client, with status, amount, accounts, approval need, rejection reason and"
              + " split; no staff note, staff identities, counterparty or purpose. A snapshot"
              + " followed by the change feed of REQ-XCH-013. The member's own data: the client"
              + " may persist it.")
  @ApiResponse(responseCode = "200", description = "The page")
  @ApiResponse(responseCode = "410", description = "The cursor has expired (CURSOR_EXPIRED)")
  public ResponseEntity<ExchangeBankRequestPageDto> requests(
      @Nullable @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "" + ExchangeFeedReader.DEFAULT_LIMIT) int limit,
      @NotNull Authentication authentication) {
    return ResponseEntity.ok(
        requestService.page(
            ExchangeCaller.of((SubjectAuthentication) authentication), cursor, limit));
  }
}
