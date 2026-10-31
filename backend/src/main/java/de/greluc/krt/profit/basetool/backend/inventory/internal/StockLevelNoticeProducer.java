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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import de.greluc.krt.profit.basetool.backend.inventory.api.events.InventoryNotices;
import de.greluc.krt.profit.basetool.backend.inventory.internal.StockLevelStates.State;
import de.greluc.krt.profit.basetool.backend.notification.api.TimedNoticeProducer;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Tells a unit once when one of its minimum stock levels turns low (REQ-NOTIF-030). The level's
 * marker is set by an atomic update that leaves its version alone, only the caller whose update
 * changed the row publishes, and the marker is cleared once the allocated stock has reached the
 * target again, so stock swinging around the minimum raises one notice.
 */
@Component
@Order(6)
@RequiredArgsConstructor
class StockLevelNoticeProducer implements TimedNoticeProducer {

  /** The producer kind, the {@code kind} label of the produced-notices counter. */
  static final String KIND = "stock_level_low";

  private final StockLevelStates states;
  private final StockLevelRepository stockLevelRepository;
  private final ApplicationEventPublisher eventPublisher;

  @Override
  @NotNull
  public String kind() {
    return KIND;
  }

  @Override
  public int produce(@NotNull Instant now, int limit) {
    int raised = 0;
    for (State state : states.ofActiveLevels()) {
      StockLevel level = state.level();
      if (level.getLowNotifiedAt() != null) {
        if (state.fill().allocated() >= level.getTarget()) {
          stockLevelRepository.clearLowNotified(level.getId());
        }
      } else if (raised < limit
          && state.fill().low()
          && stockLevelRepository.markLowNotified(level.getId(), now) == 1) {
        eventPublisher.publishEvent(InventoryNotices.stockLevelLow(noticeOf(state)));
        raised++;
      }
    }
    return raised;
  }

  private static InventoryNotices.StockLevelLow noticeOf(State state) {
    StockLevel level = state.level();
    return new InventoryNotices.StockLevelLow(
        level.getId(),
        level.getOrgUnit().getId(),
        level.getOrgUnit().getKind(),
        level.getOrgUnit().getName(),
        level.subjectName(),
        level.getQualityTier() == null ? null : level.getQualityTier().getCode(),
        level.getMinimum(),
        state.fill().allocated());
  }
}
