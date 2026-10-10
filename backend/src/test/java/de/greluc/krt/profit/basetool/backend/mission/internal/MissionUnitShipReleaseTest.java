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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionUnit;
import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.model.Ship;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.notification.api.events.ActorRef;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import de.greluc.krt.profit.basetool.backend.service.UserService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/** Pins that a deleted ship leaves every mission unit and that each unit records its change. */
@ExtendWith(MockitoExtension.class)
class MissionUnitShipReleaseTest {

  @Mock private MissionUnitRepository missionUnitRepository;
  @Mock private AuditRecorder auditRecorder;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private UserService userService;

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

  private static final ActorRef ACTOR = new ActorRef(UUID.randomUUID(), "Ada");

  private MissionUnit assignedUnit(UUID shipId, String missionStatus) {
    ShipType type = new ShipType();
    type.setName("Cutlass Black");
    Ship ship = new Ship();
    ship.setId(shipId);
    ship.setShipType(type);
    ship.setName("Private free-text name");
    Mission mission = new Mission();
    mission.setId(UUID.randomUUID());
    mission.setName("Op Aurora");
    mission.setStatus(missionStatus);
    MissionUnit unit = new MissionUnit();
    unit.setId(UUID.randomUUID());
    unit.setName("Alpha");
    unit.setShip(ship);
    unit.setMission(mission);
    when(missionUnitRepository.findByShipId(shipId)).thenReturn(List.of(unit));
    return unit;
  }

  @Test
  void deletingAShipOfAnUnfinishedMissionTellsTheLeadershipAndTheUnitsResponsible() {
    UUID shipId = UUID.randomUUID();
    MissionUnit unit = assignedUnit(shipId, "ACTIVE");
    User responsible = new User();
    responsible.setId(UUID.randomUUID());
    unit.setResponsibleUser(responsible);
    when(userService.currentActor()).thenReturn(ACTOR);

    release.beforeShipDelete(shipId);

    ArgumentCaptor<Object> captured = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher).publishEvent(captured.capture());
    NoticeEvent notice = (NoticeEvent) captured.getValue();
    assertThat(notice.eventType())
        .isEqualTo(NotificationEventType.HANGAR_SHIP_DELETED_FROM_MISSION);
    assertThat(notice.contextMissionId()).isEqualTo(unit.getMission().getId());
    assertThat(notice.contextRecipientUserId()).isEqualTo(responsible.getId());
    assertThat(notice.actorSub()).isEqualTo(ACTOR.id());
    assertThat(notice.renderParams())
        .containsEntry("shipType", "Cutlass Black")
        .containsEntry("unit", "Alpha")
        .containsEntry("mission", "Op Aurora")
        .doesNotContainValue("Private free-text name");
  }

  @Test
  void deletingAShipOfAFinishedMissionAnnouncesNothing() {
    UUID shipId = UUID.randomUUID();
    assignedUnit(shipId, "COMPLETED");
    when(userService.currentActor()).thenReturn(ACTOR);

    release.beforeShipDelete(shipId);

    verify(eventPublisher, never()).publishEvent(any(Object.class));
  }

  @Test
  void deletingAShipThatIsInNoUnitAnnouncesNothing() {
    UUID shipId = UUID.randomUUID();
    when(missionUnitRepository.findByShipId(shipId)).thenReturn(List.of());

    release.beforeShipDelete(shipId);

    verify(eventPublisher, never()).publishEvent(any(Object.class));
    verifyNoInteractions(userService);
  }
}
