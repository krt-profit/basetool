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

package de.greluc.krt.profit.basetool.frontend.controller;

import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.websocket.LiveSyncLocalBus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies that a successful order create pokes the staff live-sync queue room server-side
 * (REQ-FE-015, ADR-0094) — the guest-create path has no socket, so {@code JobOrderWriteController}
 * publishes {@code orders / [queue]} through {@link LiveSyncLocalBus} rather than relying on a
 * client broadcast.
 *
 * <p>Uses an ADMIN principal so the {@code canViewJobOrders} capability advice short-circuits to
 * all-true without a backend round-trip; the mocked {@link BackendApiClient} makes the create POST
 * succeed without a real backend.
 */
@SpringBootTest
class JobOrderCreatePublishMvcTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private LiveSyncLocalBus liveSyncLocalBus;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void materialOrderCreateAjax_publishesQueueChange() throws Exception {
    mockMvc
        .perform(
            post("/orders/create")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .param("responsibleOrgUnitId", UUID.randomUUID().toString())
                .param("handle", "Live Sync Create")
                .param("comment", "")
                .param("materials[0].materialId", UUID.randomUUID().toString())
                .param("materials[0].amount", "5"))
        .andExpect(status().isOk());

    verify(liveSyncLocalBus).publish("orders", List.of("queue"));
  }
}
