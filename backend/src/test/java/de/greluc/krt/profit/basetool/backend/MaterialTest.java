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

import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
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
class MaterialTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @Autowired private MaterialRepository materialRepository;

  @Autowired private UserRepository userRepository;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  @MockitoBean private JwtDecoder jwtDecoder;

  private User adminUser;
  private User officerUser;
  private User roleLessUser;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

    adminUser = new User();
    adminUser.setId(UUID.randomUUID());
    adminUser.setUsername("adminMat");
    userRepository.save(adminUser);

    officerUser = new User();
    officerUser.setId(UUID.randomUUID());
    officerUser.setUsername("officerMat");
    userRepository.save(officerUser);

    roleLessUser = new User();
    roleLessUser.setId(UUID.randomUUID());
    roleLessUser.setUsername("guestMat");
    userRepository.save(roleLessUser);
  }

  @Test
  void testCreateMaterial_Admin_Allowed() throws Exception {
    // Mirrors the new MaterialCreateDto contract: name + type + quantityType are required.
    Material material = new Material();
    material.setName("New Material");
    material.setDescription("Valuable");
    material.setType(MaterialType.RAW);
    material.setQuantityType(QuantityType.SCU);

    mockMvc
        .perform(
            post("/api/v1/materials")
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
                .content(objectMapper.writeValueAsString(material)))
        .andExpect(status().isOk());

    assertEquals(1, materialRepository.findAll().size());
    assertEquals("New Material", materialRepository.findAll().get(0).getName());
  }

  @Test
  void testCreateMaterial_Officer_Forbidden() throws Exception {
    Material material = new Material();
    material.setName("Illegal Material");
    material.setType(MaterialType.REFINED);
    material.setQuantityType(QuantityType.SCU);

    mockMvc
        .perform(
            post("/api/v1/materials")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(officerUser.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_OFFICER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(material)))
        .andExpect(status().isForbidden());
  }

  @Test
  void testCreateMaterial_RoleLess_Forbidden() throws Exception {
    Material material = new Material();
    material.setName("Illegal Material");
    material.setType(MaterialType.REFINED);
    material.setQuantityType(QuantityType.SCU);

    mockMvc
        .perform(
            post("/api/v1/materials")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(roleLessUser.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_NO_ROLE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(material)))
        .andExpect(status().isForbidden());
  }

  @Test
  void testUpdateMaterial_Admin_Allowed() throws Exception {
    Material material = new Material();
    material.setName("Old Material");
    material.setType(MaterialType.NO_REFINE);
    material.setIsManualRawMaterial(false);
    material = materialRepository.save(material);

    material.setName("Updated Material");
    material.setIsManualRawMaterial(true);

    mockMvc
        .perform(
            put("/api/v1/materials/" + material.getId())
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
                .content(objectMapper.writeValueAsString(material)))
        .andExpect(status().isOk());

    Material loaded = materialRepository.findById(material.getId()).orElseThrow();
    assertEquals("Updated Material", loaded.getName());
    assertEquals(true, loaded.getIsManualRawMaterial());
  }

  @Test
  void testDeleteMaterial_Admin_Allowed() throws Exception {
    Material material = new Material();
    material.setName("To Delete");
    material.setType(MaterialType.RAW);
    material = materialRepository.save(material);

    mockMvc
        .perform(
            delete("/api/v1/materials/" + material.getId())
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

    assertTrue(materialRepository.findById(material.getId()).isEmpty());
  }
}
