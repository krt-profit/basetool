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

package de.greluc.krt.profit.basetool.backend.operation.internal;

import de.greluc.krt.profit.basetool.backend.mission.api.MissionOperationSummary;
import de.greluc.krt.profit.basetool.backend.mission.api.OperationSummaryProvider;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Tells the mission module the name, status and owner of the operations its missions belong to. */
@Component
@RequiredArgsConstructor
public class OperationSummaryReader implements OperationSummaryProvider {

  private final OperationRepository operationRepository;

  @Override
  @Transactional(readOnly = true)
  public @NotNull Map<UUID, MissionOperationSummary> operations(@NotNull Collection<UUID> ids) {
    Map<UUID, MissionOperationSummary> byId = new HashMap<>();
    if (ids.isEmpty()) {
      return byId;
    }
    for (MissionOperationSummary summary : operationRepository.findSummaries(ids)) {
      byId.put(summary.id(), summary);
    }
    return byId;
  }
}
