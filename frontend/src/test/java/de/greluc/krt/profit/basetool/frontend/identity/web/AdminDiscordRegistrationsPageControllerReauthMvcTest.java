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

package de.greluc.krt.profit.basetool.frontend.identity.web;

import static de.greluc.krt.profit.basetool.frontend.support.ReauthPageMvc.expiredSession;
import static de.greluc.krt.profit.basetool.frontend.support.ReauthPageMvc.redirectsToLogin;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import de.greluc.krt.profit.basetool.frontend.identity.client.IdentityBackendClient;
import de.greluc.krt.profit.basetool.frontend.support.ReauthPageMvc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

/** Proves the Discord registration queue sends a lost session to the login (REQ-FE-036). */
class AdminDiscordRegistrationsPageControllerReauthMvcTest {

  private final IdentityBackendClient client = mock(IdentityBackendClient.class);

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = ReauthPageMvc.standalone(new AdminDiscordRegistrationsPageController(client));
    ReauthPageMvc.signInMember("ADMIN");
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void anExpiredSessionOnThePendingQueueRedirectsToLogin() throws Exception {
    when(client.pendingRegistrations()).thenThrow(expiredSession());

    mockMvc.perform(get("/admin/discord-registrations")).andExpect(redirectsToLogin());
  }

  @Test
  void anExpiredSessionOnTheRejectedListRedirectsToLogin() throws Exception {
    when(client.rejectedRegistrations()).thenThrow(expiredSession());

    mockMvc.perform(get("/admin/discord-registrations")).andExpect(redirectsToLogin());
  }
}
