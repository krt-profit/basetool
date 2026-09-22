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

package de.greluc.krt.profit.basetool.backend.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.*;
import de.greluc.krt.profit.basetool.backend.repository.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MissionManagerJobTypeTest {

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @Autowired private UserRepository userRepository;

  @Autowired private MissionRepository missionRepository;

  @Autowired private JobTypeRepository jobTypeRepository;

  @Autowired private MissionParticipantRepository participantRepository;

  @Autowired private MissionUnitRepository missionUnitRepository;

  @Autowired private ShipTypeRepository shipTypeRepository;

  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;

  @Autowired private SquadronRepository squadronRepository;

  private JsonMapper objectMapper = JsonMapper.builder().build();

  private User manager;
  private Mission mission;
  private JobType missionJobType;
  private JobType crewJobType;
  private MissionParticipant participant;
  private MissionUnit unit;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

    manager = new User();
    manager.setId(UUID.randomUUID());
    manager.setUsername("manager");
    userRepository.save(manager);
    // Post-R9 D3 (V101): MissionManager flag lives on the Staffel membership row only.
    OrgUnitMembership iridiumMembership = new OrgUnitMembership();
    iridiumMembership.setId(new OrgUnitMembershipId(manager.getId(), Squadron.IRIDIUM_ID));
    iridiumMembership.setUser(manager);
    iridiumMembership.setJoinedAt(Instant.now());
    iridiumMembership.setMissionManager(true);
    orgUnitMembershipRepository.save(iridiumMembership);

    missionJobType = new JobType();
    missionJobType.setName("Test Mission Job");
    missionJobType.setArchetype(JobTypeArchetype.MISSION);
    missionJobType.setActive(true);
    jobTypeRepository.save(missionJobType);

    crewJobType = new JobType();
    crewJobType.setName("Test Crew Job");
    crewJobType.setArchetype(JobTypeArchetype.CREW);
    crewJobType.setActive(true);
    jobTypeRepository.save(crewJobType);

    mission = new Mission();
    mission.setName("Test Mission");
    mission.setOwner(manager);
    mission.setPlannedStartTime(Instant.now().plusSeconds(3600));
    // V99 made owning_org_unit_id NOT NULL — anchor the mission to IRIDIUM so the direct save
    // does not trip the constraint.
    mission.setOwningOrgUnit(squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow());
    missionRepository.save(mission);

    participant = new MissionParticipant();
    participant.setMission(mission);
    participant.setUser(manager);
    participantRepository.save(participant);

    mission.getParticipants().add(participant);

    ShipType st = new ShipType();
    st.setName("Test Ship");
    shipTypeRepository.save(st);

    unit = new MissionUnit();
    unit.setName("Unit 1");
    unit.setMission(mission);
    unit.setShipType(st);
    missionUnitRepository.save(unit);

    mission.getAssignedUnits().add(unit);
    missionRepository.save(mission);
  }

  @Test
  void missionManagerShouldBeAbleToAssignJobType() throws Exception {
    Map<String, Object> updateRequest = new HashMap<>();
    updateRequest.put("plannedMissionJobTypeId", missionJobType.getId().toString());
    updateRequest.put("version", participant.getVersion());

    mockMvc
        .perform(
            put("/api/v1/missions/"
                    + mission.getId()
                    + "/participants/"
                    + participant.getId()
                    + "/slim")
                .with(
                    jwt()
                        .jwt(b -> b.subject(manager.getId().toString()))
                        .authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_MISSION_MANAGER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateRequest)))
        .andExpect(status().isOk());
  }

  @Test
  void missionManagerShouldBeAbleToAddCrew() throws Exception {
    Map<String, Object> addCrewRequest = new HashMap<>();
    addCrewRequest.put("participantId", participant.getId().toString());
    addCrewRequest.put("jobTypeIds", List.of(crewJobType.getId().toString()));

    mockMvc
        .perform(
            post("/api/v1/missions/" + mission.getId() + "/units/" + unit.getId() + "/crew/slim")
                .with(
                    jwt()
                        .jwt(b -> b.subject(manager.getId().toString()))
                        .authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_MISSION_MANAGER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(addCrewRequest)))
        .andExpect(status().isOk());
  }
}
