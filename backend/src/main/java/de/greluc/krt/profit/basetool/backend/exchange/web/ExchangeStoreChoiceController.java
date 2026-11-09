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
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeStoreChoices;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeStoreChoiceJobOrdersDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeStoreChoiceReceiversDto;
import de.greluc.krt.profit.basetool.backend.platform.api.SubjectAuthentication;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The store choices a refinery pickup's store lines name, for an exchange client, reachable only
 * from the ingest gateway (REQ-XCH-054).
 */
@RestController
@RequestMapping("/api/v1/exchange/me/store-choices")
@RequiredArgsConstructor
@Tag(name = "Exchange — store choices", description = "Receivers and job orders of a store line")
public class ExchangeStoreChoiceController {

  /** Decides the choices from the database. */
  private final ExchangeStoreChoices storeChoices;

  /**
   * Lists the members the member may book onto.
   *
   * @param authentication the relayed acting member the gate admitted
   * @return the receivers with their direct memberships
   */
  @NotNull
  @GetMapping("/receivers")
  @PreAuthorize("@exchangeGate.allows('exchange.refinery.store', authentication)")
  @Operation(
      summary = "Exchange: whom my store lines may book onto",
      description =
          "Gateway-only. The member, and for a Logistician the direct members of the units they"
              + " are a Logistician of, without those who withhold their name.")
  @ApiResponse(responseCode = "200", description = "The receivers")
  public ResponseEntity<ExchangeStoreChoiceReceiversDto> receivers(
      @NotNull Authentication authentication) {
    return ResponseEntity.ok(
        storeChoices.receiverList(ExchangeCaller.of((SubjectAuthentication) authentication)));
  }

  /**
   * Lists the active job orders the member sees that need a material.
   *
   * @param material the material
   * @return the job orders, empty for a material that names nothing
   */
  @NotNull
  @GetMapping("/job-orders")
  @PreAuthorize("@exchangeGate.allows('exchange.refinery.store', authentication)")
  @Operation(
      summary = "Exchange: the job orders a store line may earmark to",
      description =
          "Gateway-only. The open and in-progress job orders in the member's read scope that need"
              + " the material.")
  @ApiResponse(responseCode = "200", description = "The job orders")
  public ResponseEntity<ExchangeStoreChoiceJobOrdersDto> jobOrders(
      @NotNull @RequestParam UUID material) {
    return ResponseEntity.ok(storeChoices.jobOrders(material));
  }
}
