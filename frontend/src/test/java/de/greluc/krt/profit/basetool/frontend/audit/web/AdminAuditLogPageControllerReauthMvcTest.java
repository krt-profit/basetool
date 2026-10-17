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

package de.greluc.krt.profit.basetool.frontend.audit.web;

import static de.greluc.krt.profit.basetool.frontend.support.ReauthPageMvc.expiredSession;
import static de.greluc.krt.profit.basetool.frontend.support.ReauthPageMvc.redirectsToLogin;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import de.greluc.krt.profit.basetool.frontend.audit.client.AuditBackendClient;
import de.greluc.krt.profit.basetool.frontend.support.ReauthPageMvc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

/** Proves the unified audit log sends a lost session to the login (REQ-FE-036). */
class AdminAuditLogPageControllerReauthMvcTest {

  private final AuditBackendClient client = mock(AuditBackendClient.class);

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = ReauthPageMvc.standalone(new AdminAuditLogPageController(client));
    ReauthPageMvc.signInMember("ADMIN");
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void anExpiredSessionOnTheEventsRedirectsToLogin() throws Exception {
    when(client.bankEvents(any())).thenThrow(expiredSession());

    mockMvc.perform(get("/admin/audit-log")).andExpect(redirectsToLogin());
  }

  @Test
  void anExpiredSessionOnTheExchangeRegistryRedirectsToLogin() throws Exception {
    when(client.exchangeClients()).thenThrow(expiredSession());

    mockMvc.perform(get("/admin/audit-log")).andExpect(redirectsToLogin());
  }
}
