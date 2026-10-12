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

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * The read SPI through which the mission module learns what it shows of the operations its missions
 * belong to (plan §5.3, §7.5 P3-8a); owned by the mission module and implemented by the operation
 * module. The mission module asks per page, never per row.
 */
public interface OperationSummaryProvider {

  /**
   * Looks the operations up in one read.
   *
   * @param ids the operations
   * @return each existing operation by id; an unknown id is absent
   */
  @NotNull
  Map<UUID, MissionOperationSummary> operations(@NotNull Collection<UUID> ids);
}
