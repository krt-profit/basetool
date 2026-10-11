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

import de.greluc.krt.profit.basetool.backend.mission.api.MissionDeletionObserver;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.repository.RefineryOrderRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Unlinks the refinery orders of a mission that is being deleted, so they survive as unassigned
 * orders. Each order is updated as an entity, so its row version and update timestamp move; no
 * audit row is recorded.
 */
@Component
@RequiredArgsConstructor
public class MissionRefineryOrderRelease implements MissionDeletionObserver {

  private final RefineryOrderRepository refineryOrderRepository;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public int beforeMissionDelete(@NotNull UUID missionId) {
    List<RefineryOrder> orders = refineryOrderRepository.findAllByMissionId(missionId);
    for (RefineryOrder order : orders) {
      order.setMissionId(null);
    }
    return orders.size();
  }
}
