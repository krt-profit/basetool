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

package de.greluc.krt.profit.basetool.backend.hangar.internal;

import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncAuthorization;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicAuthorizer;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

/**
 * The hangar module's {@link LiveSyncTopicAuthorizer}: a unit's hangar room opens for whoever sees
 * that unit's ships in the unit overview (REQ-HANGAR-003, ADR-0260).
 */
@Service
@RequiredArgsConstructor
public class HangarUnitLiveSyncTopicAuthorizer implements LiveSyncTopicAuthorizer {

  private final HangarAccessPolicy hangarAccessPolicy;

  /**
   * Decides the unit hangar rooms.
   *
   * @return {@code HANGAR_UNIT}
   */
  @Override
  @NotNull
  public Set<LiveSyncAuthorization> authorizations() {
    return Set.of(LiveSyncAuthorization.HANGAR_UNIT);
  }

  /**
   * Asks the unit-overview gate for the room's org unit.
   *
   * @param topic a parsed unit hangar topic
   * @return {@code true} iff the caller's unit overview shows that unit's ships
   */
  @Override
  public boolean mayJoin(@NotNull LiveSyncTopic topic) {
    return hangarAccessPolicy.canSeeUnitHangar(topic.requiredResourceId());
  }
}
