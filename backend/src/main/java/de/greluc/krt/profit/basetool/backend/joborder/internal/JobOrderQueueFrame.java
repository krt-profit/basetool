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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/** The {@code orders/queue} frame a created order or a lifecycle change announces. */
@Component
@RequiredArgsConstructor
class JobOrderQueueFrame {

  private final LiveSyncPublisher liveSyncPublisher;

  /**
   * Refreshes the order overview after the commit, and the material demand overview for the stock
   * kinds; an aUEC order adds no material demand.
   *
   * @param order the created or changed order
   */
  void publish(@NotNull JobOrder order) {
    liveSyncPublisher.publishAfterCommit(
        LiveSyncTopic.global(LiveSyncTopicClass.ORDERS_QUEUE),
        order.getType() == JobOrderType.AUEC ? List.of("queue") : List.of("queue", "demand"));
  }
}
