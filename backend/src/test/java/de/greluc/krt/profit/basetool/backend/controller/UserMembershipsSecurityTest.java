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
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.service.OrgUnitMembershipQueryService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies that every caller class passes the URL filter for {@code GET
 * /api/v1/users/{id}/memberships} instead of hitting the admin-only {@code /api/v1/users/**} rule;
 * the method-level {@code @PreAuthorize} owns the role allow-list.
 */
@SpringBootTest
class UserMembershipsSecurityTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private OrgUnitMembershipQueryService orgUnitMembershipQueryService;
  @MockitoBean private JwtDecoder jwtDecoder;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    when(orgUnitMembershipQueryService.listOptionsForUser(any(UUID.class))).thenReturn(List.of());
  }

  /**
   * Verifies that a squadron member reaches {@code GET /api/v1/users/{id}/memberships}.
   *
   * @throws Exception MockMvc plumbing.
   */
  @Test
  void getUserMemberships_squadronMember_isAllowed() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/users/{id}/memberships", UUID.randomUUID())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
        .andExpect(status().isOk());
  }

  /**
   * Officer users must reach the endpoint — same gate as KRT Member; OFFICER is the typical caller
   * class that surfaced the original bug because they expect to see their own sidebar chip.
   *
   * @throws Exception MockMvc plumbing.
   */
  @Test
  void getUserMemberships_officer_isAllowed() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/users/{id}/memberships", UUID.randomUUID())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OFFICER"))))
        .andExpect(status().isOk());
  }

  /**
   * Admin users keep their access (no regression in the other direction).
   *
   * @throws Exception MockMvc plumbing.
   */
  @Test
  void getUserMemberships_admin_isAllowed() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/users/{id}/memberships", UUID.randomUUID())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk());
  }

  /**
   * Bank employees must reach the endpoint (REQ-BANK-044): the deposit/withdrawal counterparty
   * org-unit picker resolves the chosen user's memberships here, and a bank employee need not hold
   * any org-role (REQ-BANK-008). Pins the URL-filter widening that admits {@code BANK_EMPLOYEE}.
   *
   * @throws Exception MockMvc plumbing.
   */
  @Test
  void getUserMemberships_bankEmployee_isAllowed() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/users/{id}/memberships", UUID.randomUUID())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BANK_EMPLOYEE"))))
        .andExpect(status().isOk());
  }

  /**
   * Verifies that unauthenticated callers are rejected at the URL-filter level.
   *
   * @throws Exception MockMvc plumbing.
   */
  @Test
  void getUserMemberships_anonymous_isRejected() throws Exception {
    mockMvc
        .perform(get("/api/v1/users/{id}/memberships", UUID.randomUUID()))
        .andExpect(status().isUnauthorized());
  }
}
