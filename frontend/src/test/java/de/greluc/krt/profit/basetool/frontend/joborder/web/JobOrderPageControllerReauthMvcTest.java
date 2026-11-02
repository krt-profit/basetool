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

package de.greluc.krt.profit.basetool.frontend.joborder.web;

import static de.greluc.krt.profit.basetool.frontend.support.ReauthPageMvc.expiredSession;
import static de.greluc.krt.profit.basetool.frontend.support.ReauthPageMvc.redirectsToLogin;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import de.greluc.krt.profit.basetool.frontend.joborder.client.JobOrderBackendClient;
import de.greluc.krt.profit.basetool.frontend.joborder.model.OrderListScope;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.ParallelPageLoader;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.QualityTierCatalog;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.LogisticianGate;
import de.greluc.krt.profit.basetool.frontend.support.ReauthPageMvc;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

/** Proves the job-order pages send a lost session to the login instead of their fallbacks. */
class JobOrderPageControllerReauthMvcTest {

  private final JobOrderBackendClient client = mock(JobOrderBackendClient.class);

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        ReauthPageMvc.standalone(
            new JobOrderPageController(
                client,
                new JobOrderPageReader(client, mock(ParallelPageLoader.class)),
                mock(LogisticianGate.class),
                mock(QualityTierCatalog.class),
                mock(JobOrderItemLines.class)));
    ReauthPageMvc.signInMember();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void anExpiredSessionOnTheOrderListRedirectsToLogin() throws Exception {
    when(client.orders(any(OrderListScope.class), anyInt(), anyInt(), any(), any()))
        .thenThrow(expiredSession());

    mockMvc
        .perform(
            get("/orders")
                .param("canViewJobOrders", "true")
                .param("canViewOwnJobOrders", "false")
                .param("canViewAssignedJobOrders", "false"))
        .andExpect(redirectsToLogin());
  }

  @Test
  void anExpiredSessionOnTheOrderDetailRedirectsToLogin() throws Exception {
    UUID id = UUID.randomUUID();
    when(client.order(id)).thenThrow(expiredSession());

    mockMvc
        .perform(
            get("/orders/{id}", id)
                .param("canViewJobOrders", "true")
                .param("canViewOwnJobOrders", "false"))
        .andExpect(redirectsToLogin());
  }
}
