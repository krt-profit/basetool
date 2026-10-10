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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipDeletionObserver;
import de.greluc.krt.profit.basetool.backend.model.MissionUnit;
import de.greluc.krt.profit.basetool.backend.repository.MissionUnitRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Releases a deleted ship from every mission unit that carries it, recording one {@code
 * MISSION_UNIT_UPDATED} event per unit so the mission trail shows the change (REQ-AUDIT-001).
 */
@Component
@RequiredArgsConstructor
public class MissionUnitShipRelease implements ShipDeletionObserver {

  private final MissionUnitRepository missionUnitRepository;
  private final AuditRecorder auditRecorder;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public int beforeShipDelete(@NotNull UUID shipId) {
    List<MissionUnit> units = missionUnitRepository.findByShipId(shipId);
    for (MissionUnit unit : units) {
      unit.setShip(null);
      missionUnitRepository.save(unit);
      auditRecorder.record(
          AuditEventType.MISSION_UNIT_UPDATED,
          unit.getMission().getId(),
          unit.getMission().getName(),
          null,
          AuditDetails.of("unit", unit.getId()).with("shipDetached", shipId));
    }
    return units.size();
  }
}
