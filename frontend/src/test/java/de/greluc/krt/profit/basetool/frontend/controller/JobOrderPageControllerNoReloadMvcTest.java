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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.config.LayoutContextLoader;
import de.greluc.krt.profit.basetool.frontend.model.dto.CreateJobOrderDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderItemDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.support.LayoutResponses;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC tests for the AJAX priority reorder in {@link JobOrderWriteController} and the {@code
 * ?fragment=} section swap of {@link JobOrderPageController}'s detail page: the LOGISTICIAN gate,
 * the RFC 7807 error passthrough, and a fragment-sized error response instead of a redirect.
 */
@SpringBootTest
@ActiveProfiles("test")
class JobOrderPageControllerNoReloadMvcTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    when(backendApiClient.get(LayoutResponses.PATH, LayoutContextLoader.MeLayoutResponse.class))
        .thenReturn(LayoutResponses.capabilities(true, true, true));
  }

  private OAuth2AuthenticationToken logisticianToken(UUID userId) {
    Map<String, Object> claims = new HashMap<>();
    claims.put(IdTokenClaimNames.SUB, userId.toString());
    claims.put("preferred_username", "logistician");
    OidcIdToken idToken =
        new OidcIdToken("token-value", Instant.now(), Instant.now().plusSeconds(3600), claims);
    OidcUser oidcUser =
        new DefaultOidcUser(
            Collections.singletonList(new SimpleGrantedAuthority("ROLE_LOGISTICIAN")), idToken);
    return new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "keycloak");
  }

  private static JobOrderDto materialOrder(UUID id, long version) {
    return new JobOrderDto(
        id,
        7,
        null,
        null,
        "Handle",
        null,
        1,
        "OPEN",
        "MATERIAL",
        true,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Instant.now(),
        version,
        null,
        false);
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void updatePriorityAjax_AsLogistician_RelaysAndReturnsOrder() throws Exception {
    UUID orderId = UUID.randomUUID();
    when(backendApiClient.put(
            eq("/api/v1/orders/" + orderId + "/priority?priority=2"),
            isNull(),
            eq(JobOrderDto.class)))
        .thenReturn(materialOrder(orderId, 5L));

    mockMvc
        .perform(put("/orders/" + orderId + "/priority/ajax").param("priority", "2").with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(5));

    verify(backendApiClient)
        .put(
            eq("/api/v1/orders/" + orderId + "/priority?priority=2"),
            isNull(),
            eq(JobOrderDto.class));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void updatePriorityAjax_WhenBackendConflicts_PropagatesProblemJson() throws Exception {
    UUID orderId = UUID.randomUUID();
    when(backendApiClient.put(
            eq("/api/v1/orders/" + orderId + "/priority?priority=3"),
            isNull(),
            eq(JobOrderDto.class)))
        .thenThrow(new BackendServiceException("conflict", null, 409));

    mockMvc
        .perform(put("/orders/" + orderId + "/priority/ajax").param("priority", "3").with(csrf()))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER"})
  void updatePriorityAjax_AsPlainMember_Returns403WithoutCallingBackend() throws Exception {
    UUID orderId = UUID.randomUUID();

    mockMvc
        .perform(put("/orders/" + orderId + "/priority/ajax").param("priority", "2").with(csrf()))
        .andExpect(status().isForbidden());

    verify(backendApiClient, never()).put(any(String.class), any(), eq(JobOrderDto.class));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void blueprintVariantCounting_AsLogistician_RelaysAndReturnsOrder() throws Exception {
    UUID orderId = UUID.randomUUID();
    when(backendApiClient.patch(
            eq("/api/v1/orders/" + orderId + "/blueprint-variant-counting"),
            any(),
            eq(JobOrderDto.class)))
        .thenReturn(materialOrder(orderId, 9L));

    mockMvc
        .perform(
            post("/orders/" + orderId + "/blueprint-variant-counting")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"countBlueprintsWithVariants\":false,\"version\":5}")
                .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(9));

    verify(backendApiClient)
        .patch(
            eq("/api/v1/orders/" + orderId + "/blueprint-variant-counting"),
            any(),
            eq(JobOrderDto.class));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER"})
  void blueprintVariantCounting_AsPlainMember_Returns403WithoutCallingBackend() throws Exception {
    UUID orderId = UUID.randomUUID();

    mockMvc
        .perform(
            post("/orders/" + orderId + "/blueprint-variant-counting")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"countBlueprintsWithVariants\":false,\"version\":5}")
                .with(csrf()))
        .andExpect(status().isForbidden());

    verify(backendApiClient, never()).patch(any(String.class), any(), eq(JobOrderDto.class));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void unlinkInventoryItemAjax_AsLogistician_RelaysAndReturnsRefreshedOrder() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID invId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/orders/" + orderId), eq(JobOrderDto.class)))
        .thenReturn(materialOrder(orderId, 9L));

    mockMvc
        .perform(delete("/orders/" + orderId + "/inventory/" + invId + "/unlink/ajax").with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(9));

    verify(backendApiClient)
        .delete(
            eq("/api/v1/orders/" + orderId + "/inventory/" + invId + "/unlink"), eq(Void.class));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void unlinkInventoryItemAjax_WhenBackendConflicts_PropagatesProblemJson() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID invId = UUID.randomUUID();
    doThrow(new BackendServiceException("conflict", null, 409))
        .when(backendApiClient)
        .delete(
            eq("/api/v1/orders/" + orderId + "/inventory/" + invId + "/unlink"), eq(Void.class));

    mockMvc
        .perform(delete("/orders/" + orderId + "/inventory/" + invId + "/unlink/ajax").with(csrf()))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER"})
  void unlinkInventoryItemAjax_AsPlainMember_Returns403WithoutCallingBackend() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID invId = UUID.randomUUID();

    mockMvc
        .perform(delete("/orders/" + orderId + "/inventory/" + invId + "/unlink/ajax").with(csrf()))
        .andExpect(status().isForbidden());

    verify(backendApiClient, never()).delete(any(String.class), eq(Void.class));
  }

  @Test
  @WithMockUser(roles = {"ADMIN"})
  void deleteOrderAjax_AsAuthenticated_RelaysAndReturnsNoContent() throws Exception {
    UUID orderId = UUID.randomUUID();

    mockMvc.perform(delete("/orders/" + orderId).with(csrf())).andExpect(status().isNoContent());

    verify(backendApiClient).delete(eq("/api/v1/orders/" + orderId), eq(Void.class));
  }

  @Test
  @WithMockUser(roles = {"ADMIN"})
  void deleteOrderAjax_WhenBackendRejects_PropagatesProblemJson() throws Exception {
    UUID orderId = UUID.randomUUID();
    doThrow(new BackendServiceException("in use", null, 409))
        .when(backendApiClient)
        .delete(eq("/api/v1/orders/" + orderId), eq(Void.class));

    mockMvc
        .perform(delete("/orders/" + orderId).with(csrf()))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  private static String editBody(UUID materialId) {
    return "{\"requestingOrgUnitId\":\""
        + UUID.randomUUID()
        + "\",\"handle\":\"h\",\"comment\":\"c\",\"version\":1,\"materials\":[{\"materialId\":\""
        + materialId
        + "\",\"minQuality\":650,\"amount\":5.0}]}";
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void updateOrderAjax_AsLogistician_RelaysAndReturnsOrder() throws Exception {
    UUID orderId = UUID.randomUUID();
    when(backendApiClient.put(eq("/api/v1/orders/" + orderId), any(), eq(JobOrderDto.class)))
        .thenReturn(materialOrder(orderId, 4L));

    mockMvc
        .perform(
            post("/orders/" + orderId + "/update")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(editBody(UUID.randomUUID())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(4));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void updateOrderAjax_EmptyMaterials_Returns400WithoutCallingBackend() throws Exception {
    UUID orderId = UUID.randomUUID();

    mockMvc
        .perform(
            post("/orders/" + orderId + "/update")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"handle\":\"h\",\"version\":1,\"materials\":[]}"))
        .andExpect(status().isBadRequest());

    verify(backendApiClient, never())
        .put(eq("/api/v1/orders/" + orderId), any(), eq(JobOrderDto.class));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER"})
  void updateOrderAjax_AsPlainMember_Returns403() throws Exception {
    UUID orderId = UUID.randomUUID();

    mockMvc
        .perform(
            post("/orders/" + orderId + "/update")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(editBody(UUID.randomUUID())))
        .andExpect(status().isForbidden());
  }

  private static String handoverBody(UUID inventoryItemId) {
    return "{\"handoverTime\":\"2026-04-25T10:00:00Z\",\"recipientHandle\":\"r\","
        + "\"recipientSquadron\":\"\",\"items\":[{\"inventoryItemId\":\""
        + inventoryItemId
        + "\",\"amount\":5.0}]}";
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void createHandoverAjax_AsLogistician_RelaysAndReturnsRefreshedOrder() throws Exception {
    UUID orderId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/orders/" + orderId), eq(JobOrderDto.class)))
        .thenReturn(materialOrder(orderId, 3L));

    mockMvc
        .perform(
            post("/orders/" + orderId + "/handovers")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(handoverBody(UUID.randomUUID())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(3));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void createHandoverAjax_EmptyItems_Returns400() throws Exception {
    UUID orderId = UUID.randomUUID();

    mockMvc
        .perform(
            post("/orders/" + orderId + "/handovers")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipientHandle\":\"r\",\"items\":[]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER"})
  void createHandoverAjax_AsPlainMember_Returns403() throws Exception {
    UUID orderId = UUID.randomUUID();

    mockMvc
        .perform(
            post("/orders/" + orderId + "/handovers")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(handoverBody(UUID.randomUUID())))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void createItemHandoverAjax_AsLogistician_RelaysAndReturnsRefreshedOrder() throws Exception {
    UUID orderId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/orders/" + orderId), eq(JobOrderDto.class)))
        .thenReturn(materialOrder(orderId, 6L));

    mockMvc
        .perform(
            post("/orders/" + orderId + "/item-handovers")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"handoverTime\":\"2026-04-25T10:00:00Z\",\"recipientHandle\":\"r\",\"entries\":[{\"jobOrderItemId\":\""
                        + UUID.randomUUID()
                        + "\",\"amount\":2}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(6));
  }

  private static String productionBody(UUID inventoryItemId, UUID materialId) {
    return "{\"amount\":2,\"version\":1,\"consumption\":[{\"inventoryItemId\":\""
        + inventoryItemId
        + "\",\"materialId\":\""
        + materialId
        + "\",\"amount\":10.0,\"version\":3}]}";
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void bookProductionAjax_AsLogistician_RelaysAndReturnsRefreshedOrder() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/orders/" + orderId), eq(JobOrderDto.class)))
        .thenReturn(materialOrder(orderId, 7L));

    mockMvc
        .perform(
            post("/orders/" + orderId + "/items/" + itemId + "/production")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(productionBody(UUID.randomUUID(), UUID.randomUUID())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(7));

    verify(backendApiClient)
        .post(
            eq("/api/v1/orders/" + orderId + "/items/" + itemId + "/production"),
            any(),
            eq(JobOrderItemDto.class));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void bookProductionAjax_WhenBackendConflicts_PropagatesProblemJson() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    when(backendApiClient.post(
            eq("/api/v1/orders/" + orderId + "/items/" + itemId + "/production"),
            any(),
            eq(JobOrderItemDto.class)))
        .thenThrow(new BackendServiceException("Conflict", null, 409));

    mockMvc
        .perform(
            post("/orders/" + orderId + "/items/" + itemId + "/production")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(productionBody(UUID.randomUUID(), UUID.randomUUID())))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

    verify(backendApiClient, never()).get(eq("/api/v1/orders/" + orderId), eq(JobOrderDto.class));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void createOrderAjax_ValidMaterial_ReturnsNavigationTarget() throws Exception {
    mockMvc
        .perform(
            post("/orders/create")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .param("handle", "Pilot")
                .param("requestingOrgUnitId", UUID.randomUUID().toString())
                .param("materials[0].materialId", UUID.randomUUID().toString())
                .param("materials[0].amount", "5"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetUrl").exists());

    verify(backendApiClient).post(eq("/api/v1/orders"), any(), eq(JobOrderDto.class));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void createOrderAjax_EmptyMaterials_Returns400WithoutCallingBackend() throws Exception {
    mockMvc
        .perform(
            post("/orders/create")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .param("handle", "Pilot"))
        .andExpect(status().isBadRequest());

    verify(backendApiClient, never()).post(eq("/api/v1/orders"), any(), eq(JobOrderDto.class));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void createItemOrderAjax_ValidLine_ReturnsNavigationTarget() throws Exception {
    mockMvc
        .perform(
            post("/orders/items")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .param("handle", "Pilot")
                .param("requestingOrgUnitId", UUID.randomUUID().toString())
                .param("items[0].gameItemId", UUID.randomUUID().toString())
                .param("items[0].blueprintId", UUID.randomUUID().toString())
                .param("items[0].amount", "2")
                .param("items[0].clientLineId", "1")
                .param("items[0].materials[0].materialId", UUID.randomUUID().toString())
                .param("items[0].materials[0].quality", "700"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetUrl").exists());

    verify(backendApiClient).post(eq("/api/v1/orders/items"), any(), eq(JobOrderDto.class));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER"})
  void updateStatus_AsAuthenticated_RelaysAndReturnsOrder() throws Exception {
    UUID orderId = UUID.randomUUID();
    when(backendApiClient.put(
            eq("/api/v1/orders/" + orderId + "/status"), any(), eq(JobOrderDto.class)))
        .thenReturn(materialOrder(orderId, 8L));

    mockMvc
        .perform(
            post("/orders/" + orderId + "/status")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"IN_PROGRESS\",\"version\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(8));

    verify(backendApiClient)
        .put(eq("/api/v1/orders/" + orderId + "/status"), any(), eq(JobOrderDto.class));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER"})
  void updateStatus_WhenBackendRejectsIllegalTransition_Propagates400ProblemJson()
      throws Exception {
    UUID orderId = UUID.randomUUID();
    when(backendApiClient.put(
            eq("/api/v1/orders/" + orderId + "/status"), any(), eq(JobOrderDto.class)))
        .thenThrow(new BackendServiceException("illegal transition", null, 400));

    mockMvc
        .perform(
            post("/orders/" + orderId + "/status")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"OPEN\",\"version\":1}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  @WithAnonymousUser
  void updateStatus_AsAnonymous_IsRedirectedWithoutCallingBackend() throws Exception {
    UUID orderId = UUID.randomUUID();

    mockMvc
        .perform(
            post("/orders/" + orderId + "/status")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"IN_PROGRESS\",\"version\":1}"))
        .andExpect(status().is3xxRedirection());

    verify(backendApiClient, never())
        .put(eq("/api/v1/orders/" + orderId + "/status"), any(), eq(JobOrderDto.class));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER"})
  void updateOrderAsRequesterAjax_RelaysToRequestedEndpointAndReturnsOrder() throws Exception {
    UUID orderId = UUID.randomUUID();
    when(backendApiClient.put(
            eq("/api/v1/orders/" + orderId + "/requested"), any(), eq(JobOrderDto.class)))
        .thenReturn(materialOrder(orderId, 4L));

    mockMvc
        .perform(
            post("/orders/" + orderId + "/requested-update")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(editBody(UUID.randomUUID())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(4));

    ArgumentCaptor<CreateJobOrderDto> captor = ArgumentCaptor.captor();
    verify(backendApiClient)
        .put(
            eq("/api/v1/orders/" + orderId + "/requested"),
            captor.capture(),
            eq(JobOrderDto.class));
    CreateJobOrderDto sent = captor.getValue();
    assertThat(sent.responsibleOrgUnitId()).as("responsible org unit is never relayed").isNull();
    assertThat(sent.requestingOrgUnitId()).as("requesting org unit is stripped").isNull();
    assertThat(sent.handle()).as("handle is stripped").isNull();
    assertThat(sent.comment()).isEqualTo("c");
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER"})
  void updateOrderAsRequesterAjax_EmptyMaterials_Returns400WithoutBackendCall() throws Exception {
    UUID orderId = UUID.randomUUID();

    mockMvc
        .perform(
            post("/orders/" + orderId + "/requested-update")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"c\",\"version\":1,\"materials\":[]}"))
        .andExpect(status().isBadRequest());

    verify(backendApiClient, never())
        .put(eq("/api/v1/orders/" + orderId + "/requested"), any(), eq(JobOrderDto.class));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER"})
  void updateOrderAsRequesterAjax_FrozenAfterDelivery_Propagates400ProblemJson() throws Exception {
    UUID orderId = UUID.randomUUID();
    when(backendApiClient.put(
            eq("/api/v1/orders/" + orderId + "/requested"), any(), eq(JobOrderDto.class)))
        .thenThrow(new BackendServiceException("frozen after delivery", null, 400));

    mockMvc
        .perform(
            post("/orders/" + orderId + "/requested-update")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(editBody(UUID.randomUUID())))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  void viewOrderDetail_AssigneesFragment_RendersTheAssigneesSection() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/orders/" + orderId), eq(JobOrderDto.class)))
        .thenReturn(materialOrder(orderId, 1L));

    var result =
        mockMvc
            .perform(
                get("/orders/" + orderId)
                    .param("fragment", "assignees")
                    .with(authentication(logisticianToken(userId))))
            .andExpect(status().isOk())
            .andReturn();

    String html = result.getResponse().getContentAsString();
    assertThat(html).as("assignees section fragment root").contains("id=\"assignees-section\"");
    assertThat(html).as("no page chrome in the section fragment").doesNotContain("<main");
  }

  @Test
  void viewOrderDetail_FragmentBackendError_ReturnsNonRedirectErrorFragment() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/orders/" + orderId), eq(JobOrderDto.class)))
        .thenThrow(new RuntimeException("backend unavailable"));

    var result =
        mockMvc
            .perform(
                get("/orders/" + orderId)
                    .param("fragment", "materials")
                    .with(authentication(logisticianToken(userId))))
            .andExpect(status().isOk())
            .andReturn();

    String html = result.getResponse().getContentAsString();
    assertThat(html).as("section-sized inline error, not a page").contains("role=\"alert\"");
    assertThat(html).as("no page chrome in the error fragment").doesNotContain("<main");
  }
}
