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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.*;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.repository.*;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import java.time.Instant;
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
// REQ-SEC-052: the participant writes these cases exercise require a login. The rows they
// create are EXTERNAL participants now (ADR-0159, decision D4) — a named person without an
// account, recorded by a member who can see the Einsatz — which is the same row shape and a
// different author.
@org.springframework.security.test.context.support.WithMockUser(roles = "KRT_MEMBER")
class MissionUnitCrewTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @Autowired private UserRepository userRepository;
  @Autowired private MissionRepository missionRepository;
  @Autowired private ShipRepository shipRepository;
  @Autowired private ShipTypeRepository shipTypeRepository;
  @Autowired private JobTypeRepository jobTypeRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;

  private Squadron iridium;

  @MockitoBean private JwtDecoder jwtDecoder;

  private User officerUser;
  private Mission mission;
  private Ship ship;
  private MissionUnit unit;
  private JobType crewJob;

  @BeforeEach
  void setUp() {
    iridium = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

    officerUser = new User();
    officerUser.setId(UUID.randomUUID());
    officerUser.setUsername("officer_crew");
    userRepository.save(officerUser);
    // Post-R9 D3 (V101): home Staffel via membership row.
    OrgUnitMembership iridiumMembership = new OrgUnitMembership();
    iridiumMembership.setId(new OrgUnitMembershipId(officerUser.getId(), Squadron.IRIDIUM_ID));
    iridiumMembership.setUser(officerUser);
    iridiumMembership.setJoinedAt(Instant.now());
    orgUnitMembershipRepository.save(iridiumMembership);

    ShipType shipType = new ShipType();
    shipType.setName("Test Type");
    shipType.setManufacturer(null); // Assuming manufacturer is optional or I need to check
    shipTypeRepository.save(shipType);

    ship = new Ship();

    ship.setOwningOrgUnit(iridium);
    ship.setName("Test Ship Crew");
    ship.setOwner(officerUser);
    ship.setShipType(shipType);
    ship.setInsurance("LTI");
    shipRepository.save(ship);

    mission = new Mission();

    mission.setOwningOrgUnit(iridium);
    mission.setName("Test Mission Crew");
    mission.setStatus("PLANNED");
    mission = missionRepository.save(mission);

    unit = new MissionUnit();
    unit.setMission(mission);
    unit.setShipType(shipType);
    unit.setShip(ship);
    unit.setName("Test Unit");
    mission.getAssignedUnits().add(unit);
    mission = missionRepository.save(mission);
    // Refresh unit ID
    unit = mission.getAssignedUnits().iterator().next();

    crewJob = new JobType();
    crewJob.setName("Gunner");
    crewJob.setArchetype(JobTypeArchetype.CREW);
    jobTypeRepository.save(crewJob);

    Squadron sq = new Squadron();
    sq.setName("Test Sq");
    sq.setShorthand("TSQ");
    squadronRepository.save(sq);
  }

  @Test
  void testAssignGuestAsCrew() throws Exception {
    // 1. Add Guest Participant
    Squadron sq = squadronRepository.findAll().iterator().next();
    String joinJson =
        String.format(
            "{\"guestName\": \"Guest Pilot\", \"comment\": \"Joining\", \"desiredJobTypeId\":"
                + " \"%s\", \"squadronId\": \"%s\"}",
            crewJob.getId(), sq.getId());
    mockMvc
        .perform(
            post("/api/v1/missions/" + mission.getId() + "/participants/add")
                .contentType(MediaType.APPLICATION_JSON)
                .content(joinJson))
        .andExpect(status().isOk());

    Mission updatedMission = missionRepository.findById(mission.getId()).orElseThrow();
    MissionParticipant participant =
        updatedMission.getParticipants().stream()
            .filter(p -> "Guest Pilot".equals(p.getGuestName()))
            .findFirst()
            .orElseThrow();

    // 2. Assign Guest as Crew
    String assignJson =
        String.format(
            "{\"participantId\": \"%s\", \"jobTypeIds\": [\"%s\"]}",
            participant.getId(), crewJob.getId());

    mockMvc
        .perform(
            post("/api/v1/missions/" + mission.getId() + "/units/" + unit.getId() + "/crew")
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
                .content(assignJson))
        .andExpect(status().isOk());

    // Verify
    updatedMission = missionRepository.findById(mission.getId()).orElseThrow();
    MissionUnit updatedUnit =
        updatedMission.getAssignedUnits().stream()
            .filter(u -> u.getId().equals(unit.getId()))
            .findFirst()
            .orElseThrow();
    org.junit.jupiter.api.Assertions.assertEquals(1, updatedUnit.getCrew().size());
    org.junit.jupiter.api.Assertions.assertEquals(
        "Guest Pilot", updatedUnit.getCrew().iterator().next().getParticipant().getGuestName());
  }

  @Test
  void testGetAllShips_Officer_Allowed() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/hangar/ships")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(officerUser.getId().toString()))
                        .authorities(
                            new SimpleGrantedAuthority("ROLE_OFFICER"),
                            new SimpleGrantedAuthority("USER_MANAGE"),
                            new SimpleGrantedAuthority("MISSION_MANAGE"),
                            new SimpleGrantedAuthority("HANGAR_MANAGE"),
                            new SimpleGrantedAuthority("REFINERY_MANAGE"),
                            new SimpleGrantedAuthority("HANGAR_READ"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[?(@.name == 'Test Ship Crew')]").exists());
  }

  @Test
  void testGetAllShips_Guest_Forbidden() throws Exception {
    mockMvc
        .perform(get("/api/v1/hangar/ships")) // Anonymous
        .andExpect(
            status()
                .isUnauthorized()); // Or Forbidden depending on config, usually Unauthorized if no
    // JWT
  }
}
