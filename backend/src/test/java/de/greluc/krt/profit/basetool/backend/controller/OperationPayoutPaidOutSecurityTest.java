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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.dto.OperationPayoutStatusDto;
import de.greluc.krt.profit.basetool.backend.service.OperationService;
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
 * Security-focused MockMvc tests for {@link OperationController#setPayoutStatus} — the per-row
 * "Bezahlt" toggle behind {@code PUT /api/v1/operations/{id}/payouts/paid-out}. The endpoint
 * enforces an asymmetric authorization rule: any mission manager (or higher via the role hierarchy)
 * can flip {@code paidOut=true}, but only ADMIN or OFFICER may clear it back to {@code false}. The
 * tests pin each branch of that gate so a future refactor does not accidentally collapse the
 * asymmetry back to a symmetric {@code hasRole('MISSION_MANAGER')} check.
 */
@SpringBootTest
class OperationPayoutPaidOutSecurityTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private OperationService operationService;
  @MockitoBean private OwnerScopeService ownerScopeService;
  @MockitoBean private JwtDecoder jwtDecoder;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private static SimpleGrantedAuthority admin() {
    return new SimpleGrantedAuthority("ROLE_ADMIN");
  }

  private static SimpleGrantedAuthority officer() {
    return new SimpleGrantedAuthority("ROLE_OFFICER");
  }

  private static SimpleGrantedAuthority missionManager() {
    return new SimpleGrantedAuthority("ROLE_MISSION_MANAGER");
  }

  private static OperationPayoutStatusDto refreshedRow(String key) {
    return new OperationPayoutStatusDto(key, true, null, null);
  }

  private static String body(String key, boolean paidOut) {
    return "{\"participantKey\":\"" + key + "\",\"paidOut\":" + paidOut + "}";
  }

  @Test
  void missionManager_canSet_paidOutTrue() throws Exception {
    UUID opId = UUID.randomUUID();
    String key = UUID.randomUUID().toString();
    when(ownerScopeService.canEditOperation(opId)).thenReturn(true);
    when(operationService.setPayoutStatus(eq(opId), eq(key), eq(true)))
        .thenReturn(refreshedRow(key));

    mockMvc
        .perform(
            put("/api/v1/operations/" + opId + "/payouts/paid-out")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(key, true))
                .with(jwt().authorities(missionManager())))
        .andExpect(status().isOk());

    verify(operationService).setPayoutStatus(opId, key, true);
  }

  @Test
  void missionManager_isForbiddenFromSetting_paidOutFalse() throws Exception {
    UUID opId = UUID.randomUUID();
    String key = UUID.randomUUID().toString();
    when(ownerScopeService.canEditOperation(opId)).thenReturn(true);

    mockMvc
        .perform(
            put("/api/v1/operations/" + opId + "/payouts/paid-out")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(key, false))
                .with(jwt().authorities(missionManager())))
        .andExpect(status().isForbidden());

    // Service must not be invoked when the @PreAuthorize denies — the audit trail
    // would otherwise record a fake "this user toggled the flag" event the user
    // had no permission to trigger.
    verify(operationService, never()).setPayoutStatus(any(), any(), any(Boolean.class));
  }

  @Test
  void officer_canSet_paidOutFalse() throws Exception {
    UUID opId = UUID.randomUUID();
    String key = UUID.randomUUID().toString();
    when(ownerScopeService.canEditOperation(opId)).thenReturn(true);
    when(operationService.setPayoutStatus(eq(opId), eq(key), eq(false)))
        .thenReturn(refreshedRow(key));

    mockMvc
        .perform(
            put("/api/v1/operations/" + opId + "/payouts/paid-out")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(key, false))
                .with(jwt().authorities(officer())))
        .andExpect(status().isOk());

    verify(operationService).setPayoutStatus(opId, key, false);
  }

  @Test
  void admin_canSet_paidOutFalse() throws Exception {
    UUID opId = UUID.randomUUID();
    String key = UUID.randomUUID().toString();
    when(ownerScopeService.canEditOperation(opId)).thenReturn(true);
    when(operationService.setPayoutStatus(eq(opId), eq(key), eq(false)))
        .thenReturn(refreshedRow(key));

    mockMvc
        .perform(
            put("/api/v1/operations/" + opId + "/payouts/paid-out")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(key, false))
                .with(jwt().authorities(admin())))
        .andExpect(status().isOk());

    verify(operationService).setPayoutStatus(opId, key, false);
  }

  @Test
  void officer_canStillSet_paidOutTrue() throws Exception {
    UUID opId = UUID.randomUUID();
    String key = UUID.randomUUID().toString();
    when(ownerScopeService.canEditOperation(opId)).thenReturn(true);
    when(operationService.setPayoutStatus(eq(opId), eq(key), eq(true)))
        .thenReturn(refreshedRow(key));

    mockMvc
        .perform(
            put("/api/v1/operations/" + opId + "/payouts/paid-out")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(key, true))
                .with(jwt().authorities(officer())))
        .andExpect(status().isOk());

    verify(operationService).setPayoutStatus(opId, key, true);
  }
}
