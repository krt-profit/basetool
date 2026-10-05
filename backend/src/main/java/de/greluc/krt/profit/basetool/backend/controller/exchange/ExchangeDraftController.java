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

import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportPreviewDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryImportDraftDto;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeBlueprintDraftDto;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeRefineryDraftRequest;
import de.greluc.krt.profit.basetool.backend.platform.api.SubjectAuthentication;
import de.greluc.krt.profit.basetool.backend.service.exchange.ExchangeDraftService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The drafts an exchange client stages for the member's review in the browser, reachable only from
 * the ingest gateway (REQ-XCH-019).
 */
@RestController
@RequestMapping("/api/v1/exchange/me/drafts")
@RequiredArgsConstructor
@Tag(
    name = "Exchange — drafts",
    description = "Drafts the member reviews before anything is written")
public class ExchangeDraftController {

  /** Builds the drafts. */
  private final ExchangeDraftService draftService;

  /**
   * Previews blueprints for the member's review; nothing is written.
   *
   * @param draft the {@code basetool.blueprints} envelope
   * @param authentication the relayed acting member the gate admitted
   * @return the preview the browser's import review shows
   */
  @NotNull
  @PostMapping(value = "/blueprints", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@exchangeGate.allows('exchange.drafts.blueprints', authentication)")
  @Operation(
      summary = "Exchange: preview blueprints for review",
      description = "Gateway-only. Resolves the references and previews them as an upload would.")
  @ApiResponse(responseCode = "200", description = "The preview")
  public ResponseEntity<BlueprintImportPreviewDto> blueprints(
      @NotNull @Valid @RequestBody ExchangeBlueprintDraftDto draft,
      @NotNull Authentication authentication) {
    return ResponseEntity.ok(draftService.blueprints(member(authentication), draft));
  }

  /**
   * Builds a refinery draft for the member's review; nothing is written.
   *
   * @param extract the refinery extract
   * @param authentication the relayed acting member the gate admitted
   * @return the draft the browser's create form opens with
   */
  @NotNull
  @PostMapping(value = "/refinery-orders", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@exchangeGate.allows('exchange.drafts.refinery', authentication)")
  @Operation(
      summary = "Exchange: build a refinery draft for review",
      description = "Gateway-only. The same draft the extractor's upload builds.")
  @ApiResponse(responseCode = "200", description = "The draft")
  @ApiResponse(responseCode = "400", description = "Unsupported schema version or panel type")
  public ResponseEntity<RefineryImportDraftDto> refineryOrders(
      @NotNull @Valid @RequestBody ExchangeRefineryDraftRequest extract,
      @NotNull Authentication authentication) {
    return ResponseEntity.ok(draftService.refinery(member(authentication), extract));
  }

  /**
   * Reads the member the gate admitted.
   *
   * @param authentication the relayed acting member
   * @return the member's id
   */
  private static @NotNull UUID member(@NotNull Authentication authentication) {
    return UUID.fromString(((SubjectAuthentication) authentication).subject());
  }
}
