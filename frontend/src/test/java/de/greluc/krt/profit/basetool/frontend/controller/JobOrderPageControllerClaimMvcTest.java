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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.ClaimDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.CreateClaimDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.SquadronReferenceDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.time.Instant;
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
 * MVC tests for the AJAX claim ("Eintragung") relay endpoints in {@link JobOrderWriteController}
 * (Phase 6, #346): create/update via {@code POST /orders/{id}/claims} and withdrawal via {@code
 * POST /orders/{id}/claims/{claimId}/withdraw}. Verifies the success path, the backend-status
 * propagation (409 conflict, 400 overclaim) that the detail-page JS turns into a clean toast, and
 * the role gate that returns 403 for a plain member without ever calling the backend.
 */
@SpringBootTest
class JobOrderPageControllerClaimMvcTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private static String claimBody(UUID materialId, UUID squadronId, double amount) {
    return "{\"materialId\":\""
        + materialId
        + "\",\"qualityRequirement\":\"GOOD\",\"claimingOrgUnitId\":\""
        + squadronId
        + "\",\"amount\":"
        + amount
        + "}";
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void upsertClaim_AsLogistician_RelaysAndReturnsCreated() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID squadronId = UUID.randomUUID();
    ClaimDto created =
        new ClaimDto(
            UUID.randomUUID(),
            new SquadronReferenceDto(squadronId, "Alpha Flight", "ALF"),
            6.0,
            null,
            Instant.now(),
            1L);
    doReturn(created)
        .when(backendApiClient)
        .post(
            eq("/api/v1/orders/" + orderId + "/claims"),
            any(CreateClaimDto.class),
            eq(ClaimDto.class));

    mockMvc
        .perform(
            post("/orders/" + orderId + "/claims")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(claimBody(materialId, squadronId, 6.0)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.amount").value(6.0))
        .andExpect(jsonPath("$.claimingOrgUnit.shorthand").value("ALF"));

    verify(backendApiClient)
        .post(
            eq("/api/v1/orders/" + orderId + "/claims"),
            any(CreateClaimDto.class),
            eq(ClaimDto.class));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void upsertClaim_WhenBackendReturns409_PropagatesConflict() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID squadronId = UUID.randomUUID();
    doThrow(new BackendServiceException("conflict", null, 409))
        .when(backendApiClient)
        .post(
            eq("/api/v1/orders/" + orderId + "/claims"),
            any(CreateClaimDto.class),
            eq(ClaimDto.class));

    mockMvc
        .perform(
            post("/orders/" + orderId + "/claims")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(claimBody(materialId, squadronId, 6.0)))
        .andExpect(status().isConflict());
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void upsertClaim_WhenBackendRejectsOverclaim_Propagates400() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID squadronId = UUID.randomUUID();
    doThrow(new BackendServiceException("overclaim", null, 400))
        .when(backendApiClient)
        .post(
            eq("/api/v1/orders/" + orderId + "/claims"),
            any(CreateClaimDto.class),
            eq(ClaimDto.class));

    mockMvc
        .perform(
            post("/orders/" + orderId + "/claims")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(claimBody(materialId, squadronId, 999.0)))
        .andExpect(status().isBadRequest());
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER"})
  void upsertClaim_AsPlainMember_Returns403WithoutCallingBackend() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID squadronId = UUID.randomUUID();

    mockMvc
        .perform(
            post("/orders/" + orderId + "/claims")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(claimBody(materialId, squadronId, 6.0)))
        .andExpect(status().isForbidden());

    verify(backendApiClient, never())
        .post(any(String.class), any(CreateClaimDto.class), eq(ClaimDto.class));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void withdrawClaim_AsLogistician_RelaysAndReturnsNoContent() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID claimId = UUID.randomUUID();
    doReturn(null)
        .when(backendApiClient)
        .delete(eq("/api/v1/orders/" + orderId + "/claims/" + claimId), eq(Void.class));

    mockMvc
        .perform(post("/orders/" + orderId + "/claims/" + claimId + "/withdraw").with(csrf()))
        .andExpect(status().isNoContent());

    verify(backendApiClient)
        .delete(eq("/api/v1/orders/" + orderId + "/claims/" + claimId), eq(Void.class));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void withdrawClaim_WhenBackendReturns409_PropagatesConflict() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID claimId = UUID.randomUUID();
    doThrow(new BackendServiceException("conflict", null, 409))
        .when(backendApiClient)
        .delete(eq("/api/v1/orders/" + orderId + "/claims/" + claimId), eq(Void.class));

    mockMvc
        .perform(post("/orders/" + orderId + "/claims/" + claimId + "/withdraw").with(csrf()))
        .andExpect(status().isConflict());
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER"})
  void withdrawClaim_AsPlainMember_Returns403() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID claimId = UUID.randomUUID();

    mockMvc
        .perform(post("/orders/" + orderId + "/claims/" + claimId + "/withdraw").with(csrf()))
        .andExpect(status().isForbidden());

    verify(backendApiClient, never()).delete(any(String.class), eq(Void.class));
  }
}
