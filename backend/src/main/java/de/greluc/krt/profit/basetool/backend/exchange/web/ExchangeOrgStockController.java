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

import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeCaller;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeCapability;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeFeedReader;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeGate;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeOrgStockService;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgStockPageDto;
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
 * The shared stock of the member's direct org units, for an exchange client, reachable only from
 * the ingest gateway (REQ-XCH-042).
 */
@RestController
@RequestMapping("/api/v1/exchange/org-stock")
@RequiredArgsConstructor
@Tag(name = "Exchange — org stock", description = "The shared stock of the member's units")
public class ExchangeOrgStockController {

  /** Pages the shared lots of the acting member's direct units and counts each page. */
  private final ExchangeOrgStockService orgStockService;

  /** Tells whether the admitted request also holds {@code exchange.orgstock.details}. */
  private final ExchangeGate exchangeGate;

  /**
   * Returns a snapshot or feed page of the org stock.
   *
   * @param cursor the cursor of the last page, or absent for a new snapshot
   * @param limit the page size, at most 1000
   * @param authentication the relayed acting member the gate admitted
   * @return the page with the units the member reads
   */
  @NotNull
  @GetMapping
  @PreAuthorize("@exchangeGate.allows('exchange.orgstock.read', authentication)")
  @Operation(
      summary = "Exchange: the shared stock of my units",
      description =
          "Gateway-only. A snapshot of the pools of the member's direct units or, with a feed"
              + " cursor, their changes. With exchange.orgstock.details, the lots of a pool in"
              + " which the member is a Logistician of the unit carry their single entries.")
  @ApiResponse(responseCode = "200", description = "The page")
  @ApiResponse(responseCode = "410", description = "The cursor has expired (CURSOR_EXPIRED)")
  public ResponseEntity<ExchangeOrgStockPageDto> orgStock(
      @Nullable @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "" + ExchangeFeedReader.DEFAULT_LIMIT) int limit,
      @NotNull Authentication authentication) {
    return ResponseEntity.ok(
        orgStockService.page(
            ExchangeCaller.of((SubjectAuthentication) authentication),
            cursor,
            limit,
            exchangeGate.holds(ExchangeCapability.ORGSTOCK_DETAILS, authentication)));
  }
}
