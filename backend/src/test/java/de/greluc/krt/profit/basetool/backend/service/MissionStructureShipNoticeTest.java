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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.MissionUnit;
import de.greluc.krt.profit.basetool.backend.model.Ship;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionUnitRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipRepository;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The ship notices of the mission units (REQ-HANGAR-005): assigning a ship at creation or by edit
 * tells the owner, changing or removing it clears the former owner's notice, and keeping the ship
 * announces nothing.
 */
@ExtendWith(MockitoExtension.class)
class MissionStructureShipNoticeTest {

  @Mock private MissionRepository missionRepository;
  @Mock private MissionUnitRepository missionUnitRepository;
  @Mock private ShipRepository shipRepository;

  @Mock
  private de.greluc.krt.profit.basetool.backend.repository.ShipTypeRepository shipTypeRepository;

  @Mock private MissionNotificationPublisher notificationPublisher;
  @Mock private de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder auditRecorder;

  @InjectMocks private MissionStructureService service;

  private Mission mission;
  private MissionUnit unit;
  private Ship ship;
  private Ship otherShip;

  @BeforeEach
  void setUp() {
    mission = new Mission();
    mission.setId(UUID.randomUUID());
    mission.setName("Op Aurora");
    mission.setParticipants(new HashSet<>());
    mission.setAssignedUnits(new HashSet<>());
    ship = ship("Cutlass Black");
    otherShip = ship("Carrack");
    for (Ship s : Set.of(ship, otherShip)) {
      MissionParticipant participant = new MissionParticipant();
      participant.setUser(s.getOwner());
      mission.getParticipants().add(participant);
    }
    unit = new MissionUnit();
    unit.setId(UUID.randomUUID());
    unit.setMission(mission);
    unit.setName("Alpha");
    mission.getAssignedUnits().add(unit);
    org.mockito.Mockito.lenient()
        .when(missionRepository.findById(mission.getId()))
        .thenReturn(Optional.of(mission));
    org.mockito.Mockito.lenient()
        .when(shipRepository.findById(ship.getId()))
        .thenReturn(Optional.of(ship));
    org.mockito.Mockito.lenient()
        .when(shipRepository.findById(otherShip.getId()))
        .thenReturn(Optional.of(otherShip));
    for (Ship s : Set.of(ship, otherShip)) {
      org.mockito.Mockito.lenient()
          .when(shipTypeRepository.findById(s.getShipType().getId()))
          .thenReturn(Optional.of(s.getShipType()));
    }
  }

  private static Ship ship(String typeName) {
    User owner = new User();
    owner.setId(UUID.randomUUID());
    ShipType type = new ShipType();
    type.setId(UUID.randomUUID());
    type.setName(typeName);
    Ship ship = new Ship();
    ship.setId(UUID.randomUUID());
    ship.setOwner(owner);
    ship.setShipType(type);
    return ship;
  }

  private void update(Ship target) {
    service.updateMissionUnit(
        mission.getId(),
        unit.getId(),
        null,
        "Alpha",
        target == null ? null : target.getShipType().getId(),
        target == null ? null : target.getId(),
        false,
        null,
        null,
        null);
  }

  @Test
  void aNewUnitWithAShipTellsTheShipsOwner() {
    service.addUnitToMission(
        mission.getId(),
        "Bravo",
        ship.getShipType().getId(),
        ship.getId(),
        false,
        null,
        null,
        null);

    verify(notificationPublisher)
        .shipAssigned(any(Mission.class), any(MissionUnit.class), any(Ship.class));
  }

  @Test
  void aNewUnitWithoutAShipAnnouncesNothing() {
    service.addUnitToMission(
        mission.getId(), "Bravo", ship.getShipType().getId(), null, false, null, null, null);

    verify(notificationPublisher, never())
        .shipAssigned(any(Mission.class), any(MissionUnit.class), any(Ship.class));
  }

  @Test
  void settingAShipOnAnExistingUnitTellsItsOwner() {
    update(ship);

    verify(notificationPublisher).shipAssigned(mission, unit, ship);
    verify(notificationPublisher, never()).shipUnassigned(any(), any());
  }

  @Test
  void swappingTheShipClearsTheFormerOwnersNoticeAndTellsTheNewOwner() {
    unit.setShip(ship);

    update(otherShip);

    verify(notificationPublisher).shipUnassigned(unit.getId(), ship);
    verify(notificationPublisher).shipAssigned(mission, unit, otherShip);
  }

  @Test
  void removingTheShipOnlyClearsTheFormerOwnersNotice() {
    unit.setShip(ship);
    unit.setShipType(ship.getShipType());

    update(null);

    verify(notificationPublisher).shipUnassigned(unit.getId(), ship);
    verify(notificationPublisher, never())
        .shipAssigned(any(Mission.class), any(MissionUnit.class), any(Ship.class));
  }

  @Test
  void keepingTheSameShipAnnouncesNothing() {
    unit.setShip(ship);
    unit.setShipType(ship.getShipType());

    update(ship);

    assertThat(unit.getShip()).isSameAs(ship);
    verify(notificationPublisher, never()).shipUnassigned(any(), any());
    verify(notificationPublisher, never())
        .shipAssigned(any(Mission.class), any(MissionUnit.class), any(Ship.class));
  }
}
