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

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.GameItemReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC tests for the item-order picker's live-search JSON proxy ({@code GET /orders/item-search} →
 * backend {@code GET /api/v1/orders/item-catalog?search=...}). The picker now searches the catalog
 * on the backend per keystroke instead of preloading a capped, client-filtered list, so these cover
 * the happy mapping (id + name reach the browser) and the fail-soft empty-list behaviour that keeps
 * the field usable when the backend is unavailable.
 */
@SpringBootTest
class JobOrderPageControllerItemSearchMvcTest {

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

  @Test
  @WithMockUser
  void itemSearch_mapsBackendPageToReferenceList() throws Exception {
    UUID id = UUID.randomUUID();
    PageResponse<GameItemReferenceDto> page =
        new PageResponse<>(
            List.of(new GameItemReferenceDto(id, "P8-SC SMG", "WEAPON")), 0, 25, 1L, 1, List.of());
    doReturn(page).when(backendApiClient).get(any(), anyTypeRef(), any());

    mockMvc
        .perform(get("/orders/item-search").param("q", "p8"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$[0].id").value(id.toString()))
        .andExpect(jsonPath("$[0].name").value("P8-SC SMG"));
  }

  @Test
  @WithMockUser
  void itemSearch_backendFailure_returnsEmptyList() throws Exception {
    doThrow(new RuntimeException("backend down"))
        .when(backendApiClient)
        .get(any(), anyTypeRef(), any());

    mockMvc
        .perform(get("/orders/item-search").param("q", "x"))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
  }
}
