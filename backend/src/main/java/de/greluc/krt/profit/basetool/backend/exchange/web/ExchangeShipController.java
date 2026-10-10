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
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeShipFeedService;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeShipWriteService;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeChangeResultDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeShipChangeSet;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeShipPageDto;
import de.greluc.krt.profit.basetool.backend.platform.api.SubjectAuthentication;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
 * The member's own ships for an exchange client, reachable only from the ingest gateway
 * (REQ-XCH-013, REQ-XCH-017).
 */
@RestController
@RequestMapping("/api/v1/exchange/me/ships")
@RequiredArgsConstructor
@Tag(name = "Exchange — ships", description = "The member's ships and their changes")
public class ExchangeShipController {

  /** Reads the snapshot and the feed. */
  private final ExchangeShipFeedService feedService;

  /** Applies the change sets. */
  private final ExchangeShipWriteService writeService;

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
  @PreAuthorize("@exchangeGate.allows('exchange.hangar.read', authentication)")
  @Operation(
      summary = "Exchange: my ships and their changes",
      description = "Gateway-only. Without a cursor a snapshot, with one the changes since it.")
  @ApiResponse(responseCode = "200", description = "The page")
  @ApiResponse(responseCode = "410", description = "The cursor has expired (CURSOR_EXPIRED)")
  public ResponseEntity<ExchangeShipPageDto> ships(
      @Nullable @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "" + ExchangeFeedReader.DEFAULT_LIMIT) int limit,
      @NotNull Authentication authentication) {
    return ResponseEntity.ok(
        feedService.page(ExchangeCaller.of((SubjectAuthentication) authentication), cursor, limit));
  }

  /**
   * Applies a change set to the member's own ships.
   *
   * @param changeSet the links, upserts and removals
   * @param authentication the relayed acting member the gate admitted
   * @return the result, with the mission units the removals detached
   */
  @NotNull
  @PostMapping("/changes")
  @PreAuthorize("@exchangeGate.allows('exchange.hangar.write', authentication)")
  @Operation(
      summary = "Exchange: change my ships",
      description = "Gateway-only. 409 MASS_CHANGE_CONFIRMATION_REQUIRED writes nothing.")
  @ApiResponse(responseCode = "200", description = "The result")
  @ApiResponse(responseCode = "400", description = "An op lacks a field its kind requires")
  @ApiResponse(
      responseCode = "409",
      description = "The member must confirm the batch (MASS_CHANGE_CONFIRMATION_REQUIRED)")
  public ResponseEntity<ExchangeChangeResultDto> shipChanges(
      @NotNull @Valid @RequestBody ExchangeShipChangeSet changeSet,
      @NotNull Authentication authentication) {
    return ResponseEntity.ok(
        writeService.apply(ExchangeCaller.of((SubjectAuthentication) authentication), changeSet));
  }
}
