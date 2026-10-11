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

import de.greluc.krt.profit.basetool.backend.model.dto.MissionReferenceDto;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * The read API through which another module learns what it shows of a mission (plan §5.3, §7.5
 * P3-8b); implemented by the mission module. Callers ask per page, never per row.
 */
public interface MissionReferences {

  /**
   * Looks the missions up in one read.
   *
   * @param ids the missions
   * @return each existing mission's reference by id; an unknown id is absent
   */
  @NotNull
  Map<UUID, MissionReferenceDto> references(@NotNull Collection<UUID> ids);
}
