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

import org.jetbrains.annotations.NotNull;

/**
 * The read API through which another module asks whether the current caller may see a mission; the
 * mission module implements it with the rule of mission detail access (REQ-MISSION-008).
 */
public interface MissionVisibility {

  /**
   * Checks whether the caller may read the mission and every ancestor of it.
   *
   * @param mission the loaded mission
   * @return {@code true} iff the caller may see the mission
   */
  boolean canSeeMission(@NotNull Mission mission);
}
