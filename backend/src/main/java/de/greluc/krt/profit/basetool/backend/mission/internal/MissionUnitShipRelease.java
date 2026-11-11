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

import de.greluc.krt.profit.basetool.backend.admin.api.events.HangarNotices;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipDeletionObserver;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionUnit;
import de.greluc.krt.profit.basetool.backend.notification.api.events.ActorRef;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Releases a deleted ship from every mission unit that carries it, recording one {@code
 * MISSION_UNIT_UPDATED} event per unit so the mission trail shows the change (REQ-AUDIT-001), and
 * tells the mission leadership when the ship leaves a mission that is not finished
 * (REQ-HANGAR-006).
 */
@Component
@RequiredArgsConstructor
public class MissionUnitShipRelease implements ShipDeletionObserver {

  private final MissionUnitRepository missionUnitRepository;
  private final AuditRecorder auditRecorder;
  private final ApplicationEventPublisher eventPublisher;
  private final UserService userService;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public int beforeShipDelete(@NotNull UUID shipId) {
    List<MissionUnit> units = missionUnitRepository.findByShipId(shipId);
    if (units.isEmpty()) {
      return 0;
    }
    ActorRef actor = userService.currentActor();
    for (MissionUnit unit : units) {
      UnitShipRelease release = UnitShipRelease.of(unit, shipId);
      unit.setShip(null);
      missionUnitRepository.save(unit);
      report(release, actor);
    }
    return units.size();
  }

  /**
   * Releases the units through one projection and one bulk update, so neither a unit nor a ship of
   * the departing member enters the persistence context; the member is never the notice's
   * recipient.
   */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public int beforeOwnerShipsDelete(@NotNull UUID ownerId) {
    List<UnitShipRelease> releases = missionUnitRepository.findShipReleasesByShipOwnerId(ownerId);
    if (releases.isEmpty()) {
      return 0;
    }
    missionUnitRepository.releaseShipsOfOwner(ownerId, Instant.now());
    ActorRef actor = userService.currentActor();
    for (UnitShipRelease release : releases) {
      report(release.withoutResponsible(ownerId), actor);
    }
    return releases.size();
  }

  /**
   * Records the unit's change on the mission trail and announces the gap unless the mission is
   * finished.
   *
   * @param release the unit that lost its ship
   * @param actor who deletes the ship
   */
  private void report(@NotNull UnitShipRelease release, @NotNull ActorRef actor) {
    if (!release.missionFinished()) {
      eventPublisher.publishEvent(
          HangarNotices.shipDeleted(
              release.missionId(),
              release.responsibleUserId(),
              release.shipTypeName(),
              release.missionName(),
              release.unitName(),
              actor));
    }
    auditRecorder.record(
        AuditEventType.MISSION_UNIT_UPDATED,
        release.missionId(),
        release.missionName(),
        null,
        AuditDetails.of("unit", release.unitId()).with("shipDetached", release.shipId()));
  }
}
