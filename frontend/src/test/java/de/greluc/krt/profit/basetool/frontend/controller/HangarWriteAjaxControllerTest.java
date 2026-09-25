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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.ShipDto;
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
 * MVC tests for the #578 header-gated hangar write twins ({@link
 * HangarPageController#addShipAjax}/{@code updateShipAjax}/{@code deleteShipAjax}/{@code
 * setHomeLocationAjax}). They assert that an {@code X-Requested-With=XMLHttpRequest} JSON request
 * forwards to the backend and answers {@code 204}, a missing required field is rejected up front
 * with {@code 422} {@code problem+json} (code {@code VALIDATION}) without ever calling the backend,
 * a backend optimistic-lock failure is relayed as {@code 409} {@code problem+json} carrying its
 * {@code code}, and that a plain (no-header) POST still routes to the classic POST→redirect
 * fallback (the twins must not shadow it).
 */
@SpringBootTest
class HangarWriteAjaxControllerTest {

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
  @WithMockUser
  void addShipAjax_valid_forwardsToBackendAndReturnsNoContent() throws Exception {
    String body =
        "{\"name\":\"My Titan\",\"shipTypeId\":\""
            + UUID.randomUUID()
            + "\",\"insurance\":\"LTI\",\"locationId\":null,\"fitted\":false}";

    mockMvc
        .perform(
            post("/hangar/add")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNoContent());

    verify(backendApiClient).post(eq("/api/v1/hangar/ships"), any(), eq(ShipDto.class));
  }

  @Test
  @WithMockUser
  void addShipAjax_missingInsurance_returns422AndDoesNotCallBackend() throws Exception {
    String body =
        "{\"name\":\"My Titan\",\"shipTypeId\":\""
            + UUID.randomUUID()
            + "\",\"insurance\":\"\",\"fitted\":false}";

    mockMvc
        .perform(
            post("/hangar/add")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().is(422))
        .andExpect(jsonPath("$.code").value("VALIDATION"));

    verify(backendApiClient, never()).post(anyString(), any(), eq(ShipDto.class));
  }

  @Test
  @WithMockUser
  void updateShipAjax_backendOptimisticLock_propagatesProblemJsonWithCode() throws Exception {
    UUID id = UUID.randomUUID();
    when(backendApiClient.put(eq("/api/v1/hangar/ships/" + id), any(), eq(ShipDto.class)))
        .thenThrow(
            new BackendServiceException(
                "conflict", null, 409, "OPTIMISTIC_LOCK", null, Collections.emptyList(), null));

    String body =
        "{\"name\":\"My Titan\",\"shipTypeId\":\""
            + UUID.randomUUID()
            + "\",\"insurance\":\"LTI\",\"fitted\":true,\"version\":0}";

    mockMvc
        .perform(
            post("/hangar/" + id + "/update")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK"));
  }

  @Test
  @WithMockUser
  void deleteShipAjax_valid_forwardsToBackendAndReturnsNoContent() throws Exception {
    UUID id = UUID.randomUUID();

    mockMvc
        .perform(
            post("/hangar/" + id + "/delete")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf()))
        .andExpect(status().isNoContent());

    verify(backendApiClient).delete(eq("/api/v1/hangar/ships/" + id), eq(Void.class));
  }

  @Test
  @WithMockUser
  void setHomeLocationAjax_valid_forwardsToBackendAndReturnsNoContent() throws Exception {
    mockMvc
        .perform(
            post("/hangar/home-location")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"locationId\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isNoContent());

    verify(backendApiClient).post(eq("/api/v1/hangar/ships/home-location"), any(), eq(Void.class));
  }

  @Test
  @WithMockUser
  void addShip_withoutAjaxHeader_routesToClassicRedirectFallback() throws Exception {
    mockMvc
        .perform(
            post("/hangar/add")
                .with(csrf())
                .param("shipTypeId", UUID.randomUUID().toString())
                .param("insurance", "LTI"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/hangar"));
  }
}
