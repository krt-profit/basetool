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

package de.greluc.krt.profit.basetool.frontend.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.LocationReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC tests for the catalog pickers' live-search JSON proxies ({@code GET /catalog/material-search}
 * and {@code GET /catalog/location-search}, REQ-FE-016). The pickers search the catalog on the
 * backend per keystroke instead of preloading a full (or silently capped) list, so these cover the
 * happy mapping — including the nested refined-material metadata the refinery pickers mirror — the
 * fail-soft empty-list behaviour that keeps a picker usable when the backend is unavailable, and
 * the anonymous reachability the guest order form relies on.
 */
@SpringBootTest
class CatalogSearchControllerMvcTest {

  @Autowired private WebApplicationContext context;
  private MockMvc mockMvc;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * Builds a minimal visible material row with the fields the pickers consume (quantity type plus
   * the nested refined material).
   *
   * @param id the material id
   * @param name the material name
   * @return a {@link MaterialDto} carrying a refined material named "Refined " + name
   */
  private MaterialDto material(UUID id, String name) {
    MaterialDto refined =
        new MaterialDto(
            UUID.randomUUID(),
            "Refined " + name,
            "REFINED",
            "SCU",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            true,
            1L);
    return new MaterialDto(
        id, name, "RAW", "SCU", null, refined, null, null, null, null, true, null, null, true, 1L);
  }

  // Anonymous on purpose (no @WithMockUser): the guest order form carries a material picker, so
  // /catalog/** must stay reachable without authentication.
  @Test
  void materialSearch_anonymous_mapsBackendPageIncludingRefinedMetadata() throws Exception {
    UUID id = UUID.randomUUID();
    PageResponse<MaterialDto> page =
        new PageResponse<>(List.of(material(id, "Quantainium")), 0, 25, 1L, 1, List.of());
    doReturn(page).when(backendApiClient).getPublic(anyString(), any(), any(Object[].class));

    mockMvc
        .perform(get("/catalog/material-search").param("q", "quant").param("raw", "true"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$[0].id").value(id.toString()))
        .andExpect(jsonPath("$[0].name").value("Quantainium"))
        .andExpect(jsonPath("$[0].quantityType").value("SCU"))
        .andExpect(jsonPath("$[0].refinedMaterial.name").value("Refined Quantainium"));
  }

  @Test
  void materialSearch_relaysTheFilterFlagsIntoTheBackendUri() throws Exception {
    PageResponse<MaterialDto> page = new PageResponse<>(List.of(), 0, 25, 0L, 0, List.of());
    doReturn(page)
        .when(backendApiClient)
        .getPublic(contains("jobOrderOnly={jobOrder}&rawOnly={raw}"), any(), any(Object[].class));

    mockMvc
        .perform(get("/catalog/material-search").param("q", "x").param("jobOrder", "true"))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
  }

  @Test
  void materialSearch_backendFailure_returnsEmptyList() throws Exception {
    doThrow(new RuntimeException("backend down"))
        .when(backendApiClient)
        .getPublic(anyString(), any(), any(Object[].class));

    mockMvc
        .perform(get("/catalog/material-search").param("q", "x"))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
  }

  @Test
  void locationSearch_anonymous_mapsBackendPageToReferenceList() throws Exception {
    UUID id = UUID.randomUUID();
    PageResponse<LocationReferenceDto> page =
        new PageResponse<>(
            List.of(new LocationReferenceDto(id, "Port Olisar")), 0, 25, 1L, 1, List.of());
    doReturn(page).when(backendApiClient).getPublic(anyString(), any(), any(Object[].class));

    mockMvc
        .perform(get("/catalog/location-search").param("q", "port"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$[0].id").value(id.toString()))
        .andExpect(jsonPath("$[0].name").value("Port Olisar"));
  }

  @Test
  void locationSearch_backendFailure_returnsEmptyList() throws Exception {
    doThrow(new RuntimeException("backend down"))
        .when(backendApiClient)
        .getPublic(anyString(), any(), any(Object[].class));

    mockMvc
        .perform(get("/catalog/location-search").param("q", "x"))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
  }
}
