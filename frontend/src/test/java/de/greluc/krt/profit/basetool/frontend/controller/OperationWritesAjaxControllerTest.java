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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.OperationDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.util.Collections;
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
 * MVC tests for the in-place operation write twins ({@link
 * OperationPageController#createOperationAjax}, {@link
 * OperationPageController#updateOperationAjax}, {@link
 * OperationPageController#deleteOperationAjax}).
 *
 * <p>They return JSON, the update returns the fresh {@code {version, name, status}}, a backend
 * {@code 409} is relayed as {@code problem+json} with its {@code code}, and a POST without the AJAX
 * header falls back to the redirect handler.
 */
@SpringBootTest
class OperationWritesAjaxControllerTest {

  private static final UUID OPERATION_ID = UUID.randomUUID();

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  @WithMockUser(roles = "MISSION_MANAGER")
  void createOperationAjax_validBody_postsToBackendAndReturns200() throws Exception {
    mockMvc
        .perform(
            post("/operations/create")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Op Alpha\",\"description\":\"d\",\"status\":\"PLANNED\"}"))
        .andExpect(status().isOk());

    verify(backendApiClient).post(eq("/api/v1/operations"), any(), eq(Void.class));
  }

  @Test
  @WithMockUser(roles = "MISSION_MANAGER")
  void updateOperationAjax_valid_returnsFreshVersionNameStatusFromPut() throws Exception {
    OperationDto refreshed =
        new OperationDto(OPERATION_ID, "Renamed Op", "desc", "ACTIVE", null, 7L, null, null, null);
    when(backendApiClient.put(
            eq("/api/v1/operations/" + OPERATION_ID), any(), eq(OperationDto.class)))
        .thenReturn(refreshed);

    mockMvc
        .perform(
            post("/operations/" + OPERATION_ID + "/update")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"Renamed Op\",\"description\":\"desc\",\"status\":\"ACTIVE\","
                        + "\"version\":6}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(7))
        .andExpect(jsonPath("$.name").value("Renamed Op"))
        .andExpect(jsonPath("$.status").value("ACTIVE"));

    verify(backendApiClient)
        .put(eq("/api/v1/operations/" + OPERATION_ID), any(), eq(OperationDto.class));
  }

  @Test
  @WithMockUser(roles = "MISSION_MANAGER")
  void updateOperationAjax_backendConflict_propagatesProblemJsonWithCode() throws Exception {
    when(backendApiClient.put(
            eq("/api/v1/operations/" + OPERATION_ID), any(), eq(OperationDto.class)))
        .thenThrow(
            new BackendServiceException(
                "conflict", null, 409, "OPTIMISTIC_LOCK", null, Collections.emptyList(), null));

    mockMvc
        .perform(
            post("/operations/" + OPERATION_ID + "/update")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Op\",\"status\":\"ACTIVE\",\"version\":6}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK"));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void deleteOperationAjax_admin_deletesAndReturns200() throws Exception {
    mockMvc
        .perform(
            post("/operations/" + OPERATION_ID + "/delete")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf()))
        .andExpect(status().isOk());

    verify(backendApiClient).delete(eq("/api/v1/operations/" + OPERATION_ID), eq(Void.class));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void deleteOperation_withoutHeader_fallsBackToClassicRedirect() throws Exception {
    mockMvc
        .perform(post("/operations/" + OPERATION_ID + "/delete").with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/operations"));
  }
}
