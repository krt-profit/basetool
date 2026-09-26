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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.mapper.MaterialMapper;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.model.JobOrderType;
import de.greluc.krt.profit.basetool.backend.model.QualityRequirement;
import de.greluc.krt.profit.basetool.backend.model.dto.AggregatedMaterialDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialDto;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

/**
 * Normalises a job order of either kind into its outstanding material requirement buckets, shared
 * by the demand overview (REQ-ORDERS-034) and the allocation pickers (REQ-INV-039).
 *
 * <p>Read-only; callers must be inside a transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobOrderMaterialRequirementResolver {

  /** Normalises an item order's blueprint-derived requirements into material buckets. */
  private final JobOrderItemService jobOrderItemService;

  /** Maps a {@code MATERIAL} line's material entity to its DTO. */
  private final MaterialMapper materialMapper;

  /**
   * Normalises one order into its material buckets: a {@code MATERIAL} order's lines as they are,
   * an {@code ITEM} order's blueprint-derived outstanding aggregation.
   *
   * @param order the managed order to normalise.
   * @return its buckets; empty for an order with no requirements, never {@code null}.
   */
  @NotNull
  public List<MaterialRequirement> requirementsOf(@NotNull JobOrder order) {
    if (order.getType() == JobOrderType.ITEM) {
      List<MaterialRequirement> requirements = new ArrayList<>();
      for (AggregatedMaterialDto aggregated : jobOrderItemService.aggregateMaterials(order)) {
        if (aggregated.material() == null) {
          continue;
        }
        requirements.add(
            new MaterialRequirement(
                aggregated.material(),
                aggregated.qualityRequirement(),
                aggregated.totalQuantity() == null ? 0.0 : aggregated.totalQuantity()));
      }
      return requirements;
    }
    List<MaterialRequirement> requirements = new ArrayList<>();
    for (JobOrderMaterial line : order.getMaterials()) {
      if (line.getMaterial() == null) {
        continue;
      }
      QualityRequirement quality =
          line.getMinQuality() != null ? QualityRequirement.GOOD : QualityRequirement.NONE;
      requirements.add(
          new MaterialRequirement(
              materialMapper.toDto(line.getMaterial()),
              quality,
              line.getAmount() == null ? 0.0 : line.getAmount()));
    }
    return requirements;
  }

  /**
   * One order's normalised material bucket, the shape both order kinds are reduced to before they
   * are aggregated or projected.
   *
   * @param material the bucket's material
   * @param quality the bucket's quality requirement
   * @param requiredAmount the order's outstanding requirement for the bucket
   */
  public record MaterialRequirement(
      MaterialDto material, QualityRequirement quality, double requiredAmount) {}
}
