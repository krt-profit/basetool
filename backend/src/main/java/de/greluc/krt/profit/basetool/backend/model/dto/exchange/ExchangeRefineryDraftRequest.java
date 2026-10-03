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

package de.greluc.krt.profit.basetool.backend.model.dto.exchange;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * The refinery extract an exchange client stages as a draft, as the ingest gateway relays it after
 * checking it against {@code refinery-draft.schema.json} (REQ-XCH-019, REQ-XCH-038).
 *
 * <p>Its JSON shape and constraints are those of the web import's extract; it is a type of its own
 * so the frozen exchange route keeps its behaviour when the web import changes.
 *
 * @param schemaVersion contract version; must equal {@code 1}, checked in the service
 * @param tool producer name (provenance only)
 * @param toolVersion producer version (provenance only)
 * @param model VLM that produced the extract (provenance only)
 * @param generatedAt UTC instant of production (provenance only)
 * @param clientLanguage SC client language of the screenshots
 * @param orders extracted orders; only the first is processed
 */
public record ExchangeRefineryDraftRequest(
    @NotNull Integer schemaVersion,
    @Size(max = 100) String tool,
    @Size(max = 50) String toolVersion,
    @Size(max = 100) String model,
    Instant generatedAt,
    @Size(max = 16) String clientLanguage,
    @NotEmpty @Size(max = 5) List<@NotNull @Valid ExchangeRefineryDraftOrder> orders) {

  /**
   * One refinement order read from SETUP screenshots.
   *
   * @param panelType screen tab of the capture; only {@code SETUP} is accepted
   * @param quoted {@code false} when captured before GET QUOTE; {@code null} counts as quoted
   * @param layoutConfidence layout-parse confidence in {@code [0,1]}
   * @param rawLocationName refinery location verbatim
   * @param rawMethodName refining method verbatim
   * @param rawInManifestTotal panel {@code IN MANIFEST} total
   * @param rawToRefineTotal panel {@code TO REFINE} total
   * @param expenses total order cost in aUEC; {@code null} when unquoted
   * @param durationMinutes processing time in minutes
   * @param totalYieldScu PROCESSING-only figure; ignored
   * @param sourceImages screenshots the order was stitched from; 1 to 50
   * @param goods material rows in on-screen order; 1 to 100
   */
  public record ExchangeRefineryDraftOrder(
      @NotNull @Size(max = 32) String panelType,
      Boolean quoted,
      @DecimalMin("0.0") @DecimalMax("1.0") Double layoutConfidence,
      @Size(max = 255) String rawLocationName,
      @Size(max = 255) String rawMethodName,
      @PositiveOrZero Long rawInManifestTotal,
      @PositiveOrZero Long rawToRefineTotal,
      @PositiveOrZero @DecimalMax("1000000000.0") Double expenses,
      @PositiveOrZero Long durationMinutes,
      @PositiveOrZero Double totalYieldScu,
      @NotEmpty @Size(max = 50) List<@NotNull @Valid ExchangeRefineryDraftImage> sourceImages,
      @NotEmpty @Size(max = 100) List<@NotNull @Valid ExchangeRefineryDraftGood> goods) {}

  /**
   * One source screenshot of an extracted order.
   *
   * @param name screenshot file name on the member's machine
   * @param width capture width in pixels
   * @param height capture height in pixels
   * @param cropMode how the panel was isolated
   * @param capturedAt UTC capture instant, or {@code null} when unknown
   */
  public record ExchangeRefineryDraftImage(
      @NotNull @Size(max = 255) String name,
      @Positive Integer width,
      @Positive Integer height,
      @Size(max = 32) String cropMode,
      Instant capturedAt) {}

  /**
   * One material row of an extracted order.
   *
   * @param rowIndex on-screen position, top row = 0
   * @param rawMaterialName material name verbatim
   * @param quality SC QUALITY column; {@code null} becomes {@code 0}
   * @param inputQuantity SC QTY column
   * @param outputQuantity SC YIELD column; {@code null} when unquoted
   * @param refine SC REFINE toggle
   * @param confidence read confidence in {@code [0,1]}
   * @param sourceImage screenshot file name the row was read from (provenance only)
   */
  public record ExchangeRefineryDraftGood(
      @PositiveOrZero Integer rowIndex,
      @NotNull @Size(max = 255) String rawMaterialName,
      Integer quality,
      @NotNull @PositiveOrZero Integer inputQuantity,
      @PositiveOrZero Integer outputQuantity,
      @NotNull Boolean refine,
      @DecimalMin("0.0") @DecimalMax("1.0") Double confidence,
      @Size(max = 255) String sourceImage) {}
}
