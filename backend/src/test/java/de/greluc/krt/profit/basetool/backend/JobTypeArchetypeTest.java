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

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.JobType;
import de.greluc.krt.profit.basetool.backend.model.JobTypeArchetype;
import de.greluc.krt.profit.basetool.backend.repository.JobTypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@Transactional
public class JobTypeArchetypeTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @Autowired private JobTypeRepository jobTypeRepository;

  @Autowired
  private de.greluc.krt.profit.basetool.backend.repository.MissionRepository missionRepository;

  @Autowired private CacheManager cacheManager;

  @BeforeEach
  void setUp() {
    if (cacheManager != null) {
      cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
    }
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

    missionRepository.deleteAll();
    jobTypeRepository.deleteAll();

    JobType missionJob = new JobType();
    missionJob.setName("Mission Leader");
    missionJob.setArchetype(JobTypeArchetype.MISSION);
    jobTypeRepository.save(missionJob);

    JobType crewJob = new JobType();
    crewJob.setName("Gunner");
    crewJob.setArchetype(JobTypeArchetype.CREW);
    jobTypeRepository.save(crewJob);
    jobTypeRepository.flush();
  }

  @Test
  void testFilterByMissionArchetype() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/job-types")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")))
                .param("archetype", "MISSION"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content", hasSize(1)))
        .andExpect(jsonPath("$.content[0].name").value("Mission Leader"))
        .andExpect(jsonPath("$.content[0].archetype").value("MISSION"));
  }

  @Test
  void testFilterByCrewArchetype() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/job-types")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")))
                .param("archetype", "CREW"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content", hasSize(1)))
        .andExpect(jsonPath("$.content[0].name").value("Gunner"))
        .andExpect(jsonPath("$.content[0].archetype").value("CREW"));
  }

  @Test
  void testNoFilterReturnsAll() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/job-types")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content", hasSize(2)));
  }
}
