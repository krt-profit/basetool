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

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.config.CapabilityFlagsAdvice;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies the job-order overview status filter after it moved off the {@code orders_filter_status}
 * server cookie onto client-side localStorage: the selected statuses now arrive only as repeatable
 * {@code status} query parameters (echoed by {@code orders-index.js}), are validated against the
 * known statuses, and default to {@code OPEN}+{@code IN_PROGRESS} when nothing valid is selected —
 * and the controller never sets a status cookie any more.
 */
@SpringBootTest
@ActiveProfiles("test")
class JobOrderPageStatusFilterTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    // The default @WithMockUser is a non-admin, so the orders view's profit gate would otherwise
    // redirect to /orders/create. Stub the capability as a profit-eligible viewer so these tests
    // exercise the list path.
    when(backendApiClient.get(
            "/api/v1/me/capabilities", CapabilityFlagsAdvice.CapabilitiesResponse.class))
        .thenReturn(new CapabilityFlagsAdvice.CapabilitiesResponse(true, true, true));
  }

  @Test
  @WithMockUser
  void viewOrders_withoutStatusParam_usesDefaultAndSetsNoCookie() throws Exception {
    when(backendApiClient.get(
            eq("/api/v1/orders?page=0&size=100&sort=priority,asc&status=OPEN,IN_PROGRESS"),
            anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 0, 0L, 0, List.of()));

    mockMvc
        .perform(get("/orders"))
        .andExpect(status().isOk())
        .andExpect(cookie().doesNotExist("orders_filter_status"));

    verify(backendApiClient)
        .get(
            eq("/api/v1/orders?page=0&size=100&sort=priority,asc&status=OPEN,IN_PROGRESS"),
            anyTypeRef());
  }

  @Test
  @WithMockUser
  void viewOrders_withStatusParam_usesItAndSetsNoCookie() throws Exception {
    when(backendApiClient.get(
            eq("/api/v1/orders?page=0&size=100&sort=priority,asc&status=COMPLETED"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 0, 0L, 0, List.of()));

    mockMvc
        .perform(get("/orders").param("status", "COMPLETED"))
        .andExpect(status().isOk())
        .andExpect(cookie().doesNotExist("orders_filter_status"));

    verify(backendApiClient)
        .get(eq("/api/v1/orders?page=0&size=100&sort=priority,asc&status=COMPLETED"), anyTypeRef());
  }

  @Test
  @WithMockUser
  void viewOrders_withInvalidStatusParam_fallsBackToDefault() throws Exception {
    when(backendApiClient.get(
            eq("/api/v1/orders?page=0&size=100&sort=priority,asc&status=OPEN,IN_PROGRESS"),
            anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 0, 0L, 0, List.of()));

    mockMvc
        .perform(get("/orders").param("status", "BOGUS"))
        .andExpect(status().isOk())
        .andExpect(cookie().doesNotExist("orders_filter_status"));

    verify(backendApiClient)
        .get(
            eq("/api/v1/orders?page=0&size=100&sort=priority,asc&status=OPEN,IN_PROGRESS"),
            anyTypeRef());
  }

  @Test
  @WithMockUser
  void viewOrders_withMultipleValidStatusParams_passesThemThrough() throws Exception {
    when(backendApiClient.get(
            eq("/api/v1/orders?page=0&size=100&sort=priority,asc&status=OPEN,COMPLETED"),
            anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 0, 0L, 0, List.of()));

    mockMvc.perform(get("/orders").param("status", "OPEN", "COMPLETED")).andExpect(status().isOk());

    verify(backendApiClient)
        .get(
            eq("/api/v1/orders?page=0&size=100&sort=priority,asc&status=OPEN,COMPLETED"),
            anyTypeRef());
  }
}
