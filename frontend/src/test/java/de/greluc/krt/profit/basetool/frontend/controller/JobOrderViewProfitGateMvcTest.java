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

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.config.CapabilityFlagsAdvice;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.UUID;
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
 * Verifies the viewer-side profit gate on the order pages: a non-admin caller who is not a member
 * of any profit-eligible org unit ({@code canViewJobOrders=false}) is redirected from the order
 * list and the order detail to the create form — the only order surface open to them — without the
 * backend list/detail ever being queried. The profit-eligible / admin path (rendering the list and
 * detail) is covered by the other order MVC tests, which stub the capability {@code true}.
 */
@SpringBootTest
@ActiveProfiles("test")
class JobOrderViewProfitGateMvcTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    // Non-profit viewer with NO own placed orders (canViewJobOrders=false AND
    // canViewOwnJobOrders=false): the caller may neither browse the queue nor track own orders, so
    // both order pages route to the create form. A non-profit member WITH placed orders
    // (canViewOwnJobOrders=true) instead sees their "Meine Auftraege" list (REQ-ORDERS-023),
    // covered
    // by the backend gate tests + e2e.
    when(backendApiClient.get(
            "/api/v1/me/capabilities", CapabilityFlagsAdvice.CapabilitiesResponse.class))
        .thenReturn(new CapabilityFlagsAdvice.CapabilitiesResponse(false, false, false));
  }

  @Test
  @WithMockUser
  void orderList_nonProfitViewer_redirectsToCreate() throws Exception {
    mockMvc
        .perform(get("/orders"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/orders/create"));
  }

  @Test
  @WithMockUser
  void orderDetail_nonProfitViewer_redirectsToCreate() throws Exception {
    mockMvc
        .perform(get("/orders/" + UUID.randomUUID()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/orders/create"));
  }
}
