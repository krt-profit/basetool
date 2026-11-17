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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeReassignmentParticipant;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeReferenceParticipant;
import de.greluc.krt.profit.basetool.backend.hangar.api.Ship;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRepository;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The hangar's ship type references: the ship types a member's ship is of (REQ-P4K-052), moved onto
 * the ship type that stays when two are merged (REQ-DATA-042).
 */
@Component
@RequiredArgsConstructor
public class HangarShipTypeReferences
    implements ShipTypeReferenceParticipant, ShipTypeReassignmentParticipant {

  private final EntityManager entityManager;
  private final ShipRepository shipRepository;
  private final AuditRecorder auditRecorder;
  private final LiveSyncPublisher liveSync;
  private final HangarUnitLiveSync unitLiveSync;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull Set<UUID> referencedShipTypes(@NotNull Collection<UUID> shipTypeIds) {
    if (shipTypeIds.isEmpty()) {
      return Set.of();
    }
    return new HashSet<>(
        entityManager
            .createQuery(
                "SELECT DISTINCT s.shipType.id FROM Ship s WHERE s.shipType.id IN :ids", UUID.class)
            .setParameter("ids", shipTypeIds)
            .getResultList());
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public int reassignShipType(@NotNull UUID retiredId, @NotNull UUID survivorId) {
    List<Ship> ships = shipRepository.lockAllByShipTypeId(retiredId);
    if (ships.isEmpty()) {
      return 0;
    }
    ShipType survivor = entityManager.getReference(ShipType.class, survivorId);
    Set<UUID> owners = new HashSet<>();
    List<OrgUnit> units = new ArrayList<>();
    for (Ship ship : ships) {
      ship.setShipType(survivor);
      UUID owner = ship.getOwner().getId();
      owners.add(owner);
      units.add(ship.getOwningOrgUnit());
      auditRecorder.record(
          AuditEventType.HANGAR_SHIP_CATALOGUE_REASSIGNED,
          ship.getId(),
          survivor.getName(),
          owner,
          AuditDetails.of("retiredShipType", retiredId).with("survivorShipType", survivorId));
    }
    entityManager.flush();
    owners.forEach(owner ->
        liveSync.publishAfterCommit(
            LiveSyncTopic.of(LiveSyncTopicClass.HANGAR_OWN, owner),
            List.of(HangarUnitLiveSync.SHIPS)));
    unitLiveSync.shipsChanged(units);
    return ships.size();
  }
}
