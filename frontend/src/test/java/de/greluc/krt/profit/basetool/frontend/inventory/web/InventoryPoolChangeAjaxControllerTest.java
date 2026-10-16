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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.inventory.model.InventoryItemDto;
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventoryItemPoolChangeDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.model.OrgUnitReferenceDto;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MockMvc coverage of the two AJAX proxies of the Globales Lager's pool change (REQ-INV-059): the
 * payload is forwarded unchanged, a request without target is refused locally, the target list is
 * relayed, and a backend refusal is relayed with its code.
 */
@SpringBootTest
class InventoryPoolChangeAjaxControllerTest {

  private static final String URI = "/api/v1/inventory/{id}/pool";

  private static final String TARGETS_URI = "/api/v1/inventory/{id}/pool-targets";

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
  void forwardsVersionTargetPartAndMergeFlag() throws Exception {
    UUID itemId = UUID.randomUUID();
    UUID orgUnitId = UUID.randomUUID();
    when(backendApiClient.post(eq(URI), any(), eq(InventoryItemDto.class), eq(itemId)))
        .thenReturn(null);

    mockMvc
        .perform(
            post("/inventory/" + itemId + "/pool")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"version\":4,\"targetOwningOrgUnitId\":\""
                        + orgUnitId
                        + "\",\"amount\":2.5,\"mergeStock\":true}"))
        .andExpect(status().isOk());

    ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
    verify(backendApiClient).post(eq(URI), body.capture(), eq(InventoryItemDto.class), eq(itemId));
    InventoryItemPoolChangeDto forwarded = (InventoryItemPoolChangeDto) body.getValue();
    assertThat(forwarded.version()).isEqualTo(4L);
    assertThat(forwarded.targetOwningOrgUnitId()).isEqualTo(orgUnitId);
    assertThat(forwarded.amount()).isEqualTo(2.5);
    assertThat(forwarded.mergeStock()).isTrue();
  }

  @Test
  @WithMockUser
  void aRequestWithoutTargetIsRefusedWithoutCallingTheBackend() throws Exception {
    mockMvc
        .perform(
            post("/inventory/" + UUID.randomUUID() + "/pool")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1}"))
        .andExpect(status().isUnprocessableContent());

    verify(backendApiClient, never()).post(anyString(), any(), any(), any(Object[].class));
  }

  @Test
  @WithMockUser
  void theTargetsAreRelayed() throws Exception {
    UUID itemId = UUID.randomUUID();
    UUID bravo = UUID.randomUUID();
    when(backendApiClient.get(eq(TARGETS_URI), any(ParameterizedTypeReference.class), eq(itemId)))
        .thenReturn(List.of(new OrgUnitReferenceDto(bravo, "Bravo", "BRV", "SPECIAL_COMMAND")));

    mockMvc
        .perform(get("/inventory/" + itemId + "/pool-targets"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(bravo.toString()))
        .andExpect(jsonPath("$[0].name").value("Bravo"));
  }

  @Test
  @WithMockUser
  void aBackendRefusalIsRelayedWithItsCode() throws Exception {
    UUID itemId = UUID.randomUUID();
    when(backendApiClient.post(eq(URI), any(), eq(InventoryItemDto.class), eq(itemId)))
        .thenThrow(
            new BackendServiceException(
                "not a logistician",
                null,
                403,
                "ACCESS_DENIED",
                null,
                Collections.emptyList(),
                null));

    mockMvc
        .perform(
            post("/inventory/" + itemId + "/pool")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1,\"targetOwningOrgUnitId\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
  }
}
