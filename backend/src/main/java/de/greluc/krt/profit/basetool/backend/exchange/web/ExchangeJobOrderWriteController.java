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
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeJobOrderChangeService;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeJobOrderCreateService;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeJobOrderCancelDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeJobOrderCommentDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeJobOrderCreateDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeJobOrderCreateOptionsDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeJobOrderCreatedDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeJobOrderTargetChangeDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeJobOrderTargetChangedDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeJobOrderWrittenDto;
import de.greluc.krt.profit.basetool.backend.platform.api.ActingMemberHeader;
import de.greluc.krt.profit.basetool.backend.platform.api.SubjectAuthentication;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The job-order writes of an exchange client under {@code exchange.joborders.write}, reachable only
 * from the ingest gateway (REQ-OXCH-004 to REQ-OXCH-008): the create options, the create of an aUEC
 * order, its cancel, its target change and its comment change. The job-order family switch gates
 * every route here and the writes switch every write (REQ-OXCH-009).
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

  /** Writes on existing aUEC orders. */
  private final ExchangeJobOrderChangeService changeService;

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

  /**
   * Cancels an active aUEC order of a unit the member writes for, before its first accepted money
   * operation.
   *
   * @param id the order
   * @param body the lifecycle version the client last read
   * @param authentication the relayed acting member the gate admitted
   * @return the order's id, status and new versions
   */
  @NotNull
  @PostMapping("/{id}/cancel")
  @PreAuthorize("@exchangeGate.allows('exchange.joborders.write', authentication)")
  @Operation(
      summary = "Exchange: cancel an aUEC order",
      description =
          "Gateway-only. Cancels an open or in-progress aUEC order of one of the member's direct"
              + " Logistician units through the web's status command. Checked in this order under"
              + " the order locks: an unknown, hidden or deleted order answers 404 NOT_FOUND; an"
              + " order the member does not write for and a MATERIAL or ITEM order 403"
              + " ACCESS_DENIED; a stale lifecycle version 409 OPTIMISTIC_LOCK; an invalid"
              + " responsibility 409 JOB_ORDER_RESPONSIBILITY_INVALID; an order that is not open or"
              + " in progress 409 JOB_ORDER_CLOSED; an order a money operation of which was"
              + " accepted 409 JOB_ORDER_MONEY_STARTED. Moves no money.")
  @ApiResponse(responseCode = "200", description = "The cancelled order")
  @ApiResponse(responseCode = "403", description = "The member does not write the order")
  @ApiResponse(responseCode = "404", description = "No such order the member reads")
  @ApiResponse(
      responseCode = "409",
      description = "A stale version, invalid responsibility, a closed order or money started")
  public ResponseEntity<ExchangeJobOrderWrittenDto> cancel(
      @NotNull @PathVariable UUID id,
      @NotNull @Valid @RequestBody ExchangeJobOrderCancelDto body,
      @NotNull Authentication authentication) {
    return ResponseEntity.ok(
        changeService.cancel(
            ExchangeCaller.of((SubjectAuthentication) authentication),
            id,
            body.lifecycleVersion()));
  }

  /**
   * Changes the target of an active aUEC order of a unit the member writes for, to an amount above
   * the order's net handover.
   *
   * @param id the order
   * @param body the new target and the target version the client last read
   * @param authentication the relayed acting member the gate admitted
   * @return the order's id, status, new versions and stored target
   */
  @NotNull
  @PostMapping("/{id}/target")
  @PreAuthorize("@exchangeGate.allows('exchange.joborders.write', authentication)")
  @Operation(
      summary = "Exchange: change an aUEC order's target",
      description =
          "Gateway-only. Raises or lowers the target of an open or in-progress aUEC order of one of"
              + " the member's direct Logistician units through the web's target command. Checked"
              + " in this order under the order locks: an unknown, hidden or deleted order answers"
              + " 404 NOT_FOUND; an order the member does not write for and a MATERIAL or ITEM"
              + " order 403 ACCESS_DENIED; a stale target version 409 OPTIMISTIC_LOCK; an invalid"
              + " responsibility 409 JOB_ORDER_RESPONSIBILITY_INVALID; an order that is not open or"
              + " in progress 409 JOB_ORDER_CLOSED; a target at or below the order's net handover"
              + " 409 JOB_ORDER_TARGET_TOO_LOW. Never completes or reopens the order and moves no"
              + " money.")
  @ApiResponse(responseCode = "200", description = "The order with its new target")
  @ApiResponse(responseCode = "403", description = "The member does not write the order")
  @ApiResponse(responseCode = "404", description = "No such order the member reads")
  @ApiResponse(
      responseCode = "409",
      description = "A stale version, invalid responsibility, a closed order or a target too low")
  public ResponseEntity<ExchangeJobOrderTargetChangedDto> changeTarget(
      @NotNull @PathVariable UUID id,
      @NotNull @Valid @RequestBody ExchangeJobOrderTargetChangeDto body,
      @NotNull Authentication authentication) {
    return ResponseEntity.ok(
        changeService.changeTarget(
            ExchangeCaller.of((SubjectAuthentication) authentication),
            id,
            body.target(),
            body.targetVersion()));
  }

  /**
   * Changes the comment of an aUEC order of a unit the member writes for, under the comment's own
   * version.
   *
   * @param id the order
   * @param body the new comment and the comment version the client last read
   * @param authentication the relayed acting member the gate admitted
   * @return the order's id, status and new versions
   */
  @NotNull
  @PostMapping("/{id}/comment")
  @PreAuthorize("@exchangeGate.allows('exchange.joborders.write', authentication)")
  @Operation(
      summary = "Exchange: change an aUEC order's comment",
      description =
          "Gateway-only. Changes the comment of an aUEC order of one of the member's direct"
              + " Logistician units through the web's information command, under the comment"
              + " version alone; the contact is never written. Checked in this order under the"
              + " order locks: an unknown, hidden or deleted order answers 404 NOT_FOUND; an order"
              + " the member does not write for and a MATERIAL or ITEM order 403 ACCESS_DENIED; a"
              + " stale comment version 409 OPTIMISTIC_LOCK; an invalid responsibility 409"
              + " JOB_ORDER_RESPONSIBILITY_INVALID; a cancelled order 409 JOB_ORDER_CLOSED.")
  @ApiResponse(responseCode = "200", description = "The order with its new comment version")
  @ApiResponse(responseCode = "403", description = "The member does not write the order")
  @ApiResponse(responseCode = "404", description = "No such order the member reads")
  @ApiResponse(
      responseCode = "409",
      description = "A stale version, invalid responsibility or a cancelled order")
  public ResponseEntity<ExchangeJobOrderWrittenDto> comment(
      @NotNull @PathVariable UUID id,
      @NotNull @Valid @RequestBody ExchangeJobOrderCommentDto body,
      @NotNull Authentication authentication) {
    return ResponseEntity.ok(
        changeService.comment(
            ExchangeCaller.of((SubjectAuthentication) authentication),
            id,
            body.comment(),
            body.commentVersion()));
  }
}
