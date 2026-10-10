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

import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncAuthorization;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicAuthorizer;
import de.greluc.krt.profit.basetool.backend.service.OwnerScopeService;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

/**
 * The job-order module's {@link LiveSyncTopicAuthorizer}: an Auftrag room follows the Auftrag
 * detail read, the queue room the queue capability.
 */
@Service
@RequiredArgsConstructor
public class JobOrderLiveSyncTopicAuthorizer implements LiveSyncTopicAuthorizer {

  private final OwnerScopeService ownerScopeService;
  private final JobOrderAccessPolicy jobOrderAccessPolicy;

  /**
   * Decides the Auftrag and the Auftrag-queue rooms.
   *
   * @return {@code JOB_ORDER} and {@code JOB_ORDER_QUEUE}
   */
  @Override
  @NotNull
  public Set<LiveSyncAuthorization> authorizations() {
    return Set.of(LiveSyncAuthorization.JOB_ORDER, LiveSyncAuthorization.JOB_ORDER_QUEUE);
  }

  /**
   * Asks {@code jobOrderAccessPolicy.canSeeJobOrder(id)} for an Auftrag room and {@code
   * ownerScopeService.canViewJobOrders()} for the queue room.
   *
   * @param topic a parsed Auftrag or Auftrag-queue topic
   * @return {@code true} if the room may be opened for the caller
   * @throws IllegalArgumentException if the topic's kind is not a job-order kind
   */
  @Override
  public boolean mayJoin(@NotNull LiveSyncTopic topic) {
    LiveSyncAuthorization kind = topic.topicClass().authorization();
    if (kind == LiveSyncAuthorization.JOB_ORDER) {
      return jobOrderAccessPolicy.canSeeJobOrder(topic.requiredResourceId());
    }
    if (kind == LiveSyncAuthorization.JOB_ORDER_QUEUE) {
      return ownerScopeService.canViewJobOrders();
    }
    throw new IllegalArgumentException("Not a job-order topic: " + topic.canonical());
  }
}
