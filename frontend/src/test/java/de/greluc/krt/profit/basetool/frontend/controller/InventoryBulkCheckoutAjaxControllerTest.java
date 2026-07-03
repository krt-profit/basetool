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
 * MVC tests for the #577 part-2 bulk-checkout proxy {@link InventoryWriteController#bulkCheckout}:
 * a valid request forwards the ids to the backend and answers {@code 204}, a backend
 * optimistic-lock failure is propagated as {@code problem+json} with its {@code code} (so the
 * client can drive the reload-confirm), and an empty id list is rejected up front with {@code 422}
 * {@code problem+json} (code {@code VALIDATION}) without ever calling the backend.
 */
@SpringBootTest
class InventoryBulkCheckoutAjaxControllerTest {

  private static final String BULK_CHECKOUT_URI = "/api/v1/inventory/bulk-checkout";

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
  void bulkCheckout_valid_forwardsToBackendAndReturnsNoContent() throws Exception {
    mockMvc
        .perform(
            post("/inventory/bulk-checkout")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemIds\":[\"" + UUID.randomUUID() + "\"]}"))
        .andExpect(status().isNoContent());

    verify(backendApiClient).post(eq(BULK_CHECKOUT_URI), any(), eq(Void.class));
  }

  @Test
  @WithMockUser
  void bulkCheckout_backendOptimisticLock_propagatesProblemJsonWithCode() throws Exception {
    when(backendApiClient.post(eq(BULK_CHECKOUT_URI), any(), eq(Void.class)))
        .thenThrow(
            new BackendServiceException(
                "conflict", null, 409, "OPTIMISTIC_LOCK", null, Collections.emptyList(), null));

    mockMvc
        .perform(
            post("/inventory/bulk-checkout")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemIds\":[\"" + UUID.randomUUID() + "\"]}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK"));
  }

  @Test
  @WithMockUser
  void bulkCheckout_emptyItemIds_returns422AndDoesNotCallBackend() throws Exception {
    mockMvc
        .perform(
            post("/inventory/bulk-checkout")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemIds\":[]}"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("VALIDATION"));

    verify(backendApiClient, never()).post(anyString(), any(), eq(Void.class));
  }
}
