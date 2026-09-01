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
 * Normalises one job order — of either kind — into its outstanding material requirement buckets,
 * the shape every cross-order material read is built on.
 *
 * <p>Extracted from {@link JobOrderMaterialDemandService} (#1740) so the check-in allocation
 * picker's per-order need figures (REQ-INV-039) are folded from the <em>same</em> normalisation the
 * cross-order demand overview (REQ-ORDERS-034) uses. A second implementation would drift: the two
 * kinds reduce their requirements differently, and the difference is exactly where a "still needed"
 * figure is easy to get wrong.
 *
 * <p>Read-only and side-effect free — it maps and reads a managed order's requirement branches, so
 * every caller must already be inside a transaction with those branches reachable.
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
   * Normalises one order into its material buckets, hiding the two kinds' different shapes from the
   * callers. A {@code MATERIAL} order contributes its material lines directly (their {@code amount}
   * is already the outstanding requirement — {@code JobOrderHandoverService} decrements the line in
   * place on a handover); an {@code ITEM} order contributes the blueprint-derived aggregation,
   * which already scales each line by its not-yet-manufactured share. Neither is adjusted again
   * here, so no reduction is applied twice.
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
      // A MATERIAL line's bucket quality mirrors aggregateMaterials(): a stored 650-floor is GOOD,
      // "Keine" (null minQuality) is NONE — so both kinds land in the same bucket for one material.
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
