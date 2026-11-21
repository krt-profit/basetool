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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTier;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierService;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

/**
 * Resolves material requirements, merges equal material-quality lines and reconciles an order's
 * lines with an edit in place (REQ-ORDERS-036, ADR-0400).
 */
@Service
@RequiredArgsConstructor
public class JobOrderMaterialLines {
  /** A line may not be requested below the amount already handed over against it. */
  static final String BELOW_HANDED_OVER = "error.job_order.material_below_handed_over";

  /** A line with handed-over credit may not be removed or moved to another tier. */
  static final String HANDED_OVER_LINE_REQUIRED = "error.job_order.material_line_handed_over";

  private final MaterialRepository materialRepository;
  private final QualityTierService qualityTierService;

  /** Identifies a requirement line by its material and its quality tier. */
  private record LineKey(UUID materialId, UUID tierId) {
    static LineKey of(@NotNull JobOrderMaterial line) {
      return new LineKey(line.getMaterial().getId(), line.getQualityTier().getId());
    }
  }

  /**
   * Builds the material lines of a {@code MATERIAL} order, resolving each line's quality tier and
   * merging lines that name the same material at the same tier into one (REQ-ORDERS-036).
   *
   * @param materials the payload lines
   * @param keptTierIds tiers the order already used, which stay allowed when inactive
   * @return one detached line per {@code (material, tier)}, in payload order
   * @throws NotFoundException when a material does not exist
   * @throws BadRequestException when a tier is unknown, or inactive and not kept
   */
  @NotNull
  public List<JobOrderMaterial> build(
      @NotNull List<CreateJobOrderMaterialDto> materials, @NotNull Set<UUID> keptTierIds) {
    Map<LineKey, JobOrderMaterial> lines = new LinkedHashMap<>();
    for (CreateJobOrderMaterialDto matDto : materials) {
      Material material =
          Entities.require(
              materialRepository.findById(matDto.materialId()),
              () -> "Material not found: " + matDto.materialId());
      QualityTier tier =
          qualityTierService.resolveForRequirement(null, matDto.minQuality(), keptTierIds);
      LineKey key = new LineKey(material.getId(), tier.getId());
      JobOrderMaterial existing = lines.get(key);
      if (existing == null) {
        lines.put(
            key,
            JobOrderMaterial.builder()
                .material(material)
                .qualityTier(tier)
                .amount(matDto.amount())
                .build());
      } else {
        existing.setAmount(existing.getAmount() + matDto.amount());
      }
    }
    return new ArrayList<>(lines.values());
  }

  /**
   * Brings the order's material lines to the edited requested amounts in place, so each kept line
   * keeps its identity and its handed-over credit.
   *
   * <p>A line whose {@code (material, tier)} the payload names again takes the payload amount as
   * its requested amount; a line the payload no longer names is removed; a new pair becomes a new
   * line. When several lines share a pair, the one with the smallest id is the match.
   *
   * @param order the managed {@code MATERIAL} order
   * @param materials the payload lines with their requested amounts
   * @throws NotFoundException when a material does not exist
   * @throws BadRequestException when a tier is unknown or inactive and not kept, when a line would
   *     be requested below its handed-over amount, or when a line with handed-over credit would be
   *     removed or moved to another tier
   */
  public void reconcile(
      @NotNull JobOrder order, @NotNull List<CreateJobOrderMaterialDto> materials) {
    Set<UUID> keptTierIds = new LinkedHashSet<>();
    order.getMaterials().forEach(line -> keptTierIds.add(line.getQualityTier().getId()));
    Map<LineKey, JobOrderMaterial> desired = new LinkedHashMap<>();
    build(materials, keptTierIds).forEach(line -> desired.put(LineKey.of(line), line));

    List<JobOrderMaterial> existing =
        order.getMaterials().stream()
            .sorted(Comparator.comparing(JobOrderMaterial::getId))
            .toList();
    for (JobOrderMaterial line : existing) {
      JobOrderMaterial target = desired.remove(LineKey.of(line));
      if (target != null) {
        requestAtLeastHandedOver(line, target.getAmount());
        line.setAmount(target.getAmount());
      } else if (line.getCreditedAmount() > 0) {
        throw new BadRequestException(HANDED_OVER_LINE_REQUIRED);
      } else {
        order.getMaterials().remove(line);
      }
    }
    desired.values().forEach(order::addMaterial);
  }

  private static void requestAtLeastHandedOver(@NotNull JobOrderMaterial line, double requested) {
    if (ScuAmounts.toQuanta(requested) < ScuAmounts.toQuanta(line.getCreditedAmount())) {
      throw new BadRequestException(BELOW_HANDED_OVER);
    }
  }
}
