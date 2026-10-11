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

package de.greluc.krt.profit.basetool.frontend.kernel.backend;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.QualityTierDto;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/** Reads the cached quality-tier catalogue for the order pages' pickers (REQ-ORDERS-036). */
@Slf4j
@Service
@RequiredArgsConstructor
public class QualityTierCatalog {

  /** Decodes the catalogue endpoint's JSON array. */
  private static final ParameterizedTypeReference<List<QualityTierDto>> LIST_OF_TIERS =
      new ParameterizedTypeReference<>() {};

  /** Picker order: sort order, then floor. */
  private static final Comparator<QualityTierDto> PICKER_ORDER =
      Comparator.comparingInt(QualityTierDto::sortOrder)
          .thenComparingInt(QualityTierDto::minQuality);

  /** Fetches the catalogue through the frontend catalogue cache. */
  private final BackendApiClient backendApiClient;

  /**
   * Returns the whole catalogue, inactive tiers included, in picker order. The templates show an
   * inactive tier only where a requirement still uses it.
   *
   * @return the tiers; empty when the backend cannot be reached
   */
  @NotNull
  public List<QualityTierDto> all() {
    try {
      List<QualityTierDto> tiers =
          backendApiClient.getCached(CachedCatalog.QUALITY_TIERS, LIST_OF_TIERS);
      return tiers == null ? List.of() : tiers.stream().sorted(PICKER_ORDER).toList();
    } catch (RuntimeException e) {
      log.warn("Quality-tier catalogue unavailable: {}", e.getMessage());
      return List.of();
    }
  }
}
