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
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeBankRequestWriteService;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeCaller;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeFeedReader;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankRequestCancelDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankRequestCancelledDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankRequestCorrectionDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankRequestCreateDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankRequestPageDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankRequestWrittenDto;
import de.greluc.krt.profit.basetool.backend.platform.api.SubjectAuthentication;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The member's own bank booking requests for an exchange client, reachable only from the ingest
 * gateway: the snapshot and feed (REQ-BXCH-003) and the writes that raise, correct and cancel them
 * (REQ-BXCH-004). The bank switch gates every route here (REQ-BXCH-006).
 */
@RestController
@RequestMapping("/api/v1/exchange/bank/requests")
@RequiredArgsConstructor
@Tag(name = "Exchange — bank", description = "The bank accounts the member views")
public class ExchangeBankRequestController {

  /** Serves the snapshot and the feed of the member's own requests. */
  private final ExchangeBankRequestService requestService;

  /** Raises, corrects and cancels the member's own requests. */
  private final ExchangeBankRequestWriteService writeService;

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
              + " followed by the change feed of REQ-XCH-013; every page carries openRequestCap,"
              + " the most pending requests the member may hold. The member's own data: the"
              + " client may persist it.")
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

  /**
   * Raises a booking request for the member.
   *
   * @param body the request
   * @param authentication the relayed acting member the gate admitted
   * @return {@code 201} with the request's id, status, version and amount
   */
  @NotNull
  @PostMapping
  @PreAuthorize("@exchangeGate.allows('exchange.bank.request', authentication)")
  @Operation(
      summary = "Exchange: raise one of my bank booking requests",
      description =
          "Gateway-only. A deposit, withdrawal or transfer with the web's validation and approval"
              + " routing; a deposit may target any active account. An unknown, closed or hidden"
              + " account answers 404 NOT_FOUND; 20 pending requests answer 409"
              + " BANK_OPEN_REQUEST_CAP. Books nothing.")
  @ApiResponse(responseCode = "201", description = "The pending request")
  @ApiResponse(responseCode = "404", description = "Unknown, closed or hidden account")
  @ApiResponse(responseCode = "409", description = "A bank conflict or the open-request cap")
  public ResponseEntity<ExchangeBankRequestWrittenDto> raise(
      @NotNull @Valid @RequestBody ExchangeBankRequestCreateDto body,
      @NotNull Authentication authentication) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(writeService.raise(ExchangeCaller.of((SubjectAuthentication) authentication), body));
  }

  /**
   * Corrects one of the member's pending requests (REQ-BANK-056).
   *
   * @param id the request
   * @param body the corrected values and the echoed version
   * @param authentication the relayed acting member the gate admitted
   * @return the request's id, status, version and amount
   */
  @NotNull
  @PostMapping("/{id}/correction")
  @PreAuthorize("@exchangeGate.allows('exchange.bank.request', authentication)")
  @Operation(
      summary = "Exchange: correct one of my pending bank booking requests",
      description =
          "Gateway-only. Replaces amount, note, justification and destination of a pending, not yet"
              + " approved own request, raised anywhere, and re-routes its approval. Another"
              + " member's request answers 404 NOT_FOUND.")
  @ApiResponse(responseCode = "200", description = "The corrected request")
  @ApiResponse(responseCode = "404", description = "Unknown or foreign request")
  @ApiResponse(responseCode = "409", description = "Not pending, approved or a stale version")
  public ResponseEntity<ExchangeBankRequestWrittenDto> correct(
      @NotNull @PathVariable UUID id,
      @NotNull @Valid @RequestBody ExchangeBankRequestCorrectionDto body,
      @NotNull Authentication authentication) {
    return ResponseEntity.ok(
        writeService.correct(ExchangeCaller.of((SubjectAuthentication) authentication), id, body));
  }

  /**
   * Cancels one of the member's pending requests.
   *
   * @param id the request
   * @param body the echoed version
   * @param authentication the relayed acting member the gate admitted
   * @return the request's id, status and version
   */
  @NotNull
  @PostMapping("/{id}/cancel")
  @PreAuthorize("@exchangeGate.allows('exchange.bank.request', authentication)")
  @Operation(
      summary = "Exchange: cancel one of my pending bank booking requests",
      description =
          "Gateway-only. Cancels a pending own request, raised anywhere. Another member's request"
              + " answers 404 NOT_FOUND.")
  @ApiResponse(responseCode = "200", description = "The cancelled request")
  @ApiResponse(responseCode = "404", description = "Unknown or foreign request")
  @ApiResponse(responseCode = "409", description = "Not pending or a stale version")
  public ResponseEntity<ExchangeBankRequestCancelledDto> cancel(
      @NotNull @PathVariable UUID id,
      @NotNull @Valid @RequestBody ExchangeBankRequestCancelDto body,
      @NotNull Authentication authentication) {
    return ResponseEntity.ok(
        writeService.cancel(
            ExchangeCaller.of((SubjectAuthentication) authentication), id, body.version()));
  }
}
