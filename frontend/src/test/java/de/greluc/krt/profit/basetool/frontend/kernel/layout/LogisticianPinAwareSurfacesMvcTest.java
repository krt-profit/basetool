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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderHandoverDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.LayoutContextLoader.MeLayoutResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.model.CapabilitiesResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.model.SquadronReferenceDto;
import de.greluc.krt.profit.basetool.frontend.support.LayoutResponses;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Verifies that inventory, job-order and refinery surfaces honor the active-context grant. */
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
    UUID orderId = UUID.randomUUID();
    JobOrderDto order =
        new JobOrderDto(
            orderId,
            7,
            new SquadronReferenceDto(UUID.randomUUID(), "Responsible", "RESP"),
            new SquadronReferenceDto(UUID.randomUUID(), "Requester", "REQ"),
            "Handle",
            null,
            1,
            "OPEN",
            "ITEM",
            true,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Instant.now(),
            1L,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            0L,
            0L);
    when(backendApiClient.get(eq("/api/v1/orders/{id}"), eq(JobOrderDto.class), eq(orderId)))
        .thenReturn(order);

    mockMvc.perform(get("/orders/" + orderId + "/items/edit")).andExpect(status().isForbidden());
    verify(backendApiClient).get("/api/v1/orders/{id}", JobOrderDto.class, orderId);
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void jobOrderWriteProxy_pinnedWithoutTheGrant_isForbidden() throws Exception {
    backendReports(CapabilitiesResponse.NONE);
    UUID orderId = UUID.randomUUID();
    String detail = "Current unit has no write authority for this order.";
    when(backendApiClient.post(
            eq("/api/v1/orders/{id}/handovers"), any(), eq(JobOrderHandoverDto.class), eq(orderId)))
        .thenThrow(
            new BackendServiceException(
                "Forbidden", null, 403, "ACCESS_DENIED", null, List.of(), detail));

    mockMvc
        .perform(
            post("/orders/" + orderId + "/handovers")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"handoverTime":"2026-04-29T18:00:00.000Z","recipientHandle":"Recipient",
                     "items":[{"inventoryItemId":"%s","amount":1.5}]}
                    """
                        .formatted(UUID.randomUUID())))
        .andExpect(status().isForbidden())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(403))
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
        .andExpect(jsonPath("$.detail").value(detail));
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
