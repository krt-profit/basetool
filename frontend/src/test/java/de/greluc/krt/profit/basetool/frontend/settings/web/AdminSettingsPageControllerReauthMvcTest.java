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

package de.greluc.krt.profit.basetool.frontend.settings.web;

import static de.greluc.krt.profit.basetool.frontend.support.ReauthPageMvc.expiredSession;
import static de.greluc.krt.profit.basetool.frontend.support.ReauthPageMvc.redirectsToLogin;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.CatalogueCacheEviction;
import de.greluc.krt.profit.basetool.frontend.settings.client.SettingsBackendClient;
import de.greluc.krt.profit.basetool.frontend.settings.client.SettingsBackendClient.SystemSetting;
import de.greluc.krt.profit.basetool.frontend.support.ReauthPageMvc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

/** Proves the admin settings page sends a lost session to the login (REQ-FE-036). */
class AdminSettingsPageControllerReauthMvcTest {

  private final SettingsBackendClient client = mock(SettingsBackendClient.class);

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        ReauthPageMvc.standalone(
            new AdminSettingsPageController(
                client, mock(CatalogueCacheEviction.class), mock(MessageSource.class)));
    ReauthPageMvc.signInMember("ADMIN");
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void anExpiredSessionOnASettingRedirectsToLogin() throws Exception {
    when(client.read(SystemSetting.OPERATION_TRANSFER_FEE_RATE)).thenThrow(expiredSession());

    mockMvc.perform(get("/admin/settings")).andExpect(redirectsToLogin());
  }

  @Test
  void anExpiredSessionOnTheSquadronToggleRedirectsToLogin() throws Exception {
    when(client.squadronPage(anyInt())).thenThrow(expiredSession());

    mockMvc.perform(get("/admin/settings")).andExpect(redirectsToLogin());
  }
}
