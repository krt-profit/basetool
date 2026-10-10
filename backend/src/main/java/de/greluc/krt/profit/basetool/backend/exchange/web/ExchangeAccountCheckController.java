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

import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeAccountCheckService;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeAccountCheckDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeAccountCheckRequest;
import de.greluc.krt.profit.basetool.backend.platform.api.SubjectAuthentication;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The account check, reachable only from the ingest gateway acting for a member (REQ-XCH-031). */
@RestController
@RequestMapping("/api/v1/exchange/me/account-check")
@RequiredArgsConstructor
@Tag(name = "Exchange — account check", description = "Whether an RSI handle is the member's")
public class ExchangeAccountCheckController {

  /** Compares the handle with the member's profile. */
  private final ExchangeAccountCheckService accountCheckService;

  /**
   * Answers whether the handle is the one on the acting member's profile.
   *
   * @param request the handle the client saw
   * @param authentication the relayed acting member the gate admitted
   * @return {@code match}, {@code mismatch} or {@code unknown}; never the stored handle
   */
  @NotNull
  @PostMapping
  @PreAuthorize("@exchangeGate.allows('exchange.connect', authentication)")
  @Operation(
      summary = "Exchange: does this RSI handle belong to the member",
      description = "Gateway-only. The handle is compared, never returned, logged or stored.")
  @ApiResponse(responseCode = "200", description = "The answer")
  @ApiResponse(responseCode = "400", description = "Not an RSI handle")
  public ResponseEntity<ExchangeAccountCheckDto> check(
      @RequestBody @Valid @NotNull ExchangeAccountCheckRequest request,
      @NotNull Authentication authentication) {
    SubjectAuthentication caller = (SubjectAuthentication) authentication;
    return ResponseEntity.ok(
        new ExchangeAccountCheckDto(
            accountCheckService.check(UUID.fromString(caller.subject()), request.handle())));
  }
}
