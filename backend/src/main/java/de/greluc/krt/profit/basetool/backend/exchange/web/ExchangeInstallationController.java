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

import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeInstallationService;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeInstallationDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeInstallationLabelRequest;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The calling installation, reachable only from the ingest gateway acting for a member
 * (REQ-XCH-007).
 */
@RestController
@RequestMapping("/api/v1/exchange/me/installation")
@RequiredArgsConstructor
@Tag(name = "Exchange — installation", description = "The calling installation of a client")
public class ExchangeInstallationController {

  private final ExchangeInstallationService installationService;

  /**
   * Returns the calling installation, creating it on first sight.
   *
   * @param authentication the relayed acting member the gate admitted
   * @return the installation with its opaque id
   */
  @NotNull
  @GetMapping
  @PreAuthorize("@exchangeGate.allows('exchange.connect', authentication)")
  @Operation(summary = "Exchange: the calling installation", description = "Gateway-only.")
  @ApiResponse(responseCode = "200", description = "The installation")
  public ResponseEntity<ExchangeInstallationDto> current(@NotNull Authentication authentication) {
    SubjectAuthentication caller = (SubjectAuthentication) authentication;
    return ResponseEntity.ok(
        installationService.current(
            caller.externalClient(),
            UUID.fromString(caller.subject()),
            caller.exchangeInstallationKey()));
  }

  /**
   * Labels the calling installation.
   *
   * @param request the label
   * @param authentication the relayed acting member the gate admitted
   * @return the installation with its opaque id
   */
  @NotNull
  @PostMapping
  @PreAuthorize("@exchangeGate.allows('exchange.connect', authentication)")
  @Operation(
      summary = "Exchange: label the calling installation",
      description = "Gateway-only. The label is never logged or audited.")
  @ApiResponse(responseCode = "200", description = "The labelled installation")
  @ApiResponse(responseCode = "400", description = "The label breaks the rule")
  public ResponseEntity<ExchangeInstallationDto> label(
      @RequestBody @Valid @NotNull ExchangeInstallationLabelRequest request,
      @NotNull Authentication authentication) {
    SubjectAuthentication caller = (SubjectAuthentication) authentication;
    return ResponseEntity.ok(
        installationService.label(
            caller.externalClient(),
            UUID.fromString(caller.subject()),
            caller.exchangeInstallationKey(),
            request.label()));
  }
}
