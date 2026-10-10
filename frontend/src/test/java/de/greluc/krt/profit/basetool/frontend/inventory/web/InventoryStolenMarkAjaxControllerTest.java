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

package de.greluc.krt.profit.basetool.frontend.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;
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

import de.greluc.krt.profit.basetool.frontend.inventory.model.BulkStolenMarkRequest;
import de.greluc.krt.profit.basetool.frontend.inventory.model.BulkStolenMarkResultDto;
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventoryItemDto;
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventoryItemStolenMarkDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
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
 * MockMvc coverage of the two AJAX proxies that set or remove the „gestohlen" marker on Lager rows
 * (REQ-INV-053): the payload is forwarded unchanged, a missing marker or an empty selection is
 * refused locally, and a backend refusal is relayed with its code and detail.
 */
@SpringBootTest
class InventoryStolenMarkAjaxControllerTest {

  private static final String BULK_URI = "/api/v1/inventory/bulk-stolen";

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
  void single_forwardsVersionMarkerAndPartAmount() throws Exception {
    UUID itemId = UUID.randomUUID();
    String uri = "/api/v1/inventory/{id}/stolen";
    when(backendApiClient.post(eq(uri), any(), eq(InventoryItemDto.class), eq(itemId)))
        .thenReturn(null);

    mockMvc
        .perform(
            post("/inventory/" + itemId + "/stolen")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":3,\"stolen\":true,\"amount\":2.5}"))
        .andExpect(status().isOk());

    ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
    verify(backendApiClient).post(eq(uri), body.capture(), eq(InventoryItemDto.class), eq(itemId));
    InventoryItemStolenMarkDto forwarded = (InventoryItemStolenMarkDto) body.getValue();
    assertThat(forwarded.version()).isEqualTo(3L);
    assertThat(forwarded.stolen()).isTrue();
    assertThat(forwarded.amount()).isEqualTo(2.5);
  }

  @Test
  @WithMockUser
  void single_nullAmountMeansTheWholeRow() throws Exception {
    UUID itemId = UUID.randomUUID();
    String uri = "/api/v1/inventory/{id}/stolen";
    when(backendApiClient.post(eq(uri), any(), eq(InventoryItemDto.class), eq(itemId)))
        .thenReturn(null);

    mockMvc
        .perform(
            post("/inventory/" + itemId + "/stolen")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1,\"stolen\":false,\"amount\":null}"))
        .andExpect(status().isOk());

    ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
    verify(backendApiClient).post(eq(uri), body.capture(), eq(InventoryItemDto.class), eq(itemId));
    InventoryItemStolenMarkDto forwarded = (InventoryItemStolenMarkDto) body.getValue();
    assertThat(forwarded.stolen()).isFalse();
    assertThat(forwarded.amount()).isNull();
  }

  @Test
  @WithMockUser
  void single_missingMarkerIsRefusedWithoutCallingTheBackend() throws Exception {
    mockMvc
        .perform(
            post("/inventory/" + UUID.randomUUID() + "/stolen")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1}"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("VALIDATION"));

    verify(backendApiClient, never()).post(anyString(), any(), any());
    verify(backendApiClient, never()).post(anyString(), any(), any(), any(Object[].class));
  }

  @Test
  @WithMockUser
  void single_switchedOffConflictIsRelayedWithItsDetail() throws Exception {
    UUID itemId = UUID.randomUUID();
    when(backendApiClient.post(
            eq("/api/v1/inventory/{id}/stolen"), any(), eq(InventoryItemDto.class), eq(itemId)))
        .thenThrow(
            new BackendServiceException(
                "switched off",
                null,
                409,
                "BUSINESS_CONFLICT",
                null,
                Collections.emptyList(),
                "Die Markierung ist abgeschaltet."));

    mockMvc
        .perform(
            post("/inventory/" + itemId + "/stolen")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1,\"stolen\":true}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("BUSINESS_CONFLICT"))
        .andExpect(jsonPath("$.detail").value("Die Markierung ist abgeschaltet."));
  }

  @Test
  @WithMockUser
  void bulk_relaysTheCounts() throws Exception {
    UUID itemId = UUID.randomUUID();
    when(backendApiClient.post(eq(BULK_URI), any(), eq(BulkStolenMarkResultDto.class)))
        .thenReturn(new BulkStolenMarkResultDto(2, 1));

    mockMvc
        .perform(
            post("/inventory/bulk-stolen")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemIds\":[\"" + itemId + "\"],\"stolen\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(2))
        .andExpect(jsonPath("$.skipped").value(1));

    ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
    verify(backendApiClient).post(eq(BULK_URI), body.capture(), eq(BulkStolenMarkResultDto.class));
    BulkStolenMarkRequest forwarded = (BulkStolenMarkRequest) body.getValue();
    assertThat(forwarded.itemIds()).containsExactly(itemId);
    assertThat(forwarded.stolen()).isTrue();
  }

  @Test
  @WithMockUser
  void bulk_emptySelectionIsRefusedWithoutCallingTheBackend() throws Exception {
    mockMvc
        .perform(
            post("/inventory/bulk-stolen")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemIds\":[],\"stolen\":true}"))
        .andExpect(status().isUnprocessableContent());

    verify(backendApiClient, never()).post(anyString(), any(), any());
    verify(backendApiClient, never()).post(anyString(), any(), any(), any(Object[].class));
  }

  @Test
  @WithMockUser
  void bulk_missingMarkerIsRefusedWithoutCallingTheBackend() throws Exception {
    mockMvc
        .perform(
            post("/inventory/bulk-stolen")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemIds\":[\"" + UUID.randomUUID() + "\"]}"))
        .andExpect(status().isUnprocessableContent());

    verify(backendApiClient, never()).post(anyString(), any(), any());
    verify(backendApiClient, never()).post(anyString(), any(), any(), any(Object[].class));
  }

  @Test
  @WithMockUser
  void bulk_backendRefusalIsRelayedWithItsCode() throws Exception {
    when(backendApiClient.post(eq(BULK_URI), any(), eq(BulkStolenMarkResultDto.class)))
        .thenThrow(
            new BackendServiceException(
                "foreign row", null, 403, "FORBIDDEN", null, Collections.emptyList(), null));

    mockMvc
        .perform(
            post("/inventory/bulk-stolen")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemIds\":[\"" + UUID.randomUUID() + "\"],\"stolen\":false}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }
}
