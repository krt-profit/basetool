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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC tests for the ship-data AJAX writes on {@link ShipDataPageController} ({@link
 * ShipDataPageController#toggleShipTypeVisibilityAjax}, {@code toggleManufacturerVisibilityAjax},
 * {@code resetAllFittedAjax}): {@code 204} for an admin, {@code problem+json} with its {@code code}
 * on a backend failure, {@code 403} for a non-admin.
 */
@SpringBootTest
class ShipDataVisibilityAjaxControllerTest {

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
  @WithMockUser(roles = "ADMIN")
  void toggleShipTypeVisibilityAjax_valid_forwardsHiddenFlagAndReturnsNoContent() throws Exception {
    UUID id = UUID.randomUUID();

    mockMvc
        .perform(
            post("/ship-data/ship-types/" + id + "/visibility")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .param("hidden", "true"))
        .andExpect(status().isNoContent());

    verify(backendApiClient)
        .put(eq("/api/v1/ship-types/" + id + "/visibility?hidden=true"), any(), eq(Void.class));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void toggleManufacturerVisibilityAjax_valid_forwardsHiddenFlagAndReturnsNoContent()
      throws Exception {
    UUID id = UUID.randomUUID();

    mockMvc
        .perform(
            post("/ship-data/manufacturers/" + id + "/visibility")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .param("hidden", "false"))
        .andExpect(status().isNoContent());

    verify(backendApiClient)
        .put(eq("/api/v1/manufacturers/" + id + "/visibility?hidden=false"), any(), eq(Void.class));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void resetAllFittedAjax_valid_forwardsToBackendAndReturnsNoContent() throws Exception {
    mockMvc
        .perform(
            post("/ship-data/reset-fitted")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf()))
        .andExpect(status().isNoContent());

    verify(backendApiClient).post(eq("/api/v1/hangar/ships/reset-fitted"), any(), eq(Void.class));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void toggleShipTypeVisibilityAjax_backendFailure_propagatesProblemJsonWithCode()
      throws Exception {
    UUID id = UUID.randomUUID();
    when(backendApiClient.put(
            eq("/api/v1/ship-types/" + id + "/visibility?hidden=true"), any(), eq(Void.class)))
        .thenThrow(
            new BackendServiceException(
                "down", null, 503, "SERVICE_UNAVAILABLE", null, Collections.emptyList(), null));

    mockMvc
        .perform(
            post("/ship-data/ship-types/" + id + "/visibility")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .param("hidden", "true"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
  }

  @Test
  @WithMockUser(roles = "USER")
  void toggleShipTypeVisibilityAjax_nonAdmin_isForbidden() throws Exception {
    mockMvc
        .perform(
            post("/ship-data/ship-types/" + UUID.randomUUID() + "/visibility")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .param("hidden", "true"))
        .andExpect(status().isForbidden());
  }
}
