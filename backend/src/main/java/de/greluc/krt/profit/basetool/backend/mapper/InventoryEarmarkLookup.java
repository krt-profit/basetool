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

package de.greluc.krt.profit.basetool.backend.mapper;

import de.greluc.krt.profit.basetool.backend.model.projection.JobOrderEarmark;
import de.greluc.krt.profit.basetool.backend.model.projection.MissionEarmark;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The Lager-side lookup of the earmark targets, memoised for the running transaction; the seam the
 * Lager mapper and services ask what they show of a mission or a job order (ADR-0047).
 */
public interface InventoryEarmarkLookup {

  /**
   * Returns a mission the Lager's stock is earmarked to; the first miss in a transaction loads
   * every mission the loaded Lager rows are earmarked to in one read.
   *
   * @param missionId the mission
   * @return the mission, or {@code null} when it does not exist
   */
  @Nullable
  MissionEarmark mission(@NotNull UUID missionId);

  /**
   * Returns a job order the Lager's stock is earmarked to; the first miss in a transaction loads
   * every job order the loaded Lager rows are earmarked to in one read.
   *
   * @param jobOrderId the job order
   * @return the job order, or {@code null} when it does not exist
   */
  @Nullable
  JobOrderEarmark jobOrder(@NotNull UUID jobOrderId);
}
