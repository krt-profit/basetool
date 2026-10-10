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

import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeCatalogService;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeResolveService;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeLocationListDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeResolveRequest;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeResolveResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The exchange catalogue, reachable only from the ingest gateway acting for a member (REQ-XCH-001,
 * REQ-XCH-018).
 */
@RestController
@RequestMapping("/api/v1/exchange/catalog")
@RequiredArgsConstructor
@Tag(name = "Exchange — catalogue", description = "Reference data for approved external clients")
public class ExchangeCatalogController {

  private final ExchangeCatalogService catalogService;
  private final ExchangeResolveService resolveService;

  /**
   * Lists the Lager's non-hidden locations.
   *
   * @return the locations
   */
  @NotNull
  @GetMapping("/locations")
  @PreAuthorize("@exchangeGate.allowsAny(authentication)")
  @Operation(
      summary = "Exchange: the Lager's locations",
      description =
          "Gateway-only. The non-hidden locations with their UEX city or space station, for any"
              + " exchange capability.")
  @ApiResponse(responseCode = "200", description = "The locations")
  @ApiResponse(responseCode = "403", description = "Not a relayed exchange request")
  public ResponseEntity<ExchangeLocationListDto> locations() {
    return ResponseEntity.ok(catalogService.locations());
  }

  /**
   * Resolves item references to catalogue entries.
   *
   * @param request the catalogue and up to 500 references
   * @return one result per reference, in request order
   */
  @NotNull
  @PostMapping("/resolve")
  @PreAuthorize("@exchangeGate.allowsAny(authentication)")
  @Operation(
      summary = "Exchange: resolve item references",
      description =
          "Gateway-only. Resolves each reference to one blueprint product, item, material or ship"
              + " type, to several candidates, or to nothing; blueprint names go through the web"
              + " import's matching.")
  @ApiResponse(responseCode = "200", description = "One result per reference")
  @ApiResponse(responseCode = "400", description = "The request does not validate")
  @ApiResponse(responseCode = "403", description = "Not a relayed exchange request")
  public ResponseEntity<ExchangeResolveResponse> resolve(
      @NotNull @Valid @RequestBody ExchangeResolveRequest request) {
    return ResponseEntity.ok(resolveService.resolve(request));
  }
}
