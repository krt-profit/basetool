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
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeJobOrderCreateService;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeJobOrderCreateDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeJobOrderCreateOptionsDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeJobOrderCreatedDto;
import de.greluc.krt.profit.basetool.backend.platform.api.ActingMemberHeader;
import de.greluc.krt.profit.basetool.backend.platform.api.SubjectAuthentication;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The job-order writes of an exchange client under {@code exchange.joborders.write}, reachable only
 * from the ingest gateway (REQ-OXCH-004, REQ-OXCH-005): the create options and the create of an
 * aUEC order. The job-order family switch gates every route here and the writes switch every write
 * (REQ-OXCH-009).
 */
@RestController
@RequestMapping("/api/v1/exchange/job-orders")
@RequiredArgsConstructor
@Tag(name = "Exchange — job orders", description = "The job orders the member relates to")
public class ExchangeJobOrderWriteController {

  /** The shape of an idempotency key, as the gateway admits it (REQ-XCH-020). */
  private static final String IDEMPOTENCY_KEY = "[A-Za-z0-9._~-]{8,128}";

  /** Serves the create options and creates aUEC orders. */
  private final ExchangeJobOrderCreateService createService;

  /**
   * Returns the pairs the member may create an aUEC order for.
   *
   * @param authentication the relayed acting member the gate admitted
   * @return the member's writer units, the eligible Profit units, the suggestion, the writes switch
   *     and the member's figures against the caps on client creates
   */
  @NotNull
  @GetMapping("/create-options")
  @PreAuthorize("@exchangeGate.allows('exchange.joborders.write', authentication)")
  @Operation(
      summary = "Exchange: where I may create an aUEC order",
      description =
          "Gateway-only. The requesting units are the active units the member directly belongs to"
              + " and is a Logistician of; the responsible units are the eligible Profit units,"
              + " with the canonical Profit area suggested. Both lists are empty when the member"
              + " may create no order. writesEnabled tells whether a create can be served now;"
              + " limits carries the open-order and daily create caps with the member's own"
              + " client-created open orders and creates of the UTC day. Names no member.")
  @ApiResponse(responseCode = "200", description = "The create options")
  public ResponseEntity<ExchangeJobOrderCreateOptionsDto> createOptions(
      @NotNull Authentication authentication) {
    return ResponseEntity.ok(
        createService.options(ExchangeCaller.of((SubjectAuthentication) authentication)));
  }

  /**
   * Creates an aUEC order for the member, or answers the order created earlier under the key.
   *
   * @param body the pair, contact, comment and target
   * @param idempotencyKey the client's key of the create as the gateway relayed it
   * @param authentication the relayed acting member the gate admitted
   * @return {@code 201} with the order's id, status, versions and target
   */
  @NotNull
  @PostMapping
  @PreAuthorize("@exchangeGate.allows('exchange.joborders.write', authentication)")
  @Operation(
      summary = "Exchange: create an aUEC order",
      description =
          "Gateway-only. Creates an aUEC order with one of the member's direct Logistician units"
              + " as the requesting unit and an eligible Profit unit as the responsible unit,"
              + " through the web's create. A pair that is not among the create options answers"
              + " 400 JOB_ORDER_UNITS_NOT_OFFERED, naming no side. A member who holds the"
              + " open-order cap of open orders created through clients is refused with 409"
              + " JOB_ORDER_OPEN_ORDER_CAP, one who created the daily create cap of orders through"
              + " clients within the UTC day with 409 JOB_ORDER_DAILY_CREATE_CAP. A create under an"
              + " idempotency key the client already created an order of the member with answers"
              + " that order with its current state, whatever the caps; changed terms are never"
              + " applied. Moves no money.")
  @ApiResponse(responseCode = "201", description = "The created order")
  @ApiResponse(responseCode = "400", description = "The pair is not offered")
  @ApiResponse(responseCode = "409", description = "The open-order or the daily create cap")
  public ResponseEntity<ExchangeJobOrderCreatedDto> create(
      @NotNull @Valid @RequestBody ExchangeJobOrderCreateDto body,
      @NotNull
          @Pattern(regexp = IDEMPOTENCY_KEY)
          @RequestHeader(ActingMemberHeader.EXCHANGE_IDEMPOTENCY_KEY_HEADER)
          String idempotencyKey,
      @NotNull Authentication authentication) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            createService.create(
                ExchangeCaller.of((SubjectAuthentication) authentication), body, idempotencyKey));
  }
}
