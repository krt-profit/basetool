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

package de.greluc.krt.profit.basetool.backend.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.service.MaterialClaimService;
import de.greluc.krt.profit.basetool.backend.service.OwnerScopeService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies that the material-claim write endpoints forbid a LOGISTICIAN outside a profit-eligible
 * org unit and admit a profit-eligible one ({@code @ownerScopeService.canViewJobOrders()}).
 */
@SpringBootTest
class MaterialClaimControllerSecurityTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private MaterialClaimService materialClaimService;
  @MockitoBean private OwnerScopeService ownerScopeService;
  @MockitoBean private JwtDecoder jwtDecoder;

  private static final UUID ORDER_ID = UUID.randomUUID();
  private static final UUID CLAIM_ID = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private static SimpleGrantedAuthority logistician() {
    return new SimpleGrantedAuthority("ROLE_LOGISTICIAN");
  }

  /**
   * A valid {@link de.greluc.krt.profit.basetool.backend.model.dto.CreateClaimDto} body so bean
   * validation passes and the request reaches the {@code @PreAuthorize} gate rather than a 400.
   */
  private static String validClaimBody() {
    return "{\"materialId\":\""
        + UUID.randomUUID()
        + "\",\"qualityRequirement\":\"GOOD\",\"claimingOrgUnitId\":\""
        + UUID.randomUUID()
        + "\",\"amount\":5.0}";
  }

  @Test
  void upsertClaim_nonProfitLogistician_isForbidden() throws Exception {
    when(ownerScopeService.canViewJobOrders()).thenReturn(false);

    mockMvc
        .perform(
            post("/api/v1/orders/{id}/claims", ORDER_ID)
                .with(jwt().authorities(logistician()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validClaimBody()))
        .andExpect(status().isForbidden());

    verify(materialClaimService, never()).upsertClaim(any(), any());
  }

  @Test
  void upsertClaim_profitEligibleLogistician_reachesService() throws Exception {
    when(ownerScopeService.canViewJobOrders()).thenReturn(true);

    mockMvc
        .perform(
            post("/api/v1/orders/{id}/claims", ORDER_ID)
                .with(jwt().authorities(logistician()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validClaimBody()))
        .andExpect(status().isCreated());

    verify(materialClaimService).upsertClaim(any(), any());
  }

  @Test
  void withdrawClaim_nonProfitLogistician_isForbidden() throws Exception {
    when(ownerScopeService.canViewJobOrders()).thenReturn(false);

    mockMvc
        .perform(
            delete("/api/v1/orders/{id}/claims/{claimId}", ORDER_ID, CLAIM_ID)
                .with(jwt().authorities(logistician())))
        .andExpect(status().isForbidden());

    verify(materialClaimService, never()).withdrawClaim(any(), any());
  }

  @Test
  void withdrawClaim_profitEligibleLogistician_reachesService() throws Exception {
    when(ownerScopeService.canViewJobOrders()).thenReturn(true);

    mockMvc
        .perform(
            delete("/api/v1/orders/{id}/claims/{claimId}", ORDER_ID, CLAIM_ID)
                .with(jwt().authorities(logistician())))
        .andExpect(status().isNoContent());

    verify(materialClaimService).withdrawClaim(ORDER_ID, CLAIM_ID);
  }
}
