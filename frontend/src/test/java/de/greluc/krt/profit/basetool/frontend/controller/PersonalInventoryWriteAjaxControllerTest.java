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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalInventoryItemDto;
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
 * MVC tests for the personal-inventory write twins ({@link
 * PersonalInventoryPageController#addAjax}/{@code updateAjax}/{@code deleteAjax}): AJAX requests
 * are forwarded and answer {@code 204}, a missing location yields {@code 422} {@code problem+json}
 * without a backend call, and an optimistic-lock failure is relayed as {@code 409}.
 */
@SpringBootTest
class PersonalInventoryWriteAjaxControllerTest {

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
  void addAjax_valid_forwardsToBackendAndReturnsNoContent() throws Exception {
    String body =
        "{\"name\":\"Medpen\",\"note\":null,\"locationUexId\":42,"
            + "\"locationType\":\"CITY\",\"quantity\":5}";

    mockMvc
        .perform(
            post("/personal-inventory/add")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNoContent());

    verify(backendApiClient)
        .post(eq("/api/v1/personal-inventory"), any(), eq(PersonalInventoryItemDto.class));
  }

  @Test
  @WithMockUser
  void addAjax_missingLocation_returns422AndDoesNotCallBackend() throws Exception {
    String body =
        "{\"name\":\"Medpen\",\"locationUexId\":null,\"locationType\":\"CITY\",\"quantity\":5}";

    mockMvc
        .perform(
            post("/personal-inventory/add")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().is(422))
        .andExpect(jsonPath("$.code").value("VALIDATION"));

    verify(backendApiClient, never()).post(anyString(), any(), eq(PersonalInventoryItemDto.class));
  }

  @Test
  @WithMockUser
  void updateAjax_backendOptimisticLock_propagatesProblemJsonWithCode() throws Exception {
    UUID id = UUID.randomUUID();
    when(backendApiClient.put(
            eq("/api/v1/personal-inventory/" + id), any(), eq(PersonalInventoryItemDto.class)))
        .thenThrow(
            new BackendServiceException(
                "conflict", null, 409, "OPTIMISTIC_LOCK", null, Collections.emptyList(), null));

    String body =
        "{\"name\":\"Medpen\",\"locationUexId\":42,"
            + "\"locationType\":\"CITY\",\"quantity\":5,\"version\":0}";

    mockMvc
        .perform(
            post("/personal-inventory/" + id + "/update")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK"));
  }

  @Test
  @WithMockUser
  void deleteAjax_valid_forwardsToBackendAndReturnsNoContent() throws Exception {
    UUID id = UUID.randomUUID();

    mockMvc
        .perform(
            post("/personal-inventory/" + id + "/delete")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf()))
        .andExpect(status().isNoContent());

    verify(backendApiClient).delete(eq("/api/v1/personal-inventory/" + id), eq(Void.class));
  }
}
