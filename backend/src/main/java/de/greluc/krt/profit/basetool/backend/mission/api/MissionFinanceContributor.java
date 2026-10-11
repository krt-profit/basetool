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

package de.greluc.krt.profit.basetool.backend.mission.api;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * What another module adds to a mission's finance totals (plan §5.3, §7.5 P3-8b); owned by the
 * mission module and implemented by the refinery module, whose linked orders add their profit and
 * their expenses. The mission module asks once per mission and never reads the contributor's rows.
 */
public interface MissionFinanceContributor {

  /**
   * Sums the contributor's figures for one mission.
   *
   * @param missionId the mission
   * @return the contribution; {@link MissionFinanceContribution#NONE} when the mission has none
   */
  @NotNull
  MissionFinanceContribution contributionTo(@NotNull UUID missionId);
}
