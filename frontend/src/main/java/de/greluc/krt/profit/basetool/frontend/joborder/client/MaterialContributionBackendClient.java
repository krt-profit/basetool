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

package de.greluc.krt.profit.basetool.frontend.joborder.client;

import de.greluc.krt.profit.basetool.frontend.inventory.model.InventoryQualityCorrectionOptionDto;
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventoryQuantityCorrectionOptionDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.MaterialAttributionTargetDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.MaterialContributionHistoryDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.MaterialProvisionCorrectionDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.MaterialProvisionTargetDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/** Typed contribution history and correction calls through the shared backend transport. */
@Service
@RequiredArgsConstructor
public class MaterialContributionBackendClient {
  /** Retained current and ended material contribution facts. */
  private static final ParameterizedTypeReference<List<MaterialContributionHistoryDto>>
      LIST_OF_CONTRIBUTION_HISTORY = new ParameterizedTypeReference<>() {};

  /** Authorized historical attribution targets. */
  private static final ParameterizedTypeReference<List<MaterialAttributionTargetDto>>
      LIST_OF_ATTRIBUTION_TARGET = new ParameterizedTypeReference<>() {};

  /** Connected physical rows whose current Inventory grants permit correction. */
  private static final ParameterizedTypeReference<List<InventoryQuantityCorrectionOptionDto>>
      LIST_OF_QUANTITY_OPTION = new ParameterizedTypeReference<>() {};

  /** Editable current placements with their receipt-known quality correction quantities. */
  private static final ParameterizedTypeReference<List<InventoryQualityCorrectionOptionDto>>
      LIST_OF_QUALITY_OPTION = new ParameterizedTypeReference<>() {};

  /** Authorized open targets for an explicitly declared fresh material provision. */
  private static final ParameterizedTypeReference<List<MaterialProvisionTargetDto>>
      LIST_OF_PROVISION_TARGET = new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Reads current and ended facts through the backend's order-specific read authorization.
   *
   * @param id the concrete readable order
   * @return retained contributions, or null when the backend sends no body
   */
  @Nullable
  public List<MaterialContributionHistoryDto> contributionHistory(@NotNull UUID id) {
    return backendApiClient.get(
        "/api/v1/orders/{id}/claims/history", LIST_OF_CONTRIBUTION_HISTORY, id);
  }

  /**
   * Reads authoritative open promises suitable for the ordinary stock action's declaration.
   *
   * @param id the concrete readable order
   * @param materialId the declared material, without requiring an existing stock row
   * @param quality its declared supplied quality
   * @return permitted matching open targets, or null when no response body was sent
   */
  @Nullable
  public List<MaterialProvisionTargetDto> provisionOptions(
      @NotNull UUID id, @NotNull UUID materialId, int quality) {
    return backendApiClient.get(
        "/api/v1/orders/{id}/claims/provision-options?materialId={materialId}&quality={quality}",
        LIST_OF_PROVISION_TARGET,
        id,
        materialId,
        quality);
  }

  /**
   * Corrects a false retained provision through current backend authority and scoped versions.
   *
   * @param id the order
   * @param creditId the provision
   * @param correction the truthful connected facts
   * @return refreshed contribution history, or null when no response body was sent
   */
  @Nullable
  public List<MaterialContributionHistoryDto> correctProvision(
      @NotNull UUID id,
      @NotNull UUID creditId,
      @NotNull MaterialProvisionCorrectionDto correction) {
    var result =
        backendApiClient.post(
            "/api/v1/orders/{id}/claims/provisions/{creditId}/correction",
            correction,
            MaterialContributionHistoryDto[].class,
            id,
            creditId);
    return result == null ? null : List.of(result);
  }

  /**
   * Reads existing historical contribution targets authorized for this original provision.
   *
   * @param id the readable order
   * @param creditId the original provision
   * @return permitted existing targets, including ended contributions
   */
  @Nullable
  public List<MaterialAttributionTargetDto> attributionOptions(
      @NotNull UUID id, @NotNull UUID creditId) {
    return backendApiClient.get(
        "/api/v1/orders/{id}/claims/provisions/{creditId}/attribution-options",
        LIST_OF_ATTRIBUTION_TARGET,
        id,
        creditId);
  }

  /**
   * Reads current permitted contribution targets for a corrected original quality.
   *
   * @param id the readable order
   * @param creditId the retained provision
   * @param suppliedQuality the corrected original quality, or null for attribution-only choices
   * @return authoritative targets including the eligible current attribution
   */
  @Nullable
  public List<MaterialAttributionTargetDto> attributionOptions(
      @NotNull UUID id, @NotNull UUID creditId, @Nullable Integer suppliedQuality) {
    if (suppliedQuality == null) {
      return attributionOptions(id, creditId);
    }
    return backendApiClient.get(
        "/api/v1/orders/{id}/claims/provisions/{creditId}/attribution-options"
            + "?suppliedQuality={suppliedQuality}",
        LIST_OF_ATTRIBUTION_TARGET,
        id,
        creditId,
        suppliedQuality);
  }

  /**
   * Reads connected stock rows after current Inventory correction grants are checked.
   *
   * @param id the order
   * @param creditId the original provision
   * @return permitted retained physical rows
   */
  @Nullable
  public List<InventoryQuantityCorrectionOptionDto> quantityCorrectionOptions(
      @NotNull UUID id, @NotNull UUID creditId) {
    return backendApiClient.get(
        "/api/v1/orders/{id}/claims/provisions/{creditId}/correction-options",
        LIST_OF_QUANTITY_OPTION,
        id,
        creditId);
  }

  /**
   * Reads editable current placements without disclosing inaccessible stock rows.
   *
   * @param id the readable order
   * @param creditId the retained provision
   * @return current row versions and their exact receipt-known affected quantities
   */
  @Nullable
  public List<InventoryQualityCorrectionOptionDto> qualityCorrectionOptions(
      @NotNull UUID id, @NotNull UUID creditId) {
    return backendApiClient.get(
        "/api/v1/orders/{id}/claims/provisions/{creditId}/quality-options",
        LIST_OF_QUALITY_OPTION,
        id,
        creditId);
  }
}
