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

import de.greluc.krt.profit.basetool.frontend.config.SquadronContextAdvice;
import de.greluc.krt.profit.basetool.frontend.model.dto.CreateJobOrderDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
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
 * MVC tests for the #575 no-reload additions to the job-order controllers: the AJAX priority
 * reorder endpoint ({@code PUT /orders/{id}/priority/ajax}, since the #924 split in {@link
 * JobOrderWriteController}) and the {@code ?fragment=} section-swap support on {@link
 * JobOrderPageController}'s order-detail GET. Verifies the LOGISTICIAN gate, the {@code
 * propagateBackendError} RFC 7807 passthrough that {@code krt-fetch.js} needs for its conflict UX,
 * and that the fragment error path answers with a section-sized fragment instead of a redirect a
 * swap would follow.
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
    // Profit-eligible viewer so the order-detail gate does not redirect to /orders/create.
    when(backendApiClient.get(
            "/api/v1/me/capabilities", SquadronContextAdvice.CapabilitiesResponse.class))
        .thenReturn(new SquadronContextAdvice.CapabilitiesResponse(true, true, true));
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

    // propagateBackendError must answer application/problem+json (not a bare status) so krtFetch
    // can
    // read the RFC 7807 code and drive its conflict UX.
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

  // REQ-ORDERS-021 / #822: the variant-counting toggle proxy relays to the backend PATCH and
  // returns
  // the persisted order so the order-detail JS can patch the @Version and re-render the panel.
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
    // After the detach the endpoint re-fetches the order so the bumped @Version flows to the
    // client.
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
    // e.g. the order still has linked inventory → the page stays put with a toast, not a redirect.
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

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void createOrderAjax_ValidMaterial_ReturnsNavigationTarget() throws Exception {
    // Routed by X-Requested-With; returns the post-create navigation target as JSON (the page
    // navigates itself) instead of a server redirect.
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

    verify(backendApiClient).post(eq("/api/v1/orders"), any(), eq(JobOrderDto.class), eq(true));
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

    verify(backendApiClient, never())
        .post(eq("/api/v1/orders"), any(), eq(JobOrderDto.class), eq(true));
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

    verify(backendApiClient)
        .post(eq("/api/v1/orders/items"), any(), eq(JobOrderDto.class), eq(true));
  }

  // ------------------------------------------------------------------------
  // updateStatus — POST /orders/{id}/status (state-machine transition relay to the backend PUT).
  // Untested before: no success relay, no illegal-transition 400 passthrough, no auth-gate. The
  // endpoint is an audited (Auftraege) state-mutating activity, so a mis-pointed relay or a
  // 400/409 swallowed into a 500 must be caught here.
  // ------------------------------------------------------------------------

  @Test
  @WithMockUser(roles = {"KRT_MEMBER"})
  void updateStatus_AsAuthenticated_RelaysAndReturnsOrder() throws Exception {
    // The gate is isAuthenticated() — deliberately NOT LOGISTICIAN — so a plain member drives the
    // transition; the backend enforces the fine per-order edit scope.
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
    // The backend state machine rejects an illegal transition (e.g. COMPLETED -> OPEN) with a 400.
    // propagateBackendError must re-emit it as application/problem+json (not swallow it into a 500
    // via the generic catch) so the detail-page JS shows the state-machine message, not a generic
    // toast.
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
  void updateStatus_AsAnonymous_Returns403WithoutCallingBackend() throws Exception {
    // /orders/** is permitAll at the URL layer, but the isAuthenticated() method gate still denies
    // an anonymous principal: GlobalExceptionHandler maps the AuthorizationDeniedException to 403
    // (not the SSO entry point), and the backend is never touched.
    UUID orderId = UUID.randomUUID();

    mockMvc
        .perform(
            post("/orders/" + orderId + "/status")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"IN_PROGRESS\",\"version\":1}"))
        .andExpect(status().isForbidden());

    verify(backendApiClient, never())
        .put(eq("/api/v1/orders/" + orderId + "/status"), any(), eq(JobOrderDto.class));
  }

  // ------------------------------------------------------------------------
  // updateOrderAsRequesterAjax — POST /orders/{id}/requested-update (JSON twin, REQ-ORDERS-023).
  // The requester-side material edit relays to PUT /api/v1/orders/{id}/requested and deliberately
  // carries NO hasRole('LOGISTICIAN') — the backend requester gate is the authorization. Only
  // comment + materials + version reach the backend (org unit / handle / status are dropped).
  // ------------------------------------------------------------------------

  @Test
  @WithMockUser(roles = {"KRT_MEMBER"})
  void updateOrderAsRequesterAjax_RelaysToRequestedEndpointAndReturnsOrder() throws Exception {
    // A plain member (no LOGISTICIAN) must pass — the requester path relays to /requested and only
    // comment + materials + version cross the seam. The body carries a requestingOrgUnitId + handle
    // to prove they are stripped (regression (b): a field leak would send them through).
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
    // The empty-materials short-circuit answers 400 before any backend relay.
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
    // The backend freezes a material after it has been (partly) delivered and rejects a requester
    // edit that touches it with a 400. propagateBackendError must re-emit problem+json so the
    // requester sees an actionable message instead of a generic 500.
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
    // The order:{id} live-sync receiver refreshes the `assignees` section via GET
    // /orders/{id}?fragment=assignees (REQ-FE-015). The fragment switch must map it to the
    // assigneesSection fragment (HTTP 200, section-sized, no page chrome), not fall through to the
    // whole page — the new case added alongside the ORDER topic class.
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
    // The backend read fails mid section-swap (circuit-breaker open / timeout / 5xx). The fragment
    // path must answer with a section-sized error fragment (HTTP 200), never the classic
    // redirect:/orders — krtFetch.swap would otherwise follow the 302 into the small results
    // container (#575, mirrors the #574 fix).
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
