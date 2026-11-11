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
import static org.assertj.core.api.Assertions.assertThatNoException;

import de.greluc.krt.profit.basetool.backend.catalogue.api.CityRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ManufacturerRepository;
import de.greluc.krt.profit.basetool.backend.hangar.api.Ship;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.KeycloakService;
import de.greluc.krt.profit.basetool.backend.identity.api.Role;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionUnit;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionService;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionUnitRepository;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import de.greluc.krt.profit.basetool.backend.privacy.api.UserDeletionService;
import de.greluc.krt.profit.basetool.backend.support.TestPlaces;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real-Postgres test of the account deletion releasing the departing member's ships from mission
 * units (REQ-DATA-008, REQ-HANGAR-006): each unit drops the ship with a {@code @Version} bump and a
 * {@code MISSION_UNIT_UPDATED} row, an unfinished mission's leadership is told, and the deletion's
 * flush still succeeds although the member owns the mission and is responsible for a unit.
 *
 * <p>Runs in a rolled-back transaction, so the notices are asserted as published events rather than
 * as delivered rows.
 */
@SpringBootTest
@Transactional
@RecordApplicationEvents
class UserDeletionShipReleaseIntegrationTest {

  @Autowired private UserDeletionService userDeletionService;
  @Autowired private MissionService missionService;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private MissionRepository missionRepository;
  @Autowired private MissionUnitRepository missionUnitRepository;
  @Autowired private ShipRepository shipRepository;
  @Autowired private ShipTypeRepository shipTypeRepository;
  @Autowired private ManufacturerRepository manufacturerRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private CityRepository cityRepository;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager entityManager;
  @Autowired private ApplicationEvents events;

  @MockitoBean private KeycloakService keycloakService;

  private ShipType shipType;
  private Location location;

  @Test
  void deletingAMember_releasesTheirShipsFromEveryUnit_andTellsTheOpenMissionsLeadership() {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    admin(tag);
    User leaver = member("release-leaver-" + tag);
    User stayer = member("release-stayer-" + tag);
    fixtures(tag);
    Ship cutlass = ship(leaver, tag + "-cutlass");
    Ship carrack = ship(leaver, tag + "-carrack");
    Ship kept = ship(stayer, tag + "-kept");

    Mission open = mission("Release Open " + tag, "PLANNED", stayer);
    missionService.updateMissionOwner(open.getId(), leaver.getId(), 0L);
    Mission done = mission("Release Done " + tag, "COMPLETED", stayer);
    UUID alpha = unit(open, "Alpha", cutlass, stayer);
    UUID bravo = unit(open, "Bravo", carrack, leaver);
    UUID charlie = unit(done, "Charlie", cutlass, null);
    UUID delta = unit(open, "Delta", kept, stayer);
    entityManager.flush();
    entityManager.clear();
    Map<UUID, Long> before = versions(List.of(alpha, bravo, charlie, delta));

    UUID leaverId = leaver.getId();
    assertThatNoException()
        .isThrownBy(() -> {
          userDeletionService.deleteUser(leaverId);
          entityManager.flush();
        });
    entityManager.clear();

    assertThat(userRepository.findById(leaverId)).isEmpty();
    for (UUID released : List.of(alpha, bravo, charlie)) {
      assertThat(shipOf(released)).as("unit %s keeps no deleted ship", released).isNull();
      assertThat(versions(List.of(released)).get(released))
          .as("unit %s bumps its version", released)
          .isEqualTo(before.get(released) + 1);
    }
    assertThat(shipOf(delta)).as("another member's ship stays").isEqualTo(kept.getId());
    assertThat(versions(List.of(delta)).get(delta)).isEqualTo(before.get(delta));

    assertThat(unitAuditDetails(open.getId()))
        .containsExactlyInAnyOrder(
            "unit=" + alpha + " shipDetached=" + cutlass.getId(),
            "unit=" + bravo + " shipDetached=" + carrack.getId());
    assertThat(unitAuditDetails(done.getId()))
        .containsExactly("unit=" + charlie + " shipDetached=" + cutlass.getId());

    List<NoticeEvent> notices =
        events.stream(NoticeEvent.class)
            .filter(e -> e.eventType() == NotificationEventType.HANGAR_SHIP_DELETED_FROM_MISSION)
            .toList();
    assertThat(notices)
        .as("one notice per unit of the unfinished mission, none for the completed one")
        .hasSize(2)
        .allSatisfy(notice -> assertThat(notice.contextMissionId()).isEqualTo(open.getId()));
    assertThat(notices)
        .extracting(notice -> notice.renderParams().get("unit"))
        .containsExactlyInAnyOrder("Alpha", "Bravo");
    assertThat(notices)
        .extracting(NoticeEvent::contextRecipientUserId)
        .as("the departing member is never the recipient")
        .containsExactlyInAnyOrder(stayer.getId(), null);
  }

  private void admin(String tag) {
    Role adminRole =
        roleRepository
            .findByNameIgnoreCase("ADMIN")
            .orElseGet(() -> {
              Role fresh = new Role();
              fresh.setName("ADMIN");
              fresh.setCode("ADMIN");
              return roleRepository.save(fresh);
            });
    User admin = new User();
    admin.setId(UUID.randomUUID());
    admin.setUsername("release-admin-" + tag);
    admin.setRank(1);
    admin.setInKeycloak(true);
    admin.getRoles().add(adminRole);
    userRepository.save(admin);
  }

  private User member(String username) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username);
    user.setRank(1);
    user.setInKeycloak(false);
    return userRepository.save(user);
  }

  private void fixtures(String tag) {
    location = new Location();
    location.setName("Release Hub " + tag);
    locationRepository.save(TestPlaces.onOwnCity(location, cityRepository));
    Manufacturer manufacturer = new Manufacturer();
    manufacturer.setName("Release Works " + tag);
    manufacturer.setAbbreviation("RW" + tag.substring(0, 3));
    manufacturerRepository.save(manufacturer);
    shipType = new ShipType();
    shipType.setName("Release Hull " + tag);
    shipType.setManufacturer(manufacturer);
    shipTypeRepository.save(shipType);
  }

  private Ship ship(User owner, String name) {
    Ship ship = new Ship();
    ship.setName(name);
    ship.setShipType(shipType);
    ship.setInsurance("LTI");
    ship.setLocation(location);
    ship.setOwner(owner);
    return shipRepository.save(ship);
  }

  private Mission mission(String name, String status, User manager) {
    Mission mission = new Mission();
    mission.setName(name);
    mission.setStatus(status);
    mission.setIsInternal(false);
    mission.getManagers().add(manager);
    return missionRepository.save(mission);
  }

  private UUID unit(Mission mission, String name, Ship ship, User responsible) {
    MissionUnit unit = new MissionUnit();
    unit.setMission(mission);
    unit.setName(name);
    unit.setShip(ship);
    unit.setResponsibleUser(responsible);
    return missionUnitRepository.save(unit).getId();
  }

  private Map<UUID, Long> versions(List<UUID> units) {
    return units.stream()
        .collect(
            Collectors.toMap(
                id -> id,
                id ->
                    jdbc.queryForObject(
                        "SELECT version FROM mission_unit WHERE id = ?", Long.class, id)));
  }

  private UUID shipOf(UUID unit) {
    return jdbc.queryForObject("SELECT ship_id FROM mission_unit WHERE id = ?", UUID.class, unit);
  }

  private List<String> unitAuditDetails(UUID missionId) {
    return jdbc.queryForList(
        "SELECT details FROM audit_event WHERE event_type = 'MISSION_UNIT_UPDATED'"
            + " AND subject_id = ?",
        String.class,
        missionId);
  }
}
