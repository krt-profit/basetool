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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.service.UserRegistrationService;
import de.greluc.krt.profit.basetool.backend.service.UserService;
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
 * MockMvc gate matrix for the registration-approval admin surface, focused on the rejected-list
 * read and the reopen action added for REQ-SEC-034. Both are admin-only: an elevated non-admin role
 * must not be able to see who was rejected, nor push a rejected account back into the approval
 * queue.
 *
 * <p>Also pins the deliberate refusal of {@code ?status=ACTIVE}: the queue endpoint serves the two
 * decision-relevant states only and must not degrade into an unbounded member dump.
 */
@SpringBootTest
@ActiveProfiles("test")
class DiscordRegistrationAdminControllerSecurityTest {

  private static final String BASE = "/api/v1/admin/registrations";

  @Autowired private WebApplicationContext context;
  private MockMvc mockMvc;

  @MockitoBean private UserRegistrationService userRegistrationService;
  @MockitoBean private UserService userService;
  @MockitoBean private JwtDecoder jwtDecoder;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private static SimpleGrantedAuthority role(String name) {
    return new SimpleGrantedAuthority(name);
  }

  private static User rejected() {
    User u = new User();
    u.setId(UUID.randomUUID());
    u.setApprovalStatus(ApprovalStatus.REJECTED);
    u.setVersion(0L);
    return u;
  }

  @Test
  void rejectedList_officer_isForbidden() throws Exception {
    mockMvc
        .perform(
            get(BASE).param("status", "REJECTED").with(jwt().authorities(role("ROLE_OFFICER"))))
        .andExpect(status().isForbidden());

    verify(userRegistrationService, never()).findRejectedRegistrations();
  }

  @Test
  void rejectedList_admin_isAllowed() throws Exception {
    when(userRegistrationService.findRejectedRegistrations()).thenReturn(List.of(rejected()));

    mockMvc
        .perform(get(BASE).param("status", "REJECTED").with(jwt().authorities(role("ROLE_ADMIN"))))
        .andExpect(status().isOk());

    verify(userRegistrationService).findRejectedRegistrations();
  }

  @Test
  void list_withoutStatus_stillServesThePendingQueue() throws Exception {
    when(userRegistrationService.findPendingRegistrations()).thenReturn(List.of());

    mockMvc
        .perform(get(BASE).with(jwt().authorities(role("ROLE_ADMIN"))))
        .andExpect(status().isOk());

    verify(userRegistrationService).findPendingRegistrations();
  }

  @Test
  void list_withActiveStatus_isRejectedAsBadRequest() throws Exception {
    mockMvc
        .perform(get(BASE).param("status", "ACTIVE").with(jwt().authorities(role("ROLE_ADMIN"))))
        .andExpect(status().isBadRequest());

    verify(userRegistrationService, never()).findPendingRegistrations();
    verify(userRegistrationService, never()).findRejectedRegistrations();
  }

  @Test
  void reopen_officer_isForbidden() throws Exception {
    mockMvc
        .perform(
            post(BASE + "/" + UUID.randomUUID() + "/reopen")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}")
                .with(jwt().authorities(role("ROLE_OFFICER"))))
        .andExpect(status().isForbidden());

    verify(userRegistrationService, never()).reopenRegistration(any(), any(), any(), any());
  }

  @Test
  void reopen_bankEmployee_isForbidden() throws Exception {
    mockMvc
        .perform(
            post(BASE + "/" + UUID.randomUUID() + "/reopen")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}")
                .with(jwt().authorities(role("ROLE_BANK_EMPLOYEE"))))
        .andExpect(status().isForbidden());

    verify(userRegistrationService, never()).reopenRegistration(any(), any(), any(), any());
  }

  @Test
  void reopen_admin_isAllowed_andRelaysTheReasonAndVersion() throws Exception {
    UUID adminId = UUID.randomUUID();
    UUID registrationId = UUID.randomUUID();
    User reopened = rejected();
    reopened.setId(registrationId);
    reopened.setApprovalStatus(ApprovalStatus.PENDING);
    when(userService.getUserIdFromJwt(any())).thenReturn(adminId);
    when(userRegistrationService.reopenRegistration(registrationId, "wrong call", 2L, adminId))
        .thenReturn(reopened);

    mockMvc
        .perform(
            post(BASE + "/" + registrationId + "/reopen")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"wrong call\",\"version\":2}")
                .with(
                    jwt().jwt(j -> j.subject(adminId.toString())).authorities(role("ROLE_ADMIN"))))
        .andExpect(status().isOk());

    verify(userRegistrationService).reopenRegistration(registrationId, "wrong call", 2L, adminId);
  }

  @Test
  void reopen_admin_withoutBody_passesNullReasonAndVersion() throws Exception {
    UUID adminId = UUID.randomUUID();
    UUID registrationId = UUID.randomUUID();
    User reopened = rejected();
    reopened.setId(registrationId);
    reopened.setApprovalStatus(ApprovalStatus.PENDING);
    when(userService.getUserIdFromJwt(any())).thenReturn(adminId);
    when(userRegistrationService.reopenRegistration(registrationId, null, null, adminId))
        .thenReturn(reopened);

    mockMvc
        .perform(
            post(BASE + "/" + registrationId + "/reopen")
                .with(
                    jwt().jwt(j -> j.subject(adminId.toString())).authorities(role("ROLE_ADMIN"))))
        .andExpect(status().isOk());

    verify(userRegistrationService).reopenRegistration(registrationId, null, null, adminId);
  }
}
