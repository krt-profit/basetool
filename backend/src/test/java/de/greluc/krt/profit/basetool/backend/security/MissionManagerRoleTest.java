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

import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionDto;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.service.CustomJwtGrantedAuthoritiesConverter;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MissionManagerRoleTest {

  private MockMvc mockMvc;

  @Autowired private SquadronRepository squadronRepository;

  private Squadron iridium;

  @Autowired private WebApplicationContext context;

  @Autowired private UserRepository userRepository;

  @Autowired private MissionRepository missionRepository;

  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;

  private JsonMapper objectMapper = JsonMapper.builder().build();

  @Autowired private CustomJwtGrantedAuthoritiesConverter converter;

  @MockitoBean private JwtDecoder jwtDecoder;

  private User owner;
  private User otherMember;
  private User managerMember;
  private Mission mission;

  @BeforeEach
  void setUp() {
    iridium = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

    owner = new User();
    owner.setId(UUID.randomUUID());
    owner.setUsername("owner");
    userRepository.save(owner);
    saveIridiumMembership(owner, false, false);

    otherMember = new User();
    otherMember.setId(UUID.randomUUID());
    otherMember.setUsername("other");
    userRepository.save(otherMember);
    saveIridiumMembership(otherMember, false, false);

    managerMember = new User();
    managerMember.setId(UUID.randomUUID());
    managerMember.setUsername("manager");
    userRepository.save(managerMember);
    // Post-R9 D3 (V101): the MissionManager flag lives on the Staffel membership row only.
    saveIridiumMembership(managerMember, false, true);

    mission = new Mission();

    mission.setOwningOrgUnit(iridium);
    mission.setName("Test Mission");
    mission.setOwner(owner);
    mission.setPlannedStartTime(Instant.now().plusSeconds(3600));
    missionRepository.save(mission);
  }

  private String createMissionJson() throws Exception {
    MissionDto dto =
        new MissionDto(
            mission.getId(),
            "Updated Name",
            null,
            null,
            "PLANNED",
            null,
            mission.getPlannedStartTime(),
            null,
            null,
            null,
            false,
            Collections.emptySet(),
            Collections.emptyList(),
            Collections.emptyList(),
            null,
            null,
            Collections.emptySet(),
            false,
            false,
            0L,
            0L,
            0L,
            0L,
            0,
            0,
            null,
            null,
            null,
            null,
            0L,
            Collections.emptyList(), // steps
            0L, // stepsVersion
            Collections.emptyList(), // objectives
            0L, // objectivesVersion
            null); // meetingPoint
    return objectMapper.writeValueAsString(dto);
  }

  @Test
  void ownerShouldBeAbleToUpdateMission() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/missions/" + mission.getId())
                .with(jwt().jwt(builder -> builder.subject(owner.getId().toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createMissionJson()))
        .andExpect(status().isOk());
  }

  @Test
  void missionManagerShouldBeAbleToUpdateMission() throws Exception {
    // Post-V89 every Mission carries a non-null owning_squadron_id, so the canManageMission
    // gate now ALWAYS evaluates the squadron-scope check on top of the role check. The
    // ROLE_MISSION_MANAGER authority alone is no longer enough — the JWT subject must
    // resolve to a User whose squadron matches the mission's. managerMember is in IRIDIUM
    // (see @BeforeEach), same as the test mission.
    mockMvc
        .perform(
            put("/api/v1/missions/" + mission.getId())
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(managerMember.getId().toString()))
                        .authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_MISSION_MANAGER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createMissionJson()))
        .andExpect(status().isOk());
  }

  @Test
  void otherMemberShouldNotBeAbleToUpdateMission() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/missions/" + mission.getId())
                .with(jwt().jwt(builder -> builder.subject(otherMember.getId().toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createMissionJson()))
        .andExpect(status().isForbidden());
  }

  @Test
  void delegatedManagerShouldBeAbleToUpdateMission() throws Exception {
    mission.getManagers().add(otherMember);
    missionRepository.save(mission);

    mockMvc
        .perform(
            put("/api/v1/missions/" + mission.getId())
                .with(jwt().jwt(builder -> builder.subject(otherMember.getId().toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createMissionJson()))
        .andExpect(status().isOk());
  }

  @Test
  void adminShouldBeAbleToUpdateMission() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/missions/" + mission.getId())
                .with(
                    jwt()
                        .authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createMissionJson()))
        .andExpect(status().isOk());
  }

  @Test
  void missionDtoShouldHaveCanEditTrueForAdmin() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/missions/" + mission.getId())
                .with(
                    jwt()
                        .authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_ADMIN"))))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.canEdit")
                .value(true));
  }

  @Test
  void missionDtoShouldHaveCanEditTrueForMissionManager() throws Exception {
    // Same V89-driven contract change as missionManagerShouldBeAbleToUpdateMission: the
    // squadron-scope gate runs on every canEditMission evaluation now that the column is
    // NOT NULL, so the JWT subject must resolve to a User in the mission's squadron.
    mockMvc
        .perform(
            get("/api/v1/missions/" + mission.getId())
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(managerMember.getId().toString()))
                        .authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_MISSION_MANAGER"))))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.canEdit")
                .value(true));
  }

  @Test
  void missionDtoShouldHaveCanEditFalseForRegularMember() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/missions/" + mission.getId())
                .with(jwt().jwt(builder -> builder.subject(otherMember.getId().toString()))))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.canEdit")
                .value(false));
  }

  @Test
  void converterShouldAddMissionManagerRole() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);
    user.setUsername("manager_user");
    userRepository.save(user);
    userRepository.flush();
    // Post-R9 D3 (V101): the MissionManager flag lives on the Staffel membership row only.
    saveIridiumMembership(user, false, true);

    Jwt jwt =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("sub", userId.toString())
            .claim("preferred_username", "manager_user")
            .build();

    java.util.Collection<GrantedAuthority> authorities = converter.convert(jwt);
    org.junit.jupiter.api.Assertions.assertTrue(
        authorities.stream().anyMatch(a -> a.getAuthority().equals("ROLE_MISSION_MANAGER")),
        "Should have ROLE_MISSION_MANAGER");
  }

  /** Post-R9 D3 (V101): home Staffel + flag values via the membership row. */
  private void saveIridiumMembership(User u, boolean logistician, boolean missionManager) {
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(new OrgUnitMembershipId(u.getId(), Squadron.IRIDIUM_ID));
    m.setUser(u);
    m.setJoinedAt(Instant.now());
    m.setLogistician(logistician);
    m.setMissionManager(missionManager);
    orgUnitMembershipRepository.save(m);
    orgUnitMembershipRepository.flush();
  }
}
