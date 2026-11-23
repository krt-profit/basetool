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
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeFeedReader;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeJobOrderReadService;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeJobOrderPageDto;
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
 * The member's job orders for an exchange client, reachable only from the ingest gateway
 * (REQ-OXCH-002, REQ-OXCH-003). The job-order family switch gates every route here (REQ-OXCH-009).
 */
@RestController
@RequestMapping("/api/v1/exchange/job-orders")
@RequiredArgsConstructor
@Tag(name = "Exchange — job orders", description = "The job orders the member relates to")
public class ExchangeJobOrderController {

  /** Serves the snapshot of the member's job-order set. */
  private final ExchangeJobOrderReadService readService;

  /**
   * Returns a snapshot page of the member's job-order set.
   *
   * @param cursor the cursor of the last page, or absent for a new snapshot
   * @param limit the page size, at most 1000
   * @param authentication the relayed acting member the gate admitted
   * @return the page
   */
  @NotNull
  @GetMapping
  @PreAuthorize("@exchangeGate.allows('exchange.joborders.read', authentication)")
  @Operation(
      summary = "Exchange: the job orders I relate to",
      description =
          "Gateway-only. The job orders of all three kinds a unit the member directly belongs to"
              + " requests, is responsible for or actively supports, and those the member works"
              + " on as an assignee: active, closed within 7 days, and aUEC while its settlement"
              + " is open. Header, the member's relations, assignee names under per-client refs,"
              + " the member's own note, the section versions and each kind's progress; no"
              + " holder, amount per person, provision, Bank state, commitment or claim. A"
              + " snapshot paged by id that ends with the feed cursor of REQ-XCH-013; a client"
              + " keeps the data at most 7 days, in memory.")
  @ApiResponse(responseCode = "200", description = "The page")
  @ApiResponse(responseCode = "410", description = "The cursor has expired (CURSOR_EXPIRED)")
  public ResponseEntity<ExchangeJobOrderPageDto> jobOrders(
      @Nullable @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "" + ExchangeFeedReader.DEFAULT_LIMIT) int limit,
      @NotNull Authentication authentication) {
    return ResponseEntity.ok(
        readService.page(ExchangeCaller.of((SubjectAuthentication) authentication), cursor, limit));
  }
}
