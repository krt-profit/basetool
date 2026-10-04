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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncAuthorization;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicAuthorizer;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

/**
 * The refinery module's {@link LiveSyncTopicAuthorizer}: a refinery-order room opens for whoever
 * passes the gate of the Raffinerie-Order read.
 */
@Service
@RequiredArgsConstructor
public class RefineryLiveSyncTopicAuthorizer implements LiveSyncTopicAuthorizer {

  private final OwnerScopeService ownerScopeService;

  /**
   * Decides the refinery-order rooms.
   *
   * @return {@code REFINERY_ORDER}
   */
  @Override
  @NotNull
  public Set<LiveSyncAuthorization> authorizations() {
    return Set.of(LiveSyncAuthorization.REFINERY_ORDER);
  }

  /**
   * Asks {@code ownerScopeService.canSeeRefineryOrder(id)}.
   *
   * @param topic a parsed refinery-order topic
   * @return {@code true} if the room may be opened for the caller
   */
  @Override
  public boolean mayJoin(@NotNull LiveSyncTopic topic) {
    return ownerScopeService.canSeeRefineryOrder(topic.requiredResourceId());
  }
}
