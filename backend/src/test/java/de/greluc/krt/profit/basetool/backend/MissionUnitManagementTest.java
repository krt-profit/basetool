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

package de.greluc.krt.profit.basetool.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.*;
import de.greluc.krt.profit.basetool.backend.repository.*;
import de.greluc.krt.profit.basetool.backend.service.MissionService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MissionUnitManagementTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @Autowired private UserRepository userRepository;
  @Autowired private MissionRepository missionRepository;
  @Autowired private ShipRepository shipRepository;
  @Autowired private ShipTypeRepository shipTypeRepository;
  @Autowired private MissionService missionService;

  @MockitoBean private JwtDecoder jwtDecoder;

  private User officerUser;
  private Mission mission;
  private Ship ship;
  private MissionUnit unit;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

    officerUser = new User();
    officerUser.setId(UUID.randomUUID());
    officerUser.setUsername("officer_unit_mgmt");
    userRepository.save(officerUser);

    ShipType st = new ShipType();
    st.setName("Fighter");
    shipTypeRepository.save(st);

    ship = new Ship();
    ship.setName("Test Ship");
    ship.setOwner(officerUser);
    ship.setShipType(st);
    shipRepository.save(ship);

    mission =
        missionService.createMission(
            new de.greluc.krt.profit.basetool.backend.model.dto.request.CreateMissionRequest(
                "Test Mission Unit Mgmt",
                null,
                null,
                "PLANNED",
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null));

    // The ship owner must be a registered participant before the ship can be pinned to a unit.
    missionService.addParticipant(mission.getId(), officerUser.getId());

    mission =
        missionService.addUnitToMission(
            mission.getId(), "Initial Unit", st.getId(), ship.getId(), false, 123.45, null, null);
    unit = mission.getAssignedUnits().iterator().next();
  }

  @Test
  void testUpdateUnit_Officer_Allowed() throws Exception {
    String updateJson =
        String.format(
            "{\"name\": \"Updated Unit\", \"shipTypeId\": \"%s\", \"shipId\": \"%s\","
                + " \"highValueUnit\": true, \"frequency\": 111.11}",
            unit.getShipType().getId(), ship.getId());

    mockMvc
        .perform(
            put("/api/v1/missions/" + mission.getId() + "/units/" + unit.getId())
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(officerUser.getId().toString()))
                        .authorities(
                            new SimpleGrantedAuthority("ROLE_OFFICER"),
                            new SimpleGrantedAuthority("USER_MANAGE"),
                            new SimpleGrantedAuthority("MISSION_MANAGE"),
                            new SimpleGrantedAuthority("HANGAR_MANAGE"),
                            new SimpleGrantedAuthority("REFINERY_MANAGE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateJson))
        .andExpect(status().isOk());

    Mission updatedMission = missionRepository.findById(mission.getId()).orElseThrow();
    MissionUnit updatedUnit = updatedMission.getAssignedUnits().iterator().next();

    org.junit.jupiter.api.Assertions.assertEquals("Updated Unit", updatedUnit.getName());
    org.junit.jupiter.api.Assertions.assertTrue(updatedUnit.isHighValueUnit());
    org.junit.jupiter.api.Assertions.assertEquals(111.11, updatedUnit.getFrequency());
  }

  @Test
  void testDeleteUnit_Officer_Allowed() throws Exception {
    mockMvc
        .perform(
            delete("/api/v1/missions/" + mission.getId() + "/units/" + unit.getId())
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(officerUser.getId().toString()))
                        .authorities(
                            new SimpleGrantedAuthority("ROLE_OFFICER"),
                            new SimpleGrantedAuthority("USER_MANAGE"),
                            new SimpleGrantedAuthority("MISSION_MANAGE"),
                            new SimpleGrantedAuthority("HANGAR_MANAGE"),
                            new SimpleGrantedAuthority("REFINERY_MANAGE"))))
        .andExpect(status().isOk());

    Mission updatedMission = missionRepository.findById(mission.getId()).orElseThrow();
    assertFalse(
        updatedMission.getAssignedUnits().stream().anyMatch(u -> u.getId().equals(unit.getId())));
  }

  @Test
  void testAddUnit_ShipOwnerNotParticipant_Rejected() {
    // Given: a ship owned by a user who is NOT registered for the mission.
    Ship outsiderShip = shipOwnedByNonParticipant();

    // When / Then: pinning that ship to a new unit is rejected.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            missionService.addUnitToMission(
                mission.getId(),
                "Outsider Unit",
                outsiderShip.getShipType().getId(),
                outsiderShip.getId(),
                false,
                null,
                null,
                null));
  }

  @Test
  void testAddUnit_ShipOwnerIsParticipant_Allowed() {
    // officerUser is a registered participant (see setUp) and owns `ship`, so the assignment
    // passes.
    Mission updated =
        missionService.addUnitToMission(
            mission.getId(),
            "Participant Unit",
            ship.getShipType().getId(),
            ship.getId(),
            false,
            null,
            null,
            null);

    assertTrue(
        updated.getAssignedUnits().stream()
            .anyMatch(
                u ->
                    "Participant Unit".equals(u.getName())
                        && u.getShip() != null
                        && ship.getId().equals(u.getShip().getId())));
  }

  @Test
  void testUpdateUnit_ChangeToShipOfNonParticipant_Rejected() {
    // Switching an existing unit to a ship owned by a non-participant is rejected.
    Ship outsiderShip = shipOwnedByNonParticipant();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            missionService.updateMissionUnit(
                mission.getId(),
                unit.getId(),
                null,
                "Initial Unit",
                outsiderShip.getShipType().getId(),
                outsiderShip.getId(),
                false,
                null,
                null,
                null));
  }

  @Test
  void testUpdateUnit_KeepsExistingShipAfterOwnerLeftMission_Allowed() {
    // The unit holds officerUser's ship, assigned while officerUser was a participant. Remove
    // officerUser from the roster so the ship owner is no longer registered, then edit the unit
    // keeping the same ship: the already-assigned ship is grandfathered and the edit still passes.
    UUID participantId =
        missionRepository.findById(mission.getId()).orElseThrow().getParticipants().stream()
            .filter(p -> p.getUser() != null && p.getUser().getId().equals(officerUser.getId()))
            .findFirst()
            .orElseThrow()
            .getId();
    missionService.removeParticipant(mission.getId(), participantId);

    missionService.updateMissionUnit(
        mission.getId(),
        unit.getId(),
        null,
        "Renamed Unit",
        ship.getShipType().getId(),
        ship.getId(),
        false,
        222.22,
        null,
        null);

    MissionUnit reloaded =
        missionRepository.findById(mission.getId()).orElseThrow().getAssignedUnits().stream()
            .filter(u -> u.getId().equals(unit.getId()))
            .findFirst()
            .orElseThrow();
    assertEquals("Renamed Unit", reloaded.getName());
    assertNotNull(reloaded.getShip());
    assertEquals(ship.getId(), reloaded.getShip().getId());
  }

  /**
   * Persists a ship owned by a freshly created user who is not a participant of the mission under
   * test, reusing the {@code ship} field's ship type so the ship-to-type match still holds.
   *
   * @return the saved ship whose owner is absent from the mission roster
   */
  /** A blank display name derives the stored unit name from the assigned ship (mock: optional). */
  @Test
  void testAddUnit_BlankName_DerivesNameFromShip() {
    Mission updated =
        missionService.addUnitToMission(
            mission.getId(),
            "  ",
            ship.getShipType().getId(),
            ship.getId(),
            false,
            null,
            null,
            null);

    MissionUnit created =
        updated.getAssignedUnits().stream()
            .filter(u -> !u.getId().equals(unit.getId()))
            .findFirst()
            .orElseThrow();
    assertEquals(
        ship.getName(),
        created.getName(),
        "blank display name must derive the stored name from the assigned ship");
  }

  /** A blank display name with only a ship type derives the name from the type. */
  @Test
  void testAddUnit_BlankName_DerivesNameFromShipType() {
    Mission updated =
        missionService.addUnitToMission(
            mission.getId(), null, ship.getShipType().getId(), null, false, null, null, null);

    MissionUnit created =
        updated.getAssignedUnits().stream()
            .filter(u -> !u.getId().equals(unit.getId()))
            .findFirst()
            .orElseThrow();
    assertEquals(
        ship.getShipType().getName(),
        created.getName(),
        "blank display name must fall back to the ship type name");
  }

  /** Without name, ship, and ship type there is nothing to call the unit — rejected. */
  @Test
  void testAddUnit_BlankNameWithoutShipOrType_Rejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            missionService.addUnitToMission(
                mission.getId(), " ", null, null, false, null, null, null));
  }

  /** Responsible person and note round-trip; the note is trimmed and blank collapses to null. */
  @Test
  void testAddUnit_ResponsibleAndNote_Persisted() {
    Mission updated =
        missionService.addUnitToMission(
            mission.getId(),
            "Responsible Unit",
            null,
            null,
            false,
            null,
            officerUser.getId(),
            "  Eskorte, Gruppe 1  ");

    MissionUnit created =
        updated.getAssignedUnits().stream()
            .filter(u -> "Responsible Unit".equals(u.getName()))
            .findFirst()
            .orElseThrow();
    assertNotNull(created.getResponsibleUser(), "explicit responsible must be stored");
    assertEquals(officerUser.getId(), created.getResponsibleUser().getId());
    assertEquals("Eskorte, Gruppe 1", created.getNote(), "note must be trimmed");

    Mission cleared =
        missionService.updateMissionUnit(
            mission.getId(),
            created.getId(),
            null,
            "Responsible Unit",
            null,
            null,
            false,
            null,
            null,
            "   ");
    MissionUnit reloaded =
        cleared.getAssignedUnits().stream()
            .filter(u -> u.getId().equals(created.getId()))
            .findFirst()
            .orElseThrow();
    assertEquals(null, reloaded.getResponsibleUser(), "responsible must be clearable");
    assertEquals(null, reloaded.getNote(), "blank note must collapse to null");
  }

  private Ship shipOwnedByNonParticipant() {
    User outsider = new User();
    outsider.setId(UUID.randomUUID());
    outsider.setUsername("outsider_" + UUID.randomUUID());
    userRepository.save(outsider);

    Ship outsiderShip = new Ship();
    outsiderShip.setName("Outsider Ship");
    outsiderShip.setOwner(outsider);
    outsiderShip.setShipType(ship.getShipType());
    return shipRepository.save(outsiderShip);
  }
}
