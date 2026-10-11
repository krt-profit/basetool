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

package de.greluc.krt.profit.basetool.backend.refinery.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * The refinery orders of missions as the operation's finance and payout views read them (plan
 * §7.6). Reads run in the caller's transaction and apply no scope: the caller has authorised the
 * operation.
 */
public interface RefineryOrderFinanceReader {

  /**
   * Loads the orders linked to any of the missions with their figures and DTOs.
   *
   * @param missionIds the missions to read
   * @return one line per order, in the repository's order
   */
  @NotNull
  List<RefineryOrderLine> linesOfMissions(@NotNull List<UUID> missionIds);

  /**
   * Loads the orders linked to one mission with their figures and DTOs.
   *
   * @param missionId the mission to read
   * @return one line per order, in the repository's order
   */
  @NotNull
  List<RefineryOrderLine> linesOfMission(@NotNull UUID missionId);

  /**
   * Loads the money of the orders linked to any of the missions, without building their DTOs.
   *
   * @param missionIds the missions to read
   * @return the figures of every order, in the repository's order
   */
  @NotNull
  List<RefineryOrderFigures> figuresOfMissions(@NotNull List<UUID> missionIds);

  /**
   * Sums the profit of the orders per mission in one grouped query.
   *
   * @param missionIds the missions to sum
   * @return the profit by mission id; a mission without orders has no entry
   */
  @NotNull
  Map<UUID, Double> profitByMission(@NotNull List<UUID> missionIds);
}
