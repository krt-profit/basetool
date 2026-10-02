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

package de.greluc.krt.profit.basetool.backend.controller.exchange;

import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeChangeResultDto;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeStockChangeSet;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeStockPageDto;
import de.greluc.krt.profit.basetool.backend.service.exchange.ExchangeCaller;
import de.greluc.krt.profit.basetool.backend.service.exchange.ExchangeFeedReader;
import de.greluc.krt.profit.basetool.backend.service.exchange.ExchangeStockFeedService;
import de.greluc.krt.profit.basetool.backend.service.exchange.ExchangeStockWriteService;
import de.greluc.krt.profit.basetool.backend.support.SubjectAuthentication;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The member's stock lots, personal and shared, for an exchange client, reachable only from the
 * ingest gateway (REQ-XCH-013, REQ-XCH-016).
 */
@RestController
@RequestMapping("/api/v1/exchange/me/stock")
@RequiredArgsConstructor
@Tag(name = "Exchange — stock", description = "The member's stock lots and their changes")
public class ExchangeStockController {

  /** Reads the snapshot and the feed. */
  private final ExchangeStockFeedService feedService;

  /** Applies the changes. */
  private final ExchangeStockWriteService writeService;

  /**
   * Returns a snapshot page, or with {@code cursor} the changes since it.
   *
   * @param cursor the cursor of the last page, or absent for a new snapshot
   * @param limit the page size, at most 1000
   * @param authentication the relayed acting member the gate admitted
   * @return the page
   */
  @NotNull
  @GetMapping
  @PreAuthorize("@exchangeGate.allows('exchange.stock.read', authentication)")
  @Operation(
      summary = "Exchange: my stock lots and their changes",
      description = "Gateway-only. Without a cursor a snapshot, with one the changes since it.")
  @ApiResponse(responseCode = "200", description = "The page")
  @ApiResponse(responseCode = "410", description = "The cursor has expired (CURSOR_EXPIRED)")
  public ResponseEntity<ExchangeStockPageDto> stock(
      @Nullable @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "" + ExchangeFeedReader.DEFAULT_LIMIT) int limit,
      @NotNull Authentication authentication) {
    SubjectAuthentication caller = (SubjectAuthentication) authentication;
    return ResponseEntity.ok(feedService.page(UUID.fromString(caller.subject()), cursor, limit));
  }

  /**
   * Sets the member's lots to new quantities in one transaction.
   *
   * @param changeSet the changes
   * @param authentication the relayed acting member the gate admitted
   * @return the counts, the offers the book-outs lowered or removed, and the detail of every op
   *     that was not applied
   */
  @NotNull
  @PostMapping("/changes")
  @PreAuthorize("@exchangeGate.allows('exchange.stock.write', authentication)")
  @Operation(
      summary = "Exchange: set my stock lots",
      description = "Gateway-only. 409 MASS_CHANGE_CONFIRMATION_REQUIRED writes nothing.")
  @ApiResponse(responseCode = "200", description = "The result")
  @ApiResponse(
      responseCode = "409",
      description = "The member must confirm the batch (MASS_CHANGE_CONFIRMATION_REQUIRED)")
  public ResponseEntity<ExchangeChangeResultDto> stockChanges(
      @NotNull @Valid @RequestBody ExchangeStockChangeSet changeSet,
      @NotNull Authentication authentication) {
    return ResponseEntity.ok(
        writeService.apply(ExchangeCaller.of((SubjectAuthentication) authentication), changeSet));
  }
}
