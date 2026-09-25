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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
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

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RoleDescriptionTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @Autowired private RoleRepository roleRepository;

  @Autowired private UserRepository userRepository;

  @MockitoBean private JwtDecoder jwtDecoder;

  private User adminUser;
  private Role testRole;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

    adminUser = new User();
    adminUser.setId(UUID.randomUUID());
    adminUser.setUsername("admin1");
    userRepository.save(adminUser);

    testRole = new Role();
    testRole.setCode("TEST_ROLE");
    testRole.setName("TestRole");
    testRole = roleRepository.save(testRole);
  }

  @Test
  void testUpdateRoleDescription_Admin_Allowed() throws Exception {
    String newDescription = "This is a test description.";

    mockMvc
        .perform(
            put("/api/v1/admin/roles/" + testRole.getName() + "/description")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(adminUser.getId().toString()))
                        .authorities(
                            new SimpleGrantedAuthority("ROLE_ADMIN"),
                            new SimpleGrantedAuthority("ROLE_MANAGE"),
                            new SimpleGrantedAuthority("USER_MANAGE"),
                            new SimpleGrantedAuthority("MISSION_MANAGE"),
                            new SimpleGrantedAuthority("HANGAR_MANAGE"),
                            new SimpleGrantedAuthority("REFINERY_MANAGE")))
                .contentType(MediaType.TEXT_PLAIN)
                .content(newDescription))
        .andExpect(status().isOk());

    Role updatedRole = roleRepository.findByName(testRole.getName()).orElseThrow();
    assertEquals(newDescription, updatedRole.getDescription());
  }

  @Test
  void testUpdateRoleDescription_User_Forbidden() throws Exception {
    String newDescription = "Hacked description.";

    User regularUser = new User();
    regularUser.setId(UUID.randomUUID());
    regularUser.setUsername("user1");
    userRepository.save(regularUser);

    mockMvc
        .perform(
            put("/api/v1/admin/roles/" + testRole.getName() + "/description")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(regularUser.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")))
                .contentType(MediaType.TEXT_PLAIN)
                .content(newDescription))
        .andExpect(status().isForbidden());
  }
}
