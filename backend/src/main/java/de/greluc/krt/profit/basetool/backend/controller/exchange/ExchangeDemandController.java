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

import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeOrgDemandDto;
import de.greluc.krt.profit.basetool.backend.platform.api.SubjectAuthentication;
import de.greluc.krt.profit.basetool.backend.service.exchange.ExchangeDemandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The anonymised open demand of the member's units for an exchange client, reachable only from the
 * ingest gateway (REQ-XCH-018).
 */
@RestController
@RequestMapping("/api/v1/exchange/me/org-demand")
@RequiredArgsConstructor
@Tag(name = "Exchange — org demand", description = "The open demand of the member's units")
public class ExchangeDemandController {

  /** Computes the demand. */
  private final ExchangeDemandService demandService;

  /**
   * Returns the open demand of the units the member belongs to.
   *
   * @param authentication the relayed acting member the gate admitted
   * @return the demand
   */
  @NotNull
  @GetMapping
  @PreAuthorize("@exchangeGate.allows('exchange.demand.read', authentication)")
  @Operation(
      summary = "Exchange: the open demand of my units",
      description = "Gateway-only. Anonymised: no names, titles, free text or per-order figures.")
  public ResponseEntity<ExchangeOrgDemandDto> orgDemand(@NotNull Authentication authentication) {
    SubjectAuthentication caller = (SubjectAuthentication) authentication;
    return ResponseEntity.ok(demandService.demand(UUID.fromString(caller.subject())));
  }
}
