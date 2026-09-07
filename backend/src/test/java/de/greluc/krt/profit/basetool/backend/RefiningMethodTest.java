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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.RefiningMethod;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.RefiningMethodRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
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
class RefiningMethodTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @Autowired private RefiningMethodRepository refiningMethodRepository;

  @Autowired private UserRepository userRepository;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  @MockitoBean private JwtDecoder jwtDecoder;

  private User officerUser;
  private User roleLessUser;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

    officerUser = new User();
    officerUser.setId(UUID.randomUUID());
    officerUser.setUsername("officerMethod");
    userRepository.save(officerUser);

    roleLessUser = new User();
    roleLessUser.setId(UUID.randomUUID());
    roleLessUser.setUsername("guestMethod");
    userRepository.save(roleLessUser);
  }

  @Test
  void testCreateRefiningMethod_Officer_Forbidden() throws Exception {
    RefiningMethod method = new RefiningMethod();
    method.setName("New Method");
    method.setDescription("Best method");

    mockMvc
        .perform(
            post("/api/v1/refining-methods")
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
                .content(objectMapper.writeValueAsString(method)))
        .andExpect(status().isForbidden());

    assertEquals(0, refiningMethodRepository.findAll().size());
  }

  @Test
  void testCreateRefiningMethod_RoleLess_Forbidden() throws Exception {
    RefiningMethod method = new RefiningMethod();
    method.setName("Hacked Method");

    mockMvc
        .perform(
            post("/api/v1/refining-methods")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(roleLessUser.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_NO_ROLE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(method)))
        .andExpect(status().isForbidden());
  }

  @Test
  void testUpdateRefiningMethod_Officer_Forbidden() throws Exception {
    RefiningMethod method = new RefiningMethod();
    method.setName("Old Name");
    method = refiningMethodRepository.save(method);

    method.setName("New Name");

    mockMvc
        .perform(
            put("/api/v1/refining-methods/" + method.getId())
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
                .content(objectMapper.writeValueAsString(method)))
        .andExpect(status().isForbidden());

    RefiningMethod loaded = refiningMethodRepository.findById(method.getId()).orElseThrow();
    assertEquals("New Name", loaded.getName());
  }

  @Test
  void testDeleteRefiningMethod_Officer_Forbidden() throws Exception {
    RefiningMethod method = new RefiningMethod();
    method.setName("To Delete");
    method = refiningMethodRepository.save(method);

    mockMvc
        .perform(
            delete("/api/v1/refining-methods/" + method.getId())
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

    assertTrue(refiningMethodRepository.findById(method.getId()).isPresent());
  }
}
