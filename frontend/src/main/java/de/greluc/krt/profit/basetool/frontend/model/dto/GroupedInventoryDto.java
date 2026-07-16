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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import java.util.List;

/**
 * Frontend mirror of the per-catalog-entry Lager roll-up. Each group breaks down into {@link
 * InventoryStackDto} stacks (one per stock identity), which in turn hold the individual append-only
 * entries, so the UI renders Material/Item → Stack → Entries. Catalog-discriminated since V220
 * (REQ-INV-029): a material group carries {@code material} with the quality aggregates, a game-item
 * group carries {@code gameItem} with {@code null} quality aggregates.
 */
public record GroupedInventoryDto(
    MaterialReferenceDto material,
    InventoryGameItemReferenceDto gameItem,
    Double totalAmount,
    Double averageQuality,
    Integer maxQuality,
    List<InventoryStackDto> stacks) {

  /**
   * Counts the distinct owning users across this group's stacks, for the grouped Lager row's
   * context line ("{n} Nutzer / {m} Stacks") on the squadron-wide {@code /inventory/all} view.
   *
   * @return the number of distinct users owning at least one stack of this catalog entry
   */
  public int userCount() {
    return (int)
        stacks.stream()
            .map(InventoryStackDto::user)
            .filter(java.util.Objects::nonNull)
            .map(UserReferenceDto::id)
            .distinct()
            .count();
  }
}
