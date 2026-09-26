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

import de.greluc.krt.profit.basetool.frontend.model.dto.BulkOrgUnitChangeRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.BulkOrgUnitChangeResultDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryItemOrgUnitChangeDto;
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
 * MockMvc coverage of the two AJAX proxies that change a personal Lager row's org unit
 * (REQ-INV-052): the payload is forwarded unchanged, a {@code null} target means no unit, an empty
 * selection is refused locally, and a backend refusal is relayed with its code.
 */
@SpringBootTest
class InventoryOrgUnitChangeAjaxControllerTest {

  private static final String BULK_URI = "/api/v1/inventory/bulk-org-unit";

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
  void single_forwardsVersionTargetAndMergeFlag() throws Exception {
    UUID itemId = UUID.randomUUID();
    UUID orgUnitId = UUID.randomUUID();
    String uri = "/api/v1/inventory/" + itemId + "/org-unit";
    when(backendApiClient.post(eq(uri), any(), eq(InventoryItemDto.class))).thenReturn(null);

    mockMvc
        .perform(
            post("/inventory/" + itemId + "/org-unit")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"version\":4,\"targetOwningOrgUnitId\":\""
                        + orgUnitId
                        + "\",\"mergeStock\":true}"))
        .andExpect(status().isOk());

    ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
    verify(backendApiClient).post(eq(uri), body.capture(), eq(InventoryItemDto.class));
    InventoryItemOrgUnitChangeDto forwarded = (InventoryItemOrgUnitChangeDto) body.getValue();
    assertThat(forwarded.version()).isEqualTo(4L);
    assertThat(forwarded.targetOwningOrgUnitId()).isEqualTo(orgUnitId);
    assertThat(forwarded.mergeStock()).isTrue();
  }

  @Test
  @WithMockUser
  void single_nullTargetMeansNoUnit() throws Exception {
    UUID itemId = UUID.randomUUID();
    String uri = "/api/v1/inventory/" + itemId + "/org-unit";
    when(backendApiClient.post(eq(uri), any(), eq(InventoryItemDto.class))).thenReturn(null);

    mockMvc
        .perform(
            post("/inventory/" + itemId + "/org-unit")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1,\"targetOwningOrgUnitId\":null}"))
        .andExpect(status().isOk());

    ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
    verify(backendApiClient).post(eq(uri), body.capture(), eq(InventoryItemDto.class));
    assertThat(((InventoryItemOrgUnitChangeDto) body.getValue()).targetOwningOrgUnitId()).isNull();
  }

  @Test
  @WithMockUser
  void bulk_relaysTheCounts() throws Exception {
    UUID itemId = UUID.randomUUID();
    when(backendApiClient.post(eq(BULK_URI), any(), eq(BulkOrgUnitChangeResultDto.class)))
        .thenReturn(new BulkOrgUnitChangeResultDto(2, 1));

    mockMvc
        .perform(
            post("/inventory/bulk-org-unit")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemIds\":[\"" + itemId + "\"],\"targetOwningOrgUnitId\":null}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(2))
        .andExpect(jsonPath("$.skipped").value(1));

    ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
    verify(backendApiClient)
        .post(eq(BULK_URI), body.capture(), eq(BulkOrgUnitChangeResultDto.class));
    assertThat(((BulkOrgUnitChangeRequest) body.getValue()).itemIds()).containsExactly(itemId);
  }

  @Test
  @WithMockUser
  void bulk_emptySelectionIsRefusedWithoutCallingTheBackend() throws Exception {
    mockMvc
        .perform(
            post("/inventory/bulk-org-unit")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemIds\":[]}"))
        .andExpect(status().isUnprocessableContent());

    verify(backendApiClient, never()).post(anyString(), any(), any());
  }

  @Test
  @WithMockUser
  void bulk_backendRefusalIsRelayedWithItsCode() throws Exception {
    when(backendApiClient.post(eq(BULK_URI), any(), eq(BulkOrgUnitChangeResultDto.class)))
        .thenThrow(
            new BackendServiceException(
                "shared row", null, 400, "BAD_REQUEST", null, Collections.emptyList(), null));

    mockMvc
        .perform(
            post("/inventory/bulk-org-unit")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemIds\":[\"" + UUID.randomUUID() + "\"]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
  }
}
