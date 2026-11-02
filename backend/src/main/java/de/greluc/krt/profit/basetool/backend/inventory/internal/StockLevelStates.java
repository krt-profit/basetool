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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import de.greluc.krt.profit.basetool.backend.inventory.internal.StockLevelAllocation.Fill;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Reads every level of an active unit and catalogue entry together with its allocation, whoever may
 * see it, for the low-stock notice and the low-levels gauge (REQ-NOTIF-030, REQ-OBS-011).
 */
@Component
@RequiredArgsConstructor
class StockLevelStates {

  private final StockLevelRepository stockLevelRepository;
  private final StockLevelFigures figures;

  /**
   * A level with the figures of its allocation.
   *
   * @param level the level, unit, catalogue entry and tier loaded
   * @param fill the allocated stock and whether the level is low
   */
  record State(@NotNull StockLevel level, @NotNull Fill fill) {}

  /**
   * Allocates the pool stock over the levels of every active unit and listed catalogue entry.
   *
   * @return one state per level; levels of inactive units or delisted entries are not among them
   */
  @NotNull
  List<State> ofActiveLevels() {
    List<StockLevel> levels = stockLevelRepository.findAllActive();
    Map<UUID, Fill> fills = figures.fillsOf(levels);
    return levels.stream()
        .map(level -> new State(level, Objects.requireNonNull(fills.get(level.getId()))))
        .toList();
  }
}
