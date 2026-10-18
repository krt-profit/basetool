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

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link MissionStructureService} against mocks of its own collaborators. */
@ExtendWith(MockitoExtension.class)
class MissionStructureServiceTest {

  @Mock private MissionRepository missionRepository;
  @Mock private AuditRecorder auditRecorder;
  @Mock private MissionNotificationPublisher notificationPublisher;
  @InjectMocks private MissionStructureService missionStructureService;

  @Test
  void removeMissionUnit_shouldNotCallMissionRepositorySave() {
    UUID missionId = UUID.randomUUID();
    Mission existing = new Mission();
    existing.setId(missionId);
    MissionUnit unit = new MissionUnit();
    UUID unitId = UUID.randomUUID();
    unit.setId(unitId);
    existing.getAssignedUnits().add(unit);
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(existing));

    Mission result = missionStructureService.removeMissionUnit(missionId, unitId);

    assertSame(existing, result);
    verify(missionRepository, never()).save(any(Mission.class));
  }
}
