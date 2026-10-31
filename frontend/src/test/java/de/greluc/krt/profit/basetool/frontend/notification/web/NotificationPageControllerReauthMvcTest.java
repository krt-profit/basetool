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

package de.greluc.krt.profit.basetool.frontend.notification.web;

import static de.greluc.krt.profit.basetool.frontend.support.ReauthPageMvc.expiredSession;
import static de.greluc.krt.profit.basetool.frontend.support.ReauthPageMvc.redirectsToLogin;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendSideChannels;
import de.greluc.krt.profit.basetool.frontend.kernel.security.SecurityConfig;
import de.greluc.krt.profit.basetool.frontend.notification.client.NotificationBackendClient;
import de.greluc.krt.profit.basetool.frontend.support.ReauthPageMvc;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.test.web.servlet.MockMvc;

/** Proves the notification inbox sends a lost session to the login instead of its fallback. */
class NotificationPageControllerReauthMvcTest {

  private final NotificationBackendClient notificationClient =
      mock(NotificationBackendClient.class);

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        ReauthPageMvc.standalone(
            new NotificationPageController(
                notificationClient,
                mock(MessageSource.class),
                mock(BackendSideChannels.class),
                mock(OAuth2AuthorizedClientManager.class),
                new SimpleMeterRegistry(),
                SecurityConfig.roleHierarchy()));
    ReauthPageMvc.signInMember();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void anExpiredSessionOnTheInboxRedirectsToLogin() throws Exception {
    when(notificationClient.page(anyInt(), anyInt())).thenThrow(expiredSession());

    mockMvc.perform(get("/notifications")).andExpect(redirectsToLogin());
  }
}
