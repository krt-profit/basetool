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

package de.greluc.krt.profit.basetool.frontend.refinery.web;

import static de.greluc.krt.profit.basetool.frontend.support.ReauthPageMvc.expiredSession;
import static de.greluc.krt.profit.basetool.frontend.support.ReauthPageMvc.redirectsToLogin;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.IngestHandoffService;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.ParallelPageLoader;
import de.greluc.krt.profit.basetool.frontend.refinery.client.RefineryBackendClient;
import de.greluc.krt.profit.basetool.frontend.support.ReauthPageMvc;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

/** Proves the refinery-order pages send a lost session to the login instead of their fallbacks. */
class RefineryOrderPageControllerReauthMvcTest {

  private final RefineryBackendClient client = mock(RefineryBackendClient.class);

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        ReauthPageMvc.standalone(
            new RefineryOrderPageController(
                client,
                mock(RoleHierarchy.class),
                mock(IngestHandoffService.class),
                new ParallelPageLoader()));
    ReauthPageMvc.signInMember();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void anExpiredSessionOnTheOrderListRedirectsToLogin() throws Exception {
    when(client.orderPage(anyBoolean(), any(), anyBoolean(), any(), any(), anyInt(), anyInt()))
        .thenThrow(expiredSession());

    mockMvc.perform(get("/refinery-orders")).andExpect(redirectsToLogin());
  }

  @Test
  void anExpiredSessionOnTheOrderDetailRedirectsToLogin() throws Exception {
    UUID id = UUID.randomUUID();
    when(client.order(id)).thenThrow(expiredSession());

    mockMvc.perform(get("/refinery-orders/{id}", id)).andExpect(redirectsToLogin());
  }
}
