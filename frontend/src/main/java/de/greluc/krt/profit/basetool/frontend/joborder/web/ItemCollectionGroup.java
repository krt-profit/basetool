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

import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderItemStockEntryDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderItemStockGroupDto;
import java.util.List;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * One game item's group row on the item collection page with its collection progress
 * (REQ-ORDERS-031, REQ-UI-027).
 *
 * @param stock the earmarked stock of one game item
 * @param progress the delivered share of the group's earmarked whole units
 */
public record ItemCollectionGroup(
    @NotNull JobOrderItemStockGroupDto stock, @NotNull CollectionProgress progress) {

  /**
   * Builds the group rows with the delivered share of each group's allocated units.
   *
   * @param stock the earmarked stock per game item, in display order
   * @return one group per element of {@code stock}, in the same order
   */
  @NotNull
  @Unmodifiable
  @Contract(pure = true)
  public static List<ItemCollectionGroup> of(@NotNull List<JobOrderItemStockGroupDto> stock) {
    return stock.stream()
        .map(
            group ->
                new ItemCollectionGroup(
                    group,
                    CollectionProgress.of(
                        group.entries() == null ? List.of() : group.entries(),
                        JobOrderItemStockEntryDto::allocatedQuantity,
                        JobOrderItemStockEntryDto::delivered)))
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
  public static CollectionProgress total(@NotNull List<ItemCollectionGroup> groups) {
    return groups.stream()
        .map(ItemCollectionGroup::progress)
        .reduce(new CollectionProgress(0, 0), CollectionProgress::plus);
  }
}
