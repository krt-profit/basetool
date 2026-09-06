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

import de.greluc.krt.profit.basetool.backend.model.Manufacturer;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.ManufacturerRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
class ManufacturerTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @Autowired private ManufacturerRepository manufacturerRepository;

  @Autowired private ShipTypeRepository shipTypeRepository;

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
    officerUser.setUsername("officerMfg");
    userRepository.save(officerUser);

    roleLessUser = new User();
    roleLessUser.setId(UUID.randomUUID());
    roleLessUser.setUsername("guestMfg");
    userRepository.save(roleLessUser);
  }

  @Test
  void testToggleManufacturerVisibility_Admin_Allowed() throws Exception {
    Manufacturer manufacturer = new Manufacturer();
    manufacturer.setName("Aegis Dynamics");
    manufacturer.setAbbreviation("AEGIS");
    manufacturer.setDescription("Military spec ships");
    manufacturer.setHidden(false);
    manufacturer = manufacturerRepository.save(manufacturer);

    mockMvc
        .perform(
            put("/api/v1/manufacturers/" + manufacturer.getId() + "/visibility?hidden=true")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(officerUser.getId().toString()))
                        .authorities(
                            new SimpleGrantedAuthority("ROLE_ADMIN"),
                            new SimpleGrantedAuthority("USER_MANAGE"),
                            new SimpleGrantedAuthority("MISSION_MANAGE"),
                            new SimpleGrantedAuthority("HANGAR_MANAGE"),
                            new SimpleGrantedAuthority("REFINERY_MANAGE"))))
        .andExpect(status().isOk());

    Manufacturer saved = manufacturerRepository.findAll().get(0);
    assertTrue(saved.isHidden());
  }

  @Test
  void testToggleManufacturerVisibility_RoleLess_Forbidden() throws Exception {
    Manufacturer manufacturer = new Manufacturer();
    manufacturer.setName("Hacked Mfg");
    manufacturer.setAbbreviation("HACK");
    manufacturer.setHidden(false);
    manufacturer = manufacturerRepository.save(manufacturer);

    mockMvc
        .perform(
            put("/api/v1/manufacturers/" + manufacturer.getId() + "/visibility?hidden=true")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(roleLessUser.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_NO_ROLE"))))
        .andExpect(status().isForbidden());
  }
}
