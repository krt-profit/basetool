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

package de.greluc.krt.profit.basetool.backend.mission.internal;

import de.greluc.krt.profit.basetool.backend.inventory.api.EarmarkTargets;
import de.greluc.krt.profit.basetool.backend.inventory.api.MissionEarmark;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Tells the Lager the name and planned start of the missions its stock is earmarked to. */
@Component
@RequiredArgsConstructor
public class MissionEarmarkTargets implements EarmarkTargets.Missions {

  private final MissionRepository missionRepository;

  @Override
  @Transactional(readOnly = true)
  public @NotNull Map<UUID, MissionEarmark> missions(@NotNull Collection<UUID> ids) {
    Map<UUID, MissionEarmark> byId = new HashMap<>();
    if (ids.isEmpty()) {
      return byId;
    }
    for (MissionEarmark mission : missionRepository.findEarmarks(ids)) {
      byId.put(mission.id(), mission);
    }
    return byId;
  }
}
