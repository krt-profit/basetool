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
 * Pins the Spring Security URL-filter chain for {@code GET /api/v1/users/{id}/memberships}. Before
 * the SecurityConfig fix the URL fell into the {@code /api/v1/users/**} catch-all which is gated on
 * {@code hasRole('ADMIN')} — every non-admin (Officer, KRT Member) got a 403 from the URL filter
 * before the {@link UserController#getUserMemberships} method-level {@code @PreAuthorize} was even
 * evaluated. The frontend's {@code OrgUnitContextAdvice} then silently swallowed the 403 and
 * rendered an empty {@code availableOrgUnits} list, surfacing as "Kein Bereichskontext" in the
 * sidebar chip for any non-admin user.
 *
 * <p>These tests assert all four caller classes can reach the endpoint at the URL-filter level. The
 * concrete role allow-list is owned by the method-level {@code @PreAuthorize} (defence in depth) —
 * the URL rule only opens the gate. A regression where the catch-all swallows the path again would
 * surface here as a 403 instead of the expected 200.
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
   * Squadron-Member users must reach {@code GET /api/v1/users/{id}/memberships}. Regression guard
   * for the original bug: the {@code /api/v1/users/**} catch-all was {@code hasRole('ADMIN')} only,
   * masking the method-level allow-list.
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
   * Unauthenticated callers are rejected at the URL-filter level. The application-wide {@code
   * anyRequest().authenticated()} catch-all in SecurityConfig already covers this — the test pins
   * that the new {@code /memberships}-specific rule does not accidentally weaken the gate to
   * permit-all.
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
