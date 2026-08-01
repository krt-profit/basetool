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

import de.greluc.krt.profit.basetool.frontend.model.dto.BulkRebookRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.BulkRebookResultDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC tests for the Massen-Umbuchen proxy {@link InventoryWriteController#bulkRebook}
 * (REQ-INV-036): a valid request forwards the whole payload (ids, mode and targets) to the backend
 * and relays the moved/skipped counts the page needs to phrase its toast, a backend rejection is
 * propagated as {@code problem+json} with its {@code code}, and a request missing its ids or its
 * mode is rejected up front with {@code 422} without ever calling the backend.
 */
@SpringBootTest
class InventoryBulkRebookAjaxControllerTest {

  private static final String BULK_REBOOK_URI = "/api/v1/inventory/bulk-rebook";

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
  void bulkRebook_valid_forwardsPayloadAndRelaysCounts() throws Exception {
    UUID itemId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    when(backendApiClient.post(eq(BULK_REBOOK_URI), any(), eq(BulkRebookResultDto.class)))
        .thenReturn(new BulkRebookResultDto(3, 2));

    mockMvc
        .perform(
            post("/inventory/bulk-rebook")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"itemIds\":[\""
                        + itemId
                        + "\"],\"mode\":\"LOCATION\",\"targetLocationId\":\""
                        + locationId
                        + "\",\"mergeStock\":true}"))
        .andExpect(status().isOk())
        // The page distinguishes a full success from a largely-skipped run, so both counts must
        // survive the relay unchanged.
        .andExpect(jsonPath("$.rebooked").value(3))
        .andExpect(jsonPath("$.skipped").value(2));

    ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
    verify(backendApiClient)
        .post(eq(BULK_REBOOK_URI), body.capture(), eq(BulkRebookResultDto.class));
    BulkRebookRequest forwarded = (BulkRebookRequest) body.getValue();
    org.assertj.core.api.Assertions.assertThat(forwarded.itemIds()).containsExactly(itemId);
    org.assertj.core.api.Assertions.assertThat(forwarded.mode().name()).isEqualTo("LOCATION");
    org.assertj.core.api.Assertions.assertThat(forwarded.targetLocationId()).isEqualTo(locationId);
    org.assertj.core.api.Assertions.assertThat(forwarded.mergeStock()).isTrue();
  }

  @Test
  @WithMockUser
  void bulkRebook_personalizeMode_forwardsWithoutTransferTargets() throws Exception {
    when(backendApiClient.post(eq(BULK_REBOOK_URI), any(), eq(BulkRebookResultDto.class)))
        .thenReturn(new BulkRebookResultDto(1, 0));

    mockMvc
        .perform(
            post("/inventory/bulk-rebook")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"itemIds\":[\""
                        + UUID.randomUUID()
                        + "\"],\"mode\":\"PERSONALIZE\",\"targetUserId\":null,"
                        + "\"targetLocationId\":null}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rebooked").value(1));

    ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
    verify(backendApiClient)
        .post(eq(BULK_REBOOK_URI), body.capture(), eq(BulkRebookResultDto.class));
    BulkRebookRequest forwarded = (BulkRebookRequest) body.getValue();
    org.assertj.core.api.Assertions.assertThat(forwarded.mode().name()).isEqualTo("PERSONALIZE");
    org.assertj.core.api.Assertions.assertThat(forwarded.targetLocationId()).isNull();
  }

  @Test
  @WithMockUser
  void bulkRebook_backendRejection_propagatesProblemJsonWithCode() throws Exception {
    when(backendApiClient.post(eq(BULK_REBOOK_URI), any(), eq(BulkRebookResultDto.class)))
        .thenThrow(
            new BackendServiceException(
                "conflict", null, 409, "OPTIMISTIC_LOCK", null, Collections.emptyList(), null));

    mockMvc
        .perform(
            post("/inventory/bulk-rebook")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"itemIds\":[\"" + UUID.randomUUID() + "\"],\"mode\":\"DEPERSONALIZE\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK"));
  }

  @Test
  @WithMockUser
  void bulkRebook_emptyItemIds_returns422AndDoesNotCallBackend() throws Exception {
    mockMvc
        .perform(
            post("/inventory/bulk-rebook")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemIds\":[],\"mode\":\"LOCATION\"}"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("VALIDATION"));

    verify(backendApiClient, never()).post(anyString(), any(), eq(BulkRebookResultDto.class));
  }

  @Test
  @WithMockUser
  void bulkRebook_missingMode_returns422AndDoesNotCallBackend() throws Exception {
    // The mode drives which write the backend performs, so a payload without one must never reach
    // it — the guard lives here because @Valid would surface as a 500 through the frontend's
    // GlobalExceptionHandler.
    mockMvc
        .perform(
            post("/inventory/bulk-rebook")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemIds\":[\"" + UUID.randomUUID() + "\"]}"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("VALIDATION"));

    verify(backendApiClient, never()).post(anyString(), any(), eq(BulkRebookResultDto.class));
  }
}
