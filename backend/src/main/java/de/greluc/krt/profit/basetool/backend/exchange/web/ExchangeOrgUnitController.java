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

import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeOrgUnitService;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgUnitListDto;
import de.greluc.krt.profit.basetool.backend.platform.api.SubjectAuthentication;
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
 * The org units the member directly belongs to, for an exchange client, reachable only from the
 * ingest gateway (REQ-XCH-048).
 */
@RestController
@RequestMapping("/api/v1/exchange/me/org-units")
@RequiredArgsConstructor
@Tag(name = "Exchange — org units", description = "The member's direct org-unit memberships")
public class ExchangeOrgUnitController {

  /** Lists the memberships. */
  private final ExchangeOrgUnitService orgUnitService;

  /**
   * Returns the org units the member directly belongs to, of all four kinds.
   *
   * @param authentication the relayed acting member the gate admitted
   * @return the org units, empty when the member belongs to none
   */
  @NotNull
  @GetMapping
  @PreAuthorize("@exchangeGate.allows('exchange.orgunits.read', authentication)")
  @Operation(
      summary = "Exchange: my org units",
      description = "Gateway-only. The direct memberships: id, name, shorthand and kind.")
  public ResponseEntity<ExchangeOrgUnitListDto> orgUnits(@NotNull Authentication authentication) {
    SubjectAuthentication caller = (SubjectAuthentication) authentication;
    return ResponseEntity.ok(orgUnitService.orgUnits(UUID.fromString(caller.subject())));
  }
}
