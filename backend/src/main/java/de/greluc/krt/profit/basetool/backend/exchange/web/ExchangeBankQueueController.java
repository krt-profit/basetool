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

import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeBankQueueService;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeCaller;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankQueueDto;
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
 * The read-only bank-staff queue for an exchange client, reachable only from the ingest gateway
 * (REQ-BXCH-005). The bank switch gates the route (REQ-BXCH-006).
 */
@RestController
@RequestMapping("/api/v1/exchange/bank/queue")
@RequiredArgsConstructor
@Tag(name = "Exchange — bank", description = "The bank accounts the member views")
public class ExchangeBankQueueController {

  /** Serves the queue through the bank access policy. */
  private final ExchangeBankQueueService queueService;

  /**
   * Returns a snapshot page of the member's staff queue.
   *
   * @param cursor the cursor of the last page, or absent for the first one
   * @param limit the page size of each section, at most 500
   * @param authentication the relayed acting member the gate admitted
   * @return the page
   */
  @NotNull
  @GetMapping
  @PreAuthorize("@exchangeGate.allows('exchange.bank.queue', authentication)")
  @Operation(
      summary = "Exchange: the bank-staff queue",
      description =
          "Gateway-only. The pending requests the web staff queue shows the member (open, with"
              + " mayConfirm) and those waiting for the member's approval (toApprove), oldest"
              + " first, decided anew on every call; Admin has no effect. Foreign bank data: memory"
              + " only, at most 7 days.")
  @ApiResponse(responseCode = "200", description = "The page")
  @ApiResponse(
      responseCode = "403",
      description = "No bank role, no grant and no request to approve (NOT_BANK_STAFF)")
  @ApiResponse(responseCode = "410", description = "The cursor has expired (CURSOR_EXPIRED)")
  public ResponseEntity<ExchangeBankQueueDto> queue(
      @Nullable @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "" + ExchangeBankQueueService.MAX_LIMIT) int limit,
      @NotNull Authentication authentication) {
    return ResponseEntity.ok(
        queueService.queue(
            ExchangeCaller.of((SubjectAuthentication) authentication), cursor, limit));
  }
}
