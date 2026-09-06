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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.JobType;
import de.greluc.krt.profit.basetool.backend.model.JobTypeArchetype;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.JobTypeRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
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
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MissionAccessControlTest {

  @Autowired private SquadronRepository squadronRepository;

  private Squadron iridium;

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @Autowired private UserRepository userRepository;

  @Autowired private MissionRepository missionRepository;

  @Autowired private MissionParticipantRepository missionParticipantRepository;

  @Autowired private JobTypeRepository jobTypeRepository;

  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  @MockitoBean private JwtDecoder jwtDecoder;

  private User officerUser;
  private User guestUser;
  private JobType testJobType;

  @BeforeEach
  void setUp() {
    iridium = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

    officerUser = new User();
    officerUser.setId(UUID.randomUUID());
    officerUser.setUsername("officer1");
    userRepository.save(officerUser);
    saveIridiumMembership(officerUser);

    guestUser = new User();
    guestUser.setId(UUID.randomUUID());
    guestUser.setUsername("guest1");
    userRepository.save(guestUser);
    saveIridiumMembership(guestUser);

    testJobType = new JobType();
    testJobType.setName("Test Job");
    testJobType.setArchetype(JobTypeArchetype.MISSION);
    testJobType = jobTypeRepository.save(testJobType);
  }

  /** Post-R9 D3 (V101): home Staffel via membership row. */
  private void saveIridiumMembership(User u) {
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(new OrgUnitMembershipId(u.getId(), Squadron.IRIDIUM_ID));
    m.setUser(u);
    m.setJoinedAt(Instant.now());
    orgUnitMembershipRepository.save(m);
  }

  @Test
  void testCreateMission_Officer_Allowed() throws Exception {
    String json = "{\"name\": \"Officer Mission\", \"status\": \"PLANNED\", \"version\": 0}";

    mockMvc
        .perform(
            post("/api/v1/missions")
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
                .content(json))
        .andExpect(status().isOk());
  }

  @Test
  void testCreateMission_Unauthenticated_Refused() throws Exception {
    String json = "{\"name\": \"Anonymous Mission\", \"status\": \"PLANNED\", \"version\": 0}";

    mockMvc
        .perform(post("/api/v1/missions").contentType(MediaType.APPLICATION_JSON).content(json))
        .andExpect(status().isUnauthorized());
  }

  /**
   * The next-mission banner used to answer an anonymous caller (with 204 on an empty database).
   * REQ-SEC-052 closed the whole mission read surface, so it is turned away at the entry point.
   */
  @Test
  void testGetNextMission_Unauthenticated_Refused() throws Exception {
    mockMvc.perform(get("/api/v1/missions/next")).andExpect(status().isUnauthorized());
  }


  @Test
  void testUpdateParticipant_Self_Allowed() throws Exception {
    Mission mission = new Mission();
    mission.setOwningOrgUnit(iridium);
    mission.setName("Mission");
    mission.setStatus("PLANNED");
    mission = missionRepository.save(mission);

    mockMvc.perform(
        post("/api/v1/missions/" + mission.getId() + "/join")
            .with(jwt().jwt(builder -> builder.subject(guestUser.getId().toString()))));

    // Fetch mission to get participant ID
    Mission m = missionRepository.findById(mission.getId()).orElseThrow();
    MissionParticipant p =
        m.getParticipants().stream()
            .filter(mp -> mp.getUser() != null && mp.getUser().getId().equals(guestUser.getId()))
            .findFirst()
            .orElseThrow();

    String updateJson =
        "{\"desiredMissionJobTypeId\": \""
            + testJobType.getId()
            + "\", \"comment\": \"Ready\", \"version\": 0}";

    mockMvc
        .perform(
            put("/api/v1/missions/" + mission.getId() + "/participants/" + p.getId())
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(guestUser.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateJson))
        .andExpect(status().isOk());
  }

  @Test
  void testUpdateParticipant_OtherGuest_Forbidden() throws Exception {
    Mission mission = new Mission();
    mission.setOwningOrgUnit(iridium);
    mission.setName("Mission");
    mission.setStatus("PLANNED");
    mission = missionRepository.save(mission);

    // guest1 joins
    mockMvc.perform(
        post("/api/v1/missions/" + mission.getId() + "/join")
            .with(jwt().jwt(builder -> builder.subject(guestUser.getId().toString()))));

    User otherGuest = new User();
    otherGuest.setId(UUID.randomUUID());
    otherGuest.setUsername("guest2");
    userRepository.save(otherGuest);
    saveIridiumMembership(otherGuest);

    // Fetch mission to get participant ID
    Mission m = missionRepository.findById(mission.getId()).orElseThrow();
    MissionParticipant p =
        m.getParticipants().stream()
            .filter(mp -> mp.getUser() != null && mp.getUser().getId().equals(guestUser.getId()))
            .findFirst()
            .orElseThrow();

    String updateJson =
        "{\"desiredMissionJobTypeId\": \""
            + testJobType.getId()
            + "\", \"comment\": \"Malicious\", \"version\": 0}";

    // guest-2 tries to update guest-1 -> Now Forbidden
    mockMvc
        .perform(
            put("/api/v1/missions/" + mission.getId() + "/participants/" + p.getId())
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(otherGuest.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateJson))
        .andExpect(status().isForbidden());
  }

  @Test
  void testUpdateParticipant_Officer_Allowed() throws Exception {
    Mission mission = new Mission();
    mission.setOwningOrgUnit(iridium);
    mission.setName("Mission");
    mission.setStatus("PLANNED");
    mission = missionRepository.save(mission);

    // guest1 joins
    mockMvc.perform(
        post("/api/v1/missions/" + mission.getId() + "/join")
            .with(jwt().jwt(builder -> builder.subject(guestUser.getId().toString()))));

    // Fetch mission to get participant ID
    Mission m = missionRepository.findById(mission.getId()).orElseThrow();
    MissionParticipant p =
        m.getParticipants().stream()
            .filter(mp -> mp.getUser() != null && mp.getUser().getId().equals(guestUser.getId()))
            .findFirst()
            .orElseThrow();

    String updateJson =
        "{\"desiredMissionJobTypeId\": \""
            + testJobType.getId()
            + "\", \"comment\": \"Approved\", \"version\": 0}";

    // Officer updates guest-1
    mockMvc
        .perform(
            put("/api/v1/missions/" + mission.getId() + "/participants/" + p.getId())
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
  }

  @Test
  void testUpdateParticipant_AllFields() throws Exception {
    Mission mission = new Mission();
    mission.setOwningOrgUnit(iridium);
    mission.setName("Mission Full");
    mission.setStatus("PLANNED");
    mission = missionRepository.save(mission);

    mockMvc.perform(
        post("/api/v1/missions/" + mission.getId() + "/join")
            .with(jwt().jwt(builder -> builder.subject(guestUser.getId().toString()))));

    // Fetch mission to get participant ID
    Mission m = missionRepository.findById(mission.getId()).orElseThrow();
    MissionParticipant p =
        m.getParticipants().stream()
            .filter(mp -> mp.getUser() != null && mp.getUser().getId().equals(guestUser.getId()))
            .findFirst()
            .orElseThrow();

    // The PLANNED job type is the organisation's assignment - it carries the Einsatzleiter
    // designation - so a self-editing participant who cannot manage the mission is refused it
    // (audit MEDIUM-9). This test asserted the opposite until then: it drove a role-less setting
    // their OWN planned job type and expected 200, which is precisely the self-designation the
    // single-lead rule then held against the real leader.
    String plannedAttempt =
        String.format(
            "{\"desiredMissionJobTypeId\": \"%s\", \"plannedMissionJobTypeId\": \"%s\","
                + " \"comment\": \"Full Update\", \"version\": 0}",
            testJobType.getId(), testJobType.getId());

    mockMvc
        .perform(
            put("/api/v1/missions/" + mission.getId() + "/participants/" + p.getId())
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(guestUser.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(plannedAttempt))
        .andExpect(status().isForbidden());

    // The rest of the payload is still the participant's own to edit.
    String ownFieldsUpdate =
        String.format(
            "{\"desiredMissionJobTypeId\": \"%s\", \"comment\": \"Full Update\","
                + " \"version\": 0}",
            testJobType.getId());

    mockMvc
        .perform(
            put("/api/v1/missions/" + mission.getId() + "/participants/" + p.getId())
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(guestUser.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(ownFieldsUpdate))
        .andExpect(status().isOk());

    // Verification via Repository
    de.greluc.krt.profit.basetool.backend.model.MissionParticipant participant =
        missionRepository.findById(mission.getId()).orElseThrow().getParticipants().stream()
            .filter(mp1 -> mp1.getUser().getId().equals(guestUser.getId()))
            .findFirst()
            .orElseThrow();

    org.junit.jupiter.api.Assertions.assertEquals(
        testJobType.getId(), participant.getDesiredMissionJobType().getId());
    org.junit.jupiter.api.Assertions.assertNull(
        participant.getPlannedMissionJobType(),
        "a self-editing non-manager must not have been able to assign the planned job type");
    org.junit.jupiter.api.Assertions.assertEquals("Full Update", participant.getComment());
  }





  /**
   * Recording an external participant is a member's action now (ADR-0159, decision D4).
   *
   * <p>The endpoint kept its shape — a {@code guestName} without a {@code userId} — and any member
   * who can see the mission may still use it. What went is the caller who had no account at all.
   */
  @Test
  void testAddExternalParticipant_Unauthenticated_Refused() throws Exception {
    Mission mission = new Mission();
    mission.setOwningOrgUnit(iridium);
    mission.setName("Public Mission");
    mission.setStatus("PLANNED");
    mission = missionRepository.save(mission);

    String jsonBody =
        String.format(
            "{\"guestName\": \"John Doe\", \"comment\": \"I want to join\", \"desiredJobTypeId\":"
                + " \"%s\", \"squadronId\": \"%s\"}",
            testJobType.getId(), UUID.randomUUID());

    mockMvc
        .perform(
            post("/api/v1/missions/" + mission.getId() + "/participants/add")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody))
        .andExpect(status().isUnauthorized());
  }


}
