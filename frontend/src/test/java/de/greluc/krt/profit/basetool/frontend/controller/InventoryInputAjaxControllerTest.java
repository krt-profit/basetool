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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryAllocationInput;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryItemCreateDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryItemDto;
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
 * MVC tests for the #577 in-place book-in twin {@link
 * InventoryWriteController#addInventoryItemAjax}: a valid {@code X-Requested-With} create returns
 * the source listing URL for the client to navigate to, the server-side cross-field rule (a
 * personal entry cannot carry an order/mission) returns {@code 422} {@code problem+json} with a
 * stable code and no backend call, a backend failure is propagated as {@code problem+json}, and a
 * header-less POST falls back to the classic redirect handler.
 */
@SpringBootTest
class InventoryInputAjaxControllerTest {

  private static final UUID MATERIAL_ID = UUID.randomUUID();
  private static final UUID LOCATION_ID = UUID.randomUUID();

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
  void addInventoryItemAjax_valid_postsToBackendAndReturnsSourceTargetUrl() throws Exception {
    mockMvc
        .perform(
            post("/inventory/input")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .param("materialId", MATERIAL_ID.toString())
                .param("locationId", LOCATION_ID.toString())
                .param("quality", "100")
                .param("amount", "5")
                .param("source", "my"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetUrl").value("/inventory/my"));

    verify(backendApiClient).post(eq("/api/v1/inventory"), any(), eq(InventoryItemDto.class));
  }

  // covers REQ-INV-031 (item-mode passthrough: the create payload carries gameItemId with a null
  // quality/material/mission side, and the merge opt-in is forced off — items always auto-merge)
  @Test
  @WithMockUser
  void addInventoryItemAjax_itemMode_sendsGameItemIdWithNullQuality() throws Exception {
    UUID gameItemId = UUID.randomUUID();
    mockMvc
        .perform(
            post("/inventory/input")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .param("gameItemId", gameItemId.toString())
                .param("locationId", LOCATION_ID.toString())
                .param("amount", "5")
                .param("source", "my"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetUrl").value("/inventory/my"));

    InventoryItemCreateDto request = captureCreateRequest();
    org.assertj.core.api.Assertions.assertThat(request.gameItemId()).isEqualTo(gameItemId);
    org.assertj.core.api.Assertions.assertThat(request.materialId()).isNull();
    org.assertj.core.api.Assertions.assertThat(request.quality()).isNull();
    org.assertj.core.api.Assertions.assertThat(request.missionId()).isNull();
    org.assertj.core.api.Assertions.assertThat(request.mergeStock()).isFalse();
    org.assertj.core.api.Assertions.assertThat(request.missionAllocations()).isEmpty();
  }

  // covers REQ-INV-027 R4 (single-target shorthand): one mission row with a blank amount earmarks
  // the entry's whole amount, and the same shorthand applies independently on the job-order
  // dimension.
  @Test
  @WithMockUser
  void addInventoryItemAjax_singleAllocationWithoutAmount_earmarksFullEntryAmount()
      throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();
    mockMvc
        .perform(
            post("/inventory/input")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .param("materialId", MATERIAL_ID.toString())
                .param("locationId", LOCATION_ID.toString())
                .param("quality", "100")
                .param("amount", "23")
                .param("missionAllocations[0].targetId", missionId.toString())
                .param("missionAllocations[0].amount", "")
                .param("jobOrderAllocations[0].targetId", jobOrderId.toString())
                .param("jobOrderAllocations[0].amount", "")
                .param("source", "my"))
        .andExpect(status().isOk());

    InventoryItemCreateDto request = captureCreateRequest();
    org.assertj.core.api.Assertions.assertThat(request.missionAllocations())
        .containsExactly(new InventoryAllocationInput(missionId, 23d));
    org.assertj.core.api.Assertions.assertThat(request.jobOrderAllocations())
        .containsExactly(new InventoryAllocationInput(jobOrderId, 23d));
  }

  // covers REQ-INV-027 R4 (shorthand does not extend to several targets): with two mission rows the
  // amounts must be explicit, so the blank one is dropped instead of swallowing the entry amount.
  @Test
  @WithMockUser
  void addInventoryItemAjax_multipleAllocations_dropsBlankAmountRow() throws Exception {
    UUID firstMission = UUID.randomUUID();
    UUID secondMission = UUID.randomUUID();
    mockMvc
        .perform(
            post("/inventory/input")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .param("materialId", MATERIAL_ID.toString())
                .param("locationId", LOCATION_ID.toString())
                .param("quality", "100")
                .param("amount", "23")
                .param("missionAllocations[0].targetId", firstMission.toString())
                .param("missionAllocations[0].amount", "5")
                .param("missionAllocations[1].targetId", secondMission.toString())
                .param("missionAllocations[1].amount", "")
                .param("source", "my"))
        .andExpect(status().isOk());

    org.assertj.core.api.Assertions.assertThat(captureCreateRequest().missionAllocations())
        .containsExactly(new InventoryAllocationInput(firstMission, 5d));
  }

  // covers REQ-INV-027 R4: an explicit amount on a single row still wins over the shorthand, and a
  // trailing not-yet-picked row (no target) neither counts as a second target nor is sent.
  @Test
  @WithMockUser
  void addInventoryItemAjax_singleAllocationWithAmount_keepsExplicitAmount() throws Exception {
    UUID missionId = UUID.randomUUID();
    mockMvc
        .perform(
            post("/inventory/input")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .param("materialId", MATERIAL_ID.toString())
                .param("locationId", LOCATION_ID.toString())
                .param("quality", "100")
                .param("amount", "23")
                .param("missionAllocations[0].targetId", missionId.toString())
                .param("missionAllocations[0].amount", "7.5")
                .param("missionAllocations[1].targetId", "")
                .param("missionAllocations[1].amount", "")
                .param("source", "my"))
        .andExpect(status().isOk());

    org.assertj.core.api.Assertions.assertThat(captureCreateRequest().missionAllocations())
        .containsExactly(new InventoryAllocationInput(missionId, 7.5d));
  }

  // covers REQ-INV-027 R4 + the standing personal invariant: the shorthand row counts as an
  // assignment even with no amount typed, so a personal book-in carrying it is refused pre-backend.
  @Test
  @WithMockUser
  void addInventoryItemAjax_personalWithBlankAmountAllocation_returns422() throws Exception {
    mockMvc
        .perform(
            post("/inventory/input")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .param("materialId", MATERIAL_ID.toString())
                .param("locationId", LOCATION_ID.toString())
                .param("quality", "100")
                .param("amount", "23")
                .param("personal", "true")
                .param("missionAllocations[0].targetId", UUID.randomUUID().toString())
                .param("missionAllocations[0].amount", "")
                .param("source", "my"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("INVENTORY_PERSONAL_ASSIGNMENT"));

    verify(backendApiClient, never()).post(anyString(), any(), eq(InventoryItemDto.class));
  }

  // covers REQ-INV-031 (item mode has no mission dimension): the shorthand fills the job-order
  // dimension of an item book-in, while the mission list stays empty even if rows were crafted in.
  @Test
  @WithMockUser
  void addInventoryItemAjax_itemModeSingleOrderWithoutAmount_earmarksFullAmountAndNoMission()
      throws Exception {
    UUID gameItemId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();
    mockMvc
        .perform(
            post("/inventory/input")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .param("gameItemId", gameItemId.toString())
                .param("locationId", LOCATION_ID.toString())
                .param("amount", "4")
                .param("jobOrderAllocations[0].targetId", jobOrderId.toString())
                .param("jobOrderAllocations[0].amount", "")
                .param("missionAllocations[0].targetId", UUID.randomUUID().toString())
                .param("missionAllocations[0].amount", "")
                .param("source", "my"))
        .andExpect(status().isOk());

    InventoryItemCreateDto request = captureCreateRequest();
    org.assertj.core.api.Assertions.assertThat(request.jobOrderAllocations())
        .containsExactly(new InventoryAllocationInput(jobOrderId, 4d));
    org.assertj.core.api.Assertions.assertThat(request.missionAllocations()).isEmpty();
  }

  /**
   * Captures the single create payload the controller posted to the backend.
   *
   * @return the captured {@code /api/v1/inventory} create request.
   */
  private InventoryItemCreateDto captureCreateRequest() {
    org.mockito.ArgumentCaptor<Object> captor = org.mockito.ArgumentCaptor.captor();
    verify(backendApiClient)
        .post(eq("/api/v1/inventory"), captor.capture(), eq(InventoryItemDto.class));
    return (InventoryItemCreateDto) captor.getValue();
  }

  // covers REQ-INV-031 (catalog XOR: both references set -> 422 VALIDATION, no backend call)
  @Test
  @WithMockUser
  void addInventoryItemAjax_bothCatalogReferences_returns422() throws Exception {
    mockMvc
        .perform(
            post("/inventory/input")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .param("materialId", MATERIAL_ID.toString())
                .param("gameItemId", UUID.randomUUID().toString())
                .param("locationId", LOCATION_ID.toString())
                .param("quality", "100")
                .param("amount", "5")
                .param("source", "my"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("VALIDATION"));

    verify(backendApiClient, never()).post(anyString(), any(), eq(InventoryItemDto.class));
  }

  // covers REQ-INV-031 (catalog XOR: neither reference set -> 422 VALIDATION, no backend call)
  @Test
  @WithMockUser
  void addInventoryItemAjax_noCatalogReference_returns422() throws Exception {
    mockMvc
        .perform(
            post("/inventory/input")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .param("locationId", LOCATION_ID.toString())
                .param("amount", "5")
                .param("source", "my"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("VALIDATION"));

    verify(backendApiClient, never()).post(anyString(), any(), eq(InventoryItemDto.class));
  }

  // covers REQ-INV-031 (material mode still requires a quality — the @NotNull moved into the
  // cross-field rule so item mode can omit the field)
  @Test
  @WithMockUser
  void addInventoryItemAjax_materialWithoutQuality_returns422() throws Exception {
    mockMvc
        .perform(
            post("/inventory/input")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .param("materialId", MATERIAL_ID.toString())
                .param("locationId", LOCATION_ID.toString())
                .param("amount", "5")
                .param("source", "my"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("VALIDATION"));

    verify(backendApiClient, never()).post(anyString(), any(), eq(InventoryItemDto.class));
  }

  @Test
  @WithMockUser
  void addInventoryItemAjax_personalWithAssignment_returns422AndDoesNotCallBackend()
      throws Exception {
    mockMvc
        .perform(
            post("/inventory/input")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .param("materialId", MATERIAL_ID.toString())
                .param("locationId", LOCATION_ID.toString())
                .param("quality", "100")
                .param("amount", "5")
                .param("personal", "true")
                .param("jobOrderId", UUID.randomUUID().toString())
                .param("source", "my"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("INVENTORY_PERSONAL_ASSIGNMENT"));

    verify(backendApiClient, never()).post(anyString(), any(), eq(InventoryItemDto.class));
  }

  @Test
  @WithMockUser
  void addInventoryItemAjax_backendFailure_propagatesProblemJsonWithCode() throws Exception {
    when(backendApiClient.post(eq("/api/v1/inventory"), any(), eq(InventoryItemDto.class)))
        .thenThrow(
            new BackendServiceException(
                "conflict", null, 409, "OPTIMISTIC_LOCK", null, Collections.emptyList(), null));

    mockMvc
        .perform(
            post("/inventory/input")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .param("materialId", MATERIAL_ID.toString())
                .param("locationId", LOCATION_ID.toString())
                .param("quality", "100")
                .param("amount", "5")
                .param("source", "my"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK"));
  }

  @Test
  @WithMockUser
  void addInventoryItem_withoutHeader_fallsBackToClassicRedirect() throws Exception {
    // No X-Requested-With → Spring routes to the classic form-post handler (the no-JS fallback),
    // which redirects to the source listing instead of returning JSON.
    mockMvc
        .perform(
            post("/inventory/input")
                .with(csrf())
                .param("materialId", MATERIAL_ID.toString())
                .param("locationId", LOCATION_ID.toString())
                .param("quality", "100")
                .param("amount", "5")
                .param("source", "my"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrlPattern("/inventory/**"));
  }
}
