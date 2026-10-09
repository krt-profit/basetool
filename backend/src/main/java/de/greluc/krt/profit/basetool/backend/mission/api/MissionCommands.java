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
 * The writes another module may ask of the mission module (plan §5.3). Each command joins the
 * caller's transaction and refuses to run without one.
 */
public interface MissionCommands {

  /**
   * Clears the operation reference of every mission linked to the operation. No mission section
   * counter and no row version moves, and no audit row is recorded.
   *
   * @param operationId the operation whose missions are detached
   */
  void detachFromOperation(@NotNull UUID operationId);
}
