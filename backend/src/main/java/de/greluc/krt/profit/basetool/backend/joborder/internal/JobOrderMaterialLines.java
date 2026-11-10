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
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

/** Resolves material requirements and merges equal material-quality lines for an order edit. */
@Service
@RequiredArgsConstructor
public class JobOrderMaterialLines {
  private final MaterialRepository materialRepository;
  private final QualityTierService qualityTierService;

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
    record LineKey(UUID materialId, UUID tierId) {}

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
}
