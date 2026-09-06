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

import de.greluc.krt.profit.basetool.backend.model.*;
import de.greluc.krt.profit.basetool.backend.model.dto.JobTypeDto;
import de.greluc.krt.profit.basetool.backend.repository.*;
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
class JobTypeTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @Autowired private JobTypeRepository jobTypeRepository;

  @Autowired private UserRepository userRepository;

  @Autowired private MissionRepository missionRepository;

  @Autowired private MissionParticipantRepository missionParticipantRepository;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  @MockitoBean private JwtDecoder jwtDecoder;

  private User officerUser;
  private User roleLessUser;
  private User adminUser;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

    officerUser = new User();
    officerUser.setId(UUID.randomUUID());
    officerUser.setUsername("officerJob");
    userRepository.save(officerUser);

    roleLessUser = new User();
    roleLessUser.setId(UUID.randomUUID());
    roleLessUser.setUsername("guestJob");
    userRepository.save(roleLessUser);

    adminUser = new User();
    adminUser.setId(UUID.randomUUID());
    adminUser.setUsername("adminJob");
    userRepository.save(adminUser);
  }

  @Test
  void testCreateJobType_Officer_Forbidden() throws Exception {
    JobTypeDto jobType =
        new JobTypeDto(
            null, "Pilot", "Flies the ship", JobTypeArchetype.CREW, null, true, false, false, null);

    mockMvc
        .perform(
            post("/api/v1/job-types")
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
                .content(objectMapper.writeValueAsString(jobType)))
        .andExpect(status().isForbidden());

    assertEquals(0, jobTypeRepository.findAll().size());
  }

  @Test
  void testCreateJobType_RoleLess_Forbidden() throws Exception {
    JobTypeDto jobType =
        new JobTypeDto(
            null, "Hacker", null, JobTypeArchetype.MISSION, null, true, false, false, null);

    mockMvc
        .perform(
            post("/api/v1/job-types")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(roleLessUser.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_NO_ROLE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(jobType)))
        .andExpect(status().isForbidden());
  }

  @Test
  void testCreateJobType_WithParent() throws Exception {
    // Create parent
    JobType parent = new JobType();
    parent.setName("Engineer");
    parent.setArchetype(JobTypeArchetype.CREW);
    parent = jobTypeRepository.save(parent);

    // Create child DTO referencing parent by ID
    JobTypeDto child =
        new JobTypeDto(
            null,
            "Power Plant Engineer",
            null,
            JobTypeArchetype.CREW,
            parent.getId(),
            true,
            false,
            false,
            null);

    mockMvc
        .perform(
            post("/api/v1/job-types")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(adminUser.getId().toString()))
                        .authorities(
                            new SimpleGrantedAuthority("ROLE_ADMIN"),
                            new SimpleGrantedAuthority("USER_MANAGE"),
                            new SimpleGrantedAuthority("MISSION_MANAGE"),
                            new SimpleGrantedAuthority("HANGAR_MANAGE"),
                            new SimpleGrantedAuthority("REFINERY_MANAGE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(child)))
        .andExpect(status().isOk());

    JobType savedChild =
        jobTypeRepository.findAll().stream()
            .filter(j -> j.getName().equals("Power Plant Engineer"))
            .findFirst()
            .orElseThrow();

    assertNotNull(savedChild.getParent());
    assertEquals(parent.getId(), savedChild.getParent().getId());
  }

  @Test
  void testCreateJobType_WithEmptyParent_ShouldWork() throws Exception {
    JobTypeDto jobType =
        new JobTypeDto(
            null, "Test Empty Parent", null, JobTypeArchetype.CREW, null, true, false, false, null);

    mockMvc
        .perform(
            post("/api/v1/job-types")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(adminUser.getId().toString()))
                        .authorities(
                            new SimpleGrantedAuthority("ROLE_ADMIN"),
                            new SimpleGrantedAuthority("USER_MANAGE"),
                            new SimpleGrantedAuthority("MISSION_MANAGE"),
                            new SimpleGrantedAuthority("HANGAR_MANAGE"),
                            new SimpleGrantedAuthority("REFINERY_MANAGE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(jobType)))
        .andExpect(status().isOk());
  }

  @Test
  void testCreateJobType_WithoutArchetype_ShouldFail() throws Exception {
    JobTypeDto jobType =
        new JobTypeDto(null, "Ghost Job", null, null, null, true, false, false, null);
    mockMvc
        .perform(
            post("/api/v1/job-types")
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
                .content(objectMapper.writeValueAsString(jobType)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void testDeleteJobType_SoftDelete_Success() throws Exception {
    // Given
    JobType parent = new JobType();
    parent.setName("Parent Job");
    parent.setArchetype(JobTypeArchetype.CREW);
    parent = jobTypeRepository.save(parent);

    JobType child = new JobType();
    child.setName("Child Job");
    child.setArchetype(JobTypeArchetype.CREW);
    child.setParent(parent);
    jobTypeRepository.save(child);

    // When / Then
    mockMvc
        .perform(
            delete("/api/v1/job-types/" + parent.getId())
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(adminUser.getId().toString()))
                        .authorities(
                            new SimpleGrantedAuthority("ROLE_ADMIN"),
                            new SimpleGrantedAuthority("USER_MANAGE"),
                            new SimpleGrantedAuthority("MISSION_MANAGE"),
                            new SimpleGrantedAuthority("HANGAR_MANAGE"),
                            new SimpleGrantedAuthority("REFINERY_MANAGE"))))
        .andExpect(status().isOk());

    // Check if soft deleted
    JobType softDeletedParent = jobTypeRepository.findById(parent.getId()).orElseThrow();
    assertFalse(softDeletedParent.isActive());

    // Child still has reference
    JobType updatedChild = jobTypeRepository.findById(child.getId()).orElseThrow();
    assertNotNull(updatedChild.getParent());
  }

  @Test
  void testDeleteJobType_SoftDelete_WithParticipant_Success() throws Exception {
    // Given
    JobType jobType = new JobType();
    jobType.setName("Turret Gunner");
    jobType.setArchetype(JobTypeArchetype.CREW);
    jobType = jobTypeRepository.save(jobType);

    Mission mission = new Mission();
    mission.setName("Test Mission");
    mission.setStatus("PLANNED");
    mission.setPlannedStartTime(Instant.now());
    mission = missionRepository.save(mission);

    MissionParticipant participant = new MissionParticipant();
    participant.setMission(mission);
    participant.setGuestName("Test Guest");
    participant.setDesiredMissionJobType(jobType);
    participant = missionParticipantRepository.save(participant);

    // When / Then
    mockMvc
        .perform(
            delete("/api/v1/job-types/" + jobType.getId())
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(adminUser.getId().toString()))
                        .authorities(
                            new SimpleGrantedAuthority("ROLE_ADMIN"),
                            new SimpleGrantedAuthority("USER_MANAGE"),
                            new SimpleGrantedAuthority("MISSION_MANAGE"),
                            new SimpleGrantedAuthority("HANGAR_MANAGE"),
                            new SimpleGrantedAuthority("REFINERY_MANAGE"))))
        .andExpect(status().isOk());

    JobType softDeletedJobType = jobTypeRepository.findById(jobType.getId()).orElseThrow();
    assertFalse(softDeletedJobType.isActive());

    MissionParticipant updatedParticipant =
        missionParticipantRepository.findById(participant.getId()).orElseThrow();
    assertNotNull(updatedParticipant.getDesiredMissionJobType());
  }

  @Test
  void testActivateJobType_Success() throws Exception {
    // Given
    JobType jobType = new JobType();
    jobType.setName("Inactive Job");
    jobType.setArchetype(JobTypeArchetype.CREW);
    jobType.setActive(false);
    jobType = jobTypeRepository.save(jobType);

    // When / Then
    // Officer cannot activate
    mockMvc
        .perform(
            post("/api/v1/job-types/" + jobType.getId() + "/activate")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(officerUser.getId().toString()))
                        .authorities(
                            new SimpleGrantedAuthority("ROLE_OFFICER"),
                            new SimpleGrantedAuthority("USER_MANAGE"),
                            new SimpleGrantedAuthority("MISSION_MANAGE"),
                            new SimpleGrantedAuthority("HANGAR_MANAGE"),
                            new SimpleGrantedAuthority("REFINERY_MANAGE"))))
        .andExpect(status().isForbidden());

    // Admin can activate
    mockMvc
        .perform(
            post("/api/v1/job-types/" + jobType.getId() + "/activate")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(adminUser.getId().toString()))
                        .authorities(
                            new SimpleGrantedAuthority("ROLE_ADMIN"),
                            new SimpleGrantedAuthority("ROLE_OFFICER"),
                            new SimpleGrantedAuthority("USER_MANAGE"),
                            new SimpleGrantedAuthority("MISSION_MANAGE"),
                            new SimpleGrantedAuthority("HANGAR_MANAGE"),
                            new SimpleGrantedAuthority("REFINERY_MANAGE"))))
        .andExpect(status().isOk());

    JobType activatedJobType = jobTypeRepository.findById(jobType.getId()).orElseThrow();
    assertTrue(activatedJobType.isActive());
  }
}
