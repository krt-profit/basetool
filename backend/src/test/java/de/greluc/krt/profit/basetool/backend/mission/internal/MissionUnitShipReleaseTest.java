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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionUnit;
import de.greluc.krt.profit.basetool.backend.model.Ship;
import de.greluc.krt.profit.basetool.backend.repository.MissionUnitRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Pins that a deleted ship leaves every mission unit and that each unit records its change. */
@ExtendWith(MockitoExtension.class)
class MissionUnitShipReleaseTest {

  @Mock private MissionUnitRepository missionUnitRepository;
  @Mock private AuditRecorder auditRecorder;

  @InjectMocks private MissionUnitShipRelease release;

  @Test
  void releasesTheShipFromEveryUnitAndRecordsEachUnit() {
    UUID shipId = UUID.randomUUID();
    Ship ship = new Ship();
    ship.setId(shipId);
    Mission mission = new Mission();
    mission.setId(UUID.randomUUID());
    mission.setName("Op Aurora");
    MissionUnit unit = new MissionUnit();
    unit.setId(UUID.randomUUID());
    unit.setShip(ship);
    unit.setMission(mission);
    when(missionUnitRepository.findByShipId(shipId)).thenReturn(List.of(unit));

    assertThat(release.beforeShipDelete(shipId)).isEqualTo(1);

    assertThat(unit.getShip()).isNull();
    verify(missionUnitRepository).save(unit);
    verify(auditRecorder)
        .record(
            eq(AuditEventType.MISSION_UNIT_UPDATED),
            eq(mission.getId()),
            eq("Op Aurora"),
            isNull(),
            any());
  }

  @Test
  void aShipWithoutUnitsReleasesNothing() {
    UUID shipId = UUID.randomUUID();
    when(missionUnitRepository.findByShipId(shipId)).thenReturn(List.of());

    assertThat(release.beforeShipDelete(shipId)).isZero();

    verifyNoInteractions(auditRecorder);
  }
}
