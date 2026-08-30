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

  @Autowired
  private de.greluc.krt.profit.basetool.backend.service.GuestParticipantTokenService
      guestParticipantTokenService;

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
  void testCreateMission_Unauthenticated_Forbidden() throws Exception {
    String json = "{\"name\": \"Anonymous Mission\", \"status\": \"PLANNED\", \"version\": 0}";

    mockMvc
        .perform(post("/api/v1/missions").contentType(MediaType.APPLICATION_JSON).content(json))
        .andExpect(status().isForbidden());
  }

  @Test
  void testGetNextMission_Anonymous_Allowed() throws Exception {
    mockMvc
        .perform(get("/api/v1/missions/next"))
        .andExpect(status().isNoContent()); // Assuming no missions in test db
  }

  @Test
  void testJoinMission_Guest_Allowed() throws Exception {
    Mission mission = new Mission();
    mission.setOwningOrgUnit(iridium);
    mission.setName("Open Mission");
    mission.setStatus("PLANNED");
    mission = missionRepository.save(mission);

    mockMvc
        .perform(
            post("/api/v1/missions/" + mission.getId() + "/join")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(guestUser.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_GUEST"))))
        .andExpect(status().isOk());
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
                        .authorities(new SimpleGrantedAuthority("ROLE_GUEST")))
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
                        .authorities(new SimpleGrantedAuthority("ROLE_GUEST")))
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
    // (audit MEDIUM-9). This test asserted the opposite until then: it drove a ROLE_GUEST setting
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
                        .authorities(new SimpleGrantedAuthority("ROLE_GUEST")))
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
                        .authorities(new SimpleGrantedAuthority("ROLE_GUEST")))
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

  @Test
  void testSearchMissions_Guest_Default_ShouldSeeOnlyPlannedAndActive() throws Exception {
    missionRepository.deleteAll(); // Ensure clean state for counting

    Mission m1 = new Mission();

    m1.setOwningOrgUnit(iridium);
    m1.setName("M1");
    m1.setStatus("PLANNED");
    missionRepository.save(m1);
    Mission m2 = new Mission();
    m2.setOwningOrgUnit(iridium);
    m2.setName("M2");
    m2.setStatus("ACTIVE");
    missionRepository.save(m2);
    Mission m3 = new Mission();
    m3.setOwningOrgUnit(iridium);
    m3.setName("M3");
    m3.setStatus("COMPLETED");
    missionRepository.save(m3);
    Mission m4 = new Mission();
    m4.setOwningOrgUnit(iridium);
    m4.setName("M4");
    m4.setStatus("CANCELLED");
    missionRepository.save(m4);

    mockMvc
        .perform(get("/api/v1/missions/search"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(2));
  }

  @Test
  void testSearchMissions_Guest_ExplicitPast_ShouldBeIgnored() throws Exception {
    missionRepository.deleteAll();

    Mission m1 = new Mission();

    m1.setOwningOrgUnit(iridium);
    m1.setName("M1");
    m1.setStatus("COMPLETED");
    missionRepository.save(m1);
    Mission m2 = new Mission();
    m2.setOwningOrgUnit(iridium);
    m2.setName("M2");
    m2.setStatus("CANCELLED");
    missionRepository.save(m2);

    // Guest requests completed/cancelled explicitly -> Should receive empty list (200 OK)
    mockMvc
        .perform(
            get("/api/v1/missions/search")
                .param("status", "COMPLETED")
                .param("status", "CANCELLED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(0));
  }

  @Test
  void testGetMissionById_Guest_Planned_Allowed() throws Exception {
    Mission m = new Mission();
    m.setOwningOrgUnit(iridium);
    m.setName("Public");
    m.setStatus("PLANNED");
    m = missionRepository.save(m);

    mockMvc.perform(get("/api/v1/missions/" + m.getId())).andExpect(status().isOk());
  }

  @Test
  void testGetMissionById_Guest_Completed_Forbidden() throws Exception {
    Mission m = new Mission();
    m.setOwningOrgUnit(iridium);
    m.setName("Secret");
    m.setStatus("COMPLETED");
    m = missionRepository.save(m);

    mockMvc.perform(get("/api/v1/missions/" + m.getId())).andExpect(status().isForbidden());
  }

  @Test
  void testAddParticipantPublic_Anonymous_Allowed() throws Exception {
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
        .andExpect(status().isOk());
  }

  @Test
  void testUpdateParticipant_AnonymousGuest_WithToken_Allowed() throws Exception {
    // Security audit M1 / REQ-SEC-018: an anonymous guest may edit their own sign-up only by
    // presenting the per-row capability token (X-Guest-Edit-Token) minted at create time.
    Mission mission = new Mission();
    mission.setOwningOrgUnit(iridium);
    mission.setName("Mission");
    mission.setStatus("PLANNED");
    mission = missionRepository.save(mission);

    String token = guestParticipantTokenService.generateToken();
    MissionParticipant participant = new MissionParticipant();
    participant.setMission(mission);
    participant.setUser(null); // Guest
    participant.setGuestName("Guest User");
    participant.setGuestEditTokenHash(guestParticipantTokenService.hashToken(token));
    participant = missionParticipantRepository.save(participant);
    mission.getParticipants().add(participant);
    missionRepository.save(mission);

    // When/Then
    mockMvc
        .perform(
            put("/api/v1/missions/" + mission.getId() + "/participants/" + participant.getId())
                .header("X-Guest-Edit-Token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"guestName\": \"New Name\", \"version\": 0}"))
        .andExpect(status().isOk());
  }

  @Test
  void testUpdatePayoutPreference_AnonymousGuest_WithToken_Allowed() throws Exception {
    // Security audit M1 / REQ-SEC-018: payout-preference is sticky and finance-relevant, so the
    // guest-row gate (token or mission-manager) applies here too.
    Mission mission = new Mission();
    mission.setOwningOrgUnit(iridium);
    mission.setName("Mission");
    mission.setStatus("PLANNED");
    mission = missionRepository.save(mission);

    String token = guestParticipantTokenService.generateToken();
    MissionParticipant participant = new MissionParticipant();
    participant.setMission(mission);
    participant.setUser(null); // Guest
    participant.setGuestName("Guest User");
    participant.setGuestEditTokenHash(guestParticipantTokenService.hashToken(token));
    participant = missionParticipantRepository.save(participant);
    mission.getParticipants().add(participant);
    missionRepository.save(mission);

    // When/Then
    mockMvc
        .perform(
            put("/api/v1/missions/"
                    + mission.getId()
                    + "/participants/"
                    + participant.getId()
                    + "/payout-preference")
                .header("X-Guest-Edit-Token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"preference\": \"PAYOUT\", \"version\": 0}"))
        .andExpect(status().isOk());
  }
}
