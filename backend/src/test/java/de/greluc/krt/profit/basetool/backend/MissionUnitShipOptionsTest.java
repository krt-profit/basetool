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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.Ship;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.service.MissionService;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies the {@code GET /api/v1/missions/{id}/unit-ship-options} endpoint and the backing {@link
 * MissionService#getSelectableUnitShips} logic: a unit's ship picker offers ships of every
 * registered participant regardless of OrgUnit, excludes non-participants, and retains ships
 * already pinned to a unit even when their owner has left the roster.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MissionUnitShipOptionsTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @Autowired private UserRepository userRepository;
  @Autowired private MissionRepository missionRepository;
  @Autowired private ShipRepository shipRepository;
  @Autowired private ShipTypeRepository shipTypeRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private MissionService missionService;

  @MockitoBean private JwtDecoder jwtDecoder;

  private ShipType shipType;
  private Mission mission;
  private Ship participantShipSameOrgUnit;
  private Ship participantShipOtherOrgUnit;
  private Ship nonParticipantShip;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

    Squadron iridium = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();
    Squadron otherOrgUnit = new Squadron();
    otherOrgUnit.setName("Other Squadron");
    otherOrgUnit.setShorthand("OTH");
    otherOrgUnit = squadronRepository.save(otherOrgUnit);

    shipType = new ShipType();
    shipType.setName("Fighter");
    shipType = shipTypeRepository.save(shipType);

    User participantA = saveUser("ship_opts_a");
    User participantB = saveUser("ship_opts_b");
    User nonParticipant = saveUser("ship_opts_c");

    participantShipSameOrgUnit = saveShip("A-Ship", participantA, iridium);
    // Owner is a participant but their ship lives in a different OrgUnit — must still be offered.
    participantShipOtherOrgUnit = saveShip("B-Ship", participantB, otherOrgUnit);
    nonParticipantShip = saveShip("C-Ship", nonParticipant, iridium);

    mission = new Mission();
    mission.setOwningOrgUnit(iridium);
    mission.setName("Ship Options Mission");
    mission.setStatus("PLANNED");
    mission = missionRepository.save(mission);

    missionService.addParticipant(
        mission.getId(), participantA.getId(), null, null, null, null, null);
    missionService.addParticipant(
        mission.getId(), participantB.getId(), null, null, null, null, null);
    // nonParticipant is deliberately NOT registered.
  }

  @Test
  void getSelectableUnitShips_includesParticipantShipsAcrossOrgUnits_excludesNonParticipants() {
    List<UUID> shipIds =
        missionService.getSelectableUnitShips(mission.getId()).stream().map(Ship::getId).toList();

    assertTrue(
        shipIds.contains(participantShipSameOrgUnit.getId()),
        "ship of a same-OrgUnit participant must be offered");
    assertTrue(
        shipIds.contains(participantShipOtherOrgUnit.getId()),
        "ship of a cross-OrgUnit participant must be offered");
    assertFalse(
        shipIds.contains(nonParticipantShip.getId()),
        "ship of a non-participant must not be offered");
  }

  @Test
  void getSelectableUnitShips_includesParticipantShipWithoutAnyOrgUnit() {
    // A participant who belongs to no org unit at all — their ship is an ownerless personal ship
    // (owningOrgUnit == null) — must still have their ship offered. Selection is keyed purely on
    // participation, never on the OrgUnit the participant signed up as (or the lack of one): the
    // owner-requested "a player's ships are always selectable regardless of org unit" guarantee.
    User orgUnitlessParticipant = saveUser("ship_opts_no_orgunit");
    Ship orgUnitlessShip = saveShip("E-Ship", orgUnitlessParticipant, null);
    missionService.addParticipant(
        mission.getId(), orgUnitlessParticipant.getId(), null, null, null, null, null);

    List<UUID> shipIds =
        missionService.getSelectableUnitShips(mission.getId()).stream().map(Ship::getId).toList();

    assertTrue(
        shipIds.contains(orgUnitlessShip.getId()),
        "ship of a participant with no OrgUnit at all must still be offered");
  }

  @Test
  void getSelectableUnitShips_keepsAlreadyAssignedShipOfDepartedOwner() {
    // A ship owned by a non-participant, persisted onto a unit directly (bypassing the service
    // guard) to model a unit whose ship owner has since left the roster.
    User departed = saveUser("ship_opts_departed");
    Ship assignedShip = saveShip("D-Ship", departed, mission.getOwningOrgUnit());

    MissionUnit unit = new MissionUnit();
    unit.setMission(mission);
    unit.setName("Alpha");
    unit.setShipType(shipType);
    unit.setShip(assignedShip);
    mission.getAssignedUnits().add(unit);
    mission = missionRepository.save(mission);

    List<UUID> shipIds =
        missionService.getSelectableUnitShips(mission.getId()).stream().map(Ship::getId).toList();

    assertTrue(
        shipIds.contains(assignedShip.getId()),
        "an already-assigned ship must be retained even if its owner is not a participant");
  }

  @Test
  void getUnitShipOptionsEndpoint_asAdmin_returnsParticipantShipsExcludingNonParticipants()
      throws Exception {
    mockMvc
        .perform(
            get("/api/v1/missions/" + mission.getId() + "/unit-ship-options")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(UUID.randomUUID().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(Matchers.containsString(participantShipSameOrgUnit.getId().toString())))
        .andExpect(
            content()
                .string(Matchers.containsString(participantShipOtherOrgUnit.getId().toString())))
        .andExpect(
            content()
                .string(
                    Matchers.not(Matchers.containsString(nonParticipantShip.getId().toString()))));
  }

  private User saveUser(String username) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username);
    return userRepository.save(user);
  }

  private Ship saveShip(String name, User owner, OrgUnit owningOrgUnit) {
    Ship ship = new Ship();
    ship.setName(name);
    ship.setInsurance("10");
    ship.setOwner(owner);
    ship.setShipType(shipType);
    ship.setOwningOrgUnit(owningOrgUnit);
    return shipRepository.save(ship);
  }
}
