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

import de.greluc.krt.profit.basetool.frontend.config.SquadronContextAdvice;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import jakarta.servlet.http.Cookie;
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

@SpringBootTest
@ActiveProfiles("test")
class JobOrderPageCookieTest {

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
    // redirect to /orders/create. Stub the capability as a profit-eligible viewer so these cookie
    // tests exercise the list path.
    when(backendApiClient.get(
            "/api/v1/me/capabilities", SquadronContextAdvice.CapabilitiesResponse.class))
        .thenReturn(new SquadronContextAdvice.CapabilitiesResponse(true, true));
  }

  @Test
  @WithMockUser
  void viewOrders_WithoutCookie_ShouldUseDefaultAndSetNoNewCookie() throws Exception {
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
  void viewOrders_WithValidCookie_ShouldUseCookie() throws Exception {
    when(backendApiClient.get(
            eq("/api/v1/orders?page=0&size=100&sort=priority,asc&status=COMPLETED"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 0, 0L, 0, List.of()));

    mockMvc
        .perform(get("/orders").cookie(new Cookie("orders_filter_status", "COMPLETED")))
        .andExpect(status().isOk());

    verify(backendApiClient)
        .get(eq("/api/v1/orders?page=0&size=100&sort=priority,asc&status=COMPLETED"), anyTypeRef());
  }

  @Test
  @WithMockUser
  void viewOrders_WithInvalidOldCookie_ShouldFallbackToDefault() throws Exception {
    when(backendApiClient.get(
            eq("/api/v1/orders?page=0&size=100&sort=priority,asc&status=OPEN,IN_PROGRESS"),
            anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 0, 0L, 0, List.of()));

    mockMvc
        .perform(get("/orders").cookie(new Cookie("orders_filter_status", "OPEN_IN_PROGRESS")))
        .andExpect(status().isOk());

    verify(backendApiClient)
        .get(
            eq("/api/v1/orders?page=0&size=100&sort=priority,asc&status=OPEN,IN_PROGRESS"),
            anyTypeRef());
  }

  @Test
  @WithMockUser
  void viewOrders_WithNewCookieFormat_ShouldUseCookie() throws Exception {
    when(backendApiClient.get(
            eq("/api/v1/orders?page=0&size=100&sort=priority,asc&status=OPEN,IN_PROGRESS"),
            anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 0, 0L, 0, List.of()));

    mockMvc
        .perform(get("/orders").cookie(new Cookie("orders_filter_status", "OPEN-IN_PROGRESS")))
        .andExpect(status().isOk());

    verify(backendApiClient)
        .get(
            eq("/api/v1/orders?page=0&size=100&sort=priority,asc&status=OPEN,IN_PROGRESS"),
            anyTypeRef());
  }

  @Test
  @WithMockUser
  void viewOrders_WithQueryParam_ShouldSetNewCookie() throws Exception {
    when(backendApiClient.get(
            eq("/api/v1/orders?page=0&size=100&sort=priority,asc&status=COMPLETED"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 0, 0L, 0, List.of()));

    mockMvc
        .perform(get("/orders").param("status", "COMPLETED"))
        .andExpect(status().isOk())
        .andExpect(cookie().value("orders_filter_status", "COMPLETED"));

    verify(backendApiClient)
        .get(eq("/api/v1/orders?page=0&size=100&sort=priority,asc&status=COMPLETED"), anyTypeRef());
  }
}
