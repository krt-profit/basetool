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

import de.greluc.krt.profit.basetool.backend.mission.api.MissionReferenceDto;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionReferences;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Tells other modules the name, status and planned start of the missions they link to. */
@Component
@RequiredArgsConstructor
public class MissionReferenceReader implements MissionReferences {

  private final MissionRepository missionRepository;

  @Override
  @Transactional(readOnly = true)
  public @NotNull Map<UUID, MissionReferenceDto> references(@NotNull Collection<UUID> ids) {
    Map<UUID, MissionReferenceDto> byId = new HashMap<>();
    if (ids.isEmpty()) {
      return byId;
    }
    for (MissionReferenceDto reference : missionRepository.findReferencesByIdIn(ids)) {
      byId.put(reference.id(), reference);
    }
    return byId;
  }
}
