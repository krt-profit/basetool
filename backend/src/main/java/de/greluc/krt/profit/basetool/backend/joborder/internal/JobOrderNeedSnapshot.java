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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.kernel.AbstractEntity;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;

/**
 * Captures the editable order facts that determine whether an update changes current work.
 *
 * @param requester the requesting unit
 * @param handle the contact handle
 * @param comment the order comment
 * @param materials remaining MATERIAL need, independent of regenerated line ids
 * @param items requested ITEM lines with persistent identities
 */
record JobOrderNeedSnapshot(
    @Nullable UUID requester,
    @Nullable String handle,
    @Nullable String comment,
    Set<MaterialNeed> materials,
    Set<ItemNeed> items) {

  private record MaterialNeed(UUID material, UUID quality, Double quantity) {}

  private record ItemNeed(
      UUID line,
      UUID item,
      UUID blueprint,
      @Nullable UUID parent,
      Integer quantity,
      Set<MaterialNeed> recipe) {

    private static ItemNeed of(JobOrderItem line) {
      Set<MaterialNeed> recipe =
          line.getMaterials().stream()
              .map(material ->
                  new MaterialNeed(
                      material.getMaterial().getId(),
                      material.getQualityTier().getId(),
                      material.getRequiredQuantity()))
              .collect(Collectors.toUnmodifiableSet());
      return new ItemNeed(
          line.getId(),
          line.getGameItem().getId(),
          line.getBlueprint().getId(),
          id(line.getParentItem()),
          line.getAmount(),
          recipe);
    }
  }

  static JobOrderNeedSnapshot capture(JobOrder order) {
    Set<MaterialNeed> materials =
        order.getMaterials().stream()
            .map(line ->
                new MaterialNeed(
                    line.getMaterial().getId(), line.getQualityTier().getId(), line.getAmount()))
            .collect(Collectors.toUnmodifiableSet());
    Set<ItemNeed> items =
        order.getItems().stream().map(ItemNeed::of).collect(Collectors.toUnmodifiableSet());
    return new JobOrderNeedSnapshot(
        id(order.getRequestingOrgUnit()), order.getHandle(), order.getComment(), materials, items);
  }

  private static @Nullable UUID id(@Nullable AbstractEntity<UUID> value) {
    return value == null ? null : value.getId();
  }
}
