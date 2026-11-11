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

package de.greluc.krt.profit.basetool.backend.identity.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.identity.api.Role;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Permissions;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/** The independently versioned permission and description writes of the role REST surface. */
@SpringBootTest
@Transactional
class RoleSectionVersionIntegrationTest {

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;

  private MockMvc mockMvc;
  private User admin;
  private Role role;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    admin = new User();
    admin.setId(UUID.randomUUID());
    admin.setUsername("role-section-admin");
    userRepository.saveAndFlush(admin);
    role = new Role();
    role.setCode("ROLE_SECTION_TEST");
    role.setName("Role section test");
    roleRepository.saveAndFlush(role);
  }

  @Test
  @DisplayName("a permission edit echoes its new counter and leaves the description counter alone")
  void permissionEditAdvancesOnlyItsSection() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/roles/{name}/permissions", role.getName())
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(admin.getId().toString()))
                        .authorities(new SimpleGrantedAuthority(Roles.authority(Roles.ADMIN))))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissions\":[\"" + Permissions.HANGAR_READ + "\"],\"version\":0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.permissions[0]").value(Permissions.HANGAR_READ))
        .andExpect(jsonPath("$.permissionsVersion").value(1))
        .andExpect(jsonPath("$.descriptionVersion").value(0));
  }

  @Test
  void stalePermissionSaveConflictsButAnIndependentDescriptionSaveStillSucceeds() throws Exception {
    write("permissions", "{\"permissions\":[\"HANGAR_READ\"],\"version\":0}")
        .andExpect(status().isOk());
    write("permissions", "{\"permissions\":[],\"version\":0}").andExpect(status().isConflict());
    write("description", "{\"description\":\"independent\",\"version\":0}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.permissionsVersion").value(1))
        .andExpect(jsonPath("$.descriptionVersion").value(1));
    write("description", "{\"description\":\"stale\",\"version\":0}")
        .andExpect(status().isConflict());
  }

  private org.springframework.test.web.servlet.ResultActions write(String section, String body)
      throws Exception {
    return mockMvc.perform(
        put("/api/v1/roles/{name}/{section}", role.getName(), section)
            .with(
                jwt()
                    .jwt(builder -> builder.subject(admin.getId().toString()))
                    .authorities(new SimpleGrantedAuthority(Roles.authority(Roles.ADMIN))))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }
}
