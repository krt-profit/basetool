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

import de.greluc.krt.profit.basetool.backend.model.StarSystem;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.StarSystemRepository;
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
class StarSystemTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @Autowired private StarSystemRepository starSystemRepository;

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
    officerUser.setUsername("officerSystem");
    userRepository.save(officerUser);

    roleLessUser = new User();
    roleLessUser.setId(UUID.randomUUID());
    roleLessUser.setUsername("guestSystem");
    userRepository.save(roleLessUser);
  }

  @Test
  void testCreateStarSystem_Officer_Forbidden() throws Exception {
    StarSystem system = new StarSystem();
    system.setName("Stanton");
    system.setDescription("A corporate owned system.");

    mockMvc
        .perform(
            post("/api/v1/star-systems")
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
                .content(objectMapper.writeValueAsString(system)))
        .andExpect(status().isForbidden());

    assertEquals(0, starSystemRepository.findAll().size());
  }

  @Test
  void testCreateStarSystem_RoleLess_Forbidden() throws Exception {
    StarSystem system = new StarSystem();
    system.setName("Pyro");

    mockMvc
        .perform(
            post("/api/v1/star-systems")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(roleLessUser.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_NO_ROLE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(system)))
        .andExpect(status().isForbidden());
  }

  @Test
  void testUpdateStarSystem_Officer_Forbidden() throws Exception {
    StarSystem system = new StarSystem();
    system.setName("Stanton");
    system = starSystemRepository.save(system);

    system.setName("Stanton System");

    mockMvc
        .perform(
            put("/api/v1/star-systems/" + system.getId())
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
                .content(objectMapper.writeValueAsString(system)))
        .andExpect(status().isForbidden());

    StarSystem updated = starSystemRepository.findById(system.getId()).orElseThrow();
    assertEquals("Stanton System", updated.getName());
  }

  @Test
  void testDeleteStarSystem_Officer_Forbidden() throws Exception {
    StarSystem system = new StarSystem();
    system.setName("Nyx");
    system = starSystemRepository.save(system);

    mockMvc
        .perform(
            delete("/api/v1/star-systems/" + system.getId())
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

    assertTrue(starSystemRepository.findById(system.getId()).isPresent());
  }
}
