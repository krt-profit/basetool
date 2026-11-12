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

package de.greluc.krt.profit.basetool.backend.joborder.web;

import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryQualityCorrectionOptionDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryQuantityCorrectionOptionDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialAttributionTargetDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialContributionHistoryDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialProvisionTargetDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.MaterialContributionHistory;
import de.greluc.krt.profit.basetool.backend.joborder.internal.MaterialProvisionCorrectionDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.MaterialProvisionCorrections;
import de.greluc.krt.profit.basetool.backend.joborder.internal.MaterialProvisionOptions;
import de.greluc.krt.profit.basetool.backend.kernel.QualityValue;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Exposes durable contribution history through the concrete order's common read permission. */
@RestController
@RequestMapping("/api/v1/orders/{jobOrderId}/claims")
@RequiredArgsConstructor
@Tag(
    name = "Material Contributions",
    description = "Current and historical material contribution facts")
public class MaterialContributionController {
  private final MaterialContributionHistory history;
  private final MaterialProvisionOptions options;
  private final MaterialProvisionCorrections corrections;

  /**
   * Reads outstanding promises and historical provision, reduction and correction facts.
   *
   * @param jobOrderId the readable order
   * @return retained active and ended contribution history
   */
  @GetMapping("/history")
  @Operation(summary = "Read material contribution history")
  @PreAuthorize("isAuthenticated() and @jobOrderAccessPolicy.canSeeJobOrder(#jobOrderId)")
  public List<MaterialContributionHistoryDto> history(@PathVariable UUID jobOrderId) {
    return history.read(jobOrderId);
  }

  /**
   * Lists authorized open targets for a declared new material provision.
   *
   * @param jobOrderId the concrete readable order
   * @param materialId the material declared in the stock action
   * @param quality the declared supplied quality
   * @return suitable open promises with their independent current versions
   */
  @GetMapping("/provision-options")
  @Operation(summary = "List permitted open material provision targets")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Matching permitted open promises"),
    @ApiResponse(responseCode = "400", description = "Invalid material or quality declaration"),
    @ApiResponse(responseCode = "403", description = "Concrete order read is forbidden")
  })
  @PreAuthorize("isAuthenticated() and @jobOrderAccessPolicy.canSeeJobOrder(#jobOrderId)")
  public List<MaterialProvisionTargetDto> provisionOptions(
      @PathVariable UUID jobOrderId,
      @RequestParam UUID materialId,
      @RequestParam @QualityValue int quality) {
    return options.provisionTargets(jobOrderId, materialId, quality);
  }

  /**
   * Corrects one explicitly false original provision fact with scoped observed versions.
   *
   * @param jobOrderId the concrete order
   * @param provisionId the retained provision
   * @param correction the truthful connected correction
   * @return refreshed contribution history
   */
  @PostMapping("/provisions/{provisionId}/correction")
  @Operation(
      operationId = "correctMaterialProvision",
      summary = "Correct a false material provision fact")
  @PreAuthorize(
      "isAuthenticated() and @materialProvisionOptions.mayCorrectProvision(#jobOrderId,"
          + " #provisionId)")
  public List<MaterialContributionHistoryDto> correct(
      @PathVariable UUID jobOrderId,
      @PathVariable UUID provisionId,
      @RequestBody @Valid MaterialProvisionCorrectionDto correction) {
    return corrections.correct(jobOrderId, provisionId, correction);
  }

  /**
   * Lists server-authorized existing targets of a historical attribution correction.
   *
   * @param jobOrderId concrete readable order
   * @param provisionId retained provision fact
   * @param suppliedQuality corrected original quality, or null for attribution-only choices
   * @return eligible existing active or ended contribution targets
   */
  @GetMapping("/provisions/{provisionId}/attribution-options")
  @Operation(summary = "List permitted historical attribution targets")
  @PreAuthorize("isAuthenticated() and @jobOrderAccessPolicy.canSeeJobOrder(#jobOrderId)")
  public List<MaterialAttributionTargetDto> attributionOptions(
      @PathVariable UUID jobOrderId,
      @PathVariable UUID provisionId,
      @RequestParam(required = false) @Nullable @QualityValue Integer suppliedQuality) {
    return options.attributionTargets(jobOrderId, provisionId, suppliedQuality);
  }

  /**
   * Lists only currently editable connected stock placements for a physical correction.
   *
   * @param jobOrderId concrete readable order
   * @param provisionId retained provision fact
   * @return labelled independently versioned current physical rows
   */
  @GetMapping("/provisions/{provisionId}/correction-options")
  @Operation(summary = "List permitted connected physical correction placements")
  @PreAuthorize("isAuthenticated() and @jobOrderAccessPolicy.canSeeJobOrder(#jobOrderId)")
  public List<InventoryQuantityCorrectionOptionDto> correctionOptions(
      @PathVariable UUID jobOrderId, @PathVariable UUID provisionId) {
    return options.quantityTargets(jobOrderId, provisionId);
  }

  /**
   * Lists permitted current stock portions for an optional original-quality correction.
   *
   * @param jobOrderId the concrete readable order
   * @param provisionId the retained provision fact
   * @return labeled current rows and their server-calculated affected quantities
   */
  @GetMapping("/provisions/{provisionId}/quality-options")
  @Operation(summary = "List permitted connected quality correction portions")
  @PreAuthorize("isAuthenticated() and @jobOrderAccessPolicy.canSeeJobOrder(#jobOrderId)")
  public List<InventoryQualityCorrectionOptionDto> qualityOptions(
      @PathVariable UUID jobOrderId, @PathVariable UUID provisionId) {
    return options.qualityTargets(jobOrderId, provisionId);
  }
}
