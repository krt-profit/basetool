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

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.time.Instant;
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
 * MVC tests for the AJAX {@code DELETE /orders/{id}/assignees/{userId}} unenroll path in {@link
 * JobOrderWriteController#removeAssignee}. Since the Bearbeiter section moved to AJAX, the endpoint
 * no longer redirects with a flash toast — it re-renders the {@code orders-detail ::
 * assigneesSection} fragment and the page JS swaps it in place. The tests assert:
 *
 * <ul>
 *   <li>A Logistician removing an assignee gets the re-rendered fragment (HTTP 200).
 *   <li>A plain member may also call the endpoint ({@code isAuthenticated()}); the backend owns the
 *       per-entry authorization.
 *   <li>A backend failure is relayed as the matching HTTP status (here 500), so the page JS can
 *       surface an error toast.
 * </ul>
 */
@SpringBootTest
class JobOrderPageControllerRemoveAssigneeMvcTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /** Minimal order DTO with an empty assignee list, enough to render the section fragment. */
  private JobOrderDto orderWithNoAssignees(UUID orderId) {
    return new JobOrderDto(
        orderId,
        1,
        null,
        null,
        null,
        null,
        null,
        "OPEN",
        "MATERIAL",
        true,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Instant.now(),
        1L);
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void removeAssignee_AsLogistician_reRendersSectionFragment() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();

    when(backendApiClient.delete(
            eq("/api/v1/orders/" + orderId + "/assignees/" + userId), eq(JobOrderDto.class)))
        .thenReturn(orderWithNoAssignees(orderId));

    mockMvc
        .perform(delete("/orders/" + orderId + "/assignees/" + userId).with(csrf()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("assignees-section")));

    verify(backendApiClient)
        .delete(eq("/api/v1/orders/" + orderId + "/assignees/" + userId), eq(JobOrderDto.class));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER"})
  void removeAssignee_AsPlainMember_reRendersSectionFragment() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();

    when(backendApiClient.delete(
            eq("/api/v1/orders/" + orderId + "/assignees/" + userId), eq(JobOrderDto.class)))
        .thenReturn(orderWithNoAssignees(orderId));

    // The frontend endpoint only requires isAuthenticated(); the backend owns the per-entry
    // self-or-logistician rule and would return 403 there if violated.
    mockMvc
        .perform(delete("/orders/" + orderId + "/assignees/" + userId).with(csrf()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("assignees-section")));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void removeAssignee_WhenBackendFails_relaysStatus() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();

    when(backendApiClient.delete(
            eq("/api/v1/orders/" + orderId + "/assignees/" + userId), eq(JobOrderDto.class)))
        .thenThrow(new BackendServiceException("Internal Server Error", null, 500));

    mockMvc
        .perform(delete("/orders/" + orderId + "/assignees/" + userId).with(csrf()))
        .andExpect(status().is5xxServerError());
  }
}
