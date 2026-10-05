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

import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncAuthorization;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicAuthorizer;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

/**
 * The operation module's {@link LiveSyncTopicAuthorizer}: an operation room opens for whoever
 * passes the gate of the Operation detail read.
 */
@Service
@RequiredArgsConstructor
public class OperationLiveSyncTopicAuthorizer implements LiveSyncTopicAuthorizer {

  private final OperationAccessPolicy operationAccessPolicy;

  /**
   * Decides the operation rooms.
   *
   * @return {@code OPERATION}
   */
  @Override
  @NotNull
  public Set<LiveSyncAuthorization> authorizations() {
    return Set.of(LiveSyncAuthorization.OPERATION);
  }

  /**
   * Asks {@code operationAccessPolicy.canSeeOperation(id)}.
   *
   * @param topic a parsed operation topic
   * @return {@code true} if the room may be opened for the caller
   */
  @Override
  public boolean mayJoin(@NotNull LiveSyncTopic topic) {
    return operationAccessPolicy.canSeeOperation(topic.requiredResourceId());
  }
}
