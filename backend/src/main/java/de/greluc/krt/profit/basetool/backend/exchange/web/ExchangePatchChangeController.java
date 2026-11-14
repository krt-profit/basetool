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
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangePatchChangeService;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeHomeLocationChoiceRequest;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeHomeLocationChosenDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangePatchChangeDto;
import de.greluc.krt.profit.basetool.backend.platform.api.SubjectAuthentication;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
 * The patch change for an exchange client, reachable only from the ingest gateway: the latest patch
 * change with the member's home-location prompt (REQ-PATCH-050, REQ-PATCH-051), and the member's
 * choice (REQ-PATCH-052).
 */
@RestController
@RequestMapping("/api/v1/exchange/me/patch-change")
@RequiredArgsConstructor
@Tag(name = "Exchange — patch change", description = "The patch change and the home location")
public class ExchangePatchChangeController {

  /** Reads the patch change and makes the choice. */
  private final ExchangePatchChangeService patchChanges;

  /**
   * Reads the latest patch change and the member's home-location prompt.
   *
   * @param authentication the relayed acting member the gate admitted
   * @return the patch change, its options and the member's state with the locations on offer
   */
  @NotNull
  @GetMapping
  @PreAuthorize("@exchangeGate.allows('exchange.patch.read', authentication)")
  @Operation(
      summary = "Exchange: the patch change and my home location",
      description =
          "Gateway-only. The latest patch change with its kind, live time and options, every wipe"
              + " group with the keys of its catalogue types and the exceptions; the member's"
              + " state in the latest home-location phase, the chosen home location and when, the"
              + " curated home locations on offer and, while pending, what the relocation would"
              + " move per current location.")
  @ApiResponse(responseCode = "200", description = "The patch change and the member's prompt")
  public ResponseEntity<ExchangePatchChangeDto> patchChange(
      @NotNull Authentication authentication) {
    return ResponseEntity.ok(
        patchChanges.read(ExchangeCaller.of((SubjectAuthentication) authentication)));
  }

  /**
   * Makes the member's home-location choice and relocates the member.
   *
   * @param body the chosen home location
   * @param authentication the relayed acting member the gate admitted
   * @return the chosen home location and what moved
   */
  @NotNull
  @PostMapping("/home-location")
  @PreAuthorize("@exchangeGate.allows('exchange.patch.write', authentication)")
  @Operation(
      summary = "Exchange: choose my home location",
      description =
          "Gateway-only. The web's rules: only a curated home location on offer, once per member"
              + " and phase, final. Runs the member's relocation in this request. No open phase"
              + " asking the member answers 409 HOME_LOCATION_NOT_ASKED, a second choice 409"
              + " HOME_LOCATION_ALREADY_CHOSEN, a location not on offer 400"
              + " HOME_LOCATION_NOT_OFFERED.")
  @ApiResponse(responseCode = "200", description = "The chosen home location and what moved")
  @ApiResponse(responseCode = "400", description = "The location is not on offer")
  @ApiResponse(responseCode = "409", description = "No open phase asks the member, or chosen")
  public ResponseEntity<ExchangeHomeLocationChosenDto> chooseHomeLocation(
      @NotNull @Valid @RequestBody ExchangeHomeLocationChoiceRequest body,
      @NotNull Authentication authentication) {
    return ResponseEntity.ok(
        patchChanges.choose(
            ExchangeCaller.of((SubjectAuthentication) authentication), body.location()));
  }
}
