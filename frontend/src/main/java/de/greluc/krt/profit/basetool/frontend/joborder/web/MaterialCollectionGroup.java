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

package de.greluc.krt.profit.basetool.frontend.joborder.web;

import de.greluc.krt.profit.basetool.frontend.joborder.model.MaterialCollectionEntryDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * One material's group row on the material collection page: its entries and their collection
 * progress (REQ-UI-027).
 *
 * @param materialName the material's display name; {@code null} for entries without one
 * @param entries the material's earmarked entries, in backend order
 * @param progress the delivered share of the group's earmarked amount
 */
public record MaterialCollectionGroup(
    @Nullable String materialName,
    @NotNull List<MaterialCollectionEntryDto> entries,
    @NotNull CollectionProgress progress) {

  /**
   * Groups the entries by material name, in the order each material first appears.
   *
   * @param entries the order's earmarked entries
   * @return one group per material
   */
  @NotNull
  @Unmodifiable
  @Contract(pure = true)
  public static List<MaterialCollectionGroup> of(
      @NotNull List<MaterialCollectionEntryDto> entries) {
    Map<String, List<MaterialCollectionEntryDto>> byMaterial = new LinkedHashMap<>();
    for (MaterialCollectionEntryDto entry : entries) {
      byMaterial.computeIfAbsent(entry.materialName(), _ -> new ArrayList<>()).add(entry);
    }
    return byMaterial.entrySet().stream()
        .map(
            group ->
                new MaterialCollectionGroup(
                    group.getKey(),
                    List.copyOf(group.getValue()),
                    CollectionProgress.of(
                        group.getValue(),
                        MaterialCollectionEntryDto::allocatedQuantity,
                        MaterialCollectionEntryDto::delivered)))
        .toList();
  }

  /**
   * Adds up the progress of all groups.
   *
   * @param groups the group rows
   * @return the delivered share over every group
   */
  @NotNull
  @Contract(pure = true)
  public static CollectionProgress total(@NotNull List<MaterialCollectionGroup> groups) {
    return groups.stream()
        .map(MaterialCollectionGroup::progress)
        .reduce(new CollectionProgress(0, 0), CollectionProgress::plus);
  }
}
