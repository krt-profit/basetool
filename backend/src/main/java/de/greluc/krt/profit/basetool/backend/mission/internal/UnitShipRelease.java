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

import de.greluc.krt.profit.basetool.backend.hangar.api.Ship;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionUnit;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One mission unit letting go of a ship that is about to be deleted, with the scalars its notice
 * (REQ-HANGAR-006) and its {@code MISSION_UNIT_UPDATED} audit row name.
 *
 * <p>Built from a loaded unit on a single ship delete, or read as a JPQL projection by {@link
 * MissionUnitRepository#findShipReleasesByShipOwnerId(UUID)} on the account deletion, which must
 * not load a ship or a unit.
 *
 * @param unitId the unit
 * @param unitName the unit's name
 * @param missionId the unit's mission
 * @param missionName the mission's name
 * @param missionStatus the mission's status code, such as {@code PLANNED}
 * @param responsibleUserId the unit's responsible member, or {@code null} when it has none
 * @param shipId the ship that leaves the unit
 * @param shipTypeName the ship's type name, or {@code null} when unknown
 */
public record UnitShipRelease(
    @NotNull UUID unitId,
    @Nullable String unitName,
    @NotNull UUID missionId,
    @Nullable String missionName,
    @Nullable String missionStatus,
    @Nullable UUID responsibleUserId,
    @NotNull UUID shipId,
    @Nullable String shipTypeName) {

  /** The mission statuses after which a lost ship no longer leaves a gap in the plan. */
  private static final Set<String> FINISHED_STATUSES = Set.of("COMPLETED", "CANCELLED");

  /**
   * Captures what a loaded unit names before its ship is cleared.
   *
   * @param unit the unit that still carries the ship
   * @param shipId the ship about to be deleted
   * @return the release of that unit
   */
  static @NotNull UnitShipRelease of(@NotNull MissionUnit unit, @NotNull UUID shipId) {
    Ship ship = unit.getShip();
    return new UnitShipRelease(
        unit.getId(),
        unit.getName(),
        unit.getMission().getId(),
        unit.getMission().getName(),
        unit.getMission().getStatus(),
        unit.getResponsibleUser() == null ? null : unit.getResponsibleUser().getId(),
        shipId,
        ship == null || ship.getShipType() == null ? null : ship.getShipType().getName());
  }

  /**
   * Whether the unit's mission is finished, so the lost ship leaves no gap to announce.
   *
   * @return {@code true} for a {@code COMPLETED} or {@code CANCELLED} mission
   */
  boolean missionFinished() {
    return missionStatus != null && FINISHED_STATUSES.contains(missionStatus);
  }

  /**
   * Returns this release without its responsible member when that member is {@code memberId}, so a
   * departing member is never the recipient of the notice about their own ships.
   *
   * @param memberId the member who must not be named as responsible
   * @return this release, or a copy whose {@link #responsibleUserId()} is {@code null}
   */
  @NotNull
  UnitShipRelease withoutResponsible(@NotNull UUID memberId) {
    if (!memberId.equals(responsibleUserId)) {
      return this;
    }
    return new UnitShipRelease(
        unitId, unitName, missionId, missionName, missionStatus, null, shipId, shipTypeName);
  }
}
