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

package de.greluc.krt.profit.basetool.frontend.kernel.layout;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderHandoverDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.CapabilityFlagsAdvice.CapabilitiesResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.LayoutContextLoader.MeLayoutResponse;
import de.greluc.krt.profit.basetool.frontend.support.LayoutResponses;
import java.util.Collections;
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
 * The Logistician controls of the inventory, job-order and refinery pages follow the backend's
 * pin-aware capability flag, never the flat role: a caller holding {@code ROLE_LOGISTICIAN} whom
 * the backend reports without a grant in the active context (pinned to a unit without the grant)
 * gets none of them, and the same caller with the grant gets all.
 */
@SpringBootTest
class LogisticianPinAwareSurfacesMvcTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());
  }

  private void backendReports(CapabilitiesResponse capabilities) {
    when(backendApiClient.get(CapabilityGate.CAPABILITIES_PATH, CapabilitiesResponse.class))
        .thenReturn(capabilities);
    MeLayoutResponse layout = LayoutResponses.withCapabilities(capabilities);
    when(backendApiClient.get(LayoutResponses.PATH, MeLayoutResponse.class)).thenReturn(layout);
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void inventory_pinnedWithoutTheGrant_offersNoForeignNoteEditing() throws Exception {
    backendReports(CapabilitiesResponse.NONE);

    mockMvc
        .perform(get("/inventory?storage=my"))
        .andExpect(status().isOk())
        .andExpect(model().attribute("isLogisticianOrAbove", false));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void inventory_withTheGrantInTheActiveContext_offersForeignNoteEditing() throws Exception {
    backendReports(LayoutResponses.logistician());

    mockMvc
        .perform(get("/inventory?storage=my"))
        .andExpect(status().isOk())
        .andExpect(model().attribute("isLogisticianOrAbove", true));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void refineryCreate_pinnedWithoutTheGrant_isNotTheLogisticianForm() throws Exception {
    backendReports(CapabilitiesResponse.NONE);

    mockMvc
        .perform(get("/refinery-orders/create"))
        .andExpect(status().isOk())
        .andExpect(model().attribute("isLogistician", false));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void refineryCreate_withTheGrantInTheActiveContext_isTheLogisticianForm() throws Exception {
    backendReports(LayoutResponses.logistician());

    mockMvc
        .perform(get("/refinery-orders/create"))
        .andExpect(status().isOk())
        .andExpect(model().attribute("isLogistician", true));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void jobOrderItemEditPage_pinnedWithoutTheGrant_isForbidden() throws Exception {
    backendReports(CapabilitiesResponse.NONE);

    mockMvc
        .perform(get("/orders/" + UUID.randomUUID() + "/items/edit"))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void jobOrderWriteProxy_pinnedWithoutTheGrant_isForbidden() throws Exception {
    backendReports(CapabilitiesResponse.NONE);

    mockMvc
        .perform(
            post("/orders/" + UUID.randomUUID() + "/handovers")
                .with(csrf())
                .param("recipientHandle", "Recipient"))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void jobOrderWriteProxy_withTheGrantInTheActiveContext_reachesTheBackend() throws Exception {
    backendReports(LayoutResponses.logistician());
    UUID orderId = UUID.randomUUID();
    when(backendApiClient.post(
            eq("/api/v1/orders/{id}/handovers"), any(), eq(JobOrderHandoverDto.class), eq(orderId)))
        .thenReturn(null);

    mockMvc
        .perform(
            post("/orders/" + orderId + "/handovers")
                .with(csrf())
                .param("handoverTime", "2026-04-29T18:00:00.000Z")
                .param("recipientHandle", "Recipient")
                .param("items[0].inventoryItemId", UUID.randomUUID().toString())
                .param("items[0].amount", "1.5"))
        .andExpect(status().is3xxRedirection());
  }
}
