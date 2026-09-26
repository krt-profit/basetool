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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.time.LocalDate;
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
class UserJoinDateTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @Autowired private UserRepository userRepository;

  @MockitoBean private JwtDecoder jwtDecoder;

  private User testUser;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

    testUser = new User();
    testUser.setId(UUID.randomUUID());
    testUser.setUsername("joindate_testmember");
    testUser.setRank(1);
    userRepository.save(testUser);
  }

  @Test
  void shouldSetJoinDate_WhenAdminUpdatesAttributes() throws Exception {
    String joinDate = "2024-03-15";
    String updateJson =
        "{\"rank\": 5, \"version\": "
            + testUser.getVersion()
            + ", \"joinDate\": \""
            + joinDate
            + "\"}";

    mockMvc
        .perform(
            put("/api/v1/users/" + testUser.getId() + "/attributes")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateJson))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.joinDate").value(joinDate));
  }

  @Test
  void shouldForbidJoinDateUpdate_WhenOfficerRole() throws Exception {
    String joinDate = "2023-06-01";
    String updateJson =
        "{\"rank\": 3, \"version\": "
            + testUser.getVersion()
            + ", \"joinDate\": \""
            + joinDate
            + "\"}";

    mockMvc
        .perform(
            put("/api/v1/users/" + testUser.getId() + "/attributes")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OFFICER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateJson))
        .andExpect(status().isForbidden());
  }

  @Test
  void shouldClearJoinDate_WhenNullIsSent() throws Exception {
    testUser.setJoinDate(LocalDate.of(2022, 1, 1));
    userRepository.save(testUser);
    userRepository.flush();

    String updateJson =
        "{\"rank\": 1, \"version\": " + testUser.getVersion() + ", \"joinDate\": null}";

    mockMvc
        .perform(
            put("/api/v1/users/" + testUser.getId() + "/attributes")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateJson))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.joinDate").doesNotExist());

    User updated = userRepository.findById(testUser.getId()).orElseThrow();
    assertThat(updated.getJoinDate()).isNull();
  }

  @Test
  void shouldForbidJoinDateUpdate_WhenMemberRole() throws Exception {
    String updateJson =
        "{\"rank\": 1, \"version\": " + testUser.getVersion() + ", \"joinDate\": \"2024-01-01\"}";

    mockMvc
        .perform(
            put("/api/v1/users/" + testUser.getId() + "/attributes")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateJson))
        .andExpect(status().isForbidden());
  }

  @Test
  void shouldReturnJoinDate_InResponseDto_WhenSet() throws Exception {
    testUser.setJoinDate(LocalDate.of(2021, 5, 20));
    userRepository.save(testUser);

    String updateJson = "{\"rank\": 1, \"version\": " + testUser.getVersion() + "}";

    mockMvc
        .perform(
            put("/api/v1/users/" + testUser.getId() + "/attributes")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateJson))
        .andExpect(status().isOk());
  }
}
