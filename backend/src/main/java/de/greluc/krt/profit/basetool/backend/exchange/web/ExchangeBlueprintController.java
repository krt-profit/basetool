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

import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeBlueprintFeedService;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeBlueprintWriteService;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeCaller;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeFeedReader;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBlueprintChangeSet;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBlueprintPageDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeChangeResultDto;
import de.greluc.krt.profit.basetool.backend.platform.api.SubjectAuthentication;
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
 * The member's blueprints for an exchange client, reachable only from the ingest gateway
 * (REQ-XCH-013, REQ-XCH-015).
 */
@RestController
@RequestMapping("/api/v1/exchange/me/blueprints")
@RequiredArgsConstructor
@Tag(name = "Exchange — blueprints", description = "The member's blueprints and their changes")
public class ExchangeBlueprintController {

  /** Reads the snapshot and the feed. */
  private final ExchangeBlueprintFeedService feedService;

  /** Applies the changes. */
  private final ExchangeBlueprintWriteService writeService;

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
  @PreAuthorize("@exchangeGate.allows('exchange.blueprints.read', authentication)")
  @Operation(
      summary = "Exchange: my blueprints and their changes",
      description = "Gateway-only. Without a cursor a snapshot, with one the changes since it.")
  @ApiResponse(responseCode = "200", description = "The page")
  @ApiResponse(responseCode = "410", description = "The cursor has expired (CURSOR_EXPIRED)")
  public ResponseEntity<ExchangeBlueprintPageDto> blueprints(
      @Nullable @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "" + ExchangeFeedReader.DEFAULT_LIMIT) int limit,
      @NotNull Authentication authentication) {
    SubjectAuthentication caller = (SubjectAuthentication) authentication;
    return ResponseEntity.ok(feedService.page(UUID.fromString(caller.subject()), cursor, limit));
  }

  /**
   * Adds and removes blueprints for the member in one transaction.
   *
   * @param changeSet the changes
   * @param authentication the relayed acting member the gate admitted
   * @return the counts and the detail of every op that was not applied
   */
  @NotNull
  @PostMapping("/changes")
  @PreAuthorize("@exchangeGate.allows('exchange.blueprints.write', authentication)")
  @Operation(
      summary = "Exchange: add or remove my blueprints",
      description = "Gateway-only. 409 MASS_CHANGE_CONFIRMATION_REQUIRED writes nothing.")
  @ApiResponse(responseCode = "200", description = "The result")
  @ApiResponse(
      responseCode = "409",
      description = "The member must confirm the batch (MASS_CHANGE_CONFIRMATION_REQUIRED)")
  public ResponseEntity<ExchangeChangeResultDto> blueprintChanges(
      @NotNull @Valid @RequestBody ExchangeBlueprintChangeSet changeSet,
      @NotNull Authentication authentication) {
    return ResponseEntity.ok(
        writeService.apply(ExchangeCaller.of((SubjectAuthentication) authentication), changeSet));
  }
}
