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

package de.greluc.krt.profit.basetool.backend.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.MaterialExternalAlias;
import de.greluc.krt.profit.basetool.backend.service.MaterialExternalAliasService;
import java.util.List;
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

/**
 * Security-gate tests for {@link MaterialExternalAliasController}. The CRUD endpoints are
 * admin-only — anonymous callers must get 401, authenticated non-admins must get 403, admins must
 * pass. The service layer is {@code @MockitoBean}-stubbed so the test focuses on the
 * {@code @PreAuthorize("hasRole('ADMIN')")} class gate without dragging in JPA / TestContainers
 * setup for every assertion.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MaterialExternalAliasControllerTest {

  private static final String BASE = "/api/v1/material-external-aliases";

  @Autowired private WebApplicationContext context;

  @MockitoBean private JwtDecoder jwtDecoder;

  @MockitoBean private MaterialExternalAliasService service;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    when(service.findAll()).thenReturn(List.of());
  }

  @Test
  void getList_forbiddenForNonAdmin() throws Exception {
    mockMvc
        .perform(get(BASE).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OFFICER"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void getList_allowedForAdmin() throws Exception {
    mockMvc
        .perform(
            get(BASE)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());
  }

  @Test
  void create_forbiddenForNonAdmin() throws Exception {
    String validBody =
        """
        {
          "materialId":"%s",
          "sourceSystem":"SCWIKI",
          "externalName":"Raw Silicon"
        }
        """
            .formatted(UUID.randomUUID());

    mockMvc
        .perform(
            post(BASE)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_LOGISTICIAN")))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody))
        .andExpect(status().isForbidden());
  }

  @Test
  void create_validationError_returns400_forAdmin() throws Exception {
    String invalidBody = "{\"materialId\":null}";

    mockMvc
        .perform(
            post(BASE)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidBody))
        .andExpect(status().isBadRequest());
  }

  @Test
  void create_allowedForAdmin() throws Exception {
    UUID materialId = UUID.randomUUID();
    String validBody =
        """
        {
          "materialId":"%s",
          "sourceSystem":"SCWIKI",
          "externalName":"Raw Silicon"
        }
        """
            .formatted(materialId);
    MaterialExternalAlias persisted = new MaterialExternalAlias();
    persisted.setId(UUID.randomUUID());
    persisted.setVersion(0L);
    persisted.setSourceSystem(
        de.greluc.krt.profit.basetool.backend.model.MaterialExternalAliasSource.SCWIKI);
    persisted.setExternalName("Raw Silicon");
    de.greluc.krt.profit.basetool.backend.model.Material material =
        new de.greluc.krt.profit.basetool.backend.model.Material();
    material.setId(materialId);
    material.setName("Silicon (Raw)");
    persisted.setMaterial(material);
    when(service.create(any())).thenReturn(persisted);

    mockMvc
        .perform(
            post(BASE)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody))
        .andExpect(status().isCreated());
  }

  @Test
  void create_acceptsRefineryScreenSource() throws Exception {
    UUID materialId = UUID.randomUUID();
    String validBody =
        """
        {
          "materialId":"%s",
          "sourceSystem":"REFINERY_SCREEN",
          "externalName":"UCTION SALVAGE"
        }
        """
            .formatted(materialId);
    MaterialExternalAlias persisted = new MaterialExternalAlias();
    persisted.setId(UUID.randomUUID());
    persisted.setVersion(0L);
    persisted.setSourceSystem(
        de.greluc.krt.profit.basetool.backend.model.MaterialExternalAliasSource.REFINERY_SCREEN);
    persisted.setExternalName("UCTION SALVAGE");
    de.greluc.krt.profit.basetool.backend.model.Material material =
        new de.greluc.krt.profit.basetool.backend.model.Material();
    material.setId(materialId);
    material.setName("Construction Salvage");
    persisted.setMaterial(material);
    when(service.create(any())).thenReturn(persisted);

    mockMvc
        .perform(
            post(BASE)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody))
        .andExpect(status().isCreated());
  }

  @Test
  void delete_forbiddenForNonAdmin() throws Exception {
    mockMvc
        .perform(
            delete(BASE + "/" + UUID.randomUUID())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OFFICER")))
                .with(csrf()))
        .andExpect(status().isForbidden());
  }

  @Test
  void delete_allowedForAdmin() throws Exception {
    mockMvc
        .perform(
            delete(BASE + "/" + UUID.randomUUID())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .with(csrf()))
        .andExpect(status().isNoContent());
  }
}
