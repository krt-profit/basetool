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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionParticipantService;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionService;
import de.greluc.krt.profit.basetool.backend.mission.internal.UpdateParticipantRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class MissionTimeTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @Autowired private MissionRepository missionRepository;

  @Autowired private UserRepository userRepository;

  @Autowired private MissionParticipantRepository missionParticipantRepository;

  @Autowired private MissionService missionService;

  @Autowired private MissionParticipantService missionParticipantService;

  private final JsonMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

  private User officerUser;
  private User memberUser;
  private Mission mission;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

    officerUser = new User();
    officerUser.setId(UUID.randomUUID());
    officerUser.setUsername("officerTime");
    userRepository.save(officerUser);

    memberUser = new User();
    memberUser.setId(UUID.randomUUID());
    memberUser.setUsername("memberTime");
    userRepository.save(memberUser);

    mission = new Mission();
    mission.setName("Time Test Mission");
    mission.setStatus("PLANNED");
    mission = missionRepository.save(mission);

    missionParticipantService.addParticipant(
        mission.getId(), memberUser.getId(), null, null, null, null, null);
  }

  private UUID getParticipantId(User user) {
    Mission m = missionRepository.findById(mission.getId()).orElseThrow();
    return m.getParticipants().stream()
        .filter(p -> p.getUser() != null && p.getUser().getId().equals(user.getId()))
        .findFirst()
        .orElseThrow()
        .getId();
  }

  @Test
  void testParticipantTimeConstraints() throws Exception {
    Instant missionStart = Instant.now().minus(2, java.time.temporal.ChronoUnit.HOURS);
    mission.setActualStartTime(missionStart);
    missionRepository.save(mission);

    UpdateParticipantRequest request =
        new UpdateParticipantRequest(
            null, null, null, null, missionStart.minus(1, ChronoUnit.HOURS), null, null, null, 0L);

    mockMvc
        .perform(
            put("/api/v1/missions/"
                    + mission.getId()
                    + "/participants/"
                    + getParticipantId(memberUser))
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(memberUser.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk());
  }

  @Test
  void testParticipantTimeConstraints_Success() throws Exception {
    Instant missionStart = Instant.now().minus(2, java.time.temporal.ChronoUnit.HOURS);
    mission.setActualStartTime(missionStart);
    missionRepository.save(mission);

    UpdateParticipantRequest request =
        new UpdateParticipantRequest(
            null, null, null, null, missionStart.plus(1, ChronoUnit.HOURS), null, null, null, 0L);

    mockMvc
        .perform(
            put("/api/v1/missions/"
                    + mission.getId()
                    + "/participants/"
                    + getParticipantId(memberUser))
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(memberUser.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk());

    Mission updated = missionRepository.findById(mission.getId()).orElseThrow();
    MissionParticipant p = updated.getParticipants().stream().findFirst().orElseThrow();
    assertEquals(missionStart.plus(1, ChronoUnit.HOURS), p.getStartTime());
  }
}
