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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.service.DeletionRequestService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MockMvc gate matrix for the erasure-request surface (REQ-SEC-061).
 *
 * <ul>
 *   <li>{@code /api/v1/users/me/deletion-request} is open to every authenticated member and accepts
 *       no user id.
 *   <li>{@code /api/v1/admin/deletion-requests} is admin-only at the URL matcher and the method
 *       level.
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class DeletionRequestControllerSecurityTest {

  @Autowired private WebApplicationContext context;
  private MockMvc mockMvc;

  @MockitoBean private DeletionRequestService deletionRequestService;
  @MockitoBean private JwtDecoder jwtDecoder;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void myRequest_member_isAllowed() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/users/me/deletion-request")
                .with(
                    jwt()
                        .jwt(j -> j.subject(UUID.randomUUID().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
        .andExpect(status().isNoContent());
  }

  @Test
  void myRequest_anonymous_isUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/users/me/deletion-request")).andExpect(status().isUnauthorized());
  }

  @Test
  void adminQueue_member_isForbidden() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/admin/deletion-requests")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminQueue_officer_isForbidden() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/admin/deletion-requests")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OFFICER"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminQueue_bankManagement_isForbidden() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/admin/deletion-requests")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BANK_MANAGEMENT"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminQueue_admin_isAllowed() throws Exception {
    when(deletionRequestService.listPending()).thenReturn(List.of());

    mockMvc
        .perform(
            get("/api/v1/admin/deletion-requests")
                .with(
                    jwt()
                        .jwt(j -> j.subject(UUID.randomUUID().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk());
  }

  @Test
  void execute_officer_isForbiddenAndNeverReachesTheService() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/deletion-requests/{id}/execute", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"grantHistoryErasure\":true}")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OFFICER"))))
        .andExpect(status().isForbidden());

    verify(deletionRequestService, never()).execute(any(), anyBoolean(), any());
  }

  @Test
  void decline_member_isForbidden() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/deletion-requests/{id}/decline", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"grantHistoryErasure\":false,\"note\":\"nope\"}")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
        .andExpect(status().isForbidden());

    verify(deletionRequestService, never()).decline(any(), any(), any());
  }
}
