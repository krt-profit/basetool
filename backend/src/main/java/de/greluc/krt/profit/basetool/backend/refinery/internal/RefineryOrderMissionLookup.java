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

package de.greluc.krt.profit.basetool.backend.refinery.internal;

import de.greluc.krt.profit.basetool.backend.model.dto.MissionReferenceDto;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** The refinery module's lookup of an order's mission, memoised for the running transaction. */
public interface RefineryOrderMissionLookup {

  /**
   * Returns the reference of the mission an order is linked to; the first miss in a transaction
   * loads every mission the orders of the persistence context are linked to in one read.
   *
   * @param missionId the linked mission, or {@code null} for an unlinked order
   * @return the mission's reference, or {@code null} when the order is unlinked or the mission does
   *     not exist
   */
  @Nullable
  MissionReferenceDto mission(@Nullable UUID missionId);
}
